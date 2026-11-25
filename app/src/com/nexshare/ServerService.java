package com.nexshare;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.net.ConnectivityManager;
import android.net.LinkProperties;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.net.nsd.NsdManager;
import android.net.nsd.NsdServiceInfo;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;

import java.net.Inet4Address;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Foreground service that keeps the HTTP/WebDAV server, mDNS responder and internet tunnel
 * alive 24/7 in background even when the screen is turned off, and automatically reconnects on network changes.
 */
public class ServerService extends Service {

    public static final int PORT = 8080;
    public static final int HTTPS_PORT = 8443;
    public static final String ACTION_STOP = "com.nexshare.STOP";
    private static final String CHANNEL = "nexshare";

    public static volatile boolean running;
    public static volatile String lastError;
    public static volatile long lastActivityTime = System.currentTimeMillis();

    public static void markActivity() {
        lastActivityTime = System.currentTimeMillis();
    }

    /** Kept for the UI. The server is always fully awake while the service runs. */
    public static boolean isEcoMode() {
        return false;
    }

    private final HttpServer[] server = new HttpServer[2];
    private MdnsResponder mdns;
    private PowerManager.WakeLock wakeLock;
    private WifiManager.WifiLock wifiLock;
    private NsdManager nsd;
    private NsdManager.RegistrationListener nsdListener;
    private ConnectivityManager connMgr;
    private ConnectivityManager.NetworkCallback networkCallback;

    // Power management
    private final ScheduledExecutorService powerScheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "nexshare:power-mgr");
        t.setDaemon(true);
        return t;
    });
    private volatile boolean hasActiveTransfers = false;
    private volatile boolean screenOff = false;
    private volatile long lastEcoCheck = 0;
    private static final long ECO_MODE_DELAY_MS = 60000; // status only; never releases the locks
    private static final long WAKE_LOCK_RELEASE_DELAY_MS = 300000; // kept for compatibility

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_STOP.equals(intent.getAction())) {
            stopSelf();
            return START_NOT_STICKY;
        }
        goForeground();
        if (server[0] == null) startServer();
        return START_STICKY;
    }

    @Override
    public void onTaskRemoved(Intent rootIntent) {
        // Prevent system from killing the background sharing service when swiped from recents
        if (running) {
            updateNotification();
        }
        super.onTaskRemoved(rootIntent);
    }

    private void goForeground() {
        Notification n = buildNotification();
        if (Build.VERSION.SDK_INT >= 34) startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
        else startForeground(1, n);
    }

    public void updateNotification() {
        if (!running) return;
        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm != null) {
            nm.notify(1, buildNotification());
        }
    }

    private Notification buildNotification() {
        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        Notification.Builder b;
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel chan = new NotificationChannel(CHANNEL, "NexShare server", NotificationManager.IMPORTANCE_LOW);
            chan.setShowBadge(false);
            if (nm != null) nm.createNotificationChannel(chan);
            b = new Notification.Builder(this, CHANNEL);
        } else {
            b = new Notification.Builder(this);
        }
        int piFlags = Build.VERSION.SDK_INT >= 23 ? PendingIntent.FLAG_IMMUTABLE : 0;
        PendingIntent open = PendingIntent.getActivity(this, 0, new Intent(this, MainActivity.class), piFlags);
        PendingIntent stop = PendingIntent.getService(this, 1, new Intent(this, ServerService.class).setAction(ACTION_STOP), piFlags);

        Inet4Address ip = MdnsResponder.lanAddress();
        String text;
        if (ip != null) {
            text = "http://" + ip.getHostAddress() + ":" + PORT + "  •  https://" + ip.getHostAddress() + ":" + HTTPS_PORT;
        } else {
            text = "Waiting for network connection... (auto-reconnecting)";
        }

        b.setSmallIcon(R.drawable.ic_notify)
            .setContentTitle("NexShare is sharing files")
            .setContentText(text)
            .setContentIntent(open)
            .setOngoing(true)
            .addAction(new Notification.Action.Builder(null, "Stop", stop).build());
        return b.build();
    }

    private void startServer() {
        try {
            WebApi api = new WebApi(this, server);
            HttpServer http = new HttpServer(api, null);
            server[0] = http;
            http.start(PORT);

            try {
                javax.net.ssl.SSLContext ssl = createSslContext();
                if (ssl != null) {
                    HttpServer https = new HttpServer(api, ssl);
                    server[1] = https;
                    https.start(HTTPS_PORT);
                }
            } catch (Throwable ignored) { }

            mdns = new MdnsResponder(this);
            mdns.start();
            registerNsd();
            DownloadManager.get(this).startup();

            // Mark the service as running BEFORE restoring the tunnel: the tunnel supervisor
            // loops only while ServerService.running is true. When this flag was set at the
            // end, a service restart restored the saved tunnel setting but the supervisor
            // exited immediately, leaving the public link at Cloudflare 503 until the user
            // toggled the switch.
            running = true;

            applyStayOn();
            TunnelManager.get(this).restoreIfEnabled();
            AutoUpdater.get(this).start();

            // Keep the CPU and Wi-Fi awake for the whole time the server is running.
            // The previous "eco mode" released them after a few idle minutes, which made
            // Android suspend the process and drop HTTP/WebDAV/tunnel connections while
            // the screen was off or the app was in the background.
            acquireWakeLocks();

            registerNetworkMonitoring();
            startPowerScheduler();

            lastError = null;
            updateNotification();
        } catch (Exception e) {
            lastError = e.getMessage();
            running = false;
            server[0] = null;
            stopSelf();
        }
    }

    private void registerNetworkMonitoring() {
        try {
            connMgr = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
            if (connMgr == null) return;
            networkCallback = new ConnectivityManager.NetworkCallback() {
                @Override
                public void onAvailable(Network network) {
                    updateNotification();
                    if (mdns != null) mdns.onNetworkChanged();
                    registerNsd();
                    TunnelManager.get(ServerService.this).onNetworkAvailable();
                    AutoUpdater.get(ServerService.this).triggerCheck();
                }

                @Override
                public void onLost(Network network) {
                    updateNotification();
                    TunnelManager.get(ServerService.this).onNetworkLost();
                }

                @Override
                public void onLinkPropertiesChanged(Network network, LinkProperties linkProperties) {
                    updateNotification();
                    if (mdns != null) mdns.onNetworkChanged();
                }
            };
            if (Build.VERSION.SDK_INT >= 24) {
                connMgr.registerDefaultNetworkCallback(networkCallback);
            } else {
                NetworkRequest req = new NetworkRequest.Builder().addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET).build();
                connMgr.registerNetworkCallback(req, networkCallback);
            }
        } catch (Exception ignored) { }
    }

    private void unregisterNetworkMonitoring() {
        try {
            if (connMgr != null && networkCallback != null) {
                connMgr.unregisterNetworkCallback(networkCallback);
            }
        } catch (Exception ignored) { }
        networkCallback = null;
    }

    // ---------- Power Management ----------

    /** Called when a file transfer starts (HTTP, WebDAV, torrent download/upload, streaming). */
    public static void onTransferStart() {
        if (running) {
            // Update activity time for eco mode detection
            markActivity();
        }
    }

    /** Called when a file transfer ends. */
    public static void onTransferEnd() {
        if (running) {
            markActivity();
        }
    }

    /** Checks if there are any active transfers (HTTP, torrent, streaming, WebDAV). */
    private boolean checkActiveTransfers() {
        // Check HTTP/WebDAV transfers
        if (TransferTracker.getActiveCount() > 0) return true;

        // Check torrent downloads/uploads
        DownloadManager dm = DownloadManager.get(this);
        for (DownloadManager.Task t : dm.getTorrentTasks()) {
            if (t.speed > 0 || t.upSpeed > 0) return true;
            if (t.awaitingSelection || "metadata".equals(t.status) || "choose".equals(t.status)) return true;
            if (t.priorityFile >= 0 && ("downloading".equals(t.status) || "seeding".equals(t.status))) return true;
        }
        return false;
    }

    /** Updates power state based on current activity. */
    private void updatePowerState() {
        boolean wasActive = hasActiveTransfers;
        hasActiveTransfers = checkActiveTransfers();
        long now = System.currentTimeMillis();

        // The server is a 24/7 foreground service: keep the locks regardless of
        // transfers. Android can suspend the CPU and Wi-Fi very quickly when the
        // screen turns off, so releasing them while "idle" breaks remote access.
        acquireWakeLocks();
        lastEcoCheck = now;
        screenOff = isScreenOff();

        // Kept for logs/diagnostics; no lock is released while the service lives.
        if (wasActive && !hasActiveTransfers) {
            scheduleWakeLockRelease();
        }
    }

    /** Legacy entry point kept so old callers cannot accidentally release the locks. */
    private void enterEcoMode() {
        acquireWakeLocks();
    }

    /** Acquires wake locks for active operation. */
    private void acquireWakeLocks() {
        if (wakeLock == null || !wakeLock.isHeld()) {
            PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
            if (pm != null) {
                wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "nexshare:server");
                wakeLock.setReferenceCounted(false);
                wakeLock.acquire();
            }
        }
        if (wifiLock == null || !wifiLock.isHeld()) {
            WifiManager wm = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
            if (wm != null) {
                wifiLock = wm.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "nexshare:wifi");
                wifiLock.setReferenceCounted(false);
                wifiLock.acquire();
            }
        }
    }

    /** Only used by onDestroy. Idle time never releases the locks anymore. */
    private void releaseWakeLocks() {
        if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
        if (wifiLock != null && wifiLock.isHeld()) wifiLock.release();
    }

    /** No-op: keeping this method makes the intent explicit and avoids regressions. */
    private void scheduleWakeLockRelease() {
        if (wakeLock == null || !wakeLock.isHeld()) acquireWakeLocks();
    }

    /** Checks if the screen is currently off. */
    private boolean isScreenOff() {
        PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
        if (pm == null) return false;
        if (Build.VERSION.SDK_INT >= 20) {
            return !pm.isInteractive();
        } else {
            return !pm.isScreenOn();
        }
    }

    /** Starts the periodic power state checker. */
    private void startPowerScheduler() {
        powerScheduler.scheduleAtFixedRate(() -> {
            try {
                updatePowerState();
            } catch (Exception ignored) { }
        }, 10, 10, TimeUnit.SECONDS);
    }

    /** Whitelist app from battery optimizations (Doze mode) in Root edition, and optionally keep physical screen on. */
    private void applyStayOn() {
        if (!Flavor.ROOT) return;
        final boolean keepScreen = Settings.keepScreenOn(this);
        final String pkg = getPackageName();
        new Thread(new Runnable() {
            public void run() {
                try {
                    if (RootShell.available()) {
                        // Whitelist NexShare from Android Doze mode completely
                        RootShell.main().exec("dumpsys deviceidle whitelist +" + pkg, 8000);
                        RootShell.main().exec("am set-inactive " + pkg + " false", 8000);
                        if (keepScreen) {
                            RootShell.main().exec("svc power stayon true", 8000);
                        }
                    }
                } catch (Exception ignored) { }
            }
        }).start();
    }

    /** Advertise as a DNS-SD web service so discovery apps list it. */
    private void registerNsd() {
        try {
            if (nsd != null && nsdListener != null) {
                try {
                    nsd.unregisterService(nsdListener);
                } catch (Exception ignored) { }
            }
            nsd = (NsdManager) getSystemService(Context.NSD_SERVICE);
            if (nsd == null) return;
            NsdServiceInfo info = new NsdServiceInfo();
            info.setServiceName("NexShare");
            info.setServiceType("_http._tcp");
            info.setPort(PORT);
            nsdListener = new NsdManager.RegistrationListener() {
                public void onRegistrationFailed(NsdServiceInfo s, int e) { }
                public void onUnregistrationFailed(NsdServiceInfo s, int e) { }
                public void onServiceRegistered(NsdServiceInfo s) { }
                public void onServiceUnregistered(NsdServiceInfo s) { }
            };
            nsd.registerService(info, NsdManager.PROTOCOL_DNS_SD, nsdListener);
        } catch (Exception ignored) {
            nsdListener = null;
        }
    }

    private javax.net.ssl.SSLContext createSslContext() {
        try {
            java.security.KeyStore ks = java.security.KeyStore.getInstance("PKCS12");
            try (java.io.InputStream in = getAssets().open("keystore.p12")) {
                ks.load(in, "nexshare".toCharArray());
            }
            javax.net.ssl.KeyManagerFactory kmf = javax.net.ssl.KeyManagerFactory.getInstance(javax.net.ssl.KeyManagerFactory.getDefaultAlgorithm());
            kmf.init(ks, "nexshare".toCharArray());
            javax.net.ssl.SSLContext sc = javax.net.ssl.SSLContext.getInstance("TLS");
            sc.init(kmf.getKeyManagers(), null, null);
            return sc;
        } catch (Throwable ignored) {
            return null;
        }
    }

    @Override
    public void onDestroy() {
        running = false;
        unregisterNetworkMonitoring();
        AutoUpdater.get(this).stop();
        if (server[0] != null) server[0].stop();
        if (server[1] != null) server[1].stop();
        server[0] = null;
        server[1] = null;
        if (mdns != null) mdns.stop();
        TunnelManager.get(this).stop();
        DownloadManager.get(this).shutdown();
        if (Flavor.ROOT) {
            new Thread(new Runnable() {
                public void run() {
                    try {
                        if (RootShell.available()) RootShell.main().exec("svc power stayon false", 6000);
                    } catch (Exception ignored) { }
                }
            }).start();
        }
        try {
            if (nsd != null && nsdListener != null) nsd.unregisterService(nsdListener);
        } catch (Exception ignored) { }
        if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
        if (wifiLock != null && wifiLock.isHeld()) wifiLock.release();
        powerScheduler.shutdown();
        super.onDestroy();
    }
}
