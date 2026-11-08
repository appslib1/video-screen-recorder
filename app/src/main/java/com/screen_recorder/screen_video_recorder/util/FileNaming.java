package com.screen_recorder.screen_video_recorder.util;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public final class FileNaming {
    private FileNaming() {}

    public static String newRecordingName() {
        String stamp = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date());
        return "Screen_" + stamp + ".mp4";
    }

    public static String compressedName(String base) {
        String stem = base;
        String ext = ".mp4";
        int dot = base.lastIndexOf('.');
        if (dot > 0) {
            stem = base.substring(0, dot);
            ext = base.substring(dot);
        }
        return stem + "_compressed" + ext;
    }
}
