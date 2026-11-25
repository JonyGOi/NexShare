package com.nexshare;

import android.content.Context;
import android.os.StatFs;

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
                // Metadata only by default: no payload is fetched before the file selection is
                // applied (applyPriority() turns the files back on). This is what lets the
                // qBittorrent-style picker work with magnets, where metadata arrives over the
                // network only while the torrent is running.
                session.download(magnet, dir, metadataFlags());
            } else {
                TorrentInfo ti = new TorrentInfo(torrentFile);
                t.hash = ti.infoHash().toHex();
                t.name = ti.name();
                t.total = ti.totalSize();
                write(new File(torrentDir, t.hash + ".torrent"), torrentFile);
                // Start held for a moment: refresh()/applyPriority() releases the payload as
                // soon as the picker decision is known, so a paused add cannot sneak bytes in
                // between session.download() and the pause.
                session.download(ti, dir, null, null, null, metadataFlags());
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
                // A torrent waiting for the user's file selection must not start fetching
                // payload after a restart either; metadata-only until the picker confirms.
                boolean hold = t.awaitingSelection && t.selected == null;
                if (f.exists()) {
                    if (hold) session.download(new TorrentInfo(f), dir, null, null, null, metadataFlags());
                    else session.download(new TorrentInfo(f), dir);
                } else if (t.url.startsWith("magnet:")) {
                    session.download(t.url, dir, metadataFlags());
                }
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

    private static final long SPACE_LOW = 2L * 1024 * 1024 * 1024;
    private static final long SPACE_HIGH = 3L * 1024 * 1024 * 1024;
    private long lastSpaceCheck;

    /** Free bytes on the volume that holds a path, or -1 when unknown. */
    public static long freeSpace(File dir) {
        if (dir == null) return -1;
        try {
            StatFs st = new StatFs(dir.getAbsolutePath());
            return st.getAvailableBytes();
        } catch (Throwable e) {
            try { return dir.getUsableSpace(); } catch (Throwable e2) { return -1; }
        }
    }

    public static long totalSpace(File dir) {
        if (dir == null) return -1;
        try {
            StatFs st = new StatFs(dir.getAbsolutePath());
            return st.getTotalBytes();
        } catch (Throwable e) {
            try { return dir.getTotalSpace(); } catch (Throwable e2) { return -1; }
        }
    }

    private static boolean isActive(String status) {
        return "downloading".equals(status) || "metadata".equals(status) || "checking".equals(status);
    }

    /**
     * Stops downloading before the phone runs out of space, and resumes by itself once the
     * space is freed (the PC purges the files it already has).
     */
    private void checkSpace(Collection<DownloadManager.Task> tasks) {
        long now = System.currentTimeMillis();
        if (now - lastSpaceCheck < 5000) return;
        lastSpaceCheck = now;
        for (DownloadManager.Task t : tasks) {
            if (!t.type.equals("torrent")) continue;
            long free = freeSpace(new File(t.dest));
            if (free < 0) continue;
            TorrentHandle h = handle(t);
            if (h == null || !h.isValid()) continue;
            if (free < SPACE_LOW && !t.spacePaused && isActive(t.status)) {
                try { h.pause(); } catch (Throwable ignored) { }
                t.status = "paused";
                t.speed = t.upSpeed = 0;
                t.spacePaused = true;
                t.error = "Pouco espaço no celular (" + (free / (1024L * 1024L * 1024L))
                    + " GB livres). Transfira para o PC e apague para liberar.";
            } else if (free > SPACE_HIGH && t.spacePaused) {
                try { h.resume(); } catch (Throwable ignored) { }
                t.status = "downloading";
                t.spacePaused = false;
                t.error = null;
            }
        }
    }

    /**
     * Forgets a file that is already safe on the PC: removes it from the selection, tells
     * libtorrent to ignore it (so it is never downloaded again) and deletes it from disk.
     * This is what makes a torrent bigger than the phone's free space possible.
     */
    public boolean purgeFile(DownloadManager.Task t, int fileIndex) {
        if (t == null) return false;
        TorrentHandle h = handle(t);
        if (h == null || !h.isValid()) return false;
        TorrentInfo ti = h.torrentFile();
        if (ti == null) return false;
        FileStorage fs = ti.files();
        if (fileIndex < 0 || fileIndex >= fs.numFiles()) return false;
        boolean[] want = wantedMask(t, fs.numFiles());
        java.util.List<Integer> keep = new java.util.ArrayList<>();
        for (int i = 0; i < fs.numFiles(); i++) {
            if (i == fileIndex) continue;
            if (want == null || want[i]) keep.add(i);
        }
        int[] ids = new int[keep.size()];
        for (int k = 0; k < ids.length; k++) ids[k] = keep.get(k);
        t.selected = ids;
        t.selectionApplied = false;
        t.prioApplied = false;
        applyPriority(t);
        File f = new File(t.dest, fs.filePath(fileIndex));
        for (int attempt = 0; attempt < 4; attempt++) {
            try { Thread.sleep(200); } catch (InterruptedException ignored) { }
            if (!f.exists()) return true;
            if (f.delete()) return true;
        }
        return !f.exists();
    }

    void refresh(Collection<DownloadManager.Task> tasks) {
        checkSpace(tasks);
        for (DownloadManager.Task t : tasks) {
            if (!t.type.equals("torrent")) continue;
            TorrentHandle h = handle(t);
            if (h == null) {
                t.speed = t.upSpeed = 0;
                continue;
            }
            try {
                TorrentStatus s = h.status();
                boolean choose = t.awaitingSelection && t.selected == null;
                if (s.hasMetadata()) {
                    t.name = s.name();
                    t.total = choose ? s.total() : s.totalWanted();
                    // Selection/streaming priorities requested before metadata arrived are applied now.
                    if (!t.selectionApplied || !t.prioApplied) applyPriority(t);
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
                if (choose) {
                    // Held for the file picker: metadata is there, payload is not downloaded.
                    t.status = s.hasMetadata() ? "choose" : "metadata";
                    t.speed = t.upSpeed = 0;
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
        boolean[] want = wantedMask(t, fs.numFiles());
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
                o.put("selected", want == null || want[i] ? 1 : 0);
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
        if (t.selected != null && !contains(t.selected, t.priorityFile)) {
            // Playing a file the user did not select: make sure it downloads too.
            int[] grown = java.util.Arrays.copyOf(t.selected, t.selected.length + 1);
            grown[grown.length - 1] = t.priorityFile;
            java.util.Arrays.sort(grown);
            t.selected = grown;
            t.selectionApplied = false;
        }
        t.prioApplied = false;
        applyPriority(t);
    }

    /**
     * Chooses which files of the torrent to download.
     * null = everything, empty array = nothing, otherwise the 0-based file indices.
     */
    public void setSelection(DownloadManager.Task t, int[] indices) {
        if (t == null) return;
        if (indices == null) {
            t.selected = null;
        } else {
            int[] copy = indices.clone();
            java.util.Arrays.sort(copy);
            t.selected = copy;
        }
        t.awaitingSelection = false;
        t.selectionApplied = false;
        t.prioApplied = false;
        applyPriority(t);
    }

    /** Restores a normal (non streaming) file selection: every file at default priority. */
    public void clearPriority(DownloadManager.Task t) {
        if (t == null) return;
        t.priorityFile = NO_STREAM;
        setSelection(t, null);
    }

    private static boolean contains(int[] arr, int value) {
        for (int i : arr) if (i == value) return true;
        return false;
    }

    private static boolean[] wantedMask(DownloadManager.Task t, int count) {
        if (t.selected == null) return null;
        boolean[] want = new boolean[count];
        for (int i : t.selected) {
            if (i >= 0 && i < count) want[i] = true;
        }
        return want;
    }

    /** Applies the current selection + streaming file to libtorrent. Safe to call repeatedly. */
    private void applyPriority(DownloadManager.Task t) {
        if (t == null) return;
        TorrentHandle h = handle(t);
        if (h == null) return;
        try {
            TorrentInfo ti = h.torrentFile();
            if (ti == null) return; // metadata still loading; refresh() retries
            FileStorage fs = ti.files();
            int n = fs.numFiles();
            boolean[] want = wantedMask(t, n);

            if (t.awaitingSelection && t.selected == null) {
                // Picker/addon has not decided yet: keep every file ignored so metadata can be
                // fetched without downloading a single byte of payload.
                h.unsetFlags(TorrentFlags.SEQUENTIAL_DOWNLOAD);
                Priority[] none = new Priority[n];
                for (int i = 0; i < n; i++) none[i] = Priority.IGNORE;
                h.prioritizeFiles(none);
                t.prioApplied = true;
                t.selectionApplied = true;
                t.total = ti.totalSize();
                return;
            }
            // The selection is known now: allow payload downloads again.
            h.unsetFlags(TorrentFlags.DEFAULT_DONT_DOWNLOAD);

            int stream = t.priorityFile;
            if (stream == AUTO_STREAM || (want != null && stream >= 0 && stream < n && !want[stream])) {
                stream = pickStreamFile(fs, want);
            }
            if (stream >= n) stream = NO_STREAM;
            if (want != null && stream >= 0 && !want[stream]) stream = NO_STREAM;

            boolean any = false;
            if (want == null) {
                any = n > 0;
            } else {
                for (boolean b : want) if (b) { any = true; break; }
            }

            if (!any) {
                // Nothing to download: ignore every file and stop prioritising.
                h.unsetFlags(TorrentFlags.SEQUENTIAL_DOWNLOAD);
                Priority[] none = new Priority[n];
                for (int i = 0; i < n; i++) none[i] = Priority.IGNORE;
                h.prioritizeFiles(none);
                t.prioApplied = true;
                t.selectionApplied = true;
                t.total = 0;
                return;
            }

            if (stream >= 0) {
                h.setFlags(TorrentFlags.SEQUENTIAL_DOWNLOAD);
                int sp = fs.pieceIndexAtFile(stream);
                int ep = fs.lastPieceIndexAtFile(stream);
                if (ep >= sp) h.setSequentialRange(sp, ep);
                h.setPieceDeadline(sp, 500);
                if (sp + 1 <= ep) h.setPieceDeadline(sp + 1, 1000);
                if (ep > sp + 1) h.setPieceDeadline(ep, 1500);
            } else {
                // Sequential anyway: downloads happen in file order, so finished files can be
                // moved to the PC and deleted even when the torrent is bigger than the disk.
                h.setFlags(TorrentFlags.SEQUENTIAL_DOWNLOAD);
            }

            Priority[] prios = new Priority[n];
            for (int i = 0; i < n; i++) {
                if (want != null && !want[i]) prios[i] = Priority.IGNORE;
                else if (i == stream) prios[i] = Priority.TOP_PRIORITY;
                else prios[i] = Priority.DEFAULT;
            }
            h.prioritizeFiles(prios);
            t.prioApplied = true;
            t.selectionApplied = true;
        } catch (Throwable ignored) { }
    }

    /** Index of the file to stream for a torrent (biggest video), or NO_STREAM. */
    public int pickBestFile(DownloadManager.Task t) {
        TorrentHandle h = handle(t);
        if (h == null || !h.isValid()) return NO_STREAM;
        TorrentInfo ti = h.torrentFile();
        if (ti == null) return NO_STREAM;
        FileStorage fs = ti.files();
        return pickStreamFile(fs, wantedMask(t, fs.numFiles()));
    }

    static boolean isVideo(String name) {
        String n = name.toLowerCase(java.util.Locale.ROOT);
        return n.endsWith(".mp4") || n.endsWith(".mkv") || n.endsWith(".avi") || n.endsWith(".mov")
            || n.endsWith(".webm") || n.endsWith(".m4v") || n.endsWith(".ts") || n.endsWith(".mpg")
            || n.endsWith(".mpeg") || n.endsWith(".wmv") || n.endsWith(".flv");
    }

    /** Biggest playable video among the wanted files (falls back to the biggest file). */
    private static int pickStreamFile(FileStorage fs, boolean[] want) {
        int best = -1, bestAny = -1;
        long bestSize = -1, bestAnySize = -1;
        for (int i = 0; i < fs.numFiles(); i++) {
            if (want != null && !want[i]) continue;
            long size = fs.fileSize(i);
            if (size > bestAnySize) { bestAnySize = size; bestAny = i; }
            if (size > bestSize && isVideo(fs.fileName(i))) { bestSize = size; best = i; }
        }
        return best >= 0 ? best : bestAny;
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

    /** Flags used when a magnet is added: metadata is fetched, payload is not. */
    private static torrent_flags_t metadataFlags() {
        return new torrent_flags_t().or_(TorrentFlags.DEFAULT_DONT_DOWNLOAD);
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
