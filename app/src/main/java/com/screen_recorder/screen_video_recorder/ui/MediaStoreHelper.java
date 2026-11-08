package com.screen_recorder.screen_video_recorder.ui;

import android.app.PendingIntent;
import android.content.Context;
import android.net.Uri;
import android.os.Build;
import android.provider.MediaStore;

import androidx.annotation.RequiresApi;

import java.util.List;

public final class MediaStoreHelper {
    private MediaStoreHelper() {}

    @RequiresApi(Build.VERSION_CODES.R)
    public static PendingIntent createDeleteRequest(Context ctx, List<Uri> uris) {
        return MediaStore.createDeleteRequest(ctx.getContentResolver(), uris);
    }
}
