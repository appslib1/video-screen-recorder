package com.screen_recorder.screen_video_recorder.recording;

import android.content.Context;
import android.content.Intent;
import android.media.projection.MediaProjectionManager;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;

import androidx.activity.result.ActivityResult;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.screen_recorder.screen_video_recorder.R;
import com.screen_recorder.screen_video_recorder.data.AudioSource;
import com.screen_recorder.screen_video_recorder.data.SettingsRepository;

public class MediaProjectionRequestActivity extends AppCompatActivity {

    public static final String EXTRA_AUDIO_SOURCE = "audio_source";

    private ActivityResultLauncher<Intent> launcher;
    private AudioSource audioSource = AudioSource.NONE;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (getIntent() != null) {
            audioSource = AudioSource.fromName(
                    getIntent().getStringExtra(EXTRA_AUDIO_SOURCE), AudioSource.NONE);
        }

        launcher = registerForActivityResult(
                new ActivityResultContracts.StartActivityForResult(), this::onResult);

        requestScreenCapture();
    }

    private void requestScreenCapture() {
        MediaProjectionManager mpm = (MediaProjectionManager)
                getSystemService(Context.MEDIA_PROJECTION_SERVICE);
        try {
            launcher.launch(mpm.createScreenCaptureIntent());
        } catch (Exception e) {
            finish();
        }
    }

    private void showRetryDialog() {
        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.projection_retry_title)
                .setMessage(R.string.projection_retry_msg)
                .setCancelable(false)
                .setPositiveButton(R.string.projection_retry_action,
                        (d, w) -> requestScreenCapture())
                .setNegativeButton(R.string.projection_retry_cancel,
                        (d, w) -> finish())
                .show();
    }

    private void onResult(ActivityResult result) {
        if (result.getResultCode() != RESULT_OK || result.getData() == null) {
            showRetryDialog();
            return;
        }

        SettingsRepository settings = new SettingsRepository(this);
        int countdown = settings.getCountdownSec();

        Intent target = new Intent();
        target.putExtra(RecordingService.EXTRA_RESULT_CODE, result.getResultCode());
        target.putExtra(RecordingService.EXTRA_RESULT_DATA, result.getData());
        target.putExtra(RecordingService.EXTRA_AUDIO_SOURCE, audioSource.name());

        // Route through CountdownOverlayService ONLY when we can actually draw
        // the overlay. If overlay permission is missing the countdown service
        // would skip the overlay and relay to RecordingService anyway — but on
        // Android 14+ that extra service hop can invalidate the MediaProjection
        // token (one-shot per grant), surfacing as err_projection_denied. Skip
        // the hop to preserve the token.
        boolean canShowOverlay = Build.VERSION.SDK_INT < Build.VERSION_CODES.M
                || Settings.canDrawOverlays(this);
        if (countdown > 0 && canShowOverlay) {
            Intent countdownIntent = new Intent(this, CountdownOverlayService.class)
                    .putExtra(CountdownOverlayService.EXTRA_SECONDS, countdown)
                    .putExtras(target.getExtras());
            ContextCompat.startForegroundService(this, countdownIntent);
        } else {
            Intent recIntent = new Intent(this, RecordingService.class)
                    .setAction(RecordingService.ACTION_START)
                    .putExtras(target.getExtras());
            ContextCompat.startForegroundService(this, recIntent);
        }
        finish();
    }
}
