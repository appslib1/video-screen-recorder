package com.screen_recorder.screen_video_recorder.data;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;

import androidx.appcompat.app.AppCompatDelegate;

public class SettingsRepository {

    private static final String PREFS = "screen_recorder_prefs";

    private static final String KEY_AUDIO = "audio_source";
    private static final String KEY_COUNTDOWN = "countdown_sec";
    private static final String KEY_QUALITY = "quality";
    private static final String KEY_SHOW_TOUCHES = "show_touches";
    private static final String KEY_THEME = "theme";
    private static final String KEY_CONTROL_STYLE = "control_style";
    private static final String KEY_ONBOARDED = "onboarded";
    private static final String KEY_LAUNCHER_OVERLAY = "launcher_overlay";
    private static final String KEY_LAUNCHER_NOTIF = "launcher_notif";

    public static final String THEME_SYSTEM = "SYSTEM";
    public static final String THEME_LIGHT = "LIGHT";
    public static final String THEME_DARK = "DARK";

    private final SharedPreferences prefs;

    public SettingsRepository(Context ctx) {
        this.prefs = ctx.getApplicationContext()
                .getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public AudioSource getAudioSource() {
        AudioSource s = AudioSource.fromName(prefs.getString(KEY_AUDIO, AudioSource.MIC.name()),
                AudioSource.MIC);
        // Android < 10 can't capture internal audio. Coerce silently so the
        // pre-record sheet, first-run default and any stale value from a
        // restored backup don't trip the "unsupported" toast on every start.
        if ((s == AudioSource.INTERNAL || s == AudioSource.BOTH)
                && Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            return AudioSource.MIC;
        }
        return s;
    }

    public void setAudioSource(AudioSource s) {
        prefs.edit().putString(KEY_AUDIO, s.name()).apply();
    }

    public int getCountdownSec() {
        return prefs.getInt(KEY_COUNTDOWN, 3);
    }

    public void setCountdownSec(int s) {
        prefs.edit().putInt(KEY_COUNTDOWN, s).apply();
    }

    public Quality getQuality() {
        return Quality.fromName(prefs.getString(KEY_QUALITY, Quality.HIGH.name()),
                Quality.HIGH);
    }

    public void setQuality(Quality q) {
        prefs.edit().putString(KEY_QUALITY, q.name()).apply();
    }

    public boolean getShowTouches() {
        return prefs.getBoolean(KEY_SHOW_TOUCHES, false);
    }

    public void setShowTouches(boolean v) {
        prefs.edit().putBoolean(KEY_SHOW_TOUCHES, v).apply();
    }

    public String getTheme() {
        return prefs.getString(KEY_THEME, THEME_SYSTEM);
    }

    public void setTheme(String t) {
        prefs.edit().putString(KEY_THEME, t).apply();
    }

    public int getThemeMode() {
        switch (getTheme()) {
            case THEME_LIGHT: return AppCompatDelegate.MODE_NIGHT_NO;
            case THEME_DARK: return AppCompatDelegate.MODE_NIGHT_YES;
            default: return AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM;
        }
    }

    public ControlStyle getControlStyle() {
        return ControlStyle.fromName(prefs.getString(KEY_CONTROL_STYLE, ControlStyle.NOTIFICATION.name()),
                ControlStyle.NOTIFICATION);
    }

    public void setControlStyle(ControlStyle s) {
        prefs.edit().putString(KEY_CONTROL_STYLE, s.name()).apply();
    }

    public boolean isOnboarded() {
        return prefs.getBoolean(KEY_ONBOARDED, false);
    }

    public void setOnboarded() {
        prefs.edit().putBoolean(KEY_ONBOARDED, true).apply();
    }

    public boolean isLauncherOverlayEnabled() {
        return prefs.getBoolean(KEY_LAUNCHER_OVERLAY, false);
    }

    public void setLauncherOverlayEnabled(boolean v) {
        prefs.edit().putBoolean(KEY_LAUNCHER_OVERLAY, v).apply();
    }

    public boolean isLauncherNotifEnabled() {
        return prefs.getBoolean(KEY_LAUNCHER_NOTIF, false);
    }

    public void setLauncherNotifEnabled(boolean v) {
        prefs.edit().putBoolean(KEY_LAUNCHER_NOTIF, v).apply();
    }

    public boolean isQuickLauncherEnabled() {
        return isLauncherOverlayEnabled() || isLauncherNotifEnabled();
    }
}
