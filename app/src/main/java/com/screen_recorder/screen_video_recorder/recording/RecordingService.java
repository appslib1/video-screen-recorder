package com.screen_recorder.screen_video_recorder.recording;

import android.annotation.SuppressLint;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.AudioAttributes;
import android.media.AudioFocusRequest;
import android.media.AudioManager;
import android.media.MediaCodecInfo;
import android.media.MediaCodecList;
import android.media.MediaFormat;
import android.media.MediaRecorder;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import android.os.SystemClock;
import android.provider.MediaStore;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.WindowManager;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;

import com.screen_recorder.screen_video_recorder.R;
import com.screen_recorder.screen_video_recorder.data.AudioSource;
import com.screen_recorder.screen_video_recorder.data.ControlStyle;
import com.screen_recorder.screen_video_recorder.data.Quality;
import com.screen_recorder.screen_video_recorder.data.RecordingRepository;
import com.screen_recorder.screen_video_recorder.data.SettingsRepository;
import com.screen_recorder.screen_video_recorder.util.FileNaming;

public class RecordingService extends Service {

    private static final String TAG = "RecordingService";

    public static final String ACTION_START = "com.screen_recorder.action.START";
    public static final String ACTION_STOP = "com.screen_recorder.action.STOP";
    public static final String ACTION_PAUSE = "com.screen_recorder.action.PAUSE";
    public static final String ACTION_RESUME = "com.screen_recorder.action.RESUME";
    public static final String ACTION_STATE_CHANGED = "com.screen_recorder.action.STATE_CHANGED";
    public static final String ACTION_RECORDING_SAVED = "com.screen_recorder.action.SAVED";
    public static final String EXTRA_SAVED_URI = "saved_uri";
    public static final String EXTRA_PAUSED = "paused";

    public static final String EXTRA_RESULT_CODE = "result_code";
    public static final String EXTRA_RESULT_DATA = "result_data";
    public static final String EXTRA_AUDIO_SOURCE = "audio_source";

    private static final String CHANNEL_RECORDING = "recording";
    private static final String CHANNEL_SAVED = "saved";
    private static final int NOTIF_ID = 2001;

    private MediaProjection projection;
    private VirtualDisplay virtualDisplay;
    private MediaRecorder recorder;
    private RecorderPipeline pipeline;
    private Uri outputUri;
    private ParcelFileDescriptor outputPfd;
    private long startElapsed;
    private boolean recording;
    private boolean paused;
    private boolean autoPausedForCall;
    private boolean recordingHasMic;
    private long pauseStartElapsed;
    private long pausedDurationMs;
    private AudioFocusRequest audioFocusRequest;
    private AudioManager.OnAudioFocusChangeListener audioFocusListener;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private Runnable notifTick;

    private int outputWidth;
    private int outputHeight;

    @Override
    public void onCreate() {
        super.onCreate();
        ensureChannels();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null) return START_NOT_STICKY;
        String action = intent.getAction();
        if (ACTION_START.equals(action)) {
            // Do NOT call startForeground() here. Android 14+ throws
            // SecurityException if FGS_TYPE_MEDIA_PROJECTION is claimed before
            // the app has consumed the user's MediaProjection grant via
            // mpm.getMediaProjection(). startRecording() acquires the token
            // first, then enters foreground — well within the 5s window.
            int errRes = startRecording(intent);
            if (errRes != 0) {
                Toast.makeText(this, errRes, Toast.LENGTH_LONG).show();
                stopSelfSafe();
            }
        } else if (ACTION_STOP.equals(action)) {
            // STOP is delivered via startForegroundService() from RecordingActionReceiver.
            // Android requires startForeground() within ~5s of every startForegroundService()
            // call — the START call's startForeground() doesn't count for this one. Satisfy
            // the contract before stopping, or the system throws
            // ForegroundServiceDidNotStartInTimeException.
            try { startForegroundNotification(activeElapsedSec(), recordingHasMic); } catch (Exception ignored) {}
            stopRecording();
            stopSelfSafe();
        } else if (ACTION_PAUSE.equals(action)) {
            try { startForegroundNotification(activeElapsedSec(), recordingHasMic); } catch (Exception ignored) {}
            doPause();
        } else if (ACTION_RESUME.equals(action)) {
            try { startForegroundNotification(activeElapsedSec(), recordingHasMic); } catch (Exception ignored) {}
            doResume();
        }
        return START_NOT_STICKY;
    }

    /**
     * @return 0 on success, otherwise a string-res id to show to the user.
     * Distinguishing reasons keeps users from guessing why Record failed —
     * a user who sees "permission not granted" retries; one who sees the
     * generic old toast uninstalls.
     */
    private int startRecording(Intent intent) {
        int resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, 0);
        Intent resultData = intent.getParcelableExtra(EXTRA_RESULT_DATA);
        AudioSource audio = AudioSource.fromName(intent.getStringExtra(EXTRA_AUDIO_SOURCE), AudioSource.NONE);
        if (resultData == null || resultCode == 0) return R.string.err_projection_denied;

        // Android 14+ (API 34+) enforces that FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
        // can only be claimed AFTER mpm.getMediaProjection() has consumed the
        // user's grant. Fetch the token first, then enter foreground — this
        // whole block runs well within the 5s FGS start window.
        MediaProjectionManager mpm = (MediaProjectionManager) getSystemService(Context.MEDIA_PROJECTION_SERVICE);
        try {
            projection = mpm.getMediaProjection(resultCode, resultData);
        } catch (SecurityException se) {
            Log.w(TAG, "getMediaProjection denied", se);
            return R.string.err_projection_denied;
        }
        if (projection == null) return R.string.err_projection_denied;
        projection.registerCallback(projectionCallback, handler);

        try {
            startForegroundNotification(0, audio != AudioSource.NONE);
        } catch (SecurityException se) {
            // Vendor ROMs occasionally reject FGS_TYPE_MEDIA_PROJECTION even
            // with a valid token (e.g. battery optimizer policies). Nothing
            // to do but surface a message and clean up.
            Log.e(TAG, "startForeground MEDIA_PROJECTION denied", se);
            releaseProjection();
            return R.string.err_projection_denied;
        }

        SettingsRepository settings = new SettingsRepository(this);
        Quality quality = settings.getQuality();

        DisplayMetrics metrics = new DisplayMetrics();
        WindowManager wm = (WindowManager) getSystemService(Context.WINDOW_SERVICE);
        wm.getDefaultDisplay().getRealMetrics(metrics);
        int screenW = metrics.widthPixels;
        int screenH = metrics.heightPixels;
        int screenDpi = metrics.densityDpi;

        int targetW = quality == Quality.MAX ? screenW : quality.width;
        int targetH = quality == Quality.MAX ? screenH : quality.height;
        if (screenH > screenW) {
            int tmp = targetW; targetW = targetH; targetH = tmp;
            if (quality != Quality.MAX) {
                tmp = targetW; targetW = Math.min(targetW, screenW); targetH = Math.min(targetH, screenH);
            }
        }
        if (quality != Quality.MAX) {
            float scale = Math.min(1f, Math.min((float) screenW / targetW, (float) screenH / targetH));
            targetW = Math.round(targetW * scale) & ~1;
            targetH = Math.round(targetH * scale) & ~1;
            if (screenH > screenW && targetW > targetH) {
                int tmp = targetW; targetW = targetH; targetH = tmp;
            }
        }

        int[] fit = fitToCodec(targetW, targetH, quality);
        Quality effectiveQuality = quality;
        if (fit[0] != targetW || fit[1] != targetH) {
            Toast.makeText(this, R.string.toast_quality_adjusted, Toast.LENGTH_SHORT).show();
        }
        targetW = fit[0];
        targetH = fit[1];
        if (fit[2] == 1) {
            effectiveQuality = Quality.MEDIUM;
        } else if (fit[2] == 2) {
            effectiveQuality = Quality.LOW;
        }

        outputWidth = targetW;
        outputHeight = targetH;

        boolean wantsInternal = audio == AudioSource.INTERNAL || audio == AudioSource.BOTH;
        boolean canCaptureInternal = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q;
        if (wantsInternal && !canCaptureInternal) {
            Toast.makeText(this, R.string.toast_internal_audio_unsupported, Toast.LENGTH_LONG).show();
            audio = AudioSource.MIC;
            wantsInternal = false;
        }

        try {
            outputUri = createPendingMediaStoreEntry();
            if (outputUri == null) return R.string.err_storage_full;
            outputPfd = getContentResolver().openFileDescriptor(outputUri, "w");
            if (outputPfd == null) return R.string.err_storage_full;

            boolean pipelineStarted = false;
            if (wantsInternal) {
                try {
                    pipeline = new RecorderPipeline(audio);
                    pipeline.start(outputPfd.getFileDescriptor(), targetW, targetH, screenDpi,
                            effectiveQuality, projection, handler);
                    pipelineStarted = true;
                } catch (Exception e) {
                    // Xiaomi / Oppo / Realme sometimes block AudioPlaybackCapture
                    // via vendor DRM policies and throw on AudioRecord build or
                    // first read. Fall back to the MediaRecorder (mic) path so
                    // the user still gets a video with voice.
                    Log.w(TAG, "internal audio capture blocked, falling back to mic", e);
                    if (pipeline != null) {
                        try { pipeline.stop(); } catch (Exception ignored) {}
                        pipeline = null;
                    }
                    Toast.makeText(this, R.string.toast_internal_audio_blocked,
                            Toast.LENGTH_LONG).show();
                    audio = AudioSource.MIC;
                }
            }
            if (!pipelineStarted) {
                boolean withMic = audio == AudioSource.MIC || audio == AudioSource.BOTH;
                recorder = buildRecorder(withMic, targetW, targetH, effectiveQuality);
                recorder.prepare();

                virtualDisplay = projection.createVirtualDisplay(
                        "ScreenRecorder",
                        targetW, targetH, screenDpi,
                        DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                        recorder.getSurface(),
                        null, handler);

                recorder.start();
            }
            startElapsed = SystemClock.elapsedRealtime();
            recording = true;
            recordingHasMic = audio == AudioSource.MIC || audio == AudioSource.BOTH;
            scheduleNotifTick();
            maybeStartFloatingBubble(settings);
            requestCallAwareness();
            QuickLauncherController.notifyRecordingStarted(this);

            return 0;
        } catch (Exception e) {
            Log.e(TAG, "startRecording failed", e);
            if (pipeline != null) {
                try { pipeline.stop(); } catch (Exception ignored) {}
                pipeline = null;
            }
            cleanupOutput();
            releaseProjection();
            // Distinguish codec/prepare errors from generic failures so users
            // see an actionable hint instead of the opaque "could not start".
            String msg = e.getMessage();
            if (e instanceof IllegalStateException
                    || (msg != null && (msg.contains("codec") || msg.contains("MediaCodec")
                            || msg.contains("MediaRecorder") || msg.contains("configure")))) {
                return R.string.err_codec_unavailable;
            }
            return R.string.err_generic;
        }
    }

    @SuppressLint("WrongConstant")
    private MediaRecorder buildRecorder(boolean withMic, int width, int height, Quality quality) {
        MediaRecorder r = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
                ? new MediaRecorder(this)
                : new MediaRecorder();
        if (withMic) r.setAudioSource(MediaRecorder.AudioSource.CAMCORDER);
        r.setVideoSource(MediaRecorder.VideoSource.SURFACE);
        r.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4);
        if (withMic) {
            r.setAudioEncoder(MediaRecorder.AudioEncoder.AAC);
            r.setAudioEncodingBitRate(128_000);
            r.setAudioSamplingRate(44_100);
        }
        r.setVideoEncoder(MediaRecorder.VideoEncoder.H264);
        r.setVideoEncodingBitRate(quality.bitrate);
        r.setVideoFrameRate(quality.fps);
        r.setVideoSize(width, height);
        r.setOutputFile(outputPfd.getFileDescriptor());
        return r;
    }

    private Uri createPendingMediaStoreEntry() {
        String name = FileNaming.newRecordingName();
        ContentValues values = new ContentValues();
        values.put(MediaStore.Video.Media.DISPLAY_NAME, name);
        values.put(MediaStore.Video.Media.MIME_TYPE, "video/mp4");
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            values.put(MediaStore.Video.Media.RELATIVE_PATH, RecordingRepository.RELATIVE_PATH_PRIMARY);
            values.put(MediaStore.Video.Media.IS_PENDING, 1);
        }
        Uri collection = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
                ? MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
                : MediaStore.Video.Media.EXTERNAL_CONTENT_URI;
        return getContentResolver().insert(collection, values);
    }

    private void stopRecording() {
        if (!recording) return;
        recording = false;
        abandonCallAwareness();
        if (paused && pauseStartElapsed > 0) {
            pausedDurationMs += SystemClock.elapsedRealtime() - pauseStartElapsed;
            pauseStartElapsed = 0;
        }
        paused = false;
        handler.removeCallbacks(notifTick);
        long durationMs = Math.max(0,
                SystemClock.elapsedRealtime() - startElapsed - pausedDurationMs);
        if (pipeline != null) {
            try { pipeline.stop(); } catch (Exception ignored) {}
            pipeline = null;
        }
        try {
            if (recorder != null) {
                recorder.stop();
                recorder.reset();
                recorder.release();
            }
        } catch (Exception ignored) {}
        recorder = null;
        if (virtualDisplay != null) {
            try { virtualDisplay.release(); } catch (Exception ignored) {}
            virtualDisplay = null;
        }
        releaseProjection();
        try { if (outputPfd != null) outputPfd.close(); } catch (Exception ignored) {}
        outputPfd = null;

        if (outputUri != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ContentValues v = new ContentValues();
            v.put(MediaStore.Video.Media.IS_PENDING, 0);
            try { getContentResolver().update(outputUri, v, null, null); } catch (Exception ignored) {}
        }

        stopService(new Intent(this, FloatingControlService.class));

        if (outputUri != null) {
            postSavedNotification(durationMs);
            Intent b = new Intent(ACTION_RECORDING_SAVED)
                    .putExtra(EXTRA_SAVED_URI, outputUri)
                    .setPackage(getPackageName());
            sendBroadcast(b);
        }

        QuickLauncherController.notifyRecordingStopped(this);
    }

    private final MediaProjection.Callback projectionCallback = new MediaProjection.Callback() {
        @Override public void onStop() {
            if (recording) stopRecording();
        }
    };

    private void releaseProjection() {
        if (projection != null) {
            try { projection.unregisterCallback(projectionCallback); } catch (Exception ignored) {}
            try { projection.stop(); } catch (Exception ignored) {}
            projection = null;
        }
    }

    private void cleanupOutput() {
        try { if (outputPfd != null) outputPfd.close(); } catch (Exception ignored) {}
        outputPfd = null;
        if (outputUri != null) {
            try { getContentResolver().delete(outputUri, null, null); } catch (Exception ignored) {}
            outputUri = null;
        }
    }

    private void startForegroundNotification(long elapsedSec, boolean withMic) {
        Notification n = buildRecordingNotification(elapsedSec);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            int type = ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION;
            if (withMic && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                type |= ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE;
            }
            // Must pass a non-zero type — the manifest declares
            // mediaProjection|microphone, so the untyped overload throws
            // IllegalArgumentException on API 29+. Let SecurityException
            // propagate so callers (which wrap this in try/catch) can
            // clean up and surface err_projection_denied.
            startForeground(NOTIF_ID, n, type);
        } else {
            startForeground(NOTIF_ID, n);
        }
    }

    private Notification buildRecordingNotification(long elapsedSec) {
        long m = elapsedSec / 60, s = elapsedSec % 60;
        String elapsed = String.format(java.util.Locale.US, "%02d:%02d", m, s);

        Intent stopIntent = new Intent(this, RecordingActionReceiver.class)
                .setAction(RecordingActionReceiver.ACTION_STOP);
        PendingIntent stopPi = PendingIntent.getBroadcast(this, 0, stopIntent,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);

        Intent toggleIntent = new Intent(this, RecordingActionReceiver.class)
                .setAction(paused ? RecordingActionReceiver.ACTION_RESUME
                                  : RecordingActionReceiver.ACTION_PAUSE);
        PendingIntent togglePi = PendingIntent.getBroadcast(this, 1, toggleIntent,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);

        Intent open = new Intent(this, com.screen_recorder.screen_video_recorder.MainActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent openPi = PendingIntent.getActivity(this, 0, open,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);

        long whenStarted = System.currentTimeMillis() - elapsedSec * 1000L;

        String title = (paused ? "⏸ " : "") + getString(R.string.notif_recording_title)
                + "  •  " + elapsed;
        int toggleIcon = paused ? R.drawable.ic_play_small : R.drawable.ic_pause;
        String toggleLabel = getString(paused ? R.string.notif_resume : R.string.notif_pause);

        NotificationCompat.Builder b = new NotificationCompat.Builder(this, CHANNEL_RECORDING)
                .setContentTitle(title)
                .setContentText(getString(R.string.notif_recording_subtext))
                .setSubText(getString(R.string.notif_recording_subtext))
                .setSmallIcon(R.drawable.ic_record_notif)
                .setColor(0xFFFF4D4F)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setCategory(NotificationCompat.CATEGORY_SERVICE)
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setSilent(true)
                .setShowWhen(!paused)
                .setContentIntent(openPi)
                .addAction(toggleIcon, toggleLabel, togglePi)
                .addAction(R.drawable.ic_stop, getString(R.string.notif_stop), stopPi);
        if (!paused) {
            b.setUsesChronometer(true).setWhen(whenStarted);
        }
        return b.build();
    }

    private void scheduleNotifTick() {
        handler.removeCallbacks(notifTick);
        notifTick = new Runnable() {
            @Override public void run() {
                if (!recording || paused) return;
                NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
                nm.notify(NOTIF_ID, buildRecordingNotification(activeElapsedSec()));
                handler.postDelayed(this, 1000);
            }
        };
        handler.postDelayed(notifTick, 1000);
    }

    private void postSavedNotification(long durationMs) {
        long sec = durationMs / 1000;
        String dur = String.format(java.util.Locale.US, "%02d:%02d", sec / 60, sec % 60);
        Intent open = new Intent(Intent.ACTION_VIEW)
                .setDataAndType(outputUri, "video/mp4")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        PendingIntent pi = PendingIntent.getActivity(this, 0, open,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        Notification n = new NotificationCompat.Builder(this, CHANNEL_SAVED)
                .setContentTitle(getString(R.string.notif_saved_title))
                .setContentText(getString(R.string.notif_saved_text, dur, outputWidth + "×" + outputHeight))
                .setSmallIcon(R.drawable.ic_check)
                .setContentIntent(pi)
                .setAutoCancel(true)
                .build();
        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        nm.notify((int) System.currentTimeMillis(), n);
    }

    private void ensureChannels() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return;
        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        NotificationChannel existing = nm.getNotificationChannel(CHANNEL_RECORDING);
        if (existing != null && existing.getImportance() < NotificationManager.IMPORTANCE_DEFAULT) {
            nm.deleteNotificationChannel(CHANNEL_RECORDING);
            existing = null;
        }
        if (existing == null) {
            NotificationChannel ch = new NotificationChannel(CHANNEL_RECORDING,
                    getString(R.string.notif_channel_recording),
                    NotificationManager.IMPORTANCE_DEFAULT);
            ch.setSound(null, null);
            ch.enableVibration(false);
            ch.setShowBadge(true);
            nm.createNotificationChannel(ch);
        }
        if (nm.getNotificationChannel(CHANNEL_SAVED) == null) {
            NotificationChannel ch2 = new NotificationChannel(CHANNEL_SAVED,
                    getString(R.string.notif_channel_saved),
                    NotificationManager.IMPORTANCE_DEFAULT);
            nm.createNotificationChannel(ch2);
        }
    }

    /**
     * Walks down (res, bitrate, fps) combinations until one is supported by an
     * on-device H.264 encoder. Returns {w, h, downgradeLevel} where level 0 =
     * no downgrade, 1 = fell to MEDIUM, 2 = fell to LOW. Prevents silent
     * configure() crashes on low-end devices picking Max/High beyond caps.
     */
    private static int[] fitToCodec(int w, int h, Quality q) {
        int[][] tries;
        switch (q) {
            case MAX:
            case HIGH:
                tries = new int[][]{{w, h, q.bitrate, q.fps, 0},
                        {1280, 720, Quality.MEDIUM.bitrate, Quality.MEDIUM.fps, 1},
                        {854, 480, Quality.LOW.bitrate, Quality.LOW.fps, 2}};
                break;
            case MEDIUM:
                tries = new int[][]{{w, h, q.bitrate, q.fps, 0},
                        {854, 480, Quality.LOW.bitrate, Quality.LOW.fps, 2}};
                break;
            default:
                tries = new int[][]{{w, h, q.bitrate, q.fps, 0}};
        }
        for (int[] t : tries) {
            int cw = t[0] & ~1, ch = t[1] & ~1;
            if (codecSupports(cw, ch, t[2], t[3])) return new int[]{cw, ch, t[4]};
            // Try swapped (portrait vs landscape may matter per codec).
            if (codecSupports(ch, cw, t[2], t[3])) return new int[]{cw, ch, t[4]};
        }
        return new int[]{854, 480, 2};
    }

    private static boolean codecSupports(int w, int h, int bitrate, int fps) {
        try {
            MediaFormat f = MediaFormat.createVideoFormat("video/avc", w, h);
            f.setInteger(MediaFormat.KEY_COLOR_FORMAT,
                    MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface);
            f.setInteger(MediaFormat.KEY_BIT_RATE, bitrate);
            f.setInteger(MediaFormat.KEY_FRAME_RATE, fps);
            f.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 2);
            MediaCodecList list = new MediaCodecList(MediaCodecList.REGULAR_CODECS);
            String name = list.findEncoderForFormat(f);
            return name != null;
        } catch (Exception e) {
            return false;
        }
    }

    private long activeElapsedSec() {
        if (!recording) return 0;
        long nowRef = paused ? pauseStartElapsed : SystemClock.elapsedRealtime();
        return Math.max(0, (nowRef - startElapsed - pausedDurationMs) / 1000);
    }

    private void doPause() {
        if (!recording || paused) return;
        try {
            if (pipeline != null) pipeline.pause();
            else if (recorder != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) recorder.pause();
        } catch (Exception e) {
            Log.w(TAG, "pause failed", e);
            return;
        }
        paused = true;
        pauseStartElapsed = SystemClock.elapsedRealtime();
        handler.removeCallbacks(notifTick);
        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        nm.notify(NOTIF_ID, buildRecordingNotification(activeElapsedSec()));
        broadcastState();
    }

    private void doResume() {
        if (!recording || !paused) return;
        if (pauseStartElapsed > 0) {
            pausedDurationMs += SystemClock.elapsedRealtime() - pauseStartElapsed;
        }
        pauseStartElapsed = 0;
        try {
            if (pipeline != null) pipeline.resume();
            else if (recorder != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) recorder.resume();
        } catch (Exception e) {
            Log.w(TAG, "resume failed", e);
            return;
        }
        paused = false;
        scheduleNotifTick();
        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        nm.notify(NOTIF_ID, buildRecordingNotification(activeElapsedSec()));
        broadcastState();
    }

    /**
     * Audio-focus-based call awareness. When a call starts the system fires
     * AUDIOFOCUS_LOSS_TRANSIENT; we auto-pause so the user doesn't lose the
     * session. On GAIN we auto-resume only if the pause was triggered by us.
     * This is permission-free (no READ_PHONE_STATE needed).
     *
     * Only requested when the mic is in use — otherwise taking focus would
     * needlessly pause the user's background music while they record
     * silent/internal-audio sessions. With MIC active, the system already
     * suspends music during capture, so there's no extra disruption.
     */
    private void requestCallAwareness() {
        if (!recordingHasMic) return;
        AudioManager am = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
        if (am == null) return;
        audioFocusListener = focusChange -> {
            switch (focusChange) {
                case AudioManager.AUDIOFOCUS_LOSS:
                case AudioManager.AUDIOFOCUS_LOSS_TRANSIENT:
                case AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK:
                    if (recording && !paused) {
                        autoPausedForCall = true;
                        doPause();
                    }
                    break;
                case AudioManager.AUDIOFOCUS_GAIN:
                    if (recording && paused && autoPausedForCall) {
                        autoPausedForCall = false;
                        doResume();
                    }
                    break;
            }
        };
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            AudioAttributes attrs = new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_UNKNOWN)
                    .build();
            audioFocusRequest = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                    .setAudioAttributes(attrs)
                    .setOnAudioFocusChangeListener(audioFocusListener, handler)
                    .setWillPauseWhenDucked(true)
                    .build();
            try { am.requestAudioFocus(audioFocusRequest); } catch (Exception ignored) {}
        } else {
            try {
                am.requestAudioFocus(audioFocusListener,
                        AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN);
            } catch (Exception ignored) {}
        }
    }

    private void abandonCallAwareness() {
        AudioManager am = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
        if (am == null) return;
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && audioFocusRequest != null) {
                am.abandonAudioFocusRequest(audioFocusRequest);
            } else if (audioFocusListener != null) {
                am.abandonAudioFocus(audioFocusListener);
            }
        } catch (Exception ignored) {}
        audioFocusRequest = null;
        audioFocusListener = null;
        autoPausedForCall = false;
    }

    private void broadcastState() {
        Intent b = new Intent(ACTION_STATE_CHANGED)
                .putExtra(EXTRA_PAUSED, paused)
                .setPackage(getPackageName());
        sendBroadcast(b);
    }

    private void maybeStartFloatingBubble(SettingsRepository settings) {
        if (settings.getControlStyle() != ControlStyle.OVERLAY) return;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M
                && !android.provider.Settings.canDrawOverlays(this)) return;
        try {
            startService(new Intent(this, FloatingControlService.class));
        } catch (Exception ignored) {}
    }

    private void stopSelfSafe() {
        try { stopForeground(STOP_FOREGROUND_REMOVE); } catch (Exception ignored) {}
        stopSelf();
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        if (recording) stopRecording();
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) { return null; }
}
