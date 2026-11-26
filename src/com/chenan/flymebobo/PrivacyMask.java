package com.chenan.flymebobo;

import android.app.KeyguardManager;
import android.content.Context;
import android.hardware.display.DisplayManager;
import android.media.MediaRouter;
import android.os.Build;
import android.view.Display;

/**
 * flymebobo 2.0 — sensitive-code masking (v524, user 2026-11-26).
 *
 * Approved preview (2026-11-25, four-state preview: normal /
 * lockscreen / recording / casting): when the phone is locked, or
 * the screen is being recorded / cast, a verification or pickup
 * code capsule shows only the app name + "验证码 ••••••" — never
 * the digits. When the state lifts, the full code comes back.
 * Scope is codes only (extractCode's verdict): ordinary chat,
 * music, navigation and calls are never masked. Auto-copy still
 * copies the real code to the user's own clipboard; only what is
 * shown on screen is masked (the copy Toast hides the digits too).
 *
 * Switches (FeatureFlags, all default on): master "mask", plus
 * "maskLock" (lockscreen) and "maskRecord" (recording/casting)
 * sub-switches, nested under the "验证取件码" settings card.
 *
 * v526 adds a fourth switch in the same card, "maskApps"
 * （普通应用也打码， default OFF — user 2026-11-26): when on,
 * the same masking scenes also hide ordinary app notifications'
 * content — capsule and expanded card show only the app name +
 * "内容已隐藏". Codes are unaffected (their own logic above);
 * music / download / live / navi / call capsules are never
 * touched (see isOrdinary).
 *
 * State detection — lockscreen is reliable (KeyguardManager, the
 * same call InCallUiHide uses). Recording/casting has no single
 * public signal, so three best-effort signals are OR-ed and each
 * one is logged (FileLog "MaskState") so a test build can tell
 * which signal actually fires on this phone:
 *  1. MediaProjectionManager.getActiveProjection() via reflection
 *     (hidden API; SystemUI's own recorder and third-party
 *     recorders both go through MediaProjection);
 *  2. MediaRouter selected route is a remote display / live video
 *     (casting);
 *  3. DisplayManager reports a non-default display (a MediaProjection
 *     or cast session creates a virtual display).
 * If all three miss, a recording that starts while a code capsule
 * is already on screen stays unmasked until the next render
 * (new capsules are always evaluated at bind time, and every
 * screen-on / unlock re-renders visible code capsules via
 * BubbleWindow.refreshMasking()). That gap is a known v1 limit,
 * not a silent promise.
 */
public final class PrivacyMask {

    private PrivacyMask() {
    }

    /** Last logged state, so the FileLog line only fires on change. */
    private static volatile int sLastLogged = -1;

    /** Should code capsules be masked right now? */
    public static boolean shouldMask(Context ctx) {
        if (!FeatureFlags.mask) {
            return false;
        }
        boolean locked = isLocked(ctx);
        boolean recording = isRecordingOrCasting(ctx);
        boolean active = (locked && FeatureFlags.maskLock)
                || (recording && FeatureFlags.maskRecord);
        int state = (locked ? 1 : 0) | (recording ? 2 : 0)
                | (active ? 4 : 0)
                | (FeatureFlags.maskApps ? 8 : 0);
        if (state != sLastLogged) {
            sLastLogged = state;
            try {
                FileLog.i("MaskState locked=" + locked
                        + " recording=" + recording
                        + " active=" + active
                        + " mask=" + FeatureFlags.mask
                        + " maskLock=" + FeatureFlags.maskLock
                        + " maskRecord=" + FeatureFlags.maskRecord
                        + " maskApps=" + FeatureFlags.maskApps);
            } catch (Throwable ignored) {
            }
        }
        return active;
    }

    /** The line shown in place of an ordinary notification's
     * content while it is masked (v526). */
    public static final String HIDDEN_TEXT = "内容已隐藏";

    /**
     * v526: is this an ordinary app notification — the kind the
     * "普通应用也打码" switch covers? Judged purely by NotifData's
     * own type flags: no recognized code (codes keep their own
     * logic, untouched by the new switch) and none of the special
     * capsule flags set — music / download / call / live / navi /
     * bus show live state, not message content, and are never
     * masked by this switch.
     */
    public static boolean isOrdinary(NotifData d) {
        return d != null && d.code == null
                && !d.isMusic && !d.isDownload && !d.isCall
                && !d.isLive && !d.isNavi && !d.isBus;
    }

    /**
     * v526: should this ordinary notification's content be hidden
     * right now? Requires the maskApps switch (default off) on top
     * of the normal masking verdict — master switch on and an
     * enabled scene (lockscreen / recording-casting) active, the
     * exact same gate codes go through.
     */
    public static boolean shouldMaskOrdinary(Context ctx, NotifData d) {
        return FeatureFlags.maskApps && isOrdinary(d) && shouldMask(ctx);
    }

    /** Lockscreen up? (KeyguardManager — same call as InCallUiHide.) */
    public static boolean isLocked(Context ctx) {
        try {
            if (ctx == null) {
                return false;
            }
            KeyguardManager km = (KeyguardManager) ctx.getSystemService(
                    Context.KEYGUARD_SERVICE);
            if (km == null) {
                return false;
            }
            return Build.VERSION.SDK_INT >= 33 ? km.isKeyguardLocked()
                    : km.inKeyguardRestrictedInputMode();
        } catch (Throwable ignored) {
            return false;
        }
    }

    /** Screen recording or casting in progress? Any one signal wins. */
    public static boolean isRecordingOrCasting(Context ctx) {
        return isMediaProjectionActive(ctx)
                || isCastRouteActive(ctx)
                || hasVirtualDisplay(ctx);
    }

    /** A MediaProjection session exists (recording, or casting
     * through it). Hidden API — reflection, best effort. */
    private static boolean isMediaProjectionActive(Context ctx) {
        try {
            if (ctx == null) {
                return false;
            }
            android.media.projection.MediaProjectionManager mpm =
                    (android.media.projection.MediaProjectionManager)
                            ctx.getSystemService(
                                    Context.MEDIA_PROJECTION_SERVICE);
            if (mpm == null) {
                return false;
            }
            java.lang.reflect.Method m =
                    android.media.projection.MediaProjectionManager.class
                            .getMethod("getActiveProjection");
            Object projection = m.invoke(mpm);
            return projection != null;
        } catch (Throwable ignored) {
            return false;
        }
    }

    /** A non-default route is selected for live video, or any
     * route reports remote playback (casting). Public API.
     * (There is no ROUTE_TYPE_REMOTE_DISPLAY in MediaRouter —
     * screen casting shows up as a live-video route whose
     * playback type is remote, distinct from the default route.) */
    private static boolean isCastRouteActive(Context ctx) {
        try {
            if (ctx == null) {
                return false;
            }
            MediaRouter mr = (MediaRouter) ctx.getSystemService(
                    Context.MEDIA_ROUTER_SERVICE);
            if (mr == null) {
                return false;
            }
            MediaRouter.RouteInfo def = mr.getDefaultRoute();
            MediaRouter.RouteInfo r = mr.getSelectedRoute(
                    MediaRouter.ROUTE_TYPE_LIVE_VIDEO);
            if (r != null && def != null && r != def) {
                return true;
            }
            if (r != null && r.getPlaybackType()
                    == MediaRouter.RouteInfo.PLAYBACK_TYPE_REMOTE) {
                return true;
            }
            int n = mr.getRouteCount();
            for (int i = 0; i < n; i++) {
                MediaRouter.RouteInfo ri = mr.getRouteAt(i);
                if (ri != null && ri != def && ri.getPlaybackType()
                        == MediaRouter.RouteInfo.PLAYBACK_TYPE_REMOTE) {
                    return true;
                }
            }
            return false;
        } catch (Throwable ignored) {
            return false;
        }
    }

    /** A non-default display exists — a MediaProjection or cast
     * session creates a virtual display. Public API. */
    private static boolean hasVirtualDisplay(Context ctx) {
        try {
            if (ctx == null) {
                return false;
            }
            DisplayManager dm = (DisplayManager) ctx.getSystemService(
                    Context.DISPLAY_SERVICE);
            if (dm == null) {
                return false;
            }
            Display[] displays = dm.getDisplays();
            if (displays == null) {
                return false;
            }
            for (Display d : displays) {
                if (d != null
                        && d.getDisplayId() != Display.DEFAULT_DISPLAY) {
                    return true;
                }
            }
            return false;
        } catch (Throwable ignored) {
            return false;
        }
    }

    /** The masked capsule line: "验证码 ••••••" / "取件码 ••••••"
     * (bullets match the code length; pickup codes keep their
     * cabinet number — the code, not the locker, is the secret). */
    public static String maskedCodeLine(NotifData d) {
        if (d == null) {
            return "验证码 ••••••";
        }
        String label = (d.codeLabel != null && !d.codeLabel.isEmpty())
                ? d.codeLabel : "验证码";
        StringBuilder b = new StringBuilder(label);
        b.append(' ');
        int n = (d.code != null && !d.code.isEmpty())
                ? d.code.length() : 6;
        for (int i = 0; i < n; i++) {
            b.append('•');
        }
        if (d.isPickupCode && d.cabinet != null) {
            b.append(" · ").append(d.cabinet);
        }
        return b.toString();
    }

    /** Replace every occurrence of the code inside a longer text
     * (card title / body) with same-length bullets. Literal
     * replacement, no regex. */
    public static String maskCodeInText(String text, String code) {
        if (text == null || code == null || code.isEmpty()) {
            return text;
        }
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < code.length(); i++) {
            b.append('•');
        }
        return text.replace(code, b.toString());
    }
}
