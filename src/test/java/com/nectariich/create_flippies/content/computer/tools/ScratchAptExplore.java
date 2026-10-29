package com.nectariich.create_flippies.content.computer.tools;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/** TEMPORARY exploration harness: runs build/apt-explore/commands.txt lines ("SECONDS command" or "SHOT name"). */
@Tag("scratch")
class ScratchAptExplore {

    @Test
    @Timeout(value = 120, unit = TimeUnit.MINUTES)
    void explore() throws Exception {
        Path base = Path.of("build/apt-explore");
        org.junit.jupiter.api.Assumptions.assumeTrue("1".equals(System.getProperty("explore.run")));
        List<String> commands = Files.readAllLines(base.resolve("commands.txt"));
        Path dir = LinuxDesktopTest.emptyFolder(base.resolve("run"));
        Path web = dir.resolve("web");
        Files.createDirectories(web);
        Files.createSymbolicLink(web.resolve("deb.flip"), Path.of("guest/apt/out/archive").toAbsolutePath());
        Path files = Path.of("build/apt-explore/files");
        if (Files.isDirectory(files))
            Files.createSymbolicLink(web.resolve("get.flip"), files.toAbsolutePath());
        LinuxDesktopTest.Guest guest = new LinuxDesktopTest.Guest(dir);
        Thread t = new Thread(() -> guest.play(g -> {
            g.expect("login: ", 200);
            g.sleep(1);
            g.type("flip\r");
            g.expect("flip@flippies:~\\$ ", 30);
            g.mark("login");
            for (String line : commands) {
                if (line.isBlank() || line.startsWith("#"))
                    continue;
                if (line.startsWith("SHOT ")) {
                    g.save(g.screen(), line.substring(5).trim() + ".png");
                    continue;
                }
                if (line.startsWith("SLEEP ")) {
                    g.sleep(Double.parseDouble(line.substring(6).trim()));
                    continue;
                }
                int sp = line.indexOf(' ');
                double limit = Double.parseDouble(line.substring(0, sp));
                String cmd = line.substring(sp + 1);
                g.type(cmd + "\r");
                try {
                    g.expect("flip@flippies:~\\$ ", limit);
                    g.mark("done: " + cmd);
                } catch (AssertionError e) {
                    g.mark("TIMEOUT: " + cmd);
                    g.type("\u0003");
                    g.expect("flip@flippies:~\\$ ", 30);
                }
                Files.writeString(dir.resolve("com1.txt"), g.transcript(), StandardCharsets.ISO_8859_1);
                Files.writeString(dir.resolve("timings.txt"), g.timings());
            }
            g.type("sudo poweroff\r");
        }), "explore");
        t.setDaemon(true);
        t.start();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        String[] args = {"--cpu", "68040", "--ram", "128M", "--hdd", AptArchiveTest.sparseCopy(Path.of("guest/debian/out/debian.img"), dir.resolve("hdd.img")).toString(), "--sync-seconds", "10",
                "--catalogue", web.toString(), "--epoch", "1792944000", "--max-seconds",
                System.getProperty("explore.max", "20000")};
        try {
            HeadlessPc.run(args, InputStream.nullInputStream(), guest, new PrintStream(err, true, StandardCharsets.UTF_8), guest);
        } finally {
            Files.writeString(dir.resolve("com1.txt"), guest.transcript(), StandardCharsets.ISO_8859_1);
            Files.writeString(dir.resolve("runner.txt"), err.toString(StandardCharsets.UTF_8));
            Files.writeString(dir.resolve("timings.txt"), guest.timings());
            System.out.printf(Locale.ROOT, "%s%n", guest.timings());
        }
    }
}
