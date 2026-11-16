package com.nexshare;

import android.content.Context;

import java.io.File;

/**
 * Bundled command-line tools. BusyBox (one static binary with ~300 applets: ls, grep, sed,
 * awk, tar, vi, top, wget, nc, find, …) ships as a native lib so the root terminal has a
 * usable toolset without installing Termux. Termux's own packages are still used when present.
 */
public final class ToolBox {

    private ToolBox() { }

    /** The bundled busybox binary (extracted executable), or null if this build/ABI lacks it. */
    public static File busybox(Context ctx) {
        File f = new File(ctx.getApplicationInfo().nativeLibraryDir, "libbusybox.so");
        if (f.exists() && f.canExecute()) return f;

        File local = new File(new File(ctx.getFilesDir(), "bin"), "busybox");
        if (local.exists() && local.canExecute()) return local;

        tryExtractFromApk(ctx, local);
        if (local.exists() && local.canExecute()) return local;

        return f.exists() ? f : null;
    }

    private static void tryExtractFromApk(Context ctx, File target) {
        try {
            String apkPath = ctx.getApplicationInfo().sourceDir;
            if (apkPath == null) return;
            java.util.zip.ZipFile zip = new java.util.zip.ZipFile(apkPath);
            String[] abis = android.os.Build.SUPPORTED_ABIS;
            java.util.zip.ZipEntry found = null;
            if (abis != null) {
                for (String abi : abis) {
                    java.util.zip.ZipEntry e = zip.getEntry("lib/" + abi + "/libbusybox.so");
                    if (e != null) {
                        found = e;
                        break;
                    }
                }
            }
            if (found == null) {
                found = zip.getEntry("lib/arm64-v8a/libbusybox.so");
                if (found == null) found = zip.getEntry("lib/armeabi-v7a/libbusybox.so");
            }
            if (found != null) {
                target.getParentFile().mkdirs();
                try (java.io.InputStream in = zip.getInputStream(found);
                     java.io.OutputStream out = new java.io.FileOutputStream(target)) {
                    byte[] buf = new byte[65536];
                    int n;
                    while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                }
                target.setExecutable(true, false);
            }
            zip.close();
        } catch (Exception ignored) { }
    }

    public static boolean hasBusybox(Context ctx) {
        return busybox(ctx) != null;
    }

    /** Directory where busybox applet symlinks are installed, or null if busybox isn't bundled. */
    public static String busyboxBinDir(Context ctx) {
        if (busybox(ctx) == null) return null;
        return new File(ctx.getFilesDir(), "toolbox/bin").getAbsolutePath();
    }

    /**
     * Shell snippet (run before each command) that installs busybox applet symlinks once into
     * the bin dir. Cheap after the first run thanks to the existence guard.
     */
    public static String setupPrelude(Context ctx) {
        File bb = busybox(ctx);
        if (bb == null) return "";
        String bin = busyboxBinDir(ctx);
        return "__BB=" + RootShell.q(bb.getAbsolutePath()) + "; __BBIN=" + RootShell.q(bin) + "; "
            + "if [ ! -e \"$__BBIN/busybox\" ]; then mkdir -p \"$__BBIN\"; ln -sf \"$__BB\" \"$__BBIN/busybox\"; "
            + "\"$__BBIN/busybox\" --install -s \"$__BBIN\" 2>/dev/null; fi; ";
    }
}
