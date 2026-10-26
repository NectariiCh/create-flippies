package com.nectariich.create_flippies.content.pc;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.IOException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Random;
import java.util.UUID;

import com.nectariich.create_flippies.content.computer.device.PagedDiskImage;

import net.minecraft.nbt.CompoundTag;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** A preset floppy's bundled image, read from the class path and copied onto a new disk (docs/PC_SPEC.md 12.5). */
class FloppyPresetsTest {

    @TempDir
    Path dir;

    private ClassLoader withImage(String name, byte[] image) throws IOException {
        Path p = dir.resolve("data/create_flippies/floppies").resolve(name + ".img");
        Files.createDirectories(p.getParent());
        Files.write(p, image);
        return new URLClassLoader(new URL[] {dir.toUri().toURL()}, null);
    }

    private static PagedDiskImage blankFloppy() {
        return new PagedDiskImage(null, PagedDiskImage.FLOPPY_CAPACITY, chunk -> null, 4);
    }

    @Test
    void aSmallImageIsCopiedFromSectorZeroAndTheRestStaysZero() throws IOException {
        byte[] image = new byte[70_000];
        new Random(3).nextBytes(image);
        byte[] loaded = FloppyPresets.load(withImage("test_boot", image), "test_boot");
        assertArrayEquals(image, loaded);

        PagedDiskImage disk = blankFloppy();
        FloppyPresets.copyInto(disk, loaded);
        byte[] back = new byte[137 * 512];
        disk.read(0, 137, back, 0);
        assertArrayEquals(image, java.util.Arrays.copyOf(back, image.length));
        for (int i = image.length; i < back.length; i++)
            assertEquals(0, back[i], "the last sector is padded with zeros");
        byte[] last = new byte[512];
        disk.read(2879, 1, last, 0);
        assertArrayEquals(new byte[512], last);
    }

    @Test
    void aWholeFloppyFitsAndALargerImageIsRefused() throws IOException {
        byte[] whole = new byte[(int) PagedDiskImage.FLOPPY_CAPACITY];
        whole[whole.length - 1] = 42;
        PagedDiskImage disk = blankFloppy();
        FloppyPresets.copyInto(disk, FloppyPresets.load(withImage("whole", whole), "whole"));
        byte[] last = new byte[512];
        disk.read(2879, 1, last, 0);
        assertEquals(42, last[511]);

        ClassLoader loader = withImage("big", new byte[whole.length + 1]);
        assertThrows(IOException.class, () -> FloppyPresets.load(loader, "big"));
    }

    @Test
    void anUnbundledPresetIsNullAndBadNamesAreRefused() throws IOException {
        ClassLoader loader = withImage("x", new byte[1]);
        assertNull(FloppyPresets.load(loader, FloppyPresets.NETINSTALL));
        assertEquals("data/create_flippies/floppies/netinstall.img", FloppyPresets.resource("netinstall"));
        for (String bad : new String[] {"", "../x", "Net", "a/b", "a.b"})
            assertThrows(IllegalArgumentException.class, () -> FloppyPresets.resource(bad), bad);
        assertThrows(IllegalArgumentException.class, () -> FloppyPresets.load(loader, null));
    }

    /** The item's tag after {@code presetStack} and, when {@code inserted}, its first insert. */
    private static CompoundTag presetTag(boolean inserted, boolean writeProtected) {
        CompoundTag tag = new CompoundTag();
        tag.putString("Preset", FloppyPresets.NETINSTALL);
        tag.putBoolean("WriteProtect", writeProtected);
        if (inserted)
            tag.putUUID(PcDiskItems.TAG_DISK_ID, UUID.randomUUID());
        return tag;
    }

    @Test
    void aCopiedPresetDiskLosesItsPresetOnceWritable() {
        // Write-protected after its copy: still the netinstall floppy
        CompoundTag tag = presetTag(true, true);
        FloppyPresets.settle(tag);
        assertEquals(FloppyPresets.NETINSTALL, tag.getString("Preset"));
        // Never inserted, so not copied yet: it keeps the preset its first insert copies, writable or not
        tag = presetTag(false, false);
        FloppyPresets.settle(tag);
        assertEquals(FloppyPresets.NETINSTALL, tag.getString("Preset"));
        // Copied and writable: the player's own disk, named as a blank one; id kept
        tag = presetTag(true, false);
        UUID id = tag.getUUID(PcDiskItems.TAG_DISK_ID);
        FloppyPresets.settle(tag);
        assertFalse(tag.contains("Preset"));
        assertEquals(id, tag.getUUID(PcDiskItems.TAG_DISK_ID));
        FloppyPresets.settle(null);
    }
}
