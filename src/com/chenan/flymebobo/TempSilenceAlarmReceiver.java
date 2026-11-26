package com.chenan.flymebobo;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/**
 * flymebobo v525 — temporary-silence expiry backstop. The
 * AlarmManager alarm TempSilence schedules at start lands
 * here; ending the silence from here turns the system DND
 * back off (when this silence opened it) even if the settings
 * activity is long gone. SystemUI does not depend on this —
 * it expires off the end timestamp in the state itself.
 */
public class TempSilenceAlarmReceiver extends BroadcastReceiver {

    public static final String ACTION_END =
            "com.chenan.flymebobo.TEMP_SILENCE_END";

    @Override
    public void onReceive(Context context, Intent intent) {
        if (context == null || intent == null) {
            return;
        }
        if (ACTION_END.equals(intent.getAction())) {
            TempSilence.end(context, "expired");
        }
    }
}
