package com.chenan.flymebobo;

import android.app.Application;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import io.github.libxposed.api.XposedInterface;
import io.github.libxposed.api.XposedModule;
import io.github.libxposed.api.XposedModuleInterface;
import java.lang.reflect.Method;

/**
 * flymebobo 2.0 — LSPosed entry.
 *
 * Fresh code for 2.0 (not copied from 1.x).
 *
 * The bubble window lives in the SystemUI process: it is created here
 * (when SystemUI's Application starts) and draws through SystemUI's
 * own WindowManager, so it needs no overlay permission from the user
 * — SystemUI already holds it. The module's NotificationService reads
 * notifications in its own process and hands them over via a broadcast
 * (ACTION_NOTIFY), which this entry receives and forwards to the
 * bubble window.
 *
 * Hooks for small-window open / call-banner suppression land here
 * later, step by step, as the user directs.
 */
public class XposedEntry extends XposedModule {
    private static final String TAG = "flymebobo";
    private static volatile boolean initialized = false;

    // v174: the bubble receiver must be registered exactly once per
    // process. `initialized` used to be an instance field, so a second
    // XposedEntry instance re-ran tryInit and registered a second
    // receiver — every broadcast was delivered twice and every
    // notification popped twice (all apps, once it started). The
    // static guard plus unregister-before-register below make the
    // registration process-level idempotent.
    private static android.content.BroadcastReceiver bubbleReceiver;
    private static android.content.Context bubbleReceiverContext;

    @Override
    public void onPackageLoaded(XposedModuleInterface.PackageLoadedParam param) {
        String pkg = param.getPackageName();
        if ("com.android.systemui".equals(pkg)) {
            log(4, TAG, "flymebobo 2.0 loaded in SystemUI");
            hookAppOnCreate(false);
        } else if ("com.android.incallui".equals(pkg)) {
            // Incoming-call UI interception (v104, 1.x InCallUiHide):
            // hide the system fullscreen call page so only the bubble
            // shows. scope.list already includes incallui.
            log(4, TAG, "flymebobo 2.0 loaded in incallui");
            hookAppOnCreate(true);
            // v120: the incallui process is often already running
            // when the module loads, so Application.onCreate already
            // fired and the hook above never triggers. 1.x also
            // inits immediately from ActivityThread
            // .currentApplication() for exactly this case; without
            // it InCallUiHide never installs, the system call page
            // shows (its two answer buttons), and the system's
            // earpiece route overrides our setSpeakerphoneOn.
            tryInitInCallUiNow();
        } else if ("com.autonavi.minimap".equals(pkg)) {
            // v311: Amap live navigation (foot/ride only, user
            // "不写驾车"). The notification never carries turn
            // data, so hook Amap's own Ajx bridge
            // (ModuleRoute.notifyNaviInfo) for turn + distance.
            log(4, TAG, "flymebobo 2.0 loaded in Amap");
            hookAppOnCreateAmap();
        } else if ("com.meizu.media.music".equals(pkg)) {
            // v334: QQ音乐 Flyme 版免费听（自动补时长到 4 小时）。
            // 开关在 APP 里，只有一个，叫“QQ音乐flyme版免费听”。
            log(4, TAG, "flymebobo 2.0 loaded in QQ Music Flyme");
            qqFreeClassLoader = param.getDefaultClassLoader();
            hookAppOnCreateQQFree();
        }
    }

    // v334: QQ音乐 Flyme 版免费听。跟 Amap 一个套路：hook
    // Application.onCreate + 立即从当前 Application 初始化（进程
    // 可能已经跑起来了，onCreate 早过了）。
    private static volatile boolean qqFreeInitialized = false;
    // v337: standalone qqfreemode 用 param 的 classloader；app.getClassLoader()
    // 在某些进程里拿到的不是 QQ 音乐的 loader，这里直接用 param 的。
    private static ClassLoader qqFreeClassLoader;

    private void hookAppOnCreateQQFree() {
        try {
            Method onCreate = Application.class.getMethod("onCreate");
            hook(onCreate).intercept(new XposedInterface.Hooker() {
                @Override
                public Object intercept(XposedInterface.Chain chain) throws Throwable {
                    Object result = chain.proceed();
                    try {
                        Object thisObj = chain.getThisObject();
                        if (thisObj instanceof Application) {
                            tryInitQQFree((Application) thisObj);
                        }
                    } catch (Throwable t) {
                        log(6, TAG, "flymebobo qqfree init error", t);
                    }
                    return result;
                }
            });
        } catch (Throwable t) {
            log(6, TAG, "flymebobo failed to hook Application.onCreate (qqfree)", t);
        }
        tryInitQQFreeNow();
    }

    private void tryInitQQFree(Application app) {
        if (qqFreeInitialized) {
            return;
        }
        qqFreeInitialized = true;
        try {
            new QQFreeMode(this, app, qqFreeClassLoader).install();
        } catch (Throwable t) {
            log(6, TAG, "QQFreeMode install failed", t);
        }
    }

    private void tryInitQQFreeNow() {
        try {
            Class<?> at = Class.forName("android.app.ActivityThread");
            Method m = at.getMethod("currentApplication");
            Object appObj = m.invoke(null);
            if (appObj instanceof Application) {
                tryInitQQFree((Application) appObj);
            }
        } catch (Throwable ignored) {
        }
    }

    private void hookAppOnCreate(final boolean inCallUi) {
        try {
            Method onCreate = Application.class.getMethod("onCreate");
            hook(onCreate).intercept(new XposedInterface.Hooker() {
                @Override
                public Object intercept(XposedInterface.Chain chain) throws Throwable {
                    Object result = chain.proceed();
                    try {
                        Object thisObj = chain.getThisObject();
                        if (thisObj instanceof Application) {
                            if (inCallUi) {
                                tryInitInCallUi((Application) thisObj);
                            } else {
                                tryInit((Application) thisObj);
                            }
                        }
                    } catch (Throwable t) {
                        log(6, TAG, "flymebobo init error", t);
                    }
                    return result;
                }
            });
        } catch (Throwable t) {
            log(6, TAG, "flymebobo failed to hook Application.onCreate", t);
        }
    }

    private static volatile boolean inCallUiInitialized = false;

    private void tryInitInCallUi(Application app) {
        if (inCallUiInitialized) {
            return;
        }
        inCallUiInitialized = true;
        try {
            new InCallUiHide(this, app).install();
        } catch (Throwable t) {
            log(6, TAG, "InCallUiHide install failed", t);
        }
    }

    // v120: init InCallUiHide from the already-running Application
    // (1.x BubbleNotifyModule does the same via
    // ActivityThread.currentApplication()). Safe to call alongside
    // the onCreate hook — tryInitInCallUi guards against double
    // init.
    private void tryInitInCallUiNow() {
        try {
            Class<?> at =
                    Class.forName("android.app.ActivityThread");
            Method m = at.getMethod("currentApplication");
            Object appObj = m.invoke(null);
            if (appObj instanceof Application) {
                tryInitInCallUi((Application) appObj);
            }
        } catch (Throwable ignored) {
        }
    }

    private static volatile boolean amapInitialized = false;

    private void hookAppOnCreateAmap() {
        try {
            Method onCreate = Application.class.getMethod("onCreate");
            hook(onCreate).intercept(new XposedInterface.Hooker() {
                @Override
                public Object intercept(XposedInterface.Chain chain) throws Throwable {
                    Object result = chain.proceed();
                    try {
                        Object thisObj = chain.getThisObject();
                        if (thisObj instanceof Application) {
                            tryInitAmap((Application) thisObj);
                        }
                    } catch (Throwable t) {
                        log(6, TAG, "flymebobo amap init error", t);
                    }
                    return result;
                }
            });
        } catch (Throwable t) {
            log(6, TAG, "flymebobo failed to hook Application.onCreate (amap)", t);
        }
        // Amap may already be running when the module loads.
        tryInitAmapNow();
    }

    private void tryInitAmap(Application app) {
        if (amapInitialized) {
            return;
        }
        amapInitialized = true;
        try {
            new AmapNaviHook(this, app).install();
        } catch (Throwable t) {
            log(6, TAG, "AmapNaviHook install failed", t);
        }
        // v320: Amap bus live navigation (no voice announcements).
        try {
            new BusNaviHook(this, app).install();
        } catch (Throwable t) {
            log(6, TAG, "BusNaviHook install failed", t);
        }
    }

    private void tryInitAmapNow() {
        try {
            Class<?> at = Class.forName("android.app.ActivityThread");
            Method m = at.getMethod("currentApplication");
            Object appObj = m.invoke(null);
            if (appObj instanceof Application) {
                tryInitAmap((Application) appObj);
            }
        } catch (Throwable ignored) {
        }
    }

    private void tryInit(Application app) {
        if (initialized) {
            return;
        }
        initialized = true;
        // v198: only the main SystemUI process runs the bubble.
        // SystemUI spawns sub-processes (e.g. :screenshot) that also
        // load this module; each gets its own BubbleWindow + broadcast
        // receiver, so a running sub-process pops a SECOND bubble for
        // every notification. v197's seen-keys guard is per-process
        // (the static INSTANCE is per-process) and cannot stop a
        // second process. The main process is the one whose process
        // name equals the package name; sub-processes carry ":suffix".
        String procName = null;
        try {
            procName = android.app.Application.getProcessName();
        } catch (Throwable ignored) {
        }
        if (procName == null) {
            try {
                procName = app.getApplicationInfo().processName;
            } catch (Throwable ignored) {
            }
        }
        if (procName != null && !procName.equals(app.getPackageName())) {
            return;
        }
        BubbleWindow.getInstance().init(app);
        // v176 (method 1): read notifications straight from
        // SystemUI's NotifCollection instead of the listener
        // service + broadcast. One notification reaches the
        // collection exactly once, so a double pop becomes
        // structurally impossible. If the hook cannot install on
        // this ROM, the broadcast path below stays live.
        try {
            new NotifCollectionHook(this, app).install();
        } catch (Throwable t) {
            log(6, TAG, "NotifCollectionHook install failed", t);
        }
        // Suppress the system incoming-call heads-up banner in
        // SystemUI (v104, 1.x CallBannerSuppress) — dropping it at
        // the heads-up layer removes the race with the listener's
        // cancelNotification.
        try {
            new CallBannerSuppress(this, app).install();
        } catch (Throwable t) {
            log(6, TAG, "CallBannerSuppress install failed", t);
        }
        // Drop the call notification from SystemUI's collection (v136)
        // so it never renders anywhere — heads-up, shade, or the
        // ongoing-call card in the control center. The module's own
        // listener still gets it, so the bubble keeps working.
        try {
            new CallNotifSuppress(this, app).install();
        } catch (Throwable t) {
            log(6, TAG, "CallNotifSuppress install failed", t);
        }
        IntentFilter filter = new IntentFilter(NotificationService.ACTION_NOTIFY);
        filter.addAction(NotificationService.ACTION_LYRIC);
        filter.addAction(NotificationService.ACTION_DOWNLOAD);
        filter.addAction(NotificationService.ACTION_DOWNLOAD_REMOVE);
        filter.addAction(NotificationService.ACTION_CALL);
        filter.addAction(NotificationService.ACTION_CALL_RESULT);
        filter.addAction(NotificationService.ACTION_MUSIC_PROGRESS);
        filter.addAction(NotificationService.ACTION_FEATURE_TOGGLE);
        filter.addAction(NotificationService.ACTION_APP_RULES);
        filter.addAction(NotificationService.ACTION_APPEARANCE);
        // v471: 打开 App 时恢复本地备份后推一次全量重载（见
        // ACTION_RELOAD_SETTINGS）。
        filter.addAction(NotificationService.ACTION_RELOAD_SETTINGS);
        filter.addAction(NotificationService.ACTION_NAVI_INFO);
        filter.addAction(NotificationService.ACTION_NAVI_END);
        filter.addAction(NotificationService.ACTION_NAVI_HOOK_STATUS);
        filter.addAction(NotificationService.ACTION_NAVI_REMAIN);
        // v427: push-cancel diagnostic from the listener service
        // (see ACTION_CANCEL_DIAG) — log it in SystemUI so it
        // lands in the log the user sends.
        filter.addAction(NotificationService.ACTION_CANCEL_DIAG);
        // v320: Amap bus live navigation.
        filter.addAction(NotificationService.ACTION_BUS_INFO);
        filter.addAction(NotificationService.ACTION_BUS_ROUTE);
        filter.addAction(NotificationService.ACTION_BUS_HOOK_STATUS);
        // v322: Amap foot navigation route (compute-it-yourself).
        filter.addAction(NotificationService.ACTION_FOOT_ROUTE);
        filter.addAction(NotificationService.ACTION_FOOT_DIAG);
        filter.addAction(NotificationService.ACTION_FOOT_HOOK_STATUS);
        // v326: route details JSON diagnostic.
        filter.addAction(NotificationService.ACTION_ROUTE_DIAG);
        filter.addAction(
                NotificationService.ACTION_ROUTE_HOOK_STATUS);
        // v374: screen on/off — hide the bubble windows while the
        // screen is off so they never show on the AOD.
        filter.addAction(Intent.ACTION_SCREEN_ON);
        filter.addAction(Intent.ACTION_SCREEN_OFF);
        BroadcastReceiver receiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                if (intent == null) {
                    return;
                }
                // v374: screen off -> hide the bubble windows so
                // they never show on top of the always-on display
                // (息屏显示); screen on -> show them again (lock
                // screen included, TYPE_SYSTEM_ERROR keeps them
                // above the keyguard).
                if (Intent.ACTION_SCREEN_OFF.equals(intent.getAction())
                        || Intent.ACTION_SCREEN_ON.equals(
                                intent.getAction())) {
                    BubbleWindow bw = BubbleWindow.getInstance();
                    if (bw != null) {
                        bw.setScreenOn(Intent.ACTION_SCREEN_ON.equals(
                                intent.getAction()));
                    }
                    return;
                }
                // v427: push-cancel diagnostic from the listener
                // service — the listener's own FileLog lines never
                // reach the log the user sends, so the result is
                // forwarded here (SystemUI process) to be visible.
                if (NotificationService.ACTION_CANCEL_DIAG.equals(
                        intent.getAction())) {
                    FileLog.i("CancelDiag key=" + intent.getStringExtra("key")
                            + " stillActive=" + intent.getBooleanExtra(
                                    "stillActive", true)
                            + " pkg=" + intent.getStringExtra("pkg")
                            + " tag=" + intent.getStringExtra("tag")
                            + " id=" + intent.getIntExtra("id", -1));
                    return;
                }
                // v259: APP feature switch flipped — update the
                // live flag at once, no restart needed.
                if (NotificationService.ACTION_FEATURE_TOGGLE.equals(
                        intent.getAction())) {
                    String fkey = intent.getStringExtra("key");
                    boolean fenabled = intent.getBooleanExtra("enabled", true);
                    FeatureFlags.set(fkey, fenabled);
                    // v262: scroll switch flips apply at once.
                    if ("scroll".equals(fkey)) {
                        BubbleWindow.getInstance().refreshLyricsScroll();
                    }
                    // v376: callRight flips move an active call
                    // capsule between the dock and the right window.
                    if ("callRight".equals(fkey)) {
                        BubbleWindow.getInstance().onCallRightChanged();
                    }
                    // v443: simCall flips show/hide the fake call
                    // capsule (position preview for the call-height
                    // slider). Only on a live flip; never auto-shown
                    // at startup.
                    if ("simCall".equals(fkey)) {
                        BubbleWindow.getInstance().setSimCall(fenabled);
                    }
                    // v459: simMusic flips show/hide the fake music
                    // capsule (size preview for the music capsule /
                    // card sliders). Same pattern as simCall.
                    if ("simMusic".equals(fkey)) {
                        BubbleWindow.getInstance().setSimMusic(fenabled);
                    }
                    // v459: fanAutoDim flips the fan idle-dim on/off.
                    // Off = fan stays fully opaque, any dim is lifted.
                    if ("fanAutoDim".equals(fkey)) {
                        BubbleWindow.getInstance().setFanAutoDim(fenabled);
                    }
                    // v465: autoTint flips re-tint the capsules that
                    // are already out (icon colour <-> default dark).
                    if ("autoTint".equals(fkey)) {
                        BubbleWindow.getInstance().refreshCapsuleTint();
                    }
                    // v478: 反方向的钟——开关翻动。开着：正在放音乐
                    // 就亮跑马灯；关着：灯灭（正在跑也灭）。
                    if ("musicLed".equals(fkey)) {
                        BubbleWindow.getInstance().setMusicLed(fenabled);
                    }
                    return;
                }
                // v438/v439: APP appearance slider moved — update the
                // live value at once and re-lay out, no restart.
                // One slider only (the fan / whole unit anchor).
                // v442: 同一条广播也带来电高度 callPct——只移右边来电窗，
                // 不重排左边胶囊（onCallPositionChanged）。两个值各管各的，
                // 广播里带哪个就改哪个。
                // v456: 外观细调全家都走这条广播——带哪个改哪个；改了
                // 尺寸/圆角/扇子的，重排整组（onAppearanceChanged，
                // rebuildDock，胶囊按新值重造）；只改来电高度的还是
                // 只移右窗。
                if (NotificationService.ACTION_APPEARANCE.equals(
                        intent.getAction())) {
                    int dockPct = intent.getIntExtra("dockPct", -1);
                    int callPct = intent.getIntExtra("callPct", -1);
                    BubbleWindow bw = BubbleWindow.getInstance();
                    boolean relayout = false;
                    if (dockPct >= 0) {
                        Appearance.setDock(dockPct);
                        relayout = true;
                    }
                    if (intent.hasExtra("cornerRadius")) {
                        Appearance.setCornerRadius(intent.getIntExtra(
                                "cornerRadius", Appearance.DEF_CORNER_RADIUS));
                        relayout = true;
                    }
                    if (intent.hasExtra("capsuleHeight")) {
                        Appearance.setCapsuleHeight(intent.getIntExtra(
                                "capsuleHeight", Appearance.DEF_CAPSULE_HEIGHT));
                        relayout = true;
                    }
                    if (intent.hasExtra("capsuleWidth")) {
                        Appearance.setCapsuleWidth(intent.getIntExtra(
                                "capsuleWidth", Appearance.DEF_CAPSULE_WIDTH));
                        relayout = true;
                    }
                    if (intent.hasExtra("cardWidth")) {
                        Appearance.setCardWidth(intent.getIntExtra(
                                "cardWidth", Appearance.DEF_CARD_WIDTH));
                        relayout = true;
                    }
                    if (intent.hasExtra("cardCornerRadius")) {
                        Appearance.setCardCornerRadius(intent.getIntExtra(
                                "cardCornerRadius",
                                Appearance.DEF_CARD_CORNER_RADIUS));
                        relayout = true;
                    }
                    if (intent.hasExtra("cardMaxRows")) {
                        Appearance.setCardMaxRows(intent.getIntExtra(
                                "cardMaxRows", Appearance.DEF_CARD_MAX_ROWS));
                        relayout = true;
                    }
                    if (intent.hasExtra("fanOverlap")) {
                        Appearance.setFanOverlap(intent.getIntExtra(
                                "fanOverlap", Appearance.DEF_FAN_OVERLAP));
                        relayout = true;
                    }
                    if (intent.hasExtra("fanDirection")) {
                        Appearance.setFanDirection(intent.getIntExtra(
                                "fanDirection", Appearance.DEF_FAN_DIRECTION));
                        relayout = true;
                    }
                    if (intent.hasExtra("callCornerRadius")) {
                        Appearance.setCallCornerRadius(intent.getIntExtra(
                                "callCornerRadius",
                                Appearance.DEF_CALL_CORNER_RADIUS));
                        relayout = true;
                    }
                    if (intent.hasExtra("callCapsuleHeight")) {
                        Appearance.setCallCapsuleHeight(intent.getIntExtra(
                                "callCapsuleHeight",
                                Appearance.DEF_CALL_CAPSULE_HEIGHT));
                        relayout = true;
                    }
                    if (intent.hasExtra("callCapsuleWidth")) {
                        Appearance.setCallCapsuleWidth(intent.getIntExtra(
                                "callCapsuleWidth",
                                Appearance.DEF_CALL_CAPSULE_WIDTH));
                        relayout = true;
                    }
                    if (intent.hasExtra("musicCornerRadius")) {
                        Appearance.setMusicCornerRadius(intent.getIntExtra(
                                "musicCornerRadius",
                                Appearance.DEF_MUSIC_CORNER_RADIUS));
                        relayout = true;
                    }
                    if (intent.hasExtra("musicCapsuleHeight")) {
                        Appearance.setMusicCapsuleHeight(intent.getIntExtra(
                                "musicCapsuleHeight",
                                Appearance.DEF_MUSIC_CAPSULE_HEIGHT));
                        relayout = true;
                    }
                    if (intent.hasExtra("musicCapsuleWidth")) {
                        Appearance.setMusicCapsuleWidth(intent.getIntExtra(
                                "musicCapsuleWidth",
                                Appearance.DEF_MUSIC_CAPSULE_WIDTH));
                        relayout = true;
                    }
                    // v458: 音乐卡片宽 / 圆角（播放卡大小调整）。
                    if (intent.hasExtra("musicCardWidth")) {
                        Appearance.setMusicCardWidth(intent.getIntExtra(
                                "musicCardWidth",
                                Appearance.DEF_MUSIC_CARD_WIDTH));
                        relayout = true;
                    }
                    if (intent.hasExtra("musicCardCornerRadius")) {
                        Appearance.setMusicCardCornerRadius(intent.getIntExtra(
                                "musicCardCornerRadius",
                                Appearance.DEF_MUSIC_CARD_CORNER_RADIUS));
                        relayout = true;
                    }
                    // v462: 音乐卡片比例（60–100%）。
                    if (intent.hasExtra("musicCardScale")) {
                        Appearance.setMusicCardScalePct(intent.getIntExtra(
                                "musicCardScale",
                                Appearance.DEF_MUSIC_CARD_SCALE));
                        relayout = true;
                    }
                    // v484: 展开卡片（普通卡）比例（60–100%）。
                    if (intent.hasExtra("cardScale")) {
                        Appearance.setCardScalePct(intent.getIntExtra(
                                "cardScale",
                                Appearance.DEF_CARD_SCALE));
                        relayout = true;
                    }
                    if (relayout && bw != null) {
                        bw.onAppearanceChanged();
                    }
                    if (callPct >= 0) {
                        Appearance.setCall(callPct);
                        if (bw != null) {
                            bw.onCallPositionChanged();
                        }
                    }
                    return;
                }
                // v471: 打开 App 时恢复本地备份后推一次全量重载（用户
                // 2026-11-23 “改成打开自动加载一次”）。重读
                // FeatureFlags + Appearance（文件优先，没文件读
                // prefs——App 刚把文件值写进 prefs，所以一定读到
                // 恢复后的值），再重排 dock、重着色胶囊，开关和
                // 功能就对上了。Application context 走
                // ActivityThread.currentApplication()（receiver
                // 的 context 是受限的，createPackageContext 会炸）。
                // v504 (2.4.2): a per-app bubble rule changed —
                // reload the rule map at once (same restricted-
                // context dance as ACTION_RELOAD_SETTINGS below).
                if (NotificationService.ACTION_APP_RULES.equals(
                        intent.getAction())) {
                    try {
                        Class<?> at = Class.forName(
                                "android.app.ActivityThread");
                        java.lang.reflect.Method m =
                                at.getMethod("currentApplication");
                        android.app.Application app =
                                (android.app.Application) m.invoke(null);
                        if (app != null) {
                            AppRules.load(app);
                        }
                    } catch (Throwable ignored) {
                    }
                    return;
                }
                if (NotificationService.ACTION_RELOAD_SETTINGS.equals(
                        intent.getAction())) {
                    try {
                        Class<?> at = Class.forName(
                                "android.app.ActivityThread");
                        java.lang.reflect.Method m =
                                at.getMethod("currentApplication");
                        android.app.Application app =
                                (android.app.Application) m.invoke(null);
                        if (app != null) {
                            FeatureFlags.load(app);
                            Appearance.load(app);
                            AppRules.load(app);
                            BubbleWindow bw = BubbleWindow.getInstance();
                            if (bw != null) {
                                bw.onAppearanceChanged();
                                bw.refreshCapsuleTint();
                            }
                        }
                    } catch (Throwable ignored) {
                    }
                    return;
                }
                // v171: music progress tick — update the open music
                // card's bar / times / play-pause icon.
                if (NotificationService.ACTION_MUSIC_PROGRESS.equals(
                        intent.getAction())) {
                    String pkey = intent.getStringExtra("key");
                    long pos = intent.getLongExtra("position", 0);
                    long dur = intent.getLongExtra("duration", 0);
                    boolean playing =
                            intent.getBooleanExtra("playing", false);
                    // v174: the real heart-rating state rides the same
                    // tick, so the card's heart reflects whether this
                    // song is actually favorited.
                    boolean favorite =
                            intent.getBooleanExtra("favorite", false);
                    boolean hasFavorite =
                            intent.hasExtra("favorite");
                    BubbleWindow.getInstance().onMusicProgress(
                            pkey, pos, dur, playing, favorite, hasFavorite);
                    // v204: the full state also carries title/artist
                    // — forward them so a manual skip renames the
                    // card at once.
                    String pTitle = intent.getStringExtra("title");
                    String pArtist = intent.getStringExtra("artist");
                    if (pTitle != null || pArtist != null) {
                        BubbleWindow.getInstance().onMusicState(
                                pkey, pTitle, pArtist);
                    }
                    // v218: session album art push — the live
                    // controller's art, sent when it first appears
                    // for a song. Forward it so late art (NetEase)
                    // shows without waiting for a notification
                    // re-post.
                    android.graphics.Bitmap pArt = null;
                    try {
                        pArt = intent.getParcelableExtra("album_art");
                    } catch (Throwable ignored) {
                    }
                    if (pArt != null) {
                        BubbleWindow.getInstance().onMusicArt(
                                pkey, pArt);
                    }
                    return;
                }
                // Live lyric line for a pinned music capsule (v76).
                if (NotificationService.ACTION_LYRIC.equals(
                        intent.getAction())) {
                    String key = intent.getStringExtra("key");
                    String lyrics = intent.getStringExtra("lyrics");
                    BubbleWindow.getInstance().updateMusicLyric(key, lyrics);
                    return;
                }
                // Incoming / ongoing call (v103).
                if (NotificationService.ACTION_CALL_RESULT.equals(
                        intent.getAction())) {
                    // v142: diagnostic Toast removed — the toggle
                    // works now, no prompt needed.
                    return;
                }
                if (NotificationService.ACTION_CALL.equals(
                        intent.getAction())) {
                    String state = intent.getStringExtra("call_state");
                    String name = intent.getStringExtra("call_name");
                    String number = intent.getStringExtra("call_number");
                    String callKey = intent.getStringExtra("call_key");
                    android.app.PendingIntent spkOn =
                            intent.getParcelableExtra(
                                    "speaker_on_intent");
                    android.app.PendingIntent spkOff =
                            intent.getParcelableExtra(
                                    "speaker_off_intent");
                    BubbleWindow.getInstance().onCall(
                            state, name, number, callKey,
                            spkOn, spkOff);
                    return;
                }
                // v242: download notification gone — drop its
                // capsule (finished or cancelled).
                if (NotificationService.ACTION_DOWNLOAD_REMOVE.equals(
                        intent.getAction())) {
                    String rkey = intent.getStringExtra("key");
                    BubbleWindow.getInstance()
                            .onNotificationRemoved(rkey);
                    return;
                }
                // Live download progress (v95, v98, v99: title
                // carries the percent, "正在下载 45%", text is content).
                if (NotificationService.ACTION_DOWNLOAD.equals(
                        intent.getAction())) {
                    String key = intent.getStringExtra("key");
                    String title = intent.getStringExtra("title");
                    String text = intent.getStringExtra("text");
                    int pct = intent.getIntExtra("pct", -1);
                    boolean completed =
                            intent.getBooleanExtra("completed", false);
                    BubbleWindow.getInstance().updateDownloadProgress(
                            key, title, text, pct, completed);
                    return;
                }
                // v190: the v176 guard is REMOVED. It dropped the
                // broadcast whenever the hook was active, so a
                // notification the hook failed to pop (for any
                // reason) was lost entirely — no fallback. Before
                // the method-1 change the broadcast path popped
                // com.meizu.media.music fine (user 2026-11-20 "没换
                // 通知获取方法前 2.0 也是正常修好了的"); the hook
                // change regressed it. The broadcast is the
                // fallback again: if the hook already popped this
                // key, popCapsule's dedup (same key = update, never
                // a second pop) absorbs the duplicate, so restoring
                // the broadcast cannot double-pop.
                // v311: Amap live navigation (foot/ride) — the
                // hook in the Amap process forwards turn + distance.
                if (NotificationService.ACTION_NAVI_INFO.equals(
                        intent.getAction())) {
                    String bizType = intent.getStringExtra("bizType");
                    String nTitle = intent.getStringExtra("title");
                    String nContent = intent.getStringExtra("content");
                    int nStatus = intent.getIntExtra("status", 0);
                    int nIcon = intent.getIntExtra("icon", 0);
                    BubbleWindow.getInstance().onNaviInfo(
                            bizType, nTitle, nContent, nStatus, nIcon);
                    return;
                }
                // v312: Amap hook install status — FileLog it so
                // the user can see from BubbleNotify.log whether the
                // hook got into the Amap process at all.
                if (NotificationService.ACTION_NAVI_HOOK_STATUS.equals(
                        intent.getAction())) {
                    String st = intent.getStringExtra("status");
                    String err = intent.getStringExtra("error");
                    try {
                        FileLog.i("NaviHook status=" + st
                                + (err != null ? " error=" + err : ""));
                    } catch (Throwable ignored) {
                    }
                    return;
                }
                // v318 (diagnostic): remaining nav data from the
                // engine's PerfOptimization store — FileLog it so
                // the user can see whether foot/ride populate
                // remainDistance/remainTime and in what format.
                if (NotificationService.ACTION_NAVI_REMAIN.equals(
                        intent.getAction())) {
                    String rd = intent.getStringExtra("remainDistance");
                    String rt = intent.getStringExtra("remainTime");
                    String d = intent.getStringExtra("distance");
                    String ct = intent.getStringExtra("costTime");
                    String src = intent.getStringExtra("src");
                    try {
                        FileLog.i("NaviRemain src=" + src
                                + " remainDistance=" + rd
                                + " remainTime=" + rt
                                + " distance=" + d
                                + " costTime=" + ct);
                    } catch (Throwable ignored) {
                    }
                    return;
                }
                // v311: Amap navigation ended (its notification was
                // removed) — drop the nav capsule for this type.
                if (NotificationService.ACTION_NAVI_END.equals(
                        intent.getAction())) {
                    String bizType = intent.getStringExtra("bizType");
                    BubbleWindow.getInstance().onNaviEnd(bizType);
                    return;
                }
                // v320: Amap bus live navigation. BUS_INFO (mode A,
                // real-time arrival) and BUS_ROUTE (mode B station
                // list) come from BusNaviHook in the Amap process.
                if (NotificationService.ACTION_BUS_INFO.equals(
                        intent.getAction())) {
                    BubbleWindow.getInstance().onBusInfo(
                            intent.getStringExtra("line"),
                            intent.getStringExtra("station"),
                            intent.getStringExtra("stationLeft"),
                            intent.getStringExtra("arrival"),
                            intent.getStringExtra("busX"),
                            intent.getStringExtra("busY"));
                    return;
                }
                if (NotificationService.ACTION_BUS_ROUTE.equals(
                        intent.getAction())) {
                    BubbleWindow.getInstance().onBusRoute(
                            intent.getStringExtra("busname"),
                            intent.getStringExtra("direction"),
                            intent.getStringExtra("startName"),
                            intent.getStringExtra("endName"),
                            intent.getStringExtra("passNames"),
                            intent.getStringExtra("passCoords"));
                    return;
                }
                if (NotificationService.ACTION_BUS_HOOK_STATUS.equals(
                        intent.getAction())) {
                    String st = intent.getStringExtra("status");
                    String err = intent.getStringExtra("error");
                    try {
                        FileLog.i("BusHook status=" + st
                                + (err != null ? " error=" + err : ""));
                    } catch (Throwable ignored) {
                    }
                    return;
                }
                // v322: Amap foot navigation route (compute-it-
                // yourself) — store the polyline and start
                // position tracking.
                if (NotificationService.ACTION_FOOT_ROUTE.equals(
                        intent.getAction())) {
                    BubbleWindow.getInstance().onFootRoute(
                            intent.getIntExtra("totalLength", -1),
                            intent.getIntExtra("totalTime", -1),
                            intent.getStringExtra("points"));
                    return;
                }
                // v322 (diagnostic): foot route JSON structure —
                // FileLog it so the user can report whether the JS
                // route JSON carries a polyline at all.
                if (NotificationService.ACTION_FOOT_DIAG.equals(
                        intent.getAction())) {
                    try {
                        FileLog.i("FootDiag jsonKeys="
                                + intent.getStringExtra("jsonKeys")
                                + " hasPolyline="
                                + intent.getBooleanExtra(
                                        "hasPolyline", false)
                                + " jsonLen="
                                + intent.getIntExtra("jsonLen", -1));
                    } catch (Throwable ignored) {
                    }
                    return;
                }
                // v323: foot route hook install status.
                // v325: detail now lists WHICH hooks installed
                // (jumpFootNavi/startFootNaviPage/onPageCreated).
                if (NotificationService.ACTION_FOOT_HOOK_STATUS.equals(
                        intent.getAction())) {
                    String st = intent.getStringExtra("status");
                    String dt = intent.getStringExtra("detail");
                    try {
                        FileLog.i("FootHook status=" + st
                                + (dt != null ? " detail=" + dt
                                        : ""));
                    } catch (Throwable ignored) {
                    }
                    return;
                }
                // v326 (diagnostic): route details JSON structure
                // — the JSON JS renders as route options + the
                // "路线详情" step list. Log it so the user can
                // report the top-level keys and whether a steps
                // array exists.
                if (NotificationService.ACTION_ROUTE_DIAG.equals(
                        intent.getAction())) {
                    try {
                        FileLog.i("RouteDiag mode="
                                + intent.getStringExtra("mode")
                                + " action="
                                + intent.getStringExtra("action")
                                + " jsonKeys="
                                + intent.getStringExtra("jsonKeys")
                                + " hasSteps="
                                + intent.getBooleanExtra(
                                        "hasSteps", false)
                                + " jsonLen="
                                + intent.getIntExtra("jsonLen", -1));
                    } catch (Throwable ignored) {
                    }
                    return;
                }
                // v327: route jump hook install status.
                if (NotificationService.ACTION_ROUTE_HOOK_STATUS
                        .equals(intent.getAction())) {
                    String st = intent.getStringExtra("status");
                    String dt = intent.getStringExtra("detail");
                    try {
                        FileLog.i("RouteHook status=" + st
                                + (dt != null ? " detail=" + dt
                                        : ""));
                    } catch (Throwable ignored) {
                    }
                    return;
                }
                String key = intent.getStringExtra("key");
                boolean isMusic = intent.getBooleanExtra("isMusic", false);
                // v194 (smart guard): v190 removed the v176 guard
                // outright, so the hook AND the broadcast both pop
                // the same notification — test app A started
                // double-popping. The content fingerprints in
                // popCapsule do not catch it (the two paths parse
                // title/text differently). Drop the broadcast ONLY
                // when the hook already popped this exact key; if
                // the hook missed it, the broadcast still pops it
                // (the v190 fallback for com.meizu.media.music).
                // v226: music is EXEMPT from this guard. A music
                // notification reuses the SAME key across song
                // changes, so hasNotificationKey is true for
                // every update after the first song — the guard
                // dropped every Flyme song change (the hook never
                // sees Flyme, so the broadcast is its only update
                // path) and the capsule and card only refreshed
                // after a manual dismiss cleared the key.
                // popCapsule already treats a same-key music
                // re-post as an update, never a second pop, so
                // the guard is not needed for music.
                // v309: WeChat/QQ are EXEMPT too (user 2026-11-21).
                // Same conversation reuses the SAME key for every
                // message, so the guard dropped every message after
                // the first — only one capsule ever popped. The
                // popCapsule dedup (v146 key+content) still catches
                // true duplicates.
                String pkg = intent.getStringExtra("pkg");
                boolean isChatApp = "com.tencent.mm".equals(pkg)
                        || "com.tencent.mobileqq".equals(pkg);
                if (!isMusic && !isChatApp && key != null
                        && BubbleWindow.isNotifCollectionHookActive()
                        && BubbleWindow.getInstance()
                                .hasNotificationKey(key)) {
                    return;
                }
                String title = intent.getStringExtra("title");
                String text = intent.getStringExtra("text");
                long postTime = intent.getLongExtra("postTime", 0);
                String lyrics = intent.getStringExtra("lyrics");
                boolean isDownload = intent.getBooleanExtra("isDownload", false);
                // v24: no Bitmap is passed across processes any
                // more (see NotificationService) — the icon is loaded
                // in SystemUI from the package, the way v1 does it.
                // v171: exception — the music album art, downscaled
                // to 128px in the module, rides the broadcast so the
                // music card can show the real cover.
                android.graphics.Bitmap albumArt = null;
                try {
                    albumArt = intent.getParcelableExtra("album_art");
                } catch (Throwable ignored) {
                }
                // v328: push-service delegation (same as the hook
                // path) — when a push service posts on an app's
                // behalf, the key's 2nd field is the real app
                // package. Use it for display so the capsule shows
                // the real app (BOSS直聘) instead of "推送服务".
                // Guards above already ran on the original pkg.
                String displayPkg = pkg;
                if (key != null) {
                    String[] parts = key.split("\\|");
                    if (parts.length >= 2) {
                        String keyPkg = parts[1];
                        if (keyPkg != null && !keyPkg.isEmpty()
                                && !keyPkg.equals(pkg)
                                && isInstalledApp(keyPkg)) {
                            displayPkg = keyPkg;
                        }
                    }
                }
                NotifData data = new NotifData(
                        key, displayPkg, title, text, postTime, null,
                        albumArt, isMusic, lyrics, isDownload);
                // v239: download percent for the capsule bar.
                try {
                    data.downloadPct = intent.getIntExtra(
                            "download_pct", -1);
                } catch (Throwable ignored) {
                }
                // v143: the tap action for the card's open buttons.
                try {
                    data.contentIntent = intent.getParcelableExtra(
                            "content_intent");
                } catch (Throwable ignored) {
                }
                BubbleWindow.getInstance().onNotification(data);
            }
        };
        // v174: drop any previously registered receiver first, so a
        // re-run of tryInit can never leave two receivers live.
        try {
            if (bubbleReceiver != null && bubbleReceiverContext != null) {
                bubbleReceiverContext.unregisterReceiver(bubbleReceiver);
            }
        } catch (Throwable ignored) {
        }
        bubbleReceiver = receiver;
        bubbleReceiverContext = app;
        // The broadcast comes from our service (another process), so the
        // receiver must be exported. Use the hidden 3-arg registerReceiver
        // with RECEIVER_EXPORTED (flag 2); fall back to the public API.
        try {
            Method m = Context.class.getMethod("registerReceiver",
                    BroadcastReceiver.class, IntentFilter.class, int.class);
            m.invoke(app, receiver, filter, 2);
        } catch (Throwable t) {
            try {
                app.registerReceiver(receiver, filter);
            } catch (Throwable ignored) {
            }
        }
        // v259: read the APP's saved switches so the flags
        // match what the user left them at (defaults are all on).
        FeatureFlags.load(app);
        // v438: read the APP's saved appearance (pop/fan position).
        Appearance.load(app);
        // v504 (2.4.2): read the APP's per-app bubble rules.
        AppRules.load(app);
        log(4, TAG, "flymebobo bubble window ready");
    }

    /** v328: true when the package is installed (has an
     * ApplicationInfo), used to validate the real app package
     * parsed out of a push-delegated notification key. */
    private static boolean isInstalledApp(String pkg) {
        if (pkg == null || bubbleReceiverContext == null) {
            return false;
        }
        try {
            bubbleReceiverContext.getPackageManager()
                    .getApplicationInfo(pkg, 0);
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }
}
