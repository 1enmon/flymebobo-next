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
    // v555: 连发突破计数（安静规则三件套之一）——同一发送者
    // （pkg + 会话标题）在 5 分钟滚动窗内的到达记录，按通知
    // key 去重（同一条通知双投递只算一次）。窗内第 3 条起，
    // 场景/静默闸门本要排队时改为突破直弹。记录在每次
    // onNotification 到达时更新（含已弹的也算，见
    // recordBurstHit）；过期条目随每次记录顺手清，发送者表
    // 上限 64 个、空表即删，不留泄漏。
    private static final long BURST_WINDOW_MS = 5 * 60 * 1000L;
    private static final int BURST_THRESHOLD = 3;
    private static final class BurstHit {
        final String notifKey;
        final long at;

        BurstHit(String notifKey, long at) {
            this.notifKey = notifKey;
            this.at = at;
        }
    }
    private final java.util.HashMap<String,
            java.util.ArrayDeque<BurstHit>> burstHits =
            new java.util.HashMap<>();
    // v539: 边缘回看胶囊（wei 2026-11-26 审预览拍板）。普通气泡
    // 被手动左滑消散时，快照进回看栈（头=最近，最多 5 条）；
    // 栈非空时屏幕左边缘出竖向小把手（↩ + 橙色角标 +「回看」
    // 竖字），点它逐个经 onNotification 原样重弹（照样过场景/
    // 静默的门）。把手停留时长无人点 → 清栈淡出（v543 起可调：
    // FeatureFlags.recallTtlMin 分钟，默认 3）。重弹的快照
    // 打 recalled 标记，再划掉不入栈。开关 recallCapsule 默认
    // 关（主动开关）：关着不入栈、不出把手；开→关立即清栈。
    private final java.util.ArrayDeque<NotifData> recallStack =
            new java.util.ArrayDeque<>();
    private static final int RECALL_MAX = 5;
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
    // v546: 长按菜单（快捷回复）——通知动作暂存。NotifCollection
    // Hook 在 SystemUI 里读到真实 Notification 时抽取（回复
    // RemoteInput / 已读动作），按通知 key 存这里；胶囊长按时
    // 按 key 取。只在 SystemUI 进程内流转，不进广播 extras。
    // 胶囊消散 / 通知被移除时清掉对应条目。
    private static final java.util.Map<String, NotifActions>
            ACTIONS_BY_KEY = new java.util.HashMap<>();
    // v546: 长按菜单窗口（一屏一菜单，根=全屏透明层，点别处
    // 收起；面板挂在被长按胶囊的下方）。
    private FrameLayout menuRoot;
    private android.widget.LinearLayout menuPanel;
    private android.widget.LinearLayout menuReplyBox;
    private android.widget.EditText menuReplyInput;
    private android.widget.TextView menuReplyError;
    private WindowManager.LayoutParams menuParams;
    private boolean menuWindowAdded;
    private NotifData menuData;
    // v547: 菜单正淡出关闭中——重复 dismiss 直接忽略；
    // 新浮层打开走 closeOverlays 立即拆，不等动画。
    private boolean menuClosing;
    // v547: 菜单锚点（被长按胶囊的屏幕坐标与尺寸）和放置
    // 方向；回复框展开后面板变高，按同一锚点重算位置。
    private int menuAnchorX;
    private int menuAnchorY;
    private int menuAnchorW;
    private int menuAnchorH;
    private boolean menuPlacedAbove;
    // v546: 稍后提醒——快照 + 10 分钟后重投的定时器（走本类
    // handler，不新起服务）。key -> runnable，同 key 重设覆盖。
    private final java.util.Map<String, Runnable> snoozeTimers =
            new java.util.HashMap<>();
    private static final long SNOOZE_DELAY_MS = 10 * 60_000L;
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
                // v547: 长按菜单也别残留在息屏/锁屏上。
                // v557: 息屏走硬摘版——这种最该摘干净的
                // 时刻不走动画异步路径（动画收尾一旦被
                // 跳过，全屏层就漏在屏上吃触摸）。
                try {
                    if (!on) {
                        dismissMenuNow();
                    }
                } catch (Throwable ignored) {
                }
                // v564: 轻气泡小签/面板跟屏状态走（息屏收、
                // 亮屏按栈状态重评；锁屏中 refreshLightDot
                // 内部守卫会继续藏，解锁广播再调一次）。
                try {
                    refreshLightDot();
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
                public void onShellTopShown(String pkg, String cls) {
                    // v557: 顶层切到桌面/最近任务——三个全屏
                    // 浮层先收掉，别在概览里吃掉任务卡片的
                    // 左右滑（2026-11-26 23:54 事故）。
                    BubbleWindow.this.closeOverlays();
                }

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
        // v555: 安静规则突破判定（连发 / @我）——只影响下面
        // 场景、静默两个排队闸门的决定：本要排队的，命中突破
        // 就照常往下走直弹，并把该发送者已排队的旧条目一并
        // 补弹（onGateBreakThrough，只触发一次）。不排队时
        // （闸门没拦）这两个标志位不产生任何副作用。计数在
        // recordBurstHit 里对每条到达的普通通知更新（含已弹
        // 的也算——连发的语义是"这个人短时间发了很多条"，
        // 不管前面的弹没弹）。
        final boolean brkMention = FeatureFlags.mentionBreak
                && isMentionHit(data);
        final int burstCount = recordBurstHit(data);
        final boolean brkBurst = FeatureFlags.burstBreak
                && burstCount >= BURST_THRESHOLD;
        final boolean brk = brkMention || brkBurst;
        final boolean[] brkFired = new boolean[]{false};
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
                && !SceneExceptions.isExcepted(data.pkg)
                && !(FeatureFlags.lightMode
                        && isOrdinaryForLight(data))) {
            // v555: 命中突破 → 不排队，照常往下走（静默门
            // 同样放行，见 brk 的共用判定）。
            if (brk) {
                onGateBreakThrough(data, brkBurst, brkMention,
                        brkFired);
            } else {
                enqueueSuppressed(data);
                return;
            }
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
            // v555: 命中突破 → 不排队，直弹（同场景门）。
            if (brk) {
                onGateBreakThrough(data, brkBurst, brkMention,
                        brkFired);
            } else {
                enqueueSuppressed(data);
                return;
            }
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
        // v542: recalled snapshots exempt too — a recall tap
        // re-delivers within the 2s window by design (onRecallTap
        // also removes the seen slot; belt and braces).
        if (!data.isMusic && !data.isDownload && !data.isLive
                && !data.recalled
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

    /** v555: 连发计数的发送者口径：pkg + 会话标题/发送者名
     * （聊天通知的标题就是对方名字或群名）。 */
    private static String burstSenderKey(NotifData data) {
        String who = data.title == null ? "" : data.title.trim();
        return data.pkg + "\u0001" + who;
    }

    /** v555: 突破只管普通通知——来电/音乐/下载/实况/导航/
     * 公交各有自己的路，不参与连发计数与 @ 判定（它们本来
     * 也基本不走这两个排队闸门）。回看/稍后重投的快照带
     * recalled 标记，不重复计数。 */
    private static boolean burstCountable(NotifData data) {
        return data != null && data.pkg != null
                && !data.isCall && !data.isMusic && !data.isDownload
                && !data.isLive && !data.isNavi && !data.isBus
                && !data.recalled;
    }

    /** v555: 记一次到达并返回该发送者 5 分钟窗内的计数。
     * 按通知 key 去重：同一条通知被 Hook 和广播双投递、或
     * 排队补弹时二次经过 onNotification，都只算一次。顺手
     * 清过期记录和空表；发送者表超过 64 个时再全表扫一遍
     * 扔掉全过期的。不合计数口径的返回 0。 */
    private int recordBurstHit(NotifData data) {
        if (!burstCountable(data)) {
            return 0;
        }
        long now = System.currentTimeMillis();
        String sender = burstSenderKey(data);
        synchronized (burstHits) {
            java.util.ArrayDeque<BurstHit> dq = burstHits.get(sender);
            if (dq == null) {
                dq = new java.util.ArrayDeque<>();
                burstHits.put(sender, dq);
            }
            // 清窗外旧记录。
            while (!dq.isEmpty()
                    && now - dq.peekFirst().at > BURST_WINDOW_MS) {
                dq.pollFirst();
            }
            // 同 key 已记过 = 同一条通知的重复投递，不重复计。
            boolean dup = false;
            if (data.key != null) {
                for (BurstHit h : dq) {
                    if (data.key.equals(h.notifKey)) {
                        dup = true;
                        break;
                    }
                }
            }
            if (!dup) {
                dq.addLast(new BurstHit(data.key, now));
            }
            int count = dq.size();
            // 表膨胀兜底：全表清一遍全过期的发送者。
            if (burstHits.size() > 64) {
                java.util.Iterator<java.util.Map.Entry<String,
                        java.util.ArrayDeque<BurstHit>>> it =
                        burstHits.entrySet().iterator();
                while (it.hasNext()) {
                    java.util.ArrayDeque<BurstHit> q =
                            it.next().getValue();
                    while (!q.isEmpty() && now - q.peekFirst().at
                            > BURST_WINDOW_MS) {
                        q.pollFirst();
                    }
                    if (q.isEmpty()) {
                        it.remove();
                    }
                }
            }
            return count;
        }
    }

    /** v555: @ 模式清单——标题+正文任一命中即算被 @。
     * 「有人@我」被「@我」子串覆盖，列全只为可读。全角＠
     * （U+FF20）变体一并认。NotifData 不带原始 extras，
     * 没有标准 mention 标记可读；微信/QQ 的 @ 通知本来就
     * 把「有人@我」写在正文里，文本模式即覆盖真实场景。 */
    private static final String[] MENTION_PATTERNS = {
            "@我", "＠我", "有人@我", "有人＠我",
            "@了你", "＠了你", "@了我", "＠了我"};

    private static boolean isMentionHit(NotifData data) {
        if (!burstCountable(data)) {
            return false;
        }
        String hay = (data.title == null ? "" : data.title)
                + "\n" + (data.text == null ? "" : data.text);
        for (String p : MENTION_PATTERNS) {
            if (hay.contains(p)) {
                return true;
            }
        }
        return false;
    }

    /** v555: 闸门处命中突破的统一动作（两个闸门共用，
     * fired 保证一次到达只触发一次）：打日志，并把该发送者
     * 已排队的旧条目一并补弹——从队列里摘出来直接走
     * popCapsule（它们当初就是被闸门拦下的，突破的语义是
     * 现在全放行；popCapsule 内部的应用规则/去重照常把关）。
     * 触发条目本身不走这里——它由 onNotification 的主流程
     * 继续往下弹。 */
    private void onGateBreakThrough(NotifData data, boolean burst,
            boolean mention, boolean[] fired) {
        if (fired[0]) {
            return;
        }
        fired[0] = true;
        if (mention) {
            FileLog.i("MentionBreak pkg=" + data.pkg);
        }
        if (burst) {
            String who = data.title == null
                    ? "" : data.title.trim();
            FileLog.i("BurstBreak pkg=" + data.pkg + " who=" + who);
        }
        String sender = burstSenderKey(data);
        java.util.ArrayList<NotifData> mine =
                new java.util.ArrayList<>();
        synchronized (this.suppressedQueue) {
            java.util.Iterator<NotifData> it =
                    this.suppressedQueue.iterator();
            while (it.hasNext()) {
                NotifData qd = it.next();
                if (qd.pkg != null
                        && sender.equals(burstSenderKey(qd))) {
                    mine.add(qd);
                    it.remove();
                }
            }
        }
        if (!mine.isEmpty()) {
            for (final NotifData qd : mine) {
                handler.post(new Runnable() {
                    @Override
                    public void run() {
                        popCapsule(qd);
                    }
                });
            }
        }
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
            // v543: 保留时长读设置（分钟），不再写死。
            handler.postDelayed(recallExpiryRunnable,
                    FeatureFlags.recallTtlMin * 60_000L);
        }
    }

    /** v543: 保留时长刚被改（广播到达，FeatureFlags 已更新）：
     * 栈里还有条目时按新值重新计时（从现在起算）。 */
    public void onRecallTtlChanged() {
        handler.post(new Runnable() {
            @Override
            public void run() {
                armRecallTimer();
            }
        });
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

    // ============================================================
    // v564 (2.5.0): 轻气泡设计模式（wei 2026-11-27 定稿，预览
    // 已过：切换页与动画 + 音乐收纳卡片）。开启后（FeatureFlags
    // .lightMode）普通通知不弹胶囊：静默收进 lightStack，边缘
    // 小签是试验田「通知收纳」v0.5-v0.7 验证过的简洁模式形态
    // （栈空全隐藏 / 到达药丸只显示最新一条来源 / 落回呼吸点 /
    // 位置可拖可设）；音乐不弹胶囊也不进通知栏，只在收纳面板
    // 里置顶一张音乐卡，播放时呼吸灯变色流转；场景里被压住的
    // 普通通知并入收纳、面板只读。来电完全不碰。经典路径在
    // lightMode 关时零改动（所有新分支都先判 lightMode）。
    // ============================================================
    private final java.util.ArrayDeque<NotifData> lightStack =
            new java.util.ArrayDeque<>();
    private static final int LIGHT_MAX = 20;
    // 小签窗口（根=FrameLayout，内含横排 body 与角标）。
    private FrameLayout lightDotRoot;
    private android.widget.LinearLayout lightDotBody;
    private View lightDotTab;
    private android.graphics.drawable.GradientDrawable lightTabBg;
    private android.widget.LinearLayout lightPill;
    private android.widget.ImageView lightPillIcon;
    private android.widget.TextView lightPillLabel;
    // v569: 副药丸——放歌开词时新通知先在歌词下面闪烁、
    // 闪完再收纳（wei 定），不抢主药丸的歌词位。副药丸
    // 挂在主药丸下方的药丸列里，自带闪烁与计时。
    private android.widget.LinearLayout lightPillCol;
    private android.widget.LinearLayout lightSubPill;
    private android.widget.ImageView lightSubPillIcon;
    private android.widget.TextView lightSubPillLabel;
    private android.animation.ValueAnimator lightSubBlink;
    private boolean lightSubShowing;
    private final Runnable lightSubEndRunnable = new Runnable() {
        @Override
        public void run() {
            dismissLightSubPill();
            if (!lightPulsing && lightDotTab != null) {
                lightDotTab.setVisibility(View.VISIBLE);
            }
            refreshLightDot();
        }
    };
    private android.widget.TextView lightBadge;
    private WindowManager.LayoutParams lightDotParams;
    private boolean lightDotAdded;
    private android.animation.ValueAnimator lightBreath;
    private boolean lightPulsing;
    // v575: 正在闪的到达药丸/副药丸是哪一条——点开它时
    // 立即停闪（wei：点开后立即收纳、不要再继续闪烁）。
    private String lightFlashKey;
    private String lightSubFlashKey;
    // v611: AI 摘要循环闪（wei 定：摘要完成了就发出
    // 摘要通知一直闪；打开面板才停；放歌跟词时不显
    // 示——歌词优先，音乐停了/词关了再接着闪）。
    private String lightAiFlashText;
    private boolean lightAiPillShowing;
    private final Runnable lightPulseEndRunnable = new Runnable() {
        @Override
        public void run() {
            boolean wasArrival = lightFlashKey != null;
            lightPulsing = false;
            lightFlashKey = null;
            lightAiPillShowing = false;
            if (lightPill != null) {
                lightPill.setVisibility(View.GONE);
            }
            if (lightDotTab != null && !lightSubShowing) {
                lightDotTab.setVisibility(View.VISIBLE);
            }
            refreshLightDot();
            // v577: 刚才是通知在闪——它闪完收纳了，歌词
            // 回来把当前行再亮出来，接着跟随（歌词自己的
            // 闪结束时 wasArrival=false，不会自循环）。
            if (wasArrival && lightLyricFollow
                    && lightMusicData != null
                    && lightMusicLyric != null
                    && !lightMusicLyric.isEmpty()
                    && !"找不到当前歌词".equals(lightMusicLyric)) {
                flashLightLyricPill(lightMusicLyric);
            }
            // v611: AI 摘要续闪——没被到达/歌词占住就
            // 接着闪（放歌跟词时 maybe 里让路、不闪）。
            maybeFlashLightSummary();
        }
    };
    // 收纳面板（根=全屏透明层，盒在小签同侧）。
    private FrameLayout lightPanelRoot;
    private android.widget.LinearLayout lightPanelBox;
    private boolean lightPanelAdded;
    private boolean lightPanelRecallMode;
    private boolean lightPanelReadOnly;
    // v570: 面板内嵌长按菜单（展开在被长按行下方、挤开其他
    // 行）与面板窗口参数（回复框要输入法时临时改可聚焦）。
    private android.widget.LinearLayout lightInlineMenu;
    private String lightInlineForKey;
    private WindowManager.LayoutParams lightPanelLp;
    // v574: 面板滚动区与行容器的活引用——内嵌菜单展开时
    // 把面板可视高撑开（applyLightScrollCap）要原地改它。
    private android.widget.ScrollView lightPanelScroll;
    private android.widget.LinearLayout lightPanelRows;
    private android.animation.ValueAnimator lightCapAnim;
    // 面板音乐卡的活引用（进度 tick 原地刷新，不整表重建）。
    private android.widget.ProgressBar lightMusicBar;
    private android.widget.TextView lightMusicTime;
    private LightMusicBtn lightMusicPlayBtn;
    private LightMusicBtn lightMusicFavBtn;
    private LightMusicBtn lightMusicLyricBtn;
    private android.widget.TextView lightMusicLyricView;
    private final Runnable lightMusicTickRunnable = new Runnable() {
        @Override
        public void run() {
            updateLightMusicProgressViews();
            if (lightPanelAdded && lightMusicData != null) {
                handler.postDelayed(this, 1000);
            }
        }
    };
    // 音乐状态（轻气泡自有，不依赖经典音乐胶囊是否存在）。
    private NotifData lightMusicData;
    // v576: 下载状态（轻气泡自有）——经典下载胶囊在轻气
    // 泡下不弹，下载进面板当卡片（音乐卡之下、收纳列表
    // 之上），进度原地刷新，完成停留 3 秒自动撤（同经典
    // 口径）。面板卡的活引用按 key 存，进度广播来了原地
    // 刷，不整表重建。
    private final java.util.LinkedHashMap<String, NotifData>
            lightDownloads = new java.util.LinkedHashMap<>();
    private final java.util.HashMap<String,
            android.widget.ProgressBar> lightDlBars =
                    new java.util.HashMap<>();
    private final java.util.HashMap<String,
            android.widget.TextView> lightDlTitles =
                    new java.util.HashMap<>();
    private final java.util.HashMap<String,
            android.widget.TextView> lightDlTexts =
                    new java.util.HashMap<>();
    private final java.util.HashMap<String,
            android.widget.TextView> lightDlPcts =
                    new java.util.HashMap<>();
    // v599: 每张下载卡最后一次拿到新数据的时间（防僵
    // 尸卡清扫用，见 sweepStaleLightDownloads）。
    private final java.util.HashMap<String, Long>
            lightDlSeen = new java.util.HashMap<>();
    // v600: 轻气泡 AI（摘要+打标）——SystemUI 只管发
    // 请求与渲染：收纳栈变化（签名变）才广播给 App
    // 进程分析，结果回来存这里。标签值 suspect /
    // promo / important，按通知 key 存。
    private String lightAiSummary;
    private final java.util.HashMap<String, String>
            lightAiTags = new java.util.HashMap<>();
    private String lightAiReqSig;
    private Runnable lightAiDebounce;
    // v601: 信息卡片（本地解析）——收纳到的普通通知里
    // 认出火车票/机票/快递/账单的，在面板里成票面卡
    // 替代普通行（同码卡片口径）。按解析 id 存卡，
    // 另存通知 key -> 卡 id（行跳过、打开/移除联动）。
    // 持久化到 /sdcard/flymebobo/info_cards.txt，
    // SystemUI 重启后恢复（源通知可能已不在栈里，卡
    // 独立显示，靠卡上「删除」收）。
    private final java.util.LinkedHashMap<String,
            InfoParse.Card> lightInfoCards =
                    new java.util.LinkedHashMap<>();
    private final java.util.HashMap<String, String>
            lightInfoKeyToId = new java.util.HashMap<>();
    private boolean lightInfoLoaded;
    // v605: 信息卡 AI 兜底+核对（wei 定：开了 AI 就
    // 启用，本地+AI 同时核对）——待核对队列（防抖批
    // 发）、已核对签名（key@postTime，防重复烧钱）。
    private final java.util.LinkedHashMap<String,
            NotifData> lightInfoAiPending =
                    new java.util.LinkedHashMap<>();
    private final java.util.HashSet<String>
            lightInfoAiChecked = new java.util.HashSet<>();
    private Runnable lightInfoAiDebounce;
    // v593: 码卡片倒计时（原文写了有效期才有）的活引
    // 用与到期点，按 key 存，面板开时 1 秒一刷。
    private final java.util.HashMap<String,
            android.widget.TextView> lightCdTexts =
                    new java.util.HashMap<>();
    private final java.util.HashMap<String, View>
            lightCdFills = new java.util.HashMap<>();
    private final java.util.HashMap<String, View>
            lightCdSpaces = new java.util.HashMap<>();
    private final java.util.HashMap<String, Long>
            lightCdDeadline = new java.util.HashMap<>();
    private final java.util.HashMap<String, Long>
            lightCdTotal = new java.util.HashMap<>();
    private final Runnable lightCodeTickRunnable =
            new Runnable() {
                @Override
                public void run() {
                    if (!lightPanelAdded
                            || lightCdDeadline.isEmpty()) {
                        return;
                    }
                    updateLightCountdowns();
                    handler.postDelayed(this, 1000);
                }
            };

    /** v593: 倒计时一刷：文字「还剩 m:ss 过期」/「已
     * 过期」，细条按剩余比例缩。 */
    private void updateLightCountdowns() {
        long now = System.currentTimeMillis();
        for (java.util.Map.Entry<String, Long> e
                : lightCdDeadline.entrySet()) {
            String key = e.getKey();
            long remain = e.getValue() - now;
            android.widget.TextView t = lightCdTexts.get(key);
            if (t != null) {
                if (remain <= 0) {
                    t.setText("已过期");
                    t.setTextColor(Color.rgb(248, 113, 113));
                } else {
                    long sec = (remain + 999) / 1000;
                    t.setText("还剩 " + (sec / 60) + ":"
                            + String.format(
                                    java.util.Locale.US, "%02d",
                                    sec % 60) + " 过期");
                }
            }
            Long total = lightCdTotal.get(key);
            View fill = lightCdFills.get(key);
            View space = lightCdSpaces.get(key);
            if (total != null && total > 0 && fill != null
                    && space != null) {
                float frac = remain <= 0 ? 0f
                        : Math.min(1f, remain / (float) total);
                setLightWeight(fill, frac);
                setLightWeight(space, 1f - frac);
            }
        }
    }

    private static void setLightWeight(View v, float w) {
        android.widget.LinearLayout.LayoutParams lp =
                (android.widget.LinearLayout.LayoutParams)
                        v.getLayoutParams();
        if (lp.weight != w) {
            lp.weight = w;
            v.setLayoutParams(lp);
        }
    }
    private String lightMusicTitle;
    private String lightMusicArtist;
    private String lightMusicLyric;
    private android.graphics.Bitmap lightMusicArt;
    private long lightMusicPosMs;
    private long lightMusicDurMs;
    private boolean lightMusicPlaying;
    // v565: 收藏状态（会话真值优先，hasFavorite=false 时本地
    // 翻转兜底）与歌词跟随开关、封面取色缓存。
    private boolean lightMusicFavorite;
    private boolean lightMusicHasFavorite;
    private boolean lightLyricFollow;
    private int lightMusicTint;
    private android.graphics.Bitmap lightMusicTintSrc;
    // 应用名/图标缓存（按展示包名，推送还原后按真实包名）。
    private final java.util.HashMap<String, String> lightLabelCache =
            new java.util.HashMap<>();
    private final java.util.HashMap<String,
            android.graphics.drawable.Drawable> lightIconCache =
            new java.util.HashMap<>();

    /** v564: 轻气泡眼里的「普通通知」：音乐/下载/实况/导航/
     * 公交/来电都不是（与已移除收纳点的 isOrdinaryForCollect
     * 同口径）。 */
    private static boolean isOrdinaryForLight(NotifData d) {
        return d != null && d.pkg != null
                && !d.isMusic && !d.isDownload && !d.isLive
                && !d.isNavi && !d.isBus && !d.isCall;
    }

    /** v564: 展示包名——推送服务送达的已还原真实来源时用
     * 真实包名取图标/名字，还原不到回退 pkg 本值。 */
    private static String lightDisplayPkg(NotifData d) {
        if (d != null && d.srcPkg != null && !d.srcPkg.isEmpty()) {
            return d.srcPkg;
        }
        return d == null ? null : d.pkg;
    }

    private String lightAppLabel(String pkg, NotifData d) {
        if (d != null && d.srcLabel != null
                && pkg != null && pkg.equals(d.srcPkg)) {
            return d.srcLabel;
        }
        if (pkg == null) {
            return "";
        }
        String cached = lightLabelCache.get(pkg);
        if (cached != null) {
            return cached;
        }
        String label = pkg;
        try {
            android.content.pm.PackageManager pm =
                    app.getPackageManager();
            android.content.pm.ApplicationInfo ai =
                    pm.getApplicationInfo(pkg, 0);
            CharSequence cs = pm.getApplicationLabel(ai);
            if (cs != null && cs.length() > 0) {
                label = cs.toString();
            }
        } catch (Throwable ignored) {
        }
        lightLabelCache.put(pkg, label);
        return label;
    }

    private android.graphics.drawable.Drawable lightAppIcon(
            String pkg, NotifData d) {
        if (pkg != null) {
            android.graphics.drawable.Drawable cached =
                    lightIconCache.get(pkg);
            if (cached != null) {
                return cached;
            }
            try {
                android.graphics.drawable.Drawable dr =
                        app.getPackageManager()
                                .getApplicationIcon(pkg);
                if (dr != null) {
                    lightIconCache.put(pkg, dr);
                    return dr;
                }
            } catch (Throwable ignored) {
            }
        }
        if (d != null && d.icon != null) {
            return new android.graphics.drawable.BitmapDrawable(
                    app.getResources(), d.icon);
        }
        return null;
    }

    /** v564: 相对时间（面板行右侧）：刚刚 / N分钟前 / N小时前。 */
    private static String lightAgoText(long postTime) {
        long diff = System.currentTimeMillis() - postTime;
        if (diff < 60_000L) {
            return "刚刚";
        }
        if (diff < 3600_000L) {
            return (diff / 60_000L) + "分钟前";
        }
        if (diff < 86400_000L) {
            return (diff / 3600_000L) + "小时前";
        }
        return (diff / 86400_000L) + "天前";
    }

    /** v564: 普通通知入轻气泡收纳栈（popCapsule 的轻气泡
     * 分支调用，已在主线程）。同 key 替换、上限 20、溢出最老
     * 一条进时光机（回看栈）。 */
    // v572: 轻气泡 App 三栏（收纳 / 时光机）的镜像通道——
    // SystemUI 侧每次收纳栈 / 时光机栈 / 音乐变化后，把快照
    // 写到 /sdcard/flymebobo/light_mirror.txt 并广播，App
    // 侧两栏读它渲染（试验田 v0.3 同款路子）。格式：每行
    // 一条、制表符分隔、字段 URL 编码：
    //   V\t1 / S\t<场景中0/1> / M\t<播放中0/1>\t<歌名>\t<歌手>\t<包名>
    //   C\t<包名>\t<标题>\t<正文>\t<时间ms>（收纳，新→旧）
    //   R\t<同上>（时光机，新→旧）
    // 应用名与图标由 App 侧按包名自己解析，不进镜像。
    static final String ACTION_LIGHT_MIRROR =
            "com.chenan.flymebobo.LIGHT_MIRROR";
    private static final String LIGHT_MIRROR_PATH =
            "/sdcard/flymebobo/light_mirror.txt";

    private static String mirrorField(String s) {
        if (s == null) {
            return "";
        }
        try {
            return java.net.URLEncoder.encode(s, "UTF-8");
        } catch (Throwable t) {
            return "";
        }
    }

    private void writeLightMirror() {
        if (!FeatureFlags.lightMode) {
            return;
        }
        try {
            StringBuilder sb = new StringBuilder();
            sb.append("V\t1\n");
            sb.append("S\t").append(sceneGuard != null
                    && sceneGuard.isSuppressed() ? 1 : 0)
                    .append('\n');
            if (lightMusicData != null) {
                sb.append("M\t")
                        .append(lightMusicPlaying ? 1 : 0)
                        .append('\t')
                        .append(mirrorField(lightMusicData.title))
                        .append('\t')
                        .append(mirrorField(lightMusicData.text))
                        .append('\t')
                        .append(mirrorField(lightMusicData.pkg))
                        .append('\n');
            }
            for (NotifData d : lightStack) {
                sb.append("C\t")
                        .append(mirrorField(lightDisplayPkg(d)))
                        .append('\t')
                        .append(mirrorField(d.title)).append('\t')
                        .append(mirrorField(d.text)).append('\t')
                        .append(d.postTime).append('\t')
                        .append(mirrorField(d.key)).append('\n');
            }
            for (NotifData d : recallStack) {
                sb.append("R\t")
                        .append(mirrorField(lightDisplayPkg(d)))
                        .append('\t')
                        .append(mirrorField(d.title)).append('\t')
                        .append(mirrorField(d.text)).append('\t')
                        .append(d.postTime).append('\t')
                        .append(mirrorField(d.key)).append('\n');
            }
            java.io.File dir = new java.io.File("/sdcard/flymebobo");
            if (!dir.exists()) {
                dir.mkdirs();
            }
            java.io.FileOutputStream fos =
                    new java.io.FileOutputStream(LIGHT_MIRROR_PATH);
            fos.write(sb.toString().getBytes("UTF-8"));
            fos.close();
            android.content.Intent mi =
                    new android.content.Intent(ACTION_LIGHT_MIRROR);
            mi.setPackage("com.chenan.flymebobo");
            app.sendBroadcast(mi);
        } catch (Throwable ignored) {
        }
    }

    private void lightCollect(final NotifData d) {
        if (d == null || !FeatureFlags.lightMode
                || !isOrdinaryForLight(d)) {
            return;
        }
        if (d.key != null) {
            java.util.Iterator<NotifData> it =
                    lightStack.iterator();
            while (it.hasNext()) {
                NotifData o = it.next();
                if (o != null && d.key.equals(o.key)) {
                    it.remove();
                }
            }
        }
        lightStack.addFirst(d);
        while (lightStack.size() > LIGHT_MAX) {
            NotifData old = lightStack.removeLast();
            if (old != null && old != d) {
                lightPushRecall(old);
            }
        }
        FileLog.i("Light collect pkg=" + d.pkg
                + " n=" + lightStack.size());
        maybeMakeInfoCard(d);
        refreshLightDot();
        // v595: wei 定——安静规则设了「收纳」(EDGE，含走
        // 「全部应用」默认落到收纳的）的新通知直接进面
        // 板、只提示角标，不闪药丸；跟随的照旧闪。
        if (AppRules.modeFor(d.pkg) == AppRules.EDGE) {
            FileLog.i("Light collect quiet pkg=" + d.pkg);
        } else {
            flashLightPill(d);
        }
        if (lightPanelAdded && !lightPanelRecallMode) {
            rebuildLightPanelRows();
        }
        writeLightMirror();
    }

    /** v578: 「音乐卡片」关时的音乐落点——按普通通知
     * 收纳，但音乐通知会同 key 反复重投（切歌/进度），
     * 已在栈的只原地更新内容、不重复闪药丸；新 key 入
     * 栈才闪一次（新会话到达）。 */
    private void lightCollectMusicOff(NotifData d) {
        if (d == null || d.key == null) {
            return;
        }
        for (NotifData o : lightStack) {
            if (o != null && d.key.equals(o.key)) {
                o.title = d.title;
                o.text = d.text;
                if (lightPanelAdded && !lightPanelRecallMode) {
                    rebuildLightPanelRows();
                }
                writeLightMirror();
                return;
            }
        }
        lightStack.addFirst(d);
        while (lightStack.size() > LIGHT_MAX) {
            NotifData old = lightStack.removeLast();
            if (old != null && old != d) {
                lightPushRecall(old);
            }
        }
        FileLog.i("Light collect(music as notif) pkg="
                + d.pkg + " n=" + lightStack.size());
        refreshLightDot();
        // v595: 同 lightCollect——规则是「收纳」的只提
        // 示角标，不闪药丸。
        if (AppRules.modeFor(d.pkg) != AppRules.EDGE) {
            flashLightPill(d);
        }
        if (lightPanelAdded && !lightPanelRecallMode) {
            rebuildLightPanelRows();
        }
        writeLightMirror();
    }

    /** v578: 「音乐卡片」开关翻动（XposedEntry 转发）——
     * 关：立即清音乐状态（面板音乐卡、歌词跟随、变色呼
     * 吸都收），之后的音乐走普通收纳；开：不预置状态，
     * 下一次音乐通知进来自然点亮卡片。 */
    public void onLightMusicCardChanged(final boolean on) {
        handler.post(new Runnable() {
            @Override
            public void run() {
                FileLog.i("Light musicCard changed -> " + on);
                if (!on) {
                    String goneKey = lightMusicData != null
                            ? lightMusicData.key : null;
                    lightMusicData = null;
                    lightMusicArt = null;
                    lightMusicPlaying = false;
                    lightMusicFavorite = false;
                    lightMusicHasFavorite = false;
                    if (lightLyricFollow) {
                        lightLyricFollow = false;
                        FileLog.i("Light lyric off");
                        sendLightLyricListen(false, goneKey);
                    }
                    dismissLightSubPill();
                }
                refreshLightDot();
                if (lightPanelAdded && !lightPanelRecallMode) {
                    rebuildLightPanelRows();
                }
                writeLightMirror();
            }
        });
    }

    /** v598: 下载文案防闪（wei 录屏逐帧定位）——酷安
     * 这类源成对重投下载通知：一份全量文案（正在下载
     * 17% · 327MB/1.9GB）、一份简版（正在下载），卡面
     * 文案跟着来回跳，看到就是一闪。同一下载的文案只
     * 许换全、不许变短：新文案比旧的短且是旧的前缀时，
     * 保留旧文案。 */
    private static String lightDlTextGuard(String oldT,
            String newT) {
        if (newT == null) {
            return oldT;
        }
        if (oldT == null || oldT.isEmpty()) {
            return newT;
        }
        if (newT.length() < oldT.length()
                && oldT.startsWith(newT)) {
            return oldT;
        }
        return newT;
    }

    /** v599: 下载僵尸卡清扫（wei 04:29 截图+日志定
     * 位）——英雄联盟手游这类源 04:00:23 发了一条「0%」
     * 通知之后再也不更新、也不撤（下载在游戏内走，通
     * 知是摆设），卡在面板里挂了半小时。规则：超过
     * 10 分钟没有任何新数据的下载卡直接退休（连同活
     * 引用与时间戳）；源头真恢复下载会重新发通知，卡
     * 会自己回来。在 refreshLightDot 与开面板时各扫
     * 一遍，只动数据不动视图（面板开着时由调用方重
     * 建）。 */
    private boolean sweepStaleLightDownloads() {
        if (lightDownloads.isEmpty()) {
            return false;
        }
        boolean changed = false;
        long now = android.os.SystemClock.uptimeMillis();
        java.util.Iterator<String> it =
                lightDownloads.keySet().iterator();
        while (it.hasNext()) {
            String k = it.next();
            Long seen = lightDlSeen.get(k);
            if (seen == null) {
                lightDlSeen.put(k, now);
                continue;
            }
            if (now - seen.longValue() > 10 * 60 * 1000L) {
                it.remove();
                lightDlSeen.remove(k);
                lightDlBars.remove(k);
                lightDlTitles.remove(k);
                lightDlTexts.remove(k);
                lightDlPcts.remove(k);
                FileLog.i("Light download stale retire");
                changed = true;
            }
        }
        return changed;
    }

    /** v576: 下载进轻气泡——不弹经典胶囊，存进下载表，
     * 面板里当卡片显示（见 buildLightDownloadCard）；进
     * 度细更新走 updateDownloadProgress 的广播，这里只
     * 管到达与整表刷新口径。新 key 才重建面板，已有 key
     * 原地刷卡片视图。 */
    private void lightDownloadIn(NotifData d) {
        if (d == null || d.key == null) {
            return;
        }
        boolean isNew = !lightDownloads.containsKey(d.key);
        if (isNew) {
            lightDownloads.put(d.key, d);
            FileLog.i("Light download in pkg=" + d.pkg);
        } else {
            NotifData old = lightDownloads.get(d.key);
            if (old != null && old != d) {
                old.title = lightDlTextGuard(old.title,
                        d.title);
                old.text = lightDlTextGuard(old.text, d.text);
                if (d.downloadPct >= 0) {
                    old.downloadPct = d.downloadPct;
                }
            }
        }
        lightDlSeen.put(d.key,
                android.os.SystemClock.uptimeMillis());
        refreshLightDot();
        if (lightPanelAdded && !lightPanelRecallMode) {
            if (isNew) {
                rebuildLightPanelRows();
            } else {
                updateLightDownloadViews(d.key);
            }
        }
    }

    /** v576: 进度广播（NS 的下载 tick）在轻气泡下的落
     * 点：更新下载表 + 面板卡原地刷新；完成时先把完成文
     * 案亮出来，3 秒后撤卡（同经典 v242 口径）。 */
    private void lightDownloadProgress(String key, String title,
            String text, int pct, boolean completed) {
        NotifData d = lightDownloads.get(key);
        if (d == null) {
            return;
        }
        d.title = lightDlTextGuard(d.title, title);
        d.text = lightDlTextGuard(d.text, text);
        lightDlSeen.put(key,
                android.os.SystemClock.uptimeMillis());
        if (pct >= 0) {
            d.downloadPct = pct;
        }
        if (lightPanelAdded && !lightPanelRecallMode) {
            updateLightDownloadViews(key);
        }
        if (completed) {
            final String ckey = key;
            handler.postDelayed(new Runnable() {
                @Override
                public void run() {
                    if (lightDownloads.remove(ckey) != null) {
                        FileLog.i("Light download done remove");
                        refreshLightDot();
                        if (lightPanelAdded
                                && !lightPanelRecallMode) {
                            rebuildLightPanelRows();
                        }
                    }
                }
            }, 3000);
        }
    }

    /** v576: 面板下载卡原地刷新（标题/状态/进度条/百分比）。
     * v590: 全部字段先比对再写——tick 每 500ms 一发，值
     * 没变也 setText 会让文字重绘一遍，录屏里就是闪。 */
    private void updateLightDownloadViews(String key) {
        NotifData d = lightDownloads.get(key);
        if (d == null) {
            return;
        }
        android.widget.TextView t = lightDlTitles.get(key);
        if (t != null) {
            String nt = d.title != null ? d.title : "";
            if (!nt.contentEquals(t.getText())) {
                t.setText(nt);
            }
        }
        android.widget.TextView x = lightDlTexts.get(key);
        if (x != null) {
            String nx = d.text != null ? d.text : "";
            if (!nx.contentEquals(x.getText())) {
                x.setText(nx);
            }
        }
        android.widget.ProgressBar b = lightDlBars.get(key);
        if (b != null && d.downloadPct >= 0
                && b.getProgress() != d.downloadPct) {
            b.setProgress(d.downloadPct);
        }
        android.widget.TextView p = lightDlPcts.get(key);
        if (p != null) {
            String np = d.downloadPct >= 0
                    ? d.downloadPct + "%" : "";
            if (!np.contentEquals(p.getText())) {
                p.setText(np);
            }
        }
    }

    /** v576: 面板下载卡——图标（通知大图优先）+ 标题 +
     * 状态行 + 细进度条 + 百分比，摆法跟音乐卡一族；点
     * 卡片走这条下载自己的打开通道（下载页/安装页）。 */
    private View buildLightDownloadCard(final NotifData d) {
        android.widget.LinearLayout card =
                new android.widget.LinearLayout(app);
        card.setOrientation(
                android.widget.LinearLayout.VERTICAL);
        GradientDrawable bg = new GradientDrawable();
        if (lpWhite()) {
            bg.setColor(Color.argb(248, 248, 250, 252));
            bg.setStroke(dp(1), Color.rgb(226, 232, 240));
        } else {
            bg.setColor(Color.argb(242, 30, 36, 52));
        }
        bg.setCornerRadius(dp(14));
        card.setBackground(bg);
        card.setPadding(dp(10), dp(10), dp(10), dp(8));
        android.widget.LinearLayout.LayoutParams cardLp =
                new android.widget.LinearLayout.LayoutParams(
                        android.widget.LinearLayout.LayoutParams
                                .MATCH_PARENT,
                        android.widget.LinearLayout.LayoutParams
                                .WRAP_CONTENT);
        cardLp.bottomMargin = dp(8);
        card.setLayoutParams(cardLp);
        android.widget.LinearLayout top =
                new android.widget.LinearLayout(app);
        top.setOrientation(
                android.widget.LinearLayout.HORIZONTAL);
        top.setGravity(Gravity.CENTER_VERTICAL);
        android.widget.ImageView icon =
                new android.widget.ImageView(app);
        if (d.largeIcon != null) {
            icon.setImageBitmap(d.largeIcon);
            icon.setScaleType(
                    android.widget.ImageView.ScaleType
                            .CENTER_CROP);
        } else {
            android.graphics.drawable.Drawable dr =
                    lightAppIcon(lightDisplayPkg(d), d);
            if (dr != null) {
                icon.setImageDrawable(dr);
            }
        }
        icon.setLayoutParams(
                new android.widget.LinearLayout.LayoutParams(
                        dp(38), dp(38)));
        top.addView(icon);
        android.widget.LinearLayout mid =
                new android.widget.LinearLayout(app);
        mid.setOrientation(android.widget.LinearLayout.VERTICAL);
        android.widget.LinearLayout.LayoutParams midLp =
                new android.widget.LinearLayout.LayoutParams(
                        0, android.widget.LinearLayout.LayoutParams
                                .WRAP_CONTENT, 1f);
        midLp.leftMargin = dp(10);
        mid.setLayoutParams(midLp);
        android.widget.TextView title =
                new android.widget.TextView(app);
        title.setText(d.title != null ? d.title : "");
        title.setTextSize(13.5f);
        title.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        title.setTextColor(lpTitleC());
        title.setSingleLine(true);
        // v590: 下载卡不走跑马灯——进度文字一滚动起来
        // 眼睛就追着字跑，录屏里像一直在闪（wei 第二
        // 条录屏定位：闪的是下载卡不是呼吸灯）。稳稳
        // 定住，超长尾部省略。
        title.setEllipsize(
                android.text.TextUtils.TruncateAt.END);
        mid.addView(title, new android.widget.LinearLayout
                .LayoutParams(
                        android.widget.LinearLayout.LayoutParams
                                .MATCH_PARENT,
                        android.widget.LinearLayout.LayoutParams
                                .WRAP_CONTENT));
        android.widget.TextView text =
                new android.widget.TextView(app);
        text.setText(d.text != null ? d.text : "");
        text.setTextSize(11.5f);
        text.setTextColor(lpTextC());
        text.setSingleLine(true);
        // v590: 状态行同标题——定住不滚（见上）。
        text.setEllipsize(
                android.text.TextUtils.TruncateAt.END);
        mid.addView(text, new android.widget.LinearLayout
                .LayoutParams(
                        android.widget.LinearLayout.LayoutParams
                                .MATCH_PARENT,
                        android.widget.LinearLayout.LayoutParams
                                .WRAP_CONTENT));
        top.addView(mid);
        android.widget.TextView pct =
                new android.widget.TextView(app);
        pct.setText(d.downloadPct >= 0
                ? d.downloadPct + "%" : "");
        pct.setTextSize(11.5f);
        pct.setTextColor(lpTextC());
        top.addView(pct);
        card.addView(top);
        android.widget.ProgressBar bar =
                new android.widget.ProgressBar(app, null,
                        android.R.attr.progressBarStyleHorizontal);
        bar.setMax(100);
        bar.setProgress(Math.max(d.downloadPct, 0));
        android.widget.LinearLayout.LayoutParams barLp =
                new android.widget.LinearLayout.LayoutParams(
                        android.widget.LinearLayout.LayoutParams
                                .MATCH_PARENT, dp(3));
        barLp.topMargin = dp(7);
        bar.setLayoutParams(barLp);
        card.addView(bar);
        if (d.key != null) {
            lightDlBars.put(d.key, bar);
            lightDlTitles.put(d.key, title);
            lightDlTexts.put(d.key, text);
            lightDlPcts.put(d.key, pct);
        }
        card.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                dismissLightPanel();
                lightFireOpen(d);
            }
        });
        return card;
    }

    // v591: 出示页（取件/取餐码全屏大码）的窗口引用。
    private android.widget.FrameLayout lightStageRoot;

    /** v591: 码的种类色——验证码蓝、取件绿、取餐橙
     * （预览定稿）。 */
    private static int lightCodeAccent(NotifData d) {
        if (d != null && d.isPickupCode) {
            if ("取餐码".equals(d.codeLabel)) {
                return Color.rgb(234, 88, 12);
            }
            return Color.rgb(22, 163, 74);
        }
        return Color.rgb(59, 130, 246);
    }

    private static String lightCodeKind(NotifData d) {
        if (d != null && d.codeLabel != null
                && !d.codeLabel.isEmpty()) {
            return d.codeLabel;
        }
        return d != null && d.isPickupCode ? "取件码" : "验证码";
    }

    /** v591: 打码时码的展示形：等长圆点。 */
    private static String lightCodeMasked(String code) {
        if (code == null || code.isEmpty()) {
            return "••••••";
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < code.length(); i++) {
            if (i > 0) {
                sb.append(' ');
            }
            sb.append('•');
        }
        return sb.toString();
    }

    /** v591: 手动复制（卡上按钮/出示页）——显式动作，
     * 不过自动复制开关；打码时 Toast 不露码，剪贴板
     * 里永远是真码（经典同口径）。 */
    private void copyCodeManual(String code) {
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
                android.widget.Toast.makeText(app,
                        PrivacyMask.shouldMask(app)
                                ? "已复制" : "验证码 " + code
                                        + " 已复制",
                        android.widget.Toast.LENGTH_SHORT).show();
            }
        } catch (Throwable ignored) {
        }
    }

    /** v591: 码卡片——音乐卡之下、普通行之上的置顶卡。
     * v592: 按预览定稿重排（wei 对照截图指出不一样）：
     * 卡面带种类色描边、头部「应用名 · 种类」+ 右侧
     * 时间、绿色状态药丸一行、码居中特大、原文一行、
     * 底部双大按钮（主按钮实心种类色 + 看原文描边）。
     * 点卡身同点行（打开并进时光机）；长按出内嵌菜
     * 单；场景只读时动作全灭、只看。v593 起带倒计
     * 时：原文写明有效期（parseCodeValidityMs）才显
     * 示，按到达时间 + 有效期真走秒，认不出不显示。 */
    private View buildLightCodeCard(final NotifData d) {
        final boolean masked = PrivacyMask.shouldMask(app);
        final int accent = lightCodeAccent(d);
        android.widget.LinearLayout card =
                new android.widget.LinearLayout(app);
        card.setOrientation(
                android.widget.LinearLayout.VERTICAL);
        GradientDrawable bg = new GradientDrawable();
        if (lpWhite()) {
            bg.setColor(Color.rgb(248, 250, 252));
        } else {
            bg.setColor(Color.argb(242,
                    Color.red(accent) / 6 + 22,
                    Color.green(accent) / 6 + 26,
                    Color.blue(accent) / 6 + 40));
        }
        bg.setStroke(dp(1), Color.argb(190,
                Color.red(accent), Color.green(accent),
                Color.blue(accent)));
        bg.setCornerRadius(dp(14));
        card.setBackground(bg);
        card.setPadding(dp(14), dp(11), dp(14), dp(12));
        android.widget.LinearLayout.LayoutParams cardLp =
                new android.widget.LinearLayout.LayoutParams(
                        android.widget.LinearLayout.LayoutParams
                                .MATCH_PARENT,
                        android.widget.LinearLayout.LayoutParams
                                .WRAP_CONTENT);
        cardLp.bottomMargin = dp(8);
        card.setLayoutParams(cardLp);
        // 头部：图标 + 「应用名 · 种类」+ 右侧时间。
        android.widget.LinearLayout head =
                new android.widget.LinearLayout(app);
        head.setOrientation(
                android.widget.LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        android.widget.ImageView icon =
                new android.widget.ImageView(app);
        String dpkg = lightDisplayPkg(d);
        if (d.largeIcon != null) {
            icon.setImageBitmap(d.largeIcon);
            icon.setScaleType(
                    android.widget.ImageView.ScaleType
                            .CENTER_CROP);
        } else {
            android.graphics.drawable.Drawable dr =
                    lightAppIcon(dpkg, d);
            if (dr != null) {
                icon.setImageDrawable(dr);
            }
        }
        icon.setLayoutParams(
                new android.widget.LinearLayout.LayoutParams(
                        dp(28), dp(28)));
        head.addView(icon);
        android.widget.TextView who =
                new android.widget.TextView(app);
        who.setText(lightAppLabel(dpkg, d) + " · "
                + lightCodeKind(d));
        who.setTextSize(13);
        who.setTextColor(lpTitleC());
        who.setSingleLine(true);
        who.setEllipsize(
                android.text.TextUtils.TruncateAt.END);
        android.widget.LinearLayout.LayoutParams whoLp =
                new android.widget.LinearLayout.LayoutParams(
                        0, android.widget.LinearLayout.LayoutParams
                                .WRAP_CONTENT, 1f);
        whoLp.leftMargin = dp(9);
        who.setLayoutParams(whoLp);
        head.addView(who);
        android.widget.TextView ago =
                new android.widget.TextView(app);
        ago.setText(lightAgoText(d.postTime));
        ago.setTextSize(11);
        ago.setTextColor(lpAgoC());
        head.addView(ago);
        card.addView(head);
        // 状态药丸：验证码已自动复制 / 取件带柜号。
        String pillText = null;
        if (!d.isPickupCode && FeatureFlags.autoCopyCode) {
            pillText = "✓ 已自动复制，去粘贴就行";
        } else if (d.isPickupCode && d.cabinet != null
                && !d.cabinet.isEmpty()) {
            pillText = "柜号 · " + d.cabinet;
        }
        if (pillText != null) {
            android.widget.LinearLayout pillRow =
                    new android.widget.LinearLayout(app);
            android.widget.LinearLayout.LayoutParams prLp =
                    new android.widget.LinearLayout.LayoutParams(
                            android.widget.LinearLayout.LayoutParams
                                    .MATCH_PARENT,
                            android.widget.LinearLayout.LayoutParams
                                    .WRAP_CONTENT);
            prLp.topMargin = dp(9);
            pillRow.setLayoutParams(prLp);
            android.widget.TextView pill =
                    new android.widget.TextView(app);
            pill.setText(pillText);
            pill.setTextSize(11.5f);
            pill.setTextColor(Color.rgb(34, 197, 94));
            GradientDrawable pillBg = new GradientDrawable();
            pillBg.setColor(Color.argb(38, 34, 197, 94));
            pillBg.setCornerRadius(dp(999));
            pill.setBackground(pillBg);
            pill.setPadding(dp(10), dp(4), dp(10), dp(4));
            pillRow.addView(pill);
            card.addView(pillRow);
        }
        // 码：居中特大（预览的视觉主角）。
        android.widget.TextView code =
                new android.widget.TextView(app);
        code.setText(masked ? lightCodeMasked(d.code) : d.code);
        code.setTextSize(40);
        code.setTypeface(code.getTypeface(),
                android.graphics.Typeface.BOLD);
        code.setLetterSpacing(0.2f);
        code.setTextColor(lpTitleC());
        code.setGravity(Gravity.CENTER);
        code.setSingleLine(true);
        android.widget.LinearLayout.LayoutParams cdLp =
                new android.widget.LinearLayout.LayoutParams(
                        android.widget.LinearLayout.LayoutParams
                                .MATCH_PARENT,
                        android.widget.LinearLayout.LayoutParams
                                .WRAP_CONTENT);
        cdLp.topMargin = dp(10);
        code.setLayoutParams(cdLp);
        card.addView(code);
        // 原文一行（真实通知正文，居中、超长省略）。
        if (d.text != null && !d.text.isEmpty()) {
            android.widget.TextView ctx =
                    new android.widget.TextView(app);
            ctx.setText(d.text);
            ctx.setTextSize(11.5f);
            ctx.setTextColor(lpTextC());
            ctx.setGravity(Gravity.CENTER);
            ctx.setSingleLine(true);
            ctx.setEllipsize(
                    android.text.TextUtils.TruncateAt.END);
            android.widget.LinearLayout.LayoutParams cxLp =
                    new android.widget.LinearLayout.LayoutParams(
                            android.widget.LinearLayout.LayoutParams
                                    .MATCH_PARENT,
                            android.widget.LinearLayout.LayoutParams
                                    .WRAP_CONTENT);
            cxLp.topMargin = dp(6);
            ctx.setLayoutParams(cxLp);
            card.addView(ctx);
        }
        // v593: 倒计时（原文写明有效期才有）——「还剩
        // m:ss 过期」+ 一条细进度条，面板开时每秒刷。
        long validityMs = parseCodeValidityMs(d.title, d.text);
        if (validityMs > 0 && d.key != null) {
            android.widget.TextView cd =
                    new android.widget.TextView(app);
            cd.setTextSize(11.5f);
            cd.setTextColor(lpTextC());
            cd.setGravity(Gravity.CENTER);
            android.widget.LinearLayout.LayoutParams cdLp2 =
                    new android.widget.LinearLayout.LayoutParams(
                            android.widget.LinearLayout.LayoutParams
                                    .MATCH_PARENT,
                            android.widget.LinearLayout.LayoutParams
                                    .WRAP_CONTENT);
            cdLp2.topMargin = dp(6);
            cd.setLayoutParams(cdLp2);
            card.addView(cd);
            android.widget.LinearLayout track =
                    new android.widget.LinearLayout(app);
            track.setOrientation(
                    android.widget.LinearLayout.HORIZONTAL);
            GradientDrawable trackBg = new GradientDrawable();
            trackBg.setColor(lpWhite()
                    ? Color.rgb(226, 232, 240)
                    : Color.argb(50, 255, 255, 255));
            trackBg.setCornerRadius(dp(999));
            track.setBackground(trackBg);
            android.widget.LinearLayout.LayoutParams trLp =
                    new android.widget.LinearLayout.LayoutParams(
                            android.widget.LinearLayout.LayoutParams
                                    .MATCH_PARENT, dp(3));
            trLp.topMargin = dp(5);
            track.setLayoutParams(trLp);
            View fill = new View(app);
            GradientDrawable fillBg = new GradientDrawable();
            fillBg.setColor(accent);
            fillBg.setCornerRadius(dp(999));
            fill.setBackground(fillBg);
            track.addView(fill, new android.widget.LinearLayout
                    .LayoutParams(0,
                            android.widget.LinearLayout.LayoutParams
                                    .MATCH_PARENT, 1f));
            View space = new View(app);
            track.addView(space, new android.widget.LinearLayout
                    .LayoutParams(0,
                            android.widget.LinearLayout.LayoutParams
                                    .MATCH_PARENT, 0f));
            card.addView(track);
            lightCdTexts.put(d.key, cd);
            lightCdFills.put(d.key, fill);
            lightCdSpaces.put(d.key, space);
            lightCdDeadline.put(d.key,
                    d.postTime + validityMs);
            lightCdTotal.put(d.key, validityMs);
        }
        // 双大按钮：主按钮实心种类色，次按钮描边。
        android.widget.LinearLayout btns =
                new android.widget.LinearLayout(app);
        btns.setOrientation(
                android.widget.LinearLayout.HORIZONTAL);
        android.widget.LinearLayout.LayoutParams btLp =
                new android.widget.LinearLayout.LayoutParams(
                        android.widget.LinearLayout.LayoutParams
                                .MATCH_PARENT,
                        android.widget.LinearLayout.LayoutParams
                                .WRAP_CONTENT);
        btLp.topMargin = dp(12);
        btns.setLayoutParams(btLp);
        android.widget.TextView main =
                new android.widget.TextView(app);
        main.setText(d.isPickupCode ? "出示大码"
                : "复制" + lightCodeKind(d));
        main.setTextSize(14);
        main.setTextColor(Color.WHITE);
        main.setTypeface(main.getTypeface(),
                android.graphics.Typeface.BOLD);
        main.setGravity(Gravity.CENTER);
        GradientDrawable mainBg = new GradientDrawable();
        mainBg.setColor(accent);
        mainBg.setCornerRadius(dp(10));
        main.setBackground(mainBg);
        main.setPadding(dp(10), dp(11), dp(10), dp(11));
        btns.addView(main, new android.widget.LinearLayout
                .LayoutParams(0,
                        android.widget.LinearLayout.LayoutParams
                                .WRAP_CONTENT, 1f));
        android.widget.TextView orig =
                new android.widget.TextView(app);
        orig.setText("看原文");
        orig.setTextSize(14);
        orig.setTextColor(lpTitleC());
        orig.setGravity(Gravity.CENTER);
        GradientDrawable origBg = new GradientDrawable();
        origBg.setColor(Color.TRANSPARENT);
        origBg.setStroke(dp(1), lpWhite()
                ? Color.rgb(203, 213, 225)
                : Color.argb(120, 255, 255, 255));
        origBg.setCornerRadius(dp(10));
        orig.setBackground(origBg);
        orig.setPadding(dp(10), dp(11), dp(10), dp(11));
        android.widget.LinearLayout.LayoutParams orLp =
                new android.widget.LinearLayout.LayoutParams(
                        0, android.widget.LinearLayout.LayoutParams
                                .WRAP_CONTENT, 1f);
        orLp.leftMargin = dp(10);
        orig.setLayoutParams(orLp);
        btns.addView(orig);
        card.addView(btns);
        if (!lightPanelReadOnly) {
            main.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    if (d.isPickupCode) {
                        showLightCodeStage(d);
                    } else {
                        copyCodeManual(d.code);
                    }
                }
            });
            orig.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    lightOpenItem(d);
                }
            });
            card.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    lightOpenItem(d);
                }
            });
            card.setOnLongClickListener(
                    new View.OnLongClickListener() {
                        @Override
                        public boolean onLongClick(View v) {
                            toggleLightInlineMenu(card, d);
                            return true;
                        }
                    });
        }
        FileLog.i("Light code card pkg=" + d.pkg
                + " kind=" + lightCodeKind(d)
                + " masked=" + masked);
        return card;
    }

    /** v591: 出示页——全屏白页、大码居中、窗口亮度拉
     * 满，给柜机/店员看；点任意处关闭。打码时只显示
     * 圆点（复制键仍复制真码）。不画假条码：柜机真要
     * 扫码的场景，码本身是给人读的数字/字母。 */
    private void showLightCodeStage(final NotifData d) {
        if (app == null || wm == null || d == null
                || d.code == null) {
            return;
        }
        dismissLightCodeStage();
        final boolean masked = PrivacyMask.shouldMask(app);
        final int accent = lightCodeAccent(d);
        lightStageRoot = new android.widget.FrameLayout(app);
        lightStageRoot.setBackgroundColor(Color.WHITE);
        android.widget.LinearLayout col =
                new android.widget.LinearLayout(app);
        col.setOrientation(
                android.widget.LinearLayout.VERTICAL);
        col.setGravity(Gravity.CENTER_HORIZONTAL);
        android.widget.FrameLayout.LayoutParams colLp =
                new android.widget.FrameLayout.LayoutParams(
                        android.widget.FrameLayout.LayoutParams
                                .MATCH_PARENT,
                        android.widget.FrameLayout.LayoutParams
                                .WRAP_CONTENT);
        colLp.gravity = Gravity.CENTER;
        colLp.leftMargin = dp(28);
        colLp.rightMargin = dp(28);
        col.setLayoutParams(colLp);
        android.widget.TextView who =
                new android.widget.TextView(app);
        who.setText(lightAppLabel(lightDisplayPkg(d), d)
                + " · " + lightCodeKind(d));
        who.setTextSize(15);
        who.setTextColor(Color.rgb(100, 116, 139));
        who.setGravity(Gravity.CENTER);
        col.addView(who);
        android.widget.TextView big =
                new android.widget.TextView(app);
        big.setText(masked ? lightCodeMasked(d.code) : d.code);
        big.setTextSize(52);
        big.setTypeface(android.graphics.Typeface.MONOSPACE,
                android.graphics.Typeface.BOLD);
        big.setLetterSpacing(0.08f);
        big.setTextColor(Color.rgb(15, 23, 42));
        big.setGravity(Gravity.CENTER);
        android.widget.LinearLayout.LayoutParams bigLp =
                new android.widget.LinearLayout.LayoutParams(
                        android.widget.LinearLayout.LayoutParams
                                .MATCH_PARENT,
                        android.widget.LinearLayout.LayoutParams
                                .WRAP_CONTENT);
        bigLp.topMargin = dp(18);
        big.setLayoutParams(bigLp);
        col.addView(big);
        if (d.cabinet != null && !d.cabinet.isEmpty()) {
            android.widget.TextView cab =
                    new android.widget.TextView(app);
            cab.setText("柜号 · " + d.cabinet);
            cab.setTextSize(17);
            cab.setTextColor(Color.rgb(51, 65, 85));
            cab.setGravity(Gravity.CENTER);
            android.widget.LinearLayout.LayoutParams cbLp =
                    new android.widget.LinearLayout.LayoutParams(
                            android.widget.LinearLayout.LayoutParams
                                    .MATCH_PARENT,
                            android.widget.LinearLayout.LayoutParams
                                    .WRAP_CONTENT);
            cbLp.topMargin = dp(10);
            cab.setLayoutParams(cbLp);
            col.addView(cab);
        }
        if (masked) {
            android.widget.TextView mh =
                    new android.widget.TextView(app);
            mh.setText("按隐私设置已打码 · 复制按钮仍可复制真码");
            mh.setTextSize(12);
            mh.setTextColor(Color.rgb(148, 163, 184));
            mh.setGravity(Gravity.CENTER);
            android.widget.LinearLayout.LayoutParams mhLp =
                    new android.widget.LinearLayout.LayoutParams(
                            android.widget.LinearLayout.LayoutParams
                                    .MATCH_PARENT,
                            android.widget.LinearLayout.LayoutParams
                                    .WRAP_CONTENT);
            mhLp.topMargin = dp(12);
            mh.setLayoutParams(mhLp);
            col.addView(mh);
        }
        android.widget.LinearLayout btns =
                new android.widget.LinearLayout(app);
        btns.setOrientation(
                android.widget.LinearLayout.HORIZONTAL);
        btns.setGravity(Gravity.CENTER);
        android.widget.LinearLayout.LayoutParams btLp =
                new android.widget.LinearLayout.LayoutParams(
                        android.widget.LinearLayout.LayoutParams
                                .MATCH_PARENT,
                        android.widget.LinearLayout.LayoutParams
                                .WRAP_CONTENT);
        btLp.topMargin = dp(30);
        btns.setLayoutParams(btLp);
        android.widget.TextView copy =
                new android.widget.TextView(app);
        copy.setText("复制");
        copy.setTextSize(14);
        copy.setTextColor(Color.WHITE);
        copy.setTypeface(copy.getTypeface(),
                android.graphics.Typeface.BOLD);
        copy.setGravity(Gravity.CENTER);
        GradientDrawable copyBg = new GradientDrawable();
        copyBg.setColor(accent);
        copyBg.setCornerRadius(dp(999));
        copy.setBackground(copyBg);
        copy.setPadding(dp(26), dp(10), dp(26), dp(10));
        copy.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                copyCodeManual(d.code);
            }
        });
        btns.addView(copy);
        android.widget.TextView close =
                new android.widget.TextView(app);
        close.setText("关闭");
        close.setTextSize(14);
        close.setTextColor(Color.rgb(51, 65, 85));
        close.setGravity(Gravity.CENTER);
        GradientDrawable closeBg = new GradientDrawable();
        closeBg.setColor(Color.rgb(241, 245, 249));
        closeBg.setCornerRadius(dp(999));
        close.setBackground(closeBg);
        close.setPadding(dp(26), dp(10), dp(26), dp(10));
        android.widget.LinearLayout.LayoutParams clLp =
                new android.widget.LinearLayout.LayoutParams(
                        android.widget.LinearLayout.LayoutParams
                                .WRAP_CONTENT,
                        android.widget.LinearLayout.LayoutParams
                                .WRAP_CONTENT);
        clLp.leftMargin = dp(14);
        close.setLayoutParams(clLp);
        close.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                dismissLightCodeStage();
            }
        });
        btns.addView(close);
        col.addView(btns);
        lightStageRoot.addView(col);
        lightStageRoot.setOnClickListener(
                new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        dismissLightCodeStage();
                    }
                });
        WindowManager.LayoutParams lp = new WindowManager
                .LayoutParams(
                        WindowManager.LayoutParams.MATCH_PARENT,
                        WindowManager.LayoutParams.MATCH_PARENT,
                        WindowManager.LayoutParams.TYPE_SYSTEM_ERROR,
                        WindowManager.LayoutParams
                                .FLAG_LAYOUT_IN_SCREEN
                                | WindowManager.LayoutParams
                                        .FLAG_LAYOUT_NO_LIMITS,
                        PixelFormat.TRANSLUCENT);
        lp.setTitle("flymebobo-lightstage");
        lp.screenBrightness = 1f;
        try {
            wm.addView(lightStageRoot, lp);
        } catch (Throwable t) {
            lightStageRoot = null;
            return;
        }
        FileLog.i("Light code stage pkg=" + d.pkg
                + " masked=" + masked);
    }

    private void dismissLightCodeStage() {
        if (lightStageRoot != null && wm != null) {
            try {
                wm.removeViewImmediate(lightStageRoot);
            } catch (Throwable ignored) {
            }
        }
        lightStageRoot = null;
    }

    /** v564: 从收纳栈摘一条（引用或同 key）。摘到才刷新。 */
    private boolean lightRemove(NotifData d) {
        if (d == null) {
            return false;
        }
        boolean removed = false;
        java.util.Iterator<NotifData> it = lightStack.iterator();
        while (it.hasNext()) {
            NotifData o = it.next();
            if (o == d || (o != null && d.key != null
                    && d.key.equals(o.key))) {
                it.remove();
                removed = true;
                break;
            }
        }
        if (removed) {
            FileLog.i("Light remove pkg=" + d.pkg
                    + " n=" + lightStack.size());
            refreshLightDot();
            if (lightPanelAdded && !lightPanelRecallMode) {
                rebuildLightPanelRows();
            }
            writeLightMirror();
        }
        return removed;
    }

    /** v564: 轻气泡版入回看——与经典 pushRecall 同栈同口径
     * （同 key 去重、上限 RECALL_MAX、armRecallTimer 统一
     * 过期），只是闸门换成 lightMode（经典回看胶囊开关关着
     * 时轻气泡的「时光机」照样工作，面板里读的是同一个
     * recallStack）。排除类型与 pushRecall 一致。 */
    private void lightPushRecall(NotifData d) {
        if (d == null || !FeatureFlags.lightMode || d.recalled) {
            return;
        }
        // v601: 进时光机的通知若成过信息卡，卡联动收
        // （提醒一并取消）。
        removeInfoCardForKey(d.key);
        if (d.isMusic || d.isDownload || d.isLive || d.isNavi
                || d.isBus || d.isCall) {
            return;
        }
        if (d.key != null) {
            java.util.Iterator<NotifData> it =
                    recallStack.iterator();
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
        FileLog.i("Light recall push pkg=" + d.pkg
                + " n=" + recallStack.size());
        armRecallTimer();
        refreshRecallHandle();
        if (lightPanelAdded && lightPanelRecallMode) {
            rebuildLightPanelRows();
        }
        writeLightMirror();
    }

    /** v564: 点行打开应用——先试通知自带的 contentIntent
     * （通知已被系统侧撤除时它会被回收、send 抛异常），
     * 失效就拉起该应用主界面兜底（试验田 v0.2 定版口径）。
     * v571: 打开方式是通用元素（wei 定）——不再裸 send /
     * 裸 startActivity，改走经典同款打开通道（openPending
     * Intent / openIntent），小窗还是全屏看通用设置
     * miniwindow（默认小窗）。 */
    private void lightFireOpen(NotifData d) {
        if (d == null) {
            return;
        }
        if (d.contentIntent != null) {
            boolean ok = false;
            try {
                ok = openPendingIntent(d.contentIntent, d.pkg,
                        FeatureFlags.miniwindow);
            } catch (Throwable ignored) {
            }
            if (ok) {
                FileLog.i("Light open pkg=" + d.pkg
                        + " via=pi mini="
                        + FeatureFlags.miniwindow);
                return;
            }
            FileLog.i("Light open FAIL pkg=" + d.pkg + " pi");
        }
        String pkg = lightDisplayPkg(d);
        try {
            android.content.Intent launch =
                    app.getPackageManager()
                            .getLaunchIntentForPackage(pkg);
            if (launch != null
                    && openIntent(launch,
                            FeatureFlags.miniwindow)) {
                FileLog.i("Light open pkg=" + d.pkg
                        + " via=launch mini="
                        + FeatureFlags.miniwindow);
                return;
            }
            FileLog.i("Light open FAIL pkg=" + d.pkg
                    + " launch");
        } catch (Throwable t) {
            FileLog.i("Light open FAIL pkg=" + d.pkg
                    + " launch: " + t);
        }
    }

    /** v575: 点开的就是正在闪的那条 → 立即停闪收回
     * （wei：新通知点开后立即收纳、不要再继续闪烁）。
     * 主药丸走 pulse 收尾，副药丸走 sub 收尾，各自会把
     * 小签/呼吸点按当前栈状态摆正。 */
    private void stopLightFlashFor(NotifData d) {
        if (d == null || d.key == null) {
            return;
        }
        if (d.key.equals(lightFlashKey)) {
            handler.removeCallbacks(lightPulseEndRunnable);
            lightPulseEndRunnable.run();
            FileLog.i("Light flash stop on open pkg=" + d.pkg);
        }
        if (d.key.equals(lightSubFlashKey)) {
            lightSubEndRunnable.run();
            FileLog.i("Light subflash stop on open pkg="
                    + d.pkg);
        }
    }

    /** v564: 面板点行（收纳视图）：出栈 → 进时光机 → 开应用。 */
    private void lightOpenItem(NotifData d) {
        if (d == null) {
            return;
        }
        stopLightFlashFor(d);
        lightRemove(d);
        lightPushRecall(d);
        dismissLightPanel();
        lightFireOpen(d);
    }

    /** v575: App 收纳页点行（广播按 key 转入）——与面板
     * 点行完全同路：出栈、进时光机、停闪、按打开方式开。 */
    public void lightOpenByKey(final String key) {
        handler.post(new Runnable() {
            @Override
            public void run() {
                if (key == null) {
                    return;
                }
                for (NotifData d : lightStack) {
                    if (key.equals(d.key)) {
                        lightOpenItem(d);
                        return;
                    }
                }
                FileLog.i("Light openByKey miss");
            }
        });
    }

    /** v575: App 时光机页点行（广播按 key 转入）。 */
    public void lightRecallOpenByKey(final String key) {
        handler.post(new Runnable() {
            @Override
            public void run() {
                if (key == null) {
                    return;
                }
                for (NotifData d : recallStack) {
                    if (key.equals(d.key)) {
                        lightRecallOpen(d);
                        return;
                    }
                }
                FileLog.i("Light recallOpenByKey miss");
            }
        });
    }

    /** v564: 时光机点行：出回看栈 → 开应用（不再二次入栈）。 */
    private void lightRecallOpen(NotifData d) {
        if (d == null) {
            return;
        }
        recallStack.remove(d);
        FileLog.i("Light recall pop pkg=" + d.pkg
                + " left=" + recallStack.size());
        armRecallTimer();
        refreshRecallHandle();
        writeLightMirror();
        dismissLightPanel();
        lightFireOpen(d);
    }

    /** v564: 时光机左滑：彻底删除，不再回来。 */
    private void lightRecallDrop(NotifData d) {
        if (d == null) {
            return;
        }
        if (recallStack.remove(d)) {
            FileLog.i("Light recall drop pkg=" + d.pkg
                    + " left=" + recallStack.size());
            armRecallTimer();
            refreshRecallHandle();
            if (lightPanelAdded) {
                if (recallStack.isEmpty()
                        && lightPanelRecallMode) {
                    lightPanelRecallMode = false;
                }
                rebuildLightPanelRows();
            }
            writeLightMirror();
        }
    }

    /** v564: 全部清除——逐条进时光机（栈序），再清栈。 */
    private void lightClearAll() {
        if (lightStack.isEmpty()) {
            return;
        }
        java.util.ArrayList<NotifData> copy =
                new java.util.ArrayList<>(lightStack);
        lightStack.clear();
        for (NotifData d : copy) {
            lightPushRecall(d);
        }
        FileLog.i("Light clearAll -> recall n="
                + recallStack.size());
        refreshLightDot();
        if (lightPanelAdded) {
            rebuildLightPanelRows();
        }
        writeLightMirror();
    }

    /** v564: 模式开关翻动（XposedEntry 转发）——开：小签按
     * 当前栈/音乐状态刷新；在屏的经典胶囊不强拆（几秒内
     * 自然消散，新通知从此走收纳）。关：面板与小签立即收，
     * 音乐状态清掉（音乐通知回到经典路径）；收纳栈保留，
     * 下次开启接着用。 */
    public void onLightModeChanged(final boolean on) {
        handler.post(new Runnable() {
            @Override
            public void run() {
                FileLog.i("Light mode changed -> " + on);
                if (on) {
                    refreshLightDot();
                    // v565: 开轻气泡的瞬间，经典回看边缘
                    // 签若正挂着要立即摘掉。
                    refreshRecallHandle();
                    // v572: 刚开轻气泡先把镜像刷一份，
                    // App 三栏打开就有现成内容。
                    writeLightMirror();
                } else {
                    dismissLightPanel();
                    hideLightDot();
                    String lightGoneKey = lightMusicData != null
                            ? lightMusicData.key : null;
                    lightMusicData = null;
                    // v576: 下载表也清掉，切回轻气泡不复活
                    // 旧下载卡（进行中的下载会重新到达）。
                    lightDownloads.clear();
                    // v611: 摘要待闪一并清掉，别漏到经典去。
                    lightAiFlashText = null;
                    lightAiPillShowing = false;
                    lightMusicArt = null;
                    lightMusicPlaying = false;
                    lightMusicFavorite = false;
                    lightMusicHasFavorite = false;
                    if (lightLyricFollow) {
                        lightLyricFollow = false;
                        FileLog.i("Light lyric off");
                        sendLightLyricListen(false,
                                lightGoneKey);
                    }
                }
            }
        });
    }
    /** v564: 小签显隐总闸（试验田定版口径）：轻气泡关 /
     * 栈空且无音乐 → 全隐藏；灭屏 → 隐藏；其余出签并
     * 呼吸。v565: 锁屏不再挡——小签与面板窗口都是
     * TYPE_SYSTEM_ERROR，与经典胶囊同在锁屏之上，锁屏上
     * 也要能显示/打开（音乐卡控制、面板查看都可用；点行
     * 打开应用在锁屏上按系统规矩走解锁流程）。屏状态
     * 变化由 setScreenOn / 解锁广播顺带调。 */
    public void refreshLightDot() {
        if (app == null || wm == null) {
            return;
        }
        if (!FeatureFlags.lightMode) {
            hideLightDot();
            if (lightPanelAdded) {
                dismissLightPanel();
            }
            return;
        }
        // v601: 信息卡懒加载（重启后恢复一次）。
        loadLightInfoCards();
        // v599: 先清扫僵尸下载卡，下面的显隐判断用清
        // 扫后的表（卡退休了小签该藏就藏）；面板正开
        // 着时顺手把行重建了。
        if (sweepStaleLightDownloads() && lightPanelAdded
                && !lightPanelRecallMode) {
            rebuildLightPanelRows();
        }
        // v597: 常亮（wei 定正：常亮 = 一直显示，不是
        // 不呼吸）开着时，栈空也不隐藏小签——照常呼吸、
        // 照常发光，只是永远在。
        if (!FeatureFlags.lightDotSteady
                && lightStack.isEmpty() && lightMusicData == null
                && lightDownloads.isEmpty()
                && lightInfoCards.isEmpty()) {
            hideLightDot();
            if (lightPanelAdded) {
                dismissLightPanel();
            }
            return;
        }
        if (!isScreenInteractive()) {
            hideLightDot();
            if (lightPanelAdded) {
                dismissLightPanel();
            }
            return;
        }
        ensureLightDot();
        if (!lightDotAdded && lightDotRoot != null) {
            try {
                wm.addView(lightDotRoot, lightDotParams);
                lightDotAdded = true;
            } catch (Throwable ignored) {
            }
        }
        applyLightDotPos();
        updateLightBadge();
        startLightBreathing();
        syncLightGlow();
        maybeRequestAi();
    }

    /** v600: 收纳栈签名——栈里普通条目（带码的除外）
     * 的 key+时间拼串哈希，栈一变签名就变。 */
    private String lightAiSignature() {
        StringBuilder sb = new StringBuilder();
        for (NotifData d : lightStack) {
            if (d.code != null || d.key == null) {
                continue;
            }
            sb.append(d.key).append('@')
                    .append(d.postTime).append(';');
        }
        return Integer.toHexString(sb.toString().hashCode())
                + ":" + lightStack.size();
    }

    /** v600: AI 请求节流口——refreshLightDot 是收纳栈
     * 变动的汇合点，这里统一判断：AI 开着、栈里有可
     * 分析条目、签名没请求过，才排一次防抖（4 秒，
     * v602 由 12 秒缩短；连续进出栈只发最后一发）。
     * 验证码条目在组包时剔除，永不外送（wei 定红
     * 线）。 */
    private void maybeRequestAi() {
        if (!FeatureFlags.lightMode || !FeatureFlags.aiEnabled
                || (!FeatureFlags.aiSummary
                        && !FeatureFlags.aiTags)) {
            return;
        }
        boolean any = false;
        for (NotifData d : lightStack) {
            if (d.code == null) {
                any = true;
                break;
            }
        }
        if (!any) {
            return;
        }
        final String sig = lightAiSignature();
        if (sig.equals(lightAiReqSig)) {
            return;
        }
        if (lightAiDebounce != null) {
            handler.removeCallbacks(lightAiDebounce);
        }
        lightAiDebounce = new Runnable() {
            @Override
            public void run() {
                sendAiRequest(sig);
            }
        };
        // v602: 防抖 12 秒 -> 4 秒（wei 嫌打标慢）；连
        // 续进出栈仍只发最后一发，成本可控。
        // v614: 再缩到 1.2 秒（wei 仍嫌慢）——打标已
        // 走小模型，调用便宜，响应优先。
        handler.postDelayed(lightAiDebounce, 1200);
    }

    private void sendAiRequest(String sig) {
        if (app == null || !sig.equals(lightAiSignature())) {
            return; // 栈又变了，等下一轮防抖
        }
        StringBuilder blob = new StringBuilder();
        int n = 0;
        for (NotifData d : lightStack) {
            if (d.code != null || d.key == null) {
                continue;
            }
            String t = d.title == null ? "" : d.title;
            // v602: AI 也看全文（fullText），摘要/打标
            // 不再被短正文截断误导。
            String x = d.fullText() == null ? "" : d.fullText();
            if ((t + x).contains("验证码")
                    || (t + x).contains("校验码")) {
                continue;
            }
            if (x.length() > 160) {
                x = x.substring(0, 160);
            }
            blob.append(d.key).append("\u0001")
                    .append(t.replace("\u0001", " ")
                            .replace("\n", " "))
                    .append("\u0001")
                    .append(x.replace("\u0001", " ")
                            .replace("\n", " "))
                    .append("\n");
            if (++n >= 20) {
                break;
            }
        }
        if (blob.length() == 0) {
            return;
        }
        lightAiReqSig = sig;
        android.content.Intent intent =
                new android.content.Intent(
                        NotificationService.ACTION_AI_ANALYZE);
        intent.putExtra("sig", sig);
        intent.putExtra("blob", blob.toString());
        intent.putExtra("summary", FeatureFlags.aiSummary);
        intent.putExtra("tags", FeatureFlags.aiTags);
        try {
            app.sendBroadcast(intent);
        } catch (Throwable ignored) {
        }
        FileLog.i("Light ai request sig=" + sig + " n=" + n);
    }

    /** v600: AI 结果回填（XposedEntry 收到广播后调）：
     * 签名对得上当前栈才收；标签只留栈里还存在的
     * key。面板开着就重建行。 */
    public void onAiResult(String sig, String summary,
            String tagsBlob) {
        if (sig == null || !sig.equals(lightAiReqSig)) {
            FileLog.i("Light ai result stale sig=" + sig);
            return;
        }
        String prevSummary = lightAiSummary;
        if (summary != null && !summary.isEmpty()) {
            lightAiSummary = summary;
        }
        // v611: 新摘要就绪 → 边缘药丸循环闪（wei 定：
        // 一直闪到打开面板；放歌跟词时先挂着不闪）。
        // 与上一份摘要相同就不重复打扰。
        if (lightAiSummary != null
                && !lightAiSummary.equals(prevSummary)
                && FeatureFlags.aiSummary
                && !lightPanelAdded) {
            lightAiFlashText = lightAiSummary;
            maybeFlashLightSummary();
            FileLog.i("Light ai summary flash start");
        }
        if (tagsBlob != null && !tagsBlob.isEmpty()) {
            java.util.HashSet<String> live =
                    new java.util.HashSet<>();
            for (NotifData d : lightStack) {
                if (d.key != null) {
                    live.add(d.key);
                }
            }
            for (String line : tagsBlob.split("\n")) {
                String[] parts = line.split("\u0001", -1);
                if (parts.length == 2
                        && live.contains(parts[0])) {
                    lightAiTags.put(parts[0], parts[1]);
                }
            }
        }
        FileLog.i("Light ai applied tags="
                + lightAiTags.size());
        if (lightPanelAdded && !lightPanelRecallMode) {
            rebuildLightPanelRows();
        }
    }

    /** v600: AI 开关翻动（XposedEntry 调）：总开关关
     * 掉就清空摘要与标签并重建面板；打开则把签名清
     * 掉，让下一轮 refresh 重新请求。 */
    public void onAiSettingsChanged() {
        handler.post(new Runnable() {
            @Override
            public void run() {
                if (!FeatureFlags.aiEnabled) {
                    lightAiSummary = null;
                    lightAiTags.clear();
                    stopLightSummaryFlash();
                }
                lightAiReqSig = null;
                if (lightPanelAdded && !lightPanelRecallMode) {
                    rebuildLightPanelRows();
                }
                maybeRequestAi();
            }
        });
    }

    /** v601: 信息卡片生成口——收纳到一条普通通知时
     * 本地解析（InfoParse），认出票/单号/账单就成卡：
     * 记映射（行渲染时跳过它）、落盘、带时间的票卡给
     * App 进程排自动提醒。本模块自己的通知（提醒本
     * 身）不解析，防自产自销。 */
    private void maybeMakeInfoCard(NotifData d) {
        if (!FeatureFlags.infoCards || d.code != null
                || d.key == null
                || "com.chenan.flymebobo".equals(d.pkg)) {
            return;
        }
        // v602: 解析看全文（fullText）——短正文会被应
        // 用截断（实测顺丰单号短正文只到第 11 位数）。
        InfoParse.Card ic = InfoParse.tryParse(d.pkg,
                d.title, d.fullText(), d.postTime);
        if (ic == null) {
            // 同 key 重投后内容变了、不再是信息卡：把旧
            // 映射摘掉，行恢复普通显示。
            if (lightInfoKeyToId.containsKey(d.key)) {
                removeInfoCardForKey(d.key);
            }
            // v605: 本地没认出 → AI 兜底（开了 AI 才排队）。
            queueInfoAiCheck(d);
            return;
        }
        // v606: 退票/取消短信（wei 定要区分同一张
        // 票）——撤掉对应票卡、提醒一并取消，不另
        // 出卡。只认强信号词，购票短信里的「如需退
        // 票」不误伤。
        String full = d.fullText() == null
                ? "" : d.fullText();
        if (("train".equals(ic.kind)
                || "flight".equals(ic.kind))
                && (full.contains("退票成功")
                        || full.contains("已退票")
                        || full.contains("退款成功")
                        || full.contains("已取消")
                        || full.contains("取消成功"))) {
            InfoParse.Card tgt = lightInfoCards.get(ic.id);
            if (tgt == null && ic.fp != null) {
                for (InfoParse.Card e
                        : lightInfoCards.values()) {
                    if (ic.fp.equals(e.fp)) {
                        tgt = e;
                        break;
                    }
                }
            }
            if (tgt != null) {
                lightInfoCards.remove(tgt.id);
                if (tgt.eventTime > 0) {
                    sendInfoRemind(false, tgt);
                }
                saveLightInfoCards();
                FileLog.i("Light info cancel kind="
                        + ic.kind + " id=" + tgt.id);
            }
            return;
        }
        // v606: 同票合并——指纹相同 = 同一张票，新短
        // 信更新原卡（改签换时间/座位也并进来、提醒
        // 跟着重排），不再出一张重复卡。
        if (!lightInfoCards.containsKey(ic.id)
                && ic.fp != null) {
            for (InfoParse.Card e : lightInfoCards.values()) {
                if (!ic.fp.equals(e.fp)) {
                    continue;
                }
                long oldEv = e.eventTime;
                boolean evChanged = oldEv != ic.eventTime;
                if (evChanged && oldEv > 0
                        && FeatureFlags.infoRemind) {
                    sendInfoRemind(false, e);
                }
                e.title = ic.title;
                e.sub = ic.sub;
                e.extra = ic.extra;
                e.copyText = ic.copyText;
                e.eventTime = ic.eventTime;
                e.ai = false;
                String prevId2 = lightInfoKeyToId.put(
                        d.key, e.id);
                if (prevId2 != null
                        && !prevId2.equals(e.id)
                        && !lightInfoKeyToId.containsValue(
                                prevId2)) {
                    lightInfoCards.remove(prevId2);
                }
                FileLog.i("Light info merge fp=" + ic.fp
                        + " into=" + e.id);
                saveLightInfoCards();
                if (evChanged && e.eventTime > 0
                        && FeatureFlags.infoRemind) {
                    sendInfoRemind(true, e);
                }
                queueInfoAiCheck(d);
                return;
            }
        }
        String prevId = lightInfoKeyToId.put(d.key, ic.id);
        if (prevId != null && !prevId.equals(ic.id)
                && !lightInfoKeyToId.containsValue(prevId)) {
            lightInfoCards.remove(prevId);
        }
        boolean isNew = !lightInfoCards.containsKey(ic.id);
        lightInfoCards.put(ic.id, ic);
        FileLog.i("Light info card kind=" + ic.kind
                + " new=" + isNew);
        saveLightInfoCards();
        if (isNew && ic.eventTime > 0
                && FeatureFlags.infoRemind) {
            sendInfoRemind(true, ic);
        }
        // v605: 本地认出了 → AI 同时核对（补时间/验
        // 种类，开了 AI 才排队）。
        queueInfoAiCheck(d);
    }

    /** v605: 信息卡 AI 兜底+核对排队（wei 定：开了
     * AI 就启用，本地+AI 同时核对）。红线同 AI 组
     * 包：带码条目与含验证码字样的永不外送。3 秒防
     * 抖批发；同 key@postTime 只核对一次。 */
    private void queueInfoAiCheck(NotifData d) {
        if (!FeatureFlags.aiEnabled
                || !FeatureFlags.infoCards
                || d == null || d.key == null
                || d.code != null) {
            return;
        }
        String sig = d.key + "@" + d.postTime;
        if (lightInfoAiChecked.contains(sig)) {
            return;
        }
        String hay = (d.title == null ? "" : d.title)
                + (d.fullText() == null ? "" : d.fullText());
        if (hay.contains("验证码") || hay.contains("校验码")) {
            return;
        }
        lightInfoAiPending.put(d.key, d);
        if (lightInfoAiDebounce != null) {
            handler.removeCallbacks(lightInfoAiDebounce);
        }
        lightInfoAiDebounce = new Runnable() {
            @Override
            public void run() {
                sendInfoAiRequest();
            }
        };
        handler.postDelayed(lightInfoAiDebounce, 3000);
    }

    private void sendInfoAiRequest() {
        lightInfoAiDebounce = null;
        if (lightInfoAiPending.isEmpty()) {
            return;
        }
        if (lightInfoAiChecked.size() > 300) {
            lightInfoAiChecked.clear();
        }
        StringBuilder blob = new StringBuilder();
        int n = 0;
        for (NotifData d : lightInfoAiPending.values()) {
            if (n >= 8) {
                break;
            }
            String id = lightInfoKeyToId.get(d.key);
            InfoParse.Card cur = id != null
                    ? lightInfoCards.get(id) : null;
            String t = d.title == null ? "" : d.title;
            String x = d.fullText() == null
                    ? "" : d.fullText();
            if (x.length() > 220) {
                x = x.substring(0, 220);
            }
            blob.append(d.key).append('\u0001')
                    .append(cur != null ? cur.kind : "")
                    .append('\u0001')
                    .append(t).append('\u0001')
                    .append(x).append('\n');
            lightInfoAiChecked.add(d.key + "@" + d.postTime);
            n++;
        }
        lightInfoAiPending.clear();
        if (n == 0) {
            return;
        }
        android.content.Intent intent =
                new android.content.Intent(
                        NotificationService.ACTION_AI_ANALYZE);
        intent.putExtra("infoBlob", blob.toString());
        try {
            app.sendBroadcast(intent);
        } catch (Throwable ignored) {
        }
        FileLog.i("Light info ai request n=" + n);
    }

    /** v605: AI 信息核对结果回递（XposedEntry 调）。
     * 每行：key\u0001kind\u0001title\u0001sub\u0001eventMillis
     * \u0001copyText。处置口径：本地没认出而 AI 认
     * 出 → 补卡（标 AI 识别）；本地已认出 → AI 只补
     * 本地缺的事件时间（提醒跟着排），种类对不上只
     * 记日志不翻本地的卡（本地规则对已知格式是确
     * 定性的，AI 会看错）。 */
    public void onAiInfoResult(String blob) {
        if (blob == null || blob.isEmpty()) {
            return;
        }
        boolean changed = false;
        for (String line : blob.split("\n")) {
            String[] p = line.split("\u0001", -1);
            if (p.length < 6) {
                continue;
            }
            String key = p[0];
            String kind = p[1];
            long ev = 0;
            try {
                ev = Long.parseLong(p[4]);
            } catch (Throwable ignored) {
            }
            NotifData d = null;
            for (NotifData e : lightStack) {
                if (key.equals(e.key)) {
                    d = e;
                    break;
                }
            }
            if (d == null) {
                continue;
            }
            String id = lightInfoKeyToId.get(key);
            InfoParse.Card cur = id != null
                    ? lightInfoCards.get(id) : null;
            if ("none".equals(kind)) {
                if (cur != null) {
                    FileLog.i("Light info ai disagree(none)"
                            + " local=" + cur.kind
                            + " key=" + key);
                }
                continue;
            }
            if (!"train".equals(kind) && !"flight".equals(kind)
                    && !"courier".equals(kind)
                    && !"bill".equals(kind)) {
                continue;
            }
            if (cur == null) {
                InfoParse.Card ic = new InfoParse.Card();
                ic.kind = kind;
                ic.ai = true;
                ic.title = p[2].isEmpty() ? kind : p[2];
                ic.sub = p[3];
                ic.extra = "";
                ic.source = d.pkg;
                ic.copyText = p[5].isEmpty() ? null : p[5];
                ic.eventTime = ev;
                ic.createdAt = d.postTime;
                ic.id = "ai:" + kind + ":"
                        + (ic.copyText != null
                                ? ic.copyText : ic.title)
                        + "|" + ev;
                ic.fp = "ai|" + kind + "|" + ic.title
                        + "|" + ev;
                lightInfoCards.put(ic.id, ic);
                lightInfoKeyToId.put(key, ic.id);
                FileLog.i("Light info ai card kind=" + kind
                        + " key=" + key);
                if (ev > 0 && FeatureFlags.infoRemind
                        && ("train".equals(kind)
                                || "flight".equals(kind))) {
                    sendInfoRemind(true, ic);
                }
                changed = true;
            } else if (!kind.equals(cur.kind)) {
                FileLog.i("Light info ai disagree kind"
                        + " local=" + cur.kind + " ai=" + kind
                        + " key=" + key);
            } else if (cur.eventTime <= 0 && ev > 0) {
                cur.eventTime = ev;
                FileLog.i("Light info ai fill time kind="
                        + kind + " key=" + key);
                if (FeatureFlags.infoRemind
                        && ("train".equals(kind)
                                || "flight".equals(kind))) {
                    sendInfoRemind(true, cur);
                }
                changed = true;
            } else {
                FileLog.i("Light info ai verified kind="
                        + kind + " key=" + key);
            }
        }
        if (changed) {
            saveLightInfoCards();
            if (lightPanelAdded && !lightPanelRecallMode) {
                rebuildLightPanelRows();
            }
            refreshLightDot();
        }
    }

    /** v601: 通知离开收纳（打开/左滑/溢出/清除都经
     * lightPushRecall，或源通知被移除）时联动收卡：
     * 同一张卡还有别的通知 key 映射着就不删卡。 */
    private void removeInfoCardForKey(String key) {
        if (key == null) {
            return;
        }
        String id = lightInfoKeyToId.remove(key);
        if (id == null
                || lightInfoKeyToId.containsValue(id)) {
            return;
        }
        InfoParse.Card ic = lightInfoCards.remove(id);
        if (ic != null) {
            saveLightInfoCards();
            if (ic.eventTime > 0) {
                sendInfoRemind(false, ic);
            }
        }
    }

    /** v601: 提醒排期/取消广播给 App 进程（NS 转
     * InfoRemindReceiver 排精确闹钟）。 */
    private void sendInfoRemind(boolean schedule,
            InfoParse.Card ic) {
        if (app == null || ic == null) {
            return;
        }
        android.content.Intent intent =
                new android.content.Intent(schedule
                        ? NotificationService.ACTION_INFO_REMIND
                        : NotificationService
                                .ACTION_INFO_REMIND_CANCEL);
        intent.putExtra("id", ic.id);
        intent.putExtra("title", ic.title);
        intent.putExtra("sub", ic.sub);
        intent.putExtra("kind", ic.kind);
        intent.putExtra("event", ic.eventTime);
        try {
            app.sendBroadcast(intent);
        } catch (Throwable ignored) {
        }
    }

    /** v601: 信息卡开关翻动（XposedEntry 调）：关掉
     * 就清掉全部信息卡（提醒一并取消）并重建面板。 */
    public void onInfoCardsChanged() {
        handler.post(new Runnable() {
            @Override
            public void run() {
                if (!FeatureFlags.infoCards) {
                    for (InfoParse.Card ic
                            : lightInfoCards.values()) {
                        if (ic.eventTime > 0) {
                            sendInfoRemind(false, ic);
                        }
                    }
                    lightInfoCards.clear();
                    lightInfoKeyToId.clear();
                    saveLightInfoCards();
                }
                if (lightPanelAdded && !lightPanelRecallMode) {
                    rebuildLightPanelRows();
                }
                refreshLightDot();
            }
        });
    }

    private static final String INFO_CARDS_PATH =
            "/sdcard/flymebobo/info_cards.txt";

    /** v601: 信息卡落盘（镜像同款格式：每行一条、制
     * 表符分隔、字段 URL 编码）。 */
    private void saveLightInfoCards() {
        try {
            StringBuilder sb = new StringBuilder();
            for (InfoParse.Card ic : lightInfoCards.values()) {
                String srcKey = "";
                for (java.util.Map.Entry<String, String> e
                        : lightInfoKeyToId.entrySet()) {
                    if (e.getValue().equals(ic.id)) {
                        srcKey = e.getKey();
                        break;
                    }
                }
                sb.append("I\t")
                        .append(mirrorField(ic.id)).append('\t')
                        .append(mirrorField(ic.kind)).append('\t')
                        .append(mirrorField(ic.title)).append('\t')
                        .append(mirrorField(ic.sub)).append('\t')
                        .append(mirrorField(ic.extra)).append('\t')
                        .append(mirrorField(ic.copyText))
                        .append('\t')
                        .append(ic.eventTime).append('\t')
                        .append(mirrorField(srcKey)).append('\t')
                        .append(ic.createdAt).append('\t')
                        .append(ic.ai ? "1" : "").append('\t')
                        .append(mirrorField(ic.fp))
                        .append('\n');
            }
            java.io.File f = new java.io.File(INFO_CARDS_PATH);
            if (f.getParentFile() != null) {
                f.getParentFile().mkdirs();
            }
            java.io.FileOutputStream fos =
                    new java.io.FileOutputStream(f);
            fos.write(sb.toString().getBytes("UTF-8"));
            fos.close();
        } catch (Throwable ignored) {
        }
    }

    /** v601: 启动后恢复信息卡（refreshLightDot 里懒
     * 加载一次）。事件时间已过 24 小时的卡丢弃。 */
    private void loadLightInfoCards() {
        if (lightInfoLoaded) {
            return;
        }
        lightInfoLoaded = true;
        try {
            java.io.File f = new java.io.File(INFO_CARDS_PATH);
            if (!f.exists()) {
                return;
            }
            java.io.BufferedReader br =
                    new java.io.BufferedReader(
                            new java.io.InputStreamReader(
                                    new java.io.FileInputStream(f),
                                    "UTF-8"));
            String line;
            long cutoff = System.currentTimeMillis()
                    - 24L * 3600 * 1000;
            while ((line = br.readLine()) != null) {
                String[] p = line.split("\t", -1);
                if (p.length < 10 || !"I".equals(p[0])) {
                    continue;
                }
                InfoParse.Card ic = new InfoParse.Card();
                ic.id = mirrorUnfield(p[1]);
                ic.kind = mirrorUnfield(p[2]);
                ic.title = mirrorUnfield(p[3]);
                ic.sub = mirrorUnfield(p[4]);
                ic.extra = mirrorUnfield(p[5]);
                String cp = mirrorUnfield(p[6]);
                ic.copyText = cp.isEmpty() ? null : cp;
                try {
                    ic.eventTime = Long.parseLong(p[7]);
                    ic.createdAt = Long.parseLong(p[9]);
                } catch (Throwable ignored) {
                }
                // v605: AI 产出标记（旧文件无此字段，缺省本地）。
                ic.ai = p.length > 10 && "1".equals(p[10]);
                // v606: 票据指纹（旧文件无此字段则为 null，
                // 同票合并对老卡不生效，新短信来了自然补）。
                if (p.length > 11) {
                    String fpv = mirrorUnfield(p[11]);
                    ic.fp = fpv.isEmpty() ? null : fpv;
                }
                if (ic.eventTime > 0 && ic.eventTime < cutoff) {
                    continue;
                }
                lightInfoCards.put(ic.id, ic);
                String srcKey = mirrorUnfield(p[8]);
                if (!srcKey.isEmpty()) {
                    lightInfoKeyToId.put(srcKey, ic.id);
                }
            }
            br.close();
            FileLog.i("Light info cards loaded n="
                    + lightInfoCards.size());
        } catch (Throwable ignored) {
        }
    }

    private static String mirrorUnfield(String s) {
        if (s == null || s.isEmpty()) {
            return "";
        }
        try {
            return java.net.URLDecoder.decode(s, "UTF-8");
        } catch (Throwable t) {
            return "";
        }
    }

    // v591: 呼吸灯边缘发光——独立小窗（不触摸、只发
    // 光）：径向渐变光晕贴在小签所在的屏幕边缘，透明
    // 度跟呼吸动画同拍，放歌时颜色跟签一起流转。
    private View lightGlowRoot;
    private GradientDrawable lightGlowBg;
    private WindowManager.LayoutParams lightGlowParams;
    private boolean lightGlowAdded;

    /** v595: 光晕按当前呼吸灯颜色重设渐变（放歌流转时
     * 不调——流转自己在写颜色）。 */
    private void applyLightGlowColors() {
        if (lightGlowBg == null) {
            return;
        }
        int dc = FeatureFlags.lightDotColorInt();
        lightGlowBg.setColors(new int[]{
                Color.argb(165, Color.red(dc), Color.green(dc),
                        Color.blue(dc)),
                Color.argb(0, Color.red(dc), Color.green(dc),
                        Color.blue(dc))});
    }

    private void ensureLightGlow() {
        if (lightGlowRoot != null) {
            return;
        }
        lightGlowBg = new GradientDrawable();
        lightGlowBg.setGradientType(
                GradientDrawable.RADIAL_GRADIENT);
        lightGlowBg.setGradientRadius(dp(80));
        applyLightGlowColors();
        lightGlowRoot = new View(app);
        lightGlowRoot.setBackground(lightGlowBg);
        lightGlowParams = new WindowManager.LayoutParams(
                dp(84), dp(170),
                WindowManager.LayoutParams.TYPE_SYSTEM_ERROR,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams
                                .FLAG_NOT_TOUCHABLE
                        | WindowManager.LayoutParams
                                .FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT);
        lightGlowParams.setTitle("flymebobo-lightglow");
    }

    private void applyLightGlowPos() {
        if (lightGlowRoot == null || lightGlowParams == null
                || lightDotParams == null) {
            return;
        }
        boolean right = FeatureFlags.lightDotRight();
        lightGlowParams.gravity = (right ? Gravity.END
                : Gravity.START) | Gravity.TOP;
        // v596: 签顶 = 窗口 y + 12（触摸扩区 padding），
        // 光晕中心按签中心对齐：y + 12 + 19。
        lightGlowParams.y = lightDotParams.y + dp(12) + dp(19)
                - dp(85);
        lightGlowBg.setGradientCenter(right ? 1f : 0f, 0.5f);
        if (!lightMusicPlaying) {
            applyLightGlowColors();
        }
        if (lightGlowAdded && wm != null) {
            try {
                wm.updateViewLayout(lightGlowRoot,
                        lightGlowParams);
            } catch (Throwable ignored) {
            }
        }
    }

    private void syncLightGlow() {
        if (!FeatureFlags.lightDotGlow || !lightDotAdded
                || app == null || wm == null) {
            removeLightGlow();
            return;
        }
        ensureLightGlow();
        applyLightGlowPos();
        if (!lightGlowAdded && lightGlowRoot != null) {
            try {
                wm.addView(lightGlowRoot, lightGlowParams);
                lightGlowAdded = true;
            } catch (Throwable ignored) {
            }
        }
    }

    private void removeLightGlow() {
        if (lightGlowAdded && lightGlowRoot != null
                && wm != null) {
            try {
                wm.removeViewImmediate(lightGlowRoot);
            } catch (Throwable ignored) {
            }
        }
        lightGlowAdded = false;
    }

    private void hideLightDot() {
        removeLightGlow();
        handler.removeCallbacks(lightPulseEndRunnable);
        dismissLightSubPill();
        lightPulsing = false;
        lightFlashKey = null;
        if (lightBreath != null) {
            try {
                lightBreath.cancel();
            } catch (Throwable ignored) {
            }
            lightBreath = null;
        }
        if (lightDotAdded && lightDotRoot != null && wm != null) {
            try {
                wm.removeViewImmediate(lightDotRoot);
            } catch (Throwable ignored) {
            }
        }
        lightDotAdded = false;
    }

    /** v564: 建小签窗口：贴边竖签（13×38dp）+ 到达药丸
     * （图标+应用名）+ 红角标，窗口参数与已移除的收纳点
     * 同款（TYPE_SYSTEM_ERROR、不可聚焦）。 */
    private void ensureLightDot() {
        if (lightDotRoot != null) {
            return;
        }
        lightDotRoot = new FrameLayout(app);
        lightDotBody = new android.widget.LinearLayout(app);
        lightDotBody.setOrientation(
                android.widget.LinearLayout.HORIZONTAL);
        lightDotBody.setGravity(Gravity.CENTER_VERTICAL);
        lightDotRoot.addView(lightDotBody, new FrameLayout
                .LayoutParams(
                        FrameLayout.LayoutParams.WRAP_CONTENT,
                        FrameLayout.LayoutParams.WRAP_CONTENT));
        // 竖签。
        lightTabBg = new GradientDrawable();
        lightTabBg.setColor(FeatureFlags.lightDotColorInt());
        lightDotTab = new View(app);
        lightDotTab.setBackground(lightTabBg);
        android.widget.LinearLayout.LayoutParams tabLp =
                new android.widget.LinearLayout.LayoutParams(
                        dp(13), dp(38));
        lightDotTab.setLayoutParams(tabLp);
        lightDotBody.addView(lightDotTab);
        // 到达药丸：深底圆角，应用图标 18dp + 应用名。
        lightPill = new android.widget.LinearLayout(app);
        lightPill.setOrientation(
                android.widget.LinearLayout.HORIZONTAL);
        lightPill.setGravity(Gravity.CENTER_VERTICAL);
        GradientDrawable pillBg = new GradientDrawable();
        // v572: 白色模式白底 + 黑色描边。
        if (lpWhite()) {
            pillBg.setColor(Color.argb(245, 255, 255, 255));
            pillBg.setStroke(dp(1), Color.BLACK);
        } else {
            pillBg.setColor(Color.argb(240, 20, 24, 38));
        }
        pillBg.setCornerRadius(dp(999));
        lightPill.setBackground(pillBg);
        lightPill.setPadding(dp(8), dp(5), dp(10), dp(5));
        lightPill.setVisibility(View.GONE);
        lightPillIcon = new android.widget.ImageView(app);
        android.widget.LinearLayout.LayoutParams icLp =
                new android.widget.LinearLayout.LayoutParams(
                        dp(18), dp(18));
        lightPillIcon.setLayoutParams(icLp);
        lightPill.addView(lightPillIcon);
        lightPillLabel = new android.widget.TextView(app);
        lightPillLabel.setTextColor(lpWhite()
                ? Color.rgb(15, 23, 42) : Color.WHITE);
        lightPillLabel.setTextSize(11);
        lightPillLabel.setSingleLine(true);
        lightPillLabel.setEllipsize(
                android.text.TextUtils.TruncateAt.END);
        lightPillLabel.setMaxWidth(dp(110));
        android.widget.LinearLayout.LayoutParams lbLp =
                new android.widget.LinearLayout.LayoutParams(
                        android.widget.LinearLayout.LayoutParams
                                .WRAP_CONTENT,
                        android.widget.LinearLayout.LayoutParams
                                .WRAP_CONTENT);
        lbLp.leftMargin = dp(5);
        lightPillLabel.setLayoutParams(lbLp);
        lightPill.addView(lightPillLabel);
        // v569: 药丸列（竖排）：主药丸在上（歌词/普通到达），
        // 副药丸在下——放歌开词时新通知在歌词下面闪。
        lightPillCol = new android.widget.LinearLayout(app);
        lightPillCol.setOrientation(
                android.widget.LinearLayout.VERTICAL);
        lightPillCol.addView(lightPill);
        lightSubPill = new android.widget.LinearLayout(app);
        lightSubPill.setOrientation(
                android.widget.LinearLayout.HORIZONTAL);
        lightSubPill.setGravity(Gravity.CENTER_VERTICAL);
        GradientDrawable subBg = new GradientDrawable();
        if (lpWhite()) {
            subBg.setColor(Color.argb(245, 255, 255, 255));
            subBg.setStroke(dp(1), Color.BLACK);
        } else {
            subBg.setColor(Color.argb(240, 20, 24, 38));
        }
        subBg.setCornerRadius(dp(999));
        lightSubPill.setBackground(subBg);
        lightSubPill.setPadding(dp(8), dp(5), dp(10), dp(5));
        lightSubPill.setVisibility(View.GONE);
        android.widget.LinearLayout.LayoutParams subLp =
                new android.widget.LinearLayout.LayoutParams(
                        android.widget.LinearLayout.LayoutParams
                                .WRAP_CONTENT,
                        android.widget.LinearLayout.LayoutParams
                                .WRAP_CONTENT);
        subLp.topMargin = dp(6);
        lightSubPill.setLayoutParams(subLp);
        lightSubPillIcon = new android.widget.ImageView(app);
        lightSubPillIcon.setLayoutParams(
                new android.widget.LinearLayout.LayoutParams(
                        dp(18), dp(18)));
        lightSubPill.addView(lightSubPillIcon);
        lightSubPillLabel = new android.widget.TextView(app);
        lightSubPillLabel.setTextColor(lpWhite()
                ? Color.rgb(15, 23, 42) : Color.WHITE);
        lightSubPillLabel.setTextSize(11);
        lightSubPillLabel.setSingleLine(true);
        lightSubPillLabel.setEllipsize(
                android.text.TextUtils.TruncateAt.END);
        lightSubPillLabel.setMaxWidth(dp(110));
        android.widget.LinearLayout.LayoutParams subLbLp =
                new android.widget.LinearLayout.LayoutParams(
                        android.widget.LinearLayout.LayoutParams
                                .WRAP_CONTENT,
                        android.widget.LinearLayout.LayoutParams
                                .WRAP_CONTENT);
        subLbLp.leftMargin = dp(5);
        lightSubPillLabel.setLayoutParams(subLbLp);
        lightSubPill.addView(lightSubPillLabel);
        lightPillCol.addView(lightSubPill);
        lightDotBody.addView(lightPillCol);
        // 角标：恒红，白字加粗（与收纳点同款）。
        lightBadge = new android.widget.TextView(app);
        GradientDrawable badgeBg = new GradientDrawable();
        badgeBg.setColor(Color.rgb(239, 68, 68));
        badgeBg.setCornerRadius(dp(999));
        lightBadge.setBackground(badgeBg);
        lightBadge.setTextColor(Color.WHITE);
        lightBadge.setTextSize(9);
        lightBadge.setTypeface(android.graphics.Typeface
                .DEFAULT_BOLD);
        lightBadge.setGravity(Gravity.CENTER);
        lightBadge.setMinWidth(dp(15));
        lightBadge.setPadding(dp(3), 0, dp(3), 0);
        FrameLayout.LayoutParams badgeLp = new FrameLayout
                .LayoutParams(
                        FrameLayout.LayoutParams.WRAP_CONTENT,
                        dp(15));
        lightBadge.setLayoutParams(badgeLp);
        lightBadge.setVisibility(View.GONE);
        lightDotRoot.addView(lightBadge);
        // 触摸：轻点开面板、按住竖拖改档位（95b7303 两点同款）。
        lightDotRoot.setOnTouchListener(
                new View.OnTouchListener() {
                    private float downRawY;
                    private float downRawX;
                    private int startY;
                    private long downAt;
                    private boolean dragging;

                    @Override
                    public boolean onTouch(View v,
                            android.view.MotionEvent e) {
                        if (lightDotParams == null) {
                            return false;
                        }
                        switch (e.getAction()) {
                            case android.view.MotionEvent
                                    .ACTION_DOWN:
                                downRawY = e.getRawY();
                                downRawX = e.getRawX();
                                startY = lightDotParams.y;
                                downAt = System.currentTimeMillis();
                                dragging = false;
                                return true;
                            case android.view.MotionEvent
                                    .ACTION_MOVE: {
                                float dy = e.getRawY() - downRawY;
                                if (!dragging
                                        && Math.abs(dy) > dp(8)) {
                                    dragging = true;
                                    handler.removeCallbacks(
                                            lightPulseEndRunnable);
                                    lightPulsing = false;
                                    dismissLightSubPill();
                                    if (lightPill != null) {
                                        lightPill.setVisibility(
                                                View.GONE);
                                    }
                                    if (lightDotTab != null) {
                                        lightDotTab.setVisibility(
                                                View.VISIBLE);
                                    }
                                    if (lightPanelAdded) {
                                        dismissLightPanel();
                                    }
                                }
                                if (dragging) {
                                    int h = app.getResources()
                                            .getDisplayMetrics()
                                            .heightPixels;
                                    int ny = startY
                                            + Math.round(dy);
                                    if (ny < 0) {
                                        ny = 0;
                                    }
                                    if (ny > h - dp(64)) {
                                        ny = h - dp(64);
                                    }
                                    lightDotParams.y = ny;
                                    if (lightDotAdded) {
                                        try {
                                            wm.updateViewLayout(
                                                    lightDotRoot,
                                                    lightDotParams);
                                        } catch (Throwable ignored) {
                                        }
                                    }
                                }
                                return true;
                            }
                            case android.view.MotionEvent
                                    .ACTION_UP:
                            case android.view.MotionEvent
                                    .ACTION_CANCEL: {
                                boolean wasDrag = dragging;
                                dragging = false;
                                if (wasDrag) {
                                    persistLightPosFromDrag();
                                } else if (e.getAction()
                                        == android.view.MotionEvent
                                                .ACTION_UP
                                        && System.currentTimeMillis()
                                                - downAt < 400
                                        && Math.abs(e.getRawY()
                                                - downRawY) < dp(10)
                                        && Math.abs(e.getRawX()
                                                - downRawX) < dp(10)) {
                                    if (lightPanelAdded) {
                                        dismissLightPanel();
                                    } else {
                                        showLightPanel();
                                    }
                                }
                                return true;
                            }
                            default:
                                return true;
                        }
                    }
                });
        // v596: 窗口高 64 = 竖签 38 + 上下各 12 透明触摸
        // 扩区（applyLightDotPos 里给根加的 padding）。
        lightDotParams = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT, dp(64),
                WindowManager.LayoutParams.TYPE_SYSTEM_ERROR,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams
                                .FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT);
        lightDotParams.setTitle("flymebobo-lightdot");
        applyLightDotPos();
    }

    /** v564: 按 FeatureFlags.lightDotPos 落小签位置：贴边
     * 方向、档位高度、签的圆角朝向、药丸/角标的左右顺序。 */
    private void applyLightDotPos() {
        if (lightDotRoot == null || lightDotParams == null) {
            return;
        }
        boolean right = FeatureFlags.lightDotRight();
        int h = app.getResources().getDisplayMetrics()
                .heightPixels;
        lightDotParams.gravity = (right ? Gravity.END
                : Gravity.START) | Gravity.TOP;
        // v588: body 与角标在根里的水平贴边方向跟随左右
        // ——根宽由最宽子view 定：药丸藏着时角标（约
        // 21dp）比竖签（13dp）宽，body 默认靠左停放，
        // 右侧模式下竖签就被顶得离右边缘约 8dp（wei：
        // 「选右边的时候好像没有贴边」）。两侧都把
        // body/角标钉到外侧，竖签才真正贴边。
        if (lightDotBody != null) {
            android.widget.FrameLayout.LayoutParams blp =
                    (android.widget.FrameLayout.LayoutParams)
                            lightDotBody.getLayoutParams();
            if (blp != null) {
                blp.gravity = Gravity.TOP
                        | (right ? Gravity.END : Gravity.START);
                lightDotBody.setLayoutParams(blp);
            }
        }
        if (lightBadge != null) {
            android.widget.FrameLayout.LayoutParams glp =
                    (android.widget.FrameLayout.LayoutParams)
                            lightBadge.getLayoutParams();
            if (glp != null) {
                glp.gravity = Gravity.TOP
                        | (right ? Gravity.END : Gravity.START);
                lightBadge.setLayoutParams(glp);
            }
        }
        // v596: 触碰范围加一点（wei 定）——根窗口上下
        // 与内侧各留 12dp 透明 padding 当触摸区，竖签本
        // 身大小不变；y 补偿掉上 padding，签仍落在原来
        // 的档位高度上（面板锚点/光晕/拖拽落档都按「签
        // 顶 = 窗口 y + 12」对齐）。
        int touchExt = dp(12);
        lightDotRoot.setPadding(right ? touchExt : 0,
                touchExt, right ? 0 : touchExt, touchExt);
        lightDotParams.y = Math.round(
                h * FeatureFlags.lightDotYFrac()) - touchExt;
        // body 子顺序：贴左=[签,药丸列]，贴右=[药丸列,签]。
        if (lightPillCol != null) {
            lightPillCol.setGravity(
                    right ? Gravity.END : Gravity.START);
        }
        if (lightDotBody != null && lightDotTab != null
                && lightPillCol != null) {
            int tabIdx = lightDotBody.indexOfChild(lightDotTab);
            int pillIdx = lightDotBody.indexOfChild(lightPillCol);
            if ((!right && tabIdx > pillIdx)
                    || (right && tabIdx < pillIdx)) {
                lightDotBody.removeView(lightDotTab);
                lightDotBody.removeView(lightPillCol);
                if (right) {
                    lightDotBody.addView(lightPillCol);
                    lightDotBody.addView(lightDotTab);
                } else {
                    lightDotBody.addView(lightDotTab);
                    lightDotBody.addView(lightPillCol);
                }
            }
        }
        // 签只圆内侧两角。
        if (lightTabBg != null) {
            float r = dp(9);
            if (right) {
                lightTabBg.setCornerRadii(new float[]{
                        r, r, 0, 0, 0, 0, r, r});
            } else {
                lightTabBg.setCornerRadii(new float[]{
                        0, 0, r, r, r, r, 0, 0});
            }
            if (!lightMusicPlaying) {
                lightTabBg.setColor(
                        FeatureFlags.lightDotColorInt());
            }
        }
        // 角标贴签的外上角。
        if (lightBadge != null) {
            FrameLayout.LayoutParams blp = (FrameLayout
                    .LayoutParams) lightBadge.getLayoutParams();
            blp.gravity = Gravity.TOP
                    | (right ? Gravity.END : Gravity.START);
            if (right) {
                blp.rightMargin = dp(5);
                blp.leftMargin = 0;
            } else {
                blp.leftMargin = dp(5);
                blp.rightMargin = 0;
            }
            blp.topMargin = 0;
            lightBadge.setLayoutParams(blp);
        }
        if (lightDotAdded) {
            try {
                wm.updateViewLayout(lightDotRoot, lightDotParams);
            } catch (Throwable ignored) {
            }
        }
        // v591: 光晕跟着小签走（拖动/换边/换档位）。
        if (lightGlowAdded) {
            applyLightGlowPos();
        }
    }

    /** v564: 拖动松手吸附最近档（只改档位不改边，同两点
     * 口径）→ 本地先改 FeatureFlags，再发广播让 App 进程
     * 落盘并回推（ACTION_SET_LIGHT_DOT_POS）。 */
    private void persistLightPosFromDrag() {
        if (lightDotParams == null) {
            return;
        }
        int h = app.getResources().getDisplayMetrics()
                .heightPixels;
        float centerFrac = h > 0
                ? (lightDotParams.y + dp(12) + dp(19))
                        / (float) h : 0.46f;
        int band = centerFrac < 0.31f ? 0
                : (centerFrac < 0.59f ? 1 : 2);
        String pos = (FeatureFlags.lightDotRight() ? "R" : "L")
                + band;
        FeatureFlags.lightDotPos = pos;
        FileLog.i("Light pos drag -> " + pos);
        applyLightDotPos();
        try {
            Intent intent = new Intent(
                    NotificationService.ACTION_SET_LIGHT_DOT_POS);
            intent.setPackage("com.chenan.flymebobo");
            intent.putExtra("pos", pos);
            app.sendBroadcast(intent);
        } catch (Throwable ignored) {
        }
    }

    private void updateLightBadge() {
        if (lightBadge == null) {
            return;
        }
        int n = lightStack.size();
        if (n <= 0) {
            lightBadge.setVisibility(View.GONE);
            return;
        }
        lightBadge.setText(n > 9 ? "9+" : String.valueOf(n));
        lightBadge.setVisibility(View.VISIBLE);
    }

    /** v564: 呼吸（约 3.2 秒一周期）；音乐播放时签底色随
     * 色相流转（约 9 秒一圈，试验田 v0.7 口径），与呼吸
     * 同拍。 */
    private void startLightBreathing() {
        if (lightDotBody == null) {
            return;
        }
        if (lightBreath != null && lightBreath.isRunning()) {
            return;
        }
        lightBreath = android.animation.ValueAnimator.ofFloat(
                0.25f, 1f);
        lightBreath.setDuration(1600);
        lightBreath.setRepeatCount(
                android.animation.ValueAnimator.INFINITE);
        lightBreath.setRepeatMode(
                android.animation.ValueAnimator.REVERSE);
        lightBreath.addUpdateListener(
                new android.animation.ValueAnimator
                        .AnimatorUpdateListener() {
                    @Override
                    public void onAnimationUpdate(
                            android.animation.ValueAnimator a) {
                        if (lightDotBody != null) {
                            lightDotBody.setAlpha(
                                    (Float) a.getAnimatedValue());
                        }
                        // v591: 光晕同拍——透明度跟签身，
                        // 放歌时颜色跟签一起流转。
                        if (lightGlowRoot != null
                                && lightGlowAdded) {
                            lightGlowRoot.setAlpha(
                                    (Float) a.getAnimatedValue());
                        }
                        if (lightMusicPlaying
                                && lightTabBg != null) {
                            float hue = (System
                                    .currentTimeMillis() % 9000L)
                                    / 9000f * 360f;
                            int hc = Color.HSVToColor(
                                    new float[]{hue, 0.55f,
                                            0.95f});
                            lightTabBg.setColor(hc);
                            if (lightGlowBg != null
                                    && lightGlowAdded) {
                                lightGlowBg.setColors(new int[]{
                                        Color.argb(165,
                                                Color.red(hc),
                                                Color.green(hc),
                                                Color.blue(hc)),
                                        Color.argb(0,
                                                Color.red(hc),
                                                Color.green(hc),
                                                Color.blue(hc))});
                            }
                        }
                    }
                });
        lightBreath.start();
    }

    /** v564: 到达药丸——展开成最新一条来源的「真实图标+
     * 应用名」，跟着呼吸闪约 3.5 个周期（11200ms）后落回
     * 小点；新到达直接换脸并重置计时（永远只显示最新）。 */
    private void flashLightPill(NotifData d) {
        if (d == null) {
            return;
        }
        // v577: wei 改规矩——不再歌词/通知同时显示（原
        // v569 副药丸方案作废）：新通知直接上主药丸闪，
        // 歌词给通知让路，通知闪完收纳后歌词再回来（见
        // lightPulseEndRunnable 的续显）。
        if (!lightDotAdded) {
            refreshLightDot();
        }
        if (lightPill == null || lightDotTab == null) {
            return;
        }
        String dpkg = lightDisplayPkg(d);
        android.graphics.drawable.Drawable icon =
                lightAppIcon(dpkg, d);
        if (icon != null) {
            lightPillIcon.setImageDrawable(icon);
            lightPillIcon.setVisibility(View.VISIBLE);
        } else {
            lightPillIcon.setVisibility(View.GONE);
        }
        setLightPillLyricMode(false);
        // v591: 带码通知的药丸直接闪码（预览口径）——
        // 「验证码 834201」一眼看到；打码状态下只闪来
        // 源应用，码不外露（PrivacyMask 同口径）。
        if (d.code != null && !PrivacyMask.shouldMask(app)) {
            lightPillLabel.setText((d.codeLabel != null
                    ? d.codeLabel : "验证码") + " " + d.code);
        } else {
            lightPillLabel.setText(lightAppLabel(dpkg, d));
        }
        lightPill.setVisibility(View.VISIBLE);
        lightDotTab.setVisibility(View.GONE);
        updateLightBadge();
        lightPulsing = true;
        lightFlashKey = d.key;
        handler.removeCallbacks(lightPulseEndRunnable);
        handler.postDelayed(lightPulseEndRunnable, 11200);
        FileLog.i("Light pill pkg=" + d.pkg
                + (d.srcPkg != null ? " src=" + d.srcPkg : ""));
    }

    /** v569: 副药丸闪新通知——放歌开词时用：歌词留在主
     * 药丸不被打断，新通知在它下面闪（图标+应用名，
     * 透明度一明一暗地闪烁），约 3.5 个呼吸周期后收回
     * =收纳完成（通知本体到达时已入栈、角标已计数）。
     * 再来一条直接换脸并重置计时（永远只显示最新）。
     * v577 起 wei 改规矩（歌词给通知让路、不同时显示），
     * 到达不再走副药丸——本方法保留但已无调用方。 */
    private void flashLightSubPill(NotifData d) {
        if (!lightDotAdded) {
            refreshLightDot();
        }
        if (lightSubPill == null || lightDotTab == null
                || !lightDotAdded) {
            return;
        }
        String dpkg = lightDisplayPkg(d);
        android.graphics.drawable.Drawable icon =
                lightAppIcon(dpkg, d);
        if (icon != null) {
            lightSubPillIcon.setImageDrawable(icon);
            lightSubPillIcon.setVisibility(View.VISIBLE);
        } else {
            lightSubPillIcon.setVisibility(View.GONE);
        }
        lightSubPillLabel.setText(lightAppLabel(dpkg, d));
        lightSubPill.setVisibility(View.VISIBLE);
        lightDotTab.setVisibility(View.GONE);
        updateLightBadge();
        lightSubShowing = true;
        lightSubFlashKey = d.key;
        startLightSubBlink();
        handler.removeCallbacks(lightSubEndRunnable);
        handler.postDelayed(lightSubEndRunnable, 11200);
        FileLog.i("Light subpill pkg=" + d.pkg
                + (d.srcPkg != null ? " src=" + d.srcPkg : ""));
    }

    private void startLightSubBlink() {
        stopLightSubBlink();
        if (lightSubPill == null) {
            return;
        }
        lightSubBlink = android.animation.ValueAnimator
                .ofFloat(1f, 0.3f);
        lightSubBlink.setDuration(800);
        lightSubBlink.setRepeatCount(
                android.animation.ValueAnimator.INFINITE);
        lightSubBlink.setRepeatMode(
                android.animation.ValueAnimator.REVERSE);
        lightSubBlink.addUpdateListener(
                new android.animation.ValueAnimator
                        .AnimatorUpdateListener() {
                    @Override
                    public void onAnimationUpdate(
                            android.animation.ValueAnimator a) {
                        if (lightSubPill != null) {
                            lightSubPill.setAlpha(
                                    (Float) a.getAnimatedValue());
                        }
                    }
                });
        lightSubBlink.start();
    }

    private void stopLightSubBlink() {
        if (lightSubBlink != null) {
            try {
                lightSubBlink.cancel();
            } catch (Throwable ignored) {
            }
            lightSubBlink = null;
        }
        if (lightSubPill != null) {
            lightSubPill.setAlpha(1f);
        }
    }

    /** v569: 收起副药丸（闪完/拖动/关模式共用）。 */
    private void dismissLightSubPill() {
        handler.removeCallbacks(lightSubEndRunnable);
        stopLightSubBlink();
        lightSubShowing = false;
        lightSubFlashKey = null;
        if (lightSubPill != null) {
            lightSubPill.setVisibility(View.GONE);
        }
    }
    /** v564: 点小签开收纳面板（全局单浮层口径：先 closeOverlays
     * 收掉菜单等）。场景中打开 = 只读（行点不动，防跳出游戏/
     * 视频，wei 定）。 */
    private void showLightPanel() {
        if (app == null || wm == null
                || !FeatureFlags.lightMode) {
            return;
        }
        // v599: 开面板前先清扫僵尸下载卡，别让它顶在
        // 面板第一张装死。
        sweepStaleLightDownloads();
        // v611: 面板打开 = 摘要已经看上了，循环闪停。
        stopLightSummaryFlash();
        loadLightInfoCards();
        if (lightStack.isEmpty() && lightMusicData == null
                && lightDownloads.isEmpty()
                && lightInfoCards.isEmpty()) {
            return;
        }
        // v602: 打标提速——面板打开时若有挂起的 AI 请
        // 求，立即发，不等 4 秒防抖到点。
        if (lightAiDebounce != null) {
            handler.removeCallbacks(lightAiDebounce);
            Runnable pending = lightAiDebounce;
            lightAiDebounce = null;
            pending.run();
        }
        // v589: 先抢下小签此刻的真实窗口 y——下面
        // dismissLightPanel 带的 refresh 会把小签 y 按
        // 档位重算，抢晚了面板又锚回档位位置。
        final int dotLiveY = (lightDotParams != null
                && lightDotAdded) ? lightDotParams.y : -1;
        closeOverlays();
        dismissLightPanel();
        lightPanelRecallMode = false;
        lightPanelReadOnly = sceneGuard != null
                && sceneGuard.isSuppressed();
        FileLog.i("Light panel open n=" + lightStack.size()
                + " music=" + (lightMusicData != null)
                + " readOnly=" + lightPanelReadOnly);
        lightPanelRoot = new FrameLayout(app);
        lightPanelBox = new android.widget.LinearLayout(app);
        lightPanelBox.setOrientation(
                android.widget.LinearLayout.VERTICAL);
        GradientDrawable boxBg = new GradientDrawable();
        // v567: 面板透明度/圆角走外观设置（lightPanelStyle）。
        // v572: 白色模式白底 + 黑色描边（wei 定）。
        int panelAlpha = Math.round(255f
                * FeatureFlags.lightPanelAlphaPct() / 100f);
        if (lpWhite()) {
            boxBg.setColor(Color.argb(panelAlpha,
                    255, 255, 255));
            boxBg.setStroke(dp(2), Color.BLACK);
        } else {
            boxBg.setColor(Color.argb(panelAlpha, 20, 24, 38));
        }
        boxBg.setCornerRadius(
                dp(FeatureFlags.lightPanelRadiusDp()));
        lightPanelBox.setBackground(boxBg);
        lightPanelBox.setPadding(dp(10), dp(10), dp(10),
                dp(10));
        boolean right = FeatureFlags.lightDotRight();
        FrameLayout.LayoutParams boxLp = new FrameLayout
                .LayoutParams(dp(FeatureFlags.lightPanelWidthDp()),
                        FrameLayout.LayoutParams.WRAP_CONTENT);
        boxLp.gravity = Gravity.TOP
                | (right ? Gravity.END : Gravity.START);
        // v589: 面板顶端锚小签的真实窗口 y（拖动后的实
        // 际位置），不再按档位比例重算——两者不一致时
        // 面板离小签老远（wei：「离呼吸灯也远」）。小
        // 签不在时才回落到档位比例。
        // v596: dotLiveY 是窗口顶，签顶还要 +12（触摸
        // 扩区 padding），锚点按签顶算、与 v589 口径一致。
        int anchorY = dotLiveY >= 0 ? dotLiveY + dp(12)
                : Math.round(app.getResources()
                        .getDisplayMetrics().heightPixels
                        * FeatureFlags.lightDotYFrac());
        boxLp.topMargin = Math.max(dp(24), anchorY - dp(30));
        if (right) {
            boxLp.rightMargin = dp(14);
        } else {
            boxLp.leftMargin = dp(14);
        }
        lightPanelBox.setLayoutParams(boxLp);
        lightPanelRoot.addView(lightPanelBox);
        // 点盒外关闭（根点击 + DOWN 落在盒外，v557 同款防漏关）。
        lightPanelRoot.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                dismissLightPanel();
            }
        });
        lightPanelRoot.setOnTouchListener(new View.OnTouchListener() {
            @Override
            public boolean onTouch(View v,
                    android.view.MotionEvent e) {
                if (e.getAction()
                        == android.view.MotionEvent.ACTION_DOWN
                        && lightPanelBox != null) {
                    int[] loc = new int[2];
                    lightPanelBox.getLocationOnScreen(loc);
                    float x = e.getRawX();
                    float y = e.getRawY();
                    if (x < loc[0] || x > loc[0]
                            + lightPanelBox.getWidth()
                            || y < loc[1] || y > loc[1]
                            + lightPanelBox.getHeight()) {
                        dismissLightPanel();
                        return true;
                    }
                }
                return false;
            }
        });
        WindowManager.LayoutParams lp = new WindowManager
                .LayoutParams(
                        WindowManager.LayoutParams.MATCH_PARENT,
                        WindowManager.LayoutParams.MATCH_PARENT,
                        WindowManager.LayoutParams.TYPE_SYSTEM_ERROR,
                        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                                | WindowManager.LayoutParams
                                        .FLAG_LAYOUT_IN_SCREEN
                                | WindowManager.LayoutParams
                                        .FLAG_LAYOUT_NO_LIMITS,
                        PixelFormat.TRANSLUCENT);
        lp.setTitle("flymebobo-lightpanel");
        lightPanelLp = lp;
        try {
            wm.addView(lightPanelRoot, lp);
            lightPanelAdded = true;
        } catch (Throwable t) {
            lightPanelRoot = null;
            lightPanelBox = null;
            lightPanelLp = null;
            return;
        }
        rebuildLightPanelRows();
        // 入场：从小签侧轻轻长出来（收纳面板同款）。
        // v567: 终点缩放 = 外观设置的整体比例（默认 100%），
        // 支点在小签侧上角，面板从小签那角长出来。
        float styleScale = FeatureFlags.lightPanelScalePct()
                / 100f;
        lightPanelBox.setAlpha(0f);
        lightPanelBox.setScaleX(0.96f * styleScale);
        lightPanelBox.setScaleY(0.96f * styleScale);
        // v589: 支点别读 getWidth()——刚 addView 还没布
        // 局，读到的是 0，右侧模式支点落到左边缘，比例
        // 一缩放面板整体往左漂、离小签更远。直接用
        // LayoutParams 里写死的盒宽。
        lightPanelBox.setPivotX(right ? boxLp.width : 0);
        lightPanelBox.setPivotY(0);
        lightPanelBox.animate().alpha(1f)
                .scaleX(styleScale).scaleY(styleScale)
                .setDuration(180)
                .setInterpolator(
                        new android.view.animation
                                .DecelerateInterpolator())
                .start();
    }

    /** v570: 轻气泡面板内嵌长按菜单（wei 定）——长按行后
     * 菜单直接在面板里、被长按行下方展开，其他行被自然
     * 挤开，不再走全屏浮层。再长按同一行收起；点任意条目
     * 走 dismissMenu() 一并收起。条目与经典菜单共用
     * buildMenuItems（「放入时光机」恒有；「这个应用以
     * 后收纳」v595 起轻气泡也出现）。 */
    private void toggleLightInlineMenu(View row, final NotifData d) {
        if (row == null || d == null
                || !(row.getParent() instanceof android.view.ViewGroup)) {
            return;
        }
        boolean same = lightInlineMenu != null && d.key != null
                && d.key.equals(lightInlineForKey);
        dismissMenu();
        if (same) {
            return;
        }
        try {
            android.widget.LinearLayout menu =
                    new android.widget.LinearLayout(app);
            menu.setOrientation(
                    android.widget.LinearLayout.VERTICAL);
            menu.setPadding(dp(8), dp(8), dp(8), dp(8));
            GradientDrawable mbg = new GradientDrawable();
            // v572: 白色模式下内嵌菜单换浅底（在白面板里）。
            if (lpWhite()) {
                mbg.setColor(Color.argb(248, 248, 250, 252));
                mbg.setStroke(dp(1), Color.rgb(226, 232, 240));
            } else {
                mbg.setColor(Color.argb(245, 24, 28, 40));
            }
            mbg.setCornerRadius(dp(16));
            menu.setBackground(mbg);
            android.widget.LinearLayout.LayoutParams mlp =
                    new android.widget.LinearLayout.LayoutParams(
                            android.widget.LinearLayout.LayoutParams
                                    .MATCH_PARENT,
                            android.widget.LinearLayout.LayoutParams
                                    .WRAP_CONTENT);
            mlp.leftMargin = dp(2);
            mlp.rightMargin = dp(2);
            mlp.topMargin = dp(4);
            mlp.bottomMargin = dp(6);
            menu.setLayoutParams(mlp);
            menuForLightPanel = true;
            buildMenuItems(menu, d, actionsFor(d.key));
            android.view.ViewGroup rows = (android.view.ViewGroup) row.getParent();
            int idx = rows.indexOfChild(row);
            if (idx < 0) {
                return;
            }
            rows.addView(menu, idx + 1);
            lightInlineMenu = menu;
            lightInlineForKey = d.key;
            // v574: 空间不够时面板自动拓展开，把菜单全露
            // 出来（高度动画在 applyLightScrollCap 里）。
            applyLightScrollCap();
            FileLog.i("Light inline menu pkg=" + d.pkg);
            menu.post(new Runnable() {
                @Override
                public void run() {
                    if (lightInlineMenu != null) {
                        lightInlineMenu.requestRectangleOnScreen(
                                new android.graphics.Rect(0, 0,
                                        lightInlineMenu.getWidth(),
                                        lightInlineMenu.getHeight()),
                                false);
                    }
                }
            });
        } catch (Throwable t) {
            FileLog.i("Light inline menu fail err=" + t);
            lightInlineMenu = null;
            lightInlineForKey = null;
        }
    }

    /** v570: 收起内嵌菜单（摘视图 + 清回复框引用 + 面板
     * 窗口恢复不可聚焦）。行数据的刷新由各动作自己的路径
     * 负责（menuConsume → lightRemove 会重建面板行）。 */
    private void collapseLightInlineMenu() {
        if (lightInlineMenu == null) {
            return;
        }
        android.view.ViewParent parent = lightInlineMenu.getParent();
        if (parent instanceof android.view.ViewGroup) {
            ((android.view.ViewGroup) parent).removeView(lightInlineMenu);
        }
        lightInlineMenu = null;
        lightInlineForKey = null;
        menuReplyBox = null;
        menuReplyInput = null;
        menuReplyError = null;
        setLightPanelFocusable(false);
        // v574: 菜单收起，面板高度缩回 N 行。
        applyLightScrollCap();
    }

    /** v570: 面板窗口聚焦开关——平时不可聚焦（不抢输入
     * 法），内嵌菜单的回复框展开时临时可聚焦，收起恢复。 */
    private void setLightPanelFocusable(boolean focusable) {
        if (!lightPanelAdded || lightPanelRoot == null
                || lightPanelLp == null || wm == null) {
            return;
        }
        int want = focusable
                ? (lightPanelLp.flags
                        & ~WindowManager.LayoutParams
                                .FLAG_NOT_FOCUSABLE)
                : (lightPanelLp.flags
                        | WindowManager.LayoutParams
                                .FLAG_NOT_FOCUSABLE);
        if (lightPanelLp.flags == want) {
            return;
        }
        lightPanelLp.flags = want;
        try {
            wm.updateViewLayout(lightPanelRoot, lightPanelLp);
        } catch (Throwable ignored) {
        }
    }

    private void dismissLightPanel() {
        dismissLightCodeStage();
        handler.removeCallbacks(lightMusicTickRunnable);
        handler.removeCallbacks(lightCodeTickRunnable);
        lightInlineMenu = null;
        lightInlineForKey = null;
        lightPanelScroll = null;
        lightPanelRows = null;
        if (lightCapAnim != null) {
            lightCapAnim.cancel();
            lightCapAnim = null;
        }
        if (!lightPanelAdded || lightPanelRoot == null) {
            lightPanelRoot = null;
            lightPanelBox = null;
            lightPanelLp = null;
            lightPanelAdded = false;
            return;
        }
        if (removeOverlayView(lightPanelRoot, "lightpanel")) {
            lightPanelAdded = false;
            lightPanelRoot = null;
            lightPanelBox = null;
            lightPanelLp = null;
            lightMusicBar = null;
            lightMusicTime = null;
            lightMusicPlayBtn = null;
            lightMusicFavBtn = null;
            lightMusicLyricBtn = null;
            lightMusicLyricView = null;
        }
    }

    /** v564: 面板内容重建——音乐卡永远置顶（收纳视图），
     * 其下头部计数 + 可滑列表（可视 5 条高）；底部时光机
     * 入口。时光机视图是同一个盒换内容。 */
    /** v601: 信息卡片渲染（票面风格，wei 定：票就是
     * 票的样子，不跟面板黑白模式）——火车票蓝磁票
     * 底、机票白卡蓝带、快递/账单白卡。点卡=打开源
     * 通知（还在栈里时）；「删除」=源通知进时光机、
     * 卡收掉（重启恢复的孤儿卡只收卡）。 */
    private View buildLightInfoCard(final InfoParse.Card ic) {
        final android.widget.LinearLayout card =
                new android.widget.LinearLayout(app);
        card.setOrientation(
                android.widget.LinearLayout.VERTICAL);
        GradientDrawable bg = new GradientDrawable();
        int accent;
        String kindLabel;
        if ("train".equals(ic.kind)) {
            bg.setColor(Color.rgb(219, 234, 254));
            accent = Color.rgb(29, 78, 216);
            kindLabel = "火车票";
        } else if ("flight".equals(ic.kind)) {
            bg.setColor(Color.WHITE);
            accent = Color.rgb(37, 99, 235);
            kindLabel = "登机牌";
        } else if ("courier".equals(ic.kind)) {
            bg.setColor(Color.WHITE);
            bg.setStroke(dp(1), Color.rgb(187, 247, 208));
            accent = Color.rgb(22, 163, 74);
            kindLabel = "快递";
        } else {
            bg.setColor(Color.WHITE);
            bg.setStroke(dp(1), Color.rgb(254, 215, 170));
            accent = Color.rgb(194, 65, 12);
            kindLabel = "账单";
        }
        bg.setCornerRadius(dp(14));
        card.setBackground(bg);
        card.setPadding(dp(12), dp(9), dp(12), dp(10));
        android.widget.LinearLayout.LayoutParams cardLp =
                new android.widget.LinearLayout.LayoutParams(
                        android.widget.LinearLayout.LayoutParams
                                .MATCH_PARENT,
                        android.widget.LinearLayout.LayoutParams
                                .WRAP_CONTENT);
        cardLp.bottomMargin = dp(8);
        card.setLayoutParams(cardLp);
        if ("flight".equals(ic.kind)) {
            android.widget.TextView band =
                    new android.widget.TextView(app);
            band.setText("登机牌 BOARDING PASS");
            band.setTextColor(Color.WHITE);
            band.setTextSize(10.5f);
            band.setPadding(dp(8), dp(3), dp(8), dp(3));
            GradientDrawable bb = new GradientDrawable();
            bb.setColor(Color.rgb(37, 99, 235));
            bb.setCornerRadius(dp(5));
            band.setBackground(bb);
            card.addView(band);
        }
        android.widget.LinearLayout topRow =
                new android.widget.LinearLayout(app);
        topRow.setOrientation(
                android.widget.LinearLayout.HORIZONTAL);
        topRow.setGravity(Gravity.CENTER_VERTICAL);
        android.widget.TextView kl =
                new android.widget.TextView(app);
        kl.setText(kindLabel
                + (ic.ai ? " · AI 识别" : " · 本地识别"));
        kl.setTextSize(11);
        kl.setTextColor(accent);
        kl.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        kl.setLayoutParams(new android.widget.LinearLayout
                .LayoutParams(0,
                        android.widget.LinearLayout.LayoutParams
                                .WRAP_CONTENT, 1f));
        topRow.addView(kl);
        android.widget.TextView del =
                new android.widget.TextView(app);
        del.setText("删除");
        del.setTextSize(11.5f);
        del.setTextColor(Color.rgb(220, 38, 38));
        del.setPadding(dp(8), dp(2), dp(2), dp(2));
        del.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                NotifData src = findLightInfoSource(ic);
                if (src != null) {
                    lightPushRecall(src);
                } else {
                    lightInfoCards.remove(ic.id);
                    java.util.Iterator<java.util.Map.Entry<
                            String, String>> it =
                            lightInfoKeyToId.entrySet()
                                    .iterator();
                    while (it.hasNext()) {
                        if (it.next().getValue()
                                .equals(ic.id)) {
                            it.remove();
                        }
                    }
                    if (ic.eventTime > 0) {
                        sendInfoRemind(false, ic);
                    }
                    saveLightInfoCards();
                }
                refreshLightDot();
                if (lightPanelAdded && !lightPanelRecallMode) {
                    rebuildLightPanelRows();
                }
            }
        });
        topRow.addView(del);
        card.addView(topRow);
        android.widget.TextView t =
                new android.widget.TextView(app);
        t.setText(ic.title);
        t.setTextSize(15.5f);
        t.setTextColor(Color.rgb(15, 23, 42));
        t.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        t.setPadding(0, dp(3), 0, 0);
        applyLightScroll(t);
        card.addView(t);
        if (ic.sub != null && !ic.sub.isEmpty()) {
            android.widget.TextView s =
                    new android.widget.TextView(app);
            s.setText(ic.sub);
            s.setTextSize(12.5f);
            s.setTextColor(Color.rgb(51, 65, 85));
            s.setPadding(0, dp(2), 0, 0);
            card.addView(s);
        }
        if (ic.extra != null && !ic.extra.isEmpty()) {
            android.widget.TextView e =
                    new android.widget.TextView(app);
            e.setText(ic.extra);
            e.setTextSize(11.5f);
            e.setTextColor(Color.rgb(100, 116, 139));
            e.setPadding(0, dp(2), 0, 0);
            card.addView(e);
        }
        if (ic.eventTime > 0 && ("train".equals(ic.kind)
                || "flight".equals(ic.kind))) {
            long diff = ic.eventTime
                    - System.currentTimeMillis();
            String when = new java.text.SimpleDateFormat(
                    "MM-dd HH:mm", java.util.Locale.CHINA)
                    .format(new java.util.Date(ic.eventTime));
            String txt;
            if (diff <= 0) {
                txt = "时间：" + when + "（已过）";
            } else {
                long mins = diff / 60000L;
                String d = mins >= 1440
                        ? (mins / 1440) + " 天 "
                                + (mins % 1440) / 60 + " 小时"
                        : mins >= 60
                                ? (mins / 60) + " 小时 "
                                        + (mins % 60) + " 分"
                                : mins + " 分钟";
                txt = ("train".equals(ic.kind) ? "距发车 "
                        : "距起飞 ") + d + " · " + when;
            }
            android.widget.TextView cdv =
                    new android.widget.TextView(app);
            cdv.setText(txt);
            cdv.setTextSize(12);
            cdv.setTextColor(accent);
            cdv.setPadding(0, dp(3), 0, 0);
            card.addView(cdv);
        }
        if (ic.copyText != null) {
            final android.widget.TextView cp =
                    new android.widget.TextView(app);
            cp.setText("复制单号");
            cp.setTextSize(12.5f);
            cp.setTextColor(Color.WHITE);
            cp.setGravity(Gravity.CENTER);
            cp.setPadding(0, dp(6), 0, dp(6));
            GradientDrawable cb = new GradientDrawable();
            cb.setColor(Color.rgb(22, 163, 74));
            cb.setCornerRadius(dp(8));
            cp.setBackground(cb);
            android.widget.LinearLayout.LayoutParams clp =
                    new android.widget.LinearLayout.LayoutParams(
                            android.widget.LinearLayout.LayoutParams
                                    .MATCH_PARENT,
                            android.widget.LinearLayout.LayoutParams
                                    .WRAP_CONTENT);
            clp.topMargin = dp(7);
            cp.setLayoutParams(clp);
            cp.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    try {
                        android.content.ClipboardManager cm =
                                (android.content.ClipboardManager)
                                        app.getSystemService(
                                                android.content.Context
                                                        .CLIPBOARD_SERVICE);
                        if (cm != null) {
                            cm.setPrimaryClip(
                                    android.content.ClipData
                                            .newPlainText("单号",
                                                    ic.copyText));
                            cp.setText("已复制");
                        }
                    } catch (Throwable ignored) {
                    }
                }
            });
            card.addView(cp);
        }
        card.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                NotifData src = findLightInfoSource(ic);
                if (src != null) {
                    dismissLightPanel();
                    lightOpenItem(src);
                }
            }
        });
        return card;
    }

    /** v601: 按信息卡反查还在收纳栈里的源通知。 */
    private NotifData findLightInfoSource(InfoParse.Card ic) {
        String srcKey = null;
        for (java.util.Map.Entry<String, String> e
                : lightInfoKeyToId.entrySet()) {
            if (e.getValue().equals(ic.id)) {
                srcKey = e.getKey();
                break;
            }
        }
        if (srcKey == null) {
            return null;
        }
        for (NotifData d : lightStack) {
            if (srcKey.equals(d.key)) {
                return d;
            }
        }
        return null;
    }

    /** v600: AI 摘要卡（预览定稿样式）——浅蓝底、蓝
     * 色小标「AI 摘要」、一句话正文、底部小字注明 AI
     * 生成。白色模式浅蓝底深字，黑色模式深蓝底浅字。 */
    private View buildLightAiCard() {
        android.widget.LinearLayout card =
                new android.widget.LinearLayout(app);
        card.setOrientation(
                android.widget.LinearLayout.VERTICAL);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(lpWhite() ? Color.rgb(239, 246, 255)
                : Color.rgb(23, 37, 63));
        bg.setCornerRadius(dp(14));
        if (lpWhite()) {
            bg.setStroke(dp(1), Color.rgb(191, 219, 254));
        }
        card.setBackground(bg);
        card.setPadding(dp(12), dp(9), dp(12), dp(9));
        android.widget.LinearLayout.LayoutParams cardLp =
                new android.widget.LinearLayout.LayoutParams(
                        android.widget.LinearLayout.LayoutParams
                                .MATCH_PARENT,
                        android.widget.LinearLayout.LayoutParams
                                .WRAP_CONTENT);
        cardLp.bottomMargin = dp(8);
        card.setLayoutParams(cardLp);
        android.widget.TextView label =
                new android.widget.TextView(app);
        label.setText("✦ AI 摘要");
        label.setTextSize(11);
        label.setTextColor(lpWhite()
                ? Color.rgb(37, 99, 235)
                : Color.rgb(147, 197, 253));
        label.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        card.addView(label);
        android.widget.TextView body =
                new android.widget.TextView(app);
        body.setText(lightAiSummary);
        body.setTextSize(13);
        body.setTextColor(lpTitleC());
        body.setLineSpacing(0, 1.25f);
        android.widget.LinearLayout.LayoutParams blp =
                new android.widget.LinearLayout.LayoutParams(
                        android.widget.LinearLayout.LayoutParams
                                .MATCH_PARENT,
                        android.widget.LinearLayout.LayoutParams
                                .WRAP_CONTENT);
        blp.topMargin = dp(3);
        body.setLayoutParams(blp);
        card.addView(body);
        android.widget.TextView foot =
                new android.widget.TextView(app);
        foot.setText("AI 生成 · 仅供参考");
        foot.setTextSize(10);
        foot.setTextColor(lpFaintC());
        android.widget.LinearLayout.LayoutParams flp =
                new android.widget.LinearLayout.LayoutParams(
                        android.widget.LinearLayout.LayoutParams
                                .MATCH_PARENT,
                        android.widget.LinearLayout.LayoutParams
                                .WRAP_CONTENT);
        flp.topMargin = dp(4);
        foot.setLayoutParams(flp);
        card.addView(foot);
        return card;
    }

    private void rebuildLightPanelRows() {
        if (lightPanelBox == null || !lightPanelAdded) {
            return;
        }
        // v570: 整表重建会丢弃内嵌菜单视图，引用先清掉。
        lightInlineMenu = null;
        lightInlineForKey = null;
        lightPanelScroll = null;
        lightPanelRows = null;
        if (lightCapAnim != null) {
            lightCapAnim.cancel();
            lightCapAnim = null;
        }
        lightDlBars.clear();
        lightDlTitles.clear();
        lightDlTexts.clear();
        lightDlPcts.clear();
        lightCdTexts.clear();
        lightCdFills.clear();
        lightCdSpaces.clear();
        lightCdDeadline.clear();
        lightCdTotal.clear();
        lightMusicBar = null;
        lightMusicTime = null;
        lightMusicPlayBtn = null;
        lightMusicFavBtn = null;
        lightMusicLyricBtn = null;
        lightMusicLyricView = null;
        lightPanelBox.removeAllViews();
        if (lightPanelRecallMode) {
            if (recallStack.isEmpty()) {
                lightPanelRecallMode = false;
            } else {
                buildLightPanelHeader("时光机 "
                        + recallStack.size() + " 条", "‹ 收纳",
                        new View.OnClickListener() {
                            @Override
                            public void onClick(View v) {
                                lightPanelRecallMode = false;
                                rebuildLightPanelRows();
                            }
                        });
                android.widget.LinearLayout rows =
                        new android.widget.LinearLayout(app);
                rows.setOrientation(
                        android.widget.LinearLayout.VERTICAL);
                for (NotifData d : recallStack) {
                    rows.addView(buildLightRow(d, true));
                }
                android.widget.ScrollView scroll =
                        new android.widget.ScrollView(app);
                scroll.addView(rows);
                lightPanelScroll = scroll;
                lightPanelRows = rows;
                lightPanelBox.addView(scroll);
                capLightScrollHeight(scroll, rows);
                return;
            }
        }
        if (lightStack.isEmpty() && lightMusicData == null
                && lightDownloads.isEmpty()) {
            dismissLightPanel();
            hideLightDot();
            return;
        }
        // v605: 面板卡片顺序（wei 定）：AI 开了的话
        // AI 摘要卡放最上面，音乐第二，其他卡片第三。
        if (FeatureFlags.aiEnabled && FeatureFlags.aiSummary
                && lightAiSummary != null
                && !lightAiSummary.isEmpty()) {
            lightPanelBox.addView(buildLightAiCard());
        }
        // 音乐卡第二（无 AI 摘要时它就是第一）。
        if (lightMusicData != null) {
            lightPanelBox.addView(buildLightMusicCard());
        }
        // v591: 码卡片紧随音乐卡——带码的收纳条目（验
        // 证码/取件码/取餐码）不再混在普通行里，在这
        // 里成卡（大字码 + 复制/出示），下面的行列表
        // 跳过它们。wei 预览点头后落地。
        for (NotifData cd : lightStack) {
            if (cd.code != null) {
                lightPanelBox.addView(buildLightCodeCard(cd));
            }
        }
        // v601: 信息卡片紧随码卡片——本地解析出的火车
        // 票/机票/快递/账单成票面卡，下面的行列表跳过
        // 它们的源通知。票面颜色不跟面板黑白模式（票
        // 就是票的样子，wei 定）。
        if (FeatureFlags.infoCards) {
            for (InfoParse.Card ic : lightInfoCards.values()) {
                lightPanelBox.addView(buildLightInfoCard(ic));
            }
        }
        // v576: 下载卡在音乐卡之下、收纳列表之上。
        for (NotifData dd : lightDownloads.values()) {
            lightPanelBox.addView(buildLightDownloadCard(dd));
        }
        buildLightPanelHeader("收纳 " + lightStack.size() + " 条",
                lightStack.isEmpty() ? null : "全部清除",
                new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        lightClearAll();
                    }
                });
        if (lightPanelReadOnly) {
            android.widget.TextView hint =
                    new android.widget.TextView(app);
            hint.setText("场景中只能查看");
            hint.setTextSize(11);
            hint.setTextColor(lpFaintC());
            hint.setPadding(dp(6), 0, dp(6), dp(4));
            lightPanelBox.addView(hint);
        }
        android.widget.LinearLayout rows =
                new android.widget.LinearLayout(app);
        rows.setOrientation(android.widget.LinearLayout.VERTICAL);
        // v605: 普通通知按时间排列（wei 定）——按通知
        // 自身时间 postTime 从新到旧渲染（推送补投/迟
        // 到的通知不再乱序）；栈的到达序不动，出栈与
        // 溢出逻辑照旧。
        java.util.List<NotifData> byTime =
                new java.util.ArrayList<>(lightStack);
        java.util.Collections.sort(byTime,
                new java.util.Comparator<NotifData>() {
                    @Override
                    public int compare(NotifData a,
                            NotifData b) {
                        return Long.compare(b.postTime,
                                a.postTime);
                    }
                });
        for (NotifData d : byTime) {
            // v591: 带码的已在上面成卡，行里跳过。
            if (d.code != null) {
                continue;
            }
            // v601: 成了信息卡的源通知也在上面成卡，跳过。
            if (d.key != null
                    && lightInfoKeyToId.containsKey(d.key)) {
                continue;
            }
            rows.addView(buildLightRow(d, false));
        }
        android.widget.ScrollView scroll =
                new android.widget.ScrollView(app);
        scroll.addView(rows);
        lightPanelScroll = scroll;
        lightPanelRows = rows;
        lightPanelBox.addView(scroll);
        capLightScrollHeight(scroll, rows);
        // 底部：时光机入口（回看在轻气泡里叫时光机）。
        if (!recallStack.isEmpty()) {
            android.widget.TextView entry =
                    new android.widget.TextView(app);
            entry.setText("时光机 " + recallStack.size()
                    + " 条  ›");
            entry.setTextSize(12);
            entry.setTextColor(lpLinkC());
            entry.setPadding(dp(6), dp(8), dp(6), dp(2));
            entry.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    lightPanelRecallMode = true;
                    rebuildLightPanelRows();
                }
            });
            lightPanelBox.addView(entry);
        }
        if (lightMusicData != null) {
            handler.removeCallbacks(lightMusicTickRunnable);
            handler.postDelayed(lightMusicTickRunnable, 1000);
        }
        // v593: 有倒计时的码卡先刷一帧，再起每秒 tick。
        if (!lightCdDeadline.isEmpty()) {
            updateLightCountdowns();
            handler.removeCallbacks(lightCodeTickRunnable);
            handler.postDelayed(lightCodeTickRunnable, 1000);
        }
    }

    private void buildLightPanelHeader(String left, String right,
            View.OnClickListener rightClick) {
        android.widget.LinearLayout head =
                new android.widget.LinearLayout(app);
        head.setOrientation(
                android.widget.LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        head.setPadding(dp(6), dp(2), dp(6), dp(6));
        android.widget.TextView l =
                new android.widget.TextView(app);
        l.setText(left);
        l.setTextSize(12);
        l.setTextColor(lpFaintC());
        head.addView(l, new android.widget.LinearLayout
                .LayoutParams(0,
                        android.widget.LinearLayout.LayoutParams
                                .WRAP_CONTENT, 1f));
        if (right != null) {
            android.widget.TextView r =
                    new android.widget.TextView(app);
            r.setText(right);
            r.setTextSize(12.5f);
            r.setTextColor("全部清除".equals(right)
                    ? Color.rgb(248, 113, 113)
                    : lpLinkC());
            if (rightClick != null) {
                r.setOnClickListener(rightClick);
            }
            head.addView(r);
        }
        lightPanelBox.addView(head);
    }

    /** v564: 列表可视高度封顶 5 条（试验田 v0.5 定版：拿
     * 真实行高求和，不用估高；量不到延迟重试）。 */
    private void capLightScrollHeight(
            final android.widget.ScrollView scroll,
            final android.widget.LinearLayout rows) {
        scroll.post(new Runnable() {
            private int tries;

            @Override
            public void run() {
                // v570: 内嵌菜单不是通知行，量高时跳过它。
                int totalRows = 0;
                for (int i = 0; i < rows.getChildCount(); i++) {
                    if (rows.getChildAt(i) != lightInlineMenu) {
                        totalRows++;
                    }
                }
                int n = Math.min(FeatureFlags.lightPanelRows(),
                        totalRows);
                if (n <= 0) {
                    return;
                }
                int h = 0;
                int counted = 0;
                for (int i = 0; i < rows.getChildCount()
                        && counted < n; i++) {
                    View child = rows.getChildAt(i);
                    if (child == lightInlineMenu) {
                        continue;
                    }
                    h += child.getHeight();
                    counted++;
                }
                if (h <= 0) {
                    if (++tries <= 5) {
                        scroll.postDelayed(this, 50);
                    }
                    return;
                }
                int total = rows.getHeight();
                if (rows.getChildCount() > n && total > 0
                        && h >= total) {
                    h = total * n / rows.getChildCount();
                }
                android.view.ViewGroup.LayoutParams lp =
                        scroll.getLayoutParams();
                lp.height = h;
                scroll.setLayoutParams(lp);
            }
        });
    }

    /** v574: 内嵌菜单展开/收起、回复框展开时重算滚动区
     * 高度。菜单在场：可视高撑到「N 行 + 菜单整高」，菜单
     * 一次全露出来，不用在小面板里再滑（wei 实测：只有
     * 一条通知时长按，菜单被一行高的小面板裁得只剩第
     * 一项）；上限屏高 72%，超了才滚。菜单不在场回到
     * N 行高。高度变化带 180ms 动画，像被菜单撑开。 */
    private void applyLightScrollCap() {
        final android.widget.ScrollView scroll = lightPanelScroll;
        final android.widget.LinearLayout rows = lightPanelRows;
        if (scroll == null || rows == null || !lightPanelAdded) {
            return;
        }
        scroll.post(new Runnable() {
            private int tries;

            @Override
            public void run() {
                if (lightPanelScroll != scroll) {
                    return;
                }
                int n = FeatureFlags.lightPanelRows();
                int h = 0;
                int counted = 0;
                for (int i = 0; i < rows.getChildCount()
                        && counted < n; i++) {
                    View child = rows.getChildAt(i);
                    if (child == lightInlineMenu) {
                        continue;
                    }
                    h += child.getHeight();
                    counted++;
                }
                if (h <= 0) {
                    if (++tries <= 5) {
                        scroll.postDelayed(this, 50);
                    }
                    return;
                }
                int target = h;
                if (lightInlineMenu != null) {
                    int mh = lightInlineMenu.getHeight();
                    if (mh <= 0) {
                        if (++tries <= 5) {
                            scroll.postDelayed(this, 50);
                        }
                        return;
                    }
                    android.view.ViewGroup.LayoutParams mlp =
                            lightInlineMenu.getLayoutParams();
                    if (mlp instanceof android.view.ViewGroup
                            .MarginLayoutParams) {
                        android.view.ViewGroup.MarginLayoutParams
                                m2 = (android.view.ViewGroup
                                        .MarginLayoutParams) mlp;
                        mh += m2.topMargin + m2.bottomMargin;
                    }
                    target = h + mh;
                }
                int maxH = (int) (app.getResources()
                        .getDisplayMetrics().heightPixels * 0.72f);
                if (target > maxH) {
                    target = maxH;
                }
                final android.view.ViewGroup.LayoutParams lp =
                        scroll.getLayoutParams();
                final int from = lp.height;
                if (from == target) {
                    return;
                }
                if (from <= 0) {
                    lp.height = target;
                    scroll.setLayoutParams(lp);
                    return;
                }
                if (lightCapAnim != null) {
                    lightCapAnim.cancel();
                }
                final int to = target;
                lightCapAnim = android.animation.ValueAnimator
                        .ofInt(from, to);
                lightCapAnim.setDuration(180);
                lightCapAnim.addUpdateListener(
                        new android.animation.ValueAnimator
                                .AnimatorUpdateListener() {
                            @Override
                            public void onAnimationUpdate(
                                    android.animation.ValueAnimator a) {
                                if (lightPanelScroll != scroll) {
                                    a.cancel();
                                    return;
                                }
                                lp.height = (Integer) a
                                        .getAnimatedValue();
                                scroll.setLayoutParams(lp);
                            }
                        });
                lightCapAnim.start();
                FileLog.i("Light panel grow -> " + to);
            }
        });
    }

    /** v564: 面板行——图标（真实来源）+ 标题 + 摘要 + 时间。
     * 手势是试验田 v0.5/v0.7 的方向判定状态机：竖滑让列表
     * 滚动（拦截不生效时手动兜底）、横滑过阈值删除（收纳行
     * 进时光机、时光机行彻底删）、小位移抬手=点开、长按
     * 500ms 出胶囊同款菜单（仅收纳行）。只读（场景中）时
     * 全部手势不接。 */
    /** v568: 内容滚动是通用元素（wei 定）——轻气泡与经典
     * 共用同一个 scroll 开关、同款做法（同 CapsuleView
     * .applyScrollFlag）：ellipsize 恒设 MARQUEE，开关只
     * 切换 selected——选中时长内容滚动，不选时显示省略
     * 号。面板行与音乐卡的标题/正文都走这里。 */
    private void applyLightScroll(android.widget.TextView tv) {
        tv.setEllipsize(
                android.text.TextUtils.TruncateAt.MARQUEE);
        tv.setSelected(FeatureFlags.scroll);
    }

    // v572: 轻气泡黑 / 白双模式（wei 定）——白色模式的
    // 浮层一律白底 + 黑色描边、文字转深色系；黑色模式 =
    // 现行深色长相。以下色板助手供面板 / 药丸 / 音乐卡 /
    // 内嵌菜单共用。
    private boolean menuForLightPanel;
    static boolean lpWhite() {
        return FeatureFlags.lightPanelWhite;
    }
    private static int lpTitleC() {
        return lpWhite() ? Color.rgb(15, 23, 42)
                : Color.argb(224, 255, 255, 255);
    }
    private static int lpTextC() {
        return lpWhite() ? Color.rgb(71, 85, 105)
                : Color.argb(150, 255, 255, 255);
    }
    private static int lpFaintC() {
        return lpWhite() ? Color.rgb(100, 116, 139)
                : Color.argb(140, 255, 255, 255);
    }
    private static int lpAgoC() {
        return lpWhite() ? Color.rgb(148, 163, 184)
                : Color.argb(102, 255, 255, 255);
    }
    private static int lpLinkC() {
        return lpWhite() ? Color.rgb(37, 99, 235)
                : Color.rgb(147, 197, 253);
    }
    /** v572: 当前要建的是轻气泡内嵌菜单（白色模式）——
     * 菜单行/回复框据此换深字。经典全屏菜单永假。 */
    private boolean lpMenuLight() {
        return FeatureFlags.lightMode && lpWhite()
                && menuForLightPanel;
    }

    /** v595: 呼吸灯颜色翻动（XposedEntry 转发）——签身
     * 与光晕立即换成新色；放歌流转中不动它（停歌回落
     * 时按新色，见音乐出栈处的回落写色）。 */
    public void onLightDotColorChanged() {
        handler.post(new Runnable() {
            @Override
            public void run() {
                FileLog.i("Light dotColor applied -> "
                        + FeatureFlags.lightDotColor);
                if (lightMusicPlaying) {
                    return;
                }
                if (lightTabBg != null) {
                    lightTabBg.setColor(
                            FeatureFlags.lightDotColorInt());
                }
                applyLightGlowColors();
            }
        });
    }

    /** v572: 黑/白模式翻动（XposedEntry 转发）——小签拆掉
     * 按新皮重建，面板开着就关掉重开（整套颜色在构建时
     * 定死，重建最稳）。 */
    public void onLightPanelThemeChanged() {
        handler.post(new Runnable() {
            @Override
            public void run() {
                FileLog.i("Light theme -> "
                        + (lpWhite() ? "white" : "black"));
                boolean wasPanel = lightPanelAdded;
                if (lightPanelAdded) {
                    dismissLightPanel();
                }
                if (lightDotRoot != null && lightDotAdded) {
                    try {
                        wm.removeView(lightDotRoot);
                    } catch (Throwable ignored) {
                    }
                    lightDotAdded = false;
                    lightDotRoot = null;
                }
                refreshLightDot();
                if (wasPanel) {
                    showLightPanel();
                }
            }
        });
    }

    private View buildLightRow(final NotifData d,
            final boolean recall) {
        final android.widget.LinearLayout row =
                new android.widget.LinearLayout(app);
        row.setOrientation(
                android.widget.LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(6), dp(7), dp(6), dp(7));
        row.setClickable(true);
        String dpkg = lightDisplayPkg(d);
        android.widget.ImageView icon =
                new android.widget.ImageView(app);
        android.graphics.drawable.Drawable dr =
                lightAppIcon(dpkg, d);
        if (dr != null) {
            icon.setImageDrawable(dr);
        }
        android.widget.LinearLayout.LayoutParams icLp =
                new android.widget.LinearLayout.LayoutParams(
                        dp(28), dp(28));
        icon.setLayoutParams(icLp);
        row.addView(icon);
        android.widget.LinearLayout mid =
                new android.widget.LinearLayout(app);
        mid.setOrientation(android.widget.LinearLayout.VERTICAL);
        android.widget.LinearLayout.LayoutParams midLp =
                new android.widget.LinearLayout.LayoutParams(
                        0, android.widget.LinearLayout.LayoutParams
                                .WRAP_CONTENT, 1f);
        midLp.leftMargin = dp(8);
        mid.setLayoutParams(midLp);
        android.widget.TextView title =
                new android.widget.TextView(app);
        String label = lightAppLabel(dpkg, d);
        title.setText(d.title != null && !d.title.isEmpty()
                ? d.title : label);
        title.setTextSize(13);
        title.setTextColor(lpTitleC());
        title.setSingleLine(true);
        applyLightScroll(title);
        // 定宽 MATCH_PARENT，长标题才滚得起来（经典 v355
        // 教训：WRAP_CONTENT 量宽超父容器会被直接裁掉）。
        mid.addView(title, new android.widget.LinearLayout
                .LayoutParams(
                        android.widget.LinearLayout.LayoutParams
                                .MATCH_PARENT,
                        android.widget.LinearLayout.LayoutParams
                                .WRAP_CONTENT));
        if (d.text != null && !d.text.isEmpty()
                && !d.text.equals(d.title)) {
            android.widget.TextView text =
                    new android.widget.TextView(app);
            text.setText(d.text);
            text.setTextSize(11.5f);
            text.setTextColor(lpTextC());
            text.setSingleLine(true);
            applyLightScroll(text);
            mid.addView(text, new android.widget.LinearLayout
                    .LayoutParams(
                            android.widget.LinearLayout
                                    .LayoutParams.MATCH_PARENT,
                            android.widget.LinearLayout
                                    .LayoutParams.WRAP_CONTENT));
        }
        row.addView(mid);
        // v600: AI 语义标签 chip（打标开且这条有标签
        // 时）——疑似诈骗红、重要蓝、促销灰，摆在时间
        // 左边。时光机行不打标（recall 直接跳过）。
        if (!recall && FeatureFlags.aiEnabled
                && FeatureFlags.aiTags && d.key != null) {
            String tag = lightAiTags.get(d.key);
            String tagLabel = null;
            int tagBg = 0, tagInk = 0;
            if ("suspect".equals(tag)) {
                tagLabel = "疑似诈骗";
                tagBg = lpWhite() ? Color.rgb(254, 226, 226)
                        : Color.rgb(69, 26, 26);
                tagInk = lpWhite() ? Color.rgb(185, 28, 28)
                        : Color.rgb(252, 165, 165);
            } else if ("important".equals(tag)) {
                tagLabel = "重要";
                tagBg = lpWhite() ? Color.rgb(219, 234, 254)
                        : Color.rgb(30, 58, 95);
                tagInk = lpWhite() ? Color.rgb(29, 78, 216)
                        : Color.rgb(147, 197, 253);
            } else if ("promo".equals(tag)) {
                tagLabel = "促销";
                tagBg = lpWhite() ? Color.rgb(241, 245, 249)
                        : Color.rgb(51, 65, 85);
                tagInk = lpWhite() ? Color.rgb(100, 116, 139)
                        : Color.rgb(203, 213, 225);
            }
            if (tagLabel != null) {
                android.widget.TextView chip =
                        new android.widget.TextView(app);
                chip.setText(tagLabel);
                chip.setTextSize(10);
                chip.setTextColor(tagInk);
                chip.setPadding(dp(5), dp(1), dp(5), dp(1));
                GradientDrawable cg = new GradientDrawable();
                cg.setColor(tagBg);
                cg.setCornerRadius(dp(6));
                chip.setBackground(cg);
                android.widget.LinearLayout.LayoutParams clp =
                        new android.widget.LinearLayout
                                .LayoutParams(
                                        android.widget.LinearLayout
                                                .LayoutParams
                                                .WRAP_CONTENT,
                                        android.widget.LinearLayout
                                                .LayoutParams
                                                .WRAP_CONTENT);
                clp.leftMargin = dp(6);
                clp.rightMargin = dp(2);
                chip.setLayoutParams(clp);
                row.addView(chip);
            }
        }
        android.widget.TextView ago =
                new android.widget.TextView(app);
        ago.setText(lightAgoText(d.postTime));
        ago.setTextSize(11);
        ago.setTextColor(lpAgoC());
        row.addView(ago);
        if (lightPanelReadOnly) {
            return row;
        }
        final int[] state = new int[]{0}; // 0 none 1 horz 2 vert 3 vert-manual
        final float[] down = new float[2];
        final float[] last = new float[1];
        final boolean[] longFired = new boolean[]{false};
        final Runnable[] lpRunnable = new Runnable[1];
        lpRunnable[0] = new Runnable() {
            @Override
            public void run() {
                if (state[0] != 0 || recall
                        || !FeatureFlags.longPressMenu) {
                    return;
                }
                longFired[0] = true;
                FileLog.i("Light longmenu pkg=" + d.pkg);
                // v570: 菜单在面板里内嵌展开（wei 定），不走
                // 全屏浮层。
                toggleLightInlineMenu(row, d);
            }
        };
        row.setOnTouchListener(new View.OnTouchListener() {
            @Override
            public boolean onTouch(View v,
                    android.view.MotionEvent e) {
                switch (e.getAction()) {
                    case android.view.MotionEvent.ACTION_DOWN:
                        down[0] = e.getRawX();
                        down[1] = e.getRawY();
                        last[0] = e.getRawY();
                        state[0] = 0;
                        longFired[0] = false;
                        if (!recall) {
                            handler.postDelayed(lpRunnable[0],
                                    500);
                        }
                        return false;
                    case android.view.MotionEvent.ACTION_MOVE: {
                        float dx = e.getRawX() - down[0];
                        float dy = e.getRawY() - down[1];
                        if (state[0] == 0) {
                            if (dx < -dp(10)
                                    && Math.abs(dx) > Math.abs(dy)) {
                                state[0] = 1;
                                handler.removeCallbacks(
                                        lpRunnable[0]);
                                v.getParent()
                                        .requestDisallowInterceptTouchEvent(
                                                true);
                            } else if (Math.abs(dy) > dp(10)
                                    && Math.abs(dy) >= Math.abs(dx)) {
                                state[0] = 2;
                                handler.removeCallbacks(
                                        lpRunnable[0]);
                            }
                        }
                        if (state[0] == 1) {
                            v.setTranslationX(Math.min(0, dx));
                        } else if (state[0] == 2
                                && Math.abs(dy) > dp(28)) {
                            // ScrollView 拦截没生效 → 手动兜底滚。
                            state[0] = 3;
                        } else if (state[0] == 3) {
                            android.widget.ScrollView sv =
                                    findLightScrollAncestor(v);
                            if (sv != null) {
                                sv.scrollBy(0,
                                        Math.round(last[0]
                                                - e.getRawY()));
                            }
                        }
                        last[0] = e.getRawY();
                        return state[0] == 1 || state[0] == 3;
                    }
                    case android.view.MotionEvent.ACTION_UP: {
                        handler.removeCallbacks(lpRunnable[0]);
                        float dx = e.getRawX() - down[0];
                        if (longFired[0]) {
                            longFired[0] = false;
                            return true;
                        }
                        if (state[0] == 1) {
                            if (dx < -dp(56)) {
                                if (recall) {
                                    lightRecallDrop(d);
                                } else {
                                    lightRemove(d);
                                    lightPushRecall(d);
                                }
                            } else {
                                v.animate().translationX(0)
                                        .setDuration(150).start();
                            }
                            state[0] = 0;
                            return true;
                        }
                        if (state[0] == 0) {
                            if (recall) {
                                lightRecallOpen(d);
                            } else {
                                lightOpenItem(d);
                            }
                            return true;
                        }
                        state[0] = 0;
                        return true;
                    }
                    case android.view.MotionEvent.ACTION_CANCEL:
                        handler.removeCallbacks(lpRunnable[0]);
                        state[0] = 0;
                        if (v.getTranslationX() != 0) {
                            v.animate().translationX(0)
                                    .setDuration(120).start();
                        }
                        return true;
                    default:
                        return false;
                }
            }
        });
        return row;
    }

    private android.widget.ScrollView findLightScrollAncestor(
            View v) {
        android.view.ViewParent p = v.getParent();
        while (p instanceof View) {
            if (p instanceof android.widget.ScrollView) {
                return (android.widget.ScrollView) p;
            }
            p = ((View) p).getParent();
        }
        return null;
    }

    /** v564: 面板音乐卡（预览 A 完整卡）：封面 + 歌名/歌手
     * + 歌词行 + 进度 + 上一首/播放暂停/下一首。数据来自
     * 轻气泡自有音乐状态（通知本体 + 会话广播喂），控制走
     * 经典 sendMusicControl 同一条广播通道。 */
    /** v565: 封面主色（12×12 缩略后取鲜艳像素均值），给
     * 音乐卡按钮底色微微染色；同一张封面只算一次。 */
    private int lightArtTint(android.graphics.Bitmap bmp) {
        if (bmp == null) {
            return 0;
        }
        if (bmp == lightMusicTintSrc) {
            return lightMusicTint;
        }
        lightMusicTintSrc = bmp;
        lightMusicTint = 0;
        try {
            android.graphics.Bitmap small =
                    android.graphics.Bitmap.createScaledBitmap(
                            bmp, 12, 12, true);
            long r = 0, g = 0, b = 0, n = 0;
            for (int y = 0; y < small.getHeight(); y++) {
                for (int x = 0; x < small.getWidth(); x++) {
                    int px = small.getPixel(x, y);
                    if (Color.alpha(px) < 128) {
                        continue;
                    }
                    int pr = Color.red(px), pg = Color.green(px),
                            pb = Color.blue(px);
                    int mx = Math.max(pr, Math.max(pg, pb));
                    int mn = Math.min(pr, Math.min(pg, pb));
                    if (mx - mn < 24) {
                        continue;
                    }
                    r += pr;
                    g += pg;
                    b += pb;
                    n++;
                }
            }
            if (n > 0) {
                lightMusicTint = Color.rgb(
                        (int) (r / n), (int) (g / n),
                        (int) (b / n));
            }
        } catch (Throwable ignored) {
        }
        return lightMusicTint;
    }

    /** v565: 轻气泡音乐卡的自绘按钮（wei 点名重做：
     * v564 的 emoji 字形三键风格不统一，暂停键被系统渲染
     * 成蓝灰瓦片）。五种键共用一套自绘字形（双三角/三角/
     * 竖线/爱心/「词」），中间播放键靠尺寸分主次。
     * v567: 按 wei「不要这个蒙版玻璃」「按钮就单单是
     * 按钮」，v565 的毛玻璃圆底 + 描边环已去掉，只剩
     * 字形；按下只有 0.9 缩放；收藏空心/实心、歌词跟随
     * 开/关（变蓝）都在本类按状态重绘。 */
    private final class LightMusicBtn extends View {
        static final int PREV = 0;
        static final int TOGGLE = 1;
        static final int NEXT = 2;
        static final int FAV = 3;
        static final int LYRIC = 4;

        final int kind;
        boolean playing;
        boolean favorite;
        boolean active;
        boolean down;
        int tint;
        private final android.graphics.Paint fillPaint =
                new android.graphics.Paint(
                        android.graphics.Paint.ANTI_ALIAS_FLAG);
        private final android.graphics.Paint ringPaint =
                new android.graphics.Paint(
                        android.graphics.Paint.ANTI_ALIAS_FLAG);
        private final android.graphics.Paint glyphPaint =
                new android.graphics.Paint(
                        android.graphics.Paint.ANTI_ALIAS_FLAG);
        private final android.graphics.Paint glyphStroke =
                new android.graphics.Paint(
                        android.graphics.Paint.ANTI_ALIAS_FLAG);
        private final android.graphics.Paint textPaint =
                new android.graphics.Paint(
                        android.graphics.Paint.ANTI_ALIAS_FLAG);
        private final android.graphics.Path path =
                new android.graphics.Path();

        LightMusicBtn(int kind) {
            super(app);
            this.kind = kind;
            setClickable(true);
            ringPaint.setStyle(
                    android.graphics.Paint.Style.STROKE);
            glyphPaint.setStyle(
                    android.graphics.Paint.Style.FILL);
            // v572: 白色模式下按钮字形转深色（爱心粉、词键
            // 蓝是字形自身色，在 onDraw 里另行指定，不受
            // 这里的基础色影响）。
            int glyphBase = lpWhite()
                    ? Color.rgb(15, 23, 42) : Color.WHITE;
            glyphPaint.setColor(glyphBase);
            glyphStroke.setStyle(
                    android.graphics.Paint.Style.STROKE);
            glyphStroke.setColor(glyphBase);
            glyphStroke.setStrokeCap(
                    android.graphics.Paint.Cap.ROUND);
            glyphStroke.setStrokeJoin(
                    android.graphics.Paint.Join.ROUND);
            textPaint.setColor(glyphBase);
            textPaint.setTextAlign(
                    android.graphics.Paint.Align.CENTER);
            textPaint.setTypeface(
                    android.graphics.Typeface.DEFAULT_BOLD);
        }

        void setPlaying(boolean p) {
            if (playing != p) {
                playing = p;
                invalidate();
            }
        }

        void setFavorite(boolean f) {
            if (favorite != f) {
                favorite = f;
                invalidate();
            }
        }

        void setActive(boolean a) {
            if (active != a) {
                active = a;
                invalidate();
            }
        }

        void setTint(int t) {
            if (tint != t) {
                tint = t;
                invalidate();
            }
        }

        @Override
        public boolean onTouchEvent(
                android.view.MotionEvent e) {
            switch (e.getAction()) {
                case android.view.MotionEvent.ACTION_DOWN:
                    down = true;
                    setScaleX(0.9f);
                    setScaleY(0.9f);
                    invalidate();
                    return true;
                case android.view.MotionEvent.ACTION_UP:
                    boolean inside = down
                            && e.getX() >= 0 && e.getY() >= 0
                            && e.getX() <= getWidth()
                            && e.getY() <= getHeight();
                    down = false;
                    setScaleX(1f);
                    setScaleY(1f);
                    invalidate();
                    if (inside) {
                        performClick();
                    }
                    return true;
                case android.view.MotionEvent.ACTION_CANCEL:
                    down = false;
                    setScaleX(1f);
                    setScaleY(1f);
                    invalidate();
                    return true;
                default:
                    return super.onTouchEvent(e);
            }
        }

        @Override
        public boolean performClick() {
            super.performClick();
            return true;
        }

        @Override
        protected void onDraw(android.graphics.Canvas c) {
            float w = getWidth(), h = getHeight();
            if (w <= 0 || h <= 0) {
                return;
            }
            float cx = w / 2f, cy = h / 2f;
            // v567: 不要毛玻璃圆底（wei：「不要这个蒙版
            // 玻璃」「按钮就单单是按钮」）——圆盘填充和描边
            // 环都去掉，只画字形本身；按下反馈只剩 onTouch
            // 的 0.9 缩放。收藏爱心粉/描边、词键开启变蓝
            // 都是字形自身的颜色，保留。
            // 字形走 24 网格坐标（同 Material 图标的视口），
            // 居中缩放绘制。
            float u = Math.min(w, h) / 24f;
            c.save();
            c.translate(cx - 12f * u, cy - 12f * u);
            c.scale(u, u);
            // v580: 字形填充色按模式走——原先这里写死白色，
            // 白色模式下上一首/下一首/暂停全成白字白卡看
            // 不见（wei 逮住）；构造器里按模式定的基础色
            // 也被这行覆盖了。白色模式用墨色，黑色模式白。
            glyphPaint.setColor(lpWhite()
                    ? Color.rgb(15, 23, 42) : Color.WHITE);
            path.reset();
            switch (kind) {
                case PREV:
                    tri(18.5f, 6.8f, 12.8f, 12f, 18.5f, 17.2f);
                    tri(12.8f, 6.8f, 7.2f, 12f, 12.8f, 17.2f);
                    path.addRect(new android.graphics.RectF(
                            4.6f, 6.8f, 6.4f, 17.2f),
                            android.graphics.Path.Direction.CW);
                    c.drawPath(path, glyphPaint);
                    break;
                case NEXT:
                    tri(5.5f, 6.8f, 11.2f, 12f, 5.5f, 17.2f);
                    tri(11.2f, 6.8f, 16.8f, 12f, 11.2f, 17.2f);
                    path.addRect(new android.graphics.RectF(
                            17.6f, 6.8f, 19.4f, 17.2f),
                            android.graphics.Path.Direction.CW);
                    c.drawPath(path, glyphPaint);
                    break;
                case TOGGLE:
                    if (playing) {
                        path.addRoundRect(
                                new android.graphics.RectF(
                                        7.4f, 6.6f, 10.6f, 17.4f),
                                1.4f, 1.4f,
                                android.graphics.Path.Direction.CW);
                        path.addRoundRect(
                                new android.graphics.RectF(
                                        13.4f, 6.6f, 16.6f, 17.4f),
                                1.4f, 1.4f,
                                android.graphics.Path.Direction.CW);
                        c.drawPath(path, glyphPaint);
                    } else {
                        tri(9.2f, 6.6f, 9.2f, 17.4f, 17.8f, 12f);
                        c.drawPath(path, glyphPaint);
                        glyphStroke.setStrokeWidth(1.1f);
                        c.drawPath(path, glyphStroke);
                    }
                    break;
                case FAV:
                    path.moveTo(12f, 20.6f);
                    path.cubicTo(4.2f, 14.6f, 2.6f, 11.2f,
                            2.6f, 8.4f);
                    path.cubicTo(2.6f, 5.7f, 4.7f, 3.9f,
                            7.1f, 3.9f);
                    path.cubicTo(9.1f, 3.9f, 11f, 5.1f,
                            12f, 7.1f);
                    path.cubicTo(13f, 5.1f, 14.9f, 3.9f,
                            16.9f, 3.9f);
                    path.cubicTo(19.3f, 3.9f, 21.4f, 5.7f,
                            21.4f, 8.4f);
                    path.cubicTo(21.4f, 11.2f, 19.8f, 14.6f,
                            12f, 20.6f);
                    path.close();
                    // v576: 爱心比其他字形小一圈（wei：收藏
                    // 图标再小一点）——它的路径在 24 网格里
                    // 几乎占满（高约 16.7），上一首/暂停等只
                    // 有约 10.8，绕中心缩到 0.78 才齐平。
                    c.save();
                    c.translate(12f, 12f);
                    c.scale(0.78f, 0.78f);
                    c.translate(-12f, -12f);
                    if (favorite) {
                        glyphPaint.setColor(
                                Color.rgb(255, 64, 129));
                        c.drawPath(path, glyphPaint);
                    } else {
                        glyphStroke.setStrokeWidth(1.6f);
                        c.drawPath(path, glyphStroke);
                    }
                    c.restore();
                    break;
                case LYRIC:
                    // v580: 同上——白色模式下未激活的「词」原
                    // 先写死白色看不见，激活的浅蓝也太淡；
                    // 白色模式未激活用墨色、激活用深蓝。
                    textPaint.setColor(active
                            ? (lpWhite()
                                    ? Color.rgb(37, 99, 235)
                                    : Color.rgb(147, 197, 253))
                            : (lpWhite()
                                    ? Color.rgb(15, 23, 42)
                                    : Color.WHITE));
                    textPaint.setTextSize(11f);
                    android.graphics.Paint.FontMetrics fm =
                            textPaint.getFontMetrics();
                    float ty = 12f - (fm.ascent + fm.descent)
                            / 2f;
                    c.drawText("词", 12f, ty, textPaint);
                    break;
                default:
                    break;
            }
            c.restore();
        }

        private void tri(float x1, float y1, float x2,
                float y2, float x3, float y3) {
            path.moveTo(x1, y1);
            path.lineTo(x2, y2);
            path.lineTo(x3, y3);
            path.close();
        }
    }

    private View buildLightMusicCard() {
        android.widget.LinearLayout card =
                new android.widget.LinearLayout(app);
        card.setOrientation(
                android.widget.LinearLayout.VERTICAL);
        GradientDrawable bg = new GradientDrawable();
        // v572: 白色模式下音乐卡换浅底细线。
        if (lpWhite()) {
            bg.setColor(Color.argb(245, 248, 250, 252));
            bg.setStroke(dp(1), Color.rgb(226, 232, 240));
        } else {
            bg.setColor(Color.argb(242, 30, 36, 52));
        }
        bg.setCornerRadius(dp(14));
        card.setBackground(bg);
        card.setPadding(dp(10), dp(10), dp(10), dp(8));
        android.widget.LinearLayout.LayoutParams cardLp =
                new android.widget.LinearLayout.LayoutParams(
                        android.widget.LinearLayout.LayoutParams
                                .MATCH_PARENT,
                        android.widget.LinearLayout.LayoutParams
                                .WRAP_CONTENT);
        cardLp.bottomMargin = dp(8);
        card.setLayoutParams(cardLp);
        android.widget.LinearLayout top =
                new android.widget.LinearLayout(app);
        top.setOrientation(
                android.widget.LinearLayout.HORIZONTAL);
        top.setGravity(Gravity.CENTER_VERTICAL);
        android.widget.ImageView art =
                new android.widget.ImageView(app);
        android.graphics.Bitmap artBmp = lightMusicArt != null
                ? lightMusicArt
                : (lightMusicData != null
                        ? lightMusicData.largeIcon : null);
        if (artBmp != null) {
            art.setImageBitmap(artBmp);
            art.setScaleType(
                    android.widget.ImageView.ScaleType
                            .CENTER_CROP);
        } else {
            android.graphics.drawable.Drawable dr =
                    lightMusicData != null
                            ? lightAppIcon(lightMusicData.pkg,
                                    lightMusicData)
                            : null;
            if (dr != null) {
                art.setImageDrawable(dr);
            }
        }
        android.widget.LinearLayout.LayoutParams artLp =
                new android.widget.LinearLayout.LayoutParams(
                        dp(44), dp(44));
        art.setLayoutParams(artLp);
        top.addView(art);
        android.widget.LinearLayout mid =
                new android.widget.LinearLayout(app);
        mid.setOrientation(android.widget.LinearLayout.VERTICAL);
        android.widget.LinearLayout.LayoutParams midLp =
                new android.widget.LinearLayout.LayoutParams(
                        0, android.widget.LinearLayout.LayoutParams
                                .WRAP_CONTENT, 1f);
        midLp.leftMargin = dp(10);
        mid.setLayoutParams(midLp);
        android.widget.TextView title =
                new android.widget.TextView(app);
        title.setText(lightMusicTitle != null
                ? lightMusicTitle : "");
        title.setTextSize(13.5f);
        title.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        title.setTextColor(lpWhite()
                ? Color.rgb(15, 23, 42) : Color.WHITE);
        title.setSingleLine(true);
        applyLightScroll(title);
        mid.addView(title, new android.widget.LinearLayout
                .LayoutParams(
                        android.widget.LinearLayout.LayoutParams
                                .MATCH_PARENT,
                        android.widget.LinearLayout.LayoutParams
                                .WRAP_CONTENT));
        android.widget.TextView artist =
                new android.widget.TextView(app);
        artist.setText(lightMusicArtist != null
                ? lightMusicArtist : "");
        artist.setTextSize(11.5f);
        artist.setTextColor(lpTextC());
        artist.setSingleLine(true);
        applyLightScroll(artist);
        mid.addView(artist, new android.widget.LinearLayout
                .LayoutParams(
                        android.widget.LinearLayout.LayoutParams
                                .MATCH_PARENT,
                        android.widget.LinearLayout.LayoutParams
                                .WRAP_CONTENT));
        lightMusicLyricView = new android.widget.TextView(app);
        lightMusicLyricView.setText(lightMusicLyric != null
                ? lightMusicLyric : "");
        lightMusicLyricView.setTextSize(11.5f);
        lightMusicLyricView.setTextColor(lpWhite()
                ? Color.rgb(71, 85, 105)
                : Color.argb(200, 255, 255, 255));
        lightMusicLyricView.setSingleLine(true);
        // v568: 歌词行同经典钉住胶囊的歌词口径——恒滚动
        // （不跟 scroll 开关，那个开关管的是标题/正文）。
        lightMusicLyricView.setEllipsize(
                android.text.TextUtils.TruncateAt.MARQUEE);
        lightMusicLyricView.setMarqueeRepeatLimit(-1);
        lightMusicLyricView.setSelected(true);
        lightMusicLyricView.setVisibility(
                lightMusicLyric != null
                        && !lightMusicLyric.isEmpty()
                        ? View.VISIBLE : View.GONE);
        mid.addView(lightMusicLyricView);
        top.addView(mid);
        // v565: 右上角不再写「音乐 · 置顶」——卡片自己
        // 置顶就够了（wei 点名去掉）。
        card.addView(top);
        // 进度行（有真实时长才显示，不编造）。
        if (lightMusicDurMs > 0) {
            android.widget.LinearLayout prog =
                    new android.widget.LinearLayout(app);
            prog.setOrientation(
                    android.widget.LinearLayout.HORIZONTAL);
            prog.setGravity(Gravity.CENTER_VERTICAL);
            android.widget.LinearLayout.LayoutParams progLp =
                    new android.widget.LinearLayout.LayoutParams(
                            android.widget.LinearLayout.LayoutParams
                                    .MATCH_PARENT,
                            android.widget.LinearLayout.LayoutParams
                                    .WRAP_CONTENT);
            progLp.topMargin = dp(6);
            prog.setLayoutParams(progLp);
            lightMusicBar = new android.widget.ProgressBar(app,
                    null,
                    android.R.attr.progressBarStyleHorizontal);
            lightMusicBar.setMax((int) Math.min(
                    lightMusicDurMs, Integer.MAX_VALUE));
            lightMusicBar.setProgress((int) Math.min(
                    Math.max(lightMusicPosMs, 0),
                    Integer.MAX_VALUE));
            android.widget.LinearLayout.LayoutParams barLp =
                    new android.widget.LinearLayout.LayoutParams(
                            0, android.widget.LinearLayout
                                    .LayoutParams.WRAP_CONTENT,
                            1f);
            lightMusicBar.setLayoutParams(barLp);
            prog.addView(lightMusicBar);
            lightMusicTime = new android.widget.TextView(app);
            lightMusicTime.setTextSize(10.5f);
            lightMusicTime.setTextColor(lpTextC());
            android.widget.LinearLayout.LayoutParams timeLp =
                    new android.widget.LinearLayout.LayoutParams(
                            android.widget.LinearLayout.LayoutParams
                                    .WRAP_CONTENT,
                            android.widget.LinearLayout.LayoutParams
                                    .WRAP_CONTENT);
            timeLp.leftMargin = dp(8);
            lightMusicTime.setLayoutParams(timeLp);
            prog.addView(lightMusicTime);
            card.addView(prog);
        }
        // 控制行（v565 重做、v567 去毛玻璃圆底）：纯字形
        // 键一排——收藏
        // 在左头、词在右头（同系统音乐卡的摆法），中间
        // 上一首/播放暂停/下一首，播放键大一圈。v566:
        // 面板加宽到 292dp、侧键 40dp / 播放键 52dp /
        // 间距 8dp——v565 的 256dp 固定宽塞不进 224dp
        // 卡内宽，下一首被裁一半、词键整个出界（wei
        // 截图指出），这版五键完整不裁。
        android.widget.LinearLayout ctrls =
                new android.widget.LinearLayout(app);
        ctrls.setOrientation(
                android.widget.LinearLayout.HORIZONTAL);
        ctrls.setGravity(Gravity.CENTER_VERTICAL);
        android.widget.LinearLayout.LayoutParams ctrLp =
                new android.widget.LinearLayout.LayoutParams(
                        android.widget.LinearLayout.LayoutParams
                                .MATCH_PARENT,
                        android.widget.LinearLayout.LayoutParams
                                .WRAP_CONTENT);
        ctrLp.topMargin = dp(4);
        ctrls.setLayoutParams(ctrLp);
        final int tint = lightArtTint(artBmp);
        lightMusicFavBtn = buildLightMusicBtn(
                LightMusicBtn.FAV, tint, dp(40));
        lightMusicFavBtn.setFavorite(lightMusicFavorite);
        lightMusicFavBtn.setOnClickListener(
                new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        onLightMusicFavTap();
                    }
                });
        ctrls.addView(lightMusicFavBtn);
        ctrls.addView(lightCtrlSpacer());
        LightMusicBtn prevBtn = buildLightMusicBtn(
                LightMusicBtn.PREV, tint, dp(40));
        prevBtn.setOnClickListener(
                new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        FileLog.i("Light music action prev");
                        sendMusicControl("prev", -1);
                    }
                });
        ctrls.addView(prevBtn);
        lightMusicPlayBtn = buildLightMusicBtn(
                LightMusicBtn.TOGGLE, tint, dp(52));
        android.widget.LinearLayout.LayoutParams playLp =
                (android.widget.LinearLayout.LayoutParams)
                        lightMusicPlayBtn.getLayoutParams();
        playLp.leftMargin = dp(8);
        playLp.rightMargin = dp(8);
        lightMusicPlayBtn.setLayoutParams(playLp);
        lightMusicPlayBtn.setPlaying(lightMusicPlaying);
        lightMusicPlayBtn.setOnClickListener(
                new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        FileLog.i("Light music action toggle");
                        sendMusicControl("toggle", -1);
                    }
                });
        ctrls.addView(lightMusicPlayBtn);
        LightMusicBtn nextBtn = buildLightMusicBtn(
                LightMusicBtn.NEXT, tint, dp(40));
        nextBtn.setOnClickListener(
                new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        FileLog.i("Light music action next");
                        sendMusicControl("next", -1);
                    }
                });
        ctrls.addView(nextBtn);
        ctrls.addView(lightCtrlSpacer());
        lightMusicLyricBtn = buildLightMusicBtn(
                LightMusicBtn.LYRIC, tint, dp(40));
        lightMusicLyricBtn.setActive(lightLyricFollow);
        lightMusicLyricBtn.setOnClickListener(
                new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        onLightLyricToggle();
                    }
                });
        ctrls.addView(lightMusicLyricBtn);
        card.addView(ctrls);
        updateLightMusicProgressViews();
        return card;
    }

    private LightMusicBtn buildLightMusicBtn(int kind,
            int tint, int sizeDp) {
        LightMusicBtn b = new LightMusicBtn(kind);
        b.setTint(tint);
        b.setLayoutParams(
                new android.widget.LinearLayout.LayoutParams(
                        sizeDp, sizeDp));
        return b;
    }

    private View lightCtrlSpacer() {
        View s = new View(app);
        s.setLayoutParams(
                new android.widget.LinearLayout.LayoutParams(
                        0, 1, 1f));
        return s;
    }

    /** v565: 收藏键——走经典音乐卡同款的会话通道
     * （sendMusicControl 的 favorite 命令，NS 侧用
     * setRating 真收藏）；本地先翻转爱心兜底，会话进度
     * tick 带回真值时再对齐。会话没暴露收藏状态
     * （hasFavorite=false）时打 noAction 日志，方便定位
     * 是哪个播放器不支持。 */
    private void onLightMusicFavTap() {
        FileLog.i("Light music fav tap hasFav="
                + lightMusicHasFavorite);
        lightMusicFavorite = !lightMusicFavorite;
        if (lightMusicFavBtn != null) {
            lightMusicFavBtn.setFavorite(lightMusicFavorite);
        }
        sendMusicControl("favorite", -1);
        if (!lightMusicHasFavorite) {
            FileLog.i("Light music fav noAction");
        }
    }

    /** v565: 「词」键——开/关歌词跟随显示。开后每行新
     * 歌词按到达药丸同款在边缘亮一次（flashLightLyricPill，
     * 数据走现有 ACTION_LYRIC 通道）；关即停。 */
    /** v566: 轻气泡歌词跟随自带监听开关——直接命令 NS
     * 的歌词监听（ACTION_LYRIC_LISTEN），不再借经典路径
     * syncLyricListen 的状态：它只认经典胶囊钉住/开卡，
     * 轻气泡下没人保活，v565 实测播放到一半监听掉了、
     * 歌词就再也不亮。开时顺带把 NS 的 musicKey 对齐
     * 到当前音乐 key（播放器重发通知换 key 也不怕）。 */
    private void sendLightLyricListen(boolean on, String key) {
        if (app == null) {
            return;
        }
        if (on && key == null) {
            return;
        }
        android.content.Intent intent = new android.content.Intent(
                NotificationService.ACTION_LYRIC_LISTEN);
        if (key != null) {
            intent.putExtra("key", key);
        }
        intent.putExtra("listen", on);
        try {
            app.sendBroadcast(intent);
        } catch (Throwable ignored) {
        }
        FileLog.i("Light lyric listen -> " + on);
    }

    private void onLightLyricToggle() {
        lightLyricFollow = !lightLyricFollow;
        FileLog.i(lightLyricFollow ? "Light lyric on"
                : "Light lyric off");
        sendLightLyricListen(lightLyricFollow,
                lightMusicData != null
                        ? lightMusicData.key : null);
        if (!lightLyricFollow) {
            // v569: 词关了，歌词下面的副药丸一并收。
            lightSubEndRunnable.run();
            // v611: 词关了——挂着的 AI 摘要可以闪了。
            maybeFlashLightSummary();
        }
        if (lightMusicLyricBtn != null) {
            lightMusicLyricBtn.setActive(lightLyricFollow);
        }
        if (lightLyricFollow && lightMusicLyric != null
                && !lightMusicLyric.isEmpty()) {
            flashLightLyricPill(lightMusicLyric);
        }
    }

    /** v565: 歌词行到达展示——复用小签药丸：展开成
     * 「音乐图标 + 当前歌词行」，跟着呼吸闪约 3.5 个周期
     * 后落回小点；新行直接换内容并重置计时（同到达药丸
     * 「永远只显示最新」的口径）。 */
    private void flashLightLyricPill(String line) {
        if (line == null || line.isEmpty()) {
            return;
        }
        // v577: 通知正在主药丸上闪时歌词不抢——等它闪完
        // 收纳，收尾逻辑会把当前歌词行再亮出来。
        if (lightFlashKey != null) {
            return;
        }
        if (!lightDotAdded) {
            refreshLightDot();
        }
        if (lightPill == null || lightDotTab == null
                || !lightDotAdded) {
            return;
        }
        android.graphics.drawable.Drawable icon =
                lightMusicData != null
                        ? lightAppIcon(
                                lightDisplayPkg(lightMusicData),
                                lightMusicData)
                        : null;
        if (icon != null) {
            lightPillIcon.setImageDrawable(icon);
            lightPillIcon.setVisibility(View.VISIBLE);
        } else {
            lightPillIcon.setVisibility(View.GONE);
        }
        setLightPillLyricMode(true);
        lightPillLabel.setText(line);
        lightPill.setVisibility(View.VISIBLE);
        lightDotTab.setVisibility(View.GONE);
        updateLightBadge();
        lightPulsing = true;
        handler.removeCallbacks(lightPulseEndRunnable);
        handler.postDelayed(lightPulseEndRunnable, 11200);
        FileLog.i("Light lyric line " + line);
    }

    /** v611: AI 摘要药丸（wei 定：摘要完成就闪、一
     * 直闪到打开面板）。内容 =「✦ AI 摘要：」+ 摘要
     * 文，整行模式（同歌词药丸）可折行。续闪由收尾
     * 逻辑 lightPulseEndRunnable 驱动。 */
    private void flashLightSummaryPill() {
        if (lightAiFlashText == null) {
            return;
        }
        if (!lightDotAdded) {
            refreshLightDot();
        }
        if (lightPill == null || lightDotTab == null
                || !lightDotAdded) {
            return;
        }
        lightPillIcon.setVisibility(View.GONE);
        setLightPillLyricMode(true);
        lightPillLabel.setText(
                "✦ AI 摘要：" + lightAiFlashText);
        lightPill.setVisibility(View.VISIBLE);
        lightDotTab.setVisibility(View.GONE);
        updateLightBadge();
        lightPulsing = true;
        lightAiPillShowing = true;
        handler.removeCallbacks(lightPulseEndRunnable);
        handler.postDelayed(lightPulseEndRunnable, 11200);
    }

    /** v611: 摘要待闪的统一入口——占位/时机不对就
     * 先挂着，条件齐了再亮：面板开着不闪、到达闪与
     * 歌词闪占着药丸时不抢、放歌跟词时不显示（wei
     * 定歌词优先）。 */
    private void maybeFlashLightSummary() {
        if (lightAiFlashText == null || lightPanelAdded
                || lightFlashKey != null || lightPulsing) {
            return;
        }
        if (lightMusicData != null && lightLyricFollow) {
            return;
        }
        flashLightSummaryPill();
    }

    /** v611: 停掉摘要循环闪（打开面板/关 AI 时）。 */
    private void stopLightSummaryFlash() {
        if (lightAiFlashText == null) {
            return;
        }
        lightAiFlashText = null;
        if (lightAiPillShowing) {
            lightAiPillShowing = false;
            handler.removeCallbacks(lightPulseEndRunnable);
            lightPulsing = false;
            if (lightPill != null) {
                lightPill.setVisibility(View.GONE);
            }
            if (lightDotTab != null && !lightSubShowing) {
                lightDotTab.setVisibility(View.VISIBLE);
            }
        }
    }

    /** v566: 药丸文字模式——到达药丸（应用名）保持紧凑
     * 单行省略；歌词药丸要整行显示（wei 定「显示完整
     * 歌词、不用省略」）：放开宽度到屏幕宽减边距、允许
     * 折到第二行、不省略。两种闪法共用一个 label，每次
     * 亮之前先切模式。 */
    private void setLightPillLyricMode(boolean lyric) {
        if (lightPillLabel == null || app == null) {
            return;
        }
        if (lyric) {
            lightPillLabel.setSingleLine(false);
            lightPillLabel.setMaxLines(2);
            lightPillLabel.setEllipsize(null);
            int max = app.getResources().getDisplayMetrics()
                    .widthPixels - dp(64);
            lightPillLabel.setMaxWidth(
                    Math.max(dp(110), max));
        } else {
            lightPillLabel.setSingleLine(true);
            lightPillLabel.setMaxLines(1);
            lightPillLabel.setEllipsize(
                    android.text.TextUtils.TruncateAt.END);
            lightPillLabel.setMaxWidth(dp(110));
        }
    }

    private static String lightFormatMs(long ms) {
        if (ms < 0) {
            ms = 0;
        }
        long s = ms / 1000;
        return (s / 60) + ":" + String.format(
                java.util.Locale.US, "%02d", s % 60);
    }

    private void updateLightMusicProgressViews() {
        if (lightMusicBar != null && lightMusicDurMs > 0) {
            lightMusicBar.setMax((int) Math.min(lightMusicDurMs,
                    Integer.MAX_VALUE));
            lightMusicBar.setProgress((int) Math.min(
                    Math.max(lightMusicPosMs, 0),
                    Integer.MAX_VALUE));
        }
        if (lightMusicTime != null && lightMusicDurMs > 0) {
            lightMusicTime.setText(
                    lightFormatMs(lightMusicPosMs) + " / "
                            + lightFormatMs(lightMusicDurMs));
        }
        if (lightMusicPlayBtn != null) {
            lightMusicPlayBtn.setPlaying(lightMusicPlaying);
        }
        if (lightMusicFavBtn != null) {
            lightMusicFavBtn.setFavorite(lightMusicFavorite);
        }
        if (lightMusicLyricBtn != null) {
            lightMusicLyricBtn.setActive(lightLyricFollow);
        }
    }

    /** v564: 音乐到达（popCapsule 轻气泡分支）——不弹胶囊，
     * 只更新轻气泡音乐状态；系统音乐通知由钩子侧藏掉
     * （NotifCollectionHook 的只藏不撤），音乐从此只活在
     * 面板音乐卡里。 */
    private void lightMusicIn(NotifData d) {
        if (d == null) {
            return;
        }
        boolean newSong = lightMusicData == null
                || !d.key.equals(lightMusicData.key)
                || (lightMusicTitle == null
                        ? d.title != null
                        : !lightMusicTitle.equals(d.title));
        lightMusicData = d;
        if (d.title != null && !d.title.isEmpty()) {
            lightMusicTitle = d.title;
        }
        if (d.text != null && !d.text.isEmpty()) {
            lightMusicArtist = d.text;
        }
        if (d.largeIcon != null) {
            lightMusicArt = d.largeIcon;
        }
        if (d.lyrics != null && !d.lyrics.isEmpty()) {
            lightMusicLyric = d.lyrics;
        }
        if (newSong) {
            lightMusicPosMs = 0;
            // v565: 新歌的收藏状态重新等 tick 报真值。
            lightMusicFavorite = false;
            lightMusicHasFavorite = false;
        }
        FileLog.i("Light music in pkg=" + d.pkg
                + " title=" + lightMusicTitle);
        // v566: 歌词跟随开着时，每次音乐通知进来都把
        // NS 监听按最新 key 重新武装一次——播放器中途
        // 重发通知/换 key，监听也能自己接回来。
        if (lightLyricFollow) {
            sendLightLyricListen(true, d.key);
        }
        refreshLightDot();
        if (lightPanelAdded) {
            rebuildLightPanelRows();
        }
        writeLightMirror();
    }

    /** v564: 音乐通知被移除（NS 转发 ACTION_MUSIC_GONE）——
     * 音乐卡撤下、小签按栈状态决定去留。 */
    public void onLightMusicGone(final String key) {
        if (key == null) {
            return;
        }
        handler.post(new Runnable() {
            @Override
            public void run() {
                if (lightMusicData != null
                        && key.equals(lightMusicData.key)) {
                    FileLog.i("Light music out key=" + key);
                    lightMusicData = null;
                    lightMusicArt = null;
                    lightMusicPlaying = false;
                    lightMusicPosMs = 0;
                    lightMusicDurMs = 0;
                    writeLightMirror();
                    // v565: 音乐没了，收藏与歌词跟随一并收。
                    lightMusicFavorite = false;
                    lightMusicHasFavorite = false;
                    if (lightLyricFollow) {
                        lightLyricFollow = false;
                        FileLog.i("Light lyric off");
                        sendLightLyricListen(false, key);
                    }
                    if (lightTabBg != null) {
                        lightTabBg.setColor(
                                FeatureFlags.lightDotColorInt());
                    }
                    refreshLightDot();
                    if (lightPanelAdded) {
                        rebuildLightPanelRows();
                    }
                    // v611: 音乐停了——挂着的 AI 摘要
                    // 可以接着闪了。
                    maybeFlashLightSummary();
                }
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
        // v565: 轻气泡里回看改名「时光机」、只从收纳面板
        // 进入，经典回看边缘签不许出现（wei 截图里它还挂
        // 在左边缘带角标）。边缘只留收纳小签一个东西；
        // 经典模式下回看签照旧，不受影响。
        if (FeatureFlags.lightMode) {
            if (recallWindowAdded) {
                FileLog.i("Light recall edge off");
            }
            hideRecallHandle();
            return;
        }
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

    // ---- v546: 长按菜单（快捷回复） ----
    // CapsuleView 的长按计时（400ms）触发后走这里。菜单项按
    // 通知实际能力出现：有 RemoteInput 才有「回复」，有已读
    // 动作才有「标为已读」，都没有就用「打开应用回复」兜底；
    // 「稍后提醒 / 收进回看（回看开着时）/ 这个应用不再弹」
    // 是自有能力。注：普通胶囊自 v193 起没有自动消散计时
    // （常驻到用户处理），所以菜单开着期间不存在「计时到点
    // 被消散」的问题——胶囊只会被用户动作或系统移除带走，
    // 那两条路都会顺手把菜单收掉（dissipateOne /
    // onNotificationRemoved 里有联动）。

    /** Hook 侧抽到动作时暂存（SystemUI 进程内，静态）。 */
    public static void stashActions(String key, String pkg,
            android.app.Notification n) {
        if (key == null || n == null) {
            return;
        }
        NotifActions a = NotifActions.extract(n, pkg);
        synchronized (ACTIONS_BY_KEY) {
            if (a == null) {
                ACTIONS_BY_KEY.remove(key);
            } else {
                if (ACTIONS_BY_KEY.size() > 300) {
                    ACTIONS_BY_KEY.clear();
                }
                ACTIONS_BY_KEY.put(key, a);
            }
        }
    }

    private static NotifActions actionsFor(String key) {
        if (key == null) {
            return null;
        }
        synchronized (ACTIONS_BY_KEY) {
            return ACTIONS_BY_KEY.get(key);
        }
    }

    /** CapsuleView 长按触发点。资格细核：开关开 + 普通胶囊
     * （音乐/下载/实况/导航/公交/来电不弹菜单）。 */
    public void onCapsuleLongPress(final CapsuleView cap) {
        if (cap == null || app == null || wm == null) {
            return;
        }
        if (!FeatureFlags.longPressMenu) {
            return;
        }
        final NotifData d = cap.getData();
        if (d == null || d.isMusic || d.isDownload || d.isLive
                || d.isNavi || d.isBus || d.isCall) {
            return;
        }
        handler.post(new Runnable() {
            @Override
            public void run() {
                showCapsuleMenu(cap, d);
            }
        });
    }

    /** 菜单动作（回复发送/标已读/稍后/收进回看/不再弹/
     * 打开应用）执行后的「消费」这条通知：走 dissipateOne
     * 老路摘掉在屏胶囊（连带清它的通知动作暂存）。 */
    private void menuConsume(NotifData d) {
        // v564: 轻气泡面板里长按出的菜单，消费的是收纳栈
        // 里的条目（不在屏上，dissipateOne 摘不到）——在栈
        // 里就从栈里摘（处理完即走，不进时光机：时光机只
        // 收「打开/删除/清除」，wei 口径）。不在栈里（经典
        // 胶囊）才走老路。
        if (FeatureFlags.lightMode && lightRemove(d)) {
            return;
        }
        dissipateOne(d);
    }

    /** v558: 菜单「这个应用以后收纳」点完后，当前这条按
     * 收纳待遇落地：在屏上的走 pileIntoFan 落点收进扇子，
     * 再把可见胶囊摘掉。不走 menuConsume——这条是要
     * 「收起来」，不是「处理掉」。 */
    private void collectCurrentIntoFan(NotifData d) {
        if (d == null) {
            return;
        }
        // v564: 轻气泡下没有扇子落点——这条本来就在收纳栈
        // 里（面板菜单触发），「以后收纳」对它就是保持收纳。
        if (FeatureFlags.lightMode && isOrdinaryForLight(d)) {
            lightCollect(d);
            return;
        }
        pileIntoFan(d);
        if (d.key != null) {
            removeNotifByKey(d.key);
        }
        holdWindowHeight = true;
        rebuildDock();
    }

    /** v570: 菜单条目构建（经典浮层菜单与轻气泡面板内嵌
     * 菜单共用一份）。条目口径：回复/打开应用回复/标为已读
     * 按通知自带能力出现；稍后提醒固定有；放入时光机——经
     * 典看时光机（原回看）开关、轻气泡恒有；这个应用以后
     * 收纳——两边都出现（v595 起轻气泡恢复：收纳档与跟
     * 随档待遇有别，不是死按钮）；这个应用不再弹固定有。条目点击里调
     * 的 dismissMenu() 同时管浮层菜单与内嵌菜单的收起。 */
    private void buildMenuItems(
            android.widget.LinearLayout panel, final NotifData d,
            final NotifActions acts) {
        // 1) 回复（有 RemoteInput 才有）/ 打开应用回复（兜底）。
        if (acts != null && acts.hasReply()) {
            panel.addView(buildMenuRow("↩", "回复", "不用打开应用",
                    new View.OnClickListener() {
                        @Override
                        public void onClick(View v) {
                            toggleMenuReplyBox();
                        }
                    }));
            buildMenuReplyBox(panel, d, acts);
        } else if (d.contentIntent != null) {
            panel.addView(buildMenuRow("➤", "打开应用回复",
                    "该应用不支持直接回复",
                    new View.OnClickListener() {
                        @Override
                        public void onClick(View v) {
                            dismissMenu();
                            openAppForReply(d);
                        }
                    }));
        }
        // 2) 标为已读（通知自带已读动作才有）。
        if (acts != null && acts.hasMarkRead()) {
            panel.addView(buildMenuRow("✓", "标为已读", null,
                    new View.OnClickListener() {
                        @Override
                        public void onClick(View v) {
                            dismissMenu();
                            try {
                                acts.markReadIntent.send();
                                FileLog.i("MarkRead sent pkg="
                                        + d.pkg);
                            } catch (Throwable t) {
                                FileLog.i("MarkRead fail pkg="
                                        + d.pkg + " err=" + t);
                            }
                            menuConsume(d);
                        }
                    }));
        }
        // 3) 稍后提醒（自有能力，固定有）。
        panel.addView(buildMenuRow("◷", "稍后提醒", "10 分钟后",
                new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        dismissMenu();
                        snoozeNotif(d);
                        menuConsume(d);
                    }
                }));
        // 4) 放入时光机（v570 改名，原「收进回看」；经典看
        // 时光机开关、关着就不出现保持菜单干净；轻气泡里
        // 时光机是本体，恒有）。
        if (FeatureFlags.lightMode || FeatureFlags.recallCapsule) {
            panel.addView(buildMenuRow("↩", "放入时光机", null,
                    new View.OnClickListener() {
                        @Override
                        public void onClick(View v) {
                            dismissMenu();
                            pushRecall(d);
                            menuConsume(d);
                        }
                    }));
        }
        // 5) v558: 这个应用以后收纳（原「只贴边」待遇，
        // 改名后统一叫收纳）。当前有效规则还是跟随才
        // 显示——已是收纳/永不弹的（含走了「全部应用」
        // 默认条目的）不显示，避免死按钮。落盘与不再弹
        // 同路：广播给 App 进程走 AppRules 单一数据源。
        // v559: 有效值是 POP（显式弹出例外，绕过默认
        // 收纳）时它现在也是照常弹的，同样显示这一行。
        int effRule = AppRules.modeFor(d.pkg);
        // v595: wei 改规矩——轻气泡里「收纳」档的新通知
        // 直接进面板、只提示角标，跟随的照旧闪药丸，两
        // 档待遇有别，这一行在轻气泡下恢复显示（v570 的
        // 移除作废）。
        if (effRule == AppRules.DEFAULT
                || effRule == AppRules.POP) {
            panel.addView(buildMenuRow("⇥", "这个应用以后收纳",
                    null, new View.OnClickListener() {
                        @Override
                        public void onClick(View v) {
                            dismissMenu();
                            try {
                                android.content.Intent ri =
                                        new android.content.Intent(
                                                NotificationService
                                                        .ACTION_SET_APP_RULE);
                                ri.setPackage(
                                        "com.chenan.flymebobo");
                                ri.putExtra("pkg", d.pkg);
                                ri.putExtra("mode",
                                        AppRules.VALUE_EDGE);
                                app.sendBroadcast(ri);
                                FileLog.i("QuickMenu setEdge pkg="
                                        + d.pkg);
                            } catch (Throwable ignored) {
                            }
                            collectCurrentIntoFan(d);
                        }
                    }));
        }
        // 6) 这个应用不再弹（走 AppRules 单一数据源：广播
        // 给 App 进程落盘 + 回广播刷新 SystemUI 的规则表）。
        panel.addView(buildMenuRow("✕", "这个应用不再弹", null,
                new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        dismissMenu();
                        try {
                            android.content.Intent ri =
                                    new android.content.Intent(
                                            NotificationService
                                                    .ACTION_SET_APP_RULE);
                            ri.setPackage("com.chenan.flymebobo");
                            ri.putExtra("pkg", d.pkg);
                            ri.putExtra("mode",
                                    AppRules.VALUE_NEVER);
                            app.sendBroadcast(ri);
                            FileLog.i("QuickMenu never pkg="
                                    + d.pkg);
                        } catch (Throwable ignored) {
                        }
                        menuConsume(d);
                    }
                }));
    }

    private void showCapsuleMenu(CapsuleView cap, final NotifData d) {
        if (cap == null) {
            return;
        }
        int[] loc = new int[2];
        cap.getLocationOnScreen(loc);
        int capW = cap.getWidth() > 0 ? cap.getWidth() : dp(238);
        showCapsuleMenuAt(loc[0], loc[1], capW, cap.getHeight(), d);
    }

    /** 锚点版菜单入口——拿锚点（胶囊）的屏幕坐标/尺寸
     * 定位，菜单本体、定位、入场动画全共用一份
     * （showCapsuleMenu 也收敛到这里）。调用前要先把锚点
     * 坐标量好：本方法一进来就 closeOverlays。 */
    private void showCapsuleMenuAt(int anchorX, int anchorY,
            int anchorW, int anchorH, final NotifData d) {
        // v547: 全局只许一个浮层——先把已开的（含正淡出中的
        // 菜单）立即拆干净再开新的，杜绝叠层。
        closeOverlays();
        if (app == null || wm == null || d == null) {
            return;
        }
        final NotifActions acts = actionsFor(d.key);
        menuData = d;
        try {
            FrameLayout rootL = new FrameLayout(app);
            rootL.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    dismissMenu();
                }
            });
            final android.widget.LinearLayout panel =
                    new android.widget.LinearLayout(app);
            panel.setOrientation(
                    android.widget.LinearLayout.VERTICAL);
            panel.setPadding(dp(8), dp(8), dp(8), dp(8));
            GradientDrawable pbg = new GradientDrawable();
            pbg.setColor(Color.argb(245, 24, 28, 40));
            pbg.setCornerRadius(dp(20));
            panel.setBackground(pbg);
            // 面板内的点击不穿透到根（根一点就收起）。
            panel.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                }
            });
            // v547: 记住锚点（胶囊/锚点视图的屏幕坐标与
            // 尺寸）。面板先藏，addView 后由 placeMenuPanel
            // 按锚点算准位置再亮出来（下方放不下整块就翻
            // 到锚点上方）。
            int capW = anchorW > 0 ? anchorW : dp(238);
            menuAnchorX = anchorX;
            menuAnchorY = anchorY;
            menuAnchorW = capW;
            menuAnchorH = anchorH;
            FrameLayout.LayoutParams plp =
                    new FrameLayout.LayoutParams(
                            capW, FrameLayout.LayoutParams.WRAP_CONTENT,
                            Gravity.TOP | Gravity.START);
            plp.leftMargin = Math.max(dp(4), anchorX);
            plp.topMargin = anchorY + anchorH + dp(6);
            panel.setVisibility(View.INVISIBLE);
            rootL.addView(panel, plp);
            menuPanel = panel;

            buildMenuItems(panel, d, acts);

            menuRoot = rootL;
            // v547: 窗口类型从 TYPE_APPLICATION_OVERLAY 换成
            // TYPE_SYSTEM_ERROR——与胶囊窗口（dock/pile）同型。
            // 实测（wei 截图 4441）：OVERLAY 层在 Flyme 上被
            // SYSTEM_ERROR 的胶囊窗口压住，菜单上半截被盖。
            // 同型后菜单后加，在胶囊之上。窗口保持可聚焦
            // （不加 NOT_FOCUSABLE），回复输入框才能拿焦点
            // 弹输入法；根是全屏透明层，点面板外落在根上
            // 即收起。
            menuParams = new WindowManager.LayoutParams(
                    WindowManager.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.TYPE_SYSTEM_ERROR,
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                            | WindowManager.LayoutParams
                                    .FLAG_LAYOUT_NO_LIMITS
                            | WindowManager.LayoutParams
                                    .FLAG_SHOW_WHEN_LOCKED,
                    PixelFormat.TRANSLUCENT);
            menuParams.gravity = Gravity.TOP | Gravity.START;
            if (android.os.Build.VERSION.SDK_INT >= 30) {
                menuParams.layoutInDisplayCutoutMode = WindowManager
                        .LayoutParams
                        .LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS;
                try {
                    menuParams.setFitInsetsTypes(0);
                } catch (Throwable ignored) {
                }
            }
            menuParams.setTitle("flymebobo-menu");
            wm.addView(menuRoot, menuParams);
            menuWindowAdded = true;
            FileLog.i("Overlay add name=menu");
            // v547: 面板定位 + 入场动画（贴胶囊下方；放不下
            // 整块就翻到胶囊上方；整块必须可见）。旧版在这
            // 里用 translation 回拉，面板顶边仍压在下方胶囊
            // 的位置上，再叠加层级被压，就是截图 4441 里
            // 菜单上半截被胶囊盖住的样子。
            panel.post(new Runnable() {
                @Override
                public void run() {
                    placeMenuPanel(panel, true);
                }
            });
            FileLog.i("QuickMenu show pkg=" + d.pkg
                    + " reply=" + (acts != null && acts.hasReply())
                    + " markRead="
                    + (acts != null && acts.hasMarkRead()));
        } catch (Throwable t) {
            FileLog.i("QuickMenu show fail err=" + t);
            menuData = null;
        }
    }

    /** v547: 菜单面板定位。优先贴在被长按胶囊下方（面板
     * 顶边 = 胶囊底边 + 6dp）；下方放不下整块面板，就翻到
     * 胶囊上方（面板底边 = 胶囊顶边 - 6dp）；两边都紧张时
     * 钳进屏内——任何情况整块面板必须完整可见，不再压在
     * 别的胶囊身上。animate=true 时顺带播入场动画：以胶囊
     * 那条边为轴轻微放大 + 淡入（0.92→1 / 200ms，模块里
     * 胶囊入场同款 DecelerateInterpolator）。 */
    private void placeMenuPanel(
            final android.widget.LinearLayout panel,
            final boolean animate) {
        if (panel == null || app == null) {
            return;
        }
        try {
            int pw = panel.getWidth();
            int ph = panel.getHeight();
            if (pw <= 0 || ph <= 0) {
                panel.setVisibility(View.VISIBLE);
                return;
            }
            android.util.DisplayMetrics dm =
                    app.getResources().getDisplayMetrics();
            int screenW = dm.widthPixels;
            int screenH = dm.heightPixels;
            int gap = dp(6);
            int edge = dp(8);
            int left = Math.max(edge,
                    Math.min(menuAnchorX, screenW - pw - edge));
            int belowTop = menuAnchorY + menuAnchorH + gap;
            int top;
            if (belowTop + ph <= screenH - edge) {
                top = belowTop;
                menuPlacedAbove = false;
            } else {
                int aboveTop = menuAnchorY - gap - ph;
                if (aboveTop >= edge) {
                    top = aboveTop;
                    menuPlacedAbove = true;
                } else {
                    // 上下都放不满：贴屏底钳制，整块优先。
                    top = Math.max(edge, screenH - edge - ph);
                    menuPlacedAbove = top < menuAnchorY;
                }
            }
            FrameLayout.LayoutParams lp =
                    (FrameLayout.LayoutParams)
                            panel.getLayoutParams();
            lp.leftMargin = left;
            lp.topMargin = top;
            panel.setLayoutParams(lp);
            // 放大轴心：贴着胶囊的那条边、横向对胶囊中心。
            float pivotX = menuAnchorX + menuAnchorW / 2f - left;
            panel.setPivotX(Math.max(0, Math.min(pw, pivotX)));
            panel.setPivotY(menuPlacedAbove ? ph : 0);
            panel.setVisibility(View.VISIBLE);
            if (animate) {
                panel.setAlpha(0f);
                panel.setScaleX(0.92f);
                panel.setScaleY(0.92f);
                panel.animate().alpha(1f).scaleX(1f).scaleY(1f)
                        .setDuration(200)
                        .setInterpolator(
                                new android.view.animation
                                        .DecelerateInterpolator())
                        .start();
            }
        } catch (Throwable ignored) {
            try {
                panel.setVisibility(View.VISIBLE);
            } catch (Throwable ignored2) {
            }
        }
    }

    /** 菜单行：符号 + 标题 + 右侧小字（照定稿预览）。 */
    private android.widget.LinearLayout buildMenuRow(String glyph,
            String label, String sub, View.OnClickListener onClick) {
        android.widget.LinearLayout row =
                new android.widget.LinearLayout(app);
        row.setOrientation(android.widget.LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(10), dp(10), dp(10), dp(10));
        // v548: 行宽 MATCH_PARENT——权重分配要有确定的总宽
        // 可分，否则行按内容宽（WRAP_CONTENT）量，label 会被
        // 长 sub 挤没（见下面 label 的注释）。
        row.setLayoutParams(new android.widget.LinearLayout
                .LayoutParams(
                        android.widget.LinearLayout.LayoutParams
                                .MATCH_PARENT,
                        android.widget.LinearLayout.LayoutParams
                                .WRAP_CONTENT));
        android.widget.TextView g = new android.widget.TextView(app);
        g.setText(glyph);
        g.setTextColor(lpMenuLight()
                ? Color.rgb(15, 23, 42) : Color.WHITE);
        g.setTextSize(15);
        g.setGravity(Gravity.CENTER);
        row.addView(g, new android.widget.LinearLayout.LayoutParams(
                dp(26), android.widget.LinearLayout.LayoutParams
                        .WRAP_CONTENT));
        android.widget.TextView t = new android.widget.TextView(app);
        t.setText(label);
        t.setTextColor(lpMenuLight()
                ? Color.rgb(15, 23, 42) : Color.WHITE);
        t.setTextSize(14);
        // v548: 必须 singleLine + ellipsize。原来 label 没设
        // singleLine：sub 先按期望宽占位（如「该应用不支持
        // 直接回复」10 个字），label+sub 期望宽超过面板内宽
        // 时，带权重的 label 在二次分配里被压到约一个字宽，
        // 中文逐字换行就竖成了一列（wei 截图 4443）。设了
        // 单行省略后，最坏情况是 label 尾部省略，绝不竖排。
        t.setSingleLine(true);
        t.setEllipsize(android.text.TextUtils.TruncateAt.END);
        android.widget.LinearLayout.LayoutParams tlp =
                new android.widget.LinearLayout.LayoutParams(
                        0, android.widget.LinearLayout.LayoutParams
                                .WRAP_CONTENT, 1f);
        tlp.leftMargin = dp(10);
        row.addView(t, tlp);
        if (sub != null) {
            android.widget.TextView s =
                    new android.widget.TextView(app);
            s.setText(sub);
            s.setTextColor(lpMenuLight()
                    ? Color.rgb(100, 116, 139)
                    : Color.argb(115, 255, 255, 255));
            s.setTextSize(11.5f);
            s.setSingleLine(true);
            s.setEllipsize(android.text.TextUtils.TruncateAt.END);
            row.addView(s, new android.widget.LinearLayout
                    .LayoutParams(
                            android.widget.LinearLayout.LayoutParams
                                    .WRAP_CONTENT,
                            android.widget.LinearLayout.LayoutParams
                                    .WRAP_CONTENT));
        }
        row.setOnClickListener(onClick);
        return row;
    }

    /** 回复输入区（挂在「回复」行下面，初始收起）。 */
    private void buildMenuReplyBox(
            android.widget.LinearLayout panel, final NotifData d,
            final NotifActions acts) {
        final android.widget.LinearLayout box =
                new android.widget.LinearLayout(app);
        box.setOrientation(android.widget.LinearLayout.VERTICAL);
        box.setPadding(dp(8), dp(8), dp(8), dp(8));
        GradientDrawable bbg = new GradientDrawable();
        bbg.setColor(Color.argb(24, 255, 255, 255));
        bbg.setCornerRadius(dp(14));
        box.setBackground(bbg);
        android.widget.LinearLayout.LayoutParams blp =
                new android.widget.LinearLayout.LayoutParams(
                        android.widget.LinearLayout.LayoutParams
                                .MATCH_PARENT,
                        android.widget.LinearLayout.LayoutParams
                                .WRAP_CONTENT);
        blp.leftMargin = dp(2);
        blp.rightMargin = dp(2);
        blp.topMargin = dp(4);
        blp.bottomMargin = dp(2);
        box.setLayoutParams(blp);
        box.setVisibility(View.GONE);

        android.widget.LinearLayout inRow =
                new android.widget.LinearLayout(app);
        inRow.setOrientation(android.widget.LinearLayout.HORIZONTAL);
        inRow.setGravity(Gravity.CENTER_VERTICAL);
        final android.widget.EditText input =
                new android.widget.EditText(app);
        input.setHint("输入回复…");
        input.setHintTextColor(lpMenuLight()
                ? Color.rgb(148, 163, 184)
                : Color.argb(128, 255, 255, 255));
        input.setTextColor(lpMenuLight()
                ? Color.rgb(15, 23, 42) : Color.WHITE);
        input.setTextSize(13.5f);
        input.setSingleLine(true);
        GradientDrawable ibg = new GradientDrawable();
        ibg.setColor(lpMenuLight()
                ? Color.rgb(241, 245, 249)
                : Color.argb(36, 255, 255, 255));
        ibg.setCornerRadius(dp(999));
        input.setBackground(ibg);
        input.setPadding(dp(13), dp(8), dp(13), dp(8));
        inRow.addView(input, new android.widget.LinearLayout
                .LayoutParams(0, android.widget.LinearLayout
                        .LayoutParams.WRAP_CONTENT, 1f));
        android.widget.TextView send =
                new android.widget.TextView(app);
        send.setText("发送");
        send.setTextColor(Color.WHITE);
        send.setTextSize(13);
        send.setTypeface(send.getTypeface(),
                android.graphics.Typeface.BOLD);
        send.setGravity(Gravity.CENTER);
        GradientDrawable sbg = new GradientDrawable();
        sbg.setColor(Color.rgb(34, 197, 94));
        sbg.setCornerRadius(dp(999));
        send.setBackground(sbg);
        send.setPadding(dp(16), dp(9), dp(16), dp(9));
        android.widget.LinearLayout.LayoutParams slp =
                new android.widget.LinearLayout.LayoutParams(
                        android.widget.LinearLayout.LayoutParams
                                .WRAP_CONTENT,
                        android.widget.LinearLayout.LayoutParams
                                .WRAP_CONTENT);
        slp.leftMargin = dp(8);
        inRow.addView(send, slp);
        box.addView(inRow);

        final android.widget.TextView err =
                new android.widget.TextView(app);
        err.setTextColor(Color.rgb(248, 113, 113));
        err.setTextSize(12);
        err.setPadding(dp(4), dp(6), dp(4), 0);
        err.setVisibility(View.GONE);
        box.addView(err);

        send.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                CharSequence txt = input.getText();
                String text = txt == null ? "" : txt.toString().trim();
                if (text.isEmpty()) {
                    return;
                }
                try {
                    android.os.Bundle results = new android.os.Bundle();
                    results.putCharSequence(
                            acts.remoteInput.getResultKey(), text);
                    android.content.Intent clip =
                            new android.content.Intent();
                    android.app.RemoteInput.addResultsToIntent(
                            new android.app.RemoteInput[]{
                                    acts.remoteInput}, clip, results);
                    acts.replyIntent.send(app, 0, clip);
                    FileLog.i("QuickReply sent pkg=" + d.pkg);
                    dismissMenu();
                    menuConsume(d);
                } catch (Throwable t) {
                    FileLog.i("QuickReply fail pkg=" + d.pkg
                            + " err=" + t);
                    err.setText("发送失败，再试一次或打开应用回复");
                    err.setVisibility(View.VISIBLE);
                }
            }
        });
        menuReplyBox = box;
        menuReplyInput = input;
        menuReplyError = err;
        panel.addView(box);
    }

    private void toggleMenuReplyBox() {
        if (menuReplyBox == null) {
            return;
        }
        boolean show = menuReplyBox.getVisibility() != View.VISIBLE;
        menuReplyBox.setVisibility(show ? View.VISIBLE : View.GONE);
        // v570: 内嵌菜单里的回复框要输入法——面板窗口临时
        // 改可聚焦，收起回复框时恢复（经典浮层菜单窗口本
        // 来就可聚焦，不受影响）。
        if (lightInlineMenu != null) {
            setLightPanelFocusable(show);
            // v574: 回复框展开/收起，菜单变高/变矮，面板
            // 跟着撑开/缩回。
            applyLightScrollCap();
        }
        // v547: 回复框展开/收起后面板高度变了，按同一锚点
        // 重算位置（不播动画，整块仍须可见）。
        if (menuPanel != null) {
            menuPanel.post(new Runnable() {
                @Override
                public void run() {
                    if (menuPanel != null) {
                        placeMenuPanel(menuPanel, false);
                    }
                }
            });
        }
        if (show && menuReplyInput != null) {
            menuReplyInput.requestFocus();
            try {
                android.view.inputmethod.InputMethodManager imm =
                        (android.view.inputmethod.InputMethodManager)
                                app.getSystemService(
                                        Context.INPUT_METHOD_SERVICE);
                if (imm != null) {
                    imm.showSoftInput(menuReplyInput,
                            android.view.inputmethod
                                    .InputMethodManager.SHOW_IMPLICIT);
                }
            } catch (Throwable ignored) {
            }
        }
    }

    /** 「打开应用回复」：走卡片同款的打开通道；打开成功后按
     * 卡片的老规矩撤销原通知（音乐除外——菜单只对普通胶囊
     * 出现，这里恒为普通通知，仍照规矩判断一次）。 */
    private void openAppForReply(NotifData d) {
        boolean ok = false;
        try {
            ok = openPendingIntent(d.contentIntent, d.pkg,
                    FeatureFlags.miniwindow);
        } catch (Throwable ignored) {
        }
        if (!ok) {
            try {
                android.content.Intent li = app.getPackageManager()
                        .getLaunchIntentForPackage(d.pkg);
                if (li != null) {
                    ok = openIntent(li, FeatureFlags.miniwindow);
                }
            } catch (Throwable ignored) {
            }
        }
        FileLog.i("QuickMenu openApp pkg=" + d.pkg + " ok=" + ok);
        if (ok && !d.isMusic) {
            try {
                android.content.Intent di = new android.content.Intent(
                        NotificationService.ACTION_DISMISS);
                di.putExtra("key", d.key);
                app.sendBroadcast(di);
            } catch (Throwable ignored) {
            }
        }
        if (ok) {
            menuConsume(d);
        }
    }

    /** v547: 统一浮层管理点——长按菜单全局同时只许一个
     * 在屏。show 入口打开前先调它：已开的菜单（包括正
     * 淡出动画中的）立即拆干净，不等动画、不残留透明层
     * 挡触摸或叠暗。 */
    private void closeOverlays() {
        dismissMenuNow();
        // v564: 轻气泡收纳面板也是全局单浮层的一员。
        dismissLightPanel();
    }

    /** v557: 长按菜单开关被关（FEATURE_TOGGLE 分发调用）——
     * 正开着的菜单立即硬摘。旧版 FEATURE_TOGGLE 漏了
     * longPressMenu 分支，关开关收不掉开着的菜单。 */
    public void onLongPressMenuDisabled() {
        dismissMenuNow();
    }

    /** v557: 通知气泡总开关被关（FEATURE_TOGGLE 分发
     * 调用）——全屏浮层一并收掉，别留透明层在屏上。 */
    public void onNotifMasterDisabled() {
        closeOverlays();
    }

    /** v557: 菜单锚在场校验——整组消散 / 场景 flush 这类
     * 批量路径走完后调一次：锚数据还在 dock 胶囊里、组
     * 数据里任一处就算在场；都不在了说明锚是
     * 被批量路径顺带清掉的（dissipateOne/onNotificationRemoved
     * 的按 key 跟随关闭覆盖不到），菜单立即硬摘，别留一块
     * 冲着空气的全屏层。 */
    private void dismissMenuIfAnchorGone() {
        if (menuRoot == null || menuData == null) {
            return;
        }
        NotifData anchor = menuData;
        boolean present = false;
        if (dock != null) {
            for (int i = 0; i < dock.getChildCount() && !present;
                    i++) {
                View v = dock.getChildAt(i);
                if (v instanceof CapsuleView) {
                    NotifData cd = ((CapsuleView) v).getData();
                    if (cd == anchor || (cd != null
                            && anchor.key != null
                            && anchor.key.equals(cd.key))) {
                        present = true;
                    }
                }
            }
        }
        if (!present) {
            for (NotifGroup g : groups) {
                if (g == null) {
                    continue;
                }
                for (NotifData gd : g.items) {
                    if (gd == anchor || (gd != null
                            && anchor.key != null
                            && anchor.key.equals(gd.key))) {
                        present = true;
                        break;
                    }
                }
                if (present) {
                    break;
                }
            }
        }
        if (!present) {
            FileLog.i("QuickMenu anchor gone -> dismiss");
            dismissMenuNow();
        }
    }

    private void hideMenuIme() {
        if (menuReplyInput != null) {
            try {
                android.view.inputmethod.InputMethodManager imm =
                        (android.view.inputmethod.InputMethodManager)
                                app.getSystemService(
                                        Context.INPUT_METHOD_SERVICE);
                if (imm != null) {
                    imm.hideSoftInputFromWindow(
                            menuReplyInput.getWindowToken(), 0);
                }
            } catch (Throwable ignored) {
            }
        }
    }

    /** v557: 全屏浮层统一摘除口——removeViewImmediate 同步
     * 摘，成功才允许调用方清标志/置 null；失败打日志并返回
     * false，调用方保留句柄下次再试。事故复盘（2026-11-26
     * 23:54）：旧写法 removeView 失败被吞、句柄却无条件扔
     * 掉，窗口还挂在 WindowManager 里再没人能摘，全屏透明
     * 层吃掉最近任务的左右滑，只能靠 LSPosed 禁用/启用
     * 模块（进程级清理）才恢复。 */
    private boolean removeOverlayView(View root, String name) {
        if (root == null || wm == null) {
            return true;
        }
        try {
            wm.removeViewImmediate(root);
            FileLog.i("Overlay remove name=" + name);
            return true;
        } catch (Throwable t) {
            FileLog.i("Overlay remove FAIL name=" + name
                    + " err=" + t);
            return false;
        }
    }

    /** v547: 立即拆菜单（无动画）：窗口从 WindowManager
     * 彻底移除、字段全清。closeOverlays 与淡出动画的收尾
     * 都走这里，保证任何路径关完都不留层。 */
    private void dismissMenuNow() {
        hideMenuIme();
        if (menuPanel != null) {
            try {
                menuPanel.animate().cancel();
            } catch (Throwable ignored) {
            }
        }
        if (menuRoot != null && menuWindowAdded) {
            // v557: 摘失败保留句柄（不清标志、不置 null），
            // 等下一次 closeOverlays/守卫再试，别把窗口弄丢；
            // menuClosing 复位，让用户主动关闭还能再走一遍。
            if (!removeOverlayView(menuRoot, "menu")) {
                menuClosing = false;
                return;
            }
        }
        menuWindowAdded = false;
        menuClosing = false;
        menuRoot = null;
        menuPanel = null;
        menuReplyBox = null;
        menuReplyInput = null;
        menuReplyError = null;
        menuData = null;
    }

    /** 关菜单：面板先淡出（约 150ms）再彻底移除窗口。
     * 重复调用安全：正淡出时直接忽略；收尾按根视图身份
     * 核对，不会误拆淡出期间新打开的菜单。 */
    public void dismissMenu() {
        // v570: 轻气泡内嵌菜单也归这里收（它的条目点击走的
        // 都是 dismissMenu()）——摘掉内嵌菜单本体，行刷新
        // 由动作路径（lightRemove 等）自己负责。
        collapseLightInlineMenu();
        if (menuRoot == null) {
            menuClosing = false;
            return;
        }
        if (menuClosing) {
            return;
        }
        hideMenuIme();
        if (menuPanel == null || !menuWindowAdded) {
            dismissMenuNow();
            return;
        }
        menuClosing = true;
        final FrameLayout rootRef = menuRoot;
        menuPanel.animate()
                .alpha(0f).scaleX(0.96f).scaleY(0.96f)
                .setDuration(150)
                .setInterpolator(
                        new android.view.animation
                                .DecelerateInterpolator())
                .withEndAction(new Runnable() {
                    @Override
                    public void run() {
                        if (menuRoot == rootRef) {
                            dismissMenuNow();
                        }
                    }
                })
                .start();
        // 兜底：动画收尾动作万一没跑到，260ms 后按身份收尾。
        handler.postDelayed(new Runnable() {
            @Override
            public void run() {
                if (menuRoot == rootRef) {
                    dismissMenuNow();
                }
            }
        }, 260);
    }

    /** 稍后提醒：快照留住，10 分钟后经 onNotification 重投
     * （打 recalled 标记绕过去重；场景/静默闸门照常过）。 */
    private void snoozeNotif(final NotifData d) {
        if (d == null) {
            return;
        }
        if (d.key != null) {
            Runnable old = snoozeTimers.remove(d.key);
            if (old != null) {
                handler.removeCallbacks(old);
            }
        }
        Runnable r = new Runnable() {
            @Override
            public void run() {
                if (d.key != null) {
                    snoozeTimers.remove(d.key);
                }
                d.recalled = true;
                if (d.key != null) {
                    synchronized (seenNotifKeys) {
                        seenNotifKeys.remove(d.key);
                    }
                }
                FileLog.i("Snooze fire pkg=" + d.pkg);
                onNotification(d);
            }
        };
        if (d.key != null) {
            snoozeTimers.put(d.key, r);
        }
        handler.postDelayed(r, SNOOZE_DELAY_MS);
        FileLog.i("Snooze set pkg=" + d.pkg + " delayMin=10");
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
        // v557: flush 完校验一次菜单锚还在不在场。
        dismissMenuIfAnchorGone();
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
        // v546: 通知被移除——动作暂存清掉，冲它开的菜单收起。
        synchronized (ACTIONS_BY_KEY) {
            ACTIONS_BY_KEY.remove(key);
        }
        if (menuData != null && key.equals(menuData.key)) {
            dismissMenu();
        }
        handler.post(new Runnable() {
            @Override
            public void run() {
                // v576: 轻气泡下载卡随通知移除一起撤。
                if (FeatureFlags.lightMode
                        && lightDownloads.remove(key) != null) {
                    FileLog.i("Light download removed");
                    refreshLightDot();
                    if (lightPanelAdded
                            && !lightPanelRecallMode) {
                        rebuildLightPanelRows();
                    }
                }
                // v601: 源通知被移除时信息卡联动收（若
                // 它还在收纳栈里，removeNotifByKey 那条
                // 路也会经 lightPushRecall 再收一次，幂
                // 等无碍）。
                if (FeatureFlags.lightMode
                        && lightInfoKeyToId.containsKey(key)) {
                    removeInfoCardForKey(key);
                    refreshLightDot();
                    if (lightPanelAdded
                            && !lightPanelRecallMode) {
                        rebuildLightPanelRows();
                    }
                }
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

    /** v593: 从通知原文里认有效期——「有效期1分钟」
     * 「5分钟内有效」「10分钟后过期」「valid for 5
     * minutes」这类通知自己写明时长的，才有倒计时数
     * 据；认不出返回 0（不编倒计时）。 */
    private static long parseCodeValidityMs(String title,
            String text) {
        String s = (title != null ? title : "")
                + "\n" + (text != null ? text : "");
        String[][] pats = new String[][]{
                {"有效期[^0-9]{0,4}([0-9]+)\\s*(小时|分钟|分|秒)"},
                {"([0-9]+)\\s*(小时|分钟|分)内有效"},
                {"([0-9]+)\\s*(小时|分钟|分)后(过期|失效)"},
                {"(?i)valid for ([0-9]+)\\s*(hours?|minutes?|mins?|seconds?)"},
                {"(?i)expires? in ([0-9]+)\\s*(hours?|minutes?|mins?)"}};
        for (String[] p : pats) {
            try {
                java.util.regex.Matcher m =
                        java.util.regex.Pattern.compile(p[0])
                                .matcher(s);
                if (m.find()) {
                    long n = Long.parseLong(m.group(1));
                    String u = m.group(2);
                    if (u.startsWith("小")
                            || u.toLowerCase().startsWith("h")) {
                        return n * 3600_000L;
                    }
                    if (u.startsWith("秒")
                            || u.toLowerCase().startsWith("s")) {
                        return n * 1000L;
                    }
                    return n * 60_000L;
                }
            } catch (Throwable ignored) {
            }
        }
        return 0;
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

    /** v555: 不弹、直接收进扇子的共用落点——原是 AppRules
     * 「只贴边」（EDGE）待遇的内联代码：同 key 旧条目先从
     * 已停靠的组里摘掉（防重复堆叠），再整组走
     * addPileIconForGroup 进扇子，点扇子能原样展开。关键词
     * 过滤命中时复用同一落点，不另造路径。 */
    private void pileIntoFan(NotifData data) {
        NotifGroup stored = dockedGroups.get(data.pkg);
        if (stored != null && data.key != null) {
            for (int i = stored.items.size() - 1; i >= 0; i--) {
                NotifData old = stored.items.get(i);
                if (old != null && data.key.equals(old.key)) {
                    stored.items.remove(i);
                }
            }
        }
        NotifGroup dg = new NotifGroup(data.pkg);
        dg.add(data);
        addPileIconForGroup(dg);
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
                    && !data.isNavi && !data.isBus
                    && !FeatureFlags.lightMode) {
                // v561: recalled 重投豁免 EDGE 落点——回看
                // 把手、稍后提醒都是用户主动取回，若再按
                // EDGE 收进扇子就是原地打转。只豁免这一次
                // 落点，不改存储的规则（下条新消息照样
                // 收纳）；NEVER 在上面已否决，场景/静默
                // 闸门在下面照常管。v563: 日志标记改名为
                // Recall bypassEdge（原标记随旧功能一并清）。
                if (data.recalled) {
                    FileLog.i("Recall bypassEdge pkg="
                            + data.pkg);
                } else {
                    pileIntoFan(data);
                    return;
                }
            }
        }
        // v564: 轻气泡分支（在经典开关与后续全部逻辑之前；
        // lightMode 关时整段不存在）。普通通知：安静规则
        // NEVER 已在上面否决；「弹出」(POP) = 放行系统正常
        // 通知——不收不弹（NS 侧同步不撤这条，见
        // NotificationService 的 Light release）；「收纳」
        // (EDGE) 与默认一律入轻气泡收纳栈。音乐：不弹胶囊，
        // 只更新面板音乐卡状态。v576: 下载同样不弹经典胶
        // 囊，进面板当卡片；实况/导航/公交不接入轻气泡、
        // 也不弹经典胶囊（wei：轻气泡切换后不能显示经典
        // 模式内容），系统通知原样留在通知栏。
        if (FeatureFlags.lightMode && data != null
                && !data.isCall && data.pkg != null) {
            if (data.isMusic) {
                if (FeatureFlags.lightMusicCard) {
                    if (FeatureFlags.notif
                            && FeatureFlags.music) {
                        lightMusicIn(data);
                    }
                    return;
                }
                // v578: 「音乐卡片」关——不显示卡片，音乐
                // 按普通通知收纳（POP 仍放行；NEVER 已在
                // 上面否决）。
                if (!FeatureFlags.notif) {
                    return;
                }
                if (AppRules.modeFor(data.pkg)
                        == AppRules.POP) {
                    FileLog.i("Light release pkg=" + data.pkg);
                    return;
                }
                lightCollectMusicOff(data);
                return;
            }
            if (data.isDownload) {
                if (FeatureFlags.notif && FeatureFlags.dl) {
                    lightDownloadIn(data);
                }
                return;
            }
            if (data.isLive || data.isNavi || data.isBus) {
                return;
            }
            if (isOrdinaryForLight(data)) {
                if (!FeatureFlags.notif) {
                    return;
                }
                if (AppRules.modeFor(data.pkg) == AppRules.POP) {
                    FileLog.i("Light release pkg=" + data.pkg);
                    return;
                }
                // v587: 验证码识别搬进轻气泡——经典路径的
                // 识别点在本分支之后，轻气泡以前从没给
                // data.code 赋过值。同款识别（含柜号），
                // 识别完再收纳。
                if (FeatureFlags.code && data.code == null) {
                    // v602: 识别看全文（fullText）——取件
                    // 码/验证码常在 bigText 里，短正文会被
                    // 应用截断（实测菜鸟取件码短正文只到
                    // 「取件码：8」）。
                    String[] found = extractCode(data.title,
                            data.fullText());
                    if (found != null) {
                        data.code = found[0];
                        data.codeLabel = found[1];
                        data.isPickupCode =
                                isPickupKeyword(found[1]);
                        if (data.isPickupCode) {
                            data.cabinet = extractCabinet(
                                    data.title, data.fullText());
                        }
                    }
                }
                boolean freshLight = true;
                if (data.key != null) {
                    for (NotifData e : lightStack) {
                        if (data.key.equals(e.key)) {
                            freshLight = false;
                            break;
                        }
                    }
                }
                lightCollect(data);
                // v587: 验证码自动复制搬过来（wei 定）——
                // 与经典同口径：只复制验证码不复制取件码，
                // 只在新条目上复制一次（同 key 更新不重
                // 复），开关在 autoCopyVerifyCode 里。
                if (freshLight && data.code != null
                        && !data.isPickupCode) {
                    autoCopyVerifyCode(data.code);
                }
                // v586: 轻气泡新通知也响一声（wei 定：
                // 「轻气泡新通知提示一下」）——与经典真弹
                // 出同口径，走 BubbleSound 自己的门：提示
                // 音总开关、按应用规则（默认不响，六个默
                // 认应用跟随全局）、1 秒节流、勿扰压制全
                // 在里面。菜单重收（collectCurrentIntoFan）
                // 不走这里，不会响。
                BubbleSound.play(app, data.pkg);
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
            // v602: 同轻气泡侧——识别看全文（fullText）。
            String[] found = extractCode(data.title,
                    data.fullText());
            if (found != null) {
                data.code = found[0];
                data.codeLabel = found[1];
                data.isPickupCode = isPickupKeyword(found[1]);
                // v245: a pickup code may come with a locker
                // number — show it too (user 2026-11-21
                // "如果有柜号肯定也要显示啊").
                if (data.isPickupCode) {
                    data.cabinet = extractCabinet(data.title,
                            data.fullText());
                }
            }
        }
        // v555: 关键词过滤（安静规则三件套）——位置在应用
        // 规则之后、真正弹出之前（验证码识别已在上面做完，
        // data.code 非空即豁免）。仅普通通知：音乐/下载/
        // 实况/导航/公交/来电/验证码/取件码一律不碰。命中
        // 任一词 → 不弹，走「只贴边」同款落点收进扇子
        // （pileIntoFan）。「永不弹」的在上面应用规则处已
        // return，与本功能无关。
        if (FeatureFlags.keywordFilter && data != null
                && data.pkg != null
                && !data.isCall && !data.isMusic && !data.isDownload
                && !data.isLive && !data.isNavi && !data.isBus
                && data.code == null) {
            // v604: 关键词也扫全文（fullText）——促销词
            // 常只在 bigText 里，短正文扫不到。
            String hay = (data.title == null ? "" : data.title)
                    + "\n" + (data.fullText() == null
                            ? "" : data.fullText());
            for (String w : FeatureFlags.filterWordList()) {
                if (!w.isEmpty() && hay.contains(w)) {
                    FileLog.i("KeywordFilter hit pkg=" + data.pkg
                            + " word=" + w);
                    pileIntoFan(data);
                    return;
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
        // v542: recalled snapshots exempt from BOTH dedup layers
        // below — a recall re-delivers the same fingerprint and
        // key+content seconds after the original pop, which is
        // the feature working, not a duplicate (wei 实测 v540
        // 点了回看把手没反应，根因就是被这里吞掉).
        if (data != null && !data.isMusic && !data.isDownload
                && !data.recalled
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
        // v542: recalled snapshots exempt — WeChat/QQ are NOT
        // exempt from this layer, so it swallowed their recalls
        // even though they pass the v100 layer above.
        if (data != null && !data.isMusic && !data.isDownload
                && !data.recalled && data.key != null) {
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
        // v542: 回看重投真正上屏的回执——走到这里说明去重/门控
        // 全部放行、是真弹出（原地更新已在上方 return）。下一轮
        // 看日志时以这条为准核对回看有没有真的弹出来。
        if (data.recalled) {
            FileLog.i("Recall show pkg=" + data.pkg);
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
                // v576: 轻气泡下没有经典下载胶囊——进度进
                // 面板下载卡，原地刷新。
                if (FeatureFlags.lightMode) {
                    lightDownloadProgress(key, title, text, pct,
                            completed);
                    return;
                }
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

    public void updateMusicLyric(final String key, final String lyric,
            final String lyricSrc) {
        if (key == null || lyric == null) {
            return;
        }
        // v617: 歌词来源记在 SystemUI 侧——NS 进程的
        // FileLog 不落这个文件（两份日志实测零条），
        // 来源随 ACTION_LYRIC 广播带过来在这边记。
        FileLog.i("Lyric line src="
                + (lyricSrc == null ? "unknown" : lyricSrc)
                + " -> " + lyric);
        handler.post(new Runnable() {
            @Override
            public void run() {
                // v564: 轻气泡音乐卡歌词行跟随。
                if (FeatureFlags.lightMode
                        && lightMusicData != null
                        && key.equals(lightMusicData.key)) {
                    lightMusicLyric = lyric;
                    if (lightMusicData != null) {
                        lightMusicData.lyrics = lyric;
                    }
                    if (lightMusicLyricView != null
                            && lightPanelAdded) {
                        lightMusicLyricView.setText(lyric);
                        // v568: 换行后重选一次，跑马灯从新
                        // 行起滚（经典重绑同款做法）。
                        lightMusicLyricView.setSelected(false);
                        lightMusicLyricView.setSelected(true);
                        lightMusicLyricView.setVisibility(
                                lyric.isEmpty() ? View.GONE
                                        : View.VISIBLE);
                    }
                    // v565: 歌词跟随开着时，新行按到达
                    // 药丸同款在边缘亮一次；NS 的「找不到
                    // 当前歌词」是占位提示，不是歌词，不亮。
                    if (lightLyricFollow && !lyric.isEmpty()
                            && !"找不到当前歌词".equals(lyric)) {
                        flashLightLyricPill(lyric);
                    }
                }
                // v566 诊断：跟随开着、NS 也发了歌词，却
                // 对不上当前音乐 key——打一条，下次中途
                // 停能分清是 key 漂移还是 NS 没发。
                if (FeatureFlags.lightMode && lightLyricFollow
                        && lightMusicData != null
                        && !key.equals(lightMusicData.key)) {
                    FileLog.i("Light lyric keymiss got=" + key
                            + " want=" + lightMusicData.key);
                }
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
            // v564: 轻气泡音乐状态跟随会话广播（面板音乐卡
            // 的进度/播放态、呼吸灯变色都读这份状态）。
            if (FeatureFlags.lightMode && lightMusicData != null
                    && key != null
                    && key.equals(lightMusicData.key)) {
                lightMusicPosMs = positionMs;
                lightMusicDurMs = durationMs;
                if (playing != lightMusicPlaying) {
                    lightMusicPlaying = playing;
                    if (lightPanelAdded) {
                        updateLightMusicProgressViews();
                    }
                }
                // v565: 收藏真值随 tick 对齐（会话暴露了
                // heart rating 才有这一路；没有就保留本地
                // 翻转的状态）。
                if (hasFavorite) {
                    lightMusicHasFavorite = true;
                    if (favorite != lightMusicFavorite) {
                        lightMusicFavorite = favorite;
                        if (lightPanelAdded) {
                            updateLightMusicProgressViews();
                        }
                    }
                }
            }
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
            // v564: 轻气泡音乐卡的歌名/歌手跟随会话（切歌
            // 立刻换，不等新通知）。
            if (FeatureFlags.lightMode && lightMusicData != null
                    && key != null
                    && key.equals(lightMusicData.key)) {
                boolean changed = false;
                if (title != null && !title.isEmpty()
                        && !title.equals(lightMusicTitle)) {
                    lightMusicTitle = title;
                    changed = true;
                }
                if (artist != null && !artist.isEmpty()
                        && !artist.equals(lightMusicArtist)) {
                    lightMusicArtist = artist;
                    changed = true;
                }
                if (changed && lightPanelAdded) {
                    rebuildLightPanelRows();
                }
            }
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
            // v564: 轻气泡音乐卡封面跟随会话。
            if (FeatureFlags.lightMode && lightMusicData != null
                    && key != null && art != null
                    && key.equals(lightMusicData.key)) {
                lightMusicArt = art;
                if (lightPanelAdded) {
                    rebuildLightPanelRows();
                }
            }
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
        // v566: 轻气泡歌词跟随开着时，这条 key 的监听
        // 归轻气泡自己管（sendLightLyricListen），经典
        // 同步不许插手——它按经典胶囊状态算 want，中途
        // 发一条 listen=false 就能把轻气泡的歌词掐断。
        if (FeatureFlags.lightMode && lightLyricFollow
                && lightMusicData != null
                && key.equals(lightMusicData.key)) {
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
        // v546: 胶囊没了，它那份通知动作暂存也清掉；正开着的
        // 长按菜单若是冲着这条开的，一并收起。
        if (data.key != null) {
            synchronized (ACTIONS_BY_KEY) {
                ACTIONS_BY_KEY.remove(data.key);
            }
        }
        if (menuData != null && (menuData == data
                || (data.key != null && data.key.equals(menuData.key)))) {
            dismissMenu();
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
                // v557: 整组消散完校验一次菜单锚还在不在场。
                dismissMenuIfAnchorGone();
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
