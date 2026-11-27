import java.io.File;
import java.io.FileOutputStream;
import java.io.ByteArrayOutputStream;
import java.awt.image.BufferedImage;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;
import javax.imageio.ImageIO;
import io.github.bi2qfa.alphasync.ThumbnailExtractor;

public class PreviewHandleLeakTest {
    public static void main(String[] args) throws Exception {
        File root = new File(args[0]);
        for (int length = 0; length < 8; length++) {
            File file = new File(root, "partial-" + System.nanoTime() + ".ARW");
            byte[] header = {'I', 'I', 42, 0, 8, 0, 0, 0};
            write(file, Arrays.copyOf(header, length));
            for (int i = 0; i < 32; i++) {
                if (ThumbnailExtractor.extractPreviewWithExif(file) != null)
                    throw new AssertionError("invalid preview accepted");
                if (ThumbnailExtractor.extractSmallWithExif(file) != null)
                    throw new AssertionError("invalid thumbnail accepted");
            }
            if (!file.delete()) throw new AssertionError("ARW handle leaked at header length " + length);
        }
        // A normal embedded JPEG must still be extracted after repeated failed opens.
        ByteArrayOutputStream encoded = new ByteArrayOutputStream();
        ImageIO.write(new BufferedImage(1600, 1000, BufferedImage.TYPE_INT_RGB), "jpg", encoded);
        byte[] jpg = encoded.toByteArray();
        ByteBuffer tiff = ByteBuffer.allocate(38 + jpg.length).order(ByteOrder.LITTLE_ENDIAN);
        tiff.put((byte)'I').put((byte)'I').putShort((short)42).putInt(8);
        tiff.putShort((short)2);
        tiff.putShort((short)0x201).putShort((short)4).putInt(1).putInt(38);
        tiff.putShort((short)0x202).putShort((short)4).putInt(1).putInt(jpg.length);
        tiff.putInt(0).put(jpg);
        File valid = new File(root, "valid-" + System.nanoTime() + ".ARW");
        write(valid, tiff.array());
        ThumbnailExtractor.PreviewBundle preview = ThumbnailExtractor.extractPreviewWithExif(valid);
        if (preview == null) throw new AssertionError("normal ARW preview regressed");
        if (!valid.delete()) throw new AssertionError("normal ARW handle leaked");
        System.out.println("PASS: truncated ARW handles released; normal embedded preview preserved");
    }

    private static void write(File file, byte[] bytes) throws Exception {
        FileOutputStream out = new FileOutputStream(file);
        try { out.write(bytes); } finally { out.close(); }
    }
}
