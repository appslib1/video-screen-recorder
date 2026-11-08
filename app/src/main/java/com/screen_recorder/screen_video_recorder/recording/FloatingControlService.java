package com.screen_recorder.screen_video_recorder.recording;

import android.annotation.SuppressLint;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.PixelFormat;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.SystemClock;
import android.util.DisplayMetrics;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.Nullable;

import com.screen_recorder.screen_video_recorder.R;

import java.util.Locale;

public class FloatingControlService extends Service {

    private WindowManager wm;
    private View bubble;
    private WindowManager.LayoutParams params;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private long startElapsed;
    private long pauseStartElapsed;
    private long pausedDurationMs;
    private boolean paused;
    private Runnable tick;
    private BroadcastReceiver stateReceiver;

    @Override
    public void onCreate() {
        super.onCreate();
        wm = (WindowManager) getSystemService(Context.WINDOW_SERVICE);
        startElapsed = SystemClock.elapsedRealtime();
        showBubble();
        scheduleTick();
        registerStateReceiver();
    }

    private void registerStateReceiver() {
        stateReceiver = new BroadcastReceiver() {
            @Override public void onReceive(Context context, Intent intent) {
                if (!RecordingService.ACTION_STATE_CHANGED.equals(intent.getAction())) return;
                boolean nextPaused = intent.getBooleanExtra(RecordingService.EXTRA_PAUSED, false);
                applyPausedState(nextPaused);
            }
        };
        IntentFilter f = new IntentFilter(RecordingService.ACTION_STATE_CHANGED);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(stateReceiver, f, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(stateReceiver, f);
        }
    }

    private void applyPausedState(boolean nextPaused) {
        if (nextPaused == paused) return;
        long now = SystemClock.elapsedRealtime();
        if (nextPaused) {
            pauseStartElapsed = now;
        } else if (pauseStartElapsed > 0) {
            pausedDurationMs += now - pauseStartElapsed;
            pauseStartElapsed = 0;
        }
        paused = nextPaused;
        if (bubble != null) {
            ImageView pauseBtn = bubble.findViewById(R.id.bubblePause);
            pauseBtn.setImageResource(paused ? R.drawable.ic_play_small : R.drawable.ic_pause);
            pauseBtn.setContentDescription(getString(
                    paused ? R.string.bubble_resume_cd : R.string.bubble_pause_cd));
        }
    }

    private void showBubble() {
        bubble = LayoutInflater.from(this).inflate(R.layout.overlay_floating_bubble, null);
        View stop = bubble.findViewById(R.id.bubbleStop);
        stop.setOnClickListener(v -> sendAction(RecordingActionReceiver.ACTION_STOP));
        View pause = bubble.findViewById(R.id.bubblePause);
        pause.setOnClickListener(v -> sendAction(
                paused ? RecordingActionReceiver.ACTION_RESUME
                        : RecordingActionReceiver.ACTION_PAUSE));

        params = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                        ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                        : WindowManager.LayoutParams.TYPE_PHONE,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT);
        params.gravity = Gravity.TOP | Gravity.START;

        DisplayMetrics m = getResources().getDisplayMetrics();
        params.x = m.widthPixels - dp(180);
        params.y = dp(120);

        wm.addView(bubble, params);
        attachDrag();
    }

    private void sendAction(String action) {
        Intent broadcast = new Intent(action).setPackage(getPackageName());
        sendBroadcast(broadcast);
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
                        startX = params.x; startY = params.y;
                        downRawX = e.getRawX(); downRawY = e.getRawY();
                        dragged = false;
                        return false;
                    case MotionEvent.ACTION_MOVE:
                        int dx = (int) (e.getRawX() - downRawX);
                        int dy = (int) (e.getRawY() - downRawY);
                        if (Math.abs(dx) > 8 || Math.abs(dy) > 8) dragged = true;
                        params.x = startX + dx;
                        params.y = startY + dy;
                        try { wm.updateViewLayout(bubble, params); } catch (Exception ignored) {}
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
        int targetX = params.x + bubbleW / 2 < screenW / 2 ? dp(8) : screenW - bubbleW - dp(8);
        params.x = targetX;
        try { wm.updateViewLayout(bubble, params); } catch (Exception ignored) {}
    }

    private void scheduleTick() {
        tick = new Runnable() {
            @Override public void run() {
                if (bubble == null) return;
                long nowRef = paused ? pauseStartElapsed : SystemClock.elapsedRealtime();
                long sec = Math.max(0, (nowRef - startElapsed - pausedDurationMs) / 1000);
                TextView time = bubble.findViewById(R.id.bubbleTime);
                time.setText(String.format(Locale.US, "%02d:%02d", sec / 60, sec % 60));
                handler.postDelayed(this, 1000);
            }
        };
        handler.post(tick);
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        handler.removeCallbacksAndMessages(null);
        if (stateReceiver != null) {
            try { unregisterReceiver(stateReceiver); } catch (Exception ignored) {}
            stateReceiver = null;
        }
        if (bubble != null && bubble.isAttachedToWindow()) {
            try { wm.removeView(bubble); } catch (Exception ignored) {}
        }
        bubble = null;
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) { return null; }
}
