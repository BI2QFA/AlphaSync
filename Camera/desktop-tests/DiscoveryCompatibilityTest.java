package io.github.bi2qfa.alphasync;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Checks the actual phone source contract against the camera's discovery codec. */
public final class DiscoveryCompatibilityTest {
    public static void main(String[] args) throws Exception {
        File phoneSource = new File(args[0]);
        byte[] bytes = new byte[(int) phoneSource.length()];
        FileInputStream input = new FileInputStream(phoneSource);
        try {
            int offset = 0;
            while (offset < bytes.length) {
                int read = input.read(bytes, offset, bytes.length - offset);
                if (read < 0) throw new AssertionError("Truncated phone source");
                offset += read;
            }
        } finally { input.close(); }
        Matcher tag = Pattern.compile("const val VENDOR_TAG = \"([^\"]+)\"")
            .matcher(new String(bytes, "UTF-8"));
        check(tag.find(), "Missing phone discovery tag");
        String phoneTag = tag.group(1);
        check(phoneTag.equals(PtpCodec.VENDOR_TAG), "Phone/camera discovery tags differ: "
            + phoneTag + " vs " + PtpCodec.VENDOR_TAG);
        byte[] guid = new byte[16];
        String phoneName = "Xiaomi 13 Pro " + phoneTag;
        byte[] request = PtpCodec.probe(true, guid, phoneName, 0, 0, false, false);
        PtpCodec.Probe incoming = PtpCodec.parseProbe(PtpCodec.read(new ByteArrayInputStream(request)).body);
        check(PtpCodec.hasVendorTag(incoming.name), "Camera would send a compact response to the phone");
        String cameraName = "ILCE-7RM2 " + PtpCodec.VENDOR_TAG;
        byte[] response = PtpCodec.probe(false, guid, cameraName, 15741, 15742, true, false);
        PtpCodec.Probe decoded = PtpCodec.parseProbe(PtpCodec.read(new ByteArrayInputStream(response)).body);
        check(decoded.name.endsWith(phoneTag), "Phone would filter out camera response");
        check(decoded.protoPort == 15741 && decoded.filePort == 15742 && decoded.pairingMode,
            "Pairing status or fallback ports missing from extended response");
        System.out.println("PASS: matching discovery tags, phone request accepted, camera pairing response visible");
    }
    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
