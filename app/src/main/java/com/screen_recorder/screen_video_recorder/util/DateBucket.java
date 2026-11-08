package com.screen_recorder.screen_video_recorder.util;

import android.content.Context;

import com.screen_recorder.screen_video_recorder.R;

import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.Locale;

public final class DateBucket {
    private DateBucket() {}

    public static String bucketFor(Context ctx, long dateAddedSec) {
        Calendar now = Calendar.getInstance();
        Calendar then = Calendar.getInstance();
        then.setTimeInMillis(dateAddedSec * 1000L);

        if (now.get(Calendar.YEAR) == then.get(Calendar.YEAR)
                && now.get(Calendar.DAY_OF_YEAR) == then.get(Calendar.DAY_OF_YEAR)) {
            return ctx.getString(R.string.bucket_today);
        }

        Calendar yesterday = (Calendar) now.clone();
        yesterday.add(Calendar.DAY_OF_YEAR, -1);
        if (yesterday.get(Calendar.YEAR) == then.get(Calendar.YEAR)
                && yesterday.get(Calendar.DAY_OF_YEAR) == then.get(Calendar.DAY_OF_YEAR)) {
            return ctx.getString(R.string.bucket_yesterday);
        }

        Calendar weekAgo = (Calendar) now.clone();
        weekAgo.add(Calendar.DAY_OF_YEAR, -7);
        if (then.after(weekAgo)) {
            return ctx.getString(R.string.bucket_this_week);
        }

        if (now.get(Calendar.YEAR) == then.get(Calendar.YEAR)) {
            return new SimpleDateFormat("MMMM", Locale.getDefault()).format(then.getTime());
        }
        return new SimpleDateFormat("MMMM yyyy", Locale.getDefault()).format(then.getTime());
    }
}
