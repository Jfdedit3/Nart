package com.nartya.app.proxy;

import android.util.AtomicFile;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import okhttp3.mockwebserver.Dispatcher;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import okio.Buffer;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.file.Files;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, manifest = Config.NONE)
public class DownloadsIntegrationTest {
    private MockWebServer server;
    private Downloads downloads;
    private File root;
    private CountDownLatch finished;
    private AtomicReference<JSONObject> result;
    @Before public void setup() throws Exception {
        server = new MockWebServer(); server.start();
        root = Files.createTempDirectory("downloads-integration").toFile();
        ProxyServer.setDownloadsRoot(root);
        finished = new CountDownLatch(1); result = new AtomicReference<>();
        downloads = new Downloads(root, item -> {
            if ("done".equals(item.optString("status")) || "error".equals(item.optString("status"))) {
                result.set(item); finished.countDown();
            }
        });
        downloads.setProxy(server.getPort(), "test");
    }
    @After public void cleanup() throws Exception { downloads.shutdown(); server.shutdown(); }
    private JSONObject payload(String id) throws Exception {
        return new JSONObject().put("id", id).put("slug", "test").put("seasonId", "1").put("ep", 1)
            .put("directUrl", "https://media.example/video.mp4");
    }
    private void awaitSuccess() throws Exception {
        assertTrue("transfer completed", finished.await(20, TimeUnit.SECONDS));
        assertEquals(result.get().optString("error"), "done", result.get().optString("status"));
    }
    @Test public void parallelFailureRestartsWithoutUsingSparseFileLength() throws Exception {
        byte[] video = new byte[9 * 1024 * 1024];
        for (int i = 0; i < video.length; i++) video[i] = (byte)(i % 251);
        server.setDispatcher(new Dispatcher() {
            @Override public MockResponse dispatch(RecordedRequest request) {
                String range = request.getHeader("Range");
                if ("bytes=0-1".equals(range)) return new MockResponse().setResponseCode(206)
                    .setHeader("Content-Range", "bytes 0-1/" + video.length).setHeader("ETag", "\"v1\"")
                    .setBody(new Buffer().write(video, 0, 2));
                if (range != null) return new MockResponse().setResponseCode(503).setBody("range unavailable");
                return new MockResponse().setHeader("ETag", "\"v1\"").setBody(new Buffer().write(video));
            }
        });
        assertTrue(downloads.start(payload("parallel"))); awaitSuccess();
        assertArrayEquals(video, Files.readAllBytes(new File(ProxyServer.itemDir("parallel"), "video.mp4").toPath()));
        assertFalse(new File(ProxyServer.itemDir("parallel"), "video.parallel.part").exists());
    }
    @Test public void changedValidatorDiscardsTheOldPrefix() throws Exception {
        byte[] video = new byte[4096]; java.util.Arrays.fill(video, (byte)7);
        File dir = ProxyServer.itemDir("etag"); assertTrue(dir.mkdirs());
        Files.write(new File(dir, "video.sequential.part").toPath(), new byte[]{99, 99, 99});
        DownloadFiles.writeText(new File(dir, "video.validator"), "\"old\"");
        DownloadFiles.writeText(new File(dir, "video.source"), DownloadFiles.identity("https://media.example/video.mp4"));
        server.setDispatcher(new Dispatcher() {
            @Override public MockResponse dispatch(RecordedRequest request) {
                if (request.getHeader("Range") != null) return new MockResponse().setResponseCode(206)
                    .setHeader("Content-Range", "bytes 3-4095/4096").setHeader("ETag", "\"new\"")
                    .setBody(new Buffer().write(video, 3, 4093));
                return new MockResponse().setHeader("ETag", "\"new\"").setBody(new Buffer().write(video));
            }
        });
        assertTrue(downloads.start(payload("etag"))); awaitSuccess();
        assertArrayEquals(video, Files.readAllBytes(new File(dir, "video.mp4").toPath()));
    }
    @Test public void identicalEtagsOnDifferentSourcesDoNotShareAPrefix() throws Exception {
        File dir = ProxyServer.itemDir("source"); assertTrue(dir.mkdirs());
        Files.write(new File(dir, "video.sequential.part").toPath(), new byte[]{99, 99, 99});
        DownloadFiles.writeText(new File(dir, "video.validator"), "\"same\"");
        DownloadFiles.writeText(new File(dir, "video.source"), DownloadFiles.identity("https://other.example/video.mp4"));
        server.setDispatcher(new Dispatcher() {
            @Override public MockResponse dispatch(RecordedRequest request) {
                String range = request.getHeader("Range");
                if ("bytes=0-1".equals(range)) return new MockResponse().setResponseCode(206)
                    .setHeader("ETag", "\"same\"").setHeader("Content-Range", "bytes 0-1/6").setBody("ne");
                if (range != null) return new MockResponse().setResponseCode(206)
                    .setHeader("ETag", "\"same\"").setHeader("Content-Range", "bytes 3-5/6").setBody("123");
                return new MockResponse().setHeader("ETag", "\"same\"").setBody("new123");
            }
        });
        assertTrue(downloads.start(payload("source"))); awaitSuccess();
        assertEquals("new123", DownloadFiles.readText(new File(dir, "video.mp4")));
    }

    @Test public void changingHlsSourcesNeverReusesTheOldFragments() throws Exception {
        File dir = ProxyServer.itemDir("hls"); assertTrue(dir.mkdirs());
        Files.write(new File(dir, "seg00000.ts").toPath(), new byte[]{99});
        DownloadFiles.writeText(new File(dir, "hls.fingerprint"), "old-source");
        server.setDispatcher(new Dispatcher() {
            @Override public MockResponse dispatch(RecordedRequest request) {
                String target = request.getRequestUrl().queryParameter("url");
                if (target != null && target.endsWith(".m3u8")) return new MockResponse().setBody("#EXTM3U\n#EXTINF:10,\nsegment.ts\n#EXT-X-ENDLIST\n");
                return new MockResponse().setBody("new-segment");
            }
        });
        assertTrue(downloads.start(payload("hls").put("directUrl", "https://media.example/master.m3u8")));
        awaitSuccess();
        assertEquals("new-segment", DownloadFiles.readText(new File(dir, "seg00000.ts")));
        assertEquals("playlist.m3u8", result.get().optString("file"));
    }

    @Test public void scanWorkersDownloadAllPagesNotOnlyTheFirstSix() throws Exception {
        server.setDispatcher(new Dispatcher() {
            @Override public MockResponse dispatch(RecordedRequest request) { return new MockResponse().setBody("image"); }
        });
        JSONObject scan = new JSONObject().put("id", "scan").put("type", "scan").put("slug", "test")
            .put("oeuvre", "book").put("folder", "1").put("pages", 19).put("imageBase", server.url("/").toString());
        assertTrue(downloads.start(scan)); awaitSuccess();
        assertTrue(Downloads.isScanChapterComplete(ProxyServer.itemDir("scan"), 19));
    }
    @Test public void interruptedIndexWriteRecoversThePreviousLibrary() throws Exception {
        JSONObject entry = new JSONObject().put("id", "saved").put("status", "paused");
        Files.write(new File(root, "index.json").toPath(), new JSONObject().put("saved", entry).toString().getBytes("UTF-8"));
        AtomicFile file = new AtomicFile(new File(root, "index.json"));
        FileOutputStream partial = file.startWrite(); partial.write("{broken".getBytes("UTF-8")); partial.close();
        Downloads recovered = new Downloads(root, null);
        try { assertEquals("saved", recovered.list().get(0).getString("id")); }
        finally { recovered.shutdown(); }
    }
    @Test public void pauseKeepsPartialDataAndDoesNotMarkItAsComplete() throws Exception {
        byte[] video = new byte[512 * 1024];
        server.setDispatcher(new Dispatcher() {
            @Override public MockResponse dispatch(RecordedRequest request) {
                return new MockResponse().setHeader("ETag", "\"v1\"").setBody(new Buffer().write(video))
                    .throttleBody(8192, 50, TimeUnit.MILLISECONDS);
            }
        });
        assertTrue(downloads.start(payload("pause")));
        File partial = new File(ProxyServer.itemDir("pause"), "video.sequential.part");
        long deadline = System.currentTimeMillis() + 5000;
        while ((!partial.exists() || partial.length() == 0) && System.currentTimeMillis() < deadline) Thread.sleep(20);
        assertTrue(downloads.pause("pause", "En pause"));
        deadline = System.currentTimeMillis() + 5000;
        while (!"paused".equals(downloads.list().get(0).optString("status")) && System.currentTimeMillis() < deadline) Thread.sleep(20);
        assertEquals("paused", downloads.list().get(0).optString("status"));
        assertTrue(partial.length() > 0);
        assertFalse(new File(partial.getParentFile(), "video.mp4").exists());
    }
}
