package com.nexshare;

import android.content.Context;
import android.os.Environment;
import android.os.StatFs;
import android.util.Base64;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;
import java.util.UUID;

/**
 * WebDAV RFC 4918 server implementation for mounting phone storage as a network drive in Windows.
 * Runs on the existing HttpServer under the /dav/ prefix with zero external dependencies.
 */
public class WebDavHandler implements HttpServer.Handler {

    private final Context ctx;
    private final File storageRoot;

    public WebDavHandler(Context ctx) {
        this.ctx = ctx.getApplicationContext();
        this.storageRoot = Environment.getExternalStorageDirectory();
    }

    @Override
    public void handle(HttpServer.Request req, HttpServer.Response res) throws IOException {
        // Enforce WebDAV setting
        if (!Settings.webdavEnabled(ctx)) {
            res.text(404, "text/plain", "WebDAV is disabled in NexShare settings.\n");
            return;
        }

        // Authenticate with HTTP Basic
        if (!authenticate(req, res)) {
            return;
        }

        String m = req.method;
        if ("OPTIONS".equals(m)) {
            handleOptions(req, res);
        } else if ("PROPFIND".equals(m)) {
            handlePropfind(req, res);
        } else if ("GET".equals(m)) {
            handleGet(req, res, false);
        } else if ("HEAD".equals(m)) {
            handleGet(req, res, true);
        } else if ("PUT".equals(m)) {
            handlePut(req, res);
        } else if ("DELETE".equals(m)) {
            handleDelete(req, res);
        } else if ("MKCOL".equals(m)) {
            handleMkcol(req, res);
        } else if ("MOVE".equals(m)) {
            handleMove(req, res);
        } else if ("COPY".equals(m)) {
            handleCopy(req, res);
        } else if ("LOCK".equals(m)) {
            handleLock(req, res);
        } else if ("UNLOCK".equals(m)) {
            handleUnlock(req, res);
        } else if ("PROPPATCH".equals(m)) {
            handleProppatch(req, res);
        } else {
            res.header("Allow", "OPTIONS, GET, HEAD, PROPFIND, PUT, DELETE, MKCOL, MOVE, COPY, LOCK, UNLOCK, PROPPATCH");
            res.begin(405, null, 0);
        }
    }

    // ---------- authentication ----------

    private boolean authenticate(HttpServer.Request req, HttpServer.Response res) throws IOException {
        String auth = req.header("authorization");
        if (auth != null && auth.toLowerCase(Locale.ROOT).startsWith("basic ")) {
            try {
                String b64 = auth.substring(6).trim();
                byte[] decoded = Base64.decode(b64, Base64.DEFAULT);
                String creds = new String(decoded, StandardCharsets.UTF_8);
                int colon = creds.indexOf(':');
                if (colon >= 0) {
                    String user = creds.substring(0, colon).trim();
                    String pass = creds.substring(colon + 1);

                    // Strip Windows machine/domain prefix (e.g. WORKGROUP\nexshare)
                    int slash = user.lastIndexOf('\\');
                    if (slash >= 0) user = user.substring(slash + 1);

                    String currentPin = Settings.pin(ctx);
                    boolean userMatches = user.equalsIgnoreCase("nexshare")
                        || user.equalsIgnoreCase(Settings.username(ctx));
                    boolean passMatches = currentPin.equals(pass)
                        || (Settings.MODE_PASSWORD.equals(Settings.authMode(ctx)) && Settings.checkPassword(ctx, pass));

                    if (userMatches && passMatches) {
                        return true;
                    }
                }
            } catch (Exception ignored) { }
        }
        res.header("WWW-Authenticate", "Basic realm=\"NexShare\"");
        res.text(401, "text/plain", "Authentication required (user: nexshare, password: PIN)\n");
        return false;
    }

    // ---------- path resolution ----------

    public File resolveFile(String davPath) throws IOException {
        if (davPath == null) throw new SecurityException("no path");
        String rel = davPath;
        if (rel.startsWith("/dav")) rel = rel.substring(4);
        if (rel.startsWith("/")) rel = rel.substring(1);
        if (rel.isEmpty()) return storageRoot.getCanonicalFile();

        // Block path traversal attempts
        rel = rel.replace('\\', '/');
        for (String part : rel.split("/")) {
            if ("..".equals(part)) throw new SecurityException("path traversal blocked");
        }

        File canonicalRoot = storageRoot.getCanonicalFile();
        File target = new File(canonicalRoot, rel).getCanonicalFile();
        String rootPath = canonicalRoot.getPath();
        String targetPath = target.getPath();

        if (!targetPath.equals(rootPath) && !targetPath.startsWith(rootPath + File.separator)) {
            throw new SecurityException("outside storage");
        }
        return target;
    }

    // ---------- WebDAV methods ----------

    private void handleOptions(HttpServer.Request req, HttpServer.Response res) throws IOException {
        res.header("DAV", "1, 2");
        res.header("MS-Author-Via", "DAV");
        res.header("Allow", "OPTIONS, GET, HEAD, PROPFIND, PUT, DELETE, MKCOL, MOVE, COPY, LOCK, UNLOCK, PROPPATCH");
        res.begin(200, null, 0);
    }

    private void handlePropfind(HttpServer.Request req, HttpServer.Response res) throws IOException {
        File target;
        try {
            target = resolveFile(req.path);
        } catch (SecurityException e) {
            res.begin(403, null, 0);
            return;
        }

        if (!target.exists()) {
            res.begin(404, null, 0);
            return;
        }

        String depthHeader = req.header("depth");
        int depth = 1; // Default is Depth 1 (treating infinity as Depth 1)
        if ("0".equals(depthHeader)) depth = 0;

        StringBuilder xml = new StringBuilder();
        xml.append("<?xml version=\"1.0\" encoding=\"utf-8\" ?>\n");
        xml.append("<D:multistatus xmlns:D=\"DAV:\">\n");

        // Target resource itself
        appendResourceProp(xml, target, req.path);

        // Children if collection and Depth > 0
        if (depth > 0 && target.isDirectory()) {
            File[] children = target.listFiles();
            if (children == null && RootIo.isAvailable()) {
                List<RootIo.Entry> rootList = RootIo.listEntries(target);
                if (rootList != null) {
                    String basePath = req.path.endsWith("/") ? req.path : req.path + "/";
                    for (RootIo.Entry re : rootList) {
                        File child = new File(target, re.name);
                        appendResourcePropWithRoot(xml, child, basePath + re.name, re);
                    }
                }
            } else if (children != null) {
                Arrays.sort(children, new Comparator<File>() {
                    public int compare(File a, File b) {
                        if (a.isDirectory() != b.isDirectory()) return a.isDirectory() ? -1 : 1;
                        return a.getName().compareToIgnoreCase(b.getName());
                    }
                });
                String basePath = req.path.endsWith("/") ? req.path : req.path + "/";
                for (File child : children) {
                    appendResourceProp(xml, child, basePath + child.getName());
                }
            }
        }

        xml.append("</D:multistatus>\n");
        res.text(207, "application/xml; charset=utf-8", xml.toString());
    }

    private void appendResourceProp(StringBuilder xml, File f, String davPath) {
        String href = encodeUriPath(davPath);
        if (f.isDirectory() && !href.endsWith("/")) href += "/";

        boolean isDir = f.isDirectory();
        String name = (davPath.equals("/dav") || davPath.equals("/dav/")) ? "NexShare" : f.getName();

        xml.append("  <D:response>\n");
        xml.append("    <D:href>").append(href).append("</D:href>\n");
        xml.append("    <D:propstat>\n");
        xml.append("      <D:prop>\n");
        xml.append("        <D:displayname>").append(xmlEscape(name)).append("</D:displayname>\n");

        if (isDir) {
            xml.append("        <D:resourcetype><D:collection/></D:resourcetype>\n");
            xml.append("        <D:getcontenttype>httpd/unix-directory</D:getcontenttype>\n");
        } else {
            xml.append("        <D:resourcetype/>\n");
            xml.append("        <D:getcontentlength>").append(f.length()).append("</D:getcontentlength>\n");
            xml.append("        <D:getcontenttype>").append(WebApi.mime(f.getName())).append("</D:getcontenttype>\n");
        }

        xml.append("        <D:getlastmodified>").append(HttpServer.httpDate(f.lastModified())).append("</D:getlastmodified>\n");
        xml.append("        <D:creationdate>").append(isoDate(f.lastModified())).append("</D:creationdate>\n");
        xml.append("        <D:getetag>\"").append(Long.toHexString(f.lastModified())).append('-').append(Long.toHexString(f.length())).append("\"</D:getetag>\n");

        // Disk Quota (RFC 4331) for Windows Explorer capacity bar
        try {
            StatFs st = new StatFs(storageRoot.getPath());
            long available = st.getAvailableBytes();
            long total = st.getTotalBytes();
            long used = Math.max(0, total - available);
            xml.append("        <D:quota-available-bytes>").append(available).append("</D:quota-available-bytes>\n");
            xml.append("        <D:quota-used-bytes>").append(used).append("</D:quota-used-bytes>\n");
        } catch (Exception ignored) { }

        xml.append("      </D:prop>\n");
        xml.append("      <D:status>HTTP/1.1 200 OK</D:status>\n");
        xml.append("    </D:propstat>\n");
        xml.append("  </D:response>\n");
    }

    private void appendResourcePropWithRoot(StringBuilder xml, File f, String davPath, RootIo.Entry re) {
        String href = encodeUriPath(davPath);
        if (re.isDir && !href.endsWith("/")) href += "/";

        xml.append("  <D:response>\n");
        xml.append("    <D:href>").append(href).append("</D:href>\n");
        xml.append("    <D:propstat>\n");
        xml.append("      <D:prop>\n");
        xml.append("        <D:displayname>").append(xmlEscape(re.name)).append("</D:displayname>\n");

        if (re.isDir) {
            xml.append("        <D:resourcetype><D:collection/></D:resourcetype>\n");
            xml.append("        <D:getcontenttype>httpd/unix-directory</D:getcontenttype>\n");
        } else {
            xml.append("        <D:resourcetype/>\n");
            xml.append("        <D:getcontentlength>").append(re.size).append("</D:getcontentlength>\n");
            xml.append("        <D:getcontenttype>").append(WebApi.mime(re.name)).append("</D:getcontenttype>\n");
        }

        long mtime = re.mtime > 0 ? re.mtime : System.currentTimeMillis();
        xml.append("        <D:getlastmodified>").append(HttpServer.httpDate(mtime)).append("</D:getlastmodified>\n");
        xml.append("        <D:creationdate>").append(isoDate(mtime)).append("</D:creationdate>\n");
        xml.append("        <D:getetag>\"").append(Long.toHexString(mtime)).append('-').append(Long.toHexString(re.size)).append("\"</D:getetag>\n");

        try {
            StatFs st = new StatFs(storageRoot.getPath());
            long available = st.getAvailableBytes();
            long total = st.getTotalBytes();
            long used = Math.max(0, total - available);
            xml.append("        <D:quota-available-bytes>").append(available).append("</D:quota-available-bytes>\n");
            xml.append("        <D:quota-used-bytes>").append(used).append("</D:quota-used-bytes>\n");
        } catch (Exception ignored) { }

        xml.append("      </D:prop>\n");
        xml.append("      <D:status>HTTP/1.1 200 OK</D:status>\n");
        xml.append("    </D:propstat>\n");
        xml.append("  </D:response>\n");
    }

    private void handleGet(HttpServer.Request req, HttpServer.Response res, boolean isHead) throws IOException {
        File f;
        try {
            f = resolveFile(req.path);
        } catch (SecurityException e) {
            res.begin(403, null, 0);
            return;
        }

        if (!f.exists()) {
            res.begin(404, null, 0);
            return;
        }

        if (f.isDirectory()) {
            res.header("Allow", "OPTIONS, GET, HEAD, PROPFIND, PUT, DELETE, MKCOL, MOVE, COPY, LOCK, UNLOCK, PROPPATCH");
            if (isHead) {
                res.begin(200, "text/html; charset=utf-8", 0);
                return;
            }
            renderHtmlDirectory(f, req.path, res);
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
                res.begin(416, null, 0);
                return;
            }
        }

        res.header("Accept-Ranges", "bytes");
        res.header("Last-Modified", HttpServer.httpDate(f.lastModified()));
        res.header("ETag", "\"" + Long.toHexString(f.lastModified()) + "-" + Long.toHexString(len) + "\"");
        if (partial) res.header("Content-Range", "bytes " + start + "-" + end + "/" + len);

        long count = len == 0 ? 0 : end - start + 1;
        OutputStream out = res.begin(partial ? 206 : 200, WebApi.mime(f.getName()), Math.max(count, 0));
        if (isHead || len == 0) return;

        // Stream from disk without allocating large memory
        try (RandomAccessFile raf = new RandomAccessFile(f, "r")) {
            raf.seek(start);
            byte[] buf = new byte[65536];
            long left = count;
            while (left > 0) {
                int n = raf.read(buf, 0, (int) Math.min(buf.length, left));
                if (n < 0) break;
                out.write(buf, 0, n);
                left -= n;
            }
        } catch (IOException e) {
            if (RootIo.isAvailable()) {
                try (InputStream in = RootIo.openInputStream(f, start)) {
                    byte[] buf = new byte[65536];
                    long left = count;
                    while (left > 0) {
                        int n = in.read(buf, 0, (int) Math.min(buf.length, left));
                        if (n < 0) break;
                        out.write(buf, 0, n);
                        left -= n;
                    }
                }
            } else {
                throw e;
            }
        }
    }

    private void handlePut(HttpServer.Request req, HttpServer.Response res) throws IOException {
        File target;
        try {
            target = resolveFile(req.path);
        } catch (SecurityException e) {
            res.begin(403, null, 0);
            return;
        }

        File parent = target.getParentFile();
        if (parent == null || !parent.exists() || !parent.isDirectory()) {
            res.begin(409, null, 0); // Conflict: parent folder does not exist
            return;
        }

        boolean existed = target.exists();
        File tmp = new File(parent, "." + target.getName() + ".nexdav");
        boolean ok = false;
        try (OutputStream out = new FileOutputStream(tmp)) {
            byte[] buf = new byte[65536];
            int n;
            while ((n = req.body.read(buf)) > 0) {
                out.write(buf, 0, n);
            }
            ok = req.body.remaining() == 0;
        } finally {
            if (!ok) tmp.delete();
        }

        if (!ok) {
            res.begin(500, null, 0);
            return;
        }

        if (target.exists()) target.delete();
        if (!tmp.renameTo(target)) {
            tmp.delete();
            res.begin(500, null, 0);
            return;
        }

        res.begin(existed ? 204 : 201, null, 0);
    }

    private void handleDelete(HttpServer.Request req, HttpServer.Response res) throws IOException {
        File target;
        try {
            target = resolveFile(req.path);
        } catch (SecurityException e) {
            res.begin(403, null, 0);
            return;
        }

        if (!target.exists()) {
            res.begin(404, null, 0);
            return;
        }

        if (target.equals(storageRoot.getCanonicalFile())) {
            res.begin(403, null, 0); // Cannot delete the root storage directory
            return;
        }

        if (deleteRec(target)) {
            res.begin(204, null, 0);
        } else {
            res.begin(500, null, 0);
        }
    }

    private void handleMkcol(HttpServer.Request req, HttpServer.Response res) throws IOException {
        File target;
        try {
            target = resolveFile(req.path);
        } catch (SecurityException e) {
            res.begin(403, null, 0);
            return;
        }

        if (target.exists()) {
            res.begin(405, null, 0); // Method Not Allowed (already exists)
            return;
        }

        File parent = target.getParentFile();
        if (parent == null || !parent.exists() || !parent.isDirectory()) {
            res.begin(409, null, 0); // Conflict
            return;
        }

        if (target.mkdir() || (RootIo.isAvailable() && RootIo.mkdir(target))) {
            res.begin(201, null, 0);
        } else {
            res.begin(500, null, 0);
        }
    }

    private void handleMove(HttpServer.Request req, HttpServer.Response res) throws IOException {
        copyOrMove(req, res, true);
    }

    private void handleCopy(HttpServer.Request req, HttpServer.Response res) throws IOException {
        copyOrMove(req, res, false);
    }

    private void copyOrMove(HttpServer.Request req, HttpServer.Response res, boolean move) throws IOException {
        File src;
        try {
            src = resolveFile(req.path);
        } catch (SecurityException e) {
            res.begin(403, null, 0);
            return;
        }

        if (!src.exists()) {
            res.begin(404, null, 0);
            return;
        }

        if (src.equals(storageRoot.getCanonicalFile())) {
            res.begin(403, null, 0);
            return;
        }

        String destHeader = req.header("destination");
        if (destHeader == null || destHeader.isEmpty()) {
            res.begin(400, null, 0);
            return;
        }

        String destPath = parseDestinationPath(destHeader);
        if (destPath == null) {
            res.begin(400, null, 0);
            return;
        }

        File dst;
        try {
            dst = resolveFile(destPath);
        } catch (SecurityException e) {
            res.begin(403, null, 0);
            return;
        }

        if (dst.equals(src)) {
            res.begin(403, null, 0);
            return;
        }

        // Prevent moving a directory into itself
        if (dst.getPath().startsWith(src.getPath() + File.separator)) {
            res.begin(409, null, 0);
            return;
        }

        boolean dstExisted = dst.exists();
        String overwrite = req.header("overwrite");
        if (dstExisted) {
            if ("F".equalsIgnoreCase(overwrite)) {
                res.begin(412, null, 0); // Precondition Failed
                return;
            }
            deleteRec(dst);
        }

        File parent = dst.getParentFile();
        if (parent == null || !parent.exists() || !parent.isDirectory()) {
            res.begin(409, null, 0); // Conflict
            return;
        }

        boolean ok;
        if (move) {
            ok = src.renameTo(dst);
            if (!ok) {
                ok = copyRec(src, dst);
                if (ok) deleteRec(src);
            }
        } else {
            ok = copyRec(src, dst);
        }

        if (!ok) {
            res.begin(500, null, 0);
            return;
        }
        res.begin(dstExisted ? 204 : 201, null, 0);
    }

    private void handleLock(HttpServer.Request req, HttpServer.Response res) throws IOException {
        File target;
        try {
            target = resolveFile(req.path);
        } catch (SecurityException e) {
            res.begin(403, null, 0);
            return;
        }

        String uri = encodeUriPath(req.path);
        String token = "opaquelocktoken:" + UUID.nameUUIDFromBytes(req.path.getBytes(StandardCharsets.UTF_8)).toString();
        res.header("Lock-Token", "<" + token + ">");

        StringBuilder sb = new StringBuilder();
        sb.append("<?xml version=\"1.0\" encoding=\"utf-8\" ?>\n");
        sb.append("<D:prop xmlns:D=\"DAV:\">\n");
        sb.append("  <D:lockdiscovery>\n");
        sb.append("    <D:activelock>\n");
        sb.append("      <D:locktype><D:write/></D:locktype>\n");
        sb.append("      <D:lockscope><D:exclusive/></D:lockscope>\n");
        sb.append("      <D:depth>0</D:depth>\n");
        sb.append("      <D:timeout>Second-3600</D:timeout>\n");
        sb.append("      <D:locktoken><D:href>").append(token).append("</D:href></D:locktoken>\n");
        sb.append("      <D:lockroot><D:href>").append(uri).append("</D:href></D:lockroot>\n");
        sb.append("    </D:activelock>\n");
        sb.append("  </D:lockdiscovery>\n");
        sb.append("</D:prop>\n");

        res.text(target.exists() ? 200 : 201, "application/xml; charset=utf-8", sb.toString());
    }

    private void handleUnlock(HttpServer.Request req, HttpServer.Response res) throws IOException {
        res.begin(204, null, 0);
    }

    private void handleProppatch(HttpServer.Request req, HttpServer.Response res) throws IOException {
        String uri = encodeUriPath(req.path);
        StringBuilder sb = new StringBuilder();
        sb.append("<?xml version=\"1.0\" encoding=\"utf-8\" ?>\n");
        sb.append("<D:multistatus xmlns:D=\"DAV:\">\n");
        sb.append("  <D:response>\n");
        sb.append("    <D:href>").append(uri).append("</D:href>\n");
        sb.append("    <D:propstat>\n");
        sb.append("      <D:prop/>\n");
        sb.append("      <D:status>HTTP/1.1 200 OK</D:status>\n");
        sb.append("    </D:propstat>\n");
        sb.append("  </D:response>\n");
        sb.append("</D:multistatus>\n");
        res.text(207, "application/xml; charset=utf-8", sb.toString());
    }

    // ---------- helpers ----------

    private static String parseDestinationPath(String destHeader) {
        try {
            String path = destHeader;
            if (path.startsWith("http://") || path.startsWith("https://")) {
                int slash = path.indexOf('/', path.indexOf("://") + 3);
                if (slash < 0) return null;
                path = path.substring(slash);
            }
            int q = path.indexOf('?');
            if (q >= 0) path = path.substring(0, q);
            return HttpServer.decode(path);
        } catch (Exception e) {
            return null;
        }
    }

    private boolean deleteRec(File f) {
        if (f.isDirectory()) {
            File[] kids = f.listFiles();
            if (kids != null) {
                for (File k : kids) deleteRec(k);
            }
        }
        boolean ok = f.delete();
        if (!ok && RootIo.isAvailable()) ok = RootIo.delete(f);
        return ok;
    }

    private boolean copyRec(File src, File dst) {
        if (src.isDirectory()) {
            if (!dst.mkdirs()) return false;
            File[] kids = src.listFiles();
            boolean ok = true;
            if (kids != null) {
                for (File k : kids) ok &= copyRec(k, new File(dst, k.getName()));
            }
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

    public static String encodeUriPath(String path) {
        if (path == null || path.isEmpty()) return "/dav/";
        StringBuilder sb = new StringBuilder();
        String[] parts = path.split("/", -1);
        for (int i = 0; i < parts.length; i++) {
            if (i > 0) sb.append('/');
            String part = parts[i];
            if (!part.isEmpty()) {
                try {
                    sb.append(URLEncoder.encode(part, "UTF-8").replace("+", "%20"));
                } catch (Exception e) {
                    sb.append(part);
                }
            }
        }
        return sb.toString();
    }

    private static String isoDate(long ms) {
        SimpleDateFormat f = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US);
        f.setTimeZone(TimeZone.getTimeZone("GMT"));
        return f.format(new Date(ms));
    }

    private static String xmlEscape(String s) {
        if (s == null) return "";
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '&': sb.append("&amp;"); break;
                case '<': sb.append("&lt;"); break;
                case '>': sb.append("&gt;"); break;
                case '"': sb.append("&quot;"); break;
                case '\'': sb.append("&apos;"); break;
                default: sb.append(c); break;
            }
        }
        return sb.toString();
    }

    private void renderHtmlDirectory(File dir, String reqPath, HttpServer.Response res) throws IOException {
        String normPath = reqPath;
        if (!normPath.endsWith("/")) normPath += "/";

        StringBuilder html = new StringBuilder();
        html.append("<!DOCTYPE html><html><head><meta charset=\"utf-8\"><title>NexShare WebDAV - ")
            .append(xmlEscape(normPath))
            .append("</title><meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">")
            .append("<style>")
            .append("body{font-family:-apple-system,BlinkMacSystemFont,'Segoe UI',Roboto,sans-serif;background:#0f172a;color:#f8fafc;margin:0;padding:24px;line-height:1.5;}")
            .append(".box{max-width:960px;margin:0 auto;background:#1e293b;border:1px solid #334155;border-radius:12px;padding:24px;box-shadow:0 10px 25px rgba(0,0,0,0.4);}")
            .append("h1{margin-top:0;font-size:22px;display:flex;align-items:center;gap:10px;color:#38bdf8;}")
            .append(".path{font-family:monospace;background:#0f172a;padding:8px 12px;border-radius:6px;border:1px solid #334155;word-break:break-all;margin-bottom:16px;color:#94a3b8;}")
            .append(".banner{background:#0369a1;color:#f0f9ff;padding:12px 16px;border-radius:8px;margin-bottom:20px;font-size:14px;}")
            .append(".banner a{color:#bae6fd;font-weight:600;}")
            .append("table{width:100%;border-collapse:collapse;margin-top:10px;}")
            .append("th,td{text-align:left;padding:10px 12px;border-bottom:1px solid #334155;}")
            .append("th{background:#0f172a;color:#94a3b8;font-size:13px;text-transform:uppercase;letter-spacing:0.5px;}")
            .append("tr:hover td{background:#273549;}")
            .append("a{color:#38bdf8;text-decoration:none;word-break:break-all;}")
            .append("a:hover{text-decoration:underline;}")
            .append(".size{text-align:right;white-space:nowrap;font-family:monospace;color:#94a3b8;}")
            .append(".date{white-space:nowrap;color:#64748b;font-size:13px;}")
            .append(".icon{width:20px;display:inline-block;text-align:center;margin-right:6px;}")
            .append(".btn{display:inline-block;background:#2563eb;color:#fff;padding:8px 16px;border-radius:6px;font-size:13px;font-weight:500;text-decoration:none;margin-right:8px;}")
            .append(".btn:hover{background:#1d4ed8;text-decoration:none;}")
            .append("</style></head><body>");

        html.append("<div class=\"box\">");
        html.append("<h1>📁 NexShare WebDAV</h1>");
        html.append("<div class=\"path\">Current directory: ").append(xmlEscape(normPath)).append("</div>");

        html.append("<div class=\"banner\">")
            .append("💡 <strong>WebDAV Endpoint Active.</strong> To manage files with full desktop UI, open <a href=\"/\">NexShare Web Desktop</a>. ")
            .append("To map as a Windows Drive, use <code>net use</code> or RaiDrive with username <code>nexshare</code> and your 6-digit PIN.")
            .append("</div>");

        html.append("<table><thead><tr><th>Name</th><th class=\"size\">Size</th><th class=\"date\">Modified</th></tr></thead><tbody>");

        // Parent directory link if not root
        if (!normPath.equals("/dav/")) {
            String parentPath = normPath.substring(0, normPath.length() - 1);
            int lastSlash = parentPath.lastIndexOf('/');
            if (lastSlash >= 4) {
                String parentUrl = parentPath.substring(0, lastSlash + 1);
                html.append("<tr><td><span class=\"icon\">⬆️</span><a href=\"").append(xmlEscape(encodeUriPath(parentUrl))).append("\">.. (Parent Directory)</a></td><td class=\"size\">-</td><td class=\"date\">-</td></tr>");
            }
        }

        File[] files = dir.listFiles();
        List<RootIo.Entry> rootList = (files == null && RootIo.isAvailable()) ? RootIo.listEntries(dir) : null;
        if (files != null) {
            Arrays.sort(files, new Comparator<File>() {
                @Override
                public int compare(File a, File b) {
                    if (a.isDirectory() != b.isDirectory()) {
                        return a.isDirectory() ? -1 : 1;
                    }
                    return a.getName().compareToIgnoreCase(b.getName());
                }
            });

            SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US);
            for (File file : files) {
                boolean isDir = file.isDirectory();
                String name = file.getName();
                String itemHref = encodeUriPath(normPath + name + (isDir ? "/" : ""));
                String icon = isDir ? "📁" : "📄";
                String sizeStr = isDir ? "-" : formatSize(file.length());
                String dateStr = sdf.format(new Date(file.lastModified()));

                html.append("<tr>")
                    .append("<td><span class=\"icon\">").append(icon).append("</span>")
                    .append("<a href=\"").append(xmlEscape(itemHref)).append("\">").append(xmlEscape(name)).append(isDir ? "/" : "").append("</a></td>")
                    .append("<td class=\"size\">").append(sizeStr).append("</td>")
                    .append("<td class=\"date\">").append(dateStr).append("</td>")
                    .append("</tr>");
            }
        } else if (rootList != null) {
            SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US);
            for (RootIo.Entry re : rootList) {
                boolean isDir = re.isDir;
                String name = re.name;
                String itemHref = encodeUriPath(normPath + name + (isDir ? "/" : ""));
                String icon = isDir ? "📁" : "📄";
                String sizeStr = isDir ? "-" : formatSize(re.size);
                String dateStr = re.mtime > 0 ? sdf.format(new Date(re.mtime)) : "-";

                html.append("<tr>")
                    .append("<td><span class=\"icon\">").append(icon).append("</span>")
                    .append("<a href=\"").append(xmlEscape(itemHref)).append("\">").append(xmlEscape(name)).append(isDir ? "/" : "").append("</a></td>")
                    .append("<td class=\"size\">").append(sizeStr).append("</td>")
                    .append("<td class=\"date\">").append(dateStr).append("</td>")
                    .append("</tr>");
            }
        }

        html.append("</tbody></table>");
        html.append("<div style=\"margin-top:24px;text-align:right;\">")
            .append("<a class=\"btn\" href=\"/\">Open Web Desktop</a>")
            .append("</div>");
        html.append("</div></body></html>");

        byte[] bytes = html.toString().getBytes(StandardCharsets.UTF_8);
        res.header("Content-Type", "text/html; charset=utf-8");
        OutputStream out = res.begin(200, "text/html; charset=utf-8", bytes.length);
        out.write(bytes);
        out.flush();
    }

    private static String formatSize(long bytes) {
        if (bytes < 1024) return bytes + " B";
        int exp = (int) (Math.log(bytes) / Math.log(1024));
        String pre = "KMGTPE".charAt(exp - 1) + "";
        return String.format(Locale.US, "%.1f %sB", bytes / Math.pow(1024, exp), pre);
    }
}
