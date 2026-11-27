package com.chenan.flymebobo;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
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
 * shows a dialog (in the app) and/or a system notification.
 * v521: the download happens in-app (progress dialog, no
 * browser round-trip) and hands the finished APK to the system
 * installer; the final install tap is still the user's, as
 * before — Android never allows a silent install here.
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
        /** v524: the release notes (GitHub release "body") so the
         * update dialog can show what's new before downloading. */
        public String releaseNotes;
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
            // v524: release notes ride along in the same response
            // (user 2026-11-26 "更新内容预览") — the dialog shows
            // them before offering the download.
            info.releaseNotes = release.optString("body", "");
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
            // v521: tapping the notification opens the app; its
            // on-open check shows the download dialog in-app.
            Intent view = new Intent(ctx, MainActivity.class);
            view.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            PendingIntent pi = PendingIntent.getActivity(ctx, 0, view,
                    PendingIntent.FLAG_UPDATE_CURRENT
                            | PendingIntent.FLAG_IMMUTABLE);
            String text = "v" + info.latestName + "（版本 "
                    + info.latestCode + "）已发布，点此打开应用更新";
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
     * "下载更新" button that downloads inside the app (v521) and
     * then hands the APK to the system installer. Call on the
     * main thread (checkAsync's callback already is). */
    public static void showUpdateDialog(final Activity activity,
            final UpdateInfo info) {
        if (activity == null || info == null || !info.available
                || info.downloadUrl == null) {
            return;
        }
        try {
            String msg = "煤气泡 v" + info.latestName + "（版本 "
                    + info.latestCode + "）已发布";
            // v524: show the release notes first, so the user sees
            // what's new before choosing download or ignore.
            if (info.releaseNotes != null
                    && !info.releaseNotes.trim().isEmpty()) {
                String notes = info.releaseNotes.trim();
                if (notes.length() > 800) {
                    notes = notes.substring(0, 800) + "…";
                }
                msg += "\n\n更新内容：\n" + notes;
            }
            msg += "\n\n是否下载更新？";
            new AlertDialog.Builder(activity)
                    .setTitle("发现新版本")
                    .setMessage(msg)
                    .setPositiveButton("下载更新",
                            new DialogInterface.OnClickListener() {
                                @Override
                                public void onClick(
                                        DialogInterface d,
                                        int which) {
                                    downloadAndInstall(activity,
                                            info);
                                }
                            })
                    .setNegativeButton("稍后", null)
                    .show();
        } catch (Throwable ignored) {
        }
    }

    // ---- in-app download + install (v521) ----

    private static volatile boolean sDownloadCancelled;

    private static File updateDir(Context ctx) {
        File dir = new File(ctx.getCacheDir(), "update");
        if (!dir.exists()) {
            dir.mkdirs();
        }
        return dir;
    }

    private static File updateFile(Context ctx, UpdateInfo info) {
        return new File(updateDir(ctx),
                "flymebobo-update-" + info.latestCode + ".apk");
    }

    /** Download the release APK inside the app, with a progress
     * dialog, then hand it to the system installer. A finished
     * download for the same versionCode is reused, so the
     * grant-install-permission round trip never re-downloads. */
    public static void downloadAndInstall(final Activity activity,
            final UpdateInfo info) {
        if (activity == null || info == null
                || info.downloadUrl == null) {
            return;
        }
        final File target = updateFile(activity, info);
        if (target.isFile() && target.length() > 0) {
            installDownloaded(activity, target);
            return;
        }

        final Handler main = new Handler(Looper.getMainLooper());
        int pad = (int) (20 * activity.getResources()
                .getDisplayMetrics().density);
        LinearLayout box = new LinearLayout(activity);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(pad, pad / 2, pad, 0);
        final TextView status = new TextView(activity);
        status.setText("正在下载 " + target.getName());
        final ProgressBar bar = new ProgressBar(activity, null,
                android.R.attr.progressBarStyleHorizontal);
        bar.setMax(100);
        bar.setIndeterminate(true);
        LinearLayout.LayoutParams barLp =
                new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT);
        barLp.topMargin = pad / 2;
        box.addView(status);
        box.addView(bar, barLp);

        sDownloadCancelled = false;
        final AlertDialog dlg = new AlertDialog.Builder(activity)
                .setTitle("下载更新")
                .setView(box)
                .setNegativeButton("取消",
                        new DialogInterface.OnClickListener() {
                            @Override
                            public void onClick(DialogInterface d,
                                    int which) {
                                sDownloadCancelled = true;
                            }
                        })
                .setCancelable(false)
                .create();
        dlg.show();

        new Thread(new Runnable() {
            @Override
            public void run() {
                HttpURLConnection conn = null;
                File tmp = new File(target.getParentFile(),
                        target.getName() + ".part");
                try {
                    URL url = new URL(info.downloadUrl);
                    conn = (HttpURLConnection) url.openConnection();
                    conn.setRequestProperty("User-Agent",
                            "flymebobo");
                    conn.setConnectTimeout(15000);
                    conn.setReadTimeout(30000);
                    int code = conn.getResponseCode();
                    if (code != HttpURLConnection.HTTP_OK) {
                        throw new java.io.IOException(
                                "HTTP " + code);
                    }
                    final long total = conn.getContentLength();
                    InputStream in = conn.getInputStream();
                    FileOutputStream out =
                            new FileOutputStream(tmp);
                    byte[] buf = new byte[16384];
                    long done = 0;
                    int n;
                    while ((n = in.read(buf)) >= 0) {
                        if (sDownloadCancelled) {
                            try { out.close(); } catch (Throwable ignored) {}
                            try { in.close(); } catch (Throwable ignored) {}
                            tmp.delete();
                            main.post(new Runnable() {
                                @Override
                                public void run() {
                                    try { dlg.dismiss(); } catch (Throwable ignored) {}
                                }
                            });
                            return;
                        }
                        out.write(buf, 0, n);
                        done += n;
                        final long doneF = done;
                        main.post(new Runnable() {
                            @Override
                            public void run() {
                                if (total > 0) {
                                    bar.setIndeterminate(false);
                                    bar.setProgress((int) (doneF
                                            * 100 / total));
                                    status.setText("正在下载 "
                                            + (doneF / 1024)
                                            + " KB / "
                                            + (total / 1024)
                                            + " KB");
                                } else {
                                    status.setText("正在下载 "
                                            + (doneF / 1024)
                                            + " KB");
                                }
                            }
                        });
                    }
                    out.close();
                    in.close();
                    if (!tmp.renameTo(target)) {
                        throw new java.io.IOException(
                                "保存安装包失败");
                    }
                    main.post(new Runnable() {
                        @Override
                        public void run() {
                            try { dlg.dismiss(); } catch (Throwable ignored) {}
                            installDownloaded(activity, target);
                        }
                    });
                } catch (final Throwable t) {
                    tmp.delete();
                    main.post(new Runnable() {
                        @Override
                        public void run() {
                            try { dlg.dismiss(); } catch (Throwable ignored) {}
                            new AlertDialog.Builder(activity)
                                    .setTitle("下载失败")
                                    .setMessage(String.valueOf(
                                            t.getMessage()))
                                    .setPositiveButton("知道了", null)
                                    .show();
                        }
                    });
                } finally {
                    if (conn != null) {
                        conn.disconnect();
                    }
                }
            }
        }).start();
    }

    /** Hand a downloaded APK to the system installer. On
     * Android 8+ the user must first allow this app to install
     * packages; the downloaded file stays cached, so after
     * granting they just tap 检查更新 again to continue. */
    private static void installDownloaded(final Activity activity,
            final File file) {
        if (Build.VERSION.SDK_INT >= 26
                && !activity.getPackageManager()
                        .canRequestPackageInstalls()) {
            new AlertDialog.Builder(activity)
                    .setTitle("需要安装权限")
                    .setMessage("系统要求先允许煤气泡安装应用。"
                            + "开启后回来再点一次「检查更新」即可继续"
                            + "安装（安装包已下载好，不用重下）。")
                    .setPositiveButton("去设置",
                            new DialogInterface.OnClickListener() {
                                @Override
                                public void onClick(
                                        DialogInterface d,
                                        int which) {
                                    try {
                                        Intent it = new Intent(
                                                Settings
                                                .ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                                                Uri.parse("package:"
                                                        + activity
                                                        .getPackageName()));
                                        activity.startActivity(it);
                                    } catch (Throwable ignored) {
                                    }
                                }
                            })
                    .setNegativeButton("取消", null)
                    .show();
            return;
        }
        try {
            Uri uri = new Uri.Builder()
                    .scheme("content")
                    .authority(ApkFileProvider.AUTHORITY)
                    .appendPath(file.getName())
                    .build();
            Intent it = new Intent(Intent.ACTION_VIEW);
            it.setDataAndType(uri,
                    "application/vnd.android.package-archive");
            it.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION
                    | Intent.FLAG_ACTIVITY_NEW_TASK);
            activity.startActivity(it);
        } catch (Throwable t) {
            Toast.makeText(activity, "打开安装界面失败",
                    Toast.LENGTH_LONG).show();
        }
    }
}
