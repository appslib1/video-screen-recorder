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

        MediaProjectionManager mpm = (MediaProjectionManager)
                getSystemService(Context.MEDIA_PROJECTION_SERVICE);
        launcher.launch(mpm.createScreenCaptureIntent());
    }

    private void onResult(ActivityResult result) {
        if (result.getResultCode() != RESULT_OK || result.getData() == null) {
            finish();
            return;
        }

        SettingsRepository settings = new SettingsRepository(this);
        int countdown = settings.getCountdownSec();

        Intent target = new Intent();
        target.putExtra(RecordingService.EXTRA_RESULT_CODE, result.getResultCode());
        target.putExtra(RecordingService.EXTRA_RESULT_DATA, result.getData());
        target.putExtra(RecordingService.EXTRA_AUDIO_SOURCE, audioSource.name());

        if (countdown > 0) {
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
