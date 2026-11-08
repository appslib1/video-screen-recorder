package com.screen_recorder.screen_video_recorder.recording;

import android.content.ContentValues;
import android.content.Context;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.media.MediaMuxer;
import android.net.Uri;
import android.os.Build;
import android.os.ParcelFileDescriptor;
import android.provider.MediaStore;

import com.screen_recorder.screen_video_recorder.data.RecordingRepository;
import com.screen_recorder.screen_video_recorder.util.FileNaming;

import java.io.FileDescriptor;
import java.nio.ByteBuffer;

/**
 * Transmux-based compressor: lowers the video track bitrate via re-encode (MediaCodec),
 * passes audio track through unchanged. Keeps the implementation footprint small while still
 * giving the user a meaningfully smaller file.
 */
public class VideoCompressor {

    public enum Preset {
        LOW(1_500_000),
        MEDIUM(3_000_000),
        HIGH(6_000_000);

        public final int bitrate;
        Preset(int b) { this.bitrate = b; }
    }

    public interface Progress {
        void onProgress(int percent);
        void onDone(Uri outUri, long outSize);
        void onError(Throwable t);
    }

    private final Context ctx;

    public VideoCompressor(Context ctx) {
        this.ctx = ctx.getApplicationContext();
    }

    public void compress(Uri input, String displayName, Preset preset, Progress listener) {
        new Thread(() -> {
            MediaExtractor extractor = null;
            MediaCodec decoder = null;
            MediaCodec encoder = null;
            MediaMuxer muxer = null;
            ParcelFileDescriptor inPfd = null;
            ParcelFileDescriptor outPfd = null;
            Uri outUri = null;
            try {
                inPfd = ctx.getContentResolver().openFileDescriptor(input, "r");
                if (inPfd == null) throw new IllegalStateException("Cannot open source");

                extractor = new MediaExtractor();
                extractor.setDataSource(inPfd.getFileDescriptor());

                int videoTrack = -1;
                int audioTrack = -1;
                MediaFormat videoFormat = null;
                MediaFormat audioFormat = null;
                for (int i = 0; i < extractor.getTrackCount(); i++) {
                    MediaFormat f = extractor.getTrackFormat(i);
                    String mime = f.getString(MediaFormat.KEY_MIME);
                    if (mime == null) continue;
                    if (videoTrack < 0 && mime.startsWith("video/")) {
                        videoTrack = i; videoFormat = f;
                    } else if (audioTrack < 0 && mime.startsWith("audio/")) {
                        audioTrack = i; audioFormat = f;
                    }
                }
                if (videoTrack < 0) throw new IllegalStateException("No video track");

                int width = videoFormat.getInteger(MediaFormat.KEY_WIDTH);
                int height = videoFormat.getInteger(MediaFormat.KEY_HEIGHT);
                long durationUs = videoFormat.containsKey(MediaFormat.KEY_DURATION)
                        ? videoFormat.getLong(MediaFormat.KEY_DURATION) : 0;
                int frameRate = videoFormat.containsKey(MediaFormat.KEY_FRAME_RATE)
                        ? videoFormat.getInteger(MediaFormat.KEY_FRAME_RATE) : 30;

                MediaFormat outFormat = MediaFormat.createVideoFormat("video/avc", width, height);
                outFormat.setInteger(MediaFormat.KEY_BIT_RATE, preset.bitrate);
                outFormat.setInteger(MediaFormat.KEY_FRAME_RATE, frameRate);
                outFormat.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 2);
                outFormat.setInteger(MediaFormat.KEY_COLOR_FORMAT,
                        MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface);

                encoder = MediaCodec.createEncoderByType("video/avc");
                encoder.configure(outFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
                android.view.Surface inputSurface = encoder.createInputSurface();
                encoder.start();

                decoder = MediaCodec.createDecoderByType(videoFormat.getString(MediaFormat.KEY_MIME));
                decoder.configure(videoFormat, inputSurface, null, 0);
                decoder.start();

                outUri = createOutputMediaStore(displayName);
                outPfd = ctx.getContentResolver().openFileDescriptor(outUri, "w");
                muxer = new MediaMuxer(outPfd.getFileDescriptor(),
                        MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);

                int outVideoTrack = -1;
                int outAudioTrack = -1;
                boolean muxerStarted = false;

                extractor.selectTrack(videoTrack);
                MediaCodec.BufferInfo decoderInfo = new MediaCodec.BufferInfo();
                MediaCodec.BufferInfo encoderInfo = new MediaCodec.BufferInfo();

                boolean sawInputEOS = false;
                boolean sawDecoderEOS = false;
                boolean sawEncoderEOS = false;
                int lastProgress = -1;

                while (!sawEncoderEOS) {
                    if (!sawInputEOS) {
                        int inIndex = decoder.dequeueInputBuffer(10_000);
                        if (inIndex >= 0) {
                            ByteBuffer inBuf = decoder.getInputBuffer(inIndex);
                            int sampleSize = extractor.readSampleData(inBuf, 0);
                            if (sampleSize < 0) {
                                decoder.queueInputBuffer(inIndex, 0, 0, 0,
                                        MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                                sawInputEOS = true;
                            } else {
                                long pts = extractor.getSampleTime();
                                decoder.queueInputBuffer(inIndex, 0, sampleSize, pts, 0);
                                extractor.advance();
                            }
                        }
                    }

                    if (!sawDecoderEOS) {
                        int decOut = decoder.dequeueOutputBuffer(decoderInfo, 10_000);
                        if (decOut >= 0) {
                            boolean render = decoderInfo.size > 0;
                            decoder.releaseOutputBuffer(decOut, render);
                            if ((decoderInfo.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                                sawDecoderEOS = true;
                                encoder.signalEndOfInputStream();
                            }
                        }
                    }

                    int encOut = encoder.dequeueOutputBuffer(encoderInfo, 10_000);
                    if (encOut == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        MediaFormat encFormat = encoder.getOutputFormat();
                        outVideoTrack = muxer.addTrack(encFormat);
                        if (audioFormat != null) {
                            outAudioTrack = muxer.addTrack(audioFormat);
                        }
                        muxer.start();
                        muxerStarted = true;
                    } else if (encOut >= 0 && muxerStarted) {
                        ByteBuffer encBuf = encoder.getOutputBuffer(encOut);
                        if ((encoderInfo.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0
                                && encoderInfo.size > 0) {
                            encBuf.position(encoderInfo.offset);
                            encBuf.limit(encoderInfo.offset + encoderInfo.size);
                            muxer.writeSampleData(outVideoTrack, encBuf, encoderInfo);
                            if (durationUs > 0) {
                                int pct = (int) Math.min(99, encoderInfo.presentationTimeUs * 100 / durationUs);
                                if (pct != lastProgress) {
                                    lastProgress = pct;
                                    listener.onProgress(pct);
                                }
                            }
                        }
                        encoder.releaseOutputBuffer(encOut, false);
                        if ((encoderInfo.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                            sawEncoderEOS = true;
                        }
                    }
                }

                if (muxerStarted && outAudioTrack >= 0) {
                    extractor.unselectTrack(videoTrack);
                    extractor.selectTrack(audioTrack);
                    extractor.seekTo(0, MediaExtractor.SEEK_TO_CLOSEST_SYNC);
                    ByteBuffer buf = ByteBuffer.allocate(256 * 1024);
                    MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
                    while (true) {
                        int size = extractor.readSampleData(buf, 0);
                        if (size < 0) break;
                        info.offset = 0;
                        info.size = size;
                        info.presentationTimeUs = extractor.getSampleTime();
                        info.flags = extractor.getSampleFlags();
                        muxer.writeSampleData(outAudioTrack, buf, info);
                        extractor.advance();
                    }
                }

                try { muxer.stop(); } catch (Exception ignored) {}
                muxer.release();
                muxer = null;
                encoder.stop(); encoder.release(); encoder = null;
                decoder.stop(); decoder.release(); decoder = null;
                extractor.release(); extractor = null;
                try { outPfd.close(); } catch (Exception ignored) {}
                outPfd = null;

                finalizePending(outUri);

                long outSize = getSize(outUri);
                listener.onProgress(100);
                listener.onDone(outUri, outSize);
            } catch (Throwable t) {
                safeDelete(outUri);
                listener.onError(t);
            } finally {
                try { if (muxer != null) muxer.release(); } catch (Exception ignored) {}
                try { if (encoder != null) encoder.release(); } catch (Exception ignored) {}
                try { if (decoder != null) decoder.release(); } catch (Exception ignored) {}
                try { if (extractor != null) extractor.release(); } catch (Exception ignored) {}
                try { if (inPfd != null) inPfd.close(); } catch (Exception ignored) {}
                try { if (outPfd != null) outPfd.close(); } catch (Exception ignored) {}
            }
        }, "VideoCompressor").start();
    }

    private Uri createOutputMediaStore(String displayName) {
        String name = FileNaming.compressedName(displayName);
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
        return ctx.getContentResolver().insert(collection, values);
    }

    private void finalizePending(Uri uri) {
        if (uri == null || Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return;
        ContentValues v = new ContentValues();
        v.put(MediaStore.Video.Media.IS_PENDING, 0);
        try { ctx.getContentResolver().update(uri, v, null, null); } catch (Exception ignored) {}
    }

    private void safeDelete(Uri uri) {
        if (uri == null) return;
        try { ctx.getContentResolver().delete(uri, null, null); } catch (Exception ignored) {}
    }

    private long getSize(Uri uri) {
        try (android.database.Cursor c = ctx.getContentResolver().query(uri,
                new String[]{MediaStore.Video.Media.SIZE}, null, null, null)) {
            if (c != null && c.moveToFirst()) return c.getLong(0);
        } catch (Exception ignored) {}
        return 0;
    }
}
