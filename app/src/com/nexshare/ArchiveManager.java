package com.nexshare;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Enumeration;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * ZIP browsing and selective extraction. Torrents that contain a ZIP (common for game
 * repacks) are fully downloaded first, then the user picks only the files/folders to extract.
 */
public final class ArchiveManager {

    private static final Map<String, Job> JOBS = new ConcurrentHashMap<>();
    private static final ExecutorService WORKER = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "nexshare:unzip");
        t.setDaemon(true);
        return t;
    });

    private ArchiveManager() { }

    public static boolean isArchive(String name) {
        String n = name == null ? "" : name.toLowerCase(Locale.ROOT);
        return n.endsWith(".zip") || n.endsWith(".cbz");
    }

    /** Lists every entry without extracting anything. */
    public static JSONArray list(File zip) throws IOException {
        JSONArray out = new JSONArray();
        try (ZipFile zf = new ZipFile(zip)) {
            Enumeration<? extends ZipEntry> en = zf.entries();
            while (en.hasMoreElements()) {
                ZipEntry e = en.nextElement();
                JSONObject o = new JSONObject();
                put(o, "name", e.getName());
                put(o, "dir", e.isDirectory());
                put(o, "size", Math.max(0, e.getSize()));
                put(o, "compressed", Math.max(0, e.getCompressedSize()));
                put(o, "mtime", e.getTime());
                out.put(o);
            }
        }
        return out;
    }

    /** Starts a background extraction. Empty selection means every file. */
    public static Job start(File zip, File dest, List<String> selected) throws IOException {
        if (!zip.isFile()) throw new IOException("arquivo ZIP não encontrado");
        if (!isArchive(zip.getName())) throw new IOException("não é um arquivo ZIP");
        if (dest == null) dest = zip.getParentFile();
        if (!dest.exists() && !dest.mkdirs()) throw new IOException("não foi possível criar a pasta de destino");
        if (!dest.isDirectory()) throw new IOException("destino não é uma pasta");
        Job job = new Job(zip, dest, selected);
        JOBS.put(job.id, job);
        // Keep the map small on very old devices.
        if (JOBS.size() > 20) {
            List<Job> finished = new ArrayList<>();
            for (Job j : JOBS.values()) if (j.finished()) finished.add(j);
            finished.sort(Comparator.comparingLong(j -> j.started));
            for (int i = 0; i < finished.size() - 10; i++) JOBS.remove(finished.get(i).id);
        }
        WORKER.submit(job);
        return job;
    }

    public static Job get(String id) {
        return id == null ? null : JOBS.get(id);
    }

    public static void cancel(String id) {
        Job j = get(id);
        if (j != null) j.cancelled = true;
    }

    private static void put(JSONObject o, String k, Object v) {
        try { o.put(k, v); } catch (Exception ignored) { }
    }

    public static final class Job implements Runnable {
        public final String id = UUID.randomUUID().toString().substring(0, 8);
        public final File zip;
        public final File dest;
        public final long started = System.currentTimeMillis();
        private final List<String> selected;
        public volatile String state = "preparing";
        public volatile long total;
        public volatile long done;
        public volatile int extracted;
        public volatile int skipped;
        public volatile String current = "";
        public volatile String error = "";
        public volatile boolean cancelled;

        Job(File zip, File dest, List<String> selected) {
            this.zip = zip;
            this.dest = dest;
            this.selected = selected == null ? Collections.emptyList() : new ArrayList<>(selected);
        }

        public boolean finished() {
            return "done".equals(state) || "error".equals(state) || "cancelled".equals(state);
        }

        public JSONObject toJson() {
            JSONObject o = new JSONObject();
            put(o, "id", id);
            put(o, "state", state);
            put(o, "zip", zip.getAbsolutePath());
            put(o, "dest", dest.getAbsolutePath());
            put(o, "total", total);
            put(o, "done", done);
            put(o, "extracted", extracted);
            put(o, "skipped", skipped);
            put(o, "current", current);
            put(o, "error", error);
            put(o, "progress", total > 0 ? Math.min(100.0, done * 100.0 / total) : (finished() ? 100.0 : 0.0));
            return o;
        }

        @Override
        public void run() {
            String destRoot;
            try {
                destRoot = dest.getCanonicalPath();
            } catch (IOException e) {
                state = "error";
                error = e.getMessage();
                return;
            }
            try (ZipFile zf = new ZipFile(zip)) {
                List<? extends ZipEntry> entries = Collections.list(zf.entries());
                // Selection contains exact entry names; a directory entry selects its subtree.
                List<ZipEntry> todo = new ArrayList<>();
                long sum = 0;
                for (ZipEntry e : entries) {
                    if (e.isDirectory()) continue;
                    if (matches(e.getName())) {
                        todo.add(e);
                        if (e.getSize() > 0) sum += e.getSize();
                    }
                }
                total = sum;
                state = "extracting";
                for (ZipEntry e : todo) {
                    if (cancelled) { state = "cancelled"; return; }
                    current = e.getName();
                    String entryName = e.getName().replace('\\', '/');
                    File out = new File(dest, entryName).getCanonicalFile();
                    if (!out.getPath().equals(destRoot) && !out.getPath().startsWith(destRoot + File.separator)) {
                        skipped++;
                        continue;
                    }
                    File parent = out.getParentFile();
                    if (parent != null && !parent.exists() && !parent.mkdirs()) { skipped++; continue; }
                    if (out.exists() && out.isDirectory()) { skipped++; continue; }
                    try (InputStream in = zf.getInputStream(e);
                         OutputStream os = new FileOutputStream(out)) {
                        byte[] buf = new byte[65536];
                        int n;
                        while ((n = in.read(buf)) > 0) {
                            if (cancelled) { state = "cancelled"; return; }
                            os.write(buf, 0, n);
                            done += n;
                        }
                    }
                    if (e.getTime() > 0) out.setLastModified(e.getTime());
                    extracted++;
                }
                state = "done";
            } catch (Throwable e) {
                state = "error";
                error = e.getMessage() == null ? e.toString() : e.getMessage();
            }
        }

        private boolean matches(String name) {
            if (selected.isEmpty()) return true;
            for (String s : selected) {
                if (s == null || s.isEmpty()) continue;
                if (name.equals(s)) return true;
                if (s.endsWith("/") && name.startsWith(s)) return true;
                if (name.startsWith(s + "/")) return true;
            }
            return false;
        }
    }
}
