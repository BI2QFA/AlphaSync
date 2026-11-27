package io.github.bi2qfa.alphasync;

import java.io.File;

/** Exercise the production cache under a constrained heap, with large JPG-sized entries. */
public class PreviewCacheBudgetTest {
    public static void main(String[] args) {
        ThumbPrefetcher cache = new ThumbPrefetcher(new File(args[0]));
        for (int i = 0; i < 64; i++) {
            byte[] bytes = new byte[2 * 1024 * 1024];
            bytes[0] = (byte)i;
            cache.putPreview("photo-" + i, bytes);
            if (cache.lookupPreview("photo-" + i) != bytes)
                throw new AssertionError("recent preview not retained");
        }
        if (cache.lookupPreview("photo-0") != null) throw new AssertionError("old preview retained");
        byte[] replacement = new byte[1024];
        cache.putPreview("photo-63", replacement);
        if (cache.lookupPreview("photo-63") != replacement) throw new AssertionError("replacement lost");
        System.out.println("PASS: 64 large previews and replacement with heap limit "
                + Runtime.getRuntime().maxMemory() / (1024 * 1024) + " MiB");
    }
}
