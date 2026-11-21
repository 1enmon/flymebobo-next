package com.chenan.flymebobo;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * Feature switches for the module APP (v259).
 *
 * The APP (MainActivity) stores each switch in its own
 * SharedPreferences ("flymebobo_features") and broadcasts
 * ACTION_FEATURE_TOGGLE when one flips. This class lives in the
 * SystemUI process: it holds the live flags, reads the initial
 * state from the APP's prefs on start, and updates a flag when a
 * toggle broadcast arrives. BubbleWindow.popCapsule checks these
 * flags before popping anything, so a flipped switch takes effect
 * immediately with no SystemUI restart.
 *
 * All flags default to true: if the prefs cannot be read, every
 * feature keeps working exactly as before.
 */
public class FeatureFlags {

    public static volatile boolean notif = true;
    public static volatile boolean music = true;
    public static volatile boolean dl = true;
    public static volatile boolean code = true;
    public static volatile boolean live = true;
    public static volatile boolean card = true;
    public static volatile boolean scroll = true;
    public static volatile boolean miniwindow = true;
    public static volatile boolean blankDissipate = true;
    public static volatile boolean musicCard = true;

    public static void set(String key, boolean enabled) {
        if (key == null) {
            return;
        }
        if ("notif".equals(key)) {
            notif = enabled;
        } else if ("music".equals(key)) {
            music = enabled;
        } else if ("dl".equals(key)) {
            dl = enabled;
        } else if ("code".equals(key)) {
            code = enabled;
        } else if ("live".equals(key)) {
            live = enabled;
        } else if ("card".equals(key)) {
            card = enabled;
        } else if ("scroll".equals(key)) {
            scroll = enabled;
        } else if ("miniwindow".equals(key)) {
            miniwindow = enabled;
        } else if ("blankDissipate".equals(key)) {
            blankDissipate = enabled;
        } else if ("musicCard".equals(key)) {
            musicCard = enabled;
        }
    }

    /** Read the APP's saved switches (best effort; on any failure
     * the flags stay at their defaults, i.e. everything on). */
    public static void load(Context systemUiContext) {
        try {
            Context app = systemUiContext.createPackageContext(
                    "com.chenan.flymebobo",
                    Context.CONTEXT_IGNORE_SECURITY);
            SharedPreferences prefs = app.getSharedPreferences(
                    "flymebobo_features", Context.MODE_PRIVATE);
            notif = prefs.getBoolean("notif", true);
            music = prefs.getBoolean("music", true);
            dl = prefs.getBoolean("dl", true);
            code = prefs.getBoolean("code", true);
            live = prefs.getBoolean("live", true);
            card = prefs.getBoolean("card", true);
            scroll = prefs.getBoolean("scroll", true);
            miniwindow = prefs.getBoolean("miniwindow", true);
            blankDissipate = prefs.getBoolean("blankDissipate", true);
            musicCard = prefs.getBoolean("musicCard", true);
        } catch (Throwable ignored) {
            // Defaults (all on) stand.
        }
    }
}
