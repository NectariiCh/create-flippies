package com.nectariich.create_flippies.content.pc;

import java.io.IOException;
import java.io.InputStream;

import com.nectariich.create_flippies.content.computer.device.DiskImage;
import com.nectariich.create_flippies.content.computer.device.PagedDiskImage;

import net.minecraft.nbt.CompoundTag;

/**
 * Floppies that come with the mod (spec 12.5): a floppy item carrying {@code Preset: "name"}
 * gets the image bundled at {@code data/create_flippies/floppies/<name>.img} copied into a new
 * disk the first time it goes into a drive, and is an ordinary floppy from then on. The image is
 * read from the class path, so it ships in the jar. Pure, so it is tested without a world.
 */
public final class FloppyPresets {

    public static final String NETINSTALL = "netinstall";
    private static final String FOLDER = "data/create_flippies/floppies/";
    private static final int SECTOR = 512;

    private FloppyPresets() {}

    /** The class path resource of a preset; IllegalArgumentException unless the name is [a-z0-9_]+. */
    public static String resource(String preset) {
        if (preset == null || preset.isEmpty() || !preset.chars().allMatch(c -> c >= 'a' && c <= 'z'
                || c >= '0' && c <= '9' || c == '_'))
            throw new IllegalArgumentException("not a preset name: " + preset);
        return FOLDER + preset + ".img";
    }

    /**
     * The preset's image from {@code loader}, or null when it is not bundled. IOException when it
     * cannot be read or does not fit a 1.44 MB floppy.
     */
    public static byte[] load(ClassLoader loader, String preset) throws IOException {
        try (InputStream in = loader.getResourceAsStream(resource(preset))) {
            if (in == null)
                return null;
            byte[] image = in.readNBytes((int) PagedDiskImage.FLOPPY_CAPACITY + 1);
            if (image.length > PagedDiskImage.FLOPPY_CAPACITY)
                throw new IOException(resource(preset) + " is larger than a floppy");
            return image;
        }
    }

    /** Writes {@code image} from sector 0, the last sector padded with zeros. */
    public static void copyInto(DiskImage disk, byte[] image) {
        int sectors = (image.length + SECTOR - 1) / SECTOR;
        if (sectors == 0)
            return;
        byte[] padded = image.length == sectors * SECTOR ? image : java.util.Arrays.copyOf(image, sectors * SECTOR);
        disk.write(0, sectors, padded, 0);
    }

    /**
     * A preset disk's item tag after its image was copied or its write-protect tab moved: one that
     * has its {@code DiskId} and is writable is the player's own from then on, so it loses
     * {@code Preset} and its name stops naming content it may no longer hold. Its id and data stay.
     */
    static void settle(CompoundTag tag) {
        if (tag != null && !tag.getBoolean(FloppyDiskItem.TAG_WRITE_PROTECT) && tag.hasUUID(PcDiskItems.TAG_DISK_ID))
            tag.remove(FloppyDiskItem.TAG_PRESET);
    }
}
