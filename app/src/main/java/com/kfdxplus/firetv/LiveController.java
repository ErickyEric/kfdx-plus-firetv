package com.kfdxplus.firetv;

import android.app.Activity;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.WebView;
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
import androidx.media3.ui.AspectRatioFrameLayout;
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

/**
 * The live channel, played by the Fire TV's own video player and laid over the web page.
 * Small mode: sits exactly on the Live page's player box and plays right away.
 * Full mode: the same player fills the screen with On Now / Up Next; Back shrinks it again.
 */
@OptIn(markerClass = UnstableApi.class)
public class LiveController {
    private static final String LIVE_API =
        "https://tkx.mp.lura.live/rest/v2/mcp/video/adstQow2m50Oe5ME?anvack=XEaYoy8emZPbxu6R2OHoosjpa9DQj8BP";
    private static class Prog { String t; long b, e; }

    private final Activity a;
    private final FrameLayout root;
    private final WebView web;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final SimpleDateFormat hm = new SimpleDateFormat("h:mm a", Locale.US);
    private final List<Prog> schedule = new ArrayList<>();

    private FrameLayout box;
    private PlayerView view;
    private ExoPlayer player;
    private LinearLayout banner;
    private TextView chip, nowTitle, nowWhen, nextLine, status;
    private boolean full = false;
    private int retries = 0;
    private int rx, ry, rw, rh;   // small-box position in screen pixels

    private final Runnable tick = new Runnable() { @Override public void run() { updateBanner(); ui.postDelayed(this, 30_000); } };

    public LiveController(Activity a, FrameLayout root, WebView web) { this.a = a; this.root = root; this.web = web; }

    public boolean isActive() { return player != null; }

    /** cfg: {url, schedule:[{t,b,e}], x,y,w,h, dpr} — x/y/w/h in CSS pixels of the web page. */
    public void start(JSONObject cfg) {
        readSchedule(cfg.optJSONArray("schedule"));
        readRect(cfg);
        if (player == null) build();
        full = false;
        applyLayout();
        retries = 0;
        String url = cfg.optString("url", "");
        if (url.isEmpty()) refreshAndPlay(); else play(url);
        ui.removeCallbacks(tick);
        ui.post(tick);
    }

    public void move(JSONObject r) { readRect(r); if (!full) applyLayout(); }

    public void setFull(boolean f) {
        if (player == null) return;
        full = f;
        applyLayout();
        if (f) {
            view.setUseController(true);
            view.setFocusable(true);
            view.requestFocus();
            view.showController();
        } else {
            view.hideController();
            view.setUseController(false);
            view.setFocusable(false);
            web.requestFocus();
            web.evaluateJavascript("(function(){var b=document.getElementById('lbox');if(b&&typeof focusEl==='function')focusEl(b);})();", null);
        }
    }

    public void stop() {
        ui.removeCallbacks(tick);
        if (player != null) { player.release(); player = null; }
        if (box != null) { root.removeView(box); box = null; }
        full = false;
        web.requestFocus();
    }

    public void pause() { if (player != null) player.pause(); }
    public void resume() { if (player != null) { player.seekToDefaultPosition(); player.play(); } }

    /** Keys while full screen go to the player; Back shrinks back to the box. */
    public boolean handleKey(KeyEvent e) {
        if (player == null || !full) return false;
        int k = e.getKeyCode();
        if (k == KeyEvent.KEYCODE_BACK || k == KeyEvent.KEYCODE_ESCAPE) {
            if (e.getAction() == KeyEvent.ACTION_UP) {
                if (view.isControllerFullyVisible()) view.hideController(); else setFull(false);
            }
            return true;
        }
        if (e.getAction() == KeyEvent.ACTION_DOWN) banner.setVisibility(View.VISIBLE);
        return view.dispatchKeyEvent(e);
    }

    // ---------------------------------------------------------------------------------

    private void build() {
        box = new FrameLayout(a);
        box.setBackgroundColor(Color.BLACK);
        view = new PlayerView(a);
        view.setUseController(false);
        view.setFocusable(false);
        view.setShowNextButton(false);
        view.setShowPreviousButton(false);
        view.setShowFastForwardButton(false);
        view.setShowRewindButton(false);
        view.setControllerShowTimeoutMs(3500);
        view.setResizeMode(AspectRatioFrameLayout.RESIZE_MODE_FIT);
        view.setKeepScreenOn(true);
        box.addView(view, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        chip = label(12, Color.WHITE, true);
        chip.setText("●  LIVE");
        GradientDrawable red = new GradientDrawable(); red.setColor(Color.parseColor("#e2483d")); red.setCornerRadius(dp(20));
        chip.setBackground(red);
        chip.setPadding(dp(10), dp(4), dp(12), dp(4));
        chip.setShadowLayer(0, 0, 0, 0);
        FrameLayout.LayoutParams cp = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP | Gravity.START);
        cp.setMargins(dp(10), dp(10), 0, 0);
        box.addView(chip, cp);

        banner = new LinearLayout(a);
        banner.setOrientation(LinearLayout.VERTICAL);
        banner.setPadding(dp(40), dp(28), dp(40), dp(24));
        banner.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, new int[]{Color.argb(200, 0, 0, 0), Color.argb(0, 0, 0, 0)}));
        TextView live = label(14, Color.parseColor("#e2483d"), true);
        live.setText("●  LIVE  ·  KFDX+");
        nowTitle = label(30, Color.WHITE, true);
        nowWhen = label(15, Color.parseColor("#c9d2ea"), false);
        nextLine = label(15, Color.parseColor("#f2b632"), false);
        nextLine.setPadding(0, dp(8), 0, 0);
        banner.addView(live); banner.addView(nowTitle); banner.addView(nowWhen); banner.addView(nextLine);
        box.addView(banner, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP));

        status = label(16, Color.WHITE, false);
        status.setGravity(Gravity.CENTER);
        status.setPadding(dp(24), dp(12), dp(24), dp(12));
        GradientDrawable sb = new GradientDrawable(); sb.setColor(Color.argb(190, 11, 19, 38)); sb.setCornerRadius(dp(12));
        status.setBackground(sb);
        status.setVisibility(View.GONE);
        box.addView(status, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER));

        view.setControllerVisibilityListener((PlayerView.ControllerVisibilityListener) v -> {
            if (full) banner.setVisibility(v == View.VISIBLE ? View.VISIBLE : View.GONE);
        });
        root.addView(box);

        player = new ExoPlayer.Builder(a).build();
        view.setPlayer(player);
        player.addListener(new Player.Listener() {
            @Override public void onPlaybackStateChanged(int s) {
                if (s == Player.STATE_READY) { retries = 0; status.setVisibility(View.GONE); }
            }
            @Override public void onPlayerError(PlaybackException error) {
                if (error.errorCode == PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW) { player.seekToDefaultPosition(); player.prepare(); return; }
                if (retries++ < 3) { showStatus("Reconnecting…"); refreshAndPlay(); }      // signed links expire; grab a fresh one
                else showStatus("The live channel won't load right now. Try again in a minute.");
            }
        });
    }

    private void applyLayout() {
        if (box == null) return;
        FrameLayout.LayoutParams lp;
        if (full) {
            lp = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
            chip.setVisibility(View.GONE);
            banner.setVisibility(View.VISIBLE);
            updateBanner();
        } else {
            lp = new FrameLayout.LayoutParams(Math.max(1, rw), Math.max(1, rh), Gravity.TOP | Gravity.START);
            lp.leftMargin = rx; lp.topMargin = ry;
            chip.setVisibility(View.VISIBLE);
            banner.setVisibility(View.GONE);
        }
        box.setLayoutParams(lp);
        box.bringToFront();
    }

    private void readRect(JSONObject r) {
        double dpr = r.optDouble("dpr", a.getResources().getDisplayMetrics().density);
        int[] loc = new int[2];
        web.getLocationInWindow(loc);
        rx = (int) Math.round(r.optDouble("x", 0) * dpr) + loc[0];
        ry = (int) Math.round(r.optDouble("y", 0) * dpr) + loc[1];
        rw = (int) Math.round(r.optDouble("w", 0) * dpr);
        rh = (int) Math.round(r.optDouble("h", 0) * dpr);
    }

    private void readSchedule(JSONArray arr) {
        if (arr == null) return;
        schedule.clear();
        for (int i = 0; i < arr.length(); i++) {
            JSONObject o = arr.optJSONObject(i);
            if (o == null) continue;
            Prog p = new Prog(); p.t = o.optString("t"); p.b = o.optLong("b"); p.e = o.optLong("e");
            schedule.add(p);
        }
    }

    private void play(String url) {
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
                ui.post(() -> { if (player != null) play(fresh); });
            } catch (Exception e) {
                ui.post(() -> { if (player != null) showStatus("Can't reach the live channel. Check the internet connection."); });
            }
        }).start();
    }

    private void updateBanner() {
        if (nowTitle == null) return;
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

    private void showStatus(String s) { status.setText(s); status.setVisibility(View.VISIBLE); }

    private TextView label(int sp, int color, boolean bold) {
        TextView t = new TextView(a);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(color);
        if (bold) t.setTypeface(t.getTypeface(), Typeface.BOLD);
        t.setShadowLayer(6, 0, 2, Color.BLACK);
        t.setMaxLines(2);
        return t;
    }
    private int dp(int v) { return Math.round(v * a.getResources().getDisplayMetrics().density); }
}
