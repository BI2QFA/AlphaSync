package io.github.bi2qfa.alphasync;

import java.io.File;
import java.io.IOException;

/** Run against the production PairingStore, without Android or mocked file IO. */
public final class PairingPersistenceTest {
    private static class Store extends PairingStore {
        int barriers;
        boolean failSync;
        Store(File dir) { super(dir); }
        protected void syncStorage() throws IOException {
            barriers++;
            if (failSync) throw new IOException("injected storage sync failure");
            super.syncStorage();
        }
    }
    public static void main(String[] args) throws Exception {
        File dir = new File(args[0]);
        if (!dir.mkdirs()) throw new AssertionError("Use a fresh test directory");
        Store first = new Store(dir);
        String cameraId = first.deviceIdHex();
        PairingStore.Paired original = new PairingStore.Paired();
        original.peerDeviceId = "0102030405060708";
        original.peerName = "Xiaomi 13 Pro";
        first.upsert(original);
        PairingStore.Paired repair = new PairingStore.Paired();
        repair.peerDeviceId = "a1a2a3a4a5a6a7a8";
        repair.peerName = original.peerName;
        first.upsert(repair);
        check(first.lastError() == null, "Pairing writes must succeed");
        check(first.barriers >= 2, "Both pairings must run a storage barrier");
        for (int i = 0; i < 20; i++) {
            PairingStore reloaded = new PairingStore(dir);
            check(reloaded.size() == 2, "Original and repaired phone records must both survive reload");
            check(reloaded.find(original.peerDeviceId) != null, "Original phone identity changed");
            check(reloaded.find(repair.peerDeviceId) != null, "Repaired phone identity changed");
            check(cameraId.equals(reloaded.deviceIdHex()), "Camera identity changed");
        }
        first.remove(original.peerDeviceId);
        PairingStore removed = new PairingStore(dir);
        check(removed.size() == 1 && removed.find(repair.peerDeviceId) != null,
            "Removal must preserve the other phone record");
        first.failSync = true;
        first.upsert(original);
        check(first.lastError() != null && first.lastError().contains("injected"),
            "A failed storage barrier must not report a successful save");
        check(first.find(original.peerDeviceId) == null,
            "Failed pairing must not remain authorized only in memory");
        check(!new File(dir, "pairings.json.tmp").exists(), "Temporary file leaked");
        System.out.println("PASS: two same-name phone identities, 20 reloads, stable camera identity, removal, sync failure");
    }
    private static void check(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }
}
