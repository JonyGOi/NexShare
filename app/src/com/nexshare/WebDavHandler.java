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
            if (children != null) {
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
            res.header("Allow", "OPTIONS, PROPFIND");
            res.text(200, "text/plain; charset=utf-8", "Directory: " + f.getName() + " (use WebDAV PROPFIND)\n");
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

        if (target.mkdir()) {
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
        return f.delete();
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
}
