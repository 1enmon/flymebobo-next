package com.chenan.flymebobo;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;

import java.util.HashMap;
import java.util.Map;

/**
 * flymebobo 2.4.2 (v504) — per-app bubble rules. From the feature
 * backlog (#5, user 2026-11-23); the settings UI was previewed
 * and approved before implementation, per the new house rule.
 *
 * Each app can be set to:
 *   DEFAULT — follow the global switches (no rule stored);
 *   NEVER   — its notifications never become bubbles (the system
 *             notification itself is untouched);
 *   EDGE    — no capsule pop; the notification goes straight
 *             into the docked fan, tap the fan to expand it back.
 *
 * Storage mirrors the other settings: a dedicated prefs file
 * ("flymebobo_apprules", pkg -> "never"/"edge") in the APP,
 * backed up into the settings file by SettingsStore with the
 * "r." prefix so SystemUI can read it without
 * createPackageContext. The SystemUI side keeps a static map,
 * loaded at start / settings reload / ACTION_APP_RULES
 * broadcast, so a change applies at once with no restart.
 * Calls are exempt (same as the scene guard).
 */
public final class AppRules {

    public static final int DEFAULT = 0;
    public static final int NEVER = 1;
    public static final int EDGE = 2;

    public static final String PREFS = "flymebobo_apprules";
    public static final String VALUE_NEVER = "never";
    public static final String VALUE_EDGE = "edge";

    private static volatile Map<String, Integer> rules =
            new HashMap<>();

    private AppRules() {
    }

    public static int modeFor(String pkg) {
        if (pkg == null) {
            return DEFAULT;
        }
        Integer v = rules.get(pkg);
        return v == null ? DEFAULT : v;
    }

    public static int parse(String v) {
        if (VALUE_NEVER.equals(v)) {
            return NEVER;
        }
        if (VALUE_EDGE.equals(v)) {
            return EDGE;
        }
        return DEFAULT;
    }

    public static String value(int mode) {
        if (mode == NEVER) {
            return VALUE_NEVER;
        }
        if (mode == EDGE) {
            return VALUE_EDGE;
        }
        return null;
    }

    /** SystemUI side: rebuild the static map. The settings file
     * wins over prefs (same order as FeatureFlags.load). */
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
                    if (mode != DEFAULT) {
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
                        SettingsStore.PREFIX_RULE)) {
                    String pkg = e.getKey().substring(
                            SettingsStore.PREFIX_RULE.length());
                    int mode = parse(e.getValue());
                    if (!pkg.isEmpty() && mode != DEFAULT) {
                        m.put(pkg, mode);
                    }
                }
            }
        } catch (Throwable ignored) {
        }
        rules = m;
    }

    // ---- APP side ----

    /** All rules currently stored in the APP's prefs. */
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
                    if (mode != DEFAULT) {
                        m.put(e.getKey(), mode);
                    }
                }
            }
        } catch (Throwable ignored) {
        }
        return m;
    }

    /** Save one rule (DEFAULT removes it), back everything up to
     * the settings file, and tell SystemUI to reload at once. */
    public static void setRule(Context appCtx, String pkg,
            int mode) {
        if (appCtx == null || pkg == null) {
            return;
        }
        try {
            SharedPreferences.Editor ed = appCtx
                    .getSharedPreferences(PREFS,
                            Context.MODE_PRIVATE)
                    .edit();
            String v = value(mode);
            if (v == null) {
                ed.remove(pkg);
            } else {
                ed.putString(pkg, v);
            }
            ed.apply();
        } catch (Throwable ignored) {
        }
        SettingsStore.saveAll(appCtx);
        try {
            appCtx.sendBroadcast(new Intent(
                    NotificationService.ACTION_APP_RULES));
        } catch (Throwable ignored) {
        }
    }
}
