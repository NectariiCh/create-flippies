package com.nectariich.create_flippies.content.design_workbench.blueprint;

import java.io.BufferedInputStream;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.zip.GZIPInputStream;

import com.nectariich.create_flippies.content.design_workbench.ControlDesign;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;

/**
 * Reading and writing the blueprint compounds that leave memory: packed for the
 * network, and kept on disk for the local section and the favourites.
 *
 * Packing is gzipped NBT. A blueprint is mostly repeated part names and keys, so it
 * shrinks several times over, which keeps a design with many lights and texts inside
 * the 32 KiB a client may send in one packet and keeps a whole world section's sync
 * small. Unpacking reads with a size budget, because the bytes come from the other
 * side and a small blob may inflate to gigabytes.
 *
 * Files are written aside and moved in, so a crash halfway through a save leaves the
 * old file rather than half a new one.
 */
public final class BlueprintFiles {

    /** How far a packed compound may inflate; far beyond any honest blueprint section. */
    public static final long UNPACK_BUDGET = 64L * 1024 * 1024;

    private BlueprintFiles() {}

    public static byte[] pack(CompoundTag tag) {
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            NbtIo.writeCompressed(tag, out);
            return out.toByteArray();
        } catch (IOException e) {
            // Writing to memory does not fail
            throw new IllegalStateException(e);
        }
    }

    /** The compound back from {@link #pack}, read within {@code budget} bytes of tags. */
    public static CompoundTag unpack(byte[] bytes, long budget) throws IOException {
        return read(new ByteArrayInputStream(bytes), budget);
    }

    private static CompoundTag read(InputStream in, long budget) throws IOException {
        try (DataInputStream data = new DataInputStream(new BufferedInputStream(new GZIPInputStream(in)))) {
            return NbtIo.read(data, new NbtAccounter(budget));
        } catch (RuntimeException e) {
            // The accounter throws a RuntimeException when the budget runs out
            throw new IOException(e.getMessage(), e);
        }
    }

    /** A compound file, or null when there is none yet. */
    public static CompoundTag readNbt(Path file) throws IOException {
        if (!Files.isRegularFile(file))
            return null;
        try (InputStream in = Files.newInputStream(file)) {
            return read(in, UNPACK_BUDGET);
        }
    }

    public static void writeNbt(Path file, CompoundTag tag) throws IOException {
        writeAtomic(file, pack(tag));
    }

    /** Where a store file lives in a folder of them, or null for a name that is not a hash. */
    public static Path blobFile(Path dir, String hash) {
        return ControlDesign.isHash(hash) ? dir.resolve(hash + ".bin") : null;
    }

    /** A store file from a folder of them, or null when it is not there. */
    public static byte[] readBlob(Path dir, String hash) {
        Path file = blobFile(dir, hash);
        if (file == null || !Files.isRegularFile(file))
            return null;
        try {
            return Files.readAllBytes(file);
        } catch (IOException e) {
            return null;
        }
    }

    /** Keeps a store file in a folder of them; one already there is left alone, since the hash names its bytes. */
    public static void writeBlob(Path dir, String hash, byte[] bytes) throws IOException {
        Path file = blobFile(dir, hash);
        if (file == null)
            throw new IOException("Not a hash: " + hash);
        if (!Files.isRegularFile(file))
            writeAtomic(file, bytes);
    }

    public static void writeAtomic(Path file, byte[] bytes) throws IOException {
        Files.createDirectories(file.getParent());
        Path temp = file.resolveSibling(file.getFileName() + ".tmp");
        Files.write(temp, bytes);
        try {
            Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            // Some file systems cannot replace atomically; a plain replace is the next best thing
            Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING);
        }
    }
}
