package com.screen_recorder.screen_video_recorder.ui;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;

import com.google.android.material.bottomsheet.BottomSheetDialogFragment;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.screen_recorder.screen_video_recorder.R;
import com.screen_recorder.screen_video_recorder.SettingsActivity;
import com.screen_recorder.screen_video_recorder.data.AudioSource;
import com.screen_recorder.screen_video_recorder.data.ControlStyle;
import com.screen_recorder.screen_video_recorder.data.Quality;
import com.screen_recorder.screen_video_recorder.data.SettingsRepository;
import com.screen_recorder.screen_video_recorder.databinding.BottomSheetPreRecordBinding;
import com.screen_recorder.screen_video_recorder.recording.MediaProjectionRequestActivity;

public class PreRecordBottomSheet extends BottomSheetDialogFragment {

    private BottomSheetPreRecordBinding binding;
    private SettingsRepository settings;

    private ActivityResultLauncher<String> micPermLauncher;
    private ActivityResultLauncher<String> notifPermLauncher;
    private ActivityResultLauncher<Intent> overlaySettingsLauncher;

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        micPermLauncher = registerForActivityResult(
                new ActivityResultContracts.RequestPermission(),
                granted -> checkMicThenGo());
        notifPermLauncher = registerForActivityResult(
                new ActivityResultContracts.RequestPermission(),
                granted -> {
                    if (!granted) promptEnableNotifications();
                    else checkMicThenGo();
                });
        // Resumes the record flow automatically after the user returns from
        // the system overlay-permission settings — no need to re-tap Record.
        overlaySettingsLauncher = registerForActivityResult(
                new ActivityResultContracts.StartActivityForResult(),
                r -> checkMicThenGo());
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater,
                             @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        binding = BottomSheetPreRecordBinding.inflate(inflater, container, false);
        return binding.getRoot();
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        settings = new SettingsRepository(requireContext());
        bindSummary();

        binding.settingsShortcut.setOnClickListener(v ->
                startActivity(new Intent(requireContext(), SettingsActivity.class)));
        binding.rowAudio.setOnClickListener(v -> openAudioDialog());
        binding.rowQuality.setOnClickListener(v -> openQualityDialog());
        binding.rowCountdown.setOnClickListener(v -> openCountdownDialog());
        binding.startBtn.setOnClickListener(v -> {
            v.performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY);
            handleStartClicked();
        });
    }

    @Override
    public void onResume() {
        super.onResume();
        if (binding != null && settings != null) bindSummary();
    }

    private void bindSummary() {
        binding.sumAudio.setText(audioLabel(settings.getAudioSource()));
        binding.sumQuality.setText(qualityShortLabel(settings.getQuality()));
        int cd = settings.getCountdownSec();
        binding.sumCountdown.setText(cd == 0
                ? getString(R.string.setting_countdown_off)
                : getString(R.string.pre_countdown_seconds, cd));
    }

    private String audioLabel(AudioSource s) {
        switch (s) {
            case NONE: return getString(R.string.pre_audio_none);
            case INTERNAL: return getString(R.string.pre_audio_internal);
            case BOTH: return getString(R.string.pre_audio_both);
            case MIC:
            default: return getString(R.string.pre_audio_mic);
        }
    }

    private String qualityShortLabel(Quality q) {
        switch (q) {
            case LOW: return "Low · 480p";
            case MEDIUM: return "Medium · 720p";
            case HIGH: return "High · 1080p";
            case MAX:
            default: return "Max";
        }
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
        new MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.setting_audio_title)
                .setSingleChoiceItems(labels, selected, (d, w) -> picked[0] = w)
                .setPositiveButton(android.R.string.ok, (d, w) -> {
                    settings.setAudioSource(options[picked[0]]);
                    bindSummary();
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
        Quality current = settings.getQuality();
        int selected = 2;
        for (int i = 0; i < options.length; i++) if (options[i] == current) selected = i;
        final int[] picked = {selected};
        new MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.setting_quality_title)
                .setSingleChoiceItems(labels, selected, (d, w) -> picked[0] = w)
                .setPositiveButton(android.R.string.ok, (d, w) -> {
                    settings.setQuality(options[picked[0]]);
                    bindSummary();
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
        int current = settings.getCountdownSec();
        int selected = 1;
        for (int i = 0; i < values.length; i++) if (values[i] == current) selected = i;
        final int[] picked = {selected};
        new MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.setting_countdown_title)
                .setSingleChoiceItems(labels, selected, (d, w) -> picked[0] = w)
                .setPositiveButton(android.R.string.ok, (d, w) -> {
                    settings.setCountdownSec(values[picked[0]]);
                    bindSummary();
                })
                .setNegativeButton(R.string.perm_cancel, null)
                .show();
    }

    private void handleStartClicked() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                && ContextCompat.checkSelfPermission(requireContext(),
                        Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            notifPermLauncher.launch(Manifest.permission.POST_NOTIFICATIONS);
            return;
        }
        checkMicThenGo();
    }

    private void checkMicThenGo() {
        AudioSource audio = settings.getAudioSource();
        boolean needsMic = audio != AudioSource.NONE;
        if (needsMic && ContextCompat.checkSelfPermission(requireContext(),
                Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            if (shouldShowRequestPermissionRationale(Manifest.permission.RECORD_AUDIO)) {
                new MaterialAlertDialogBuilder(requireContext())
                        .setTitle(R.string.perm_mic_title)
                        .setMessage(R.string.perm_mic_msg)
                        .setPositiveButton(R.string.perm_grant,
                                (d, w) -> micPermLauncher.launch(Manifest.permission.RECORD_AUDIO))
                        .setNegativeButton(R.string.perm_cancel, null)
                        .show();
            } else {
                micPermLauncher.launch(Manifest.permission.RECORD_AUDIO);
            }
            return;
        }
        boolean needsOverlay = settings.getCountdownSec() > 0
                || settings.getControlStyle() == ControlStyle.OVERLAY;
        if (needsOverlay && !canDrawOverlays()) {
            new MaterialAlertDialogBuilder(requireContext())
                    .setTitle(R.string.perm_overlay_title)
                    .setMessage(R.string.perm_overlay_msg)
                    .setPositiveButton(R.string.perm_overlay_go, (d, w) -> {
                        Intent i = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                Uri.parse("package:" + requireContext().getPackageName()));
                        // Keep the sheet alive so the overlay-result callback
                        // can continue the flow when the user returns.
                        try { overlaySettingsLauncher.launch(i); } catch (Exception ignored) {}
                    })
                    .setNegativeButton(R.string.perm_cancel, (d, w) -> proceedToPermissionActivity())
                    .show();
            return;
        }
        proceedToPermissionActivity();
    }

    private boolean canDrawOverlays() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return true;
        return Settings.canDrawOverlays(requireContext());
    }

    private void promptEnableNotifications() {
        new MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.perm_notif_title)
                .setMessage(R.string.perm_notif_msg)
                .setPositiveButton(R.string.perm_overlay_go, (d, w) -> {
                    Intent i = new Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                            .putExtra(Settings.EXTRA_APP_PACKAGE,
                                    requireContext().getPackageName());
                    try { startActivity(i); } catch (Exception ignored) {}
                    dismissAllowingStateLoss();
                })
                .setNegativeButton(R.string.perm_cancel, (d, w) -> checkMicThenGo())
                .show();
    }

    private void proceedToPermissionActivity() {
        AudioSource audio = settings.getAudioSource();
        Intent i = new Intent(requireContext(), MediaProjectionRequestActivity.class);
        i.putExtra(MediaProjectionRequestActivity.EXTRA_AUDIO_SOURCE, audio.name());
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        startActivity(i);
        dismissAllowingStateLoss();
    }
}
