package com.nexshare;

import android.content.Context;
import android.util.Log;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.util.ArrayList;
import java.util.List;

/**
 * Real-time container/codec conversion for the web player.
 *
 * <p>ffmpeg reads the torrent file from stdin (fed only with contiguous, already-downloaded
 * bytes) and writes a fragmented MP4 to stdout. Chrome can start playing the first fragment
 * while the rest of the movie is still downloading, so no full-file wait and no VLC jump.</p>
 */
public final class LiveRemux {
    private static final String TAG = "NexShareLive";

    private LiveRemux() { }

    /** The bundled ffmpeg executable, or null when this ABI has no build. */
    public static File binary(Context ctx) {
        File f = new File(ctx.getApplicationInfo().nativeLibraryDir, "libffmpeg.so");
        if (!f.isFile()) return null;
        try {
            if (!f.canExecute()) f.setExecutable(true, false);
        } catch (SecurityException ignored) { }
        return f.isFile() ? f : null;
    }

    public static boolean available(Context ctx) {
        File b = binary(ctx);
        return b != null && b.canExecute();
    }

    /**
     * Streams a fragmented MP4 to {@code out} until the source ends or the client disconnects.
     * The caller owns HTTP headers and must close {@code out}.
     */
    public static void stream(Context ctx, File source, DownloadManager.StreamRef ref, OutputStream out)
            throws IOException {
        File bin = binary(ctx);
        if (bin == null) throw new IOException("ffmpeg is not bundled for this ABI");

        List<String> cmd = new ArrayList<>();
        cmd.add(bin.getAbsolutePath());
        cmd.add("-hide_banner");
        cmd.add("-loglevel"); cmd.add("error");
        cmd.add("-fflags"); cmd.add("+genpts");
        cmd.add("-probesize"); cmd.add("5M");
        cmd.add("-analyzeduration"); cmd.add("5M");
        cmd.add("-i"); cmd.add("pipe:0");
        cmd.add("-map"); cmd.add("0:V:0");
        cmd.add("-map"); cmd.add("0:a:0?");
        cmd.add("-sn"); cmd.add("-dn");
        cmd.add("-c:v"); cmd.add("copy");
        cmd.add("-c:a"); cmd.add("aac");
        cmd.add("-b:a"); cmd.add("192k");
        cmd.add("-ac"); cmd.add("2");
        cmd.add("-movflags"); cmd.add("+frag_keyframe+empty_moov+default_base_moof");
        cmd.add("-max_muxing_queue_size"); cmd.add("4096");
        cmd.add("-flush_packets"); cmd.add("1");
        cmd.add("-f"); cmd.add("mp4");
        cmd.add("pipe:1");

        Process p;
        try {
            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.redirectErrorStream(false);
            p = pb.start();
        } catch (Exception e) {
            throw new IOException("ffmpeg did not start: " + e.getMessage(), e);
        }

        Thread err = new Thread(() -> drain(p.getErrorStream()), "ffmpeg-stderr");
        err.setDaemon(true);
        err.start();
        Thread feeder = new Thread(() -> feed(p, source, ref), "ffmpeg-stdin");
        feeder.setDaemon(true);
        feeder.start();

        byte[] buf = new byte[64 * 1024];
        try (InputStream in = p.getInputStream()) {
            int n;
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
                out.flush();
            }
        } catch (IOException e) {
            // Browser closed the player or navigated away: stop the encoder promptly.
            Log.d(TAG, "client disconnected: " + e.getMessage());
        } finally {
            p.destroy();
            feeder.interrupt();
            try { p.getOutputStream().close(); } catch (IOException ignored) { }
            // Never block the HTTP worker on waitFor(): a stuck child must not pin a server
            // thread after the browser has already gone away. destroy() sends termination.
        }
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
                        // Refresh past the 300 ms cache before declaring the source finished.
                        available = Math.min(ref.contiguous(true), size);
                        if (available <= pos) break;
                    } else {
                        try { Thread.sleep(120); } catch (InterruptedException e) {
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
                    try { Thread.sleep(120); } catch (InterruptedException e) {
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
            // ffmpeg finished early or the client vanished; the stdout pump reports it.
            Log.d(TAG, "feeder stopped: " + e.getMessage());
        }
    }

    private static void drain(InputStream in) {
        byte[] buf = new byte[4096];
        try (InputStream s = in) {
            int n;
            while ((n = s.read(buf)) > 0) {
                String line = new String(buf, 0, n, java.nio.charset.StandardCharsets.UTF_8).trim();
                if (!line.isEmpty()) Log.w(TAG, line);
            }
        } catch (IOException ignored) { }
    }
}
