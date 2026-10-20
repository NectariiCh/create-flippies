package com.nectariich.create_flippies.content.data_cord;

import java.util.UUID;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtUtils;

/**
 * One end's view of a data cord: where the other end is and which cord joins them.
 *
 * Both endpoint block entities keep one of these per linked port (spec 11.2.5), so
 * the per-tick exchange works from block entities alone, without loading the
 * cord entity's chunk. {@code cordId} is the entity's; a link whose peer does not
 * hold the same id back is stale and is cleared.
 */
public record DataLink(BlockPos peerPos, int peerPort, UUID cordId) {

    public CompoundTag write() {
        CompoundTag tag = new CompoundTag();
        tag.put("Peer", NbtUtils.writeBlockPos(peerPos));
        tag.putInt("Port", peerPort);
        tag.putUUID("Cord", cordId);
        return tag;
    }

    /** The link in {@code tag}, or null if it is not a whole one. */
    public static DataLink read(CompoundTag tag) {
        if (!tag.contains("Peer") || !tag.hasUUID("Cord"))
            return null;
        return new DataLink(NbtUtils.readBlockPos(tag.getCompound("Peer")), tag.getInt("Port"), tag.getUUID("Cord"));
    }

    /** Whether {@code other}, held at {@code otherPos} port {@code otherPort}, is this link's other half. */
    public boolean pairsWith(BlockPos ownPos, int ownPort, DataLink other) {
        return other != null && other.cordId.equals(cordId) && other.peerPos.equals(ownPos)
                && other.peerPort == ownPort;
    }
}
