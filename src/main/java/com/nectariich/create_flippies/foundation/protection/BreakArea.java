package com.nectariich.create_flippies.foundation.protection;

import java.util.UUID;
import java.util.function.LongConsumer;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.phys.AABB;

/**
 * A box of blocks a screwdriver refuses to mine through.
 *
 * A box of whole blocks, unlike everything else the screwdriver rules. A placement
 * grid is a rectangle on one face because what it constrains is where a control
 * lands on that face; what this constrains is a block being broken, and a block is
 * a block from every side, so two opposite corners of a box is both the smallest
 * thing that can say it and the easiest thing to point at.
 *
 * The bounds are inclusive at both ends, which is what "the box between these two
 * blocks" means to whoever clicked them: click the same block twice and the area
 * is that one block, not nothing.
 *
 * It carries an id rather than being identified by its bounds. Two areas may be
 * drawn over the same blocks — protecting a panel and then protecting the room it
 * is in is an ordinary thing to do — so the store needs a handle that survives one
 * of them being taken away.
 */
public record BreakArea(UUID id, int minX, int minY, int minZ, int maxX, int maxY, int maxZ) {

    /**
     * How far an area may run along any one axis, in blocks.
     *
     * Four chunks. Every area is filed under each chunk it touches and every break
     * consults the ones filed under its own, so an unbounded box would be a list
     * every player in the world walks past on every swing. Anything larger is also
     * almost certainly a click the player did not mean to make on a far-off block —
     * and a mistake here is a box you cannot mine your way out of.
     */
    public static final int MAX_SPAN = 64;

    /** The box between two clicked blocks, both of them inside it. */
    public static BreakArea between(UUID id, BlockPos first, BlockPos second) {
        return new BreakArea(id,
                Math.min(first.getX(), second.getX()), Math.min(first.getY(), second.getY()),
                Math.min(first.getZ(), second.getZ()), Math.max(first.getX(), second.getX()),
                Math.max(first.getY(), second.getY()), Math.max(first.getZ(), second.getZ()));
    }

    public int spanX() {
        return maxX - minX + 1;
    }

    public int spanY() {
        return maxY - minY + 1;
    }

    public int spanZ() {
        return maxZ - minZ + 1;
    }

    /** How many blocks it covers, which is how the smallest area over a block is picked. */
    public long blocks() {
        return (long) spanX() * spanY() * spanZ();
    }

    /** Whether the geometry is one a store should hold; see {@link #MAX_SPAN}. */
    public boolean isTooLarge() {
        return spanX() > MAX_SPAN || spanY() > MAX_SPAN || spanZ() > MAX_SPAN;
    }

    public boolean contains(BlockPos pos) {
        return pos.getX() >= minX && pos.getX() <= maxX
                && pos.getY() >= minY && pos.getY() <= maxY
                && pos.getZ() >= minZ && pos.getZ() <= maxZ;
    }

    /** The box in world units, which runs to the far side of the last block. */
    public AABB box() {
        return new AABB(minX, minY, minZ, maxX + 1.0, maxY + 1.0, maxZ + 1.0);
    }

    /** Every chunk the area overlaps, as {@link ChunkPos#toLong()} keys, each once. */
    public void forEachChunk(LongConsumer keys) {
        for (int x = minX >> 4; x <= maxX >> 4; x++)
            for (int z = minZ >> 4; z <= maxZ >> 4; z++)
                keys.accept(ChunkPos.asLong(x, z));
    }

    public CompoundTag save() {
        CompoundTag tag = new CompoundTag();
        tag.putUUID("Id", id);
        tag.putIntArray("Bounds", new int[] { minX, minY, minZ, maxX, maxY, maxZ });
        return tag;
    }

    /** Null when the tag is not an area at all, which is how a truncated save is survived. */
    public static BreakArea load(CompoundTag tag) {
        int[] bounds = tag.getIntArray("Bounds");
        if (!tag.hasUUID("Id") || bounds.length != 6)
            return null;
        return new BreakArea(tag.getUUID("Id"), bounds[0], bounds[1], bounds[2], bounds[3], bounds[4], bounds[5]);
    }

    public void write(FriendlyByteBuf buffer) {
        buffer.writeUUID(id);
        buffer.writeVarInt(minX);
        buffer.writeVarInt(minY);
        buffer.writeVarInt(minZ);
        buffer.writeVarInt(maxX);
        buffer.writeVarInt(maxY);
        buffer.writeVarInt(maxZ);
    }

    /**
     * One off the wire, put back into order rather than trusted. The bounds decide
     * what a chunk loop iterates over, so a packet claiming a maximum below its
     * minimum has to become an ordinary small box rather than a loop that never
     * runs — or, with the spans read the other way, one that runs for ever.
     */
    public static BreakArea read(FriendlyByteBuf buffer) {
        UUID id = buffer.readUUID();
        int[] c = new int[6];
        for (int i = 0; i < c.length; i++)
            c[i] = buffer.readVarInt();
        return new BreakArea(id, Math.min(c[0], c[3]), Math.min(c[1], c[4]), Math.min(c[2], c[5]),
                Math.max(c[0], c[3]), Math.max(c[1], c[4]), Math.max(c[2], c[5]));
    }
}
