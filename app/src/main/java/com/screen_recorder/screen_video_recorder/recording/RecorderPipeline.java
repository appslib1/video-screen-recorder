package com.screen_recorder.screen_video_recorder.recording;

import android.annotation.SuppressLint;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioPlaybackCaptureConfiguration;
import android.media.AudioRecord;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import android.media.MediaMuxer;
import android.media.MediaRecorder;
import android.media.projection.MediaProjection;
import android.os.Build;
import android.os.Handler;
import android.util.Log;
import android.view.Surface;

import androidx.annotation.RequiresApi;

import com.screen_recorder.screen_video_recorder.data.AudioSource;
import com.screen_recorder.screen_video_recorder.data.Quality;

import java.io.FileDescriptor;
import java.io.IOException;
import java.nio.ByteBuffer;

@RequiresApi(Build.VERSION_CODES.Q)
class RecorderPipeline {

    private static final String TAG = "RecorderPipeline";

    private static final String VIDEO_MIME = "video/avc";
    private static final String AUDIO_MIME = "audio/mp4a-latm";
    private static final int AUDIO_SAMPLE_RATE = 44_100;
    private static final int AUDIO_CHANNEL_COUNT = 1;
    private static final int AUDIO_BITRATE = 128_000;
    private static final int AUDIO_FRAMES_PER_READ = 1024;
    private static final int I_FRAME_INTERVAL_SEC = 2;
    private static final int DEQUEUE_TIMEOUT_US = 10_000;

    private final boolean captureInternal;
    private final boolean captureMic;

    private MediaCodec videoEncoder;
    private MediaCodec audioEncoder;
    private Surface inputSurface;
    private VirtualDisplay virtualDisplay;
    private MediaMuxer muxer;
    private AudioRecord micRecord;
    private AudioRecord playbackRecord;

    private int videoTrack = -1;
    private int audioTrack = -1;
    private boolean muxerStarted;
    private final Object muxerLock = new Object();

    private Thread videoDrainThread;
    private Thread audioCaptureThread;
    private Thread audioDrainThread;
    private volatile boolean running;
    private volatile boolean paused;
    private volatile long pauseStartUs = -1;
    private volatile long pausedDurationUs;

    // Shared origin so audio and video land on the same timeline in the muxer.
    // nanoTime-based: video surface PTS uses the same clock, so subtracting
    // this value gives 0-anchored presentation times for both tracks.
    private long startAnchorUs;

    private long audioSamplesWritten;
    private long audioStartPtsUs = -1;

    RecorderPipeline(AudioSource audio) {
        this.captureInternal = audio == AudioSource.INTERNAL || audio == AudioSource.BOTH;
        this.captureMic = audio == AudioSource.MIC || audio == AudioSource.BOTH;
    }

    boolean hasAudio() { return captureInternal || captureMic; }

    @SuppressLint("MissingPermission")
    void start(FileDescriptor fd, int width, int height, int dpi,
               Quality quality, MediaProjection projection, Handler displayHandler)
            throws IOException {
        MediaFormat vf = MediaFormat.createVideoFormat(VIDEO_MIME, width, height);
        vf.setInteger(MediaFormat.KEY_COLOR_FORMAT,
                MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface);
        vf.setInteger(MediaFormat.KEY_BIT_RATE, quality.bitrate);
        vf.setInteger(MediaFormat.KEY_FRAME_RATE, quality.fps);
        vf.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, I_FRAME_INTERVAL_SEC);
        videoEncoder = MediaCodec.createEncoderByType(VIDEO_MIME);
        videoEncoder.configure(vf, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
        inputSurface = videoEncoder.createInputSurface();

        if (hasAudio()) {
            MediaFormat af = MediaFormat.createAudioFormat(AUDIO_MIME,
                    AUDIO_SAMPLE_RATE, AUDIO_CHANNEL_COUNT);
            af.setInteger(MediaFormat.KEY_AAC_PROFILE,
                    MediaCodecInfo.CodecProfileLevel.AACObjectLC);
            af.setInteger(MediaFormat.KEY_BIT_RATE, AUDIO_BITRATE);
            audioEncoder = MediaCodec.createEncoderByType(AUDIO_MIME);
            audioEncoder.configure(af, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
        }

        muxer = new MediaMuxer(fd, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);

        videoEncoder.start();
        if (audioEncoder != null) audioEncoder.start();

        startAnchorUs = System.nanoTime() / 1000L;

        if (captureMic) micRecord = createMicRecord();
        if (captureInternal) playbackRecord = createPlaybackRecord(projection);
        if (micRecord != null) micRecord.startRecording();
        if (playbackRecord != null) playbackRecord.startRecording();

        virtualDisplay = projection.createVirtualDisplay(
                "ScreenRecorder", width, height, dpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                inputSurface, null, displayHandler);

        running = true;
        videoDrainThread = new Thread(this::drainVideoLoop, "video-drain");
        videoDrainThread.start();
        if (audioEncoder != null) {
            audioCaptureThread = new Thread(this::captureAudioLoop, "audio-cap");
            audioCaptureThread.start();
            audioDrainThread = new Thread(this::drainAudioLoop, "audio-drain");
            audioDrainThread.start();
        }
    }

    void pause() {
        if (paused) return;
        pauseStartUs = System.nanoTime() / 1000L;
        paused = true;
    }

    void resume() {
        if (!paused) return;
        if (pauseStartUs > 0) {
            pausedDurationUs += Math.max(0, (System.nanoTime() / 1000L) - pauseStartUs);
            pauseStartUs = -1;
        }
        paused = false;
    }

    boolean isPaused() { return paused; }

    void stop() {
        running = false;
        if (virtualDisplay != null) {
            try { virtualDisplay.release(); } catch (Exception ignored) {}
            virtualDisplay = null;
        }
        if (videoEncoder != null) {
            try { videoEncoder.signalEndOfInputStream(); } catch (Exception ignored) {}
        }
        if (micRecord != null) {
            try { micRecord.stop(); } catch (Exception ignored) {}
        }
        if (playbackRecord != null) {
            try { playbackRecord.stop(); } catch (Exception ignored) {}
        }
        joinQuiet(audioCaptureThread);
        joinQuiet(audioDrainThread);
        joinQuiet(videoDrainThread);

        if (micRecord != null) {
            try { micRecord.release(); } catch (Exception ignored) {}
            micRecord = null;
        }
        if (playbackRecord != null) {
            try { playbackRecord.release(); } catch (Exception ignored) {}
            playbackRecord = null;
        }
        if (videoEncoder != null) {
            try { videoEncoder.stop(); } catch (Exception ignored) {}
            try { videoEncoder.release(); } catch (Exception ignored) {}
            videoEncoder = null;
        }
        if (audioEncoder != null) {
            try { audioEncoder.stop(); } catch (Exception ignored) {}
            try { audioEncoder.release(); } catch (Exception ignored) {}
            audioEncoder = null;
        }
        if (inputSurface != null) {
            try { inputSurface.release(); } catch (Exception ignored) {}
            inputSurface = null;
        }
        if (muxer != null) {
            synchronized (muxerLock) {
                if (muxerStarted) {
                    try { muxer.stop(); } catch (Exception ignored) {}
                    muxerStarted = false;
                }
                try { muxer.release(); } catch (Exception ignored) {}
                muxer = null;
            }
        }
    }

    @SuppressLint("MissingPermission")
    private AudioRecord createMicRecord() {
        int min = AudioRecord.getMinBufferSize(AUDIO_SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
        int buf = Math.max(min, AUDIO_FRAMES_PER_READ * AUDIO_CHANNEL_COUNT * 2 * 4);
        return new AudioRecord(MediaRecorder.AudioSource.CAMCORDER,
                AUDIO_SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT, buf);
    }

    @SuppressLint("MissingPermission")
    private AudioRecord createPlaybackRecord(MediaProjection projection) {
        AudioPlaybackCaptureConfiguration cfg =
                new AudioPlaybackCaptureConfiguration.Builder(projection)
                        .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                        .addMatchingUsage(AudioAttributes.USAGE_GAME)
                        .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
                        .build();
        AudioFormat format = new AudioFormat.Builder()
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setSampleRate(AUDIO_SAMPLE_RATE)
                .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                .build();
        int min = AudioRecord.getMinBufferSize(AUDIO_SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
        int buf = Math.max(min, AUDIO_FRAMES_PER_READ * AUDIO_CHANNEL_COUNT * 2 * 4);
        return new AudioRecord.Builder()
                .setAudioFormat(format)
                .setBufferSizeInBytes(buf)
                .setAudioPlaybackCaptureConfig(cfg)
                .build();
    }

    private void drainVideoLoop() {
        MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
        while (true) {
            int out;
            try {
                out = videoEncoder.dequeueOutputBuffer(info, DEQUEUE_TIMEOUT_US);
            } catch (Exception e) {
                Log.e(TAG, "dequeueVideo", e);
                return;
            }
            if (out == MediaCodec.INFO_TRY_AGAIN_LATER) continue;
            if (out == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                MediaFormat f = videoEncoder.getOutputFormat();
                synchronized (muxerLock) {
                    videoTrack = muxer.addTrack(f);
                    maybeStartMuxer();
                }
                continue;
            }
            if (out < 0) continue;
            ByteBuffer buf = videoEncoder.getOutputBuffer(out);
            if ((info.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) info.size = 0;
            if (buf != null && info.size > 0) {
                long adjusted = info.presentationTimeUs - startAnchorUs - pausedDurationUs;
                if (paused || adjusted < 0) {
                    try { videoEncoder.releaseOutputBuffer(out, false); } catch (Exception ignored) {}
                    if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) return;
                    continue;
                }
                info.presentationTimeUs = adjusted;
                synchronized (muxerLock) {
                    if (muxerStarted && videoTrack >= 0) {
                        buf.position(info.offset);
                        buf.limit(info.offset + info.size);
                        try { muxer.writeSampleData(videoTrack, buf, info); }
                        catch (Exception e) { Log.e(TAG, "writeVideo", e); }
                    }
                }
            }
            try { videoEncoder.releaseOutputBuffer(out, false); } catch (Exception ignored) {}
            if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) return;
        }
    }

    private void captureAudioLoop() {
        int frameBytes = AUDIO_CHANNEL_COUNT * 2;
        int readBytes = AUDIO_FRAMES_PER_READ * frameBytes;
        byte[] micBuf = captureMic ? new byte[readBytes] : null;
        byte[] playBuf = captureInternal ? new byte[readBytes] : null;
        byte[] mixBuf = (captureMic && captureInternal) ? new byte[readBytes] : null;

        while (running) {
            int micRead = 0, playRead = 0;
            if (micRecord != null) {
                int r = micRecord.read(micBuf, 0, readBytes);
                micRead = Math.max(0, r);
            }
            if (playbackRecord != null) {
                int r = playbackRecord.read(playBuf, 0, readBytes);
                playRead = Math.max(0, r);
            }
            // During pause we still drain the AudioRecord to prevent buffer
            // overflow, but we skip feeding the encoder so PTS stays frozen.
            if (paused) continue;
            byte[] data;
            int len;
            if (micBuf != null && playBuf != null) {
                int n = Math.min(micRead, playRead);
                mixInto(micBuf, playBuf, mixBuf, n);
                data = mixBuf;
                len = n;
            } else if (micBuf != null) {
                data = micBuf; len = micRead;
            } else {
                data = playBuf; len = playRead;
            }
            if (len > 0) feedAudioEncoder(data, len, false);
        }
        feedAudioEncoder(null, 0, true);
    }

    private static void mixInto(byte[] a, byte[] b, byte[] out, int bytes) {
        for (int i = 0; i + 1 < bytes; i += 2) {
            int sa = (short) ((a[i] & 0xff) | (a[i + 1] << 8));
            int sb = (short) ((b[i] & 0xff) | (b[i + 1] << 8));
            int s = sa + sb;
            if (s > Short.MAX_VALUE) s = Short.MAX_VALUE;
            else if (s < Short.MIN_VALUE) s = Short.MIN_VALUE;
            out[i] = (byte) (s & 0xff);
            out[i + 1] = (byte) ((s >> 8) & 0xff);
        }
    }

    private void feedAudioEncoder(byte[] data, int len, boolean eos) {
        if (eos) {
            int idx;
            try { idx = audioEncoder.dequeueInputBuffer(100_000); }
            catch (Exception e) { return; }
            if (idx >= 0) {
                long pts = computeAudioPtsUs();
                try {
                    audioEncoder.queueInputBuffer(idx, 0, 0, pts,
                            MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                } catch (Exception ignored) {}
            }
            return;
        }
        int offset = 0;
        while (offset < len) {
            int idx;
            try { idx = audioEncoder.dequeueInputBuffer(DEQUEUE_TIMEOUT_US); }
            catch (Exception e) { return; }
            if (idx < 0) return;
            ByteBuffer in = audioEncoder.getInputBuffer(idx);
            if (in == null) {
                try { audioEncoder.queueInputBuffer(idx, 0, 0, 0, 0); } catch (Exception ignored) {}
                return;
            }
            int cap = in.capacity();
            int chunk = Math.min(cap, len - offset);
            in.clear();
            in.put(data, offset, chunk);
            long pts = computeAudioPtsUs();
            try {
                audioEncoder.queueInputBuffer(idx, 0, chunk, pts, 0);
            } catch (Exception e) {
                Log.e(TAG, "queueAudio", e);
                return;
            }
            int frames = chunk / (AUDIO_CHANNEL_COUNT * 2);
            audioSamplesWritten += frames;
            offset += chunk;
        }
    }

    private long computeAudioPtsUs() {
        if (audioStartPtsUs < 0) {
            audioStartPtsUs = Math.max(0, (System.nanoTime() / 1000L) - startAnchorUs);
        }
        return audioStartPtsUs + audioSamplesWritten * 1_000_000L / AUDIO_SAMPLE_RATE;
    }

    private void drainAudioLoop() {
        MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
        while (true) {
            int out;
            try {
                out = audioEncoder.dequeueOutputBuffer(info, DEQUEUE_TIMEOUT_US);
            } catch (Exception e) {
                Log.e(TAG, "dequeueAudio", e);
                return;
            }
            if (out == MediaCodec.INFO_TRY_AGAIN_LATER) continue;
            if (out == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                MediaFormat f = audioEncoder.getOutputFormat();
                synchronized (muxerLock) {
                    audioTrack = muxer.addTrack(f);
                    maybeStartMuxer();
                }
                continue;
            }
            if (out < 0) continue;
            ByteBuffer buf = audioEncoder.getOutputBuffer(out);
            if ((info.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) info.size = 0;
            if (buf != null && info.size > 0) {
                synchronized (muxerLock) {
                    if (muxerStarted && audioTrack >= 0) {
                        buf.position(info.offset);
                        buf.limit(info.offset + info.size);
                        try { muxer.writeSampleData(audioTrack, buf, info); }
                        catch (Exception e) { Log.e(TAG, "writeAudio", e); }
                    }
                }
            }
            try { audioEncoder.releaseOutputBuffer(out, false); } catch (Exception ignored) {}
            if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) return;
        }
    }

    private void maybeStartMuxer() {
        if (muxerStarted) return;
        if (videoTrack < 0) return;
        if (hasAudio() && audioTrack < 0) return;
        try {
            muxer.start();
            muxerStarted = true;
        } catch (Exception e) {
            Log.e(TAG, "muxer.start", e);
        }
    }

    private static void joinQuiet(Thread t) {
        if (t == null) return;
        try { t.join(5_000); }
        catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
    }
}
