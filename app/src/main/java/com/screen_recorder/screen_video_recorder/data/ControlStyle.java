package com.screen_recorder.screen_video_recorder.data;

public enum ControlStyle {
    NOTIFICATION,
    OVERLAY;

    public static ControlStyle fromName(String name, ControlStyle fallback) {
        if (name == null) return fallback;
        try { return valueOf(name); } catch (IllegalArgumentException e) { return fallback; }
    }
}
