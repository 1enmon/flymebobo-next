package com.chenan.flymebobo;

import android.graphics.Bitmap;

/**
 * flymebobo 2.0 — 通知数据 (NotifData).
 *
 * A plain holder for one notification, read by NotificationService
 * and shown as a capsule. Fresh code for 2.0.
 */
public class NotifData {
    public final String key;
    public final String pkg;
    /** Mutable: live download progress (v98) rewrites line 1 in place
     * ("正在下载 45%"), so a rebuild shows the latest percent. */
    public String title;
    /** Mutable: live download progress (v95) rewrites line 2 in place,
     * so a rebuild shows the latest percent instead of the stale one. */
    public String text;
    public final long postTime;
    public final Bitmap icon;
    /** Mutable (v218): late session album art is applied in place
     * (CapsuleView.updateArt) when the art arrives after the pop —
     * the hook only reads art once at post time. */
    public Bitmap largeIcon;
    public final boolean isMusic;
    /** A download in progress (regular notification with progress).
     * Auto-pins on pop (user 2026-11-19 "这个弹出会自动钉住") so it
     * stays out showing live progress instead of docking after 3s. */
    public final boolean isDownload;
    /** v239: live download percent (0-100) for the capsule
     * progress bar. -1 when unknown / not a download. Mutable. */
    public int downloadPct = -1;
    /** An incoming / ongoing call (v103). Green capsule, auto-pinned
     * with NO pin icon, green answer button at the right end. */
    public final boolean isCall;
    /** A Flyme live activity (实况, v222): a notification with
     * is_live / a notification.live.capsule bundle (Meituan takeout,
     * the timer, ...). Its content updates by re-posting the same
     * key, so popCapsule treats a same-key live re-post as an
     * UPDATE (like music), not a duplicate. Set by the hook after
     * construction. */
    public boolean isLive;
    /** The notification's tap action (contentIntent), for the card's
     * 全屏打开 / 小窗打开 buttons (v143). Passed across processes as a
     * Parcelable extra in the ACTION_NOTIFY broadcast, like 1.x
     * (rn.intent = notification.contentIntent). Null when the
     * notification has no tap action — the card then falls back to
     * the app's launch intent. Set by XposedEntry after construction. */
    public android.app.PendingIntent contentIntent;
    /** v244: recognized code from the notification (verification
     * or pickup), e.g. "123456". Null when no code was found.
     * Set by BubbleWindow.popCapsule after recognition. Mutable. */
    public String code;
    /** v244: the keyword that matched ("验证码", "取餐码", ...),
     * used as the display label. Null when no code was found. */
    public String codeLabel;
    /** v245: smart-locker cabinet/box number (e.g. "第17箱",
     * "2-01柜", "1号柜"), extracted alongside a pickup code.
     * Null when the notification carries no cabinet number. */
    public String cabinet;
    /** v244: true for a pickup code (取货码/取件码/取餐码/...),
     * false for a verification code (验证码). Only verification
     * codes auto-copy; both types auto-pin and show enlarged. */
    public boolean isPickupCode;
    /** Current lyric line for music (null for non-music). Shown
     * enlarged + scrolling when the music capsule is pinned; falls
     * back to the song name when unavailable. Mutable: the live
     * MediaSession lyric listener (v76) updates it in place while
     * the capsule is pinned. */
    public String lyrics;

    public NotifData(String key, String pkg, String title, String text,
                     long postTime, Bitmap icon, Bitmap largeIcon) {
        this(key, pkg, title, text, postTime, icon, largeIcon, false, null, false);
    }

    public NotifData(String key, String pkg, String title, String text,
                     long postTime, Bitmap icon, Bitmap largeIcon,
                     boolean isMusic, String lyrics) {
        this(key, pkg, title, text, postTime, icon, largeIcon, isMusic, lyrics, false);
    }

    public NotifData(String key, String pkg, String title, String text,
                     long postTime, Bitmap icon, Bitmap largeIcon,
                     boolean isMusic, String lyrics, boolean isDownload) {
        this(key, pkg, title, text, postTime, icon, largeIcon,
                isMusic, lyrics, isDownload, false);
    }

    public NotifData(String key, String pkg, String title, String text,
                     long postTime, Bitmap icon, Bitmap largeIcon,
                     boolean isMusic, String lyrics, boolean isDownload,
                     boolean isCall) {
        this.key = key;
        this.pkg = pkg;
        this.title = title;
        this.text = text;
        this.postTime = postTime;
        this.icon = icon;
        this.largeIcon = largeIcon;
        this.isMusic = isMusic;
        this.lyrics = lyrics;
        this.isDownload = isDownload;
        this.isCall = isCall;
    }
}
