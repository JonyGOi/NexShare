package com.nexshare;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;

/**
 * Starts ServerService on device boot so NexShare stays continuously available as a server.
 */
public class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null) return;
        String action = intent.getAction();
        if (Intent.ACTION_BOOT_COMPLETED.equals(action) ||
            "android.intent.action.QUICKBOOT_POWERON".equals(action) ||
            "com.htc.intent.action.QUICKBOOT_POWERON".equals(action)) {
            if (Settings.bootStartEnabled(context)) {
                Intent s = new Intent(context, ServerService.class);
                try {
                    if (Build.VERSION.SDK_INT >= 26) {
                        context.startForegroundService(s);
                    } else {
                        context.startService(s);
                    }
                } catch (Exception ignored) { }
            }
        }
    }
}
