package com.chenan.flymebobo;

import android.util.Log;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

// fixed35: the module records its own diagnostic log to a file, so the
// user can export it without fighting logcat permissions (the Logcat
// Reader app recorded 0 logs — it never got READ_LOGS). Every process
// the module runs in appends to the same file (SystemUI, the listener
// service, incallui), so each line carries its process name — that is
// also what settles the two-process double-pop theory. /data/local/tmp
// is world-writable, so no storage permission is needed from any of
// these processes (the module manifest does not declare one).
public class FileLog {
    public static final String LOG_PATH = "/data/local/tmp/BubbleNotify.log";
    // fixed50: fallback location — on some devices /data/local/tmp is not
    // writable from SystemUI / the listener service (SELinux), so the log
    // file never appears and the export fails ("日志文件不存在"). Try the
    // sdcard copy too; the export checks both paths.
    public static final String LOG_PATH_ALT = "/sdcard/BubbleNotify.log";
    // fixed51: third location — the module app's own private files dir.
    // Only the listener-service process (com.chenan.flymebobo) can
    // write here; SystemUI fails silently. But the service's "Posted" /
    // "Added" lines are the ones that matter most for double-pop, and
    // MainActivity can always read its own private dir, so the export
    // works even when both shared locations are blocked.
    public static final String LOG_PATH_PRIVATE = "/data/data/com.chenan.flymebobo/files/BubbleNotify.log";
    // v158: fourth location — the app-specific external files dir.
    // Always writable without any storage permission (app-specific
    // external storage is exempt from scoped storage), and any file
    // manager can read it WITHOUT root. This is the path the user
    // should check first. The other three all failed silently on
    // the user's phone (rebooted, v156 running, still no file):
    // /data/local/tmp is SELinux-blocked, /sdcard/ root is scoped-
    // storage-blocked for a fresh package, and the private files/
    // dir never existed (no mkdirs) so the stream threw.
    public static final String LOG_PATH_EXT =
            "/sdcard/Android/data/com.chenan.flymebobo/files/BubbleNotify.log";
    private static final long MAX_BYTES = 2 * 1024 * 1024; // 2 MB, then restart the file
    private static final Object LOCK = new Object();
    private static String cachedProcessName;

    // Mirrors the message to logcat (as before) and appends it to the file.
    public static void i(String msg) {
        try {
            Log.i("flymebobo", msg);
        } catch (Throwable ignored) {
        }
        write(msg);
    }

    private static void write(String msg) {
        synchronized (LOCK) {
            String line = new SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US).format(new Date())
                    + " " + processName() + " " + msg + "\n";
            writeTo(LOG_PATH, line);
            writeTo(LOG_PATH_ALT, line);
            writeTo(LOG_PATH_PRIVATE, line);
            writeTo(LOG_PATH_EXT, line);
        }
    }

    private static void writeTo(String path, String line) {
        try {
            File f = new File(path);
            // v158: create the parent dir first. FileOutputStream
            // does NOT create missing parents — the private files/
            // dir and the app-specific external files/ dir may not
            // exist yet (the user never opened MainActivity), so
            // every write threw FileNotFoundException and was
            // swallowed by the catch below. That is why the log
            // file never appeared even after a reboot.
            File parent = f.getParentFile();
            if (parent != null && !parent.exists()) {
                parent.mkdirs();
            }
            if (f.exists() && f.length() > MAX_BYTES) {
                if (!f.delete()) {
                    return;
                }
            }
            FileOutputStream out = new FileOutputStream(f, true);
            try {
                out.write(line.getBytes("UTF-8"));
            } finally {
                out.close();
            }
        } catch (Throwable ignored) {
            // never crash the module for logging
        }
    }

    private static String processName() {
        if (cachedProcessName != null) {
            return cachedProcessName;
        }
        String name = "?";
        try {
            FileInputStream in = new FileInputStream("/proc/self/cmdline");
            byte[] buf = new byte[256];
            int n = in.read(buf);
            in.close();
            if (n > 0) {
                String s = new String(buf, 0, n, "UTF-8");
                int z = s.indexOf('\0');
                if (z >= 0) {
                    s = s.substring(0, z);
                }
                if (!s.isEmpty()) {
                    name = s;
                }
            }
        } catch (Throwable ignored) {
        }
        cachedProcessName = name;
        return name;
    }
}
