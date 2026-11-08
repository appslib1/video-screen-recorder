package com.screen_recorder.screen_video_recorder.data;

public enum Quality {
    LOW(854, 480, 2_000_000, 30),
    MEDIUM(1280, 720, 4_000_000, 30),
    HIGH(1920, 1080, 8_000_000, 30),
    MAX(0, 0, 12_000_000, 60); // 0 = use device native

    public final int width;
    public final int height;
    public final int bitrate;
    public final int fps;

    Quality(int width, int height, int bitrate, int fps) {
        this.width = width;
        this.height = height;
        this.bitrate = bitrate;
        this.fps = fps;
    }

    public static Quality fromName(String name, Quality fallback) {
        if (name == null) return fallback;
        try { return valueOf(name); } catch (IllegalArgumentException e) { return fallback; }
    }
}
