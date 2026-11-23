package com.chenan.flymebobo;

import android.app.Application;
import android.app.Notification;
import android.graphics.Bitmap;
import android.os.Bundle;
import android.service.notification.StatusBarNotification;
import android.util.Log;
import io.github.libxposed.api.XposedInterface;
import java.lang.reflect.Method;

// v176 (method 1): notification acquisition via NotifCollection hook.
//
// The module used to read notifications through its own listener
// service (a separate process) and forward them to SystemUI over a
// broadcast (ACTION_NOTIFY). That path can deliver a notification
// twice (receiver registered twice, service bound twice) and every
// fix so far (v174 static receiver, v175 music 2s dedup) has been a
// patch on one specific cause. Hooking NotifCollection inside
// SystemUI removes the broadcast hop entirely: one notification
// reaches the collection exactly once, so a double pop becomes
// structurally impossible.
//
// Same hook point as Flyme Island 1.0.38, and the same signature
// 1.x's NotifCollectionHook used as a safety net
// (onNotificationPosted(StatusBarNotification, RankingMap)).
// Only the hook point is shared — the intake, detection and
// NotifData assembly below are fresh 2.0 code.
//
// Calls are NOT popped here: call handling is stateful
// (RINGING/OFFHOOK, speaker intents, TelephonyManager) and stays
// with NotificationService, which keeps sending ACTION_CALL. The
// hook skips call notifications so the two paths never fight.
//
// Music and downloads ARE popped here. The heavy lifting (lyric
// resolution, progress tick) stays in NotificationService, which
// keeps sending ACTION_LYRIC / ACTION_MUSIC_PROGRESS /
// ACTION_DOWNLOAD updates — those refine what the hook popped.
// The hook sets the initial lyric to the song name (the same
// fallback NotificationService uses when no lyric is resolved
// yet); the live lyric arrives via ACTION_LYRIC.
//
// If the hook cannot install (class/method missing on some ROM),
// it logs and leaves BubbleWindow's hook flag false — the
// broadcast path (ACTION_NOTIFY) keeps working as before.
public final class NotifCollectionHook {
    private static final String TAG = "flymebobo";
    private final XposedInterface bridge;
    private final Application app;
    private boolean done;

    // v203 (1.0.5): sticky state for music titles (see the title
    // block in onNotificationPosted).
    private static final java.util.Map<String, String> musicLastTitleByKey =
            new java.util.HashMap<>();
    private static final java.util.Map<String, Long> musicLastDurationByKey =
            new java.util.HashMap<>();
    // v223: last session position per key - the backward-jump
    // evidence for the hook sticky (mirrors the v216 broadcast rule).
    private static final java.util.Map<String, Long> musicLastPositionByKey =
            new java.util.HashMap<>();
    // v224: last raw session ARTIST per key - the artist-change
    // evidence for the hook sticky (mirrors the v216 broadcast rule).
    private static final java.util.Map<String, String> musicLastRawArtistByKey =
            new java.util.HashMap<>();

    public NotifCollectionHook(XposedInterface xposed, Application app) {
        this.bridge = xposed;
        this.app = app;
    }

    public void install() {
        if (done) {
            return;
        }
        try {
            Class<?> collection = Class.forName(
                    "com.android.systemui.statusbar.notification.collection.NotifCollection",
                    false, app.getClassLoader());
            Class<?> ranking = Class.forName(
                    "android.service.notification.NotificationListenerService$RankingMap",
                    false, app.getClassLoader());
            Method posted = collection.getDeclaredMethod("onNotificationPosted",
                    StatusBarNotification.class, ranking);
            bridge.hook(posted).intercept(new XposedInterface.Hooker() {
                @Override
                public Object intercept(XposedInterface.Chain chain) throws Throwable {
                    // v370: hide the notification from the lock screen
                    // so only the bubble shows there. Set visibility
                    // SECRET before SystemUI processes it. The shade
                    // still shows it, and the module's own listener
                    // (separate process) is unaffected, so music lyric
                    // / download progress re-reads keep working. Only
                    // when the master switch is on; otherwise the lock
                    // screen would lose notifications with no bubble
                    // to show. Calls are skipped (CallNotifSuppress
                    // already drops them from the collection).
                    try {
                        if (FeatureFlags.notif) {
                            StatusBarNotification sbn =
                                    (StatusBarNotification) chain.getArg(0);
                            if (sbn != null
                                    && sbn.getNotification() != null) {
                                String pkg = sbn.getPackageName();
                                if (pkg != null
                                        && !pkg.equals("com.chenan.flymebobo")
                                        && !pkg.equals("com.android.systemui")
                                        && !pkg.equals("android")
                                        && !isCall(sbn,
                                                sbn.getNotification())) {
                                    sbn.getNotification().visibility =
                                            Notification.VISIBILITY_SECRET;
                                }
                            }
                        }
                    } catch (Throwable ignored) {
                    }
                    Object result = chain.proceed();
                    try {
                        onPosted((StatusBarNotification) chain.getArg(0));
                    } catch (Throwable ignored) {
                    }
                    return result;
                }
            });
            // v177: onNotificationRemoved hook REMOVED. It was the
            // v176 regression: NotifCollection fires a removal for
            // the same key right after the post (update / ranking
            // change / old-entry replace), and the hook's
            // removeNotifByKey + rebuildDock wiped the capsule that
            // had just popped — "pop once, then all gone", docked
            // icons gone too. Capsule removal goes back to user
            // action (swipe) as before; the shade-dismiss sync is
            // not worth breaking the pop. Only the post hook stays.
            done = true;
            BubbleWindow.setNotifCollectionHookActive(true);
            log("NotifCollectionHook: installed (method 1)");
        } catch (Throwable th) {
            log("NotifCollectionHook install failed (broadcast fallback stays): " + th);
        }
    }

    private void onPosted(StatusBarNotification sbn) {
        if (sbn == null || sbn.getKey() == null || sbn.getNotification() == null) {
            return;
        }
        String pkg = sbn.getPackageName();
        if (pkg == null || pkg.equals("com.chenan.flymebobo")
                || pkg.equals("com.android.systemui") || pkg.equals("android")) {
            return;
        }
        Notification n = sbn.getNotification();
        Bundle extras = n.extras;
        // Calls stay with NotificationService (stateful). Skip them.
        if (isCall(sbn, n)) {
            return;
        }
        boolean ongoing = (n.flags & Notification.FLAG_ONGOING_EVENT) != 0
                || (n.flags & Notification.FLAG_FOREGROUND_SERVICE) != 0;
        int progress = extras != null ? extras.getInt("android.progress", 0) : 0;
        int progressMax = extras != null ? extras.getInt("android.progressMax", 0) : 0;
        boolean hasProgress = progressMax > 0
                || (extras != null
                        && extras.getBoolean("android.progressIndeterminate", false));
        boolean isMusic = isMusic(sbn, n);
        // v222: Flyme live activity (实况). Two shapes seen
        // 2026-11-20: Meituan takeout (posted by
        // com.meizu.suggestion, delegated to com.sankuai.meituan,
        // no title/text, content only in the capsule RemoteViews)
        // and the timer (posted directly, title/text present,
        // countdown ticks every second). Both carry is_live and a
        // notification.live.capsule bundle. A live activity must
        // pop (it is not an ongoing-service notification), and a
        // same-key re-post is an UPDATE, not a duplicate.
        boolean isLive = false;
        String liveDelegatedPkg = null;
        android.os.Bundle liveCapsule = null;
        int liveType = -1;
        try {
            if (extras != null) {
                liveCapsule = extras.getBundle("notification.live.capsule");
                if (extras.getBoolean("is_live", false) || liveCapsule != null) {
                    isLive = true;
                }
                liveDelegatedPkg = extras.getString(
                        "notification.live.app.pkg.delegated");
                liveType = extras.getInt("notification.live.type", -1);
            }
        } catch (Throwable ignored) {
        }
        // v187: one line per notification the hook sees — the
        // diagnosis for "never pops" lives here, not in logcat.
        log("HookPosted pkg=" + pkg + " key=" + sbn.getKey()
                + " ongoing=" + ongoing + " hasProgress=" + hasProgress
                + " isMusic=" + isMusic
                + " flags=" + n.flags
                + " cat=" + n.category);
        // v206: QQ Original placeholder — a bare status notification
        // ("QQ音乐 / 当前列表为空") with no session and no MediaStyle,
        // posted ~0.5s before the real player notification on the
        // same key. It is not a song; do not pop a capsule for it.
        if (isMusic && "com.tencent.qqmusic".equals(pkg)) {
            boolean hasSession = false;
            try {
                if (extras != null
                        && extras.getParcelable(Notification.EXTRA_MEDIA_SESSION) != null) {
                    hasSession = true;
                }
            } catch (Throwable ignored) {
            }
            if (!hasSession) {
                String t0 = getText(extras, "android.title");
                String x0 = getText(extras, "android.text");
                boolean titleIsLabel = false;
                try {
                    CharSequence label = app.getPackageManager().getApplicationLabel(
                            app.getPackageManager().getApplicationInfo(pkg, 0));
                    titleIsLabel = t0 != null && label != null
                            && label.toString().equals(t0);
                } catch (Throwable ignored) {
                }
                if (titleIsLabel && isStatusMessage(x0)) {
                    log("HookDrop qq-placeholder pkg=" + pkg
                            + " key=" + sbn.getKey());
                    return;
                }
            }
        }
        boolean isDownload = false;
        int downloadPct = -1;
        if (!isMusic && hasProgress) {
            String channelId = null;
            try {
                channelId = n.getChannelId();
            } catch (Throwable ignored) {
            }
            String t0 = getText(extras, "android.title");
            String x0 = getText(extras, "android.text");
            isDownload = hasDownloadSignal(pkg, channelId, t0, x0);
            // v239: percent for the capsule bar on first pop.
            if (isDownload && extras != null) {
                try {
                    int prog = extras.getInt(
                            android.app.Notification.EXTRA_PROGRESS, 0);
                    int progMax = extras.getInt(
                            android.app.Notification.EXTRA_PROGRESS_MAX,
                            0);
                    if (progMax > 0) {
                        downloadPct = (prog * 100) / progMax;
                        if (downloadPct > 100) {
                            downloadPct = 100;
                        }
                    }
                } catch (Throwable ignored) {
                }
            }
        }
        // v313: Amap navigation starts (user 2026-11-21 "启动了之后就
        // 应该弹出弹窗啊") — the Ajx engine does not push
        // notifyNaviInfo on start (only on turn/distance changes,
        // if at all), so a nav capsule must pop as soon as the
        // navigation notification posts (99910002 foot / 99910003
        // ride). The notification carries no turn data, so pop with
        // the static status text; the first real NaviInfo push
        // updates it in place (same key, isLive). The ongoing
        // filter below would drop this notification, so intercept
        // it here. requestNaviInfo is car-page only — no pull for
        // foot/ride, pop-on-notification is the only start signal.
        if ("com.autonavi.minimap".equals(pkg)) {
            int nid = 0;
            try {
                nid = sbn.getId();
            } catch (Throwable ignored) {
            }
            String naviBiz = null;
            String naviTitle = null;
            if (nid == 99910002) {
                naviBiz = "footnavi";
                naviTitle = "步行导航";
            } else if (nid == 99910003) {
                naviBiz = "ridenavi";
                naviTitle = "骑行导航";
            }
            if (naviBiz != null) {
                // v315: the turn instruction rides in android.title
                // (e.g. "左转,随后四十米右转,进入三钢二路"), NOT in
                // android.text (which is just "高德地图" after the
                // first post). The title is only the generic label
                // ("高德地图持续为您导航" / "高德地图") on the very
                // first post — then fall back to the text.
                String naviContent = getText(extras, "android.title");
                if (naviContent == null || naviContent.isEmpty()
                        || "高德地图持续为您导航".equals(naviContent)
                        || "高德地图".equals(naviContent)) {
                    naviContent = getText(extras, "android.text");
                }
                if (naviContent == null || naviContent.isEmpty()) {
                    naviContent = "正在导航";
                }
                try {
                    // v317: placeholder status 1 (was 0) — the
                    // notification-triggered pop is a live navigation,
                    // not an idle state; the icon stays 0 because the
                    // notification carries no turn-action code.
                    BubbleWindow.getInstance().onNaviInfo(naviBiz,
                            naviTitle, naviContent, 1, 0);
                } catch (Throwable ignored) {
                }
                return;
            }
        }
        // v222: a live activity is ongoing by flags (the timer),
        // but it is not a background-service notification — it must
        // pop, so it is exempt from the ongoing filter.
        if (ongoing && !hasProgress && !isMusic && !isLive) {
            log("HookDrop ongoing-filter pkg=" + pkg
                    + " key=" + sbn.getKey());
            return;
        }
        // Title/text with the full fallback chain (1.x fixed33).
        String title = getText(extras, "android.title");
        String text = getText(extras, "android.text");
        if (text == null || text.isEmpty()) {
            text = getText(extras, "android.bigText");
        }
        if (text == null || text.isEmpty()) {
            text = getLastTextLine(extras);
        }
        if (text == null || text.isEmpty()) {
            text = getLatestMessageText(extras);
        }
        if (text == null || text.isEmpty()) {
            CharSequence ticker = n.tickerText;
            if (ticker != null && !ticker.toString().trim().isEmpty()) {
                text = ticker.toString();
            }
        }
        if (text == null || text.isEmpty()) {
            text = getText(extras, "android.subText");
        }
        // v203 (1.0.5): the notification's own title is
        // authoritative. If it is non-empty, not a credit/lyric
        // line and not the app label, it IS the song name.
        // Only when the title is the app label / a credit line /
        // empty do we resolve: session metadata TITLE first, then
        // parse bigText/text (NOT ticker - ticker is stale on
        // updates, it is a lyric candidate only, never a title
        // source). Then 1.0.5 sticky: same duration (>0 and
        // equal), or no duration but position past 5s, means the
        // same song - keep the old title instead of a flickered one.
        if (isMusic) {
            try {
                CharSequence label = null;
                try {
                    label = app.getPackageManager().getApplicationLabel(
                            app.getPackageManager().getApplicationInfo(pkg, 0));
                } catch (Throwable ignored) {
                }
                boolean titleIsLabel = title != null && !title.isEmpty()
                        && label != null && label.toString().equals(title);
                String candidate = null;
                String candidateArtist = null;
                // v230: true when the candidate IS the
                // notification title (non-Flyme only) — the
                // notification title is the song name for
                // these apps, so a change is a song change
                // by itself (see keepOld below).
                boolean candidateFromNotifTitle = false;
                // v206: Flyme (com.meizu.media.music) is handled
                // separately — its title is the current line
                // (lyric/credits), never the song, and its text /
                // session ARTIST are "song-artist" (song FIRST),
                // the reverse of the "artist-song" other apps use.
                // parseMusicSongArtist would swap them, so resolve
                // Flyme first and skip the generic logic when it
                // yields anything usable.
                boolean flymeResolved = false;
                if ("com.meizu.media.music".equals(pkg)) {
                    String[] flyme = resolveFlymeSongArtist(n, extras, text);
                    if (flyme != null) {
                        candidate = flyme[0];
                        candidateArtist = flyme[1];
                        flymeResolved = true;
                    }
                }
                if (!flymeResolved && title != null && !title.isEmpty()
                        && !titleIsLabel && !isCreditOrLyricTitle(title)) {
                    candidate = title;
                    candidateFromNotifTitle = true;
                } else if (!flymeResolved) {
                    String[] sessionMeta = readSessionMetadata(n);
                    if (sessionMeta != null && sessionMeta[0] != null
                            && !sessionMeta[0].isEmpty()
                            && !isCreditOrLyricTitle(sessionMeta[0])) {
                        candidate = sessionMeta[0];
                        candidateArtist = sessionMeta[1];
                    } else {
                        String[] parsed = parseMusicSongArtist(n, extras, text);
                        if (parsed != null) {
                            candidateArtist = parsed[0];
                            candidate = parsed[1];
                        }
                    }
                }
                if (candidate != null && !candidate.isEmpty()) {
                    String k = sbn.getKey();
                    String lastTitle = k != null
                            ? musicLastTitleByKey.get(k) : null;
                    Long lastDur = k != null
                            ? musicLastDurationByKey.get(k) : null;
                    Long lastPos = k != null
                            ? musicLastPositionByKey.get(k) : null;
                    String lastRawArtist = k != null
                            ? musicLastRawArtistByKey.get(k) : null;
                    long[] dp = readSessionDurationPosition(n);
                    // v224: raw session ARTIST (mirrors v216).
                    // For Flyme it is "song-artist", so it
                    // changes on every skip even when the
                    // position is still near 0 (rapid skips)
                    // and the duration is unchanged.
                    String rawArtist = null;
                    String[] sm = readSessionMetadata(n);
                    if (sm != null && sm.length > 1) {
                        rawArtist = sm[1];
                    }
                    boolean artistChanged = lastRawArtist != null
                            && rawArtist != null
                            && !rawArtist.equals(lastRawArtist);
                    boolean keepOld = false;
                    if (lastTitle != null && !lastTitle.isEmpty()
                            && !lastTitle.equals(candidate) && dp != null) {
                        long dur = dp[0];
                        long pos = dp[1];
                        // v223: a backward position jump (>2s) is
                        // song-change evidence (mirrors v216) - it
                        // beats the duration check, which is still
                        // stale right after a song change (notably
                        // on "previous").
                        boolean posJumpedBack = pos >= 0 && lastPos != null
                                && lastPos.longValue() > 0
                                && pos + 2000 < lastPos.longValue();
                        // v230: a non-Flyme notification title
                        // IS the song name — a change is a song
                        // change by itself. On rapid skips the
                        // session evidence is unavailable (same
                        // artist, position never advanced past
                        // ~0, duration stale), so requiring it
                        // locks the previous song's name.
                        if (!candidateFromNotifTitle && !artistChanged
                                && !posJumpedBack
                                && ((dur > 0 && lastDur != null
                                        && dur == lastDur)
                                        || (dur <= 0 && pos > 5000))) {
                            keepOld = true;
                        }
                    }
                    if (keepOld) {
                        title = lastTitle;
                    } else {
                        title = candidate;
                        if (candidateArtist != null
                                && !candidateArtist.isEmpty()) {
                            text = candidateArtist;
                        }
                        if (k != null) {
                            musicLastTitleByKey.put(k, title);
                            if (dp != null && dp[0] > 0) {
                                musicLastDurationByKey.put(k, dp[0]);
                            }
                            if (dp != null && dp[1] >= 0) {
                                musicLastPositionByKey.put(k, dp[1]);
                            }
                            if (rawArtist != null && !rawArtist.isEmpty()) {
                                musicLastRawArtistByKey.put(k, rawArtist);
                            }
                            if (musicLastTitleByKey.size() > 64) {
                                musicLastTitleByKey.clear();
                                musicLastDurationByKey.clear();
                                musicLastPositionByKey.clear();
                                musicLastRawArtistByKey.clear();
                            }
                        }
                    }
                }
            } catch (Throwable ignored) {
            }
        }
        // v188 (1.0.5 method, user 2026-11-20 "用1.0.5的方法来
        // 写吧"): the notification's own title/text are
        // authoritative and are NEVER overwritten with MediaSession
        // metadata. Both apps here share ONE system MediaSession,
        // so the session's metadata is whichever app last wrote to
        // it — v182's overwrite is why the card showed the wrong
        // song / song name only. The session is used only for
        // lyric resolution, never for song/artist. Only a MISSING
        // artist may be filled from the session metadata (same
        // rule as NotificationService).
        // v183: the session read is wrapped so a failure here (e.g.
        // SystemUI cannot create a MediaController for this token)
        // can never drop the notification — it falls back to the
        // notification title/text above.
        if (isMusic && (text == null || text.isEmpty())) {
            try {
                String[] sessionMeta = readSessionMetadata(n);
                if (sessionMeta != null && sessionMeta[1] != null
                        && !sessionMeta[1].isEmpty()
                        && !isStatusMessage(sessionMeta[1])) {
                    text = sessionMeta[1];
                }
            } catch (Throwable ignored) {
            }
        }
        // v189 (1.0.5): DISPLAY_SUBTITLE is another "Song - Artist"
        // source. If the artist is still missing after the
        // notification parse and the session ARTIST, split the
        // subtitle the same way resolveMusicLyric does.
        if (isMusic && (text == null || text.isEmpty())) {
            try {
                String subArtist = readSessionSubtitleArtist(n);
                if (subArtist != null && !subArtist.isEmpty()
                        && !isStatusMessage(subArtist)) {
                    text = subArtist;
                }
            } catch (Throwable ignored) {
            }
        }
        if ((title == null || title.isEmpty())
                && (text == null || text.isEmpty())) {
            return;
        }
        // Initial lyric: none yet (v184). The live lyric arrives
        // via ACTION_LYRIC from NotificationService; until then the
        // pinned capsule shows song + singer, not the song name
        // disguised as a lyric.
        // v206: Flyme is the exception — its title IS the current
        // line (a lyric when it is not a credits/status line), so
        // use it as the initial lyric; other apps' titles are song
        // names and must never become a lyric.
        String lyrics = null;
        if ("com.meizu.media.music".equals(pkg) && isMusic) {
            String rawTitle = getText(extras, "android.title");
            if (rawTitle != null && !rawTitle.isEmpty()
                    && !isCreditOrLyricTitle(rawTitle)
                    && !isStatusMessage(rawTitle)
                    && (title == null || !rawTitle.equals(title))) {
                lyrics = rawTitle;
            }
        }
        // v222: live-activity text. The normal chain already covers
        // the timer (title = countdown "04:44", text = status).
        // Meituan has NO title/text — fill from the live overlays,
        // then from the capsule RemoteViews text (ReflectionAction
        // setText). Title becomes the (delegated) app label so the
        // capsule reads "美团" + the delivery status.
        String displayPkg = pkg;
        if (isLive) {
            if (liveDelegatedPkg != null && !liveDelegatedPkg.isEmpty()) {
                displayPkg = liveDelegatedPkg;
            }
            if (title == null || title.isEmpty()) {
                String to = getText(extras, "notification.live.titleOverlay");
                if (to != null && !to.isEmpty()) {
                    title = to;
                }
            }
            if (text == null || text.isEmpty()) {
                String co = getText(extras, "notification.live.contentOverlay");
                if (co != null && !co.isEmpty()) {
                    text = co;
                }
            }
            if ((title == null || title.isEmpty()
                    || text == null || text.isEmpty())
                    && liveCapsule != null) {
                String liveText = null;
                try {
                    android.widget.RemoteViews rv = liveCapsule.getParcelable(
                            "notification.live.capsule.content.remote.view");
                    liveText = extractLiveText(rv);
                } catch (Throwable ignored) {
                }
                if (liveText != null && !liveText.isEmpty()) {
                    if (text == null || text.isEmpty()) {
                        text = liveText;
                    }
                    if (title == null || title.isEmpty()) {
                        title = appLabel(displayPkg);
                    }
                }
            }
            if (title == null || title.isEmpty()) {
                title = appLabel(displayPkg);
            }
            log("HookLive pkg=" + pkg + " delegated=" + liveDelegatedPkg
                    + " type=" + liveType + " title=" + title
                    + " text=" + text);
        }
        // v328: push-service delegation — when a notification is
        // posted by a push service (e.g. com.meizu.cloud) on behalf
        // of another app, the key's 2nd field is the real app
        // package (e.g. com.hpbr.bosszhipin for BOSS). Use it for
        // display so the capsule shows the real app (BOSS直聘)
        // instead of "推送服务". Live activities keep their
        // delegated pkg (set above). Only when the key package
        // differs from the posting pkg and is an installed app
        // (appLabel resolves) — normal notifications have
        // keyPkg == pkg, so nothing changes for them.
        if (!isLive && displayPkg.equals(pkg)) {
            String keyPkg = keyPackage(sbn.getKey());
            if (keyPkg != null && !keyPkg.isEmpty()
                    && !keyPkg.equals(pkg)
                    && appLabel(keyPkg) != null) {
                displayPkg = keyPkg;
                log("HookPushDelegated pkg=" + pkg
                        + " displayPkg=" + displayPkg
                        + " key=" + sbn.getKey());
            }
        }
        Bitmap largeIcon = null;
        // v218: art status for the HookMusic log, so the Qishui /
        // Flyme Icon path can be diagnosed from the log instead of
        // guessed at (session art? largeIcon kind? unwrap ok?).
        boolean artSession = false;
        String artLargeIconKind = "null";
        boolean artIconOk = false;
        // v220: notification largeIcon FIRST. The session-first
        // order (v182) read stale art: at post time the session
        // metadata can still be the previous song, so the old
        // cover won and the notification's own (current) cover
        // was never read — "switched songs but still the
        // previous album" (user 2026-11-20). The notification
        // largeIcon is the current song's cover on every post
        // (all five apps, 154x154), so read it first; session
        // ALBUM_ART is only the fallback when the notification
        // carries no image. The v218 push still upgrades to
        // the bigger session art later.
        if (largeIcon == null) {
            try {
                if (extras != null) {
                    Object li = extras.getParcelable("android.largeIcon");
                    if (li instanceof Bitmap) {
                        artLargeIconKind = "Bitmap";
                        largeIcon = (Bitmap) li;
                    } else if (li instanceof android.graphics.drawable.Icon) {
                        artLargeIconKind = "Icon";
                        // v207 (universal): the largeIcon is an Icon,
                        // not a raw Bitmap, for every music app
                        // (Original/Flyme/Migu/NetEase/Qishui all
                        // post Icon typ=BITMAP 154x154). The system
                        // player unwraps it; so do we — this gives
                        // Flyme and Qishui album art, whose sessions
                        // carry no ALBUM_ART.
                        largeIcon = iconToBitmap(
                                (android.graphics.drawable.Icon) li);
                        artIconOk = largeIcon != null;
                    } else if (li != null) {
                        artLargeIconKind = "other";
                    }
                }
            } catch (Throwable ignored) {
            }
        }
        // v220: session art is only the fallback now (see above).
        // v183: same guard as above — a session-read failure must
        // not drop the notification.
        if (largeIcon == null && isMusic) {
            try {
                largeIcon = readSessionAlbumArt(n);
                artSession = largeIcon != null;
            } catch (Throwable ignored) {
                largeIcon = null;
            }
        }
        // v203 (H1): log the resolved title/text for music, so a
        // "title did not refresh" report can be settled from the log.
        if (isMusic) {
            log("HookMusic pkg=" + pkg + " key=" + sbn.getKey()
                    + " title=" + title + " text=" + text
                    + " artSession=" + artSession
                    + " artLargeIcon=" + artLargeIconKind
                    + " artIconOk=" + artIconOk
                    + " art=" + (largeIcon != null));
        }
        NotifData data = new NotifData(sbn.getKey(), displayPkg, title, text,
                sbn.getPostTime(), null, largeIcon, isMusic, lyrics, isDownload);
        data.downloadPct = downloadPct;
        data.isLive = isLive;
        try {
            data.contentIntent = n.contentIntent;
        } catch (Throwable ignored) {
        }
        BubbleWindow.getInstance().onNotification(data);
        // v425 (diagnostic, user 2026-11-22 "查"): restore the v422
        // hook-side push cancel. Push-service notifications
        // (com.meizu.cloud posting for the real app) pop a bubble
        // but their system card stays — cancel it here, in the
        // same hook that popped the bubble. Music/downloads are
        // exempt (they keep their system card).
        if (isPushService(pkg) && !isMusic && !isDownload) {
            cancelViaSystemUI(sbn);
        }
    }

    // v422 (restored in v425): a push-service notification is
    // one whose posting package is com.meizu.cloud, or whose app
    // label is "推送服务".
    private boolean isPushService(String pkg) {
        if (pkg == null) {
            return false;
        }
        if ("com.meizu.cloud".equals(pkg)) {
            return true;
        }
        try {
            if (app != null) {
                android.content.pm.PackageManager pm =
                        app.getPackageManager();
                if (pm != null) {
                    android.content.pm.ApplicationInfo ai =
                            pm.getApplicationInfo(pkg, 0);
                    CharSequence label = pm.getApplicationLabel(ai);
                    if (label != null
                            && "推送服务".equals(label.toString())) {
                        return true;
                    }
                }
            }
        } catch (Throwable ignored) {
        }
        return false;
    }

    // v422 (restored in v425, diagnostic): cancel the push
    // notification through the system's own INotificationManager,
    // from the SystemUI hook. The bubble is popped by this hook,
    // so the cancel happens in the same place. v425 change: the
    // catch logs the FULL cause chain — BubbleNotify_46 showed
    // v488 (user 2026-11-23 "到底怎么屏蔽"): cancel the push
    // notification through the module's own NotificationListenerService
    // instead of INotificationManager. The old path (v422/v425)
    // called cancelNotificationWithTag, which doesn't exist on this
    // Flyme version, then fell back to cancelAllNotifications — and
    // the system refused it: "Package com.meizu.cloud is not owned
    // by uid 10082" (cancelAllNotifications requires the caller to
    // own the package). The listener service can cancel ANY
    // notification by key (BIND_NOTIFICATION_LISTENER_SERVICE), so
    // send it the same ACTION_DISMISS broadcast BubbleWindow uses
    // when a card opens an app. No reflection, no ownership check.
    // v490: also send the POSTING package (com.meizu.cloud) — the
    // key only carries the ORIGINAL package (e.g. com.baidu.netdisk),
    // and the service needs both to try all three cancels (see
    // NotificationService.cancelByKey).
    // v492 (user 2026-11-23 "still didn't intercept", after v488 +
    // v490 both failed in the field): the listener-service cancels
    // never remove a delegated push — the system registers it under
    // the push service and the listener's cancelNotification can't
    // reach it (v488 single-key: CancelDiag stillActive=true;
    // v490 three-way: the shade still shows them). So first try the
    // FlymeIsland route, proven on this exact case: call
    // IStatusBarService.onNotificationClear(pkg, userId, key, 1, 0,
    // visibility) directly from SystemUI — the status bar's own
    // "user swiped the notification away" path, no ownership check.
    // The hook already runs inside SystemUI, so no broadcast is
    // needed. Only if the reflection throws do we fall back to the
    // old ACTION_DISMISS broadcast (listener three-way cancel).
    private void cancelViaSystemUI(
            android.service.notification.StatusBarNotification sbn) {
        if (sbn == null) {
            return;
        }
        String key;
        String postPkg;
        try {
            key = sbn.getKey();
            postPkg = sbn.getPackageName();
        } catch (Throwable t) {
            log("HookCancel threw: " + t);
            return;
        }
        if (key == null) {
            return;
        }
        log("HookCancel key=" + key + " postPkg=" + postPkg);
        try {
            if (clearViaStatusBar(key)) {
                log("HookCancel cleared via onNotificationClear key="
                        + key);
                return;
            }
        } catch (Throwable t) {
            log("HookCancel onNotificationClear threw: " + t);
        }
        try {
            android.content.Intent di = new android.content.Intent(
                    NotificationService.ACTION_DISMISS);
            di.putExtra("key", key);
            if (postPkg != null) {
                di.putExtra("postPkg", postPkg);
            }
            app.sendBroadcast(di);
            log("HookCancel sent dismiss key=" + key);
        } catch (Throwable t) {
            log("HookCancel threw: " + t);
        }
    }

    // v492: IStatusBarService.onNotificationClear, the FlymeIsland
    // route (decompiled from its CoolapkSystemClear). key format is
    // "userId|pkg|id|tag|uid" — the pkg in the key is the ORIGINAL
    // app (e.g. com.baidu.netdisk), which is what onNotificationClear
    // wants. Returns false on any failure so the caller falls back.
    private boolean clearViaStatusBar(String key) {
        try {
            String[] parts = key.split("\\|");
            if (parts.length < 2) {
                return false;
            }
            int userId = Integer.parseInt(parts[0]);
            String origPkg = parts[1];
            Object binder = Class.forName("android.os.ServiceManager")
                    .getMethod("getService", String.class)
                    .invoke(null, "statusbar");
            Class<?> stub = Class.forName(
                    "com.android.internal.statusbar"
                            + ".IStatusBarService$Stub");
            Class<?> svc = Class.forName(
                    "com.android.internal.statusbar.IStatusBarService");
            Object bar = stub.getMethod("asInterface",
                    android.os.IBinder.class).invoke(null, binder);
            Class<?> visCls = Class.forName(
                    "com.android.internal.statusbar"
                            + ".NotificationVisibility");
            Object vis = visCls.getMethod("obtain", String.class,
                    Integer.TYPE, Integer.TYPE, Boolean.TYPE)
                    .invoke(null, key, 0, 1, true);
            svc.getMethod("onNotificationClear", String.class,
                    Integer.TYPE, String.class, Integer.TYPE,
                    Integer.TYPE, visCls)
                    .invoke(bar, origPkg, userId, key, 1, 0, vis);
            return true;
        } catch (Throwable t) {
            log("HookCancel clearViaStatusBar threw: " + t);
            return false;
        }
    }

    // v219: unwrap a notification largeIcon Icon to the
    // bitmap it carries. The v207 reflection on the private
    // mBitmap field never yielded art on the device, and the
    // v210 ActivityThread reflection was unnecessary — the
    // hook already holds the application Context (app). Now
    // just Icon.loadDrawable(app), the supported path, exactly
    // how the system player and our own NotifDiag 1.6 read the
    // same Icon (Flyme/Qishui covers proved live 2026-11-20:
    // notification largeIcon 154x154, session art null). A
    // non-BitmapDrawable result is drawn onto a canvas instead
    // of being dropped, so any drawable yields art.
    private Bitmap iconToBitmap(android.graphics.drawable.Icon icon) {
        if (icon == null || app == null) {
            return null;
        }
        try {
            android.graphics.drawable.Drawable d =
                    icon.loadDrawable(app);
            if (d == null) {
                return null;
            }
            if (d instanceof android.graphics.drawable.BitmapDrawable) {
                return ((android.graphics.drawable.BitmapDrawable) d)
                        .getBitmap();
            }
            int w = d.getIntrinsicWidth();
            int h = d.getIntrinsicHeight();
            if (w <= 0 || h <= 0) {
                w = 154;
                h = 154;
            }
            android.graphics.Bitmap bm = android.graphics.Bitmap
                    .createBitmap(w, h,
                            android.graphics.Bitmap.Config.ARGB_8888);
            android.graphics.Canvas c =
                    new android.graphics.Canvas(bm);
            d.setBounds(0, 0, w, h);
            d.draw(c);
            return bm;
        } catch (Throwable ignored) {
            return null;
        }
    }

    // v222: pull the visible text out of a live-activity capsule
    // RemoteViews (Meituan via com.meizu.suggestion). The
    // notification has no title/text — the status lives only in
    // the RemoteViews (notification.live.capsule.content.remote
    // .view). Reflect into RemoteViews.mActions and collect every
    // setText CharSequence, in order. Any failure returns null
    // (never crash SystemUI for a live activity).
    private static String extractLiveText(android.widget.RemoteViews rv) {
        if (rv == null) {
            return null;
        }
        try {
            java.lang.reflect.Field f =
                    android.widget.RemoteViews.class.getDeclaredField("mActions");
            f.setAccessible(true);
            Object actions = f.get(rv);
            if (!(actions instanceof java.util.List)) {
                return null;
            }
            StringBuilder sb = new StringBuilder();
            for (Object a : (java.util.List<?>) actions) {
                if (a == null) {
                    continue;
                }
                Class<?> c = a.getClass();
                if (!"ReflectionAction".equals(c.getSimpleName())) {
                    continue;
                }
                java.lang.reflect.Field mf =
                        c.getDeclaredField("methodName");
                mf.setAccessible(true);
                if (!"setText".equals(mf.get(a))) {
                    continue;
                }
                java.lang.reflect.Field tf =
                        c.getDeclaredField("type");
                tf.setAccessible(true);
                Object t = tf.get(a);
                if (!(t instanceof Integer)
                        || ((Integer) t).intValue() != 10) {
                    continue;
                }
                java.lang.reflect.Field vf =
                        c.getDeclaredField("value");
                vf.setAccessible(true);
                Object v = vf.get(a);
                if (v instanceof CharSequence) {
                    String s = v.toString().trim();
                    if (!s.isEmpty()) {
                        if (sb.length() > 0) {
                            sb.append(" · ");
                        }
                        sb.append(s);
                    }
                }
            }
            return sb.length() > 0 ? sb.toString() : null;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private String appLabel(String p) {
        if (p == null || app == null) {
            return null;
        }
        try {
            CharSequence l = app.getPackageManager().getApplicationLabel(
                    app.getPackageManager().getApplicationInfo(p, 0));
            return l != null ? l.toString() : null;
        } catch (Throwable ignored) {
            return null;
        }
    }

    /** v328: the owning app package from a notification key.
     * Key format: "0|com.hpbr.bosszhipin|-1439767870|null|10105"
     * — the 2nd field is the app the notification is for, which
     * differs from the posting package when a push service posts
     * on the app's behalf. Null when the key has no such field. */
    private static String keyPackage(String key) {
        if (key == null) {
            return null;
        }
        String[] parts = key.split("\\|");
        return parts.length >= 2 ? parts[1] : null;
    }

    // --- MediaSession metadata (v182, Flyme Island style) ---
    // Read the song/artist from the notification's MediaSession
    // instead of the notification's title/text. Returns
    // [title, artist] or null when there is no usable session.

    /** v189 (1.0.5): the session DISPLAY_SUBTITLE often holds
     * "Song - Artist". Returns the artist half, or null. */
    private String readSessionSubtitleArtist(Notification n) {
        if (n == null || app == null) {
            return null;
        }
        try {
            Bundle ex = n.extras;
            if (ex == null) {
                return null;
            }
            Object tokenObj = ex.getParcelable(
                    Notification.EXTRA_MEDIA_SESSION);
            if (!(tokenObj
                    instanceof android.media.session.MediaSession.Token)) {
                return null;
            }
            android.media.session.MediaSession.Token token =
                    (android.media.session.MediaSession.Token) tokenObj;
            android.media.session.MediaController controller =
                    new android.media.session.MediaController(app, token);
            android.media.MediaMetadata metadata =
                    controller.getMetadata();
            if (metadata == null) {
                return null;
            }
            String subtitle = metadata.getString(
                    android.media.MediaMetadata
                            .METADATA_KEY_DISPLAY_SUBTITLE);
            if (subtitle == null || subtitle.isEmpty()) {
                return null;
            }
            // v206: a "song–artist" subtitle (Flyme) has the artist
            // in the second half; a plain subtitle (e.g. "刘嘉亮")
            // is the artist itself. Previously this returned
            // parsed[0] (the song) or null (plain artist).
            String[] parsed = parseArtistSong(subtitle);
            if (parsed == null) {
                return subtitle;
            }
            return parsed[1];
        } catch (Throwable ignored) {
            return null;
        }
    }

    // v203 (1.0.5): a title that starts with one of these prefixes
    // is a credits/lyric line, never a song name.
    private static boolean isCreditOrLyricTitle(String s) {
        if (s == null) {
            return false;
        }
        String t = s.trim();
        String[] prefixes = {"\u8bcd\uff1a", "\u66f2\uff1a", "\u8bcd:", "\u66f2:",
                "\u4f5c\u8bcd", "\u4f5c\u66f2", "\u7f16\u66f2", "\u6f14\u5531",
                "\u539f\u5531", "\u8bcd\u66f2", "\u4f5c\u8bcd\uff1a",
                "\u4f5c\u66f2\uff1a", "\u7f16\u66f2\uff1a", "\u6f14\u5531\uff1a",
                "\u539f\u5531\uff1a"};
        for (String p : prefixes) {
            if (t.startsWith(p)) {
                return true;
            }
        }
        return false;
    }

    // v203 (1.0.5): session [durationMs, positionMs] for the sticky
    // check. Position is corrected by elapsedRealtime like 1.0.5.
    // Returns null when there is no usable session (e.g. QQ Flyme
    // has no MediaSession token) - then no sticky applies.
    private long[] readSessionDurationPosition(Notification n) {
        if (n == null || app == null) {
            return null;
        }
        try {
            Bundle ex = n.extras;
            if (ex == null) {
                return null;
            }
            Object tokenObj = ex.getParcelable(
                    Notification.EXTRA_MEDIA_SESSION);
            if (!(tokenObj
                    instanceof android.media.session.MediaSession.Token)) {
                return null;
            }
            android.media.session.MediaSession.Token token =
                    (android.media.session.MediaSession.Token) tokenObj;
            android.media.session.MediaController controller =
                    new android.media.session.MediaController(app, token);
            long dur = -1;
            try {
                android.media.MediaMetadata metadata =
                        controller.getMetadata();
                if (metadata != null) {
                    dur = metadata.getLong(
                            android.media.MediaMetadata.METADATA_KEY_DURATION);
                }
            } catch (Throwable ignored) {
            }
            long pos = -1;
            try {
                android.media.session.PlaybackState state =
                        controller.getPlaybackState();
                if (state != null) {
                    pos = state.getPosition();
                    if (pos >= 0) {
                        long last = state.getLastPositionUpdateTime();
                        if (last > 0) {
                            pos += android.os.SystemClock.elapsedRealtime() - last;
                        }
                    }
                }
            } catch (Throwable ignored) {
            }
            return new long[]{dur, pos};
        } catch (Throwable ignored) {
            return null;
        }
    }

    private String[] readSessionMetadata(Notification n) {
        if (n == null || app == null) {
            return null;
        }
        try {
            Bundle ex = n.extras;
            if (ex == null) {
                return null;
            }
            Object tokenObj = ex.getParcelable(
                    Notification.EXTRA_MEDIA_SESSION);
            if (!(tokenObj
                    instanceof android.media.session.MediaSession.Token)) {
                return null;
            }
            android.media.session.MediaSession.Token token =
                    (android.media.session.MediaSession.Token) tokenObj;
            android.media.session.MediaController controller =
                    new android.media.session.MediaController(app, token);
            android.media.MediaMetadata metadata =
                    controller.getMetadata();
            if (metadata == null) {
                return null;
            }
            String song = metadata.getString(
                    android.media.MediaMetadata.METADATA_KEY_TITLE);
            String artist = metadata.getString(
                    android.media.MediaMetadata.METADATA_KEY_ARTIST);
            if (artist == null || artist.isEmpty()) {
                artist = metadata.getString(
                        android.media.MediaMetadata
                                .METADATA_KEY_ALBUM_ARTIST);
            }
            if ((song == null || song.isEmpty())
                    && (artist == null || artist.isEmpty())) {
                return null;
            }
            return new String[]{song, artist};
        } catch (Throwable ignored) {
            return null;
        }
    }

    private android.graphics.Bitmap readSessionAlbumArt(
            Notification n) {
        if (n == null || app == null) {
            return null;
        }
        try {
            Bundle ex = n.extras;
            if (ex == null) {
                return null;
            }
            Object tokenObj = ex.getParcelable(
                    Notification.EXTRA_MEDIA_SESSION);
            if (!(tokenObj
                    instanceof android.media.session.MediaSession.Token)) {
                return null;
            }
            android.media.session.MediaSession.Token token =
                    (android.media.session.MediaSession.Token) tokenObj;
            android.media.session.MediaController controller =
                    new android.media.session.MediaController(app, token);
            android.media.MediaMetadata metadata =
                    controller.getMetadata();
            if (metadata == null) {
                return null;
            }
            return metadata.getBitmap(
                    android.media.MediaMetadata.METADATA_KEY_ALBUM_ART);
        } catch (Throwable ignored) {
            return null;
        }
    }

    // --- detection (ported from NotificationService, SystemUI side) ---

    // v231: QQ Music song-recognition ("\u542c\u6b4c\u8bc6\u66f2") posts an ongoing
    // foreground-service notification ("\u53ef\u8bc6\u522b\u5176\u5b83APP\u91cc\u64ad\u653e\u7684\u6b4c\u66f2")
    // from a known music package, so isKnownMusicApp misclassified
    // it as music. It is listening, not playing -- no MediaSession,
    // no MediaStyle. A title/text containing \u542c\u6b4c\u8bc6\u66f2 or \u53ef\u8bc6\u522b
    // is never music.
    private static boolean isSongRecognition(Notification n) {
        if (n == null || n.extras == null) {
            return false;
        }
        CharSequence t = n.extras.getCharSequence(Notification.EXTRA_TITLE);
        if (t != null) {
            String s = t.toString();
            if (s.contains("\u542c\u6b4c\u8bc6\u66f2") || s.contains("\u53ef\u8bc6\u522b")) {
                return true;
            }
        }
        CharSequence x = n.extras.getCharSequence(Notification.EXTRA_TEXT);
        if (x != null) {
            String s = x.toString();
            if (s.contains("\u542c\u6b4c\u8bc6\u66f2") || s.contains("\u53ef\u8bc6\u522b")) {
                return true;
            }
        }
        return false;
    }

    private static boolean isMusic(StatusBarNotification sbn, Notification n) {
        if (n == null) {
            return false;
        }
        if (isSongRecognition(n)) {
            return false;
        }
        if (Notification.CATEGORY_TRANSPORT.equals(n.category)) {
            return true;
        }
        Bundle ex = n.extras;
        if (ex != null) {
            try {
                if (ex.getParcelable(Notification.EXTRA_MEDIA_SESSION) != null) {
                    return true;
                }
            } catch (Throwable ignored) {
            }
        }
        return isKnownMusicApp(sbn != null ? sbn.getPackageName() : null, n);
    }

    // v186: the ongoing requirement is dropped for the hook path.
    // com.meizu.media.music (Flyme QQ Music) posts a player
    // notification that was not recognised as ongoing, so
    // isKnownMusicApp returned false, isMusic was false, and the
    // ongoing filter dropped it — it never popped. A known music
    // app's notification is music regardless of the ongoing flag;
    // the ongoing filter must never block it.
    private static boolean isKnownMusicApp(String pkg, Notification n) {
        if (pkg == null || n == null) {
            return false;
        }
        return "com.tencent.qqmusic".equals(pkg)
                || "com.netease.cloudmusic".equals(pkg)
                || "com.kugou.android".equals(pkg)
                || "cn.kuwo.player".equals(pkg)
                || "cmccwm.mobilemusic".equals(pkg)
                || "com.ximalaya.ting.android".equals(pkg)
                || "com.luna.music".equals(pkg)
                || "com.meizu.media.music".equals(pkg)
                || pkg.contains("qqmusic")
                || pkg.contains("kugou")
                || pkg.contains("kuwo");
    }

    // Call check (subset of NotificationService.isCallNotification):
    // enough to recognise a call so the hook skips it. The full
    // call state machine stays in NotificationService.
    private static boolean isCall(StatusBarNotification sbn, Notification n) {
        if (n == null) {
            return false;
        }
        if (sbn != null && "com.android.incallui".equals(sbn.getPackageName())) {
            return true;
        }
        if ("call".equals(n.category)) {
            return true;
        }
        if (n.fullScreenIntent != null && sbn != null) {
            String pkg = sbn.getPackageName();
            if (pkg != null) {
                String lower = pkg.toLowerCase();
                if (lower.contains("dialer") || lower.contains("phone")
                        || lower.contains("telecom") || lower.contains("incallui")
                        || lower.contains("call")) {
                    return true;
                }
            }
        }
        return false;
    }

    // Dual-condition download signal (v110).
    private static boolean hasDownloadSignal(String pkg, String channelId,
            String title, String text) {
        // v241: 安装 no longer excluded — app-store install
        // progress is a download capsule too (mirrors
        // NotificationService). The signal gate below still
        // blocks a stray 安装 from a non-store app.
        if (containsAny(title, "上传", "备份", "同步")
                || containsAny(text, "上传", "备份", "同步")) {
            return false;
        }
        if (containsAny(title, "下载") || containsAny(text, "下载")) {
            return true;
        }
        if (channelId != null
                && channelId.toLowerCase(java.util.Locale.US).contains("download")) {
            return true;
        }
        return pkg != null && isDownloaderPkg(pkg);
    }

    private static boolean isDownloaderPkg(String pkg) {
        if (pkg.equals("com.meizu.mstore")
                || pkg.equals("com.xiaomi.market")
                || pkg.equals("com.huawei.appmarket")
                || pkg.equals("com.oppo.market")
                || pkg.equals("com.vivo.appstore")
                || pkg.equals("com.bbk.appstore")
                || pkg.equals("com.tencent.android.qqdownloader")
                || pkg.equals("com.android.vending")) {
            return true;
        }
        if (pkg.equals("com.android.browser")
                || pkg.equals("com.UCMobile")
                || pkg.equals("com.tencent.mtt")
                || pkg.equals("com.baidu.searchbox")
                || pkg.equals("org.mozilla.firefox")
                || pkg.equals("com.microsoft.emmx")
                || pkg.equals("com.quark.browser")) {
            return true;
        }
        String lower = pkg.toLowerCase();
        return lower.contains("download") || lower.contains("downloader");
    }

    private static boolean containsAny(String s, String... words) {
        if (s == null) {
            return false;
        }
        for (String w : words) {
            if (w != null && s.contains(w)) {
                return true;
            }
        }
        return false;
    }

    /** v184: a status message is never a song/artist. QQ Music
     * Flyme's EXTRA_TEXT is "当前列表为空，点击选择歌曲播放" — it
     * must not be used as the song, the artist, or a lyric, or the
     * capsule/card show it and a song change (same status text)
     * is deduped away as a duplicate. */
    private static boolean isStatusMessage(String s) {
        if (s == null || s.isEmpty()) {
            return false;
        }
        return s.contains("列表为空") || s.contains("点击选择")
                || s.contains("点击播放") || s.contains("暂无")
                || s.contains("未播放") || s.contains("没有正在播放");
    }

    /** v186: the artist often lives in android.subText while the
     * song is in bigText/ticker. Returns it when it is a real name,
     * else "" (never a status message). */
    private static String subTextArtist(Bundle extras) {
        String sub = getText(extras, "android.subText");
        if (sub == null || sub.isEmpty() || isStatusMessage(sub)) {
            return "";
        }
        return sub;
    }

    // v206: Flyme (com.meizu.media.music) song/artist. Its title
    // is the current line (lyric/credits), never the song, and its
    // text / session ARTIST are "song-artist" (song FIRST) — the
    // reverse of the "artist-song" other apps use. Sources, in
    // order: DISPLAY_SUBTITLE "song–artist" (en dash) or a plain
    // artist name; session ARTIST "song-artist"; notification text
    // ("artist - song" or "song-artist - song"). The session ALBUM
    // is NEVER used for the song name (v214, user 2026-11-20
    // "有些歌他读的是专辑名"): for some songs ALBUM holds the real
    // album title, not the song. Returns [song, artist] (either may
    // be null) or null when nothing usable is found.
    private String[] resolveFlymeSongArtist(Notification n,
            Bundle extras, String text) {
        String song = null;
        String artist = null;
        String subtitle = readSessionSubtitle(n);
        if (subtitle != null && !subtitle.isEmpty()
                && !isStatusMessage(subtitle)
                && !isCreditOrLyricTitle(subtitle)) {
            String[] p = parseArtistSong(subtitle);
            if (p != null) {
                song = p[0];
                artist = p[1];
            } else {
                artist = subtitle;
            }
        }
        // v211 (user 2026-11-20 "artist"): Flyme's session ARTIST
        // is "song-artist" (song FIRST) and it carries the new song
        // at song-change time, while the ALBUM can still hold the
        // previous song. Parse ARTIST before ALBUM so the song name
        // comes from the artist field, not the stale album.
        if (song == null) {
            String[] meta = readSessionMetadata(n);
            String metaArtist = (meta != null) ? meta[1] : null;
            if (metaArtist != null && !metaArtist.isEmpty()
                    && !isStatusMessage(metaArtist)
                    && !isCreditOrLyricTitle(metaArtist)) {
                String[] p = parseArtistSong(metaArtist);
                if (p != null) {
                    song = p[0];
                    if (artist == null) {
                        artist = p[1];
                    }
                }
            }
        }
        // v214: the notification text is the fallback when the
        // session ARTIST is only an artist name. The text is
        // "artist - song" (artist first) or "song-artist - song";
        // when we already know the artist, the song is the part
        // after the artist. Otherwise fall back to song-first
        // parsing.
        if (song == null) {
            String src = text;
            if (src != null && !src.isEmpty() && !isStatusMessage(src)) {
                if (artist != null && !artist.isEmpty()) {
                    int idx = src.indexOf(artist);
                    if (idx >= 0) {
                        String rest = src.substring(
                                idx + artist.length());
                        rest = rest.trim();
                        int i = 0;
                        while (i < rest.length()) {
                            char c = rest.charAt(i);
                            if (c == ' ' || c == '-'
                                    || c == 0x2013 || c == 0x2014) {
                                i++;
                            } else {
                                break;
                            }
                        }
                        rest = rest.substring(i).trim();
                        int dash = rest.indexOf(" - ");
                        if (dash > 0) {
                            rest = rest.substring(0, dash).trim();
                        }
                        if (!rest.isEmpty()
                                && !isCreditOrLyricTitle(rest)
                                && !rest.equals(artist)) {
                            song = rest;
                        }
                    }
                }
                if (song == null) {
                    String[] p = parseArtistSong(src);
                if (p != null) {
                    song = p[0];
                    if (artist == null) {
                        String after = p[1];
                        int sep = after.indexOf(" - ");
                        artist = (sep > 0)
                                ? after.substring(0, sep).trim()
                                : after.trim();
                    }
                }
                }
            }
        }
        if ((song == null || song.isEmpty())
                && (artist == null || artist.isEmpty())) {
            return null;
        }
        return new String[]{song, artist};
    }

    // v206: the session DISPLAY_SUBTITLE as a plain string, or
    // null when there is no session / no subtitle.
    private String readSessionSubtitle(Notification n) {
        if (n == null || app == null) {
            return null;
        }
        try {
            Bundle ex = n.extras;
            if (ex == null) {
                return null;
            }
            Object tokenObj = ex.getParcelable(
                    Notification.EXTRA_MEDIA_SESSION);
            if (!(tokenObj
                    instanceof android.media.session.MediaSession.Token)) {
                return null;
            }
            android.media.session.MediaSession.Token token =
                    (android.media.session.MediaSession.Token) tokenObj;
            android.media.session.MediaController controller =
                    new android.media.session.MediaController(app, token);
            android.media.MediaMetadata metadata =
                    controller.getMetadata();
            if (metadata == null) {
                return null;
            }
            return metadata.getString(
                    android.media.MediaMetadata
                            .METADATA_KEY_DISPLAY_SUBTITLE);
        } catch (Throwable ignored) {
            return null;
        }
    }

    // v206: the session ALBUM as a plain string, or null when
    // there is no session / no album.
    private String readSessionAlbum(Notification n) {
        if (n == null || app == null) {
            return null;
        }
        try {
            Bundle ex = n.extras;
            if (ex == null) {
                return null;
            }
            Object tokenObj = ex.getParcelable(
                    Notification.EXTRA_MEDIA_SESSION);
            if (!(tokenObj
                    instanceof android.media.session.MediaSession.Token)) {
                return null;
            }
            android.media.session.MediaSession.Token token =
                    (android.media.session.MediaSession.Token) tokenObj;
            android.media.session.MediaController controller =
                    new android.media.session.MediaController(app, token);
            android.media.MediaMetadata metadata =
                    controller.getMetadata();
            if (metadata == null) {
                return null;
            }
            return metadata.getString(
                    android.media.MediaMetadata.METADATA_KEY_ALBUM);
        } catch (Throwable ignored) {
            return null;
        }
    }

    /** v184: robust song/artist extraction for a music notification
     * whose title is the app name. Tries, in order: the
     * "Artist - Song" dash format in ticker / bigText / text;
     * bigText split into lines (first line song, second artist);
     * textLines the same way. Returns [artist, song] or null. */
    private static String[] parseMusicSongArtist(Notification n,
            Bundle extras, String text) {
        // v203 (1.0.5): ticker is NOT a title source - it is stale
        // on updates (music apps refresh bigText/text, not ticker),
        // so it is removed from the title candidates. It stays a
        // lyric candidate elsewhere.
        String[] candidates = new String[]{
                getText(extras, "android.bigText"),
                text,
        };
        for (String c : candidates) {
            if (c == null || c.isEmpty() || isStatusMessage(c)) {
                continue;
            }
            String[] parsed = parseArtistSong(c);
            if (parsed != null) {
                return parsed;
            }
            // Multi-line bigText: first non-empty line is the song,
            // second is the artist.
            String[] lines = c.split("\n");
            String first = null;
            String second = null;
            for (String ln : lines) {
                if (ln == null) {
                    continue;
                }
                String t = ln.trim();
                if (t.isEmpty() || isStatusMessage(t)) {
                    continue;
                }
                if (first == null) {
                    first = t;
                } else if (second == null) {
                    second = t;
                    break;
                }
            }
            if (first != null && second != null) {
                return new String[]{second, first};
            }
            if (first != null) {
                return new String[]{subTextArtist(extras), first};
            }
        }
        // textLines array, same line logic.
        try {
            if (extras != null) {
                CharSequence[] lines =
                        extras.getCharSequenceArray("android.textLines");
                if (lines != null) {
                    String first = null;
                    String second = null;
                    for (CharSequence ln : lines) {
                        if (ln == null) {
                            continue;
                        }
                        String t = ln.toString().trim();
                        if (t.isEmpty() || isStatusMessage(t)) {
                            continue;
                        }
                        if (first == null) {
                            first = t;
                        } else if (second == null) {
                            second = t;
                            break;
                        }
                    }
                    if (first != null && second != null) {
                        return new String[]{second, first};
                    }
                    if (first != null) {
                        return new String[]{subTextArtist(extras), first};
                    }
                }
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    private static String[] parseArtistSong(String s) {
        if (s == null || s.isEmpty()) {
            return null;
        }
        int dash = s.indexOf('\u2013');
        if (dash < 0) {
            dash = s.indexOf('-');
        }
        if (dash < 0) {
            // Migu sends "Artist · Album" (U+00B7 middle dot) —
            // the second part is the ALBUM name, not the song.
            // Split on it so the artist comes out clean.
            dash = s.indexOf('\u00b7');
        }
        if (dash <= 0 || dash >= s.length() - 1) {
            return null;
        }
        String a = s.substring(0, dash).trim();
        String b = s.substring(dash + 1).trim();
        if (a.isEmpty() || b.isEmpty()) {
            return null;
        }
        return new String[]{a, b};
    }

    private static String getText(Bundle bundle, String str) {
        if (bundle == null) {
            return null;
        }
        try {
            CharSequence cs = bundle.getCharSequence(str);
            return cs != null ? cs.toString() : null;
        } catch (Throwable th) {
            return null;
        }
    }

    private static String getLastTextLine(Bundle bundle) {
        if (bundle == null) {
            return null;
        }
        try {
            CharSequence[] lines = bundle.getCharSequenceArray("android.textLines");
            if (lines != null) {
                for (int i = lines.length - 1; i >= 0; i--) {
                    if (lines[i] != null && !lines[i].toString().trim().isEmpty()) {
                        return lines[i].toString();
                    }
                }
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    private static String getLatestMessageText(Bundle bundle) {
        if (bundle == null) {
            return null;
        }
        try {
            android.os.Parcelable[] messages =
                    bundle.getParcelableArray("android.messages");
            if (messages != null) {
                for (int i = messages.length - 1; i >= 0; i--) {
                    Object msg = messages[i];
                    if (msg == null) {
                        continue;
                    }
                    if ("android.app.Notification$MessagingStyle$Message"
                            .equals(msg.getClass().getName())) {
                        try {
                            java.lang.reflect.Method getText =
                                    msg.getClass().getMethod("getText");
                            Object t = getText.invoke(msg);
                            if (t instanceof CharSequence
                                    && !t.toString().trim().isEmpty()) {
                                return t.toString();
                            }
                        } catch (Throwable ignored) {
                        }
                    }
                }
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    private void log(String msg) {
        // v187: FileLog (file + logcat). The hook is the pop path
        // since v177 but only wrote to logcat, so the exportable
        // log file never got a single hook line — the user had no
        // log at all to send when a notification did not pop.
        try {
            FileLog.i(msg);
        } catch (Throwable ignored) {
        }
    }
}
