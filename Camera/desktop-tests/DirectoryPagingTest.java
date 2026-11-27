package io.github.bi2qfa.alphasync;

import java.io.File;
import java.util.HashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Verify a small initial page and larger continuation pages retain every file and the total count. */
public final class DirectoryPagingTest {
    public static void main(String[] args) throws Exception {
        int firstPageSize = args.length > 1 ? Integer.parseInt(args[1]) : 64;
        File root = new File(args[0]);
        File dcim = new File(root, "DCIM");
        if (!dcim.mkdirs()) throw new AssertionError("Use a fresh test directory");
        for (int index = 0; index < 400; index++) {
            new File(dcim, String.format("DSC%05d.JPG", index)).createNewFile();
        }
        new File(dcim, "SUBDIR").mkdir();
        PtpCameraHandler handler = new PtpCameraHandler(root, null, null, null);
        for (int direction = 0; direction < 2; direction++) {
            Set<String> names = new HashSet<String>();
            int offset = 0;
            do {
                String json = new String(handler.listDir("/DCIM", offset, offset == 0 ? firstPageSize : 256,
                    direction == 1), "UTF-8");
                check(number(json, "totalCount") == 401, "Total directory count missing or incorrect");
                int next = number(json, "nextOffset");
                check(next > offset, "Pagination did not advance");
                if (offset == 0) check(next == firstPageSize, "First page did not honor its smaller size");
                Matcher matcher = Pattern.compile("\"name\":\"([^\"]+)\"").matcher(json);
                while (matcher.find()) check(names.add(matcher.group(1)), "Duplicate item across pages");
                offset = next;
            } while (offset < 401);
            check(names.size() == 401 && names.contains("SUBDIR"), "Some directory items were lost");
        }
        System.out.println("PASS: " + firstPageSize + "-item initial page, 256-item continuations, all 401 items in both directions, totalCount");
    }
    private static int number(String json, String key) {
        Matcher matcher = Pattern.compile("\"" + key + "\":([0-9]+)").matcher(json);
        if (!matcher.find()) throw new AssertionError("Missing " + key);
        return Integer.parseInt(matcher.group(1));
    }
    private static void check(boolean passed, String message) {
        if (!passed) throw new AssertionError(message);
    }
}
