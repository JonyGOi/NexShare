package com.nexshare;

import android.content.Context;

import org.libtorrent4j.AddTorrentParams;
import org.libtorrent4j.FileStorage;
import org.libtorrent4j.Priority;
import org.libtorrent4j.SessionHandle;
import org.libtorrent4j.SessionManager;
import org.libtorrent4j.SessionParams;
import org.libtorrent4j.SettingsPack;
import org.libtorrent4j.Sha1Hash;
import org.libtorrent4j.TorrentFlags;
import org.libtorrent4j.TorrentHandle;
import org.libtorrent4j.TorrentInfo;
import org.libtorrent4j.TorrentStatus;
import org.libtorrent4j.swig.file_progress_flags_t;
import org.libtorrent4j.swig.torrent_flags_t;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.Collection;

/** BitTorrent downloads (magnet links and .torrent files) on top of libtorrent4j. */
public class TorrentEngine {

    private final SessionManager session;
    private final File torrentDir;

    public TorrentEngine(Context ctx) throws IOException {
        torrentDir = new File(ctx.getFilesDir(), "torrents");
        torrentDir.mkdirs();
        SettingsPack sp = new SettingsPack();
        sp.activeDownloads(4);
        sp.activeSeeds(4);
        sp.setEnableDht(true);
        session = new SessionManager(false);
        session.start(new SessionParams(sp));
        session.startDht();
    }

    void add(DownloadManager.Task t, String magnet, byte[] torrentFile, File dir) throws IOException {
        try {
            if (magnet != null) {
                AddTorrentParams p = AddTorrentParams.parseMagnetUri(magnet);
                t.hash = p.getInfoHashes().getBest().toHex();
                String dn = p.getName();
                if (dn != null && !dn.isEmpty()) t.name = dn;
                session.download(magnet, dir, new torrent_flags_t());
            } else {
                TorrentInfo ti = new TorrentInfo(torrentFile);
                t.hash = ti.infoHash().toHex();
                t.name = ti.name();
                t.total = ti.totalSize();
                write(new File(torrentDir, t.hash + ".torrent"), torrentFile);
                session.download(ti, dir);
            }
        } catch (IOException e) {
            throw e;
        } catch (Throwable e) {
            throw new IOException("invalid torrent: " + e.getMessage());
        }
    }

    /** Re-adds torrents saved in the task list after the app restarts. */
    void restore(Collection<DownloadManager.Task> tasks) {
        for (DownloadManager.Task t : tasks) {
            if (!t.type.equals("torrent") || t.hash == null) continue;
            try {
                File f = new File(torrentDir, t.hash + ".torrent");
                File dir = new File(t.dest);
                if (f.exists()) session.download(new TorrentInfo(f), dir);
                else if (t.url.startsWith("magnet:")) session.download(t.url, dir, new torrent_flags_t());
                TorrentHandle h = handle(t);
                if (h != null && h.isValid()) {
                    if (t.status.equals("paused") || t.status.equals("done")) {
                        pauseHandle(h);
                    } else {
                        applyPriority(t);
                    }
                }
            } catch (Throwable e) {
                t.status = "error";
                t.error = e.getMessage();
            }
        }
    }

    void refresh(Collection<DownloadManager.Task> tasks) {
        for (DownloadManager.Task t : tasks) {
            if (!t.type.equals("torrent")) continue;
            TorrentHandle h = handle(t);
            if (h == null) {
                t.speed = t.upSpeed = 0;
                continue;
            }
            try {
                TorrentStatus s = h.status();
                if (s.hasMetadata()) {
                    t.name = s.name();
                    t.total = s.totalWanted();
                    // Debrid streaming priorities requested before metadata arrived are applied now.
                    if (!t.prioApplied && t.priorityFile != NO_STREAM) applyPriority(t);
                }
                t.done = s.totalWantedDone();
                t.speed = s.downloadPayloadRate();
                t.upSpeed = s.uploadPayloadRate();
                t.peers = s.numPeers();
                t.seeds = s.numSeeds();
                if (s.errorCode() != null && s.errorCode().isError()) {
                    t.status = "error";
                    t.error = s.errorCode().getMessage();
                    continue;
                }
                if (t.status.equals("paused") || t.status.equals("done")) {
                    t.speed = t.upSpeed = 0;
                    continue;
                }
                switch (s.state()) {
                    case DOWNLOADING_METADATA: t.status = "metadata"; break;
                    case CHECKING_FILES:
                    case CHECKING_RESUME_DATA: t.status = "checking"; break;
                    case FINISHED:
                    case SEEDING: t.status = "seeding"; break;
                    default: t.status = "downloading";
                }
            } catch (Throwable ignored) { }
        }
    }

    /** Lists all files inside a torrent with individual progress for Debrid streaming. */
    public java.util.List<org.json.JSONObject> listFiles(DownloadManager.Task t) {
        TorrentHandle h = handle(t);
        if (h == null || !h.isValid()) return java.util.Collections.emptyList();
        TorrentInfo ti = h.torrentFile();
        if (ti == null) return java.util.Collections.emptyList();
        FileStorage fs = ti.files();
        long[] progress = fileProgressOf(h, fs.numFiles());
        java.util.List<org.json.JSONObject> list = new java.util.ArrayList<>();
        String dest = t.dest;
        for (int i = 0; i < fs.numFiles(); i++) {
            org.json.JSONObject o = new org.json.JSONObject();
            try {
                o.put("id", i);
                o.put("name", fs.fileName(i));
                String rel = fs.filePath(i);
                o.put("path", rel);
                long sz = fs.fileSize(i);
                o.put("size", sz);
                long done = (progress != null && i < progress.length) ? progress[i] : 0;
                o.put("done", done);
                o.put("progress", sz > 0 ? Math.min(100.0, (done * 100.0) / sz) : 0);
                o.put("startPiece", fs.pieceIndexAtFile(i));
                o.put("endPiece", fs.lastPieceIndexAtFile(i));
                o.put("fullPath", new File(dest, rel).getAbsolutePath());
                list.add(o);
            } catch (Exception ignored) { }
        }
        return list;
    }

    /** Prioritizes the biggest video file as soon as metadata arrives (best guess for streaming). */
    public void autoPrioritize(DownloadManager.Task t) {
        if (t == null) return;
        t.priorityFile = AUTO_STREAM;
        t.prioApplied = false;
        applyPriority(t);
    }

    /** Prioritizes a specific file for instant sequential streaming. */
    public void prioritizeFile(DownloadManager.Task t, int fileIndex) {
        if (t == null) return;
        t.priorityFile = Math.max(0, fileIndex);
        t.prioApplied = false;
        applyPriority(t);
    }

    /** Restores a normal (non streaming) file selection: every file at default priority. */
    public void clearPriority(DownloadManager.Task t) {
        if (t == null) return;
        t.priorityFile = NO_STREAM;
        t.prioApplied = false;
        TorrentHandle h = handle(t);
        if (h == null) return;
        try {
            h.unsetFlags(TorrentFlags.SEQUENTIAL_DOWNLOAD);
            TorrentInfo ti = h.torrentFile();
            if (ti != null) {
                Priority[] prios = new Priority[ti.files().numFiles()];
                for (int i = 0; i < prios.length; i++) prios[i] = Priority.DEFAULT;
                h.prioritizeFiles(prios);
            }
            t.prioApplied = true;
        } catch (Throwable ignored) { }
    }

    private void applyPriority(DownloadManager.Task t) {
        if (t == null || t.priorityFile == NO_STREAM) return;
        TorrentHandle h = handle(t);
        if (h == null) return;
        try {
            h.setFlags(TorrentFlags.SEQUENTIAL_DOWNLOAD);
            TorrentInfo ti = h.torrentFile();
            if (ti == null) return; // metadata still loading; refresh() retries
            FileStorage fs = ti.files();
            if (t.priorityFile == AUTO_STREAM) {
                t.priorityFile = pickStreamFile(fs);
                if (t.priorityFile < 0) { t.priorityFile = NO_STREAM; t.prioApplied = true; return; }
            }
            int idx = t.priorityFile;
            if (idx < 0 || idx >= fs.numFiles()) return;
            int sp = fs.pieceIndexAtFile(idx);
            int ep = fs.lastPieceIndexAtFile(idx);
            if (ep >= sp) h.setSequentialRange(sp, ep);
            h.setPieceDeadline(sp, 500);
            if (sp + 1 <= ep) h.setPieceDeadline(sp + 1, 1000);
            if (ep > sp + 1) h.setPieceDeadline(ep, 1500);

            Priority[] prios = new Priority[fs.numFiles()];
            for (int i = 0; i < fs.numFiles(); i++) {
                prios[i] = (i == idx) ? Priority.TOP_PRIORITY : Priority.DEFAULT;
            }
            h.prioritizeFiles(prios);
            t.prioApplied = true;
        } catch (Throwable ignored) { }
    }

    /** Biggest playable video in the torrent (falls back to the biggest file). */
    private static int pickStreamFile(FileStorage fs) {
        int best = -1, bestAny = -1;
        long bestSize = -1, bestAnySize = -1;
        for (int i = 0; i < fs.numFiles(); i++) {
            long size = fs.fileSize(i);
            if (size > bestAnySize) { bestAnySize = size; bestAny = i; }
            if (size > bestSize && isVideo(fs.fileName(i))) { bestSize = size; best = i; }
        }
        return best >= 0 ? best : bestAny;
    }

    private static boolean isVideo(String name) {
        String n = name.toLowerCase(java.util.Locale.ROOT);
        return n.endsWith(".mp4") || n.endsWith(".mkv") || n.endsWith(".avi") || n.endsWith(".mov")
            || n.endsWith(".webm") || n.endsWith(".m4v") || n.endsWith(".ts") || n.endsWith(".mpg")
            || n.endsWith(".mpeg") || n.endsWith(".wmv") || n.endsWith(".flv");
    }

    /** Index of a file inside a torrent by its absolute path, or -1. */
    public int indexOfFile(DownloadManager.Task t, String absolutePath) {
        TorrentHandle h = handle(t);
        if (h == null || t.dest == null) return -1;
        TorrentInfo ti = h.torrentFile();
        if (ti == null) return -1;
        FileStorage fs = ti.files();
        String base = new File(t.dest).getAbsolutePath();
        String target = new File(absolutePath).getAbsolutePath();
        for (int i = 0; i < fs.numFiles(); i++) {
            if (new File(base, fs.filePath(i)).getAbsolutePath().equals(target)) return i;
        }
        return -1;
    }

    /** Bytes already downloaded for one file of a torrent (0 when unknown). */
    public long fileDownloaded(DownloadManager.Task t, int fileIndex) {
        TorrentHandle h = handle(t);
        if (h == null) return 0;
        TorrentInfo ti = h.torrentFile();
        if (ti == null) return 0;
        long[] progress = fileProgressOf(h, ti.files().numFiles());
        return progress != null && fileIndex >= 0 && fileIndex < progress.length ? progress[fileIndex] : 0;
    }

    private long[] fileProgressOf(TorrentHandle h, int files) {
        try {
            return h.fileProgress(new file_progress_flags_t());
        } catch (Throwable ignored) {
            try {
                return h.fileProgress();
            } catch (Throwable ignored2) {
                return null;
            }
        }
    }

    void pause(DownloadManager.Task t) {
        TorrentHandle h = handle(t);
        if (h != null) pauseHandle(h);
    }

    void resume(DownloadManager.Task t) throws IOException {
        TorrentHandle h = handle(t);
        if (h == null) throw new IOException("torrent not loaded");
        h.setFlags(TorrentFlags.AUTO_MANAGED);
        h.resume();
    }

    void remove(DownloadManager.Task t, boolean deleteFiles) {
        TorrentHandle h = handle(t);
        if (h != null) {
            if (deleteFiles) session.remove(h, SessionHandle.DELETE_FILES);
            else session.remove(h);
        }
        if (t.hash != null) new File(torrentDir, t.hash + ".torrent").delete();
    }

    void stop() {
        try {
            session.stop();
        } catch (Throwable ignored) { }
    }

    private void pauseHandle(TorrentHandle h) {
        h.unsetFlags(TorrentFlags.AUTO_MANAGED);
        h.pause();
    }

    private TorrentHandle handle(DownloadManager.Task t) {
        if (t.hash == null) return null;
        try {
            TorrentHandle h = session.find(Sha1Hash.parseHex(t.hash));
            return h != null && h.isValid() ? h : null;
        } catch (Throwable e) {
            return null;
        }
    }

    private static void write(File f, byte[] data) throws IOException {
        try (OutputStream out = new FileOutputStream(f)) {
            out.write(data);
        }
    }

    /** Sentinel: no streaming priority requested. */
    public static final int NO_STREAM = -1;
    /** Sentinel: pick the biggest video file once metadata is available. */
    public static final int AUTO_STREAM = -2;
}
