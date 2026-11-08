package com.screen_recorder.screen_video_recorder.recording;

import android.annotation.SuppressLint;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.graphics.PixelFormat;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.DisplayMetrics;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;

import com.screen_recorder.screen_video_recorder.MainActivity;
import com.screen_recorder.screen_video_recorder.R;
import com.screen_recorder.screen_video_recorder.data.AudioSource;
import com.screen_recorder.screen_video_recorder.data.SettingsRepository;

/**
 * Persistent, user-visible launcher: lets the user START a recording session
 * from outside the app via a shade notification and/or a draggable overlay
 * bubble. Hides itself transiently while a recording is active so the
 * {@link RecordingService} notification + {@link FloatingControlService}
 * bubble are the only on-screen controls during capture.
 */
public class QuickLauncherService extends Service {

    public static final String ACTION_SYNC = "com.screen_recorder.launcher.SYNC";
    public static final String ACTION_START_RECORDING = "com.screen_recorder.launcher.START_RECORDING";

    private static final String CHANNEL_LAUNCHER = "launcher";
    private static final String CHANNEL_LAUNCHER_SILENT = "launcher_silent";
    private static final int NOTIF_ID = 2101;

    private WindowManager wm;
    private View bubble;
    private WindowManager.LayoutParams bubbleParams;

    private final Handler handler = new Handler(Looper.getMainLooper());

    @Override
    public void onCreate() {
        super.onCreate();
        wm = (WindowManager) getSystemService(Context.WINDOW_SERVICE);
        ensureChannels();
        startAsForeground();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent != null ? intent.getAction() : null;
        if (ACTION_START_RECORDING.equals(action)) {
            launchRecordingFlow();
        } else {
            applyCurrentSettings();
        }
        return START_STICKY;
    }

    private void applyCurrentSettings() {
        SettingsRepository settings = new SettingsRepository(this);
        if (!settings.isQuickLauncherEnabled()) {
            stopSelfSafe();
            return;
        }
        refreshNotification();
        if (settings.isLauncherOverlayEnabled() && canOverlay()) {
            showBubble();
        } else {
            removeBubble();
        }
    }

    private boolean canOverlay() {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.M
                || android.provider.Settings.canDrawOverlays(this);
    }

    private void startAsForeground() {
        Notification n = buildLauncherNotification();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            try {
                startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
            } catch (Exception e) {
                startForeground(NOTIF_ID, n);
            }
        } else {
            startForeground(NOTIF_ID, n);
        }
    }

    private void refreshNotification() {
        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm != null) nm.notify(NOTIF_ID, buildLauncherNotification());
    }

    private Notification buildLauncherNotification() {
        SettingsRepository settings = new SettingsRepository(this);
        boolean showAction = settings.isLauncherNotifEnabled();

        PendingIntent openPi = PendingIntent.getActivity(this, 0,
                new Intent(this, MainActivity.class)
                        .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);

        AudioSource audio = settings.getAudioSource();
        Intent recordIntent = new Intent(this, MediaProjectionRequestActivity.class)
                .putExtra(MediaProjectionRequestActivity.EXTRA_AUDIO_SOURCE, audio.name())
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                        | Intent.FLAG_ACTIVITY_CLEAR_TOP
                        | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent recordPi = PendingIntent.getActivity(this, 1, recordIntent,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);

        String channel = showAction ? CHANNEL_LAUNCHER : CHANNEL_LAUNCHER_SILENT;
        NotificationCompat.Builder b = new NotificationCompat.Builder(this, channel)
                .setContentTitle(getString(R.string.notif_launcher_title))
                .setContentText(getString(R.string.notif_launcher_text))
                .setSmallIcon(R.drawable.ic_record_notif)
                .setColor(0xFFFF4D4F)
                .setPriority(showAction
                        ? NotificationCompat.PRIORITY_DEFAULT
                        : NotificationCompat.PRIORITY_MIN)
                .setCategory(NotificationCompat.CATEGORY_SERVICE)
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                .setOngoing(true)
                .setSilent(true)
                .setShowWhen(false)
                .setContentIntent(openPi);
        if (showAction) {
            b.addAction(R.drawable.ic_record_notif,
                    getString(R.string.notif_launcher_action), recordPi);
        }
        return b.build();
    }

    private void launchRecordingFlow() {
        SettingsRepository settings = new SettingsRepository(this);
        AudioSource audio = settings.getAudioSource();
        Intent i = new Intent(this, MediaProjectionRequestActivity.class)
                .putExtra(MediaProjectionRequestActivity.EXTRA_AUDIO_SOURCE, audio.name())
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                        | Intent.FLAG_ACTIVITY_CLEAR_TOP
                        | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        try { startActivity(i); } catch (Exception ignored) {}
    }

    private void showBubble() {
        if (bubble != null) return;
        bubble = LayoutInflater.from(this).inflate(R.layout.overlay_launcher_bubble, null);
        bubble.setOnClickListener(v -> launchRecordingFlow());

        bubbleParams = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                        ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                        : WindowManager.LayoutParams.TYPE_PHONE,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT);
        bubbleParams.gravity = Gravity.TOP | Gravity.START;

        DisplayMetrics m = getResources().getDisplayMetrics();
        bubbleParams.x = m.widthPixels - dp(72);
        bubbleParams.y = dp(220);

        try {
            wm.addView(bubble, bubbleParams);
            attachDrag();
        } catch (Exception e) {
            bubble = null;
            bubbleParams = null;
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private void attachDrag() {
        bubble.setOnTouchListener(new View.OnTouchListener() {
            int startX, startY;
            float downRawX, downRawY;
            boolean dragged;

            @Override
            public boolean onTouch(View v, MotionEvent e) {
                switch (e.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        startX = bubbleParams.x; startY = bubbleParams.y;
                        downRawX = e.getRawX(); downRawY = e.getRawY();
                        dragged = false;
                        return false;
                    case MotionEvent.ACTION_MOVE:
                        int dx = (int) (e.getRawX() - downRawX);
                        int dy = (int) (e.getRawY() - downRawY);
                        if (Math.abs(dx) > 8 || Math.abs(dy) > 8) dragged = true;
                        bubbleParams.x = startX + dx;
                        bubbleParams.y = startY + dy;
                        try { wm.updateViewLayout(bubble, bubbleParams); } catch (Exception ignored) {}
                        return dragged;
                    case MotionEvent.ACTION_UP:
                        snapToEdge();
                        return dragged;
                }
                return false;
            }
        });
    }

    private void snapToEdge() {
        DisplayMetrics m = getResources().getDisplayMetrics();
        int screenW = m.widthPixels;
        int bubbleW = bubble.getWidth();
        int targetX = bubbleParams.x + bubbleW / 2 < screenW / 2 ? dp(8) : screenW - bubbleW - dp(8);
        bubbleParams.x = targetX;
        try { wm.updateViewLayout(bubble, bubbleParams); } catch (Exception ignored) {}
    }

    private void removeBubble() {
        if (bubble != null) {
            try { wm.removeView(bubble); } catch (Exception ignored) {}
            bubble = null;
            bubbleParams = null;
        }
    }

    private void ensureChannels() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return;
        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm == null) return;
        if (nm.getNotificationChannel(CHANNEL_LAUNCHER) == null) {
            NotificationChannel ch = new NotificationChannel(CHANNEL_LAUNCHER,
                    getString(R.string.notif_channel_launcher),
                    NotificationManager.IMPORTANCE_LOW);
            ch.setSound(null, null);
            ch.enableVibration(false);
            ch.setShowBadge(false);
            nm.createNotificationChannel(ch);
        }
        if (nm.getNotificationChannel(CHANNEL_LAUNCHER_SILENT) == null) {
            NotificationChannel ch = new NotificationChannel(CHANNEL_LAUNCHER_SILENT,
                    getString(R.string.notif_channel_launcher),
                    NotificationManager.IMPORTANCE_MIN);
            ch.setSound(null, null);
            ch.enableVibration(false);
            ch.setShowBadge(false);
            nm.createNotificationChannel(ch);
        }
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    private void stopSelfSafe() {
        try { stopForeground(STOP_FOREGROUND_REMOVE); } catch (Exception ignored) {}
        stopSelf();
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        handler.removeCallbacksAndMessages(null);
        removeBubble();
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) { return null; }
}
