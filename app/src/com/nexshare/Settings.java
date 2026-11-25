package com.nexshare;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Locale;

/** SharedPreferences wrapper for the access settings (login mode, PIN, username/password). */
public final class Settings {

    public static final String MODE_NONE = "none";
    public static final String MODE_PIN = "pin";
    public static final String MODE_PASSWORD = "password";

    private Settings() { }

    private static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences("nexshare", Context.MODE_PRIVATE);
    }

    // ---------- login mode ----------

    /** "none" | "pin" | "password". Defaults to "pin"; migrates the old pinEnabled flag. */
    public static String authMode(Context c) {
        SharedPreferences p = prefs(c);
        String m = p.getString("authMode", null);
        if (m != null) return m;
        return p.getBoolean("pinEnabled", true) ? MODE_PIN : MODE_NONE;
    }

    public static void setAuthMode(Context c, String mode) {
        if (!MODE_NONE.equals(mode) && !MODE_PIN.equals(mode) && !MODE_PASSWORD.equals(mode)) mode = MODE_PIN;
        prefs(c).edit().putString("authMode", mode).putBoolean("pinEnabled", !MODE_NONE.equals(mode)).apply();
    }

    public static boolean authRequired(Context c) {
        return !MODE_NONE.equals(authMode(c));
    }

    /**
     * WebDAV and the Cloudflare tunnel expose storage beyond the local Wi-Fi, so they must never
     * run with login disabled: switching them on forces PIN mode.
     */
    public static void requireLogin(Context c) {
        if (MODE_NONE.equals(authMode(c))) setAuthMode(c, MODE_PIN);
    }

    // Kept for existing callers; true when any login is required.
    public static boolean pinEnabled(Context c) {
        return authRequired(c);
    }

    public static void setPinEnabled(Context c, boolean on) {
        setAuthMode(c, on ? MODE_PIN : MODE_NONE);
    }

    // ---------- PIN ----------

    public static String pin(Context c) {
        SharedPreferences p = prefs(c);
        String pin = p.getString("pin", null);
        if (pin == null) {
            pin = newPin();
            p.edit().putString("pin", pin).apply();
        }
        return pin;
    }

    public static String regeneratePin(Context c) {
        String pin = newPin();
        prefs(c).edit().putString("pin", pin).apply();
        return pin;
    }

    private static String newPin() {
        return String.format(Locale.ROOT, "%06d", new SecureRandom().nextInt(1000000));
    }

    // ---------- username / password ----------

    public static String username(Context c) {
        return prefs(c).getString("username", "admin");
    }

    public static void setUsername(Context c, String name) {
        if (name == null || name.trim().isEmpty()) name = "admin";
        prefs(c).edit().putString("username", name.trim()).apply();
    }

    public static boolean hasPassword(Context c) {
        return prefs(c).getString("passHash", null) != null;
    }

    public static void setPassword(Context c, String plain) {
        String salt = randomHex(16);
        prefs(c).edit()
            .putString("passHash", salt + ":" + hash(salt, plain))
            .putString("plainPass", plain)
            .apply();
    }

    public static String plainPassword(Context c) {
        return prefs(c).getString("plainPass", null);
    }

    public static boolean checkPassword(Context c, String plain) {
        String stored = prefs(c).getString("passHash", null);
        if (stored == null || plain == null) return false;
        int i = stored.indexOf(':');
        if (i < 0) return false;
        String salt = stored.substring(0, i);
        String expect = stored.substring(i + 1);
        boolean ok = constantEquals(expect, hash(salt, plain));
        if (ok) {
            prefs(c).edit().putString("plainPass", plain).apply();
        }
        return ok;
    }

    private static String hash(String salt, String plain) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] cur = (salt + ":" + plain).getBytes(StandardCharsets.UTF_8);
            for (int i = 0; i < 12000; i++) cur = md.digest(cur);
            return hex(cur);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static boolean constantEquals(String a, String b) {
        if (a == null || b == null || a.length() != b.length()) return false;
        int r = 0;
        for (int i = 0; i < a.length(); i++) r |= a.charAt(i) ^ b.charAt(i);
        return r == 0;
    }

    private static String randomHex(int bytes) {
        byte[] b = new byte[bytes];
        new SecureRandom().nextBytes(b);
        return hex(b);
    }

    private static String hex(byte[] b) {
        StringBuilder sb = new StringBuilder(b.length * 2);
        for (byte x : b) sb.append(String.format(Locale.ROOT, "%02x", x));
        return sb.toString();
    }

    // ---------- API key (Debrid REST + stream links) ----------

    /** Persistent API key used by the Real-Debrid compatible API, Stremio/VLC links and remote clients. */
    public static String apiKey(Context c) {
        SharedPreferences p = prefs(c);
        String k = p.getString("apiKey", null);
        if (k == null || k.length() < 24) {
            k = regenerateApiKey(c);
        }
        return k;
    }

    public static String regenerateApiKey(Context c) {
        String k = randomHex(20);
        prefs(c).edit().putString("apiKey", k).apply();
        return k;
    }

    // ---------- misc ----------

    public static boolean webdavEnabled(Context c) {
        return prefs(c).getBoolean("webdavEnabled", false);
    }

    public static void setWebdavEnabled(Context c, boolean on) {
        prefs(c).edit().putBoolean("webdavEnabled", on).apply();
    }

    public static String tunnelMode(Context c) {
        return prefs(c).getString("tunnelMode", "off");
    }

    public static String tunnelToken(Context c) {
        return prefs(c).getString("tunnelToken", "");
    }

    /** Public hostname of a named tunnel (e.g. nas.example.com), used to show the real URL. */
    public static String tunnelHost(Context c) {
        return prefs(c).getString("tunnelHost", "");
    }

    public static void setTunnelHost(Context c, String host) {
        if (host == null) return;
        host = host.trim().toLowerCase(Locale.ROOT);
        if (host.startsWith("https://")) host = host.substring(8);
        else if (host.startsWith("http://")) host = host.substring(7);
        int slash = host.indexOf('/');
        if (slash > 0) host = host.substring(0, slash);
        if (host.endsWith(".")) host = host.substring(0, host.length() - 1);
        if (host.contains(" ") || host.isEmpty()) return;
        prefs(c).edit().putString("tunnelHost", host).apply();
    }

    public static void setTunnel(Context c, String mode, String token) {
        prefs(c).edit().putString("tunnelMode", mode == null ? "off" : mode)
            .putString("tunnelToken", token == null ? "" : token).apply();
    }

    public static boolean keepScreenOn(Context c) {
        return prefs(c).getBoolean("keepScreenOn", true);
    }

    public static void setKeepScreenOn(Context c, boolean on) {
        prefs(c).edit().putBoolean("keepScreenOn", on).apply();
    }

    public static boolean bootStartEnabled(Context c) {
        return prefs(c).getBoolean("bootStart", true);
    }

    public static void setBootStartEnabled(Context c, boolean on) {
        prefs(c).edit().putBoolean("bootStart", on).apply();
    }

    public static boolean autoUpdateEnabled(Context c) {
        return prefs(c).getBoolean("autoUpdate", true);
    }

    public static void setAutoUpdateEnabled(Context c, boolean on) {
        prefs(c).edit().putBoolean("autoUpdate", on).apply();
    }

    public static String serverName(Context c) {
        return prefs(c).getString("name", "NexShare-" + Build.MODEL.replace(' ', '-'));
    }

    // ---------- Nuvio / Debrid Addon settings ----------

    /** Custom scraper URL for the Nuvio addon (default: torrentio.strem.fun). */
    public static String addonScraperUrl(Context c) {
        String url = prefs(c).getString("addonScraperUrl", null);
        if (url == null || url.trim().isEmpty()) return "https://torrentio.strem.fun";
        return normalizeScraperUrl(url);
    }

    public static void setAddonScraperUrl(Context c, String url) {
        if (url == null) url = "";
        url = normalizeScraperUrl(url);
        if (url.isEmpty()) {
            prefs(c).edit().remove("addonScraperUrl").apply();
        } else {
            if (!url.startsWith("http://") && !url.startsWith("https://")) url = "https://" + url;
            prefs(c).edit().putString("addonScraperUrl", url).apply();
        }
    }

    /**
     * Accepts the scraper base URL with or without a trailing slash, /manifest.json or /stream;
     * otherwise the addon would append /stream twice and the scraper answers 404.
     */
    private static String normalizeScraperUrl(String url) {
        if (url == null) return "";
        url = url.trim();
        while (url.endsWith("/")) url = url.substring(0, url.length() - 1);
        if (url.endsWith("/manifest.json")) url = url.substring(0, url.length() - "/manifest.json".length());
        if (url.endsWith("/stream")) url = url.substring(0, url.length() - "/stream".length());
        while (url.endsWith("/")) url = url.substring(0, url.length() - 1);
        return url;
    }

    /** Cache duration for scraper results in minutes (default: 10). 0 = no cache. */
    public static int addonCacheMinutes(Context c) {
        return prefs(c).getInt("addonCacheMinutes", 10);
    }

    public static void setAddonCacheMinutes(Context c, int minutes) {
        if (minutes < 0) minutes = 0;
        if (minutes > 1440) minutes = 1440;
        prefs(c).edit().putInt("addonCacheMinutes", minutes).apply();
    }

    /** Maximum streams to return per request (default: 30). */
    public static int addonMaxStreams(Context c) {
        return prefs(c).getInt("addonMaxStreams", 30);
    }

    public static void setAddonMaxStreams(Context c, int max) {
        if (max < 1) max = 1;
        if (max > 100) max = 100;
        prefs(c).edit().putInt("addonMaxStreams", max).apply();
    }

    /** Timeout for metadata fetching in addonPlay (ms, default: 7000 — below the ~8 s player read timeout). */
    public static int addonMetadataTimeoutMs(Context c) {
        return prefs(c).getInt("addonMetadataTimeoutMs", 7000);
    }

    public static void setAddonMetadataTimeoutMs(Context c, int ms) {
        if (ms < 5000) ms = 5000;
        if (ms > 120000) ms = 120000;
        prefs(c).edit().putInt("addonMetadataTimeoutMs", ms).apply();
    }
}
