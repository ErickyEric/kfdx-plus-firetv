package com.kfdxplus.firetv;

import android.app.Activity;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.OptIn;
import androidx.media3.common.MediaItem;
import androidx.media3.common.MimeTypes;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.ui.PlayerView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/** Full-screen live channel with an On Now / Up Next banner that follows the schedule. */
@OptIn(markerClass = UnstableApi.class)
public class LivePlayerActivity extends Activity {
    private static final String LIVE_API =
        "https://tkx.mp.lura.live/rest/v2/mcp/video/adstQow2m50Oe5ME?anvack=XEaYoy8emZPbxu6R2OHoosjpa9DQj8BP";

    private static class Prog { String t; long b, e; }

    private ExoPlayer player;
    private PlayerView view;
    private LinearLayout banner;
    private TextView nowTitle, nowWhen, nextLine, status;
    private final List<Prog> schedule = new ArrayList<>();
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final SimpleDateFormat hm = new SimpleDateFormat("h:mm a", Locale.US);
    private int retries = 0;
    private final Runnable tick = new Runnable() { @Override public void run() { updateBanner(); ui.postDelayed(this, 30_000); } };
    private final Runnable hideBanner = () -> { if (view != null && !view.isControllerFullyVisible()) banner.setVisibility(View.GONE); };

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON | WindowManager.LayoutParams.FLAG_FULLSCREEN);
        hideSystemBars();

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);
        view = new PlayerView(this);
        view.setKeepScreenOn(true);
        view.setShowNextButton(false);
        view.setShowPreviousButton(false);
        view.setShowFastForwardButton(false);
        view.setShowRewindButton(false);
        view.setControllerShowTimeoutMs(3500);
        root.addView(view, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        // On Now / Up Next banner, top of the screen
        banner = new LinearLayout(this);
        banner.setOrientation(LinearLayout.VERTICAL);
        banner.setPadding(dp(40), dp(28), dp(40), dp(24));
        GradientDrawable fade = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, new int[]{Color.argb(200, 0, 0, 0), Color.argb(0, 0, 0, 0)});
        banner.setBackground(fade);
        TextView live = label(14, Color.parseColor("#e2483d"), true);
        live.setText("●  LIVE  ·  KFDX+");
        nowTitle = label(30, Color.WHITE, true);
        nowWhen = label(15, Color.parseColor("#c9d2ea"), false);
        nextLine = label(15, Color.parseColor("#f2b632"), false);
        nextLine.setPadding(0, dp(8), 0, 0);
        banner.addView(live); banner.addView(nowTitle); banner.addView(nowWhen); banner.addView(nextLine);
        root.addView(banner, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP));

        status = label(20, Color.WHITE, false);
        status.setGravity(Gravity.CENTER);
        status.setPadding(dp(40), dp(16), dp(40), dp(16));
        status.setVisibility(View.GONE);
        root.addView(status, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER));

        view.setControllerVisibilityListener((PlayerView.ControllerVisibilityListener) v -> {
            if (v == View.VISIBLE) banner.setVisibility(View.VISIBLE); else ui.postDelayed(hideBanner, 600);
        });
        setContentView(root);

        String url = null;
        try {
            JSONObject cfg = new JSONObject(getIntent().getStringExtra("cfg"));
            url = cfg.optString("url", null);
            JSONArray arr = cfg.optJSONArray("schedule");
            if (arr != null) for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.getJSONObject(i);
                Prog p = new Prog(); p.t = o.optString("t"); p.b = o.optLong("b"); p.e = o.optLong("e");
                schedule.add(p);
            }
        } catch (Exception ignored) { }

        player = new ExoPlayer.Builder(this).build();
        view.setPlayer(player);
        player.addListener(new Player.Listener() {
            @Override public void onPlaybackStateChanged(int s) {
                if (s == Player.STATE_READY) { retries = 0; status.setVisibility(View.GONE); }
                if (s == Player.STATE_BUFFERING && player.getCurrentPosition() == 0) showStatus("Tuning in…");
            }
            @Override public void onPlayerError(PlaybackException error) {
                if (error.errorCode == PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW) {
                    player.seekToDefaultPosition(); player.prepare(); return;
                }
                if (retries++ < 3) { showStatus("Reconnecting…"); refreshAndPlay(); }   // signed links expire; grab a fresh one
                else showStatus("The live channel won't load right now.\nPress Back and try again in a minute.");
            }
        });

        updateBanner();
        ui.postDelayed(tick, 30_000);
        ui.postDelayed(hideBanner, 8000);
        if (url != null && !url.isEmpty()) start(url); else refreshAndPlay();
        view.requestFocus();
    }

    private void start(String url) {
        showStatus("Tuning in…");
        player.setMediaItem(new MediaItem.Builder().setUri(Uri.parse(url)).setMimeType(MimeTypes.APPLICATION_M3U8)
                .setLiveConfiguration(new MediaItem.LiveConfiguration.Builder().build()).build());
        player.prepare();
        player.setPlayWhenReady(true);
    }

    private void refreshAndPlay() {
        new Thread(() -> {
            try {
                HttpURLConnection c = (HttpURLConnection) new URL(LIVE_API + "&rnd=" + System.currentTimeMillis()).openConnection();
                c.setConnectTimeout(10000); c.setReadTimeout(15000);
                InputStream in = c.getInputStream();
                ByteArrayOutputStream b = new ByteArrayOutputStream(); byte[] buf = new byte[8192]; int n;
                while ((n = in.read(buf)) > 0) b.write(buf, 0, n);
                String t = b.toString("UTF-8");
                JSONObject j = new JSONObject(t.substring(t.indexOf('(') + 1, t.lastIndexOf(')')));
                final String fresh = j.getJSONArray("published_urls").getJSONObject(0).getString("embed_url");
                ui.post(() -> { if (!isFinishing()) start(fresh); });
            } catch (Exception e) {
                ui.postDelayed(() -> { if (!isFinishing()) showStatus("Can't reach the live channel.\nCheck the internet connection, then press Back and try again."); }, 0);
            }
        }).start();
    }

    private void updateBanner() {
        long now = System.currentTimeMillis();
        Prog on = null, next = null;
        for (Prog p : schedule) if (p.b <= now && p.e > now) { on = p; break; }
        for (Prog p : schedule) if (p.b >= (on != null ? on.e - 60_000 : now) && p != on && (p.e - p.b) > 15 * 60_000) { next = p; break; }
        nowTitle.setText(on != null ? on.t : "KFDX 3");
        if (on != null) {
            long left = Math.max(1, Math.round((on.e - now) / 60_000.0));
            nowWhen.setText(hm.format(new Date(on.b)) + " – " + hm.format(new Date(on.e)) + "  ·  " + left + " min left");
        } else nowWhen.setText("Live from Wichita Falls");
        if (next != null) { nextLine.setVisibility(View.VISIBLE); nextLine.setText("UP NEXT  " + hm.format(new Date(next.b)) + "  ·  " + next.t); }
        else nextLine.setVisibility(View.GONE);
    }

    private void showStatus(String s) {
        status.setText(s);
        GradientDrawable bg = new GradientDrawable(); bg.setColor(Color.argb(190, 11, 19, 38)); bg.setCornerRadius(dp(12));
        status.setBackground(bg);
        status.setVisibility(View.VISIBLE);
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        int k = event.getKeyCode();
        if (k == KeyEvent.KEYCODE_BACK || k == KeyEvent.KEYCODE_ESCAPE) {
            if (event.getAction() == KeyEvent.ACTION_UP) {
                if (view.isControllerFullyVisible()) view.hideController(); else finish();
            }
            return true;
        }
        // any button brings up the On Now / Up Next banner along with the controls
        if (event.getAction() == KeyEvent.ACTION_DOWN) { ui.removeCallbacks(hideBanner); banner.setVisibility(View.VISIBLE); }
        return view.dispatchKeyEvent(event) || super.dispatchKeyEvent(event);
    }

    @Override public void onWindowFocusChanged(boolean hasFocus) { super.onWindowFocusChanged(hasFocus); if (hasFocus) hideSystemBars(); }
    @Override protected void onPause() { super.onPause(); if (player != null) player.pause(); }
    @Override protected void onResume() { super.onResume(); if (player != null) { player.seekToDefaultPosition(); player.play(); } }
    @Override protected void onDestroy() { super.onDestroy(); ui.removeCallbacksAndMessages(null); if (player != null) { player.release(); player = null; } }

    private void hideSystemBars() {
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_FULLSCREEN
                | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION);
    }
    private TextView label(int sp, int color, boolean bold) {
        TextView t = new TextView(this);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(color);
        if (bold) t.setTypeface(t.getTypeface(), android.graphics.Typeface.BOLD);
        t.setShadowLayer(6, 0, 2, Color.BLACK);
        t.setMaxLines(2);
        return t;
    }
    private int dp(int v) { return Math.round(v * getResources().getDisplayMetrics().density); }
}
