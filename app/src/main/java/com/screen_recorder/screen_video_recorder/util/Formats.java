package com.screen_recorder.screen_video_recorder.util;

import android.content.Context;
import android.text.format.DateUtils;
import android.text.format.Formatter;

import java.util.Locale;
import java.util.concurrent.TimeUnit;

public final class Formats {
    private Formats() {}

    public static String durationHuman(long millis) {
        long totalSec = millis / 1000;
        long h = TimeUnit.SECONDS.toHours(totalSec);
        long m = (totalSec / 60) % 60;
        long s = totalSec % 60;
        if (h > 0) return String.format(Locale.US, "%d:%02d:%02d", h, m, s);
        return String.format(Locale.US, "%02d:%02d", m, s);
    }

    public static String sizeHuman(Context ctx, long bytes) {
        return Formatter.formatShortFileSize(ctx, bytes);
    }

    public static String dateHuman(Context ctx, long secondsSinceEpoch) {
        long millis = secondsSinceEpoch * 1000L;
        return DateUtils.formatDateTime(ctx, millis,
                DateUtils.FORMAT_SHOW_DATE | DateUtils.FORMAT_SHOW_TIME | DateUtils.FORMAT_ABBREV_MONTH);
    }

    public static String resolution(int w, int h) {
        if (w <= 0 || h <= 0) return "—";
        return w + " × " + h;
    }
}
