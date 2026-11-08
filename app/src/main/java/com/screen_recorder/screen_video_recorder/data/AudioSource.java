package com.screen_recorder.screen_video_recorder.data;

public enum AudioSource {
    NONE,
    MIC,
    INTERNAL,
    BOTH;

    public static AudioSource fromName(String name, AudioSource fallback) {
        if (name == null) return fallback;
        try { return valueOf(name); } catch (IllegalArgumentException e) { return fallback; }
    }
}
