package io.github.bi2qfa.alphasync;

import java.io.File;
import java.io.FileOutputStream;

/** Parser fixture with a 2.5 MiB preview, cached history and a camera-like 24 MiB heap. */
public final class JpgHeapPressureTest {
    private static byte[] appState;
    private static void zeros(FileOutputStream out, int count) throws Exception {
        byte[] block = new byte[4096];
        while (count > 0) { int n = Math.min(count, block.length); out.write(block, 0, n); count -= n; }
    }
    public static void main(String[] args) throws Exception {
        File file = new File(args[0], "heap-pressure-" + System.nanoTime() + ".JPG");
        FileOutputStream out = new FileOutputStream(file);
        try {
            out.write(new byte[]{(byte)255,(byte)216,(byte)255,(byte)225,0,22,
                'E','x','i','f',0,0,'I','I',42,0,8,0,0,0,0,0,0,0,0,0});
            zeros(out, 2 * 1024 * 1024);
            out.write(new byte[]{(byte)255,(byte)216,(byte)255,(byte)192,0,11,8,5,(byte)220,7,(byte)208,1,1,17,0,
                (byte)255,(byte)218,0,8,1,1,0,0,63,0});
            zeros(out, 5 * 1024 * 1024 / 2);
            out.write(new byte[]{(byte)255,(byte)217});
        } finally { out.close(); }
        try {
            appState = new byte[12 * 1024 * 1024];
            appState[0] = 7;
            ThumbPrefetcher cache = new ThumbPrefetcher(new File(args[0]));
            for (int i = 0; i < 16; i++) cache.putPreview("old-" + i, new byte[512 * 1024]);
            for (int i = 0; i < 4; i++) {
                ThumbnailExtractor.PreviewBundle result = ThumbnailExtractor.extractPreviewWithExif(file);
                if (result == null) throw new AssertionError("large JPG returned no preview under 24 MiB heap pressure\n" + AppLog.dump(8));
                byte[] bundle = ThumbnailExtractor.bundleJsonJpeg(result.jpeg, result.exifJson);
                cache.putPreview("new-" + i, bundle);
                if (bundle.length < 2 * 1024 * 1024) throw new AssertionError("unexpected preview length");
            }
            if (appState[0] != 7) throw new AssertionError();
            System.out.println("PASS: repeated 2.5 MiB JPG extraction and bundling with 12 MiB live state on 24 MiB heap");
        } finally { if (!file.delete()) throw new AssertionError("JPG handle leaked"); }
    }
}
