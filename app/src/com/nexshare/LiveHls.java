package com.nexshare;

import android.content.Context;
import android.util.Log;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Segmented real-time conversion for the Torrent Player.
 *
 * <p>ffmpeg reads the growing, contiguous part of the selected torrent file and writes short
 * HLS/MPEG-TS segments plus an event playlist. The browser starts on the first complete
 * segment, while the rest of the movie is still downloading and being converted.</p>
 */
public final class LiveHls {
    private static final String TAG = "NexShareHls";
    private static final long IDLE_MS = 5 * 60_000L;
    private static final Map<String, Session> SESSIONS = new ConcurrentHashMap<>();
    private static File root;
    private static Thread cleaner;

    private LiveHls() { }

    public static final class Session {
        public final String id;
        public final String key;
        public final File dir;
        public final File playlist;
        public final Process process;
        public final DownloadManager.StreamRef ref;
        public volatile long lastAccess = System.currentTimeMillis();
        public volatile boolean closed;
        public volatile Thread feeder;
        private final StringBuilder tail = new StringBuilder();

        Session(String id, String key, File dir, Process process, DownloadManager.StreamRef ref) {
            this.id = id;
            this.key = key;
            this.dir = dir;
            this.playlist = new File(dir, "index.m3u8");
            this.process = process;
            this.ref = ref;
        }

        public void touch() { lastAccess = System.currentTimeMillis(); }

        public File segment(String name) { return new File(dir, name); }

        String errorTail() {
            synchronized (tail) { return tail.toString(); }
        }

        void appendError(String line) {
            synchronized (tail) {
                tail.append(line).append('\n');
                if (tail.length() > 4000) tail.delete(0, tail.length() - 4000);
            }
        }
    }

    private static synchronized void init(Context ctx) {
        if (root == null) {
            root = new File(ctx.getCacheDir(), "hls");
            root.mkdirs();
        }
        if (cleaner != null) return;
        cleaner = new Thread(() -> {
            while (true) {
                try {
                    Thread.sleep(20_000L);
                    long now = System.currentTimeMillis();
                    for (Session s : new ArrayList<>(SESSIONS.values())) {
                        if (s.closed || now - s.lastAccess > IDLE_MS) stop(s.id);
                    }
                    File[] old = root.listFiles();
                    if (old != null) {
                        for (File d : old) {
                            if (now - d.lastModified() > 24L * 3600_000L) deleteTree(d);
                        }
                    }
                } catch (InterruptedException e) {
                    return;
                } catch (Throwable e) {
                    Log.w(TAG, "cleanup: " + e.getMessage());
                }
            }
        }, "nexshare-hls-cleaner");
        cleaner.setDaemon(true);
        cleaner.start();
    }

    /** Starts (or reuses) a session and waits until the first playable segment exists. */
    public static Session start(Context ctx, File source, DownloadManager.StreamRef ref) throws IOException {
        init(ctx);
        String key = source.getAbsolutePath();
        for (Session s : SESSIONS.values()) {
            if (key.equals(s.key) && !s.closed && s.process.isAlive()) {
                s.touch();
                return s;
            }
        }

        String id = UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        File dir = new File(root, id);
        if (!dir.mkdirs()) throw new IOException("sem espaço para o cache de conversão");
        List<String> cmd = new ArrayList<>();
        cmd.add(binary(ctx).getAbsolutePath());
        cmd.add("-hide_banner");
        cmd.add("-loglevel"); cmd.add("error");
        cmd.add("-y");
        cmd.add("-fflags"); cmd.add("+genpts");
        cmd.add("-probesize"); cmd.add("5M");
        cmd.add("-analyzeduration"); cmd.add("5M");
        cmd.add("-i"); cmd.add("pipe:0");
        cmd.add("-map"); cmd.add("0:V:0");
        cmd.add("-map"); cmd.add("0:a:0?");
        cmd.add("-sn");
        cmd.add("-dn");
        cmd.add("-c:v"); cmd.add("copy");
        cmd.add("-c:a"); cmd.add("aac");
        cmd.add("-b:a"); cmd.add("192k");
        cmd.add("-ac"); cmd.add("2");
        cmd.add("-max_muxing_queue_size"); cmd.add("4096");
        cmd.add("-flush_packets"); cmd.add("1");
        cmd.add("-hls_time"); cmd.add("2");
        cmd.add("-hls_list_size"); cmd.add("0");
        cmd.add("-hls_playlist_type"); cmd.add("event");
        cmd.add("-hls_flags"); cmd.add("temp_file");
        cmd.add("-hls_allow_cache"); cmd.add("0");
        cmd.add("-hls_segment_filename"); cmd.add(new File(dir, "seg%06d.ts").getAbsolutePath());
        cmd.add("-f"); cmd.add("hls");
        cmd.add(new File(dir, "index.m3u8").getAbsolutePath());

        Process p;
        try {
            p = new ProcessBuilder(cmd).redirectErrorStream(false).start();
        } catch (Exception e) {
            deleteTree(dir);
            throw new IOException("ffmpeg não iniciou: " + e.getMessage(), e);
        }

        Session s = new Session(id, key, dir, p, ref);
        SESSIONS.put(id, s);
        Thread err = new Thread(() -> drain(s, p.getErrorStream()), "nexshare-hls-err");
        err.setDaemon(true);
        err.start();
        Thread junk = new Thread(() -> drainQuiet(p.getInputStream()), "nexshare-hls-out");
        junk.setDaemon(true);
        junk.start();
        Thread feed = new Thread(() -> feed(p, source, ref), "nexshare-hls-in");
        feed.setDaemon(true);
        s.feeder = feed;
        feed.start();
        Thread watch = new Thread(() -> {
            try { p.waitFor(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            if (!s.closed) Log.d(TAG, "ffmpeg ended: " + s.errorTail());
        }, "nexshare-hls-wait");
        watch.setDaemon(true);
        watch.start();

        long deadline = System.currentTimeMillis() + 20_000L;
        while (System.currentTimeMillis() < deadline) {
            if (!p.isAlive() && countSegments(dir) == 0) {
                String msg = s.errorTail();
                stop(id);
                throw new IOException("ffmpeg parou antes do primeiro trecho" + (msg.isEmpty() ? "" : ": " + msg));
            }
            if (playlistReady(dir)) return s;
            try {
                Thread.sleep(120L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        String msg = s.errorTail();
        stop(id);
        throw new IOException("o primeiro trecho demorou demais" + (msg.isEmpty() ? "" : ": " + msg));
    }

    private static File binary(Context ctx) throws IOException {
        File f = new File(ctx.getApplicationInfo().nativeLibraryDir, "libffmpeg.so");
        if (!f.isFile()) throw new IOException("ffmpeg não está incluído neste APK");
        try {
            if (!f.canExecute()) f.setExecutable(true, false);
        } catch (SecurityException ignored) { }
        if (!f.canExecute()) throw new IOException("ffmpeg não pôde ser executado neste aparelho");
        return f;
    }

    public static Session get(String id) {
        return id == null ? null : SESSIONS.get(id);
    }

    public static void stop(String id) {
        Session s = id == null ? null : SESSIONS.remove(id);
        if (s == null) return;
        s.closed = true;
        try { s.process.destroy(); } catch (Throwable ignored) { }
        if (s.feeder != null) s.feeder.interrupt();
        deleteTree(s.dir);
    }

    public static void stopAll() {
        for (String id : new ArrayList<>(SESSIONS.keySet())) stop(id);
    }

    private static boolean playlistReady(File dir) {
        if (countSegments(dir) == 0) return false;
        File p = new File(dir, "index.m3u8");
        if (!p.isFile()) return false;
        try (RandomAccessFile raf = new RandomAccessFile(p, "r")) {
            int len = (int) Math.min(raf.length(), 1 << 20);
            byte[] data = new byte[len];
            raf.readFully(data);
            return new String(data, java.nio.charset.StandardCharsets.UTF_8).contains("#EXTINF");
        } catch (IOException e) {
            return false;
        }
    }

    private static int countSegments(File dir) {
        File[] files = dir.listFiles();
        if (files == null) return 0;
        int n = 0;
        for (File f : files) if (f.isFile() && f.getName().endsWith(".ts")) n++;
        return n;
    }

    private static void feed(Process p, File source, DownloadManager.StreamRef ref) {
        long size = ref != null ? ref.size() : source.length();
        if (ref != null && size <= 0) ref = null;
        if (size <= 0) size = source.length();
        try (RandomAccessFile raf = new RandomAccessFile(source, "r");
             OutputStream in = p.getOutputStream()) {
            long pos = 0;
            byte[] buf = new byte[64 * 1024];
            while (!Thread.currentThread().isInterrupted() && pos < size) {
                long available = ref != null ? Math.min(ref.contiguous(), size) : size;
                if (available <= pos) {
                    if (ref != null && ref.complete()) {
                        available = Math.min(ref.contiguous(true), size);
                        if (available <= pos) break;
                    } else {
                        try { Thread.sleep(120L); } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                            break;
                        }
                        continue;
                    }
                }
                int want = (int) Math.min(buf.length, available - pos);
                raf.seek(pos);
                int n = raf.read(buf, 0, want);
                if (n <= 0) {
                    try { Thread.sleep(120L); } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                    continue;
                }
                in.write(buf, 0, n);
                in.flush();
                pos += n;
            }
        } catch (Exception e) {
            Log.d(TAG, "feeder stopped: " + e.getMessage());
        }
    }

    private static void drain(Session s, InputStream in) {
        byte[] buf = new byte[4096];
        try (InputStream stream = in) {
            int n;
            while ((n = stream.read(buf)) > 0) {
                String line = new String(buf, 0, n, java.nio.charset.StandardCharsets.UTF_8).trim();
                if (line.isEmpty()) continue;
                Log.w(TAG, line);
                s.appendError(line);
            }
        } catch (IOException ignored) { }
    }

    private static void drainQuiet(InputStream in) {
        byte[] buf = new byte[4096];
        try (InputStream stream = in) {
            while (stream.read(buf) > 0) { /* discard */ }
        } catch (IOException ignored) { }
    }

    private static void deleteTree(File f) {
        if (f == null) return;
        File[] children = f.listFiles();
        if (children != null) for (File c : children) deleteTree(c);
        f.delete();
    }
}