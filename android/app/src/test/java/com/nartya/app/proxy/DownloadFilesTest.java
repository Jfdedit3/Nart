package com.nartya.app.proxy;

import org.junit.Test;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import static org.junit.Assert.*;

public class DownloadFilesTest {
    @Test public void rejectsWrongStartEndAndTruncatedRanges() {
        assertTrue(DownloadFiles.validRange("bytes 10-19/20", 10, -1));
        assertTrue(DownloadFiles.validRange("bytes 10-15/20", 10, 15));
        assertFalse(DownloadFiles.validRange("bytes 0-19/20", 10, -1));
        assertFalse(DownloadFiles.validRange("bytes 10-18/20", 10, -1));
        assertFalse(DownloadFiles.validRange("bytes 10-20/20", 10, -1));
        assertFalse(DownloadFiles.validRange("bytes 10-15/*", 10, 15));
        assertFalse(DownloadFiles.validRange("bytes 999999999999999999999-20/21", 0, 20));
    }
    @Test public void rejectsIncompleteAndEmptyFiles() throws Exception {
        assertThrows(IOException.class, () -> DownloadFiles.requireLength(9, 10));
        assertThrows(IOException.class, () -> DownloadFiles.requireLength(0, -1));
        DownloadFiles.requireLength(10, 10);
        DownloadFiles.requireLength(10, -1);
    }
    @Test public void failedFinalizationNeverReportsSuccess() throws Exception {
        File dir = Files.createTempDirectory("finalize").toFile();
        File source = new File(dir, "video.part");
        Files.write(source.toPath(), new byte[]{1, 2, 3});
        File blocked = new File(dir, "destination");
        assertTrue(blocked.mkdirs());
        Files.write(new File(blocked, "child").toPath(), new byte[]{1});
        assertThrows(IOException.class, () -> DownloadFiles.commit(source, blocked));
        assertTrue(source.isFile());
        File target = new File(dir, "video.mp4");
        DownloadFiles.commit(source, target);
        assertArrayEquals(new byte[]{1, 2, 3}, Files.readAllBytes(target.toPath()));
    }
}
