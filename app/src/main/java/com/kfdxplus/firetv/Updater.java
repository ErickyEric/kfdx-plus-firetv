package com.kfdxplus.firetv;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;

import androidx.core.content.FileProvider;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * Checks GitHub Releases for a newer build of this app, downloads it, and hands it to the Fire TV installer.
 * Builds are numbered by GitHub's run number, which is also this app's versionCode.
 */
public class Updater {
    private static final String LATEST = "https://api.github.com/repos/ErickyEric/kfdx-plus-firetv/releases/latest";
    private final Activity a;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private boolean busy = false;
    private String pendingUrl = null;   // waiting on the one-time "install unknown apps" permission

    public Updater(Activity a) { this.a = a; }

    public static int currentBuild() { return BuildConfig.VERSION_CODE; }

    /** quiet = launch-time check: only speak up when there's an update. */
    public void check(final boolean quiet) {
        if (busy) return;
        busy = true;
        new Thread(() -> {
            try {
                HttpURLConnection c = (HttpURLConnection) new URL(LATEST).openConnection();
                c.setRequestProperty("Accept", "application/vnd.github+json");
                c.setConnectTimeout(10000);
                c.setReadTimeout(15000);
                if (c.getResponseCode() != 200) throw new IOException("HTTP " + c.getResponseCode());
                JSONObject j = new JSONObject(readAll(c.getInputStream()));
                final int latest = Integer.parseInt(j.getString("tag_name").replaceAll("\\D", ""));
                String url = null;
                JSONArray assets = j.getJSONArray("assets");
                for (int i = 0; i < assets.length(); i++) {
                    JSONObject as = assets.getJSONObject(i);
                    if (as.getString("name").endsWith(".apk")) url = as.getString("browser_download_url");
                }
                final String apk = url;
                ui.post(() -> {
                    busy = false;
                    if (a.isFinishing()) return;
                    if (apk != null && latest > currentBuild()) offer(latest, apk);
                    else if (!quiet) say("You're up to date", "This is the newest version of KFDX+ for Fire TV (build " + currentBuild() + ").");
                });
            } catch (Exception e) {
                ui.post(() -> {
                    busy = false;
                    if (!quiet && !a.isFinishing()) say("Couldn't check for updates", "Make sure the Fire Stick is connected to the internet, then try again.");
                });
            }
        }).start();
    }

    private void offer(int build, String url) {
        new AlertDialog.Builder(a)
                .setTitle("Update available")
                .setMessage("A new version of KFDX+ is ready (build " + build + "). You have build " + currentBuild() + ".")
                .setPositiveButton("Install now", (d, w) -> download(url))
                .setNegativeButton("Later", null)
                .show();
    }

    /** Call from the activity's onResume: picks the update back up after the person grants install permission. */
    public void onResume() {
        if (pendingUrl != null && canInstall()) {
            String u = pendingUrl;
            pendingUrl = null;
            download(u);
        }
    }

    private boolean canInstall() {
        return Build.VERSION.SDK_INT < 26 || a.getPackageManager().canRequestPackageInstalls();
    }

    private void download(final String url) {
        if (!canInstall()) { askPermission(url); return; }
        final AlertDialog progress = new AlertDialog.Builder(a)
                .setTitle("Downloading update")
                .setMessage("Starting…")
                .setCancelable(false)
                .show();
        new Thread(() -> {
            try {
                File dir = Build.VERSION.SDK_INT >= 24 ? new File(a.getCacheDir(), "updates") : a.getExternalFilesDir(null);
                if (dir != null) dir.mkdirs();
                final File out = new File(dir, "kfdx-plus.apk");
                HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
                c.setInstanceFollowRedirects(true);
                c.setConnectTimeout(15000);
                c.setReadTimeout(30000);
                if (c.getResponseCode() != 200) throw new IOException("HTTP " + c.getResponseCode());
                final int total = c.getContentLength();
                try (InputStream in = c.getInputStream(); OutputStream os = new FileOutputStream(out)) {
                    byte[] buf = new byte[64 * 1024];
                    int n, done = 0, lastPct = -1;
                    while ((n = in.read(buf)) > 0) {
                        os.write(buf, 0, n);
                        done += n;
                        if (total > 0) {
                            final int pct = (int) (done * 100L / total);
                            if (pct != lastPct) { lastPct = pct; ui.post(() -> progress.setMessage(pct + "%")); }
                        }
                    }
                }
                ui.post(() -> { progress.dismiss(); install(out); });
            } catch (Exception e) {
                ui.post(() -> { progress.dismiss(); say("The update didn't download", "Check the Fire Stick's internet connection and try again."); });
            }
        }).start();
    }

    private void install(File apk) {
        Uri uri = Build.VERSION.SDK_INT >= 24
                ? FileProvider.getUriForFile(a, a.getPackageName() + ".files", apk)
                : Uri.fromFile(apk);
        Intent i = new Intent(Intent.ACTION_VIEW)
                .setDataAndType(uri, "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK);
        try { a.startActivity(i); }
        catch (ActivityNotFoundException e) { say("Couldn't start the installer", "Try again, or install the update with Downloader."); }
    }

    private void askPermission(final String url) {
        pendingUrl = url;
        new AlertDialog.Builder(a)
                .setTitle("One-time setup")
                .setMessage("To install its own updates, KFDX+ needs permission to install apps.\n\nOn the next screen, turn KFDX+ ON, then press Back. The update will start on its own.")
                .setPositiveButton("Continue", (d, w) -> {
                    try {
                        a.startActivity(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + a.getPackageName())));
                    } catch (Exception e) {
                        say("Turn this on in Settings",
                            "Go to Settings → My Fire TV → Developer Options → Install unknown apps, turn KFDX+ ON, then come back and choose Check for app updates.");
                    }
                })
                .setNegativeButton("Later", (d, w) -> pendingUrl = null)
                .show();
    }

    private void say(String title, String msg) {
        new AlertDialog.Builder(a).setTitle(title).setMessage(msg).setPositiveButton("OK", null).show();
    }

    private static String readAll(InputStream in) throws IOException {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) > 0) b.write(buf, 0, n);
        return b.toString("UTF-8");
    }
}
