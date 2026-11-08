package com.screen_recorder.screen_video_recorder.recording;

import android.content.Context;
import android.content.Intent;

import androidx.core.content.ContextCompat;

import com.screen_recorder.screen_video_recorder.data.SettingsRepository;

/**
 * Thin façade over {@link QuickLauncherService} so callers (Settings, App,
 * BootReceiver, RecordingService) don't need to know the service's actions.
 */
public final class QuickLauncherController {

    private QuickLauncherController() {}

    /**
     * Starts or stops the launcher service to match current preferences.
     * Safe to call repeatedly — the service handles its own state.
     */
    public static void sync(Context ctx) {
        SettingsRepository settings = new SettingsRepository(ctx);
        if (settings.isQuickLauncherEnabled()) {
            Intent i = new Intent(ctx, QuickLauncherService.class)
                    .setAction(QuickLauncherService.ACTION_SYNC);
            try { ContextCompat.startForegroundService(ctx, i); } catch (Exception ignored) {}
        } else {
            ctx.stopService(new Intent(ctx, QuickLauncherService.class));
        }
    }

    /**
     * Called when a recording session actually begins. Fully stops the
     * launcher — a foreground service always carries a notification, so to
     * avoid showing two shade entries during capture we drop it entirely and
     * restart on {@link #notifyRecordingStopped}.
     */
    public static void notifyRecordingStarted(Context ctx) {
        try { ctx.stopService(new Intent(ctx, QuickLauncherService.class)); } catch (Exception ignored) {}
    }

    /** Called after a recording session ends. */
    public static void notifyRecordingStopped(Context ctx) {
        sync(ctx);
    }
}
