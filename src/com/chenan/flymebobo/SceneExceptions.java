package com.chenan.flymebobo;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * flymebobo v508 — scene exception list (feature backlog #1,
 * previewed 2026-11-24 morning, user approved "也加上" the
 * same afternoon). While a landscape game/video scene
 * suppresses bubbles, most notifications queue until the scene
 * ends — apps named here break through and pop anyway.
 *
 * Per-app tri-state, mirroring AppRules:
 *   stored "break"  — pop even in a scene;
 *   stored "follow" — queue like everything else;
 *   nothing stored  — the built-in defaults decide
 *                     (WeChat / QQ break through out of the box).
 * Calls were already exempt and stay exempt.
 *
 * Storage mirrors AppRules exactly: dedicated prefs
 * ("flymebobo_sceneexc") in the APP, backed up into the
 * settings file by SettingsStore with the "x." prefix, and the
 * live channel to SystemUI is the flattened set riding the
 * ACTION_SCENE_EXC broadcast extra — never a cross-process
 * prefs re-read (that channel is what sank 1.x app rules).
 */
public final class SceneExceptions {

    public static final int FOLLOW = 0;
    public static final int BREAK = 1;

    public static final String PREFS = "flymebobo_sceneexc";
    public static final String VALUE_BREAK = "break";
    public static final String VALUE_FOLLOW = "follow";

    /** Out-of-the-box breakthrough apps (chat — the ones a
     * player actually wants to see mid-game). */
    private static final Set<String> DEFAULT_BREAK =
            new HashSet<>();
    static {
        DEFAULT_BREAK.add("com.tencent.mm");
        DEFAULT_BREAK.add("com.tencent.mobileqq");
    }

    private static volatile Map<String, Integer> stored =
            new HashMap<>();

    private SceneExceptions() {
    }

    /** Effective mode for a pkg given a stored-entries map:
     * an explicit entry wins, otherwise the defaults decide. */
    public static int effective(Map<String, Integer> storedMap,
            String pkg) {
        if (pkg == null) {
            return FOLLOW;
        }
        Integer v = storedMap == null ? null : storedMap.get(pkg);
        if (v != null) {
            return v;
        }
        return DEFAULT_BREAK.contains(pkg) ? BREAK : FOLLOW;
    }

    public static int modeFor(String pkg) {
        return effective(stored, pkg);
    }

    public static boolean isExcepted(String pkg) {
        return modeFor(pkg) == BREAK;
    }

    /** Intent extra carrying the stored entries flattened as
     * "pkg=value;pkg=value" — the live channel to SystemUI. */
    public static final String EXTRA_EXC = "sceneexc_flat";

    public static String flatten(Map<String, Integer> entries) {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, Integer> e : entries.entrySet()) {
            String v = value(e.getValue());
            if (v == null) {
                continue;
            }
            if (sb.length() > 0) {
                sb.append(';');
            }
            sb.append(e.getKey()).append('=').append(v);
        }
        return sb.toString();
    }

    public static void applyFlat(String flat) {
        Map<String, Integer> m = new HashMap<>();
        if (flat != null) {
            for (String part : flat.split(";")) {
                int eq = part.indexOf('=');
                if (eq <= 0) {
                    continue;
                }
                int mode = parse(part.substring(eq + 1).trim());
                if (mode >= 0) {
                    m.put(part.substring(0, eq).trim(), mode);
                }
            }
        }
        stored = m;
        // v509 diagnostics: prove the live channel arrived.
        FileLog.i("SceneExc applyFlat " + flatten(m));
    }

    /** v509 diagnostics: read-only view of the stored map. */
    public static Map<String, Integer> snapshot() {
        return stored;
    }

    /** @return BREAK / FOLLOW, or -1 for an unknown value. */
    public static int parse(String v) {
        if (VALUE_BREAK.equals(v)) {
            return BREAK;
        }
        if (VALUE_FOLLOW.equals(v)) {
            return FOLLOW;
        }
        return -1;
    }

    public static String value(int mode) {
        if (mode == BREAK) {
            return VALUE_BREAK;
        }
        if (mode == FOLLOW) {
            return VALUE_FOLLOW;
        }
        return null;
    }

    /** SystemUI side: rebuild the stored map. The settings file
     * wins over prefs (same order as AppRules.load). */
    public static void load(Context ctx) {
        if (ctx == null) {
            return;
        }
        Map<String, Integer> m = new HashMap<>();
        try {
            Context app = ctx.createPackageContext(
                    "com.chenan.flymebobo",
                    Context.CONTEXT_IGNORE_SECURITY);
            SharedPreferences p = app.getSharedPreferences(
                    PREFS, Context.MODE_PRIVATE);
            for (Map.Entry<String, ?> e : p.getAll().entrySet()) {
                if (e.getKey() != null
                        && e.getValue() instanceof String) {
                    int mode = parse((String) e.getValue());
                    if (mode >= 0) {
                        m.put(e.getKey(), mode);
                    }
                }
            }
        } catch (Throwable ignored) {
        }
        try {
            Map<String, String> file = SettingsStore.readAll();
            for (Map.Entry<String, String> e : file.entrySet()) {
                if (e.getKey() != null && e.getKey().startsWith(
                        SettingsStore.PREFIX_SCENE_EXC)) {
                    String pkg = e.getKey().substring(
                            SettingsStore.PREFIX_SCENE_EXC.length());
                    int mode = parse(e.getValue());
                    if (!pkg.isEmpty() && mode >= 0) {
                        m.put(pkg, mode);
                    }
                }
            }
        } catch (Throwable ignored) {
        }
        stored = m;
        // v509 diagnostics: prove what startup/fallback loads see.
        FileLog.i("SceneExc load " + flatten(m));
    }

    // ---- APP side ----

    /** All explicitly stored entries in the APP's prefs. */
    public static Map<String, Integer> readAll(Context appCtx) {
        Map<String, Integer> m = new HashMap<>();
        if (appCtx == null) {
            return m;
        }
        try {
            SharedPreferences p = appCtx.getSharedPreferences(
                    PREFS, Context.MODE_PRIVATE);
            for (Map.Entry<String, ?> e : p.getAll().entrySet()) {
                if (e.getKey() != null
                        && e.getValue() instanceof String) {
                    int mode = parse((String) e.getValue());
                    if (mode >= 0) {
                        m.put(e.getKey(), mode);
                    }
                }
            }
        } catch (Throwable ignored) {
        }
        return m;
    }

    /** Save one app's choice (always explicit — even "follow"
     * for a default-break app is a real choice), back everything
     * up to the settings file, and tell SystemUI at once. */
    public static void setMode(Context appCtx, String pkg,
            int mode) {
        if (appCtx == null || pkg == null) {
            return;
        }
        String v = value(mode);
        if (v == null) {
            return;
        }
        try {
            SharedPreferences.Editor ed = appCtx
                    .getSharedPreferences(PREFS,
                            Context.MODE_PRIVATE)
                    .edit();
            ed.putString(pkg, v);
            // commit, not apply — same rule as AppRules.
            ed.commit();
        } catch (Throwable ignored) {
        }
        SettingsStore.saveAll(appCtx);
        try {
            Intent intent = new Intent(
                    NotificationService.ACTION_SCENE_EXC);
            intent.setPackage("com.android.systemui");
            intent.putExtra(EXTRA_EXC,
                    flatten(readAll(appCtx)));
            appCtx.sendBroadcast(intent);
        } catch (Throwable ignored) {
        }
    }
}
