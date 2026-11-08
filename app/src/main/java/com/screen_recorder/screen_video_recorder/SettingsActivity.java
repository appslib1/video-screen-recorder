package com.screen_recorder.screen_video_recorder;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.util.DisplayMetrics;
import android.view.View;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.activity.EdgeToEdge;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.app.AppCompatDelegate;
import androidx.core.content.ContextCompat;

import com.google.android.gms.ads.AdRequest;
import com.google.android.gms.ads.AdSize;
import com.google.android.gms.ads.AdView;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.materialswitch.MaterialSwitch;
import com.screen_recorder.screen_video_recorder.data.AudioSource;
import com.screen_recorder.screen_video_recorder.data.Quality;
import com.screen_recorder.screen_video_recorder.data.SettingsRepository;
import com.screen_recorder.screen_video_recorder.databinding.ActivitySettingsBinding;
import com.screen_recorder.screen_video_recorder.recording.QuickLauncherController;

public class SettingsActivity extends AppCompatActivity {

    private ActivitySettingsBinding binding;
    private SettingsRepository settings;
    private AdView adView;
    private ActivityResultLauncher<String> notifPermLauncher;
    private ActivityResultLauncher<Intent> overlayPermLauncher;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        EdgeToEdge.enable(this);
        binding = ActivitySettingsBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());
        binding.toolbar.setNavigationOnClickListener(v -> finish());

        notifPermLauncher = registerForActivityResult(
                new ActivityResultContracts.RequestPermission(),
                granted -> bindRows());
        overlayPermLauncher = registerForActivityResult(
                new ActivityResultContracts.StartActivityForResult(),
                result -> {
                    if (canDrawOverlays()) {
                        settings.setLauncherOverlayEnabled(true);
                        QuickLauncherController.sync(this);
                    }
                    bindRows();
                });

        settings = new SettingsRepository(this);
        bindRows();

        loadBanner();
    }

    private void loadBanner() {
        if (binding.adViewContainer == null) return;
        adView = new AdView(this);
        adView.setAdUnitId(getString(R.string.banner));
        adView.setAdSize(getAdSize());
        binding.adViewContainer.removeAllViews();
        binding.adViewContainer.addView(adView);
        adView.loadAd(new AdRequest.Builder().build());
    }

    private AdSize getAdSize() {
        DisplayMetrics dm = getResources().getDisplayMetrics();
        int adWidth = (int) (dm.widthPixels / dm.density);
        return AdSize.getCurrentOrientationAnchoredAdaptiveBannerAdSize(this, adWidth);
    }

    @Override
    protected void onPause() {
        if (adView != null) adView.pause();
        super.onPause();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (adView != null) adView.resume();
        if (settings != null) bindRows();
    }

    @Override
    protected void onDestroy() {
        if (adView != null) {
            adView.destroy();
            adView = null;
        }
        super.onDestroy();
    }

    private void bindRows() {
        setupRow(binding.rowAudio.getRoot(), R.drawable.ic_mic,
                getString(R.string.setting_audio_title), audioLabel(settings.getAudioSource()),
                false, false, this::openAudioDialog);

        setupRow(binding.rowCountdown.getRoot(), R.drawable.ic_timer,
                getString(R.string.setting_countdown_title), countdownLabel(settings.getCountdownSec()),
                false, false, this::openCountdownDialog);

        setupRow(binding.rowQuality.getRoot(), R.drawable.ic_hd,
                getString(R.string.setting_quality_title), qualityLabel(settings.getQuality()),
                false, false, this::openQualityDialog);

        setupRow(binding.rowShowTouches.getRoot(), R.drawable.ic_touch,
                getString(R.string.setting_show_touches_title),
                getString(R.string.setting_show_touches_sub),
                true, settings.getShowTouches(), () -> {
                    boolean next = !settings.getShowTouches();
                    settings.setShowTouches(next);
                    MaterialSwitch sw = binding.rowShowTouches.getRoot().findViewById(R.id.toggle);
                    sw.setChecked(next);
                    if (next) {
                        new MaterialAlertDialogBuilder(this)
                                .setMessage(R.string.toast_show_touches_hint)
                                .setPositiveButton(android.R.string.ok, null)
                                .show();
                    }
                });

        setupRow(binding.rowLauncherOverlay.getRoot(), R.drawable.ic_record_notif,
                getString(R.string.setting_launcher_overlay_title),
                getString(R.string.setting_launcher_overlay_sub),
                true, settings.isLauncherOverlayEnabled(), this::onLauncherOverlayToggled);

        setupRow(binding.rowLauncherNotif.getRoot(), R.drawable.ic_record_notif,
                getString(R.string.setting_launcher_notif_title),
                getString(R.string.setting_launcher_notif_sub),
                true, settings.isLauncherNotifEnabled(), this::onLauncherNotifToggled);

        setupRow(binding.rowSaveTo.getRoot(), R.drawable.ic_folder,
                getString(R.string.setting_save_to_title),
                getString(R.string.setting_save_to_default),
                false, false, null);
        binding.rowSaveTo.getRoot().findViewById(R.id.chevron).setVisibility(View.GONE);

        setupRow(binding.rowTheme.getRoot(), R.drawable.ic_palette,
                getString(R.string.setting_theme_title), themeLabel(settings.getTheme()),
                false, false, this::openThemeDialog);

        setupRow(binding.rowVersion.getRoot(), R.drawable.ic_info,
                getString(R.string.setting_version_title), getVersionName(),
                false, false, null);
        binding.rowVersion.getRoot().findViewById(R.id.chevron).setVisibility(View.GONE);
    }

    private String getVersionName() {
        try {
            return getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
        } catch (PackageManager.NameNotFoundException e) {
            return "1.0";
        }
    }

    private interface Action { void run(); }

    private void setupRow(View row, int iconRes, String title, String subtitle,
                          boolean toggle, boolean checked, Action onClick) {
        ((ImageView) row.findViewById(R.id.icon)).setImageResource(iconRes);
        ((TextView) row.findViewById(R.id.title)).setText(title);
        ((TextView) row.findViewById(R.id.subtitle)).setText(subtitle);
        MaterialSwitch sw = row.findViewById(R.id.toggle);
        View chev = row.findViewById(R.id.chevron);
        if (toggle) {
            sw.setVisibility(View.VISIBLE);
            sw.setChecked(checked);
            chev.setVisibility(View.GONE);
        } else {
            sw.setVisibility(View.GONE);
            chev.setVisibility(View.VISIBLE);
        }
        if (onClick != null) row.setOnClickListener(v -> onClick.run());
        else row.setClickable(false);
    }

    private void openAudioDialog() {
        AudioSource[] options = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
                ? new AudioSource[]{AudioSource.NONE, AudioSource.MIC, AudioSource.INTERNAL, AudioSource.BOTH}
                : new AudioSource[]{AudioSource.NONE, AudioSource.MIC};
        String[] labels = new String[options.length];
        int selected = 0;
        AudioSource current = settings.getAudioSource();
        for (int i = 0; i < options.length; i++) {
            labels[i] = audioLabel(options[i]);
            if (options[i] == current) selected = i;
        }
        final int[] picked = {selected};
        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.setting_audio_title)
                .setSingleChoiceItems(labels, selected, (d, w) -> picked[0] = w)
                .setPositiveButton(android.R.string.ok, (d, w) -> {
                    settings.setAudioSource(options[picked[0]]);
                    bindRows();
                })
                .setNegativeButton(R.string.perm_cancel, null)
                .show();
    }

    private void openCountdownDialog() {
        int[] values = {0, 3, 5, 10};
        String[] labels = {
                getString(R.string.setting_countdown_off),
                getString(R.string.setting_countdown_3),
                getString(R.string.setting_countdown_5),
                getString(R.string.setting_countdown_10)
        };
        int cur = settings.getCountdownSec();
        int selected = 1;
        for (int i = 0; i < values.length; i++) if (values[i] == cur) selected = i;
        final int[] picked = {selected};
        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.setting_countdown_title)
                .setSingleChoiceItems(labels, selected, (d, w) -> picked[0] = w)
                .setPositiveButton(android.R.string.ok, (d, w) -> {
                    settings.setCountdownSec(values[picked[0]]);
                    bindRows();
                })
                .setNegativeButton(R.string.perm_cancel, null)
                .show();
    }

    private void openQualityDialog() {
        Quality[] options = {Quality.LOW, Quality.MEDIUM, Quality.HIGH, Quality.MAX};
        String[] labels = {
                getString(R.string.setting_quality_low),
                getString(R.string.setting_quality_medium),
                getString(R.string.setting_quality_high),
                getString(R.string.setting_quality_max)
        };
        Quality cur = settings.getQuality();
        int selected = 2;
        for (int i = 0; i < options.length; i++) if (options[i] == cur) selected = i;
        final int[] picked = {selected};
        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.setting_quality_title)
                .setSingleChoiceItems(labels, selected, (d, w) -> picked[0] = w)
                .setPositiveButton(android.R.string.ok, (d, w) -> {
                    settings.setQuality(options[picked[0]]);
                    bindRows();
                })
                .setNegativeButton(R.string.perm_cancel, null)
                .show();
    }

    private void openThemeDialog() {
        String[] values = {SettingsRepository.THEME_SYSTEM, SettingsRepository.THEME_LIGHT, SettingsRepository.THEME_DARK};
        String[] labels = {
                getString(R.string.setting_theme_system),
                getString(R.string.setting_theme_light),
                getString(R.string.setting_theme_dark)
        };
        String cur = settings.getTheme();
        int selected = 0;
        for (int i = 0; i < values.length; i++) if (values[i].equals(cur)) selected = i;
        final int[] picked = {selected};
        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.setting_theme_title)
                .setSingleChoiceItems(labels, selected, (d, w) -> picked[0] = w)
                .setPositiveButton(android.R.string.ok, (d, w) -> {
                    settings.setTheme(values[picked[0]]);
                    AppCompatDelegate.setDefaultNightMode(settings.getThemeMode());
                    bindRows();
                })
                .setNegativeButton(R.string.perm_cancel, null)
                .show();
    }

    private void onLauncherOverlayToggled() {
        boolean next = !settings.isLauncherOverlayEnabled();
        if (next && !canDrawOverlays()) {
            new MaterialAlertDialogBuilder(this)
                    .setTitle(R.string.perm_overlay_title)
                    .setMessage(R.string.perm_overlay_msg)
                    .setPositiveButton(R.string.perm_overlay_go, (d, w) -> {
                        Intent i = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                Uri.parse("package:" + getPackageName()));
                        overlayPermLauncher.launch(i);
                    })
                    .setNegativeButton(R.string.perm_cancel, null)
                    .show();
            return;
        }
        if (next && !hasNotifPermission()) {
            notifPermLauncher.launch(Manifest.permission.POST_NOTIFICATIONS);
            // Flip the pref now; sync even if the user denies — the overlay still works.
        }
        settings.setLauncherOverlayEnabled(next);
        QuickLauncherController.sync(this);
        bindRows();
    }

    private void onLauncherNotifToggled() {
        boolean next = !settings.isLauncherNotifEnabled();
        if (next && !hasNotifPermission()) {
            notifPermLauncher.launch(Manifest.permission.POST_NOTIFICATIONS);
            // Still flip the pref — if denied the toggle will visually be on
            // but the system suppresses the notification. User can disable later.
        }
        settings.setLauncherNotifEnabled(next);
        QuickLauncherController.sync(this);
        bindRows();
    }

    private boolean canDrawOverlays() {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.M
                || Settings.canDrawOverlays(this);
    }

    private boolean hasNotifPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return true;
        return ContextCompat.checkSelfPermission(this,
                Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED;
    }

    private String audioLabel(AudioSource s) {
        switch (s) {
            case NONE: return getString(R.string.pre_audio_none);
            case MIC: return getString(R.string.pre_audio_mic);
            case INTERNAL: return getString(R.string.pre_audio_internal);
            case BOTH:
            default: return getString(R.string.pre_audio_both);
        }
    }

    private String countdownLabel(int sec) {
        if (sec == 0) return getString(R.string.setting_countdown_off);
        return sec + " s";
    }

    private String qualityLabel(Quality q) {
        switch (q) {
            case LOW: return getString(R.string.setting_quality_low);
            case MEDIUM: return getString(R.string.setting_quality_medium);
            case HIGH: return getString(R.string.setting_quality_high);
            case MAX:
            default: return getString(R.string.setting_quality_max);
        }
    }

    private String themeLabel(String theme) {
        if (SettingsRepository.THEME_LIGHT.equals(theme)) return getString(R.string.setting_theme_light);
        if (SettingsRepository.THEME_DARK.equals(theme)) return getString(R.string.setting_theme_dark);
        return getString(R.string.setting_theme_system);
    }
}
