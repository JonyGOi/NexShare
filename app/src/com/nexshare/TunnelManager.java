package com.nexshare;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.LinkProperties;
import android.net.Network;
import android.os.Build;
import android.text.TextUtils;
import android.util.Base64;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Runs Cloudflare's cloudflared to expose the local web server on the internet.
 *
 * Android runs cloudflared as a static Go binary, which cannot read the system resolver the way
 * Android apps do (there is usually no /etc/resolv.conf). We therefore pass the phone's own DNS
 * servers through TUNNEL_DNS_RESOLVER_ADDRS, and retry with HTTP/2 + IPv4 when a network blocks
 * QUIC/UDP — the two failures that make "quick tunnels" silently hang on campus/corporate Wi-Fi.
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
    private volatile String explain = "";
    private volatile int attempt = 0;
    private volatile boolean timedOut = false;

    private volatile String desiredMode = "off";
    private volatile String desiredToken = "";
    private Thread supervisor;
    private final Object reconnectLock = new Object();

    private static final Pattern QUICK_URL = Pattern.compile("https://[a-z0-9-]+\\.trycloudflare\\.com");
    private static final Pattern TOKEN_INVALID = Pattern.compile(
        "(?i)(provided tunnel token is not valid|failed to unmarshal token|illegal base64|invalid tunnel token)");
    private static final Pattern QUICK_FAILED = Pattern.compile("(?i)failed to request quick Tunnel");
    private static final String[] PUBLIC_DNS = {"1.1.1.1:53", "8.8.8.8:53", "1.0.0.1:53"};

    private static final int CONNECT_TIMEOUT_MS = 45000;

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

    /** Human explanation of the last failure, in Portuguese, shown in the UI. */
    public synchronized String explain() {
        return explain;
    }

    public synchronized int attempt() {
        return attempt;
    }

    public synchronized String logTail() {
        StringBuilder sb = new StringBuilder();
        int from = Math.max(0, log.size() - 60);
        for (int i = from; i < log.size(); i++) sb.append(log.get(i)).append('\n');
        return sb.toString();
    }

    /** The bundled cloudflared binary, extracted to the app's native lib dir and executable. */
    public File binary() {
        File f = new File(ctx.getApplicationInfo().nativeLibraryDir, "libcloudflared.so");
        if (f.exists()) return f;

        File local = new File(new File(ctx.getFilesDir(), "bin"), "cloudflared");
        if (local.exists()) return local;

        // Try extracting from APK if native library directory didn't extract it
        tryExtractFromApk(local);
        if (local.exists()) {
            local.setExecutable(true, false);
            return local;
        }

        File tmp = new File("/data/local/tmp/libcloudflared.so");
        if (tmp.exists()) return tmp;

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
        File b = binary();
        return b != null && b.exists();
    }

    public static String sanitizeToken(String raw) {
        if (raw == null) return "";
        raw = raw.trim();
        if (raw.startsWith("\"") && raw.endsWith("\"") && raw.length() > 2) raw = raw.substring(1, raw.length() - 1);
        if (raw.startsWith("'") && raw.endsWith("'") && raw.length() > 2) raw = raw.substring(1, raw.length() - 1);
        int idx = raw.indexOf("eyJh");
        if (idx >= 0) {
            String sub = raw.substring(idx).trim();
            int space = sub.indexOf(' ');
            if (space > 0) sub = sub.substring(0, space);
            int quote = sub.indexOf('"');
            if (quote > 0) sub = sub.substring(0, quote);
            int singleQuote = sub.indexOf('\'');
            if (singleQuote > 0) sub = sub.substring(0, singleQuote);
            return sub.trim();
        }
        return raw.trim();
    }

    /**
     * Checks that a pasted value really is a Cloudflare **tunnel** token (the base64 JSON with
     * a/t/s keys shown by "Install connector"). API tokens, tunnel IDs and dashboard URLs are the
     * usual mix-ups, and cloudflared only says "Provided Tunnel token is not valid."
     */
    public static String validateToken(String token) {
        if (token == null || token.isEmpty()) {
            return "Cole o token do túnel. No Cloudflare: Zero Trust → Networks → Tunnels → seu túnel → "
                 + "Configure → Install connector. Copie só o texto depois de --token.";
        }
        if (token.startsWith("http://") || token.startsWith("https://")) {
            return "Isso é uma URL, não o token. Copie o valor depois de --token no comando do connector.";
        }
        if (!token.startsWith("eyJ")) {
            return "Esse valor não parece um token de túnel. O token correto começa com \"eyJ\" (ex.: "
                 + "eyJhIjoi...). Um API Token do Cloudflare, o ID do túnel (UUID) ou a URL do dashboard não funcionam.";
        }
        byte[] decoded = null;
        int[] flags = {
            Base64.URL_SAFE | Base64.NO_WRAP | Base64.NO_PADDING,
            Base64.DEFAULT,
            Base64.URL_SAFE | Base64.NO_WRAP,
        };
        for (int f : flags) {
            try {
                decoded = Base64.decode(token, f);
                if (decoded != null && decoded.length > 10) break;
            } catch (Exception ignored) { }
        }
        if (decoded == null) {
            return "O token parece truncado ou com caracteres inválidos. Copie novamente o token completo.";
        }
        try {
            JSONObject o = new JSONObject(new String(decoded, StandardCharsets.UTF_8));
            String account = o.optString("a", "");
            String tunnelId = o.optString("t", "");
            String secret = o.optString("s", "");
            if (account.isEmpty() || tunnelId.isEmpty() || secret.isEmpty()) {
                return "O token decodificou, mas não tem os campos a/t/s de um túnel. "
                     + "Use o token do connector, não uma credencial de outro tipo.";
            }
        } catch (Exception e) {
            return "O token não é um JSON válido em base64. Copie o valor completo depois de --token.";
        }
        return null;
    }

    public synchronized void start(String mode, String token) throws Exception {
        start(mode, token, Settings.tunnelHost(ctx));
    }

    public synchronized void start(String mode, String token, String hostname) throws Exception {
        if (!supported()) throw new Exception("This device is not supported (no cloudflared binary for this CPU architecture)");
        Settings.requireLogin(ctx); // never expose the files to the internet without a login
        desiredMode = mode == null ? "off" : mode;
        desiredToken = sanitizeToken(token);
        if ("named".equals(desiredMode)) {
            String invalid = validateToken(desiredToken);
            if (invalid != null) throw new IllegalArgumentException(invalid);
            Settings.setTunnelHost(ctx, hostname);
        }
        Settings.setTunnel(ctx, desiredMode, desiredToken);

        stopProcess();
        synchronized (this) {
            log.clear();
            url = "";
            // A named tunnel with a configured public hostname has a stable, known URL.
            String host = Settings.tunnelHost(ctx);
            if ("named".equals(desiredMode) && !host.isEmpty()) url = "https://" + host;
            error = "";
            explain = "";
            attempt = 0;
            timedOut = false;
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
            explain = "";
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
                int tryIndex = 0;
                while (!"off".equals(desiredMode) && ServerService.running) {
                    boolean fatal = false;
                    synchronized (TunnelManager.this) {
                        attempt = tryIndex;
                    }
                    try {
                        fatal = runProcessOnce();
                    } catch (Exception e) {
                        synchronized (TunnelManager.this) {
                            error = e.getMessage();
                            explain = explainFor(error, logTail());
                            state = State.ERROR;
                        }
                    }

                    if (fatal) {
                        synchronized (TunnelManager.this) {
                            state = State.ERROR;
                        }
                        break;
                    }
                    if ("off".equals(desiredMode) || !ServerService.running) break;

                    // Connected: reset the escalation. Failed: move to the next transport
                    // (auto -> HTTP/2 -> HTTP/2 + IPv4 + public DNS).
                    if (state() == State.CONNECTED) {
                        tryIndex = 0;
                        retryDelay = 3;
                    } else {
                        tryIndex = Math.min(tryIndex + 1, 2);
                    }

                    synchronized (TunnelManager.this) {
                        if (state != State.CONNECTED) {
                            state = State.STARTING;
                            if (error == null || error.isEmpty()) {
                                error = "Sem conexão. Nova tentativa em " + retryDelay + "s...";
                            }
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

    /** Runs one cloudflared attempt; returns true when the failure is permanent (bad token). */
    private boolean runProcessOnce() throws Exception {
        File cf = binary();
        if (!cf.canExecute()) cf.setExecutable(true, false);
        if (Flavor.ROOT && RootShell.available()) {
            try {
                RootShell.main().exec("chmod 755 " + RootShell.q(cf.getAbsolutePath()), 3000);
            } catch (Exception ignored) { }
        }

        final int thisAttempt;
        synchronized (this) {
            thisAttempt = attempt;
        }

        File home = new File(ctx.getFilesDir(), "cloudflared");
        home.mkdirs();

        List<String> cmd = new ArrayList<>();
        cmd.add(cf.getAbsolutePath());
        // Global flag: must come before the subcommand (same form cloudflared writes into its
        // systemd unit), otherwise cloudflared can reject it as an unknown tunnel flag.
        cmd.add("--no-autoupdate");
        cmd.add("tunnel");

        final boolean named = "named".equals(desiredMode);
        final String token = sanitizeToken(desiredToken);
        if (named) {
            if (token.isEmpty()) throw new Exception("Tunnel token is required");
            cmd.add("run");
            cmd.add("--token");
            cmd.add(token);
        } else {
            cmd.add("--url");
            cmd.add("http://127.0.0.1:" + ServerService.PORT);
        }

        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.redirectErrorStream(true);
        pb.directory(home);
        pb.environment().put("HOME", home.getAbsolutePath());
        pb.environment().put("TMPDIR", ctx.getCacheDir().getAbsolutePath());
        pb.environment().put("QUIC_GO_DISABLE_ECN", "true");
        pb.environment().put("TUNNEL_LOGLEVEL", "info");
        applyNetworkEnv(pb, thisAttempt);
        if (named) {
            pb.environment().put("TUNNEL_TOKEN", token);
        }

        Process startedProcess;
        try {
            startedProcess = pb.start();
        } catch (java.io.IOException ioe) {
            if (Flavor.ROOT && RootShell.available()) {
                File tmpBin = new File("/data/local/tmp/libcloudflared.so");
                RootShell.main().exec("cp -f " + RootShell.q(cf.getAbsolutePath()) + " " + RootShell.q(tmpBin.getAbsolutePath()) + " && chmod 755 " + RootShell.q(tmpBin.getAbsolutePath()), 5000);
                cmd.set(0, tmpBin.getAbsolutePath());
                ProcessBuilder pbRoot = new ProcessBuilder(cmd);
                pbRoot.redirectErrorStream(true);
                pbRoot.directory(home);
                pbRoot.environment().putAll(pb.environment());
                startedProcess = pbRoot.start();
            } else {
                throw ioe;
            }
        }
        final Process p = startedProcess;
        synchronized (this) {
            proc = p;
        }
        startWatchdog(p);

        final boolean[] tokenRejected = {false};
        Thread reader = new Thread(new Runnable() {
            @Override
            public void run() {
                try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = r.readLine()) != null) {
                        synchronized (TunnelManager.this) {
                            log.add(line);
                            if (log.size() > 300) log.remove(0);

                            Matcher m = QUICK_URL.matcher(line);
                            if (m.find()) {
                                url = m.group();
                                state = State.CONNECTED;
                                error = "";
                                explain = "";
                            }
                            if (named && (line.contains("Registered tunnel connection") || line.contains("Connection registered"))) {
                                state = State.CONNECTED;
                                error = "";
                                explain = "";
                                if (url.isEmpty()) {
                                    String host = Settings.tunnelHost(ctx);
                                    if (!host.isEmpty()) url = "https://" + host;
                                }
                            }
                            if (TOKEN_INVALID.matcher(line).find()) {
                                tokenRejected[0] = true;
                            }
                            String low = line.toLowerCase(Locale.ROOT);
                            if (low.contains("error") || low.contains("failed") || low.contains("unauthorized")
                                || low.contains("invalid") || low.contains("incorrect usage")) {
                                if (state != State.CONNECTED && !isBenign(line)) {
                                    error = line;
                                    explain = explainFor(line, null);
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

        int exitCode = -1;
        try {
            exitCode = p.waitFor();
        } catch (InterruptedException ignored) { }

        stopProcess();

        synchronized (this) {
            if (!"off".equals(desiredMode)) {
                String tail = logTail();
                if (tokenRejected[0]) {
                    explain = "O Cloudflare recusou o token (\"Provided Tunnel token is not valid\"). "
                            + "Gere o token de novo em Zero Trust → Networks → Tunnels → seu túnel → "
                            + "Configure → Install connector e cole o texto depois de --token.";
                    error = "Invalid tunnel token";
                    state = State.ERROR;
                    return true;
                }
                if (state != State.CONNECTED) {
                    if (!timedOut) {
                        if (!log.isEmpty()) error = log.get(log.size() - 1);
                        else error = "cloudflared exited with code " + exitCode;
                        explain = explainFor(error, tail);
                    }
                    timedOut = false;
                    state = State.ERROR;
                } else {
                    error = "Connection lost. Reconnecting...";
                    state = State.STARTING;
                }
                url = named ? (Settings.tunnelHost(ctx).isEmpty() ? "" : "https://" + Settings.tunnelHost(ctx)) : "";
            }
        }
        return false;
    }

    /** Protocol/DNS/IP version per attempt: straight, then HTTP/2, then HTTP/2 + IPv4 + public DNS. */
    private void applyNetworkEnv(ProcessBuilder pb, int thisAttempt) {
        List<String> dns = new ArrayList<>();
        if (thisAttempt < 2) {
            dns.addAll(androidDnsServers());
            if (dns.isEmpty()) {
                for (String d : PUBLIC_DNS) dns.add(d);
            } else if (dns.size() < 2) {
                dns.add("1.1.1.1:53");
            }
        } else {
            for (String d : PUBLIC_DNS) dns.add(d);
        }
        pb.environment().put("TUNNEL_DNS_RESOLVER_ADDRS", TextUtils.join(",", dns));

        if (thisAttempt == 0) {
            pb.environment().put("TUNNEL_TRANSPORT_PROTOCOL", "auto");
        } else {
            // QUIC (UDP 7844) is blocked on many campus/corporate networks: HTTP/2 over 443 always works.
            pb.environment().put("TUNNEL_TRANSPORT_PROTOCOL", "http2");
            pb.environment().put("TUNNEL_EDGE_IP_VERSION", "4");
        }
        pb.environment().put("TUNNEL_POST_QUANTUM", "false");
    }

    /** The phone's own DNS servers (IPv4) so cloudflared can resolve without /etc/resolv.conf. */
    private List<String> androidDnsServers() {
        List<String> out = new ArrayList<>();
        try {
            ConnectivityManager cm = (ConnectivityManager) ctx.getSystemService(Context.CONNECTIVITY_SERVICE);
            Network n = cm.getActiveNetwork();
            LinkProperties lp = n == null ? null : cm.getLinkProperties(n);
            if (lp != null) {
                for (InetAddress a : lp.getDnsServers()) {
                    if (a instanceof Inet4Address) out.add(a.getHostAddress() + ":53");
                }
            }
        } catch (Throwable ignored) { }
        return out;
    }

    /** Kills a hung attempt so the supervisor can retry with the next protocol. */
    private void startWatchdog(final Process p) {
        Thread t = new Thread(new Runnable() {
            @Override
            public void run() {
                long started = System.currentTimeMillis();
                while (p.isAlive()) {
                    try {
                        Thread.sleep(2000);
                    } catch (InterruptedException e) {
                        return;
                    }
                    if (state() == State.CONNECTED) continue;
                    if (System.currentTimeMillis() - started > CONNECT_TIMEOUT_MS) {
                        synchronized (TunnelManager.this) {
                            if (state != State.CONNECTED) {
                                timedOut = true;
                                error = "Tempo esgotado ao conectar (rede bloqueando QUIC/porta 7844). Tentando HTTP/2 + IPv4...";
                                explain = error;
                                p.destroy();
                            }
                        }
                        return;
                    }
                }
            }
        }, "cloudflared-watchdog");
        t.setDaemon(true);
        t.start();
    }

    /** Lines that look like errors but are harmless. */
    private static boolean isBenign(String line) {
        String l = line.toLowerCase(Locale.ROOT);
        return l.contains("receive buffer")
            || l.contains("cannot determine default configuration path")
            || l.contains("autoupdate")
            || l.contains("failed to check for update")
            || l.contains("icmp proxy")
            || l.contains("updated to new configuration")
            || l.contains("failed to add dns resolver")
            || l.contains("failed to sufficiently increase");
    }

    /** Turns a raw cloudflared line into something a person can act on. */
    static String explainFor(String line, String logTail) {
        String l = ((line == null ? "" : line) + "\n" + (logTail == null ? "" : logTail)).toLowerCase(Locale.ROOT);
        if (l.contains("provided tunnel token is not valid") || l.contains("failed to unmarshal token")
            || l.contains("illegal base64") || l.contains("invalid tunnel token")) {
            return "O Cloudflare recusou o token. Gere de novo em Zero Trust → Networks → Tunnels → seu túnel → "
                 + "Configure → Install connector e cole só o texto depois de --token (começa com eyJh).";
        }
        if ((l.contains("lookup") && (l.contains(":53") || l.contains("no such host")))
            || l.contains("failed to resolve any edge addresses")) {
            return "Falha de DNS ao resolver o Cloudflare. Troque para Wi-Fi com DNS normal ou desligue DNS "
                 + "privado/VPN no celular. O app vai tentar novamente com HTTP/2 e DNS público.";
        }
        if (l.contains("failed to dial to edge with quic") || l.contains("quic") && l.contains("timeout")) {
            return "A rede está bloqueando QUIC (UDP 7844), comum em faculdade/empresa. "
                 + "O app vai reconectar automaticamente por HTTP/2 na porta 443.";
        }
        if (l.contains("failed to request quick tunnel")) {
            return "O Cloudflare não aceitou criar o Quick Tunnel agora (limite temporário ou bloqueio da rede). "
                 + "Tente de novo em 1 minuto ou use um túnel com token.";
        }
        if (l.contains("1033") || l.contains("tunnel not found")) {
            return "O túnel não existe mais nessa conta Cloudflare. Crie outro túnel (Zero Trust → Networks → Tunnels) "
                 + "e use o token novo.";
        }
        if (l.contains("429") || l.contains("rate limit")) {
            return "O Cloudflare limitou as tentativas (rate limit). Aguarde alguns minutos.";
        }
        if (l.contains("exec format error") || l.contains("permission denied")) {
            return "O binário do cloudflared não conseguiu executar neste aparelho/arquitetura.";
        }
        if (l.contains("server misbehaving") || l.contains("i/o timeout") && l.contains(":53")) {
            return "O DNS do celular não respondeu. Desligue DNS privado (Configurações → Rede → DNS privado), "
                 + "VPN ou Pi-hole e tente de novo.";
        }
        if (l.contains("no such host") || l.contains("network is unreachable") || l.contains("connection refused")) {
            return "Sem acesso à internet/à API do Cloudflare. Verifique a conexão do celular (Wi-Fi ou dados).";
        }
        if (l.contains("incorrect usage") || l.contains("unknown flag") || l.contains("flag provided but not defined")) {
            return "O cloudflared rejeitou os parâmetros. Veja os logs e atualize o app.";
        }
        return line == null || line.isEmpty() ? "" : line;
    }

    /** Network + binary preflight shown in the UI ("Testar" button). */
    public JSONObject diagnose() {
        JSONObject o = new JSONObject();
        try {
            File b = binary();
            o.put("supported", b != null && b.exists());
            o.put("binary", b != null ? b.getAbsolutePath() : "");
            o.put("executable", b != null && b.canExecute());
            o.put("arch", Build.SUPPORTED_ABIS != null && Build.SUPPORTED_ABIS.length > 0 ? Build.SUPPORTED_ABIS[0] : "?");
            List<String> dns = androidDnsServers();
            o.put("dns", dns.isEmpty() ? "(nenhum — usando 1.1.1.1)" : TextUtils.join(", ", dns));
            o.put("internet", hasInternet());
            o.put("api", resolveMs("api.trycloudflare.com"));
            o.put("edge", resolveMs("region1.v2.argotunnel.com"));
            o.put("state", state().name().toLowerCase(Locale.ROOT));
            o.put("attempt", attempt());
            o.put("error", error());
            o.put("explain", explain());
        } catch (Exception e) {
            try {
                o.put("error", e.getMessage());
            } catch (Exception ignored) { }
        }
        return o;
    }

    private boolean hasInternet() {
        try {
            ConnectivityManager cm = (ConnectivityManager) ctx.getSystemService(Context.CONNECTIVITY_SERVICE);
            Network n = cm.getActiveNetwork();
            if (n == null) return false;
            android.net.NetworkCapabilities nc = cm.getNetworkCapabilities(n);
            return nc != null && nc.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET);
        } catch (Throwable e) {
            return false;
        }
    }

    private String resolveMs(String host) {
        long t0 = System.currentTimeMillis();
        try {
            InetAddress[] all = InetAddress.getAllByName(host);
            long ms = System.currentTimeMillis() - t0;
            return "ok " + ms + " ms" + (all.length > 0 ? " (" + all[0].getHostAddress() + ")" : "");
        } catch (Exception e) {
            return "falhou: " + e.getMessage();
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
            start(mode, Settings.tunnelToken(ctx), Settings.tunnelHost(ctx));
        } catch (Exception e) {
            synchronized (this) {
                error = e.getMessage();
                explain = e.getMessage();
                state = State.ERROR;
            }
        }
    }
}
