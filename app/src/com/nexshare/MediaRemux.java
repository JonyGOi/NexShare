package com.nexshare;

import android.content.Context;
import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.media.MediaMuxer;

import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.nio.ByteBuffer;
import java.security.MessageDigest;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Remuxes MKV/AVI/TS files into MP4 without re-encoding (H.264/H.265 + AAC).
 * Browsers cannot play Matroska directly even when the codecs are supported, so a
 * container change is enough for most videos and is fast (no quality loss).
 */
public final class MediaRemux {

    private static final Map<String, Job> JOBS = new ConcurrentHashMap<>();
    private static final ExecutorService WORKER = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "nexshare:remux");
        t.setDaemon(true);
        return t;
    });
    private static File cacheDir;

    private MediaRemux() { }

    public static boolean needsRemux(String name) {
        String n = name == null ? "" : name.toLowerCase(Locale.ROOT);
        return n.endsWith(".mkv") || n.endsWith(".avi") || n.endsWith(".mov") || n.endsWith(".ts")
            || n.endsWith(".m2ts") || n.endsWith(".mpg") || n.endsWith(".mpeg")
            || n.endsWith(".wmv") || n.endsWith(".flv") || n.endsWith(".vob");
    }

    public static synchronized Job start(Context ctx, File src) throws Exception {
        if (src == null || !src.isFile()) throw new Exception("arquivo não encontrado");
        cacheDir = new File(ctx.getCacheDir(), "remux");
        if (!cacheDir.exists() && !cacheDir.mkdirs()) throw new Exception("sem espaço para o cache");
        String key = key(src);
        File out = new File(cacheDir, key + ".mp4");
        // Reuse a running conversion or a previous successful one.
        for (Job j : JOBS.values()) {
            if (!j.source.equals(src.getAbsolutePath())) continue;
            if ("preparing".equals(j.state) || "remuxing".equals(j.state)) return j;
            if ("done".equals(j.state) && j.out.exists()) return j;
        }
        cleanup(out);
        Job job = new Job(src, out);
        if (out.isFile() && out.length() > 1024) {
            job.state = "done";
            job.progress = 100;
            JOBS.put(job.id, job);
            return job;
        }
        JOBS.put(job.id, job);
        WORKER.submit(job);
        return job;
    }

    public static Job get(String id) {
        return id == null ? null : JOBS.get(id);
    }

    public static File file(String id) {
        Job j = get(id);
        return j != null && "done".equals(j.state) ? j.out : null;
    }

    private static String key(File f) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-1");
        md.update(f.getAbsolutePath().getBytes("UTF-8"));
        md.update(Long.toString(f.length()).getBytes("UTF-8"));
        md.update(Long.toString(f.lastModified()).getBytes("UTF-8"));
        StringBuilder sb = new StringBuilder();
        for (byte b : md.digest()) sb.append(String.format(Locale.ROOT, "%02x", b & 0xff));
        return sb.toString();
    }

    private static void cleanup(File keep) {
        File[] files = cacheDir == null ? null : cacheDir.listFiles();
        if (files == null) return;
        long now = System.currentTimeMillis();
        for (File f : files) {
            if (keep != null && f.equals(keep)) continue;
            long age = now - f.lastModified();
            // Never delete a conversion that a just-started job is still writing.
            if (age > 7L * 24 * 3600 * 1000 || (f.length() == 0 && age > 3600_000L)) f.delete();
        }
    }

    public static final class Job implements Runnable {
        public final String id = UUID.randomUUID().toString().substring(0, 8);
        public final File source;
        public final File out;
        public volatile String state = "preparing";
        public volatile double progress;
        public volatile String error = "";
        public volatile String videoCodec = "";
        public volatile String audioCodec = "";
        public volatile boolean cancelled;

        Job(File source, File out) {
            this.source = source;
            this.out = out;
        }

        public JSONObject toJson() {
            JSONObject o = new JSONObject();
            try {
                o.put("id", id);
                o.put("state", state);
                o.put("progress", progress);
                o.put("error", error);
                o.put("video", videoCodec);
                o.put("audio", audioCodec);
                o.put("ready", "done".equals(state) && out.exists());
                o.put("size", out.exists() ? out.length() : 0);
            } catch (Exception ignored) { }
            return o;
        }

        @Override
        public void run() {
            File part = new File(out.getPath() + ".part");
            MediaExtractor vex = null, aex = null;
            MediaMuxer mux = null;
            try {
                vex = new MediaExtractor();
                vex.setDataSource(source.getAbsolutePath());
                int v = pickTrack(vex, true);
                if (v < 0) throw new Exception("este vídeo não tem uma faixa compatível (H.264/H.265)");
                MediaFormat vf = vex.getTrackFormat(v);
                videoCodec = vf.getString(MediaFormat.KEY_MIME);
                vex.selectTrack(v);
                int a = pickTrack(vex, false);
                MediaFormat af = a >= 0 ? vex.getTrackFormat(a) : null;
                if (a >= 0) {
                    audioCodec = af.getString(MediaFormat.KEY_MIME);
                    aex = new MediaExtractor();
                    aex.setDataSource(source.getAbsolutePath());
                    aex.selectTrack(a);
                }
                long duration = 0;
                try { duration = vf.getLong(MediaFormat.KEY_DURATION); } catch (Exception ignored) { }
                if (duration <= 0) duration = Math.max(1, source.length() / 40000L) * 1000L;

                mux = new MediaMuxer(part.getAbsolutePath(), MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
                int vTrack = mux.addTrack(vf);
                int aTrack = -1;
                if (aex != null && af != null) {
                    try { aTrack = mux.addTrack(af); }
                    catch (Exception e) { audioCodec = ""; aex = null; a = -1; }
                }
                mux.start();
                state = "remuxing";
                TrackReader vr = new TrackReader(vex, v);
                TrackReader ar = aex == null ? null : new TrackReader(aex, a);
                long minStart = vr.firstPts;
                if (ar != null) {
                    ar.prepareFirst();
                    minStart = Math.min(minStart, ar.firstPts);
                }
                long maxPts = 0;
                while (vr.has || (ar != null && ar.has)) {
                    if (cancelled) throw new InterruptedException("cancelado");
                    TrackReader pick;
                    if (vr.has && (ar == null || !ar.has || vr.pts <= ar.pts)) pick = vr;
                    else pick = ar;
                    if (pick == null) break;
                    if (pick.size > 0) {
                        long pts = Math.max(0, pick.pts - minStart);
                        MediaCodec.BufferInfo bi = new MediaCodec.BufferInfo();
                        bi.set(0, pick.size, pts, pick.flags);
                        mux.writeSampleData(pick == vr ? vTrack : aTrack, pick.buf, bi);
                        if (pts > maxPts) maxPts = pts;
                    }
                    pick.advance();
                    if (duration > 0) progress = Math.min(99.0, maxPts * 100.0 / duration);
                }
                mux.stop();
                mux.release();
                mux = null;
                if (!part.renameTo(out)) {
                    // Some Android volumes cannot atomically rename; fall back to a copy.
                    copy(part, out);
                    part.delete();
                }
                progress = 100;
                state = "done";
                cleanup(null);
            } catch (InterruptedException e) {
                state = "cancelled";
            } catch (Throwable e) {
                state = "error";
                error = e.getMessage() == null ? e.toString() : e.getMessage();
            } finally {
                try { if (mux != null) mux.release(); } catch (Exception ignored) { }
                try { if (vex != null) vex.release(); } catch (Exception ignored) { }
                try { if (aex != null) aex.release(); } catch (Exception ignored) { }
                part.delete();
            }
        }

        private static void copy(File in, File out) throws Exception {
            try (FileInputStream is = new FileInputStream(in);
                 java.io.FileOutputStream os = new java.io.FileOutputStream(out)) {
                byte[] b = new byte[65536];
                int n;
                while ((n = is.read(b)) > 0) os.write(b, 0, n);
            }
        }

        private static int pickTrack(MediaExtractor ex, boolean video) {
            int fallback = -1;
            for (int i = 0; i < ex.getTrackCount(); i++) {
                try {
                    String mime = ex.getTrackFormat(i).getString(MediaFormat.KEY_MIME);
                    if (mime == null) continue;
                    if (video) {
                        if (mime.equals("video/avc")) return i;
                        if (mime.equals("video/hevc")) fallback = fallback < 0 ? i : fallback;
                        else if (fallback < 0 && (mime.equals("video/mp4v-es") || mime.equals("video/3gpp"))) fallback = i;
                    } else {
                        if (mime.equals("audio/mp4a-latm")) return i;
                        if (fallback < 0) fallback = i;
                    }
                } catch (Exception ignored) { }
            }
            return video ? fallback : fallback;
        }
    }

    /** Independent extractor reader, so video and audio can be interleaved by PTS. */
    private static final class TrackReader {
        final MediaExtractor ex;
        final int track;
        final ByteBuffer buf = ByteBuffer.allocateDirect(8 * 1024 * 1024);
        final MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
        boolean has;
        int size;
        long pts;
        int flags;
        long firstPts = 0;
        boolean firstReady;

        TrackReader(MediaExtractor ex, int track) {
            this.ex = ex;
            this.track = track;
            this.has = read();
            if (has) firstPts = pts;
        }

        void prepareFirst() {
            // Already read the first sample in the constructor; used only for min start.
            if (has) firstReady = true;
        }

        boolean read() {
            size = ex.readSampleData(buf, 0);
            if (size < 0) { has = false; return false; }
            pts = ex.getSampleTime();
            flags = ex.getSampleFlags();
            has = true;
            return true;
        }

        void advance() {
            ex.advance();
            read();
        }
    }
}
