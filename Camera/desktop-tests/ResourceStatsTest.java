package io.github.bi2qfa.alphasync;

public final class ResourceStatsTest {
    private static void check(boolean ok) { if (!ok) throw new AssertionError(); }
    public static void main(String[] args) {
        long[] cpu = ResourceStats.cpu("cpu 10 20 30 40 50 60 70 80 999 999\ncpu0 0\n");
        check(cpu[0] == 360 && cpu[1] == 90);
        check(ResourceStats.cpu("") == null);
        check(ResourceStats.processTicks("123 (app name (worker)) S 0 0 0 0 0 0 0 0 0 0 42 8 0") == 50);
        check(ResourceStats.processTicks("bad") == -1);
        check(ResourceStats.kb("MemTotal: 123 kB\nVmRSS: 40 kB\n", "MemTotal") == 123 * 1024);
        check(ResourceStats.kb("MemTotal: invalid\n", "MemTotal") == -1);
        check(ResourceStats.kb("MemFree: 2 kB\n", "MemAvailable") == -1);
        check(ResourceStats.percent10(25, 100) == 250);
        check(ResourceStats.percent10(0, 0) == -1);
        check(ResourceStats.percent10(-1, 100) == -1);
        check(ResourceStats.percent10(200, 100) == -1);
        StringBuilder json = SJson.startObj();
        new ResourceStats().append(json);
        java.util.Map<String, Object> result = SJson.parse(SJson.endObj(json));
        check(((Long) result.get("resV")).longValue() == 1);
        check(((Long) result.get("cpuSystem10")).longValue() == -1);
        check(((Long) result.get("heapUsed")).longValue() >= 0);
        check(((Long) result.get("appRss")).longValue() == -1); // Windows has no /proc.
        System.out.println("ResourceStatsTest PASS: CPU counters, process names, unknown readings, JSON");
    }
}
