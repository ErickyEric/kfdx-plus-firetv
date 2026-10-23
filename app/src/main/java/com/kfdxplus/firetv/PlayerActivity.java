package com.kfdxplus.firetv;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.OptIn;
import androidx.media3.common.MediaItem;
import androidx.media3.common.MediaMetadata;
import androidx.media3.common.MimeTypes;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.ui.PlayerView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/** Full-screen TV video player. Plays the chosen episode, then rolls into the next newer one. */
@OptIn(markerClass = UnstableApi.class)
public class PlayerActivity extends Activity {
    private ExoPlayer player;
    private PlayerView view;
    private TextView showLabel, titleLabel;
    private final List<String> ids = new ArrayList<>();
    private final List<String> titles = new ArrayList<>();
    private final List<String> showNames = new ArrayList<>();
    private final JSONArray progress = new JSONArray();
    private int currentIndex = 0;
    private boolean finishing = false;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON | WindowManager.LayoutParams.FLAG_FULLSCREEN);
        hideSystemBars();

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);
        view = new PlayerView(this);
        view.setKeepScreenOn(true);
        view.setShowNextButton(true);
        view.setShowPreviousButton(false);
        view.setControllerShowTimeoutMs(3500);
        root.addView(view, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        // Show name + episode title, top-left, visible while the controls are up
        LinearLayout info = new LinearLayout(this);
        info.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(36);
        info.setPadding(pad, dp(28), pad, dp(20));
        info.setBackgroundColor(Color.argb(140, 0, 0, 0));
        showLabel = label(15, Color.parseColor("#f2b632"));
        titleLabel = label(26, Color.WHITE);
        info.addView(showLabel);
        info.addView(titleLabel);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP);
        root.addView(info, lp);
        view.setControllerVisibilityListener((PlayerView.ControllerVisibilityListener) visibility -> info.setVisibility(visibility));
        setContentView(root);

        List<MediaItem> items = new ArrayList<>();
        long resumeMs = 0;
        try {
            JSONObject cfg = new JSONObject(getIntent().getStringExtra("cfg"));
            resumeMs = cfg.optLong("resumeMs", 0);
            JSONArray arr = cfg.getJSONArray("items");
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.getJSONObject(i);
                ids.add(o.getString("id"));
                titles.add(o.optString("title"));
                showNames.add(o.optString("show"));
                items.add(new MediaItem.Builder()
                        .setUri(Uri.parse(o.getString("url")))
                        .setMimeType(MimeTypes.APPLICATION_M3U8)
                        .setMediaId(o.getString("id"))
                        .setMediaMetadata(new MediaMetadata.Builder()
                                .setTitle(o.optString("title"))
                                .setArtist(o.optString("show")).build())
                        .build());
            }
        } catch (Exception e) {
            Toast.makeText(this, "Couldn't open that episode.", Toast.LENGTH_LONG).show();
            finish();
            return;
        }

        player = new ExoPlayer.Builder(this)
                .setSeekBackIncrementMs(10_000)
                .setSeekForwardIncrementMs(10_000)
                .build();
        view.setPlayer(player);
        player.addListener(new Player.Listener() {
            @Override public void onMediaItemTransition(MediaItem item, int reason) {
                int newIndex = player.getCurrentMediaItemIndex();
                if (reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO || reason == Player.MEDIA_ITEM_TRANSITION_REASON_SEEK) {
                    record(currentIndex, 0, 0, true);      // the one we left was watched to the end / skipped
                }
                currentIndex = newIndex;
                updateLabels();
            }
            @Override public void onPlaybackStateChanged(int state) {
                if (state == Player.STATE_ENDED) { record(currentIndex, 0, 0, true); done(); }
            }
            @Override public void onPlayerError(PlaybackException error) {
                Toast.makeText(PlayerActivity.this, "This episode won't load right now. Try another one.", Toast.LENGTH_LONG).show();
            }
        });
        player.setMediaItems(items, 0, resumeMs);
        player.prepare();
        player.setPlayWhenReady(true);
        updateLabels();
        view.requestFocus();
    }

    private void updateLabels() {
        if (currentIndex < titles.size()) {
            showLabel.setText(showNames.get(currentIndex).toUpperCase());
            titleLabel.setText(titles.get(currentIndex));
        }
    }

    private void record(int index, long posSec, long durSec, boolean finished) {
        if (index < 0 || index >= ids.size()) return;
        try {
            JSONObject o = new JSONObject();
            o.put("id", ids.get(index));
            o.put("t", finished ? 0 : posSec);
            o.put("d", finished ? Math.max(durSec, 1) : durSec);
            progress.put(o);
        } catch (Exception ignored) { }
    }

    private void done() {
        if (finishing) return;
        finishing = true;
        if (player != null && player.getPlaybackState() != Player.STATE_ENDED) {
            long dur = player.getDuration();
            record(currentIndex, player.getCurrentPosition() / 1000, dur > 0 ? dur / 1000 : 0, false);
        }
        setResult(RESULT_OK, new Intent().putExtra("progress", progress.toString()));
        finish();
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        int k = event.getKeyCode();
        if (k == KeyEvent.KEYCODE_BACK || k == KeyEvent.KEYCODE_ESCAPE) {
            if (event.getAction() == KeyEvent.ACTION_UP) {
                if (view.isControllerFullyVisible()) view.hideController(); else done();
            }
            return true;
        }
        // Select / arrows / play-pause / rewind / fast-forward all go to the player controls
        return view.dispatchKeyEvent(event) || super.dispatchKeyEvent(event);
    }

    @Override public void onWindowFocusChanged(boolean hasFocus) { super.onWindowFocusChanged(hasFocus); if (hasFocus) hideSystemBars(); }
    @Override protected void onPause() { super.onPause(); if (player != null && !isFinishing()) player.pause(); }
    @Override protected void onDestroy() { super.onDestroy(); if (player != null) { player.release(); player = null; } }

    private void hideSystemBars() {
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_FULLSCREEN
                | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION);
    }
    private TextView label(int sp, int color) {
        TextView t = new TextView(this);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(color);
        t.setShadowLayer(6, 0, 2, Color.BLACK);
        t.setMaxLines(2);
        return t;
    }
    private int dp(int v) { return Math.round(v * getResources().getDisplayMetrics().density); }
}
