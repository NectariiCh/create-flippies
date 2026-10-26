package com.nectariich.create_flippies.content.computer.net;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** The HTTP/1.1 server of one connection (spec 12.4.2), fed bytes and read back as TCP would. */
class HttpSessionTest {

    private static final byte[] BIG = NetFrames.pattern(200_000, 3);
    private final MemoryContent content = new MemoryContent().put("get.flip/index.txt", "image /d.fdi 9 Debian\n")
            .put("get.flip/big.bin", BIG).put("get.flip/a b.txt", "spaced").putAll(DeflatedChunks.store("deb.flip/big.bin", BIG));
    private HttpSession http;
    private final TestClient reader = new TestClient();
    private long read;

    @BeforeEach
    void setUp() {
        http = new HttpSession(new Catalogue(content));
    }

    private void in(String text) {
        byte[] b = text.getBytes(StandardCharsets.ISO_8859_1);
        http.received(b, 0, b.length);
    }

    /** Everything new in the output stream, read in odd-sized pieces and acknowledged. */
    private void drain() throws IOException {
        byte[] buf = new byte[1000];
        while (read < http.end()) {
            int n = http.read(read, buf, 0, (int) Math.min(777, http.end() - read));
            assertTrue(n > 0);
            reader.stream.write(buf, 0, n);
            read += n;
            http.acked(read);
        }
    }

    private int at;

    private TestClient.HttpReply next(boolean head) throws IOException {
        drain();
        TestClient.HttpReply r = reader.response(at, head);
        assertTrue(r != null, "a whole response");
        at = r.end();
        return r;
    }

    private TestClient.HttpReply get(String path, String... headers) throws IOException {
        in("GET " + path + " HTTP/1.1\r\nHost: get.flip\r\n" + String.join("", headers) + "\r\n");
        return next(false);
    }

    @Test
    void getServesAFileWithItsLength() throws IOException {
        TestClient.HttpReply r = get("/index.txt");
        assertEquals(200, r.status());
        assertEquals("text/plain; charset=utf-8", r.header("content-type"));
        assertEquals("22", r.header("content-length"));
        assertEquals("bytes", r.header("accept-ranges"));
        assertNull(r.header("connection"), "keep-alive is the default in 1.1");
        assertEquals("image /d.fdi 9 Debian\n", new String(r.body(), StandardCharsets.UTF_8));
        assertFalse(http.closing());
        r = get("/big.bin");
        assertEquals("application/octet-stream", r.header("content-type"));
        assertArrayEquals(BIG, r.body(), "streamed across 64 KiB blocks");
        assertEquals(200, get("/a%20b.txt").status(), "percent-decoded");
        in("GET http://deb.flip/big.bin HTTP/1.1\r\nHost: get.flip\r\n\r\n");
        assertArrayEquals(BIG, next(false).body(), "absolute form names the host; deflated chunks inflate");
        in("GET /index.txt?x=1 HTTP/1.1\r\nHost: GET.FLIP:80\r\n\r\n");
        assertEquals(200, next(false).status(), "query dropped, port and case ignored in Host");
    }

    @Test
    void headSendsTheHeadersOnly() throws IOException {
        in("HEAD /big.bin HTTP/1.1\r\nHost: get.flip\r\n\r\n");
        TestClient.HttpReply r = next(true);
        assertEquals(200, r.status());
        assertEquals("200000", r.header("content-length"));
        in("HEAD /nothing HTTP/1.1\r\nHost: get.flip\r\n\r\n");
        assertEquals(404, next(true).status());
        assertEquals(read, http.end(), "no body after either");
        assertEquals(200, get("/index.txt").status(), "and the stream is still in step");
    }

    @Test
    void unknownPathsHostsAndDirectoriesAre404() throws IOException {
        for (String path : new String[] { "/nothing", "/", "/dists/", "/../get.flip/index.txt", "/./index.txt", "//index.txt" }) {
            TestClient.HttpReply r = get(path);
            assertEquals(404, r.status(), path);
            assertEquals("404 Not Found\n", new String(r.body(), StandardCharsets.US_ASCII));
        }
        in("GET /index.txt HTTP/1.1\r\nHost: nope.flip\r\n\r\n");
        assertEquals(404, next(false).status());
        assertFalse(http.closing(), "a 404 keeps the connection");
    }

    @Test
    void rangeRequests() throws IOException {
        TestClient.HttpReply r = get("/big.bin", "Range: bytes=70000-70009\r\n");
        assertEquals(206, r.status());
        assertEquals("bytes 70000-70009/200000", r.header("content-range"));
        assertEquals("10", r.header("content-length"));
        assertArrayEquals(Arrays.copyOfRange(BIG, 70000, 70010), r.body());
        r = get("/big.bin", "Range: bytes=131072-\r\n");
        assertArrayEquals(Arrays.copyOfRange(BIG, 131072, BIG.length), r.body(), "open end, a resumed download");
        r = get("/big.bin", "Range: bytes=-5\r\n");
        assertEquals("bytes 199995-199999/200000", r.header("content-range"));
        r = get("/big.bin", "Range: bytes=199990-999999999\r\n");
        assertEquals("bytes 199990-199999/200000", r.header("content-range"), "end clamped");
        r = get("/big.bin", "Range: bytes=200000-\r\n");
        assertEquals(416, r.status());
        assertEquals("bytes */200000", r.header("content-range"));
        assertEquals(0, r.body().length);
        for (String ignored : new String[] { "bytes=1-2,5-6", "bytes=9-3", "lines=1-2", "bytes=x-", "bytes=" }) {
            r = get("/big.bin", "Range: " + ignored + "\r\n");
            assertEquals(200, r.status(), ignored);
            assertEquals(BIG.length, r.body().length);
        }
        r = get("/big.bin", "Range: bytes=0-1\r\n", "If-Range: \"x\"\r\n");
        assertEquals(200, r.status(), "If-Range cannot match: we send no validators");
    }

    /**
     * What apt's http method sends for {@code apt update} and {@code apt install} (spec 12.8):
     * pipelined requests on one connection, Cache-Control and If-Modified-Since on indexes, a
     * resumed partial download with Range and If-Range, '+' and '~' in pool names percent-encoded,
     * and a 404 in the middle that keeps the connection. With no validators sent, every answer is
     * the whole file.
     */
    @Test
    void aptRequestsInOnePipeline() throws IOException {
        byte[] deb = NetFrames.pattern(5000, 7);
        http = new HttpSession(new Catalogue(new MemoryContent().put("deb.flip/dists/unstable/InRelease", "signed\n")
                .put("deb.flip/pool-m68k/main/m/motif/libxm4_2.3.8-5+b2_m68k.deb", deb)
                .put("deb.flip/pool/main/c/curl/curl_8.21.0~rc3-1_m68k.deb", deb)));
        String apt = "Host: deb.flip\r\nUser-Agent: Debian APT-HTTP/1.3 (3.1.11)\r\n";
        in("GET /dists/unstable/InRelease HTTP/1.1\r\n" + apt + "Cache-Control: max-age=0\r\n"
                + "If-Modified-Since: Sun, 25 Oct 2026 07:00:00 GMT\r\nAccept: text/*\r\n\r\n"
                + "GET /dists/unstable/main/binary-m68k/by-hash/SHA256/0123 HTTP/1.1\r\n" + apt + "\r\n"
                + "GET /pool-m68k/main/m/motif/libxm4_2.3.8-5%2bb2_m68k.deb HTTP/1.1\r\n" + apt
                + "Range: bytes=1000-\r\nIf-Range: Sun, 25 Oct 2026 07:00:00 GMT\r\n\r\n"
                + "GET /pool/main/c/curl/curl_8.21.0%7erc3-1_m68k.deb HTTP/1.1\r\n" + apt + "\r\n");
        TestClient.HttpReply r = next(false);
        assertEquals(200, r.status(), "no Last-Modified was sent, so If-Modified-Since cannot give 304");
        assertEquals("signed\n", new String(r.body(), StandardCharsets.US_ASCII));
        assertEquals(404, next(false).status());
        r = next(false);
        assertEquals(200, r.status(), "If-Range with a date we never sent: the whole file");
        assertArrayEquals(deb, r.body());
        assertArrayEquals(deb, next(false).body());
        assertFalse(http.closing());
    }

    @Test
    void rangeParsing() {
        assertArrayEquals(new long[] { 0, 9 }, HttpSession.range("bytes=0-9", 100));
        assertArrayEquals(new long[] { 90, 99 }, HttpSession.range("Bytes=90-", 100), "the unit is case-insensitive");
        assertArrayEquals(new long[] { 0, 99 }, HttpSession.range("bytes=-500", 100), "suffix longer than the file");
        assertArrayEquals(new long[] { -1, -1 }, HttpSession.range("bytes=-0", 100));
        assertArrayEquals(new long[] { -1, -1 }, HttpSession.range("bytes=0-", 0), "an empty file has no ranges");
        assertArrayEquals(new long[] { -1, -1 }, HttpSession.range("bytes=99999999999999999999-", 100));
        assertArrayEquals(new long[] { 5, 99 }, HttpSession.range("bytes=5-99999999999999999999", 100));
        assertNull(HttpSession.range(null, 100));
    }

    @Test
    void keepAliveAndClose() throws IOException {
        TestClient.HttpReply r = get("/index.txt", "Connection: keep-alive, Upgrade\r\n");
        assertFalse(http.closing());
        r = get("/index.txt", "Connection: close\r\n");
        assertEquals("close", r.header("connection"));
        assertTrue(http.closing());
        long end = http.end();
        in("GET /index.txt HTTP/1.1\r\nHost: get.flip\r\n\r\n");
        assertEquals(end, http.end(), "nothing after a closing response");
    }

    @Test
    void http10ClosesUnlessAskedToKeepAlive() throws IOException {
        in("GET /index.txt HTTP/1.0\r\nHost: get.flip\r\nConnection: keep-alive\r\n\r\n");
        assertEquals("keep-alive", next(false).header("connection"));
        assertFalse(http.closing());
        in("GET /index.txt HTTP/1.0\r\nHost: get.flip\r\n\r\n");
        assertEquals("close", next(false).header("connection"));
        assertTrue(http.closing());
    }

    @Test
    void pipelinedAndByteByByteRequests() throws IOException {
        in("GET /index.txt HTTP/1.1\r\nHost: get.flip\r\n\r\n\r\nHEAD /big.bin HTTP/1.1\nHost: get.flip\n\nGET /nothing HTTP/1.1\r\nHost: get.flip\r\n\r\n");
        assertEquals(200, next(false).status());
        assertEquals("200000", next(true).header("content-length"), "bare LF line ends work too");
        assertEquals(404, next(false).status());
        byte[] b = "GET /index.txt HTTP/1.1\r\nHost: get.flip\r\n\r\n".getBytes(StandardCharsets.US_ASCII);
        for (byte x : b)
            http.received(new byte[] { x }, 0, 1);
        assertEquals(200, next(false).status());
    }

    @Test
    void inputWaitsWhileTooManyAnswersAreUnacknowledged() throws IOException {
        String one = "GET /index.txt HTTP/1.1\r\nHost: get.flip\r\n\r\n";
        byte[] b = one.repeat(HttpSession.MAX_QUEUED + 8).getBytes(StandardCharsets.US_ASCII);
        int taken = http.received(b, 0, b.length);
        assertEquals(HttpSession.MAX_QUEUED * one.length(), taken, "the requests past the cap are left");
        assertEquals(0, http.received(b, taken, b.length - taken), "and still left while nothing is acknowledged");
        drain();
        assertEquals(b.length - taken, http.received(b, taken, b.length - taken), "taken once the answers are");
        for (int i = 0; i < HttpSession.MAX_QUEUED + 8; i++)
            assertEquals(200, next(false).status());
        in("GET /index.txt HTTP/1.1\r\nHost: get.flip\r\nConnection: close\r\n\r\n");
        assertEquals(5, http.received(b, 0, 5), "after the session closes, input is taken and ignored");
    }

    @Test
    void badRequests() throws IOException {
        in("GET /index.txt\r\n\r\n");
        assertEquals(400, next(false).status(), "no version");
        assertTrue(http.closing());
        String[] bad = { "GET /index.txt HTTP/1.1\r\n\r\n", "GET index.txt HTTP/1.1\r\nHost: get.flip\r\n\r\n",
                "GET /index.txt HTTP/1.1\r\nHost get.flip\r\n\r\n", "GET /index.txt HTTP/1.1\r\nHost: get.flip\r\n folded\r\n\r\n",
                "GET /a%2Fb HTTP/1.1\r\nHost: get.flip\r\n\r\n", "GET /a%zz HTTP/1.1\r\nHost: get.flip\r\n\r\n",
                "GET /index.txt HTTP/1.1\r\nHost: get.flip\r\nContent-Length: x\r\n\r\n" };
        for (String request : bad) {
            setUp();
            reader.stream.reset();
            at = 0;
            read = 0;
            in(request);
            TestClient.HttpReply r = next(false);
            assertEquals(400, r.status(), request);
            assertEquals("close", r.header("connection"));
            assertTrue(http.closing());
        }
        setUp();
        reader.stream.reset();
        at = 0;
        read = 0;
        in("GET /index.txt HTTP/2.0\r\nHost: get.flip\r\n\r\n");
        assertEquals(505, next(false).status());
    }

    @Test
    void otherMethodsAre501AndTheirBodiesSkipped() throws IOException {
        in("POST /index.txt HTTP/1.1\r\nHost: get.flip\r\nContent-Length: 5\r\n\r\nhelloGET /index.txt HTTP/1.1\r\nHost: get.flip\r\n\r\n");
        assertEquals(501, next(false).status());
        assertEquals(200, next(false).status(), "the body was skipped, the next request served");
        in("PUT /index.txt HTTP/1.1\r\nHost: get.flip\r\nTransfer-Encoding: chunked\r\n\r\n");
        assertEquals(501, next(false).status());
        assertTrue(http.closing(), "a chunked body cannot be skipped: close");
    }

    @Test
    void oversizedHeadersAre431() throws IOException {
        in("GET /index.txt HTTP/1.1\r\nHost: get.flip\r\nX: " + "a".repeat(HttpSession.MAX_HEAD) + "\r\n\r\n");
        assertEquals(431, next(false).status());
        assertTrue(http.closing());
    }

    @Test
    void finishedInputClosesAfterTheAnswers() throws IOException {
        in("GET /index.txt HTTP/1.1\r\nHost: get.flip\r\n\r\nGET /ind");
        http.inputEnded();
        assertTrue(http.closing());
        assertEquals(200, next(false).status());
        assertEquals(read, http.end(), "the half request is dropped");
    }

    @Test
    void outputIsReadByOffsetUntilAcknowledged() throws IOException {
        in("GET /big.bin HTTP/1.1\r\nHost: get.flip\r\n\r\n");
        byte[] a = new byte[100], b = new byte[100];
        long off = http.end() - BIG.length + 65530;
        assertEquals(6, http.read(off, a, 0, 100), "stops at the block boundary");
        assertEquals(100, http.read(off + 6, b, 0, 100));
        assertArrayEquals(Arrays.copyOfRange(BIG, 65536, 65636), b);
        assertEquals(10, http.read(0, a, 0, 10), "back to the head (a retransmission)");
        assertEquals("HTTP/1.1 2", new String(a, 0, 10, StandardCharsets.US_ASCII));
        assertEquals(http.end() - BIG.length - 5, http.read(5, new byte[300], 0, 300), "a head read stops at the body");
        assertEquals(0, http.read(http.end(), a, 0, 100));
        http.acked(http.end());
        assertThrows(IllegalArgumentException.class, () -> http.read(0, a, 0, 1));
        http.close();
    }

    @Test
    void decoding() {
        assertEquals("/a b/\u00FC", HttpSession.decode("/a%20b/%C3%BC"));
        assertEquals("/plain", HttpSession.decode("/plain"));
        assertNull(HttpSession.decode("/a%2f"));
        assertNull(HttpSession.decode("/a%00"));
        assertNull(HttpSession.decode("/a%2"));
    }
}
