import io.github.bi2qfa.alphasync.ThumbnailExtractor;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.util.Arrays;
import java.util.Random;
import javax.imageio.ImageIO;

public class JpgPreviewWindowTest {
    public static void main(String[] args) throws Exception {
        BufferedImage image = new BufferedImage(2000, 1500, BufferedImage.TYPE_INT_RGB);
        Random random = new Random(1);
        for (int y = 0; y < image.getHeight(); y++)
            for (int x = 0; x < image.getWidth(); x++) image.setRGB(x, y, random.nextInt());
        ByteArrayOutputStream encoded = new ByteArrayOutputStream();
        ImageIO.write(image, "jpg", encoded);
        byte[] preview = encoded.toByteArray();
        if (preview.length <= 1024 * 1024) throw new AssertionError("fixture must cross old scan window");
        File file = new File(args[0], "large-preview-" + System.nanoTime() + ".JPG");
        FileOutputStream out = new FileOutputStream(file);
        try {
            // EXIF/TIFF IFD0 with no JPEG pointer: the normal tail-scanning fallback is needed.
            out.write(new byte[]{(byte)255,(byte)216,(byte)255,(byte)225,0,22,
                'E','x','i','f',0,0,'I','I',42,0,8,0,0,0,0,0,0,0,0,0});
            out.write(new byte[2 * 1024 * 1024]);
            out.write(preview);
        } finally { out.close(); }
        try {
            ThumbnailExtractor.PreviewBundle bundle = ThumbnailExtractor.extractPreviewWithExif(file);
            if (bundle == null || !Arrays.equals(preview, bundle.jpeg))
                throw new AssertionError("JPG preview SOI outside 1 MB tail window was lost (" + preview.length + " bytes)");
            ByteArrayOutputStream tiny = new ByteArrayOutputStream();
            ImageIO.write(new BufferedImage(320, 240, BufferedImage.TYPE_INT_RGB), "jpg", tiny);
            FileOutputStream append = new FileOutputStream(file, true);
            try { append.write(tiny.toByteArray()); } finally { append.close(); }
            bundle = ThumbnailExtractor.extractPreviewWithExif(file);
            if (bundle == null || !Arrays.equals(preview, bundle.jpeg))
                throw new AssertionError("small JPEG must not mask a larger preview outside first scan window");
            FileOutputStream plain = new FileOutputStream(file);
            try { plain.write(preview); } finally { plain.close(); }
            bundle = ThumbnailExtractor.extractPreviewWithExif(file);
            if (bundle == null || !Arrays.equals(preview, bundle.jpeg))
                throw new AssertionError("missing EXIF must not suppress valid JPEG fallback");
            FileOutputStream partial = new FileOutputStream(file);
            try { partial.write(Arrays.copyOf(preview, 100)); } finally { partial.close(); }
            if (ThumbnailExtractor.extractPreviewWithExif(file) != null)
                throw new AssertionError("incomplete JPEG accepted");
        } finally { if (!file.delete()) throw new AssertionError("JPG handle leaked"); }
        System.out.println("PASS: large JPG, small candidate, missing EXIF, incomplete JPEG, handle closure");
    }
}
