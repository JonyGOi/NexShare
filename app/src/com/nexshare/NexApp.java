package com.nexshare;

import android.app.Application;

import java.io.File;
import java.io.FileWriter;
import java.io.PrintWriter;
import java.util.Date;

/** Keeps the last Java crash on disk so the web UI can show it after a restart. */
public class NexApp extends Application {
    @Override
    public void onCreate() {
        super.onCreate();
        final Thread.UncaughtExceptionHandler previous = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler((thread, error) -> {
            try {
                File out = new File(getFilesDir(), "last-crash.txt");
                try (PrintWriter p = new PrintWriter(new FileWriter(out, false))) {
                    p.println(new Date().toString());
                    p.println("thread=" + thread.getName());
                    error.printStackTrace(p);
                }
            } catch (Throwable ignored) { }
            if (previous != null) previous.uncaughtException(thread, error);
        });
    }
}
