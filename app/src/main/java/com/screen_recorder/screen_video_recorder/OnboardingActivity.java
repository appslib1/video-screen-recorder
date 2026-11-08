package com.screen_recorder.screen_video_recorder;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.view.View;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.activity.EdgeToEdge;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.StringRes;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.color.MaterialColors;
import com.screen_recorder.screen_video_recorder.data.SettingsRepository;
import com.screen_recorder.screen_video_recorder.databinding.ActivityOnboardingBinding;

/**
 * First-run permission setup: three independent cards (Notifications, Mic,
 * Overlay) each with their own Allow / Granted ✓ chip. Modeled after the
 * reference pattern in the power/volume buttons project — the user grants
 * what they want and taps Done, no cascading system prompts on first Record.
 * {@link #onResume} refreshes chip states so coming back from Settings
 * updates the UI immediately.
 */
public class OnboardingActivity extends AppCompatActivity {

    private ActivityOnboardingBinding binding;
    private ActivityResultLauncher<String> notifLauncher;
    private ActivityResultLauncher<String> micLauncher;
    private ActivityResultLauncher<Intent> overlayLauncher;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        EdgeToEdge.enable(this);
        binding = ActivityOnboardingBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        ViewCompat.setOnApplyWindowInsetsListener(binding.getRoot(), (v, insets) -> {
            Insets bars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom);
            return insets;
        });

        notifLauncher = registerForActivityResult(
                new ActivityResultContracts.RequestPermission(), g -> refreshStates());
        micLauncher = registerForActivityResult(
                new ActivityResultContracts.RequestPermission(), g -> refreshStates());
        overlayLauncher = registerForActivityResult(
                new ActivityResultContracts.StartActivityForResult(), r -> refreshStates());

        setupCard(binding.cardNotif.getRoot(), R.drawable.ic_record_notif,
                R.string.onboard_card_notif_title, R.string.onboard_card_notif_desc,
                this::requestNotifications);
        setupCard(binding.cardMic.getRoot(), R.drawable.ic_mic,
                R.string.onboard_card_mic_title, R.string.onboard_card_mic_desc,
                this::requestMic);
        setupCard(binding.cardOverlay.getRoot(), R.drawable.ic_fullscreen,
                R.string.onboard_card_overlay_title, R.string.onboard_card_overlay_desc,
                this::requestOverlay);

        binding.btnStart.setOnClickListener(v -> finishOnboarding());

        refreshStates();
    }

    @Override
    protected void onResume() {
        super.onResume();
        // Returning from Settings (overlay) or a permission dialog — reflect
        // whatever state we're in without waiting for an explicit action.
        refreshStates();
    }

    private void setupCard(View root, int iconRes, @StringRes int titleRes,
                           @StringRes int descRes, Runnable onAction) {
        ((ImageView) root.findViewById(R.id.cardIcon)).setImageResource(iconRes);
        ((TextView) root.findViewById(R.id.cardTitle)).setText(titleRes);
        ((TextView) root.findViewById(R.id.cardDesc)).setText(descRes);
        MaterialButton btn = root.findViewById(R.id.cardAction);
        btn.setOnClickListener(v -> onAction.run());
    }

    private void requestNotifications() {
        if (!needsPostNotifications()) {
            refreshStates();
            return;
        }
        notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS);
    }

    private void requestMic() {
        if (hasMic()) {
            refreshStates();
            return;
        }
        micLauncher.launch(Manifest.permission.RECORD_AUDIO);
    }

    private void requestOverlay() {
        if (hasOverlay()) {
            refreshStates();
            return;
        }
        Intent i = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:" + getPackageName()));
        try { overlayLauncher.launch(i); } catch (Exception ignored) {}
    }

    private void refreshStates() {
        applyChipState(binding.cardNotif.getRoot(), hasNotifications(), R.string.onboard_action_allow);
        applyChipState(binding.cardMic.getRoot(), hasMic(), R.string.onboard_action_allow);
        applyChipState(binding.cardOverlay.getRoot(), hasOverlay(), R.string.onboard_action_enable);
    }

    private void applyChipState(View card, boolean granted, @StringRes int actionLabel) {
        MaterialButton btn = card.findViewById(R.id.cardAction);
        if (granted) {
            btn.setText(R.string.onboard_action_granted);
            btn.setBackgroundTintList(ColorStateList.valueOf(
                    ContextCompat.getColor(this, R.color.onboarding_granted_bg)));
            btn.setTextColor(ContextCompat.getColor(this, R.color.onboarding_granted_fg));
            btn.setIconResource(R.drawable.ic_check);
            btn.setIconTint(ColorStateList.valueOf(
                    ContextCompat.getColor(this, R.color.onboarding_granted_fg)));
            btn.setEnabled(false);
            btn.setAlpha(1f);
        } else {
            int primary = MaterialColors.getColor(btn,
                    com.google.android.material.R.attr.colorPrimary);
            int onPrimary = MaterialColors.getColor(btn,
                    com.google.android.material.R.attr.colorOnPrimary);
            btn.setText(actionLabel);
            btn.setBackgroundTintList(ColorStateList.valueOf(primary));
            btn.setTextColor(onPrimary);
            btn.setIcon(null);
            btn.setEnabled(true);
            btn.setAlpha(1f);
        }
    }

    private boolean needsPostNotifications() {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                && ContextCompat.checkSelfPermission(this,
                        Manifest.permission.POST_NOTIFICATIONS)
                        != PackageManager.PERMISSION_GRANTED;
    }

    private boolean hasNotifications() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return true;
        return ContextCompat.checkSelfPermission(this,
                Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED;
    }

    private boolean hasMic() {
        return ContextCompat.checkSelfPermission(this,
                Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED;
    }

    private boolean hasOverlay() {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.M
                || Settings.canDrawOverlays(this);
    }

    private void finishOnboarding() {
        new SettingsRepository(this).setOnboarded();
        finish();
    }

    @Override
    public void onBackPressed() {
        new SettingsRepository(this).setOnboarded();
        super.onBackPressed();
    }
}
