package com.chenan.flymebobo;

import android.content.Intent;
import android.os.Bundle;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;

/**
 * flymebobo 2.0 — 通知监听 (NotificationService).
 *
 * Reads notifications from the system and broadcasts them to the
 * bubble window, which lives in the SystemUI process (see XposedEntry).
 * Fresh code for 2.0 (not copied from 1.x).
 *
 * Why a broadcast: the bubble window runs inside SystemUI, so it can
 * draw without the overlay permission (SystemUI already holds it).
 * This service runs in the module's own process, so it hands the
 * notification over via a broadcast instead of touching the window
 * directly.
 *
 * Rules (kept from the 1.x behaviour the user confirmed):
 *  - only real notifications (no ongoing / foreground-service ones
 *    unless they carry progress);
 *  - the notification is cancelled after reading so it does not sit
 *    in the shade as well as in a bubble.
 */
public class NotificationService extends NotificationListenerService {
    public static final String ACTION_NOTIFY =
            "com.chenan.flymebobo.NOTIFY";
    /** v427 (push diagnostic): cancelNotif reports its 500ms
     * still-active check to SystemUI, so the result lands in the
     * SystemUI log the user sends — the listener process's own
     * FileLog lines never appear in those files, so without this
     * we cannot tell whether the listener's cancel ran or worked.
     * extras: key, stillActive, pkg, tag, id. */
    public static final String ACTION_CANCEL_DIAG =
            "com.chenan.flymebobo.CANCEL_DIAG";
    /** Live lyric line, service -> SystemUI (BubbleWindow). */
    public static final String ACTION_LYRIC =
            "com.chenan.flymebobo.LYRIC";
    public static final String ACTION_DOWNLOAD =
            "com.chenan.flymebobo.DOWNLOAD";
    /** v242: download finished/cancelled and its notification
     * is gone — service -> SystemUI, remove the capsule. */
    public static final String ACTION_DOWNLOAD_REMOVE =
            "com.chenan.flymebobo.DOWNLOAD_REMOVE";
    public static final String ACTION_CALL =
            "com.chenan.flymebobo.CALL";

    // v125 (Flyme Island): SystemUI -> module, fire one of the
    // incallui notification's own actions. extra "command" is one
    // of answer / reject / hangup / speaker_on / speaker_off.
    public static final String ACTION_CALL_CONTROL =
            "com.chenan.flymebobo.CALL_CONTROL";
    /** Card open (v143): SystemUI asks the module to dismiss a
     * notification after the user opened its app from the card. The
     * module is the NotificationListenerService, so it can cancel any
     * notification by key. */
    public static final String ACTION_DISMISS =
            "com.chenan.flymebobo.DISMISS";
    // v137: fireCallAction reports its outcome here so SystemUI can
    // Toast it (module-side Toast may not display).
    public static final String ACTION_CALL_RESULT =
            "com.chenan.flymebobo.CALL_RESULT";
    public static final String CALL_RINGING = "ringing";
    public static final String CALL_OFFHOOK = "offhook";
    public static final String CALL_IDLE = "idle";
    /** Start/stop the lyric listener, SystemUI -> service. Sent by
     * BubbleWindow.togglePin when a music capsule is pinned or
     * unpinned. Extras: "key", "listen" (boolean). */
    public static final String ACTION_LYRIC_LISTEN =
            "com.chenan.flymebobo.LYRIC_LISTEN";
    /** Music card controls (v171, 1.x approach): SystemUI -> module.
     * extra "command" is one of toggle / next / prev / favorite /
     * seek (seek also carries "seek_position" ms). The module owns
     * the MediaController, so it executes the transport control. */
    public static final String ACTION_MUSIC_CONTROL =
            "com.chenan.flymebobo.MUSIC_CONTROL";
    /** Music progress (v171): module -> SystemUI, 1s tick while a
     * session is active. Extras: "position" (ms), "duration" (ms),
     * "playing" (boolean), "key" (music notification key). */
    public static final String ACTION_MUSIC_PROGRESS =
            "com.chenan.flymebobo.MUSIC_PROGRESS";
    /** v259: APP -> SystemUI, a feature switch flipped. Extras:
     * "key" (notif/music/dl/code/live), "enabled" (boolean). */
    public static final String ACTION_FEATURE_TOGGLE =
            "com.chenan.flymebobo.FEATURE_TOGGLE";
    /** v504 (2.4.2): APP -> SystemUI, a per-app bubble rule
     * changed in AppRulesActivity. SystemUI reloads AppRules at
     * once, no restart needed. */
    public static final String ACTION_APP_RULES =
            "com.chenan.flymebobo.APP_RULES";
    /** v508: APP -> SystemUI, a scene exception changed in
     * SceneExceptionsActivity. Extra: "sceneexc_flat" (the whole
     * stored set, flattened). SystemUI applies it at once. */
    public static final String ACTION_SCENE_EXC =
            "com.chenan.flymebobo.SCENE_EXC";
    /** v511: APP -> SystemUI, the online game list was
     * refreshed. Extra: "gamelist_flat" (the whole list,
     * flattened). SystemUI applies it at once. */
    public static final String ACTION_GAME_LIST =
            "com.chenan.flymebobo.GAME_LIST";
    /** v438/v439: APP -> SystemUI, an appearance slider moved.
     * Extra: "dockPct" (int 0-85) — the anchor Y of the whole unit
     * (capsules + fan, they are one piece). SystemUI re-reads the
     * prefs and re-lays out at once, no restart needed. */
    public static final String ACTION_APPEARANCE =
            "com.chenan.flymebobo.APPEARANCE";
    /** v471: APP -> SystemUI, reload all settings once. Sent by
     * MainActivity.onCreate after restoreToPrefs() restores the
     * local backup file into prefs (user 2026-11-23 “改成打开
     * 自动加载一次”：文件写了但重启后不生效，是因为 SystemUI
     * 在 App 打开之前已经按旧 prefs 载完了；打开 App 时推一次
     * 重载，SystemUI 重新读一遍 FeatureFlags + Appearance 并
     * 重排/重着色，开关和功能就对上了）。No extras. */
    public static final String ACTION_RELOAD_SETTINGS =
            "com.chenan.flymebobo.RELOAD_SETTINGS";
    /** v311: Amap live navigation (foot/ride) — Amap process ->
     * SystemUI, turn + distance from ModuleRoute.notifyNaviInfo.
     * Extras: "bizType" (footnavi/ridenavi), "title", "content",
     * "status", "icon". */
    public static final String ACTION_NAVI_INFO =
            "com.chenan.flymebobo.NAVI_INFO";
    /** v311: Amap navigation ended — service -> SystemUI, drop
     * the nav capsule. Extra: "bizType". */
    public static final String ACTION_NAVI_END =
            "com.chenan.flymebobo.NAVI_END";
    /** v312: Amap hook install status — Amap process -> SystemUI,
     * so the result lands in BubbleNotify.log (the Amap process
     * cannot write the module's FileLog itself). Extras:
     * "status" (installed/failed), "error" (failure only). */
    public static final String ACTION_NAVI_HOOK_STATUS =
            "com.chenan.flymebobo.NAVI_HOOK_STATUS";
    /** v318 (diagnostic): remaining nav data — Amap process ->
     * SystemUI. Extras: "remainDistance", "remainTime",
     * "distance", "costTime" (all strings, may be null), and
     * "src" (notifyNaviInfo / onNotifyChange-foot / -ride). */
    public static final String ACTION_NAVI_REMAIN =
            "com.chenan.flymebobo.NAVI_REMAIN";
    /** v320: Amap bus live navigation — Amap process -> SystemUI.
     * ACTION_BUS_INFO (real-time arrival, mode A): extras "line",
     * "station", "stationLeft", "arrival", "busX", "busY" (all
     * strings). ACTION_BUS_ROUTE (station list, mode B): extras
     * "busname", "direction", "startName", "endName", "passNames"
     * (space-separated intermediate stations), "passCoords"
     * (comma-separated lng,lat pairs). ACTION_BUS_HOOK_STATUS:
     * extras "status" (installed/failed), "error". */
    public static final String ACTION_BUS_INFO =
            "com.chenan.flymebobo.BUS_INFO";
    public static final String ACTION_BUS_ROUTE =
            "com.chenan.flymebobo.BUS_ROUTE";
    public static final String ACTION_BUS_HOOK_STATUS =
            "com.chenan.flymebobo.BUS_HOOK_STATUS";
    /** v322: Amap foot navigation "compute it yourself" — Amap
     * process -> SystemUI. The foot JS never pushes notifyNaviInfo
     * and the foot notification never updates, so the route
     * (polyline + total length/time) is captured at nav start and
     * the capsule is computed from the live position projected
     * onto the polyline. Extras: "totalLength" (int, meters),
     * "totalTime" (int, seconds), "points" (String, compact
     * "lat,lng;lat,lng;..." polyline). */
    public static final String ACTION_FOOT_ROUTE =
            "com.chenan.flymebobo.FOOT_ROUTE";
    /** v322 (diagnostic): foot route JSON structure — Amap process
     * -> SystemUI. The Java OnFootNaviPath is a legacy empty shell
     * (mAllPoints never populated), so the only hope is the route
     * JSON JS passes to ModuleFoot.jumpFootNavi(String). Extras:
     * "jsonKeys" (top-level keys), "hasPolyline" (boolean),
     * "jsonLen" (int). FileLogged so the user can report whether
     * the JSON carries a polyline at all. */
    public static final String ACTION_FOOT_DIAG =
            "com.chenan.flymebobo.FOOT_DIAG";
    /** v323: foot route hook install status — Amap process ->
     * SystemUI. Extras: "status" (installed/failed), "error".
     * The FootDiag never appeared even after a fresh nav start,
     * so the hook itself may have failed to install (its failure
     * only went to logcat). */
    public static final String ACTION_FOOT_HOOK_STATUS =
            "com.chenan.flymebobo.FOOT_HOOK_STATUS";
    /** v326: route details JSON diagnostic — Amap process ->
     * SystemUI. Extras: "mode" (foot/ride), "action" (the jump
     * str, e.g. footPreview/footNavi/ridePreview/rideNavi, or
     * "ajxMessage" for handleAjxMessage), "jsonKeys" (top-level
     * keys), "hasSteps" (a steps-like array exists),
     * "jsonLen". The jump(String, String) hook: str2 is the
     * complete route JSON that JS renders as the route options
     * + "路线详情" step list. */
    public static final String ACTION_ROUTE_DIAG =
            "com.chenan.flymebobo.ROUTE_DIAG";
    /** v327: route jump hook install status — Amap process ->
     * SystemUI. Extras: "status" (installed/failed), "detail"
     * (which of jumpFoot/jumpRide/ajxFoot/ajxRide installed).
     * v326 had no status for the jump hook, so a missing
     * RouteDiag could not tell a failed install from a
     * never-called jump. */
    public static final String ACTION_ROUTE_HOOK_STATUS =
            "com.chenan.flymebobo.ROUTE_HOOK_STATUS";

    /** Full 1.x notification dedup + recognition (user 2026-11-19
     * "直接套用版本1的全部通知去重，识别"), ported to 2.0:
     *  1. Key cache, content-aware — same key + identical content is
     *     a repeat (dropped); same key + new content is a new message
     *     (pops). TTL 1 hour; pre-filled on reconnect.
     *  2. Cross-package content fingerprint (title + text, no pkg),
     *     15s — the same message via push service + app only pops
     *     once (1.x fixed5, window widened fixed10).
     *  3. Group summary — FLAG_GROUP_SUMMARY whose child was already
     *     shown; text-containment summaries; "[N条]sender" aggregated
     *     summaries (same pkg + sender within 60s) (1.x fixed2/4/10).
     *  4. PostTime STALE (Flyme Island, 1.x fixed11) — a notification
     *     whose postTime is older than the last seen for its key is a
     *     stale re-delivery, dropped.
     * Media / call paths are untouched (1.x: media follows MediaSession). */
    private static final long DEDUP_TTL_MS = 3600_000L; // 1 hour
    private static final int DEDUP_MAX_KEYS = 256;
    // Full 1.x dedup (v105, user "完整套用"): absolute stale window
    // (fixed28) and the flags mask (fixed28/31/135/137).
    private static final long STALE_WINDOW_MS = 5000L;
    private static final String PKG_MEITUAN = "com.sankuai.meituan";
    private static final String PKG_ELEME = "me.ele";
    private static final String PKG_AMAP = "com.autonavi.minimap";
    private static final long FINGERPRINT_WINDOW_MS = 15_000L;
    private static final long SUMMARY_WINDOW_MS = 60_000L;
    private static final long GROUP_WINDOW_MS = 10_000L;
    private final java.util.Map<String, Long> seenKeys =
            new java.util.HashMap<>();
    private final java.util.Map<String, String> seenContent =
            new java.util.HashMap<>();
    private final java.util.Map<String, Long> recentFingerprints =
            new java.util.HashMap<>();
    // Vendor push services (v144, ported from 1.x fixed36/fixed54).
    // 1.0.5 dropped these outright; 2.0 must NOT — BOSS/鱼泡 in the
    // background only send via the push service, so dropping them
    // outright would lose those notifications entirely. The smart
    // filter below drops a push copy ONLY when the real app already
    // posted the same content recently.
    // v394: all system push services (user 2026-11-22 "把所有系统
    // 的推送都加上去") — every OEM / system push package that can
    // post a notification on another app's behalf. The label
    // fallback in isPushService ("推送服务") already catches any
    // of these whose app label is exactly that; the explicit list
    // is the reliable path.
    private static final java.util.Set<String> PUSH_SERVICE_PACKAGES =
            new java.util.HashSet<String>(java.util.Arrays.asList(
            // Meizu / Flyme (v393: com.meizu.cloud posts BOSS).
            "com.meizu.cloud",
            "com.meizu.cloud.pushservice", "com.meizu.push",
            "com.meizu.flyme.push", "com.meizu.cloud.pushsdk",
            // Xiaomi / Redmi / MIUI / HyperOS.
            "com.xiaomi.xmsf", "com.xiaomi.push",
            "com.xiaomi.mipush", "com.xiaomi.xmpush",
            // Huawei / EMUI / HarmonyOS.
            "com.huawei.android.pushagent", "com.huawei.hms",
            "com.huawei.android.push",
            // Honor / MagicOS (split from Huawei).
            "com.hihonor.push", "com.honor.push",
            "com.hihonor.android.pushagent",
            "com.honor.android.pushagent",
            // OPPO / ColorOS / OnePlus / Realme (HeyTap).
            "com.coloros.mcs", "com.heytap.mcs", "com.heytap.msp",
            "com.oppo.push", "com.coloros.push", "com.heytap.push",
            "com.oneplus.push", "com.oneplus.mcs",
            "com.realme.push", "com.realme.mcs",
            // Vivo / Funtouch OS / OriginOS.
            "com.vivo.pushservice", "com.vivo.push",
            "com.vivo.push.sdk",
            // Samsung / One UI.
            "com.samsung.android.push",
            "com.samsung.android.mobileservice",
            "com.sec.android.push", "com.samsung.push",
            // Google / FCM.
            "com.google.android.gms", "com.google.android.gsf",
            // ZTE / Nubia.
            "com.zte.push", "com.nubia.push",
            // Lenovo / Motorola.
            "com.lenovo.push", "com.motorola.push",
            // Sony.
            "com.sony.nfx", "com.sonymobile.push",
            // LG / HTC / ASUS / Sharp / TCL / Nokia.
            "com.lge.push", "com.htc.push", "com.asus.push",
            "com.sharp.push", "com.tcl.push",
            "com.hmdglobal.push",
            // Baidu / 360 / Meitu / Gionee / Coolpad / Smartisan.
            "com.baidu.push", "com.baidu.android.pushservice",
            "com.qihoo.push", "com.meitu.push",
            "com.gionee.push", "com.coolpad.push",
            "com.smartisan.push",
            // Generic.
            "com.android.push"));
    // Who last posted each content digest (title+text) — so the push
    // filter can tell whether the real app already posted it. Kept
    // in step with recentFingerprints by isFingerprintRepeat.
    private final java.util.Map<String, String> recentContentPoster =
            new java.util.HashMap<>();
    // Same-package fingerprint (v96, ported from 1.x isDuplicateNotif):
    // pkg + title + text, 15s window.
    private final java.util.Map<String, Long> recentPkgFingerprints =
            new java.util.HashMap<>();
    private final java.util.Map<String, Long> recentNotifGroups =
            new java.util.HashMap<>();
    // Per-package last fingerprint (1.x fixed28): identical
    // id:postTime:title/text:progress means the exact same
    // notification was re-delivered — drop with zero action. No time
    // window; LRU size 32.
    private final java.util.LinkedHashMap<String, String> lastNotifFingerprintByPkg =
            new java.util.LinkedHashMap<String, String>(32, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(
                        java.util.Map.Entry<String, String> eldest) {
                    return size() > 32;
                }
            };
    // Per-key message lifecycle (1.x fixed11/12): each state remembers
    // the postTime and the set of individual message digests already
    // seen for that key, so a summary that only repeats already-seen
    // messages is not fresh. A second map tracks the latest message
    // per conversation. No time window — an old re-delivery is stale
    // by postTime alone.
    private final java.util.Map<String, MsgState> msgStates =
            new java.util.HashMap<>();
    private final java.util.Map<String, String> lastMsgByConversation =
            new java.util.HashMap<>();
    private final java.util.Map<String, Long> lastPostTimeByKey =
            new java.util.HashMap<>();
    private final java.util.Map<String, String> lastTitleByPkg =
            new java.util.HashMap<>();
    private final java.util.Map<String, String> lastTextByPkg =
            new java.util.HashMap<>();
    private final java.util.Map<String, Long> lastShownAtByPkg =
            new java.util.HashMap<>();
    private final java.util.Map<String, Long> lastSummarySenderAt =
            new java.util.HashMap<>();

    private static String contentDigest(String title, String text) {
        return contentDigest(title, text, -1, -1);
    }

    // v225: unwrap a notification largeIcon (Bitmap or Icon)
    // to a Bitmap. A straight Bitmap cast throws
    // ClassCastException for every music app (they all post
    // an Icon), so the broadcast never carried art. Mirrors
    // the hook's iconToBitmap; this Service is the Context.
    private android.graphics.Bitmap bitmapFromLargeIcon(Object li) {
        if (li == null) {
            return null;
        }
        try {
            if (li instanceof android.graphics.Bitmap) {
                return (android.graphics.Bitmap) li;
            }
            if (li instanceof android.graphics.drawable.Icon) {
                android.graphics.drawable.Drawable d =
                        ((android.graphics.drawable.Icon) li)
                                .loadDrawable(this);
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
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    // 1.x fixed133: include progress in the content, so a progress
    // update (same key/title/text, new progress) counts as new content
    // and refreshes the bubble instead of being dropped as a
    // duplicate.
    private static String contentDigest(String title, String text,
            int progress, int progressMax) {
        return (title == null ? "" : title) + "\u0001"
                + (text == null ? "" : text) + "\u0001"
                + progress + "/" + progressMax;
    }

    /** Layer 1 — true if this exact notification (same key, same
     * content) was already seen within the TTL. */
    /** First non-empty string among the given, else null. */
    private static String firstNonEmpty(String... values) {
        if (values == null) {
            return null;
        }
        for (String v : values) {
            if (v != null && !v.isEmpty()) {
                return v;
            }
        }
        return null;
    }

    // 1.x verbatim (fixed33): read one text extra as a String.
    private static String getText(android.os.Bundle bundle, String str) {
        if (bundle == null) {
            return null;
        }
        try {
            CharSequence charSequence = bundle.getCharSequence(str);
            if (charSequence != null) {
                return charSequence.toString();
            }
            return null;
        } catch (Throwable th) {
            return null;
        }
    }

    // 1.x verbatim (fixed33): last non-empty line of android.textLines.
    private static String getLastTextLine(android.os.Bundle bundle) {
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

    // 1.x verbatim (fixed33): text of the latest android.messages entry
    // (Notification.MessagingStyle.Message). The last entry is newest.
    // Message does not declare Parcelable in android-34.jar, so a direct
    // instanceof/cast does not compile — read getText() by reflection.
    private static String getLatestMessageText(android.os.Bundle bundle) {
        if (bundle == null) {
            return null;
        }
        try {
            android.os.Parcelable[] messages = bundle.getParcelableArray("android.messages");
            if (messages != null) {
                for (int i = messages.length - 1; i >= 0; i--) {
                    Object msg = messages[i];
                    if (msg == null) {
                        continue;
                    }
                    if ("android.app.Notification$MessagingStyle$Message".equals(msg.getClass().getName())) {
                        try {
                            java.lang.reflect.Method getText = msg.getClass().getMethod("getText");
                            Object t = getText.invoke(msg);
                            if (t instanceof CharSequence && !t.toString().trim().isEmpty()) {
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

    private static class MsgState {
        long postTime;
        final java.util.LinkedHashSet<String> seen =
                new java.util.LinkedHashSet<>();

        MsgState(long postTime) {
            this.postTime = postTime;
        }
    }

    private boolean isRepeatNotif(String key, String title, String text) {
        return isRepeatNotif(key, title, text, -1, -1);
    }

    // 1.x verbatim (isRepeatNotifKey): same key + same content within
    // the TTL is an identical re-delivery. Records the key+content on
    // EVERY call (before the later dedup checks), synchronised on the
    // map, with the 128-entry expiry sweep — 2.0's earlier version
    // recorded only in recordNotif (after all checks passed) and did
    // not synchronise.
    private boolean isRepeatNotif(String key, String title, String text,
            int progress, int progressMax) {
        if (key == null) {
            return false;
        }
        try {
            String content = contentDigest(title, text, progress, progressMax);
            long now = android.os.SystemClock.uptimeMillis();
            synchronized (seenKeys) {
                Long last = seenKeys.get(key);
                String lastContent = seenContent.get(key);
                if (last != null && now - last.longValue() < DEDUP_TTL_MS
                        && content.equals(lastContent)) {
                    return true;
                }
                seenKeys.put(key, Long.valueOf(now));
                seenContent.put(key, content);
                if (seenKeys.size() > 128) {
                    java.util.Iterator<java.util.Map.Entry<String, Long>> it =
                            seenKeys.entrySet().iterator();
                    while (it.hasNext()) {
                        java.util.Map.Entry<String, Long> e = it.next();
                        if (now - e.getValue().longValue() > DEDUP_TTL_MS) {
                            it.remove();
                            seenContent.remove(e.getKey());
                        }
                    }
                }
            }
        } catch (Throwable ignored) {
        }
        return false;
    }

    /** 1.x fixed28: true if this package's last notification had the
     * identical fingerprint (id:postTime:title/text:progress). A
     * repeat fingerprint is a re-delivery of the exact same
     * notification — drop it with zero action. No time window. */
    private boolean isRepeatNotifFingerprint(String pkg,
            android.service.notification.StatusBarNotification sbn,
            String title, String text, int progress, int progressMax) {
        if (pkg == null || sbn == null) {
            return false;
        }
        try {
            String fp = sbn.getId() + ":" + sbn.getPostTime() + ":"
                    + (title == null ? "" : title) + "/"
                    + (text == null ? "" : text) + ":"
                    + progress + "/" + progressMax;
            synchronized (lastNotifFingerprintByPkg) {
                String last = lastNotifFingerprintByPkg.get(pkg);
                if (fp.equals(last)) {
                    return true;
                }
                lastNotifFingerprintByPkg.put(pkg, fp);
            }
        } catch (Throwable ignored) {
        }
        return false;
    }

    /** 1.x fixed11/12: per-key lifecycle dedup. Instead of matching
     * the summary's text format ("[N条]sender: msg") inside a time
     * window, track each notification by its KEY and remember which
     * individual messages were already seen for that key and for
     * that conversation:
     *  - STALE: this postTime is older than the stored one for the key
     *    (an old re-delivery) -> redundant.
     *  - every message in this snapshot was already seen for this key,
     *    or its latest message equals the conversation's latest ->
     *    redundant.
     * Otherwise record the messages and return false (fresh, show it).
     * Message digests come from android.messages (MessagingStyle),
     * falling back to android.textLines, then to title+text. No time
     * window is involved, so a late summary is still caught. */
    private boolean isRedundantMessage(
            android.service.notification.StatusBarNotification sbn,
            android.app.Notification notification, android.os.Bundle bundle,
            String pkg, String title, String text) {
        if (sbn == null || sbn.getKey() == null) {
            return false;
        }
        try {
            java.util.ArrayList<String> messages =
                    new java.util.ArrayList<>();
            if (bundle != null) {
                android.os.Parcelable[] parcelableArray =
                        bundle.getParcelableArray("android.messages");
                if (parcelableArray != null) {
                    for (int i = Math.max(0, parcelableArray.length - 25);
                            i < parcelableArray.length; i++) {
                        if (parcelableArray[i] instanceof android.os.Bundle) {
                            android.os.Bundle m =
                                    (android.os.Bundle) parcelableArray[i];
                            CharSequence t = m.getCharSequence("text");
                            if (t != null && t.length() > 0) {
                                CharSequence sender =
                                        m.getCharSequence("sender");
                                String s = sender == null ? ""
                                        : sender.toString();
                                messages.add(s + "\u0001" + t.toString());
                            }
                        }
                    }
                }
                if (messages.isEmpty()) {
                    CharSequence[] lines =
                            bundle.getCharSequenceArray("android.textLines");
                    if (lines != null) {
                        for (int i = Math.max(0, lines.length - 25);
                                i < lines.length; i++) {
                            if (lines[i] != null && lines[i].length() > 0) {
                                messages.add(lines[i].toString());
                            }
                        }
                    }
                }
            }
            if (messages.isEmpty()) {
                String content = (title == null ? "" : title) + "\u0001"
                        + (text == null ? "" : text);
                if (!content.trim().isEmpty()) {
                    messages.add(content);
                }
            }
            if (messages.isEmpty()) {
                return false;
            }
            String conversation = null;
            if (bundle != null) {
                CharSequence convTitle =
                        bundle.getCharSequence("android.conversationTitle");
                String conv = convTitle == null ? ""
                        : convTitle.toString();
                if (conv.isEmpty() && notification != null
                        && notification.getShortcutId() != null) {
                    conv = notification.getShortcutId();
                }
                if (conv.isEmpty() && title != null && !title.isEmpty()
                        && !"微信".equals(title) && !"QQ".equals(title)
                        && !title.contains("条新消息")
                        && !title.contains("新消息")) {
                    conv = title;
                }
                if (!conv.isEmpty()) {
                    conversation = pkg + "\u0001" + conv;
                }
            }
            String latest = messages.get(messages.size() - 1);
            String key = sbn.getKey();
            long postTime = sbn.getPostTime();
            synchronized (msgStates) {
                MsgState state = msgStates.get(key);
                if (state != null && postTime < state.postTime) {
                    return true;
                }
                if (state == null) {
                    state = new MsgState(postTime);
                    msgStates.put(key, state);
                    if (msgStates.size() > 256) {
                        java.util.Iterator<String> it =
                                msgStates.keySet().iterator();
                        if (it.hasNext()) {
                            it.next();
                            it.remove();
                        }
                    }
                } else {
                    state.postTime = postTime;
                }
                boolean allSeen = true;
                for (String m : messages) {
                    if (!state.seen.contains(m)) {
                        allSeen = false;
                        break;
                    }
                }
                if (allSeen) {
                    return true;
                }
                if (conversation != null) {
                    synchronized (lastMsgByConversation) {
                        String last =
                                lastMsgByConversation.get(conversation);
                        if (last != null && last.equals(latest)) {
                            for (String m : messages) {
                                state.seen.add(m);
                                if (state.seen.size() > 64) {
                                    state.seen.remove(
                                            state.seen.iterator().next());
                                }
                            }
                            return true;
                        }
                    }
                }
                for (String m : messages) {
                    state.seen.add(m);
                    if (state.seen.size() > 64) {
                        state.seen.remove(state.seen.iterator().next());
                    }
                }
                if (conversation != null) {
                    synchronized (lastMsgByConversation) {
                        lastMsgByConversation.put(conversation, latest);
                        if (lastMsgByConversation.size() > 256) {
                            java.util.Iterator<String> it =
                                    lastMsgByConversation.keySet().iterator();
                            if (it.hasNext()) {
                                it.next();
                                it.remove();
                            }
                        }
                    }
                }
            }
            return false;
        } catch (Throwable ignored) {
            return false;
        }
    }

    /** Layer 4 — true if this notification's postTime is older than
     * the last seen for its key (a stale re-delivery). */
    private boolean isStalePostTime(String key, long postTime) {
        if (key == null) {
            return false;
        }
        Long last = lastPostTimeByKey.get(key);
        return last != null && postTime < last;
    }

    /** Layer 2a — true if the same package already showed the
     * same title + text within 15s (ported from 1.x isDuplicateNotif's
     * first layer). This is the layer 2.0 was missing: without it, a
     * same-app re-post with a NEW key sails through the source dedup
     * and SystemUI's key check can't catch it — two capsules, two
     * fan groups, all apps (v94). Records the fingerprint either
     * way. */
    private boolean isPkgFingerprintRepeat(String pkg, String title,
            String text) {
        String fp = (pkg == null ? "" : pkg) + "\u0001"
                + contentDigest(title, text);
        long now = android.os.SystemClock.uptimeMillis();
        Long at = recentPkgFingerprints.get(fp);
        boolean repeat = at != null && now - at < FINGERPRINT_WINDOW_MS;
        recentPkgFingerprints.put(fp, now);
        if (recentPkgFingerprints.size() > 64) {
            java.util.Iterator<java.util.Map.Entry<String, Long>> it =
                    recentPkgFingerprints.entrySet().iterator();
            while (it.hasNext()) {
                if (now - it.next().getValue() > FINGERPRINT_WINDOW_MS) {
                    it.remove();
                }
            }
        }
        return repeat;
    }

    private boolean isPushService(String pkg) {
        if (pkg == null) {
            return false;
        }
        if (PUSH_SERVICE_PACKAGES.contains(pkg)) {
            return true;
        }
        try {
            CharSequence label = getPackageManager().getApplicationLabel(
                    getPackageManager().getApplicationInfo(pkg, 0));
            if (label != null && "推送服务".equals(label.toString())) {
                return true;
            }
        } catch (Throwable ignored) {
        }
        return false;
    }

    /** v407: cancel a notification by key AND by pkg/tag/id.
     * Push-service notifications (com.meizu.cloud) carry a key
     * whose package field is the REAL app (e.g. com.coolapk.market,
     * com.eg.android.AlipayGphone) while the posting package is the
     * push service — cancelNotification(key) does not resolve it on
     * Flyme, so the card stays in the shade even though the bubble
     * popped (user 2026-11-22 酷安/支付宝). The 3-arg cancel uses
     * the package + tag + id, which does resolve.
     * v408 (user 2026-11-22 "消这种通知实际上应该还是要靠消原来
     * 的报名的"): the 3-arg cancel must use the ORIGINAL package
     * from the KEY (e.g. com.hpbr.bosszhipin), NOT the posting
     * package (com.meizu.cloud) — the notification is registered
     * under the original package, so cancelling by the push-service
     * package finds nothing. Both are tried; the second is a no-op
     * if the first already removed it. Replaces every
     * cancelNotification(sbn.getKey()) in onNotificationPosted. */
    private void cancelNotif(StatusBarNotification sbn) {
        if (sbn == null) {
            return;
        }
        // v417 (diagnostic): log the cancel parameters and whether
        // each call throws. The Migu push (19:38:42) popped a bubble
        // but the card stayed in the shade — both cancels are
        // silently swallowed, so log what is actually attempted.
        final String key = sbn.getKey();
        final String keyPkg = keyPackage(key);
        final String pkg = (keyPkg != null && !keyPkg.isEmpty())
                ? keyPkg : sbn.getPackageName();
        final String tag = sbn.getTag();
        final int id = sbn.getId();
        FileLog.i("cancelNotif key=" + key + " keyPkg=" + keyPkg
                + " pkg=" + pkg + " postPkg=" + sbn.getPackageName()
                + " tag=" + tag + " id=" + id);
        try {
            cancelNotification(key);
        } catch (Throwable t) {
            FileLog.i("cancelNotif key-cancel threw: " + t);
        }
        try {
            cancelNotification(pkg, tag, id);
        } catch (Throwable t) {
            FileLog.i("cancelNotif 3-arg cancel threw: " + t);
        }
        // v419 (user 2026-11-22 "做吧/修复"): third attempt — the
        // POSTING package. A push notification is registered under
        // the push service that posted it (com.meizu.cloud), not
        // under the original app in the key, so the key cancel and
        // the original-package 3-arg cancel both miss it. Only
        // cancelling by the posting package finds it. Skipped when
        // the posting package already equals the key package
        // (normal notifications — the 2nd attempt covered it).
        final String postPkg = sbn.getPackageName();
        if (postPkg != null && !postPkg.equals(pkg)) {
            try {
                cancelNotification(postPkg, tag, id);
            } catch (Throwable t) {
                FileLog.i("cancelNotif postPkg cancel threw: " + t);
            }
        }
        // v417 (diagnostic): 500ms later, check whether the
        // notification is still active. A silent no-op (not found)
        // throws nothing, so only this tells us the cancel worked.
        try {
            new android.os.Handler(
                    android.os.Looper.getMainLooper()).postDelayed(
                    new Runnable() {
                @Override
                public void run() {
                    try {
                        boolean stillThere = false;
                        StatusBarNotification[] act =
                                getActiveNotifications();
                        if (act != null) {
                            for (StatusBarNotification a : act) {
                                if (a != null
                                        && key.equals(a.getKey())) {
                                    stillThere = true;
                                    break;
                                }
                            }
                        }
                        FileLog.i("cancelNotif stillActive=" + stillThere
                                + " key=" + key);
                        // v427: report the result to SystemUI so it
                        // lands in the log the user sends (see
                        // ACTION_CANCEL_DIAG). stillActive=true means
                        // all three cancels missed (or the push
                        // service re-posted); false means the cancel
                        // worked and a staying card is a re-post.
                        try {
                            Intent diag = new Intent(ACTION_CANCEL_DIAG);
                            diag.putExtra("key", key);
                            diag.putExtra("stillActive", stillThere);
                            diag.putExtra("pkg", pkg);
                            diag.putExtra("tag", tag);
                            diag.putExtra("id", id);
                            sendBroadcast(diag);
                        } catch (Throwable ignored) {
                        }
                    } catch (Throwable ignored) {
                    }
                }
            }, 500);
        } catch (Throwable ignored) {
        }
    }

    /** v490: dismiss by key with all three cancel attempts (same as
     * cancelNotif, but the caller only has the key string, not the
     * StatusBarNotification). The v488 hook sent ACTION_DISMISS with
     * the key, but the 1-arg cancelNotification(key) alone does not
     * remove delegated push notifications (CancelDiag stillActive=true
     * 0.5s after the dismiss) — v408/v419 disagree on whether the
     * notification is registered under the original package (key) or
     * the posting package (com.meizu.cloud), so try all three, exactly
     * like cancelNotif. The postPkg extra carries the posting package
     * (not in the key); without it only the first two attempts run.
     * Card-open dismiss (BubbleWindow, no postPkg) also routes here —
     * strictly more robust than the old single key cancel. */
    private void cancelByKey(final String key, String postPkg) {
        if (key == null) {
            return;
        }
        final String keyPkg = keyPackage(key);
        String tag = null;
        int id = 0;
        boolean parsed = false;
        try {
            String[] parts = key.split("\\|");
            if (parts.length >= 4) {
                id = Integer.parseInt(parts[2]);
                String t = parts[3];
                tag = "null".equals(t) ? null : t;
                parsed = true;
            }
        } catch (Throwable ignored) {
        }
        FileLog.i("cancelByKey key=" + key + " keyPkg=" + keyPkg
                + " postPkg=" + postPkg + " tag=" + tag + " id=" + id);
        try {
            cancelNotification(key);
        } catch (Throwable t) {
            FileLog.i("cancelByKey key-cancel threw: " + t);
        }
        if (parsed && keyPkg != null && !keyPkg.isEmpty()) {
            try {
                cancelNotification(keyPkg, tag, id);
            } catch (Throwable t) {
                FileLog.i("cancelByKey keyPkg cancel threw: " + t);
            }
        }
        if (parsed && postPkg != null && !postPkg.isEmpty()
                && !postPkg.equals(keyPkg)) {
            try {
                cancelNotification(postPkg, tag, id);
            } catch (Throwable t) {
                FileLog.i("cancelByKey postPkg cancel threw: " + t);
            }
        }
        // 500ms later, check whether it is still active (same
        // diagnostic as cancelNotif).
        try {
            new android.os.Handler(
                    android.os.Looper.getMainLooper()).postDelayed(
                    new Runnable() {
                @Override
                public void run() {
                    try {
                        boolean stillThere = false;
                        StatusBarNotification[] act =
                                getActiveNotifications();
                        if (act != null) {
                            for (StatusBarNotification a : act) {
                                if (a != null
                                        && key.equals(a.getKey())) {
                                    stillThere = true;
                                    break;
                                }
                            }
                        }
                        FileLog.i("cancelByKey stillActive=" + stillThere
                                + " key=" + key);
                    } catch (Throwable ignored) {
                    }
                }
            }, 500);
        } catch (Throwable ignored) {
        }
    }

    /** v408: the package field (2nd) of a notification key
     * ("user|pkg|id|tag|uid"). For push-service notifications this
     * is the ORIGINAL app package, not the posting push service. */
    private static String keyPackage(String key) {
        if (key == null) {
            return null;
        }
        String[] parts = key.split("\\|");
        return parts.length >= 2 ? parts[1] : null;
    }

    /** Smart push-service filter (v144, user 2026-11-19 "可以"):
     * drop a push-service copy ONLY if the real app already posted
     * the same content (title+text) within 15s. If the app never
     * posted it (BOSS/鱼泡 in the background), the push copy is the
     * only one — keep it. Must run BEFORE isFingerprintRepeat, so
     * the push copy does not overwrite the poster first. */
    private boolean isRedundantPushCopy(String pkg, String title,
            String text) {
        if (!isPushService(pkg)) {
            return false;
        }
        String fp = contentDigest(title, text);
        Long at = recentFingerprints.get(fp);
        String poster = recentContentPoster.get(fp);
        return at != null && poster != null
                && !isPushService(poster)
                && android.os.SystemClock.uptimeMillis() - at < FINGERPRINT_WINDOW_MS;
    }

    /** Layer 2 — true if the same title + text (any package) was
     * already shown within 15s. Records the fingerprint either way,
     * and records who posted it (for the push filter). */
    private boolean isFingerprintRepeat(String pkg, String title,
            String text) {
        String fp = contentDigest(title, text);
        long now = android.os.SystemClock.uptimeMillis();
        Long at = recentFingerprints.get(fp);
        boolean repeat = at != null && now - at < FINGERPRINT_WINDOW_MS;
        recentFingerprints.put(fp, now);
        recentContentPoster.put(fp, pkg == null ? "" : pkg);
        if (recentFingerprints.size() > 64) {
            java.util.Iterator<java.util.Map.Entry<String, Long>> it =
                    recentFingerprints.entrySet().iterator();
            while (it.hasNext()) {
                java.util.Map.Entry<String, Long> e = it.next();
                if (now - e.getValue() > FINGERPRINT_WINDOW_MS) {
                    it.remove();
                    recentContentPoster.remove(e.getKey());
                }
            }
        }
        return repeat;
    }

    /** Layer 3a — true if this is a FLAG_GROUP_SUMMARY whose child
     * notification (same pkg + group) was already shown. */
    private boolean isRedundantGroupSummary(android.app.Notification n,
            String pkg) {
        try {
            if ((n.flags
                    & android.app.Notification.FLAG_GROUP_SUMMARY) == 0) {
                return false;
            }
            String group = n.getGroup();
            if (group == null || pkg == null) {
                return false;
            }
            Long at = recentNotifGroups.get(pkg + "\u0001" + group);
            return at != null
                    && android.os.SystemClock.uptimeMillis() - at < GROUP_WINDOW_MS;
        } catch (Throwable ignored) {
            return false;
        }
    }

    /** Record a non-summary notification's group, so a later summary
     * for the same group is recognised as redundant. */
    private void noteNotifGroup(android.app.Notification n, String pkg) {
        try {
            if ((n.flags
                    & android.app.Notification.FLAG_GROUP_SUMMARY) != 0) {
                return;
            }
            String group = n.getGroup();
            if (group == null || pkg == null) {
                return;
            }
            recentNotifGroups.put(pkg + "\u0001" + group,
                    android.os.SystemClock.uptimeMillis());
            if (recentNotifGroups.size() > 64) {
                recentNotifGroups.clear();
            }
        } catch (Throwable ignored) {
        }
    }

    /** Layer 3b — true if this notification is a text summary of the
     * last one shown for the same package (one text contains the
     * other and contains the title, either direction — 1.x fixed2). */
    private boolean isSummaryOf(String pkg, String title, String text) {
        if (pkg == null || text == null || text.isEmpty()) {
            return false;
        }
        String lastTitle = lastTitleByPkg.get(pkg);
        String lastText = lastTextByPkg.get(pkg);
        Long at = lastShownAtByPkg.get(pkg);
        if (lastText == null || lastText.isEmpty() || at == null
                || android.os.SystemClock.uptimeMillis() - at > SUMMARY_WINDOW_MS) {
            return false;
        }
        String t = title == null ? "" : title;
        String lt = lastTitle == null ? "" : lastTitle;
        boolean contains = text.contains(lastText)
                || lastText.contains(text);
        boolean hasTitle = (!t.isEmpty()
                && (text.contains(t) || lastText.contains(t)))
                || (!lt.isEmpty()
                        && (text.contains(lt) || lastText.contains(lt)));
        return contains && hasTitle;
    }

    /** Parse the sender out of a "[N条]sender: message" summary. */
    private static String parseSummarySender(String text) {
        if (text == null) {
            return null;
        }
        int open = text.indexOf('[');
        int close = open >= 0 ? text.indexOf(']', open) : -1;
        if (open < 0 || close < 0 || close + 1 >= text.length()) {
            return null;
        }
        String head = text.substring(open + 1, close);
        if (!head.endsWith("条")) {
            return null;
        }
        int colon = text.indexOf(':', close);
        if (colon < 0 || colon + 1 >= text.length()) {
            return null;
        }
        String sender = text.substring(close + 1, colon).trim();
        return sender.isEmpty() ? null : sender;
    }

    /** Layer 3c — true if this is an aggregated "[N条]sender" summary
     * for a sender already shown for this package within 60s. */
    private boolean isAggregatedSummary(String pkg, String text) {
        if (pkg == null) {
            return false;
        }
        String sender = parseSummarySender(text);
        if (sender == null) {
            return false;
        }
        Long at = lastSummarySenderAt.get(pkg + "\u0001" + sender);
        return at != null
                && android.os.SystemClock.uptimeMillis() - at < SUMMARY_WINDOW_MS;
    }

    private void recordNotif(String key, String title, String text,
            String pkg, long postTime) {
        recordNotif(key, title, text, pkg, postTime, -1, -1);
    }

    private void recordNotif(String key, String title, String text,
            String pkg, long postTime, int progress, int progressMax) {
        if (key == null) {
            return;
        }
        if (seenKeys.size() >= DEDUP_MAX_KEYS) {
            long now = android.os.SystemClock.uptimeMillis();
            java.util.Iterator<java.util.Map.Entry<String, Long>> it =
                    seenKeys.entrySet().iterator();
            while (it.hasNext()) {
                java.util.Map.Entry<String, Long> e = it.next();
                if (now - e.getValue() > DEDUP_TTL_MS) {
                    it.remove();
                    seenContent.remove(e.getKey());
                }
            }
        }
        seenKeys.put(key, android.os.SystemClock.uptimeMillis());
        seenContent.put(key,
                contentDigest(title, text, progress, progressMax));
        Long lastPost = lastPostTimeByKey.get(key);
        if (lastPost == null || postTime > lastPost) {
            lastPostTimeByKey.put(key, postTime);
        }
        if (pkg != null) {
            lastTitleByPkg.put(pkg, title == null ? "" : title);
            lastTextByPkg.put(pkg, text == null ? "" : text);
            lastShownAtByPkg.put(pkg, android.os.SystemClock.uptimeMillis());
            String sender = parseSummarySender(text);
            if (sender != null) {
                lastSummarySenderAt.put(pkg + "\u0001" + sender,
                        android.os.SystemClock.uptimeMillis());
            }
        }
    }

    // ---- Live lyric listener (v76) ----
    // Active ONLY while a music capsule is pinned (user 2026-11-19
    // "固定了就实时监听歌词"). Unpinned music just pops once per
    // song change (v75) — no listener. The listener is a
    // MediaController.Callback on the music session: every metadata
    // change (lyric line moves on) broadcasts the new lyric to
    // SystemUI, which updates the pinned capsule in place.
    private android.media.session.MediaController musicController;
    private android.media.session.MediaController.Callback musicCallback;
    private android.media.session.MediaSession.Token musicToken;
    private String musicKey;
    private boolean musicListening;
    private String lastLyricSent;
    /** v171 (1.x fixed150): the song currently showing. A title /
     * artist change is a REAL song change only if the song's own
     * numbers confirm it — duration changed, or position reset near
     * 0, or no previous title, or no position/duration info at all.
     * A lyric line arriving as the title keeps the SAME duration
     * and a MID-SONG position, so it is ignored and never pops. */
    private String currentMusicTitle;
    /** v204: last title/artist/duration sent by
     * broadcastMusicProgress — drives the 1.0.5 sticky (a metadata
     * title that differs while the duration matches is the same
     * song re-read, not a song change) and the title-change dedup. */
    private String lastBroadcastTitle;
    private String lastBroadcastArtist;
    /** v216: the raw session ARTIST string last broadcast - the
     * generic song-change signal. The resolved artist is just the
     * singer (same across songs by one singer), but the raw ARTIST
     * ("song-singer" for Flyme) changes with every song. */
    private String lastBroadcastRawArtist;
    /** v218: the song title the session album art was last sent
     * for — art is pushed only when it is new for this song
     * (first arrival, or song change), so the 1s tick does not
     * re-send it every second. */
    private String lastBroadcastArtTitle;
    private long lastBroadcastDuration = -1;
    /** v173: the key of the current music notification. 1.x and
     * both Flyme Island builds decide same-song by notification
     * key — the same key re-posted is a lyric refresh, never a
     * pop — and it needs no MediaSession at all. */
    private String currentMusicNotifKey;
    private String currentMusicArtist;
    private long currentMusicDurationMs = -1;
    private long lastBroadcastPositionMs = -1;
    // v174: the heart-rating state last sent to the card, and a
    // just-set rating waiting for the session metadata to catch up
    // (so the card's optimistic flip is not undone by a stale read).
    private boolean lastBroadcastFavorite;
    private Boolean pendingFavorite;
    private long pendingFavoriteAt;
    // Lyric poll (v84): while pinned, re-read the live lyric every
    // 1s so it tracks the music even when the app's callbacks /
    // re-posts are infrequent. Only broadcasts on a real change.
    private android.os.Handler lyricPollHandler;
    private Runnable lyricPollRunnable;
    private static final long LYRIC_POLL_MS = 500;
    // v430: periodic update check (user 2026-11-23 "提示一下有更新
    // 就行" — prompt only, never a silent install). The listener
    // service is always on, so it hosts the background check: first
    // run 60s after connect, then every 6 hours. A newer release
    // posts one system notification (once per versionCode).
    private android.os.Handler updateCheckHandler;
    private Runnable updateCheckRunnable;
    private boolean updateCheckStarted;
    private static final long UPDATE_CHECK_FIRST_MS = 60 * 1000;
    private static final long UPDATE_CHECK_INTERVAL_MS =
            6 * 60 * 60 * 1000;
    // Network lyrics fallback (v88, LRCLIB). Only used when the
    // notification/MediaSession have no lyric. Lines are timed
    // (LRC), picked by playback position.
    private String netLyricSong;
    private String netLyricArtist;
    private long[] netLyricTimes;
    private String[] netLyricTexts;
    private boolean netLyricFetching;
    private String netLyricFetchingFor;
    // Fallback clock for apps whose MediaSession never reports a
    // position (e.g. Douyin): elapsed time since these lines were
    // fetched stands in for the playback position.
    private long netLyricStartTime;
    // Real-time download progress (v95, 1.x fixed140): 1s tick
    // re-reads the live progress and pushes updates.
    private String downloadKey;
    // Incoming call (v103, 1.x handleCallNotification): the key of
    // the call notification, so its removal means the call ended.
    private String callKey;
    // v130: the speaker PendingIntents of the latest call
    // notification (kind 3 = speaker on/toggle, 4 = speaker
    // off). Sent to SystemUI so it can fire them directly.
    private android.app.PendingIntent callSpeakerOnIntent;
    private android.app.PendingIntent callSpeakerOffIntent;
    // v135: human-readable list of the latest call notification's
    // actions, sent to SystemUI so it can Toast what the module
    // actually saw (the module-side Toast may not display).
    private String callActionsDesc;
    // Current call's number, for the duplicate-RINGING dedup (v113).
    private String callNumber;
    // v132: current call's name (EXTRA_TITLE, the caller name).
    // After answering, the notification's EXTRA_TEXT becomes the
    // call duration, not the number — so the number check in
    // Guard 2 misses the re-post. The name stays the same, so
    // it identifies the same call when the number no longer
    // matches.
    private String callName;
    // v115 (1.x fixed90): keys we cancelled ourselves to hide the
    // system banner. Their onNotificationRemoved must NOT send IDLE —
    // the removal was ours, not the call ending. Without this, the
    // self-cancel produced a spurious IDLE that wiped the answered
    // state (speaker, timer) right after the user tapped answer.
    private final java.util.Set<String> selfCancelledKeys =
            java.util.Collections.synchronizedSet(
                    new java.util.HashSet<String>());
    private android.os.Handler downloadTickHandler;
    private Runnable downloadTickRunnable;
    // v241: which downloadKey the running tick is refreshing.
    // startDownloadTick is idempotent per key — re-posts of
    // the same download must NOT reset the 1s timer (that is
    // why the capsule never refreshed: re-posts every ~0.5s
    // kept pushing the 1s delay forward forever).
    private String downloadTickKey;
    private int lastDownloadPct = -1;
    // v239: download speed sampler — last byte count + time.
    private long lastDownloadBytes = -1;
    private long lastDownloadBytesTime = 0;
    // v241: last speed/ETA the tick broadcast, so the
    // early-return also fires when only speed/ETA changed
    // (TapTap's speed/ETA live in the text and move while
    // the percent is unchanged).
    private long lastDownloadSpeed = -1;
    private long lastDownloadEta = -1;
    // v242: consecutive ticks where the download notification
    // was not in getActiveNotifications(). Two in a row means
    // it is really gone (finished/cancelled), not a re-post
    // gap — then the capsule is removed.
    private int downloadGoneTicks = 0;
    private static final long DOWNLOAD_TICK_MS = 500;
    /** v171 (1.x fixed150, QQ Music Flyme): the Flyme edition
     * re-posts on every lyric line but carries no MediaSession
     * token and no TRANSPORT category, so it was never recognised
     * as music. Fall back to the package: an ongoing /
     * foreground-service notification from a known music app is
     * music, so the per-package music dedup and the fixed150
     * song-change check apply. */
    // v231: same song-recognition exclusion as
    // NotifCollectionHook.isSongRecognition, for the broadcast path.
    private static boolean isSongRecognition(
            android.app.Notification n) {
        if (n == null || n.extras == null) {
            return false;
        }
        CharSequence t = n.extras.getCharSequence(
                android.app.Notification.EXTRA_TITLE);
        if (t != null) {
            String s = t.toString();
            if (s.contains("\u542c\u6b4c\u8bc6\u66f2") || s.contains("\u53ef\u8bc6\u522b")) {
                return true;
            }
        }
        CharSequence x = n.extras.getCharSequence(
                android.app.Notification.EXTRA_TEXT);
        if (x != null) {
            String s = x.toString();
            if (s.contains("\u542c\u6b4c\u8bc6\u66f2") || s.contains("\u53ef\u8bc6\u522b")) {
                return true;
            }
        }
        return false;
    }

    private static boolean isKnownMusicApp(String pkg,
            android.app.Notification n) {
        if (pkg == null || n == null) {
            return false;
        }
        boolean ongoing = (n.flags
                & android.app.Notification.FLAG_ONGOING_EVENT) != 0
                || (n.flags
                        & android.app.Notification.FLAG_FOREGROUND_SERVICE)
                        != 0;
        if (!ongoing) {
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

    // v171: music progress ticker (1s) — broadcasts position /
    // duration / playing while a music session is active.
    private android.os.Handler musicTickHandler;
    private Runnable musicTickRunnable;
    private static final long MUSIC_TICK_MS = 1000;

    /** v172: the progress tick (and the controller it reads)
     * must run whenever a music session is active — not only while
     * pinned. The card is opened by tapping the capsule, and an
     * unpinned capsule never started the listener, so the card's
     * progress stayed 0:00 and the lyric never refreshed (user
     * 2026-11-20 screenshot "不会刷新"). The lyric callback + poll
     * stay pinned-only (v76); only the controller + tick are
     * session-scoped. */
    private void ensureMusicController() {
        if (musicToken == null) {
            return;
        }
        try {
            if (musicController == null) {
                musicController =
                        new android.media.session.MediaController(
                                this, musicToken);
            }
            startMusicTick();
        } catch (Throwable ignored) {
        }
    }

    private void setupMusicListener() {
        teardownMusicListener();
        if (musicToken == null || musicKey == null) {
            return;
        }
        try {
            ensureMusicController();
            lastLyricSent = null;
            android.media.session.MediaController.Callback cb =
                    new android.media.session.MediaController.Callback() {
                        @Override
                        public void onMetadataChanged(
                                android.media.MediaMetadata metadata) {
                            refreshLyricFromActive();
                            // v204: a manual skip changes the session
                            // metadata at once — push the full state
                            // (title/artist/favorite/progress) so the
                            // card follows without waiting for a new
                            // notification (1.0.5
                            // sendMusicStateFromController).
                            broadcastMusicProgress();
                        }

                        @Override
                        public void onPlaybackStateChanged(
                                android.media.session.PlaybackState state) {
                            refreshLyricFromActive();
                            broadcastMusicProgress();
                        }
                    };
            musicCallback = cb;
            musicController.registerCallback(cb);
            refreshLyricFromActive();
            startLyricPoll();
        } catch (Throwable ignored) {
            musicController = null;
            musicCallback = null;
        }
    }

    private void startLyricPoll() {
        try {
            stopLyricPoll();
            if (lyricPollHandler == null) {
                lyricPollHandler = new android.os.Handler(
                        android.os.Looper.getMainLooper());
            }
            lyricPollRunnable = new Runnable() {
                @Override
                public void run() {
                    try {
                        if (!musicListening) {
                            return;
                        }
                        refreshLyricFromActive();
                    } catch (Throwable ignored) {
                    } finally {
                        try {
                            if (musicListening && lyricPollHandler != null
                                    && lyricPollRunnable != null) {
                                lyricPollHandler.postDelayed(
                                        lyricPollRunnable, LYRIC_POLL_MS);
                            }
                        } catch (Throwable ignored) {
                        }
                    }
                }
            };
            lyricPollHandler.postDelayed(lyricPollRunnable, LYRIC_POLL_MS);
        } catch (Throwable ignored) {
        }
    }

    private void stopLyricPoll() {
        try {
            if (lyricPollHandler != null && lyricPollRunnable != null) {
                lyricPollHandler.removeCallbacks(lyricPollRunnable);
            }
        } catch (Throwable ignored) {
        }
        lyricPollRunnable = null;
    }

    /** v430: periodic update check (user 2026-11-23 "提示一下有更新
     * 就行"). First run 60s after the listener connects, then every
     * 6 hours. A newer GitHub release posts one system notification
     * (once per versionCode) — never a silent install. Guarded so a
     * reconnect does not start a second loop. */
    private void startUpdateCheck() {
        if (updateCheckStarted) {
            return;
        }
        updateCheckStarted = true;
        try {
            if (updateCheckHandler == null) {
                updateCheckHandler = new android.os.Handler(
                        android.os.Looper.getMainLooper());
            }
            updateCheckRunnable = new Runnable() {
                @Override
                public void run() {
                    try {
                        // v511: ride the same periodic wake as
                        // the version check to refresh the
                        // online game list (internally throttled
                        // to one attempt per 6h).
                        GamePackages.fetchIfStale(
                                NotificationService.this);
                        UpdateChecker.checkAsync(
                                NotificationService.this,
                                new UpdateChecker.Callback() {
                                    @Override
                                    public void onResult(
                                            UpdateChecker.UpdateInfo
                                                    info) {
                                        if (info != null
                                                && info.available) {
                                            UpdateChecker.notifyUpdate(
                                                    NotificationService
                                                            .this,
                                                    info);
                                        }
                                    }
                                });
                    } catch (Throwable ignored) {
                    }
                    try {
                        if (updateCheckHandler != null
                                && updateCheckRunnable != null) {
                            updateCheckHandler.postDelayed(
                                    updateCheckRunnable,
                                    UPDATE_CHECK_INTERVAL_MS);
                        }
                    } catch (Throwable ignored) {
                    }
                }
            };
            updateCheckHandler.postDelayed(updateCheckRunnable,
                    UPDATE_CHECK_FIRST_MS);
        } catch (Throwable ignored) {
        }
    }

    /**
     * Re-read the lyric from the currently ACTIVE music notification
     * and the MediaSession metadata, and broadcast it when it
     * changed. This is the 1.x method (fixed128): the lyric is
     * delivered through the NOTIFICATION (ticker / text / bigText),
     * so the candidates must be re-read from the live notification
     * on every callback — reading only the metadata subtitle goes
     * stale after a line or two (user 2026-11-19 "刷一两句就不动了").
     * Bypasses the onNotificationPosted dedup (same key + same
     * title/text would drop lyric-only re-posts) by reading the
     * active notification directly. Priority (1.x): ticker -> text
     * -> bigText -> DISPLAY_SUBTITLE, each rejected when it equals
     * the song title or the artist.
     */
    private void refreshLyricFromActive() {
        if (musicKey == null) {
            return;
        }
        try {
            String song = null;
            String artist = null;
            String subtitle = null;
            String albumArtist = null;
            String metaTitle = null;
            long durMs = -1;
            // Metadata first (artist + subtitle for validation).
            // The stable song title comes from the notification's
            // EXTRA_TITLE below — NOT from the metadata TITLE, which
            // some players use for the lyric line itself (1.x fixed17).
            if (musicController != null) {
                try {
                    android.media.MediaMetadata md =
                            musicController.getMetadata();
                    if (md != null) {
                        metaTitle = md.getString(android.media.MediaMetadata
                                .METADATA_KEY_TITLE);
                        durMs = md.getLong(android.media.MediaMetadata
                                .METADATA_KEY_DURATION);
                        artist = md.getString(android.media.MediaMetadata
                                .METADATA_KEY_ARTIST);
                        subtitle = md.getString(android.media.MediaMetadata
                                .METADATA_KEY_DISPLAY_SUBTITLE);
                        albumArtist = md.getString(android.media.MediaMetadata
                                .METADATA_KEY_ALBUM_ARTIST);
                    }
                } catch (Throwable ignored) {
                }
            }
            // Then the live notification title (the stable song
            // anchor). The lyric candidates are re-read into the
            // persistent slots by refreshLyricCandidatesFromActive
            // below (1.x fixed128).
            try {
                android.service.notification.StatusBarNotification[] actives =
                        getActiveNotifications();
                if (actives != null) {
                    for (android.service.notification.StatusBarNotification sbn : actives) {
                        if (sbn == null || sbn.getNotification() == null
                                || !musicKey.equals(sbn.getKey())) {
                            continue;
                        }
                        android.os.Bundle ex =
                                sbn.getNotification().extras;
                        if (ex != null) {
                            CharSequence t = ex.getCharSequence(
                                    android.app.Notification.EXTRA_TITLE);
                            if (t != null && !t.toString().isEmpty()) {
                                song = t.toString();
                            }
                        }
                        break;
                    }
                }
            } catch (Throwable ignored) {
            }
            // 1.x: refresh the persistent lyric slots from the
            // live notification first (fixed128), then resolve.
            refreshLyricCandidatesFromActive();
            String lyric = resolveMusicLyric(
                    metaTitle, subtitle, artist, albumArtist, song);
            if (lyric == null || lyric.isEmpty()) {
                // No local lyric — network fallback (v88). If we
                // already have LRCLIB lines for this song, pick the
                // line by playback position; otherwise start a fetch.
                lyric = netLyricForCurrentPosition(song, artist);
                if ((lyric == null || lyric.isEmpty())
                        && song != null && !song.isEmpty()) {
                    maybeFetchNetworkLyrics(song, artist, durMs);
                }
            }
            // v234: LRCLIB was tried for this song and came back
            // empty -- the fetch finished (netLyricFetching
            // false, key matches netLyricFetchingFor) and there
            // is still no lyric. Show "\u627e\u4e0d\u5230\u5f53\u524d\u6b4c\u8bcd"
            // instead of leaving the lyric line blank. While a
            // fetch is still running, show nothing yet (the
            // lyric may still arrive).
            if ((lyric == null || lyric.isEmpty())
                    && song != null && !song.isEmpty()
                    && !netLyricFetching
                    && netLyricFetchingFor != null
                    && netLyricFetchingFor.equals(
                            song + "\u0000"
                                    + (artist == null ? "" : artist))) {
                lyric = "\u627e\u4e0d\u5230\u5f53\u524d\u6b4c\u8bcd";
            }
            if (lyric == null || lyric.isEmpty()
                    || lyric.equals(lastLyricSent)) {
                return;
            }
            lastLyricSent = lyric;
            Intent intent = new Intent(ACTION_LYRIC);
            intent.putExtra("key", musicKey);
            intent.putExtra("lyrics", lyric);
            try {
                sendBroadcast(intent);
            } catch (Throwable ignored) {
            }
        } catch (Throwable ignored) {
        }
    }

    // ---- 1.x lyric logic, ported (user 2026-11-19 "之前已经修复了
    // 拿过来用就是了"). The lyric is resolved the way 1.x does it in
    // sendMusicStateFromController (fixed31/128/152/153): swapped
    // layout detection (TITLE is the lyric, subtitle is "song -
    // artist"), then a validated pick over ticker -> text -> bigText
    // -> DISPLAY_SUBTITLE.
    private boolean swappedLayout;
    private String swappedSongTitle;
    private String swappedArtist;
    private String lastSubtitle;
    private String lastRawTitle;
    private String lastArtistMeta;
    private String lastNotifTitle;
    private String currentNotifTitle;
    // 1.x persistent lyric candidate slots (fixed31): the notification
    // ticker/text/bigText, kept across re-posts so a lyric-less
    // re-post never wipes the lyric we already have.
    private String currentLyricTicker;
    private String currentLyricText;
    private String currentLyricBigText;

    /** 1.x isLyricCandidate: a lyric line is never the song title,
     * the artist, the album artist, a song+artist combination, or a
     * credits line (v172: "作曲: 困困羊" was showing as the lyric). */
    private static boolean isLyricCandidate(String s, String title,
            String artist, String albumArtist) {
        try {
            if (s == null) {
                return false;
            }
            s = s.trim();
            if (s.isEmpty()) {
                return false;
            }
            if (isCreditOrLyricTitle(s)) {
                return false;
            }
            // v228: status lines and copyright notices are
            // never lyrics (NetEase "网易云音乐正在播放",
            // "(© ...)" / "(P) 2024 ..." / "版权所有").
            if (isStatusMessage(s)) {
                return false;
            }
            if (isCopyrightNotice(s)) {
                return false;
            }
            if (title != null && !title.isEmpty()) {
                if (s.equals(title) || s.startsWith(title + " - ")
                        || s.endsWith(" - " + title)
                        || s.startsWith(title + "：")
                        || s.endsWith("：" + title)
                        || s.startsWith(title + " \u2013 ")
                        || s.endsWith(" \u2013 " + title)) {
                    return false;
                }
            }
            if (artist != null && !artist.isEmpty()) {
                if (s.equals(artist) || s.equals(title + " - " + artist)
                        || s.equals(artist + " - " + title)
                        || s.equals(title + "：" + artist)
                        || s.equals(artist + "：" + title)
                        || s.equals(title + " \u2013 " + artist)
                        || s.equals(artist + " \u2013 " + title)) {
                    return false;
                }
                // v233: "artist - album" (artist + separator +
                // album) is metadata, never a lyric. The separator
                // may be a hyphen, an en-dash, or a full-width
                // colon; the part after it is the album, not the
                // title, so the exact checks above miss it. A real
                // lyric line never starts with the artist's name
                // followed by a separator.
                if (s.startsWith(artist + " - ")
                        || s.startsWith(artist + " \u2013 ")
                        || s.startsWith(artist + "：")) {
                    return false;
                }
            }
            if (albumArtist != null && !albumArtist.isEmpty()
                    && s.equals(albumArtist)) {
                return false;
            }
            if (title != null && !title.isEmpty()) {
                String ns = s.replaceAll("\\s+", "");
                String nt = title.replaceAll("\\s+", "");
                if (!nt.isEmpty() && (ns.equals(nt)
                        || ns.startsWith(nt + "-")
                        || ns.startsWith(nt + "："))) {
                    return false;
                }
                if (artist != null && !artist.isEmpty()) {
                    String na = artist.replaceAll("\\s+", "");
                    if (!na.isEmpty() && ns.contains(nt)
                            && ns.contains(na)) {
                        return false;
                    }
                }
            }
            return true;
        } catch (Throwable th) {
            return false;
        }
    }

    /** 1.x isCreditOrLyricTitle (fixed17): credits line, never a song
     * title. v209: also English credits ("Lyrics by: ..." from
     * LRCLIB / ticker) — those were passing as a title and as a
     * lyric (Yellow card showed "Lyrics by: Guy Berryman/Jonny"
     * as the title). English match is case-insensitive. */
    private static boolean isCreditOrLyricTitle(String s) {
        if (s == null) {
            return false;
        }
        String t = s.trim();
        String[] prefixes = {"\u8bcd\uff1a", "\u66f2\uff1a", "\u8bcd:", "\u66f2:", "\u4f5c\u8bcd", "\u4f5c\u66f2",
                "\u7f16\u66f2", "\u6f14\u5531", "\u539f\u5531", "\u8bcd\u66f2", "\u4f5c\u8bcd\uff1a", "\u4f5c\u66f2\uff1a", "\u7f16\u66f2\uff1a",
                "\u6f14\u5531\uff1a", "\u539f\u5531\uff1a",
                // v228: production credits.
                "\u5236\u4f5c", "\u6df7\u97f3", "\u6bcd\u5e26", "\u76d1\u5236",
                "\u51fa\u54c1", "\u53d1\u884c", "\u5236\u4f5c\uff1a", "\u6df7\u97f3\uff1a",
                "\u6bcd\u5e26\uff1a", "\u76d1\u5236\uff1a", "\u51fa\u54c1\uff1a", "\u53d1\u884c\uff1a"};
        for (String p : prefixes) {
            if (t.startsWith(p)) {
                return true;
            }
        }
        String low = t.toLowerCase(java.util.Locale.ROOT);
        String[] enPrefixes = {"lyrics by", "lyric by", "lyrics:",
                "lyric:", "written by", "composed by", "music by",
                "words by", "arranged by", "produced by",
                "mixed by", "mastered by"};
        for (String p : enPrefixes) {
            if (low.startsWith(p)) {
                return true;
            }
        }
        return false;
    }

    /** 1.x updateLyricCandidates: store the notification's
     * ticker/text/bigText into the persistent slots. Same-song
     * re-posts only overwrite slots that have a value, so a
     * lyric-less re-post does not wipe the lyric we already have. */
    private void updateLyricCandidates(
            android.app.Notification notification, boolean sameSong) {
        try {
            String text = null;
            String bigText = null;
            String ticker = null;
            if (notification != null && notification.extras != null) {
                CharSequence cs = notification.extras.getCharSequence(
                        "android.text");
                if (cs != null) {
                    text = cs.toString().trim();
                }
                CharSequence cb = notification.extras.getCharSequence(
                        "android.bigText");
                if (cb != null) {
                    bigText = cb.toString().trim();
                }
            }
            if (notification != null && notification.tickerText != null) {
                ticker = notification.tickerText.toString().trim();
            }
            if (sameSong) {
                if (text != null && !text.isEmpty()) {
                    currentLyricText = text;
                }
                if (bigText != null && !bigText.isEmpty()) {
                    currentLyricBigText = bigText;
                }
                if (ticker != null && !ticker.isEmpty()) {
                    currentLyricTicker = ticker;
                }
            } else {
                currentLyricText = text;
                currentLyricBigText = bigText;
                currentLyricTicker = ticker;
            }
        } catch (Throwable ignored) {
        }
    }

    /** 1.x refreshLyricCandidatesFromActive (fixed128): re-read the
     * ticker/text/bigText from the live notification into the
     * persistent slots, so a metadata-driven update does not pick a
     * stale notification line over the fresh DISPLAY_SUBTITLE. */
    private void refreshLyricCandidatesFromActive() {
        try {
            if (musicKey == null) {
                return;
            }
            android.service.notification.StatusBarNotification[] actives =
                    getActiveNotifications();
            if (actives == null) {
                return;
            }
            for (android.service.notification.StatusBarNotification sbn : actives) {
                if (sbn == null || sbn.getNotification() == null
                        || !musicKey.equals(sbn.getKey())) {
                    continue;
                }
                updateLyricCandidates(sbn.getNotification(), true);
                break;
            }
        } catch (Throwable ignored) {
        }
    }

    /** v175: split an "Artist - Song" / "Artist - Song" string
     * into [artist, song]. QQ Music uses an en dash. Returns null
     * when there is no dash or either side is empty. */
    /** v196: status messages ("当前列表为空，点击选择歌曲播放")
     * are never the song, the artist, or a lyric. Same check as
     * NotifCollectionHook.isStatusMessage (kept local: the hook
     * class is a different class). */
    // v228: copyright / phonogram notice — never a lyric.
    private static boolean isCopyrightNotice(String s) {
        if (s == null) {
            return false;
        }
        String t = s.trim();
        if (t.isEmpty()) {
            return false;
        }
        if (t.contains("\u00a9") || t.contains("\u2117")
                || t.contains("\u7248\u6743")) {
            return true;
        }
        String low = t.toLowerCase(java.util.Locale.ROOT);
        if (low.contains("copyright")) {
            return true;
        }
        // "(P) 2024 ..." / "(C) 2024 ..." phonogram line.
        if (t.startsWith("(P)") || t.startsWith("(C)")
                || t.startsWith("(p)") || t.startsWith("(c)")) {
            return true;
        }
        return false;
    }

    private static boolean isStatusMessage(String s) {
        if (s == null || s.isEmpty()) {
            return false;
        }
        return s.contains("\u5217\u8868\u4e3a\u7a7a")
                || s.contains("\u70b9\u51fb\u9009\u62e9")
                || s.contains("\u70b9\u51fb\u64ad\u653e")
                || s.contains("\u6682\u65e0")
                || s.contains("\u672a\u64ad\u653e")
                || s.contains("\u6ca1\u6709\u6b63\u5728\u64ad\u653e")
                // v228: "<App>音乐正在播放" status line
                // (NetEase ticker) — never a lyric.
                || s.contains("\u97f3\u4e50\u6b63\u5728\u64ad\u653e");
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

    /**
     * Resolve the lyric from the MediaSession metadata + the
     * notification fields, using the 1.x logic. When the layout is
     * swapped, swappedSongTitle / swappedArtist hold the parsed song
     * / artist (callers should use them as the song/artist). Returns
     * the lyric, or null. Callers pass the notification title as
     * notifTitle (the stable song anchor, 1.x fixed18).
     */
    private String resolveMusicLyric(String rawTitle, String subtitle,
            String artistMeta, String albumArtist, String notifTitle) {
        String ticker = currentLyricTicker;
        String text = currentLyricText;
        String bigText = currentLyricBigText;
        if (notifTitle != null && !notifTitle.isEmpty()) {
            currentNotifTitle = notifTitle;
        }
        // Parse "song - artist" from the subtitle, or from ARTIST.
        String songFromSub = null;
        String artistFromSub = null;
        boolean fromArtist = false;
        if (subtitle != null && !subtitle.isEmpty()
                && subtitle.contains("-")) {
            int dash = subtitle.indexOf('-');
            String a = subtitle.substring(0, dash).trim();
            String b = subtitle.substring(dash + 1).trim();
            if (!a.isEmpty() && !b.isEmpty()) {
                songFromSub = a;
                artistFromSub = b;
            }
        }
        if (songFromSub == null && artistMeta != null
                && !artistMeta.isEmpty() && artistMeta.contains("-")) {
            int dash2 = artistMeta.indexOf('-');
            String xa = artistMeta.substring(0, dash2).trim();
            String ya = artistMeta.substring(dash2 + 1).trim();
            if (!xa.isEmpty() && !ya.isEmpty()) {
                songFromSub = xa;
                artistFromSub = ya;
                fromArtist = true;
            }
        }
        // Swapped detection (1.x fixed152/153).
        if (songFromSub != null && rawTitle != null && !rawTitle.isEmpty()
                && !songFromSub.equals(rawTitle)
                && (artistMeta == null
                        || !songFromSub.equals(artistMeta))) {
            boolean stableField = fromArtist
                    ? (lastArtistMeta != null
                            && lastArtistMeta.equals(artistMeta)
                            && lastRawTitle != null
                            && !lastRawTitle.equals(rawTitle))
                    : (lastSubtitle != null
                            && lastSubtitle.equals(subtitle)
                            && lastRawTitle != null
                            && !lastRawTitle.equals(rawTitle));
            boolean notifStable = lastNotifTitle != null
                    && lastNotifTitle.equals(currentNotifTitle)
                    && lastRawTitle != null
                    && !lastRawTitle.equals(rawTitle)
                    && !rawTitle.equals(currentNotifTitle);
            boolean triggerSwap = songFromSub.equals(currentNotifTitle)
                    || stableField
                    || (!fromArtist
                            && rawTitle.length() > songFromSub.length())
                    || isCreditOrLyricTitle(rawTitle)
                    || rawTitle.startsWith("女：")
                    || rawTitle.startsWith("男：")
                    || rawTitle.startsWith("合：")
                    || rawTitle.startsWith("女:")
                    || rawTitle.startsWith("男:")
                    || notifStable;
            if (triggerSwap) {
                swappedLayout = true;
                swappedSongTitle = songFromSub;
                swappedArtist = artistFromSub;
            }
        }
        // New song on the same key, back to a normal layout.
        if (swappedLayout && songFromSub != null && swappedSongTitle != null
                && !songFromSub.equals(swappedSongTitle)
                && rawTitle != null && rawTitle.equals(songFromSub)) {
            swappedLayout = false;
            swappedSongTitle = null;
            swappedArtist = null;
        }
        lastSubtitle = subtitle;
        lastRawTitle = rawTitle;
        lastArtistMeta = artistMeta;
        lastNotifTitle = currentNotifTitle;

        // v196: when there is no MediaSession TITLE (rawTitle
        // null), fall back to the notification title as the song
        // anchor — the caller already fixed it to the real song
        // name, so a lyric candidate equal to it is the song name
        // and must be rejected (it was showing in every lyric slot
        // because checkTitle was null).
        String checkTitle = songFromSub != null ? songFromSub
                : (rawTitle != null && !rawTitle.isEmpty() ? rawTitle
                        : notifTitle);
        String checkArtist = artistFromSub != null ? artistFromSub : artistMeta;
        String swappedLyric = null;
        if (swappedLayout && swappedSongTitle != null
                && rawTitle != null && !rawTitle.isEmpty()) {
            swappedLyric = rawTitle;
            checkTitle = swappedSongTitle;
            checkArtist = swappedArtist;
        }
        // v228: the swapped session TITLE is the lyric only
        // when it is a real lyric line — a credit line
        // ("编曲：颜小健") or a status/copyright line must
        // not be returned as the lyric; fall through to the
        // normal candidate chain instead.
        if (swappedLyric != null
                && isLyricCandidate(swappedLyric, checkTitle,
                        checkArtist, albumArtist)) {
            return swappedLyric.trim();
        }
        if (isLyricCandidate(ticker, checkTitle, checkArtist, albumArtist)) {
            return ticker.trim();
        }
        if (isLyricCandidate(text, checkTitle, checkArtist, albumArtist)) {
            return text.trim();
        }
        if (isLyricCandidate(bigText, checkTitle, checkArtist, albumArtist)) {
            return bigText.trim();
        }
        if (isLyricCandidate(subtitle, checkTitle, checkArtist, albumArtist)) {
            return subtitle.trim();
        }
        // Last resort: the MediaSession TITLE itself. Some players
        // (NetEase) put the lyric line in TITLE, but the subtitle is
        // not "song - artist", so the swapped detection never fires
        // and the lyric is missed — it fell back to the song name.
        // isLyricCandidate rejects it when it IS the song/artist.
        if (isLyricCandidate(rawTitle, checkTitle, checkArtist, albumArtist)) {
            return rawTitle.trim();
        }
        return null;
    }

    // ---- Network lyrics fallback (LRCLIB, v88) ----
    // Only called when the notification/MediaSession have no lyric.
    // Fetches once per song on a background thread; the 500ms poll
    // then picks the line by playback position.

    private void maybeFetchNetworkLyrics(String song, String artist,
            long durationMs) {
        try {
            if (netLyricFetching) {
                return;
            }
            String key = song + "\u0000" + (artist == null ? "" : artist);
            if (key.equals(netLyricFetchingFor)) {
                return;
            }
            // Already have lines for this exact song — no refetch.
            if (song.equals(netLyricSong)
                    && (artist == null ? netLyricArtist == null
                            : artist.equals(netLyricArtist))
                    && netLyricTimes != null && netLyricTimes.length > 0) {
                return;
            }
            netLyricFetching = true;
            netLyricFetchingFor = key;
            final String fSong = song;
            final String fArtist = artist;
            final long fDurMs = durationMs;
            new Thread(new Runnable() {
                @Override
                public void run() {
                    try {
                        fetchNetworkLyrics(fSong, fArtist, fDurMs);
                    } catch (Throwable ignored) {
                    } finally {
                        netLyricFetching = false;
                    }
                }
            }).start();
        } catch (Throwable ignored) {
        }
    }

    private void fetchNetworkLyrics(String song, String artist,
            long durationMs) {
        java.net.HttpURLConnection conn = null;
        try {
            String url = "https://lrclib.net/api/get?track_name="
                    + java.net.URLEncoder.encode(song, "UTF-8")
                    + "&artist_name="
                    + java.net.URLEncoder.encode(
                            artist == null ? "" : artist, "UTF-8");
            conn = (java.net.HttpURLConnection) new java.net.URL(url)
                    .openConnection();
            conn.setConnectTimeout(5000);
            conn.setReadTimeout(5000);
            conn.setRequestMethod("GET");
            if (conn.getResponseCode() != 200) {
                return;
            }
            java.io.BufferedReader br = new java.io.BufferedReader(
                    new java.io.InputStreamReader(conn.getInputStream(),
                            "UTF-8"));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = br.readLine()) != null) {
                sb.append(line);
            }
            br.close();
            String json = sb.toString();
            String synced = extractJsonString(json, "syncedLyrics");
            if (synced == null || synced.isEmpty()) {
                return;
            }
            // v208: LRCLIB /api/get returns the best fuzzy match, which
            // can be a different version of the same song (live,
            // cover, another album) — same opening, wrong later
            // verses, and timestamps that drift. The response carries
            // its own duration; when we know the playing song's
            // duration and the two differ by more than 10s, it is
            // almost certainly the wrong version — reject it rather
            // than showing wrong lyrics. (No duration known: accept
            // as before.)
            if (durationMs > 0) {
                long libDurMs = extractJsonDurationMs(json);
                if (libDurMs > 0
                        && Math.abs(libDurMs - durationMs) > 10000) {
                    return;
                }
            }
            long[] times;
            String[] texts;
            java.util.ArrayList<Long> tList = new java.util.ArrayList<>();
            java.util.ArrayList<String> xList = new java.util.ArrayList<>();
            for (String lrcLine : synced.split("\n")) {
                String t = lrcLine.trim();
                if (!t.startsWith("[")) {
                    continue;
                }
                int close = t.indexOf(']');
                if (close < 0) {
                    continue;
                }
                String ts = t.substring(1, close).trim();
                String txt = t.substring(close + 1).trim();
                if (txt.isEmpty()) {
                    continue;
                }
                // v228: LRC files carry credit / status /
                // copyright lines as timed lines — never show
                // them as lyrics.
                if (isCreditOrLyricTitle(txt) || isStatusMessage(txt)
                        || isCopyrightNotice(txt)) {
                    continue;
                }
                try {
                    String[] parts = ts.split(":");
                    long ms;
                    if (parts.length == 2) {
                        double sec = Double.parseDouble(parts[1]);
                        ms = (long) (Long.parseLong(parts[0]) * 1000
                                + sec * 1000);
                    } else {
                        continue;
                    }
                    tList.add(ms);
                    xList.add(txt);
                } catch (Throwable ignored) {
                }
            }
            if (tList.isEmpty()) {
                return;
            }
            times = new long[tList.size()];
            texts = new String[xList.size()];
            for (int i = 0; i < tList.size(); i++) {
                times[i] = tList.get(i);
                texts[i] = xList.get(i);
            }
            netLyricSong = song;
            netLyricArtist = artist;
            netLyricTimes = times;
            netLyricTexts = texts;
            netLyricStartTime = System.currentTimeMillis();
            // Push the current line immediately so the capsule does
            // not wait for the next poll tick.
            refreshLyricFromActive();
        } catch (Throwable ignored) {
        } finally {
            if (conn != null) {
                try {
                    conn.disconnect();
                } catch (Throwable ignored) {
                }
            }
        }
    }

    // v208: read a numeric "duration" (seconds, e.g. 268.0) from
    // an LRCLIB JSON response and return it in milliseconds, or -1.
    private static long extractJsonDurationMs(String json) {
        try {
            String pat = "\"duration\":";
            int i = json.indexOf(pat);
            if (i < 0) {
                return -1;
            }
            int start = i + pat.length();
            int end = start;
            while (end < json.length()) {
                char c = json.charAt(end);
                if ((c >= '0' && c <= '9') || c == '.' || c == '-') {
                    end++;
                } else {
                    break;
                }
            }
            if (end <= start) {
                return -1;
            }
            double sec = Double.parseDouble(json.substring(start, end));
            if (sec <= 0) {
                return -1;
            }
            return (long) (sec * 1000);
        } catch (Throwable ignored) {
        }
        return -1;
    }

    private static String extractJsonString(String json, String key) {
        try {
            String pat = "\"" + key + "\":\"";
            int i = json.indexOf(pat);
            if (i < 0) {
                // Maybe null or non-string value.
                return null;
            }
            int start = i + pat.length();
            StringBuilder out = new StringBuilder();
            for (int j = start; j < json.length(); j++) {
                char c = json.charAt(j);
                if (c == '\\' && j + 1 < json.length()) {
                    char n = json.charAt(j + 1);
                    if (n == 'n') {
                        out.append('\n');
                    } else if (n == 't') {
                        out.append('\t');
                    } else if (n == 'r') {
                        out.append('\r');
                    } else if (n == 'u' && j + 5 < json.length()) {
                        out.append((char) Integer.parseInt(
                                json.substring(j + 2, j + 6), 16));
                        j += 4;
                    } else {
                        out.append(n);
                    }
                    j++;
                } else if (c == '"') {
                    return out.toString();
                } else {
                    out.append(c);
                }
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    private String netLyricForCurrentPosition(String song, String artist) {
        try {
            if (netLyricTimes == null || netLyricTexts == null
                    || netLyricTimes.length == 0) {
                return null;
            }
            if (song == null || !song.equals(netLyricSong)) {
                return null;
            }
            long pos = -1;
            if (musicController != null) {
                try {
                    android.media.session.PlaybackState st =
                            musicController.getPlaybackState();
                    if (st != null) {
                        pos = st.getPosition();
                    }
                } catch (Throwable ignored) {
                }
            }
            if (pos < 0) {
                // MediaSession never reports a position (e.g.
                // Douyin): use elapsed time since the lines were
                // fetched, so the line still advances as the song
                // plays instead of sticking on the first line.
                if (netLyricStartTime > 0) {
                    pos = System.currentTimeMillis() - netLyricStartTime;
                } else {
                    return netLyricTexts[0];
                }
            }
            String cur = null;
            for (int i = 0; i < netLyricTimes.length; i++) {
                if (netLyricTimes[i] <= pos) {
                    cur = netLyricTexts[i];
                } else {
                    break;
                }
            }
            return cur != null ? cur : netLyricTexts[0];
        } catch (Throwable ignored) {
            return null;
        }
    }

    // ---- Real-time download progress (v95, v97 multi-source) ----
    /** 1.x readProgressFromRemoteViews: the live progress is often in
     * the RemoteViews ProgressBar, not in extras. Reflection on the
     * mActions list, looking for setMax / setProgress. */
    private static int[] readProgressFromRemoteViews(
            android.app.Notification n) {
        try {
            android.widget.RemoteViews rv = n.contentView;
            if (rv == null) {
                rv = n.bigContentView;
            }
            if (rv == null) {
                return null;
            }
            java.lang.reflect.Field f =
                    android.widget.RemoteViews.class.getDeclaredField("mActions");
            f.setAccessible(true);
            java.util.ArrayList actions =
                    (java.util.ArrayList) f.get(rv);
            if (actions == null) {
                return null;
            }
            int max = -1;
            int prog = -1;
            for (Object a : actions) {
                if (a == null) {
                    continue;
                }
                Class c = a.getClass();
                if (!c.getSimpleName().equals("ReflectionAction")) {
                    continue;
                }
                java.lang.reflect.Field mf =
                        c.getDeclaredField("methodName");
                mf.setAccessible(true);
                String method = (String) mf.get(a);
                java.lang.reflect.Field vf = c.getDeclaredField("value");
                vf.setAccessible(true);
                Object v = vf.get(a);
                if ("setMax".equals(method) && (v instanceof Number)) {
                    max = ((Number) v).intValue();
                } else if ("setProgress".equals(method)
                        && (v instanceof Number)) {
                    prog = ((Number) v).intValue();
                }
            }
            if (max > 0 && prog >= 0) {
                return new int[]{prog, max};
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    /** 1.x parseProgressPercent: "45%" or "12MB / 30MB" in the text. */
    private static int parseProgressPercent(String str) {
        if (str == null || str.isEmpty()) {
            return -1;
        }
        try {
            java.util.regex.Matcher m = java.util.regex.Pattern.compile(
                    "(\\d+(?:\\.\\d+)?)\\s*%").matcher(str);
            if (m.find()) {
                float f = Float.parseFloat(m.group(1));
                if (f >= 0.0f && f <= 100.0f) {
                    return Math.round(f);
                }
            }
            m = java.util.regex.Pattern.compile(
                    "(\\d+(?:\\.\\d+)?)\\s*(?:MB|KB|GB|B)?\\s*/\\s*(\\d+(?:\\.\\d+)?)\\s*(?:MB|KB|GB|B)?")
                    .matcher(str);
            if (m.find()) {
                float a = Float.parseFloat(m.group(1));
                float b = Float.parseFloat(m.group(2));
                if (b > 0.0f && a >= 0.0f && a <= b) {
                    return Math.round((a * 100.0f) / b);
                }
            }
        } catch (Throwable ignored) {
        }
        return -1;
    }

    /** Strip any "NN%" the app itself put in the title, so our
     * appended percent never doubles ("正在下载 45% 45%"). */
    private static String stripPercent(String str) {
        if (str == null) {
            return "";
        }
        return str.replaceAll("\\s*\\d+(?:\\.\\d+)?\\s*%", "").trim();
    }

    /** Normalize a download-type notification to the standard layout
     * (v99, v101): line 1 = 正在下载 + percent, line 2 = content.
     * Returns {line1, line2}. The content is the title after a 下载
     * prefix, else the text when the text is not itself a status.
     * Install (安装) is not handled here any more (user 2026-11-19
     * "把带安装的去掉"). */
    /** v239: raw byte counts (progress / progressMax) when the
     * notification carries a real byte total. Returns null when
     * progress is percent-based (progressMax <= 100, e.g. the
     * Meizu store install log) or absent. Extras first, then
     * RemoteViews. */
    private static long[] readDownloadBytes(
            android.app.Notification n) {
        if (n == null) {
            return null;
        }
        android.os.Bundle b = n.extras;
        if (b != null) {
            long prog = b.getInt(
                    android.app.Notification.EXTRA_PROGRESS, 0);
            long progMax = b.getInt(
                    android.app.Notification.EXTRA_PROGRESS_MAX, 0);
            if (progMax > 100 && prog >= 0) {
                return new long[]{prog, progMax};
            }
        }
        int[] rv = readProgressFromRemoteViews(n);
        if (rv != null && rv[1] > 100 && rv[0] >= 0) {
            return new long[]{rv[0], rv[1]};
        }
        return null;
    }

    /** v239: human byte size — 123B / 12.3KB / 12.5MB / 1.2GB. */
    private static String formatBytes(long bytes) {
        if (bytes < 0) {
            return "?";
        }
        if (bytes < 1024) {
            return bytes + "B";
        }
        double v = bytes;
        String unit = "KB";
        v /= 1024.0;
        if (v >= 1024.0) {
            v /= 1024.0;
            unit = "MB";
        }
        if (v >= 1024.0) {
            v /= 1024.0;
            unit = "GB";
        }
        String s = String.format(java.util.Locale.US, "%.1f", v);
        if (s.endsWith(".0")) {
            s = s.substring(0, s.length() - 2);
        }
        return s + unit;
    }

    /** v239: speed string — "2.3MB/s" / "850KB/s". */
    private static String formatSpeed(long bytesPerSec) {
        if (bytesPerSec < 0) {
            return "";
        }
        return formatBytes(bytesPerSec) + "/s";
    }

    /** v239: speed from the byte delta since the last sample.
     * Returns bytes/sec, or -1 when there is no usable previous
     * sample yet. Only forward progress counts. */
    private long trackDownloadSpeed(long bytes) {
        long now = android.os.SystemClock.uptimeMillis();
        long speed = -1;
        if (bytes >= 0 && lastDownloadBytes >= 0
                && bytes >= lastDownloadBytes
                && now > lastDownloadBytesTime) {
            long dtMs = now - lastDownloadBytesTime;
            if (dtMs > 0) {
                speed = (bytes - lastDownloadBytes) * 1000 / dtMs;
            }
        }
        if (bytes >= 0) {
            lastDownloadBytes = bytes;
            lastDownloadBytesTime = now;
        }
        return speed;
    }

    /** v240: parse a size like "2.03M", "1.98G", "79.26 MB",
     * "1.75 GB", "0.00 KB" into bytes. Units B/KB/MB/GB and
     * the bare K/M/G, with or without a space. -1 if unparsable. */
    private static long parseSizeToBytes(String s) {
        if (s == null) {
            return -1;
        }
        String t = s.trim().toUpperCase(java.util.Locale.US);
        if (t.isEmpty()) {
            return -1;
        }
        long mult = 1;
        if (t.endsWith("GB") || t.endsWith("G")) {
            mult = 1024L * 1024L * 1024L;
            t = t.replaceAll("(GB|G)$", "").trim();
        } else if (t.endsWith("MB") || t.endsWith("M")) {
            mult = 1024L * 1024L;
            t = t.replaceAll("(MB|M)$", "").trim();
        } else if (t.endsWith("KB") || t.endsWith("K")) {
            mult = 1024L;
            t = t.replaceAll("(KB|K)$", "").trim();
        } else if (t.endsWith("B")) {
            t = t.substring(0, t.length() - 1).trim();
        }
        try {
            double v = Double.parseDouble(t);
            if (v < 0) {
                return -1;
            }
            return (long) (v * mult);
        } catch (Throwable ignored) {
            return -1;
        }
    }

    /** v240: downloaded/total from the text fields. Coolapk puts
     * it in infoText as "2.03M/1.98G"; the browser puts it in
     * text as "已下载 79.26 MB，共 1.75 GB". Returns
     * {downloaded, total} in bytes, or null. */
    private static long[] parseDownloadedTotal(String text,
            String infoText) {
        // Coolapk infoText: "2.03M/1.98G".
        if (infoText != null) {
            String s = infoText.trim();
            int slash = s.indexOf('/');
            if (slash > 0 && slash < s.length() - 1) {
                long dl = parseSizeToBytes(
                        s.substring(0, slash).trim());
                long total = parseSizeToBytes(
                        s.substring(slash + 1).trim());
                if (dl >= 0 && total > 0) {
                    return new long[]{dl, total};
                }
            }
        }
        // Browser text: "已下载 79.26 MB，共 1.75 GB".
        if (text != null) {
            String s = text.trim();
            int a = s.indexOf("已下载");
            int b = s.indexOf("，共", a >= 0 ? a : 0);
            if (b < 0) {
                b = s.indexOf(",共", a >= 0 ? a : 0);
            }
            if (a >= 0 && b > a) {
                long dl = parseSizeToBytes(
                        s.substring(a + 3, b).trim());
                long total = parseSizeToBytes(
                        s.substring(b + 2).trim());
                if (dl >= 0 && total > 0) {
                    return new long[]{dl, total};
                }
            }
        }
        return null;
    }

    /** v240: speed straight from the text, e.g. TapTap's
     * "40.81 MB/s  剩余时间 35秒". Returns bytes/sec, or -1. */
    private static long parseSpeedFromText(String text) {
        if (text == null) {
            return -1;
        }
        // Find "<number> <unit>/s" — the first such occurrence.
        java.util.regex.Matcher m = java.util.regex.Pattern.compile(
                "([0-9]+(?:\\.[0-9]+)?)\\s*([KMGT]?B)/s",
                java.util.regex.Pattern.CASE_INSENSITIVE)
                .matcher(text);
        if (!m.find()) {
            return -1;
        }
        try {
            double v = Double.parseDouble(m.group(1));
            String u = m.group(2).toUpperCase(java.util.Locale.US);
            long mult = 1;
            if ("KB".equals(u)) {
                mult = 1024L;
            } else if ("MB".equals(u)) {
                mult = 1024L * 1024L;
            } else if ("GB".equals(u)) {
                mult = 1024L * 1024L * 1024L;
            } else if ("TB".equals(u)) {
                mult = 1024L * 1024L * 1024L * 1024L;
            }
            if (v < 0) {
                return -1;
            }
            return (long) (v * mult);
        } catch (Throwable ignored) {
            return -1;
        }
    }

    /** v240: ETA seconds from the text. TapTap: "剩余时间 35秒";
     * browser subText: "还剩 43 秒". Returns seconds, or -1. */
    private static long parseEtaSeconds(String text, String subText) {
        String[] srcs = new String[]{text, subText};
        for (String s : srcs) {
            if (s == null) {
                continue;
            }
            java.util.regex.Matcher m =
                    java.util.regex.Pattern.compile(
                            "(?:剩余时间|还剩)\\s*([0-9]+)\\s*秒")
                            .matcher(s);
            if (m.find()) {
                try {
                    long v = Long.parseLong(m.group(1));
                    if (v >= 0) {
                        return v;
                    }
                } catch (Throwable ignored) {
                }
            }
        }
        return -1;
    }

    /** v239: line 1 = file name (what is downloading); line 2 =
     * progress details — "正在下载 45%" + " · 12.5MB/48MB"
     * (byte counts, when the notification carries a real byte
     * total) + " · 2.3MB/s" (when a speed sample exists).
     * Percent-only progress shows just the %. */
    private static String[] normalizeDownload(String title, String text,
            int pct, long bytes, long total, long speed,
            long etaSec) {
        String t = title == null ? "" : title;
        String x = text == null ? "" : text;
        // v241: app-store install — the title is the status
        // ("正在安装") and the app name lives in the text.
        boolean isInstall = t.contains("安装");
        String status = isInstall ? "正在安装" : "正在下载";
        // Content: title after a 下载/安装 prefix…
        String content = null;
        String[] prefixes = {"正在下载", "下载", "正在安装", "安装"};
        for (String pre : prefixes) {
            if (t.startsWith(pre)) {
                String rest = t.substring(pre.length()).trim();
                if (!rest.isEmpty()) {
                    content = rest;
                }
                break;
            }
        }
        // …else the text, when the text is not itself a status.
        if (content == null) {
            if (!x.isEmpty() && !x.contains("下载")
                    && !x.contains("准备") && !x.contains("安装")) {
                content = x;
            } else if (!t.isEmpty()
                    && !t.equals("正在下载") && !t.equals("下载")
                    && !t.equals("正在安装") && !t.equals("安装")) {
                content = t;
            } else {
                content = x;
            }
        }
        if (content == null || content.isEmpty()) {
            content = status;
        }
        // v240: TapTap titles carry the percent ("正在下载{game}
        // 21%") — the content already had "正在下载" stripped,
        // so also strip a trailing " N%" instead of repeating
        // the percent in line 1.
        content = content.replaceAll("\\s+[0-9]+%$", "").trim();
        if (content.isEmpty()) {
            content = status;
        }
        StringBuilder line2 = new StringBuilder(status);
        if (pct >= 0) {
            line2.append(" ").append(pct).append("%");
        }
        if (bytes >= 0 && total > 0) {
            line2.append(" · ").append(formatBytes(bytes))
                    .append("/").append(formatBytes(total));
        }
        if (speed >= 0) {
            line2.append(" · ").append(formatSpeed(speed));
        }
        // v240: ETA from the text ("还剩43秒").
        if (etaSec >= 0) {
            line2.append(" · 还剩").append(etaSec).append("秒");
        }
        return new String[]{content, line2.toString()};
    }

    /** Best percent across all sources (extras, RemoteViews, text),
     * highest wins (1.x fixed136: progress only increases). -1 if
     * no source carries progress. */
    private static int readDownloadPct(android.app.Notification n,
            String title, String text) {
        if (n == null) {
            return -1;
        }
        int best = -1;
        android.os.Bundle b = n.extras;
        if (b != null) {
            int prog = b.getInt(
                    android.app.Notification.EXTRA_PROGRESS, 0);
            int progMax = b.getInt(
                    android.app.Notification.EXTRA_PROGRESS_MAX, 0);
            if (progMax > 0) {
                best = (prog * 100) / progMax;
            }
        }
        int[] rv = readProgressFromRemoteViews(n);
        if (rv != null && rv[1] > 0) {
            int rvPct = (rv[0] * 100) / rv[1];
            if (rvPct > best) {
                best = rvPct;
            }
        }
        int textPct = parseProgressPercent(text);
        if (textPct < 0) {
            textPct = parseProgressPercent(title);
        }
        if (textPct > best) {
            best = textPct;
        }
        if (best < 0) {
            return -1;
        }
        if (best > 100) {
            best = 100;
        }
        return best;
    }

    /** Dual-condition download signal (v110). A notification is
     * only a download when it carries real progress (checked by the
     * caller) AND one of these signals, and none of the exclusions.
     * Chat messages have neither the word 下载 nor a download
     * channel nor a downloader package, so they stay ordinary
     * notifications even if they carry stale progress extras. */
    private static boolean hasDownloadSignal(String pkg,
            String channelId, String title, String text) {
        // Exclusions first: upload / backup / sync are never
        // downloads, even with progress. v241: 安装 is NO
        // longer excluded — the app store's install progress
        // (user 2026-11-21 "add the app store too") is a
        // download capsule too. The signal gate below still
        // blocks a stray 安装 from a non-store app (no 下载
        // keyword, no download channel, no downloader pkg).
        if (containsAny(title, "上传", "备份", "同步")
                || containsAny(text, "上传", "备份", "同步")) {
            return false;
        }
        // Signal 1: the words say 下载.
        if (containsAny(title, "下载") || containsAny(text, "下载")) {
            return true;
        }
        // Signal 2: a download channel.
        if (channelId != null && channelId.toLowerCase(
                java.util.Locale.US).contains("download")) {
            return true;
        }
        // Signal 3: a known store / browser / downloader app.
        return pkg != null && isDownloaderPkg(pkg);
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

    /** Known app-store / browser / downloader packages (v110). The
     * 下载 keyword and the download channel cover most real
     * downloads; this is the backstop for ones that show only a
     * filename and a percent. */
    private static boolean isDownloaderPkg(String pkg) {
        // App stores.
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
        // Browsers.
        if (pkg.equals("com.android.browser")
                || pkg.equals("com.UCMobile")
                || pkg.equals("com.tencent.mtt")
                || pkg.equals("com.baidu.searchbox")
                || pkg.equals("org.mozilla.firefox")
                || pkg.equals("com.microsoft.emmx")
                || pkg.equals("com.quark.browser")
                || pkg.equals("com.qihoo.browser")
                || pkg.equals("com.opera.browser")) {
            return true;
        }
        // Download managers.
        return pkg.equals("com.android.providers.downloads")
                || pkg.equals("com.dv.adm");
    }

    private void startDownloadTick() {
        try {
            // v241: a tick already running for THIS download
            // must keep its timer — re-posts of the same
            // notification arrive every ~0.5-1s and used to
            // reset the 1s delay each time, so the tick never
            // fired and the capsule never refreshed.
            if (downloadTickRunnable != null
                    && downloadKey != null
                    && downloadKey.equals(downloadTickKey)) {
                return;
            }
            stopDownloadTick();
            downloadTickKey = downloadKey;
            if (downloadTickHandler == null) {
                downloadTickHandler = new android.os.Handler(
                        android.os.Looper.getMainLooper());
            }
            downloadTickRunnable = new Runnable() {
                @Override
                public void run() {
                    try {
                        if (downloadKey == null) {
                            return;
                        }
                        refreshDownloadProgress();
                    } catch (Throwable ignored) {
                    } finally {
                        try {
                            if (downloadKey != null
                                    && downloadTickHandler != null
                                    && downloadTickRunnable != null) {
                                downloadTickHandler.postDelayed(
                                        downloadTickRunnable,
                                        DOWNLOAD_TICK_MS);
                            }
                        } catch (Throwable ignored) {
                        }
                    }
                }
            };
            downloadTickHandler.postDelayed(
                    downloadTickRunnable, DOWNLOAD_TICK_MS);
        } catch (Throwable ignored) {
        }
    }

    private void stopDownloadTick() {
        try {
            if (downloadTickHandler != null && downloadTickRunnable != null) {
                downloadTickHandler.removeCallbacks(downloadTickRunnable);
            }
        } catch (Throwable ignored) {
        }
        downloadTickRunnable = null;
        downloadTickKey = null;
    }

    /** v242: does the live title/text say the download is
     * done? Apps rewrite the notification as "下载完成"
     * (and drop the progress extras) instead of posting 100%.
     */
    private static boolean isDownloadComplete(String title,
            String text) {
        return (title != null
                && (title.contains("下载完成")
                        || title.contains("安装完成")
                        || title.contains("已完成")
                        || title.contains("下载完毕")
                        || title.contains("已下载")
                        || title.contains("下载成功")
                        || title.toLowerCase(java.util.Locale.ROOT)
                                .contains("download complete")))
                || (text != null
                        && (text.contains("下载完成")
                                || text.contains("安装完成")
                                || text.contains("已完成")
                                || text.contains("下载完毕")
                                || text.contains("已下载")
                                || text.contains("下载成功")
                                || text.toLowerCase(java.util.Locale.ROOT)
                                        .contains("download complete")));
    }

    /** v242: tell SystemUI to drop the download capsule. */
    private void sendDownloadRemove(String key) {
        if (key == null) {
            return;
        }
        try {
            android.content.Intent intent =
                    new android.content.Intent(ACTION_DOWNLOAD_REMOVE);
            intent.putExtra("key", key);
            sendBroadcast(intent);
        } catch (Throwable ignored) {
        }
    }

    private void refreshDownloadProgress() {
        if (downloadKey == null) {
            return;
        }
        try {
            android.service.notification.StatusBarNotification[] actives =
                    getActiveNotifications();
            if (actives == null) {
                return;
            }
            for (android.service.notification.StatusBarNotification sbn : actives) {
                if (sbn == null || sbn.getNotification() == null
                        || !downloadKey.equals(sbn.getKey())) {
                    continue;
                }
                android.app.Notification notif = sbn.getNotification();
                android.os.Bundle ex = notif.extras;
                CharSequence titleCs = ex != null ? ex.getCharSequence(
                        android.app.Notification.EXTRA_TITLE) : null;
                CharSequence textCs0 = ex != null ? ex.getCharSequence(
                        android.app.Notification.EXTRA_TEXT) : null;
                int pct = readDownloadPct(notif,
                        titleCs != null ? titleCs.toString() : null,
                        textCs0 != null ? textCs0.toString() : null);
                // v242: a completed notification usually drops
                // the progress extras (pct < 0) and rewrites
                // itself as "下载完成" — that IS done, not a
                // missing download. Treat it as 100% so the
                // completed branch below fires.
                if (pct < 0) {
                    String ct = titleCs != null
                            ? titleCs.toString() : null;
                    String cx = textCs0 != null
                            ? textCs0.toString() : null;
                    if (isDownloadComplete(ct, cx)) {
                        pct = 100;
                    } else {
                        // No progress and no completion word:
                        // the download stopped progressing.
                        // Count it as gone; two such ticks
                        // remove the capsule instead of
                        // leaving it stuck forever.
                        if (++downloadGoneTicks >= 2) {
                            sendDownloadRemove(downloadKey);
                            stopDownloadTick();
                            downloadKey = null;
                        }
                        return;
                    }
                }
                downloadGoneTicks = 0;
                // v240: bytes / speed / ETA from the text fields.
                String t2 = titleCs != null
                        ? titleCs.toString() : null;
                String x2 = textCs0 != null
                        ? textCs0.toString() : null;
                String i2 = ex != null
                        ? getText(ex, "android.infoText") : null;
                String s2 = ex != null
                        ? getText(ex, "android.subText") : null;
                long[] dlParsed = parseDownloadedTotal(x2, i2);
                long[] dlBytes = dlParsed != null
                        ? dlParsed : readDownloadBytes(notif);
                long dlB = dlBytes != null ? dlBytes[0] : -1;
                long dlT = dlBytes != null ? dlBytes[1] : -1;
                long prevDlB = lastDownloadBytes;
                long dlSpeed = parseSpeedFromText(x2);
                if (dlSpeed < 0 && dlB >= 0) {
                    dlSpeed = trackDownloadSpeed(dlB);
                }
                long dlEta = parseEtaSeconds(x2, s2);
                // v242: completed — show "下载完成" (or
                // "安装完成") and stop the tick; BubbleWindow
                // auto-removes the capsule after a few seconds.
                if (pct >= 100) {
                    String[] doneLines = normalizeDownload(
                            titleCs != null ? titleCs.toString() : null,
                            textCs0 != null ? textCs0.toString() : null,
                            100, -1, -1, -1, -1);
                    String doneText = (titleCs != null
                            && titleCs.toString().contains("安装"))
                            ? "安装完成" : "下载完成";
                    android.content.Intent doneIntent =
                            new android.content.Intent(ACTION_DOWNLOAD);
                    doneIntent.putExtra("key", downloadKey);
                    doneIntent.putExtra("title", doneLines[0]);
                    doneIntent.putExtra("text", doneText);
                    doneIntent.putExtra("pct", 100);
                    doneIntent.putExtra("completed", true);
                    try {
                        sendBroadcast(doneIntent);
                    } catch (Throwable ignored) {
                    }
                    stopDownloadTick();
                    downloadKey = null;
                    return;
                }
                // v241: skip only when NOTHING visible changed
                // — pct, bytes, speed and ETA all count.
                if (pct == lastDownloadPct && dlB == prevDlB
                        && dlSpeed == lastDownloadSpeed
                        && dlEta == lastDownloadEta) {
                    return;
                }
                lastDownloadPct = pct;
                lastDownloadSpeed = dlSpeed;
                lastDownloadEta = dlEta;
                CharSequence titleCs2 = ex.getCharSequence(
                        android.app.Notification.EXTRA_TITLE);
                CharSequence textCs2 = ex.getCharSequence(
                        android.app.Notification.EXTRA_TEXT);
                String[] lines = normalizeDownload(
                        titleCs2 != null ? titleCs2.toString() : null,
                        textCs2 != null ? textCs2.toString() : null,
                        pct, dlB, dlT, dlSpeed, dlEta);
                Intent intent = new Intent(ACTION_DOWNLOAD);
                intent.putExtra("key", downloadKey);
                intent.putExtra("title", lines[0]);
                intent.putExtra("text", lines[1]);
                intent.putExtra("pct", pct);
                try {
                    sendBroadcast(intent);
                } catch (Throwable ignored) {
                }
                return;
            }
            // v242: notification gone — finished or cancelled.
            // Require two consecutive gone ticks so a re-post
            // gap does not wipe a live download, then remove
            // the capsule (it has nothing to show any more).
            if (++downloadGoneTicks >= 2) {
                sendDownloadRemove(downloadKey);
                stopDownloadTick();
                downloadKey = null;
            }
        } catch (Throwable ignored) {
        }
    }

    private void teardownMusicListener() {
        stopLyricPoll();
        // v172: unpinning stops the LYRIC listener only. The
        // controller + progress tick stay alive while the session
        // is active, so an open (unpinned) card keeps refreshing.
        // The session end (onNotificationRemoved) stops the tick
        // and drops the controller.
        if (musicController != null && musicCallback != null) {
            try {
                musicController.unregisterCallback(musicCallback);
            } catch (Throwable ignored) {
            }
        }
        musicCallback = null;
        lastLyricSent = null;
    }

    /** v172: the music session really ended — stop the progress
     * tick, drop the controller, and reset the song-change state
     * so the next song (even the same title) is a fresh pop. */
    private void teardownMusicSession() {
        teardownMusicListener();
        stopMusicTick();
        musicController = null;
        currentMusicTitle = null;
        currentMusicArtist = null;
        currentMusicNotifKey = null;
        currentMusicDurationMs = -1;
        lastBroadcastPositionMs = -1;
        // v216: a fresh session starts with a fresh song-name lock.
        lastBroadcastTitle = null;
        lastBroadcastArtist = null;
        lastBroadcastRawArtist = null;
        lastBroadcastArtTitle = null;
        lastBroadcastDuration = -1;
    }

    /** v171: execute a music-card control on the active session
     * (1.x handleMusicControl, via MediaController transport
     * controls — the notification's own actions are not needed). */
    private void handleMusicControl(String cmd, long seekPos) {
        if (cmd == null || musicController == null) {
            return;
        }
        try {
            android.media.session.MediaController.TransportControls tc =
                    musicController.getTransportControls();
            if (tc == null) {
                return;
            }
            if ("toggle".equals(cmd)) {
                android.media.session.PlaybackState st =
                        musicController.getPlaybackState();
                boolean playing = st != null
                        && st.getState()
                                == android.media.session.PlaybackState
                                        .STATE_PLAYING;
                if (playing) {
                    tc.pause();
                } else {
                    tc.play();
                }
            } else if ("next".equals(cmd)) {
                tc.skipToNext();
            } else if ("prev".equals(cmd)) {
                tc.skipToPrevious();
            } else if ("favorite".equals(cmd)) {
                // v174: toggle from the real rating, not always
                // "liked" - the card already flipped its local heart,
                // so send the opposite of the current rating.
                try {
                    boolean cur = readHeartRating();
                    tc.setRating(android.media.Rating
                            .newHeartRating(!cur));
                    pendingFavorite = !cur;
                    pendingFavoriteAt = System.currentTimeMillis();
                } catch (Throwable ignored) {
                }
            } else if ("seek".equals(cmd) && seekPos >= 0) {
                tc.seekTo(seekPos);
            }
            broadcastMusicProgress();
        } catch (Throwable ignored) {
        }
    }

    private void startMusicTick() {
        try {
            stopMusicTick();
            if (musicTickHandler == null) {
                musicTickHandler = new android.os.Handler(
                        android.os.Looper.getMainLooper());
            }
            musicTickRunnable = new Runnable() {
                @Override
                public void run() {
                    try {
                        broadcastMusicProgress();
                    } catch (Throwable ignored) {
                    }
                    try {
                        if (musicController != null
                                && musicTickHandler != null
                                && musicTickRunnable != null) {
                            musicTickHandler.postDelayed(
                                    musicTickRunnable, MUSIC_TICK_MS);
                        }
                    } catch (Throwable ignored) {
                    }
                }
            };
            musicTickHandler.postDelayed(musicTickRunnable, MUSIC_TICK_MS);
        } catch (Throwable ignored) {
        }
    }

    private void stopMusicTick() {
        try {
            if (musicTickHandler != null && musicTickRunnable != null) {
                musicTickHandler.removeCallbacks(musicTickRunnable);
            }
        } catch (Throwable ignored) {
        }
        musicTickRunnable = null;
    }

    /** v171: read position / duration / playing from the session
     * and broadcast them for the music card's progress bar.
     * (1.x fixed62: a broadcast position identical to the previous
     * broadcast's is a stale repeat — the bar keeps the locally
     * interpolated clock instead of jumping back.) */

    /** v174: the current heart-rating state from the session metadata
     * (false when there is no rating / no session). A just-set rating
     * wins while it is fresh, so two quick taps toggle correctly even
     * before the session metadata catches up. */
    private boolean readHeartRating() {
        if (pendingFavorite != null
                && System.currentTimeMillis() - pendingFavoriteAt
                        < 3000) {
            return pendingFavorite;
        }
        try {
            if (musicController == null) {
                return false;
            }
            android.media.MediaMetadata md =
                    musicController.getMetadata();
            if (md == null) {
                return false;
            }
            android.media.Rating rating = md.getRating(
                    android.media.MediaMetadata.METADATA_KEY_RATING);
            return rating != null && rating.isRated()
                    && rating.hasHeart();
        } catch (Throwable ignored) {
            return false;
        }
    }

    // v209: is this music key from QQ Music Flyme edition?
    // Key form: "0|com.meizu.media.music|2|null|10218".
    private static boolean isFlymeMusicKey(String key) {
        if (key == null) {
            return false;
        }
        try {
            String[] parts = key.split("\\|");
            return parts.length > 1
                    && "com.meizu.media.music".equals(parts[1]);
        } catch (Throwable ignored) {
        }
        return false;
    }

    // v209: Flyme session-path song/artist, mirroring the hook's
    // resolveFlymeSongArtist (v206). DISPLAY_SUBTITLE is usually
    // just the artist; ALBUM is the song (strip " (version)");
    // ARTIST is "song-artist" (song first). Returns [song, artist]
    // (either may be null) or null when nothing resolves.
    private static String[] resolveFlymeSessionSongArtist(
            String subtitle, String artist, String album) {
        String song = null;
        String art = null;
        if (subtitle != null && !subtitle.isEmpty()
                && !isStatusMessage(subtitle)
                && !isCreditOrLyricTitle(subtitle)) {
            String[] p = parseArtistSong(subtitle);
            if (p != null) {
                song = p[0];
                art = p[1];
            } else {
                art = subtitle;
            }
        }
        // v215: the session ALBUM is NEVER used for the song name
        // (user 2026-11-20 "有些歌他读的是专辑名" / "还是会读错"):
        // for some songs ALBUM holds the real album title, not the
        // song. The hook path was fixed in v214, but the card title
        // comes from this session path, so ALBUM is removed here too.
        // Song now comes from DISPLAY_SUBTITLE or the session ARTIST
        // ("song-artist", song first) only.
        if (song == null && artist != null && !artist.isEmpty()
                && !isStatusMessage(artist)) {
            String[] p = parseArtistSong(artist);
            if (p != null) {
                song = p[0];
                if (art == null) {
                    String after = p[1];
                    int sep = after.indexOf(" - ");
                    art = (sep > 0)
                            ? after.substring(0, sep).trim()
                            : after.trim();
                }
            }
        }
        if (song == null && art == null) {
            return null;
        }
        return new String[]{song, art};
    }

    private void broadcastMusicProgress() {
        try {
            if (musicController == null) {
                return;
            }
            android.media.session.PlaybackState st =
                    musicController.getPlaybackState();
            android.media.MediaMetadata md =
                    musicController.getMetadata();
            long pos = st != null ? st.getPosition() : 0;
            long dur = -1;
            if (md != null) {
                dur = md.getLong(android.media.MediaMetadata
                        .METADATA_KEY_DURATION);
            }
            boolean playing = st != null
                    && st.getState()
                            == android.media.session.PlaybackState
                                    .STATE_PLAYING;
            // v174: the real heart-rating state, so the card's heart
            // reflects whether this song is actually favorited. A
            // just-set rating wins for 3s - the session metadata lags
            // behind setRating, and re-reading it would send the old
            // value back and undo the card's optimistic flip.
            boolean favorite = false;
            boolean hasFavorite = false;
            if (pendingFavorite != null
                    && System.currentTimeMillis() - pendingFavoriteAt
                            < 3000) {
                hasFavorite = true;
                favorite = pendingFavorite;
            } else {
                pendingFavorite = null;
                try {
                    if (md != null) {
                        android.media.Rating rating = md.getRating(
                                android.media.MediaMetadata
                                        .METADATA_KEY_RATING);
                        if (rating != null && rating.isRated()) {
                            hasFavorite = true;
                            favorite = rating.hasHeart();
                        }
                    }
                } catch (Throwable ignored) {
                }
            }
            // v204: 1.0.5 sendMusicStateFromController — the tick
            // also carries the song title/artist, so a manual skip
            // updates the card without waiting for a new
            // notification. Title: Flyme resolves from ARTIST /
            // DISPLAY_SUBTITLE (v209/v215), others take the
            // notification title unless it is a credit/lyric line,
            // else the session metadata TITLE. Sticky (v216,
            // generic - all apps): a candidate that differs from
            // the locked title is only accepted on a real song
            // change (raw ARTIST / duration / position jump).
            String metaTitle = null;
            String metaArtist = null;
            String metaSubtitle = null;
            String metaAlbum = null;
            if (md != null) {
                metaTitle = md.getString(android.media.MediaMetadata
                        .METADATA_KEY_TITLE);
                metaArtist = md.getString(android.media.MediaMetadata
                        .METADATA_KEY_ARTIST);
                metaSubtitle = md.getString(android.media.MediaMetadata
                        .METADATA_KEY_DISPLAY_SUBTITLE);
                metaAlbum = md.getString(android.media.MediaMetadata
                        .METADATA_KEY_ALBUM);
            }
            // v205: QQ Flyme's session puts the lyric in TITLE and
            // "song-artist" in DISPLAY_SUBTITLE (the system player
            // shows the lyric big and song-artist small; only QQ
            // Flyme does this — QQ Original and Migu put the song
            // in TITLE and the artist in DISPLAY_SUBTITLE). When
            // DISPLAY_SUBTITLE parses as "song-artist" (en dash
            // only, so a hyphenated artist name like "A-Lin" never
            // splits), the song/artist come from it — the metadata
            // TITLE is the lyric, not the song. Other apps'
            // DISPLAY_SUBTITLE never parses this way, so they are
            // unaffected.
            String subSong = null;
            String subArtist = null;
            if (metaSubtitle != null && !metaSubtitle.isEmpty()) {
                int dash = metaSubtitle.indexOf('\u2013');
                if (dash > 0 && dash < metaSubtitle.length() - 1) {
                    String a = metaSubtitle.substring(0, dash).trim();
                    String b = metaSubtitle.substring(dash + 1).trim();
                    if (!a.isEmpty() && !b.isEmpty()) {
                        subSong = a;
                        subArtist = b;
                    }
                }
            }
            // v209: Flyme separate handling (session path). The
            // notification title and the session TITLE cycle through
            // the lyric line, credits lines and "song-artist", so
            // neither is a reliable song name — the hook already
            // resolves Flyme this way (v206), but the session path
            // was still taking the cycling title and overwriting
            // the capsule/card title with a lyric. Resolve from
            // ARTIST / DISPLAY_SUBTITLE here too (never ALBUM, v215),
            // and never fall through to the cycling title for Flyme.
            String flymeSong = null;
            String flymeArtist = null;
            if (isFlymeMusicKey(musicKey)) {
                String[] fa = resolveFlymeSessionSongArtist(
                        metaSubtitle, metaArtist, metaAlbum);
                if (fa != null) {
                    flymeSong = fa[0];
                    flymeArtist = fa[1];
                }
            }
            // v216: generic sticky song-name lock (1.0.5 rule, all
            // music apps - user 2026-11-20 "做成通用"). The title
            // candidate is only accepted when the song actually
            // changed. Song-change evidence (any one counts): the
            // raw session ARTIST string changed, the duration
            // changed, or the position jumped backwards (a new song
            // started, or a seek back to the start). Otherwise the
            // locked title stays - the notification title and the
            // session TITLE cycle through lyric lines and credits
            // while playing (Flyme), and must never overwrite the
            // song name. The old sticky only guarded metadata
            // titles (titleFromNotif bypassed it), so a lyric-line
            // notification title walked straight in - that is the
            // v215 《退后》 capsule bug.
            String title = null;
            if (flymeSong != null) {
                title = flymeSong;
            } else if (subSong != null) {
                title = subSong;
            } else if (isFlymeMusicKey(musicKey)) {
                // v216: Flyme + no ARTIST-derived song - never fall
                // back to the cycling notification title or the
                // session TITLE (both carry lyric lines). Keep the
                // locked title; a real song change resolves
                // flymeSong again from the new ARTIST.
                title = lastBroadcastTitle;
            } else if (currentNotifTitle != null
                    && !currentNotifTitle.isEmpty()
                    && !isCreditOrLyricTitle(currentNotifTitle)) {
                title = currentNotifTitle;
            } else if (metaTitle != null && !metaTitle.isEmpty()
                    && !isCreditOrLyricTitle(metaTitle)) {
                title = metaTitle;
            }
            if (title != null && lastBroadcastTitle != null
                    && !title.equals(lastBroadcastTitle)) {
                boolean songChanged = false;
                if (metaArtist != null
                        && lastBroadcastRawArtist != null
                        && !metaArtist.equals(lastBroadcastRawArtist)) {
                    songChanged = true;
                } else if (dur > 0 && lastBroadcastDuration > 0
                        && dur != lastBroadcastDuration) {
                    songChanged = true;
                } else if (pos >= 0 && lastBroadcastPositionMs > 0
                        && pos + 2000 < lastBroadcastPositionMs) {
                    songChanged = true;
                }
                // v230: for non-Flyme apps the notification
                // title IS the song name (stable) — a changed
                // notification title is a song change by
                // itself. On rapid skips the session evidence
                // is unavailable (same artist, position never
                // advanced, duration stale), so the sticky
                // would lock the previous song's name.
                if (!songChanged && !isFlymeMusicKey(musicKey)
                        && currentNotifTitle != null
                        && title != null
                        && title.equals(currentNotifTitle)) {
                    songChanged = true;
                }
                if (!songChanged) {
                    title = lastBroadcastTitle;
                }
            }
            String artist = flymeArtist != null ? flymeArtist
                    : (subArtist != null ? subArtist
                            : (metaArtist != null && !metaArtist.isEmpty()
                                    ? metaArtist : null));
            // v218: session album art push. The hook only reads art
            // once at post time, so art that arrives late (NetEase
            // ~0.1s after the post) is missed, and the broadcast
            // path passes no art at all (it reads largeIcon as a
            // Bitmap; all five apps post an Icon, so the cast fails
            // and nothing is sent). The live controller here has
            // the art as soon as the session does — read it every
            // broadcast, downscale to 128px (same Binder budget as
            // v171), and send it only when it is new for this song
            // (first arrival, or song change).
            android.graphics.Bitmap sessionArt = null;
            boolean artNew = false;
            try {
                if (md != null) {
                    android.graphics.Bitmap rawArt = md.getBitmap(
                            android.media.MediaMetadata
                                    .METADATA_KEY_ALBUM_ART);
                    if (rawArt != null
                            && (rawArt.getWidth() > 128
                                    || rawArt.getHeight() > 128)) {
                        rawArt = android.graphics.Bitmap
                                .createScaledBitmap(rawArt, 128, 128,
                                        true);
                    }
                    sessionArt = rawArt;
                }
            } catch (Throwable ignored) {
                sessionArt = null;
            }
            if (sessionArt != null) {
                String artKey = title != null ? title : "";
                if (!artKey.equals(lastBroadcastArtTitle)) {
                    artNew = true;
                }
            }
            boolean titleChanged = title != null
                    && !title.equals(lastBroadcastTitle);
            boolean artistChanged = artist != null
                    && !artist.equals(lastBroadcastArtist);
            if (pos == lastBroadcastPositionMs && !playing
                    && (!hasFavorite
                            || favorite == lastBroadcastFavorite)
                    && !titleChanged && !artistChanged && !artNew) {
                return;
            }
            // v229: preserve the jump reference across a song
            // change. lastBroadcastPositionMs is what the v216
            // sticky compares against to detect a backward
            // position jump (new song started). Overwriting it
            // with the new song's ~0 on a tick that runs before
            // the notification arrives (session callback fires
            // first) loses the old song's end position, so the
            // jump is never seen and the new title is never
            // accepted — the card keeps the previous song's
            // name on auto-advance (same artist, so the ARTIST
            // evidence never fires either). Only advance it
            // while the same song plays, or reset it when a
            // new title was just accepted.
            if (titleChanged || pos >= lastBroadcastPositionMs) {
                lastBroadcastPositionMs = pos;
            }
            if (hasFavorite) {
                lastBroadcastFavorite = favorite;
            }
            if (title != null) {
                lastBroadcastTitle = title;
            }
            if (artist != null) {
                lastBroadcastArtist = artist;
            }
            if (metaArtist != null) {
                lastBroadcastRawArtist = metaArtist;
            }
            if (artNew) {
                lastBroadcastArtTitle = title != null ? title : "";
            }
            if (dur > 0) {
                lastBroadcastDuration = dur;
            }
            android.content.Intent intent = new android.content.Intent(
                    ACTION_MUSIC_PROGRESS);
            intent.putExtra("position", pos);
            intent.putExtra("duration", dur);
            intent.putExtra("playing", playing);
            if (title != null) {
                intent.putExtra("title", title);
            }
            if (artist != null) {
                intent.putExtra("artist", artist);
            }
            if (hasFavorite) {
                intent.putExtra("favorite", favorite);
            }
            if (artNew && sessionArt != null) {
                intent.putExtra("album_art", sessionArt);
            }
            if (musicKey != null) {
                intent.putExtra("key", musicKey);
            }
            sendBroadcast(intent);
        } catch (Throwable ignored) {
        }
    }

    /** v418: the app's version (name + code) for the log, so a log
     * file always shows which build produced it. */
    private String appVersion() {
        try {
            android.content.pm.PackageInfo pi = getPackageManager()
                    .getPackageInfo(getPackageName(), 0);
            return pi.versionName + " (" + pi.versionCode + ")";
        } catch (Throwable t) {
            return "unknown";
        }
    }

    @Override
    public void onListenerConnected() {
        // v158: prove the log is writing — one line on every
        // (re)connect, so the user can check the file exists even
        // before any notification arrives.
        FileLog.i("Listener connected, FileLog ready");
        // v418 (user 2026-11-22 "让你把版本写入日志"): log the
        // version on every connect, so a log file always shows
        // which build produced it.
        FileLog.i("Version: " + appVersion());
        // v430: start the periodic update check (see fields above).
        startUpdateCheck();
        // v133: the toggle command comes from SystemUI (another
        // process), so this receiver MUST be exported. A plain
        // 2-arg registerReceiver never receives a cross-app
        // broadcast on Android 13+ — that is why the whole
        // sendCallControl/fireCallAction path (v125/v129) never
        // ran: the broadcast never arrived. Same 3-arg reflective
        // RECEIVER_EXPORTED (flag 2) as the lyric receiver below.
        // The diagnostic build registers its receiver with flag 2.
        try {
            java.lang.reflect.Method m = android.content.Context.class
                    .getMethod("registerReceiver",
                            android.content.BroadcastReceiver.class,
                            android.content.IntentFilter.class,
                            int.class);
            android.content.IntentFilter dismissFilter =
                    new android.content.IntentFilter(ACTION_CALL_CONTROL);
            dismissFilter.addAction(ACTION_DISMISS);
            dismissFilter.addAction(ACTION_MUSIC_CONTROL);
            m.invoke(this, callControlReceiver, dismissFilter, 2);
        } catch (Throwable t) {
            try {
                android.content.IntentFilter dismissFilter2 =
                        new android.content.IntentFilter(
                                ACTION_CALL_CONTROL);
                dismissFilter2.addAction(ACTION_DISMISS);
                dismissFilter2.addAction(ACTION_MUSIC_CONTROL);
                registerReceiver(callControlReceiver, dismissFilter2);
            } catch (Throwable ignored) {
            }
        }
        // v260: the APP's feature switches are real-time, and this
        // service runs in its own process — XposedEntry's copy of
        // FeatureFlags is a different instance, so this process
        // needs its own toggle receiver plus the saved initial state.
        // Without this, the cancel below would eat notifications
        // even when the master switch is off (bubble never pops,
        // notification gone from the shade too).
        FeatureFlags.load(this);
        try {
            final android.content.BroadcastReceiver toggleReceiver =
                    new android.content.BroadcastReceiver() {
                        @Override
                        public void onReceive(android.content.Context c,
                                android.content.Intent intent) {
                            if (intent == null) {
                                return;
                            }
                            String k = intent.getStringExtra("key");
                            boolean e = intent.getBooleanExtra(
                                    "enabled", true);
                            FeatureFlags.set(k, e);
                        }
                    };
            java.lang.reflect.Method m2 = android.content.Context.class
                    .getMethod("registerReceiver",
                            android.content.BroadcastReceiver.class,
                            android.content.IntentFilter.class,
                            int.class);
            android.content.IntentFilter tf =
                    new android.content.IntentFilter(
                            ACTION_FEATURE_TOGGLE);
            m2.invoke(this, toggleReceiver, tf, 2);
        } catch (Throwable t) {
            try {
                android.content.BroadcastReceiver toggleReceiver2 =
                        new android.content.BroadcastReceiver() {
                            @Override
                            public void onReceive(
                                    android.content.Context c,
                                    android.content.Intent intent) {
                                if (intent == null) {
                                    return;
                                }
                                String k = intent.getStringExtra("key");
                                boolean e = intent.getBooleanExtra(
                                        "enabled", true);
                                FeatureFlags.set(k, e);
                            }
                        };
                registerReceiver(toggleReceiver2,
                        new android.content.IntentFilter(
                                ACTION_FEATURE_TOGGLE));
            } catch (Throwable ignored) {
            }
        }
        // Pre-fill the dedup cache with the currently active
        // notifications (key + content), so a service reconnect
        // re-delivery does not re-pop everything (1.x fixed9).
        try {
            android.service.notification.StatusBarNotification[] actives =
                    getActiveNotifications();
            if (actives != null) {
                for (android.service.notification.StatusBarNotification sbn : actives) {
                    if (sbn == null || sbn.getKey() == null
                            || sbn.getNotification() == null) {
                        continue;
                    }
                    android.os.Bundle ex =
                            sbn.getNotification().extras;
                    String t = ex != null ? ex.getString(
                            android.app.Notification.EXTRA_TITLE) : null;
                    CharSequence cs = ex != null ? ex.getCharSequence(
                            android.app.Notification.EXTRA_TEXT) : null;
                    int seedProgress = ex != null ? ex.getInt(
                            android.app.Notification.EXTRA_PROGRESS, 0) : 0;
                    int seedProgressMax = ex != null ? ex.getInt(
                            android.app.Notification.EXTRA_PROGRESS_MAX,
                            0) : 0;
                    recordNotif(sbn.getKey(), t,
                            cs != null ? cs.toString() : null,
                            sbn.getPackageName(), sbn.getPostTime(),
                            seedProgress, seedProgressMax);
                }
            }
        } catch (Throwable ignored) {
        }
        // Listen for pin/unpin from SystemUI (BubbleWindow.togglePin):
        // start the lyric listener when a music capsule is pinned,
        // stop it when unpinned.
        try {
            android.content.IntentFilter filter =
                    new android.content.IntentFilter(ACTION_LYRIC_LISTEN);
            android.content.BroadcastReceiver receiver =
                    new android.content.BroadcastReceiver() {
                        @Override
                        public void onReceive(android.content.Context context,
                                android.content.Intent intent) {
                            if (intent == null) {
                                return;
                            }
                            boolean listen = intent.getBooleanExtra(
                                    "listen", false);
                            String key = intent.getStringExtra("key");
                            if (listen) {
                                musicListening = true;
                                if (key != null) {
                                    musicKey = key;
                                }
                                setupMusicListener();
                            } else {
                                musicListening = false;
                                teardownMusicListener();
                            }
                        }
                    };
            // The broadcast comes from SystemUI (another process), so
            // the receiver must be exported — same as XposedEntry:
            // hidden 3-arg registerReceiver with RECEIVER_EXPORTED
            // (flag 2), falling back to the public API. A plain
            // registerReceiver never receives a cross-app broadcast
            // on Android 13+, so the lyric listener never started.
            try {
                java.lang.reflect.Method m = android.content.Context.class
                        .getMethod("registerReceiver",
                                android.content.BroadcastReceiver.class,
                                android.content.IntentFilter.class,
                                int.class);
                m.invoke(this, receiver, filter, 2);
            } catch (Throwable t) {
                try {
                    registerReceiver(receiver, filter);
                } catch (Throwable ignored) {
                }
            }
        } catch (Throwable ignored) {
        }
    }

    /** v389: recognise a phone number vs a contact name (user
     * 2026-11-22 "识别名称和号码"). The incallui notification
     * posts TWICE for one call: first title = the raw number
     * ("18883204388", text empty, titleOverlay = "188 8320
     * 4388"), then 16ms later title = the name ("我的", text =
     * "188 8320 4388"). The old code took EXTRA_TITLE as the name
     * and EXTRA_TEXT as the number blindly — no judgement at all —
     * so the first post showed the raw unspaced number as the
     * "name" and an empty second line, and the key dedup dropped
     * the second post, so the name never arrived.
     * A string is a phone number when, after stripping spaces,
     * dashes and a leading +, it is all digits and 7+ digits
     * long. Anything else non-empty is a name. */
    private static boolean isPhoneNumber(String s) {
        if (s == null) {
            return false;
        }
        String d = s.trim().replace(" ", "").replace("-", "");
        if (d.startsWith("+")) {
            d = d.substring(1);
        }
        if (d.length() < 7) {
            return false;
        }
        for (int i = 0; i < d.length(); i++) {
            if (!Character.isDigit(d.charAt(i))) {
                return false;
            }
        }
        return true;
    }

    /** Digits only, for comparing numbers ("18883204388" ==
     * "188 8320 4388"). */
    private static String digitsOnly(String s) {
        if (s == null) {
            return null;
        }
        String d = s.trim().replace(" ", "").replace("-", "");
        if (d.startsWith("+")) {
            d = d.substring(1);
        }
        return d;
    }

    /** Display form: an 11-digit mobile with no spaces becomes
     * "188 8320 4388" (3-4-4). Anything else is returned trimmed. */
    private static String formatNumber(String s) {
        if (s == null) {
            return null;
        }
        String t = s.trim();
        if (t.isEmpty()) {
            return t;
        }
        String d = digitsOnly(t);
        if (d != null && d.length() == 11 && !t.contains(" ")) {
            return d.substring(0, 3) + " " + d.substring(3, 7)
                    + " " + d.substring(7);
        }
        return t;
    }

    /** 1.x isCallNotification: category call, full-screen intent
     * from a dialer/phone/telecom/incallui package, or a title that
     * looks like a phone number (7+ digits) or carries a call word. */
    private boolean isCallNotification(
            android.service.notification.StatusBarNotification sbn,
            android.app.Notification n) {
        if (n == null) {
            return false;
        }
        // v134: the incallui call notification itself — the ongoing
        // one after answering has no fullScreenIntent (used up at
        // RINGING) and may not carry category "call", so it stopped
        // matching here and the re-post never reached the call
        // branch at all: no key update, no speaker extraction, and
        // SystemUI never got a speaker button ("无扬声器按钮").
        // The diagnostic build matches incallui by package alone.
        if (sbn != null
                && "com.android.incallui".equals(sbn.getPackageName())) {
            return true;
        }
        if ("call".equals(n.category)) {
            return true;
        }
        if (n.fullScreenIntent != null) {
            String pkg = sbn != null ? sbn.getPackageName() : null;
            if (pkg != null) {
                String lower = pkg.toLowerCase();
                if (lower.contains("dialer") || lower.contains("phone")
                        || lower.contains("telecom")
                        || lower.contains("incallui")
                        || lower.contains("call")) {
                    return true;
                }
            }
            try {
                android.os.Bundle b = n.extras;
                String title = b != null ? b.getString(
                        android.app.Notification.EXTRA_TITLE) : null;
                if (title != null) {
                    String digits = title.replaceAll("[^0-9]", "");
                    if (digits.length() >= 7) {
                        return true;
                    }
                    if (title.contains("来电") || title.contains("呼入")
                            || title.contains("正在呼叫")) {
                        return true;
                    }
                }
            } catch (Throwable ignored) {
            }
        }
        return false;
    }

    // v125 (Flyme Island, MusicListenerService): fire the
    // incallui notification's own action by matching its title
    // (answer / reject / hangup / speaker on / speaker off) and
    // confirming the PendingIntent was created by incallui.
    // The system then answers / routes the audio itself, which
    // is why Flyme Island's speaker works while our direct
    // TelecomManager + setSpeakerphoneOn calls do not.
    // v129 (Flyme Island performCallAction): match the
    // notification by its KEY when the sender supplies one —
    // the key identifies THIS call. Fall back to package-only
    // when no key is given.
    private boolean fireCallAction(String command, String key) {
        // v135: two passes. First try the given key; if no incallui
        // notification matches it (the key went stale when the
        // notification refreshed after answering), fall back to
        // package-only — there is only ever one active call, so the
        // first incallui notification IS the call.
        if (fireCallActionPass(command, key, true)) {
            reportCallResult("切换: 已点扬声器");
            return true;
        }
        if (fireCallActionPass(command, key, false)) {
            reportCallResult("切换: 已点扬声器(按包)");
            return true;
        }
        // v137: report what the lookup actually saw, so we can tell
        // "no call notification" from "notification has no speaker".
        reportCallResult(describeCallActions());
        return false;
    }

    private void reportCallResult(String text) {
        try {
            android.content.Intent i =
                    new android.content.Intent(ACTION_CALL_RESULT);
            // No setPackage: this goes to SystemUI (XposedEntry),
            // which is a different package. The action name is
            // unique to this module so nobody else listens for it.
            i.putExtra("call_result", text);
            sendBroadcast(i);
        } catch (Throwable ignored) {
        }
    }

    private String describeCallActions() {
        try {
            android.service.notification.StatusBarNotification[] act =
                    getActiveNotifications();
            if (act == null) {
                return "切换查找: 无通知列表";
            }
            for (android.service.notification.StatusBarNotification sbn
                    : act) {
                if (sbn == null
                        || !"com.android.incallui".equals(
                                sbn.getPackageName())) {
                    continue;
                }
                android.app.Notification n = sbn.getNotification();
                if (n == null || n.actions == null) {
                    return "切换查找: 通话通知无按钮";
                }
                StringBuilder sb = new StringBuilder("切换查找:");
                for (android.app.Notification.Action a : n.actions) {
                    if (a != null && a.title != null) {
                        sb.append(" [").append(a.title.toString())
                                .append("]");
                    }
                }
                sb.append(" 无扬声器");
                return sb.toString();
            }
            return "切换查找: 无通话通知";
        } catch (Throwable t) {
            return "切换查找失败: " + t;
        }
    }

    private boolean fireCallActionPass(String command, String key,
            boolean matchKey) {
        try {
            android.service.notification.StatusBarNotification[] act =
                    getActiveNotifications();
            if (act == null) {
                return false;
            }
            int want = classifyCallAction(command);
            if (want < 0) {
                return false;
            }
            for (android.service.notification.StatusBarNotification sbn
                    : act) {
                if (sbn == null
                        || !"com.android.incallui".equals(
                                sbn.getPackageName())) {
                    continue;
                }
                if (matchKey && key != null && !key.isEmpty()
                        && !key.equals(sbn.getKey())) {
                    continue;
                }
                android.app.Notification n = sbn.getNotification();
                if (n == null || n.actions == null) {
                    continue;
                }
                for (android.app.Notification.Action a : n.actions) {
                    if (a == null || a.actionIntent == null
                            || a.title == null) {
                        continue;
                    }
                    int kind = classifyCallAction(a.title.toString());
                    // v138: the notification carries ONE speaker
                    // action (a toggle, "打开扬声器"), not separate
                    // on/off actions. If the UI's speaker flag is out
                    // of sync the tap sends speaker_off (want 4)
                    // while only kind 3 exists, so nothing matches.
                    // For a speaker command, fire whichever speaker
                    // action exists — it toggles the speaker.
                    boolean speakerCmd = want == 3 || want == 4;
                    boolean speakerAct = kind == 3 || kind == 4;
                    if (speakerCmd ? !speakerAct : kind != want) {
                        continue;
                    }
                    try {
                        if (!"com.android.incallui".equals(
                                a.actionIntent.getCreatorPackage())) {
                            continue;
                        }
                    } catch (Throwable ignored) {
                        continue;
                    }
                    a.actionIntent.send();
                    return true;
                }
            }
        } catch (Throwable ignored) {
        }
        return false;
    }

    // v125 (Flyme Island CallActionPolicy.classify, same titles):
    // 0 answer, 1 reject, 2 hangup, 3 speaker on, 4 speaker off,
    // 5 silence.
    private int classifyCallAction(String title) {
        if (title == null) {
            return -1;
        }
        String t = title.trim().toLowerCase(java.util.Locale.ROOT);
        if (t.equals("接听") || t.equals("接聽")
                || t.equals("answer") || t.equals("accept")) {
            return 0;
        }
        if (t.equals("拒接") || t.equals("拒绝") || t.equals("拒絕")
                || t.equals("decline") || t.equals("reject")) {
            return 1;
        }
        if (t.equals("挂断") || t.equals("掛斷")
                || t.equals("hang up")) {
            return 2;
        }
        // v130: the system button shows an icon, not text — the
        // real title is often just "扬声器" / "speaker" / "免提"
        // (a single toggle), not "打开扬声器". Match by CONTAINS.
        if (t.contains("扬声器") || t.contains("揚聲器")
                || t.contains("speaker") || t.contains("免提")) {
            if (t.contains("关") || t.contains("關")
                    || t.contains("off")) {
                return 4;
            }
            return 3;
        }
        if (t.equals("静音") || t.equals("靜音")
                || t.equals("silence")) {
            return 5;
        }
        return -1;
    }

    private final android.content.BroadcastReceiver callControlReceiver =
            new android.content.BroadcastReceiver() {
                @Override
                public void onReceive(android.content.Context context,
                        android.content.Intent intent) {
                    if (intent == null) {
                        return;
                    }
                    // v171: music card control from SystemUI.
                    if (ACTION_MUSIC_CONTROL.equals(intent.getAction())) {
                        String mcmd = intent.getStringExtra("command");
                        long seek = intent.getLongExtra(
                                "seek_position", -1);
                        handleMusicControl(mcmd, seek);
                        return;
                    }
                    // v143: dismiss a notification after the card
                    // opened its app. v490: use cancelByKey (all
                    // three cancel attempts) — the plain
                    // cancelNotification(key) alone does not remove
                    // delegated push notifications (v488 proved it:
                    // HookCancel sent dismiss, CancelDiag still
                    // stillActive=true 0.5s later).
                    if (ACTION_DISMISS.equals(intent.getAction())) {
                        String dkey = intent.getStringExtra("key");
                        if (dkey != null) {
                            cancelByKey(dkey,
                                    intent.getStringExtra("postPkg"));
                        }
                        return;
                    }
                    String cmd = intent.getStringExtra("command");
                    if (cmd == null) {
                        return;
                    }
                    String key = intent.getStringExtra("call_key");
                    fireCallAction(cmd, key);
                }
            };

    // v130: scan a call notification's actions for the speaker
    // button(s) and keep their PendingIntents.
    private void extractCallSpeakerIntents(
            android.app.Notification n) {
        callSpeakerOnIntent = null;
        callSpeakerOffIntent = null;
        if (n == null || n.actions == null) {
            return;
        }
        for (android.app.Notification.Action a : n.actions) {
            if (a == null || a.actionIntent == null
                    || a.title == null) {
                continue;
            }
            int kind = classifyCallAction(a.title.toString());
            if (kind == 3) {
                callSpeakerOnIntent = a.actionIntent;
            } else if (kind == 4) {
                callSpeakerOffIntent = a.actionIntent;
            }
        }
    }

    private void sendCallBroadcast(String state, String name,
            String number) {
        try {
            Intent intent = new Intent(ACTION_CALL);
            intent.putExtra("call_state", state);
            intent.putExtra("call_name", name);
            intent.putExtra("call_number", number);
            // v129: the REAL system notification key, so the
            // SystemUI side can tell the module which
            // notification's action to fire (Flyme Island passes
            // its island_source_key the same way).
            if (callKey != null) {
                intent.putExtra("call_key", callKey);
            }
            // v130: hand the speaker buttons to SystemUI so it
            // can fire them directly at tap time.
            if (callSpeakerOnIntent != null) {
                intent.putExtra("speaker_on_intent",
                        callSpeakerOnIntent);
            }
            if (callSpeakerOffIntent != null) {
                intent.putExtra("speaker_off_intent",
                        callSpeakerOffIntent);
            }
            sendBroadcast(intent);
        } catch (Throwable ignored) {
        }
    }

    @Override
    public void onNotificationPosted(StatusBarNotification sbn) {
        if (sbn == null || sbn.getNotification() == null) {
            return;
        }
        try {
            android.app.Notification n = sbn.getNotification();
            // 1.x fixed33: one log line per posted notification, so a
            // double delivery shows up as two Posted lines for the same
            // key. FileLog writes to /data/local/tmp/BubbleNotify.log
            // (and /sdcard/BubbleNotify.log) — export it after a double.
            FileLog.i("Posted pkg=" + sbn.getPackageName() + " key=" + sbn.getKey()
                    + " flags=" + n.flags);
            // Incoming call (v103): handle it on its own path, not as
            // a regular notification. Cancel the system banner so only
            // the call bubble shows (1.x handleCallNotification).
            if (isCallNotification(sbn, n)) {
                // v112/v113: the system re-posts the call
                // notification when it is answered (incoming ->
                // ongoing). Without this guard the same call sent
                // RINGING again, and BubbleWindow.onCall("ringing")
                // wiped the answered state (speaker choice, timer)
                // — the tap answered via earpiece and the button
                // stayed a ringing handset. v112 deduped by
                // notification key, but the ongoing notification
                // can carry a different key, so v113 dedups by the
                // phone number too: same caller + a call already up
                // = the same call updating, skip it. A different
                // number still rings (call waiting).
                android.os.Bundle cb = n.extras;
                String rawTitle = cb != null ? cb.getString(
                        android.app.Notification.EXTRA_TITLE) : null;
                String rawText = cb != null ? cb.getString(
                        android.app.Notification.EXTRA_TEXT) : null;
                // v389: recognise name vs number (user 2026-11-22
                // "识别名称和号码"). Flyme's display strings live
                // in notification.live.titleOverlay /
                // contentOverlay — the formatted number with spaces
                // ("188 8320 4388"), which EXTRA_TITLE/EXTRA_TEXT
                // do not always carry (first post: title = raw
                // "18883204388", text empty).
                String titleOverlay = cb != null ? cb.getString(
                        "notification.live.titleOverlay") : null;
                String contentOverlay = cb != null ? cb.getString(
                        "notification.live.contentOverlay") : null;
                // Number: first phone-number-like string, preferring
                // one that already has spaces (formatted). Name:
                // first non-empty string that is NOT a number.
                String number = null;
                if (isPhoneNumber(titleOverlay)) {
                    number = formatNumber(titleOverlay);
                } else if (isPhoneNumber(rawText)) {
                    number = formatNumber(rawText);
                } else if (isPhoneNumber(contentOverlay)) {
                    number = formatNumber(contentOverlay);
                } else if (isPhoneNumber(rawTitle)) {
                    number = formatNumber(rawTitle);
                }
                String name = null;
                if (rawTitle != null && !rawTitle.trim().isEmpty()
                        && !isPhoneNumber(rawTitle)) {
                    name = rawTitle.trim();
                } else if (titleOverlay != null
                        && !titleOverlay.trim().isEmpty()
                        && !isPhoneNumber(titleOverlay)) {
                    name = titleOverlay.trim();
                }
                if (callKey != null
                        && callKey.equals(sbn.getKey())) {
                    // v389: same key, but the second post may be the
                    // name resolving (first post had only the number
                    // as the title). If we stored no real name yet
                    // and this post brings one, update the capsule
                    // instead of dropping it. Still RINGING (not
                    // offhook), so re-sending RINGING only refreshes
                    // the capsule — BubbleWindow drops it once
                    // offhook.
                    boolean storedHasName = callName != null
                            && !callName.trim().isEmpty()
                            && !isPhoneNumber(callName);
                    if (name != null && !storedHasName) {
                        callName = name;
                        if (number != null) {
                            callNumber = digitsOnly(number);
                        }
                        extractCallSpeakerIntents(n);
                        sendCallBroadcast(CALL_RINGING, name, number);
                    }
                    return;
                }
                // v132: match by name too — after answering, the
                // re-posted notification's EXTRA_TEXT is the call
                // duration, not the number, so the number check
                // misses it and the re-post falls through to the
                // "new call" branch, re-sending RINGING. The caller
                // name (EXTRA_TITLE) does not change.
                // v135: but only when a call is actually OFFHOOK. A
                // stale callNumber/callName (v131 kept them when the
                // removal raced the telephony state) makes the NEXT
                // call from the same number look like a re-post, so
                // no RINGING is sent and no bubble pops. A re-post
                // only happens after answering (OFFHOOK); a new
                // incoming call arrives while RINGING.
                // v389: callNumber is stored digits-only, so the
                // formatted display number ("188 8320 4388") matches
                // the raw one ("18883204388") in this comparison.
                boolean sameNumber = callNumber != null
                        && number != null
                        && callNumber.equals(digitsOnly(number));
                boolean sameName = callName != null && name != null
                        && callName.equals(name);
                boolean callOffhook = false;
                try {
                    android.telephony.TelephonyManager tm =
                            (android.telephony.TelephonyManager)
                            getSystemService(
                                    android.content.Context
                                            .TELEPHONY_SERVICE);
                    callOffhook = tm != null
                            && tm.getCallState()
                            == android.telephony.TelephonyManager
                                    .CALL_STATE_OFFHOOK;
                } catch (Throwable ignored) {
                }
                if ((sameNumber || sameName) && callOffhook) {
                    // v129: same call, re-posted after answering
                    // (incoming -> ongoing). The ongoing
                    // notification can carry a DIFFERENT key, and
                    // its actions are the ones that matter now
                    // (speaker on/off). Update the stored key and
                    // tell SystemUI, so the speaker toggle fires
                    // the refreshed notification's action — but do
                    // NOT re-send RINGING (that would wipe the
                    // answered state, v112/v113).
                    if (sbn.getKey() != null
                            && !sbn.getKey().equals(callKey)) {
                        callKey = sbn.getKey();
                        extractCallSpeakerIntents(n);
                        sendCallBroadcast(CALL_OFFHOOK, name, number);
                    }
                    return;
                }
                callKey = sbn.getKey();
                // v389: store digits-only for the dedup above; the
                // broadcast still carries the formatted number for
                // display.
                callNumber = digitsOnly(number);
                callName = name;
                extractCallSpeakerIntents(n);
                sendCallBroadcast(CALL_RINGING, name, number);
                // v125 (Flyme Island): do NOT cancel the call
                // notification. Its actions (answer / speaker /
                // hangup) must stay live so they can be fired via
                // their own PendingIntents. The banner is suppressed
                // by CallBannerSuppress and the full-screen UI by
                // InCallUiHide — the cancel is no longer needed.
                return;
            }
            // Music is detected by a MediaSession token in the
            // notification extras, OR by CATEGORY_TRANSPORT (1.x
            // fixed175: handleMediaNotification is entered when the
            // token is present, regardless of category). Some players
            // post a media notification with a token but without the
            // TRANSPORT category — 2.0 only checked the category, so
            // they were never recognised as music and the pinned
            // capsule only ever showed the song name. A music
            // player's notification is an ongoing foreground-service
            // notification, and the user wants it as a bubble (user
            // 2026-11-19 "下面开始音乐播放器通知").
            android.media.session.MediaSession.Token mediaToken = null;
            Bundle exForToken = n.extras;
            if (exForToken != null) {
                try {
                    android.os.Parcelable tok = exForToken.getParcelable(
                            android.app.Notification.EXTRA_MEDIA_SESSION);
                    if (tok instanceof android.media.session.MediaSession.Token) {
                        mediaToken = (android.media.session.MediaSession.Token) tok;
                    }
                } catch (Throwable ignored) {
                }
            }
            boolean isMusic = !isSongRecognition(n)
                    && (android.app.Notification.CATEGORY_TRANSPORT
                            .equals(n.category) || mediaToken != null
                            || isKnownMusicApp(sbn.getPackageName(), n));
            // Skip ongoing / foreground-service notifications unless
            // they carry download progress (or are music).
            boolean ongoing = (n.flags & android.app.Notification.FLAG_ONGOING_EVENT) != 0
                    || (n.flags & android.app.Notification.FLAG_FOREGROUND_SERVICE) != 0;
            Bundle extras = n.extras;
            // 1.x fixed133/136: progress from the extras, refined by the
            // live RemoteViews ProgressBar (highest percent wins). A
            // notification only "has progress" when the max is real
            // (> 0) or it is indeterminate — mere key presence (stale
            // 0/0 extras from chat apps) does NOT count. 2.0's old
            // containsKey check wrongly exempted those from the stale
            // check, the flags mask and the fingerprint dedup.
            int notifProgress = extras != null ? extras.getInt(
                    "android.progress", 0) : 0;
            int notifProgressMax = extras != null ? extras.getInt(
                    "android.progressMax", 0) : 0;
            boolean notifProgressIndeterminate = extras != null
                    && extras.getBoolean("android.progressIndeterminate", false);
            int[] rvProgress = readProgressFromRemoteViews(n);
            if (rvProgress != null && rvProgress[1] > 0) {
                int rvPct = (rvProgress[0] * 100) / rvProgress[1];
                int exPct = notifProgressMax > 0
                        ? (notifProgress * 100) / notifProgressMax : -1;
                if (rvPct > exPct) {
                    notifProgress = rvProgress[0];
                    notifProgressMax = rvProgress[1];
                }
            }
            boolean hasProgress = notifProgressMax > 0
                    || notifProgressIndeterminate;
            if (ongoing && !hasProgress && !isMusic) {
                return;
            }
            // 1.x verbatim (fixed33): title/text with the full fallback
            // chain — some apps keep content outside android.text
            // (WeChat MessagingStyle -> android.messages / textLines,
            // Meituan / custom content -> bigText / RemoteViews). Without
            // these, the dedup digest is built from an empty text and a
            // re-delivery whose text moved fields pops again.
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
            // Music: song / singer / lyrics. Prefer the MediaSession
            // metadata (definitive title + artist); fall back to the
            // notification's own title / text. Lyrics come from the
            // notification (sub-text / ticker / big text — 1.x read
            // lyrics from ticker -> text -> bigText -> subtitle);
            // when no lyric is available, the song name is used.
            // Music: resolve song / singer / lyrics with the 1.x
            // logic (resolveMusicLyric — swapped layout + validated
            // pick). The notification title is the stable song anchor
            // (1.x fixed18); the MediaSession TITLE may itself be a
            // lyric line.
            // Download: a regular notification carrying progress.
            // Append the percent to line 2 (1.x fixed133) and start
            // the 1s live tick so the capsule updates in real time.
            int downloadPct = -1;
            boolean isDownloadType = false;
            if (!isMusic) {
                downloadPct = readDownloadPct(n, title, text);
                // Dual condition (v110): real progress AND a download
                // signal. v102's pure-progress check mis-detected chat
                // messages — a WeChat job post was treated as a
                // download (user 2026-11-19 "微信这种消息他都识别成
                // 下载"), because chat apps can carry stale progress
                // extras and hasProgress is mere key presence. The
                // signal (下载 in text, a download channel, or a known
                // store/browser/downloader) plus the upload/backup/
                // sync/install exclusion keeps those out.
                isDownloadType = downloadPct >= 0
                        && hasDownloadSignal(sbn.getPackageName(),
                                n.getChannelId(), title, text);
                if (isDownloadType) {
                    // v240: bytes / speed / ETA from the text
                    // fields first (Coolapk infoText, browser
                    // text+subText, TapTap text), extras byte
                    // counts as the fallback.
                    String dlInfo = extras != null
                            ? getText(extras, "android.infoText")
                            : null;
                    String dlSub = extras != null
                            ? getText(extras, "android.subText")
                            : null;
                    long[] dlParsed = parseDownloadedTotal(
                            text, dlInfo);
                    long[] dlBytes = dlParsed != null
                            ? dlParsed : readDownloadBytes(n);
                    long dlB = dlBytes != null ? dlBytes[0] : -1;
                    long dlT = dlBytes != null ? dlBytes[1] : -1;
                    long dlSpeed = parseSpeedFromText(text);
                    long dlEta = parseEtaSeconds(text, dlSub);
                    lastDownloadBytes = dlB;
                    lastDownloadBytesTime =
                            android.os.SystemClock.uptimeMillis();
                    String[] lines = normalizeDownload(
                            title, text, downloadPct, dlB, dlT,
                            dlSpeed, dlEta);
                    title = lines[0];
                    text = lines[1];
                    downloadKey = sbn.getKey();
                    lastDownloadPct = downloadPct;
                    lastDownloadSpeed = -1;
                    lastDownloadEta = -1;
                    startDownloadTick();
                } else if (downloadPct < 0
                        && hasDownloadSignal(sbn.getPackageName(),
                                n.getChannelId(), title, text)
                        && isDownloadComplete(title, text)) {
                    // v319: small file that finished before any
                    // progress-bearing post was seen — the hook may
                    // already have popped its capsule, but the tick
                    // never started (isDownloadType needs pct >= 0),
                    // so nothing would ever remove it. Track it so
                    // the tick's completed/gone path drops it.
                    downloadKey = sbn.getKey();
                    lastDownloadPct = 100;
                    lastDownloadSpeed = -1;
                    lastDownloadEta = -1;
                    startDownloadTick();
                }
            }
            String lyrics = null;
            if (isMusic && extras != null) {
                String prevMusicKey = musicKey;
                String rawTitle = null;
                String subtitle = null;
                String artistMeta = null;
                String albumArtist = null;
                try {
                    // Token was already read above (isMusic check);
                    // reuse it here.
                    android.media.session.MediaSession.Token tok = mediaToken;
                    if (tok != null) {
                        // v199: if the session token changed, the user
                        // switched music apps. The old controller is
                        // bound to the old app's session (e.g. Flyme
                        // QQ Music), so progress / playback state /
                        // lyrics would all keep coming from the old
                        // app while the card shows the new app's song
                        // name (the name comes from the notification,
                        // the rest from the controller). Drop the old
                        // controller (and its callback) so
                        // ensureMusicController below builds one for
                        // the new token.
                        if (musicToken != null && !musicToken.equals(tok)) {
                            if (musicController != null) {
                                if (musicCallback != null) {
                                    try {
                                        musicController.unregisterCallback(musicCallback);
                                    } catch (Throwable ignored) {
                                    }
                                }
                                musicController = null;
                            }
                            musicCallback = null;
                            lastLyricSent = null;
                        }
                        // Remember the session so the lyric listener
                        // (v76) can attach when the capsule is pinned.
                        // If already listening, re-attach to the new
                        // token (a song change may bring a new one).
                        musicToken = tok;
                        musicKey = sbn.getKey();
                        // v172: a session attached — make sure the
                        // controller + progress tick are running even
                        // when nothing is pinned, so the open card
                        // refreshes.
                        ensureMusicController();
                        if (musicListening) {
                            setupMusicListener();
                        }
                        android.media.session.MediaController mc =
                                new android.media.session.MediaController(
                                        this,
                                        (android.media.session.MediaSession.Token) tok);
                        android.media.MediaMetadata md = mc.getMetadata();
                        if (md != null) {
                            rawTitle = md.getString(
                                    android.media.MediaMetadata.METADATA_KEY_TITLE);
                            artistMeta = md.getString(
                                    android.media.MediaMetadata.METADATA_KEY_ARTIST);
                            subtitle = md.getString(
                                    android.media.MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE);
                            albumArtist = md.getString(
                                    android.media.MediaMetadata.METADATA_KEY_ALBUM_ARTIST);
                        }
                    }
                } catch (Throwable ignored) {
                }
                // Store this notification's lyric candidates into
                // the persistent slots (1.x updateLyricCandidates).
                // Same key as the PREVIOUS music notification = same
                // song: only fill slots that have a value, so a
                // lyric-less re-post does not wipe the lyric we
                // already have. (musicKey was just overwritten with
                // this key above, so compare against prevMusicKey.)
                boolean sameSong = prevMusicKey != null
                        && prevMusicKey.equals(sbn.getKey());
                updateLyricCandidates(n, sameSong);
                // v196: fix the song/artist BEFORE resolving the
                // lyric (user 2026-11-20 "所有歌词的地方全部放上了
                // 歌名，歌名的地方是空白的"). QQ Music (Flyme build
                // and the original) posts a player notification
                // whose EXTRA_TITLE is the app name ("QQ音乐") and
                // EXTRA_TEXT is a status message ("当前列表为空，
                // 点击选择歌曲播放") — the song name never reaches
                // the title slot, and the lyric resolver then
                // accepts the song name itself as a lyric
                // (checkTitle is null when there is no MediaSession
                // TITLE), so every lyric slot shows the song name
                // and the title slot is blank. Fix the title first,
                // in this order: (a) parse "Artist - Song" from
                // ticker/bigText/text (v175, Flyme build); (b)
                // MediaSession metadata TITLE/ARTIST (the original
                // QQ Music carries them; the token came from this
                // notification's own extras, and this only runs
                // when the notification itself has no song info,
                // so it cannot overwrite a real title); (c) the
                // bare ticker/bigText/text as the song name when
                // it is short and not a status message or a
                // credits line (the original QQ Music ticker is
                // just the song name, no dash). Once the title is
                // the real song name, the lyric resolver below
                // rejects it as a lyric and the real lyric is
                // found.
                if (title != null && !title.isEmpty()) {
                    String parsedSong = null;
                    String parsedArtist = null;
                    try {
                        CharSequence label = getPackageManager()
                                .getApplicationLabel(
                                        getPackageManager()
                                                .getApplicationInfo(
                                                        sbn.getPackageName(),
                                                        0));
                        if (label != null
                                && label.toString().equals(title)) {
                            String candidate = currentLyricTicker;
                            if (candidate == null
                                    || candidate.isEmpty()) {
                                candidate = currentLyricBigText;
                            }
                            if (candidate == null
                                    || candidate.isEmpty()) {
                                candidate = currentLyricText;
                            }
                            String[] parsed =
                                    parseArtistSong(candidate);
                            if (parsed != null) {
                                parsedArtist = parsed[0];
                                parsedSong = parsed[1];
                            }
                            if (parsedSong == null
                                    && rawTitle != null
                                    && !rawTitle.isEmpty()
                                    && !isCreditOrLyricTitle(rawTitle)
                                    && !rawTitle.equals(title)) {
                                parsedSong = rawTitle;
                                if (artistMeta != null
                                        && !artistMeta.isEmpty()
                                        && !isCreditOrLyricTitle(
                                                artistMeta)) {
                                    parsedArtist = artistMeta;
                                }
                            }
                            if (parsedSong == null
                                    && candidate != null
                                    && !candidate.isEmpty()
                                    && candidate.length() <= 30
                                    && !isCreditOrLyricTitle(candidate)
                                    && !isStatusMessage(candidate)) {
                                parsedSong = candidate.trim();
                            }
                        }
                    } catch (Throwable ignored) {
                    }
                    if (parsedSong != null) {
                        title = parsedSong;
                        if (parsedArtist != null) {
                            text = parsedArtist;
                        }
                    }
                }
                lyrics = resolveMusicLyric(rawTitle, subtitle, artistMeta,
                        albumArtist, title);
                // Song/artist: the notification's own EXTRA_TITLE /
                // EXTRA_TEXT are app-specific and authoritative. NEVER
                // overwrite them with MediaSession metadata — both
                // apps here use the SYSTEM player (one shared
                // MediaSession), so the session's metadata is
                // whichever app last wrote to it (here Flyme QQ
                // Music's song), and the NetEase capsule ended up
                // showing Flyme QQ Music's song/artist. The MediaSession
                // is used only for lyric resolution above, never for
                // song/artist. (Swapped-layout parse still runs inside
                // resolveMusicLyric for the lyric; it no longer
                // rewrites title/text.)
                // Only fill in a missing artist from the metadata.
                if ((text == null || text.isEmpty())
                        && artistMeta != null && !artistMeta.isEmpty()
                        && !isCreditOrLyricTitle(artistMeta)) {
                    text = artistMeta;
                }
                // v196: the v175 title fix moved BEFORE the
                // lyric resolution above (it must run first, or the
                // lyric resolver accepts the song name as a lyric).
                // v189 (1.0.5 method): NO fallback of the lyric to
                // the song name. 1.0.5 never does this — no lyric
                // means no lyric, and the broadcast simply omits
                // the lyric extra. Falling back to the title is
                // why a pinned capsule showed the song name where
                // the lyric belongs (user 2026-11-20 "钉住应该读
                // 歌词它读的歌名").
            }
            if ((title == null || title.isEmpty())
                    && (text == null || text.isEmpty())) {
                // v375: an empty notification is still a visible
                // card — cancel it so it does not leak onto the
                // lock screen / shade.
                if (!isMusic && !isDownloadType && FeatureFlags.notif) {
                    try {
                        cancelNotif(sbn);
                    } catch (Throwable ignored) {
                    }
                }
                return;
            }
            // Full 1.x dedup (v105, user "完整套用"), in 1.x order:
            // absolute stale -> flags mask -> per-package fingerprint
            // -> key (content-aware, progress-suffixed) -> redundant
            // message (lifecycle) -> duplicate (pkg fp + content fp +
            // summary/aggregated, skipped for progress) -> group
            // summary. Any hit drops the notification outright (it
            // never reaches the bubble).
            String pkg = sbn.getPackageName();
            // notifProgress/notifProgressMax were read (and refined by
            // RemoteViews) above for hasProgress; reuse them here.
            if (downloadPct >= 0 && notifProgressMax <= 0) {
                notifProgress = downloadPct;
                notifProgressMax = 100;
            }
            // 1.x fixed136: refine progress from the text ("45%",
            // "12MB/50MB") — the text often carries the live percent
            // while the extras stay at 0. Highest wins.
            int textPct = parseProgressPercent(text);
            if (textPct < 0) {
                textPct = parseProgressPercent(title);
            }
            if (textPct >= 0) {
                int curPct = notifProgressMax > 0
                        ? (notifProgress * 100) / notifProgressMax : -1;
                if (textPct > curPct) {
                    if (notifProgressMax > 0) {
                        notifProgress = (textPct * notifProgressMax) / 100;
                    } else {
                        notifProgressMax = 100;
                        notifProgress = textPct;
                    }
                    hasProgress = true;
                }
            }
            // Absolute stale (1.x fixed28, Flyme Island STALE): postTime
            // more than 5s ago means the system re-delivered an old
            // notification (e.g. after the listener reconnected). A
            // download lasts minutes — exempt progress notifications;
            // music is exempt too (1.x returns early for music before
            // this check, and lyric re-posts reuse the old postTime).
            if (!hasProgress && !isMusic && System.currentTimeMillis()
                    - sbn.getPostTime() > STALE_WINDOW_MS) {
                FileLog.i("Stale notification dropped (>5s old): " + pkg);
                // v375: a re-delivered stale notification is a
                // fresh visible card — cancel it (no-op if it is
                // already gone).
                if (!isMusic && !isDownloadType && FeatureFlags.notif) {
                    try {
                        cancelNotif(sbn);
                    } catch (Throwable ignored) {
                    }
                }
                return;
            }
            // Flags mask (1.x fixed28/31/135/137): 578 = 0x242 =
            // ONGOING | FOREGROUND_SERVICE | GROUP_SUMMARY. Ongoing /
            // foreground-service notifications are not user messages,
            // and a group summary is redundant once its children are
            // shown. Delivery orders are exempt from the ongoing bit
            // only (576); progress notifications are exempt from both
            // ongoing bits (512, GROUP_SUMMARY only).
            boolean isDeliveryOrder = PKG_MEITUAN.equals(pkg)
                    || PKG_ELEME.equals(pkg) || PKG_AMAP.equals(pkg);
            int dropMask = hasProgress ? 512
                    : (isDeliveryOrder ? 576 : 578);
            // Music is exempt (1.x returns early for music before the
            // mask — a player notification is ongoing by definition).
            if (!isMusic && (n.flags & dropMask) != 0) {
                FileLog.i("Notification dropped by flags mask " + dropMask + ": " + pkg);
                // v372: a group summary dropped here is still a
                // visible notification (its children popped as
                // bubbles) — cancel it so it does not leak onto the
                // lock screen / shade.
                // v377: same for ONGOING / FOREGROUND_SERVICE.
                // BOSS 直聘 (and similar apps) post a real user
                // message — a job recommendation — with the
                // ongoing / foreground-service flag set, so it was
                // dropped here with no bubble AND no cancel, and
                // the card stayed on the lock screen (user
                // 2026-11-22 "这种还是没有屏蔽到"). A dropped
                // notification is a visible card either way, so
                // cancel it. Music / downloads are exempt (they
                // never reach this branch).
                if (!isDownloadType && FeatureFlags.notif) {
                    try {
                        cancelNotif(sbn);
                    } catch (Throwable ignored) {
                    }
                }
                return;
            }
            // Per-package fingerprint (1.x fixed28): identical
            // id:postTime:title/text:progress means the exact same
            // notification was re-delivered.
            if (isRepeatNotifFingerprint(pkg, sbn, title, text,
                    notifProgress, notifProgressMax)) {
                FileLog.i("Repeat notification fingerprint dropped: " + pkg);
                // v375: a re-delivered notification is a fresh
                // visible card — cancel it (no-op if gone).
                if (!isMusic && !isDownloadType && FeatureFlags.notif) {
                    try {
                        cancelNotif(sbn);
                    } catch (Throwable ignored) {
                    }
                }
                return;
            }
            if (isRepeatNotif(sbn.getKey(), title, text,
                    notifProgress, notifProgressMax)) {
                FileLog.i("Repeat notification key dropped: " + sbn.getKey());
                // v375: same — cancel the re-delivered card.
                if (!isMusic && !isDownloadType && FeatureFlags.notif) {
                    try {
                        cancelNotif(sbn);
                    } catch (Throwable ignored) {
                    }
                }
                return;
            }
            if (isStalePostTime(sbn.getKey(), sbn.getPostTime())) {
                FileLog.i("Stale postTime dropped: " + sbn.getKey());
                // v375: same — cancel the stale card.
                if (!isMusic && !isDownloadType && FeatureFlags.notif) {
                    try {
                        cancelNotif(sbn);
                    } catch (Throwable ignored) {
                    }
                }
                return;
            }
            // Per-key message lifecycle (1.x fixed11/12): a re-post
            // of the same notification, or a summary that only repeats
            // already-seen messages, is redundant no matter which app
            // posted it.
            if (isRedundantMessage(sbn, n, extras, pkg, title, text)) {
                FileLog.i("Redundant message dropped (already seen): " + pkg);
                // v375: a re-posted message is a fresh visible
                // card — cancel it (no-op if gone).
                if (!isMusic && !isDownloadType && FeatureFlags.notif) {
                    try {
                        cancelNotif(sbn);
                    } catch (Throwable ignored) {
                    }
                }
                return;
            }
            // Duplicate (1.x fixed135): skipped for progress
            // notifications — a progress update has the same title/text
            // as the last one, so it would be dropped as a duplicate
            // within its 15s window. isRepeatNotif is already
            // progress-aware and still catches exact duplicates.
            // Smart push-service filter (v144): the push-service
            // copy of a message the app already posted is a duplicate
            // — drop it. A push copy the app never posted (BOSS/鱼泡
            // in the background) is the only copy, so it is kept.
            if (isRedundantPushCopy(pkg, title, text)) {
                FileLog.i("Redundant push copy dropped: " + pkg);
                // v372: the push copy is a DISTINCT notification
                // (different key, posted by the push service) — the
                // real app's copy already popped a bubble and was
                // cancelled, but this copy stays visible unless it
                // is cancelled too. That is the "系统推送" leak.
                if (!isMusic && !isDownloadType && FeatureFlags.notif) {
                    try {
                        cancelNotif(sbn);
                    } catch (Throwable ignored) {
                    }
                }
                return;
            }
            if (!hasProgress && isPkgFingerprintRepeat(pkg, title, text)) {
                FileLog.i("Duplicate notification dropped (pkg fp): " + pkg);
                // v375: a duplicate is a FRESH visible card (new
                // post, same content) — cancel it. This is the
                // test app's "再发一条相同内容" case.
                if (!isMusic && !isDownloadType && FeatureFlags.notif) {
                    try {
                        cancelNotif(sbn);
                    } catch (Throwable ignored) {
                    }
                }
                return;
            }
            if (!hasProgress && isFingerprintRepeat(pkg, title, text)) {
                FileLog.i("Duplicate notification dropped (content fp): " + pkg);
                // v375: same — cancel the duplicate card.
                if (!isMusic && !isDownloadType && FeatureFlags.notif) {
                    try {
                        cancelNotif(sbn);
                    } catch (Throwable ignored) {
                    }
                }
                return;
            }
            if (isRedundantGroupSummary(n, pkg)) {
                FileLog.i("Group summary dropped (child already shown): " + pkg);
                // v372: cancel the summary itself — its children
                // already popped as bubbles, so the summary card
                // must not stay on the lock screen / shade.
                if (!isMusic && !isDownloadType && FeatureFlags.notif) {
                    try {
                        cancelNotif(sbn);
                    } catch (Throwable ignored) {
                    }
                }
                return;
            }
            if (isSummaryOf(pkg, title, text)) {
                FileLog.i("Summary dropped: " + pkg);
                // v372: cancel the summary itself (same leak as the
                // group summary — its content is already shown).
                if (!isMusic && !isDownloadType && FeatureFlags.notif) {
                    try {
                        cancelNotif(sbn);
                    } catch (Throwable ignored) {
                    }
                }
                return;
            }
            if (isAggregatedSummary(pkg, text)) {
                FileLog.i("Aggregated summary dropped: " + pkg);
                // v372: cancel the aggregated summary itself.
                if (!isMusic && !isDownloadType && FeatureFlags.notif) {
                    try {
                        cancelNotif(sbn);
                    } catch (Throwable ignored) {
                    }
                }
                return;
            }
            noteNotifGroup(n, pkg);
            recordNotif(sbn.getKey(), title, text, pkg, sbn.getPostTime(),
                    notifProgress, notifProgressMax);
            // v171 (1.x fixed150 + fixed17): a music re-post is a REAL
            // song change only if the song's own numbers confirm it.
            // QQ Music Flyme re-posts on every lyric line with the
            // same song title — same duration, mid-song position —
            // so it is a lyric refresh, NOT a new song: only the
            // lyric line is sent (ACTION_LYRIC) and nothing pops.
            // A credits/lyric line as the title is never a song.
            if (isMusic) {
                // v195: title/artist change is computed FIRST.
                // v173's same-key check below used to return lyric-
                // only for EVERY same-key re-post, so a real song
                // change (same key, new title) never reached the
                // capsule — it stayed on the first song's name.
                // A same-key re-post is a lyric refresh ONLY when
                // the title/artist did NOT change; a changed title
                // or artist is a new song and falls through.
                boolean titleIsCredit = isCreditOrLyricTitle(title);
                boolean titleIsLyric = title != null
                        && !title.isEmpty()
                        && title.equals(lyrics);
                boolean titleChanged = !titleIsCredit && !titleIsLyric
                        && title != null && !title.isEmpty()
                        && !title.equals(currentMusicTitle);
                boolean artistChanged = text != null && !text.isEmpty()
                        && !text.equals(currentMusicArtist);
                // v173: same notification key re-posted = same song
                // (1.x same key = same song; Flyme Island 0.119:
                // same key + new postTime = re-post, not a pop).
                // This matters for apps with NO MediaSession (QQ
                // Music Flyme), where the duration/position check
                // below has no data and its "no info = new song"
                // fallback misfires on every lyric-line title
                // change. Lyric refresh only — and only when the
                // song itself did not change (v195).
                if (currentMusicNotifKey != null
                        && currentMusicNotifKey.equals(sbn.getKey())
                        && !titleChanged && !artistChanged) {
                    try {
                        Intent li = new Intent(ACTION_LYRIC);
                        li.putExtra("key", sbn.getKey());
                        li.putExtra("lyrics", lyrics);
                        sendBroadcast(li);
                    } catch (Throwable ignored) {
                    }
                    return;
                }
                long sessDur = -1;
                long sessPos = -1;
                try {
                    if (musicController != null) {
                        android.media.MediaMetadata md =
                                musicController.getMetadata();
                        if (md != null) {
                            sessDur = md.getLong(
                                    android.media.MediaMetadata
                                            .METADATA_KEY_DURATION);
                        }
                        android.media.session.PlaybackState st =
                                musicController.getPlaybackState();
                        if (st != null) {
                            sessPos = st.getPosition();
                        }
                    }
                } catch (Throwable ignored) {
                }
                // titleChanged / artistChanged were computed
                // above (v195), before the same-key check.
                boolean songChangeConfirmed;
                if (!titleChanged && !artistChanged) {
                    songChangeConfirmed = false;
                } else if (artistChanged) {
                    // v227: the notification TEXT changed. For
                    // Flyme the text is "song - artist - album"
                    // — it only changes on a real song change,
                    // never on a lyric-line title cycle. The
                    // session may still hold the old song at
                    // broadcast time (previous-tap: old position
                    // / duration), so do not require session
                    // confirmation when the text itself proves
                    // a new song.
                    songChangeConfirmed = true;
                } else if (currentMusicTitle == null
                        || currentMusicTitle.isEmpty()) {
                    songChangeConfirmed = true;
                } else if (sessDur > 0 && currentMusicDurationMs > 0
                        && sessDur != currentMusicDurationMs) {
                    songChangeConfirmed = true;
                } else if (sessPos >= 0 && sessPos < 5000) {
                    songChangeConfirmed = true;
                } else if (sessPos < 0 && sessDur <= 0) {
                    songChangeConfirmed = true;
                } else {
                    songChangeConfirmed = false;
                }
                if (!songChangeConfirmed) {
                    try {
                        Intent li = new Intent(ACTION_LYRIC);
                        li.putExtra("key", sbn.getKey());
                        li.putExtra("lyrics", lyrics);
                        sendBroadcast(li);
                    } catch (Throwable ignored) {
                    }
                    return;
                }
                currentMusicTitle = title;
                currentMusicArtist = text;
                currentMusicNotifKey = sbn.getKey();
                if (sessDur > 0) {
                    currentMusicDurationMs = sessDur;
                }
            }
            // Hand the notification to the bubble window in SystemUI.
            Intent intent = new Intent(ACTION_NOTIFY);
            intent.putExtra("key", sbn.getKey());
            intent.putExtra("pkg", sbn.getPackageName());
            intent.putExtra("title", title);
            intent.putExtra("text", text);
            intent.putExtra("lyrics", lyrics);
            intent.putExtra("postTime", sbn.getPostTime());
            intent.putExtra("isMusic", isMusic);
            intent.putExtra("isDownload", isDownloadType);
            // v239: download percent for the capsule bar.
            if (isDownloadType) {
                intent.putExtra("download_pct", downloadPct);
            }
            // v143: the card's open buttons need the tap action.
            // PendingIntent is Parcelable, so it rides the broadcast
            // like 1.x (rn.intent = notification.contentIntent).
            try {
                android.app.Notification posted =
                        sbn.getNotification();
                if (posted != null && posted.contentIntent != null) {
                    intent.putExtra("content_intent",
                            posted.contentIntent);
                }
            } catch (Throwable ignored) {
            }
            // v171: music card disc — album art, downscaled to 128px
            // (64KB, far under the binder limit) so the card can show
            // the real cover instead of the app icon. Full-size
            // largeIcon is NOT passed (v24: binder transactions).
            if (isMusic) {
                try {
                    android.app.Notification posted =
                            sbn.getNotification();
                    android.graphics.Bitmap art = null;
                    if (posted != null && posted.extras != null) {
                        // v225: unwrap the Icon (see
                        // bitmapFromLargeIcon) — a straight
                        // Bitmap cast throws for every music
                        // app and the broadcast never carried
                        // art.
                        Object li = posted.extras.getParcelable(
                                android.app.Notification
                                        .EXTRA_LARGE_ICON);
                        art = bitmapFromLargeIcon(li);
                    }
                    if (art != null && (art.getWidth() > 128
                            || art.getHeight() > 128)) {
                        art = android.graphics.Bitmap
                                .createScaledBitmap(art, 128, 128,
                                        true);
                    }
                    if (art != null) {
                        intent.putExtra("album_art", art);
                    }
                } catch (Throwable ignored) {
                }
            }
            try {
                sendBroadcast(intent);
            } catch (Throwable ignored) {
            }
            // Cancel so the notification lives only in the bubble.
            // Music is NOT cancelled (1.x): the live lyric refresh
            // re-reads the active notification on every callback, and
            // cancelling it would leave nothing to re-read. Downloads
            // are NOT cancelled either (v95): the 1s tick re-reads the
            // live progress from the active notification.
            boolean isDownload = isDownloadType;
            if (!isMusic && !isDownload && FeatureFlags.notif) {
                try {
                    cancelNotif(sbn);
                } catch (Throwable ignored) {
                }
            }
        } catch (Throwable ignored) {
        }
    }

    @Override
    public void onNotificationRemoved(StatusBarNotification sbn) {
        try {
            // v311: Amap navigation ended — its nav notification
            // (foot 99910002 / ride 99910003) was removed. Tell
            // SystemUI to drop the nav capsule for this type.
            if (sbn != null
                    && "com.autonavi.minimap".equals(sbn.getPackageName())) {
                String bizType = null;
                if (sbn.getId() == 99910002) {
                    bizType = "footnavi";
                } else if (sbn.getId() == 99910003) {
                    bizType = "ridenavi";
                }
                if (bizType != null) {
                    try {
                        android.content.Intent intent =
                                new android.content.Intent(ACTION_NAVI_END);
                        intent.putExtra("bizType", bizType);
                        sendBroadcast(intent);
                    } catch (Throwable ignored) {
                    }
                }
            }
            if (sbn != null && downloadKey != null
                    && downloadKey.equals(sbn.getKey())) {
                // v321: Coolapk removes its download notification
                // the instant the download finishes (never posts a
                // "下载完成" state) — the tick's gone path never got
                // to run because this stopped the tick first, so
                // the capsule was left stuck at the last progress.
                // Tell SystemUI to drop the capsule before stopping
                // the tick. (A download that completed via the
                // "下载完成" update already cleared downloadKey, so
                // this only fires for a download still in progress.)
                sendDownloadRemove(downloadKey);
                stopDownloadTick();
                downloadKey = null;
            }
            if (sbn != null && callKey != null
                    && callKey.equals(sbn.getKey())) {
                // v125: the call notification is no longer
                // self-cancelled, so its removal means the call
                // really ended (system removed it). Send IDLE.
                // v131: BUT the system re-posts the call
                // notification after answering with a DIFFERENT
                // key — it REMOVES the old one first. That removal
                // is not a hangup. If the telephony state still
                // says a call is up, keep the call state and send
                // nothing; the re-post that follows will update
                // the key. Only send IDLE when there is really
                // no call left.
                boolean callStillUp = false;
                try {
                    android.telephony.TelephonyManager tm =
                            (android.telephony.TelephonyManager)
                            getSystemService(
                                    android.content.Context
                                            .TELEPHONY_SERVICE);
                    if (tm != null
                            && tm.getCallState()
                            != android.telephony.TelephonyManager
                                    .CALL_STATE_IDLE) {
                        callStillUp = true;
                    }
                } catch (Throwable ignored) {
                }
                if (!callStillUp) {
                    callKey = null;
                    callNumber = null;
                    callName = null;
                    sendCallBroadcast(CALL_IDLE, null, null);
                }
            }
        } catch (Throwable ignored) {
        }
        // The bubble owns its own dismiss; nothing to do here — except
        // the lyric listener: if the music notification goes away,
        // the music stopped, so stop listening.
        if (sbn != null && musicKey != null
                && musicKey.equals(sbn.getKey())) {
            musicListening = false;
            teardownMusicSession();
            musicToken = null;
            musicKey = null;
        }
    }
}
