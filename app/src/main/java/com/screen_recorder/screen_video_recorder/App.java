package com.screen_recorder.screen_video_recorder;

import android.app.Application;
import androidx.appcompat.app.AppCompatDelegate;
import com.google.android.gms.ads.MobileAds;
import com.google.android.material.color.DynamicColors;
import com.screen_recorder.screen_video_recorder.data.RecordingRepository;
import com.screen_recorder.screen_video_recorder.data.SettingsRepository;
import com.screen_recorder.screen_video_recorder.recording.QuickLauncherController;

public class App extends Application {

    @Override
    public void onCreate() {
        super.onCreate();
        DynamicColors.applyToActivitiesIfAvailable(this);
        SettingsRepository settings = new SettingsRepository(this);
        AppCompatDelegate.setDefaultNightMode(settings.getThemeMode());
        MobileAds.initialize(this, initializationStatus -> {});

        // Finalize or drop any MediaStore entries left pending from a session
        // the OS killed. Off-main because it does a provider query + updates.
        new Thread(() -> new RecordingRepository(this).recoverOrphanPending(),
                "pending-recovery").start();

        QuickLauncherController.sync(this);
    }
}
