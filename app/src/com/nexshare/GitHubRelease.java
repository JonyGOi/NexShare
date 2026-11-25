package com.nexshare;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Resolves the latest GitHub release without the REST API.
 *
 * The unauthenticated api.github.com endpoint allows only 60 requests per hour
 * per IP. When that quota is exhausted it answers HTTP 403, which used to break
 * both the web updater and the background updater. GitHub's normal release
 * pages are not subject to that API quota, so this class reads:
 *   1. /releases/latest (HTTP redirect gives the tag), and
 *   2. /releases/expanded_assets/<tag> (contains the real APK links).
 */
final class GitHubRelease {

    private static final String REPO = "JonyGOi/NexShare";
    private static final String WEB = "https://github.com/" + REPO;
    private static final Pattern HREF = Pattern.compile("href=\"([^\"]*/releases/download/[^\"]+\\.apk)\"");

    private GitHubRelease() { }

    static final class Release {
        String version = "";
        String tag = "";
        String title = "";
        String notes = "";
        String pageUrl = "";
        String assetName = "";
        String downloadUrl = "";
        long size = 0;
    }

    static Release latest() throws IOException {
        String tag = resolveTag();
        if (tag.isEmpty()) throw new IOException("Tag da versão mais recente não encontrada");

        Release rel = new Release();
        rel.tag = tag;
        rel.version = tag.startsWith("v") || tag.startsWith("V") ? tag.substring(1) : tag;
        rel.title = "NexShare " + rel.version;
        rel.pageUrl = WEB + "/releases/tag/" + tag;

        List<String> assets = new ArrayList<>();
        try {
            String html = get(WEB + "/releases/expanded_assets/" + tag, "text/html");
            Matcher m = HREF.matcher(html);
            while (m.find()) {
                String href = m.group(1).replace("&amp;", "&");
                if (href.startsWith("/")) href = "https://github.com" + href;
                if (!assets.contains(href)) assets.add(href);
            }
        } catch (IOException ignored) { }

        if (assets.isEmpty()) {
            // Fallback for a markup change: the release asset names are predictable.
            String expected = Flavor.ROOT
                ? "NexShare-Root-v" + rel.version + ".apk"
                : "NexShare-v" + rel.version + ".apk";
            String direct = WEB + "/releases/download/" + tag + "/" + expected;
            if (head(direct)) assets.add(direct);
        }

        String chosen = null;
        String fallback = null;
        for (String url : assets) {
            String name = fileName(url);
            if (!name.toLowerCase(Locale.ROOT).endsWith(".apk")) continue;
            if (fallback == null) fallback = url;
            boolean rootAsset = name.toLowerCase(Locale.ROOT).contains("root");
            if (Flavor.ROOT == rootAsset) { chosen = url; break; }
        }
        if (chosen == null) chosen = fallback;
        if (chosen == null) throw new IOException("Nenhum APK encontrado na release " + tag);

        rel.downloadUrl = chosen;
        rel.assetName = fileName(chosen);
        return rel;
    }

    private static String resolveTag() throws IOException {
        HttpURLConnection c = open(WEB + "/releases/latest", "text/html");
        try {
            int code = c.getResponseCode();
            if (code >= 400) throw new IOException("GitHub web retornou HTTP " + code);
            String tag = tagFrom(c.getURL().getPath());
            if (!tag.isEmpty()) return tag;
            String html = read(c);
            Matcher m = Pattern.compile("/releases/tag/([A-Za-z0-9._-]+)").matcher(html);
            if (m.find()) return m.group(1);
            return "";
        } finally {
            c.disconnect();
        }
    }

    private static String tagFrom(String path) {
        if (path == null) return "";
        int i = path.indexOf("/releases/tag/");
        if (i < 0) return "";
        String tag = path.substring(i + "/releases/tag/".length());
        int end = tag.length();
        for (int k = 0; k < tag.length(); k++) {
            char ch = tag.charAt(k);
            if (ch == '/' || ch == '?' || ch == '#') { end = k; break; }
        }
        return tag.substring(0, end);
    }

    private static boolean head(String url) {
        try {
            HttpURLConnection c = open(url, "*/*");
            c.setRequestMethod("HEAD");
            try {
                int code = c.getResponseCode();
                return code >= 200 && code < 400;
            } finally {
                c.disconnect();
            }
        } catch (IOException e) {
            return false;
        }
    }

    private static String get(String url, String accept) throws IOException {
        HttpURLConnection c = open(url, accept);
        try {
            int code = c.getResponseCode();
            if (code >= 400) throw new IOException("GitHub web retornou HTTP " + code);
            return read(c);
        } finally {
            c.disconnect();
        }
    }

    private static HttpURLConnection open(String url, String accept) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setInstanceFollowRedirects(true);
        c.setConnectTimeout(10000);
        c.setReadTimeout(15000);
        c.setRequestProperty("User-Agent", "NexShare/" + Flavor.VERSION);
        c.setRequestProperty("Accept", accept);
        c.setRequestProperty("Accept-Language", "pt-BR,pt;q=0.9,en;q=0.5");
        return c;
    }

    private static String read(HttpURLConnection c) throws IOException {
        try (InputStream in = c.getInputStream()) {
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            int total = 0;
            while ((n = in.read(buf)) > 0) {
                total += n;
                if (total > 1024 * 1024) break;
                bo.write(buf, 0, n);
            }
            return new String(bo.toByteArray(), StandardCharsets.UTF_8);
        }
    }

    private static String fileName(String url) {
        int i = url.lastIndexOf('/');
        String name = i >= 0 ? url.substring(i + 1) : url;
        try {
            return URLDecoder.decode(name, "UTF-8");
        } catch (Exception e) {
            return name;
        }
    }
}
