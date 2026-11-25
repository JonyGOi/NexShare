package com.nexshare;

import android.content.Context;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.media.MediaMuxer;

import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
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

    /** Cancels a conversion and removes its cached MP4 (used when a player session ends). */
    public static void remove(String id) {
        Job j = get(id);
        if (j == null) return;
        j.cancelled = true;
        j.out.delete();
        new File(j.out.getPath() + ".part").delete();
        JOBS.remove(id);
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
            File audioTemp = null;
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
                double progressBase = 0;
                if (a >= 0) {
                    audioCodec = af.getString(MediaFormat.KEY_MIME);
                    // MKV releases very often carry AC-3/E-AC-3/DTS, which Chrome cannot
                    // decode even when the MP4 container is valid. Keep AAC as-is; convert
                    // any other audio codec to AAC while the video is copied untouched.
                    if (!isAac(audioCodec)) {
                        audioTemp = new File(out.getPath() + ".audio.mp4");
                        audioTemp.delete();
                        if (transcodeAudio(source, a, audioTemp)) {
                            aex = new MediaExtractor();
                            aex.setDataSource(audioTemp.getAbsolutePath());
                            a = 0;
                            af = aex.getTrackFormat(a);
                            aex.selectTrack(a);
                            audioCodec = "audio/mp4a-latm";
                            progressBase = 35;
                        } else {
                            audioCodec = "";
                            af = null;
                        }
                    } else {
                        aex = new MediaExtractor();
                        aex.setDataSource(source.getAbsolutePath());
                        aex.selectTrack(a);
                    }
                }
                long duration = 0;
                try { duration = vf.getLong(MediaFormat.KEY_DURATION); } catch (Exception ignored) { }
                if (duration <= 0) duration = Math.max(1, source.length() / 40000L) * 1000L;

                mux = new MediaMuxer(part.getAbsolutePath(), MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
                int vTrack = mux.addTrack(vf);
                int aTrack = -1;
                if (aex != null && af != null) {
                    try { aTrack = mux.addTrack(af); }
                    catch (Exception e) { audioCodec = ""; aex = null; a = -1; af = null; }
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
                    if (duration > 0) {
                        double p = maxPts * 100.0 / duration;
                        progress = Math.min(99.0, progressBase + p * (100.0 - progressBase) / 100.0);
                    }
                }
                if (cancelled) throw new InterruptedException("cancelado");
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
                if (audioTemp != null) audioTemp.delete();
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

        private static boolean isAac(String mime) {
            return mime != null && mime.equalsIgnoreCase("audio/mp4a-latm");
        }

        /**
         * Converts an AC-3/E-AC-3/DTS/Opus/MP3 track to stereo AAC in a temporary MP4.
         * The video is never re-encoded; this keeps a 1080p movie conversion near disk speed
         * instead of real-time playback speed, while making the audio playable in Chrome.
         */
        private boolean transcodeAudio(File src, int track, File dst) {
            MediaExtractor ex = null;
            MediaCodec dec = null, enc = null;
            MediaMuxer mux = null;
            boolean ok = false;
            try {
                ex = new MediaExtractor();
                ex.setDataSource(src.getAbsolutePath());
                ex.selectTrack(track);
                MediaFormat in = ex.getTrackFormat(track);
                String mime = in.getString(MediaFormat.KEY_MIME);
                int channels = in.containsKey(MediaFormat.KEY_CHANNEL_COUNT)
                    ? in.getInteger(MediaFormat.KEY_CHANNEL_COUNT) : 2;
                int rate = in.containsKey(MediaFormat.KEY_SAMPLE_RATE)
                    ? in.getInteger(MediaFormat.KEY_SAMPLE_RATE) : 48000;
                int outCh = Math.min(2, Math.max(1, channels));
                rate = Math.min(48000, Math.max(8000, rate));
                long durationUs = 0;
                try { durationUs = in.getLong(MediaFormat.KEY_DURATION); } catch (Exception ignored) { }

                MediaFormat encFmt = MediaFormat.createAudioFormat("audio/mp4a-latm", rate, outCh);
                encFmt.setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC);
                encFmt.setInteger(MediaFormat.KEY_BIT_RATE, outCh > 1 ? 192000 : 96000);
                encFmt.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 1 << 20);
                enc = MediaCodec.createEncoderByType("audio/mp4a-latm");
                enc.configure(encFmt, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
                enc.start();

                dec = MediaCodec.createDecoderByType(mime);
                dec.configure(in, null, null, 0);
                dec.start();
                mux = new MediaMuxer(dst.getAbsolutePath(), MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);

                MediaCodec.BufferInfo di = new MediaCodec.BufferInfo();
                MediaCodec.BufferInfo ei = new MediaCodec.BufferInfo();
                MediaFormat pcmFmt = null;
                boolean inputDone = false, decoderDone = false, encoderDone = false, eosQueued = false;
                int outTrack = -1;
                boolean muxStarted = false;
                long lastPts = 0;

                while (!encoderDone) {
                    if (cancelled) throw new InterruptedException("cancelado");
                    if (!inputDone) {
                        int inIdx = dec.dequeueInputBuffer(10000);
                        if (inIdx >= 0) {
                            ByteBuffer ib = dec.getInputBuffer(inIdx);
                            ib.clear();
                            int n = ex.readSampleData(ib, 0);
                            long pts = ex.getSampleTime();
                            if (n < 0) {
                                dec.queueInputBuffer(inIdx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                                inputDone = true;
                            } else {
                                dec.queueInputBuffer(inIdx, 0, n, pts, 0);
                                ex.advance();
                            }
                        }
                    }

                    int diIdx;
                    while ((diIdx = dec.dequeueOutputBuffer(di, 0)) != MediaCodec.INFO_TRY_AGAIN_LATER) {
                        if (diIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                            pcmFmt = dec.getOutputFormat();
                            continue;
                        }
                        if (diIdx < 0) continue;
                        if (di.size > 0) {
                            if (durationUs > 0) progress = Math.min(34.0, di.presentationTimeUs * 34.0 / durationUs);
                            ByteBuffer db = dec.getOutputBuffer(diIdx);
                            ByteBuffer pcm = downmix(db, pcmFmt != null ? pcmFmt : in, outCh);
                            if (pcm != null && pcm.remaining() > 0) {
                                int enIdx = enc.dequeueInputBuffer(20000);
                                if (enIdx >= 0) {
                                    ByteBuffer eb = enc.getInputBuffer(enIdx);
                                    eb.clear();
                                    eb.put(pcm);
                                    lastPts = di.presentationTimeUs;
                                    enc.queueInputBuffer(enIdx, 0, pcm.remaining(), di.presentationTimeUs, 0);
                                }
                            }
                        }
                        boolean eos = (di.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0;
                        dec.releaseOutputBuffer(diIdx, false);
                        if (eos) decoderDone = true;
                    }

                    if (decoderDone && !eosQueued) {
                        int enIdx = enc.dequeueInputBuffer(20000);
                        if (enIdx >= 0) {
                            enc.queueInputBuffer(enIdx, 0, 0, lastPts, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                            eosQueued = true;
                        }
                    }

                    int eiIdx;
                    while ((eiIdx = enc.dequeueOutputBuffer(ei, 0)) != MediaCodec.INFO_TRY_AGAIN_LATER) {
                        if (eiIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                            if (!muxStarted) {
                                outTrack = mux.addTrack(enc.getOutputFormat());
                                mux.start();
                                muxStarted = true;
                            }
                            continue;
                        }
                        if (eiIdx < 0) continue;
                        if (!muxStarted) {
                            outTrack = mux.addTrack(enc.getOutputFormat());
                            mux.start();
                            muxStarted = true;
                        }
                        if (ei.size > 0) {
                            ByteBuffer eb = enc.getOutputBuffer(eiIdx);
                            mux.writeSampleData(outTrack, eb, ei);
                        }
                        boolean eos = (ei.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0;
                        enc.releaseOutputBuffer(eiIdx, false);
                        if (eos) { encoderDone = true; break; }
                    }
                }
                if (!muxStarted) throw new Exception("o codec de áudio não produziu faixas");
                mux.stop();
                mux.release();
                mux = null;
                ok = dst.isFile() && dst.length() > 0;
                return ok;
            } catch (Throwable e) {
                return false;
            } finally {
                try { if (mux != null) mux.release(); } catch (Exception ignored) { }
                try { if (dec != null) dec.stop(); } catch (Exception ignored) { }
                try { if (dec != null) dec.release(); } catch (Exception ignored) { }
                try { if (enc != null) enc.stop(); } catch (Exception ignored) { }
                try { if (enc != null) enc.release(); } catch (Exception ignored) { }
                try { if (ex != null) ex.release(); } catch (Exception ignored) { }
                if (!ok) dst.delete();
            }
        }

        private static ByteBuffer downmix(ByteBuffer src, MediaFormat fmt, int outCh) throws Exception {
            int inCh = fmt.containsKey(MediaFormat.KEY_CHANNEL_COUNT)
                ? fmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT) : outCh;
            int encoding = fmt.containsKey(MediaFormat.KEY_PCM_ENCODING)
                ? fmt.getInteger(MediaFormat.KEY_PCM_ENCODING) : android.media.AudioFormat.ENCODING_PCM_16BIT;
            if (encoding != android.media.AudioFormat.ENCODING_PCM_16BIT || inCh <= 0) return null;
            inCh = Math.max(1, inCh);
            ByteBuffer in = src.duplicate();
            in.order(ByteOrder.LITTLE_ENDIAN);
            int samples = in.remaining() / 2;
            int frames = samples / inCh;
            if (frames <= 0) return null;
            ByteBuffer out = ByteBuffer.allocateDirect(frames * outCh * 2).order(ByteOrder.LITTLE_ENDIAN);
            for (int i = 0; i < frames; i++) {
                short[] s = new short[inCh];
                for (int c = 0; c < inCh; c++) s[c] = in.getShort();
                if (outCh == 1) {
                    float sum = 0;
                    for (short v : s) sum += v;
                    int v = Math.round(sum / inCh);
                    out.putShort((short) Math.max(Short.MIN_VALUE, Math.min(Short.MAX_VALUE, v)));
                } else {
                    float l, r;
                    if (inCh == 1) {
                        l = r = s[0];
                    } else if (inCh >= 6) {
                        l = s[0] + 0.707f * s[2] + 0.707f * s[4];
                        r = s[1] + 0.707f * s[2] + 0.707f * s[5];
                    } else if (inCh == 3) {
                        l = s[0] + 0.707f * s[2];
                        r = s[1] + 0.707f * s[2];
                    } else {
                        l = s[0];
                        r = s[1];
                    }
                    out.putShort((short) Math.max(Short.MIN_VALUE, Math.min(Short.MAX_VALUE, Math.round(l))));
                    out.putShort((short) Math.max(Short.MIN_VALUE, Math.min(Short.MAX_VALUE, Math.round(r))));
                }
            }
            out.flip();
            return out;
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
