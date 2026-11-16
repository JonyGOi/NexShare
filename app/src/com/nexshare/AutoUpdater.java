package com.nexshare;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

/**
 * Background auto-updater for NexShare.
 * Periodically checks GitHub releases. When a new version is detected:
 * - Monitors TransferTracker to ensure NO file transfers are active.
 * - Confirms sustained idle time (at least 15s) before proceeding.
 * - Downloads the APK in background.
 * - Verifies again that no file transfers started during download.
 * - On Root edition: silently updates via `pm install -r -g` and revives service.
 * - On Standard edition: pre-downloads APK and notifies user to apply with 1 tap.
 */
public class AutoUpdater {

    private static final String GITHUB_API_LATEST = "https://api.github.com/repos/JonyGOi/NexShare/releases/latest";
    private static final long CHECK_INTERVAL_MS = 2 * 3600 * 1000; // 2 hours
    private static final long INITIAL_DELAY_MS = 25000; // 25 seconds after server start
    private static final long IDLE_COOLDOWN_MS = 15000; // 15 seconds of quiet time

    private static AutoUpdater instance;
    private final Context ctx;
    private final Object lock = new Object();
    private Thread worker;
    private volatile boolean running = false;

    public static synchronized AutoUpdater get(Context context) {
        if (instance == null) {
            instance = new AutoUpdater(context.getApplicationContext());
        }
        return instance;
    }

    private AutoUpdater(Context context) {
        this.ctx = context;
    }

    public synchronized void start() {
        if (running && worker != null && worker.isAlive()) return;
        running = true;
        worker = new Thread(new Runnable() {
            @Override
            public void run() {
                loop();
            }
        }, "nexshare-autoupdater");
        worker.setDaemon(true);
        worker.start();
    }

    public synchronized void stop() {
        running = false;
        synchronized (lock) {
            lock.notifyAll();
        }
        if (worker != null) {
            worker.interrupt();
            worker = null;
        }
    }

    public void triggerCheck() {
        synchronized (lock) {
            lock.notifyAll();
        }
    }

    private void loop() {
        // Initial delay after server startup
        try {
            synchronized (lock) {
                lock.wait(INITIAL_DELAY_MS);
            }
        } catch (InterruptedException e) {
            return;
        }

        while (running) {
            try {
                if (Settings.autoUpdateEnabled(ctx)) {
                    checkAndApply();
                }
            } catch (Throwable ignored) { }

            try {
                synchronized (lock) {
                    lock.wait(CHECK_INTERVAL_MS);
                }
            } catch (InterruptedException e) {
                if (!running) break;
            }
        }
    }

    /**
     * Checks for a newer GitHub release and performs the update if found and idle.
     */
    public void checkAndApply() {
        if ("downloading".equals(WebApi.UpdateTask.state) || "installing".equals(WebApi.UpdateTask.state)) {
            return;
        }

        try {
            HttpURLConnection conn = (HttpURLConnection) new URL(GITHUB_API_LATEST).openConnection();
            conn.setRequestProperty("User-Agent", "NexShare/" + Flavor.VERSION);
            conn.setRequestProperty("Accept", "application/vnd.github+json");
            conn.setConnectTimeout(10000);
            conn.setReadTimeout(15000);
            int code = conn.getResponseCode();
            if (code != 200) return;

            InputStream in = conn.getInputStream();
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
            String json = new String(bo.toByteArray(), StandardCharsets.UTF_8);
            JSONObject rel = new JSONObject(json);

            String tag = rel.optString("tag_name", "");
            String latestVer = tag.startsWith("v") ? tag.substring(1) : tag;
            if (!isNewerVersion(latestVer, Flavor.VERSION)) {
                return;
            }

            JSONArray assets = rel.optJSONArray("assets");
            if (assets == null) return;
            String downloadUrl = null;
            long assetSize = 0;
            for (int i = 0; i < assets.length(); i++) {
                JSONObject a = assets.getJSONObject(i);
                String aname = a.optString("name", "");
                if (aname.endsWith(".apk")) {
                    boolean isRootAsset = aname.toLowerCase(Locale.ROOT).contains("root");
                    if (Flavor.ROOT == isRootAsset) {
                        downloadUrl = a.optString("browser_download_url", null);
                        assetSize = a.optLong("size", 0);
                        break;
                    }
                }
            }

            if (downloadUrl == null || downloadUrl.isEmpty()) return;

            WebApi.UpdateTask.version = latestVer;

            // Wait until any active file transfers (Web API, WebDAV, Download Station) finish
            while (running && TransferTracker.isBusy(ctx)) {
                WebApi.UpdateTask.state = "waiting_idle";
                WebApi.UpdateTask.message = "Update v" + latestVer + " detected. Waiting for active file transfers to complete...";
                try {
                    synchronized (lock) {
                        lock.wait(5000);
                    }
                } catch (InterruptedException e) {
                    return;
                }
            }

            // Ensure sustained idle period without any transfers
            long idleTarget = System.currentTimeMillis() + IDLE_COOLDOWN_MS;
            while (running && System.currentTimeMillis() < idleTarget) {
                if (TransferTracker.isBusy(ctx, 1000)) {
                    idleTarget = System.currentTimeMillis() + IDLE_COOLDOWN_MS;
                }
                try {
                    synchronized (lock) {
                        lock.wait(1000);
                    }
                } catch (InterruptedException e) {
                    return;
                }
            }

            if (!running || !Settings.autoUpdateEnabled(ctx)) return;

            // Begin downloading the APK to cache
            File apkDir = new File(ctx.getCacheDir(), "apk");
            apkDir.mkdirs();
            File apk = new File(apkDir, "update.apk");

            WebApi.UpdateTask.state = "downloading";
            WebApi.UpdateTask.progress = 0;
            WebApi.UpdateTask.downloaded = 0;
            WebApi.UpdateTask.total = assetSize > 0 ? assetSize : 48000000;
            WebApi.UpdateTask.message = "Downloading update v" + latestVer + "...";
            WebApi.UpdateTask.error = "";

            HttpURLConnection dlConn = (HttpURLConnection) new URL(downloadUrl).openConnection();
            dlConn.setRequestProperty("User-Agent", "NexShare/" + Flavor.VERSION);
            dlConn.setInstanceFollowRedirects(true);
            dlConn.setConnectTimeout(15000);
            dlConn.setReadTimeout(60000);
            int dlCode = dlConn.getResponseCode();
            if (dlCode >= 400) {
                WebApi.UpdateTask.state = "error";
                WebApi.UpdateTask.error = "HTTP error " + dlCode + " downloading update";
                return;
            }

            long len = dlConn.getContentLengthLong();
            if (len > 0) WebApi.UpdateTask.total = len;

            try (InputStream din = dlConn.getInputStream(); OutputStream dout = new FileOutputStream(apk)) {
                byte[] dbuf = new byte[65536];
                int dn;
                long done = 0;
                while ((dn = din.read(dbuf)) > 0) {
                    if (!running) {
                        apk.delete();
                        return;
                    }
                    dout.write(dbuf, 0, dn);
                    done += dn;
                    WebApi.UpdateTask.downloaded = done;
                    if (WebApi.UpdateTask.total > 0) {
                        WebApi.UpdateTask.progress = (int) Math.min(99, (done * 100) / WebApi.UpdateTask.total);
                    }
                }
            }

            WebApi.UpdateTask.progress = 100;

            // Re-verify that NO file transfers started while the APK was downloading
            while (running && TransferTracker.isBusy(ctx)) {
                WebApi.UpdateTask.state = "waiting_idle";
                WebApi.UpdateTask.message = "Update v" + latestVer + " downloaded. Waiting for active transfers to finish before installing...";
                try {
                    synchronized (lock) {
                        lock.wait(5000);
                    }
                } catch (InterruptedException e) {
                    return;
                }
            }

            if (!running) return;

            if (Flavor.ROOT && RootShell.available()) {
                WebApi.UpdateTask.state = "installing";
                WebApi.UpdateTask.message = "Installing update v" + latestVer + " via Root... NexShare will restart.";
                String out = RootShell.main().exec("pm install -r -g " + RootShell.q(apk.getAbsolutePath()), 180000);
                apk.delete();
                if (out.toLowerCase(Locale.ROOT).contains("success")) {
                    WebApi.UpdateTask.state = "done";
                    WebApi.UpdateTask.message = "Updated successfully! Server is restarting...";
                    try {
                        RootShell.main().exec("am start-foreground-service -n com.nexshare/.ServerService", 5000);
                    } catch (Exception ignored) { }
                } else {
                    WebApi.UpdateTask.state = "error";
                    WebApi.UpdateTask.error = "Root install failed: " + out.trim();
                }
            } else {
                WebApi.UpdateTask.state = "prompt";
                WebApi.UpdateTask.message = "Update v" + latestVer + " downloaded. Ready to install.";
                notifyUpdateReady(latestVer, apk);
            }
        } catch (Exception e) {
            WebApi.UpdateTask.state = "error";
            WebApi.UpdateTask.error = "Auto-update error: " + e.getMessage();
        }
    }

    private void notifyUpdateReady(String ver, File apk) {
        try {
            NotificationManager nm = (NotificationManager) ctx.getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm == null) return;
            String chanId = "nexshare_updates";
            if (Build.VERSION.SDK_INT >= 26) {
                NotificationChannel chan = new NotificationChannel(chanId, "NexShare Updates", NotificationManager.IMPORTANCE_HIGH);
                nm.createNotificationChannel(chan);
            }
            Notification.Builder b = Build.VERSION.SDK_INT >= 26 ? new Notification.Builder(ctx, chanId) : new Notification.Builder(ctx);
            Uri uri = NexFileProvider.uriFor(ctx, apk);
            Intent i = new Intent(Intent.ACTION_VIEW);
            i.setDataAndType(uri, "application/vnd.android.package-archive");
            i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK);
            int flags = Build.VERSION.SDK_INT >= 23 ? PendingIntent.FLAG_IMMUTABLE : 0;
            PendingIntent pi = PendingIntent.getActivity(ctx, 2, i, flags);

            b.setSmallIcon(R.drawable.ic_notify)
                .setContentTitle("NexShare v" + ver + " is ready to install")
                .setContentText("Tap to finish updating NexShare")
                .setContentIntent(pi)
                .setAutoCancel(true);
            nm.notify(2, b.build());
        } catch (Exception ignored) { }
    }

    public static boolean isNewerVersion(String latest, String current) {
        if (latest == null || current == null) return false;
        String l = latest.startsWith("v") ? latest.substring(1) : latest;
        String c = current.startsWith("v") ? current.substring(1) : current;
        if (l.equals(c)) return false;
        String[] lParts = l.split("[.\\-_]");
        String[] cParts = c.split("[.\\-_]");
        int len = Math.max(lParts.length, cParts.length);
        for (int i = 0; i < len; i++) {
            int lNum = 0, cNum = 0;
            if (i < lParts.length) {
                try { lNum = Integer.parseInt(lParts[i]); } catch (Exception e) { return !l.equals(c); }
            }
            if (i < cParts.length) {
                try { cNum = Integer.parseInt(cParts[i]); } catch (Exception e) { return !l.equals(c); }
            }
            if (lNum > cNum) return true;
            if (lNum < cNum) return false;
        }
        return false;
    }
}
