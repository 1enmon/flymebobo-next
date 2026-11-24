package com.chenan.flymebobo;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * flymebobo v511 — online-updated game list for the scene
 * guard (user 2026-11-24: "游戏名单改成线上更新").
 *
 * The guard recognises games three ways already (system
 * appCategory, engine Activity names, a built-in package
 * list), but a hardcoded list always lags behind new games —
 * 金铲铲之战 shipped with neither a category nor an engine
 * hint and slipped through. So the package list also lives in
 * the repo as games.txt; the APP fetches it at most every
 * 6 hours, caches it in prefs ("flymebobo_gamelist"), backs
 * it into the settings file (SettingsStore prefix "g.") and
 * pushes it live to SystemUI flattened on the
 * ACTION_GAME_LIST broadcast — the same three channels
 * AppRules / SceneExceptions use, because a plain
 * cross-process prefs re-read is the channel that sank 1.x.
 *
 * Offline / fetch failure: the last cached list (or just the
 * built-in one) keeps working; nothing here can break scene
 * detection, it can only add packages to it.
 */
public final class GamePackages {

    public static final String PREFS = "flymebobo_gamelist";
    private static final String META_PREFS = "flymebobo_gamelist_meta";
    private static final String KEY_LAST_ATTEMPT = "last_attempt";

    /** The list itself, one package per line, "#" comments. */
    public static final String LIST_URL =
            "https://raw.githubusercontent.com/1enmon/flymebobo-next"
                    + "/main/games.txt";

    /** Broadcast extra: the whole list flattened "pkg;pkg;...". */
    public static final String EXTRA_LIST = "gamelist_flat";

    private static final long FETCH_INTERVAL_MS = 6L * 3600L * 1000L;

    private static volatile Set<String> remote = new HashSet<>();

    private GamePackages() {
    }

    public static boolean contains(String pkg) {
        return pkg != null && remote.contains(pkg);
    }

    public static String flatten(Set<String> pkgs) {
        StringBuilder sb = new StringBuilder();
        for (String p : pkgs) {
            if (p == null || p.isEmpty()) {
                continue;
            }
            if (sb.length() > 0) {
                sb.append(';');
            }
            sb.append(p);
        }
        return sb.toString();
    }

    public static void applyFlat(String flat) {
        Set<String> s = new HashSet<>();
        if (flat != null) {
            for (String part : flat.split(";")) {
                String p = part.trim();
                if (!p.isEmpty()) {
                    s.add(p);
                }
            }
        }
        remote = s;
        // v511 diagnostics, same idea as SceneExc: prove the
        // live channel arrived and what it carried.
        FileLog.i("GameList applyFlat n=" + s.size());
    }

    /** SystemUI side: rebuild from prefs, settings file wins
     * (same order as SceneExceptions.load). */
    public static void load(Context ctx) {
        if (ctx == null) {
            return;
        }
        Set<String> s = new HashSet<>();
        try {
            Context app = ctx.createPackageContext(
                    "com.chenan.flymebobo",
                    Context.CONTEXT_IGNORE_SECURITY);
            SharedPreferences p = app.getSharedPreferences(
                    PREFS, Context.MODE_PRIVATE);
            for (Map.Entry<String, ?> e : p.getAll().entrySet()) {
                if (e.getKey() != null
                        && "1".equals(e.getValue())) {
                    s.add(e.getKey());
                }
            }
        } catch (Throwable ignored) {
        }
        try {
            Map<String, String> file = SettingsStore.readAll();
            for (Map.Entry<String, String> e : file.entrySet()) {
                if (e.getKey() != null && e.getKey().startsWith(
                        SettingsStore.PREFIX_GAME)) {
                    String pkg = e.getKey().substring(
                            SettingsStore.PREFIX_GAME.length());
                    if (!pkg.isEmpty()) {
                        s.add(pkg);
                    }
                }
            }
        } catch (Throwable ignored) {
        }
        remote = s;
        FileLog.i("GameList load n=" + s.size());
    }

    // ---- APP side ----

    /** All cached packages in the APP's prefs. */
    public static Set<String> readAll(Context appCtx) {
        Set<String> s = new HashSet<>();
        if (appCtx == null) {
            return s;
        }
        try {
            SharedPreferences p = appCtx.getSharedPreferences(
                    PREFS, Context.MODE_PRIVATE);
            for (Map.Entry<String, ?> e : p.getAll().entrySet()) {
                if (e.getKey() != null
                        && "1".equals(e.getValue())) {
                    s.add(e.getKey());
                }
            }
        } catch (Throwable ignored) {
        }
        return s;
    }

    /** Fetch games.txt when the last attempt is stale.
     * Fire-and-forget on a worker thread; safe to call on
     * every app open / service start. */
    public static void fetchIfStale(final Context appCtx) {
        if (appCtx == null) {
            return;
        }
        try {
            SharedPreferences meta = appCtx.getSharedPreferences(
                    META_PREFS, Context.MODE_PRIVATE);
            long last = meta.getLong(KEY_LAST_ATTEMPT, 0L);
            long now = System.currentTimeMillis();
            if (now - last < FETCH_INTERVAL_MS) {
                return;
            }
            meta.edit().putLong(KEY_LAST_ATTEMPT, now).commit();
        } catch (Throwable ignored) {
            return;
        }
        new Thread(new Runnable() {
            @Override
            public void run() {
                Set<String> fetched = fetchRemote();
                if (fetched == null || fetched.isEmpty()) {
                    // Keep the old cache; an empty/garbled
                    // download must never wipe the list.
                    return;
                }
                try {
                    SharedPreferences.Editor ed = appCtx
                            .getSharedPreferences(PREFS,
                                    Context.MODE_PRIVATE)
                            .edit();
                    ed.clear();
                    for (String p : fetched) {
                        ed.putString(p, "1");
                    }
                    ed.commit();
                } catch (Throwable ignored) {
                    return;
                }
                SettingsStore.saveAll(appCtx);
                try {
                    Intent intent = new Intent(
                            NotificationService.ACTION_GAME_LIST);
                    intent.setPackage("com.android.systemui");
                    intent.putExtra(EXTRA_LIST, flatten(fetched));
                    appCtx.sendBroadcast(intent);
                } catch (Throwable ignored) {
                }
            }
        }, "flymebobo-gamelist").start();
    }

    private static Set<String> fetchRemote() {
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(LIST_URL)
                    .openConnection();
            conn.setConnectTimeout(10000);
            conn.setReadTimeout(10000);
            conn.setRequestProperty("User-Agent", "flymebobo");
            if (conn.getResponseCode() != 200) {
                return null;
            }
            Set<String> s = new HashSet<>();
            BufferedReader r = new BufferedReader(
                    new InputStreamReader(conn.getInputStream(),
                            "UTF-8"));
            String line;
            while ((line = r.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty() || line.startsWith("#")) {
                    continue;
                }
                int sp = line.indexOf(' ');
                if (sp > 0) {
                    line = line.substring(0, sp);
                }
                int hash = line.indexOf('#');
                if (hash > 0) {
                    line = line.substring(0, hash);
                }
                line = line.trim();
                // Package names only — refuse anything that
                // could not be one, so a bad mirror can never
                // inject junk into scene detection.
                if (line.matches("[a-zA-Z0-9_.]+")
                        && line.contains(".")) {
                    s.add(line);
                }
            }
            r.close();
            return s;
        } catch (Throwable th) {
            return null;
        } finally {
            if (conn != null) {
                conn.disconnect();
            }
        }
    }
}
