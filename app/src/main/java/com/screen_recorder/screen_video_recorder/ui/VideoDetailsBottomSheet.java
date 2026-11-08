package com.screen_recorder.screen_video_recorder.ui;

import android.app.PendingIntent;
import android.app.RecoverableSecurityException;
import android.content.Intent;
import android.content.IntentSender;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.text.InputType;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.IntentSenderRequest;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.bumptech.glide.Glide;
import com.google.android.material.bottomsheet.BottomSheetDialogFragment;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.progressindicator.LinearProgressIndicator;
import com.google.android.material.textfield.TextInputLayout;
import com.screen_recorder.screen_video_recorder.R;
import com.screen_recorder.screen_video_recorder.data.Recording;
import com.screen_recorder.screen_video_recorder.data.RecordingRepository;
import com.screen_recorder.screen_video_recorder.databinding.BottomSheetVideoDetailsBinding;
import com.screen_recorder.screen_video_recorder.recording.VideoCompressor;
import com.screen_recorder.screen_video_recorder.util.Formats;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class VideoDetailsBottomSheet extends BottomSheetDialogFragment {

    public static VideoDetailsBottomSheet newInstance(long id) {
        VideoDetailsBottomSheet f = new VideoDetailsBottomSheet();
        Bundle b = new Bundle();
        b.putLong("id", id);
        f.setArguments(b);
        return f;
    }

    private BottomSheetVideoDetailsBinding binding;
    private RecordingRepository repo;
    private Recording recording;
    private ActivityResultLauncher<IntentSenderRequest> deleteLauncher;

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        deleteLauncher = registerForActivityResult(
                new ActivityResultContracts.StartIntentSenderForResult(), r -> {
                    if (r.getResultCode() == android.app.Activity.RESULT_OK) {
                        Toast.makeText(requireContext(), R.string.toast_deleted, Toast.LENGTH_SHORT).show();
                        dismissAllowingStateLoss();
                    }
                });
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater,
                             @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        binding = BottomSheetVideoDetailsBinding.inflate(inflater, container, false);
        return binding.getRoot();
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        repo = new RecordingRepository(requireContext());
        long id = requireArguments().getLong("id");
        recording = findById(id);
        if (recording == null) { dismissAllowingStateLoss(); return; }

        render();

        binding.actionPlay.setOnClickListener(v -> play());
        binding.actionShare.setOnClickListener(v -> share());
        binding.actionRename.setOnClickListener(v -> rename());
        binding.actionCompress.setOnClickListener(v -> compress());
        binding.actionDelete.setOnClickListener(v -> deletePrompt());

        String initial = requireArguments().getString("initial_action");
        if (initial != null) {
            switch (initial) {
                case "play": play(); break;
                case "share": share(); break;
                case "rename": rename(); break;
                case "compress": compress(); break;
                case "delete": deletePrompt(); break;
            }
        }
    }

    private Recording findById(long id) {
        for (Recording r : repo.queryAll()) if (r.id == id) return r;
        return null;
    }

    private void render() {
        binding.name.setText(recording.displayName);
        Glide.with(binding.thumb).load(recording.uri)
                .centerCrop()
                .into(binding.thumb);
        setMeta(binding.rowDuration.getRoot(), getString(R.string.details_duration),
                Formats.durationHuman(recording.durationMs));
        setMeta(binding.rowSize.getRoot(), getString(R.string.details_size),
                Formats.sizeHuman(requireContext(), recording.sizeBytes));
        setMeta(binding.rowResolution.getRoot(), getString(R.string.details_resolution),
                Formats.resolution(recording.width, recording.height));
        setMeta(binding.rowDate.getRoot(), getString(R.string.details_date),
                Formats.dateHuman(requireContext(), recording.dateAddedSec));
    }

    private void setMeta(View row, String key, String value) {
        ((TextView) row.findViewById(R.id.metaKey)).setText(key);
        ((TextView) row.findViewById(R.id.metaValue)).setText(value);
    }

    private void play() {
        Intent i = new Intent(Intent.ACTION_VIEW)
                .setDataAndType(recording.uri, "video/mp4")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        try { startActivity(i); } catch (Exception ignored) {}
    }

    private void share() {
        Intent i = new Intent(Intent.ACTION_SEND)
                .setType("video/mp4")
                .putExtra(Intent.EXTRA_STREAM, recording.uri)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        startActivity(Intent.createChooser(i, getString(R.string.action_share)));
    }

    private void rename() {
        EditText input = new EditText(requireContext());
        input.setInputType(InputType.TYPE_CLASS_TEXT);
        input.setText(stripExt(recording.displayName));
        input.setSelectAllOnFocus(true);
        TextInputLayout til = new TextInputLayout(requireContext());
        til.setHint(getString(R.string.rename_hint));
        til.setPadding(dp(24), dp(8), dp(24), 0);
        til.addView(input);

        new MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.rename_title)
                .setView(til)
                .setPositiveButton(R.string.rename_save, (d, w) -> {
                    String name = input.getText().toString().trim();
                    if (name.isEmpty()) return;
                    boolean ok = repo.rename(recording, name);
                    if (ok) {
                        Toast.makeText(requireContext(), R.string.toast_renamed, Toast.LENGTH_SHORT).show();
                        dismissAllowingStateLoss();
                    }
                })
                .setNegativeButton(R.string.perm_cancel, null)
                .show();
    }

    private String stripExt(String s) {
        int dot = s.lastIndexOf('.');
        return dot > 0 ? s.substring(0, dot) : s;
    }

    private void compress() {
        VideoCompressor.Preset[] presets = {
                VideoCompressor.Preset.LOW,
                VideoCompressor.Preset.MEDIUM,
                VideoCompressor.Preset.HIGH
        };
        String[] labels = {
                getString(R.string.compress_low),
                getString(R.string.compress_medium),
                getString(R.string.compress_high)
        };
        final int[] picked = {0};
        new MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.compress_title)
                .setMessage(R.string.compress_msg)
                .setSingleChoiceItems(labels, 0, (d, w) -> picked[0] = w)
                .setPositiveButton(R.string.action_compress, (d, w) -> runCompression(presets[picked[0]]))
                .setNegativeButton(R.string.perm_cancel, null)
                .show();
    }

    private void runCompression(VideoCompressor.Preset preset) {
        LinearProgressIndicator progress = new LinearProgressIndicator(requireContext());
        progress.setIndeterminate(false);
        progress.setMax(100);
        TextView pctText = new TextView(requireContext());
        pctText.setPadding(dp(24), 0, dp(24), dp(8));

        android.widget.LinearLayout wrapper = new android.widget.LinearLayout(requireContext());
        wrapper.setOrientation(android.widget.LinearLayout.VERTICAL);
        wrapper.setPadding(dp(24), dp(16), dp(24), 0);
        wrapper.addView(pctText);
        wrapper.addView(progress);

        androidx.appcompat.app.AlertDialog dlg = new MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.compress_title)
                .setView(wrapper)
                .setCancelable(false)
                .create();
        dlg.show();

        final long originalSize = recording.sizeBytes;
        new VideoCompressor(requireContext()).compress(recording.uri, recording.displayName, preset,
                new VideoCompressor.Progress() {
                    @Override public void onProgress(int percent) {
                        requireActivity().runOnUiThread(() -> {
                            progress.setProgress(percent);
                            pctText.setText(getString(R.string.compress_progress, percent));
                        });
                    }
                    @Override public void onDone(Uri outUri, long outSize) {
                        requireActivity().runOnUiThread(() -> {
                            dlg.dismiss();
                            String saved = Formats.sizeHuman(requireContext(),
                                    Math.max(0, originalSize - outSize));
                            String name = outUri.getLastPathSegment();
                            Toast.makeText(requireContext(),
                                    getString(R.string.compress_done, name, saved),
                                    Toast.LENGTH_LONG).show();
                            dismissAllowingStateLoss();
                        });
                    }
                    @Override public void onError(Throwable t) {
                        requireActivity().runOnUiThread(() -> {
                            dlg.dismiss();
                            Toast.makeText(requireContext(), R.string.compress_failed,
                                    Toast.LENGTH_LONG).show();
                        });
                    }
                });
    }

    private androidx.appcompat.app.AlertDialog deleteDialog;

    private void deletePrompt() {
        if (deleteDialog != null && deleteDialog.isShowing()) return;
        deleteDialog = new MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.delete_title)
                .setMessage(getString(R.string.delete_msg, recording.displayName))
                .setPositiveButton(R.string.delete_confirm, (d, w) -> doDelete())
                .setNegativeButton(R.string.perm_cancel, null)
                .setOnDismissListener(d -> deleteDialog = null)
                .show();
    }

    private void doDelete() {
        android.content.Context ctx = getContext();
        if (ctx == null || !isAdded()) return;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            List<Uri> uris = new ArrayList<>(Collections.singletonList(recording.uri));
            PendingIntent pi = MediaStoreHelper.createDeleteRequest(ctx, uris);
            try {
                deleteLauncher.launch(new IntentSenderRequest.Builder(pi.getIntentSender()).build());
            } catch (Exception ignored) {}
            return;
        }
        try {
            if (repo.delete(recording)) {
                Toast.makeText(ctx, R.string.toast_deleted, Toast.LENGTH_SHORT).show();
                dismissAllowingStateLoss();
            }
        } catch (RecoverableSecurityException rse) {
            try {
                IntentSender is = rse.getUserAction().getActionIntent().getIntentSender();
                deleteLauncher.launch(new IntentSenderRequest.Builder(is).build());
            } catch (Exception ignored) {}
        } catch (Exception ignored) {}
    }

    @Override
    public void onDestroyView() {
        if (deleteDialog != null) {
            try { deleteDialog.dismiss(); } catch (Exception ignored) {}
            deleteDialog = null;
        }
        super.onDestroyView();
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }
}
