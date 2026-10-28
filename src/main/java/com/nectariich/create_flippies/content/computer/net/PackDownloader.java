package com.nectariich.create_flippies.content.computer.net;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URLConnection;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Locale;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.regex.Pattern;

/**
 * Fetches a content pack named in the server config (spec 12.4.3) into the server's pack folder:
 * to a temporary file beside it, checked against its SHA-256 and its pack.json, then moved into
 * place in one step, so a half-downloaded or wrong pack is never scanned. Runs on a background
 * thread, never the server's. The temporary file is {@code <name>.part}, so a retry reuses it, and
 * one a stopped server or a crash left behind goes at the next start ({@link #removeStaleParts}).
 *
 * A pack already there wins: a file of the target name with the configured hash is not fetched
 * again, and one with another hash (dropped in by hand, or an older version) is kept and logged;
 * deleting it lets the next start fetch the configured one. Only https, unless http is allowed;
 * a redirect never changes the scheme (HttpURLConnection does not follow one that would).
 */
public final class PackDownloader {

    /** Waiting for the server to accept the connection, and for each read, at most. */
    public static final int CONNECT_TIMEOUT_MS = 30_000, READ_TIMEOUT_MS = 60_000;
    private static final Pattern NAME = Pattern.compile("[a-z0-9][a-z0-9._-]*\\" + PackContent.EXTENSION);
    private static final Pattern SHA256 = Pattern.compile("[0-9a-f]{64}");
    /** What a download in progress is called: the pack's file name and this. */
    static final String PART = ".part";

    private PackDownloader() {
    }

    /** One configured pack: the file name it takes in the pack folder, its SHA-256 (lower-case hex) and where it is fetched. */
    public record Source(String name, String sha256, URI url) {

        /**
         * From a config line {@code "<name> <sha256> <url>"}, white space separated, as
         * {@code "debian-os.flippack 3f2a... https://example.com/debian-os.flippack"}; IllegalArgumentException
         * saying what is wrong.
         */
        public static Source parse(String line, boolean allowHttp) {
            String[] f = line.trim().split("\\s+");
            if (f.length != 3)
                throw new IllegalArgumentException("needs \"<name> <sha256> <url>\": " + line);
            if (!NAME.matcher(f[0]).matches())
                throw new IllegalArgumentException("the name must be a lower-case file name ending in "
                        + PackContent.EXTENSION + ": " + f[0]);
            String sha = f[1].toLowerCase(Locale.ROOT);
            if (!SHA256.matcher(sha).matches())
                throw new IllegalArgumentException("the SHA-256 must be 64 hex digits: " + f[1]);
            URI url;
            try {
                url = new URI(f[2]);
            } catch (URISyntaxException e) {
                throw new IllegalArgumentException("not a URL: " + f[2]);
            }
            String scheme = url.getScheme() == null ? "" : url.getScheme().toLowerCase(Locale.ROOT);
            if (!scheme.equals("https") && !(allowHttp && scheme.equals("http")))
                throw new IllegalArgumentException("only https URLs" + (allowHttp ? " and http ones" : "") + " are fetched: " + f[2]);
            if (url.getHost() == null)
                throw new IllegalArgumentException("the URL names no host: " + f[2]);
            return new Source(f[0], sha, url);
        }
    }

    public enum Result {
        /** The target file is there with the configured hash. */
        PRESENT,
        /** The target file is there with another hash, and is kept. */
        KEPT,
        DOWNLOADED,
        FAILED
    }

    /**
     * Brings {@code source} into {@code folder}: one {@link Result}, and a line to {@code log} for
     * anything but PRESENT. {@code maxBytes} bounds the download; {@code cancelled} is asked between
     * reads (the server stopping). Never throws for a network or file problem.
     */
    public static Result fetch(Source source, Path folder, boolean allowHttp, long maxBytes,
            BooleanSupplier cancelled, Consumer<String> log) {
        Path target = folder.resolve(source.name());
        Path part = null;
        try {
            if (Files.exists(target)) {
                String have = sha256(target);
                if (have.equals(source.sha256()))
                    return Result.PRESENT;
                log.accept("Pack " + target + " is there with SHA-256 " + have + ", not the configured "
                        + source.sha256() + "; kept (a pack already there wins). Delete it to fetch " + source.url());
                return Result.KEPT;
            }
            Files.createDirectories(folder);
            part = folder.resolve(source.name() + PART);
            Files.deleteIfExists(part);
            String got = download(source.url(), part, allowHttp, maxBytes, cancelled);
            if (!got.equals(source.sha256()))
                throw new IOException("its SHA-256 is " + got + ", not the configured " + source.sha256());
            try (PackContent pack = PackContent.open(part)) {
                log.accept("Fetched pack " + pack.manifest().id() + " " + pack.manifest().version() + " (" + Files.size(part)
                        + " bytes) from " + source.url());
            } catch (IOException e) {
                throw new IOException("it is not a usable pack: " + e.getMessage(), e);
            }
            if (Files.exists(target)) {
                log.accept("Pack " + target + " appeared while it was being fetched; kept (a pack already there wins)");
                return Result.KEPT;
            }
            try {
                Files.move(part, target, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(part, target);
            }
            return Result.DOWNLOADED;
        } catch (IOException | RuntimeException e) {
            log.accept("Could not fetch pack " + source.name() + " from " + source.url() + ": " + e.getMessage());
            return Result.FAILED;
        } finally {
            if (part != null) {
                try {
                    Files.deleteIfExists(part);
                } catch (IOException e) {
                    // Left behind; the folder's scan skips .part files, the next start removes it
                }
            }
        }
    }

    /**
     * Deletes the unfinished downloads in {@code folder} (any {@code *.flippack*.part}), which a
     * server stopped or killed mid-download leaves: its daemon thread can end before it cleans up.
     * Call it before any download starts. Problems go to {@code log}; never throws.
     */
    public static void removeStaleParts(Path folder, Consumer<String> log) {
        if (!Files.isDirectory(folder))
            return;
        try (DirectoryStream<Path> parts = Files.newDirectoryStream(folder, "*" + PackContent.EXTENSION + "*" + PART)) {
            for (Path part : parts) {
                if (!Files.isRegularFile(part, LinkOption.NOFOLLOW_LINKS))
                    continue;
                try {
                    long size = Files.size(part);
                    Files.delete(part);
                    log.accept("Removed the unfinished pack download " + part + " (" + size + " bytes)");
                } catch (IOException e) {
                    log.accept("Could not remove the unfinished pack download " + part + ": " + e.getMessage());
                }
            }
        } catch (IOException e) {
            log.accept("Could not look for unfinished pack downloads in " + folder + ": " + e.getMessage());
        }
    }

    /** Streams {@code url} into {@code to}; the body's SHA-256 in lower-case hex. */
    private static String download(URI url, Path to, boolean allowHttp, long maxBytes, BooleanSupplier cancelled)
            throws IOException {
        URLConnection c = url.toURL().openConnection();
        if (!(c instanceof HttpURLConnection http))
            throw new IOException("not an http or https URL");
        http.setConnectTimeout(CONNECT_TIMEOUT_MS);
        http.setReadTimeout(READ_TIMEOUT_MS);
        http.setInstanceFollowRedirects(true);
        http.setUseCaches(false);
        http.setRequestProperty("User-Agent", "create_flippies pack downloader");
        try {
            int status = http.getResponseCode();
            String scheme = http.getURL().getProtocol().toLowerCase(Locale.ROOT);
            if (!scheme.equals("https") && !(allowHttp && scheme.equals("http")))
                throw new IOException("redirected to " + http.getURL());
            if (status != HttpURLConnection.HTTP_OK)
                throw new IOException("the server answered " + status + " " + http.getResponseMessage());
            long length = http.getContentLengthLong();
            if (length > maxBytes)
                throw new IOException("it is " + length + " bytes, more than the limit of " + maxBytes);
            MessageDigest digest = digest();
            long total = 0;
            byte[] buf = new byte[65536];
            try (InputStream in = http.getInputStream(); OutputStream out = Files.newOutputStream(to)) {
                for (int n; (n = in.read(buf)) > 0; ) {
                    if (cancelled.getAsBoolean())
                        throw new IOException("cancelled (the server is stopping)");
                    total += n;
                    if (total > maxBytes)
                        throw new IOException("it is more than the limit of " + maxBytes + " bytes");
                    digest.update(buf, 0, n);
                    out.write(buf, 0, n);
                }
            }
            if (length >= 0 && total != length)
                throw new IOException("the body ended after " + total + " of " + length + " bytes");
            return HexFormat.of().formatHex(digest.digest());
        } finally {
            http.disconnect();
        }
    }

    /** The SHA-256 of {@code file} in lower-case hex. */
    public static String sha256(Path file) throws IOException {
        MessageDigest digest = digest();
        byte[] buf = new byte[65536];
        try (InputStream in = Files.newInputStream(file)) {
            for (int n; (n = in.read(buf)) > 0; )
                digest.update(buf, 0, n);
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private static MessageDigest digest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("no SHA-256 in this Java", e);
        }
    }
}
