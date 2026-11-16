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

/**
 * Foreground service that keeps the HTTP/WebDAV server, mDNS responder and internet tunnel
 * alive 24/7 in background even when the screen is turned off, and automatically reconnects on network changes.
 */
public class ServerService extends Service {

    public static final int PORT = 8080;
    public static final String ACTION_STOP = "com.nexshare.STOP";
    private static final String CHANNEL = "nexshare";

    public static volatile boolean running;
    public static volatile String lastError;
    public static volatile long lastActivityTime = System.currentTimeMillis();

    public static void markActivity() {
        lastActivityTime = System.currentTimeMillis();
    }

    public static boolean isEcoMode() {
        return (System.currentTimeMillis() - lastActivityTime) > 45000;
    }

    private final HttpServer[] server = new HttpServer[1];
    private MdnsResponder mdns;
    private PowerManager.WakeLock wakeLock;
    private WifiManager.WifiLock wifiLock;
    private NsdManager nsd;
    private NsdManager.RegistrationListener nsdListener;
    private ConnectivityManager connMgr;
    private ConnectivityManager.NetworkCallback networkCallback;

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
            text = "http://" + MdnsResponder.HOST + ":" + PORT + "  •  " + ip.getHostAddress() + ":" + PORT;
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
            HttpServer s = new HttpServer(new WebApi(this, server));
            server[0] = s;
            s.start(PORT);
            mdns = new MdnsResponder(this);
            mdns.start();
            registerNsd();
            DownloadManager.get(this).startup();
            applyStayOn();
            TunnelManager.get(this).restoreIfEnabled();
            AutoUpdater.get(this).start();

            // Acquire CPU wake lock so the server executes continuously with screen off
            PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
            if (pm != null) {
                wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "nexshare:server");
                wakeLock.setReferenceCounted(false);
                wakeLock.acquire();
            }

            // Acquire Wi-Fi lock so Wi-Fi does not sleep or drop packets with screen off
            // Using WIFI_MODE_FULL allows hardware 802.11 power saving when idle while keeping server reachable
            WifiManager wm = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
            if (wm != null) {
                wifiLock = wm.createWifiLock(WifiManager.WIFI_MODE_FULL, "nexshare:wifi");
                wifiLock.setReferenceCounted(false);
                wifiLock.acquire();
            }

            registerNetworkMonitoring();

            running = true;
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

    @Override
    public void onDestroy() {
        running = false;
        unregisterNetworkMonitoring();
        AutoUpdater.get(this).stop();
        if (server[0] != null) server[0].stop();
        server[0] = null;
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
        super.onDestroy();
    }
}
