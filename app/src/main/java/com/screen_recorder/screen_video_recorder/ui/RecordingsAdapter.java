package com.screen_recorder.screen_video_recorder.ui;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.bumptech.glide.Glide;
import com.google.android.material.imageview.ShapeableImageView;
import com.screen_recorder.screen_video_recorder.R;
import com.screen_recorder.screen_video_recorder.data.Recording;
import com.screen_recorder.screen_video_recorder.util.DateBucket;
import com.screen_recorder.screen_video_recorder.util.Formats;

import java.util.ArrayList;
import java.util.List;

public class RecordingsAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {

    public static final int TYPE_HEADER = 0;
    public static final int TYPE_ITEM = 1;

    public interface Callbacks {
        void onItemClicked(Recording r);
        void onItemLongClicked(Recording r, View anchor);
    }

    private final Callbacks callbacks;
    private final List<Object> items = new ArrayList<>();

    public RecordingsAdapter(Callbacks callbacks) {
        this.callbacks = callbacks;
        setHasStableIds(true);
    }

    public void submit(Context ctx, List<Recording> recordings) {
        items.clear();
        String currentBucket = null;
        for (Recording r : recordings) {
            String bucket = DateBucket.bucketFor(ctx, r.dateAddedSec);
            if (!bucket.equals(currentBucket)) {
                items.add(bucket);
                currentBucket = bucket;
            }
            items.add(r);
        }
        notifyDataSetChanged();
    }

    public int getSpanSize(int position, int totalSpans) {
        return items.get(position) instanceof String ? totalSpans : 1;
    }

    @Override
    public int getItemCount() { return items.size(); }

    @Override
    public int getItemViewType(int position) {
        return items.get(position) instanceof String ? TYPE_HEADER : TYPE_ITEM;
    }

    @Override
    public long getItemId(int position) {
        Object o = items.get(position);
        if (o instanceof String) return ("H:" + o).hashCode();
        return ((Recording) o).id;
    }

    @NonNull
    @Override
    public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        LayoutInflater inflater = LayoutInflater.from(parent.getContext());
        if (viewType == TYPE_HEADER) {
            View v = inflater.inflate(R.layout.item_recording_header, parent, false);
            return new HeaderVH(v);
        }
        View v = inflater.inflate(R.layout.item_recording, parent, false);
        return new ItemVH(v);
    }

    @Override
    public void onBindViewHolder(@NonNull RecyclerView.ViewHolder h, int position) {
        Object o = items.get(position);
        if (o instanceof String) {
            ((HeaderVH) h).title.setText((String) o);
        } else {
            ((ItemVH) h).bind((Recording) o, callbacks);
        }
    }

    static class HeaderVH extends RecyclerView.ViewHolder {
        final TextView title;
        HeaderVH(@NonNull View itemView) {
            super(itemView);
            title = (TextView) itemView;
        }
    }

    static class ItemVH extends RecyclerView.ViewHolder {
        final ShapeableImageView thumb;
        final TextView duration;

        ItemVH(@NonNull View itemView) {
            super(itemView);
            thumb = itemView.findViewById(R.id.thumb);
            duration = itemView.findViewById(R.id.duration);
        }

        void bind(Recording r, Callbacks cb) {
            duration.setText(Formats.durationHuman(r.durationMs));
            Glide.with(thumb).load(r.uri)
                    .centerCrop()
                    .placeholder(R.color.surface_variant_dark)
                    .into(thumb);
            itemView.setOnClickListener(v -> cb.onItemClicked(r));
            itemView.setOnLongClickListener(v -> { cb.onItemLongClicked(r, v); return true; });
        }
    }
}
