package com.screen_recorder.screen_video_recorder.recording;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.graphics.PixelFormat;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.view.ContextThemeWrapper;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.WindowManager;
import android.widget.TextView;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;
import androidx.core.content.ContextCompat;

import com.google.android.material.button.MaterialButton;
import com.screen_recorder.screen_video_recorder.R;

public class CountdownOverlayService extends Service {

    public static final String EXTRA_SECONDS = "seconds";
    public static final String ACTION_CANCEL = "com.screen_recorder.action.CANCEL_COUNTDOWN";
    private static final String CHANNEL_ID = "countdown";
    private static final int NOTIF_ID = 1001;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private WindowManager wm;
    private View overlay;
    private TextView countdownText;
    private int remaining;
    private Intent recordIntent;
    private boolean cancelled;

    @Override
    public void onCreate() {
        super.onCreate();
        wm = (WindowManager) getSystemService(Context.WINDOW_SERVICE);
        ensureChannel();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null) { stopSelf(); return START_NOT_STICKY; }
        if (ACTION_CANCEL.equals(intent.getAction())) {
            cancel();
            return START_NOT_STICKY;
        }
        remaining = intent.getIntExtra(EXTRA_SECONDS, 3);
        recordIntent = new Intent(this, RecordingService.class)
                .setAction(RecordingService.ACTION_START)
                .putExtras(intent.getExtras());
        startInForeground();
        if (!canOverlay()) {
            goStart();
            return START_NOT_STICKY;
        }
        showOverlay();
        tick();
        return START_NOT_STICKY;
    }

    private boolean canOverlay() {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.M
                || android.provider.Settings.canDrawOverlays(this);
    }

    private void showOverlay() {
        Context themed = new ContextThemeWrapper(this, R.style.Theme_ScreenVideoRecorder);
        overlay = LayoutInflater.from(themed).inflate(R.layout.overlay_countdown, null);
        countdownText = overlay.findViewById(R.id.countdownText);
        MaterialButton cancel = overlay.findViewById(R.id.cancelBtn);
        cancel.setOnClickListener(v -> cancel());

        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                        ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                        : WindowManager.LayoutParams.TYPE_PHONE,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.TOP | Gravity.START;
        try {
            wm.addView(overlay, lp);
            overlay.setAlpha(0f);
            overlay.animate().alpha(1f).setDuration(180).start();
        } catch (Exception e) {
            overlay = null;
        }
    }

    private void tick() {
        if (cancelled) return;
        if (remaining <= 0) {
            goStart();
            return;
        }
        if (countdownText != null) {
            countdownText.setText(String.valueOf(remaining));
            countdownText.setScaleX(1.35f);
            countdownText.setScaleY(1.35f);
            countdownText.setAlpha(0f);
            countdownText.animate()
                    .scaleX(1f).scaleY(1f).alpha(1f)
                    .setDuration(260)
                    .start();
        }
        remaining--;
        handler.postDelayed(this::tick, 1000);
    }

    private void goStart() {
        if (overlay != null) {
            overlay.animate().alpha(0f).setDuration(160)
                    .setListener(new AnimatorListenerAdapter() {
                        @Override public void onAnimationEnd(Animator animation) {
                            removeOverlay();
                            launchRecordingAndStop();
                        }
                    }).start();
        } else {
            launchRecordingAndStop();
        }
    }

    private void launchRecordingAndStop() {
        if (recordIntent != null) {
            ContextCompat.startForegroundService(this, recordIntent);
        }
        stopForeground(STOP_FOREGROUND_REMOVE);
        stopSelf();
    }

    private void cancel() {
        cancelled = true;
        handler.removeCallbacksAndMessages(null);
        removeOverlay();
        stopForeground(STOP_FOREGROUND_REMOVE);
        stopSelf();
    }

    private void removeOverlay() {
        if (overlay != null && overlay.isAttachedToWindow()) {
            try { wm.removeView(overlay); } catch (Exception ignored) {}
        }
        overlay = null;
    }

    private void ensureChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return;
        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm.getNotificationChannel(CHANNEL_ID) == null) {
            NotificationChannel ch = new NotificationChannel(CHANNEL_ID,
                    getString(R.string.notif_channel_recording),
                    NotificationManager.IMPORTANCE_LOW);
            ch.setSound(null, null);
            ch.enableVibration(false);
            nm.createNotificationChannel(ch);
        }
    }

    private void startInForeground() {
        Notification n = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle(getString(R.string.notif_countdown_title))
                .setSmallIcon(R.drawable.ic_record_notif)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setOngoing(true)
                .setSilent(true)
                .build();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            try {
                startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
            } catch (Exception e) { startForeground(NOTIF_ID, n); }
        } else {
            startForeground(NOTIF_ID, n);
        }
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        handler.removeCallbacksAndMessages(null);
        removeOverlay();
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) { return null; }
}
