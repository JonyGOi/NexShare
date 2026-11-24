package com.nexshare;

import android.app.ActivityManager;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.media.ThumbnailUtils;
import android.net.TrafficStats;
import android.net.Uri;
import android.os.BatteryManager;
import android.os.Build;
import android.os.Environment;
import android.os.StatFs;
import android.os.SystemClock;
import android.provider.MediaStore;
import android.util.LruCache;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.net.Inet4Address;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.zip.Deflater;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/** Routes for the web UI: static assets, auth, file operations and system stats. */
public class WebApi implements HttpServer.Handler {

    private final Context ctx;
    private final HttpServer[] serverRef;
    private final RootApi rootApi;
    private final WebDavHandler webDavHandler;
    private final Set<String> tokens = Collections.newSetFromMap(new ConcurrentHashMap<String, Boolean>());
    private final Map<String, int[]> failures = new ConcurrentHashMap<>(); // ip -> {count, lockUntilSec}
    private final Map<String, byte[]> assetCache = new ConcurrentHashMap<>();
    private final java.util.Map<String, String> hashes = new java.util.HashMap<>();
    private final LruCache<String, byte[]> thumbs = new LruCache<String, byte[]>(16 * 1024 * 1024) {
        @Override
        protected int sizeOf(String k, byte[] v) {
            return v.length;
        }
    };
    private final SecureRandom rnd = new SecureRandom();
    private long[] lastCpu;
    private long lastProcCpu = -1;
    private long lastProcTime = -1;

    public WebApi(Context ctx, HttpServer[] serverRef) {
        this.ctx = ctx.getApplicationContext();
        this.serverRef = serverRef;
        this.rootApi = new RootApi(ctx);
        this.webDavHandler = new WebDavHandler(ctx);
    }

    @Override
    public void handle(HttpServer.Request req, HttpServer.Response res) throws IOException {
        String p = req.path;
        if (p.equals("/addon") || p.startsWith("/addon/")) {
            if (handleAddon(req, res, p)) return;
        }
        if (p.equals("/dav") || p.startsWith("/dav/")) {
            webDavHandler.handle(req, res);
            return;
        }
        if (p.equals("/rest/1.0") || p.startsWith("/rest/1.0/")) {
            // Real-Debrid compatible API: needs the API key (Bearer / X-Api-Key / ?token=) or a session cookie.
            if (!authorized(req)) {
                res.header("WWW-Authenticate", "Bearer realm=\"NexShare\"");
                res.header("Access-Control-Allow-Origin", "*");
                res.error(401, "bad_token");
                return;
            }
            handleDebridRest(req, res);
            return;
        }
        if (Settings.webdavEnabled(ctx)) {
            String m = req.method;
            if ("OPTIONS".equals(m) || "PROPFIND".equals(m) || "PROPPATCH".equals(m) ||
                "MKCOL".equals(m) || "COPY".equals(m) || "MOVE".equals(m) ||
                "LOCK".equals(m) || "UNLOCK".equals(m)) {
                webDavHandler.handle(req, res);
                return;
            }
        }
        if (p.equals("/api/cert")) {
            serveCert(res);
            return;
        }
        if (p.equals("/api/webdav/reg")) {
            serveWebdavReg(res);
            return;
        }
        if (!p.startsWith("/api/")) {
            serveAsset(p, res);
            return;
        }
        if (p.startsWith("/api/root/")) {
            if (!authorized(req)) { res.error(401, "unauthorized"); return; }
            if (rootApi.handle(req, res)) return;
        }
        if (p.equals("/api/login")) {
            login(req, res);
            return;
        }
        if (p.equals("/api/session")) {
            JSONObject o = new JSONObject();
            boolean isAuth = authorized(req);
            put(o, "auth", isAuth);
            put(o, "pinRequired", Settings.authRequired(ctx));
            put(o, "authMode", Settings.authMode(ctx));
            put(o, "username", Settings.username(ctx));
            put(o, "server", Build.MODEL);
            put(o, "flavor", Flavor.ROOT ? "root" : "normal");
            put(o, "version", Flavor.VERSION);
            put(o, "root", Flavor.ROOT && RootShell.available());
            put(o, "webdavEnabled", Settings.webdavEnabled(ctx));
            if (isAuth) {
                put(o, "token", getOrCreateStreamToken());
                put(o, "apiKey", Settings.apiKey(ctx));
                put(o, "tunnelUrl", tunnelPublicUrl());
                put(o, "davUrl", davUrl(false));
                put(o, "davHttpsUrl", davUrl(true));
            }
            res.json(200, o.toString());
            return;
        }
        if (p.equals("/api/webdav/status")) {
            webdavStatus(res);
            return;
        }
        if (p.equals("/api/webdav/toggle")) {
            webdavToggle(req, res);
            return;
        }
        if (!authorized(req)) {
            res.error(401, "unauthorized");
            return;
        }
        switch (p) {
            case "/api/logout": tokens.remove(req.cookie("ns_token")); res.json(200, "{}"); break;
            case "/api/roots": roots(res); break;
            case "/api/list": list(req, res); break;
            case "/api/search": search(req, res); break;
            case "/api/media": media(req, res); break;
            case "/api/file": file(req, res); break;
            case "/api/hash": hash(req, res); break;
            case "/api/thumb": thumb(req, res); break;
            case "/api/zip": zip(req, res); break;
            case "/api/upload": upload(req, res); break;
            case "/api/mkdir": mkdir(req, res); break;
            case "/api/rename": rename(req, res); break;
            case "/api/delete": delete(req, res); break;
            case "/api/move": copyMove(req, res, true); break;
            case "/api/copy": copyMove(req, res, false); break;
            case "/api/system": system(res); break;
            case "/api/wallpaper": setWallpaper(req, res); break;
            case "/api/tunnel/status": tunnelStatus(res); break;
            case "/api/tunnel/start": tunnelStart(req, res); break;
            case "/api/tunnel/stop": TunnelManager.get(ctx).stop(); Settings.setTunnel(ctx, "off", Settings.tunnelToken(ctx)); tunnelStatus(res); break;
            case "/api/tunnel/diagnose": res.json(200, TunnelManager.get(ctx).diagnose().toString()); break;
            case "/api/dl/list": dlList(res); break;
            case "/api/dl/add": dlAdd(req, res); break;
            case "/api/dl/torrent": dlTorrent(req, res); break;
            case "/api/dl/pause": DownloadManager.get(ctx).pause(req.param("id")); dlList(res); break;
            case "/api/dl/resume": DownloadManager.get(ctx).resume(req.param("id")); dlList(res); break;
            case "/api/dl/remove": DownloadManager.get(ctx).remove(req.param("id"), "1".equals(req.param("files"))); dlList(res); break;
            case "/api/dl/clear": DownloadManager.get(ctx).clearFinished(); dlList(res); break;
            case "/api/debrid/unrestrict": debridUnrestrict(req, res); break;
            case "/api/debrid/files": debridFiles(req, res); break;
            case "/api/debrid/prioritize": debridPrioritize(req, res); break;
            case "/api/debrid/select": debridSelect(req, res); break;
            case "/api/addon/test": addonTest(req, res); break;
            case "/api/debrid/rotatekey": debridRotateKey(res); break;
            case "/api/update/check": checkUpdate(res); break;
            case "/api/update/install": installUpdate(req, res); break;
            case "/api/update/status": updateStatus(res); break;
            case "/api/update/auto": updateAuto(req, res); break;
            default: res.error(404, "not found");
        }
    }

    // ---------- auth ----------

    private boolean authorized(HttpServer.Request req) {
        if (!Settings.authRequired(ctx)) return true;
        String apiKey = Settings.apiKey(ctx);
        String pin = Settings.pin(ctx);

        String t = req.cookie("ns_token");
        if (t != null && tokens.contains(t)) return true;

        String paramToken = req.param("token");
        if (paramToken == null) paramToken = req.param("t");
        if (paramToken == null) paramToken = req.param("apikey");
        if (paramToken != null && !paramToken.isEmpty()) {
            if (tokens.contains(paramToken) || paramToken.equals(apiKey)) return true;
            if (Settings.MODE_PIN.equals(Settings.authMode(ctx)) && paramToken.equals(pin)) return true;
        }

        String headerKey = req.header("x-api-key");
        if (headerKey != null && headerKey.trim().equals(apiKey)) return true;

        String authHeader = req.header("authorization");
        if (authHeader != null) {
            String ah = authHeader.trim();
            if (ah.regionMatches(true, 0, "Bearer ", 0, 7)) {
                String bearer = ah.substring(7).trim();
                if (tokens.contains(bearer)) return true;
                if (bearer.equals(apiKey)) return true;
                if (Settings.MODE_PIN.equals(Settings.authMode(ctx)) && bearer.equals(pin)) return true;
                if (Settings.MODE_PASSWORD.equals(Settings.authMode(ctx)) && Settings.checkPassword(ctx, bearer)) return true;
            } else if (ah.regionMatches(true, 0, "Basic ", 0, 6)) {
                try {
                    String cred = new String(android.util.Base64.decode(ah.substring(6).trim(), android.util.Base64.DEFAULT), StandardCharsets.UTF_8);
                    int colon = cred.indexOf(':');
                    if (colon > 0) {
                        String user = cred.substring(0, colon);
                        String pass = cred.substring(colon + 1);
                        String mode = Settings.authMode(ctx);
                        if (pass.equals(apiKey)) return true;
                        if (Settings.MODE_PASSWORD.equals(mode)) {
                            if (user.equals(Settings.username(ctx)) && Settings.checkPassword(ctx, pass)) return true;
                        } else {
                            if (pass.equals(pin)) return true;
                        }
                    }
                } catch (Exception ignored) { }
            }
        }
        return false;
    }

    private void login(HttpServer.Request req, HttpServer.Response res) throws IOException {
        int now = (int) (System.currentTimeMillis() / 1000);
        int[] f = failures.get(req.remote);
        if (f != null && f[1] > now) {
            res.error(429, "locked");
            return;
        }
        JSONObject body;
        try {
            body = new JSONObject(req.bodyString(4096));
        } catch (Exception e) {
            res.error(400, "bad request");
            return;
        }
        String mode = Settings.authMode(ctx);
        boolean ok;
        if (Settings.MODE_PASSWORD.equals(mode)) {
            ok = body.optString("username").trim().equals(Settings.username(ctx))
                && Settings.checkPassword(ctx, body.optString("password"));
        } else { // pin (or none, which is already allowed by authorized())
            ok = Settings.pin(ctx).equals(body.optString("pin").trim());
        }
        if (!ok) {
            if (f == null) f = new int[2];
            f[0]++;
            if (f[0] >= 5) {
                f[0] = 0;
                f[1] = now + 60;
            }
            failures.put(req.remote, f);
            res.error(401, Settings.MODE_PASSWORD.equals(mode) ? "invalid username or password" : "wrong pin");
            return;
        }
        failures.remove(req.remote);
        byte[] b = new byte[24];
        rnd.nextBytes(b);
        StringBuilder sb = new StringBuilder();
        for (byte x : b) sb.append(String.format(Locale.ROOT, "%02x", x));
        String token = sb.toString();
        tokens.add(token);
        res.header("Set-Cookie", "ns_token=" + token + "; Path=/; HttpOnly; SameSite=Strict; Max-Age=2592000");
        res.json(200, "{\"ok\":true,\"token\":\"" + token + "\"}");
    }

    /** Stable token for stream/API links. Rotating it invalidates every saved Stremio/VLC link. */
    private String getOrCreateStreamToken() {
        return Settings.apiKey(ctx);
    }

    // ---------- static ----------

    private void serveAsset(String p, HttpServer.Response res) throws IOException {
        if (p.equals("/") || p.isEmpty()) p = "/index.html";
        if (p.contains("..")) {
            res.error(400, "bad path");
            return;
        }
        String name = "web" + p;
        byte[] data = assetCache.get(name);
        if (data == null) {
            try (InputStream in = ctx.getAssets().open(name)) {
                data = readAll(in);
                assetCache.put(name, data);
            } catch (IOException e) {
                res.error(404, "not found");
                return;
            }
        }
        res.header("Cache-Control", "no-cache");
        res.bytes(200, mime(p), data);
    }

    // ---------- files ----------

    private List<File> rootDirs() {
        List<File> roots = new ArrayList<>();
        roots.add(Environment.getExternalStorageDirectory());
        File[] ext = ctx.getExternalFilesDirs(null);
        for (int i = 1; ext != null && i < ext.length; i++) {
            if (ext[i] == null) continue;
            String path = ext[i].getAbsolutePath();
            int idx = path.indexOf("/Android/data");
            if (idx > 0) roots.add(new File(path.substring(0, idx)));
        }
        return roots;
    }

    private File resolve(String path) throws IOException {
        if (path == null || path.isEmpty()) throw new SecurityException("no path");
        File f = new File(path).getCanonicalFile();
        String fp = f.getPath();
        for (File r : rootDirs()) {
            String rp = r.getCanonicalPath();
            if (fp.equals(rp) || fp.startsWith(rp + "/")) return f;
        }
        throw new SecurityException("outside storage");
    }

    private boolean isRoot(File f) throws IOException {
        for (File r : rootDirs()) if (r.getCanonicalPath().equals(f.getPath())) return true;
        return false;
    }

    private void roots(HttpServer.Response res) throws IOException {
        JSONArray arr = new JSONArray();
        int i = 0;
        for (File r : rootDirs()) {
            JSONObject o = new JSONObject();
            String path = r.getCanonicalPath();
            put(o, "name", i == 0 ? "Internal storage" : "SD card " + i);
            put(o, "path", path);
            try {
                StatFs st = new StatFs(path);
                put(o, "total", st.getTotalBytes());
                put(o, "free", st.getAvailableBytes());
            } catch (Exception ignored) { }
            put(o, "readable", r.listFiles() != null);
            arr.put(o);
            i++;
        }
        JSONObject out = new JSONObject();
        put(out, "roots", arr);
        res.json(200, out.toString());
    }

    private JSONObject entry(File f) {
        JSONObject o = new JSONObject();
        put(o, "name", f.getName());
        put(o, "path", f.getAbsolutePath());
        put(o, "dir", f.isDirectory());
        put(o, "size", f.isDirectory() ? 0 : f.length());
        put(o, "mtime", f.lastModified());
        put(o, "hidden", f.getName().startsWith("."));
        return o;
    }

    private void list(HttpServer.Request req, HttpServer.Response res) throws IOException {
        File dir = resolve(req.param("path"));
        if (!dir.isDirectory()) {
            res.error(404, "not a folder");
            return;
        }
        File[] files = dir.listFiles();
        if (files == null) {
            List<RootIo.Entry> rootEntries = RootIo.listEntries(dir);
            if (rootEntries != null) {
                JSONArray arr = new JSONArray();
                for (RootIo.Entry re : rootEntries) {
                    JSONObject o = new JSONObject();
                    put(o, "name", re.name);
                    put(o, "path", new File(dir, re.name).getAbsolutePath());
                    put(o, "dir", re.isDir);
                    put(o, "size", re.isDir ? 0 : re.size);
                    put(o, "mtime", re.mtime);
                    put(o, "hidden", re.name.startsWith("."));
                    arr.put(o);
                }
                JSONObject o = new JSONObject();
                put(o, "path", dir.getPath());
                put(o, "parent", isRoot(dir) ? JSONObject.NULL : dir.getParent());
                put(o, "entries", arr);
                res.json(200, o.toString());
                return;
            }
            res.error(403, "no_permission");
            return;
        }
        JSONArray arr = new JSONArray();
        for (File f : files) arr.put(entry(f));
        JSONObject o = new JSONObject();
        put(o, "path", dir.getPath());
        put(o, "parent", isRoot(dir) ? JSONObject.NULL : dir.getParent());
        put(o, "entries", arr);
        res.json(200, o.toString());
    }

    private void search(HttpServer.Request req, HttpServer.Response res) throws IOException {
        File dir = resolve(req.param("path"));
        String q = req.param("q") == null ? "" : req.param("q").toLowerCase(Locale.ROOT);
        JSONArray arr = new JSONArray();
        if (!q.isEmpty()) walkSearch(dir, q, arr, 0);
        JSONObject o = new JSONObject();
        put(o, "entries", arr);
        res.json(200, o.toString());
    }

    private void walkSearch(File dir, String q, JSONArray out, int depth) {
        File[] files = dir.listFiles();
        if (files == null || depth > 12) return;
        for (File f : files) {
            if (out.length() >= 500) return;
            if (f.getName().toLowerCase(Locale.ROOT).contains(q)) out.put(entry(f));
            if (f.isDirectory() && !f.getName().equals("Android")) walkSearch(f, q, out, depth + 1);
        }
    }

    /** Recent photos and videos from the usual camera / picture folders, newest first. */
    private void media(HttpServer.Request req, HttpServer.Response res) throws IOException {
        String type = req.param("type") == null ? "all" : req.param("type");
        File base = Environment.getExternalStorageDirectory();
        List<File> found = new ArrayList<>();
        for (String d : new String[]{"DCIM", "Pictures", "Movies", "Music", "Download", "Recordings"}) collectMedia(new File(base, d), type, found, 0);
        Collections.sort(found, new Comparator<File>() {
            public int compare(File a, File b) {
                return Long.compare(b.lastModified(), a.lastModified());
            }
        });
        JSONArray arr = new JSONArray();
        for (int i = 0; i < found.size() && i < 2000; i++) arr.put(entry(found.get(i)));
        JSONObject o = new JSONObject();
        put(o, "entries", arr);
        res.json(200, o.toString());
    }

    private void collectMedia(File dir, String type, List<File> out, int depth) {
        File[] files = dir.listFiles();
        if (files == null || depth > 6) return;
        for (File f : files) {
            if (f.getName().startsWith(".")) continue;
            if (f.isDirectory()) collectMedia(f, type, out, depth + 1);
            else {
                String m = mime(f.getName());
                boolean img = m.startsWith("image/"), vid = m.startsWith("video/"), aud = m.startsWith("audio/");
                if ((type.equals("image") && img) || (type.equals("video") && vid) || (type.equals("audio") && aud)
                    || (type.equals("all") && (img || vid))) out.add(f);
            }
        }
    }

    private void file(HttpServer.Request req, HttpServer.Response res) throws IOException {
        File f = resolve(req.param("path"));
        if (!f.isFile()) {
            res.error(404, "not found");
            return;
        }
        long len = f.length();
        long start = 0, end = len - 1;
        boolean partial = false;
        String range = req.header("range");
        if (range != null && range.startsWith("bytes=") && !range.contains(",")) {
            String[] r = range.substring(6).split("-", -1);
            try {
                if (r[0].isEmpty()) {
                    start = Math.max(0, len - Long.parseLong(r[1]));
                } else {
                    start = Long.parseLong(r[0]);
                    if (!r[1].isEmpty()) end = Math.min(Long.parseLong(r[1]), len - 1);
                }
                partial = true;
            } catch (NumberFormatException ignored) { }
            if (start >= len || start > end) {
                res.header("Content-Range", "bytes */" + len);
                res.error(416, "bad range");
                return;
            }
        }
        res.header("Accept-Ranges", "bytes");
        res.header("Last-Modified", HttpServer.httpDate(f.lastModified()));
        String disp = "1".equals(req.param("dl")) ? "attachment" : "inline";
        res.header("Content-Disposition", disp + "; filename*=UTF-8''" + URLEncoder.encode(f.getName(), "UTF-8").replace("+", "%20"));
        if (partial) res.header("Content-Range", "bytes " + start + "-" + end + "/" + len);
        long count = end - start + 1;
        OutputStream out = res.begin(partial ? 206 : 200, mime(f.getName()), Math.max(count, 0));
        if ("HEAD".equals(req.method) || len == 0) return;
        TransferTracker.onTransferStart();
        DownloadManager.StreamRef stream = "1".equals(req.param("dl")) ? null : DownloadManager.get(ctx).locateTorrentFile(f);
        try {
            try (RandomAccessFile raf = new RandomAccessFile(f, "r")) {
                raf.seek(start);
                InputStream in = java.nio.channels.Channels.newInputStream(raf.getChannel());
                pumpFile(in, out, start, count, stream);
            } catch (IOException e) {
                if (RootIo.isAvailable()) {
                    try (InputStream in = RootIo.openInputStream(f, start)) {
                        pumpFile(in, out, start, count, stream);
                    }
                } else {
                    throw e;
                }
            }
        } finally {
            TransferTracker.onTransferEnd();
        }
    }

    /**
     * Copies a byte range to the client. When the file belongs to a torrent that is still
     * downloading, writes only what is already on disk and waits for the pieces being read,
     * so VLC/the browser can start playback immediately (debrid-style streaming).
     */
    private void pumpFile(InputStream in, OutputStream out, long start, long count, DownloadManager.StreamRef ref) throws IOException {
        long pos = start;
        long left = count;
        if (ref != null && ref.downloaded() - start >= count) ref = null; // whole range already on disk
        long avail = ref == null ? Long.MAX_VALUE : ref.downloaded();
        long stalledSince = 0;
        byte[] buf = new byte[65536];
        while (left > 0) {
            int want = buf.length;
            if (ref != null) {
                long now = ref.downloaded();
                if (now > avail) { avail = now; stalledSince = 0; }
                long ready = avail - pos;
                if (ready <= 0) {
                    if (stalledSince == 0) stalledSince = System.currentTimeMillis();
                    if (System.currentTimeMillis() - stalledSince > 60000) break; // stalled swarm: end the stream
                    try { Thread.sleep(200); } catch (InterruptedException e) { Thread.currentThread().interrupt(); break; }
                    continue;
                }
                want = (int) Math.min(want, ready);
            }
            int n = in.read(buf, 0, (int) Math.min(want, left));
            if (n < 0) break;
            out.write(buf, 0, n);
            left -= n;
            pos += n;
        }
        out.flush();
    }

    /**
     * SHA-256 of a file on the phone, so the PC can prove the copy it received is byte
     * identical before the phone deletes anything. Results are cached by path+size+mtime.
     */
    private void hash(HttpServer.Request req, HttpServer.Response res) throws IOException {
        File f = resolve(req.param("path"));
        if (!f.isFile()) {
            res.error(404, "not found");
            return;
        }
        String key = f.getAbsolutePath() + "|" + f.length() + "|" + f.lastModified();
        String sha;
        synchronized (hashes) {
            sha = hashes.get(key);
        }
        if (sha == null) {
            sha = sha256Of(f);
            synchronized (hashes) {
                hashes.clear();
                hashes.put(key, sha);
            }
        }
        JSONObject o = new JSONObject();
        put(o, "path", f.getAbsolutePath());
        put(o, "size", f.length());
        put(o, "sha256", sha);
        res.json(200, o.toString());
    }

    private static String sha256Of(File f) throws IOException {
        try (java.io.InputStream in = new java.io.FileInputStream(f)) {
            java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-256");
            byte[] buf = new byte[1 << 20];
            int n;
            while ((n = in.read(buf)) > 0) md.update(buf, 0, n);
            byte[] d = md.digest();
            StringBuilder sb = new StringBuilder(d.length * 2);
            for (byte b : d) {
                sb.append(Character.forDigit((b >> 4) & 0xF, 16));
                sb.append(Character.forDigit(b & 0xF, 16));
            }
            return sb.toString();
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IOException("sha-256 unavailable");
        }
    }

    private void thumb(HttpServer.Request req, HttpServer.Response res) throws IOException {
        File f = resolve(req.param("path"));
        int size = 320;
        try {
            if (req.param("s") != null) size = Math.max(64, Math.min(1024, Integer.parseInt(req.param("s"))));
        } catch (NumberFormatException ignored) { }
        String key = f.getPath() + "|" + f.lastModified() + "|" + size;
        byte[] data = thumbs.get(key);
        if (data == null) {
            Bitmap bmp = null;
            String m = mime(f.getName());
            try {
                if (m.startsWith("video/")) {
                    bmp = ThumbnailUtils.createVideoThumbnail(f.getPath(), MediaStore.Images.Thumbnails.MINI_KIND);
                } else if (m.startsWith("image/")) {
                    BitmapFactory.Options o = new BitmapFactory.Options();
                    o.inJustDecodeBounds = true;
                    BitmapFactory.decodeFile(f.getPath(), o);
                    int sample = 1;
                    while (Math.max(o.outWidth, o.outHeight) / (sample * 2) >= size) sample *= 2;
                    o = new BitmapFactory.Options();
                    o.inSampleSize = sample;
                    bmp = BitmapFactory.decodeFile(f.getPath(), o);
                }
            } catch (Throwable ignored) { }
            if (bmp == null) {
                res.error(404, "no thumbnail");
                return;
            }
            float scale = Math.min(1f, (float) size / Math.max(bmp.getWidth(), bmp.getHeight()));
            if (scale < 1f) {
                Bitmap s = Bitmap.createScaledBitmap(bmp, Math.round(bmp.getWidth() * scale), Math.round(bmp.getHeight() * scale), true);
                if (s != bmp) bmp.recycle();
                bmp = s;
            }
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            bmp.compress(Bitmap.CompressFormat.JPEG, 78, bo);
            bmp.recycle();
            data = bo.toByteArray();
            thumbs.put(key, data);
        }
        res.header("Cache-Control", "private, max-age=86400");
        res.bytes(200, "image/jpeg", data);
    }

    private void zip(HttpServer.Request req, HttpServer.Response res) throws IOException {
        List<File> items = new ArrayList<>();
        for (String p : req.params("p")) items.add(resolve(p));
        if (items.isEmpty()) {
            res.error(400, "nothing selected");
            return;
        }
        String name = items.size() == 1 ? items.get(0).getName() : "NexShare";
        res.header("Content-Disposition", "attachment; filename*=UTF-8''" + URLEncoder.encode(name + ".zip", "UTF-8").replace("+", "%20"));
        TransferTracker.onTransferStart();
        try {
            OutputStream out = res.begin(200, "application/zip", -1);
            ZipOutputStream z = new ZipOutputStream(new java.io.BufferedOutputStream(out, 65536));
            z.setLevel(Deflater.BEST_SPEED);
            for (File f : items) addZip(z, f, f.getName());
            z.finish();
            z.flush();
        } finally {
            TransferTracker.onTransferEnd();
        }
    }

    private void addZip(ZipOutputStream z, File f, String name) throws IOException {
        if (f.isDirectory()) {
            z.putNextEntry(new ZipEntry(name + "/"));
            z.closeEntry();
            File[] kids = f.listFiles();
            if (kids != null) for (File k : kids) addZip(z, k, name + "/" + k.getName());
            return;
        }
        ZipEntry e = new ZipEntry(name);
        e.setTime(f.lastModified());
        z.putNextEntry(e);
        try (InputStream in = new FileInputStream(f)) {
            byte[] buf = new byte[65536];
            int n;
            while ((n = in.read(buf)) > 0) z.write(buf, 0, n);
        } catch (IOException ignored) { }
        z.closeEntry();
    }

    private void upload(HttpServer.Request req, HttpServer.Response res) throws IOException {
        if (!"POST".equals(req.method) && !"PUT".equals(req.method)) {
            res.error(400, "use POST");
            return;
        }
        File dir = resolve(req.param("path"));
        String name = cleanName(req.param("name"));
        String rel = req.param("rel"); // optional sub-folder path for folder uploads
        if (rel != null && !rel.isEmpty()) {
            for (String part : rel.split("/")) {
                if (part.isEmpty() || part.equals(".") || part.equals("..")) continue;
                dir = new File(dir, cleanName(part));
            }
            dir = resolve(dir.getPath());
            if (!dir.isDirectory() && !dir.mkdirs()) {
                res.error(500, "cannot create folder");
                return;
            }
        }
        if (name == null || !dir.isDirectory()) {
            res.error(400, "bad target");
            return;
        }
        File target = uniqueFile(dir, name);
        File tmp = new File(dir, "." + target.getName() + ".nexpart");
        boolean ok = false;
        TransferTracker.onTransferStart();
        try {
            try (OutputStream out = new FileOutputStream(tmp)) {
                byte[] buf = new byte[65536];
                int n;
                while ((n = req.body.read(buf)) > 0) out.write(buf, 0, n);
                ok = req.body.remaining() == 0;
            } finally {
                if (!ok) tmp.delete();
            }
        } finally {
            TransferTracker.onTransferEnd();
        }
        if (!tmp.renameTo(target)) {
            tmp.delete();
            res.error(500, "cannot save");
            return;
        }
        String lm = req.param("mtime");
        if (lm != null) try {
            target.setLastModified(Long.parseLong(lm));
        } catch (NumberFormatException ignored) { }
        res.json(200, entry(target).toString());
    }

    private void mkdir(HttpServer.Request req, HttpServer.Response res) throws IOException {
        File dir = resolve(req.param("path"));
        String name = cleanName(req.param("name"));
        if (name == null) {
            res.error(400, "bad name");
            return;
        }
        File nd = new File(dir, name);
        if (nd.exists()) {
            res.error(409, "exists");
            return;
        }
        if (!nd.mkdirs() && (!RootIo.isAvailable() || !RootIo.mkdir(nd))) {
            res.error(500, "cannot create");
            return;
        }
        res.json(200, entry(nd).toString());
    }

    private void rename(HttpServer.Request req, HttpServer.Response res) throws IOException {
        File f = resolve(req.param("path"));
        String name = cleanName(req.param("name"));
        if (name == null || isRoot(f)) {
            res.error(400, "bad name");
            return;
        }
        File nf = new File(f.getParentFile(), name);
        if (nf.exists()) {
            res.error(409, "exists");
            return;
        }
        if (!f.renameTo(nf) && (!RootIo.isAvailable() || !RootIo.move(f, nf))) {
            res.error(500, "cannot rename");
            return;
        }
        res.json(200, entry(nf).toString());
    }

    private void delete(HttpServer.Request req, HttpServer.Response res) throws IOException {
        int n = 0;
        for (String p : req.params("p")) {
            File f = resolve(p);
            if (isRoot(f)) continue;
            if (deleteRec(f)) n++;
        }
        res.json(200, "{\"deleted\":" + n + "}");
    }

    private boolean deleteRec(File f) {
        if (f.isDirectory()) {
            File[] kids = f.listFiles();
            if (kids != null) for (File k : kids) deleteRec(k);
        }
        boolean ok = f.delete();
        if (!ok && RootIo.isAvailable()) ok = RootIo.delete(f);
        return ok;
    }

    private void copyMove(HttpServer.Request req, HttpServer.Response res, boolean move) throws IOException {
        File dst = resolve(req.param("dst"));
        if (!dst.isDirectory()) {
            res.error(400, "bad destination");
            return;
        }
        int n = 0;
        for (String p : req.params("p")) {
            File src = resolve(p);
            if (isRoot(src)) continue;
            if (dst.getPath().equals(src.getPath()) || dst.getPath().startsWith(src.getPath() + "/")) continue;
            File target = uniqueFile(dst, src.getName());
            boolean ok = move && src.renameTo(target);
            if (!ok) {
                ok = copyRec(src, target);
                if (ok && move) deleteRec(src);
            }
            if (ok) n++;
        }
        res.json(200, "{\"done\":" + n + "}");
    }

    private boolean copyRec(File src, File dst) {
        if (src.isDirectory()) {
            if (!dst.mkdirs()) return false;
            File[] kids = src.listFiles();
            boolean ok = true;
            if (kids != null) for (File k : kids) ok &= copyRec(k, new File(dst, k.getName()));
            return ok;
        }
        try (InputStream in = new FileInputStream(src); OutputStream out = new FileOutputStream(dst)) {
            byte[] buf = new byte[65536];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            dst.setLastModified(src.lastModified());
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    private static String cleanName(String n) {
        if (n == null) return null;
        n = n.replace("/", "_").replace("\\", "_").replace("\0", "").trim();
        if (n.isEmpty() || n.equals(".") || n.equals("..")) return null;
        return n;
    }

    private static File uniqueFile(File dir, String name) {
        File f = new File(dir, name);
        if (!f.exists()) return f;
        int dot = name.lastIndexOf('.');
        String base = dot > 0 ? name.substring(0, dot) : name;
        String ext = dot > 0 ? name.substring(dot) : "";
        for (int i = 1; ; i++) {
            f = new File(dir, base + " (" + i + ")" + ext);
            if (!f.exists()) return f;
        }
    }

    /** Sets the phone wallpaper from either a stored file (?path=) or a POSTed image body. */
    private void setWallpaper(HttpServer.Request req, HttpServer.Response res) throws IOException {
        android.graphics.Bitmap bmp;
        if (req.param("path") != null) {
            File f = resolve(req.param("path"));
            if (!f.isFile()) { res.error(404, "not found"); return; }
            bmp = android.graphics.BitmapFactory.decodeFile(f.getPath());
        } else {
            if (req.contentLength <= 0 || req.contentLength > 64 * 1024 * 1024) { res.error(400, "no image"); return; }
            byte[] data = new byte[(int) req.contentLength];
            int off = 0, n;
            while (off < data.length && (n = req.body.read(data, off, data.length - off)) > 0) off += n;
            bmp = android.graphics.BitmapFactory.decodeByteArray(data, 0, data.length);
        }
        if (bmp == null) { res.error(400, "not an image"); return; }
        try {
            android.app.WallpaperManager wm = android.app.WallpaperManager.getInstance(ctx);
            String which = req.param("which");
            if (Build.VERSION.SDK_INT >= 24 && "lock".equals(which)) wm.setBitmap(bmp, null, true, android.app.WallpaperManager.FLAG_LOCK);
            else if (Build.VERSION.SDK_INT >= 24 && "home".equals(which)) wm.setBitmap(bmp, null, true, android.app.WallpaperManager.FLAG_SYSTEM);
            else wm.setBitmap(bmp);
            bmp.recycle();
            res.json(200, "{\"ok\":true}");
        } catch (Exception e) {
            res.error(500, e.getMessage());
        }
    }

    // ---------- cloudflare tunnel ----------

    private void tunnelStatus(HttpServer.Response res) throws IOException {
        TunnelManager tm = TunnelManager.get(ctx);
        JSONObject o = new JSONObject();
        put(o, "supported", tm.supported());
        put(o, "state", tm.state().name().toLowerCase(Locale.ROOT));
        put(o, "url", tm.url());
        put(o, "error", tm.error());
        put(o, "mode", Settings.tunnelMode(ctx));
        put(o, "hasToken", !Settings.tunnelToken(ctx).isEmpty());
        put(o, "host", Settings.tunnelHost(ctx));
        put(o, "explain", tm.explain());
        put(o, "attempt", tm.attempt());
        put(o, "log", tm.logTail());
        res.json(200, o.toString());
    }

    private void tunnelStart(HttpServer.Request req, HttpServer.Response res) throws IOException {
        JSONObject b;
        try {
            b = new JSONObject(req.bodyString(1 << 16));
        } catch (Exception e) {
            res.error(400, "bad request");
            return;
        }
        String mode = b.optString("mode", "quick");
        String token = b.optString("token", Settings.tunnelToken(ctx));
        String host = b.has("host") ? b.optString("host", "") : Settings.tunnelHost(ctx);
        // Exposing the files to the internet without a PIN would be reckless: turn it on.
        Settings.requireLogin(ctx);
        try {
            TunnelManager.get(ctx).start(mode, token, host);
        } catch (IllegalArgumentException e) {
            res.error(400, e.getMessage());
            return;
        } catch (Exception e) {
            res.error(500, e.getMessage());
            return;
        }
        tunnelStatus(res);
    }

    // ---------- webdav ----------

    private void serveCert(HttpServer.Response res) throws IOException {
        try (InputStream in = ctx.getAssets().open("nexshare.crt")) {
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n;
            while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
            byte[] data = bo.toByteArray();
            res.header("Content-Disposition", "attachment; filename=\"nexshare-ca.crt\"");
            res.bytes(200, "application/x-x509-ca-cert", data);
        } catch (Exception e) {
            res.error(404, "certificate not found");
        }
    }

    private void serveWebdavReg(HttpServer.Response res) throws IOException {
        String reg = "Windows Registry Editor Version 5.00\r\n\r\n"
            + "; Configuração WebDAV para o cliente nativo do Windows (WebClient)\r\n"
            + "; Permite autenticação HTTP Basic sem SSL/HTTPS e eleva limite para 4GB\r\n\r\n"
            + "[HKEY_LOCAL_MACHINE\\SYSTEM\\CurrentControlSet\\Services\\WebClient\\Parameters]\r\n"
            + "\"BasicAuthLevel\"=dword:00000002\r\n"
            + "\"FileSizeLimitInBytes\"=dword:ffffffff\r\n";
        byte[] data = reg.getBytes(StandardCharsets.UTF_8);
        res.header("Content-Disposition", "attachment; filename=\"windows-webdav.reg\"");
        res.bytes(200, "application/octet-stream", data);
    }

    /** Public base URL when the Cloudflare tunnel is up ("" when only reachable on the LAN). */
    private String tunnelPublicUrl() {
        TunnelManager tm = TunnelManager.get(ctx);
        String u = tm.url();
        return (tm.state() == TunnelManager.State.CONNECTED && u != null && !u.isEmpty()) ? u : "";
    }

    private String lanHost() {
        Inet4Address ip = MdnsResponder.lanAddress();
        return ip != null ? ip.getHostAddress() : MdnsResponder.HOST;
    }

    /** WebDAV endpoint: the public HTTPS one wins when a tunnel is up (works on locked-down networks). */
    private String davUrl(boolean https) {
        String pub = tunnelPublicUrl();
        if (!pub.isEmpty()) return pub + "/dav/";
        return (https ? "https://" + lanHost() + ":" + ServerService.HTTPS_PORT
                      : "http://" + lanHost() + ":" + ServerService.PORT) + "/dav/";
    }

    private void webdavStatus(HttpServer.Response res) throws IOException {
        Inet4Address ip = MdnsResponder.lanAddress();
        String ipStr = ip != null ? ip.getHostAddress() : "";
        String url = ip != null ? "http://" + ipStr + ":" + ServerService.PORT + "/dav/" : "http://" + MdnsResponder.HOST + ":" + ServerService.PORT + "/dav/";
        String httpsUrl = ip != null ? "https://" + ipStr + ":" + ServerService.HTTPS_PORT + "/dav/" : "https://" + MdnsResponder.HOST + ":" + ServerService.HTTPS_PORT + "/dav/";
        String pub = tunnelPublicUrl();
        JSONObject o = new JSONObject();
        put(o, "enabled", Settings.webdavEnabled(ctx));
        put(o, "url", url);
        put(o, "httpsUrl", httpsUrl);
        put(o, "publicUrl", pub.isEmpty() ? JSONObject.NULL : pub + "/dav/");
        put(o, "user", Settings.authMode(ctx).equals("password") ? Settings.username(ctx) : "nexshare");
        put(o, "pin", Settings.pin(ctx));
        res.json(200, o.toString());
    }

    private void webdavToggle(HttpServer.Request req, HttpServer.Response res) throws IOException {
        JSONObject body;
        try {
            body = new JSONObject(req.bodyString(1024));
        } catch (Exception e) {
            body = new JSONObject();
        }
        boolean enable = body.optBoolean("enabled", !Settings.webdavEnabled(ctx));
        if (enable) Settings.requireLogin(ctx);
        Settings.setWebdavEnabled(ctx, enable);
        webdavStatus(res);
    }

    // ---------- download station ----------

    private void dlList(HttpServer.Response res) throws IOException {
        DownloadManager dm = DownloadManager.get(ctx);
        JSONObject o = new JSONObject();
        put(o, "tasks", dm.list());
        put(o, "defaultDir", DownloadManager.defaultDir().getAbsolutePath());
        put(o, "torrent", dm.torrentSupported());
        res.json(200, o.toString());
    }

    private File dlDir(String p) throws IOException {
        if (p == null || p.isEmpty()) return DownloadManager.defaultDir();
        return resolve(p);
    }

    private void dlAdd(HttpServer.Request req, HttpServer.Response res) throws IOException {
        JSONObject body;
        try {
            body = new JSONObject(req.bodyString(1 << 20));
        } catch (Exception e) {
            res.error(400, "bad request");
            return;
        }
        File dir = dlDir(body.optString("dest", null));
        String urls = body.optString("urls", "");
        int added = 0;
        StringBuilder errors = new StringBuilder();
        for (String line : urls.split("\\s*\\n\\s*")) {
            line = line.trim();
            if (line.isEmpty()) continue;
            try {
                DownloadManager.get(ctx).addUrl(line, dir);
                added++;
            } catch (IOException e) {
                errors.append(e.getMessage()).append('\n');
            }
        }
        if (added == 0) {
            res.error(400, errors.length() > 0 ? errors.toString().trim() : "no links");
            return;
        }
        res.json(200, "{\"added\":" + added + "}");
    }

    private void dlTorrent(HttpServer.Request req, HttpServer.Response res) throws IOException {
        if (req.contentLength <= 0 || req.contentLength > 20 * 1024 * 1024) {
            res.error(400, "bad torrent file");
            return;
        }
        byte[] data = new byte[(int) req.contentLength];
        int off = 0, n;
        while (off < data.length && (n = req.body.read(data, off, data.length - off)) > 0) off += n;
        try {
            DownloadManager.get(ctx).addTorrent(null, data, dlDir(req.param("dest")));
        } catch (IOException e) {
            res.error(400, e.getMessage());
            return;
        }
        res.json(200, "{\"added\":1}");
    }

    // ---------- system ----------

    private void system(HttpServer.Response res) throws IOException {
        JSONObject o = new JSONObject();
        put(o, "name", Settings.serverName(ctx));
        put(o, "model", Build.MANUFACTURER + " " + Build.MODEL);
        put(o, "android", Build.VERSION.RELEASE);
        put(o, "sdk", Build.VERSION.SDK_INT);
        put(o, "uptime", SystemClock.elapsedRealtime());
        put(o, "cores", Runtime.getRuntime().availableProcessors());
        Inet4Address ip = MdnsResponder.lanAddress();
        put(o, "ip", ip == null ? "" : ip.getHostAddress());
        put(o, "host", MdnsResponder.HOST);
        put(o, "port", ServerService.PORT);
        put(o, "httpsPort", ServerService.HTTPS_PORT);
        put(o, "httpsEnabled", serverRef.length > 1 && serverRef[1] != null);
        int conns = 0;
        for (HttpServer s : serverRef) {
            if (s != null) conns += s.activeConnections.get();
        }
        put(o, "connections", conns);

        ActivityManager am = (ActivityManager) ctx.getSystemService(Context.ACTIVITY_SERVICE);
        ActivityManager.MemoryInfo mi = new ActivityManager.MemoryInfo();
        am.getMemoryInfo(mi);
        put(o, "memTotal", mi.totalMem);
        put(o, "memAvail", mi.availMem);

        put(o, "cpu", cpuPercent());

        StatFs st = new StatFs(Environment.getExternalStorageDirectory().getPath());
        put(o, "diskTotal", st.getTotalBytes());
        put(o, "diskFree", st.getAvailableBytes());

        double batTemp = -1;
        Intent bat = ctx.registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
        if (bat != null) {
            int level = bat.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
            int scale = bat.getIntExtra(BatteryManager.EXTRA_SCALE, 100);
            put(o, "battery", level < 0 ? -1 : Math.round(level * 100f / scale));
            put(o, "charging", bat.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0);
            batTemp = bat.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) / 10.0;
            put(o, "batteryTemp", batTemp);
        }

        double cpuTemp = readCpuTemp();
        if (cpuTemp > 0) {
            put(o, "cpuTemp", cpuTemp);
            put(o, "temp", cpuTemp);
        } else if (batTemp > 0) {
            put(o, "temp", batTemp);
        }

        put(o, "eco", ServerService.isEcoMode());
        put(o, "rx", TrafficStats.getTotalRxBytes());
        put(o, "tx", TrafficStats.getTotalTxBytes());
        put(o, "time", System.currentTimeMillis());
        put(o, "version", Flavor.VERSION);
        put(o, "flavor", Flavor.ROOT ? "root" : "normal");
        res.json(200, o.toString());
    }

    /** Real CPU calculation: /proc/stat if readable or via root; otherwise process-level CPU usage. */
    private synchronized double cpuPercent() {
        // 1. Try reading /proc/stat directly (works on Android <= 7 or permissive SELinux)
        try (RandomAccessFile r = new RandomAccessFile("/proc/stat", "r")) {
            String line = r.readLine();
            if (line != null && line.startsWith("cpu ")) {
                double pct = parseCpuStat(line);
                if (pct >= 0) return pct;
            }
        } catch (Exception ignored) { }

        // 2. In Root edition, read /proc/stat via root shell if granted
        if (Flavor.ROOT && RootShell.available()) {
            try {
                String out = RootShell.main().exec("head -n 1 /proc/stat", 600);
                if (out != null && out.startsWith("cpu ")) {
                    double pct = parseCpuStat(out);
                    if (pct >= 0) return pct;
                }
            } catch (Exception ignored) { }
        }

        // 3. Process-level CPU from android.os.Process.getElapsedCpuTime()
        try {
            long procCpu = android.os.Process.getElapsedCpuTime();
            long now = SystemClock.elapsedRealtime();
            int cores = Math.max(1, Runtime.getRuntime().availableProcessors());
            double pct = -1;
            if (lastProcCpu > 0 && lastProcTime > 0 && now > lastProcTime) {
                long dCpu = procCpu - lastProcCpu;
                long dTime = now - lastProcTime;
                pct = (dCpu * 100.0) / (dTime * cores);
                pct = Math.min(100.0, Math.max(0.0, pct));
            }
            lastProcCpu = procCpu;
            lastProcTime = now;
            if (pct >= 0) return Math.round(pct * 10.0) / 10.0;
        } catch (Exception ignored) { }

        return 0.5;
    }

    private double parseCpuStat(String line) {
        String[] t = line.trim().split("\\s+");
        if (t.length < 5) return -1;
        long idle = Long.parseLong(t[4]) + (t.length > 5 ? Long.parseLong(t[5]) : 0);
        long total = 0;
        for (int i = 1; i < t.length; i++) total += Long.parseLong(t[i]);
        long[] cur = {total, idle};
        double pct = -1;
        if (lastCpu != null && cur[0] > lastCpu[0]) {
            pct = 100.0 * (1 - (double) (cur[1] - lastCpu[1]) / (cur[0] - lastCpu[0]));
        }
        lastCpu = cur;
        return pct >= 0 ? Math.round(pct * 10.0) / 10.0 : -1;
    }

    public static double readCpuTemp() {
        for (int i = 0; i <= 24; i++) {
            File f = new File("/sys/class/thermal/thermal_zone" + i + "/temp");
            if (f.exists() && f.canRead()) {
                try (RandomAccessFile r = new RandomAccessFile(f, "r")) {
                    String line = r.readLine();
                    if (line != null) {
                        double t = Double.parseDouble(line.trim());
                        if (t > 1000) t = t / 1000.0;
                        if (t >= 15.0 && t <= 110.0) {
                            return Math.round(t * 10.0) / 10.0;
                        }
                    }
                } catch (Exception ignored) { }
            }
        }
        return -1;
    }

    private static long readLong(String path) {
        try (RandomAccessFile r = new RandomAccessFile(path, "r")) {
            return Long.parseLong(r.readLine().trim());
        } catch (Exception e) {
            return -1;
        }
    }

    // ---------- helpers ----------

    private static void put(JSONObject o, String k, Object v) {
        try {
            o.put(k, v);
        } catch (Exception ignored) { }
    }

    private static byte[] readAll(InputStream in) throws IOException {
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        byte[] buf = new byte[16384];
        int n;
        while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
        return bo.toByteArray();
    }

    private static final Map<String, String> MIME = new HashMap<>();

    static {
        String[] m = {
            "html", "text/html; charset=utf-8", "htm", "text/html; charset=utf-8", "css", "text/css; charset=utf-8",
            "js", "application/javascript; charset=utf-8", "json", "application/json", "svg", "image/svg+xml",
            "txt", "text/plain; charset=utf-8", "log", "text/plain; charset=utf-8", "md", "text/plain; charset=utf-8",
            "csv", "text/plain; charset=utf-8", "xml", "text/xml; charset=utf-8", "ico", "image/x-icon",
            "jpg", "image/jpeg", "jpeg", "image/jpeg", "png", "image/png", "gif", "image/gif", "webp", "image/webp",
            "bmp", "image/bmp", "heic", "image/heic", "heif", "image/heif",
            "mp4", "video/mp4", "m4v", "video/mp4", "webm", "video/webm", "mkv", "video/x-matroska", "3gp", "video/3gpp",
            "mov", "video/quicktime", "avi", "video/x-msvideo",
            "mp3", "audio/mpeg", "m4a", "audio/mp4", "aac", "audio/aac", "ogg", "audio/ogg", "opus", "audio/ogg",
            "wav", "audio/wav", "flac", "audio/flac", "amr", "audio/amr",
            "pdf", "application/pdf", "zip", "application/zip", "apk", "application/vnd.android.package-archive",
        };
        for (int i = 0; i < m.length; i += 2) MIME.put(m[i], m[i + 1]);
    }

    /** Percent-encodes a value for a query string. URLEncoder emits '+' for spaces, but our
     *  query parser treats '+' as a literal plus, so spaces must go as %20. */
    static String encodeQuery(String value) {
        try {
            return java.net.URLEncoder.encode(value, "UTF-8").replace("+", "%20");
        } catch (Exception e) {
            return value;
        }
    }

    static String mime(String name) {
        int dot = name.lastIndexOf('.');
        String t = dot >= 0 ? MIME.get(name.substring(dot + 1).toLowerCase(Locale.ROOT)) : null;
        return t == null ? "application/octet-stream" : t;
    }

    private void checkUpdate(HttpServer.Response res) throws IOException {
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    String apiUrl = "https://api.github.com/repos/JonyGOi/NexShare/releases/latest";
                    java.net.HttpURLConnection conn = (java.net.HttpURLConnection) new java.net.URL(apiUrl).openConnection();
                    conn.setRequestProperty("User-Agent", "NexShare/" + Flavor.VERSION);
                    conn.setRequestProperty("Accept", "application/vnd.github+json");
                    conn.setConnectTimeout(8000);
                    conn.setReadTimeout(12000);
                    int code = conn.getResponseCode();
                    if (code != 200) {
                        res.error(code, "GitHub returned " + code);
                        return;
                    }
                    InputStream in = conn.getInputStream();
                    ByteArrayOutputStream bo = new ByteArrayOutputStream();
                    byte[] buf = new byte[8192];
                    int n;
                    while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
                    String json = new String(bo.toByteArray(), StandardCharsets.UTF_8);
                    JSONObject rel = new JSONObject(json);

                    String tag = rel.optString("tag_name", "");
                    String latestVer = tag.startsWith("v") ? tag.substring(1) : tag;
                    String currentVer = Flavor.VERSION;
                    boolean hasUpdate = !currentVer.equals(latestVer);

                    JSONArray assets = rel.optJSONArray("assets");
                    String downloadUrl = "";
                    String assetName = "";
                    long size = 0;
                    if (assets != null) {
                        for (int i = 0; i < assets.length(); i++) {
                            JSONObject a = assets.getJSONObject(i);
                            String aname = a.optString("name", "");
                            if (aname.endsWith(".apk")) {
                                boolean isRootAsset = aname.toLowerCase(Locale.ROOT).contains("root");
                                if (Flavor.ROOT == isRootAsset) {
                                    downloadUrl = a.optString("browser_download_url", "");
                                    assetName = aname;
                                    size = a.optLong("size", 0);
                                    break;
                                }
                            }
                        }
                    }

                    JSONObject out = new JSONObject();
                    put(out, "ok", true);
                    put(out, "currentVersion", currentVer);
                    put(out, "latestVersion", latestVer);
                    put(out, "hasUpdate", hasUpdate);
                    put(out, "title", rel.optString("name", tag));
                    put(out, "notes", rel.optString("body", ""));
                    put(out, "releaseUrl", rel.optString("html_url", ""));
                    put(out, "downloadUrl", downloadUrl);
                    put(out, "assetName", assetName);
                    put(out, "size", size);
                    put(out, "flavor", Flavor.ROOT ? "root" : "normal");
                    res.json(200, out.toString());
                } catch (Exception e) {
                    try {
                        res.error(500, "Failed to check update: " + e.getMessage());
                    } catch (Exception ignored) { }
                }
            }
        }, "update-check").start();
    }

    public static class UpdateTask {
        public static volatile String state = "idle"; // idle, waiting_idle, downloading, installing, prompt, done, error
        public static volatile int progress = 0;
        public static volatile long downloaded = 0;
        public static volatile long total = 0;
        public static volatile String message = "";
        public static volatile String error = "";
        public static volatile String version = "";
    }

    private void updateStatus(HttpServer.Response res) throws IOException {
        JSONObject o = new JSONObject();
        put(o, "ok", true);
        put(o, "state", UpdateTask.state);
        put(o, "progress", UpdateTask.progress);
        put(o, "downloaded", UpdateTask.downloaded);
        put(o, "total", UpdateTask.total);
        put(o, "msg", UpdateTask.message);
        put(o, "error", UpdateTask.error);
        put(o, "version", UpdateTask.version);
        put(o, "flavor", Flavor.ROOT ? "root" : "normal");
        put(o, "autoUpdate", Settings.autoUpdateEnabled(ctx));
        put(o, "activeTransfers", TransferTracker.getActiveCount());
        put(o, "busy", TransferTracker.isBusy(ctx));
        res.json(200, o.toString());
    }

    private void updateAuto(HttpServer.Request req, HttpServer.Response res) throws IOException {
        if ("POST".equals(req.method)) {
            String en = req.param("enabled");
            if (en == null) {
                try {
                    JSONObject b = new JSONObject(req.bodyString(1024));
                    if (b.has("enabled")) en = b.optBoolean("enabled") ? "1" : "0";
                } catch (Exception ignored) { }
            }
            if (en != null) {
                boolean on = "1".equals(en) || "true".equalsIgnoreCase(en);
                Settings.setAutoUpdateEnabled(ctx, on);
                if (on) {
                    AutoUpdater.get(ctx).triggerCheck();
                }
            }
        }
        JSONObject o = new JSONObject();
        put(o, "ok", true);
        put(o, "autoUpdate", Settings.autoUpdateEnabled(ctx));
        put(o, "busy", TransferTracker.isBusy(ctx));
        put(o, "activeTransfers", TransferTracker.getActiveCount());
        res.json(200, o.toString());
    }

    private void installUpdate(HttpServer.Request req, HttpServer.Response res) throws IOException {
        String downloadUrl = req.param("url");
        if (downloadUrl == null || downloadUrl.isEmpty()) {
            try {
                JSONObject b = new JSONObject(req.bodyString(4096));
                downloadUrl = b.optString("url", null);
            } catch (Exception ignored) { }
        }
        if (downloadUrl == null || downloadUrl.isEmpty()) {
            res.error(400, "Missing download URL");
            return;
        }

        if ("downloading".equals(UpdateTask.state) || "installing".equals(UpdateTask.state)) {
            res.json(200, "{\"ok\":true,\"status\":\"already_running\",\"msg\":\"Update already in progress\"}");
            return;
        }

        UpdateTask.state = "downloading";
        UpdateTask.progress = 0;
        UpdateTask.downloaded = 0;
        UpdateTask.total = 0;
        UpdateTask.message = "Starting download...";
        UpdateTask.error = "";

        // Respond immediately to prevent HTTP timeouts and Failed to fetch!
        res.json(200, "{\"ok\":true,\"status\":\"started\",\"msg\":\"Update download started in background\"}");

        final String targetUrl = downloadUrl;
        new Thread(new Runnable() {
            @Override
            public void run() {
                File apkDir = new File(ctx.getCacheDir(), "apk");
                apkDir.mkdirs();
                File apk = new File(apkDir, "update.apk");

                try {
                    java.net.HttpURLConnection conn = (java.net.HttpURLConnection) new java.net.URL(targetUrl).openConnection();
                    conn.setRequestProperty("User-Agent", "NexShare/" + Flavor.VERSION);
                    conn.setInstanceFollowRedirects(true);
                    conn.setConnectTimeout(15000);
                    conn.setReadTimeout(60000);
                    int code = conn.getResponseCode();
                    if (code >= 400) {
                        UpdateTask.state = "error";
                        UpdateTask.error = "HTTP error " + code + " downloading update";
                        return;
                    }
                    long len = conn.getContentLengthLong();
                    UpdateTask.total = len > 0 ? len : 48000000;
                    UpdateTask.message = "Downloading update...";

                    try (InputStream in = conn.getInputStream(); OutputStream out = new FileOutputStream(apk)) {
                        byte[] buf = new byte[65536];
                        int n;
                        long done = 0;
                        while ((n = in.read(buf)) > 0) {
                            out.write(buf, 0, n);
                            done += n;
                            UpdateTask.downloaded = done;
                            if (UpdateTask.total > 0) {
                                UpdateTask.progress = (int) Math.min(99, (done * 100) / UpdateTask.total);
                            }
                        }
                    }

                    UpdateTask.progress = 100;

                    // Before applying the update, ensure no active transfers are in flight
                    while (TransferTracker.isBusy(ctx)) {
                        UpdateTask.state = "waiting_idle";
                        UpdateTask.message = "Update downloaded. Waiting for active file transfers to complete before updating...";
                        try {
                            Thread.sleep(3000);
                        } catch (InterruptedException ignored) { }
                    }

                    if (Flavor.ROOT && RootShell.available()) {
                        UpdateTask.state = "installing";
                        UpdateTask.message = "Installing update via Root... NexShare will restart.";
                        String out = RootShell.main().exec("pm install -r -g " + RootShell.q(apk.getAbsolutePath()), 180000);
                        apk.delete();
                        if (out.toLowerCase(Locale.ROOT).contains("success")) {
                            UpdateTask.state = "done";
                            UpdateTask.message = "Updated successfully! Server is restarting...";
                            try {
                                RootShell.main().exec("am start-foreground-service -n com.nexshare/.ServerService", 5000);
                            } catch (Exception ignored) { }
                        } else {
                            UpdateTask.state = "error";
                            UpdateTask.error = "Root install failed: " + out.trim();
                        }
                    } else {
                        UpdateTask.state = "prompt";
                        UpdateTask.message = "Update downloaded. Installer prompt opened on your phone screen.";
                        Uri uri = NexFileProvider.uriFor(ctx, apk);
                        Intent i = new Intent(Intent.ACTION_VIEW);
                        i.setDataAndType(uri, "application/vnd.android.package-archive");
                        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK);
                        ctx.startActivity(i);
                    }
                } catch (Exception e) {
                    apk.delete();
                    UpdateTask.state = "error";
                    UpdateTask.error = "Update failed: " + e.getMessage();
                }
            }
        }, "update-install").start();
    }

    // ---------- debrid hub & real-debrid API ----------

    private void handleDebridRest(HttpServer.Request req, HttpServer.Response res) throws IOException {
        res.header("Access-Control-Allow-Origin", "*");
        res.header("Access-Control-Allow-Methods", "GET, POST, PUT, DELETE, OPTIONS");
        res.header("Access-Control-Allow-Headers", "*");
        if ("OPTIONS".equals(req.method)) {
            res.json(200, "{}");
            return;
        }

        String p = req.path;
        String token = getOrCreateStreamToken();
        String host = req.header("host");
        String proto = req.isSecure ? "https" : "http";
        String baseUrl = proto + "://" + (host != null ? host : (MdnsResponder.HOST + ":" + ServerService.PORT));
        String tunnel = tunnelPublicUrl();
        String remoteUrl = tunnel.isEmpty() ? baseUrl : tunnel;

        DownloadManager dm = DownloadManager.get(ctx);
        TorrentEngine te = dm.torrentEngine();

        if (p.equals("/rest/1.0/user")) {
            JSONObject u = new JSONObject();
            put(u, "id", 1);
            put(u, "username", Settings.authMode(ctx).equals(Settings.MODE_PASSWORD) ? Settings.username(ctx) : "nexshare");
            put(u, "email", "nexshare@local");
            put(u, "points", 1000);
            put(u, "locale", "en");
            put(u, "avatar", "");
            put(u, "type", "premium");
            put(u, "premium", 9999999999L);
            put(u, "expiration", "2039-12-31T23:59:59.000Z");
            File storage = DownloadManager.defaultDir();
            put(u, "free_space", TorrentEngine.freeSpace(storage));
            put(u, "total_space", TorrentEngine.totalSpace(storage));
            res.json(200, u.toString());
            return;
        }

        if (p.startsWith("/rest/1.0/torrents/instantAvailability")) {
            String rest = p.substring("/rest/1.0/torrents/instantAvailability".length()).replaceAll("^/+", "");
            JSONObject root = new JSONObject();
            for (String rawHash : rest.split("[/,]")) {
                String hash = rawHash.trim().toLowerCase(Locale.ROOT);
                if (hash.isEmpty()) continue;
                DownloadManager.Task t = dm.findByHash(hash);
                if (t != null) {
                    JSONObject rd = new JSONObject();
                    JSONArray arr = new JSONArray();
                    JSONArray variants = new JSONArray();
                    JSONObject fObj = new JSONObject();
                    put(fObj, "filename", t.name);
                    put(fObj, "filesize", t.total > 0 ? t.total : 1000000L);
                    variants.put(fObj);
                    put(rd, "rd", variants);
                    arr.put(rd);
                    put(root, hash, arr);
                } else {
                    put(root, hash, new JSONObject());
                }
            }
            res.json(200, root.toString());
            return;
        }

        if (p.equals("/rest/1.0/hosts/domains")) {
            JSONObject hosts = new JSONObject();
            JSONArray supported = new JSONArray();
            supported.put("nexshare");
            supported.put("torrent");
            put(hosts, "hosts", supported);
            res.json(200, hosts.toString());
            return;
        }

        if (p.equals("/rest/1.0/time/iso")) {
            res.text(200, "text/plain", isoNow());
            return;
        }

        if (p.equals("/rest/1.0/torrents")) {
            List<DownloadManager.Task> tasks = dm.getTorrentTasks();
            JSONArray arr = new JSONArray();
            for (DownloadManager.Task t : tasks) {
                JSONObject o = new JSONObject();
                put(o, "id", t.id);
                put(o, "filename", t.name);
                put(o, "original_filename", t.name);
                put(o, "hash", t.hash != null ? t.hash.toLowerCase(Locale.ROOT) : "");
                put(o, "bytes", t.total > 0 ? t.total : 0L);
                put(o, "original_bytes", t.total > 0 ? t.total : 0L);
                put(o, "host", "nexshare");
                put(o, "split", 2000);
                put(o, "progress", t.total > 0 ? Math.min(100.0, (t.done * 100.0) / t.total) : 0);
                put(o, "status", ("done".equals(t.status) || "seeding".equals(t.status)) ? "downloaded" : "downloading");
                put(o, "added", HttpServer.httpDate(t.created));
                put(o, "speed", t.speed);
                put(o, "seeders", t.seeds);

                JSONArray links = new JSONArray();
                if (te != null) {
                    List<JSONObject> files = te.listFiles(t);
                    for (int i = 0; i < files.size(); i++) {
                        links.put(remoteUrl + "/rest/1.0/unrestrict/link?id=" + t.id + "&file=" + i);
                    }
                }
                put(o, "links", links);
                arr.put(o);
            }
            res.json(200, arr.toString());
            return;
        }

        if (p.equals("/rest/1.0/torrents/addMagnet")) {
            String magnet = req.param("magnet");
            if (magnet == null || magnet.isEmpty()) {
                String body = req.bodyString(65536);
                if (body != null) {
                    if (body.startsWith("magnet:")) magnet = body.trim();
                    else if (body.contains("magnet=")) {
                        int idx = body.indexOf("magnet=");
                        magnet = URLDecoder.decode(body.substring(idx + 7).split("&")[0], "UTF-8");
                    } else {
                        try {
                            JSONObject b = new JSONObject(body);
                            magnet = b.optString("magnet");
                        } catch (Exception ignored) { }
                    }
                }
            }
            if (magnet == null || !magnet.startsWith("magnet:")) {
                res.error(400, "invalid magnet link");
                return;
            }
            String hash = magnetHash(magnet);
            DownloadManager.Task existing = dm.findByHash(hash);
            if (existing != null) {
                JSONObject o = new JSONObject();
                put(o, "id", existing.id);
                put(o, "uri", "/rest/1.0/torrents/info/" + existing.id);
                res.json(200, o.toString());
                return;
            }
            DownloadManager.Task t = dm.addUrl(magnet, DownloadManager.defaultDir());
            if (te != null) te.autoPrioritize(t);
            if (isOn(req, null, "paused")) dm.pause(t.id); // pick the files first, like qBittorrent
            JSONObject o = new JSONObject();
            put(o, "id", t.id);
            put(o, "uri", "/rest/1.0/torrents/info/" + t.id);
            res.json(200, o.toString());
            return;
        }

        if (p.equals("/rest/1.0/torrents/addTorrent")) {
            byte[] torrentBytes = req.bodyBytes();
            if (torrentBytes == null || torrentBytes.length == 0) {
                res.error(400, "empty torrent body");
                return;
            }
            DownloadManager.Task t = dm.addTorrent(null, torrentBytes, DownloadManager.defaultDir());
            if (te != null) te.autoPrioritize(t);
            if (isOn(req, null, "paused")) dm.pause(t.id);
            JSONObject o = new JSONObject();
            put(o, "id", t.id);
            put(o, "uri", "/rest/1.0/torrents/info/" + t.id);
            res.json(200, o.toString());
            return;
        }

        if (p.startsWith("/rest/1.0/torrents/info/")) {
            String id = p.substring("/rest/1.0/torrents/info/".length()).trim();
            DownloadManager.Task t = dm.getTask(id);
            if (t == null) {
                res.error(404, "torrent not found");
                return;
            }
            JSONObject o = new JSONObject();
            put(o, "id", t.id);
            put(o, "filename", t.name);
            put(o, "original_filename", t.name);
            put(o, "hash", t.hash != null ? t.hash.toLowerCase(Locale.ROOT) : "");
            put(o, "bytes", t.total > 0 ? t.total : 0L);
            put(o, "original_bytes", t.total > 0 ? t.total : 0L);
            put(o, "host", "nexshare");
            put(o, "split", 2000);
            put(o, "progress", t.total > 0 ? Math.min(100.0, (t.done * 100.0) / t.total) : 0);
            put(o, "status", ("done".equals(t.status) || "seeding".equals(t.status)) ? "downloaded" : "downloading");
            put(o, "added", HttpServer.httpDate(t.created));
            put(o, "speed", t.speed);
            put(o, "seeders", t.seeds);
            put(o, "error", t.error == null ? "" : t.error);

            JSONArray filesArr = new JSONArray();
            JSONArray linksArr = new JSONArray();
            if (te != null) {
                List<JSONObject> fList = te.listFiles(t);
                for (int i = 0; i < fList.size(); i++) {
                    JSONObject fi = fList.get(i);
                    JSONObject fo = new JSONObject();
                    put(fo, "id", i + 1);
                    put(fo, "path", "/" + fi.optString("path"));
                    put(fo, "bytes", fi.optLong("size"));
                    put(fo, "done", fi.optLong("done"));
                    put(fo, "selected", fi.optInt("selected", 1));
                    put(fo, "progress", fi.optDouble("progress", 0));
                    put(fo, "selected", 1);
                    filesArr.put(fo);
                    linksArr.put(remoteUrl + "/rest/1.0/unrestrict/link?id=" + t.id + "&file=" + i);
                }
            }
            put(o, "files", filesArr);
            put(o, "links", linksArr);
            res.json(200, o.toString());
            return;
        }

        if (p.startsWith("/rest/1.0/torrents/purgeFile/")) {
            String id = p.substring("/rest/1.0/torrents/purgeFile/".length()).trim();
            DownloadManager.Task t = dm.getTask(id);
            if (t == null) {
                res.error(404, "torrent not found");
                return;
            }
            int idx = -1;
            try {
                idx = Integer.parseInt(req.param("file")) - 1; // 1-based like Real-Debrid
            } catch (Exception ignored) { }
            boolean ok = te != null && te.purgeFile(t, idx);
            dm.save();
            JSONObject o = new JSONObject();
            put(o, "ok", ok);
            put(o, "freeSpace", TorrentEngine.freeSpace(new File(t.dest)));
            put(o, "totalSpace", TorrentEngine.totalSpace(new File(t.dest)));
            res.json(200, o.toString());
            return;
        }

        if (p.startsWith("/rest/1.0/torrents/selectFiles/")) {
            String id = p.substring("/rest/1.0/torrents/selectFiles/".length()).trim();
            DownloadManager.Task t = dm.getTask(id);
            if (t != null && te != null) {
                String sel = req.param("files");
                if (sel == null) {
                    String body = req.bodyString(65536);
                    if (body != null) {
                        for (String part : body.split("&")) {
                            int eq = part.indexOf('=');
                            if (eq > 0 && "files".equals(part.substring(0, eq).trim())) {
                                sel = URLDecoder.decode(part.substring(eq + 1), "UTF-8");
                                break;
                            }
                        }
                        if (sel == null && body.startsWith("magnet:")) sel = "0";
                    }
                }
                sel = sel == null ? "" : sel.trim();
                if (sel.isEmpty() || "all".equalsIgnoreCase(sel)) {
                    te.setSelection(t, null);
                } else if ("none".equalsIgnoreCase(sel)) {
                    te.setSelection(t, new int[0]);
                } else {
                    java.util.List<Integer> picked = new java.util.ArrayList<>();
                    for (String part : sel.split(",")) {
                        try {
                            int idx = Integer.parseInt(part.trim());
                            // Real-Debrid file ids are 1-based; NexShare indexes files from 0.
                            if (idx >= 1) picked.add(idx - 1);
                        } catch (NumberFormatException ignored) { }
                    }
                    int[] ids = new int[picked.size()];
                    for (int k = 0; k < ids.length; k++) ids[k] = picked.get(k);
                    te.setSelection(t, ids);
                }
                dm.save();
            }
            res.json(200, "{\"status\":\"ok\"}");
            return;
        }

        if (p.startsWith("/rest/1.0/torrents/delete/") || p.equals("/rest/1.0/torrents/delete")) {
            String id = p.startsWith("/rest/1.0/torrents/delete/")
                ? p.substring("/rest/1.0/torrents/delete/".length()).trim()
                : req.param("id");
            if (id == null || id.isEmpty()) id = req.param("id");
            String files = req.param("files");
            boolean deleteFiles = "1".equals(files) || "true".equalsIgnoreCase(files) || "all".equalsIgnoreCase(files);
            if (id != null) dm.remove(id, deleteFiles);
            res.json(200, "{\"status\":\"ok\"}");
            return;
        }

        if (p.startsWith("/rest/1.0/torrents/pause/") || p.startsWith("/rest/1.0/torrents/resume/")) {
            boolean pause = p.startsWith("/rest/1.0/torrents/pause/");
            String prefix = pause ? "/rest/1.0/torrents/pause/" : "/rest/1.0/torrents/resume/";
            String id = p.substring(prefix.length()).trim();
            if (id.isEmpty()) id = req.param("id");
            if (id != null) {
                try {
                    if (pause) dm.pause(id);
                    else dm.resume(id);
                } catch (Exception e) {
                    res.error(409, e.getMessage());
                    return;
                }
            }
            res.json(200, "{\"status\":\"ok\"}");
            return;
        }

        if (p.equals("/rest/1.0/unrestrict/link")) {
            String taskId = req.param("id");
            int fileIdx = 0;
            try { fileIdx = Integer.parseInt(req.param("file")); } catch (Exception ignored) { }

            if (taskId == null || taskId.isEmpty()) {
                String link = req.param("link");
                if (link == null) {
                    String b = req.bodyString(4096);
                    if (b != null && b.contains("link=")) {
                        link = URLDecoder.decode(b.substring(b.indexOf("link=") + 5).split("&")[0], "UTF-8");
                    }
                }
                if (link != null && link.contains("id=")) {
                    int idIdx = link.indexOf("id=");
                    taskId = link.substring(idIdx + 3).split("&")[0];
                    if (link.contains("file=")) {
                        try {
                            fileIdx = Integer.parseInt(link.substring(link.indexOf("file=") + 5).split("&")[0]);
                        } catch (Exception ignored) { }
                    }
                }
            }

            DownloadManager.Task t = taskId != null ? dm.getTask(taskId) : null;
            if (t == null) {
                List<DownloadManager.Task> list = dm.getTorrentTasks();
                if (!list.isEmpty()) t = list.get(0);
            }

            if (t == null) {
                res.error(404, "torrent not found");
                return;
            }

            if (te != null) te.prioritizeFile(t, fileIdx);

            String fileName = t.name;
            String streamUrl;
            if ("http".equals(t.type)) {
                streamUrl = t.url;
            } else {
                String filePath = t.name;
                if (te != null) {
                    List<JSONObject> fList = te.listFiles(t);
                    if (fileIdx >= 0 && fileIdx < fList.size()) {
                        JSONObject fi = fList.get(fileIdx);
                        filePath = fi.optString("path", t.name);
                        fileName = fi.optString("name", t.name);
                    }
                }
                File targetFile = new File(new File(t.dest), filePath);
                String streamPath = targetFile.getAbsolutePath();
                // baseUrl = how the caller reached us (LAN or tunnel), so links keep working for them.
                streamUrl = baseUrl + "/api/file?path=" + encodeQuery(streamPath) + "&token=" + token;
            }

            if ("GET".equals(req.method)) {
                res.header("Location", streamUrl);
                res.text(302, "text/plain", "Redirecting to stream: " + streamUrl);
                return;
            }

            JSONObject out = new JSONObject();
            put(out, "id", t.id + "_" + fileIdx);
            put(out, "filename", fileName);
            put(out, "mimeType", mime(fileName));
            put(out, "filesize", t.total);
            put(out, "link", streamUrl);
            put(out, "host", "nexshare");
            put(out, "download", streamUrl + "&dl=1");
            put(out, "streamable", 1);
            res.json(200, out.toString());
            return;
        }

        res.error(404, "not found");
    }

    /** Extracts the info hash from a magnet URI (hex, or base32 on older links). */
    static String magnetHash(String magnet) {
        if (magnet == null) return null;
        int idx = magnet.toLowerCase(Locale.ROOT).indexOf("urn:btih:");
        if (idx < 0) return null;
        int start = idx + 9;
        int end = start;
        while (end < magnet.length()) {
            char c = magnet.charAt(end);
            if (c == '&' || c == '?' || c == '#' || c == ' ') break;
            end++;
        }
        String raw = magnet.substring(start, end).trim();
        if (raw.length() == 40) return raw.toLowerCase(Locale.ROOT);
        if (raw.length() == 32) {
            // base32 -> hex
            try {
                String alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567";
                StringBuilder bits = new StringBuilder();
                for (char c : raw.toUpperCase(Locale.ROOT).toCharArray()) {
                    int v = alphabet.indexOf(c);
                    if (v < 0) return raw.toLowerCase(Locale.ROOT);
                    bits.append(String.format(Locale.ROOT, "%5s", Integer.toBinaryString(v)).replace(' ', '0'));
                }
                StringBuilder hex = new StringBuilder();
                for (int i = 0; i + 4 <= bits.length() && hex.length() < 40; i += 4)
                    hex.append(Character.forDigit(Integer.parseInt(bits.substring(i, i + 4), 2), 16));
                return hex.toString();
            } catch (Exception e) {
                return raw.toLowerCase(Locale.ROOT);
            }
        }
        return raw.toLowerCase(Locale.ROOT);
    }

    private static String isoNow() {
        java.text.SimpleDateFormat f = new java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.ROOT);
        f.setTimeZone(java.util.TimeZone.getTimeZone("UTC"));
        return f.format(new java.util.Date());
    }

    private void debridUnrestrict(HttpServer.Request req, HttpServer.Response res) throws IOException {
        JSONObject body;
        try {
            body = new JSONObject(req.bodyString(65536));
        } catch (Exception e) {
            res.error(400, "bad request");
            return;
        }
        String magnet = body.optString("magnet", "").trim();
        if (magnet.isEmpty()) magnet = body.optString("url", "").trim();
        if (magnet.isEmpty()) {
            res.error(400, "missing magnet link or url");
            return;
        }
        File dest = dlDir(body.optString("dest", null));
        DownloadManager dm = DownloadManager.get(ctx);
        DownloadManager.Task t = dm.addUrl(magnet, dest);
        TorrentEngine te = dm.torrentEngine();
        if (te != null) te.autoPrioritize(t);
        // Added paused on purpose: the file picker must come before any byte is downloaded.
        if (body.optBoolean("paused", true)) dm.pause(t.id);

        String token = getOrCreateStreamToken();
        String host = req.header("host");
        String proto = req.isSecure ? "https" : "http";
        String baseUrl = proto + "://" + (host != null ? host : (MdnsResponder.HOST + ":" + ServerService.PORT));
        String tunnel = tunnelPublicUrl();
        String remoteUrl = tunnel.isEmpty() ? null : tunnel;

        JSONObject o = new JSONObject();
        put(o, "ok", true);
        put(o, "task", t.toJson());
        put(o, "token", token);
        put(o, "baseUrl", baseUrl);
        put(o, "remoteUrl", remoteUrl);

        put(o, "files", debridFileList(t, te, baseUrl, remoteUrl, token));
        res.json(200, o.toString());
    }

    private void debridFiles(HttpServer.Request req, HttpServer.Response res) throws IOException {
        String id = req.param("id");
        DownloadManager.Task t = DownloadManager.get(ctx).getTask(id);
        if (t == null) {
            res.error(404, "task not found");
            return;
        }
        res.json(200, debridPayload(req, t).toString());
    }

    /** Task + file list + stream links, shared by the debrid endpoints. */
    private JSONObject debridPayload(HttpServer.Request req, DownloadManager.Task t) throws IOException {
        DownloadManager dm = DownloadManager.get(ctx);
        TorrentEngine te = dm.torrentEngine();
        String token = getOrCreateStreamToken();
        String host = req.header("host");
        String proto = req.isSecure ? "https" : "http";
        String baseUrl = proto + "://" + (host != null ? host : (MdnsResponder.HOST + ":" + ServerService.PORT));
        String tunnel = tunnelPublicUrl();
        String remoteUrl = tunnel.isEmpty() ? null : tunnel;
        JSONObject o = new JSONObject();
        put(o, "task", t.toJson());
        put(o, "token", token);
        put(o, "baseUrl", baseUrl);
        put(o, "remoteUrl", remoteUrl);
        put(o, "files", debridFileList(t, te, baseUrl, remoteUrl, token));
        return o;
    }

    /** Chooses which files of a torrent download (mode: all | none | videos, or an explicit list). */
    private void debridSelect(HttpServer.Request req, HttpServer.Response res) throws IOException {
        JSONObject body;
        try {
            body = new JSONObject(req.bodyString(1 << 16));
        } catch (Exception e) {
            res.error(400, "bad request");
            return;
        }
        DownloadManager dm = DownloadManager.get(ctx);
        DownloadManager.Task t = dm.getTask(body.optString("id"));
        if (t == null) {
            res.error(404, "task not found");
            return;
        }
        TorrentEngine te = dm.torrentEngine();
        if (te == null) {
            res.error(503, "torrent engine unavailable");
            return;
        }
        String mode = body.optString("mode", "");
        if ("all".equalsIgnoreCase(mode)) {
            te.setSelection(t, null);
        } else if ("none".equalsIgnoreCase(mode)) {
            te.setSelection(t, new int[0]);
        } else if ("videos".equalsIgnoreCase(mode)) {
            List<JSONObject> files = te.listFiles(t);
            java.util.List<Integer> picked = new java.util.ArrayList<>();
            for (int i = 0; i < files.size(); i++) {
                if (TorrentEngine.isVideo(files.get(i).optString("name", ""))) picked.add(i);
            }
            int[] ids = new int[picked.size()];
            for (int k = 0; k < ids.length; k++) ids[k] = picked.get(k);
            te.setSelection(t, ids);
        } else {
            JSONArray arr = body.optJSONArray("files");
            if (arr == null) {
                te.setSelection(t, null);
            } else {
                java.util.List<Integer> picked = new java.util.ArrayList<>();
                for (int i = 0; i < arr.length(); i++) picked.add(arr.optInt(i));
                int[] ids = new int[picked.size()];
                for (int k = 0; k < ids.length; k++) ids[k] = picked.get(k);
                te.setSelection(t, ids);
            }
        }
        dm.save();
        if (body.optBoolean("start", false)) dm.resume(t.id);
        res.json(200, debridPayload(req, t).toString());
    }

    // ------------------------------------------------------------ Nuvio / Stremio addon

    /**
     * Stremio-compatible addon that turns the phone into the debrid resolver:
     * Nuvio asks for streams, we scrape Torrentio, and each result becomes a link that, when
     * played, makes the phone download that exact file (paused until then, so nothing is
     * downloaded just because you browsed).
     */
    private static final String ADDON_SCRAPER = "https://torrentio.strem.fun";

    private boolean handleAddon(HttpServer.Request req, HttpServer.Response res, String p) throws IOException {
        String rest = p.substring("/addon/".length());
        int slash = rest.indexOf('/');
        String key = slash < 0 ? rest : rest.substring(0, slash);
        String tail = slash < 0 ? "" : rest.substring(slash);
        String apiKey = Settings.apiKey(ctx);
        if (!key.equals(apiKey)) {
            res.error(404, "not found");
            return true;
        }
        if (tail.equals("/manifest.json") || tail.equals("/manifest.json/")) {
            addonManifest(res);
            return true;
        }
        if (tail.startsWith("/stream/") && tail.endsWith(".json")) {
            addonStreams(req, res, tail, key);
            return true;
        }
        if (tail.equals("/play")) {
            addonPlay(req, res, key);
            return true;
        }
        return false;
    }

    private String addonBase(HttpServer.Request req) {
        String host = req.header("host");
        String fwd = req.header("x-forwarded-proto");
        if (fwd != null) {
            int comma = fwd.indexOf(',');
            if (comma > 0) fwd = fwd.substring(0, comma);
            fwd = fwd.trim();
        }
        String proto = (fwd != null && (fwd.equals("https") || fwd.equals("http"))) ? fwd : (req.isSecure ? "https" : "http");
        return proto + "://" + (host != null ? host : (MdnsResponder.HOST + ":" + ServerService.PORT));
    }

    private void addonManifest(HttpServer.Response res) throws IOException {
        JSONObject m = new JSONObject();
        put(m, "id", "com.nexshare.debrid");
        put(m, "version", "1.0.0");
        put(m, "name", "NexShare Debrid");
        put(m, "description", "Seu celular como debrid: escolha o stream e ele baixa e entrega na hora.");
        put(m, "types", new JSONArray().put("movie").put("series"));
        put(m, "resources", new JSONArray().put("stream"));
        put(m, "idPrefixes", new JSONArray().put("tt"));
        put(m, "catalogs", new JSONArray());
        res.header("Access-Control-Allow-Origin", "*");
        res.json(200, m.toString());
    }

    /** Fetches the scraper (Torrentio) JSON for a movie/series id. */
    private String fetchScraper(String type, String id) throws IOException {
        String url = ADDON_SCRAPER + "/stream/" + type + "/"
            + java.net.URLEncoder.encode(id, "UTF-8") + ".json";
        java.net.HttpURLConnection c = (java.net.HttpURLConnection) new java.net.URL(url).openConnection();
        c.setConnectTimeout(8000);
        c.setReadTimeout(9000);
        c.setRequestProperty("User-Agent", "NexShare/1.0 (Android)");
        c.setRequestProperty("Accept", "application/json");
        try {
            int code = c.getResponseCode();
            InputStream in = code >= 400 ? c.getErrorStream() : c.getInputStream();
            if (in == null) throw new IOException("HTTP " + code);
            java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) {
                bo.write(buf, 0, n);
                if (bo.size() > 4 * 1024 * 1024) break;
            }
            in.close();
            if (code >= 400) throw new IOException("scraper HTTP " + code);
            return new String(bo.toByteArray(), StandardCharsets.UTF_8);
        } finally {
            c.disconnect();
        }
    }

    private void addonStreams(HttpServer.Request req, HttpServer.Response res, String tail, String key) throws IOException {
        String inner = tail.substring("/stream/".length(), tail.length() - ".json".length());
        int slash = inner.indexOf('/');
        String type = slash < 0 ? "" : inner.substring(0, slash);
        String id = slash < 0 ? "" : inner.substring(slash + 1);
        res.header("Access-Control-Allow-Origin", "*");
        if ((!type.equals("movie") && !type.equals("series")) || !id.startsWith("tt")) {
            res.json(200, "{\"streams\":[]}");
            return;
        }
        JSONArray out = new JSONArray();
        String error = null;
        try {
            JSONObject body = new JSONObject(fetchScraper(type, id));
            JSONArray list = body.optJSONArray("streams");
            String base = addonBase(req);
            if (list != null) {
                for (int i = 0; i < list.length() && out.length() < 30; i++) {
                    JSONObject st = list.optJSONObject(i);
                    if (st == null) continue;
                    String hash = st.optString("infoHash", "");
                    if (hash != null && !hash.isEmpty()) {
                        JSONObject o = new JSONObject();
                        put(o, "name", st.optString("name", "NexShare"));
                        String title = st.optString("title", "");
                        put(o, "title", (title.isEmpty() ? "" : title + "\n") + "▶ desbridar no celular (NexShare)");
                        JSONObject hints = st.optJSONObject("behaviorHints");
                        if (hints != null) put(o, "behaviorHints", hints);
                        StringBuilder u = new StringBuilder(base).append("/addon/").append(key).append("/play?h=").append(hash);
                        int fidx = st.optInt("fileIdx", -1);
                        if (fidx >= 0) u.append("&i=").append(fidx);
                        o.put("url", u.toString());
                        out.put(o);
                    } else {
                        String direct = st.optString("url", "");
                        if (direct != null && !direct.isEmpty()) out.put(st);
                    }
                }
            }
        } catch (Exception e) {
            error = e.getMessage();
        }
        if (out.length() == 0 && error != null) {
            JSONObject o = new JSONObject();
            put(o, "name", "NexShare ⚠");
            put(o, "title", "Não consegui falar com o Torrentio: " + error);
            put(o, "url", addonBase(req) + "/addon/" + key + "/play?h=0000000000000000000000000000000000000000");
            out.put(o);
        }
        JSONObject result = new JSONObject();
        put(result, "streams", out);
        res.json(200, result.toString());
    }

    /** Adds (paused) the torrent of that hash, picks the file and redirects to the stream. */
    private void addonPlay(HttpServer.Request req, HttpServer.Response res, String key) throws IOException {
        String hash = req.param("h");
        if (hash == null || hash.length() < 20) {
            res.error(400, "hash invalido");
            return;
        }
        hash = hash.toLowerCase(Locale.ROOT);
        int want = -1;
        try {
            want = Integer.parseInt(req.param("i"));
        } catch (Exception ignored) { }
        DownloadManager dm = DownloadManager.get(ctx);
        DownloadManager.Task t = dm.findByHash(hash);
        if (t == null) {
            t = dm.addUrl("magnet:?xt=urn:btih:" + hash, DownloadManager.defaultDir());
            t.fromAddon = true;
            dm.pause(t.id); // nothing downloads until the exact file is known
            dm.save();
        }
        TorrentEngine te = dm.torrentEngine();
        if (te == null) {
            res.error(503, "torrent engine unavailable");
            return;
        }
        long deadline = System.currentTimeMillis() + 12000;
        List<JSONObject> files = te.listFiles(t);
        while (files.isEmpty() && System.currentTimeMillis() < deadline) {
            try { Thread.sleep(300); } catch (InterruptedException e) { break; }
            files = te.listFiles(t);
        }
        if (files.isEmpty()) {
            res.error(503, "metadados ainda nao chegaram - toque de novo no stream");
            return;
        }
        int idx = -1;
        if (want >= 0 && want < files.size()) {
            if (TorrentEngine.isVideo(files.get(want).optString("path", ""))) idx = want;
        }
        if (idx < 0) idx = te.pickBestFile(t);
        if (idx < 0) {
            res.error(404, "nenhum video encontrado no torrent");
            return;
        }
        // Only narrow the selection when the addon created the torrent; a torrent the user is
        // already downloading from the panel keeps its own selection.
        if (t.fromAddon && t.selected == null) te.setSelection(t, new int[]{idx});
        te.prioritizeFile(t, idx);
        dm.resume(t.id);
        dm.save();
        String path = files.get(idx).optString("fullPath");
        String url = addonBase(req) + "/api/file?path=" + encodeQuery(path) + "&token=" + key;
        res.header("Location", url);
        res.header("Access-Control-Allow-Origin", "*");
        res.begin(302, null, 0);
    }

    /** Diagnostic used by the web setup page: can the phone reach the scraper? */
    private void addonTest(HttpServer.Request req, HttpServer.Response res) throws IOException {
        String id = req.param("id");
        if (id == null || id.isEmpty()) id = "tt0111161";
        String type = id.contains(":") ? "series" : "movie";
        JSONObject o = new JSONObject();
        put(o, "scraper", ADDON_SCRAPER);
        put(o, "id", id);
        long t0 = System.currentTimeMillis();
        try {
            JSONObject body = new JSONObject(fetchScraper(type, id));
            JSONArray list = body.optJSONArray("streams");
            put(o, "ok", true);
            put(o, "streams", list == null ? 0 : list.length());
        } catch (Exception e) {
            put(o, "ok", false);
            put(o, "error", e.getMessage());
            put(o, "streams", 0);
        }
        put(o, "ms", System.currentTimeMillis() - t0);
        put(o, "manifestPath", "/addon/" + Settings.apiKey(ctx) + "/manifest.json");
        res.json(200, o.toString());
    }

    /** File entries (with stream links) for a debrid task: torrent files or a direct http(s) url. */
    private JSONArray debridFileList(DownloadManager.Task t, TorrentEngine te, String baseUrl, String remoteUrl, String token) {
        JSONArray arr = new JSONArray();
        if ("http".equals(t.type)) {
            JSONObject fi = new JSONObject();
            put(fi, "id", 0);
            put(fi, "name", t.name);
            put(fi, "path", t.name);
            put(fi, "size", t.total);
            put(fi, "done", t.done);
            put(fi, "progress", t.total > 0 ? Math.min(100.0, (t.done * 100.0) / t.total) : 0);
            put(fi, "selected", 1);
            put(fi, "streamUrl", t.url);
            put(fi, "remoteStreamUrl", t.url);
            put(fi, "fullPath", t.url);
            arr.put(fi);
            return arr;
        }
        if (te == null) return arr;
        List<JSONObject> fList = te.listFiles(t);
        File baseDir = new File(t.dest);
        for (int i = 0; i < fList.size(); i++) {
            JSONObject fi = fList.get(i);
            File f = new File(baseDir, fi.optString("path"));
            String fPath = f.getAbsolutePath();
            String encoded = encodeQuery(fPath);
            put(fi, "streamUrl", baseUrl + "/api/file?path=" + encoded + "&token=" + token);
            put(fi, "remoteStreamUrl", remoteUrl != null ? remoteUrl + "/api/file?path=" + encoded + "&token=" + token : null);
            put(fi, "fullPath", fPath);
            arr.put(fi);
        }
        return arr;
    }

    /** True when a boolean flag comes as ?flag=1 on the query or as flag=1 in a form body. */
    private static boolean isOn(HttpServer.Request req, String body, String name) {
        String v = req.param(name);
        if (v == null && body != null) {
            for (String part : body.split("&")) {
                int eq = part.indexOf('=');
                if (eq > 0 && name.equals(part.substring(0, eq).trim())) {
                    v = part.substring(eq + 1).trim();
                    break;
                }
            }
        }
        return v != null && (v.equals("1") || v.equalsIgnoreCase("true") || v.equalsIgnoreCase("yes") || v.equals("on"));
    }

    /** Issues a new API key, invalidating every stream/API link shared so far. */
    private void debridRotateKey(HttpServer.Response res) throws IOException {
        String key = Settings.regenerateApiKey(ctx);
        JSONObject o = new JSONObject();
        put(o, "ok", true);
        put(o, "apiKey", key);
        res.json(200, o.toString());
    }

    private void debridPrioritize(HttpServer.Request req, HttpServer.Response res) throws IOException {
        JSONObject body;
        try {
            body = new JSONObject(req.bodyString(4096));
        } catch (Exception e) {
            res.error(400, "bad request");
            return;
        }
        String id = body.optString("id");
        int fileIndex = body.optInt("fileIndex", 0);
        DownloadManager dm = DownloadManager.get(ctx);
        DownloadManager.Task t = dm.getTask(id);
        if (t != null) {
            TorrentEngine te = dm.torrentEngine();
            if (te != null) te.prioritizeFile(t, fileIndex);
        }
        res.json(200, "{\"ok\":true}");
    }
}
