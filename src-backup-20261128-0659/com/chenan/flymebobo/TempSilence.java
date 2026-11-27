package com.chenan.flymebobo;

import android.app.AlarmManager;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;

import java.util.Map;

/**
 * flymebobo v525 — temporary silence × system DND, two-way
 * linked (feature backlog #4 + the 2026-11-24 DND-link item,
 * merged by the user 2026-11-26; final design pinned the same
 * morning: queued notifications re-pop in order on restore, and
 * the call breakthrough is a switch, default on, living in this
 * feature's own settings page next to the duration picker and
 * the linkage switch).
 *
 * Behaviour:
 *  - The user starts a timed silence (30 min / 1 h / 2 h) from
 *    TempSilenceActivity. While it runs, ordinary (swipeable)
 *    notifications do not pop — BubbleWindow queues them and
 *    re-delivers them in order when the silence ends (timer or
 *    early cancel). Music / downloads / live activities are
 *    ongoing-class and never queued; calls pop anyway unless
 *    the "静默时来电突破" switch is off.
 *  - Linkage switch on (default): starting a silence also turns
 *    the system Do Not Disturb on (INTERRUPTION_FILTER_PRIORITY)
 *    and the end of the silence turns it back off — but only
 *    when this silence is the one that turned it on (dndByUs +
 *    the filter value we set are recorded, so a DND the user
 *    opened themselves is never closed by us).
 *  - The other direction needs no app process: SystemUI reads
 *    the live interruption filter itself, so a DND opened any
 *    way (quick settings, schedule, another app) degrades
 *    bubbles to the same queue while it lasts.
 *  - If the user turns DND off by hand mid-silence, that counts
 *    as an early cancel: the module listener service watches
 *    ACTION_INTERRUPTION_FILTER_CHANGED and ends the silence.
 *
 * Storage mirrors SceneExceptions: a dedicated prefs file in
 * the APP ("flymebobo_tempsilence"), backed up into the
 * settings file by SettingsStore under the "t." prefix, and
 * the live channel to SystemUI is the ACTION_TEMP_SILENCE
 * broadcast carrying the whole state — never a cross-process
 * prefs re-read for the live path. The state carries the end
 * timestamp, so SystemUI can expire and flush on its own even
 * if the APP process dies; the APP-side clean-up (turning DND
 * back off) is backed by an AlarmManager alarm.
 */
public final class TempSilence {

    public static final String PREFS = "flymebobo_tempsilence";

    public static final String KEY_UNTIL = "until";
    public static final String KEY_MINUTES = "minutes";
    public static final String KEY_LINK_DND = "linkDnd";
    public static final String KEY_CALL_BREAK = "callBreak";
    public static final String KEY_DND_BY_US = "dndByUs";
    public static final String KEY_DND_FILTER = "dndFilter";
    /** v529: who started the current silence — SRC_APP (the
     * in-app button) or SRC_DND (pulled up by the DND linkage).
     * Recorded so the start animation plays exactly once, in
     * the process that owns the road taken. */
    public static final String KEY_SOURCE = "source";

    public static final String SRC_APP = "app";
    public static final String SRC_DND = "dnd";

    public static final String EXTRA_UNTIL = "ts_until";
    public static final String EXTRA_CALL_BREAK = "ts_callBreak";
    public static final String EXTRA_LINK_DND = "ts_linkDnd";
    public static final String EXTRA_SOURCE = "ts_source";
    /** v529: this broadcast is a fresh DND-linkage activation —
     * SystemUI should play the start animation as the receipt. */
    public static final String EXTRA_ANIM = "ts_anim";
    /** Free-form diagnostic note ("start ...", "end ..."),
     * logged by the SystemUI receiver so APP-side events land
     * in the log the user sends (APP-process FileLog lines do
     * not reach it — same lesson as ACTION_CANCEL_DIAG). */
    public static final String EXTRA_NOTE = "ts_note";
    /** v533: on an end broadcast, the machine-readable end
     * reason ("cancel" / "expired" / "dndOff", parsed out of
     * the note by broadcastState). SystemUI routes the exit
     * animation by it: "cancel" is the in-app button, whose
     * receipt plays in the APP; every other road is receipted
     * by SystemUI itself. */
    public static final String EXTRA_END_REASON = "ts_endReason";
    /** v533: how many queued notifications an end-of-silence
     * flush re-delivered — carried by the SystemUI -> APP
     * receipt broadcast (NotificationService
     * .ACTION_TEMP_SILENCE_FLUSHED) so the in-app exit
     * animation can print the real count. */
    public static final String EXTRA_FLUSHED = "ts_flushed";

    public static final int[] DURATIONS = {30, 60, 120};
    public static final int DEFAULT_MINUTES = 60;

    private TempSilence() {
    }

    // ---------- SystemUI-side live state ----------

    public static volatile long untilMs = 0;
    public static volatile boolean callBreak = true;
    public static volatile boolean linkDnd = true;
    /** v529: source of the current activation (SRC_APP /
     * SRC_DND / "" when none), mirrored from the APP. */
    public static volatile String source = "";

    public static boolean isActive() {
        return untilMs > System.currentTimeMillis();
    }

    public static void applyState(long until, boolean cb,
            boolean ld, String src) {
        applyState(until, cb, ld, src, null);
    }

    public static void applyState(long until, boolean cb,
            boolean ld, String src, String srcOfSilence) {
        untilMs = until;
        callBreak = cb;
        linkDnd = ld;
        if (srcOfSilence != null) {
            source = srcOfSilence;
        }
        try {
            FileLog.i("TempSilence apply src=" + src
                    + " until=" + until
                    + " active=" + isActive()
                    + " callBreak=" + cb
                    + " linkDnd=" + ld
                    + " source=" + source);
        } catch (Throwable ignored) {
        }
    }

    /** SystemUI startup / reload: rebuild the live state. The
     * settings file wins over prefs (same order as
     * SceneExceptions.load). */
    public static void load(Context ctx) {
        if (ctx == null) {
            return;
        }
        long until = 0;
        boolean cb = true;
        boolean ld = true;
        String srcOfSilence = "";
        try {
            Context app = ctx.createPackageContext(
                    "com.chenan.flymebobo",
                    Context.CONTEXT_IGNORE_SECURITY);
            SharedPreferences p = app.getSharedPreferences(
                    PREFS, Context.MODE_PRIVATE);
            until = p.getLong(KEY_UNTIL, 0);
            cb = p.getBoolean(KEY_CALL_BREAK, true);
            ld = p.getBoolean(KEY_LINK_DND, true);
            srcOfSilence = p.getString(KEY_SOURCE, "");
        } catch (Throwable ignored) {
        }
        try {
            Map<String, String> file = SettingsStore.readAll();
            String fu = file.get(SettingsStore.PREFIX_TEMPSILENCE
                    + KEY_UNTIL);
            if (fu != null) {
                try {
                    until = Long.parseLong(fu);
                } catch (NumberFormatException ignored) {
                }
            }
            String fc = file.get(SettingsStore.PREFIX_TEMPSILENCE
                    + KEY_CALL_BREAK);
            if (fc != null) {
                cb = "true".equalsIgnoreCase(fc);
            }
            String fl = file.get(SettingsStore.PREFIX_TEMPSILENCE
                    + KEY_LINK_DND);
            if (fl != null) {
                ld = "true".equalsIgnoreCase(fl);
            }
            String fs = file.get(SettingsStore.PREFIX_TEMPSILENCE
                    + KEY_SOURCE);
            if (fs != null) {
                srcOfSilence = fs;
            }
        } catch (Throwable ignored) {
        }
        applyState(until, cb, ld, "load", srcOfSilence);
    }

    /** Is the system Do Not Disturb on right now? Read live
     * from NotificationManager — no APP process involved, so a
     * DND opened any way at all is seen. INTERRUPTION_FILTER_ALL
     * is the only "off" value. */
    public static boolean isSystemDndOn(Context ctx) {
        try {
            if (ctx == null) {
                return false;
            }
            NotificationManager nm = (NotificationManager)
                    ctx.getSystemService(
                            Context.NOTIFICATION_SERVICE);
            if (nm == null) {
                return false;
            }
            return nm.getCurrentInterruptionFilter()
                    != NotificationManager.INTERRUPTION_FILTER_ALL;
        } catch (Throwable ignored) {
            return false;
        }
    }

    /** SystemUI gate (BubbleWindow.onNotification): queue this
     * notification instead of popping it? Ongoing classes
     * (music / download / live) are never queued; calls break
     * through while the call-break switch is on. */
    public static boolean shouldQueue(Context ctx, NotifData d) {
        if (d == null) {
            return false;
        }
        if (d.isMusic || d.isDownload || d.isLive) {
            return false;
        }
        if (d.isCall && callBreak) {
            return false;
        }
        if (isActive()) {
            return true;
        }
        return linkDnd && isSystemDndOn(ctx);
    }

    /** Any suppression in force right now (flush decisions). */
    public static boolean suppressionOn(Context ctx) {
        return isActive() || (linkDnd && isSystemDndOn(ctx));
    }

    // ---------- APP-side stored state ----------

    public static final class State {
        public long until;
        public int minutes = DEFAULT_MINUTES;
        public boolean linkDnd = true;
        public boolean callBreak = true;
        public boolean dndByUs;
        public int dndFilter;
        /** v529: SRC_APP / SRC_DND / "" — see KEY_SOURCE. */
        public String source = "";

        public boolean active() {
            return until > System.currentTimeMillis();
        }
    }

    public static State readState(Context ctx) {
        State s = new State();
        if (ctx == null) {
            return s;
        }
        try {
            SharedPreferences p = ctx.getSharedPreferences(
                    PREFS, Context.MODE_PRIVATE);
            s.until = p.getLong(KEY_UNTIL, 0);
            s.minutes = p.getInt(KEY_MINUTES, DEFAULT_MINUTES);
            s.linkDnd = p.getBoolean(KEY_LINK_DND, true);
            s.callBreak = p.getBoolean(KEY_CALL_BREAK, true);
            s.dndByUs = p.getBoolean(KEY_DND_BY_US, false);
            s.dndFilter = p.getInt(KEY_DND_FILTER, 0);
            s.source = p.getString(KEY_SOURCE, "");
        } catch (Throwable ignored) {
        }
        return s;
    }

    private static void writeState(Context ctx, State s) {
        try {
            SharedPreferences.Editor ed = ctx
                    .getSharedPreferences(PREFS,
                            Context.MODE_PRIVATE)
                    .edit();
            ed.putLong(KEY_UNTIL, s.until);
            ed.putInt(KEY_MINUTES, s.minutes);
            ed.putBoolean(KEY_LINK_DND, s.linkDnd);
            ed.putBoolean(KEY_CALL_BREAK, s.callBreak);
            ed.putBoolean(KEY_DND_BY_US, s.dndByUs);
            ed.putInt(KEY_DND_FILTER, s.dndFilter);
            ed.putString(KEY_SOURCE, s.source);
            // commit, not apply — same rule as AppRules /
            // SceneExceptions (a killed process must not lose it).
            ed.commit();
        } catch (Throwable ignored) {
        }
    }

    /** Persist a settings-page change (duration / linkage /
     * call-break), back it up, and push the live state to
     * SystemUI at once. */
    public static void saveSettings(Context ctx) {
        if (ctx == null) {
            return;
        }
        SettingsStore.saveAll(ctx);
        broadcastState(ctx, null);
    }

    /** Settings page: write the three settings (duration,
     * linkage, call-break) in one go, then back up + push. */
    public static void updateSettings(Context ctx, int minutes,
            boolean linkDnd, boolean callBreak) {
        if (ctx == null) {
            return;
        }
        State s = readState(ctx);
        s.minutes = minutes;
        s.linkDnd = linkDnd;
        s.callBreak = callBreak;
        writeState(ctx, s);
        saveSettings(ctx);
    }

    // ---------- APP-side lifecycle ----------

    /** v529: wall-clock of the latest in-app start (same
     * process as the NotificationService watcher). The filter
     * change our own start() causes must not be mistaken for
     * a DND-linkage activation. */
    private static volatile long lastAppStartMs = 0;
    /** v529: last interruption filter seen by the watcher, for
     * off→on transition detection. -1 = not seen yet. */
    private static volatile int lastFilterSeen = -1;

    /** Start a silence of the given length. Also opens the
     * system DND when the linkage is on and access is granted;
     * a missing grant never blocks the silence itself (the
     * settings page surfaces the grant prompt — no silent
     * failure). */
    public static void start(Context ctx, int minutes) {
        if (ctx == null) {
            return;
        }
        lastAppStartMs = System.currentTimeMillis();
        State s = readState(ctx);
        s.source = SRC_APP;
        s.minutes = minutes;
        s.until = System.currentTimeMillis()
                + minutes * 60_000L;
        s.dndByUs = false;
        s.dndFilter = 0;
        String dndNote = "off";
        if (s.linkDnd) {
            try {
                NotificationManager nm = (NotificationManager)
                        ctx.getSystemService(
                                Context.NOTIFICATION_SERVICE);
                if (nm != null
                        && nm.isNotificationPolicyAccessGranted()) {
                    int cur = nm.getCurrentInterruptionFilter();
                    if (cur == NotificationManager
                            .INTERRUPTION_FILTER_ALL) {
                        nm.setInterruptionFilter(
                                NotificationManager
                                        .INTERRUPTION_FILTER_PRIORITY);
                        s.dndByUs = true;
                        s.dndFilter = NotificationManager
                                .INTERRUPTION_FILTER_PRIORITY;
                        dndNote = "on";
                    } else {
                        // Already on — not ours to close later.
                        dndNote = "already";
                    }
                } else {
                    dndNote = "denied";
                }
            } catch (Throwable ignored) {
                dndNote = "error";
            }
        }
        writeState(ctx, s);
        SettingsStore.saveAll(ctx);
        scheduleAlarm(ctx, s.until);
        broadcastState(ctx, "start minutes=" + minutes
                + " linkDnd=" + s.linkDnd + " dnd=" + dndNote);
    }

    /** End the silence (reason: expired / cancel / dndOff).
     * Idempotent — the alarm, the activity and the DND watcher
     * can all race here; the first one wins, the rest no-op.
     * State is cleared BEFORE the DND is touched, so the
     * filter-change broadcast our own DND-off triggers does
     * not re-enter as a "manual off". */
    public static void end(Context ctx, String reason) {
        if (ctx == null) {
            return;
        }
        State s = readState(ctx);
        boolean wasActive = s.until != 0;
        boolean dndOff = false;
        s.until = 0;
        s.source = "";
        boolean byUs = s.dndByUs;
        int ourFilter = s.dndFilter;
        s.dndByUs = false;
        s.dndFilter = 0;
        writeState(ctx, s);
        cancelAlarm(ctx);
        if (byUs) {
            try {
                NotificationManager nm = (NotificationManager)
                        ctx.getSystemService(
                                Context.NOTIFICATION_SERVICE);
                if (nm != null
                        && nm.isNotificationPolicyAccessGranted()
                        && nm.getCurrentInterruptionFilter()
                                == ourFilter) {
                    nm.setInterruptionFilter(
                            NotificationManager
                                    .INTERRUPTION_FILTER_ALL);
                    dndOff = true;
                }
            } catch (Throwable ignored) {
            }
        }
        SettingsStore.saveAll(ctx);
        broadcastState(ctx, "end reason=" + reason
                + " wasActive=" + wasActive
                + " dndOff=" + dndOff);
    }

    /** APP-side watcher entry (NotificationService registers
     * for ACTION_INTERRUPTION_FILTER_CHANGED): the user turned
     * DND off by hand while a silence that opened it is still
     * running — that is an early cancel. */
    public static void onInterruptionFilterChanged(Context ctx) {
        if (ctx == null) {
            return;
        }
        State s = readState(ctx);
        if (!s.active() || !s.dndByUs) {
            return;
        }
        try {
            NotificationManager nm = (NotificationManager)
                    ctx.getSystemService(
                            Context.NOTIFICATION_SERVICE);
            if (nm != null
                    && nm.getCurrentInterruptionFilter()
                            == NotificationManager
                                    .INTERRUPTION_FILTER_ALL) {
                end(ctx, "dndOff");
            }
        } catch (Throwable ignored) {
        }
    }

    /** APP-side watcher entry for every interruption-filter
     * change broadcast (NotificationService registers it):
     * the off direction keeps its v525 meaning (manual DND-off
     * = early cancel of a silence that opened the DND); the
     * v529 addition watches the on direction — a DND opened
     * any other way pulls the bubble silence up with it when
     * the linkage is on, and that activation gets the same
     * start-animation receipt the in-app button has, played
     * by SystemUI (the APP UI is not on screen then). */
    public static void onFilterBroadcast(Context ctx) {
        onInterruptionFilterChanged(ctx);
        if (ctx == null) {
            return;
        }
        try {
            NotificationManager nm = (NotificationManager)
                    ctx.getSystemService(
                            Context.NOTIFICATION_SERVICE);
            if (nm == null) {
                return;
            }
            int cur = nm.getCurrentInterruptionFilter();
            int prev = lastFilterSeen;
            lastFilterSeen = cur;
            if (prev != NotificationManager.INTERRUPTION_FILTER_ALL
                    || cur == NotificationManager
                            .INTERRUPTION_FILTER_ALL) {
                return;
            }
            // off → on. Our own start() also lands here (it
            // opens the DND itself): the timed state is active
            // then — and freshly stamped — so it never counts
            // as a linkage activation; the APP already plays
            // the animation for that road.
            State s = readState(ctx);
            if (!s.linkDnd || s.active()) {
                return;
            }
            if (System.currentTimeMillis() - lastAppStartMs
                    < 5000) {
                return;
            }
            broadcastDndActivation(ctx);
        } catch (Throwable ignored) {
        }
    }

    /** Tell SystemUI a fresh DND-linkage activation happened
     * (source=dnd, anim=1). The stored until is 0 in this
     * case — the suppression lasts while the DND lasts, and
     * SystemUI already derives it live from the filter; this
     * broadcast only carries the receipt request. */
    public static void broadcastDndActivation(Context ctx) {
        if (ctx == null) {
            return;
        }
        try {
            State s = readState(ctx);
            Intent intent = new Intent(
                    NotificationService.ACTION_TEMP_SILENCE);
            intent.setPackage("com.android.systemui");
            intent.putExtra(EXTRA_UNTIL, s.until);
            intent.putExtra(EXTRA_CALL_BREAK, s.callBreak);
            intent.putExtra(EXTRA_LINK_DND, s.linkDnd);
            intent.putExtra(EXTRA_SOURCE, SRC_DND);
            intent.putExtra(EXTRA_ANIM, true);
            intent.putExtra(EXTRA_NOTE, "dndOn linkAnim");
            ctx.sendBroadcast(intent);
        } catch (Throwable ignored) {
        }
    }

    /** Push the whole stored state to SystemUI. The note (when
     * non-null) is logged by the SystemUI receiver. */
    public static void broadcastState(Context ctx, String note) {
        if (ctx == null) {
            return;
        }
        try {
            State s = readState(ctx);
            Intent intent = new Intent(
                    NotificationService.ACTION_TEMP_SILENCE);
            intent.setPackage("com.android.systemui");
            intent.putExtra(EXTRA_UNTIL, s.until);
            intent.putExtra(EXTRA_CALL_BREAK, s.callBreak);
            intent.putExtra(EXTRA_LINK_DND, s.linkDnd);
            intent.putExtra(EXTRA_SOURCE, s.source);
            if (note != null) {
                intent.putExtra(EXTRA_NOTE, note);
                // v533: surface the end reason as its own
                // extra so the SystemUI receiver can route
                // the exit-animation receipt without parsing
                // the log line at the call site.
                if (note.startsWith("end reason=")) {
                    String r = note.substring(
                            "end reason=".length());
                    int sp = r.indexOf(' ');
                    intent.putExtra(EXTRA_END_REASON,
                            sp >= 0 ? r.substring(0, sp) : r);
                }
            }
            ctx.sendBroadcast(intent);
        } catch (Throwable ignored) {
        }
    }

    // ---------- expiry alarm (APP-side DND clean-up backstop) --

    private static PendingIntent alarmIntent(Context ctx) {
        Intent intent = new Intent(ctx,
                TempSilenceAlarmReceiver.class);
        intent.setAction(TempSilenceAlarmReceiver.ACTION_END);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (android.os.Build.VERSION.SDK_INT >= 31) {
            flags |= PendingIntent.FLAG_IMMUTABLE;
        }
        return PendingIntent.getBroadcast(ctx, 0x7e51, intent,
                flags);
    }

    private static void scheduleAlarm(Context ctx, long until) {
        try {
            AlarmManager am = (AlarmManager) ctx.getSystemService(
                    Context.ALARM_SERVICE);
            if (am != null) {
                am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP,
                        until, alarmIntent(ctx));
            }
        } catch (Throwable ignored) {
        }
    }

    private static void cancelAlarm(Context ctx) {
        try {
            AlarmManager am = (AlarmManager) ctx.getSystemService(
                    Context.ALARM_SERVICE);
            if (am != null) {
                am.cancel(alarmIntent(ctx));
            }
        } catch (Throwable ignored) {
        }
    }
}
