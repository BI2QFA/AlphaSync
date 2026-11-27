package io.github.bi2qfa.alphasync;

import java.io.BufferedReader;
import java.io.FileReader;

/** Small heartbeat-only sampler. No image allocation, worker or SD-card access. */
public final class ResourceStats {
    private long[] previous;
    private long previousProcess = -1;

    static String read(String path) {
        BufferedReader reader = null;
        try {
            reader = new BufferedReader(new FileReader(path), 1024);
            StringBuilder text = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null && text.length() < 16384) {
                text.append(line).append('\n');
            }
            return text.toString();
        } catch (Exception ignored) { return ""; }
        finally { if (reader != null) try { reader.close(); } catch (Exception ignored) {} }
    }

    static long[] cpu(String text) {
        try {
            String[] fields = text.split("\n", 2)[0].trim().split("\\s+");
            if (!"cpu".equals(fields[0]) || fields.length < 5) return null;
            long total = 0, idle = 0;
            // guest counters are already included in user/nice.
            for (int i = 1; i < fields.length && i <= 8; i++) {
                long n = Long.parseLong(fields[i]);
                total += n;
                if (i == 4 || i == 5) idle += n;
            }
            return new long[] {total, idle};
        } catch (Exception ignored) { return null; }
    }

    static long processTicks(String text) {
        try {
            int end = text.lastIndexOf(')');
            if (end < 0) return -1;
            String[] fields = text.substring(end + 1).trim().split("\\s+");
            return Long.parseLong(fields[11]) + Long.parseLong(fields[12]);
        } catch (Exception ignored) { return -1; }
    }

    static long kb(String text, String key) {
        try {
            String[] lines = text.split("\n");
            for (int i = 0; i < lines.length; i++) {
                if (lines[i].startsWith(key + ":")) {
                    long n = Long.parseLong(lines[i].substring(key.length() + 1).trim().split("\\s+")[0]);
                    return n < 0 ? -1 : n * 1024;
                }
            }
        } catch (Exception ignored) {}
        return -1;
    }

    static int percent10(long amount, long total) {
        if (total <= 0 || amount < 0 || amount > total) return -1;
        return (int) Math.round(amount * 1000.0 / total);
    }

    public synchronized void append(StringBuilder json) {
        long[] current = cpu(read("/proc/stat"));
        long process = processTicks(read("/proc/self/stat"));
        int systemCpu = -1, appCpu = -1;
        if (current != null && previous != null) {
            long delta = current[0] - previous[0];
            long idle = current[1] - previous[1];
            if (idle >= 0 && idle <= delta) systemCpu = percent10(delta - idle, delta);
            if (process >= 0 && previousProcess >= 0) appCpu = percent10(process - previousProcess, delta);
        }
        previous = current;
        previousProcess = process;
        String memory = read("/proc/meminfo");
        long total = kb(memory, "MemTotal"), available = kb(memory, "MemAvailable");
        boolean estimated = available < 0;
        if (estimated) {
            long free = kb(memory, "MemFree"), buffers = kb(memory, "Buffers"), cached = kb(memory, "Cached");
            if (free >= 0 && buffers >= 0 && cached >= 0) available = free + buffers + cached;
        }
        long used = total > 0 && available >= 0 ? total - Math.min(total, available) : -1;
        Runtime runtime = Runtime.getRuntime();
        SJson.member(json, "resV", 1);
        SJson.member(json, "cpuSystem10", systemCpu);
        SJson.member(json, "cpuApp10", appCpu);
        SJson.member(json, "memTotal", total);
        SJson.member(json, "memUsed", used);
        SJson.member(json, "memEstimated", estimated);
        SJson.member(json, "appRss", kb(read("/proc/self/status"), "VmRSS"));
        SJson.member(json, "heapUsed", runtime.totalMemory() - runtime.freeMemory());
        SJson.member(json, "heapMax", runtime.maxMemory());
    }
}
