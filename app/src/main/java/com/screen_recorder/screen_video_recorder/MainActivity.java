package com.screen_recorder.screen_video_recorder;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.util.DisplayMetrics;
import android.view.View;

import androidx.activity.EdgeToEdge;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.PopupMenu;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.recyclerview.widget.GridLayoutManager;

import com.google.android.gms.ads.AdRequest;
import com.google.android.gms.ads.AdSize;
import com.google.android.gms.ads.AdView;
import com.google.android.material.snackbar.Snackbar;
import com.screen_recorder.screen_video_recorder.data.Recording;
import com.screen_recorder.screen_video_recorder.data.RecordingRepository;
import com.screen_recorder.screen_video_recorder.data.SettingsRepository;
import com.screen_recorder.screen_video_recorder.databinding.ActivityMainBinding;
import com.screen_recorder.screen_video_recorder.recording.RecordingService;
import com.screen_recorder.screen_video_recorder.ui.PreRecordBottomSheet;
import com.screen_recorder.screen_video_recorder.ui.RecordingsAdapter;
import com.screen_recorder.screen_video_recorder.ui.VideoDetailsBottomSheet;
import com.screen_recorder.screen_video_recorder.util.Formats;

import java.util.List;

public class MainActivity extends AppCompatActivity implements RecordingsAdapter.Callbacks {

    private static final int SPAN_COUNT = 2;

    private ActivityMainBinding binding;
    private RecordingsAdapter adapter;
    private RecordingRepository repo;
    private AdView adView;
    private BroadcastReceiver savedReceiver;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (!new SettingsRepository(this).isOnboarded()) {
            startActivity(new Intent(this, OnboardingActivity.class));
        }
        EdgeToEdge.enable(this);
        binding = ActivityMainBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        ViewCompat.setOnApplyWindowInsetsListener(binding.list, (v, insets) -> {
            Insets bars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            v.setPadding(v.getPaddingLeft(), v.getPaddingTop(), v.getPaddingRight(), bars.bottom + dp(96));
            return insets;
        });
        ViewCompat.setOnApplyWindowInsetsListener(binding.fabRecord, (v, insets) -> {
            Insets bars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            ((androidx.coordinatorlayout.widget.CoordinatorLayout.LayoutParams) v.getLayoutParams())
                    .bottomMargin = bars.bottom + dp(24);
            v.requestLayout();
            return insets;
        });

        repo = new RecordingRepository(this);
        adapter = new RecordingsAdapter(this);
        GridLayoutManager glm = new GridLayoutManager(this, SPAN_COUNT);
        glm.setSpanSizeLookup(new GridLayoutManager.SpanSizeLookup() {
            @Override public int getSpanSize(int position) {
                return adapter.getSpanSize(position, SPAN_COUNT);
            }
        });
        binding.list.setLayoutManager(glm);
        binding.list.setAdapter(adapter);

        binding.toolbar.setOnMenuItemClickListener(item -> {
            if (item.getItemId() == R.id.action_settings) {
                startActivity(new Intent(this, SettingsActivity.class));
                return true;
            }
            return false;
        });

        binding.fabRecord.setOnClickListener(v -> openPreRecord());

        loadBanner();
    }

    private void loadBanner() {
        if (binding.adViewContainer == null) return;
        adView = new AdView(this);
        adView.setAdUnitId(getString(R.string.banner));
        adView.setAdSize(getAdSize());
        binding.adViewContainer.removeAllViews();
        binding.adViewContainer.addView(adView);
        adView.loadAd(new AdRequest.Builder().build());
    }

    private AdSize getAdSize() {
        DisplayMetrics dm = getResources().getDisplayMetrics();
        int adWidth = (int) (dm.widthPixels / dm.density);
        return AdSize.getCurrentOrientationAnchoredAdaptiveBannerAdSize(this, adWidth);
    }

    @Override
    protected void onPause() {
        if (adView != null) adView.pause();
        super.onPause();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (adView != null) adView.resume();
    }

    @Override
    protected void onDestroy() {
        if (adView != null) {
            adView.destroy();
            adView = null;
        }
        super.onDestroy();
    }

    @Override
    protected void onStart() {
        super.onStart();
        repo.observe(this::refresh);
        refresh();
        registerSavedReceiver();
    }

    @Override
    protected void onStop() {
        super.onStop();
        repo.stopObserving();
        if (savedReceiver != null) {
            try { unregisterReceiver(savedReceiver); } catch (Exception ignored) {}
            savedReceiver = null;
        }
    }

    private void registerSavedReceiver() {
        savedReceiver = new BroadcastReceiver() {
            @Override public void onReceive(Context context, Intent intent) {
                Uri uri = intent.getParcelableExtra(RecordingService.EXTRA_SAVED_URI);
                if (uri != null) showSavedSnackbar(uri);
            }
        };
        IntentFilter f = new IntentFilter(RecordingService.ACTION_RECORDING_SAVED);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(savedReceiver, f, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(savedReceiver, f);
        }
    }

    private void showSavedSnackbar(Uri uri) {
        Snackbar sb = Snackbar.make(binding.getRoot(),
                R.string.snackbar_saved, Snackbar.LENGTH_LONG);
        sb.setAction(R.string.snackbar_view, v -> {
            Intent play = new Intent(Intent.ACTION_VIEW)
                    .setDataAndType(uri, "video/mp4")
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            try { startActivity(play); } catch (Exception ignored) {}
        });
        sb.setAnchorView(binding.fabRecord);
        sb.show();
    }

    private void refresh() {
        List<Recording> all = repo.queryAll();
        adapter.submit(this, all);
        boolean empty = all.isEmpty();
        binding.empty.getRoot().setVisibility(empty ? View.VISIBLE : View.GONE);
        binding.list.setVisibility(empty ? View.GONE : View.VISIBLE);
        if (empty) {
            binding.stats.setVisibility(View.GONE);
        } else {
            binding.stats.setVisibility(View.VISIBLE);
            binding.stats.setText(getString(R.string.library_stats,
                    all.size(),
                    Formats.sizeHuman(this, repo.totalSizeBytes(all))));
        }
    }

    private void openPreRecord() {
        new PreRecordBottomSheet().show(getSupportFragmentManager(), "pre_record");
    }

    @Override
    public void onItemClicked(Recording r) {
        VideoDetailsBottomSheet.newInstance(r.id)
                .show(getSupportFragmentManager(), "details");
    }

    @Override
    public void onItemLongClicked(Recording r, @NonNull View anchor) {
        PopupMenu menu = new PopupMenu(this, anchor);
        menu.inflate(R.menu.menu_video_item);
        menu.setOnMenuItemClickListener(item -> {
            VideoDetailsBottomSheet sheet = VideoDetailsBottomSheet.newInstance(r.id);
            Bundle b = sheet.getArguments();
            int id = item.getItemId();
            if (id == R.id.action_play) b.putString("initial_action", "play");
            else if (id == R.id.action_share) b.putString("initial_action", "share");
            else if (id == R.id.action_rename) b.putString("initial_action", "rename");
            else if (id == R.id.action_compress) b.putString("initial_action", "compress");
            else if (id == R.id.action_delete) b.putString("initial_action", "delete");
            sheet.show(getSupportFragmentManager(), "details");
            return true;
        });
        menu.show();
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }
}
