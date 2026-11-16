package com.nexshare;

import android.content.Context;
import android.os.Build;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Runs Cloudflare's cloudflared to expose the local web server on the internet.
 * Automatically keeps reconnecting whenever internet drops or network changes.
 */
public class TunnelManager {

    public enum State { OFF, STARTING, CONNECTED, ERROR }

    private static TunnelManager instance;

    private final Context ctx;
    private Process proc;
    private final List<String> log = new ArrayList<>();
    private volatile State state = State.OFF;
    private volatile String url = "";
    private volatile String error = "";

    private volatile String desiredMode = "off";
    private volatile String desiredToken = "";
    private Thread supervisor;
    private final Object reconnectLock = new Object();

    private static final Pattern QUICK_URL = Pattern.compile("https://[a-z0-9-]+\\.trycloudflare\\.com");

    public static synchronized TunnelManager get(Context c) {
        if (instance == null) instance = new TunnelManager(c.getApplicationContext());
        return instance;
    }

    private TunnelManager(Context ctx) {
        this.ctx = ctx;
    }

    public synchronized State state() {
        return state;
    }

    public synchronized String url() {
        return url;
    }

    public synchronized String error() {
        return error;
    }

    public synchronized String logTail() {
        StringBuilder sb = new StringBuilder();
        int from = Math.max(0, log.size() - 40);
        for (int i = from; i < log.size(); i++) sb.append(log.get(i)).append('\n');
        return sb.toString();
    }

    /** The bundled cloudflared binary, extracted to the app's native lib dir and executable. */
    public File binary() {
        File f = new File(ctx.getApplicationInfo().nativeLibraryDir, "libcloudflared.so");
        if (f.exists() && f.canExecute()) return f;

        File local = new File(new File(ctx.getFilesDir(), "bin"), "cloudflared");
        if (local.exists() && local.canExecute()) return local;

        // Try extracting from APK if native library directory didn't extract it
        tryExtractFromApk(local);
        if (local.exists() && local.canExecute()) return local;

        return f;
    }

    private void tryExtractFromApk(File target) {
        try {
            String apkPath = ctx.getApplicationInfo().sourceDir;
            if (apkPath == null) return;
            ZipFile zip = new ZipFile(apkPath);
            String[] abis = Build.SUPPORTED_ABIS;
            ZipEntry found = null;
            if (abis != null) {
                for (String abi : abis) {
                    ZipEntry e = zip.getEntry("lib/" + abi + "/libcloudflared.so");
                    if (e != null) {
                        found = e;
                        break;
                    }
                }
            }
            if (found == null) {
                found = zip.getEntry("lib/arm64-v8a/libcloudflared.so");
                if (found == null) found = zip.getEntry("lib/armeabi-v7a/libcloudflared.so");
            }
            if (found != null) {
                target.getParentFile().mkdirs();
                try (java.io.InputStream in = zip.getInputStream(found);
                     java.io.OutputStream out = new java.io.FileOutputStream(target)) {
                    byte[] buf = new byte[65536];
                    int n;
                    while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                }
                target.setExecutable(true, false);
            }
            zip.close();
        } catch (Exception ignored) { }
    }

    public boolean supported() {
        return binary().exists();
    }

    public synchronized void start(String mode, String token) throws Exception {
        if (!supported()) throw new Exception("This device is not supported (no cloudflared binary for this CPU architecture)");
        desiredMode = mode == null ? "off" : mode;
        desiredToken = token == null ? "" : token.trim();
        Settings.setTunnel(ctx, desiredMode, desiredToken);

        stopProcess();
        synchronized (this) {
            log.clear();
            url = "";
            error = "";
            state = State.STARTING;
        }

        startSupervisor();
    }

    public synchronized void stop() {
        desiredMode = "off";
        Settings.setTunnel(ctx, "off", Settings.tunnelToken(ctx));
        stopProcess();
        synchronized (this) {
            state = State.OFF;
            url = "";
        }
        synchronized (reconnectLock) {
            reconnectLock.notifyAll();
        }
    }

    private synchronized void stopProcess() {
        if (proc != null) {
            try {
                proc.destroy();
            } catch (Exception ignored) { }
            proc = null;
        }
    }

    private void startSupervisor() {
        if (supervisor != null && supervisor.isAlive()) {
            synchronized (reconnectLock) {
                reconnectLock.notifyAll();
            }
            return;
        }

        supervisor = new Thread(new Runnable() {
            @Override
            public void run() {
                int retryDelay = 3;
                while (!"off".equals(desiredMode) && ServerService.running) {
                    try {
                        runProcessOnce();
                    } catch (Exception e) {
                        synchronized (TunnelManager.this) {
                            error = e.getMessage();
                            state = State.ERROR;
                        }
                    }

                    if ("off".equals(desiredMode) || !ServerService.running) break;

                    synchronized (TunnelManager.this) {
                        if (state != State.CONNECTED) {
                            state = State.STARTING;
                            error = "Internet disconnected. Reconnecting in " + retryDelay + "s...";
                        }
                    }

                    // Wait before reconnecting; woken up immediately if network becomes available
                    synchronized (reconnectLock) {
                        try {
                            reconnectLock.wait(retryDelay * 1000L);
                        } catch (InterruptedException ignored) { }
                    }

                    if ("off".equals(desiredMode) || !ServerService.running) break;
                    retryDelay = Math.min(retryDelay * 2, 20); // Backoff up to 20s
                }

                synchronized (TunnelManager.this) {
                    if ("off".equals(desiredMode)) {
                        state = State.OFF;
                        url = "";
                    }
                }
            }
        }, "tunnel-supervisor");
        supervisor.setDaemon(true);
        supervisor.start();
    }

    private void runProcessOnce() throws Exception {
        File cf = binary();
        File home = new File(ctx.getFilesDir(), "cloudflared");
        home.mkdirs();

        List<String> cmd = new ArrayList<>();
        cmd.add(cf.getAbsolutePath());
        cmd.add("tunnel");
        cmd.add("--no-autoupdate");
        cmd.add("--edge-ip-version");
        cmd.add("auto");

        final boolean named = "named".equals(desiredMode);
        if (named) {
            if (desiredToken.isEmpty()) throw new Exception("Tunnel token is required");
            cmd.add("run");
            cmd.add("--token");
            cmd.add(desiredToken);
        } else {
            cmd.add("--url");
            cmd.add("http://127.0.0.1:" + ServerService.PORT);
        }

        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.redirectErrorStream(true);
        pb.directory(home);
        pb.environment().put("HOME", home.getAbsolutePath());
        pb.environment().put("TMPDIR", ctx.getCacheDir().getAbsolutePath());

        final Process p = pb.start();
        synchronized (this) {
            proc = p;
        }

        Thread reader = new Thread(new Runnable() {
            @Override
            public void run() {
                try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = r.readLine()) != null) {
                        synchronized (TunnelManager.this) {
                            log.add(line);
                            if (log.size() > 200) log.remove(0);

                            Matcher m = QUICK_URL.matcher(line);
                            if (m.find()) {
                                url = m.group();
                                state = State.CONNECTED;
                                error = "";
                            }
                            if (named && (line.contains("Registered tunnel connection") || line.contains("Connection registered"))) {
                                state = State.CONNECTED;
                                error = "";
                            }
                            String low = line.toLowerCase(Locale.ROOT);
                            if (low.contains("error") && (low.contains("unauthorized") || low.contains("invalid") || low.contains("failed to"))) {
                                if (state != State.CONNECTED) {
                                    error = line;
                                    state = State.ERROR;
                                }
                            }
                        }
                    }
                } catch (Exception ignored) { }
            }
        }, "cloudflared-reader");
        reader.setDaemon(true);
        reader.start();

        try {
            p.waitFor();
        } catch (InterruptedException ignored) { }

        stopProcess();

        synchronized (this) {
            if (!"off".equals(desiredMode)) {
                if (state != State.ERROR) {
                    error = "Connection lost. Reconnecting...";
                }
                state = State.STARTING;
                url = "";
            }
        }
    }

    /** Called by ServerService when internet connectivity is detected/restored. */
    public void onNetworkAvailable() {
        synchronized (reconnectLock) {
            reconnectLock.notifyAll(); // Wake up supervisor immediately
        }
    }

    /** Called by ServerService when internet connectivity is lost. */
    public void onNetworkLost() {
        synchronized (this) {
            if (state == State.CONNECTED) {
                error = "Internet disconnected. Waiting for connection...";
            }
        }
    }

    /** Re-start the tunnel after a service restart if the user had one enabled. */
    public void restoreIfEnabled() {
        String mode = Settings.tunnelMode(ctx);
        if (mode == null || "off".equals(mode)) return;
        try {
            start(mode, Settings.tunnelToken(ctx));
        } catch (Exception e) {
            synchronized (this) {
                error = e.getMessage();
                state = State.ERROR;
            }
        }
    }
}
