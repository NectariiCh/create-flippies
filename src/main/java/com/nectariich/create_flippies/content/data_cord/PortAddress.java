package com.nectariich.create_flippies.content.data_cord;

import java.util.Objects;
import java.util.UUID;

import com.nectariich.create_flippies.compat.NbtCompat;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.FriendlyByteBuf;

/**
 * Where a port host is (spec 11.2.5): a block entity at {@code pos}, or an entity
 * ({@code entity} not null) standing in the block {@code pos}. An entity host never
 * moves, so its block says which chunk to have loaded before looking for it; it is
 * found, and two addresses are equal, by its UUID alone.
 */
public record PortAddress(BlockPos pos, UUID entity) {

    public static PortAddress block(BlockPos pos) {
        return new PortAddress(pos.immutable(), null);
    }

    public static PortAddress entity(BlockPos pos, UUID entity) {
        return new PortAddress(pos.immutable(), entity);
    }

    public boolean isEntity() {
        return entity != null;
    }

    /** Writes {@code Peer} (the block) and, for an entity, {@code PeerEntity}, as links always have. */
    public void write(CompoundTag tag, String key) {
        tag.put(key, NbtCompat.writeBlockPos(pos));
        if (entity != null)
            tag.putUUID(key + "Entity", entity);
    }

    /** The address under {@code key}, or null if there is none. */
    public static PortAddress read(CompoundTag tag, String key) {
        if (!tag.contains(key))
            return null;
        BlockPos pos = NbtCompat.readBlockPos(tag.getCompound(key));
        return new PortAddress(pos, tag.hasUUID(key + "Entity") ? tag.getUUID(key + "Entity") : null);
    }

    public void write(FriendlyByteBuf buffer) {
        buffer.writeBlockPos(pos);
        buffer.writeBoolean(entity != null);
        if (entity != null)
            buffer.writeUUID(entity);
    }

    public static PortAddress read(FriendlyByteBuf buffer) {
        BlockPos pos = buffer.readBlockPos();
        return new PortAddress(pos, buffer.readBoolean() ? buffer.readUUID() : null);
    }

    @Override
    public boolean equals(Object o) {
        if (!(o instanceof PortAddress other))
            return false;
        return entity != null ? entity.equals(other.entity) : other.entity == null && pos.equals(other.pos);
    }

    @Override
    public int hashCode() {
        return entity != null ? entity.hashCode() : Objects.hashCode(pos);
    }

    @Override
    public String toString() {
        return entity == null ? pos.toShortString() : pos.toShortString() + " (" + entity.toString().substring(0, 8) + ")";
    }
}
