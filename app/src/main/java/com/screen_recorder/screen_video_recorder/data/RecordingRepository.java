package com.screen_recorder.screen_video_recorder.data;

import android.content.ContentResolver;
import android.content.ContentUris;
import android.content.ContentValues;
import android.content.Context;
import android.database.ContentObserver;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;

import java.util.ArrayList;
import java.util.List;

public class RecordingRepository {

    public static final String FOLDER = "ScreenRecorder";
    public static final String RELATIVE_PATH_PRIMARY = "Movies/" + FOLDER + "/";

    public interface Listener { void onChanged(); }

    private final Context ctx;
    private final ContentResolver cr;
    private ContentObserver observer;

    public RecordingRepository(Context ctx) {
        this.ctx = ctx.getApplicationContext();
        this.cr = this.ctx.getContentResolver();
    }

    public List<Recording> queryAll() {
        List<Recording> out = new ArrayList<>();
        Uri collection = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
                ? MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
                : MediaStore.Video.Media.EXTERNAL_CONTENT_URI;

        String[] projection;
        String selection;
        String[] args;

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            projection = new String[]{
                    MediaStore.Video.Media._ID,
                    MediaStore.Video.Media.DISPLAY_NAME,
                    MediaStore.Video.Media.DURATION,
                    MediaStore.Video.Media.SIZE,
                    MediaStore.Video.Media.DATE_ADDED,
                    MediaStore.Video.Media.WIDTH,
                    MediaStore.Video.Media.HEIGHT,
                    MediaStore.Video.Media.RELATIVE_PATH
            };
            selection = MediaStore.Video.Media.RELATIVE_PATH + " LIKE ?";
            args = new String[]{"Movies/" + FOLDER + "/%"};
        } else {
            projection = new String[]{
                    MediaStore.Video.Media._ID,
                    MediaStore.Video.Media.DISPLAY_NAME,
                    MediaStore.Video.Media.DURATION,
                    MediaStore.Video.Media.SIZE,
                    MediaStore.Video.Media.DATE_ADDED,
                    MediaStore.Video.Media.WIDTH,
                    MediaStore.Video.Media.HEIGHT,
                    MediaStore.Video.Media.DATA
            };
            selection = MediaStore.Video.Media.DATA + " LIKE ?";
            args = new String[]{"%/" + FOLDER + "/%"};
        }

        String order = MediaStore.Video.Media.DATE_ADDED + " DESC";

        try (Cursor c = cr.query(collection, projection, selection, args, order)) {
            if (c == null) return out;
            int idIdx = c.getColumnIndexOrThrow(MediaStore.Video.Media._ID);
            int nameIdx = c.getColumnIndexOrThrow(MediaStore.Video.Media.DISPLAY_NAME);
            int durIdx = c.getColumnIndexOrThrow(MediaStore.Video.Media.DURATION);
            int sizeIdx = c.getColumnIndexOrThrow(MediaStore.Video.Media.SIZE);
            int dateIdx = c.getColumnIndexOrThrow(MediaStore.Video.Media.DATE_ADDED);
            int wIdx = c.getColumnIndexOrThrow(MediaStore.Video.Media.WIDTH);
            int hIdx = c.getColumnIndexOrThrow(MediaStore.Video.Media.HEIGHT);
            int pathIdx = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
                    ? c.getColumnIndexOrThrow(MediaStore.Video.Media.RELATIVE_PATH)
                    : c.getColumnIndexOrThrow(MediaStore.Video.Media.DATA);

            while (c.moveToNext()) {
                long id = c.getLong(idIdx);
                Uri uri = ContentUris.withAppendedId(collection, id);
                out.add(new Recording(
                        id, uri,
                        c.getString(nameIdx),
                        c.getLong(durIdx),
                        c.getLong(sizeIdx),
                        c.getLong(dateIdx),
                        c.getInt(wIdx),
                        c.getInt(hIdx),
                        c.getString(pathIdx)
                ));
            }
        } catch (Exception e) {
            // Permission denied or query failure — return what we have.
        }
        return out;
    }

    public long totalSizeBytes(List<Recording> recordings) {
        long sum = 0;
        for (Recording r : recordings) sum += r.sizeBytes;
        return sum;
    }

    public void observe(Listener l) {
        if (observer != null) stopObserving();
        observer = new ContentObserver(new Handler(Looper.getMainLooper())) {
            @Override public void onChange(boolean selfChange) { l.onChanged(); }
        };
        Uri collection = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
                ? MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
                : MediaStore.Video.Media.EXTERNAL_CONTENT_URI;
        cr.registerContentObserver(collection, true, observer);
    }

    public void stopObserving() {
        if (observer != null) {
            cr.unregisterContentObserver(observer);
            observer = null;
        }
    }

    public boolean rename(Recording r, String newName) {
        String name = newName.trim();
        if (!name.toLowerCase().endsWith(".mp4")) name = name + ".mp4";
        ContentValues v = new ContentValues();
        v.put(MediaStore.Video.Media.DISPLAY_NAME, name);
        try {
            return cr.update(r.uri, v, null, null) > 0;
        } catch (Exception e) {
            return false;
        }
    }

    public boolean delete(Recording r) {
        try {
            return cr.delete(r.uri, null, null) > 0;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Finds MediaStore entries we created but never finalized — typically
     * because the recording service was killed mid-session. Entries with
     * actual bytes get IS_PENDING cleared so they show up in the library;
     * empty ones get deleted. Called on app launch, best-effort.
     */
    public void recoverOrphanPending() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return;
        Uri collection = MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY);
        Bundle args = new Bundle();
        args.putString(ContentResolver.QUERY_ARG_SQL_SELECTION,
                MediaStore.Video.Media.RELATIVE_PATH + " LIKE ? AND "
                        + MediaStore.Video.Media.IS_PENDING + " = 1");
        args.putStringArray(ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS,
                new String[]{"Movies/" + FOLDER + "/%"});
        String[] proj = {MediaStore.Video.Media._ID, MediaStore.Video.Media.SIZE};
        try (Cursor c = cr.query(collection, proj, args, null)) {
            if (c == null) return;
            int idIdx = c.getColumnIndexOrThrow(MediaStore.Video.Media._ID);
            int sizeIdx = c.getColumnIndexOrThrow(MediaStore.Video.Media.SIZE);
            while (c.moveToNext()) {
                long id = c.getLong(idIdx);
                long size = c.getLong(sizeIdx);
                Uri uri = ContentUris.withAppendedId(collection, id);
                if (size > 0) {
                    ContentValues v = new ContentValues();
                    v.put(MediaStore.Video.Media.IS_PENDING, 0);
                    try { cr.update(uri, v, null, null); } catch (Exception ignored) {}
                } else {
                    try { cr.delete(uri, null, null); } catch (Exception ignored) {}
                }
            }
        } catch (Exception ignored) {}
    }
}
