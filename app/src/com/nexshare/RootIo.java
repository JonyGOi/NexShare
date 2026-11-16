package com.nexshare;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.List;

/**
 * Provides root-backed filesystem operations when standard Java File APIs fail with permission denied,
 * allowing NexShare Root to access restricted folders like Android/data, Android/obb, and Android/media.
 */
public final class RootIo {

    public static class Entry {
        public final String name;
        public final boolean isDir;
        public final long size;
        public final long mtime;

        public Entry(String name, boolean isDir, long size, long mtime) {
            this.name = name;
            this.isDir = isDir;
            this.size = size;
            this.mtime = mtime;
        }
    }

    private RootIo() { }

    public static boolean isAvailable() {
        return Flavor.ROOT && RootShell.available();
    }

    /** Lists entries in a directory using root shell when Java File.listFiles() returns null. */
    public static List<Entry> listEntries(File dir) {
        if (!isAvailable()) return null;
        String p = dir.getAbsolutePath();
        String cmd = "for f in " + RootShell.q(p) + "/* " + RootShell.q(p) + "/.*; do "
            + "[ -e \"$f\" ] || continue; "
            + "bn=\"${f##*/}\"; "
            + "[ \"$bn\" = \".\" ] && continue; "
            + "[ \"$bn\" = \"..\" ] && continue; "
            + "if [ -d \"$f\" ]; then "
            + "echo \"D\t0\t0\t$bn\"; "
            + "else "
            + "echo \"F\t$(stat -c %s \"$f\" 2>/dev/null || echo 0)\t$(stat -c %Y \"$f\" 2>/dev/null || echo 0)\t$bn\"; "
            + "fi; "
            + "done";
        try {
            String out = RootShell.main().exec(cmd, 15000);
            List<Entry> list = new ArrayList<>();
            BufferedReader br = new BufferedReader(new StringReader(out));
            String line;
            while ((line = br.readLine()) != null) {
                if (line.isEmpty()) continue;
                String[] parts = line.split("\t", 4);
                if (parts.length >= 4) {
                    boolean isDir = "D".equals(parts[0]);
                    long size = 0;
                    long mtime = 0;
                    try { size = Long.parseLong(parts[1].trim()); } catch (Exception ignored) { }
                    try { mtime = Long.parseLong(parts[2].trim()) * 1000L; } catch (Exception ignored) { }
                    String name = parts[3];
                    list.add(new Entry(name, isDir, size, mtime));
                }
            }
            return list;
        } catch (Exception e) {
            return null;
        }
    }

    /** Opens an InputStream using root to stream a restricted file. */
    public static InputStream openInputStream(File f, long offset) throws IOException {
        String cmd;
        if (offset > 0) {
            cmd = "tail -c +" + (offset + 1) + " " + RootShell.q(f.getAbsolutePath());
        } else {
            cmd = "cat " + RootShell.q(f.getAbsolutePath());
        }
        Process p = new ProcessBuilder("su", "-c", cmd).start();
        return p.getInputStream();
    }

    public static boolean delete(File f) {
        if (!isAvailable()) return false;
        try {
            RootShell.main().exec("rm -rf " + RootShell.q(f.getAbsolutePath()), 10000);
            return !f.exists();
        } catch (Exception e) {
            return false;
        }
    }

    public static boolean mkdir(File f) {
        if (!isAvailable()) return false;
        try {
            RootShell.main().exec("mkdir -p " + RootShell.q(f.getAbsolutePath()), 5000);
            return f.exists() || f.isDirectory();
        } catch (Exception e) {
            return false;
        }
    }

    public static boolean move(File src, File dst) {
        if (!isAvailable()) return false;
        try {
            RootShell.main().exec("mv -f " + RootShell.q(src.getAbsolutePath()) + " " + RootShell.q(dst.getAbsolutePath()), 10000);
            return dst.exists();
        } catch (Exception e) {
            return false;
        }
    }
}
