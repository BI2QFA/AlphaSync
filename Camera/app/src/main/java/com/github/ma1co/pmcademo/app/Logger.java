package com.github.ma1co.pmcademo.app;

import android.os.Environment;

import java.io.*;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public class Logger {
    private static final SimpleDateFormat TIME_FMT = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US);
    private static final int BUFFER_FLUSH_CHARS = 2048;
    private static final StringBuilder s_pending = new StringBuilder();

    public static File getFile() {
        return new File(Environment.getExternalStorageDirectory(), "PMCADEMO/LOG.TXT");
    }

    public static synchronized void flush() {
        if (s_pending.length() == 0) {
            return;
        }
        BufferedWriter writer = null;
        try {
            File f = getFile();
            if (f.getParentFile() != null) {
                f.getParentFile().mkdirs();
            }
            writer = new BufferedWriter(new FileWriter(f, true));
            writer.write(s_pending.toString());
            s_pending.setLength(0);
        } catch (IOException ignored) {
        } finally {
            if (writer != null) {
                try {
                    writer.close();
                } catch (IOException ignored) {}
            }
        }
    }

    protected static synchronized void log(String msg) {
        logLine(msg, false);
    }

    private static synchronized void logLine(String msg, boolean flushNow) {
        s_pending.append(msg).append('\n');
        if (flushNow || s_pending.length() >= BUFFER_FLUSH_CHARS) {
            flush();
        }
    }

    protected static void log(String type, String msg) {
        log(type, msg, false);
    }

    private static void log(String type, String msg, boolean flushNow) {
        String ts;
        synchronized (Logger.class) {
            ts = TIME_FMT.format(new Date());
        }
        logLine("[" + ts + "] [" + type + "] " + msg, flushNow);
    }

    public static void info(String msg) { log("INFO", msg, false); }
    public static void error(String msg) { log("ERROR", msg, true); }
}
