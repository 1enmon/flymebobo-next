package com.chenan.flymebobo;

import android.app.Activity;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * flymebobo 2.0 — update check (v430, user 2026-11-23).
 *
 * After the user publishes a new release on GitHub, the phone
 * detects it and PROMPTS — never a silent install (user
 * "不要静默安装" / "提示一下有更新就行"). The check reads the
 * latest release of 1enmon/flymebobo-next, parses the versionCode
 * out of the APK asset name (flymebobo-2.4-v430.apk -> 430) and
 * compares it with the installed versionCode. A newer release
 * shows a dialog (in the app) and/or a system notification that
 * opens the release download page in the browser — the user taps
 * to download and installs it himself, as before.
 *
 * Built fresh for 2.0 (not copied from 1.x). Only framework APIs:
 * HttpURLConnection + org.json, no new dependencies.
 */
public final class UpdateChecker {

    private UpdateChecker() {
    }

    private static final String REPO = "1enmon/flymebobo-next";
    private static final String API_URL =
            "https://api.github.com/repos/" + REPO + "/releases/latest";

    /** SharedPreferences for "already notified for this version". */
    private static final String PREFS = "flymebobo_update";
    private static final String KEY_NOTIFIED_CODE = "notifiedCode";

    private static final String CHANNEL_ID = "flymebobo_update";
    private static final int NOTIFY_ID = 9001;

    /** Result of one check. */
    public static final class UpdateInfo {
        /** True when the release is newer than the installed build. */
        public boolean available;
        /** The release's versionCode (from the APK asset name). */
        public int latestCode;
        /** The release's versionName (from the tag, without "v"). */
        public String latestName;
        /** The APK's browser download URL (null when unavailable). */
        public String downloadUrl;
        /** Non-null when the check itself failed (network / parse). */
        public String error;
    }

    public interface Callback {
        void onResult(UpdateInfo info);
    }

    /** Run one check on a background thread; the callback runs on
     * the main thread. */
    public static void checkAsync(final Context ctx,
            final Callback cb) {
        if (ctx == null || cb == null) {
            return;
        }
        final Context appCtx = ctx.getApplicationContext();
        final Handler main = new Handler(Looper.getMainLooper());
        new Thread(new Runnable() {
            @Override
            public void run() {
                final UpdateInfo info = checkSync(appCtx);
                main.post(new Runnable() {
                    @Override
                    public void run() {
                        try {
                            cb.onResult(info);
                        } catch (Throwable ignored) {
                        }
                    }
                });
            }
        }).start();
    }

    /** The actual check (background thread only): installed
     * versionCode vs the latest GitHub release's APK asset. */
    private static UpdateInfo checkSync(Context ctx) {
        UpdateInfo info = new UpdateInfo();
        int installed = 0;
        try {
            installed = ctx.getPackageManager()
                    .getPackageInfo(ctx.getPackageName(), 0).versionCode;
        } catch (Throwable ignored) {
        }
        HttpURLConnection conn = null;
        try {
            URL url = new URL(API_URL);
            conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("GET");
            // GitHub's API refuses requests without a User-Agent.
            conn.setRequestProperty("User-Agent", "flymebobo");
            conn.setRequestProperty("Accept",
                    "application/vnd.github+json");
            conn.setConnectTimeout(10000);
            conn.setReadTimeout(10000);
            int status = conn.getResponseCode();
            if (status != HttpURLConnection.HTTP_OK) {
                info.error = "GitHub HTTP " + status;
                return info;
            }
            InputStream in = conn.getInputStream();
            BufferedReader r = new BufferedReader(
                    new InputStreamReader(in, "UTF-8"));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = r.readLine()) != null) {
                sb.append(line);
            }
            try {
                r.close();
            } catch (Throwable ignored) {
            }
            JSONObject release = new JSONObject(sb.toString());
            String tag = release.optString("tag_name", "");
            info.latestName = tag.startsWith("v")
                    ? tag.substring(1) : tag;
            JSONArray assets = release.optJSONArray("assets");
            if (assets == null) {
                info.error = "no assets";
                return info;
            }
            int bestCode = 0;
            String bestUrl = null;
            for (int i = 0; i < assets.length(); i++) {
                JSONObject a = assets.optJSONObject(i);
                if (a == null) {
                    continue;
                }
                String name = a.optString("name", "");
                if (!name.startsWith("flymebobo-")
                        || !name.endsWith(".apk")) {
                    continue;
                }
                int code = parseCodeFromName(name);
                if (code > bestCode) {
                    bestCode = code;
                    bestUrl = a.optString("browser_download_url",
                            null);
                }
            }
            if (bestCode <= 0 || bestUrl == null) {
                info.error = "no apk asset";
                return info;
            }
            info.latestCode = bestCode;
            info.downloadUrl = bestUrl;
            info.available = bestCode > installed;
            return info;
        } catch (Throwable t) {
            info.error = t.getMessage() != null
                    ? t.getMessage() : t.toString();
            return info;
        } finally {
            if (conn != null) {
                try {
                    conn.disconnect();
                } catch (Throwable ignored) {
                }
            }
        }
    }

    /** "flymebobo-2.4-v430.apk" -> 430; -1 when unparsable. */
    private static int parseCodeFromName(String name) {
        try {
            int v = name.lastIndexOf("-v");
            int dot = name.lastIndexOf(".apk");
            if (v < 0 || dot <= v + 2) {
                return -1;
            }
            return Integer.parseInt(name.substring(v + 2, dot));
        } catch (Throwable ignored) {
            return -1;
        }
    }

    /** Post the "new version" system notification — once per
     * versionCode (a later check for the same release stays quiet).
     * Tapping it opens the release download page; nothing is
     * installed automatically. */
    public static void notifyUpdate(Context ctx, UpdateInfo info) {
        if (ctx == null || info == null || !info.available
                || info.downloadUrl == null) {
            return;
        }
        try {
            SharedPreferences p = ctx.getSharedPreferences(PREFS,
                    Context.MODE_PRIVATE);
            if (p.getInt(KEY_NOTIFIED_CODE, -1) == info.latestCode) {
                return;
            }
            p.edit().putInt(KEY_NOTIFIED_CODE, info.latestCode)
                    .apply();
        } catch (Throwable ignored) {
        }
        try {
            NotificationManager nm = (NotificationManager)
                    ctx.getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm == null) {
                return;
            }
            if (Build.VERSION.SDK_INT >= 26) {
                NotificationChannel ch = new NotificationChannel(
                        CHANNEL_ID, "版本更新",
                        NotificationManager.IMPORTANCE_DEFAULT);
                nm.createNotificationChannel(ch);
            }
            Intent view = new Intent(Intent.ACTION_VIEW,
                    Uri.parse(info.downloadUrl));
            PendingIntent pi = PendingIntent.getActivity(ctx, 0, view,
                    PendingIntent.FLAG_UPDATE_CURRENT
                            | PendingIntent.FLAG_IMMUTABLE);
            String text = "v" + info.latestName + "（版本 "
                    + info.latestCode + "）已发布，点此前往下载";
            Notification.Builder b;
            if (Build.VERSION.SDK_INT >= 26) {
                b = new Notification.Builder(ctx, CHANNEL_ID);
            } else {
                b = new Notification.Builder(ctx);
            }
            b.setSmallIcon(android.R.drawable.ic_dialog_info)
                    .setContentTitle("煤气泡有新版本")
                    .setContentText(text)
                    .setContentIntent(pi)
                    .setAutoCancel(true);
            nm.notify(NOTIFY_ID, b.build());
        } catch (Throwable ignored) {
        }
    }

    /** The in-app prompt: a dialog with the new version and a
     * "去下载" button that opens the release page. No install —
     * the user downloads and installs it himself. Call on the
     * main thread (checkAsync's callback already is). */
    public static void showUpdateDialog(final Activity activity,
            final UpdateInfo info) {
        if (activity == null || info == null || !info.available
                || info.downloadUrl == null) {
            return;
        }
        try {
            String msg = "煤气泡 v" + info.latestName + "（版本 "
                    + info.latestCode + "）已发布，是否前往下载？";
            new android.app.AlertDialog.Builder(activity)
                    .setTitle("发现新版本")
                    .setMessage(msg)
                    .setPositiveButton("去下载",
                            new android.content.DialogInterface
                                    .OnClickListener() {
                                @Override
                                public void onClick(
                                        android.content.DialogInterface d,
                                        int which) {
                                    try {
                                        activity.startActivity(
                                                new Intent(
                                                        Intent.ACTION_VIEW,
                                                        Uri.parse(info
                                                                .downloadUrl)));
                                    } catch (Throwable ignored) {
                                    }
                                }
                            })
                    .setNegativeButton("稍后", null)
                    .show();
        } catch (Throwable ignored) {
        }
    }
}
