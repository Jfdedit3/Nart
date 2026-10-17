package com.nartya.app.storage;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, manifest = Config.NONE)
public class DownloadPolicyTest {
    @Test public void quotaIncludesPartialFilesWithoutDeletingThem() throws Exception {
        File root = Files.createTempDirectory("policy").toFile();
        File partial = new File(root, "video.part");
        Files.write(partial.toPath(), new byte[]{1, 2, 3});
        DownloadPolicy policy = new DownloadPolicy(RuntimeEnvironment.getApplication(), root);
        policy.configure(false, 2);
        assertEquals(3, policy.usedBytes());
        assertThrows(IOException.class, () -> policy.check(0));
        assertTrue(partial.exists());
        policy.configure(false, 3);
        policy.check(0);
        policy.configure(false, 0);
        policy.check(100);
    }
    @Test public void refusesTransfersWhenDeviceReserveWouldBeUsed() throws Exception {
        File root = new File(Files.createTempDirectory("full-disk").toFile().getPath()) {
            @Override public long getUsableSpace() { return DownloadPolicy.RESERVE_BYTES - 1; }
        };
        DownloadPolicy policy = new DownloadPolicy(RuntimeEnvironment.getApplication(), root);
        policy.configure(false, 0);
        assertThrows(IOException.class, () -> policy.check(0));
    }
    @Test public void wifiOnlyRejectsAnUnvalidatedNetwork() throws Exception {
        DownloadPolicy policy = new DownloadPolicy(RuntimeEnvironment.getApplication(), Files.createTempDirectory("wifi").toFile());
        policy.configure(true, 0);
        assertThrows(DownloadPolicy.WifiRequired.class, () -> policy.check(0));
    }
}
