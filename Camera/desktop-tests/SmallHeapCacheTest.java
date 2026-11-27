package io.github.bi2qfa.alphasync;

import java.io.File;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Map;

/** Verify all retained byte caches remain bounded, including replacement accounting. */
public final class SmallHeapCacheTest {
    private static void exercise(Object owner, String methodName, String mapName, String sizeName) throws Exception {
        Method method = owner.getClass().getDeclaredMethod(methodName, String.class, byte[].class);
        method.setAccessible(true);
        Field field = owner.getClass().getDeclaredField(mapName); field.setAccessible(true);
        Field bytesField = owner.getClass().getDeclaredField(sizeName); bytesField.setAccessible(true);
        for (int i = 0; i < 16; i++) method.invoke(owner, "photo-" + i, new byte[400 * 1024]);
        method.invoke(owner, "photo-15", new byte[1024]);
        Map<?, ?> map = (Map<?, ?>) field.get(owner);
        long total = 0;
        for (Object value : map.values()) total += ((byte[]) value).length;
        if (total > 1024 * 1024 || total != bytesField.getLong(owner)) throw new AssertionError("cache byte budget/accounting");
        if (((byte[]) map.get("photo-15")).length != 1024) throw new AssertionError("replacement lost");
    }
    public static void main(String[] args) throws Exception {
        exercise(new ThumbPrefetcher(new File(args[0])), "cacheSmallLocked", "cache", "smallCacheBytes");
        exercise(new PtpCameraHandler(new File(args[0]), null, null, null), "cacheMemoLocked", "memo", "memoBytes");
        System.out.println("PASS: small preview and memo caches remain within 1 MiB each on a small heap");
    }
}
