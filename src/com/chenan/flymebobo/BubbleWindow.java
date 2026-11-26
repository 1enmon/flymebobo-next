package com.chenan.flymebobo;

import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.drawable.GradientDrawable;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.FrameLayout;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Random;
import java.util.HashMap;
import java.util.Map;
import java.util.Comparator;
import java.util.List;

/**
 * flymebobo 2.0 — 气泡窗口 (BubbleWindow).
 *
 * Owns the overlay window and the free-layout dock. Capsules and the
 * pile are positioned by coordinates in the dock (no vertical
 * LinearLayout), so the pile and a pinned capsule can share a row.
 *
 * Capsule stack rule (user 2026-11-18 "不同胶囊根据时间向下排列"):
 * different capsules are arranged DOWNWARD by time — newest at the
 * top, older ones below, in notification-time order. A new capsule
 * never sinks the ones already out; it takes its time-ordered slot.
 *
 * Fresh code for 2.0 (not copied from 1.x).
 */
public class BubbleWindow {
    private static final BubbleWindow INSTANCE = new BubbleWindow();

    // v176 (method 1): true once the NotifCollection hook installed
    // successfully in SystemUI. While true, the ACTION_NOTIFY
    // broadcast path is a fallback only — XposedEntry drops its
    // deliveries so a notification popped by the hook is not popped
    // again by the broadcast. False (hook missing on this ROM) means
    // the broadcast path is the live one.
    private static volatile boolean notifCollectionHookActive = false;

    public static void setNotifCollectionHookActive(boolean active) {
        notifCollectionHookActive = active;
    }

    /** v194: true when any active or docked group already holds
     * this notification key. Used by XposedEntry to drop a
     * broadcast delivery the hook already popped, so restoring
     * the broadcast fallback (v190) cannot double-pop. The KEY
     * is the same on both paths even when their title/text
     * parsing differs, so a key check is reliable where the
     * content fingerprints are not. */
    public boolean hasNotificationKey(String key) {
        if (key == null) {
            return false;
        }
        for (NotifGroup g : groups) {
            if (g != null && g.hasKey(key)) {
                return true;
            }
        }
        for (NotifGroup g : dockedGroups.values()) {
            if (g != null && g.hasKey(key)) {
                return true;
            }
        }
        return false;
    }

    public static boolean isNotifCollectionHookActive() {
        return notifCollectionHookActive;
    }

    /** One uniform gap: between apps, after a stack, and between
     * expanded items — all the same (user 2026-11-18 "不同应用通知间隔一致"). */
    private static final int CAPSULE_GAP_DP = 6;
    /** Pin badge overhang: the badge stands this far left of the capsule. */
    private static final int PIN_OVERHANG_DP = 28;
    private static final int STACK_EDGE_DP = 6;
    private static final int DOCK_EDGE_DP = 0;

    private Context app;
    private WindowManager wm;
    private FrameLayout root;
    private FrameLayout dock;
    private WindowManager.LayoutParams params;
    private boolean windowAdded;
    // v476: 充电全屏动画——插充电器播一次，从右上角（电池位置）
    // 向对角线拓展至全屏。窗口全屏、FLAG_NOT_TOUCHABLE（不挡
    // 点击，点直接穿透到下面），播完就撤。
    private android.view.View chargingRoot;
    private ChargingAnimView chargingView;
    private WindowManager.LayoutParams chargingParams;
    private boolean chargingWindowAdded;
    private boolean chargingAnimPlaying;
    // v478: 反方向的钟——音乐跑马灯。放音乐时屏幕边缘一圈 LED
    // 灯珠（MusicLedView），窗口全屏、FLAG_NOT_TOUCHABLE，不挡点击。
    // 开关（musicLed）+ 播放状态双控：开关开着且正在放音乐才亮，
    // 音乐一停就灭。
    private MusicLedView musicLedView;
    private WindowManager.LayoutParams musicLedParams;
    private boolean musicLedWindowAdded;
    private boolean musicLedPlaying;
    private boolean dockLeft = true;
    private final Handler handler = new Handler(Looper.getMainLooper());
    // Scene guard (user 2026-11-23): landscape game / video scene —
    // bubbles hidden while it lasts, notifications queued and
    // flushed on exit. See ImmersiveSceneGuard.
    private ImmersiveSceneGuard sceneGuard;
    private boolean sceneSuppressed;
    private final java.util.ArrayDeque<NotifData> suppressedQueue =
            new java.util.ArrayDeque<>();
    // v539: 边缘回看胶囊（wei 2026-11-26 审预览拍板）。普通气泡
    // 被手动左滑消散时，快照进回看栈（头=最近，最多 5 条）；
    // 栈非空时屏幕左边缘出竖向小把手（↩ + 橙色角标 +「回看」
    // 竖字），点它逐个经 onNotification 原样重弹（照样过场景/
    // 静默的门）。把手停 3 分钟无人点 → 清栈淡出。重弹的快照
    // 打 recalled 标记，再划掉不入栈。开关 recallCapsule 默认
    // 关（主动开关）：关着不入栈、不出把手；开→关立即清栈。
    private final java.util.ArrayDeque<NotifData> recallStack =
            new java.util.ArrayDeque<>();
    private static final int RECALL_MAX = 5;
    private static final long RECALL_TTL_MS = 3 * 60_000L;
    private FrameLayout recallRoot;
    private android.widget.TextView recallBadge;
    private WindowManager.LayoutParams recallParams;
    private boolean recallWindowAdded;
    private final Runnable recallExpiryRunnable = new Runnable() {
        @Override
        public void run() {
            if (!recallStack.isEmpty()) {
                FileLog.i("Recall expire n=" + recallStack.size());
            }
            recallStack.clear();
            hideRecallHandle();
        }
    };
    /** v320: Amap bus live navigation state (no voice). Mode A
     * (waiting) shows real-time arrival from BusNaviHook; mode B
     * (on the bus) shows next stop / remaining stations, computed
     * from the route station list + the user's live position.
     * Station coords are GCJ-02 (Amap) while the phone GPS is
     * WGS-84, so the pass radius is generous (300m) and boarding
     * is decided by bus-arrived + moving at bus speed near the
     * route, NOT by matching the bus's reported position. */
    private String busLine;
    private String busStartName;
    private String busEndName;
    private final ArrayList<String> busStationNames = new ArrayList<>();
    // Per-station [lng, lat] for stations that have coords
    // (intermediates from passdepotcoord); null for start/end.
    private final ArrayList<double[]> busStationCoords = new ArrayList<>();
    private boolean busOnBoard;
    private int busPassedIdx = -1;
    private String busLastShownText;
    private double busUserLat;
    private double busUserLng;
    private long busUserLocTime;
    private double busUserSpeed;
    private boolean busLocListening;
    private LocationManager busLocMgr;
    private LocationListener busLocListener;
    /** v322: Amap foot navigation "compute it yourself". The foot
     * JS never pushes notifyNaviInfo and the foot notification
     * never updates, so the route polyline + totals are captured
     * at nav start (ACTION_FOOT_ROUTE) and the capsule is computed
     * from the live position projected onto the polyline:
     * remaining distance/time, arrival time, and the next turn
     * (first significant bearing change ahead). */
    private double[] footPtsLat;
    private double[] footPtsLng;
    private double[] footSegLen;
    private double footTotalLen;
    private int footTotalTime;
    private double footTraveled;
    private String footLastShownText;
    /** v512: last time a live footnavi info arrived via
     * onNaviInfo (Label.setText parse or notifyNaviInfo). While
     * fresh (< 10s) the v322 computed capsule stands down, so the
     * two sources never fight over the "navi:footnavi" key. */
    private volatile long footLabelFreshAt;
    /** v514: navi keys that have received a live instruction
     * this session. The Amap notification re-posts all trip
     * long and lands ~100ms after each live push; without this
     * guard its static placeholder (title = the nav-type label,
     * status 1) overwrites the live instruction every time
     * (user log 2026-11-25 14:41–14:42). Cleared on navi end. */
    private final java.util.Set<String> liveNaviKeys =
            java.util.Collections.newSetFromMap(
                    new java.util.concurrent.ConcurrentHashMap<
                            String, Boolean>());
    /** v516: navi keys the user manually unpinned (collapsed
     * the auto-pinned card) during this navigation session.
     * While a key is in here, popCapsule must not auto-pin the
     * navi card open again — the live updates keep coming for
     * the whole trip and would otherwise re-expand it within a
     * second, fighting the user's explicit collapse. Cleared
     * on navi end (onNaviEnd), so the next trip auto-pins
     * afresh. */
    private final java.util.Set<String> naviUserUnpinned =
            java.util.Collections.newSetFromMap(
                    new java.util.concurrent.ConcurrentHashMap<
                            String, Boolean>());
    private boolean footLocListening;
    private LocationManager footLocMgr;
    private LocationListener footLocListener;
    /** 1.x pop throttle: same app only pops once per 30s, and any
     * notification within 5s of the last pop only refreshes (the
     * notification still joins its group/stack, it just does not
     * replay the pop-in animation). */
    private static final long SAME_APP_POP_WINDOW_MS = 30_000L;
    private static final long REPOP_GUARD_MS = 5_000L;
    private final Map<String, Long> lastPopByPkg = new HashMap<>();
    private long lastPopAt;
    /** v302: Migu songs the user dismissed (right-swipe). Key →
     * title at dismiss time. A lyric update for the same song must
     * NOT re-pop the capsule the user just dismissed. A new song
     * (different title) clears the entry and pops normally. */
    private final Map<String, String> dismissedMiguMusic = new HashMap<>();

    /** Groups currently out, newest first (by latest notification time). */
    private final List<NotifGroup> groups = new ArrayList<>();
    /** Docked groups by package, so tapping a pile icon can expand
     * that app's notifications back out. */
    private final Map<String, NotifGroup> dockedGroups = new HashMap<>();
    private DockedPile pile;
    // The pile (docked fan) lives in its OWN window, separate from the
    // capsule window, so the blank gap between the capsules and the
    // fan is outside both windows and taps fall through to the app
    // below (user 2026-11-19 "贴合图标和胶囊之间的空白无法点击" /
    // "胶囊不在都是正常点击的" / "胶囊出现了之后就不行了"). One
    // rectangular window can never have a hole in it: anything inside
    // its bounds swallows touches even when no view handles them.
    private FrameLayout pileRoot;
    private WindowManager.LayoutParams pileParams;
    private boolean pileWindowAdded;
    /** v376: right-side call window (the "来电放右边" switch).
     * A small transient window at the right edge, a little below
     * the middle of the screen, holding only the call capsule.
     * Created lazily on the first right-side call; hidden (GONE)
     * when there is no right-side call. The capsule is the same
     * 240×60 call capsule as in the dock, just docked right
     * (dockLeft=false) so its swipe mirrors. */
    private FrameLayout callRoot;
    private WindowManager.LayoutParams callParams;
    private boolean callWindowAdded;
    private CapsuleView callCapView;
    private String callCapKey;
    /** Target screen Y for the pile window; set in rebuildDock. */
    private int pileTargetY;
    /** True while the pile window Y is being animated. */
    private boolean pileYAnimating;
    /** v161: hold the dock window tall while survivors slide up.
     * rebuildDock shrinks the window to the new (smaller) height
     * immediately, but the survivors animate from their OLD
     * (lower) positions — the last one (longest stagger) starts
     * after the window already shrank, so its slide is clipped
     * (user 2026-11-19 "最后一个的窗口设置的太快了，会把最后
     * 一个通知向上靠拢的动画截掉"). While true, updateWindowSize
     * never shrinks the height; slideSurvivorsUp releases it
     * once the slide finishes. */
    private boolean holdWindowHeight;
    // Re-entry guard for dissipateAllOneByOne (v93): the tap-outside
    // handler can fire twice for one tap, and without this guard the
    // whole particle sequence runs twice.
    private boolean isDissipatingAll;
    // Card (v143): the capsule grown into a card. The card is the
    // SAME CapsuleView, grown — not a new window. cardKey is the
    // NotifData key whose card is open (null = none); cardHeightPx
    // is the snapped card height (k*50-6dp). rebuildDock() restores
    // the card from these two fields, so a rebuild (new notification,
    // pin change) never loses an open card.
    private String cardKey;
    /** v236: keys of items pinned as CARDS (expanded). A pinned
     * card stays expanded even after another card opens; only
     * unpinning reverts it to a capsule. Pinning a plain capsule
     * does not add its key here. */
    private final java.util.Set<String> pinnedCardKeys =
            new java.util.HashSet<>();
    /** v238: unpinned cards stay in place — key to the pin time
     * they had when unpinned. Phase 1 keeps such an item at
     * that pin-time row as a capsule instead of jumping back
     * to its group position. Only pinned cards get this. */
    private final java.util.Map<String, Long> stayPutPinTime =
            new java.util.HashMap<>();
    /** v237: an open card was just pinned — rebuildDock pops its
     * group's newly-visible capsules in with the cascade
     * animation instead of letting them appear instantly. */
    private boolean popInAfterRebuild = false;
    private NotifGroup popInGroup = null;
    private int cardHeightPx;
    /** v174: whether the module's lyric listener is currently on. */
    private boolean lyricListenOn;
    /** Top (px) of the open card in the dock; -1 when no card. */
    private int cardTopY = -1;
    /** v235: frozen top (px) of the open card, and its key.
     * While the same card stays open, rebuildDock reuses this
     * instead of recomputing the row from the current group
     * order, so a new notification arriving above the card
     * cannot shift it down. */
    private int frozenCardTopY = -1;
    private String frozenCardKey = null;
    /** True while the card grow/shrink animation is running. */
    private boolean cardAnimating;
    // v358: 大卡片模式自动开卡时用直接弹出（淡入+放大），不用从左滑入。
    // 从左滑入看着像先弹胶囊再变卡片（用户 2026-11-22 “还是先弹
    // 的胶囊，再弹得大卡片”）。popCapsule 里调 openCard 前置 true，
    // openCard 读到后用直接弹出并清掉；点胶囊开卡片不受影响。
    private boolean bigCardDirectEntrance;
    /** v151: the card's transparent trailing tail (the gradient strip
     * that stretches from the screen's left edge to the card while
     * it right-swipes away). Null when no close is running. */
    private View cardTail;
    /** v364: which capsule the tail currently belongs to (the manual
     * drag sets it in ensureCardTail; the auto swipe sets it in
     * beginAutoSwipeTail). The staggered auto-dissipate overlaps
     * swipes by ~40ms, so the end only drops the tail if it still
     * belongs to that capsule. */
    private CapsuleView cardTailOwner;
    /** v156: the open card's left edge sits this far in from the
     * screen's left edge (its pop-out from the left lands here).
     * v150–v155 used 20dp; user 2026-11-19 "让他贴合左边屏幕啊" —
     * the card is an enlarged capsule and capsules hug the left
     * edge with no gap, so the card lands flush at 0. */
    private static final int CARD_LEFT_OFFSET_DP = 0;
    // v366: 拖尾的固定布局宽（dp）。拖尾不再用 LayoutParams 改宽，
    // 改用这 100dp 宽 + scaleX 拉伸到卡片左边缘（见 setCardTailWidth）。
    private static final int TAIL_BASE_WIDTH_DP = 100;
    // Incoming / ongoing call (v103). One call at a time; the call
    // capsule is a green NotifData (isCall) in its own group.
    private static final String CALL_PKG = "com.android.incallui";
    private static final String CALL_KEY = "flymebobo-call";
    // v443: 模拟来电（测试用）的 key——跟真来电 CALL_KEY 区分开，
    // 这样收起模拟时只撤模拟那组，不会误撤真来电。
    private static final String SIM_CALL_KEY = "flymebobo-simcall";
    // v459: 模拟音乐的假 key（和模拟来电一个路子）。假音乐胶囊
    // 的 pkg 用 QQ Flyme（com.meizu.media.music，已装，图标能
    // 读到），歌是晴天 - 周杰伦。
    private static final String SIM_MUSIC_KEY = "flymebobo-simmusic";
    private static final String SIM_MUSIC_PKG = "com.meizu.media.music";
    // Call state machine (v117, ported whole from 1.x CallBubble):
    // "idle" / "ringing" / "offhook". The single source of truth,
    // driven by the PhoneStateListener (telephony ground truth)
    // and the call broadcasts. A duplicate RINGING or a stray IDLE
    // can never wipe the answered state, because the state only
    // moves on real telephony transitions.
    // v125 (Flyme Island): SystemUI -> module, fire one of the
    // incallui notification's own actions. extra "command" is one
    // of answer / reject / hangup / speaker_on / speaker_off.
    private static final String ACTION_CALL_CONTROL =
            "com.chenan.flymebobo.CALL_CONTROL";

    // v129 (Flyme Island sendCallAction): the broadcast that
    // fires a call action must carry the sender's identity and
    // target the module package explicitly, and it must carry
    // the notification key so the module fires the action of
    // THIS call, not just any incallui notification. Without
    // setShareIdentityEnabled(true) the module cannot verify
    // the sender, and without the key it cannot pick the right
    // notification — that is why the tap did nothing.
    private void sendCallControl(String command) {
        try {
            android.content.Intent intent =
                    new android.content.Intent(ACTION_CALL_CONTROL);
            intent.setPackage("com.chenan.flymebobo");
            intent.putExtra("command", command);
            intent.putExtra("call_key",
                    callKey != null ? callKey : "");
            intent.putExtra("sent_at",
                    android.os.SystemClock.elapsedRealtime());
            try {
                android.os.Bundle opts =
                        android.app.BroadcastOptions.makeBasic()
                                .setShareIdentityEnabled(true).toBundle();
                app.sendBroadcast(intent, null, opts);
            } catch (Throwable t) {
                app.sendBroadcast(intent);
            }
        } catch (Throwable ignored) {
        }
    }

    private String callState = "idle";
    // v443: 模拟来电胶囊当前是否在屏上（测试用）。只由
    // setSimCall/showSimCall/hideSimCall 改，重启后默认 false，
    // 所以模拟胶囊不会在开机时自己冒出来。
    private boolean simCallShowing = false;
    // v459: 模拟音乐胶囊当前是否在屏上（测试用）。和 simCall 一个
    // 路子，只由 setSimMusic/showSimMusic/hideSimMusic 改。
    private boolean simMusicShowing = false;
    // v459: 扇子自动变淡——扇子出现后 5 秒没点就变半透明。
    // fanDimmed 是当前是否已变淡；dimRunnable 是 5 秒定时器。
    private boolean fanDimmed = false;
    private Runnable fanDimRunnable = null;
    // v444: 锚点拖动（长按胶囊拖整组）进行中。拖动只改锚点
    // dockTopPct（跟滑块同一个值），整组走滑块那条路
    // （onAppearanceChanged / rebuildDock），不直接改窗口，
    // 所以胶囊和扇子永远对得上。拖动中不调 beginDragWindow
    // （v441 的钳制教训）——触摸由按住的胶囊接着（touch
    // target 粘住，手指划出窗也照样收到 MOVE）。
    // v444 修正：拖动中不能调 onAppearanceChanged() / rebuildDock()
    // ——rebuildDock 会把按住的那颗胶囊删了重建，触摸目标一丢，
    // MOVE 就断了，拖一下就断。拖动中只挪两个窗口（主窗 +
    // 扇子窗），不重建任何胶囊：主窗靠 updateWindowSize() 按
    // 新锚点下移；扇子窗靠 pileTargetY = 拖开始时记下的
    // 相对偏移 + 新锚点。松手落盘后，下次 rebuildDock 再正常算。
    private boolean anchorDragActive = false;
    private float anchorDragStartY;
    private int anchorDragStartPct;
    private int anchorDragPileRelY;
    private long callStartTime;
    // Current call's number, for the duplicate-RINGING guard (v112).
    private String callNumber;
    private String callKey;
    // v130: the speaker PendingIntents handed to SystemUI by the
    // module (from the ongoing call notification). Fired directly
    // at tap time — no round-trip broadcast to the module.
    private android.app.PendingIntent callSpeakerOnIntent;
    private android.app.PendingIntent callSpeakerOffIntent;
    /** Speakerphone on for the ongoing call (v108). */
    private boolean callSpeaker;
    private android.os.Handler callTickHandler;
    private Runnable callTickRunnable;
    // Phone-state listener (v111, ported from 1.x CallBubble): 1.x
    // applies the speakerphone when the real OFFHOOK state is
    // observed, not just at answer time. 2.0 set it immediately
    // after acceptRingingCall(), before the call connects, so the
    // audio mode wasn't MODE_IN_CALL yet and the set was ignored.
    private android.telephony.TelephonyManager callTelephonyManager;
    private android.telephony.PhoneStateListener callPhoneListener;
    // Second-line dedup (v100): pkg + title + text, 15s window.
    // Catches duplicates that slip through NotificationService's
    // source dedup (different key, missed fingerprint) before they
    // can pop a second capsule or build a second fan group.
    private final java.util.Map<String, Long> popFingerprints =
            new java.util.HashMap<>();
    // Long-lived key+content backstop (v146): the same message must
    // never pop twice, even if the module's dedup missed it — the
    // listener reconnects (boot, or a restart after long use) and
    // re-delivers active notifications, and the module's in-memory
    // cache was reset. Same key + same content within 1h = duplicate.
    // Same key + new content = an update, allowed. Music/downloads
    // are exempt (their re-posts are legitimate updates).
    private static final long POP_KEY_TTL_MS = 3600_000L;
    private final java.util.Map<String, String> poppedKeys =
            new java.util.HashMap<>();
    private final java.util.Map<String, Long> poppedKeysTime =
            new java.util.HashMap<>();
    // v197: synchronous duplicate-delivery guard. The v194 smart
    // guard (XposedEntry drops the broadcast when the hook already
    // popped the key) has a race: onNotification posts popCapsule
    // to a handler, so the key is not in any group yet when the
    // broadcast arrives a few ms later, and BOTH deliveries pop.
    // This map is written SYNCHRONOUSLY in onNotification (before
    // the handler.post), so whichever path arrives second sees the
    // key and is dropped on the spot, in either order. Only for
    // regular notifications: music/downloads have their own
    // update logic in popCapsule (same key = an update) and must
    // not be dropped here. 2s window: a duplicate delivery
    // arrives within milliseconds; a legitimate re-post is
    // seconds later. Entries older than 60s are pruned.
    private final java.util.Map<String, Long> seenNotifKeys =
            new java.util.HashMap<>();
    private static final long SEEN_KEY_WINDOW_MS = 2000L;
    private static final long SEEN_KEY_TTL_MS = 60_000L;
    private static final long POP_FINGERPRINT_WINDOW_MS = 15000;
    /** Groups currently being docked/dissipated (their animation
     * is running). Manual and automatic paths both check this so they
     * never fight over one group (user 2026-11-19 "手动的和自动的抢
     * 动画了"). */
    private final java.util.Set<NotifGroup> dockingGroups =
            new java.util.HashSet<>();

    /** v363: keys currently being docked one-by-one (dockOneCapsule).
     * dockGroup guards by group; a single big capsule needs a
     * per-key guard so a second heavy swipe during its 300ms
     * animation can't gather a second fan icon for it. */
    private final java.util.Set<String> dockingKeys =
            new java.util.HashSet<>();

    public static BubbleWindow getInstance() {
        return INSTANCE;
    }

    /** v448: 有没有一通已经接听的电话（offhook）。CapsuleView
     * bind() 里用——新造出来的胶囊要是已经接听了，直接缩
     * 成计时器，不用等 setCallTimer 那一秒。 */
    public boolean isCallAnswered() {
        return "offhook".equals(callState);
    }

    // v374: hide the bubble windows while the screen is off so
    // they never show on top of the always-on display (息屏显示).
    // TYPE_SYSTEM_ERROR sits above everything, including the AOD,
    // so the windows are gated on screen on/off instead: visible
    // on the lock screen (screen on) and hidden on the AOD
    // (screen off). Capsules that pop while the screen is off are
    // added to the hidden root and appear when the screen turns on.
    public void setScreenOn(final boolean on) {
        handler.post(new Runnable() {
            @Override
            public void run() {
                try {
                    if (root != null) {
                        root.setVisibility(on ? View.VISIBLE : View.GONE);
                    }
                } catch (Throwable ignored) {
                }
                try {
                    if (pileRoot != null) {
                        pileRoot.setVisibility(
                                on ? View.VISIBLE : View.GONE);
                    }
                } catch (Throwable ignored) {
                }
                // v376: the right-side call window hides on the
                // AOD too, like the main and pile windows.
                try {
                    if (callRoot != null) {
                        callRoot.setVisibility(
                                on ? View.VISIBLE : View.GONE);
                    }
                } catch (Throwable ignored) {
                }
                // v539: the recall handle never shows with the
                // screen off; on wake it re-evaluates (keyguard
                // still locked -> stays hidden until unlock).
                try {
                    if (recallRoot != null) {
                        if (on) {
                            refreshRecallHandle();
                        } else {
                            recallRoot.setVisibility(View.GONE);
                        }
                    }
                } catch (Throwable ignored) {
                }
            }
        });
    }

    /** v524: re-render visible code capsules for the current
     * masking state — called on screen-on / unlock, so a capsule
     * that popped masked gets its full text back when the phone
     * is unlocked (and vice versa). Only code capsules re-render;
     * refreshMasking() on the view no-ops for anything else. */
    public void refreshMasking() {
        handler.post(new Runnable() {
            @Override
            public void run() {
                try {
                    if (dock != null) {
                        for (int i = 0; i < dock.getChildCount(); i++) {
                            android.view.View v = dock.getChildAt(i);
                            if (v instanceof CapsuleView) {
                                ((CapsuleView) v).refreshMasking();
                            }
                        }
                    }
                } catch (Throwable ignored) {
                }
            }
        });
    }

    public void init(Context app) {
        if (windowAdded || this.app != null) {
            return;
        }
        this.app = app.getApplicationContext();
        wm = (WindowManager) this.app.getSystemService(Context.WINDOW_SERVICE);
        root = new FrameLayout(this.app);
        root.setClipChildren(false);
        dock = new FrameLayout(this.app);
        dock.setClipChildren(false);
        int grav = Gravity.TOP | (dockLeft ? Gravity.START : Gravity.END);
        dock.setLayoutParams(new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT, grav));
        root.addView(dock);
        // Tap on empty screen docks everything to the edge (user
        // 2026-11-19 "点击屏幕自动缩小贴边"). FLAG_WATCH_OUTSIDE_TOUCH
        // delivers ACTION_OUTSIDE for taps outside this window; the
        // tap still reaches the app below (we never consume it).
        root.setOnTouchListener(new View.OnTouchListener() {
            @Override
            public boolean onTouch(View v, android.view.MotionEvent event) {
                // v174: a tap on empty window area also closes an open
                // card. ACTION_OUTSIDE only fires outside the window;
                // the window is widened while a card is open (auto
                // swipe), so a tap just right of the card is INSIDE
                // the window and never produced ACTION_OUTSIDE - a
                // pinned card could then only be removed by a manual
                // right swipe. ACTION_DOWN here only fires when no
                // child (capsule/card) consumed the tap.
                if (event.getAction()
                        == android.view.MotionEvent.ACTION_DOWN
                        && cardKey != null) {
                    // v180: a pinned card does not auto-disappear —
                    // tap outside leaves it open; only a manual
                    // right-swipe (or unpin) removes it.
                    // v192 (user "钉住以后…也正常触发点击屏幕空白
                    // 位置"): a pinned card is a big capsule — the
                    // tap still triggers the normal auto-dissipate
                    // for the OTHER notifications below it.
                    if (!isCardPinned()) {
                        // v360: 大卡片模式点空白——卡片自动右滑消散
                        // 成扇子图标，不再缩回胶囊（用户 “别再回到
                        // 胶囊了”）。
                        if (FeatureFlags.bigCard) {
                            dissipateOpenCard();
                        } else {
                            closeCard();
                        }
                    } else if (FeatureFlags.blankDissipate) {
                        dissipateAllOneByOne();
                    }
                    return false;
                }
                if (event.getAction()
                        != android.view.MotionEvent.ACTION_OUTSIDE) {
                    return false;
                }
                // A tap on the fan window is not an empty-screen tap.
                if (pile != null && pile.getIconCount() > 0
                        && pileWindowAdded && pileParams != null) {
                    float rawX = event.getRawX();
                    float rawY = event.getRawY();
                    int px = pileParams.x;
                    int py = pileParams.y;
                    if (rawX >= px && rawX < px + pileParams.width
                            && rawY >= py
                            && rawY < py + pileParams.height) {
                        return false;
                    }
                }
                // A card is open: tap outside closes the card
                // instead of dissipating everything — unless the
                // card is pinned (v180: a pinned card stays open).
                // v192: a pinned card is a big capsule — the tap
                // still dissipates the OTHER notifications below it.
                if (cardKey != null) {
                    if (!isCardPinned()) {
                        // v360: 大卡片模式点空白——卡片自动右滑消散
                        // 成扇子图标，不再缩回胶囊（用户 “别再回到
                        // 胶囊了”）。
                        if (FeatureFlags.bigCard) {
                            dissipateOpenCard();
                        } else {
                            closeCard();
                        }
                    } else if (FeatureFlags.blankDissipate) {
                        dissipateAllOneByOne();
                    }
                    return false;
                }
                // v150: a card open/close animation is running — a
                // tap outside mid-animation must not start a
                // dissipate on top of it.
                if (cardAnimating) {
                    return false;
                }
                // v272: "点击空白自动消散" switch off -> the tap
                // does nothing; capsules stay until swiped by hand.
                if (FeatureFlags.blankDissipate) {
                    dissipateAllOneByOne();
                }
                return false;
            }
        });
        pile = new DockedPile(this.app, dockLeft);
        // Pile window: the fan lives here, not in the dock.
        pileRoot = new FrameLayout(this.app);
        pileRoot.setClipChildren(false);
        pileRoot.addView(pile, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.TOP | (dockLeft ? Gravity.START : Gravity.END)));
        pileParams = new WindowManager.LayoutParams(1, 1,
                WindowManager.LayoutParams.TYPE_SYSTEM_ERROR,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
                        | WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED,
                PixelFormat.TRANSLUCENT);
        pileParams.gravity = grav;
        pileParams.y = dp(DOCK_EDGE_DP);
        pileParams.setTitle("flymebobo-pile");
        params = new WindowManager.LayoutParams(1, 1,
                WindowManager.LayoutParams.TYPE_SYSTEM_ERROR,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
                        | WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH
                        | WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED,
                PixelFormat.TRANSLUCENT);
        params.gravity = grav;
        params.y = dp(DOCK_EDGE_DP);
        params.setTitle("flymebobo");
        try {
            wm.addView(root, params);
            windowAdded = true;
        } catch (Throwable ignored) {
        }
        try {
            wm.addView(pileRoot, pileParams);
            pileWindowAdded = true;
        } catch (Throwable ignored) {
        }
        // v374: initial screen state — if SystemUI starts with the
        // screen off, hide the windows at once so they never show
        // on the AOD before the first SCREEN_OFF broadcast.
        try {
            android.os.PowerManager pm = (android.os.PowerManager)
                    this.app.getSystemService(Context.POWER_SERVICE);
            if (pm != null && !pm.isInteractive()) {
                root.setVisibility(View.GONE);
                pileRoot.setVisibility(View.GONE);
            }
        } catch (Throwable ignored) {
        }
        // Scene guard init (same pattern as the screen-off hide
        // above): entering a landscape game/video scene hides both
        // windows; leaving it restores them (unless the screen is
        // off) and flushes the notifications queued meanwhile.
        try {
            this.sceneGuard = new ImmersiveSceneGuard(this.app, this.handler);
            this.sceneGuard.setListener(new ImmersiveSceneGuard.Listener() {
                @Override
                public void onSceneChanged(boolean immersive) {
                    BubbleWindow.this.sceneSuppressed = immersive;
                    if (immersive) {
                        BubbleWindow.this.root.setVisibility(View.GONE);
                        BubbleWindow.this.pileRoot.setVisibility(View.GONE);
                        // v539: the recall handle hides in-scene
                        // too (refresh re-evaluates on exit).
                        if (BubbleWindow.this.recallRoot != null) {
                            BubbleWindow.this.recallRoot
                                    .setVisibility(View.GONE);
                        }
                        // v507: the music LED is its own window —
                        // the scene hide above never covered it,
                        // so it kept glowing through games.
                        BubbleWindow.this.hideMusicLed();
                        // v531: scene-entry receipt — the same
                        // v2 fullscreen animation the settings
                        // switch plays (guard: sceneMode on,
                        // screen on, unlocked; once per entry).
                        BubbleWindow.this.maybePlaySceneEnterAnim();
                    } else {
                        // v507: restore the LED with the windows
                        // if music is still playing.
                        if (BubbleWindow.this.musicLedPlaying
                                && FeatureFlags.musicLed) {
                            BubbleWindow.this.showMusicLed();
                        }
                        try {
                            android.os.PowerManager pm2 = (android.os.PowerManager)
                                    BubbleWindow.this.app.getSystemService(
                                            Context.POWER_SERVICE);
                            if (pm2 == null || pm2.isInteractive()) {
                                BubbleWindow.this.root.setVisibility(View.VISIBLE);
                                BubbleWindow.this.pileRoot.setVisibility(View.VISIBLE);
                            }
                        } catch (Throwable ignored) {
                        }
                        // v539: scene over — the recall handle may
                        // come back (refresh checks the rest).
                        BubbleWindow.this.refreshRecallHandle();
                        // v532: the flush runs first, synchronously
                        // — the exit animation below is only its
                        // receipt and must never delay it. The
                        // flushed count feeds the exit subtitle.
                        int flushed =
                                BubbleWindow.this.flushSuppressedQueue();
                        BubbleWindow.this.maybePlaySceneExitAnim(flushed);
                    }
                }
            });
            this.sceneGuard.start();
        } catch (Throwable ignored) {
        }
        // v525: a timed silence can survive a SystemUI restart
        // (its state was loaded by XposedEntry before init) —
        // arm the expiry flush for whatever is left of it.
        // v533: seed the exit-receipt session flag the same
        // way, so a silence already in force at (re)start
        // still gets its exit animation when it ends.
        try {
            silenceSessionOn = TempSilence.suppressionOn(app);
            scheduleSilenceFlush();
        } catch (Throwable ignored) {
        }
        // Phone-state listener (v117, ported whole from 1.x
        // CallBubble): the listener is the source of truth for the
        // call state. It posts to the handler and handlePhoneState
        // moves the state machine — RINGING only from IDLE,
        // OFFHOOK runs enterOffhook (applies the speaker, starts
        // the tick), IDLE ends the call UI. Registered once for the
        // module's lifetime.
        try {
            callTelephonyManager = (android.telephony.TelephonyManager)
                    app.getSystemService(
                            android.content.Context.TELEPHONY_SERVICE);
            if (callTelephonyManager != null) {
                callPhoneListener =
                        new android.telephony.PhoneStateListener() {
                    @Override
                    public void onCallStateChanged(final int state,
                            final String number) {
                        try {
                            if (handler != null) {
                                handler.post(new Runnable() {
                                    @Override
                                    public void run() {
                                        handlePhoneState(state, number);
                                    }
                                });
                            }
                        } catch (Throwable ignored) {
                        }
                    }
                };
                callTelephonyManager.listen(callPhoneListener,
                        android.telephony.PhoneStateListener
                                .LISTEN_CALL_STATE);
            }
        } catch (Throwable ignored) {
        }
        // v476: 充电器插入监听——只在插上那一刻播一次全屏动画
        // （ACTION_POWER_CONNECTED）。充电中反复收到电池广播
        // 也不重播，只有“插上”这个动作才触发。
        try {
            android.content.BroadcastReceiver powerReceiver =
                    new android.content.BroadcastReceiver() {
                        @Override
                        public void onReceive(Context c, Intent i) {
                            if (Intent.ACTION_POWER_CONNECTED
                                    .equals(i.getAction())) {
                                showChargingAnim();
                            }
                        }
                    };
            android.content.IntentFilter powerFilter =
                    new android.content.IntentFilter(
                            Intent.ACTION_POWER_CONNECTED);
            app.registerReceiver(powerReceiver, powerFilter);
        } catch (Throwable ignored) {
        }
    }

    /** v476: 插充电器播一次的全屏动画——从右上角（电池）向对角线
     * 拓展至全屏。窗口全屏、FLAG_NOT_TOUCHABLE，不挡点击；播完
     * （约 1 秒）自动撤窗。正在播就不再重播（快速反复插拔只播
     * 一次，等播完再插才播）。 */
    public void showChargingAnim() {
        if (chargingAnimPlaying || app == null || wm == null) {
            return;
        }
        chargingAnimPlaying = true;
        try {
            if (chargingRoot == null) {
                chargingView = new ChargingAnimView(app);
                chargingView.setOnDone(new Runnable() {
                    @Override
                    public void run() {
                        hideChargingAnim();
                    }
                });
                chargingRoot = chargingView;
                chargingParams = new WindowManager.LayoutParams(
                        WindowManager.LayoutParams.MATCH_PARENT,
                        WindowManager.LayoutParams.MATCH_PARENT,
                        WindowManager.LayoutParams.TYPE_SYSTEM_ERROR,
                        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                                | WindowManager.LayoutParams
                                        .FLAG_NOT_TOUCHABLE
                                | WindowManager.LayoutParams
                                        .FLAG_LAYOUT_NO_LIMITS
                                | WindowManager.LayoutParams
                                        .FLAG_SHOW_WHEN_LOCKED,
                        PixelFormat.TRANSLUCENT);
                chargingParams.gravity = android.view.Gravity.TOP
                        | android.view.Gravity.LEFT;
                chargingParams.setTitle("flymebobo-charging");
            }
            if (!chargingWindowAdded) {
                wm.addView(chargingRoot, chargingParams);
                chargingWindowAdded = true;
            }
            chargingRoot.setVisibility(View.VISIBLE);
            chargingView.start();
        } catch (Throwable ignored) {
            chargingAnimPlaying = false;
        }
    }

    private void hideChargingAnim() {
        chargingAnimPlaying = false;
        try {
            if (chargingRoot != null && chargingWindowAdded) {
                wm.removeView(chargingRoot);
                chargingWindowAdded = false;
            }
        } catch (Throwable ignored) {
        }
    }

    /** v478: 反方向的钟——开关翻动。开着：正在放音乐就亮跑马灯；
     * 关着：灯灭（正在跑也灭）。只响应开关翻动。 */
    public void setMusicLed(boolean on) {
        if (!on) {
            hideMusicLed();
            return;
        }
        if (musicLedPlaying && !sceneSuppressed) {
            showMusicLed();
        }
    }

    /** v478: 反方向的钟——放音乐时亮跑马灯（屏幕边缘一圈 LED）。
     * 窗口全屏、FLAG_NOT_TOUCHABLE，不挡点击。 */
    public void showMusicLed() {
        if (app == null || wm == null) {
            return;
        }
        try {
            if (musicLedView == null) {
                musicLedView = new MusicLedView(app);
                // v483: v481 的往上顶（y=-状态栏高、高度=整屏高）撤了——
                // 它把底排灯珠顶没了（高度跟窗口管理器实际给的不
                // 对上，底排被裁掉），状态栏那边也没变。根因还是
                // Flyme 的窗口管理器对 TYPE_SYSTEM_ERROR 的窗口按
                // 状态栏内缩。换 TYPE_APPLICATION_OVERLAY（2038，
                // 比 TYPE_SYSTEM_ERROR 还高，是专门给悬浮窗用的），
                // 宽高回到 MATCH_PARENT、y 回 0，让窗口管理器自己
                // 按全屏铺——顶排压状态栏、底排回到底边，两头都顾上。
                musicLedParams = new WindowManager.LayoutParams(
                        WindowManager.LayoutParams.MATCH_PARENT,
                        WindowManager.LayoutParams.MATCH_PARENT,
                        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                                | WindowManager.LayoutParams
                                        .FLAG_NOT_TOUCHABLE
                                | WindowManager.LayoutParams
                                        .FLAG_LAYOUT_IN_SCREEN
                                | WindowManager.LayoutParams
                                        .FLAG_LAYOUT_NO_LIMITS
                                | WindowManager.LayoutParams
                                        .FLAG_SHOW_WHEN_LOCKED,
                        PixelFormat.TRANSLUCENT);
                musicLedParams.gravity = android.view.Gravity.TOP
                        | android.view.Gravity.LEFT;
                musicLedParams.setTitle("flymebobo-musicled");
            }
            if (!musicLedWindowAdded) {
                wm.addView(musicLedView, musicLedParams);
                musicLedWindowAdded = true;
            }
            musicLedView.setVisibility(View.VISIBLE);
            musicLedView.start();
        } catch (Throwable ignored) {
        }
    }

    /** v478: 反方向的钟——音乐停了灯就灭。 */
    public void hideMusicLed() {
        try {
            if (musicLedView != null) {
                musicLedView.stop();
            }
            if (musicLedView != null && musicLedWindowAdded) {
                wm.removeView(musicLedView);
                musicLedWindowAdded = false;
            }
        } catch (Throwable ignored) {
        }
    }

    // v124 (v122 CallBubble.dropStaleCallState / resetCallState,
    // copied as-is, adapted only for 2.0's String callState):
    // a stale OFFHOOK/RINGING left by a previous call (e.g. a test
    // call) must be cleared when a new RINGING arrives, otherwise
    // the state machine thinks it is already in a call and the
    // speaker/timer never engage. Uses TelephonyManager.getCallState
    // as the ground truth (2 = OFFHOOK, 0 = IDLE).
    private boolean dropStaleCallState() {
        try {
            int phoneState = callTelephonyManager == null ? 0
                    : callTelephonyManager.getCallState();
            if ("offhook".equals(callState) && phoneState != 2) {
                resetCallState();
                return true;
            }
            if ("ringing".equals(callState) && phoneState == 0) {
                resetCallState();
                return true;
            }
            return false;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private void resetCallState() {
        callState = "idle";
        callNumber = null;
        callSpeaker = false;
        callStartTime = 0;
    }

    /** A notification was read — pop its capsule. */
    public void onNotification(final NotifData data) {
        if (data == null || app == null) {
            return;
        }
        // Scene guard: in a landscape game/video scene, queue the
        // notification instead of popping it; flushed on scene exit
        // by flushSuppressedQueue(). This runs BEFORE the seen-key
        // dedup below on purpose — a queued notification must not
        // consume its dedup slot, or the flush would be swallowed.
        // Calls are exempt: a ringing call still shows in-game.
        // v508: scene exceptions break through — an excepted
        // app's notification skips the queue and pops normally.
        // v509 diagnostics: every in-scene decision is logged —
        // v508 shipped this gate silent and a "no effect" report
        // could not be settled from the log.
        if (!data.isCall && this.sceneGuard != null
                && this.sceneGuard.isSuppressed()) {
            FileLog.i("SceneGate pkg=" + data.pkg
                    + " sceneMode=" + FeatureFlags.sceneMode
                    + " excepted=" + SceneExceptions.isExcepted(data.pkg)
                    + " stored=" + SceneExceptions.flatten(
                            SceneExceptions.snapshot()));
        }
        if (!data.isCall && FeatureFlags.sceneMode
                && this.sceneGuard != null
                && this.sceneGuard.isSuppressed()
                && !SceneExceptions.isExcepted(data.pkg)) {
            enqueueSuppressed(data);
            return;
        }
        // v525: temporary silence / DND linkage — while silenced
        // (timed silence running, or system DND on with the
        // linkage switch on), ordinary notifications queue in
        // the same suppressed queue and re-pop in order on
        // restore (maybeFlushSilenceQueue). Same pre-dedup
        // placement as the scene gate, same reason. Music /
        // downloads / live activities are exempt inside
        // TempSilence.shouldQueue; calls follow the call-break
        // switch.
        if (TempSilence.shouldQueue(app, data)) {
            FileLog.i("TempSilence queue pkg=" + data.pkg
                    + " call=" + data.isCall
                    + " timed=" + TempSilence.isActive()
                    + " linkDnd=" + TempSilence.linkDnd);
            enqueueSuppressed(data);
            return;
        }
        // v197: synchronous duplicate-delivery guard (see
        // seenNotifKeys). Both the hook and the broadcast funnel
        // here; the second delivery of the same key within 2s is
        // the same notification arriving twice, not an update.
        // This runs BEFORE the handler.post, so there is no race
        // with the async popCapsule. Music/downloads are exempt
        // (same key = a legitimate update in popCapsule). Live
        // activities are exempt too (v222: the timer re-posts the
        // same key every second; double delivery is still stopped
        // by the v194 hook-popped guard in XposedEntry).
        // v307: WeChat/QQ exempt from the 2s seen guard too (user
        // 2026-11-21). Same person sends two messages: both show in
        // the shade, but the second was counted as a duplicate and
        // dropped — only the first capsule ever popped. WeChat reuses
        // the same notification key per conversation, so the same-key
        // guard swallows the second message. The v146 key+content
        // backstop still catches true duplicates.
        if (!data.isMusic && !data.isDownload && !data.isLive
                && data.key != null
                && !"com.tencent.mm".equals(data.pkg)
                && !"com.tencent.mobileqq".equals(data.pkg)) {
            long now = System.currentTimeMillis();
            synchronized (seenNotifKeys) {
                Long at = seenNotifKeys.get(data.key);
                if (at != null && now - at < SEEN_KEY_WINDOW_MS) {
                    return;
                }
                seenNotifKeys.put(data.key, now);
                if (seenNotifKeys.size() > 256) {
                    java.util.Iterator<java.util.Map.Entry<String, Long>> it =
                            seenNotifKeys.entrySet().iterator();
                    while (it.hasNext()) {
                        if (now - it.next().getValue() > SEEN_KEY_TTL_MS) {
                            it.remove();
                        }
                    }
                }
            }
        }
        handler.post(new Runnable() {
            @Override
            public void run() {
                popCapsule(data);
            }
        });
    }

    /** Queue a notification for later re-delivery (scene guard
     * and temporary silence share the queue). Same-key entries
     * are replaced so an update does not pop twice on flush;
     * the queue is FIFO and capped at 20. */
    private void enqueueSuppressed(NotifData data) {
        synchronized (this.suppressedQueue) {
            java.util.Iterator<NotifData> qit =
                    this.suppressedQueue.iterator();
            while (qit.hasNext()) {
                NotifData old = qit.next();
                if (old.key != null && old.key.equals(data.key)) {
                    qit.remove();
                }
            }
            this.suppressedQueue.add(data);
            while (this.suppressedQueue.size() > 20) {
                this.suppressedQueue.poll();
            }
        }
    }

    // ---- v539: 边缘回看胶囊 ----
    // Manual left-swipe dissipate is the ONLY way in (the three
    // capsule onSwipeLeft runnables call manualDissipate instead
    // of dissipateOne directly; auto-dissipate and blank-tap
    // dissipation call dissipateOne themselves and never land
    // here). Ordinary capsules only: music / download / live /
    // navi / bus / call never stack.

    /** Manual swipe-away: leave a snapshot in the recall stack,
     * then dissipate as usual. */
    private void manualDissipate(NotifData d) {
        pushRecall(d);
        dissipateOne(d);
    }

    private void pushRecall(NotifData d) {
        if (d == null || !FeatureFlags.recallCapsule || d.recalled) {
            return;
        }
        if (d.isMusic || d.isDownload || d.isLive || d.isNavi
                || d.isBus || d.isCall) {
            return;
        }
        // Same key replaces: a re-posted conversation swiped
        // twice must not stack a stale copy under the fresh one.
        if (d.key != null) {
            java.util.Iterator<NotifData> it = recallStack.iterator();
            while (it.hasNext()) {
                NotifData o = it.next();
                if (o != null && d.key.equals(o.key)) {
                    it.remove();
                }
            }
        }
        recallStack.addFirst(d);
        while (recallStack.size() > RECALL_MAX) {
            recallStack.removeLast();
        }
        FileLog.i("Recall push pkg=" + d.pkg
                + " n=" + recallStack.size());
        armRecallTimer();
        refreshRecallHandle();
    }

    private void armRecallTimer() {
        handler.removeCallbacks(recallExpiryRunnable);
        if (!recallStack.isEmpty()) {
            handler.postDelayed(recallExpiryRunnable, RECALL_TTL_MS);
        }
    }

    /** The switch flipped off: clear the stack and take the
     * handle down at once (called from XposedEntry). */
    public void onRecallCapsuleDisabled() {
        handler.post(new Runnable() {
            @Override
            public void run() {
                recallStack.clear();
                handler.removeCallbacks(recallExpiryRunnable);
                hideRecallHandle();
            }
        });
    }

    /** Tap the edge handle: re-deliver the most recently swiped
     * capsule through onNotification, so the scene / silence
     * gates apply exactly as for a fresh arrival (it queues
     * instead of popping when a gate is up — never bypasses).
     * The snapshot is marked recalled first: swiping the
     * re-popped capsule away again does not re-stack it. */
    private void onRecallTap() {
        NotifData d = recallStack.pollFirst();
        if (d == null) {
            refreshRecallHandle();
            return;
        }
        FileLog.i("Recall pop pkg=" + d.pkg
                + " left=" + recallStack.size());
        d.recalled = true;
        // A fast recall (< 2s after the original delivery) would
        // be swallowed by the seen-key duplicate guard; the user
        // explicitly asked for this one back, so clear its slot.
        if (d.key != null) {
            synchronized (seenNotifKeys) {
                seenNotifKeys.remove(d.key);
            }
        }
        armRecallTimer();
        refreshRecallHandle();
        onNotification(d);
    }

    private boolean isScreenInteractive() {
        try {
            android.os.PowerManager pm = (android.os.PowerManager)
                    app.getSystemService(Context.POWER_SERVICE);
            return pm == null || pm.isInteractive();
        } catch (Throwable ignored) {
            return true;
        }
    }

    private boolean isKeyguardLocked() {
        try {
            android.app.KeyguardManager km =
                    (android.app.KeyguardManager) app.getSystemService(
                            Context.KEYGUARD_SERVICE);
            return km != null && km.isKeyguardLocked();
        } catch (Throwable ignored) {
            return false;
        }
    }

    /** Show / update / hide the edge handle from the current
     * state. The window exists only while the stack is
     * non-empty; visibility additionally requires the feature
     * on, screen interactive, keyguard unlocked and no
     * immersive scene (the scene listener and setScreenOn call
     * this as those states change; unlock comes via
     * XposedEntry's USER_PRESENT branch). */
    public void refreshRecallHandle() {
        if (!FeatureFlags.recallCapsule || recallStack.isEmpty()) {
            hideRecallHandle();
            return;
        }
        ensureRecallHandle();
        if (recallRoot == null || wm == null) {
            return;
        }
        if (!recallWindowAdded) {
            try {
                recallRoot.setTranslationX(-dp(26));
                wm.addView(recallRoot, recallParams);
                recallWindowAdded = true;
                recallRoot.animate().translationX(0)
                        .setDuration(220).start();
            } catch (Throwable ignored) {
                return;
            }
        }
        if (recallBadge != null) {
            recallBadge.setText(String.valueOf(recallStack.size()));
        }
        boolean show = isScreenInteractive() && !isKeyguardLocked()
                && !sceneSuppressed;
        recallRoot.setVisibility(show ? View.VISIBLE : View.GONE);
    }

    private void hideRecallHandle() {
        if (recallRoot != null && recallWindowAdded && wm != null) {
            try {
                wm.removeView(recallRoot);
            } catch (Throwable ignored) {
            }
        }
        recallWindowAdded = false;
    }

    private void ensureRecallHandle() {
        if (recallRoot != null || app == null || wm == null) {
            return;
        }
        try {
            FrameLayout rootL = new FrameLayout(app);
            android.widget.LinearLayout tab =
                    new android.widget.LinearLayout(app);
            tab.setOrientation(android.widget.LinearLayout.VERTICAL);
            tab.setGravity(Gravity.CENTER_HORIZONTAL);
            tab.setPadding(0, dp(7), 0, dp(7));
            GradientDrawable bg = new GradientDrawable();
            bg.setColor(Color.argb(222, 28, 32, 44));
            float r = dp(14);
            bg.setCornerRadii(new float[]{0, 0, r, r, r, r, 0, 0});
            tab.setBackground(bg);
            android.widget.TextView arrow =
                    new android.widget.TextView(app);
            arrow.setText("↩");
            arrow.setTextColor(Color.WHITE);
            arrow.setTextSize(13);
            arrow.setGravity(Gravity.CENTER);
            tab.addView(arrow);
            recallBadge = new android.widget.TextView(app);
            recallBadge.setTextColor(Color.WHITE);
            recallBadge.setTextSize(11);
            recallBadge.setTypeface(recallBadge.getTypeface(),
                    android.graphics.Typeface.BOLD);
            recallBadge.setGravity(Gravity.CENTER);
            GradientDrawable bbg = new GradientDrawable();
            bbg.setShape(GradientDrawable.OVAL);
            bbg.setColor(Color.rgb(255, 120, 71));
            recallBadge.setBackground(bbg);
            android.widget.LinearLayout.LayoutParams blp =
                    new android.widget.LinearLayout.LayoutParams(
                            dp(17), dp(17));
            blp.topMargin = dp(3);
            recallBadge.setLayoutParams(blp);
            tab.addView(recallBadge);
            android.widget.TextView label =
                    new android.widget.TextView(app);
            label.setText("回\n看");
            label.setTextColor(Color.argb(190, 255, 255, 255));
            label.setTextSize(9.5f);
            label.setGravity(Gravity.CENTER);
            android.widget.LinearLayout.LayoutParams llp =
                    new android.widget.LinearLayout.LayoutParams(
                            android.widget.LinearLayout.LayoutParams
                                    .MATCH_PARENT,
                            android.widget.LinearLayout.LayoutParams
                                    .WRAP_CONTENT);
            llp.topMargin = dp(3);
            label.setLayoutParams(llp);
            tab.addView(label);
            rootL.addView(tab, new FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT));
            rootL.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    onRecallTap();
                }
            });
            recallRoot = rootL;
            recallParams = new WindowManager.LayoutParams(
                    dp(26), dp(76),
                    WindowManager.LayoutParams.TYPE_SYSTEM_ERROR,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                            | WindowManager.LayoutParams
                                    .FLAG_LAYOUT_NO_LIMITS,
                    PixelFormat.TRANSLUCENT);
            recallParams.gravity = Gravity.START | Gravity.TOP;
            // Below the capsule zone, like the approved preview
            // (the tab sat under the capsule, ~45% down).
            android.util.DisplayMetrics dm =
                    app.getResources().getDisplayMetrics();
            recallParams.y = (int) (dm.heightPixels * 0.42f);
            recallParams.setTitle("flymebobo-recall");
        } catch (Throwable ignored) {
        }
    }

    // ---- v525: temporary silence flush plumbing ----
    // The silence state carries its own end timestamp, so the
    // flush does not depend on the APP process: a delayed
    // runnable fires at expiry, and DND-off / state-change
    // broadcasts call maybeFlushSilenceQueue too. Flushing goes
    // back through onNotification, so if another suppression
    // (scene, a fresh silence) is up by then, items simply
    // re-queue — never pop early.

    private final Runnable tsExpiryRunnable = new Runnable() {
        @Override
        public void run() {
            maybeFlushSilenceQueue("expiry", null);
        }
    };

    /** The APP pushed a new silence state (start / end /
     * settings change): re-arm the expiry flush, and flush at
     * once if nothing suppresses anymore (early cancel). */
    public void onTempSilenceStateChanged() {
        onTempSilenceStateChanged(null);
    }

    /** v533: endReason is the APP-side end reason ("cancel" /
     * "expired" / "dndOff") on an end broadcast, null for
     * starts and settings changes. It routes the silence-exit
     * animation: an in-app "cancel" is receipted by the APP
     * itself (MainActivity plays it with the flushed count
     * this process broadcasts back); every other road is
     * receipted here in SystemUI. */
    public void onTempSilenceStateChanged(
            final String endReason) {
        handler.post(new Runnable() {
            @Override
            public void run() {
                scheduleSilenceFlush();
                maybeFlushSilenceQueue("state", endReason);
            }
        });
    }

    /** v531: fullscreen-anim window params, truly edge-to-edge.
     * v529's recipe (TYPE_APPLICATION_OVERLAY + LAYOUT_IN_
     * SCREEN + LAYOUT_NO_LIMITS) still left a bright strip of
     * wallpaper at the very top and bottom on Flyme (wei's
     * screenshot 4366): the window was framed inside the
     * display cutout / system-bar insets. The missing pieces
     * are layoutInDisplayCutoutMode (SHORT_EDGES pre-30,
     * ALWAYS on 30+) and, on API 30+, fitInsetsTypes=0 so no
     * inset type eats into the frame. MATCH_PARENT both ways.
     * v533 (wei): FLAG_NOT_TOUCHABLE — the animation is not
     * tappable and must play to completion; touches fall
     * through to whatever the user is operating underneath. */
    private static WindowManager.LayoutParams
            fullscreenAnimParams(String title) {
        WindowManager.LayoutParams lp =
                new WindowManager.LayoutParams(
                        WindowManager.LayoutParams.MATCH_PARENT,
                        WindowManager.LayoutParams.MATCH_PARENT,
                        WindowManager.LayoutParams
                                .TYPE_APPLICATION_OVERLAY,
                        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                                | WindowManager.LayoutParams
                                        .FLAG_NOT_TOUCHABLE
                                | WindowManager.LayoutParams
                                        .FLAG_LAYOUT_IN_SCREEN
                                | WindowManager.LayoutParams
                                        .FLAG_LAYOUT_NO_LIMITS,
                        PixelFormat.TRANSLUCENT);
        lp.gravity = android.view.Gravity.TOP
                | android.view.Gravity.LEFT;
        if (android.os.Build.VERSION.SDK_INT >= 30) {
            lp.layoutInDisplayCutoutMode = WindowManager
                    .LayoutParams
                    .LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS;
            try {
                lp.setFitInsetsTypes(0);
            } catch (Throwable ignored) {
            }
        } else if (android.os.Build.VERSION.SDK_INT >= 28) {
            lp.layoutInDisplayCutoutMode = WindowManager
                    .LayoutParams
                    .LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
        }
        lp.setTitle(title);
        return lp;
    }

    /** v529: the DND-linkage start animation veil, while up.
     * v533: the silence-exit animation shares this slot, so a
     * start and an exit receipt never stack. */
    private View silenceAnimVeil;

    /** v533: a silence session (timed, or DND-linkage) is
     * currently suppressing, as last evaluated by
     * maybeFlushSilenceQueue / init. The falling edge is what
     * fires the exit-animation receipt — exactly once per
     * session, whichever road ends it. */
    private boolean silenceSessionOn;

    /** v531: the scene-enter animation veil, while up. */
    private View sceneAnimVeil;

    /** v529: the silence was pulled up by the DND linkage
     * (user opened system Do Not Disturb elsewhere) — play the
     * same start animation the in-app button plays, as the
     * "it really turned on" receipt, on a fullscreen overlay
     * window here in SystemUI. Skipped (never queued for
     * later) while the screen is off or the keyguard is up:
     * a receipt nobody can see is just noise. The window type
     * follows the v483 music-LED recipe — TYPE_APPLICATION_
     * OVERLAY + LAYOUT_IN_SCREEN is what Flyme's window
     * manager actually铺 full-screen. v533: the window is
     * NOT_TOUCHABLE — no tap-to-dismiss, it plays to
     * completion while touches pass through. */
    public void maybePlayDndSilenceAnim() {
        handler.post(new Runnable() {
            @Override
            public void run() {
                if (app == null || wm == null) {
                    return;
                }
                try {
                    android.os.PowerManager pm =
                            (android.os.PowerManager) app
                                    .getSystemService(
                                            Context.POWER_SERVICE);
                    if (pm == null || !pm.isInteractive()) {
                        FileLog.i("TempSilence anim skip"
                                + " (screen off)");
                        return;
                    }
                    if (PrivacyMask.isLocked(app)) {
                        FileLog.i("TempSilence anim skip"
                                + " (locked)");
                        return;
                    }
                    if (silenceAnimVeil != null) {
                        return;
                    }
                    final SilenceAnim.Handle[] hh =
                            new SilenceAnim.Handle[1];
                    SilenceAnim.Handle h = SilenceAnim.create(
                            app, SilenceAnim.subtitleDnd(),
                            new Runnable() {
                                @Override
                                public void run() {
                                    if (hh[0] != null
                                            && silenceAnimVeil
                                                    == hh[0].veil) {
                                        silenceAnimVeil = null;
                                        try {
                                            wm.removeView(
                                                    hh[0].veil);
                                        } catch (Throwable
                                                ignored) {
                                        }
                                    }
                                }
                            });
                    hh[0] = h;
                    WindowManager.LayoutParams lp =
                            fullscreenAnimParams(
                                    "flymebobo-silence-anim");
                    silenceAnimVeil = h.veil;
                    wm.addView(h.veil, lp);
                    h.play();
                    FileLog.i("TempSilence anim play (dnd link)");
                } catch (Throwable t) {
                    silenceAnimVeil = null;
                }
            }
        });
    }

    /** v531: entering a game/video scene — play the SAME v2
     * fullscreen animation the settings switch plays (wei:
     * both scene triggers must look identical), here in
     * SystemUI on the edge-to-edge overlay window. Called
     * from the scene guard listener, which only fires on
     * transitions — entering plays once per scene session,
     * leaving resets, re-entering plays again. Skipped when
     * the scene-mode master switch is off (the gate is off
     * anyway), and while the screen is off or the keyguard
     * is up (never queued for later — same rule as the DND
     * silence receipt). */
    public void maybePlaySceneEnterAnim() {
        handler.post(new Runnable() {
            @Override
            public void run() {
                if (app == null || wm == null) {
                    return;
                }
                if (!FeatureFlags.sceneMode) {
                    return;
                }
                try {
                    android.os.PowerManager pm =
                            (android.os.PowerManager) app
                                    .getSystemService(
                                            Context.POWER_SERVICE);
                    if (pm == null || !pm.isInteractive()) {
                        FileLog.i("SceneAnim skip (screen off)");
                        return;
                    }
                    if (PrivacyMask.isLocked(app)) {
                        FileLog.i("SceneAnim skip (locked)");
                        return;
                    }
                    if (sceneAnimVeil != null) {
                        return;
                    }
                    // v541: 场景动画 30 分钟合并冷却（开/退
                    // 共用），冷却中只跳过动画，场景功能本身
                    // 不受影响；既有规则跳过的不走到这里、不
                    // 消耗机会。
                    long cdEnter = SceneAnimCooldown.remainingMs();
                    if (cdEnter > 0) {
                        FileLog.i("SceneAnim cooldown skip"
                                + " (enter), "
                                + (cdEnter / 60000) + " min left");
                        return;
                    }
                    final SilenceAnim.Handle[] hh =
                            new SilenceAnim.Handle[1];
                    SilenceAnim.Handle h = SilenceAnim.create(
                            app, SilenceAnim.SCENE,
                            SilenceAnim.subtitleScene(),
                            new Runnable() {
                                @Override
                                public void run() {
                                    if (hh[0] != null
                                            && sceneAnimVeil
                                                    == hh[0].veil) {
                                        sceneAnimVeil = null;
                                        try {
                                            wm.removeView(
                                                    hh[0].veil);
                                        } catch (Throwable
                                                ignored) {
                                        }
                                    }
                                }
                            });
                    hh[0] = h;
                    sceneAnimVeil = h.veil;
                    wm.addView(h.veil, fullscreenAnimParams(
                            "flymebobo-scene-anim"));
                    // v541: 确实要播了才记冷却时间。
                    SceneAnimCooldown.markPlayed();
                    h.play();
                    FileLog.i("SceneAnim play (enter scene)");
                } catch (Throwable t) {
                    sceneAnimVeil = null;
                }
            }
        });
    }

    /** v532: leaving a game/video scene — the EXIT receipt,
     * the same v2 fullscreen animation as the entry (wei:
     * exit gets the fullscreen treatment too, only the copy
     * changes). Title 「场景模式已退出」; the subtitle reports
     * the exit flush that just ran in the scene listener —
     * "N 条消息已补弹" with the real flushed count, or
     * "通知恢复正常" when nothing was queued. Called from
     * the scene guard listener right AFTER flushSuppressedQueue
     * ran synchronously, so the animation never delays the
     * re-delivery it receipts. The guard only fires on
     * transitions, so one exit plays once. Same guards as
     * the entry receipt: sceneMode off, screen off or
     * keyguard up → skip, never queued (logged). */
    public void maybePlaySceneExitAnim(final int flushed) {
        handler.post(new Runnable() {
            @Override
            public void run() {
                if (app == null || wm == null) {
                    return;
                }
                if (!FeatureFlags.sceneMode) {
                    return;
                }
                try {
                    android.os.PowerManager pm =
                            (android.os.PowerManager) app
                                    .getSystemService(
                                            Context.POWER_SERVICE);
                    if (pm == null || !pm.isInteractive()) {
                        FileLog.i("SceneAnim exit skip"
                                + " (screen off)");
                        return;
                    }
                    if (PrivacyMask.isLocked(app)) {
                        FileLog.i("SceneAnim exit skip (locked)");
                        return;
                    }
                    if (sceneAnimVeil != null) {
                        return;
                    }
                    // v541: 与开启共用同一个 30 分钟冷却。
                    long cdExit = SceneAnimCooldown.remainingMs();
                    if (cdExit > 0) {
                        FileLog.i("SceneAnim cooldown skip"
                                + " (exit), "
                                + (cdExit / 60000) + " min left");
                        return;
                    }
                    final SilenceAnim.Handle[] hh =
                            new SilenceAnim.Handle[1];
                    SilenceAnim.Handle h = SilenceAnim.create(
                            app, SilenceAnim.SCENE_EXIT,
                            flushed > 0
                                    ? SilenceAnim
                                            .subtitleSceneExitFlushed(
                                                    flushed)
                                    : SilenceAnim
                                            .subtitleSceneExitEmpty(),
                            new Runnable() {
                                @Override
                                public void run() {
                                    if (hh[0] != null
                                            && sceneAnimVeil
                                                    == hh[0].veil) {
                                        sceneAnimVeil = null;
                                        try {
                                            wm.removeView(
                                                    hh[0].veil);
                                        } catch (Throwable
                                                ignored) {
                                        }
                                    }
                                }
                            });
                    hh[0] = h;
                    sceneAnimVeil = h.veil;
                    wm.addView(h.veil, fullscreenAnimParams(
                            "flymebobo-scene-anim"));
                    // v541: 确实要播了才记冷却时间。
                    SceneAnimCooldown.markPlayed();
                    h.play();
                    FileLog.i("SceneAnim play (exit scene)"
                            + " flushed=" + flushed);
                } catch (Throwable t) {
                    sceneAnimVeil = null;
                }
            }
        });
    }

    /** The system interruption filter changed (any source):
     * if DND just went off and no timed silence is running,
     * whatever it queued can pop now. */
    public void onDndChanged() {
        handler.post(new Runnable() {
            @Override
            public void run() {
                maybeFlushSilenceQueue("dnd", null);
            }
        });
    }

    private void scheduleSilenceFlush() {
        handler.removeCallbacks(tsExpiryRunnable);
        long delay = TempSilence.untilMs
                - System.currentTimeMillis();
        if (delay > 0) {
            handler.postDelayed(tsExpiryRunnable, delay + 500);
        }
    }

    /** v533: the flush evaluation, now also the silence
     * session tracker. While suppression holds it only marks
     * the session on; on the falling edge it flushes first
     * (the re-delivery is never delayed by the animation),
     * then receipts the end:
     *  - the flushed count is broadcast back to the APP on
     *    every session end, so an in-app cancel can print
     *    the real count in its own exit animation;
     *  - unless the end was that in-app cancel (endReason
     *    "cancel" — the APP plays the receipt), SystemUI
     *    plays the exit animation itself: expiry (APP alarm
     *    or this process's own runnable), a manual DND-off
     *    early cancel, and a pure DND-linkage session ending
     *    all land here. */
    private void maybeFlushSilenceQueue(String why,
            String endReason) {
        if (app == null) {
            return;
        }
        if (TempSilence.suppressionOn(app)) {
            silenceSessionOn = true;
            return;
        }
        boolean wasSession = silenceSessionOn;
        silenceSessionOn = false;
        int flushed = 0;
        boolean has;
        synchronized (this.suppressedQueue) {
            has = !this.suppressedQueue.isEmpty();
        }
        if (has) {
            FileLog.i("TempSilence flush why=" + why);
            flushed = flushSuppressedQueue();
        }
        if (!wasSession) {
            return;
        }
        broadcastSilenceFlushed(flushed);
        if (!"cancel".equals(endReason)) {
            maybePlaySilenceExitAnim(flushed);
        }
    }

    /** v533: tell the APP an end-of-silence flush ran and
     * how much it re-delivered (0 included — the APP needs
     * the "nothing was queued" answer too, to print
     * 「通知恢复正常」 without guessing). */
    private void broadcastSilenceFlushed(int n) {
        try {
            Intent i = new Intent(NotificationService
                    .ACTION_TEMP_SILENCE_FLUSHED);
            i.setPackage("com.chenan.flymebobo");
            i.putExtra(TempSilence.EXTRA_FLUSHED, n);
            app.sendBroadcast(i);
            FileLog.i("TempSilence flushed receipt n=" + n);
        } catch (Throwable ignored) {
        }
    }

    /** v533: the silence EXIT receipt — the same v2
     * fullscreen animation as the start (wei: 跟场景退出
     * 同款), moon illustration, title 「临时静默已结束」;
     * the subtitle reports the flush that just ran —
     * "N 条消息已补弹" with the real count, or
     * "通知恢复正常" when nothing was queued. Played by
     * SystemUI for every end road except the in-app cancel
     * (the APP receipts that one itself — see
     * maybeFlushSilenceQueue). Same guards as the start
     * receipt: screen off or keyguard up → skip, never
     * queued for later. Shares the silenceAnimVeil slot
     * with the start animation so the two never stack. */
    public void maybePlaySilenceExitAnim(final int flushed) {
        handler.post(new Runnable() {
            @Override
            public void run() {
                if (app == null || wm == null) {
                    return;
                }
                try {
                    android.os.PowerManager pm =
                            (android.os.PowerManager) app
                                    .getSystemService(
                                            Context.POWER_SERVICE);
                    if (pm == null || !pm.isInteractive()) {
                        FileLog.i("TempSilence exit anim skip"
                                + " (screen off)");
                        return;
                    }
                    if (PrivacyMask.isLocked(app)) {
                        FileLog.i("TempSilence exit anim skip"
                                + " (locked)");
                        return;
                    }
                    if (silenceAnimVeil != null) {
                        return;
                    }
                    final SilenceAnim.Handle[] hh =
                            new SilenceAnim.Handle[1];
                    SilenceAnim.Handle h = SilenceAnim.create(
                            app, SilenceAnim.SILENCE_EXIT,
                            flushed > 0
                                    ? SilenceAnim
                                            .subtitleSilenceExitFlushed(
                                                    flushed)
                                    : SilenceAnim
                                            .subtitleSilenceExitEmpty(),
                            new Runnable() {
                                @Override
                                public void run() {
                                    if (hh[0] != null
                                            && silenceAnimVeil
                                                    == hh[0].veil) {
                                        silenceAnimVeil = null;
                                        try {
                                            wm.removeView(
                                                    hh[0].veil);
                                        } catch (Throwable
                                                ignored) {
                                        }
                                    }
                                }
                            });
                    hh[0] = h;
                    silenceAnimVeil = h.veil;
                    wm.addView(h.veil, fullscreenAnimParams(
                            "flymebobo-silence-anim"));
                    h.play();
                    FileLog.i("TempSilence exit anim play"
                            + " flushed=" + flushed);
                } catch (Throwable t) {
                    silenceAnimVeil = null;
                }
            }
        });
    }

    /** Scene guard: re-deliver everything queued during a
     * game/video scene. Goes back through onNotification so the
     * normal dedup / grouping / pop logic applies unchanged.
     * v532: returns how many were flushed — the scene-exit
     * animation prints the real count in its subtitle. */
    private int flushSuppressedQueue() {
        java.util.ArrayList<NotifData> copy;
        synchronized (this.suppressedQueue) {
            copy = new java.util.ArrayList<>(this.suppressedQueue);
            this.suppressedQueue.clear();
        }
        if (!copy.isEmpty()) {
            FileLog.i("SceneFlush n=" + copy.size());
        }
        for (NotifData d : copy) {
            onNotification(d);
        }
        return copy.size();
    }

    // v176 (method 1): the system removed a notification (user
    // swiped it away in the shade, the app cancelled it). Drop its
    // capsule from the bubble groups so the bubble stays in sync
    // with the shade. Runs on the main handler like onNotification.
    // v311: Amap live navigation (foot/ride) — turn + distance
    // from the Amap hook (ModuleRoute.notifyNaviInfo). One capsule
    // per type (key "navi:footnavi" / "navi:ridenavi"), isLive so a
    // re-post updates in place (same as the v222 live pattern) and
    // the dedup guards exempt it. Auto-pinned like downloads so it
    // stays out while navigating; removed on NAVI_END.
    public void onNaviInfo(final String bizType, final String title,
            final String content, final int status, final int icon) {
        onNaviInfo(bizType, title, content, status, icon, 0);
    }

    /** v515: totalMeters = the route's total length when the
     * sender knows it (foot label stream: walked + remaining);
     * 0 = unknown (ride, notification placeholder). */
    public void onNaviInfo(final String bizType, final String title,
            final String content, final int status, final int icon,
            final int totalMeters) {
        if (bizType == null || app == null) {
            return;
        }
        // v314 (diagnostic): log every push BEFORE the bizType
        // filter, so BubbleNotify.log shows whether the engine
        // calls notifyNaviInfo at all and with what bizType.
        try {
            FileLog.i("NaviInfo bizType=" + bizType + " title=" + title
                    + " content=" + content + " status=" + status
                    + " icon=" + icon + " total=" + totalMeters);
        } catch (Throwable ignored) {
        }
        if (!"footnavi".equals(bizType) && !"ridenavi".equals(bizType)) {
            return;
        }
        // v512: live foot info flowing — the v322 computed capsule
        // stands down while this is fresh.
        if ("footnavi".equals(bizType)) {
            footLabelFreshAt = System.currentTimeMillis();
        }
        final String key = "navi:" + bizType;
        // v317: the engine puts the live instruction in title OR
        // content (one is empty) — title="96米后左转" content="",
        // or title="" content="直行114米". Merge so the instruction
        // is always the capsule title and the nav-type label is the
        // second line; only the start state ("导航开始" /
        // "从当前位置出发") keeps both fields as-is.
        String navLabel = "footnavi".equals(bizType) ? "步行导航"
                : "骑行导航";
        // v514: placeholder pushes (the Amap notification's
        // static start text, title == navLabel / status 1) pop
        // the capsule at nav start, but once a live instruction
        // has arrived for this key they must never overwrite it.
        boolean placeholderPush = status == 1
                || navLabel.equals(title);
        if (!placeholderPush) {
            liveNaviKeys.add(key);
        } else if (liveNaviKeys.contains(key)) {
            try {
                FileLog.i("NaviInfo placeholder suppressed key="
                        + key);
            } catch (Throwable ignored) {
            }
            return;
        }
        // v523: one navigation, one capsule — a live push for
        // this type evicts the other type's capsule (a phantom
        // foot capsule otherwise survives next to the real
        // ride capsule for the whole trip).
        if (!placeholderPush) {
            removeNotifByKey("footnavi".equals(bizType)
                    ? "navi:ridenavi" : "navi:footnavi");
        }
        boolean hasTitle = title != null && !title.isEmpty();
        boolean hasContent = content != null && !content.isEmpty();
        String dispTitle;
        String dispText;
        if (hasTitle && hasContent) {
            dispTitle = title;
            dispText = content;
        } else if (hasTitle) {
            dispTitle = title;
            dispText = navLabel;
        } else if (hasContent) {
            dispTitle = content;
            dispText = navLabel;
        } else {
            dispTitle = "正在导航";
            dispText = navLabel;
        }
        final NotifData data = new NotifData(key,
                "com.autonavi.minimap",
                dispTitle, dispText, System.currentTimeMillis(),
                null, null);
        data.isLive = true;
        data.isNavi = true;
        // v513: the v322 foot route total (when the route hook
        // delivered one) feeds the navi card's progress bar.
        // v515: the sender's own total (foot label stream:
        // walked + remaining) wins — the v322 total is silent
        // in most real walks.
        if (totalMeters > 0) {
            data.naviTotalMeters = totalMeters;
        } else if ("footnavi".equals(bizType) && footTotalLen > 0) {
            data.naviTotalMeters = (int) Math.round(footTotalLen);
        }
        onNotification(data);
    }

    // v311: Amap navigation ended — drop the nav capsule.
    public void onNaviEnd(final String bizType) {
        if (bizType == null || app == null) {
            return;
        }
        final String key = "navi:" + bizType;
        liveNaviKeys.remove(key);
        // v516: the manual-unpin memory is per navigation
        // session — the next trip auto-pins the card afresh.
        // (removeNotifByKey below already drops the key from
        // pinnedCardKeys and the group's pin table, so no
        // pinned-card state survives the removal either.)
        naviUserUnpinned.remove(key);
        handler.post(new Runnable() {
            @Override
            public void run() {
                removeNotifByKey(key);
                rebuildDock();
            }
        });
    }

    // v320: Amap bus live navigation, mode A (waiting for the bus).
    // Called on every real-time poll (~30s, ~10s when close).
    // Shows "xx路 还有N站，约M分钟到" as an auto-pinned live
    // capsule, one per line (key "bus:<line>"). Once boarding is
    // detected (busOnBoard) these updates are ignored — mode B
    // owns the capsule from then on.
    public void onBusInfo(final String line, final String station,
            final String stationLeft, final String arrival,
            final String busX, final String busY) {
        if (line == null || app == null) {
            return;
        }
        try {
            FileLog.i("BusInfo line=" + line + " station=" + station
                    + " left=" + stationLeft + " arrival=" + arrival
                    + " bus=(" + busX + "," + busY + ")");
        } catch (Throwable ignored) {
        }
        if (busOnBoard) {
            return;
        }
        int left = parseIntSafe(stationLeft, -1);
        int arrSec = parseIntSafe(arrival, -1);
        String text;
        if (left == 0 || (arrSec >= 0 && arrSec <= 30)) {
            text = "正在进站";
        } else if (left > 0 && arrSec >= 0) {
            int min = (arrSec + 59) / 60;
            text = "还有" + left + "站，约" + min + "分钟到";
        } else if (left > 0) {
            text = "还有" + left + "站";
        } else if (arrSec >= 0) {
            text = "约" + ((arrSec + 59) / 60) + "分钟到";
        } else {
            text = "实时公交";
        }
        // Only pop when the text changed — otherwise a dismissed
        // capsule would re-pop on every poll (~30s) / fix (~2s).
        if (text.equals(busLastShownText)) {
            return;
        }
        busLastShownText = text;
        final NotifData data = new NotifData("bus:" + line,
                "com.autonavi.minimap", line, text,
                System.currentTimeMillis(), null, null);
        data.isLive = true;
        data.isBus = true;
        onNotification(data);
    }

    // v320: Amap bus live navigation, mode B route. Stores the
    // station list (start + intermediates + end) and the
    // intermediate coords, resets boarding state, and starts the
    // passive location listener so boarding can be detected.
    public void onBusRoute(final String busname,
            final String direction, final String startName,
            final String endName, final String passNames,
            final String passCoords) {
        if (busname == null || app == null) {
            return;
        }
        try {
            FileLog.i("BusRoute bus=" + busname + " dir=" + direction
                    + " " + startName + "->" + endName
                    + " passNames=" + passNames
                    + " passCoords=" + passCoords);
        } catch (Throwable ignored) {
        }
        busLine = busname;
        busStartName = startName;
        busEndName = endName;
        busOnBoard = false;
        busPassedIdx = -1;
        busLastShownText = null;
        busStationNames.clear();
        busStationCoords.clear();
        if (startName != null && !startName.isEmpty()) {
            busStationNames.add(startName);
            busStationCoords.add(null);
        }
        String[] names = null;
        if (passNames != null && !passNames.isEmpty()) {
            names = passNames.split(" ");
        }
        double[] coords = null;
        if (passCoords != null && !passCoords.isEmpty()) {
            String[] parts = passCoords.split(",");
            coords = new double[parts.length];
            for (int i = 0; i < parts.length; i++) {
                coords[i] = parseDoubleSafe(parts[i], 0);
            }
        }
        if (names != null) {
            for (int i = 0; i < names.length; i++) {
                if (names[i] == null || names[i].isEmpty()) {
                    continue;
                }
                busStationNames.add(names[i]);
                double[] c = null;
                if (coords != null && coords.length >= (i + 1) * 2) {
                    c = new double[]{coords[i * 2], coords[i * 2 + 1]};
                }
                busStationCoords.add(c);
            }
        }
        if (endName != null && !endName.isEmpty()) {
            busStationNames.add(endName);
            busStationCoords.add(null);
        }
        startBusLocation();
    }

    // v320: passive location listener (SystemUI). Passive only —
    // it never powers GPS itself, it just receives fixes other
    // apps (Amap) already requested. Feeds boarding detection
    // and mode B next-stop tracking.
    private void startBusLocation() {
        if (busLocListening || app == null) {
            return;
        }
        try {
            busLocMgr = (LocationManager) app.getSystemService(
                    Context.LOCATION_SERVICE);
            if (busLocMgr == null) {
                return;
            }
            busLocListener = new LocationListener() {
                @Override
                public void onLocationChanged(Location location) {
                    onBusLocation(location);
                }

                @Override
                public void onStatusChanged(String provider,
                        int status, Bundle extras) {
                }

                @Override
                public void onProviderEnabled(String provider) {
                }

                @Override
                public void onProviderDisabled(String provider) {
                }
            };
            busLocMgr.requestLocationUpdates(
                    LocationManager.PASSIVE_PROVIDER, 2000, 0,
                    busLocListener);
            busLocListening = true;
            try {
                FileLog.i("BusLoc passive listener started");
            } catch (Throwable ignored) {
            }
        } catch (Throwable th) {
            try {
                FileLog.i("BusLoc start failed: " + th);
            } catch (Throwable ignored) {
            }
        }
    }

    private void stopBusLocation() {
        if (!busLocListening) {
            return;
        }
        busLocListening = false;
        try {
            if (busLocMgr != null && busLocListener != null) {
                busLocMgr.removeUpdates(busLocListener);
            }
        } catch (Throwable ignored) {
        }
        busLocMgr = null;
        busLocListener = null;
    }

    // v320: each passive fix updates the user's position, then
    // runs boarding detection (mode A -> B) or next-stop tracking
    // (mode B). Speed falls back to distance/time between fixes
    // when the provider reports no speed.
    private void onBusLocation(Location location) {
        if (location == null || busLine == null) {
            return;
        }
        double lat = location.getLatitude();
        double lng = location.getLongitude();
        long now = System.currentTimeMillis();
        double speed = location.hasSpeed() ? location.getSpeed() : 0;
        if (speed <= 0 && busUserLocTime > 0 && now > busUserLocTime) {
            double d = distMeters(busUserLat, busUserLng, lat, lng);
            double dt = (now - busUserLocTime) / 1000.0;
            if (dt > 0) {
                speed = d / dt;
            }
        }
        busUserLat = lat;
        busUserLng = lng;
        busUserLocTime = now;
        busUserSpeed = speed;
        if (busStationNames.isEmpty()) {
            return;
        }
        if (!busOnBoard) {
            maybeBoardBus();
        } else {
            updateBusNextStop();
        }
    }

    // v320: boarding detection. The bus has arrived at the user's
    // station (the latest BUS_INFO said so — tracked implicitly:
    // we only run this while mode A is live) and the user is now
    // moving at bus speed (>3 m/s) near the route (within 500m
    // of any station with coords, or within 500m of the boarding
    // station area). GCJ-02 vs WGS-84 offset makes exact
    // bus-position matching unreliable, so it is not used.
    private void maybeBoardBus() {
        if (busUserSpeed < 3.0) {
            return;
        }
        boolean nearRoute = false;
        for (int i = 0; i < busStationCoords.size(); i++) {
            double[] c = busStationCoords.get(i);
            if (c == null) {
                continue;
            }
            // c is [lng, lat].
            if (distMeters(busUserLat, busUserLng, c[1], c[0])
                    <= 500) {
                nearRoute = true;
                break;
            }
        }
        if (!nearRoute) {
            return;
        }
        busOnBoard = true;
        busPassedIdx = 0;
        try {
            FileLog.i("BusBoarded line=" + busLine
                    + " speed=" + busUserSpeed);
        } catch (Throwable ignored) {
        }
        updateBusNextStop();
    }

    // v320: mode B next-stop tracking. Advance busPassedIdx past
    // every station the user has come within 300m of (generous
    // radius for GCJ-02/WGS-84 offset + GPS error), then the next
    // stop is the station after it. Updates the same bus capsule
    // ("下一站：XX站，还剩N站").
    private void updateBusNextStop() {
        if (busStationNames.isEmpty() || busLine == null) {
            return;
        }
        for (int i = busPassedIdx + 1;
                i < busStationNames.size() - 1; i++) {
            double[] c = busStationCoords.get(i);
            if (c == null) {
                continue;
            }
            if (distMeters(busUserLat, busUserLng, c[1], c[0])
                    <= 300) {
                busPassedIdx = i;
            } else {
                break;
            }
        }
        int nextIdx = busPassedIdx + 1;
        if (nextIdx >= busStationNames.size()) {
            return;
        }
        String nextName = busStationNames.get(nextIdx);
        int remain = busStationNames.size() - 1 - nextIdx;
        String text = "下一站：" + nextName + "站";
        if (remain > 0) {
            text += "，还剩" + remain + "站";
        } else {
            text += "，终点站";
        }
        // Only pop when the next stop changed (see onBusInfo).
        if (text.equals(busLastShownText)) {
            return;
        }
        busLastShownText = text;
        final NotifData data = new NotifData("bus:" + busLine,
                "com.autonavi.minimap", busLine, text,
                System.currentTimeMillis(), null, null);
        data.isLive = true;
        data.isBus = true;
        onNotification(data);
    }

    // v320: bus capsule dismissed — stop tracking this route.
    public void onBusEnd() {
        if (busLine == null) {
            return;
        }
        final String key = "bus:" + busLine;
        busLine = null;
        busOnBoard = false;
        busPassedIdx = -1;
        busLastShownText = null;
        busStationNames.clear();
        busStationCoords.clear();
        stopBusLocation();
        handler.post(new Runnable() {
            @Override
            public void run() {
                removeNotifByKey(key);
                rebuildDock();
            }
        });
    }

    private static int parseIntSafe(String s, int def) {
        if (s == null) {
            return def;
        }
        try {
            return Integer.parseInt(s.trim());
        } catch (Throwable ignored) {
            return def;
        }
    }

    private static double parseDoubleSafe(String s, double def) {
        if (s == null) {
            return def;
        }
        try {
            return Double.parseDouble(s.trim());
        } catch (Throwable ignored) {
            return def;
        }
    }

    private static double distMeters(double lat1, double lng1,
            double lat2, double lng2) {
        double r = 6371000.0;
        double dLat = Math.toRadians(lat2 - lat1);
        double dLng = Math.toRadians(lng2 - lng1);
        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                + Math.cos(Math.toRadians(lat1))
                * Math.cos(Math.toRadians(lat2))
                * Math.sin(dLng / 2) * Math.sin(dLng / 2);
        double c = 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
        return r * c;
    }

    // v322: Amap foot navigation "compute it yourself" — route
    // received at nav start. Parse the compact polyline
    // ("lat,lng;lat,lng;..."), precompute segment lengths, and
    // start the passive location listener.
    public void onFootRoute(final int totalLength,
            final int totalTime, final String points) {
        if (points == null || points.isEmpty() || app == null) {
            return;
        }
        try {
            FileLog.i("FootRoute totalLen=" + totalLength
                    + " totalTime=" + totalTime
                    + " points=" + points.length() + " chars");
        } catch (Throwable ignored) {
        }
        String[] pairs = points.split(";");
        int n = pairs.length;
        double[] lats = new double[n];
        double[] lngs = new double[n];
        int count = 0;
        for (int i = 0; i < n; i++) {
            String[] ll = pairs[i].split(",");
            if (ll.length < 2) {
                continue;
            }
            double la = parseDoubleSafe(ll[0], 0);
            double lo = parseDoubleSafe(ll[1], 0);
            if (la == 0 && lo == 0) {
                continue;
            }
            lats[count] = la;
            lngs[count] = lo;
            count++;
        }
        if (count < 2) {
            return;
        }
        footPtsLat = new double[count];
        footPtsLng = new double[count];
        System.arraycopy(lats, 0, footPtsLat, 0, count);
        System.arraycopy(lngs, 0, footPtsLng, 0, count);
        footSegLen = new double[count - 1];
        double sum = 0;
        for (int i = 0; i < count - 1; i++) {
            footSegLen[i] = distMeters(footPtsLat[i], footPtsLng[i],
                    footPtsLat[i + 1], footPtsLng[i + 1]);
            sum += footSegLen[i];
        }
        footTotalLen = totalLength > 0 ? totalLength : sum;
        footTotalTime = totalTime;
        footTraveled = 0;
        footLastShownText = null;
        footLabelFreshAt = 0;
        startFootLocation();
        // Show the capsule immediately with the total, so the user
        // sees it even before the first fix arrives.
        updateFootCapsule();
    }

    private void startFootLocation() {
        if (footLocListening || app == null) {
            return;
        }
        try {
            footLocMgr = (LocationManager) app.getSystemService(
                    Context.LOCATION_SERVICE);
            if (footLocMgr == null) {
                return;
            }
            footLocListener = new LocationListener() {
                @Override
                public void onLocationChanged(Location location) {
                    onFootLocation(location);
                }

                @Override
                public void onStatusChanged(String provider,
                        int status, Bundle extras) {
                }

                @Override
                public void onProviderEnabled(String provider) {
                }

                @Override
                public void onProviderDisabled(String provider) {
                }
            };
            footLocMgr.requestLocationUpdates(
                    LocationManager.PASSIVE_PROVIDER, 1000, 0,
                    footLocListener);
            footLocListening = true;
            try {
                FileLog.i("FootLoc passive listener started");
            } catch (Throwable ignored) {
            }
        } catch (Throwable th) {
            try {
                FileLog.i("FootLoc start failed: " + th);
            } catch (Throwable ignored) {
            }
        }
    }

    private void stopFootLocation() {
        if (!footLocListening) {
            return;
        }
        footLocListening = false;
        try {
            if (footLocMgr != null && footLocListener != null) {
                footLocMgr.removeUpdates(footLocListener);
            }
        } catch (Throwable ignored) {
        }
        footLocMgr = null;
        footLocListener = null;
    }

    // v322: each passive fix projects the position onto the route
    // polyline, advances footTraveled (monotonic — never goes
    // backward), and refreshes the walk capsule.
    private void onFootLocation(Location location) {
        if (location == null || footPtsLat == null
                || footPtsLat.length < 2) {
            return;
        }
        double traveled = projectTraveled(location.getLatitude(),
                location.getLongitude());
        if (traveled > footTraveled) {
            footTraveled = traveled;
        }
        updateFootCapsule();
    }

    // v322: project (lat,lng) onto the polyline and return the
    // distance along the polyline to the closest point. Uses a
    // local equirectangular projection (accurate enough for a
    // walking route) and clamps each segment projection to the
    // segment. Returns -1 when there is no polyline.
    private double projectTraveled(double lat, double lng) {
        if (footPtsLat == null || footPtsLat.length < 2) {
            return -1;
        }
        double latRad = Math.toRadians(lat);
        double cosLat = Math.cos(latRad);
        double bestDist = Double.MAX_VALUE;
        double bestTraveled = 0;
        double cum = 0;
        for (int i = 0; i < footPtsLat.length - 1; i++) {
            double ax = (footPtsLng[i] - lng) * cosLat;
            double ay = footPtsLat[i] - lat;
            double bx = (footPtsLng[i + 1] - lng) * cosLat;
            double by = footPtsLat[i + 1] - lat;
            double dx = bx - ax;
            double dy = by - ay;
            double segLen2 = dx * dx + dy * dy;
            double t = 0;
            if (segLen2 > 0) {
                t = -(ax * dx + ay * dy) / segLen2;
                if (t < 0) {
                    t = 0;
                } else if (t > 1) {
                    t = 1;
                }
            }
            double px = ax + t * dx;
            double py = ay + t * dy;
            double dDeg = Math.sqrt(px * px + py * py);
            double dMeters = dDeg * 111320.0;
            if (dMeters < bestDist) {
                bestDist = dMeters;
                bestTraveled = cum + t * footSegLen[i];
            }
            cum += footSegLen[i];
        }
        return bestTraveled;
    }

    // v322: recompute the walk capsule from footTraveled. Line 1
    // is the next turn + distance ("左转 50米" / "直行 120米"),
    // line 2 is remaining distance + time ("剩1.9公里 · 32分钟").
    // Only pops when the text changed, so a dismissed capsule
    // does not re-pop on every fix.
    private void updateFootCapsule() {
        // v512: label-driven foot info is flowing — do not
        // overwrite its capsule with the computed projection.
        if (System.currentTimeMillis() - footLabelFreshAt < 10_000L) {
            return;
        }
        if (footPtsLat == null || footPtsLat.length < 2) {
            return;
        }
        double remain = footTotalLen - footTraveled;
        if (remain < 0) {
            remain = 0;
        }
        String turnText = nextTurnText();
        String remainText = formatRemain(remain);
        String title;
        String text;
        if (remain <= 5) {
            title = "即将到达";
            text = remainText;
        } else {
            title = turnText;
            text = remainText;
        }
        String shown = title + "|" + text;
        if (shown.equals(footLastShownText)) {
            return;
        }
        footLastShownText = shown;
        final NotifData data = new NotifData("navi:footnavi",
                "com.autonavi.minimap", title, text,
                System.currentTimeMillis(), null, null);
        data.isLive = true;
        data.isNavi = true;
        // v513: route total for the navi card's progress bar.
        data.naviTotalMeters = (int) Math.round(footTotalLen);
        onNotification(data);
    }

    // v322: next turn from the polyline. Starting at the segment
    // containing the projected position, find the first segment
    // whose bearing differs from the current bearing by more than
    // 30 degrees; that is the next turn. Direction from the sign
    // of the bearing change (clockwise = right). Returns
    // "直行 X米" when no turn is found within the route.
    private String nextTurnText() {
        if (footPtsLat == null || footPtsLat.length < 2) {
            return "正在导航";
        }
        int segIdx = segmentAtTraveled(footTraveled);
        if (segIdx < 0 || segIdx >= footPtsLat.length - 1) {
            return "正在导航";
        }
        double curBearing = bearing(footPtsLat[segIdx],
                footPtsLng[segIdx], footPtsLat[segIdx + 1],
                footPtsLng[segIdx + 1]);
        double distToTurn = footSegLen[segIdx]
                - (footTraveled - cumLenTo(segIdx));
        for (int i = segIdx + 1; i < footPtsLat.length - 1; i++) {
            double b = bearing(footPtsLat[i], footPtsLng[i],
                    footPtsLat[i + 1], footPtsLng[i + 1]);
            double diff = normAngle(b - curBearing);
            if (diff > 30) {
                return "右转 " + formatDist(distToTurn);
            }
            if (diff < -30) {
                return "左转 " + formatDist(distToTurn);
            }
            distToTurn += footSegLen[i];
            curBearing = b;
        }
        return "直行 " + formatDist(distToTurn);
    }

    private int segmentAtTraveled(double traveled) {
        double cum = 0;
        for (int i = 0; i < footSegLen.length; i++) {
            if (traveled <= cum + footSegLen[i]) {
                return i;
            }
            cum += footSegLen[i];
        }
        return footSegLen.length - 1;
    }

    private double cumLenTo(int segIdx) {
        double cum = 0;
        for (int i = 0; i < segIdx && i < footSegLen.length; i++) {
            cum += footSegLen[i];
        }
        return cum;
    }

    private static double bearing(double lat1, double lng1,
            double lat2, double lng2) {
        double dLng = Math.toRadians(lng2 - lng1);
        double la1 = Math.toRadians(lat1);
        double la2 = Math.toRadians(lat2);
        double y = Math.sin(dLng) * Math.cos(la2);
        double x = Math.cos(la1) * Math.sin(la2)
                - Math.sin(la1) * Math.cos(la2) * Math.cos(dLng);
        double brg = Math.toDegrees(Math.atan2(y, x));
        return (brg + 360) % 360;
    }

    private static double normAngle(double a) {
        a = (a + 540) % 360 - 180;
        return a;
    }

    private String formatRemain(double remainMeters) {
        String dist = formatDist(remainMeters);
        int remainSec = -1;
        if (footTotalTime > 0 && footTotalLen > 0) {
            remainSec = (int) Math.round(footTotalTime
                    * (remainMeters / footTotalLen));
        }
        if (remainSec >= 0) {
            int min = (remainSec + 59) / 60;
            return "剩" + dist + " · " + min + "分钟";
        }
        return "剩" + dist;
    }

    private static String formatDist(double meters) {
        if (meters >= 1000) {
            double km = meters / 1000.0;
            return String.format(java.util.Locale.ROOT, "%.1f公里", km);
        }
        return ((int) Math.round(meters)) + "米";
    }

    // v322: foot navigation ended — drop the walk capsule and stop
    // tracking the route.
    public void onFootEnd() {
        footPtsLat = null;
        footPtsLng = null;
        footSegLen = null;
        footTotalLen = 0;
        footTotalTime = 0;
        footTraveled = 0;
        footLastShownText = null;
        footLabelFreshAt = 0;
        stopFootLocation();
        handler.post(new Runnable() {
            @Override
            public void run() {
                removeNotifByKey("navi:footnavi");
                rebuildDock();
            }
        });
    }

    public void onNotificationRemoved(final String key) {
        if (key == null || app == null) {
            return;
        }
        handler.post(new Runnable() {
            @Override
            public void run() {
                removeNotifByKey(key);
                rebuildDock();
            }
        });
    }

    /** v244: verification-code keywords (auto-copy). */
    private static final String[] VERIFY_KEYWORDS =
            new String[]{"验证码", "校验码", "动态码"};
    /** v244: pickup-code keywords (auto-pin). Meituan
     * self-pickup is 取餐码 / 自提码; couriers and lockers use
     * 取件码 / 取货码 / 提货码. */
    private static final String[] PICKUP_KEYWORDS = new String[]{
            "取货码", "取件码", "提货码", "取餐码", "自提码",
            "寄存码", "领取码"};

    /** v244: find a code near one of the keywords. Returns
     * {code, matchedKeyword} or null. A code is 4-8 digits
     * within ~12 chars after the keyword ("验证码是 123456")
     * or before it ("123456是您的验证码"); pickup codes may
     * also be alphanumeric as long as they contain a digit
     * ("取件码 A1234"). Pickup keywords are tried first —
     * they are more specific than the generic verify words. */
    private static String[] extractCode(String title, String text) {
        String s = (title != null ? title : "")
                + "\n" + (text != null ? text : "");
        if (s.trim().isEmpty()) {
            return null;
        }
        String[] r = findCodeNear(s, PICKUP_KEYWORDS, true);
        if (r != null) {
            return r;
        }
        r = findCodeNear(s, VERIFY_KEYWORDS, false);
        if (r != null) {
            return r;
        }
        // v245: locker / courier phrasing with no code keyword —
        // "请凭173351...取件" (百为智能) and "凭10-18-0413取件"
        // (菜鸟驿站). The code sits right after 凭: plain 4-8
        // digits, or hyphenated groups (10-18-0413). A time
        // like 19:00 is safe — it sits BEFORE 凭, and the
        // pattern only reads what follows it.
        java.util.regex.Matcher pm = java.util.regex.Pattern.compile(
                "凭\\s*([0-9]{4,8}|[0-9]{2,4}(?:-[0-9]{2,4}){1,2})")
                .matcher(s);
        if (pm.find()) {
            return new String[]{pm.group(1), "取件码"};
        }
        // v248: English (WeChat verifying / Meta confirmation
        // code / two-factor authentication). Tried last, after
        // all Chinese patterns.
        return findEnglishCode(s);
    }

    /** v245: smart-locker cabinet/box number. "第17箱" (百为智能),
     * "2-01智能柜" (韵达), "1号快递柜". Returns the cabinet
     * string (e.g. "第17箱", "2-01柜", "1号柜") or null.
     * The hyphenated cabinet (2-01) is only matched when 柜
     * follows it, so a hyphenated pickup code (10-18-0413)
     * is never mistaken for a cabinet. */
    private static String extractCabinet(String title, String text) {
        String s = (title != null ? title : "")
                + "\n" + (text != null ? text : "");
        if (s.trim().isEmpty()) {
            return null;
        }
        java.util.regex.Matcher m1 = java.util.regex.Pattern.compile(
                "第([0-9]+)箱").matcher(s);
        if (m1.find()) {
            return "第" + m1.group(1) + "箱";
        }
        java.util.regex.Matcher m2 = java.util.regex.Pattern.compile(
                "([0-9]+-[0-9]+)[智能]*柜").matcher(s);
        if (m2.find()) {
            return m2.group(1) + "柜";
        }
        java.util.regex.Matcher m3 = java.util.regex.Pattern.compile(
                "([0-9]+)号快递柜").matcher(s);
        if (m3.find()) {
            return m3.group(1) + "号柜";
        }
        return null;
    }

    private static String[] findCodeNear(String s, String[] keywords,
            boolean allowAlpha) {
        for (String kw : keywords) {
            int idx = s.indexOf(kw);
            if (idx < 0) {
                continue;
            }
            // Code after the keyword: "验证码是 123456".
            String after = s.substring(idx + kw.length());
            java.util.regex.Matcher m =
                    java.util.regex.Pattern.compile("[0-9]{4,8}")
                            .matcher(after);
            if (m.find() && m.start() < 12) {
                return new String[]{m.group(), kw};
            }
            // Code before the keyword: "123456是您的验证码".
            String before = s.substring(0, idx);
            java.util.regex.Matcher m2 =
                    java.util.regex.Pattern.compile("[0-9]{4,8}")
                            .matcher(before);
            String last = null;
            int lastStart = -1;
            while (m2.find()) {
                last = m2.group();
                lastStart = m2.start();
            }
            if (last != null && before.length() - lastStart < 16) {
                return new String[]{last, kw};
            }
            // Alphanumeric pickup code: "取件码 A1234".
            if (allowAlpha) {
                java.util.regex.Matcher m3 = java.util.regex.Pattern
                        .compile("[A-Za-z0-9]{4,8}").matcher(after);
                while (m3.find() && m3.start() < 12) {
                    String c = m3.group();
                    if (c.matches(".*[0-9].*")) {
                        return new String[]{c, kw};
                    }
                }
            }
        }
        return null;
    }

    /** v248: English code recognition. Keywords are matched
     * case-insensitively; the window is 25 non-digit chars
     * because English puts words between the keyword and the
     * code ("verifying mobile number (335403)"). Both orders
     * are covered: code after the keyword ("Your OTP is
     * 482915") and code before it ("332641 is your Meta
     * confirmation code.", "Use 838516 for two-factor
     * authentication"). Returns {code, "验证码"} or {code,
     * "取件码"} (Chinese labels, so the rest of the pipeline
     * works unchanged), or null. */
    private static String[] findEnglishCode(String s) {
        String verify = "(?:verif\\w*|otp|one[\\s-]time code|"
                + "authentication code|two-factor authentication|"
                + "two-factor|2fa|security code|confirmation code|"
                + "confirm code|passcode)";
        String pickup = "(?:pickup code|pick[\\s-]up code|"
                + "collection code|parcel code|delivery code)";
        java.util.regex.Matcher m;
        // Code after the keyword (pickup first — more specific).
        m = java.util.regex.Pattern.compile(
                "(?i)" + pickup + "[^0-9]{0,25}([0-9]{4,8})")
                .matcher(s);
        if (m.find()) {
            return new String[]{m.group(1), "取件码"};
        }
        m = java.util.regex.Pattern.compile(
                "(?i)" + verify + "[^0-9]{0,25}([0-9]{4,8})")
                .matcher(s);
        if (m.find()) {
            return new String[]{m.group(1), "验证码"};
        }
        // Code before the keyword.
        m = java.util.regex.Pattern.compile(
                "(?i)([0-9]{4,8})[^0-9]{0,25}" + pickup)
                .matcher(s);
        if (m.find()) {
            return new String[]{m.group(1), "取件码"};
        }
        m = java.util.regex.Pattern.compile(
                "(?i)([0-9]{4,8})[^0-9]{0,25}" + verify)
                .matcher(s);
        if (m.find()) {
            return new String[]{m.group(1), "验证码"};
        }
        return null;
    }

    /** v244: is this keyword a pickup keyword? (Decides
     * auto-copy: verification codes copy, pickup codes do not.) */
    private static boolean isPickupKeyword(String kw) {
        if (kw == null) {
            return false;
        }
        for (String k : PICKUP_KEYWORDS) {
            if (k.equals(kw)) {
                return true;
            }
        }
        return false;
    }

    /** v244: copy a verification code to the clipboard and
     * confirm with a short Toast. Runs in SystemUI, which may
     * write the clipboard.
     * v540: 入口先过「自动复制验证码」开关（FeatureFlags
     * .autoCopyCode，默认开 = 老行为）——关掉后验证码只显示
     * 不复制，识别/钉住都不受影响。 */
    private void autoCopyVerifyCode(String code) {
        if (!FeatureFlags.autoCopyCode) {
            return;
        }
        if (code == null || app == null) {
            return;
        }
        try {
            android.content.ClipboardManager cm =
                    (android.content.ClipboardManager)
                    app.getSystemService(
                            android.content.Context.CLIPBOARD_SERVICE);
            if (cm != null) {
                cm.setPrimaryClip(
                        android.content.ClipData.newPlainText(
                                "验证码", code));
                // v524: 打码状态下 Toast 也不露码（剪贴板还是真码）。
                String toast = PrivacyMask.shouldMask(app)
                        ? "验证码已复制"
                        : "验证码 " + code + " 已复制";
                android.widget.Toast.makeText(app,
                        toast,
                        android.widget.Toast.LENGTH_SHORT).show();
            }
        } catch (Throwable ignored) {
        }
    }

    private void popCapsule(NotifData data) {
        if (dock == null) {
            return;
        }
        // v504 (2.4.2): per-app rules (AppRulesActivity, backlog
        // #5). NEVER = this app's notifications never become
        // bubbles (the system notification itself is untouched).
        // EDGE = no capsule pop — the notification goes straight
        // into the docked fan via the same addPileIconForGroup
        // path a manual dock uses, so tapping the fan expands it
        // back out unchanged. Calls are exempt (same as the scene
        // guard); download / live / navi keep their own capsule
        // behaviour and are exempt from EDGE. v507: music is NO
        // LONGER exempt from EDGE (user 2026-11-24: music apps
        // must obey the rule like everything else).
        if (data != null && !data.isCall && data.pkg != null) {
            int rule = AppRules.modeFor(data.pkg);
            if (rule == AppRules.NEVER) {
                return;
            }
            if (rule == AppRules.EDGE
                    && !data.isDownload && !data.isLive
                    && !data.isNavi && !data.isBus) {
                NotifGroup stored = dockedGroups.get(data.pkg);
                if (stored != null && data.key != null) {
                    for (int i = stored.items.size() - 1; i >= 0;
                            i--) {
                        NotifData old = stored.items.get(i);
                        if (old != null
                                && data.key.equals(old.key)) {
                            stored.items.remove(i);
                        }
                    }
                }
                NotifGroup dg = new NotifGroup(data.pkg);
                dg.add(data);
                addPileIconForGroup(dg);
                return;
            }
        }
        // v259: APP feature switches (real-time, no restart).
        // Master switch off = no capsule at all; the others gate
        // only their own kind. Switches on = exactly the old path.
        if (!FeatureFlags.notif) {
            return;
        }
        if (data != null && data.isMusic && !FeatureFlags.music) {
            return;
        }
        if (data != null && data.isDownload && !FeatureFlags.dl) {
            return;
        }
        if (data != null && data.isCall && !FeatureFlags.live) {
            return;
        }
        // v244: recognize a verification / pickup code in the
        // title/text before anything else, so the code is set
        // for dedup, display, auto-pin and auto-copy.
        // v259: code switch off = skip recognition entirely, the
        // notification still pops as a regular capsule.
        if (FeatureFlags.code && data != null && data.code == null) {
            String[] found = extractCode(data.title, data.text);
            if (found != null) {
                data.code = found[0];
                data.codeLabel = found[1];
                data.isPickupCode = isPickupKeyword(found[1]);
                // v245: a pickup code may come with a locker
                // number — show it too (user 2026-11-21
                // "如果有柜号肯定也要显示啊").
                if (data.isPickupCode) {
                    data.cabinet =
                            extractCabinet(data.title, data.text);
                }
            }
        }
        // Second-line dedup (v100): same app, same title + text
        // within 15s — drop it here even if the source dedup let it
        // through. Music and downloads are exempt (their re-posts
        // are legitimate updates).
        // v307: WeChat/QQ exempt too (user 2026-11-21). Same person
        // sends two messages: both show in the shade, but the second
        // was counted as a duplicate and dropped — only the first
        // capsule ever popped. The v146 key+content backstop still
        // catches true duplicates (same key + same content).
        if (data != null && !data.isMusic && !data.isDownload
                && data.pkg != null
                && !"com.tencent.mm".equals(data.pkg)
                && !"com.tencent.mobileqq".equals(data.pkg)) {
            String fp = data.pkg + "\u0001"
                    + (data.title == null ? "" : data.title) + "\u0001"
                    + (data.text == null ? "" : data.text);
            long now = System.currentTimeMillis();
            Long at = popFingerprints.get(fp);
            if (at != null && now - at < POP_FINGERPRINT_WINDOW_MS) {
                return;
            }
            popFingerprints.put(fp, now);
            if (popFingerprints.size() > 64) {
                java.util.Iterator<java.util.Map.Entry<String, Long>> it =
                        popFingerprints.entrySet().iterator();
                while (it.hasNext()) {
                    if (now - it.next().getValue()
                            > POP_FINGERPRINT_WINDOW_MS) {
                        it.remove();
                    }
                }
            }
        }
        // v175: music double-delivery backstop. Music is exempt
        // from the v100/v146 dedup (a same-key re-post is a
        // legitimate song change), but a double DELIVERY - the same
        // song change arriving twice within milliseconds because the
        // broadcast was received twice - pops the same capsule
        // twice. A real song change has different title/text; the
        // same key + same content within 2s can only be a duplicate
        // delivery. (v174's static receiver guard did not stop it,
        // so the dedup has to catch it here.)
        // v203 (1.0.5): music bypasses this 2s dedup entirely.
        // 1.0.5's music branch returns before any dedup, and a
        // same-key music re-post is an UPDATE (song change), not a
        // duplicate. The v197 sync seen-guard already stops a dual
        // delivery before it reaches here, so this guard only ever
        // swallowed legitimate song changes whose parsed title had
        // not (yet) changed.
        // Long-lived key+content backstop (v146): same key + same
        // title/text within 1h = the same message, drop it even if
        // the module let it through (reconnect re-delivery). Same key
        // + new content = an update, let it through.
        if (data != null && !data.isMusic && !data.isDownload
                && data.key != null) {
            String digest = (data.title == null ? "" : data.title)
                    + "\u0001" + (data.text == null ? "" : data.text);
            long now = System.currentTimeMillis();
            Long at = poppedKeysTime.get(data.key);
            String prev = poppedKeys.get(data.key);
            if (at != null && now - at < POP_KEY_TTL_MS
                    && digest.equals(prev)) {
                return;
            }
            poppedKeys.put(data.key, digest);
            poppedKeysTime.put(data.key, now);
            if (poppedKeys.size() > 256) {
                java.util.Iterator<java.util.Map.Entry<String, Long>> it =
                        poppedKeysTime.entrySet().iterator();
                while (it.hasNext()) {
                    java.util.Map.Entry<String, Long> e = it.next();
                    if (now - e.getValue() > POP_KEY_TTL_MS) {
                        it.remove();
                        poppedKeys.remove(e.getKey());
                    }
                }
            }
        }
        // v301: Migu-only (user: "针对修复" / "不写通用").
        // Migu re-posts the same key every time the lyric refreshes
        // and pops a duplicate each time; other apps don't do this
        // (NetEase's ticker is filtered, its re-posts are spaced
        // out). So only Migu gets this gate: if a Migu music
        // capsule is already on screen, refresh its lyric in place
        // and return — never pop again. Other music apps keep the
        // existing logic untouched.
        // v302: if the user already dismissed this Migu song
        // (right-swipe), a lyric update for the SAME song must NOT
        // re-pop it. Only a new song (different title) pops again.
        if (data.isMusic
                && "cmccwm.mobilemusic".equals(data.pkg)) {
            if (data.key != null && data.title != null) {
                String dismissedTitle = dismissedMiguMusic.get(data.key);
                if (data.title.equals(dismissedTitle)) {
                    return;
                }
                if (dismissedTitle != null) {
                    dismissedMiguMusic.remove(data.key);
                }
            }
            CapsuleView miguCap = findMusicCapsuleByPkg(data.pkg);
            if (miguCap != null) {
                if (data.lyrics != null) {
                    miguCap.updateLyrics(data.lyrics);
                }
                if (data.title != null) {
                    miguCap.updateTitle(data.title);
                }
                if (data.text != null) {
                    miguCap.updateText(data.text);
                }
                miguCap.updateMusicCard();
                return;
            }
        }
        // v296: music re-post? If a capsule view with this exact
        // key is already on screen, refresh it in place and return
        // — never touch the groups, never pop. The log proves Migu
        // re-posts the same key 6 times in 11s (HookPosted/HookMusic
        // only, no broadcast); the group remove/re-add is what let
        // the 2nd/4th/6th delivery slip through as a "new" pop.
        // The view is the ground truth — if it's there, it's an
        // update, full stop.
        if (data.isMusic && data.key != null) {
            CapsuleView existingCap = findCapsuleByKey(data.key);
            if (existingCap == null
                    && !SIM_MUSIC_KEY.equals(data.key)) {
                // Fallback: the key may differ between hook and
                // broadcast deliveries of the same re-post. If ANY
                // music capsule for this app is on screen, it's the
                // same song — refresh it in place, don't pop.
                // v459: 模拟音乐（SIM_MUSIC_KEY）不走这个 fallback——
                // 否则它会把真 QQ Flyme 胶囊当成自己刷成假数据。
                existingCap = findMusicCapsuleByPkg(data.pkg);
            }
            if (existingCap != null) {
                NotifData oldData = existingCap.getData();
                if (oldData != null) {
                    oldData.title = data.title;
                    oldData.text = data.text;
                    oldData.lyrics = data.lyrics;
                    oldData.largeIcon = data.largeIcon;
                    oldData.contentIntent = data.contentIntent;
                }
                if (data.title != null) {
                    existingCap.updateTitle(data.title);
                }
                if (data.text != null) {
                    existingCap.updateText(data.text);
                }
                if (data.lyrics != null) {
                    existingCap.updateLyrics(data.lyrics);
                }
                existingCap.updateMusicCard();
                return;
            }
        }
        // v426 (user 2026-11-22 "改成一个胶囊吧"): WeChat/QQ
        // same-person messages merge into ONE capsule. WeChat
        // reuses the same notification key per conversation, so a
        // new message from the same person arrives with the same
        // key — update the on-screen capsule in place (title/text)
        // instead of popping a new one. Different people have
        // different keys, so they still get separate capsules.
        // If the capsule is not on screen (docked/gone), fall
        // through to a normal pop.
        if (!data.isMusic && !data.isLive && data.key != null
                && ("com.tencent.mm".equals(data.pkg)
                        || "com.tencent.mobileqq".equals(data.pkg))) {
            CapsuleView existingCap = findCapsuleByKey(data.key);
            if (existingCap != null) {
                NotifData oldData = existingCap.getData();
                if (oldData != null) {
                    oldData.largeIcon = data.largeIcon;
                    oldData.contentIntent = data.contentIntent;
                }
                if (data.title != null) {
                    existingCap.updateTitle(data.title);
                }
                if (data.text != null) {
                    existingCap.updateText(data.text);
                }
                return;
            }
        }
        // Dedup by key: the same notification never pops twice.
        // EXCEPT music: a music notification keeps the SAME key across
        // song changes (one notification, updated — the player is a
        // singleton, one current song), so a same-key music re-post is
        // an UPDATE, not a duplicate. Remove the old item so the new
        // song takes its place (user 2026-11-19 "是不是切歌也不会变"
        // — it didn't change, because this dedup dropped the new
        // song). Non-music keeps the drop.
        // A music same-key update must NEVER pop again (user 2026-11-19
        // "双通知就是又弹出两次通知了"): the music app re-posts the
        // same key on lyric lines / updates, and the old code still
        // ran the pop-in animation whenever the 5s/30s throttle had
        // passed — the same notification popping twice.
        boolean isMusicUpdate = false;
        boolean isLiveUpdate = false;
        // v308: WeChat/QQ exempt from the same-key drop (user
        // 2026-11-21). Same person sends two messages: WeChat reuses
        // the same notification key per conversation, so the second
        // message hit "same key in a group → return" and was dropped
        // — only the first capsule ever popped. For WeChat/QQ the
        // same key with new content is a NEW message, not a
        // duplicate; let it through (v306 noStack gives it its own
        // group/capsule).
        boolean isChatApp = "com.tencent.mm".equals(data.pkg)
                || "com.tencent.mobileqq".equals(data.pkg);
        for (NotifGroup g : groups) {
            if (g.hasKey(data.key)) {
                if (data.isMusic) {
                    g.removeByKey(data.key);
                    isMusicUpdate = true;
                } else if (data.isLive) {
                    // v222: a live activity re-posts the same key
                    // with new content (timer tick, delivery
                    // status) — that is an UPDATE, not a duplicate.
                    g.removeByKey(data.key);
                    isLiveUpdate = true;
                } else if (!isChatApp) {
                    return;
                }
                break;
            }
        }
        // Non-music: also drop when the key is already docked (in the
        // fan). A re-post of a docked notification (same key, updated
        // content) otherwise pops again as a brand-new capsule, and
        // every app re-posts — that is the "all apps pop twice"
        // regression.
        // v308: WeChat/QQ exempt — same key is a new message, not a
        // re-post of the docked one.
        if (!data.isMusic && !data.isLive && !isChatApp) {
            for (NotifGroup g : dockedGroups.values()) {
                if (g.hasKey(data.key)) {
                    return;
                }
            }
        }
        if (data.isLive) {
            // v222: a docked live activity that updates pops back
            // out (like music) instead of staying in the fan.
            for (NotifGroup g : dockedGroups.values()) {
                if (g.hasKey(data.key)) {
                    g.removeByKey(data.key);
                    isLiveUpdate = true;
                    break;
                }
            }
        }
        if (data.isMusic) {
            for (NotifGroup g : dockedGroups.values()) {
                if (g.hasKey(data.key)) {
                    g.removeByKey(data.key);
                    isMusicUpdate = true;
                    break;
                }
            }
            // Some players re-post the media notification with a NEW
            // key on every update, so the same-key dedup above never
            // fires and a second music capsule pops (user 2026-11-19
            // "又开始双通知了"). Dedup music by PACKAGE: one app has
            // at most one music capsule. Remove any existing music
            // item for this app first, and treat this as an update.
            if (!isMusicUpdate) {
                for (NotifGroup g : groups) {
                    if (g.pkg != null && g.pkg.equals(data.pkg)) {
                        for (int i = g.items.size() - 1; i >= 0; i--) {
                            if (g.items.get(i).isMusic) {
                                g.items.remove(i);
                                isMusicUpdate = true;
                            }
                        }
                    }
                }
                for (NotifGroup g : dockedGroups.values()) {
                    if (g.pkg != null && g.pkg.equals(data.pkg)) {
                        for (int i = g.items.size() - 1; i >= 0; i--) {
                            if (g.items.get(i).isMusic) {
                                g.items.remove(i);
                                isMusicUpdate = true;
                            }
                        }
                    }
                }
                // Drop groups left empty by the removal above, so an
                // empty group does not linger in the dock.
                for (int gi = groups.size() - 1; gi >= 0; gi--) {
                    if (groups.get(gi).items.isEmpty()) {
                        groups.remove(gi);
                    }
                }
                java.util.Iterator<java.util.Map.Entry<String, NotifGroup>> it =
                        dockedGroups.entrySet().iterator();
                while (it.hasNext()) {
                    if (it.next().getValue().items.isEmpty()) {
                        it.remove();
                    }
                }
            }
        }
        // Same app stacks into one group (newest first inside it).
        // v306: WeChat + QQ are the exception (user 2026-11-21):
        // every message is its own capsule row, never stacked.
        // Same person, different messages = separate popups.
        boolean noStack = "com.tencent.mm".equals(data.pkg)
                || "com.tencent.mobileqq".equals(data.pkg);
        NotifGroup group = null;
        if (!noStack) {
            for (NotifGroup g : groups) {
                if (g.pkg != null && g.pkg.equals(data.pkg)) {
                    group = g;
                    break;
                }
            }
        }
        // v160: discard any DOCKED group for this app (notifications
        // the user already dissipated). The old code reclaimed it when
        // there was no active group — the old items came back as
        // capsules together with the new one — and left it lingering
        // when an active group existed (stale pile icon overlapping
        // the capsules, the "second layer", and tapping the icon
        // resurrected the dismissed capsules). Dismissed = gone:
        // only the new notification appears.
        NotifGroup docked = dockedGroups.remove(data.pkg);
        if (docked != null && pile != null) {
            pile.removeIconForPkg(data.pkg);
        }
        if (group == null) {
            group = new NotifGroup(data.pkg);
            groups.add(group);
        }
        // Downloads auto-pin on pop (user 2026-11-19 "这个弹出会
        // 自动钉住"): the capsule stays out showing live progress
        // instead of docking after 3s. Same pin bookkeeping as
        // togglePin's pin branch.
        if (data.isDownload && data.key != null
                && !group.pinnedAtByKey.containsKey(data.key)) {
            boolean firstPin = group.pinnedAtByKey.isEmpty();
            group.pinnedAtByKey.put(data.key, System.currentTimeMillis());
            group.sortItems();
            if (firstPin) {
                group.pinned = true;
                group.pinnedAt = System.currentTimeMillis();
            }
        }
        // v244: a recognized code auto-pins too (user
        // 2026-11-21 "识别了就放大钉住") — verification and
        // pickup alike — so it stays out instead of docking.
        if (data.code != null && data.key != null
                && !group.pinnedAtByKey.containsKey(data.key)) {
            boolean firstPin = group.pinnedAtByKey.isEmpty();
            group.pinnedAtByKey.put(data.key, System.currentTimeMillis());
            group.sortItems();
            if (firstPin) {
                group.pinned = true;
                group.pinnedAt = System.currentTimeMillis();
            }
        }
        // v311: a nav capsule auto-pins like a download/code so it
        // stays out showing live turn info while navigating.
        if ((data.isNavi || data.isBus) && data.key != null
                && !group.pinnedAtByKey.containsKey(data.key)) {
            boolean firstPin = group.pinnedAtByKey.isEmpty();
            group.pinnedAtByKey.put(data.key, System.currentTimeMillis());
            group.sortItems();
            if (firstPin) {
                group.pinned = true;
                group.pinnedAt = System.currentTimeMillis();
            }
            // v516: foot/ride navi also auto-pins AS A CARD — the
            // capsule pops already expanded (the dashboard card)
            // and stays expanded for the whole trip, refreshed in
            // place by the live updates. Same bookkeeping a user
            // pinning the open card produces: the key joins
            // pinnedCardKeys, and rebuildDock's Phase 1 renders
            // pinned-card keys expanded. Bus keeps the v311
            // stay-out pin only. A key the user manually
            // unpinned this session (naviUserUnpinned) is NOT
            // re-pinned as a card by the live updates.
            if (data.isNavi && !naviUserUnpinned.contains(data.key)) {
                pinnedCardKeys.add(data.key);
            }
        }
        // Is any group expanded (before adding this notification)?
        boolean anyExpanded = false;
        for (NotifGroup g : groups) {
            if (g.expanded) {
                anyExpanded = true;
                break;
            }
        }
        if (anyExpanded && group.expanded) {
            // The new notification belongs to an expanded group: append
            // it to the END of that group's items, so it appears AFTER
            // all the already-expanded notifications in the group (not
            // at the top). The group stays where it is.
            group.items.add(data);
        } else {
            // Newest-first insert (sorted by postTime).
            group.add(data);
        }
        if (anyExpanded) {
            // The new notification appears AFTER all the already-
            // expanded notifications — i.e., just after the last
            // expanded group. If its app is the expanded group itself,
            // it stays there (the item was appended above). Otherwise,
            // move its group to just after the last expanded group.
            // The fan (docked icons) is pushed down after it, since
            // rebuildDock always places the pile after all groups. Do
            // not re-sort by time, so the expanded view is not
            // disturbed.
            if (!group.expanded) {
                int lastExpandedIdx = -1;
                for (int i = 0; i < groups.size(); i++) {
                    if (groups.get(i).expanded) {
                        lastExpandedIdx = i;
                    }
                }
                groups.remove(group);
                groups.add(lastExpandedIdx + 1, group);
            }
        } else {
            // Groups order by their latest notification time, newest first
            // — but pinned groups always stay on top (user 2026-11-19
            // "有钉住通知时，新通知也要在顶住通知下面弹出"): a new
            // notification pops in just under the pinned ones, never
            // above them.
            Collections.sort(groups, new Comparator<NotifGroup>() {
                @Override
                public int compare(NotifGroup a, NotifGroup b) {
                    if (a.pinned != b.pinned) {
                        return a.pinned ? -1 : 1;
                    }
                    if (a.pinned) {
                        return Long.compare(a.pinnedAt, b.pinnedAt);
                    }
                    return Long.compare(b.latestTime(), a.latestTime());
                }
            });
        }
        rebuildDock();
        // A music same-key update refreshes the capsule in place and
        // never pops again (see above) — skip the pop-in animation
        // and the throttle bookkeeping entirely.
        if (isMusicUpdate) {
            // v217 (user 2026-11-20 "QQ音乐，网易云音乐，汽水音乐
            // 的刷新有问题" / "胶囊切歌不刷新"): GENERIC - the
            // force-update below was Flyme-only (v211, user then
            // said "单flyme版QQ音乐这样读取，其他不改"), but the
            // same staleness hits every music app: rebuildDock()
            // recreates the views, yet the visible capsule was
            // observed staying on the old song after a song change
            // (manual switch does not change it, and neither does
            // auto-switch; only dismiss-then-switch works, because
            // that takes the fresh-pop path, and only opening the
            // card refreshes it). Force the title/artist/lyric
            // onto the visible capsule for ALL music, so any song
            // change shows the new song at once, whether or not
            // the card is open. updateMusicCard also re-reads
            // data.largeIcon, so late album art follows the
            // refresh too.
            CapsuleView cap = findCapsuleByKey(data.key);
            if (cap != null) {
                if (data.title != null) {
                    cap.updateTitle(data.title);
                }
                if (data.text != null) {
                    cap.updateText(data.text);
                }
                // v213 (user 2026-11-20 "这里才变正确歌词"):
                // the capsule lyric line must follow the new
                // song too. The title/artist were forced above,
                // but the lyric stayed on the old song until the
                // user expanded the card and shrank it back.
                // Force the lyric as well so the capsule shows
                // the new song's lyric immediately.
                if (data.lyrics != null) {
                    cap.updateLyrics(data.lyrics);
                }
                cap.updateMusicCard();
            }
            return;
        }
        // v222: a live-activity same-key update refreshes the
        // capsule in place — rebuildDock already rebound it with
        // the new text, so skip the pop-in animation (like a music
        // update).
        if (isLiveUpdate) {
            return;
        }
        // v534: 气泡提示音——走到这里的都是真弹出（更新刷新已在
        // 上面 return）。只给普通气泡响一声：音乐/下载/实况/
        // 导航/来电这些特殊胶囊各有自己的存在感，不响（wei 定）。
        // 开关、节流、静音/勿扰判断都在 BubbleSound 里。场景/
        // 静默排队时气泡根本走不到这里；补弹时逐条走 popCapsule，
        // 按同一规则响（节流保证一波只响第一声）。
        // v535: 带上包名——按应用规则（静音/指定音效）在
        // BubbleSound.play 里查 SoundRules 定夺。
        if (!data.isMusic && !data.isDownload && !data.isLive
                && !data.isNavi && !data.isBus && !data.isCall) {
            BubbleSound.play(app, data.pkg);
        }
        // v244: a verification code auto-copies on pop (user
        // 2026-11-21 "验证码这种就自动复制"). Pickup codes do
        // not copy — they are for showing at the counter. This
        // runs only for a real pop (dedup above already dropped
        // duplicates), so one notification copies exactly once.
        if (data.code != null && !data.isPickupCode) {
            autoCopyVerifyCode(data.code);
        }
        // Pop-in on the group's top capsule (newest, on top of the
        // stack). v413: the 1.x pop throttle (same app within 30s,
        // or any notification within 5s) used to skip the pop-in
        // animation entirely, so a re-pop just appeared with no
        // motion ("突然出现", user 2026-11-22). Now every new
        // capsule slides in (see below); the notification still
        // joins its group/stack either way.
        long now = System.currentTimeMillis();
        CapsuleView cap = topCapsule(group);
        // v360: 大卡片模式重做（用户 2026-11-22 “重新做一套”）——
        // 普通通知不再走 openCard，直接以放大胶囊（卡片内容）依次
        // 排列（rebuildDock 里按卡片宽/高渲染）；音乐仍用展开卡片
        // （用户 “音乐卡的大卡片就用展开卡片”）。v359 的自成一体
        // 不变：不依赖“展开卡片”/音乐卡片开关。节流、已有卡片开
        // 着都不挡；来电不走卡片，照常弹胶囊。
        if (FeatureFlags.bigCard && cap != null
                && (data == null || !data.isCall)) {
            lastPopByPkg.put(data.pkg, now);
            lastPopAt = now;
            if (data != null && data.isMusic) {
                // 音乐：展开卡片（播放器卡片），左滑弹入。
                if (!cardAnimating) {
                    bigCardDirectEntrance = true;
                    openCard(cap, data);
                } else {
                    // 卡片动画进行中：先把胶囊藏起来（不弹胶囊），等
                    // 动画结束再开卡片；还打不开就恢复显示，别丢通知。
                    final CapsuleView pendingCap = cap;
                    final NotifData pendingData = data;
                    pendingCap.setAlpha(0f);
                    if (dock != null) {
                        dock.postDelayed(new Runnable() {
                            @Override
                            public void run() {
                                if (cardAnimating) {
                                    pendingCap.setAlpha(1f);
                                    return;
                                }
                                bigCardDirectEntrance = true;
                                openCard(pendingCap, pendingData);
                            }
                        }, 350);
                    } else {
                        pendingCap.setAlpha(1f);
                    }
                }
            } else {
                // 普通通知：放大胶囊，左滑弹入。已有卡片（音乐卡）
                // 开着时先把它收回成胶囊，再弹新的大胶囊。
                if (!cardAnimating) {
                    if (cardKey != null) {
                        closeCardInstant();
                        cap = topCapsule(group);
                    }
                    popInBigCapsule(cap);
                } else {
                    final CapsuleView pendingCap = cap;
                    final NotifData pendingData = data;
                    pendingCap.setAlpha(0f);
                    if (dock != null) {
                        dock.postDelayed(new Runnable() {
                            @Override
                            public void run() {
                                if (cardAnimating) {
                                    pendingCap.setAlpha(1f);
                                    return;
                                }
                                if (cardKey != null) {
                                    closeCardInstant();
                                }
                                if (pendingData == null
                                        || pendingData.key == null) {
                                    return;
                                }
                                CapsuleView fresh = findCapsuleByKey(
                                        pendingData.key);
                                popInBigCapsule(fresh);
                            }
                        }, 350);
                    } else {
                        pendingCap.setAlpha(1f);
                    }
                }
            }
        } else {
            lastPopByPkg.put(data.pkg, now);
            lastPopAt = now;
            if (cap != null) {
                // New notifications pop out from the docked direction too
                // (user 2026-11-19 "新通知也是这么弹出来"): the capsule
                // slides in from the left edge, not scaling in place.
                // v413 (user 2026-11-22 "都改成滑入 / 贴边滑入"): this
                // slide-in used to be gated by the pop throttle, so a
                // re-pop appeared with no motion at all ("突然出现").
                // Now it always runs — every new capsule slides in
                // from off-screen left to its edge position.
                cap.setTranslationX(-dp(200));
                cap.setAlpha(0f);
                cap.animate().translationX(0f).alpha(1f)
                        .setDuration(240).withLayer().start();
            }
        }
        // v193 (user 2026-11-20 "新通知3s自动消失取消掉"): the
        // 3s auto-dock is REMOVED. A new notification stays out
        // until the user deals with it — tap outside to dissipate,
        // left-swipe to dock, right-swipe to dismiss. Nothing
        // auto-disappears any more.
    }

    /**
     * Live lyric update (v76). Called from XposedEntry when the
     * module process's MediaSession listener reports a new lyric
     * line for a pinned music capsule. Updates the stored lyric (so
     * a rebuild keeps it) and the visible capsule's lyric line.
     * Runs on the UI thread via handler.
     */
    /** Incoming / ongoing call (v103). Called from XposedEntry on
     * ACTION_CALL. RINGING pops the green call capsule (auto-pinned,
     * no pin icon); OFFHOOK starts the timer; IDLE removes it. */
    // v129: the fourth argument is the REAL system notification
    // key of this call (from NotificationService), not CALL_KEY.
    // v130: the last two are the speaker PendingIntents handed
    // over by the module (may be null until the ongoing
    // notification arrives).
    public void onCall(final String state, final String name,
            final String number, final String notifKey,
            final android.app.PendingIntent spkOn,
            final android.app.PendingIntent spkOff) {
        if (state == null) {
            return;
        }
        handler.post(new Runnable() {
            @Override
            public void run() {
                // v132: store the speaker intents FIRST, before
                // any early return. The system re-posts the call
                // notification after answering; its EXTRA_TEXT is
                // the call duration, not the number, so the module
                // falls through to the "new call" branch and
                // re-sends RINGING — and the v113 guard below
                // returns early on RINGING while offhook,
                // discarding the intents that RINGING carried.
                // Storing them here means the toggle always has
                // the refreshed notification's speaker action,
                // whichever state the broadcast claims.
                if (spkOn != null) {
                    callSpeakerOnIntent = spkOn;
                }
                if (spkOff != null) {
                    callSpeakerOffIntent = spkOff;
                }
                if ("ringing".equals(state)) {
                    // v124 (v122 onCallBroadcast): first drop a
                    // stale call state left by a previous call,
                    // then only ring when not already in a call.
                    // If the state machine says OFFHOOK, this is
                    // the system re-posting the call notification
                    // after answering — a duplicate that must not
                    // wipe the answered state (speaker, timer,
                    // button).
                    dropStaleCallState();
                    if ("offhook".equals(callState)) {
                        return;
                    }
                    callState = "ringing";
                    callSpeaker = false;
                    callNumber = number;
                    callKey = notifKey;
                    stopCallTick();
                    String title = name != null && !name.isEmpty()
                            ? name : number;
                    // v395: no name → the number is the title
                    // ONLY. v389 set text=number too, so the
                    // number showed on BOTH lines (video: two
                    // rows of "188 8320 4388"); the user asked
                    // for just the number, bigger, centred
                    // ("就号码加大居中"). With a name, text is
                    // the number (second line); without one it
                    // stays empty and the title centres alone.
                    String text = (name != null && !name.isEmpty()
                            && number != null) ? number : "";
                    NotifData d = new NotifData(CALL_KEY, CALL_PKG,
                            title, text, System.currentTimeMillis(),
                            null, null, false, null, false, true);
                    popCallCapsule(d);
                } else if ("offhook".equals(state)) {
                    // v129: the notification refreshes once after
                    // answering (incoming -> ongoing) and can carry
                    // a new key. Take the fresh key here so the
                    // speaker toggle fires the refreshed
                    // notification's action.
                    if (notifKey != null && !notifKey.isEmpty()) {
                        callKey = notifKey;
                    }
                    if ("ringing".equals(callState)) {
                        enterOffhook();
                    } else if ("offhook".equals(callState)) {
                        updateCallTimer();
                    }
                } else if ("idle".equals(state)) {
                    if (!"idle".equals(callState)) {
                        endCallUi();
                    }
                }
            }
        });
    }

    /** Pop the call capsule: its own group, auto-pinned (no pin icon),
     * no 3s auto-dock, no pop throttle. */
    private void popCallCapsule(NotifData d) {
        if (dock == null) {
            return;
        }
        // v395: do NOT call removeCallCapsule() here. It removes
        // the call group and rebuilds the dock WHILE the group is
        // gone — rebuildDock ends in syncCallWindow, which finds
        // no call and hideCallWindow() DESTROYS the capsule being
        // dragged (the video "vanish on drag start": the call
        // notification re-posts, the old capsule is removed, a
        // new one is created, the drag state is lost). Remove the
        // old group silently and rebuild ONCE below, after the new
        // group is in place, so syncCallWindow finds the call and
        // showCallOnRight keeps the existing CapsuleView.
        // v414: remember whether a call was already showing, so a
        // RINGING re-post (same call, capsule already on screen)
        // does not re-trigger the slide-in below.
        boolean hadCall = false;
        for (int i = groups.size() - 1; i >= 0; i--) {
            NotifGroup g = groups.get(i);
            if (g != null && CALL_PKG.equals(g.pkg)) {
                // v443: 真来电顶掉模拟胶囊时，把模拟标记清掉，
                // 否则开关状态和实际显示对不上。
                for (NotifData item : g.items) {
                    if (item != null && SIM_CALL_KEY.equals(item.key)) {
                        simCallShowing = false;
                        break;
                    }
                }
                groups.remove(i);
                hadCall = true;
            }
        }
        NotifGroup group = new NotifGroup(CALL_PKG);
        group.items.add(d);
        group.pinned = true;
        group.pinnedAt = System.currentTimeMillis();
        group.pinnedAtByKey.put(d.key, System.currentTimeMillis());
        groups.add(0, group);
        rebuildDock();
        // v414: left-dock call capsule slides in from the left edge
        // (user 2026-11-22 "左边来电胶囊还是直接出现的 / 右边正常").
        // Calls bypass popCapsule entirely, so the left capsule
        // never got the slide-in — rebuildDock just creates it and
        // it appears with no motion. Only slide in for a genuinely
        // new call (hadCall == false); a RINGING re-post recreates
        // the capsule too, but it is already on screen. When
        // callRight is on, syncCallWindow (end of rebuildDock)
        // already slid it in on the right via showCallOnRight.
        if (!hadCall && !FeatureFlags.callRight) {
            CapsuleView cap = topCapsule(group);
            if (cap != null) {
                cap.setTranslationX(-dp(260));
                cap.setAlpha(0f);
                cap.animate().translationX(0f).alpha(1f)
                        .setDuration(240).withLayer().start();
            }
        }
    }

    private void removeCallCapsule() {
        boolean changed = false;
        for (int i = groups.size() - 1; i >= 0; i--) {
            NotifGroup g = groups.get(i);
            if (g != null && CALL_PKG.equals(g.pkg)) {
                groups.remove(i);
                changed = true;
            }
        }
        if (changed) {
            rebuildDock();
        }
    }

    /** v507: app rules just reloaded — evict capsules that are
     * already on screen for apps now ruled NEVER. The popCapsule
     * gate only stops NEW pops; a music capsule already showing
     * kept refreshing in place via MediaSession updates and
     * looked like the rule did nothing (user 2026-11-24). Also
     * clears the app's docked (pile) group. Calls are exempt,
     * matching every other rule path. */
    public void evictNeverRuled() {
        handler.post(new Runnable() {
            @Override
            public void run() {
                try {
                    boolean changed = false;
                    for (int i = groups.size() - 1; i >= 0; i--) {
                        NotifGroup g = groups.get(i);
                        if (g != null && g.pkg != null
                                && !CALL_PKG.equals(g.pkg)
                                && AppRules.modeFor(g.pkg)
                                        == AppRules.NEVER) {
                            groups.remove(i);
                            changed = true;
                        }
                    }
                    java.util.Iterator<String> it =
                            dockedGroups.keySet().iterator();
                    while (it.hasNext()) {
                        String pkg = it.next();
                        if (pkg != null
                                && AppRules.modeFor(pkg)
                                        == AppRules.NEVER) {
                            it.remove();
                            if (pile != null) {
                                pile.removeIconForPkg(pkg);
                            }
                        }
                    }
                    if (changed) {
                        rebuildDock();
                    }
                } catch (Throwable ignored) {
                }
            }
        });
    }

    /** v443: 模拟来电开关（APP「来电」卡片里）——弹一颗假来电
     * 胶囊，让用户看着调来电高度、测位置到没到位。不用真打
     * 电话。开 = 弹，关 = 收。只响应开关翻动，重启不自动弹。 */
    public void setSimCall(boolean on) {
        if (on) {
            showSimCall();
        } else {
            hideSimCall();
        }
    }

    private void showSimCall() {
        if (dock == null || simCallShowing) {
            return;
        }
        // 真来电优先：正在响铃 / 通话中，不弹模拟的。
        if ("ringing".equals(callState) || "offhook".equals(callState)) {
            return;
        }
        // 假数据：号码只做显示，没有任何通知动作。
        NotifData d = new NotifData(SIM_CALL_KEY, CALL_PKG,
                "138 0000 0000", "", System.currentTimeMillis(),
                null, null, false, null, false, true);
        simCallShowing = true;
        popCallCapsule(d);
    }

    private void hideSimCall() {
        if (!simCallShowing) {
            return;
        }
        simCallShowing = false;
        // 只撤模拟那组（按 SIM_CALL_KEY 认），别动真来电。
        boolean changed = false;
        for (int i = groups.size() - 1; i >= 0; i--) {
            NotifGroup g = groups.get(i);
            if (g == null || !CALL_PKG.equals(g.pkg)) {
                continue;
            }
            boolean hasSim = false;
            for (NotifData item : g.items) {
                if (item != null && SIM_CALL_KEY.equals(item.key)) {
                    hasSim = true;
                    break;
                }
            }
            if (hasSim) {
                groups.remove(i);
                changed = true;
            }
        }
        if (changed) {
            rebuildDock();
        }
    }

    /** v459: 模拟音乐开关（APP「音乐胶囊」卡片里）——弹一颗假音乐
     * 胶囊，让用户看着调音乐胶囊/卡片大小。不用真放音乐。开 = 弹，
     * 关 = 收。只响应开关翻动，重启不自动弹。 */
    public void setSimMusic(boolean on) {
        if (on) {
            showSimMusic();
        } else {
            hideSimMusic();
        }
    }

    private void showSimMusic() {
        if (dock == null) {
            return;
        }
        // 已在屏上就别再弹（按实际在屏状态认，不只认标记——用户
        // 手滑把假胶囊消散掉时标记还留着，再开开关要能重新弹）。
        if (findCapsuleByKey(SIM_MUSIC_KEY) != null) {
            simMusicShowing = true;
            return;
        }
        // 假数据：歌名/歌手/歌词只做显示，没有任何通知动作，也没有
        // MediaSession——卡片上的播放控制是摆设（bindCardCapsule
        // 里对 SIM_MUSIC_KEY 接了空操作，不碰真音乐）。
        NotifData d = new NotifData(SIM_MUSIC_KEY, SIM_MUSIC_PKG,
                "晴天", "周杰伦", System.currentTimeMillis(),
                null, null, true, "从前从前有个人爱你很久");
        simMusicShowing = true;
        popCapsule(d);
    }

    private void hideSimMusic() {
        if (!simMusicShowing
                && findCapsuleByKey(SIM_MUSIC_KEY) == null) {
            return;
        }
        simMusicShowing = false;
        // 假卡片还开着就先关掉，否则卡片会指着一个已撤的胶囊。
        if (SIM_MUSIC_KEY.equals(cardKey)) {
            closeCard();
        }
        // 只摘模拟那一条（按 SIM_MUSIC_KEY 认）——真 QQ Flyme
        // 胶囊可能在同一组里，别动它；组空了才整组撤掉。
        boolean changed = false;
        for (int i = groups.size() - 1; i >= 0; i--) {
            NotifGroup g = groups.get(i);
            if (g == null || g.items == null) {
                continue;
            }
            boolean removed = false;
            for (int j = g.items.size() - 1; j >= 0; j--) {
                NotifData item = g.items.get(j);
                if (item != null && SIM_MUSIC_KEY.equals(item.key)) {
                    g.items.remove(j);
                    removed = true;
                }
            }
            if (removed) {
                changed = true;
                if (g.items.isEmpty()) {
                    groups.remove(i);
                }
            }
        }
        if (changed) {
            rebuildDock();
        }
    }

    /** v459: 扇子自动变淡开关（APP「扇子自动变淡」卡片）。开着：
     * 扇子出现后 5 秒没点就变半透明；关着：扇子一直不透明，正在
     * 变淡的也恢复。只响应开关翻动。 */
    public void setFanAutoDim(boolean on) {
        if (on) {
            scheduleFanDim();
        } else {
            cancelFanDim();
        }
    }

    /** v459: 扇子出现/更新后开始 5 秒闲置计时——5 秒内没点就变
     * 半透明。开关关着不计时；扇子空着不计时。每次有新图标进来
     * 都重计时（先取消变淡、恢复不透明，再重新开始 5 秒）。 */
    private void scheduleFanDim() {
        if (!FeatureFlags.fanAutoDim || pile == null) {
            return;
        }
        if (fanDimRunnable != null) {
            handler.removeCallbacks(fanDimRunnable);
        }
        if (pile.getIconCount() <= 0) {
            return;
        }
        if (fanDimRunnable == null) {
            fanDimRunnable = new Runnable() {
                @Override
                public void run() {
                    if (!FeatureFlags.fanAutoDim || pile == null
                            || pile.getIconCount() <= 0) {
                        return;
                    }
                    fanDimmed = true;
                    pile.setDimmed(true);
                }
            };
        }
        handler.postDelayed(fanDimRunnable, 5000L);
    }

    /** v459: 取消变淡计时，并把已变淡的扇子恢复不透明。扇子被点
     * （展开）、有新图标进来、开关关掉时调。 */
    private void cancelFanDim() {
        if (fanDimRunnable != null) {
            handler.removeCallbacks(fanDimRunnable);
        }
        if (fanDimmed) {
            fanDimmed = false;
            if (pile != null) {
                pile.setDimmed(false);
            }
        }
    }

    /** Apply the desired speaker state to the AudioManager (v111,
     * 1.x CallBubble.applySpeaker). Called at answer time, from the
     * phone-state listener when the call really connects, and from
     * the timer toggle. */
    // v123: the AudioManager calls from SystemUI have no effect
    // (v122 "无效"). Set the route from the incallui process too —
    // the process that owns the call. Send a broadcast; InCallUiHide
    // applies it there with the incallui Application context. Keep
    // the local SystemUI calls as well.
    /** v127: apply the speaker for the toggle. Primary: send
     * the route to the incallui process (which owns the call)
     * via ACTION_CALL_AUDIO — InCallUiHide applies it there with
     * setCommunicationDevice + setSpeakerphoneOn. Backup: also
     * fire the notification's own "打开扬声器" / "关闭扬声器"
     * action via the module (v125). The direct SystemUI
     * setSpeakerphoneOn never worked (1.x is also wrong). */
    private void applyCallSpeaker() {
        // v139/v140: one tap = one toggle, via the fresh lookup
        // only. The pre-extracted PendingIntent (v130) goes stale
        // (tap 3 fired a dead intent), so v139 dropped it. The
        // ACTION_CALL_AUDIO broadcast (v127) ALSO toggled the
        // speaker a second time per tap — one sets, one toggles —
        // so a tap could flip twice and land back where it started
        // ("好像一直再点"). The diagnostic fires the notification
        // action exactly once per tap; do the same: only
        // sendCallControl, and let the module (fireCallAction,
        // v138) fire the live action once.
        sendCallControl(callSpeaker ? "speaker_on" : "speaker_off");
    }

    /** v117 (1.x CallBubble.handlePhoneState): the telephony
     * listener moves the state machine. RINGING only from IDLE;
     * OFFHOOK from RINGING runs enterOffhook; OFFHOOK while
     * already offhook re-applies the speaker; IDLE ends the UI. */
    private void handlePhoneState(int state, String number) {
        try {
            switch (state) {
                case android.telephony.TelephonyManager.CALL_STATE_IDLE:
                    if (!"idle".equals(callState)) {
                        endCallUi();
                    }
                    break;
                case android.telephony.TelephonyManager.CALL_STATE_RINGING:
                    dropStaleCallState();
                    if ("idle".equals(callState)) {
                        callState = "ringing";
                    }
                    break;
                case android.telephony.TelephonyManager.CALL_STATE_OFFHOOK:
                    if ("ringing".equals(callState)) {
                        enterOffhook();
                    } else if ("offhook".equals(callState)) {
                        // v141: no speaker touch here. applyCallSpeaker
                        // now fires the TOGGLE action, so re-applying
                        // on every OFFHOOK event would flip it again.
                        updateCallTimer();
                    }
                    break;
                default:
                    break;
            }
        } catch (Throwable ignored) {
        }
    }

    /** v117 (1.x CallBubble.enterOffhook): the single transition
     * into the ongoing call. Sets the state machine to OFFHOOK,
     * records the start time once, applies the speaker if the
     * user answered with speaker, starts the tick and switches
     * the button to the timer. Called from answerCall AND from
     * handlePhoneState when the real OFFHOOK is observed. */
    private void enterOffhook() {
        try {
            boolean wasNotOffhook = !"offhook".equals(callState);
            callState = "offhook";
            if (wasNotOffhook) {
                callStartTime = android.os.SystemClock.elapsedRealtime();
            }
            // v141: no speaker touch here either. The user answers
            // with the earpiece (callSpeaker is false at answer,
            // v126) and taps the timer to switch; firing the toggle
            // here would flip it once more.
            startCallTick();
            updateCallTimer();
        } catch (Throwable ignored) {
        }
    }

    /** v117 (1.x endCallUi path): clear the state machine, stop
     * the tick and remove the capsule. */
    private void endCallUi() {
        callState = "idle";
        callSpeaker = false;
        callNumber = null;
        stopCallTick();
        removeCallCapsule();
    }

    /** Answer via TelecomManager (1.x CallBubble.answerCall). Runs in
     * SystemUI, which holds the permission. */
    /** v126 (user: one tap = normal answer): answer with the
     * direct TelecomManager.acceptRingingCall() and force the
     * speaker off — a normal earpiece answer. The v125
     * notification-action answer does not fire on this Flyme
     * (button does nothing), so the direct call is back for
     * answering. enterOffhook starts the timer; since
     * callSpeaker is false it does not touch the speaker. */
    public void answerCall(final boolean speaker) {
        try {
            android.telecom.TelecomManager tm =
                    (android.telecom.TelecomManager) app.getSystemService(
                            android.content.Context.TELECOM_SERVICE);
            if (tm != null) {
                tm.acceptRingingCall();
            }
        } catch (Throwable ignored) {
        }
        callSpeaker = false;
        enterOffhook();
    }

    /** Bind the call actions on a call capsule (v117): the answer
     * button, hangup and speaker toggle. Used in Phase 1 AND
     * Phase 2, so the button works even after the call capsule
     * is unpinned (it then renders in Phase 2, which previously
     * never bound these callbacks). */
    private void bindCallCapsule(final CapsuleView cap) {
        // v443: 模拟来电胶囊（测试用）——接听/挂断/扬声器都不许碰
        // 真电话状态（否则点一下接听会把模块推进 offhook）。
        // 点接听或挂断就收起预览，扬声器点它没用。
        NotifData cd = cap.getData();
        if (cd != null && SIM_CALL_KEY.equals(cd.key)) {
            cap.setOnAnswer(new Runnable() {
                @Override
                public void run() {
                    hideSimCall();
                }
            });
            cap.setOnHangup(new Runnable() {
                @Override
                public void run() {
                    hideSimCall();
                }
            });
            cap.setOnToggleSpeaker(new Runnable() {
                @Override
                public void run() {
                }
            });
            return;
        }
        cap.setOnAnswer(new Runnable() {
            @Override
            public void run() {
                // v126: one tap = normal (earpiece) answer.
                answerCall(false);
            }
        });
        cap.setOnHangup(new Runnable() {
            @Override
            public void run() {
                hangupCallWithParticles();
            }
        });
        cap.setOnToggleSpeaker(new Runnable() {
            @Override
            public void run() {
                toggleCallSpeaker();
            }
        });
    }

    /** Toggle speakerphone mid-call (v108): tapping the timer
     * switches between the handset and speaker timer states. */
    public void toggleCallSpeaker() {
        callSpeaker = !callSpeaker;
        applyCallSpeaker();
        // v116: 1.x toggles the speaker icon in place
        // (toggleSpeaker -> refreshLevelContent); no dissipate.
        // v376: the call capsule may live in the right window.
        CapsuleView cap = findCallCapsule();
        if (cap != null) {
            cap.setCallSpeaker(callSpeaker);
        }
    }

    public void endCall() {
        // v128: hang up with the direct TelecomManager.endCall()
        // again. The v125 notification-action hangup never fires
        // on this Flyme, so the swipe/button did nothing.
        try {
            android.telecom.TelecomManager tm =
                    (android.telecom.TelecomManager) app.getSystemService(
                            android.content.Context.TELECOM_SERVICE);
            if (tm != null) {
                tm.endCall();
            }
        } catch (Throwable ignored) {
        }
        endCallUi();
    }

    /** Hang up from a right swipe, with the particle burst (v111,
     * user "右滑挂断也没有粒子特效"). The phone call ends at once;
     * the capsule bursts into particles and is removed 300ms later,
     * after the burst plays — rebuildDock() clears dock, which
     * would kill the particles instantly if we removed now (same
     * delay as dissipateOne). */
    public void hangupCallWithParticles() {
        // v128: hang up with the direct TelecomManager.endCall()
        // again (see endCall). The right swipe still calls this
        // via onHangup — the gesture was never deleted, only its
        // mechanism was swapped to the notification action in
        // v125, which never fires here.
        try {
            android.telecom.TelecomManager tm =
                    (android.telecom.TelecomManager) app.getSystemService(
                            android.content.Context.TELECOM_SERVICE);
            if (tm != null) {
                tm.endCall();
            }
        } catch (Throwable ignored) {
        }
        callState = "idle";
        callSpeaker = false;
        callNumber = null;
        stopCallTick();
        // v376: the call capsule may live in the right window.
        CapsuleView callCap = findCallCapsule();
        if (callCap != null) {
            burstParticles(callCap);
        }
        handler.postDelayed(new Runnable() {
            @Override
            public void run() {
                removeCallCapsule();
            }
        }, 300);
    }

    private void startCallTick() {
        try {
            // v118: do NOT call stopCallTick() here — it resets
            // callStartTime to 0, wiping the start time that
            // enterOffhook() had just set. Clear only the old
            // callbacks so the new tick keeps the live start time.
            try {
                if (callTickHandler != null && callTickRunnable != null) {
                    callTickHandler.removeCallbacks(callTickRunnable);
                }
            } catch (Throwable ignored) {
            }
            callTickRunnable = null;
            if (callTickHandler == null) {
                callTickHandler = new android.os.Handler(
                        android.os.Looper.getMainLooper());
            }
            callTickRunnable = new Runnable() {
                @Override
                public void run() {
                    try {
                        // v141: the tick only updates the timer. It
                        // used to re-apply the speaker every second
                        // (v112, when applyCallSpeaker SET the
                        // speaker and was idempotent). Now
                        // applyCallSpeaker fires the TOGGLE action,
                        // so the tick was toggling the speaker once
                        // a second — "一直再自动点击扬声器". Only
                        // the user's tap (toggleCallSpeaker) fires
                        // the toggle now.
                        updateCallTimer();
                    } catch (Throwable ignored) {
                    } finally {
                        try {
                            if (callTickHandler != null
                                    && callTickRunnable != null
                                    && callStartTime > 0) {
                                callTickHandler.postDelayed(
                                        callTickRunnable, 1000);
                            }
                        } catch (Throwable ignored) {
                        }
                    }
                }
            };
            callTickHandler.postDelayed(callTickRunnable, 1000);
        } catch (Throwable ignored) {
        }
    }

    private void stopCallTick() {
        callStartTime = 0;
        try {
            if (callTickHandler != null && callTickRunnable != null) {
                callTickHandler.removeCallbacks(callTickRunnable);
            }
        } catch (Throwable ignored) {
        }
        callTickRunnable = null;
    }

    private void updateCallTimer() {
        if (callStartTime <= 0) {
            return;
        }
        long secs = (android.os.SystemClock.elapsedRealtime()
                - callStartTime) / 1000;
        String timer = (secs / 60) + ":"
                + String.format("%02d", secs % 60);
        // v376: the call capsule may live in the right window.
        CapsuleView cap = findCallCapsule();
        if (cap != null) {
            cap.setCallTimer(timer, callSpeaker);
        } else {
            // v448 diag: tick 跑了但找不到胶囊——setCallTimer
            // 根本没人调，pill 永远缩不了。
            FileLog.i("CallTimerDiag updateCallTimer NO CAP"
                    + " timer=" + timer
                    + " callCapView=" + (callCapView != null)
                    + " dockChildren=" + (dock == null
                            ? -1 : dock.getChildCount()));
        }
    }

    /** Live download progress (v95, v98, v99): update the capsule's
     * lines in place (same key), so the percent ticks up in real time.
     * Title is "正在下载 45%", text is the content. */
    public void updateDownloadProgress(final String key,
            final String title, final String text, final int pct,
            final boolean completed) {
        if (key == null || title == null || dock == null) {
            return;
        }
        handler.post(new Runnable() {
            @Override
            public void run() {
                for (int i = 0; i < dock.getChildCount(); i++) {
                    View v = dock.getChildAt(i);
                    if (v instanceof CapsuleView) {
                        CapsuleView cap = (CapsuleView) v;
                        NotifData d = cap.getData();
                        if (d != null && key.equals(d.key)) {
                            cap.updateTitle(title);
                            if (text != null) {
                                cap.updateText(text);
                            }
                            // v239: capsule bottom progress bar.
                            cap.updateDownloadBar(pct);
                        }
                    }
                }
                // v242: completed — show "下载完成" / "安装完成"
                // for 3s, then drop the capsule. Guarded: only
                // remove if it still shows the completed text,
                // so a new download reusing the key is safe.
                if (completed) {
                    final String ckey = key;
                    handler.postDelayed(new Runnable() {
                        @Override
                        public void run() {
                            if (dock == null) {
                                return;
                            }
                            for (int j = 0;
                                    j < dock.getChildCount(); j++) {
                                View cv = dock.getChildAt(j);
                                if (cv instanceof CapsuleView) {
                                    CapsuleView cap =
                                            (CapsuleView) cv;
                                    NotifData dd = cap.getData();
                                    // v319: lenient completion guard.
                                    // The old exact-equals check
                                    // ("下载完成"/"安装完成") failed
                                    // whenever the hook re-posted the
                                    // completion notification with a
                                    // different text (filename, "已下载"
                                    // ...) after completed=true arrived
                                    // but before this 3s check ran, so
                                    // the capsule got stuck. Accept any
                                    // completion-like text, or a full
                                    // bar (pct 100 was set by the
                                    // completed broadcast). A new
                                    // download reusing the key has
                                    // pct < 100 and non-completion
                                    // text, so it stays safe.
                                    String dt = dd != null ? dd.text : null;
                                    boolean doneText = dt != null
                                            && (dt.contains("下载完成")
                                                    || dt.contains("安装完成")
                                                    || dt.contains("已完成")
                                                    || dt.contains("下载完毕")
                                                    || dt.contains("已下载")
                                                    || dt.contains("下载成功"));
                                    if (dd != null
                                            && ckey.equals(dd.key)
                                            && dd.isDownload
                                            && (doneText
                                                    || dd.downloadPct >= 100)) {
                                        onNotificationRemoved(ckey);
                                        break;
                                    }
                                }
                            }
                        }
                    }, 3000);
                }
            }
        });
    }

    public void updateMusicLyric(final String key, final String lyric) {
        if (key == null || lyric == null) {
            return;
        }
        handler.post(new Runnable() {
            @Override
            public void run() {
                if (dock == null) {
                    return;
                }
                for (NotifGroup g : groups) {
                    for (NotifData d : g.items) {
                        if (d != null && key.equals(d.key)) {
                            d.lyrics = lyric;
                        }
                    }
                }
                for (int i = 0; i < dock.getChildCount(); i++) {
                    android.view.View v = dock.getChildAt(i);
                    if (v instanceof CapsuleView) {
                        CapsuleView cap = (CapsuleView) v;
                        NotifData d = cap.getData();
                        if (d != null && key.equals(d.key)) {
                            cap.updateLyrics(lyric);
                        }
                    }
                }
            }
        });
    }

    /** The top capsule of a group (newest, drawn on top of the stack). */
    private CapsuleView topCapsule(NotifGroup group) {
        if (dock == null || group.latest() == null) {
            return null;
        }
        String key = group.latest().key;
        for (int i = 0; i < dock.getChildCount(); i++) {
            View v = dock.getChildAt(i);
            if (v instanceof CapsuleView) {
                CapsuleView c = (CapsuleView) v;
                if (c.getTag() == group && c.getData() != null
                        && key != null && key.equals(c.getData().key)) {
                    return c;
                }
            }
        }
        return null;
    }

    /**
     * Rebuild the dock from the groups. Same-app notifications use the
     * v1 shrink CASCADE stack: the capsules overlap, each one 6dp below
     * the previous, newest on top, so each older capsule shows a 6dp
     * edge (user 2026-11-18 "不是这种叠加，是版本1收缩那种叠加").
     * Tapping a stack peels it out DOWNWARD: each notification becomes
     * its own capsule below, newest first. Different apps are regular
     * parallel rows, one per group, newest group on top.
     */
    /** v438: APP 外观滑块动了（弹出/扇子位置）—— SystemUI 侧
     * 已更新 Appearance 缓存，这里重排一次立即生效，不用重启。 */
    public void onAppearanceChanged() {
        // v462（用户「卡片高度为什么无法缩小」）：卡片高度只在点
        // 开时量一次、存在 cardHeightPx 里，之后外观一变
        // rebuildDock 用的还是缓存的旧高度——歌词行数 / 比例
        // 改了，卡片也不跟着变矮。所以外观变了、又有卡片开着
        // 时，先按当前外观重新量一遍卡片高（跟点开时一个量法，
        // 用个临时 view 量），再重排。没卡片开着不用量。
        if (cardKey != null && dock != null) {
            try {
                NotifData cardData = null;
                for (NotifGroup g : groups) {
                    if (g == null) {
                        continue;
                    }
                    for (NotifData d : g.items) {
                        if (d != null && cardKey.equals(d.key)) {
                            cardData = d;
                            break;
                        }
                    }
                    if (cardData != null) {
                        break;
                    }
                }
                if (cardData != null) {
                    CapsuleView scratch = new CapsuleView(app);
                    scratch.bind(cardData);
                    scratch.setCardExpanded(true);
                    cardHeightPx = scratch.cardHeightForWidth(
                            dp(CapsuleView.cardWidthDp(app)));
                }
            } catch (Throwable ignored) {
                // 量失败就用旧高度，不炸。
            }
        }
        rebuildDock();
    }

    /** v444: 锚点拖动——长按胶囊拖整组。只认左贴边、没展开成
     * 卡片的胶囊（右来电、卡片不拖；扇子图标长按还是换位）。
     * 开始拖：记下手指起点 Y 和当前锚点；拖动中：手指竖着走
     * 多少，锚点就跟着走多少（delta 映射），整组走滑块那条路；
     * 松手：把最后的锚点落盘 + 发广播。 */
    public boolean isAnchorDragActive() {
        return anchorDragActive;
    }

    public void startAnchorDrag(CapsuleView cap) {
        if (cap == null || anchorDragActive) {
            return;
        }
        if (!cap.isLeftDock() || cap.isCardExpanded()) {
            return;
        }
        anchorDragActive = true;
        anchorDragStartY = cap.getDownY();
        anchorDragStartPct = Appearance.dockTopPct;
        // 扇子相对锚点的偏移（拖动中胶囊布局不变，这个偏移是常量，
        // 扇子就按它跟着锚点走）。
        anchorDragPileRelY = pileTargetY - Appearance.dockTopPx(app);
    }

    public void updateAnchorDrag(float rawY) {
        if (!anchorDragActive) {
            return;
        }
        int sh = 0;
        try {
            sh = app.getResources().getDisplayMetrics().heightPixels;
        } catch (Throwable ignored) {
        }
        if (sh <= 0) {
            return;
        }
        float deltaPx = rawY - anchorDragStartY;
        int pct = anchorDragStartPct
                + Math.round(deltaPx / sh * 100f);
        if (pct < 0) {
            pct = 0;
        }
        if (pct > Appearance.MAX_DOCK_PCT) {
            pct = Appearance.MAX_DOCK_PCT;
        }
        if (pct != Appearance.dockTopPct) {
            Appearance.setDock(pct);
            // 只挪窗口，不重建胶囊（见上面注释）。扇子窗的 Y =
            // 拖开始时的相对偏移 + 新锚点。
            pileTargetY = anchorDragPileRelY + Appearance.dockTopPx(app);
            updateWindowSize();
        }
    }

    public void endAnchorDrag() {
        if (!anchorDragActive) {
            return;
        }
        anchorDragActive = false;
        // 落盘（跟滑块松手一样：commit 同步写，杀进程也不丢），
        // 再发广播让其它地方同步。SystemUI 进程经
        // createPackageContext 写 APP 的 prefs（同 Appearance.load
        // 的读法）。
        final int pct = Appearance.dockTopPct;
        try {
            Context appCtx = app.createPackageContext(
                    "com.chenan.flymebobo",
                    Context.CONTEXT_IGNORE_SECURITY);
            appCtx.getSharedPreferences(Appearance.PREFS,
                    Context.MODE_PRIVATE)
                    .edit().putInt(Appearance.KEY_DOCK_PCT, pct).commit();
        } catch (Throwable ignored) {
        }
        try {
            Intent i = new Intent(
                    "com.chenan.flymebobo.APPEARANCE");
            i.putExtra("dockPct", pct);
            app.sendBroadcast(i);
        } catch (Throwable ignored) {
        }
    }

    private void rebuildDock() {
        if (dock == null) {
            return;
        }
        dock.removeAllViews();
        // v155: removeAllViews also drops the card tail view — the
        // cardTail reference would go stale (detached view) and
        // ensureCardTail would then refuse to create a new one, so
        // the tail went missing entirely (user "拖尾和卡片有缝隙").
        cardTail = null;
        NotifGroup fanAnchor = fanAnchorGroup();
        int fanAnchorBottom = 0;
        // v235: clear the frozen card top when no card is open
        // or a different card opens, so the new card takes its
        // own row top. While the same card stays open, the
        // frozen top is kept and reused below.
        if (cardKey == null || !cardKey.equals(frozenCardKey)) {
            frozenCardTopY = -1;
            frozenCardKey = null;
        }
        cardTopY = -1;
        int y = 0;

        // Phase 1 — pinned notifications, in pin-time order, each its
        // own row. Pinned notifications do NOT participate in group
        // arrangement (user 2026-11-19 "钉住的不参与分组排列" / "第一
        // 钉住就排到第一去，第二钉住就排到第二去"): the first pinned
        // goes to 1st place, the second pinned to 2nd place, by pin
        // time, regardless of app. Only after unpinning ("钉住回复")
        // do they rejoin the normal group arrangement.
        final java.util.List<NotifData> pinnedList =
                new java.util.ArrayList<>();
        final java.util.Map<NotifData, NotifGroup> pinnedGroup =
                new java.util.HashMap<>();
        final java.util.Map<NotifData, Long> pinnedTime =
                new java.util.HashMap<>();
        for (NotifGroup g : groups) {
            if (g == null) {
                continue;
            }
            for (NotifData d : g.items) {
                if (d != null && d.key != null
                        && g.pinnedAtByKey.containsKey(d.key)) {
                    pinnedList.add(d);
                    pinnedGroup.put(d, g);
                    pinnedTime.put(d, g.pinnedAtByKey.get(d.key));
                } else if (d != null && d.key != null
                        && stayPutPinTime.containsKey(d.key)) {
                    // v238: an unpinned card stays in place at
                    // its old pin-time row, as a capsule.
                    pinnedList.add(d);
                    pinnedGroup.put(d, g);
                    pinnedTime.put(d, stayPutPinTime.get(d.key));
                }
            }
        }
        java.util.Collections.sort(pinnedList,
                new java.util.Comparator<NotifData>() {
                    @Override
                    public int compare(NotifData a, NotifData b) {
                        Long ta = pinnedTime.get(a);
                        Long tb = pinnedTime.get(b);
                        long x = ta == null ? 0 : ta;
                        long z = tb == null ? 0 : tb;
                        return Long.compare(x, z);
                    }
                });
        // v180 (user 2026-11-20 "钉住卡片也立即排到第一位"): a
        // pinned card goes to FIRST position (row 0) immediately.
        // v152's fall-down (card below the pinned section) is gone.
        // The card is rendered first at y=0; the other pinned
        // capsules follow below it, in pin-time order, starting
        // under the card's height. Build a reordered list: card
        // first, then the rest in pin-time order.
        final java.util.List<NotifData> pinnedOrdered =
                new java.util.ArrayList<>();
        NotifData pinnedCardData = null;
        for (NotifData pd : pinnedList) {
            if (pd != null && isCardKey(pd.key)) {
                pinnedCardData = pd;
            }
        }
        if (pinnedCardData != null) {
            pinnedOrdered.add(pinnedCardData);
        }
        for (NotifData pd : pinnedList) {
            if (pd != null && pd != pinnedCardData) {
                pinnedOrdered.add(pd);
            }
        }
        for (int pi = 0; pi < pinnedOrdered.size(); pi++) {
            final NotifData d = pinnedOrdered.get(pi);
            final NotifGroup group = pinnedGroup.get(d);
            // v376: the call capsule lives in the right window when
            // the "来电放右边" switch is on — it takes no dock row.
            // (syncCallWindow, at the end of this rebuild, puts it
            // there; the pin state does not matter.)
            if (d.isCall && FeatureFlags.callRight) {
                continue;
            }
            CapsuleView cap = new CapsuleView(app);
            cap.setTag(group);
            cap.bind(d);
            // v238: a stay-put item (unpinned card) shows no pin
            // badge — it is no longer pinned, it just keeps its row.
            cap.setPinned(group != null && d.key != null
                    && group.pinnedAtByKey.containsKey(d.key));
            cap.setStacked(false);
            // Card (v143): this capsule is the open card — same view,
            // grown, with its buttons wired; it takes the card height
            // so the ones below start lower.
            // v236: a pinned item renders as a card when it is
            // the open card OR was pinned as a card
            // (pinnedCardKeys). Other cards opening must not
            // shrink it back to a capsule.
            final boolean isCardPinned = isCardKey(d.key)
                    || pinnedCardKeys.contains(d.key);
            // v365: 大卡片模式钉住不要变成胶囊 —— 钉住的大卡片
            // 还是大卡片（不是小胶囊）。用户 2026-11-22 05:14
            // "大卡片模式钉住不要变成胶囊"。只有普通通知放大，
            // 音乐/来电/下载/实况仍是小胶囊。
            final boolean isBigPinned = !isCardPinned
                    && isBigCapsule(d);
            // v237: this pinned card's own measured height.
            int pinnedCardH = 0;
            int bigPinnedH = 0;
            if (isCardPinned) {
                cap.setCardExpanded(true);
                // v180: pinned card goes to FIRST position (row 0).
                // v236: a pinned card that is not the open card
                // sits at its pin-order row (y), not forced to 0.
                // v237: measure THIS card's own height. The
                // shared cardHeightPx is the open card's height
                // and is reset to 0 when it closes; a pinned
                // card left behind would be squeezed to a 44dp
                // strip (video 01:27).
                // v517: measure BEFORE bindCardCapsule — the
                // open-card path (Phase 2) takes its height from
                // a scratch view measured before any card wiring
                // (showCardAfterRipple), so the pin badges are
                // still hidden while measuring. Measuring after
                // bindCardCapsule measured with the pins shown.
                pinnedCardH = cap.cardHeightForWidth(
                        dp(CapsuleView.cardWidthDp(app)));
                bindCardCapsule(cap, d, group);
                // v517: evidence line for the auto-pinned navi
                // card missing its icon (user report 2026-11-25):
                // which surface ended up VISIBLE for this frame.
                if (d.isNavi) {
                    FileLog.i("NaviCard phase1 key=" + d.key
                            + " " + cap.naviCardDiag());
                }
                cap.setLayoutParams(cardLp(y, pinnedCardH, cap));
                // Left edge starts after the retracted slivers.
                cap.setTranslationX(dp(CARD_LEFT_OFFSET_DP));
            } else if (isBigPinned) {
                // v365: 钉住的大卡片按自己量出来的高度排，
                // 和 Phase 2 的大卡片同一套（bindBigCapsule）。
                cap.setCardExpanded(true);
                bindBigCapsule(cap, d, group);
                bigPinnedH = cap.cardHeightForWidth(
                        dp(CapsuleView.cardWidthDp(app)));
                cap.setLayoutParams(cardLp(y, bigPinnedH, cap));
                cap.setTranslationX(dp(CARD_LEFT_OFFSET_DP));
            } else {
                // Pinned capsules sit below the card (when there is
                // one) in pin-time order; y already accounts for the
                // card's height.
                // v376: the call capsule is bigger (240×60).
                cap.setLayoutParams(capsuleLp(y, cap));
            }
            // v151: pinned capsules do not participate in the ripple,
            // so they stay visible while a card is open (the tapped
            // pinned capsule is the card itself). Nothing is hidden
            // here any more. The card sits ABOVE the pinned capsules
            // below it (z 1) so its content is never covered by them
            // — they show as slivers at the left edge behind it.
            if (isCardPinned || isBigPinned) {
                cap.setTranslationZ(1f);
            }
            // (v150: no vertical connector, no edge fade.)
            if (d.isCall) {
                // Call capsule (v103): tap does nothing (no expanded
                // card yet, user 2026-11-19 "胶囊展开先不设计"); the
                // answer button, left swipe and right swipe are the
                // call actions.
                // Tap = speaker answer (user 2026-11-19 "来电后点
                // 一下就是扬声器接听"); tapping again switches to
                // normal answer (the timer-tap toggle, v108).
                cap.setOnAnswer(new Runnable() {
                    @Override
                    public void run() {
                        answerCall(true);
                    }
                });
                cap.setOnHangup(new Runnable() {
                    @Override
                    public void run() {
                        hangupCallWithParticles();
                    }
                });
                cap.setOnToggleSpeaker(new Runnable() {
                    @Override
                    public void run() {
                        toggleCallSpeaker();
                    }
                });
            }
            cap.setOnTap(new Runnable() {
                @Override
                public void run() {
                    if (d.isCall) {
                        return;
                    }
                    // v365: 钉住的大卡片已经是卡片内容，点它就是
                    // 打开 App（和 Phase 2 的大卡片一致），不
                    // 再长成展开卡片。
                    if (isBigPinned) {
                        openCardApp(d);
                        return;
                    }
                    // Tap a pinned capsule: it grows into its card.
                    openCard(cap, d);
                }
            });
            // Left swipe = pin/unpin again, including on the call
            // capsule (user 2026-11-19 "左滑继续改回钉住和解除钉
            // 住"). Reverts the v109 shield; the left-swipe speaker
            // answer is gone.
            cap.setOnSwipeRight(new Runnable() {
                @Override
                public void run() {
                    togglePin(group, d);
                    // v180: pinning a card rebuilds the dock so the
                    // card jumps to first position immediately
                    // (user 2026-11-20 "钉住卡片也立即排到第一位").
                    // rebuildDock re-renders the open card at y=0.
                    rebuildDock();
                }
            });
            // v279: heavy left swipe docks into the fan.
            // v367: 胶囊模式也只缩被滑的那一张（用户 2026-11-22
            // 05:28 “同应用不同通知缩小怎么又变成一起缩小了”）——
            // 不再整组收进扇子，钉住的、没钉住的都一样。
            cap.setOnDockSwipe(new Runnable() {
                @Override
                public void run() {
                    dockOneCapsule(cap, d, group);
                }
            });
            cap.setOnSwipeLeft(new Runnable() {
                @Override
                public void run() {
                    // v154: a manual right-swipe on the open card
                    // dismisses the notification (no capsule pops
                    // back); otherwise it dissipates the capsule.
                    // v539: the dissipate goes through
                    // manualDissipate so the recall stack gets
                    // its snapshot first.
                    if (isCardKey(d.key)) {
                        dismissOpenCard(d);
                    } else {
                        manualDissipate(d);
                    }
                }
            });
            dock.addView(cap);
            // v180: a pinned card DOES take layout space now — it is
            // first, and the pinned capsules below it start under
            // its height. (The unpinned card in Phase 2 still does
            // not take layout space; only the pinned card does.)
            if (isCardPinned) {
                // v236: cardTopY is the OPEN card's top (for its
                // tail and the fan); a pinned card that is not
                // the open card only takes layout space.
                // Accumulate y so several pinned cards stack
                // instead of overwriting each other.
                if (isCardKey(d.key)) {
                    cardTopY = y;
                }
                y += pinnedCardH + dp(CAPSULE_GAP_DP);
            } else if (isBigPinned) {
                // v365: 钉住的大卡片按自己量出来的高度占行。
                y += bigPinnedH + dp(CAPSULE_GAP_DP);
            } else {
                // v456: 行高按类型走（来电 / 音乐 / 普通各栏的高度）。
                y += dp(CapsuleView.heightDpFor(d) + CAPSULE_GAP_DP);
            }
        }

        // Phase 2 — unpinned notifications, grouped normally by app
        // (user 2026-11-19 "其他同应用通知正常分组排列").
        for (final NotifGroup group : groups) {
            if (group.items.isEmpty()) {
                continue;
            }
            // Only the unpinned items participate in group arrangement.
            final java.util.List<NotifData> items =
                    new java.util.ArrayList<>();
            for (NotifData d : group.items) {
                if (d != null && (d.key == null
                        || (!group.pinnedAtByKey.containsKey(d.key)
                            && !stayPutPinTime.containsKey(d.key)))
                        // v376: a call capsule never sits in the
                        // dock when the "来电放右边" switch is on —
                        // it lives in the right window (see
                        // syncCallWindow), pinned or not.
                        && !(d.isCall && FeatureFlags.callRight)) {
                    items.add(d);
                }
            }
            if (items.isEmpty()) {
                continue;
            }
            // v360: 大卡片模式不叠加——每个通知都是自己的大胶囊，
            // 依次排列（用户 “通知来了就依次排列”）。
            if (items.size() > 1 && !group.expanded
                    && !FeatureFlags.bigCard) {
                // Cascade stack: oldest first (drawn under), newest last
                // (drawn on top). Each capsule sits 6dp below the one
                // above it, so the older ones peek out by a 6dp edge.
                int n = items.size();
                for (int i = n - 1; i >= 0; i--) {
                    final NotifData d = items.get(i);
                    CapsuleView cap = new CapsuleView(app);
                    cap.setTag(group);
                    cap.bind(d);
                    cap.setPinned(false);
                    if (d.isCall) {
                        bindCallCapsule(cap);
                    }
                    // Stacked capsules get an outline so they stay
                    // readable (user 2026-11-19 "叠加后自动给叠加的
                    // 添加描边").
                    cap.setStacked(true);
                    // i=0 (newest) at the top of the stack; older ones
                    // 6dp lower each, peeking out below.
                    // v378: the call capsule keeps its 240×60 size
                    // here too — the regular capsuleLp (180×44) made
                    // it snap back to the original size first when it
                    // shrank (user 2026-11-22 "缩小的时候他会先变成
                    // 原来的胶囊大小", left dock).
                    int top = y + i * dp(STACK_EDGE_DP);
                    cap.setLayoutParams(capsuleLp(top, cap));
                    // v150: a card is open elsewhere — this stack
                    // dissipated in the ripple, so it stays hidden.
                    // Calls stay out.
                    // v191 (user "卡片钉住以后，其他通知正常排列
                    // 在下面显示" / "就当展开卡片是大胶囊"): a
                    // PINNED card is just a big capsule in the
                    // normal flow — the others stay visible below
                    // it, so do not hide them when the card is pinned.
                    if (cardKey != null && !isCardPinned()
                            && !d.isCall) {
                        cap.setVisibility(View.INVISIBLE);
                    }
                    final boolean isTop = (i == 0);
                    cap.setOnTap(new Runnable() {
                        @Override
                        public void run() {
                            if (isTop) {
                                // Expand with animation (user 2026-11-19
                                // "通知叠加和展开加一个动画").
                                group.expanded = true;
                                rebuildDock();
                                cascadeCapsulesIn(java.util.Collections
                                        .singletonList(group));
                            } else {
                                // Stack with animation.
                                collapseGroupAnimated(group);
                            }
                        }
                    });
                    cap.setOnSwipeRight(new Runnable() {
                        @Override
                        public void run() {
                            togglePin(group, d);
                            // v180: pinning a card rebuilds the dock
                            // so it jumps to first position immediately.
                            rebuildDock();
                        }
                    });
                    // v279: heavy left swipe docks into the fan.
                    // v367: 级联叠加里的重滑也只缩被滑的那一张
                    // （同应用其他通知留着），不再整组收进扇子。
                    cap.setOnDockSwipe(new Runnable() {
                        @Override
                        public void run() {
                            dockOneCapsule(cap, d, group);
                        }
                    });
                    cap.setOnSwipeLeft(new Runnable() {
                        @Override
                        public void run() {
                            if (isCardKey(d.key)) {
                                dismissOpenCard(d);
                            } else {
                                manualDissipate(d);
                            }
                        }
                    });
                    dock.addView(cap);
                }
                // v456: 叠起来的高度跟顶上那颗走（按类型）。
                int stackTopH = CapsuleView.heightDpFor(items.get(0));
                y += dp(stackTopH
                        + (n - 1) * STACK_EDGE_DP + CAPSULE_GAP_DP);
                if (group == fanAnchor) {
                    fanAnchorBottom = y - dp(CAPSULE_GAP_DP);
                }
            } else {
                // Single capsule, or an expanded group: each notification
                // as its own capsule, newest first, going downward.
                // (Add oldest-first so the newest is drawn on top —
                // the same drawing order as the cascade branch above.
                // Adding newest-first puts the OLDEST on top, so the
                // stack shows reversed ("叠反了", user video 04:58).)
                // Card (v143): compute each item's top first, so an
                // open card's height pushes the ones below it down
                // (items.get(0) is the newest, at the top).
                int groupTop = y;
                // Card does NOT take layout space (user 2026-11-19
                // "卡片不要挤占位置"): every row keeps its own
                // pitch; the card overlays the rows below it.
                // v147: EXCEPT the capsules directly above and below
                // the card (same app) — they stay visible and connect
                // to the card (user 2026-11-19 "我说的连在一起就是
                // 这样" / "连接处就是这种平滑透明"). The one above is
                // already 6dp above the card; the one below is moved
                // to 6dp below the card's bottom edge, and every row
                // under it shifts down by the same amount.
                // v360: 大卡片模式的放大胶囊占自己的行高（卡片高），
                // 下面的行从它底下开始；普通胶囊仍是 44dp 行高。
                int[] tops = new int[items.size()];
                int[] heights = new int[items.size()];
                int ty = groupTop;
                for (int i = 0; i < items.size(); i++) {
                    // v456: 行高按类型走（来电 / 音乐 / 普通各栏的高度）。
                    int h = dp(CapsuleView.heightDpFor(items.get(i)));
                    if (isBigCapsule(items.get(i))) {
                        CapsuleView scratch = new CapsuleView(app);
                        scratch.bind(items.get(i));
                        scratch.setCardExpanded(true);
                        h = scratch.cardHeightForWidth(
                                dp(CapsuleView.cardWidthDp(app)));
                    }
                    heights[i] = h;
                    tops[i] = ty;
                    ty += h + dp(CAPSULE_GAP_DP);
                }
                // v148: the card does NOT push anything down (user
                // 2026-11-19 "都说了不挤通知") — the rows below keep
                // their own pitch and the card overlays them (v145).
                // v150: no vertical link to the capsules above/below
                // (user "不是连着上下的胶囊"), and no edge fade — the
                // card pops out from the left on its own.
                for (int idx = items.size() - 1; idx >= 0; idx--) {
                    final NotifData d = items.get(idx);
                    CapsuleView cap = new CapsuleView(app);
                    cap.setTag(group);
                    cap.bind(d);
                    cap.setPinned(false);
                    if (d.isCall) {
                        bindCallCapsule(cap);
                    }
                    cap.setStacked(false);
                    final boolean isCard = isCardKey(d.key);
                    // v360: 大卡片模式的放大胶囊（卡片内容长在胶囊上）。
                    final boolean isBig =
                            !isCard && isBigCapsule(d);
                    int placeTop = tops[idx];
                    if (isCard) {
                        cap.setCardExpanded(true);
                        bindCardCapsule(cap, d, group);
                        // v235: freeze the card's top while this
                        // card stays open. A new notification
                        // above the card shifts its row in the
                        // group order; reusing the frozen top
                        // keeps the card still.
                        int cardTop;
                        if (frozenCardTopY >= 0 && d.key != null
                                && d.key.equals(frozenCardKey)) {
                            cardTop = frozenCardTopY;
                        } else {
                            cardTop = tops[idx];
                            frozenCardTopY = cardTop;
                            frozenCardKey = d.key;
                        }
                        cap.setLayoutParams(cardLp(cardTop, cap));
                        // Left edge starts after the retracted slivers.
                        cap.setTranslationX(dp(CARD_LEFT_OFFSET_DP));
                    } else if (isBig) {
                        cap.setCardExpanded(true);
                        bindBigCapsule(cap, d, group);
                        cap.setLayoutParams(
                                cardLp(placeTop, heights[idx], cap));
                        // Left edge flush, like the card.
                        cap.setTranslationX(dp(CARD_LEFT_OFFSET_DP));
                    } else {
                        // v378: the call capsule keeps its 240×60
                        // size in an expanded group too.
                        cap.setLayoutParams(capsuleLp(placeTop, cap));
                    }
                    if (cardKey != null && !isCardPinned() && !isCard) {
                        // v150: dissipated in the ripple; hidden
                        // while the card is open. Calls stay out.
                        // v191: a PINNED card is a big capsule — the
                        // others are laid out below it and stay visible.
                        if (!d.isCall) {
                            cap.setVisibility(View.INVISIBLE);
                        }
                    } else if (isCard) {
                        cap.setTranslationZ(0f);
                        cardTopY = frozenCardTopY;
                    }
                    cap.setOnTap(new Runnable() {
                        @Override
                        public void run() {
                            if (d.isCall) {
                                return;
                            }
                            // v360: 放大胶囊已经是卡片内容，点它就是
                            // 打开 App（和点开着的卡片一样）。
                            if (isBig) {
                                openCardApp(d);
                                return;
                            }
                            // Tap a capsule: it grows into its card.
                            // (Single notification, or one item of an
                            // expanded group — the group stays expanded
                            // underneath.)
                            openCard(cap, d);
                        }
                    });
                    cap.setOnSwipeRight(new Runnable() {
                        @Override
                        public void run() {
                            // v363: 轻滑钉住（用户 “左滑轻滑钉住，重滑
                            // 缩小”）——v361 的轻滑整组收进扇子已撤掉。
                            // 钉住后放大胶囊变回小钉住胶囊；重滑缩进
                            // 扇子走下面的 onDockSwipe。
                            togglePin(group, d);
                            // v180: pinning a card rebuilds the dock
                            // so it jumps to first position immediately.
                            rebuildDock();
                        }
                    });
                    // v279: heavy left swipe docks into the fan.
                    // v367: 胶囊模式也只缩被滑的那一张（用户 2026-11-22
                    // 05:28 “同应用不同通知缩小怎么又变成一起缩小了”）——
                    // 大卡片和胶囊都走 dockOneCapsule，不再整组收进扇子。
                    cap.setOnDockSwipe(new Runnable() {
                        @Override
                        public void run() {
                            dockOneCapsule(cap, d, group);
                        }
                    });
                    final NotifData item = d;
                    cap.setOnSwipeLeft(new Runnable() {
                        @Override
                        public void run() {
                            if (isCardKey(item.key)) {
                                dismissOpenCard(item);
                            } else {
                                manualDissipate(item);
                            }
                        }
                    });
                    dock.addView(cap);
                }
                y = ty;
                if (group == fanAnchor) {
                    fanAnchorBottom = y - dp(CAPSULE_GAP_DP);
                }
            }
        }
        // v150: the card edge fade (v148) is off — the card now pops
        // out from the left and slides right to close, so a static
        // fade strip on its right edge cannot follow it.
        // The fan is ALWAYS below the notifications (user 2026-11-18
        // "这个贴边图标永远在通知下面") — never on top of them, never
        // jumping over them. The fan can ONLY stack at the very end
        // (below ALL notifications). Its screen Y is the pile window's
        // Y now (the pile lives in its own window, not in the dock).
        // When no capsule is left, it sits at the top.
        // The fan sits below everything — the rows AND the card
        // (the card overlays rows but the fan must not sit under it).
        int cardBottom = (cardKey != null && cardTopY >= 0)
                ? cardTopY + cardHeightPx : 0;
        int newPileY = groups.isEmpty() ? 0
                : Math.max(y, cardBottom);
        // v439: 胶囊和扇子是一个整体——扇子还是跟胶囊走（v427 原样），
        // 整组再整体下移 anchorY。扇子窗的 Y 是屏幕绝对值，所以
        // anchorY 加在这里；主窗口的 Y 在 updateWindowSize() 里加。
        int anchorY = Appearance.dockTopPx(app);
        newPileY += anchorY;
        int oldPileY = (pileWindowAdded && pileParams != null)
                ? pileParams.y : -1;
        pileTargetY = newPileY;
        boolean slidePile = oldPileY >= 0 && oldPileY != newPileY
                && pile != null && pile.getIconCount() > 0;
        if (slidePile) {
            // A new notification pushes the fan down smoothly: the
            // animator drives the pile window Y; updateWindowSize
            // must not jump it meanwhile (pileYAnimating guard).
            pileYAnimating = true;
        }
        updateWindowSize();
        if (slidePile) {
            animatePileWindowY(oldPileY, newPileY);
        }
        // v114: a rebuild re-binds every capsule, and bind() resets
        // the call capsule to the ringing state (handset, no timer).
        // If a call is up, restore the answered button (speaker +
        // timer) right away instead of waiting for the next tick.
        updateCallTimer();
        // v376: keep the right call window in sync (show the call
        // capsule there when the switch is on, hide it otherwise).
        syncCallWindow();
        // v237: an open card was just pinned — its group's
        // capsules that were hidden while it was open are now
        // visible; pop them in one by one instead of letting
        // them appear instantly.
        if (popInAfterRebuild && popInGroup != null) {
            popInAfterRebuild = false;
            NotifGroup g = popInGroup;
            popInGroup = null;
            cascadeCapsulesIn(java.util.Collections.singletonList(g));
        }
    }

    /** v456: 胶囊的 dock 槽位——宽/高走胶囊自己的值（来电 / 音乐 /
     * 普通各走各栏的宽高），不再写死 180×44。 */
    private FrameLayout.LayoutParams capsuleLp(int y, CapsuleView cap) {
        int w = CapsuleView.DEFAULT_WIDTH_DP;
        int h = CapsuleView.DEFAULT_HEIGHT_DP;
        if (cap != null) {
            w = cap.baseWidthDp();
            h = cap.baseHeightDp();
        }
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                dp(w),
                dp(h),
                Gravity.TOP | (dockLeft ? Gravity.START : Gravity.END));
        lp.topMargin = y;
        return lp;
    }

    // ---- v376: right-side call window ----

    /** The "来电放右边" switch flipped — re-layout so an active
     * call capsule moves between the dock and the right window
     * at once (called from the feature-toggle receiver). */
    public void onCallRightChanged() {
        rebuildDock();
    }

    /** The current call capsule, whether it lives in the dock
     * (switch off) or in the right window (switch on). */
    private CapsuleView findCallCapsule() {
        // v448: callCapView 会过期——右边来电用完切回左边，
        // callCapView 还指着已摘掉的右窗胶囊，findCallCapsule
        // 直接把它返回，setCallTimer 就打到摘掉的旧实例上，
        // 左边 dock 里真正显示那颗永远收不到 setCallTimer，
        // 缩计时器永远不触发（v444–v447 三版都没修到点子上，
        // 根因在这里）。先验它还在不在树上、是不是来电，
        // 不对就清掉、改去 dock 里找。
        if (callCapView != null) {
            boolean stale = callCapView.getParent() == null;
            if (!stale) {
                NotifData d = callCapView.getData();
                stale = (d == null || !d.isCall);
            }
            if (stale) {
                FileLog.i("CallTimerDiag findCallCapsule STALE"
                        + " callCapView cleared"
                        + " hadParent=" + (callCapView.getParent() != null)
                        + " key=" + callCapKey);
                callCapView = null;
                callCapKey = null;
            } else {
                return callCapView;
            }
        }
        if (dock != null) {
            for (int i = 0; i < dock.getChildCount(); i++) {
                View v = dock.getChildAt(i);
                if (v instanceof CapsuleView) {
                    CapsuleView cap = (CapsuleView) v;
                    NotifData d = cap.getData();
                    if (d != null && d.isCall) {
                        return cap;
                    }
                }
            }
        }
        return null;
    }

    /** Create the right-side call window on first use. It is a
     * small transient window (560×80dp): 160dp of drag room on
     * the left (the hang-up tail), the 240dp capsule, 160dp on
     * the right (the pin/dock drag past the edge). */
    private void ensureCallWindow() {
        // v384: always sync the window size/position, even when
        // the window already exists. v383 grew the window to
        // 800x300dp but this method returned early when callRoot
        // already existed, so an updated module kept the OLD
        // 560x80 window while new capsules used the NEW margins
        // (280/120) — the capsule landed 120dp too far right
        // (cut off) and 110dp too low. Now the size/position are
        // re-applied on every call.
        if (wm == null || app == null) {
            return;
        }
        if (callRoot == null) {
            callRoot = new FrameLayout(app);
            callRoot.setClipChildren(false);
            callRoot.setVisibility(View.GONE);
        }
        // v390: the idle window is EXACTLY the capsule (240x60dp)
        // v392: mirror the LEFT dock (user 2026-11-22 "之前左边是
        // 怎么设计的"). The left window is gravity LEFT with the
        // capsule anchored to its left edge, and a drag ONLY grows
        // the window wider (to full screen) — position, margins
        // and translation are never touched, so the capsule cannot
        // jump. The right window is the mirror: gravity RIGHT, the
        // capsule anchored to its right edge, and a drag only grows
        // the window wider — the right edge stays on the screen
        // edge and the window extends LEFT, giving the capsule room
        // to slide toward the centre. v390/v391 moved the window
        // and translated the root mid-drag; the two never land in
        // the same frame, so it kept jumping.
        if (callParams == null) {
            callParams = new WindowManager.LayoutParams(dp(240), dp(60),
                    WindowManager.LayoutParams.TYPE_SYSTEM_ERROR,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                            | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                            | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
                            | WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED,
                    PixelFormat.TRANSLUCENT);
            // v401: gravity LEFT + explicit x (see updateCallWindowPos).
            // v392–v400 used gravity RIGHT + x=0, but the v399/v400
            // logs proved Flyme does NOT keep the window right-anchored
            // on resize — the capsule slides off the right edge (pos
            // x 559 -> 2054) when the window grows to 800dp. Gravity
            // is evidently not re-applied on updateViewLayout here,
            // so the anchor is computed explicitly instead.
            callParams.gravity = Gravity.TOP | Gravity.LEFT;
            callParams.setTitle("flymebobo-call");
        } else if (callDragWindowOpen || callDragWindowShrinkPending) {
            // v391: a drag is in progress, or one just ended and
            // the shrink is waiting out the 200ms spring-back —
            // keep the drag window. ensureCallWindow runs at the
            // end of every rebuildDock, and shrinking it mid-drag
            // (or mid-spring-back) would clip the capsule and
            // break the touch stream.
            // v406: width is the SCREEN width (callScreenWidth),
            // not dp(800). v403 fixed beginCallDragWindow to the
            // screen width so x is never negative, but this branch
            // still used dp(800)=2935px — any rebuildDock while a
            // drag was open or a shrink was pending (e.g. right
            // swipe pin -> onSwipeRight -> rebuildDock) reset the
            // width to 2935, x went to -1495, Flyme clamped it to
            // 0 and the capsule jumped right to 2054 (off-screen)
            // — the extra right bounce after the spring-back, in
            // both directions. Now both paths use the screen width.
            callParams.width = callScreenWidth();
            callParams.height = dp(300);
        } else {
            callParams.width = dp(240);
            callParams.height = dp(60);
        }
        updateCallWindowPos();
        if (!callWindowAdded) {
            try {
                wm.addView(callRoot, callParams);
                callWindowAdded = true;
            } catch (Throwable ignored) {
            }
        }
    }

    /** v403: the real screen width in pixels (via the
     * WindowManager's default display getRealSize, falling back
     * to app resources). Used to size/position the right call
     * window so it never needs a negative x (see below). */
    private int callScreenWidth() {
        try {
            android.graphics.Point sz =
                    new android.graphics.Point();
            if (wm != null) {
                wm.getDefaultDisplay().getRealSize(sz);
                if (sz.x > 0) {
                    return sz.x;
                }
            }
        } catch (Throwable ignored) {
        }
        return app.getResources().getDisplayMetrics().widthPixels;
    }

    /** (Re)compute the right call window's screen position from
     * the current display metrics (rotation-safe). */
    private void updateCallWindowPos() {
        if (callParams == null || app == null) {
            return;
        }
        // v402: get the REAL screen size from the WindowManager's
        // default display, not from app resources. The v401 log
        // proved app-resources DisplayMetrics is wrong here:
        // v401 computed x = widthPixels - windowWidth, but the
        // capsule still landed at pos x=2054 (identical to
        // v399/v400), which means widthPixels came back as ~2935
        // (the window width) instead of the real screen width
        // 1080 — so x ended up ~0 and the window stayed
        // left-anchored, growing right off-screen. getRealSize
        // returns the physical display size in pixels.
        // v403: getRealSize DID return the real size (1440x3200 —
        // the recording is a 0.75x downscale, the panel is 1440
        // wide, not 1080), and the resize log showed x=-1495
        // (1440-2935). But the capsule STILL went to pos x=2054:
        // Flyme evidently clamps a negative window x to 0, so the
        // window stays left-anchored at x=0 and the capsule (at
        // the window's right edge) lands at 0+2935-881=2054,
        // off-screen. So the window must NEVER be wider than the
        // screen — then x is never negative (see beginCallDragWindow).
        int screenW = callScreenWidth();
        int screenH = 0;
        try {
            android.graphics.Point sz =
                    new android.graphics.Point();
            if (wm != null) {
                wm.getDefaultDisplay().getRealSize(sz);
                screenH = sz.y;
            }
        } catch (Throwable ignored) {
        }
        if (screenH <= 0) {
            screenH = app.getResources().getDisplayMetrics()
                    .heightPixels;
        }
        // v401: explicit right anchor. Gravity RIGHT + x=0 did NOT
        // hold on resize (v399/v400 logs: the capsule's pos x went
        // 559 -> 2054 when the window grew to 800dp, i.e. the whole
        // window drifted right off-screen). So the window is now
        // gravity LEFT and x is computed directly: x = screen width
        // - window width, which puts the window's right edge exactly
        // on the screen edge for ANY width (idle 240dp or drag
        // 800dp). updateCallWindowPos runs on every resize
        // (begin/endCallDragWindow call it), so the anchor is
        // re-computed with the new width each time.
        // v442: y 不再写死 55%——改由来电高度滑条定（Appearance.callTopPct，
        // 胶囊中心高度百分比，默认 55，跟原来 0.55 一样）。滑条在 APP
        // 「来电放右边」开关下面。夹住：胶囊 60dp 高，上下都不能出屏
        // （滑条拉到顶/底时顶边/底边贴住屏幕边，不会整个飞出去）。
        callParams.gravity = Gravity.TOP | Gravity.LEFT;
        callParams.x = screenW - callParams.width;
        int callY = (int) (screenH * (Appearance.callTopPct / 100f))
                - dp(30);
        int maxCallY = screenH - dp(60) - dp(8);
        if (callY > maxCallY) callY = maxCallY;
        if (callY < 0) callY = 0;
        callParams.y = callY;
        if (callWindowAdded && callRoot != null && wm != null) {
            try {
                wm.updateViewLayout(callRoot, callParams);
            } catch (Throwable ignored) {
            }
        }
    }

    /** v442: 来电高度滑条改了——只重算右边来电窗的位置（整颗胶囊
     * 一起移），不重排左边胶囊。XposedEntry 收到 callPct 广播后调。 */
    public void onCallPositionChanged() {
        updateCallWindowPos();
    }

    /** Keep the right call window in step with the current call
     * and the switch (called at the end of every rebuildDock).
     * The call capsule is found by scanning the groups, so its
     * pin state does not matter — pinned or not, it goes right
     * when the switch is on, and only there. */
    private void syncCallWindow() {
        if (!FeatureFlags.callRight) {
            hideCallWindow();
            return;
        }
        NotifData callData = null;
        NotifGroup callGroup = null;
        for (NotifGroup g : groups) {
            if (g == null) {
                continue;
            }
            for (NotifData d : g.items) {
                if (d != null && d.isCall) {
                    callData = d;
                    callGroup = g;
                    break;
                }
            }
            if (callData != null) {
                break;
            }
        }
        if (callData == null) {
            hideCallWindow();
            return;
        }
        showCallOnRight(callData, callGroup);
    }

    /** Show the call capsule in the right window. The capsule is
     * created once per call (keyed by the call key); later
     * rebuilds keep the same view so the timer and the answered
     * state survive. */
    private void showCallOnRight(final NotifData d,
            final NotifGroup group) {
        if (d == null || group == null) {
            return;
        }
        ensureCallWindow();
        if (callRoot == null) {
            return;
        }
        // Same call already showing — re-apply the capsule's
        // margins (v384: they must match the window size; a
        // capsule created with mismatched margins would sit in
        // the wrong place) and show it.
        if (callCapView != null && d.key != null
                && d.key.equals(callCapKey)) {
            // v396: drag diagnosis — log whether a re-post keeps
            // the existing capsule during a drag.
            // v399: also log the capsule's screen position.
            FileLog.i("CallWin keep dragOpen=" + callDragWindowOpen
                    + " transX=" + callCapView.getTranslationX()
                    + " pos=" + callCapScreenPos());
            // v391: the capsule always fills its window (margins
            // 0/0); the drag slack lives in the window size and
            // the callRoot translation, never in the margins.
            FrameLayout.LayoutParams curLp =
                    (FrameLayout.LayoutParams)
                            callCapView.getLayoutParams();
            if (curLp != null) {
                curLp.leftMargin = 0;
                curLp.topMargin = 0;
                callCapView.setLayoutParams(curLp);
            }
            updateCallWindowPos();
            callRoot.setVisibility(View.VISIBLE);
            return;
        }
        // New call: drop the old capsule (if any) and build one.
        // v396: drag diagnosis — log if a NEW capsule is built
        // while a drag is in progress (the old one is destroyed).
        FileLog.i("CallWin NEW dragOpen=" + callDragWindowOpen
                + " hadCap=" + (callCapView != null));
        if (callCapView != null) {
            try {
                callRoot.removeView(callCapView);
            } catch (Throwable ignored) {
            }
            callCapView = null;
            callCapKey = null;
        }
        final CapsuleView cap = new CapsuleView(app);
        cap.setDockLeft(false);
        cap.setTag(group);
        cap.bind(d);
        cap.setPinned(true);
        cap.setStacked(false);
        // Same call actions as the pinned dock call capsule
        // (v103/v108): tap = speaker answer, timer tap = toggle.
        cap.setOnAnswer(new Runnable() {
            @Override
            public void run() {
                answerCall(true);
            }
        });
        cap.setOnHangup(new Runnable() {
            @Override
            public void run() {
                hangupCallWithParticles();
            }
        });
        cap.setOnToggleSpeaker(new Runnable() {
            @Override
            public void run() {
                toggleCallSpeaker();
            }
        });
        cap.setOnTap(new Runnable() {
            @Override
            public void run() {
                // Calls never open a card.
            }
        });
        cap.setOnSwipeRight(new Runnable() {
            @Override
            public void run() {
                togglePin(group, d);
                rebuildDock();
            }
        });
        cap.setOnDockSwipe(new Runnable() {
            @Override
            public void run() {
                dockOneCapsule(cap, d, group);
            }
        });
        cap.setOnSwipeLeft(new Runnable() {
            @Override
            public void run() {
                dissipateOne(d);
            }
        });
        // v392: the capsule is anchored to the window's right edge
        // (gravity END), so it never moves when the window grows
        // left during a drag — the mirror of the left dock, whose
        // capsule is anchored to its window's left edge.
        // v456: 右窗来电胶囊宽/高走来电栏的设定值。
        FrameLayout.LayoutParams lp =
                new FrameLayout.LayoutParams(
                        dp(Appearance.callCapsuleWidthDp),
                        dp(Appearance.callCapsuleHeightDp),
                        Gravity.TOP | Gravity.END);
        lp.leftMargin = 0;
        lp.topMargin = 0;
        callRoot.addView(cap, lp);
        callCapView = cap;
        callCapKey = d.key;
        updateCallWindowPos();
        callRoot.setVisibility(View.VISIBLE);
        // If the call was already answered (e.g. the switch flipped
        // mid-call), restore the timer at once instead of waiting
        // for the next tick — bind() just reset it to ringing.
        updateCallTimer();
        // v413: slide in from the right edge instead of popping in
        // (user 2026-11-22 "来电时胶囊应该是滑入的 / 都改成滑入 /
        // 贴边滑入"). The capsule starts one full width to the right
        // (off-screen) and slides to its edge position, mirroring
        // the left capsule's slide-in from the left in popCapsule.
        // Only a NEW call slides in; the keep branch above (same
        // call re-post) leaves the capsule where it is.
        cap.setTranslationX(dp(CapsuleView.CALL_WIDTH_DP));
        cap.setAlpha(0f);
        cap.animate().translationX(0f).alpha(1f)
                .setDuration(250).withLayer().start();
    }

    /** Hide the right call window and drop its capsule (call
     * ended, or the switch turned off). */
    /** v399: the right call capsule's screen position for drag
     * diagnosis — getLocationOnScreen gives the absolute screen
     * x/y of the capsule's left-top corner ("-" when there is
     * no capsule). Together with the pill width and transX it
     * tells whether a "vanished" capsule was removed/invisible
     * or simply moved off-screen. */
    private String callCapScreenPos() {
        if (callCapView == null) {
            return "-";
        }
        int[] loc = new int[2];
        try {
            callCapView.getLocationOnScreen(loc);
        } catch (Throwable ignored) {
        }
        return loc[0] + "," + loc[1];
    }

    private void hideCallWindow() {
        // v396: drag diagnosis — log if the call capsule is
        // destroyed while a drag is in progress (the video
        // "vanish on drag start"). If this fires mid-drag, the
        // removal path is the cause; if it never fires, the
        // vanish is a rendering/window issue, not a removal.
        // v399: also log the capsule's screen position, so a
        // "vanished" capsule can be told apart from one that
        // is still there but moved off-screen.
        FileLog.i("CallWin hide dragOpen=" + callDragWindowOpen
                + " hasCap=" + (callCapView != null)
                + " pos=" + callCapScreenPos());
        if (callCapView != null && callRoot != null) {
            try {
                callRoot.removeView(callCapView);
            } catch (Throwable ignored) {
            }
        }
        callCapView = null;
        callCapKey = null;
        if (callRoot != null) {
            callRoot.setVisibility(View.GONE);
        }
        // v397: the user is right — the call window was a
        // leftover. It was added to the WindowManager once and
        // NEVER removed; hideCallWindow only set the root GONE.
        // The empty window (240x60, or 800x300 if a drag left
        // it enlarged) stayed at the right edge and swallowed
        // every touch inside its bounds (FLAG_NOT_TOUCH_MODAL
        // only lets touches OUTSIDE the window through, and
        // with a GONE root nothing inside handles them, so
        // they are dropped). That is why the spot where the
        // right capsule was could not be tapped. Remove the
        // window itself; ensureCallWindow re-adds it when a
        // new call comes (callWindowAdded == false).
        if (callWindowAdded && callRoot != null && wm != null) {
            try {
                wm.removeView(callRoot);
            } catch (Throwable ignored) {
            } finally {
                callWindowAdded = false;
            }
        }
    }

    // ---- Card (v143) ----
    private FrameLayout.LayoutParams cardLp(int y, CapsuleView cap) {
        return cardLp(y, cardHeightPx, cap);
    }

    // v237: card layout with an explicit height (a pinned card
    // measures its own — the shared cardHeightPx is the open
    // card's height and is reset to 0 when it closes).
    // v456: 卡片宽走胶囊自己的值（普通卡外观宽，音乐卡 v458 起也走外观的音乐卡片宽）。
    private FrameLayout.LayoutParams cardLp(int y, int heightPx,
            CapsuleView cap) {
        int cw = CapsuleView.CARD_WIDTH_DP;
        if (cap != null) {
            cw = cap.cardWidthDp();
        }
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                dp(cw),
                Math.max(heightPx, dp(CapsuleView.DEFAULT_HEIGHT_DP)),
                Gravity.TOP | (dockLeft ? Gravity.START : Gravity.END));
        lp.topMargin = y;
        return lp;
    }

    private boolean isCardKey(String key) {
        return cardKey != null && key != null && cardKey.equals(key);
    }

    /** v180: true when the open card is pinned. A pinned card does
     * not auto-disappear (tap outside does not close it); like a
     * pinned capsule it can only be removed manually (right-swipe
     * dismiss on the card, or unpin first). */
    private boolean isCardPinned() {
        if (cardKey == null) {
            return false;
        }
        for (NotifGroup g : groups) {
            if (g != null && g.pinnedAtByKey.containsKey(cardKey)) {
                return true;
            }
        }
        return false;
    }

    private CapsuleView findCapsuleByKey(String key) {
        if (dock == null || key == null) {
            return null;
        }
        for (int i = 0; i < dock.getChildCount(); i++) {
            android.view.View v = dock.getChildAt(i);
            if (v instanceof CapsuleView) {
                CapsuleView cap = (CapsuleView) v;
                if (cap.getData() != null
                        && key.equals(cap.getData().key)) {
                    return cap;
                }
            }
        }
        return null;
    }

    /** Find the on-screen music capsule for this app, if any.
     * Used as a fallback when the key doesn't match (hook vs
     * broadcast may deliver the same re-post with a different
     * key object). One app has at most one music capsule. */
    private CapsuleView findMusicCapsuleByPkg(String pkg) {
        if (dock == null || pkg == null) {
            return null;
        }
        for (int i = 0; i < dock.getChildCount(); i++) {
            android.view.View v = dock.getChildAt(i);
            if (v instanceof CapsuleView) {
                CapsuleView cap = (CapsuleView) v;
                NotifData d = cap.getData();
                // v459: 模拟音乐胶囊不算——真音乐 re-post 时别把它
                // 当成真胶囊刷掉，也别让它挡住真胶囊弹出。
                if (d != null && d.isMusic && pkg.equals(d.pkg)
                        && !SIM_MUSIC_KEY.equals(d.key)) {
                    return cap;
                }
            }
        }
        return null;
    }

    /** v150: open the card for this capsule. The particle-gather
     * expand (v149, user "不要这个了") and the slide-then-grow
     * (v148) are gone. New flow (user 2026-11-19 "点了胶囊后胶囊
     * 从点击胶囊开始自动右滑消散，点击胶囊先开始，两边依次开始右滑
     * 消散，然后展开卡片从左边弹出来"): every capsule auto
     * right-swipes and bursts, rippling outward from the tapped one
     * (tapped first, then the neighbours above and below, wave by
     * wave); then the card pops out from the left edge. The
     * dissipate is visual only — the notifications stay in their
     * groups, so closing the card pops the capsules back out.
     * v151: calls and pinned capsules (other than the tapped one)
     * never auto-dissipate. */
    /** v263: open the App from the open card. Small window when
     * the miniwindow switch is on, fullscreen when off. On success
     * the notification is dismissed (v143) and the card closes.
     * v270: no card open (capsule tap) — the capsule auto
     * right-slides and bursts into particles as the App opens,
     * instead of vanishing instantly. */
    private void openCardApp(NotifData d) {
        if (d == null) {
            return;
        }
        boolean hadCard = cardKey != null;
        // v270: capsule tap — start the auto right-slide first, so
        // the animation is already running when the App launches.
        final NotifData data = d;
        if (!hadCard) {
            CapsuleView cap = findCapsuleByKey(d.key);
            if (cap != null) {
                // v364: 点开 App 的自动右滑也带拖尾（和自动消散同一套）。
                cap.playAutoSwipeRight(new Runnable() {
                    @Override
                    public void run() {
                        dissipateOne(data);
                    }
                }, true);
            } else {
                dissipateOne(data);
            }
        }
        // v277: some music notifications (notably QQ Music Flyme)
        // carry no contentIntent at all, so the tap did nothing.
        // Fall back to launching the app by package name.
        boolean ok;
        if (d.contentIntent != null) {
            ok = openPendingIntent(d.contentIntent, d.pkg,
                    FeatureFlags.miniwindow);
        } else {
            ok = false;
            try {
                android.content.Intent li = app.getPackageManager()
                        .getLaunchIntentForPackage(d.pkg);
                if (li != null) {
                    ok = openIntent(li, FeatureFlags.miniwindow);
                }
            } catch (Throwable ignored) {
            }
        }
        if (!ok) {
            return;
        }
        // v276: music notifications are NOT dismissed when the App
        // opens — cancelling the music notification kills the
        // foreground service and the music (and the App) gets
        // closed. Only regular notifications are dismissed.
        if (!d.isMusic) {
            try {
                android.content.Intent di = new android.content.Intent(
                        NotificationService.ACTION_DISMISS);
                di.putExtra("key", d.key);
                app.sendBroadcast(di);
            } catch (Throwable ignored) {
            }
        }
        if (hadCard) {
            // Card was open: close it (its own slide-right animation)
            // and remove the notification.
            removeNotifByKey(d.key);
            closeCard();
        }
        // No card was open: dissipateOne (started above) bursts the
        // capsule and removes + rebuilds after its 310ms delay.
    }

    /** v360: 大卡片模式的放大胶囊弹出——左滑弹入（和胶囊的弹出
     * 动画一样，从左滑入 200dp，240ms）。 */
    private void popInBigCapsule(CapsuleView cap) {
        if (cap == null) {
            return;
        }
        cap.setTranslationX(-dp(200));
        cap.setAlpha(0f);
        cap.animate().translationX(0f).alpha(1f)
                .setDuration(240).withLayer().start();
    }

    public void openCard(CapsuleView cap, NotifData d) {
        if (cap == null || d == null || d.isCall || cardAnimating) {
            return;
        }
        // v358: 大卡片模式直接弹出——这里就把标记读走清掉，避免中途
        // return 导致标记残留，下次点胶囊开卡片也被当成直接弹出。
        boolean directEntrance = bigCardDirectEntrance;
        bigCardDirectEntrance = false;
        // v278: log the tap decision (isMusic / card / musicCard)
        // so a "music card won't open" report can be diagnosed
        // from the log instead of guessed at.
        try {
            FileLog.i("TapCard key=" + d.key + " pkg=" + d.pkg
                    + " isMusic=" + d.isMusic
                    + " card=" + FeatureFlags.card
                    + " musicCard=" + FeatureFlags.musicCard);
        } catch (Throwable ignored) {
        }
        // v261/v266: card-expansion switch off = tapping a capsule
        // does not expand the card; instead it opens the App
        // directly (small window / fullscreen per the miniwindow
        // switch), so the capsule stays useful with the card off.
        // v274: the card switch does NOT control music — music
        // capsules are governed ONLY by the musicCard switch.
        // v359: 大卡片模式下不认这两个开关——开着大卡片就要能弹卡、
        // 能展开（用户 2026-11-22 “开大卡片之后就不能展开卡片了”）。
        // 开关关着时点胶囊本来是直接开 App，这里大卡片优先。
        if (!FeatureFlags.bigCard) {
            if (d.isMusic) {
                // v278: safety net — if the in-memory musicCard flag
                // is off, re-read the APP's prefs once in case the
                // toggle broadcast was missed (stale flag). If the
                // prefs also say off, then open the App directly.
                if (!FeatureFlags.musicCard) {
                    try {
                        FeatureFlags.load(app);
                    } catch (Throwable ignored) {
                    }
                }
                if (!FeatureFlags.musicCard) {
                    openCardApp(d);
                    return;
                }
            } else if (!FeatureFlags.card) {
                openCardApp(d);
                return;
            }
        }
        if (isCardKey(d.key)) {
            // v263: tapping the open card opens the App — small
            // window when the switch is on, fullscreen when off.
            openCardApp(d);
            return;
        }
        if (cardKey != null) {
            closeCardInstant();
        }
        cardAnimating = true;
        if (dock == null) {
            cardAnimating = false;
            return;
        }
        // v358: 大卡片模式直接弹出——不播涟漪消散、不等 280ms，
        // 卡片立刻出现。否则新通知的胶囊会在涟漪期间先露出来
        // （用户 2026-11-22 "还是先弹的胶囊"）。其他胶囊由
        // showCardAfterRipple 里的 rebuildDock 收回边缘。
        // v360: 入口改回左滑弹入（用户 “大卡片的动画还是左滑弹入”），
        // 不再淡入+放大。
        if (directEntrance) {
            showCardAfterRipple(d);
            return;
        }
        // Ripple participants, in visual (top-to-bottom) order: the
        // tapped capsule plus the unpinned, non-call capsules. Calls
        // never dissipate, and pinned capsules (other than the tapped
        // one) stay out (user 2026-11-19 "来电和钉住的的不参与" /
        // "如果点击的胶囊是钉住的则这个胶囊展开也是正常右滑消散那
        // 种"). Find the tapped one by key (the view may have been
        // rebuilt above).
        final java.util.List<CapsuleView> caps =
                new java.util.ArrayList<>();
        CapsuleView tapped = null;
        for (int i = 0; i < dock.getChildCount(); i++) {
            View v = dock.getChildAt(i);
            if (v instanceof CapsuleView) {
                CapsuleView c = (CapsuleView) v;
                NotifData cd = c.getData();
                if (cd == null || cd.isCall) {
                    continue;
                }
                boolean isTapped = d.key != null
                        && d.key.equals(cd.key);
                if (!isTapped) {
                    NotifGroup g = (NotifGroup) c.getTag();
                    if (g != null && cd.key != null
                            && g.pinnedAtByKey.containsKey(cd.key)) {
                        continue;
                    }
                }
                caps.add(c);
                if (isTapped) {
                    tapped = c;
                }
            }
        }
        if (tapped == null) {
            cardAnimating = false;
            return;
        }
        caps.sort(new java.util.Comparator<CapsuleView>() {
            @Override
            public int compare(CapsuleView a, CapsuleView b) {
                return Integer.compare(topOf(a), topOf(b));
            }
        });
        final int tappedIdx = caps.indexOf(tapped);
        // Waves: wave 0 = the tapped capsule; wave k = the capsules
        // k rows above and below it. Both sides of one wave go
        // together, so the dissipate ripples outward from the tap.
        final java.util.List<java.util.List<CapsuleView>> waves =
                new java.util.ArrayList<>();
        waves.add(java.util.Collections.singletonList(tapped));
        for (int k = 1; ; k++) {
            java.util.List<CapsuleView> wave =
                    new java.util.ArrayList<>();
            int above = tappedIdx - k;
            int below = tappedIdx + k;
            if (above >= 0) {
                wave.add(caps.get(above));
            }
            if (below < caps.size()) {
                wave.add(caps.get(below));
            }
            if (wave.isEmpty()) {
                break;
            }
            waves.add(wave);
        }
        final int waveCount = waves.size();
        for (int wi = 0; wi < waveCount; wi++) {
            final java.util.List<CapsuleView> wave = waves.get(wi);
            dock.postDelayed(new Runnable() {
                @Override
                public void run() {
                    for (CapsuleView c : wave) {
                        final CapsuleView cv = c;
                        cv.playAutoSwipeRight(new Runnable() {
                            @Override
                            public void run() {
                                burstParticles(cv);
                            }
                        });
                    }
                }
            }, wi * 200);
        }
        // After the last wave has swiped, pop the card out from the
        // left edge.
        final NotifData data = d;
        dock.postDelayed(new Runnable() {
            @Override
            public void run() {
                showCardAfterRipple(data);
            }
        }, (waveCount - 1) * 200 + 280);
    }

    /** Build the card for the tapped notification and pop it out
     * from the left edge (v150). Runs after the ripple dissipate. */
    private void showCardAfterRipple(NotifData d) {
        if (d == null || dock == null) {
            cardAnimating = false;
            return;
        }
        // Measure the card height on a scratch view (the dock's
        // capsule just burst, so it cannot be measured in place).
        CapsuleView scratch = new CapsuleView(app);
        scratch.bind(d);
        scratch.setCardExpanded(true);
        int h = scratch.cardHeightForWidth(
                dp(CapsuleView.cardWidthDp(app)));
        cardKey = d.key;
        cardHeightPx = h;
        rebuildDock();
        updateWindowSize();
        // v174: an opened music card listens for lyrics too.
        if (d.isMusic) {
            syncLyricListen(d.key);
        }
        final CapsuleView cardCap = findCapsuleByKey(d.key);
        if (cardCap == null) {
            cardAnimating = false;
            return;
        }
        // Pop out from the left: start fully off-screen left and
        // slide to the card's slot (flush at 0 since v156).
        // v159: NO overshoot — user 2026-11-19 "卡片弹出的时候
        // 不要他多弹一下了". The OvershootInterpolator(1.2f) made
        // the card bounce past its slot and spring back (one extra
        // bounce); a plain DecelerateInterpolator lands exactly on
        // the slot and stops.
        // v351: 卡片滑入前先把窗口加宽到卡片宽 + 余量，否则滑入动画
        // 被窗口截断（用户 2026-11-22 平板视频：卡片左边被切掉）。
        // updateWindowSize() 靠 measure，卡片刚重建时量不准；这里直接定宽。
        try {
            int cw = dp(CapsuleView.cardWidthDp(app)) + dp(PIN_OVERHANG_DP);
            if (params != null && params.width < cw) {
                params.width = cw;
                wm.updateViewLayout(root, params);
            }
        } catch (Throwable ignored) {
        }
        final int finalX = dp(CARD_LEFT_OFFSET_DP);
        // v360: 入口一律左滑弹入（用户 “大卡片的动画还是左滑弹入”）——
        // v358 的淡入+放大不要了。
        cardCap.setTranslationX(-dp(CapsuleView.cardWidthDp(app)));
        cardCap.setScaleX(1f);
        cardCap.setScaleY(1f);
        cardCap.setAlpha(1f);
        cardCap.animate()
                .translationX(finalX)
                .setDuration(300)
                .setInterpolator(
                        new android.view.animation.DecelerateInterpolator())
                .withLayer()
                .withEndAction(new Runnable() {
                    @Override
                    public void run() {
                        cardAnimating = false;
                        updateWindowSize();
                    }
                })
                .start();
    }

    /** v153: make sure the card's trailing tail exists (creates
     * it on first use). The tail is a gradient strip in the dock at
     * the card's row, from the screen's left edge (transparent) to
     * the card's tint; it stretches from the left edge to the card's
     * left edge. Its width tracks the card's translationX. */
    private void ensureCardTail(CapsuleView cardCap) {
        // v155: a tail whose view is no longer in the dock (e.g. a
        // rebuild cleared it) is stale — drop it and build a fresh
        // one instead of wrongly reusing the dead reference.
        if (cardTail != null && cardTail.getParent() != dock) {
            cardTail = null;
        }
        // v367: 拖尾归属变了（上次拖的卡片没清掉、这次是另一张卡）
        // 也要丢掉重建 —— 否则新卡片复用旧拖尾，行不对、颜色不
        // 对，看起来就是拖尾和卡片之间有条缝。
        if (cardTail != null && cardTailOwner != null
                && cardTailOwner != cardCap) {
            removeCardTail();
        }
        if (cardTail != null || cardCap == null || dock == null) {
            return;
        }
        final int tailColor = cardCap.getPillColor() != 0
                ? cardCap.getPillColor()
                : android.graphics.Color.argb(204, 30, 32, 38);
        int tailTransparent = android.graphics.Color.argb(0,
                android.graphics.Color.red(tailColor),
                android.graphics.Color.green(tailColor),
                android.graphics.Color.blue(tailColor));
        // v473: 展开卡片的拖尾也要描边（用户 2026-11-23 “展开卡片的
        // 拖尾没有描边”）。拖尾背景改成 EdgeBorderDrawable（跟胶囊
        // 本身一个路子）：渐变底色不变（透明→卡片色）。
        // v474: 拖尾不要单独描边（用户 2026-11-23 “拖尾不要单独描边”
        // “他和展开卡片一个描边”“你把前面展开卡片的描边延长到拖尾
        // 上面”）：拖尾只描顶边和底边（把卡片的边延长到拖尾上），
        // 不描竖边——卡片和拖尾共用一条边，中间没有竖线隔开。
        // 之前 v473 给拖尾描了三边（含靠卡片那头的竖边），截图里
        // 中间那条竖着的黑线就是它，看着像拖尾自己单独描了边。
        EdgeBorderDrawable tailBg = new EdgeBorderDrawable();
        tailBg.setShape(
                android.graphics.drawable.GradientDrawable.RECTANGLE);
        tailBg.setOrientation(
                android.graphics.drawable.GradientDrawable.Orientation
                        .LEFT_RIGHT);
        tailBg.setColors(new int[]{tailTransparent, tailColor});
        int tailBorder = android.graphics.Color.TRANSPARENT;
        try {
            tailBorder = cardCap.getBorderColor();
        } catch (Throwable ignored) {
        }
        tailBg.setBorder(tailBorder, dp(1), true, 0f);
        tailBg.setDrawEnd(false);
        final View tail = new View(app);
        // v366: 拖尾和卡片不重叠 —— 拖尾右端正好顶到卡片左边缘，
        // 不再往卡片底下塞重叠（v364 的 24dp 撤掉，用户 2026-11-22
        // 05:18 "拖尾和卡片的重叠 / 别重叠啊"）。拖尾也不再用
        // LayoutParams 改宽（走 layout 会比卡片的 translationX
        // 慢一帧、快拖出缝）：固定 100dp 宽 + scaleX 拉伸，
        // scaleX 和 translationX 都是渲染属性，同一帧生效，
        // 既没缝也不重叠。
        // v362: the tail sits on the dragged card's OWN row — read
        // top/height from the card's LayoutParams, not the open
        // card's shared cardTopY/cardHeightPx (a big capsule in big
        // card mode is on its own row with its own height; for the
        // open card the LayoutParams hold the same values).
        int tailTop = cardTopY;
        int tailHeight = cardHeightPx;
        if (cardCap.getLayoutParams()
                instanceof FrameLayout.LayoutParams) {
            FrameLayout.LayoutParams clp =
                    (FrameLayout.LayoutParams)
                            cardCap.getLayoutParams();
            tailTop = clp.topMargin;
            if (clp.height > 0) {
                tailHeight = clp.height;
            }
        }
        int baseW = dp(TAIL_BASE_WIDTH_DP);
        FrameLayout.LayoutParams tlp =
                new FrameLayout.LayoutParams(baseW, tailHeight);
        tlp.topMargin = tailTop;
        tlp.leftMargin = 0;
        tail.setLayoutParams(tlp);
        tail.setBackground(tailBg);
        tail.setPivotX(0f);
        tail.setScaleX(baseW > 0
                ? Math.max(0f, cardCap.getTranslationX()) / baseW
                : 0f);
        tail.setClickable(false);
        dock.addView(tail, 0);
        cardTail = tail;
        cardTailOwner = cardCap;
    }

    /** Drop the card's trailing tail, if one is showing. */
    private void removeCardTail() {
        if (cardTail != null) {
            try {
                if (dock != null) {
                    dock.removeView(cardTail);
                }
            } catch (Throwable ignored) {
            }
            cardTail = null;
            cardTailOwner = null;
        }
    }

    /** v364: the auto right-slide's trailing tail (user “自动消散也
     * 没有加拖尾”) — the same gradient strip as the manual drag,
     * but driven by the auto swipe. A new swipe steals the tail
     * from the previous capsule; the end only drops it if it still
     * belongs to that capsule. */
    public void beginAutoSwipeTail(CapsuleView cap) {
        if (cap == null || dock == null) {
            return;
        }
        if (cardTail != null && cardTailOwner != cap) {
            removeCardTail();
        }
        ensureCardTail(cap);
        cardTailOwner = cap;
    }

    public void updateAutoSwipeTail(CapsuleView cap) {
        if (cap == null || cardTail == null || cardTailOwner != cap) {
            return;
        }
        setCardTailWidth(cap.getTranslationX());
    }

    public void endAutoSwipeTail(CapsuleView cap) {
        if (cap != null && cardTail != null && cardTailOwner == cap) {
            removeCardTail();
        }
    }

    /** v366/v367: 把拖尾右端拉到卡片左边缘（translationX），零重叠。
     * 改宽用 scaleX（渲染属性，和卡片的 translationX 同一帧生效）。
     * v367: w 用 float，不再 (int) 截断 —— 截断会丢掉小数部分，
     * 拖尾右端比卡片左边缘短不到 1px，快拖时就是一条细缝。 */
    private void setCardTailWidth(float w) {
        // v366: 拖尾右端 = 卡片左边缘（translationX），不加任何重叠。
        // 改宽用 scaleX（渲染属性，和卡片的 translationX 同一帧
        // 生效），不再走 LayoutParams（会慢一帧出缝）。
        if (cardTail == null) {
            return;
        }
        int baseW = dp(TAIL_BASE_WIDTH_DP);
        float s = baseW > 0 ? Math.max(0f, w) / baseW : 0f;
        if (cardTail.getScaleX() != s) {
            cardTail.setScaleX(s);
        }
    }

    /** v153: manual drag on the open card (the card is an enlarged
     * capsule, user "相当于一个放大版胶囊"). dx is the drag distance
     * in px from the touch-down point. Right: the card slides right
     * from its slot and the tail stretches live from the left edge
     * to the card — the same design as the capsule's
     * growLongCapsule. Left: the tail drops and the card slides
     * left from its slot. */
    private void updateCardDrag(CapsuleView cardCap, float dx) {
        if (cardCap == null || dock == null) {
            return;
        }
        final int startX = dp(CARD_LEFT_OFFSET_DP);
        if (dx <= 0) {
            removeCardTail();
            cardCap.setTranslationX(startX + dx);
            return;
        }
        ensureCardTail(cardCap);
        cardCap.setTranslationX(startX + dx);
        setCardTailWidth(cardCap.getTranslationX());
    }

    /** v153: the manual card drag ended below the dissipate
     * threshold (or was cancelled): spring the card back to its
     * slot and shrink the tail away with it, then shrink the
     * window. (Past the threshold the close already fired via
     * onSwipeLeft → closeCard, so this only runs for a short
     * drag.) */
    private void endCardDrag(final CapsuleView cardCap, float dx) {
        if (cardCap == null || dock == null) {
            return;
        }
        final int startX = dp(CARD_LEFT_OFFSET_DP);
        final float fromX = cardCap.getTranslationX();
        if (fromX == startX) {
            removeCardTail();
            endDragWindowDelayed();
            return;
        }
        android.animation.ValueAnimator anim =
                android.animation.ValueAnimator.ofFloat(0f, 1f);
        anim.setDuration(200);
        anim.setInterpolator(
                new android.view.animation.DecelerateInterpolator());
        anim.addUpdateListener(
                new android.animation.ValueAnimator.AnimatorUpdateListener() {
                    @Override
                    public void onAnimationUpdate(
                            android.animation.ValueAnimator a) {
                        float f = (Float) a.getAnimatedValue();
                        float x = fromX + (startX - fromX) * f;
                        cardCap.setTranslationX(x);
                        setCardTailWidth(x);
                    }
                });
        anim.addListener(
                new android.animation.AnimatorListenerAdapter() {
                    @Override
                    public void onAnimationEnd(
                            android.animation.Animator animator) {
                        removeCardTail();
                        endDragWindowDelayed();
                    }
                });
        anim.start();
    }

    /** v151: close the open card (tap outside / close button /
     * right swipe on the card). The card auto right-swipes away and
     * bursts; then the capsules pop back out from the left edge,
     * rippling outward from the card's old row. NEW in v151: while
     * the card slides right it drags a transparent trailing tail
     * (user "展开卡片也要做透明拖尾" / "因为它也有右滑消散的动画")
     * — a gradient strip from the screen's left edge (transparent)
     * to the card's tint, stretching as the card slides right, same
     * principle as the capsule's drag tail. */
    public void closeCard() {
        if (cardKey == null || cardAnimating || dock == null) {
            return;
        }
        final CapsuleView cardCap = findCapsuleByKey(cardKey);
        if (cardCap == null) {
            closeCardInstant();
            return;
        }
        cardAnimating = true;
        // v157: the card stays fully opaque while it auto-slides
        // right (user 2026-11-19: manual right-swipe is perfect,
        // the auto slide's tail + card opacity are wrong). The v151
        // fade (alpha 1.0 -> 0.3) made the card a ghost while the
        // tail stayed solid — the two no longer matched, so the tail
        // looked disconnected from the card. The manual drag never
        // fades the card, so the auto slide must not either.
        cardCap.setAlpha(1f);
        // The card's row: the capsules pop back out rippling
        // outward from here.
        final int cardRowTop = cardTopY;
        // The card's key, so the pop-out knows which capsule is the
        // card itself (it pops back out even if it is pinned).
        final String closedCardKey = cardKey;
        // Widen the window so the card's right slide stays visible
        // (the narrow window the auto-swipe uses).
        beginAutoSwipeWindow();
        // v153: start from wherever the card is — a manual drag may
        // already have it part-way right (don't snap back to the
        // slot before sliding away).
        final int startX = (int) cardCap.getTranslationX();
        final int endX = startX + dp(200);
        // v151/v153: the transparent trailing tail. Reuse the one
        // from a manual drag if it exists, else create it.
        ensureCardTail(cardCap);
        android.animation.ValueAnimator anim =
                android.animation.ValueAnimator.ofFloat(0f, 1f);
        anim.setDuration(240);
        anim.setInterpolator(
                new android.view.animation.DecelerateInterpolator());
        anim.addUpdateListener(
                new android.animation.ValueAnimator.AnimatorUpdateListener() {
                    @Override
                    public void onAnimationUpdate(
                            android.animation.ValueAnimator a) {
                        float f = (Float) a.getAnimatedValue();
                        cardCap.setTranslationX(
                                startX + (endX - startX) * f);
                        // v157: no alpha fade — the card stays at
                        // 1.0 (like the manual drag) so the tail and
                        // the card keep the same opacity throughout.
                        // The tail stretches from the left edge to
                        // the card's left edge as it slides right.
                        setCardTailWidth(cardCap.getTranslationX());
                    }
                });
        anim.addListener(
                new android.animation.AnimatorListenerAdapter() {
                    @Override
                    public void onAnimationEnd(
                            android.animation.Animator animator) {
                        // Burst the card, drop the tail, then — after
                        // the particles have flown (same 310ms as
                        // dissipateOne, because the rebuild clears
                        // the dock and would cut the burst short) —
                        // bring the capsules back.
                        burstParticles(cardCap);
                        removeCardTail();
                        dock.postDelayed(new Runnable() {
                            @Override
                            public void run() {
                                closeCardInstant();
                                popCapsulesOutFromLeft(cardRowTop,
                                        closedCardKey);
                                cardAnimating = false;
                            }
                        }, 310);
                    }
                });
        anim.start();
    }

    /** v151: after the card dissipates, the capsules pop back out
     * from the left edge, rippling outward from the card's old row.
     * Only the capsules that actually dissipated pop back out: the
     * unpinned, non-call ones, plus the card's own capsule (it pops
     * back even if it is pinned). Pinned capsules (other than the
     * card) and calls never left, so they stay put. */
    private void popCapsulesOutFromLeft(final int cardRowTop,
            final String closedCardKey) {
        if (dock == null) {
            return;
        }
        final java.util.List<CapsuleView> caps =
                new java.util.ArrayList<>();
        for (int i = 0; i < dock.getChildCount(); i++) {
            View v = dock.getChildAt(i);
            if (v instanceof CapsuleView) {
                CapsuleView c = (CapsuleView) v;
                NotifData cd = c.getData();
                if (cd == null || cd.isCall) {
                    continue;
                }
                boolean isCard = closedCardKey != null
                        && closedCardKey.equals(cd.key);
                if (!isCard) {
                    NotifGroup g = (NotifGroup) c.getTag();
                    if (g != null && cd.key != null
                            && g.pinnedAtByKey.containsKey(cd.key)) {
                        continue;
                    }
                }
                caps.add(c);
            }
        }
        if (caps.isEmpty()) {
            return;
        }
        for (final CapsuleView c : caps) {
            int top = topOf(c);
            int dist = 0;
            if (cardRowTop >= 0) {
                dist = Math.abs(top - cardRowTop)
                        / Math.max(1, dp(CapsuleView.DEFAULT_HEIGHT_DP
                                + CAPSULE_GAP_DP));
            }
            c.setTranslationX(-dp(200));
            c.setAlpha(1f);
            c.animate().translationX(0f)
                    .setStartDelay(dist * 35L)
                    .setDuration(280)
                    .setInterpolator(
                            new android.view.animation.DecelerateInterpolator())
                    .withLayer()
                    .start();
        }
        updateWindowSize();
    }

    /** Drop the card state and rebuild the plain layout. No
     * animation — used when the card view is gone, or after the
     * close animation already put everything home. */
    private void closeCardInstant() {
        // v174: re-sync lyric listening for the closed card's key -
        // the card is gone, so listening continues only if pinned.
        String closedKey = cardKey;
        cardKey = null;
        cardHeightPx = 0;
        // v151: drop a leftover card tail (close interrupted).
        removeCardTail();
        rebuildDock();
        if (closedKey != null) {
            syncLyricListen(closedKey);
        }
    }

    /** Wire a card capsule (called from rebuildDock when it places
     * the card). v162: the open buttons are gone — the header pin
     * icon toggles pin/unpin instead; the X is gone too (close by
     * tapping outside or swiping right). */
    private void bindCardCapsule(final CapsuleView cap,
            final NotifData d, final NotifGroup group) {
        // v153: the card is an enlarged capsule — wire its manual
        // drag (live tail) to BubbleWindow.
        cap.setOnCardDragListener(
                new CapsuleView.OnCardDragListener() {
                    @Override
                    public void onCardDrag(float dx) {
                        updateCardDrag(cap, dx);
                    }

                    @Override
                    public void onCardDragEnd(float dx) {
                        endCardDrag(cap, dx);
                    }
                });
        cap.setCardPinned(group != null && d != null && d.key != null
                && group.pinnedAtByKey.containsKey(d.key));
        cap.setOnCardPinToggle(new Runnable() {
            @Override
            public void run() {
                togglePin(group, d);
                // v180: pinning a card rebuilds the dock so the card
                // jumps to first position immediately (user 2026-11-20
                // "钉住卡片也立即排到第一位"). v163's in-place pin is
                // gone.
                rebuildDock();
            }
        });
        cap.setOnCardClose(new Runnable() {
            @Override
            public void run() {
                closeCard();
            }
        });
        // v171: music card controls — send the command to the module
        // process, which owns the MediaController.
        // v459: 模拟音乐卡片的控制是摆设——假胶囊没有 MediaSession，
        // 点播放/上一首不能去动真音乐（真在放的歌会被它切掉）。
        // 接空操作，只翻本地图标，不发广播。
        if (d != null && SIM_MUSIC_KEY.equals(d.key)) {
            cap.setOnMusicControl(new CapsuleView.OnMusicControlListener() {
                @Override
                public void onMusicControl(String command, long seekMs) {
                }
            });
        } else {
            cap.setOnMusicControl(new CapsuleView.OnMusicControlListener() {
                @Override
                public void onMusicControl(String command, long seekMs) {
                    sendMusicControl(command, seekMs);
                }
            });
        }
    }

    /** v360: 大卡片模式的放大胶囊——普通通知（非音乐/来电/下载/
     * 实况）直接以卡片内容显示，不经过展开卡片。 */
    private boolean isBigCapsule(NotifData d) {
        return FeatureFlags.bigCard && d != null
                && !d.isMusic && !d.isCall && !d.isDownload
                && !d.isLive;
    }

    /** v360: 给放大胶囊接卡片头的钉住开关（只在钉住时显示）。
     * v362: 接上和展开卡片同一套手动拖拽（带透明渐变拖尾）——
     * 用户 “大卡片模式也要有透明渐变拖尾”。updateCardDrag /
     * endCardDrag 只认卡片自己的位置（translationX 和
     * LayoutParams 的 topMargin、height），不认展开卡片的共享
     * 字段，所以放大胶囊的拖尾长在自己那一行。左滑仍走胶囊自己
     * 的 onSwipeRight（v361 收进扇子）。 */
    private void bindBigCapsule(final CapsuleView cap,
            final NotifData d, final NotifGroup group) {
        // v362: 手动右拖带透明渐变拖尾（和展开卡片同一套）。
        cap.setOnCardDragListener(
                new CapsuleView.OnCardDragListener() {
                    @Override
                    public void onCardDrag(float dx) {
                        updateCardDrag(cap, dx);
                    }

                    @Override
                    public void onCardDragEnd(float dx) {
                        endCardDrag(cap, dx);
                    }
                });
        cap.setCardPinned(group != null && d != null && d.key != null
                && group.pinnedAtByKey.containsKey(d.key));
        cap.setOnCardPinToggle(new Runnable() {
            @Override
            public void run() {
                togglePin(group, d);
                rebuildDock();
            }
        });
    }

    /** v171: send a music control command to the module process. */
    private void sendMusicControl(String command, long seekMs) {
        try {
            android.content.Intent intent = new android.content.Intent(
                    NotificationService.ACTION_MUSIC_CONTROL);
            intent.putExtra("command", command);
            if (seekMs >= 0) {
                intent.putExtra("seek_position", seekMs);
            }
            app.sendBroadcast(intent);
        } catch (Throwable ignored) {
        }
    }

    /** v171: progress tick from the module — update the open music
     * card's bar, times and play/pause icon (only when the open
     * card is the music notification for this key). */
    public void onMusicProgress(String key, long positionMs,
            long durationMs, boolean playing, boolean favorite,
            boolean hasFavorite) {
        try {
            // v478: 反方向的钟——播放状态变了就同步跑马灯：开关开着
            // 且正在放音乐才亮，暂停/停了就灭。
            if (playing != musicLedPlaying) {
                musicLedPlaying = playing;
                if (playing) {
                    // v507: not while a game/video scene is up.
                    if (FeatureFlags.musicLed && !sceneSuppressed) {
                        showMusicLed();
                    }
                } else {
                    hideMusicLed();
                }
            }
            // v332: the old cardKey gate ("only update while a card
            // is open") starved the COLLAPSED music capsule — with
            // no card open cardKey is null, so updateMusicProgress
            // was never called and the capsule's volume bars sat at
            // minimum ("....", user 2026-11-21 "音量条没动" three
            // times, across the Handler and the ValueAnimator
            // rewrites — the animator was never the problem). The
            // capsule's motion (notes F, volume bars, breathing C)
            // lives on the collapsed capsule and needs the 1s tick
            // whether a card is open or not. Find the music capsule
            // by key and update it either way; the card UI (seek /
            // time) is harmless when the card is closed.
            if (key == null) {
                return;
            }
            if (dock == null) {
                return;
            }
            for (int i = 0; i < dock.getChildCount(); i++) {
                android.view.View v = dock.getChildAt(i);
                if (v instanceof CapsuleView) {
                    CapsuleView cap = (CapsuleView) v;
                    NotifData cd = cap.getData();
                    if (cd != null && key.equals(cd.key)
                            && cd.isMusic) {
                        cap.updateMusicProgress(positionMs, durationMs,
                                playing);
                        if (hasFavorite) {
                            cap.setMusicFavorite(favorite);
                        }
                        return;
                    }
                }
            }
        } catch (Throwable ignored) {
        }
    }




    /** v204: full music state from the session (1.0.5
     * sendMusicStateFromController) — a manual skip changes the
     * session metadata immediately, so the card's title/artist
     * follow without waiting for a new notification. Only the
     * matching music capsule is updated, and only when the
     * broadcast title/artist actually differ.
     * v212 (user 2026-11-20, Flyme only): the old guard returned
     * early unless a card was open, so the dock capsule stayed on
     * the old song until the user expanded the card and shrank it
     * back ("要我展开卡片之后缩小他才变"). For Flyme, update the
     * capsule even with no card open.
     * v217 (user 2026-11-20, generic): the same staleness was
     * reported for QQ Original / NetEase / Qishui ("胶囊切歌不
     * 刷新" / "只有点卡卡片才会一起刷新") — the session knew the
     * new song but the capsule never heard it without an open
     * card. The guard is gone for ALL music now: the capsule
     * follows the session whether or not a card is open. The
     * key+isMusic match below still scopes the update to the
     * one music capsule for this key. */
    public void onMusicState(String key, String title, String artist) {
        try {
            if (key == null) {
                return;
            }
            if (dock == null) {
                return;
            }
            for (int i = 0; i < dock.getChildCount(); i++) {
                android.view.View v = dock.getChildAt(i);
                if (v instanceof CapsuleView) {
                    CapsuleView cap = (CapsuleView) v;
                    NotifData cd = cap.getData();
                    if (cd != null && key.equals(cd.key)
                            && cd.isMusic) {
                        boolean changed = false;
                        if (title != null && !title.isEmpty()
                                && !title.equals(cd.title)) {
                            cap.updateTitle(title);
                            changed = true;
                        }
                        if (artist != null && !artist.isEmpty()
                                && !artist.equals(cd.text)) {
                            cap.updateText(artist);
                            changed = true;
                        }
                        if (changed) {
                            cap.updateMusicCard();
                        }
                        return;
                    }
                }
            }
        } catch (Throwable ignored) {
        }
    }

    /** v218: live session album art for the music capsule (the
     * controller's art, pushed when it first appears for a song).
     * Sets the capsule's art in place — icon, tint and card disc —
     * without waiting for a notification re-post. The card is the
     * capsule grown, so findCapsuleByKey covers the open card too;
     * a docked (fan) music item has no visible capsule to update. */
    /** v262: apply the scroll switch to every visible capsule
     * at once, so flipping it stops (or restarts) the lyric
     * scrolling immediately. */
    public void refreshLyricsScroll() {
        if (dock == null) {
            return;
        }
        for (int i = 0; i < dock.getChildCount(); i++) {
            View v = dock.getChildAt(i);
            if (v instanceof CapsuleView) {
                ((CapsuleView) v).applyScrollFlag();
            }
        }
    }

    /** v465: 胶囊自动取色开关拨动后，把已经弹出来的胶囊按新开关
     * 重新上色（跟 refreshLyricsScroll 一个路子）。 */
    public void refreshCapsuleTint() {
        if (dock == null) {
            return;
        }
        for (int i = 0; i < dock.getChildCount(); i++) {
            View v = dock.getChildAt(i);
            if (v instanceof CapsuleView) {
                ((CapsuleView) v).refreshTint();
            }
        }
    }

    public void onMusicArt(String key,
            android.graphics.Bitmap art) {
        try {
            if (key == null || art == null || dock == null) {
                return;
            }
            CapsuleView cap = findCapsuleByKey(key);
            if (cap != null) {
                NotifData cd = cap.getData();
                if (cd != null && cd.isMusic) {
                    cap.updateArt(art);
                }
            }
        } catch (Throwable ignored) {
        }
    }

    /** v154: manual right-swipe on the open card dismisses the
     * notification (user "展开卡片手动右滑就消掉通知"). Like a
     * capsule's manual right-swipe (dissipateOne): the notification
     * leaves the bubble group, so no capsule pops back when the
     * card closes. The card still slides right with its tail and
     * bursts (closeCard) — it just doesn't come back. */
    private void dismissOpenCard(NotifData d) {
        if (d == null || d.key == null) {
            closeCard();
            return;
        }
        removeNotifByKey(d.key);
        closeCard();
    }

    /** v360: 大卡片模式点空白——把开着的卡片自动右滑消散成扇子
     * 图标，不缩回胶囊（用户 2026-11-22 “别再回到胶囊了”）。
     * 复用 closeCard 的右滑+爆掉动画；先把通知从组里拿掉（和
     * dismissOpenCard 一样），这样卡片关掉后它不会再弹回成胶囊；
     * 等动画收尾（滑走 240ms + 310ms）后再把这条通知收进扇子，
     * 图标位置才算得准。系统通知不取消——消散不是消通知。 */
    private void dissipateOpenCard() {
        if (cardKey == null || cardAnimating || dock == null) {
            return;
        }
        final CapsuleView cardCap = findCapsuleByKey(cardKey);
        if (cardCap == null) {
            closeCardInstant();
            return;
        }
        final NotifData d = cardCap.getData();
        if (d == null || d.key == null) {
            closeCard();
            return;
        }
        removeNotifByKey(d.key);
        final String pkg = d.pkg;
        final NotifData removed = d;
        // v457: 自动销毁——开着时，消散的卡片通知同时撤销，
        // 不进扇子（音乐不撤销，撤销会停掉音乐，v276 同款）。
        final boolean destroy = FeatureFlags.autoDestroy && !d.isMusic;
        if (destroy) {
            dismissNotification(d.key);
        }
        closeCard();
        dock.postDelayed(new Runnable() {
            @Override
            public void run() {
                if (destroy) {
                    return;
                }
                NotifGroup docked = new NotifGroup(pkg);
                docked.add(removed);
                addPileIconForGroup(docked);
            }
        }, 600);
    }

    /** Remove one notification (by key) from the bubble groups,
     * synchronously. Drops its pin if it was pinned; drops the
     * group if it becomes empty. (dissipateOne does this after a
     * 310ms delay; the card dismiss needs it now, before
     * closeCard's rebuildDock, so the capsule can't pop back.) */
    private void removeNotifByKey(String key) {
        if (key == null) {
            return;
        }
        for (int gi = groups.size() - 1; gi >= 0; gi--) {
            NotifGroup g = groups.get(gi);
            if (g == null) {
                continue;
            }
            boolean removed = false;
            for (int i = g.items.size() - 1; i >= 0; i--) {
                NotifData it = g.items.get(i);
                if (it != null && key.equals(it.key)) {
                    g.items.remove(i);
                    removed = true;
                    break;
                }
            }
            if (removed) {
                // v238: dropping a removed notification's stay-put
                // record (and pinned-card mark) too.
                stayPutPinTime.remove(key);
                pinnedCardKeys.remove(key);
                if (g.pinnedAtByKey.remove(key) != null) {
                    g.sortItems();
                    if (g.pinnedAtByKey.isEmpty()) {
                        g.pinned = false;
                        g.pinnedAt = 0;
                    } else {
                        g.pinnedAt = earliestPinTime(g);
                    }
                }
                if (g.items.isEmpty()) {
                    groups.remove(gi);
                }
                break;
            }
        }
    }

    /** Ask the module (the NotificationListenerService) to cancel a
     * notification by key — SystemUI cannot cancel another app's
     * notification itself. */
    private void dismissNotification(String key) {
        if (key == null || app == null) {
            return;
        }
        try {
            android.content.Intent intent = new android.content.Intent(
                    "com.chenan.flymebobo.DISMISS");
            intent.setPackage("com.chenan.flymebobo");
            intent.putExtra("key", key);
            try {
                android.os.Bundle opts =
                        android.app.BroadcastOptions.makeBasic()
                                .setShareIdentityEnabled(true).toBundle();
                app.sendBroadcast(intent, null, opts);
            } catch (Throwable t) {
                app.sendBroadcast(intent);
            }
        } catch (Throwable ignored) {
        }
    }

    // ---- Open app (v143, ported whole from 1.x BubbleManager,
    // fixed14/88/143 — user 2026-11-18 "把代码完整拿过来，别做改变
    // 了" / "照搬改成我们能用的"). freeform = small window. ----
    private boolean openPendingIntent(
            android.app.PendingIntent pi, String pkg, boolean freeform) {
        if (pi == null) {
            return false;
        }
        try {
            android.os.Bundle opts = makeFreeformOptions(pkg, freeform);
            try {
                if (opts != null) {
                    pi.send(app, 0, null, null, null, null, opts);
                } else {
                    pi.send();
                }
                return true;
            } catch (Throwable th) {
                try {
                    pi.send();
                    return true;
                } catch (Throwable ignored) {
                    return false;
                }
            }
        } catch (Throwable ignored) {
            return false;
        }
    }

    private boolean openIntent(
            android.content.Intent intent, boolean freeform) {
        if (intent == null) {
            return false;
        }
        try {
            intent.addFlags(
                    android.content.Intent.FLAG_ACTIVITY_NEW_TASK);
            String targetPkg = intent.getPackage();
            if (targetPkg == null && intent.getComponent() != null) {
                targetPkg = intent.getComponent().getPackageName();
            }
            android.os.Bundle opts =
                    makeFreeformOptions(targetPkg, freeform);
            if (opts != null) {
                app.startActivity(intent, opts);
            } else {
                app.startActivity(intent);
            }
            return true;
        } catch (Throwable th) {
            try {
                app.startActivity(intent);
                return true;
            } catch (Throwable ignored) {
                return false;
            }
        }
    }

    private boolean prepareFlymeMiniWindow(String pkg) {
        if (pkg == null) {
            return false;
        }
        try {
            Class<?> cls =
                    Class.forName("flyme.view.WindowManagerExt");
            Object inst = cls.getMethod("getInstance",
                    Context.class).invoke(null, app);
            if (inst == null) {
                return false;
            }
            cls.getMethod("setStartWindowMode", String.class)
                    .invoke(inst, pkg);
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private void clearFlymeMiniWindow(String pkg) {
        if (pkg == null) {
            return;
        }
        try {
            Class<?> cls =
                    Class.forName("flyme.view.WindowManagerExt");
            Object inst = cls.getMethod("getInstance",
                    Context.class).invoke(null, app);
            if (inst == null) {
                return;
            }
            try {
                cls.getMethod("setStartWindowMode", String.class,
                        Boolean.TYPE).invoke(inst, pkg, Boolean.FALSE);
            } catch (Throwable ignored) {
            }
        } catch (Throwable ignored) {
        }
    }

    private android.os.Bundle makeFreeformOptions(
            String pkg, boolean freeform) {
        final int WINDOWING_MODE_FULLSCREEN = 1;
        final int WINDOWING_MODE_FREEFORM = 5;
        if (!freeform) {
            // Fullscreen: clear any persisted mini-window mode, then
            // launch with explicit fullscreen windowing mode (fixed143
            // all-ROM background-start opt-in).
            clearFlymeMiniWindow(pkg);
            try {
                android.app.ActivityOptions opts =
                        android.app.ActivityOptions.makeBasic();
                try {
                    android.app.ActivityOptions.class.getMethod(
                            "setLaunchWindowingMode", Integer.TYPE)
                            .invoke(opts, WINDOWING_MODE_FULLSCREEN);
                } catch (Throwable ignored) {
                }
                if (android.os.Build.VERSION.SDK_INT >= 34) {
                    try {
                        opts.setPendingIntentBackgroundActivityStartMode(1);
                    } catch (Throwable ignored) {
                    }
                } else {
                    try {
                        android.app.ActivityOptions.class.getMethod(
                                "setPendingIntentBackgroundActivityStartMode",
                                Integer.TYPE).invoke(opts, 1);
                    } catch (Throwable ignored) {
                    }
                }
                return opts.toBundle();
            } catch (Throwable ignored) {
                return null;
            }
        }
        try {
            android.app.ActivityOptions opts =
                    android.app.ActivityOptions.makeBasic();
            // Flyme path first: proprietary small window.
            if (prepareFlymeMiniWindow(pkg)) {
                if (android.os.Build.VERSION.SDK_INT >= 34) {
                    try {
                        opts.setPendingIntentBackgroundActivityStartMode(1);
                    } catch (Throwable ignored) {
                    }
                }
                android.os.Bundle bundle = opts.toBundle();
                bundle.putBoolean("start_windowmode", true);
                return bundle;
            }
            // Standard Android freeform fallback (non-Flyme devices).
            float f = 1080f;
            float f2 = 2400f;
            try {
                android.util.DisplayMetrics dm =
                        app.getResources().getDisplayMetrics();
                f = dm.widthPixels;
                f2 = dm.heightPixels;
            } catch (Throwable ignored) {
            }
            android.graphics.Rect rect = new android.graphics.Rect(
                    Math.round(0.075f * f), Math.round(0.175f * f2),
                    Math.round(f * 0.925f), Math.round(f2 * 0.825f));
            opts.setLaunchBounds(rect);
            try {
                android.app.ActivityOptions.class.getMethod(
                        "setLaunchWindowingMode", Integer.TYPE)
                        .invoke(opts, WINDOWING_MODE_FREEFORM);
            } catch (Throwable ignored) {
            }
            return opts.toBundle();
        } catch (Throwable ignored) {
            return null;
        }
    }

    /**
     * Arrange the capsules DOWNWARD by time: newest at the top, older
     * ones below, each one CAPSULE_GAP below the previous. The dock is
     * a free layout, so each capsule is placed by its own top margin.
     */
    private void layoutCapsules() {
        rebuildDock();
    }

    public void dismissCapsule(CapsuleView cap) {
        if (cap == null) {
            return;
        }
        NotifGroup group = (NotifGroup) cap.getTag();
        if (group == null) {
            return;
        }
        // A manual dismiss is an active dock: the whole group
        // converges into a notification icon in the fan.
        dockGroup(group);
    }

    /**
     * RIGHT SWIPE — dock (user 2026-11-18 "这个是我主动贴合的功能"
     * / "也就是右滑贴合功能" / "右滑汇聚成通知图标"): the user
     * actively docks a notification (swipe toward the edge), one at
     * a time. That notification converges into a notification icon,
     * joining the fan as the newest; the survivors move up and the
     * fan gathers below the next anchor. The icon can ONLY gather
     * BELOW the capsule notification — it must not jump over it
     * ("图标只能汇聚在胶囊通知下面 / 不能跃过"). This is the manual
     * trigger; dissipateAllOneByOne() just plays the same step
     * repeatedly.
     */
    /**
     * Add (or refresh) the fan icon for a group being docked. One icon
     * per app (DockedPile dedups by pkg). The group is remembered in
     * dockedGroups so tapping the icon expands it back out. Returns the
     * icon (for the converge animation) or null.
     */
    private PileIcon addPileIconForGroup(NotifGroup group) {
        if (group == null || pile == null) {
            return null;
        }
        // Merge into the already-docked group for this app, if any, so
        // earlier docked notifications are not lost when the same app
        // docks again.
        NotifGroup stored = dockedGroups.get(group.pkg);
        if (stored != null && stored != group) {
            for (NotifData d : group.items) {
                if (d != null && !stored.hasKey(d.key)) {
                    stored.add(d);
                }
            }
            group = stored;
        } else {
            dockedGroups.put(group.pkg, group);
        }
        NotifData latest = group.latest();
        if (latest == null) {
            return null;
        }
        final String pkg = group.pkg;
        PileIcon icon = new PileIcon(app, dockLeft);
        icon.setPkg(pkg);
        // v24: load the app icon here in SystemUI (v1 does it this
        // way) — no Bitmap is passed across processes any more.
        try {
            android.graphics.drawable.Drawable d =
                    app.getPackageManager().getApplicationIcon(pkg);
            if (d != null) {
                icon.setIcon(d);
            }
        } catch (Throwable ignored) {
        }
        icon.setOnTap(new Runnable() {
            @Override
            public void run() {
                expandAllDockedGroups();
            }
        });
        setupPileIconDrag(icon);
        pile.addIcon(icon);
        // v459: 扇子出现/更新——先恢复不透明，再重计 5 秒闲置变淡。
        cancelFanDim();
        scheduleFanDim();
        // Resize the pile window right away (v25): it must never be
        // left 1x1 while icons exist, even if a caller forgets
        // rebuildDock.
        updatePileWindow();
        return icon;
    }

    /** Tap a pile icon: expand ALL docked notifications back out
     * (user 2026-11-19 "点击贴合图标展开全部通知") — every app's
     * docked group comes back, not just the tapped icon's app.
     * Staged animation (user 2026-11-19 "展开动画为先向下排列图标
     * 然后由第一个图标依次向下展开通知" / "通知胶囊从贴合方向弹出来"):
     * (1) the pile icons first arrange downward into a vertical
     * column; (2) then, from the first icon, each group's capsules
     * expand downward one by one, each capsule sliding in from the
     * docked direction (the left edge). */
    private void expandAllDockedGroups() {
        if (dockedGroups.isEmpty()) {
            return;
        }
        // v459: 扇子被点了——恢复不透明（别让展开动画带着半透明跑），
        // 取消变淡计时；展开后扇子清空，计时也不用再跑。
        cancelFanDim();
        final java.util.List<NotifGroup> all =
                new java.util.ArrayList<>(dockedGroups.values());
        Collections.sort(all, new Comparator<NotifGroup>() {
            @Override
            public int compare(NotifGroup a, NotifGroup b) {
                return Long.compare(b.latestTime(), a.latestTime());
            }
        });
        dockedGroups.clear();
        // Phase 1 — pile icons arrange downward, then fade out.
        animatePileIconsDownward(new Runnable() {
            @Override
            public void run() {
                // Phase 2 — groups back, capsules cascade in one by one.
                // Merge by pkg: if a group for this app is already
                // out (a new notification reclaimed it), fold the
                // docked items into it instead of adding a second
                // group for the same app.
                if (pile != null) {
                    pile.clearIcons();
                }
                for (NotifGroup g : all) {
                    if (g == null) {
                        continue;
                    }
                    // v306: WeChat/QQ docked messages come back as
                    // separate capsules too — split the stored group
                    // into one group per message.
                    boolean gNoStack =
                            "com.tencent.mm".equals(g.pkg)
                            || "com.tencent.mobileqq".equals(g.pkg);
                    if (gNoStack && g.items.size() > 1) {
                        for (NotifData d : new java.util.ArrayList<>(
                                g.items)) {
                            if (d == null) {
                                continue;
                            }
                            NotifGroup one = new NotifGroup(g.pkg);
                            one.add(d);
                            one.expanded = true;
                            groups.add(one);
                        }
                        continue;
                    }
                    NotifGroup existing = null;
                    for (NotifGroup eg : groups) {
                        if (eg.pkg != null && eg.pkg.equals(g.pkg)) {
                            existing = eg;
                            break;
                        }
                    }
                    if (existing != null && existing != g) {
                        for (NotifData d : g.items) {
                            if (d != null && !existing.hasKey(d.key)) {
                                existing.add(d);
                            }
                        }
                        // Re-expanded: show spread out, not stacked
                        // (user 2026-11-19 "你他么叠放了啊").
                        existing.expanded = true;
                    } else if (!groups.contains(g)) {
                        // Re-expanded: show spread out, not stacked.
                        g.expanded = true;
                        groups.add(g);
                    }
                }
                Collections.sort(groups, new Comparator<NotifGroup>() {
                    @Override
                    public int compare(NotifGroup a, NotifGroup b) {
                        // Pinned groups stay at the very front (user
                        // 2026-11-19 "钉住的通知自动排在最前面后，重新
                        // 展开的其他通知只能由它后面开始排列"): pinned
                        // first, then by latest time within each side,
                        // so re-expanded groups can only land behind
                        // the pinned ones.
                        if (a.pinned != b.pinned) {
                            return a.pinned ? -1 : 1;
                        }
                        if (a.pinned) {
                            return Long.compare(a.pinnedAt, b.pinnedAt);
                        }
                        return Long.compare(b.latestTime(), a.latestTime());
                    }
                });
                rebuildDock();
                cascadeCapsulesIn(all);
                updatePileWindow();
            }
        });
    }

    /** Phase 1 of the expand animation: the pile (fan) icons arrange
     * downward into a vertical column (newest/anchor on top, older
     * ones below), then the whole pile fades out. Calls onDone when
     * finished. The pile window is grown tall first so the icons are
     * not clipped while they move down. */
    private void animatePileIconsDownward(final Runnable onDone) {
        if (pile == null || pile.getChildCount() == 0) {
            if (onDone != null) {
                onDone.run();
            }
            return;
        }
        final int n = pile.getChildCount();
        final int step = dp(40);
        // Grow the pile window tall enough for the vertical column,
        // and wide enough that the icons (34dp) are never clipped at
        // the right edge while they slide to X=0.
        if (pileWindowAdded && pileParams != null && wm != null) {
            pileParams.height = n * step + dp(8);
            // v111: min 48dp (was 40dp) so the 34dp icon has room.
            pileParams.width = Math.max(pileParams.width, dp(48));
            try {
                wm.updateViewLayout(pileRoot, pileParams);
            } catch (Throwable ignored) {
            }
        }
        // Each icon moves from its fan slot into the column: the
        // leftmost (slot 0, newest/anchor) goes to the top row, older
        // ones below. Slot is read from the icon's actual position so
        // the order follows the fan, not the child order.
        // v458 (user "扇子竖着点击展开动画和横向一样" / "图标先依次
        // 移动到胶囊展开位置去，然后胶囊再弹出来"): 纵向时扇子图标
        // 是往下叠的（topMargin），X 全是 0——按 X 算 slot 全是 0，
        // 图标挤在顶行一起淡出，跟横向不一样。纵向按 Y 算 slot，
        // 图标才依次移到各自的胶囊展开位置（40dp 一档），再淡出、
        // 胶囊弹出，跟横向一个路子。
        final boolean vertical = Appearance.fanDirection
                == Appearance.FAN_DIR_VERTICAL;
        float stepPx = 1f;
        try {
            stepPx = pile.stepPx();
        } catch (Throwable ignored) {
        }
        long maxDelay = 0;
        for (int i = 0; i < n; i++) {
            View icon = pile.getChildAt(i);
            if (icon == null) {
                continue;
            }
            int slot = vertical
                    ? (stepPx > 0
                            ? Math.round(icon.getY() / stepPx) : i)
                    : (stepPx > 0
                            ? Math.round(icon.getX() / stepPx) : i);
            if (slot < 0) {
                slot = 0;
            }
            if (slot >= n) {
                slot = n - 1;
            }
            long delay = slot * 45L;
            if (delay > maxDelay) {
                maxDelay = delay;
            }
            // v459 (user "扇子竖着展开图标间隔有点远了" / "要跟随胶囊
            // 弹出位置" / "截断图标了"): 纵向时图标已经带着 topMargin
            // （gi * stepPx，往下叠的），再平移 slot * 40dp，落点
            // 是 slot * (stepPx + 40dp) ≈ slot * 58dp——比胶囊行
            // （40dp 一档）宽，越往下越偏，末尾几颗顶出窗口底被截断。
            // 落点改成胶囊行本身：translationY = slot * 40dp - 当前 Y，
            // 横向当前 Y 是 0（和原来一样），纵向扣掉已有的 topMargin，
            // 两边都落在 slot * 40dp，40dp 间距，跟胶囊弹出位置走，
            // 窗口 n * 40dp + 8dp 也够装，不再截断。
            // v111: no withLayer() here — a hardware layer is
            // sized to the 22dp frame and can clip the 34dp icon's
            // 12dp overflow on tap.
            icon.animate()
                    .translationX(-icon.getX())
                    .translationY(slot * step - icon.getY())
                    .setDuration(260)
                    .setStartDelay(delay)
                    .start();
        }
        // Fade the pile out, then hand over to phase 2.
        if (pileRoot != null) {
            pileRoot.animate().alpha(0f).setDuration(180)
                    .setStartDelay(260L + maxDelay)
                    .withLayer()
                    .withEndAction(new Runnable() {
                        @Override
                        public void run() {
                            pileRoot.setAlpha(1f);
                            if (onDone != null) {
                                onDone.run();
                            }
                        }
                    }).start();
        } else if (onDone != null) {
            onDone.run();
        }
    }

    /** Phase 2 of the expand animation: the capsules of each group
     * expand downward one by one, starting from the first (top)
     * group. Every capsule slides in from the docked direction (the
     * left edge) and fades in. Groups are staggered so they appear
     * in order, top to bottom. */
    private void cascadeCapsulesIn(java.util.List<NotifGroup> order) {
        if (dock == null || order == null || order.isEmpty()) {
            return;
        }
        // Collect the dock's capsules per group, in dock order.
        // Pinned capsules are skipped: they do not participate in
        // group arrangement (user 2026-11-19 "钉住的不参与分组排列"),
        // so expanding/collapsing a group only moves its unpinned
        // items; pinned rows stay fixed in pin order.
        final java.util.Map<NotifGroup, java.util.List<CapsuleView>> byGroup =
                new java.util.HashMap<>();
        for (int i = 0; i < dock.getChildCount(); i++) {
            View v = dock.getChildAt(i);
            if (!(v instanceof CapsuleView)) {
                continue;
            }
            Object tag = v.getTag();
            if (!(tag instanceof NotifGroup)) {
                continue;
            }
            NotifGroup g = (NotifGroup) tag;
            CapsuleView cv = (CapsuleView) v;
            if (cv.getData() != null && cv.getData().key != null
                    && g.pinnedAtByKey.containsKey(cv.getData().key)) {
                continue;
            }
            java.util.List<CapsuleView> list = byGroup.get(g);
            if (list == null) {
                list = new java.util.ArrayList<>();
                byGroup.put(g, list);
            }
            list.add((CapsuleView) v);
        }
        // One running counter across ALL capsules: every capsule
        // pops out one by one, 110ms apart (user 2026-11-19 05:52
        // "同应用不同通知也要逐条弹出不要一起弹出"). Within a group
        // the dock's child order is oldest-first (rebuildDock adds
        // old first), so iterate in REVERSE (newest/top first) to keep
        // the top-to-bottom expand direction ("由上往下展开").
        int index = 0;
        for (NotifGroup g : order) {
            java.util.List<CapsuleView> caps = byGroup.get(g);
            if (caps == null || caps.isEmpty()) {
                continue;
            }
            for (int i = caps.size() - 1; i >= 0; i--) {
                CapsuleView cap = caps.get(i);
                long delay = index * 110L;
                index++;
                cap.setTranslationX(-dp(200));
                cap.setAlpha(0f);
                cap.animate().translationX(0f).alpha(1f)
                        .setDuration(240)
                        .setStartDelay(delay)
                        .withLayer()
                        .start();
            }
        }
    }

    /** Tap a pile icon: expand that app's docked notifications back out. */
    private void expandDockedGroup(String pkg) {
        if (pkg == null) {
            return;
        }
        NotifGroup g = dockedGroups.remove(pkg);
        if (g == null) {
            return;
        }
        if (pile != null) {
            pile.removeIconForPkg(pkg);
        }
        // Merge by pkg — never two groups for one app.
        NotifGroup existing = null;
        for (NotifGroup eg : groups) {
            if (eg.pkg != null && eg.pkg.equals(pkg)) {
                existing = eg;
                break;
            }
        }
        if (existing != null && existing != g) {
            for (NotifData d : g.items) {
                if (d != null && !existing.hasKey(d.key)) {
                    existing.add(d);
                }
            }
            // Re-expanded: show spread out, not stacked.
            existing.expanded = true;
        } else if (!groups.contains(g)) {
            // Re-expanded: show spread out, not stacked.
            g.expanded = true;
            groups.add(g);
        }
        Collections.sort(groups, new Comparator<NotifGroup>() {
            @Override
            public int compare(NotifGroup a, NotifGroup b) {
                // Pinned groups stay at the very front (user 2026-11-19
                // "钉住的通知自动排在最前面后，重新展开的其他通知只能由
                // 它后面开始排列"): pinned first, then by latest time
                // within each side, so re-expanded groups can only land
                // behind the pinned ones.
                if (a.pinned != b.pinned) {
                    return a.pinned ? -1 : 1;
                }
                if (a.pinned) {
                    return Long.compare(a.pinnedAt, b.pinnedAt);
                }
                return Long.compare(b.latestTime(), a.latestTime());
            }
        });
        rebuildDock();
    }

    /**
     * Long-press drag to move a pile icon (reorder the fan). Long-press
     * picks the icon up; dragging moves it with the finger; releasing
     * drops it into the nearest slot.
     */
    private void setupPileIconDrag(final PileIcon icon) {
        // v454: 用户 2026-11-23 06:17 “把扇子长按也去了”——
        // 扇子图标长按换位撤掉，不再可拖动换位。
        return;
    }

    /** Stack a capsule under the previous one (user 2026-11-19 "缩小改成
     * 由最下面通知叠加到上一个通知下面" / "消散成排列好了的图标串"): the
     * capsule slides up by one slot (44dp + 6dp gap) to tuck under the
     * notification above it. Shrink runs bottom-up, one by one, so the
     * capsules pile up into a stack that dissipates into the arranged
     * string of fan icons. No fade — the capsule stays visible as it
     * stacks. */
    private void stackCapsuleUnder(View v) {
        if (v == null) {
            return;
        }
        // Use a fresh ObjectAnimator, NOT v.animate(): the expand
        // (cascadeCapsulesIn) drives the same view's
        // ViewPropertyAnimator for its slide-in, and reusing it here
        // while the expand is still pending/running swallows the
        // stack (user 2026-11-19 "你这个没有叠加啊" — the stack never
        // played). An ObjectAnimator on translationY is independent.
        // RELATIVE: up another step from wherever it is now, so
        // calling it again on the same capsule keeps rising (the pile
        // rises together, user 2026-11-19 "叠加是通知一起移动上去吧").
        // The step is just the capsule HEIGHT (44dp), not height+gap:
        // from a 50dp spacing that lands each capsule 6dp below the
        // one above it — the SAME-APP CASCADE look (6dp edges, newest
        // on top), not an exact overlap where you can't see the
        // individual notifications (user 2026-11-19 "叠加成同应用那种
        // 啊" / "别搞个不伦不类的").
        // v363: the step is the view's OWN height — a big capsule
        // slides up by its full card height ("全缩 / 别只缩一半"),
        // not the 44dp nudge. For a 44dp capsule this is unchanged.
        int step = dp(CapsuleView.DEFAULT_HEIGHT_DP);
        if (v.getLayoutParams() instanceof FrameLayout.LayoutParams) {
            int h = ((FrameLayout.LayoutParams)
                    v.getLayoutParams()).height;
            if (h > 0) {
                step = h;
            }
        }
        android.animation.ObjectAnimator anim =
                android.animation.ObjectAnimator.ofFloat(v, "translationY",
                        v.getTranslationY(),
                        v.getTranslationY() - step);
        anim.setDuration(240);
        anim.start();
    }
    /** Layout top of a dock child (its LayoutParams topMargin) — the
     * reliable vertical order right after rebuildDock, before layout
     * has run and getY() becomes meaningful. */
    private static int topOf(View v) {
        if (v == null || !(v.getLayoutParams()
                instanceof FrameLayout.LayoutParams)) {
            return 0;
        }
        return ((FrameLayout.LayoutParams) v.getLayoutParams()).topMargin;
    }


    /** Collapse a group into a stack with animation (user 2026-11-19
     * "通知叠加和展开加一个动画"): record each capsule's current Y,
     * rebuild the dock (which lays them out as a cascade), then slide
     * each capsule from its old spread-out position into its stacked
     * position (240ms, 110ms stagger per capsule, same pace as the
     * expand). */
    private void collapseGroupAnimated(final NotifGroup group) {
        if (dock == null || group == null) {
            return;
        }
        final java.util.Map<String, Integer> oldTops =
                new java.util.HashMap<>();
        for (int i = 0; i < dock.getChildCount(); i++) {
            View v = dock.getChildAt(i);
            if (!(v instanceof CapsuleView)) {
                continue;
            }
            CapsuleView c = (CapsuleView) v;
            if (c.getTag() == group && c.getData() != null
                    && c.getData().key != null
                    && !group.pinnedAtByKey.containsKey(c.getData().key)) {
                oldTops.put(c.getData().key, (int) v.getY());
            }
        }
        group.expanded = false;
        rebuildDock();
        int step = 0;
        for (int i = 0; i < dock.getChildCount(); i++) {
            View v = dock.getChildAt(i);
            if (!(v instanceof CapsuleView)) {
                continue;
            }
            CapsuleView c = (CapsuleView) v;
            if (c.getTag() != group || c.getData() == null
                    || c.getData().key == null
                    || group.pinnedAtByKey.containsKey(c.getData().key)) {
                continue;
            }
            Integer oldTop = oldTops.get(c.getData().key);
            if (oldTop == null) {
                continue;
            }
            int newTop = ((FrameLayout.LayoutParams)
                    v.getLayoutParams()).topMargin;
            int dy = oldTop - newTop;
            if (dy != 0) {
                v.setTranslationY(dy);
                v.animate().translationY(0f)
                        .setDuration(240)
                        .setStartDelay(step * 110L)
                        .withLayer().start();
                step++;
            }
        }
    }

    /** Expand ALL groups before shrinking (user 2026-11-19 "改成全部展开
     * 然后由最后一个逐一向上叠加" / "就是之前说过的收缩动画改成我现在说的
     * 这种"): the shrink starts with every notification spread out on
     * its own row (no cascades), then the stacking runs bottom-up one
     * by one. Returns true if any group was expanded, so the caller
     * waits for the expand to be visible before the first stack
     * starts. (Replaces v35's collapseExpandedGroups, which did the
     * opposite — it merged expanded groups into a stack first.) */
    private boolean expandAllGroupsAnimated() {
        boolean expanded = false;
        java.util.List<NotifGroup> order = new java.util.ArrayList<>();
        for (NotifGroup g : groups) {
            if (g != null && g.items.size() > 1 && !g.expanded
                    && !g.pinned) {
                g.expanded = true;
                expanded = true;
            }
            if (g != null) {
                order.add(g);
            }
        }
        if (expanded) {
            rebuildDock();
            cascadeCapsulesIn(order);
        }
        return expanded;
    }

    /** Add a group's fan icon and hold it just below its capsule
     * (user 2026-11-18 "图标只能汇聚在胶囊通知下面"). The icon is
     * gathered BEFORE the capsule shrinks, so the capsule visibly
     * becomes the icon (user 2026-11-19 "先缩成图标"). Returns the
     * icon, or null. */
    private PileIcon gatherIconBelowCapsule(NotifGroup target,
            int capsuleTop) {
        return gatherIconBelowCapsule(target, capsuleTop,
                dp(CapsuleView.DEFAULT_HEIGHT_DP));
    }

    /** v363: capsuleHeight is the capsule's own height — the icon
     * gathers below the capsule's BOTTOM edge. (A big capsule's icon
     * must sit below its ~200dp body, not 44dp below its top.) */
    private PileIcon gatherIconBelowCapsule(NotifGroup target,
            int capsuleTop, int capsuleHeight) {
        if (pile == null || target == null) {
            return null;
        }
        PileIcon icon = addPileIconForGroup(target);
        if (icon == null) {
            return null;
        }
        // v303: the user diagnosed it — "有个窗口在这里挡着了".
        // The dock window ("flymebobo") sits ON TOP of the pile
        // window ("flymebobo-pile") and covers the icon while it
        // sinks down, so a piece goes missing. Both are
        // TYPE_APPLICATION_OVERLAY; the later-added window is on
        // top, but the dock window gets updateViewLayout calls that
        // can push it back above the pile. Re-add the pile window
        // so it's on top before the icon appears.
        if (pileWindowAdded && pileRoot != null && wm != null
                && pileParams != null) {
            try {
                wm.removeView(pileRoot);
            } catch (Throwable ignored) {
            }
            try {
                wm.addView(pileRoot, pileParams);
            } catch (Throwable ignored) {
            }
        }
        // v303: grow the pile window WIDE enough before the icon
        // appears. The user reports the Migu icon loses a piece
        // (right side clipped) while it sinks down — the width was
        // only grown later in stackIconToFan, so during the gather
        // and the drop the window was still too narrow. Grow it
        // here, before the icon is positioned.
        if (pileWindowAdded && pileParams != null && wm != null
                && pile != null) {
            int needW = dp(pile.totalWidthDp()) + dp(48);
            if (pileParams.width < needW) {
                pileParams.width = needW;
                try {
                    wm.updateViewLayout(pileRoot, pileParams);
                } catch (Throwable ignored) {
                }
            }
        }
        // v440: 坐标系混算修复（用户 “手动消除和缩小的没有以前下来 /
        // 还是会回到原位”）。capsuleTop 是 getY() 来的窗内相对 Y，
        // fanTop（pileParams.y / pileTargetY）是屏幕绝对 Y。v427
        // 主窗在 y=0，两者相等，混算没事；v439 滑块把整组下移
        // anchorY 后，主窗在 y=anchorY，相对值和绝对值差一个
        // anchorY：图标落点高了 anchorY，缩不进扇子；stretch 又把
        // 相对值当绝对值写进 pileParams.y，扇子弹回顶部。这里把
        // belowTop 转成屏幕绝对值（加主窗 Y），两边就对上了。
        int winTop = (params != null) ? params.y : 0;
        int fanTop = (pileParams != null) ? pileParams.y : 0;
        int belowTop = winTop + capsuleTop + capsuleHeight
                + dp(CAPSULE_GAP_DP);
        icon.setTranslationY(belowTop - fanTop);
        // Stretch the pile window up to the icon so it is not clipped
        // ("残缺") while it waits above the fan.
        stretchPileWindowTo(belowTop);
        return icon;
    }

    /** Slide a gathered icon down to stack onto the fan (user
     * 2026-11-19 "然后再叠加图标"): the icon's translationY animates
     * to 0 (its fan slot) and the fan stack animation runs — the
     * reverse of the top-to-bottom expand. */
    private void stackIconToFan(PileIcon icon) {
        if (icon == null) {
            return;
        }
        // v304: the user reports the icon is STILL clipped ("还是缺").
        // The re-add in gatherIconBelowCapsule isn't enough — the
        // 300ms delay runs rebuildDock, which calls updateViewLayout
        // on the dock window and pushes it back on top of the pile
        // window before the slide starts. Re-add the pile window
        // here too, right before the slide, so it's on top during
        // the whole drop.
        if (pileWindowAdded && pileRoot != null && wm != null
                && pileParams != null) {
            try {
                wm.removeView(pileRoot);
            } catch (Throwable ignored) {
            }
            try {
                wm.addView(pileRoot, pileParams);
            } catch (Throwable ignored) {
            }
        }
        // v281: grow the pile window BEFORE the icon slides down.
        // The user reports the icon's RIGHT half is clipped while
        // it drops ("下滑的右边被切掉了一半") — the window width
        // (totalWidthDp + 16dp) is too tight for the sliding icon,
        // so give it a full extra icon width of right margin.
        if (pileWindowAdded && pileParams != null && wm != null
                && pile != null) {
            int n = pile.getIconCount();
            int needW = dp(pile.totalWidthDp()) + dp(48);
            int needH = n * dp(40) + dp(16);
            boolean changed = false;
            if (pileParams.width < needW) {
                pileParams.width = needW;
                changed = true;
            }
            if (pileParams.height < needH) {
                pileParams.height = needH;
                changed = true;
            }
            if (changed) {
                try {
                    wm.updateViewLayout(pileRoot, pileParams);
                } catch (Throwable ignored) {
                }
            }
        }
        // v368: 去掉 withLayer()—— 硬件层按 22dp 的框裁，图标 34dp
        // 溢出的 12dp 会被层裁掉（v111 同款教训）。只滑 translationY，
        // 不用硬件层，图标不再缺一块。
        icon.animate().translationY(0f)
                .setDuration(350).start();
        // (Fan stack animation deleted, user 2026-11-19 "删了": the
        // column sweep made the icons fly around. Only the slide into
        // the fan remains.)
    }

    /** Dock gathered icons to the edge (user 2026-11-19 "图标贴边不叠
     * 加，只有最后一个通知也缩小再叠加"): all but the last icon snap
     * straight to their fan slot with no slide and no stacking; only
     * the last icon slides down to stack onto the fan, then the fan
     * stack animation runs once. */
    private void dockIconsToEdge(final List<PileIcon> icons) {
        if (icons == null || icons.isEmpty()) {
            return;
        }
        // v281: same as stackIconToFan — grow the pile window
        // (width + height) before the last icon slides down, or
        // its right half is clipped while it drops.
        // v456: 纵向时扇子本身就是一列，窗高得按扇子总高算。
        if (pileWindowAdded && pileParams != null && wm != null
                && pile != null) {
            int n = pile.getIconCount();
            int needW = dp(pile.totalWidthDp()) + dp(48);
            int needH = n * dp(40) + dp(16);
            int fanH = dp(pile.totalHeightDp()) + dp(48);
            if (fanH > needH) {
                needH = fanH;
            }
            boolean changed = false;
            if (pileParams.width < needW) {
                pileParams.width = needW;
                changed = true;
            }
            if (pileParams.height < needH) {
                pileParams.height = needH;
                changed = true;
            }
            if (changed) {
                try {
                    wm.updateViewLayout(pileRoot, pileParams);
                } catch (Throwable ignored) {
                }
            }
        }
        for (int i = 0; i < icons.size() - 1; i++) {
            PileIcon icon = icons.get(i);
            icon.animate().cancel();
            icon.setTranslationY(0f);
        }
        PileIcon last = icons.get(icons.size() - 1);
        last.animate().translationY(0f)
                .setDuration(350).withLayer().start();
        // (Fan stack animation deleted, user 2026-11-19 "删了".)
    }

    /** Set the pile's top margin inside the pile window. Used to keep
     * the fan at the same screen Y while the window is stretched. */
    private void setPileTopMargin(int marginPx) {
        if (pile == null) {
            return;
        }
        FrameLayout.LayoutParams lp =
                (FrameLayout.LayoutParams) pile.getLayoutParams();
        if (lp.topMargin != marginPx) {
            lp.topMargin = marginPx;
            pile.setLayoutParams(lp);
        }
    }

    /** Stretch the pile window up to cover a screen Y (user 2026-11-19
     * "扇子图标残缺"): a gathered icon sits above the fan (below its
     * capsule); if the window stays 34dp tall the icon is clipped.
     * The window top moves up to the icon and the pile is pushed down
     * inside it by the same amount, so the fan stays exactly where it
     * was on screen. updatePileWindow shrinks it back afterwards. */
    private void stretchPileWindowTo(int topY) {
        if (pileParams == null || !pileWindowAdded || pile == null
                || wm == null) {
            return;
        }
        int pad = dp(4);
        int fanTop = pileTargetY;
        if (topY >= fanTop - pad) {
            return; // already covered
        }
        int newTop = topY - pad;
        int shift = fanTop - newTop;
        int fanBottom = fanTop
                + Math.max(pileRoot != null
                        ? pileRoot.getMeasuredHeight() : 0, dp(34));
        setPileTopMargin(shift);
        pileParams.y = newTop;
        pileParams.height = Math.max(fanBottom - newTop, 1);
        try {
            wm.updateViewLayout(pileRoot, pileParams);
        } catch (Throwable ignored) {
        }
    }



    public void dockGroup(final NotifGroup target) {
        if (target == null || dock == null
                || !dockingGroups.add(target)) {
            return;
        }
        // Old tops of the surviving capsules, by key, so they can
        // slide up one by one to fill the position.
        int rowTop = 0;
        final Map<String, Integer> oldTops = new HashMap<>();
        for (int i = 0; i < dock.getChildCount(); i++) {
            View v = dock.getChildAt(i);
            if (v instanceof CapsuleView) {
                CapsuleView c = (CapsuleView) v;
                if (c.getTag() == target) {
                    rowTop = (int) v.getY();
                } else if (c.getData() != null && c.getData().key != null) {
                    oldTops.put(c.getData().key, (int) v.getY());
                }
            }
        }
        // Phase 1 (user 2026-11-19 "先缩成图标" / "和展开动画相反"):
        // gather the icon below the capsule FIRST, so as the capsule
        // shrinks it visibly becomes the icon.
        final PileIcon icon = gatherIconBelowCapsule(target, rowTop);
        // Stack the capsule under the previous one (user 2026-11-19).
        for (int i = 0; i < dock.getChildCount(); i++) {
            View v = dock.getChildAt(i);
            if (v instanceof CapsuleView) {
                CapsuleView c = (CapsuleView) v;
                if (c.getTag() == target) {
                    stackCapsuleUnder(v);
                }
            }
        }
        dock.postDelayed(new Runnable() {
            @Override
            public void run() {
                groups.remove(target);
                dockingGroups.remove(target);
                holdWindowHeight = true;
                rebuildDock();
                // Phase 2 (user 2026-11-19 "然后再叠加图标"): the icon
                // slides down to stack onto the fan, and at the same
                // time the other notifications slide up to fill the gap.
                stackIconToFan(icon);
                slideSurvivorsUp(oldTops);
            }
        }, 300);
    }

    /** v363: 大卡片模式重左滑——只缩被滑的那一张，全缩进扇子图标
     * （用户 “左滑轻滑钉住，重滑缩小” + “只缩被滑的那一张，其他的
     * 留着” + “全缩 / 别只缩一半”）。轻滑仍走 onSwipeRight 钉住；
     * 重滑走这里。v364: 卡片不再往上滑——一边滑回左边缘、一边横
     * 向压扁进左边缘（用户 “左滑动画 / 没有缩到屏幕边缘去”），
     * 300ms 后只把这一条通知从组里拿掉、单独收进扇子，其他通知
     * 原地不动、依次前顶补位。图标聚在被滑大卡片的下沿（不是
     * 44dp 处）。 */
    private void dockOneCapsule(final CapsuleView cap, final NotifData d,
            final NotifGroup group) {
        if (cap == null || d == null || d.key == null || group == null
                || dock == null || !dockingKeys.add(d.key)) {
            return;
        }
        // Old tops of the surviving capsules, by key, so they can
        // slide up to fill the gap (same as dockGroup — but here the
        // survivors include the same group's OTHER capsules).
        // v367: 这是“只缩被滑的那一张”的通用实现 —— 大卡片和
        // 普通胶囊都走这里（胶囊模式同应用也不再整组收）。
        final Map<String, Integer> oldTops = new HashMap<>();
        for (int i = 0; i < dock.getChildCount(); i++) {
            View v = dock.getChildAt(i);
            if (v instanceof CapsuleView) {
                CapsuleView c = (CapsuleView) v;
                if (c != cap && c.getData() != null
                        && c.getData().key != null) {
                    oldTops.put(c.getData().key, (int) v.getY());
                }
            }
        }
        int capTop = (int) cap.getY();
        int capHeight = dp(CapsuleView.DEFAULT_HEIGHT_DP);
        if (cap.getLayoutParams() instanceof FrameLayout.LayoutParams) {
            int h = ((FrameLayout.LayoutParams)
                    cap.getLayoutParams()).height;
            if (h > 0) {
                capHeight = h;
            }
        }
        // Gather the icon below the big card's BOTTOM edge first, so
        // as the card slides up it visibly becomes the icon. The icon
        // is built from a single-item group for this notification, so
        // only it goes into the fan (it merges into the app's docked
        // group if that app already has docked notifications).
        final NotifGroup docked = new NotifGroup(group.pkg);
        docked.add(d);
        final PileIcon icon =
                gatherIconBelowCapsule(docked, capTop, capHeight);
        // v364: 滑缩到屏幕边缘——卡片滑回左边缘（translationX 回 0，
        // 左拖的 -80dp 顶出也一并收回来），同时以左边缘为轴心横向
        // 压扁到 0（scaleX），240ms，和其他消散动画同速。图标在
        // 下沿等着，卡片缩进边缘后图标再沉进扇子。
        // v380: 右窗的来电胶囊反过来——轴心在右边缘（靠屏边），
        // 否则它靠右缩到一半、最后却弹到左边去（用户 2026-11-22
        // 视频“靠右缩的，但是最后动画弹到左边去了”）。
        boolean isRightCall = cap == callCapView;
        if (isRightCall) {
            float w = cap.getWidth() > 0 ? cap.getWidth()
                    : dp(CapsuleView.CALL_WIDTH_DP);
            cap.setPivotX(w);
        } else {
            cap.setPivotX(0f);
        }
        cap.animate().translationX(0f).scaleX(0f).setDuration(240)
                .setInterpolator(
                        new android.view.animation.DecelerateInterpolator())
                .withLayer()
                .start();
        dock.postDelayed(new Runnable() {
            @Override
            public void run() {
                removeNotifByKey(d.key);
                dockingKeys.remove(d.key);
                holdWindowHeight = true;
                rebuildDock();
                stackIconToFan(icon);
                slideSurvivorsUp(oldTops);
            }
        }, 300);
    }


    /** Slide the surviving capsules up to close the gap left by a
     * docked/dissipated group (their old tops are captured before
     * the rebuild). */
    // The notifications behind a removed row move up ONE BY ONE
    // to fill the position (user 2026-11-18 "后面的通知就依次向上
    // 补位"): each survivor slides up in order (top to bottom), 80ms
    // apart, instead of all jumping at once.
    private void slideSurvivorsUp(Map<String, Integer> oldTops) {
        if (dock == null || oldTops == null || oldTops.isEmpty()) {
            // v161: no survivors to slide — release the held window
            // height right away, or the window stays tall forever.
            if (holdWindowHeight) {
                holdWindowHeight = false;
                updateWindowSize();
            }
            return;
        }
        // Collect the survivors that actually moved, each as a single
        // notification (user 2026-11-19 "其他通知依次前顶的那点也要改
        // 成按单一通知来看" / "每条通知都按照单通知来看").
        final java.util.List<CapsuleView> moved =
                new java.util.ArrayList<>();
        final java.util.Map<CapsuleView, Integer> dys =
                new java.util.HashMap<>();
        for (int i = 0; i < dock.getChildCount(); i++) {
            View v = dock.getChildAt(i);
            if (!(v instanceof CapsuleView)) {
                continue;
            }
            CapsuleView c = (CapsuleView) v;
            Integer oldTop = c.getData() != null
                    ? oldTops.get(c.getData().key) : null;
            if (oldTop == null) {
                continue;
            }
            // The new top comes from the layout params, NOT getY():
            // these views were just created in rebuildDock and have
            // not been laid out yet, so getY() is still 0; using it
            // makes every survivor jump when the layout lands
            // mid-animation (user 2026-11-19 "拖动消散会到处跳动").
            int newTop = (int) v.getY();
            if (v.getLayoutParams() instanceof FrameLayout.LayoutParams) {
                newTop = ((FrameLayout.LayoutParams)
                        v.getLayoutParams()).topMargin;
            }
            int dy = oldTop - newTop;
            if (dy != 0) {
                moved.add(c);
                dys.put(c, dy);
            }
        }
        // Slide up one by one from top to bottom (ascending OLD top):
        // the survivor nearest the gap (directly below the removed
        // notification) moves first, then the next one down. Dock
        // child order is oldest-first inside a group (bottom-to-top),
        // so staggering in child order made the bottom-most survivor
        // move first, which looked wrong.
        java.util.Collections.sort(moved,
                new java.util.Comparator<CapsuleView>() {
                    @Override
                    public int compare(CapsuleView a, CapsuleView b) {
                        Integer ta = a.getData() != null
                                ? oldTops.get(a.getData().key) : null;
                        Integer tb = b.getData() != null
                                ? oldTops.get(b.getData().key) : null;
                        int x = ta == null ? 0 : ta;
                        int y = tb == null ? 0 : tb;
                        return Integer.compare(x, y);
                    }
                });
        int step = 0;
        for (CapsuleView c : moved) {
            c.setTranslationY(dys.get(c));
            c.animate().translationY(0f)
                    .setDuration(300)
                    .setStartDelay(step * 80L)
                    .withLayer().start();
            step++;
        }
        // v161: release the held window height once the slide
        // finishes (300ms + 80ms stagger per survivor, plus a
        // small buffer) — shrinking earlier clips the last
        // survivor's slide and it looks like flickering (user
        // 2026-11-19 "导致他看起来想闪烁一样"). No survivors
        // moved: release right away so the window shrinks now.
        final int movedCount = moved.size();
        final long releaseDelay = movedCount <= 0 ? 0
                : 300L + 80L * (movedCount - 1) + 60L;
        dock.postDelayed(new Runnable() {
            @Override
            public void run() {
                holdWindowHeight = false;
                updateWindowSize();
            }
        }, releaseDelay);
    }

    /**
     * LEFT SWIPE — particle dissipate (user 2026-11-18 "左滑就是
     * 直接粒子化消散"): the notification bursts into particles and
     * vanishes outright. It does NOT become an edge icon and does
     * NOT join the fan; it is simply gone. The survivors move up.
     */
    /**
     * Real particle burst: small circles fly outward from the capsule
     * and fade away (preview: particles scatter in place and vanish,
     * they do NOT converge to the edge). The capsule itself fades and
     * shrinks at the same time; the particle views are removed when
     * their animation ends.
     */
    private void burstParticles(View capsule) {
        if (capsule == null || dock == null || app == null) {
            return;
        }
        // The capsule itself vanishes instantly as the particles spawn
        // (user 2026-11-19 "有粒子动画但是通知没有消散"): without this,
        // the particles play and the capsule stays on screen.
        capsule.setVisibility(View.INVISIBLE);
        int color = Color.WHITE;
        if (capsule instanceof CapsuleView) {
            int pc = ((CapsuleView) capsule).getPillColor();
            if (pc != 0) {
                color = pc;
            }
        }
        Random rnd = new Random();
        // The whole pill dissipates together — body AND tail. The pill
        // is (180+dx) wide when dragged out; particles must spawn along
        // that full length, not just the 180dp capsule body (user
        // 2026-11-19 "拖尾和胶囊一起消散" / "只消散胶囊没有让胶囊后面
        // 的拖尾一起消散"). Scale the count with the length so the tail
        // is covered at the same density.
        int pillW = capsule.getWidth();
        if (capsule instanceof CapsuleView) {
            pillW = ((CapsuleView) capsule).getPillWidth();
        }
        if (pillW <= 0) {
            pillW = capsule.getWidth();
        }
        int baseW = dp(CapsuleView.capsuleWidthDp(app));
        int count = 16;
        if (baseW > 0 && pillW > baseW) {
            count = Math.round(16f * pillW / baseW);
        }
        for (int i = 0; i < count; i++) {
            final View p = new View(app);
            int size = dp(3 + rnd.nextInt(4));
            GradientDrawable bg = new GradientDrawable();
            bg.setShape(GradientDrawable.OVAL);
            bg.setColor(color);
            p.setBackground(bg);
            FrameLayout.LayoutParams lp =
                    new FrameLayout.LayoutParams(size, size);
            float sx = capsule.getX() + rnd.nextFloat() * pillW;
            float sy = capsule.getY() + rnd.nextFloat() * capsule.getHeight();
            lp.leftMargin = (int) sx;
            lp.topMargin = (int) sy;
            p.setLayoutParams(lp);
            dock.addView(p);
            double angle = rnd.nextDouble() * Math.PI * 2;
            float dist = dp(40 + rnd.nextInt(50));
            float dx = (float) (Math.cos(angle) * dist);
            float dy = (float) (Math.sin(angle) * dist);
            p.animate()
                    .translationX(dx)
                    .translationY(dy)
                    .alpha(0f)
                    .scaleX(0.5f)
                    .scaleY(0.5f)
                    .setDuration(320 + rnd.nextInt(120))
                    .withLayer()
                    .withEndAction(new Runnable() {
                        @Override
                        public void run() {
                            try {
                                dock.removeView(p);
                            } catch (Throwable ignored) {
                            }
                        }
                    })
                    .start();
        }
    }

    /**
     * Dissipate ONLY the dragged notification (user 2026-11-19
     * "同一应用多个通知滑动会导致一起消散" / "能不能改成只消散拖动
     * 的那一个"): the other notifications in the same app's group
     * stay. The group shrinks; if it empties, it goes away.
     */
    /**
     * Toggle the pin of ONE notification (per-notification pin, user
     * 2026-11-19 "每条通知都按照单通知来看" / "钉住一个也应该能钉住
     * 其他的"). Pinning the first notification of a group pins the
     * group (pin-order insert, behind already-pinned groups, v57);
     * pinning another while one is already pinned just adds its key
     * (the group is already in the pin section, pinnedAt unchanged).
     * Unpinning the last pinned notification unpins the group (it
     * drops into the non-pinned section by latest time).
     */
    /** v174: lyric listening is on while a music capsule is pinned
     * OR its music card is open. The module only runs the lyric
     * listener (500ms poll re-reading the live notification) while
     * musicListening is true, and togglePin was the only sender - so
     * opening a music card never started it and the card's lyric
     * froze at the pop-time line. Centralize the decision here so
     * every pin/card transition re-syncs it. */
    private void syncLyricListen(String key) {
        if (app == null || key == null) {
            return;
        }
        boolean want = false;
        outer:
        for (NotifGroup g : groups) {
            if (g == null) {
                continue;
            }
            for (NotifData d : g.items) {
                if (d != null && key.equals(d.key) && d.isMusic) {
                    want = g.pinnedAtByKey.containsKey(key)
                            || isCardKey(key);
                    break outer;
                }
            }
        }
        if (want != lyricListenOn) {
            lyricListenOn = want;
            android.content.Intent intent = new android.content.Intent(
                    NotificationService.ACTION_LYRIC_LISTEN);
            intent.putExtra("key", key);
            intent.putExtra("listen", want);
            try {
                app.sendBroadcast(intent);
            } catch (Throwable ignored) {
            }
        }
    }

    private void togglePin(NotifGroup group, NotifData d) {
        if (group == null || d == null || d.key == null) {
            return;
        }
        if (group.pinnedAtByKey.containsKey(d.key)) {
            // Unpin this one.
            Long unpinTime = group.pinnedAtByKey.get(d.key);
            boolean wasPinnedCard = pinnedCardKeys.contains(d.key);
            group.pinnedAtByKey.remove(d.key);
            // v236: unpinning a pinned card reverts it to a capsule.
            pinnedCardKeys.remove(d.key);
            // v516: a MANUAL unpin of a navi card is remembered
            // for the rest of this navigation session, so the
            // live updates do not auto-pin the card open again
            // (togglePin is only invoked by user gestures, so
            // this records user intent only). Cleared on navi
            // end in onNaviEnd.
            if (d.isNavi) {
                naviUserUnpinned.add(d.key);
            }
            // v238: a pinned card that is unpinned stays in
            // place — it does NOT jump back to its group
            // position. Remember its pin time so Phase 1 keeps
            // it at that row as a capsule.
            if (wasPinnedCard && unpinTime != null) {
                stayPutPinTime.put(d.key, unpinTime);
            }
            group.sortItems();
            if (group.pinnedAtByKey.isEmpty()) {
                group.pinned = false;
                group.pinnedAt = 0;
                groups.remove(group);
                int at = 0;
                while (at < groups.size() && groups.get(at).pinned) {
                    at++;
                }
                while (at < groups.size()
                        && groups.get(at).latestTime() > group.latestTime()) {
                    at++;
                }
                groups.add(at, group);
            } else {
                // Still pinned (other notifications pinned): the
                // group's pin time is the earliest remaining pin.
                group.pinnedAt = earliestPinTime(group);
            }
        } else {
            // Pin this one (its own pin time — pinned notifications
            // of one app sort by pin order, user 2026-11-19 06:08).
            boolean firstPin = group.pinnedAtByKey.isEmpty();
            group.pinnedAtByKey.put(d.key, System.currentTimeMillis());
            // v238: re-pinning clears the stay-put record.
            stayPutPinTime.remove(d.key);
            // v236: pinning an OPEN card makes it a pinned card
            // — it stays expanded even after another card opens.
            // Pinning a plain capsule does not add it here.
            if (isCardKey(d.key)) {
                pinnedCardKeys.add(d.key);
                // v237: this card's group's capsules were hidden
                // while it was open; now that it is pinned they
                // become visible — pop them in (flag consumed by
                // rebuildDock below).
                popInAfterRebuild = true;
                popInGroup = group;
            }
            group.sortItems();
            if (firstPin) {
                group.pinned = true;
                group.pinnedAt = System.currentTimeMillis();
                groups.remove(group);
                int at = 0;
                while (at < groups.size() && groups.get(at).pinned) {
                    at++;
                }
                groups.add(at, group);
            }
        }
        // Live lyrics (v76): the module process only listens to the
        // MediaSession while a music capsule is pinned (user
        // 2026-11-19 "固定了就实时监听歌词"); unpinned music just
        // pops once per song change (v75). Tell it the new state.
        // v174: lyric listening follows pin OR open card (see
        // syncLyricListen) - a card opened unpinned must listen too.
        if (d.isMusic) {
            syncLyricListen(d.key);
        }
    }

    /** Earliest pin time among a group's pinned notifications. */
    private long earliestPinTime(NotifGroup group) {
        long t = 0;
        for (Long v : group.pinnedAtByKey.values()) {
            if (v != null && (t == 0 || v < t)) {
                t = v;
            }
        }
        return t;
    }

    public void dissipateOne(final NotifData data) {
        if (data == null || dock == null) {
            return;
        }
        // v362: 右滑消散的放大胶囊可能正拖着拖尾（手动右拖）——
        // 拖尾和胶囊一起消散，别让它残留到 310ms 后的 rebuild。
        removeCardTail();
        // v302: user dismissed a Migu song (right-swipe) — remember
        // it so a lyric update for the same song doesn't re-pop it.
        if (data.isMusic
                && "cmccwm.mobilemusic".equals(data.pkg)
                && data.key != null && data.title != null) {
            dismissedMiguMusic.put(data.key, data.title);
            if (dismissedMiguMusic.size() > 64) {
                dismissedMiguMusic.clear();
            }
        }
        NotifGroup targetGroup = null;
        final Map<String, Integer> oldTops = new HashMap<>();
        for (int i = 0; i < dock.getChildCount(); i++) {
            View v = dock.getChildAt(i);
            if (v instanceof CapsuleView) {
                CapsuleView c = (CapsuleView) v;
                if (c.getData() == data
                        || (c.getData() != null && data.key != null
                                && data.key.equals(c.getData().key))) {
                    targetGroup = (NotifGroup) c.getTag();
                    burstParticles(v);
                    v.setVisibility(View.INVISIBLE);
                } else if (c.getData() != null && c.getData().key != null) {
                    oldTops.put(c.getData().key, (int) v.getY());
                }
            }
        }
        if (targetGroup == null || !dockingGroups.add(targetGroup)) {
            return;
        }
        final NotifGroup group = targetGroup;
        dock.postDelayed(new Runnable() {
            @Override
            public void run() {
                // Remove just this notification, by key.
                for (int i = group.items.size() - 1; i >= 0; i--) {
                    NotifData it = group.items.get(i);
                    if (it == data || (it != null && data.key != null
                            && data.key.equals(it.key))) {
                        group.items.remove(i);
                        break;
                    }
                }
                // If the dismissed notification was pinned, drop its
                // pin (per-notification pin); if that was the last
                // pin, the group is no longer pinned.
                if (data != null && data.key != null
                        && group.pinnedAtByKey.remove(data.key) != null) {
                    group.sortItems();
                    if (group.pinnedAtByKey.isEmpty()) {
                        group.pinned = false;
                        group.pinnedAt = 0;
                    } else {
                        group.pinnedAt = earliestPinTime(group);
                    }
                }
                if (group.items.isEmpty()) {
                    groups.remove(group);
                }
                dockingGroups.remove(group);
                holdWindowHeight = true;
                rebuildDock();
                slideSurvivorsUp(oldTops);
            }
        }, 310);
    }

    public void dissipateToParticles(final NotifGroup target) {
        if (target == null || dock == null) {
            return;
        }
        // Old tops of the surviving capsules, so they can slide up
        // one by one to fill the position.
        final Map<String, Integer> oldTops = new HashMap<>();
        for (int i = 0; i < dock.getChildCount(); i++) {
            View v = dock.getChildAt(i);
            if (v instanceof CapsuleView) {
                CapsuleView c = (CapsuleView) v;
                if (c.getTag() == target) {
                    // Burst into real particles and vanish directly —
                    // no icon left, and no lingering fade. The capsule
                    // AND its tail disappear the instant the burst
                    // starts (user 2026-11-19 "消散的时候直接消失"),
                    // the particles carry the dissipate visual.
                    burstParticles(v);
                    v.setVisibility(View.INVISIBLE);
                } else if (c.getData() != null && c.getData().key != null) {
                    oldTops.put(c.getData().key, (int) v.getY());
                }
            }
        }
        dock.postDelayed(new Runnable() {
            @Override
            public void run() {
                groups.remove(target);
                holdWindowHeight = true;
                rebuildDock();
                // The notifications behind move up one by one.
                slideSurvivorsUp(oldTops);
            }
        }, 310);
    }

    /**
     * The ANCHOR (user 2026-11-18 "现在以最上面这个通知为锚点"):
     * the topmost notification — the first group, newest first.
     * Expand/shrink animations use it as the reference point.
     */
    public NotifGroup anchorGroup() {
        return groups.isEmpty() ? null : groups.get(0);
    }

    /**
     * The anchor level of a group (user 2026-11-18 "三级锚点也是
     * 类似于二级锚点"): 0 = the primary anchor (the topmost group),
     * 1 = the secondary anchor (the first pinned group below it),
     * 2 = the tertiary anchor (the next pinned group below that),
     * and so on. -1 means the group is not an anchor.
     */
    public int anchorLevel(NotifGroup g) {
        if (groups.isEmpty() || g == null) {
            return -1;
        }
        if (g == groups.get(0)) {
            return 0;
        }
        int level = 0;
        for (int i = 1; i < groups.size(); i++) {
            if (groups.get(i).pinned) {
                level++;
                if (groups.get(i) == g) {
                    return level;
                }
            }
        }
        return -1;
    }

    /** The DEEPEST anchor that still exists: the last pinned group
     * below the primary anchor (tertiary, secondary, ...). Null when
     * no pinned group sits below the primary anchor. */
    public NotifGroup deepestAnchorGroup() {
        NotifGroup deep = null;
        for (int i = 1; i < groups.size(); i++) {
            if (groups.get(i).pinned) {
                deep = groups.get(i);
            }
        }
        return deep;
    }

    /** The group the fan stacks below: the deepest anchor if there
     * is one, otherwise the primary anchor. */
    public NotifGroup fanAnchorGroup() {
        NotifGroup deep = deepestAnchorGroup();
        return deep != null ? deep : anchorGroup();
    }

    /** Screen centre of the anchor capsule's top, for animations. */
    public int[] anchorLocationOnScreen() {
        NotifGroup anchor = anchorGroup();
        if (anchor == null) {
            return null;
        }
        CapsuleView cap = topCapsule(anchor);
        if (cap == null) {
            return null;
        }
        int[] loc = new int[2];
        cap.getLocationOnScreen(loc);
        loc[0] += cap.getWidth() / 2;
        loc[1] += cap.getHeight() / 2;
        return loc;
    }

    public DockedPile getPile() {
        return pile;
    }

    public List<NotifGroup> getGroups() {
        return new ArrayList<>(groups);
    }

    private void updateWindowSize() {
        if (!windowAdded || wm == null || root == null) {
            return;
        }
        try {
            root.measure(
                    View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
                    View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
            int w = Math.max(root.getMeasuredWidth(), 1) + dp(PIN_OVERHANG_DP);
            int h = Math.max(root.getMeasuredHeight(), 1);
            // v161: while survivors are sliding up, keep the window
            // at its old (taller) height so the animation is not
            // clipped; slideSurvivorsUp shrinks it afterwards.
            if (holdWindowHeight && params.height > h) {
                h = params.height;
            }
            // v439: 整组（胶囊+扇子）下移 anchorY —— 靠窗口本身下移，
            // 不在窗内垫空位。窗内垫空位会让窗口从屏顶一直罩到胶囊，
            // 空的那段也在窗内、触控进不去（FLAG_NOT_TOUCH_MODAL
            // 只放行窗外的触控）。窗口仍包内容，触控照常穿透。
            int anchorY = Appearance.dockTopPx(app);
            int winY = dp(DOCK_EDGE_DP) + anchorY;
            if (params.width != w || params.height != h
                    || params.y != winY) {
                params.width = w;
                params.height = h;
                params.y = winY;
                wm.updateViewLayout(root, params);
            }
        } catch (Throwable ignored) {
        }
        updatePileWindow();
    }

    /** Size the pile window to the actual fan content and put it at
     * pileTargetY. The width follows the real fan (totalWidthDp), not
     * a fixed 136dp, so the window never covers app UI past the last
     * icon. No icons: shrink to a dot so it blocks nothing. */
    private void updatePileWindow() {
        if (!pileWindowAdded || wm == null || pileRoot == null
                || pileParams == null) {
            return;
        }
        try {
            int count = pile != null ? pile.getIconCount() : 0;
            if (count <= 0) {
                if (pileParams.width != 1 || pileParams.height != 1) {
                    pileParams.width = 1;
                    pileParams.height = 1;
                    wm.updateViewLayout(pileRoot, pileParams);
                }
                return;
            }
            pileRoot.measure(
                    View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
                    View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
            // Undo any stretch: the pile sits at the top of the window
            // again (user 2026-11-19 "扇子图标残缺").
            setPileTopMargin(0);
            // Right margin so the rightmost icon's 34dp iconView never
            // ends exactly at the window edge and gets clipped on tap
            // (user 2026-11-19 "贴边图标点它的时候右边会被截掉一块").
            // v111: +16dp (was +6dp, v92) — the rightmost icon's
            // 34dp iconView still got clipped on tap.
            int w = Math.max(dp(pile.totalWidthDp()) + dp(16), 1);
            // Floor at the icon height (v25): a bad measurement must
            // never shrink the window to 1px and hide every icon.
            int h = Math.max(pileRoot.getMeasuredHeight(), dp(34));
            int y = pileTargetY;
            // v368: 扇子图标左半被裁（用户 “小图标都有一半被窗口裁掉
            // 了” / “我记得之前是修好了的”）—— 扇子窗口的 x 从来没
            // 被显式定过，窗口一旦被重加/改宽就可能留在负 x 上，整排
            // 图标跟着左移半个图标。钉死在左边缘（dock 固定在左），
            // 让窗口永远从 x=0 起。
            int x = 0;
            // v368 diagnostic: if the window ever sits off the edge,
            // log it so the real shift source shows in BubbleNotify.log.
            if (pileParams.x != 0) {
                FileLog.i("PileWin x=" + pileParams.x
                        + " fixed to 0 w=" + w + " h=" + h + " y=" + y);
            }
            if (pileYAnimating) {
                // The animator owns Y right now; only fix the size.
                if (pileParams.width != w || pileParams.height != h
                        || pileParams.x != x) {
                    pileParams.width = w;
                    pileParams.height = h;
                    pileParams.x = x;
                    wm.updateViewLayout(pileRoot, pileParams);
                }
                return;
            }
            if (pileParams.width != w || pileParams.height != h
                    || pileParams.y != y || pileParams.x != x) {
                pileParams.width = w;
                pileParams.height = h;
                pileParams.y = y;
                pileParams.x = x;
                wm.updateViewLayout(pileRoot, pileParams);
            }
        } catch (Throwable ignored) {
        }
    }

    /** Shrink-stack the pile icons into the fan (user 2026-11-19
     * "这应该是你设置的窗口有问题对吧"): the stack animation starts
     * the icons in a vertical column BELOW the fan, but the pile
     * window is only one icon row tall, so the column is outside
     * the window and gets clipped (and icons can get stuck
     * translated). Grow the window tall enough for the column
     * first, run the animation, then shrink it back. */
    /** Slide the pile window from one Y to another (new notification
     * pushing the fan down). Drives pileParams.y directly. */
    private void animatePileWindowY(int fromY, int toY) {
        if (!pileWindowAdded || wm == null || pileRoot == null
                || pileParams == null || fromY == toY) {
            pileYAnimating = false;
            return;
        }
        pileYAnimating = true;
        android.animation.ValueAnimator anim =
                android.animation.ValueAnimator.ofInt(fromY, toY);
        anim.setDuration(300);
        anim.addUpdateListener(
                new android.animation.ValueAnimator.AnimatorUpdateListener() {
                    @Override
                    public void onAnimationUpdate(
                            android.animation.ValueAnimator a) {
                        try {
                            pileParams.y = (Integer) a.getAnimatedValue();
                            wm.updateViewLayout(pileRoot, pileParams);
                        } catch (Throwable ignored) {
                        }
                    }
                });
        anim.addListener(new android.animation.AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(android.animation.Animator a) {
                pileYAnimating = false;
                updatePileWindow();
            }
        });
        anim.start();
    }

    /**
     * Widen the window to the full screen width while a capsule is being
     * dragged, so the long pill is never clipped (user 2026-11-19 "你把
     * 滑动距离取消限制"). The window is only wide during the drag — the
     * user is dragging then, so nothing else needs tapping — and it is
     * shrunk back by endDragWindow() the moment the drag ends. A
     * permanently full-screen window blocks ALL touches (v4 regression,
     * user "魔块导致屏幕无法被点击了"): FLAG_NOT_TOUCH_MODAL only passes
     * through touches outside the window, and a full-screen window has
     * no outside.
     */
    /**
     * Widen ONLY the capsule window for an AUTO right-swipe
     * (playAutoSwipeRight), just enough for the longest tail — NOT
     * full-screen (user 2026-11-19 "为什么我要等全部气泡都消散了才
     * 能点击屏幕" / "能不能做穿透"). A full-screen window swallows
     * every touch (FLAG_NOT_TOUCH_MODAL only passes touches outside
     * the window, and a full-screen window has no outside), so during
     * the whole auto-dissipate sequence the user could tap nothing.
     * The auto-swipe only grows the pill to the right (180 + 200dp),
     * so the window only needs to be that wide; touches outside it
     * (almost the whole screen) pass through to the app below. The
     * pile window is left alone. Manual drag still uses
     * beginDragWindow() (full-screen) — the finger is dragging then.
     */
    public void beginAutoSwipeWindow() {
        if (!windowAdded || wm == null || root == null || params == null) {
            return;
        }
        dragWindowGen++;
        try {
            // v352: 卡片关时向右滑 200dp，窗口得按卡片宽算（380dp），
            // 不能只按胶囊宽（260dp）——否则卡片右滑出 92dp 被窗口截掉。
            // 手动拖拽用全屏窗口所以能拉到最右边，自动动画也得够宽
            // （用户 2026-11-22 “手动倒是可以拉到屏幕最右边”）。
            // v360: 大卡片模式的放大胶囊也是卡片宽（300/380dp），
            // 右滑 200dp 时窗口得按卡片宽算，不然右沿被截掉。
            int baseW = (cardKey != null || FeatureFlags.bigCard)
                    ? CapsuleView.cardWidthDp(app)
                    : CapsuleView.capsuleWidthDp(app);
            int w = dp(baseW + 200 + PIN_OVERHANG_DP);
            if (params.width != w) {
                params.width = w;
                wm.updateViewLayout(root, params);
            }
        } catch (Throwable ignored) {
        }
    }

    public void beginDragWindow() {
        if (!windowAdded || wm == null || root == null || params == null) {
            return;
        }
        dragWindowGen++;
        try {
            int sw = app.getResources().getDisplayMetrics().widthPixels;
            int sh = app.getResources().getDisplayMetrics().heightPixels;
            if (params.width != sw || params.height != sh) {
                params.width = sw;
                params.height = sh;
                wm.updateViewLayout(root, params);
            }
            // Widen the pile window too, so a dragged pile icon is
            // never clipped at its window edge.
            if (pileWindowAdded && pileParams != null && pileRoot != null) {
                if (pileParams.width != sw || pileParams.height != sh) {
                    pileParams.width = sw;
                    pileParams.height = sh;
                    wm.updateViewLayout(pileRoot, pileParams);
                }
            }
        } catch (Throwable ignored) {
        }
    }

    /** v441: width-only drag window for a CAPSULE swipe (manual
     * dissipate / card drag). A capsule only slides horizontally,
     * so it only needs the width — the height must stay content
     * height. beginDragWindow() sets height to full screen; with
     * the dock slid down (anchorY > 0) the window then spans
     * anchorY..anchorY+screenHeight, past the screen bottom, and
     * the system clamps its Y back to 0 — the whole unit jumps to
     * the top and stays there after a manual right-swipe
     * (user 2026-11-23 "手动消除...还是会回到原位"). Widening only
     * keeps the window at anchorY, so the unit stays where the
     * slider put it. The pile window is untouched (a capsule swipe
     * never drags the fan). The pile-icon long-press drag keeps
     * using beginDragWindow() (2D, needs the full window). */
    public void beginCapsuleDragWindow() {
        if (!windowAdded || wm == null || root == null || params == null) {
            return;
        }
        dragWindowGen++;
        try {
            int sw = app.getResources().getDisplayMetrics().widthPixels;
            if (params.width != sw) {
                params.width = sw;
                wm.updateViewLayout(root, params);
            }
        } catch (Throwable ignored) {
        }
    }

    /** Shrink the window back to content size after a drag ends. */
    public void endDragWindow() {
        updateWindowSize();
    }

    /** Generation of the drag window; a delayed shrink only runs if no
     * new drag has started since it was posted. */
    private int dragWindowGen;

    /**
     * Shrink the window back after the dissipate particles finish
     * flying (~450ms). The window must stay wide while the tail
     * particles burst, or they are clipped at the window edge.
     */
    public void endDragWindowDelayed() {
        if (dock == null) {
            return;
        }
        final int gen = dragWindowGen;
        dock.postDelayed(new Runnable() {
            @Override
            public void run() {
                if (gen == dragWindowGen) {
                    updateWindowSize();
                }
            }
        }, 450);
    }

    /** v386: enlarge the right call window ONLY while its capsule
     * is being dragged, then shrink it back. The idle window is a
     * fixed 560x120dp; a horizontal swipe drifts a little
     * vertically, and once the finger leaves that window the touch
     * stream breaks, no UP arrives, and the capsule stays stuck
     * halfway instead of springing back ("还是不会弹出"). The drag
     * window is 800x300dp — but unlike v384, this ONLY changes the
     * window's width/height. The window's x/y and the capsule's
     * margins are NEVER touched mid-drag, so the capsule's screen
     * spot cannot jump and the capsule cannot look freely
     * draggable; only the invisible slack around it grows. Called
     * from CapsuleView when a right-dock drag starts; the drag
     * end (UP/CANCEL) shrinks it back.
     * v390: the idle window shrank to exactly the capsule
     * (240x60dp) so touches around it reach the app below.
     * v391: the drag slack no longer comes from the capsule's
     * margins (changing them mid-drag is a layout change — the
     * capsule jumped). The window moved left/up by 280/120 and
     * the ROOT view was translated right/down by the same — but
     * the two halves never land in the same frame, so it STILL
     * jumped (user 2026-11-22 "还是跳动").
     * v392: mirror the LEFT dock exactly (user "之前左边是怎么
     * 设计的"). Left: gravity LEFT, capsule anchored left, drag
     * ONLY widens the window — position, margins, translation
     * never touched, so the capsule cannot jump. Right: gravity
     * RIGHT, capsule anchored right (END), drag ONLY widens the
     * window (800x300) — the right edge stays on the screen
     * edge, the window extends left, the capsule does not move.
     * The shrink still waits 250ms so the 200ms spring-back
     * finishes while the window is still big. */
    private boolean callDragWindowOpen;
    private boolean callDragWindowShrinkPending;
    private Runnable callDragWindowShrinkRunnable;

    public void beginCallDragWindow() {
        if (!callWindowAdded || wm == null || callRoot == null
                || callParams == null) {
            return;
        }
        if (callDragWindowOpen) {
            return;
        }
        // A previous drag just ended and its shrink is still
        // waiting out the spring-back — cancel it, we need the
        // big window again.
        if (callDragWindowShrinkRunnable != null) {
            try {
                handler.removeCallbacks(
                        callDragWindowShrinkRunnable);
            } catch (Throwable ignored) {
            }
            callDragWindowShrinkRunnable = null;
        }
        callDragWindowShrinkPending = false;
        callDragWindowOpen = true;
        try {
            // v403: the drag window is exactly the SCREEN width,
            // not 800dp. 800dp (2935px) is wider than the screen
            // (1440px), so right-anchoring it needed x=-1495 — and
            // the v402 log proved Flyme clamps a negative window x
            // to 0: the resize log showed x=-1495 but the capsule
            // still landed at pos x=2054 (0+2935-881), i.e. the
            // window stayed left-anchored at x=0 and the capsule
            // went off-screen right. With width == screen width,
            // x = screenW - width = 0 (never negative), the window
            // spans the full screen, and the capsule stays at the
            // right edge (x = screenW - 881). The pill grows left
            // from there; it has 559px of room before the window's
            // left edge, and its tail is a fade so clipping there
            // is barely visible. Height stays 300dp.
            callParams.width = callScreenWidth();
            callParams.height = dp(300);
            // v400: re-assert the window position after resizing.
            // The v399 log proved the capsule slides off the right
            // edge during a left drag (pos x 559 -> 2054 while the
            // finger drags left 746px, transX stays 0) — the window
            // is NOT staying right-anchored on resize, so the whole
            // window (and the capsule in it) drifts right. A bare
            // updateViewLayout with only the size changed evidently
            // lets Flyme reposition the window; updateCallWindowPos
            // re-asserts the anchor in the same update, so the
            // right edge stays on the screen edge.
            // v402: log the window geometry at resize so the next
            // test shows exactly what screen size and x were used.
            updateCallWindowPos();
            FileLog.i("CallWin resize w=" + callParams.width
                    + " h=" + callParams.height
                    + " x=" + callParams.x + " y=" + callParams.y
                    + " g=" + callParams.gravity);
        } catch (Throwable ignored) {
        }
    }

    /** Shrink the right call window back to its idle 240x60dp.
     * v405: deferred 250ms again, AFTER the spring-back.
     * v404 shrank it immediately on release and that broke the
     * tail in both directions (user 2026-11-22 "拖尾呢" /
     * "缩回去的机会" / "右滑弹出的时候"):
     * - Left drag: at release the pill is still LONG (grown);
     *   shrinking the window first clips the tail at the
     *   window's left edge — the tail never gets to retract,
     *   it is just cut off.
     * - Right drag: at release the capsule is still shifted
     *   right by transX; shrinking the window first clips the
     *   capsule's right end (answer button) at the window's
     *   right edge while the 200ms translationX spring-back
     *   is still running — the capsule pops back half-cut.
     * So the window must stay wide until the capsule itself
     * has finished springing back. The 250ms delay is back:
     * the pill reset (resetLongCapsule) and the translationX
     * animation both run inside the still-wide window, and
     * the shrink lands at 250ms after they finish. Hangup /
     * dissipate is unaffected: the capsule is set INVISIBLE
     * and burstParticles spawns at its screen position, and
     * removeCallCapsule/hideCallWindow run 300ms later. */
    public void endCallDragWindow() {
        if (!callDragWindowOpen) {
            return;
        }
        callDragWindowOpen = false;
        callDragWindowShrinkPending = true;
        try {
            if (callDragWindowShrinkRunnable != null) {
                handler.removeCallbacks(
                        callDragWindowShrinkRunnable);
            }
            callDragWindowShrinkRunnable = new Runnable() {
                @Override
                public void run() {
                    callDragWindowShrinkPending = false;
                    callDragWindowShrinkRunnable = null;
                    // v410: animate the shrink (see
                    // animateCallWindowShrink) instead of jumping
                    // it — the one-shot resize is what bounces.
                    // (v413: reverted to v410 — the v411/v412
                    // remove+re-add line flashed once per shrink,
                    // user 2026-11-22 "算了 / 退回上一版".)
                    animateCallWindowShrink();
                }
            };
            handler.postDelayed(callDragWindowShrinkRunnable, 250);
        } catch (Throwable ignored) {
            callDragWindowShrinkPending = false;
        }
    }

    /** v410 (restored in v413): animate the right call window
     * from drag size back to idle (240x60dp). Root cause of the
     * post-settle left bounce (user 2026-11-22 v409 video,
     * frame-by-frame): after a left or right swipe release the
     * capsule springs back, sticks to the edge, and then — ~70ms
     * after it settles — jumps LEFT once and comes back. That
     * jump lines up exactly with the 250ms delayed shrink in
     * endCallDragWindow: the one-shot resize (width 1440->881,
     * x 0->559) is NOT applied atomically by Flyme — the window
     * transiently sits narrow at x=0, so the right-anchored
     * capsule jumps left to ~0 and then comes back when x lands
     * (the "extra bounce", in both directions). The v409 tail
     * only covered the 200ms spring-back, so it never saw this.
     * Animating the width in small steps, with
     * updateCallWindowPos() recomputing x = screenW - width on
     * every frame, keeps x + width == screenW throughout, so the
     * right-anchored capsule stays at the edge for the whole
     * shrink; any per-frame transient is only one step wide.
     * Height lerps 300->60dp alongside. If the window is already
     * idle-sized, just re-assert the position.
     * v413: v411/v412's remove+re-add line is dropped (it
     * flashed once per shrink; user "算了 / 退回上一版") and
     * this v410 animation is back. The ~60px per-step dip
     * remains ("还是有一点点") — accepted, not chased. */
    private void animateCallWindowShrink() {
        if (callParams == null || callRoot == null || wm == null) {
            return;
        }
        final int startW = callParams.width;
        final int startH = callParams.height;
        final int endW = dp(240);
        final int endH = dp(60);
        if (startW <= endW) {
            try {
                callParams.width = endW;
                callParams.height = endH;
                updateCallWindowPos();
            } catch (Throwable ignored) {
            }
            return;
        }
        android.animation.ValueAnimator anim =
                android.animation.ValueAnimator.ofFloat(0f, 1f);
        anim.setDuration(120);
        anim.addUpdateListener(
                new android.animation.ValueAnimator
                        .AnimatorUpdateListener() {
            @Override
            public void onAnimationUpdate(
                    android.animation.ValueAnimator a) {
                float f = (Float) a.getAnimatedValue();
                try {
                    callParams.width = (int) (startW
                            + (endW - startW) * f);
                    callParams.height = (int) (startH
                            + (endH - startH) * f);
                    // x = screenW - width, so x + width stays the
                    // screen width and the capsule never moves.
                    updateCallWindowPos();
                } catch (Throwable ignored) {
                }
            }
        });
        anim.addListener(
                new android.animation.AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(
                    android.animation.Animator animation) {
                try {
                    callParams.width = endW;
                    callParams.height = endH;
                    updateCallWindowPos();
                } catch (Throwable ignored) {
                }
            }
        });
        anim.start();
    }

    private int dp(int v) {
        return Math.round(v * app.getResources().getDisplayMetrics().density);
    }

    /**
     * Dissipate the notifications below the anchor into their own
     * edge icons (user 2026-11-18 "下面的通知消散成各自贴边图标
     * 并且没有消散的通知会自动向上靠"): every non-pinned group below
     * the anchor fades out and becomes one pile icon; the survivors
     * (anchor + pinned) are re-laid out from the top and slide up
     * to close the gap. The particle burst itself is a visual layer
     * (previewed); here the capsule fades/shrinks toward the edge.
     */
    public void dissipateBelowAnchor() {
        if (groups.size() <= 1 || dock == null) {
            return;
        }
        // Expand all first, then stack up one by one from the last
        // notification (user 2026-11-19 "改成全部展开然后由最后一个逐一
        // 向上叠加").
        int mergeDelay = expandAllGroupsAnimated() ? 350 : 0;
        final List<NotifGroup> targets = new ArrayList<>();
        for (int i = 1; i < groups.size(); i++) {
            NotifGroup g = groups.get(i);
            if (!g.pinned) {
                targets.add(g);
            }
        }
        if (targets.isEmpty()) {
            return;
        }
        // Old top (dock coordinates) of every surviving capsule, by key,
        // and the capsule top of each dissipating group.
        final Map<String, Integer> oldTops = new HashMap<>();
        final Map<NotifGroup, Integer> targetTops = new HashMap<>();
        for (int i = 0; i < dock.getChildCount(); i++) {
            View v = dock.getChildAt(i);
            if (v instanceof CapsuleView) {
                CapsuleView c = (CapsuleView) v;
                if (c.getData() != null && c.getData().key != null) {
                    oldTops.put(c.getData().key, (int) v.getY());
                }
                NotifGroup g = (NotifGroup) c.getTag();
                if (g != null && targets.contains(g)) {
                    targetTops.put(g, (int) v.getY());
                }
            }
        }
        // Phase 1 (user 2026-11-19 "由最后一个通知开始逐一缩小成
        // 图标" / "缩小的速度和展开保持一致"): shrink one by one,
        // starting from the last (bottom-most) target and going up,
        // at the expand's pace (240ms each, 110ms apart). Each icon
        // is gathered below its capsule just before its own shrink.
        final List<PileIcon> icons = new ArrayList<>();
        for (int ti = targets.size() - 1; ti >= 0; ti--) {
            final NotifGroup g = targets.get(ti);
            final int order = targets.size() - 1 - ti;
            dock.postDelayed(new Runnable() {
                @Override
                public void run() {
                    Integer top = targetTops.get(g);
                    PileIcon icon = gatherIconBelowCapsule(g,
                            top != null ? top : 0);
                    if (icon != null) {
                        icons.add(icon);
                    }
                    for (int i = 0; i < dock.getChildCount(); i++) {
                        View v = dock.getChildAt(i);
                        if (v instanceof CapsuleView) {
                            CapsuleView c = (CapsuleView) v;
                            if (c.getTag() == g) {
                                stackCapsuleUnder(v);
                                break;
                            }
                        }
                    }
                }
            }, mergeDelay + order * 110);
        }
        dock.postDelayed(new Runnable() {
            @Override
            public void run() {
                groups.removeAll(targets);
                rebuildDock();
                // Phase 2 (user 2026-11-19 "图标贴边不叠加，只有
                // 最后一个通知也缩小再叠加"): all but the last icon snap
                // to the edge; only the last one stacks.
                dockIconsToEdge(icons);
                // Survivors slide up to their new (higher) positions.
                dock.post(new Runnable() {
                    @Override
                    public void run() {
                        for (int i = 0; i < dock.getChildCount(); i++) {
                            View v = dock.getChildAt(i);
                            if (!(v instanceof CapsuleView)) {
                                continue;
                            }
                            CapsuleView c = (CapsuleView) v;
                            Integer oldTop = c.getData() != null
                                    ? oldTops.get(c.getData().key) : null;
                            if (oldTop == null) {
                                continue;
                            }
                            int dy = oldTop - (int) v.getY();
                            if (dy != 0) {
                                v.setTranslationY(dy);
                                v.animate().translationY(0f)
                                        .setDuration(300).withLayer().start();
                            }
                        }
                    }
                });
            }
        }, mergeDelay + (targets.size() - 1) * 110 + 300);
    }


    /**
     * Dissipate the DEEPEST anchor (user 2026-11-18 "直到这个二级
     * 锚点也消散会汇聚成图标，这堆叠加图标继续向一级锚点下面聚拢",
     * and "三级锚点也是类似于二级锚点"): the deepest pinned anchor
     * also dissipates and converges into an icon, joining the fan
     * as the newest (flush at the edge); the whole fan then gathers
     * up to sit below the next anchor up the chain.
     */
    public void dissipateDeepestAnchor() {
        NotifGroup deep = deepestAnchorGroup();
        if (deep == null || dock == null) {
            return;
        }
        final NotifGroup target = deep;
        // Phase 1 (user 2026-11-19 "先缩成图标"): gather the icon
        // below the capsule FIRST, so the capsule visibly becomes it.
        int rowTop = 0;
        for (int i = 0; i < dock.getChildCount(); i++) {
            View v = dock.getChildAt(i);
            if (v instanceof CapsuleView) {
                CapsuleView c = (CapsuleView) v;
                if (c.getTag() == target) {
                    rowTop = (int) v.getY();
                    break;
                }
            }
        }
        final PileIcon icon = gatherIconBelowCapsule(target, rowTop);
        // Stack the capsule under the previous one (user 2026-11-19).
        for (int i = 0; i < dock.getChildCount(); i++) {
            View v = dock.getChildAt(i);
            if (v instanceof CapsuleView) {
                CapsuleView c = (CapsuleView) v;
                if (c.getTag() == target) {
                    stackCapsuleUnder(v);
                }
            }
        }
        dock.postDelayed(new Runnable() {
            @Override
            public void run() {
                groups.remove(target);
                rebuildDock();
                // Phase 2 (user 2026-11-19 "然后再叠加图标"): the icon
                // slides down to stack onto the fan.
                stackIconToFan(icon);
            }
        }, 300);
    }


    /**
     * Dissipate everything ONE BY ONE (user 2026-11-18 "你整个一条
     * 一条消散的"): the non-pinned notifications below the anchor
     * go first, bottom-up, each bursting into particles and joining
     * the fan; then the anchors dissipate one at a time from the
     * deepest up (tertiary, secondary, primary), each joining the
     * fan as the newest while the whole fan gathers up below the
     * next anchor. Each step is ~650ms apart.
     */
    public void dissipateAllOneByOne() {
        if (dock == null || groups.isEmpty() || isDissipatingAll) {
            return;
        }
        // A card is open: close it first; a second tap outside
        // dissipates. The card must not be swept away with the rest.
        // v180: a pinned card is never auto-closed here either.
        // v192: but a pinned card no longer BLOCKS the dissipate —
        // it is a big capsule, and the others below it dissipate
        // normally (the queue skips pinned notifications and calls,
        // so the pinned card stays out).
        if (cardKey != null && !isCardPinned()) {
            // v360: 大卡片模式下卡片不缩回胶囊——直接消散成图标。
            if (FeatureFlags.bigCard) {
                dissipateOpenCard();
            } else {
                closeCard();
            }
            return;
        }
        isDissipatingAll = true;
        // Expand ALL groups first (including pinned ones, so their
        // non-pinned notifications are visible), then dissipate one by
        // one from the bottom (user 2026-11-19 "改成全展开后由下往上依
        // 次消散").
        boolean anyExpanded = false;
        for (NotifGroup g : groups) {
            if (g != null && g.items.size() > 1 && !g.expanded) {
                g.expanded = true;
                anyExpanded = true;
            }
        }
        int mergeDelay = 0;
        if (anyExpanded) {
            rebuildDock();
            cascadeCapsulesIn(new ArrayList<>(groups));
            mergeDelay = 350;
        }
        // The queue is ALL groups, bottom-up. The auto right-swipe is
        // a dissipate (消散), not a dismiss (消通知): it SKIPS OVER the
        // pinned notification of the same app (that one stays out) and
        // dissipates the group's other notifications, which are
        // preserved in the fan (user 2026-11-19 05:44 "自动右滑是消散，
        // 但是不消通知，消散完了它会略过钉住的同应用通知").
        final List<NotifGroup> queue = new ArrayList<>();
        for (int i = groups.size() - 1; i >= 0; i--) {
            NotifGroup g = groups.get(i);
            if (g != null) {
                queue.add(g);
            }
        }
        if (queue.isEmpty()) {
            isDissipatingAll = false;
            return;
        }
        // Phase 1 (user 2026-11-19 "全部都缩成图标了再开始叠加" /
        // "由最后一个通知开始逐一缩小成图标" / "缩小的速度和展开保持一致"):
        // shrink ALL capsules into icons first — one by one, starting
        // from the last (bottom-most) notification and going up, at
        // the expand's pace (240ms each, 110ms apart — the reverse of
        // the top-to-bottom expand). No stacking starts until every
        // shrink has finished.
        // Stack EVERY capsule, one by one, bottom-to-top, regardless
        // of app (user 2026-11-19 "不区分应用，全部叠加到一起").
        final List<View> caps = new ArrayList<>();
        final java.util.Set<String> dissipatedKeys = new java.util.HashSet<>();
        // v457: 自动销毁——开着时要撤销的通知 key（消散时撤销，
        // 不进扇子）。音乐不进这个集合（撤销会停掉音乐，v276 同款）。
        final java.util.Set<String> destroyedKeys = new java.util.HashSet<>();
        for (int i = 0; i < dock.getChildCount(); i++) {
            View v = dock.getChildAt(i);
            if (v instanceof CapsuleView) {
                CapsuleView c = (CapsuleView) v;
                NotifGroup tag = (NotifGroup) c.getTag();
                if (queue.contains(tag)) {
                    // Spare every pinned notification (per-notification
                    // pin); the group's other notifications still
                    // dissipate. A call capsule is never auto-dissipated
                    // (v110): even after unpinning it must stay out
                    // until the call ends (user 2026-11-19 "解除钉住
                    // 后自动右滑仍然是贴边").
                    NotifData d = c.getData();
                    if (d != null && d.isCall) {
                        continue;
                    }

                    if (tag != null && d != null && d.key != null
                            && tag.pinnedAtByKey.containsKey(d.key)) {
                        continue;
                    }
                    caps.add(v);
                    if (d != null && d.key != null) {
                        dissipatedKeys.add(d.key);
                        // v457: 自动销毁——开着时记下来，消散完撤销
                        // 通知、不进扇子（音乐豁免）。
                        if (FeatureFlags.autoDestroy && !d.isMusic) {
                            destroyedKeys.add(d.key);
                        }
                    }
                }
            }
        }
        if (caps.isEmpty()) {
            isDissipatingAll = false;
            return;
        }
        caps.sort(new java.util.Comparator<View>() {
            @Override
            public int compare(View a, View b) {
                // Sort by layout top (topMargin), NOT getY(): getY() is
                // 0 for every capsule until layout runs after
                // rebuildDock, so sorting by it is meaningless. The
                // topMargin is set synchronously by rebuildDock.
                int ta = topOf(a);
                int tb = topOf(b);
                // v362: 大卡片模式下自动消散由上而下依次（用户
                // “自动消散也是由上而下依次”）——胶囊模式保持由下
                // 往上（用户 2026-11-19 “由下往上依次消散”）。
                if (FeatureFlags.bigCard) {
                    return Integer.compare(ta, tb);
                }
                return Integer.compare(tb, ta);
            }
        });
        // Dissipate one by one, bottom to top (user 2026-11-19
        // "改成全展开后由下往上依次消散" / "去除叠加了"): NO stacking
        // any more. v362: in big card mode the sort above flips
        // this to top-to-bottom (user "自动消散也是由上而下依次").
        // After the expand, each capsule bursts into
        // particles IN PLACE, starting from the bottom-most one and
        // going up. caps is already sorted bottom-to-top, so caps[0]
        // is the bottom-most. Stagger 200ms (v94, user "间隔时间让他
        // 显得平滑一点"): the 240ms swipe is still running when the
        // next capsule starts, so the bursts overlap into a smooth
        // continuous cascade instead of choppy one-by-one gaps.
        // Hide all pile icons first, so the fan is empty while the
        // capsules dissipate.
        dock.postDelayed(new Runnable() {
            @Override
            public void run() {
                if (pile != null) {
                    for (int i = 0; i < pile.getChildCount(); i++) {
                        View v = pile.getChildAt(i);
                        if (v instanceof PileIcon) {
                            v.setVisibility(View.INVISIBLE);
                        }
                    }
                }
            }
        }, mergeDelay);
        final int n = caps.size();
        for (int ci = 0; ci < n; ci++) {
            final View cap = caps.get(ci);
            dock.postDelayed(new Runnable() {
                @Override
                public void run() {
                    // Auto right-swipe, then burst at the end of the
                    // swipe (user 2026-11-19 "展开后逐条自动右滑消散").
                    if (cap instanceof CapsuleView) {
                        // v364: 自动消散的右滑带拖尾（用户 “自动消散
                        // 也没有加拖尾”）——普通胶囊走自己的长胶囊，
                        // 放大胶囊走共享拖尾。
                        ((CapsuleView) cap).playAutoSwipeRight(
                                new Runnable() {
                                    @Override
                                    public void run() {
                                        burstParticles(cap);
                                    }
                                }, true);
                    } else {
                        burstParticles(cap);
                    }
                }
            }, mergeDelay + ci * 200);
        }
        // After the last dissipate finishes, the icons pop in.
        int dissipateDone = mergeDelay + n * 200 + 380;
        dock.postDelayed(new Runnable() {
            @Override
            public void run() {
                // Non-pinned groups go away entirely (into the fan).
                // A pinned group keeps its pinned notification; its
                // dissipated notifications go into a NEW docked group
                // for the same app, so the icon string still pops out
                // for them (addPileIconForGroup merges into an existing
                // docked group for that pkg). Clicking the icon
                // expands them back, arranged together with the pinned
                // ones (expandDockedGroup merges by pkg, pinned-first).
                // Collect the pinned groups' docked groups first — do
                // NOT add their icons yet. The pile window must be
                // repositioned by rebuildDock() first (pileTargetY is
                // recomputed from the final state: only the pinned
                // notification left out, so the pile sits right below
                // it), and only then are the icons added, so they pop
                // in below the pinned notification, not elsewhere
                // (user 2026-11-19 05:49 "图标弹出的位置是钉住的通知下
                // 面" / "不要弹到其他地方去").
                final java.util.List<NotifGroup> pinnedDocked =
                        new java.util.ArrayList<>();
                for (NotifGroup g : queue) {
                    if (g.pinned) {
                        NotifGroup docked = new NotifGroup(g.pkg);
                        java.util.Iterator<NotifData> it =
                                g.items.iterator();
                        while (it.hasNext()) {
                            NotifData d = it.next();
                            if (d != null && d.key != null
                                    && dissipatedKeys.contains(d.key)) {
                                it.remove();
                                // v457: 自动销毁——撤销通知，不进扇子；
                                // 否则照常进扇子，点图标能展开回来。
                                if (destroyedKeys.contains(d.key)) {
                                    dismissNotification(d.key);
                                } else {
                                    docked.add(d);
                                }
                            }
                        }
                        if (!docked.items.isEmpty()) {
                            pinnedDocked.add(docked);
                        }
                    } else {
                        // v457: 自动销毁——先把要撤销的从组里摘掉
                        // （撤销通知，不进扇子），剩下的才进扇子。
                        java.util.Iterator<NotifData> dit =
                                g.items.iterator();
                        while (dit.hasNext()) {
                            NotifData dd = dit.next();
                            if (dd != null && dd.key != null
                                    && destroyedKeys.contains(dd.key)) {
                                dit.remove();
                                dismissNotification(dd.key);
                            }
                        }
                        groups.remove(g);
                    }
                }
                rebuildDock();
                // The icons pop directly from off-screen to their
                // designated spots (user 2026-11-19 "汇聚完成后图标直接
                // 由屏幕外弹出到指定地方" — and "改回去": this pop-in was
                // asked for and should NOT have been reverted in v50).
                // Pinned groups' docked icons first (they pop in
                // below the pinned notification), then the rest.
                for (NotifGroup docked : pinnedDocked) {
                    addPileIconForGroup(docked);
                }
                for (NotifGroup g : queue) {
                    // v457: 自动销毁——组里全被撤销了就没图标可弹了。
                    if (!g.pinned && !g.items.isEmpty()) {
                        addPileIconForGroup(g);
                    }
                }
                updatePileWindow();
                isDissipatingAll = false;
                if (pile != null) {
                    for (int i = 0; i < pile.getChildCount(); i++) {
                        View v = pile.getChildAt(i);
                        if (v instanceof PileIcon) {
                            v.setVisibility(View.VISIBLE);
                            v.setTranslationX(-dp(200));
                            v.setAlpha(0f);
                            v.animate().translationX(0f).alpha(1f)
                                    .setDuration(240).withLayer().start();
                        }
                    }
                }
            }
        }, dissipateDone);
    }



}
