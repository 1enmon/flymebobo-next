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
        BroadcastReceiver receiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                if (intent == null) {
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
                NotifData data = new NotifData(
                        key, pkg, title, text, postTime, null, albumArt,
                        isMusic, lyrics, isDownload);
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
        log(4, TAG, "flymebobo bubble window ready");
    }
}
