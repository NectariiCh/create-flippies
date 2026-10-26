package com.nectariich.create_flippies.foundation.wire.entity;

import java.util.Objects;
import java.util.UUID;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

import org.patryk3211.powergrid.electricity.GlobalElectricNetworks;
import org.patryk3211.powergrid.electricity.sim.ElectricalNetwork;
import org.patryk3211.powergrid.electricity.sim.node.OwnedFloatingNode;
import org.patryk3211.powergrid.electricity.wire.BaseWireEntity;
import org.patryk3211.powergrid.electricity.wire.IWireEndpoint;
import org.patryk3211.powergrid.electricity.wire.WireEndpointType;

/**
 * One terminal of an {@link EntityElectricHost}, as Power Grid's network keys and
 * reaches it: what a {@code BlockWireEndpoint} is to a block's terminal. While the
 * host is not loaded it answers as a block's does: no behaviour, and the node the
 * network keeps for it, if any.
 *
 * Power Grid's endpoint types are a closed enum, so this saves itself as
 * {@link WireEndpointType#IMAGINARY} with {@link #TAG} added, and
 * {@code WireEndpointTypeMixin} reads that back as this class. Without this mod the
 * tag reads as an imaginary point at the host's block, which joins nothing.
 */
public final class EntityWireEndpoint implements IWireEndpoint {

    /** The host's UUID; its presence is what marks one of ours. */
    public static final String TAG = "FlippiesEntity";
    /** {@code "terminal"} here, {@code "cord"} on an {@link EntityCordEndpoint}. */
    public static final String KIND = "FlippiesKind";

    private UUID entity;
    private BlockPos anchor;
    private int terminal;

    public EntityWireEndpoint() {
        this(new UUID(0, 0), BlockPos.ZERO, 0);
    }

    public EntityWireEndpoint(UUID entity, BlockPos anchor, int terminal) {
        this.entity = entity;
        this.anchor = anchor;
        this.terminal = terminal;
    }

    public UUID entity() {
        return entity;
    }

    public int terminal() {
        return terminal;
    }

    @Override
    public WireEndpointType type() {
        return WireEndpointType.IMAGINARY;
    }

    @Override
    public void read(CompoundTag tag) {
        entity = tag.getUUID(TAG);
        anchor = NbtUtils.readBlockPos(tag.getCompound("Anchor"));
        terminal = tag.getInt("Terminal");
    }

    @Override
    public void write(CompoundTag tag) {
        tag.putUUID(TAG, entity);
        tag.putString(KIND, "terminal");
        tag.put("Anchor", NbtUtils.writeBlockPos(anchor));
        tag.putInt("Terminal", terminal);
        // What an imaginary endpoint reads, should this mod be gone
        Vec3 at = Vec3.atCenterOf(anchor);
        CompoundTag pos = new CompoundTag();
        pos.putDouble("X", at.x);
        pos.putDouble("Y", at.y);
        pos.putDouble("Z", at.z);
        tag.put("Pos", pos);
    }

    private EntityElectricHolder holder(Level level) {
        EntityElectricHost host = EntityElectricHost.find(level, entity);
        return host == null ? null : host.electric();
    }

    @Override
    public Vec3 getExactPosition(Level level) {
        EntityElectricHost host = EntityElectricHost.find(level, entity);
        return host == null ? Vec3.atCenterOf(anchor) : host.terminalPosition(terminal);
    }

    @Override
    public boolean isValid(Level level) {
        EntityElectricHolder holder = holder(level);
        return holder != null && holder.hasTerminal(terminal);
    }

    @Override
    public OwnedFloatingNode getNode(Level level) {
        EntityElectricHolder holder = holder(level);
        if (holder == null)
            return GlobalElectricNetworks.getWorldNetworks(level).globalExternalNodes.get(this);
        return holder.terminal(terminal);
    }

    @Override
    public void joinNetwork(Level level, ElectricalNetwork network) {
        EntityElectricHolder holder = holder(level);
        if (holder != null) {
            holder.joinNetwork(network, terminal);
            return;
        }
        OwnedFloatingNode node = GlobalElectricNetworks.getWorldNetworks(level).globalExternalNodes.get(this);
        if (node != null)
            network.addNode(node);
    }

    @Override
    public void assignWireEntity(BaseWireEntity wire) {
        EntityElectricHolder holder = holder(wire.level());
        if (holder != null)
            holder.addConnection(terminal, wire);
    }

    @Override
    public void removeWireEntity(BaseWireEntity wire) {
        EntityElectricHolder holder = holder(wire.level());
        if (holder != null)
            holder.removeConnection(terminal, wire);
    }

    /** The host never moves with whatever moved the wire. */
    @Override
    public IWireEndpoint makeOffset(BlockPos offset) {
        return this;
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof EntityWireEndpoint other && entity.equals(other.entity) && terminal == other.terminal;
    }

    @Override
    public int hashCode() {
        return Objects.hash(entity, terminal);
    }

    @Override
    public String toString() {
        return String.format("Entity(id=%s, n=%d)", entity, terminal);
    }
}
