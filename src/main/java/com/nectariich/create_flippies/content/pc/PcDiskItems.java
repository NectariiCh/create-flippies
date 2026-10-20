package com.nectariich.create_flippies.content.pc;

import java.util.UUID;

import net.minecraft.world.item.ItemStack;

/**
 * The identity a disk item carries (spec 11.2.3): {@code DiskId}, a UUID assigned
 * the first time it is installed or inserted. The image lives in the disk store
 * under that id, so the data travels with the item.
 */
public final class PcDiskItems {

    public static final String TAG_DISK_ID = "DiskId";

    private PcDiskItems() {}

    /** The disk's id, or null if it has never been used. */
    public static UUID idOf(ItemStack stack) {
        return stack.hasTag() && stack.getTag().hasUUID(TAG_DISK_ID) ? stack.getTag().getUUID(TAG_DISK_ID) : null;
    }

    /** The disk's id, assigning a fresh one first if it has none. */
    public static UUID assignId(ItemStack stack) {
        UUID id = idOf(stack);
        if (id == null) {
            id = UUID.randomUUID();
            stack.getOrCreateTag().putUUID(TAG_DISK_ID, id);
        }
        return id;
    }

    /** The first eight hex digits of the id, as the tooltip and the case screen show it. */
    public static String shortId(UUID id) {
        return id == null ? "-" : id.toString().substring(0, 8);
    }
}
