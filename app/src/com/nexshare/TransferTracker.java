package com.nexshare;

import android.content.Context;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Global tracker for active file transfers across HTTP WebApi, WebDAV, and DownloadStation.
 * Guarantees that updates, server restarts, or maintenance tasks never interrupt in-flight transfers.
 */
public final class TransferTracker {

    private static final AtomicInteger activeTransfers = new AtomicInteger(0);
    private static volatile long lastTransferEndTime = 0;

    private TransferTracker() { }

    public static void onTransferStart() {
        activeTransfers.incrementAndGet();
        ServerService.markActivity();
    }

    public static void onTransferEnd() {
        int count = activeTransfers.decrementAndGet();
        if (count < 0) {
            activeTransfers.set(0);
        }
        lastTransferEndTime = System.currentTimeMillis();
        ServerService.markActivity();
    }

    public static int getActiveCount() {
        return Math.max(0, activeTransfers.get());
    }

    public static long getLastTransferEndTime() {
        return lastTransferEndTime;
    }

    /**
     * Checks if any file transfers are currently occurring or ended very recently (safety cooldown).
     * @param ctx Android Context to query DownloadManager
     * @param quietWindowMs Time in ms of silence required after the last transfer (e.g. 15000 ms)
     * @return true if busy, false if completely idle
     */
    public static boolean isBusy(Context ctx, long quietWindowMs) {
        if (activeTransfers.get() > 0) return true;
        // Torrent Player keeps ffmpeg writing HLS segments while the user watches.
        // That is a live transfer even when the torrent itself already finished, so
        // an auto-update must not restart the app in the middle of playback.
        if (LiveHls.hasActiveSessions()) return true;
        if (ctx != null) {
            try {
                if (DownloadManager.get(ctx).hasActiveTransfers()) return true;
            } catch (Exception ignored) { }
        }
        if (lastTransferEndTime > 0 && (System.currentTimeMillis() - lastTransferEndTime) < quietWindowMs) {
            return true;
        }
        return false;
    }

    public static boolean isBusy(Context ctx) {
        return isBusy(ctx, 15000); // 15-second grace period for batch file transfers
    }
}
