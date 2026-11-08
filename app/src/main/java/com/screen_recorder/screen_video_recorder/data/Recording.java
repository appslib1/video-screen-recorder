package com.screen_recorder.screen_video_recorder.data;

import android.net.Uri;

import java.util.Objects;

public class Recording {
    public final long id;
    public final Uri uri;
    public final String displayName;
    public final long durationMs;
    public final long sizeBytes;
    public final long dateAddedSec;
    public final int width;
    public final int height;
    public final String relativePath;

    public Recording(long id, Uri uri, String displayName, long durationMs, long sizeBytes,
                     long dateAddedSec, int width, int height, String relativePath) {
        this.id = id;
        this.uri = uri;
        this.displayName = displayName;
        this.durationMs = durationMs;
        this.sizeBytes = sizeBytes;
        this.dateAddedSec = dateAddedSec;
        this.width = width;
        this.height = height;
        this.relativePath = relativePath;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof Recording)) return false;
        Recording that = (Recording) o;
        return id == that.id
                && durationMs == that.durationMs
                && sizeBytes == that.sizeBytes
                && Objects.equals(displayName, that.displayName);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id, displayName, durationMs, sizeBytes);
    }
}
