package com.screen_recorder.screen_video_recorder;

import android.app.Application;
import androidx.appcompat.app.AppCompatDelegate;
import com.google.android.gms.ads.MobileAds;
import com.google.android.gms.ads.RequestConfiguration;
import com.google.android.material.color.DynamicColors;
import com.screen_recorder.screen_video_recorder.data.RecordingRepository;
import com.screen_recorder.screen_video_recorder.data.SettingsRepository;
import com.screen_recorder.screen_video_recorder.recording.QuickLauncherController;

import java.util.Arrays;

public class App extends Application {

    // Device fingerprints that always receive AdMob test ads, even in release.
    // These are SHA hashes of the AAID — they identify a specific phone only,
    // not a user, and are safe to commit. Add new IDs after reading them from
    // logcat: `adb logcat | grep setTestDeviceIds`.
    private static final java.util.List<String> TEST_DEVICE_IDS = Arrays.asList(
            "A9BBF521C645A1C188F66DB26CE08446"
    );

    @Override
    public void onCreate() {
        super.onCreate();
        DynamicColors.applyToActivitiesIfAvailable(this);
        SettingsRepository settings = new SettingsRepository(this);
        AppCompatDelegate.setDefaultNightMode(settings.getThemeMode());

        MobileAds.setRequestConfiguration(
                new RequestConfiguration.Builder()
                        .setTestDeviceIds(TEST_DEVICE_IDS)
                        .build());
        MobileAds.initialize(this, initializationStatus -> {});

        // Finalize or drop any MediaStore entries left pending from a session
        // the OS killed. Off-main because it does a provider query + updates.
        new Thread(() -> new RecordingRepository(this).recoverOrphanPending(),
                "pending-recovery").start();

        QuickLauncherController.sync(this);
    }
}
