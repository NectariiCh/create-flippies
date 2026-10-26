package com.nectariich.create_flippies.foundation.wire.entity;

import java.util.UUID;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

import org.patryk3211.powergrid.electricity.wire.IWireEndpoint;
import org.patryk3211.powergrid.electricity.wire.WireEndpointType;
import org.patryk3211.powergrid.electricity.wire.powercord.ICordEndpoint;

/**
 * Power Grid's copper cord plugged into an {@link EntityElectricHost}'s socket: its
 * terminals 0 and 1, and the point the cord ends at (the plug's outer face), as an
 * {@code AutoCordEndpoint} is for a block's socket.
 *
 * Saved as {@link WireEndpointType#AUTO_CORD} with {@link EntityWireEndpoint#TAG}
 * added, and read back as this class by {@code WireEndpointTypeMixin}; the
 * auto-cord fields are written too, so without this mod it reads as a cord on the
 * host's block, which is not there and so stays unresolved.
 */
public final class EntityCordEndpoint implements ICordEndpoint {

    private UUID entity;
    private BlockPos anchor;
    private Vec3 placement;

    public EntityCordEndpoint() {
        this(new UUID(0, 0), BlockPos.ZERO, Vec3.ZERO);
    }

    public EntityCordEndpoint(UUID entity, BlockPos anchor, Vec3 placement) {
        this.entity = entity;
        this.anchor = anchor;
        this.placement = placement;
    }

    public UUID entity() {
        return entity;
    }

    @Override
    public IWireEndpoint getEndpoint1() {
        return new EntityWireEndpoint(entity, anchor, 0);
    }

    @Override
    public IWireEndpoint getEndpoint2() {
        return new EntityWireEndpoint(entity, anchor, 1);
    }

    @Override
    public WireEndpointType type() {
        return WireEndpointType.AUTO_CORD;
    }

    @Override
    public void read(CompoundTag tag) {
        entity = tag.getUUID(EntityWireEndpoint.TAG);
        anchor = NbtUtils.readBlockPos(tag.getCompound("Position"));
        placement = new Vec3(tag.getDouble("PlacementX"), tag.getDouble("PlacementY"), tag.getDouble("PlacementZ"));
    }

    @Override
    public void write(CompoundTag tag) {
        tag.putUUID(EntityWireEndpoint.TAG, entity);
        tag.putString(EntityWireEndpoint.KIND, "cord");
        tag.putDouble("PlacementX", placement.x);
        tag.putDouble("PlacementY", placement.y);
        tag.putDouble("PlacementZ", placement.z);
        // What an auto-cord endpoint reads, should this mod be gone
        tag.put("Position", NbtUtils.writeBlockPos(anchor));
        tag.putInt("Terminal1", 0);
        tag.putInt("Terminal2", 1);
        tag.putFloat("X", (float) placement.x);
        tag.putFloat("Y", (float) placement.y);
        tag.putFloat("Z", (float) placement.z);
    }

    @Override
    public Vec3 getExactPosition(Level level) {
        return placement;
    }

    @Override
    public boolean isValid(Level level) {
        return getEndpoint1().isValid(level) && getEndpoint2().isValid(level);
    }

    @Override
    public IWireEndpoint makeOffset(BlockPos offset) {
        return this;
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof EntityCordEndpoint other && entity.equals(other.entity);
    }

    @Override
    public int hashCode() {
        return entity.hashCode();
    }

    @Override
    public String toString() {
        return String.format("EntityCord(id=%s)", entity);
    }
}
