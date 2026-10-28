package com.nectariich.create_flippies.content.pc;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import java.util.zip.ZipFile;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The snapshot file (spec 12.19): what goes in comes out, disks chunk for chunk. */
class PcSnapshotTest {

    @TempDir
    Path dir;

    private static PcSnapshot.Manifest manifest(boolean state, UUID hdd, UUID floppy) {
        return new PcSnapshot.Manifest(PcSnapshot.FORMAT, "debian-desktop", "2026-10-28T12:00:00Z", "1.20.1", "forge",
                state, "Motorola 68040", 64L << 20, hdd, floppy);
    }

    private static CompoundTag caseTag() {
        CompoundTag part = new CompoundTag();
        part.putString("id", "create_flippies:pc_hdd");
        part.putString("Slot", "HDD");
        ListTag parts = new ListTag();
        parts.add(part);
        CompoundTag tag = new CompoundTag();
        tag.put("Parts", parts);
        return tag;
    }

    @Test
    void roundTrip() throws IOException {
        Path hdd = Files.createDirectories(dir.resolve("disks/hdd-id"));
        Files.write(hdd.resolve("0.bin"), new byte[] {1, 2, 3});
        Files.write(hdd.resolve("16383.bin"), new byte[] {9});
        Files.write(hdd.resolve("0.bin.tmp"), new byte[] {7});
        Files.write(hdd.resolve("notes.txt"), new byte[] {7});
        byte[] state = new byte[100_000];
        new java.util.Random(1).nextBytes(state);
        UUID hddId = UUID.randomUUID();
        Path file = dir.resolve("out/debian-desktop" + PcSnapshot.EXTENSION);

        PcSnapshot.write(file, manifest(true, hddId, null), caseTag(), state, hdd, dir.resolve("no-such-floppy"));

        try (ZipFile zip = new ZipFile(file.toFile())) {
            assertEquals(manifest(true, hddId, null), PcSnapshot.readManifest(zip));
            assertEquals(caseTag(), PcSnapshot.readCase(zip));
            assertArrayEquals(state, PcSnapshot.readState(zip));
            Path copy = dir.resolve("disks/new-id");
            PcSnapshot.extractDisk(zip, PcSnapshot.HDD, copy);
            assertArrayEquals(new byte[] {1, 2, 3}, Files.readAllBytes(copy.resolve("0.bin")));
            assertArrayEquals(new byte[] {9}, Files.readAllBytes(copy.resolve("16383.bin")));
            try (var files = Files.list(copy)) {
                assertEquals(2, files.count(), "only chunk files travel");
            }
            Path floppy = dir.resolve("disks/floppy-copy");
            PcSnapshot.extractDisk(zip, PcSnapshot.FLOPPY, floppy);
            try (var files = Files.list(floppy)) {
                assertEquals(0, files.count());
            }
        }
    }

    @Test
    void aPcThatWasOffHasNoState() throws IOException {
        Path file = dir.resolve("off" + PcSnapshot.EXTENSION);
        PcSnapshot.write(file, manifest(false, null, null), caseTag(), null, null, null);
        try (ZipFile zip = new ZipFile(file.toFile())) {
            assertFalse(PcSnapshot.readManifest(zip).hasState());
            assertNull(PcSnapshot.readState(zip));
        }
    }

    @Test
    void otherZipsAreRefused() throws IOException {
        Path file = dir.resolve("other.zip");
        try (var zip = new java.util.zip.ZipOutputStream(Files.newOutputStream(file))) {
            zip.putNextEntry(new java.util.zip.ZipEntry("readme.txt"));
            zip.closeEntry();
        }
        try (ZipFile zip = new ZipFile(file.toFile())) {
            assertThrows(IOException.class, () -> PcSnapshot.readManifest(zip));
        }
    }

    @Test
    void names() {
        assertTrue(PcSnapshot.validName("debian-desktop"));
        assertTrue(PcSnapshot.validName("boot_1.2"));
        assertFalse(PcSnapshot.validName(".hidden"));
        assertFalse(PcSnapshot.validName("../escape"));
        assertFalse(PcSnapshot.validName("a/b"));
        assertFalse(PcSnapshot.validName(""));
        assertFalse(PcSnapshot.validName("x".repeat(65)));
    }

    @Test
    void chunkFileNames() {
        assertEquals(0, PcSnapshot.chunkIndex("0.bin"));
        assertEquals(12, PcSnapshot.chunkIndex("12.bin"));
        assertEquals(-1, PcSnapshot.chunkIndex("012.bin"));
        assertEquals(-1, PcSnapshot.chunkIndex("1.bin.tmp"));
        assertEquals(-1, PcSnapshot.chunkIndex("-1.bin"));
    }
}
