package com.screen_recorder.screen_video_recorder.recording;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

import androidx.core.content.ContextCompat;

public class RecordingActionReceiver extends BroadcastReceiver {

    public static final String ACTION_STOP = "com.screen_recorder.action.STOP";
    public static final String ACTION_PAUSE = "com.screen_recorder.action.PAUSE";
    public static final String ACTION_RESUME = "com.screen_recorder.action.RESUME";

    @Override
    public void onReceive(Context context, Intent intent) {
        String a = intent.getAction();
        if (a == null) return;
        String serviceAction;
        switch (a) {
            case ACTION_STOP: serviceAction = RecordingService.ACTION_STOP; break;
            case ACTION_PAUSE: serviceAction = RecordingService.ACTION_PAUSE; break;
            case ACTION_RESUME: serviceAction = RecordingService.ACTION_RESUME; break;
            default: return;
        }
        Intent svc = new Intent(context, RecordingService.class).setAction(serviceAction);
        ContextCompat.startForegroundService(context, svc);
    }
}
