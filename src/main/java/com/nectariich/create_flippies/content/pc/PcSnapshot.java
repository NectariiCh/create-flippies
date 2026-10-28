package com.nectariich.create_flippies.content.pc;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.nectariich.create_flippies.compat.NbtCompat;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtIo;

/**
 * A PC snapshot file, {@code <name>.flipsnap} (spec 12.19): a zip holding
 * <ul>
 * <li>{@code snapshot.json}, the {@link Manifest};</li>
 * <li>{@code case.nbt}, the case's parts as its block entity saves them (plain NBT);</li>
 * <li>{@code state.bin}, {@code Machine.saveState()}, only when the PC was on;</li>
 * <li>{@code disks/hdd/<chunk>.bin} and {@code disks/floppy/<chunk>.bin}, the disks' chunk files
 * exactly as the disk store keeps them (each a deflated 64 KiB chunk).</li>
 * </ul>
 * Everything but the manifest and the case is compressed already, so it is STORED.
 */
public final class PcSnapshot {

    public static final int FORMAT = 1;
    public static final String EXTENSION = ".flipsnap";
    /** A snapshot name: a file name without the extension, no path and no leading dot. */
    public static final Pattern NAME = Pattern.compile("[A-Za-z0-9_-][A-Za-z0-9_.-]{0,63}");
    public static final String HDD = "hdd";
    public static final String FLOPPY = "floppy";

    private static final String MANIFEST = "snapshot.json";
    private static final String CASE = "case.nbt";
    private static final String STATE = "state.bin";
    private static final String DISKS = "disks/";
    /** The case NBT is a few KiB; this is the most it may claim when read. */
    private static final long CASE_QUOTA = 16L * 1024 * 1024;

    /**
     * What a snapshot says about itself.
     *
     * @param minecraft the game version it was taken on; item NBT and the machine differ between
     *                  versions, so it is placed only on the same one
     * @param hdd       the id the HDD had when it was taken (its chunks are under {@code disks/hdd/}),
     *                  or null without one; likewise {@code floppy}
     */
    public record Manifest(int format, String name, String created, String minecraft, String loader, boolean hasState,
            String cpu, long ramBytes, UUID hdd, UUID floppy) {

        JsonObject toJson() {
            JsonObject o = new JsonObject();
            o.addProperty("format", format);
            o.addProperty("name", name);
            o.addProperty("created", created);
            o.addProperty("minecraft", minecraft);
            o.addProperty("loader", loader);
            o.addProperty("state", hasState);
            o.addProperty("cpu", cpu);
            o.addProperty("ramBytes", ramBytes);
            if (hdd != null)
                o.addProperty(HDD, hdd.toString());
            if (floppy != null)
                o.addProperty(FLOPPY, floppy.toString());
            return o;
        }

        static Manifest fromJson(JsonObject o) {
            return new Manifest(o.get("format").getAsInt(), o.get("name").getAsString(), string(o, "created"),
                    o.get("minecraft").getAsString(), string(o, "loader"), o.get("state").getAsBoolean(),
                    string(o, "cpu"), o.has("ramBytes") ? o.get("ramBytes").getAsLong() : 0, uuid(o, HDD),
                    uuid(o, FLOPPY));
        }

        private static String string(JsonObject o, String key) {
            return o.has(key) ? o.get(key).getAsString() : "";
        }

        private static UUID uuid(JsonObject o, String key) {
            return o.has(key) ? UUID.fromString(o.get(key).getAsString()) : null;
        }
    }

    private PcSnapshot() {}

    public static boolean validName(String name) {
        return name != null && NAME.matcher(name).matches();
    }

    /**
     * Writes a snapshot to {@code target}, replacing it. {@code hddDir} and {@code floppyDir} are
     * the disks' folders in the disk store (null, or missing, for no disk or one never written).
     */
    public static void write(Path target, Manifest manifest, CompoundTag caseTag, byte[] state, Path hddDir,
            Path floppyDir) throws IOException {
        Files.createDirectories(target.toAbsolutePath().getParent());
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(target))) {
            put(zip, MANIFEST, manifest.toJson().toString().getBytes(StandardCharsets.UTF_8), true);
            ByteArrayOutputStream nbt = new ByteArrayOutputStream();
            try (DataOutputStream out = new DataOutputStream(nbt)) {
                NbtIo.write(caseTag, out);
            }
            put(zip, CASE, nbt.toByteArray(), true);
            if (state != null)
                put(zip, STATE, state, false);
            putDisk(zip, HDD, hddDir);
            putDisk(zip, FLOPPY, floppyDir);
        }
    }

    private static void putDisk(ZipOutputStream zip, String role, Path dir) throws IOException {
        if (dir == null || !Files.isDirectory(dir))
            return;
        List<Path> chunks = new ArrayList<>();
        try (Stream<Path> files = Files.list(dir)) {
            files.filter(f -> chunkIndex(f.getFileName().toString()) >= 0).sorted().forEach(chunks::add);
        }
        for (Path chunk : chunks)
            put(zip, DISKS + role + "/" + chunk.getFileName(), Files.readAllBytes(chunk), false);
    }

    private static void put(ZipOutputStream zip, String name, byte[] bytes, boolean deflate) throws IOException {
        ZipEntry entry = new ZipEntry(name);
        if (!deflate) {
            CRC32 crc = new CRC32();
            crc.update(bytes);
            entry.setMethod(ZipEntry.STORED);
            entry.setSize(bytes.length);
            entry.setCompressedSize(bytes.length);
            entry.setCrc(crc.getValue());
        }
        zip.putNextEntry(entry);
        zip.write(bytes);
        zip.closeEntry();
    }

    /** "123.bin" is chunk 123; anything else is -1. */
    static int chunkIndex(String fileName) {
        if (!fileName.endsWith(".bin"))
            return -1;
        try {
            int n = Integer.parseInt(fileName.substring(0, fileName.length() - 4));
            return n >= 0 && String.valueOf(n).equals(fileName.substring(0, fileName.length() - 4)) ? n : -1;
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    public static Manifest readManifest(ZipFile zip) throws IOException {
        ZipEntry entry = zip.getEntry(MANIFEST);
        if (entry == null)
            throw new IOException("not a PC snapshot (no " + MANIFEST + ")");
        try (InputStream in = zip.getInputStream(entry)) {
            Manifest manifest = Manifest.fromJson(JsonParser.parseString(
                    new String(in.readNBytes(64 * 1024), StandardCharsets.UTF_8)).getAsJsonObject());
            if (manifest.format() != FORMAT)
                throw new IOException("snapshot format " + manifest.format() + " is not " + FORMAT);
            return manifest;
        } catch (RuntimeException e) {
            throw new IOException("the snapshot's " + MANIFEST + " is broken: " + e.getMessage(), e);
        }
    }

    public static CompoundTag readCase(ZipFile zip) throws IOException {
        ZipEntry entry = zip.getEntry(CASE);
        if (entry == null)
            throw new IOException("the snapshot has no " + CASE);
        try (DataInputStream in = new DataInputStream(zip.getInputStream(entry))) {
            return NbtIo.read(in, NbtCompat.accounter(CASE_QUOTA));
        }
    }

    /** The machine state, or null for a snapshot of a PC that was off. */
    public static byte[] readState(ZipFile zip) throws IOException {
        ZipEntry entry = zip.getEntry(STATE);
        if (entry == null)
            return null;
        try (InputStream in = zip.getInputStream(entry)) {
            return in.readAllBytes();
        }
    }

    /** Copies one disk's chunk files into {@code targetDir} (the new disk's folder in the disk store). */
    public static void extractDisk(ZipFile zip, String role, Path targetDir) throws IOException {
        String prefix = DISKS + role + "/";
        Files.createDirectories(targetDir);
        Enumeration<? extends ZipEntry> entries = zip.entries();
        while (entries.hasMoreElements()) {
            ZipEntry entry = entries.nextElement();
            if (!entry.getName().startsWith(prefix))
                continue;
            String file = entry.getName().substring(prefix.length());
            if (chunkIndex(file) < 0)
                continue;
            try (InputStream in = zip.getInputStream(entry); OutputStream out = Files.newOutputStream(
                    targetDir.resolve(file))) {
                in.transferTo(out);
            }
        }
    }
}
