package com.nectariich.create_flippies.content.data_cord;

import java.util.UUID;

import net.minecraft.nbt.CompoundTag;

/**
 * One end's view of a data cord: where the other end is and which cord joins them.
 *
 * Both endpoint hosts keep one of these per linked port (spec 11.2.5), so the
 * per-tick exchange works from the hosts alone, without loading the cord entity's
 * chunk. {@code cordId} is the entity's; a link whose peer does not hold the same
 * id back is stale and is cleared.
 */
public record DataLink(PortAddress peer, int peerPort, UUID cordId) {

    public CompoundTag write() {
        CompoundTag tag = new CompoundTag();
        peer.write(tag, "Peer");
        tag.putInt("Port", peerPort);
        tag.putUUID("Cord", cordId);
        return tag;
    }

    /** The link in {@code tag}, or null if it is not a whole one. */
    public static DataLink read(CompoundTag tag) {
        PortAddress peer = PortAddress.read(tag, "Peer");
        if (peer == null || !tag.hasUUID("Cord"))
            return null;
        return new DataLink(peer, tag.getInt("Port"), tag.getUUID("Cord"));
    }

    /** Whether {@code other}, held at {@code own} port {@code ownPort}, is this link's other half. */
    public boolean pairsWith(PortAddress own, int ownPort, DataLink other) {
        return other != null && other.cordId.equals(cordId) && other.peer.equals(own) && other.peerPort == ownPort;
    }
}
