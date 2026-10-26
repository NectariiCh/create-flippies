package com.nectariich.create_flippies.content.computer.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Random;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * The world's file share (localfilesys.com) as the installed Debian sees it, through the headless
 * runner's {@code --share}: a file by its encoded name, a file added while the guest runs, the
 * folder redirect, a Range request, a link out of the share refused, and {@code wget -r} walking
 * the index pages.
 *
 * Tagged "linux": needs the image of guest/debian/build.sh (or FLIPPIES_DEBIAN_IMAGE), skipped
 * without it; opened read-only. The share, the COM1 transcript and the timings stay in
 * build/shared-folder.
 */
@Tag("linux")
class SharedFolderGuestTest {

    private static final Path IMAGE = Path.of(System.getProperty("flippies.debianImage",
            System.getenv().getOrDefault("FLIPPIES_DEBIAN_IMAGE", "guest/debian/out/debian.img")));
    private static final Path OUT = Path.of("build/shared-folder");
    private static final long EPOCH = 1_792_944_000L;
    private static final String PROMPT = "flip@flippies:~\\$ ";
    private static final byte[] BIG = new byte[300_000];

    static {
        new Random(7).nextBytes(BIG);
    }

    @Test
    @Timeout(value = 20, unit = TimeUnit.MINUTES)
    void theGuestFetchesTheShare() throws Exception {
        assumeTrue(Files.isRegularFile(IMAGE), IMAGE + " is not built (guest/debian/build.sh)");
        Path dir = LinuxDesktopTest.emptyFolder(OUT);
        Path share = Files.createDirectories(dir.resolve("localfilesys/sub dir"));
        Files.writeString(dir.resolve("localfilesys/hello world.txt"), "hello from the share\n");
        Files.writeString(share.resolve("über.txt"), "umlaut\n");
        Files.write(share.resolve("big.bin"), BIG);
        Files.writeString(dir.resolve("secret.txt"), "secret\n");
        Files.createSymbolicLink(dir.resolve("localfilesys/leak.txt"), dir.resolve("secret.txt").toAbsolutePath());

        LinuxDesktopTest.Guest guest = new LinuxDesktopTest.Guest(dir);
        Thread scenario = new Thread(() -> guest.play(g -> scenario(g, dir.resolve("localfilesys"))), "share-scenario");
        scenario.setDaemon(true);
        scenario.start();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        String[] args = {"--cpu", "68040", "--ram", "128M", "--hdd", IMAGE.toString(), "--readonly",
                "--share", dir.resolve("localfilesys").toString(), "--epoch", Long.toString(EPOCH), "--max-seconds", "900"};
        int status;
        try {
            status = HeadlessPc.run(args, InputStream.nullInputStream(), guest,
                    new PrintStream(err, true, StandardCharsets.UTF_8), guest);
        } finally {
            Files.writeString(dir.resolve("com1.txt"), guest.transcript(), StandardCharsets.ISO_8859_1);
            Files.writeString(dir.resolve("timings.txt"), guest.timings());
        }
        System.out.print(guest.timings());
        String runner = err.toString(StandardCharsets.UTF_8);
        assertTrue(guest.finished(), "the scenario did not finish:\n" + guest.tail());
        assertEquals(0, status, runner + "\n" + guest.tail());
    }

    private void scenario(LinuxDesktopTest.Guest g, Path share) throws IOException {
        g.expect("login: ", 180);
        g.mark("login: on COM1");
        g.sleep(1);
        g.type("flip\r");
        g.expect(PROMPT, 30);
        g.command("wget -qO- http://localfilesys.com/hello%20world.txt", "hello from the share\r?\n");
        g.command("wget -qO- http://localfilesys.com/sub%20dir/%C3%BCber.txt", "umlaut\r?\n");

        Files.writeString(share.resolve("later.txt"), "added while running\n");
        g.command("wget -qO- http://localfilesys.com/later.txt", "added while running\r?\n");

        g.command("wget -S -O /dev/null http://localfilesys.com/sub%20dir 2>&1 | grep -E '^ *(HTTP|Location)'",
                "HTTP/1.1 301 Moved Permanently\r?\n *Location: http://localfilesys.com/sub%20dir/\r?\n[\\s\\S]*HTTP/1.1 200 OK");
        // a resumed download: the first 1000 bytes there, wget -c asks for the rest with Range
        g.command("mkdir -p /tmp/r && cd /tmp/r && wget -qO- http://localfilesys.com/sub%20dir/big.bin | head -c 1000 > big.bin; "
                + "wget -S -c http://localfilesys.com/sub%20dir/big.bin 2>&1 | grep -E '^ *HTTP/'; md5sum big.bin; cd",
                "HTTP/1.1 206 Partial Content\r?\n" + md5(BIG) + " ");
        g.command("wget -qO- http://localfilesys.com/leak.txt; echo rc=$?", "rc=8\r?\n");

        g.command("rm -rf /tmp/got && wget -q -r -np -nH -P /tmp/got -R 'index.html*' http://localfilesys.com/; "
                + "find /tmp/got -type f | wc -l; md5sum '/tmp/got/sub dir/big.bin'", "[\r\n]4\r?\n" + md5(BIG) + " ");
        g.command("sudo poweroff", null);
    }

    private static String md5(byte[] b) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("MD5").digest(b));
        } catch (NoSuchAlgorithmException e) {
            throw new AssertionError(e);
        }
    }
}
