package com.nectariich.create_flippies.network;

import com.nectariich.create_flippies.content.procedural_gauge.EdgewiseFaceDefinition;
import com.nectariich.create_flippies.content.procedural_gauge.EdgewiseGaugeBlockEntity;
import com.nectariich.create_flippies.content.procedural_gauge.EdgewiseProceduralGaugeBlockEntity;
import com.nectariich.create_flippies.content.procedural_gauge.EdgewiseProceduralGaugeControl;
import com.nectariich.create_flippies.content.procedural_gauge.EdgewiseVoltmeterControl;
import com.nectariich.create_flippies.content.procedural_gauge.SupplyVoltage;
import com.nectariich.create_flippies.foundation.control.ControlMount;
import com.nectariich.create_flippies.foundation.control.ControlRackHolder;
import com.nectariich.create_flippies.foundation.control.HostedControl;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

/**
 * Applies the edgewise face menu's result to an edgewise gauge: either a full face
 * definition or a reset to the variant's default, plus (for the link-driven gauge)
 * the rated supply voltage. The edgewise counterpart of
 * {@link ConfigureProceduralGaugePacket}, with the same two forms: a null mount means
 * the block at {@code pos} is the gauge, a mount means {@code pos} is a control host
 * and the gauge is the control mounted there.
 *
 * The face travels as its NBT and is read back through
 * {@link EdgewiseFaceDefinition#fromNbt}, which sanitises every field, so a client
 * cannot print anything the menu itself could not.
 */
public class ConfigureEdgewiseGaugePacket implements FlippiesPacket {

    private final BlockPos pos;
    private final EdgewiseFaceDefinition face;
    private final SupplyVoltage supply;
    private final ControlMount mount;

    /** face == null resets to the default; supply == null leaves it unchanged; mount == null is the block. */
    public ConfigureEdgewiseGaugePacket(BlockPos pos, EdgewiseFaceDefinition face, SupplyVoltage supply,
            ControlMount mount) {
        this.pos = pos;
        this.face = face;
        this.supply = supply;
        this.mount = mount;
    }

    public ConfigureEdgewiseGaugePacket(FriendlyByteBuf buffer) {
        pos = buffer.readBlockPos();
        CompoundTag tag = buffer.readBoolean() ? buffer.readNbt() : null;
        face = tag == null ? null : EdgewiseFaceDefinition.fromNbt(tag);
        supply = buffer.readBoolean() ? buffer.readEnum(SupplyVoltage.class) : null;
        mount = buffer.readBoolean() ? ControlMount.read(buffer) : null;
    }

    public void encode(FriendlyByteBuf buffer) {
        buffer.writeBlockPos(pos);
        buffer.writeBoolean(face != null);
        if (face != null)
            buffer.writeNbt(face.toNbt());
        buffer.writeBoolean(supply != null);
        if (supply != null)
            buffer.writeEnum(supply);
        buffer.writeBoolean(mount != null);
        if (mount != null)
            mount.write(buffer);
    }

    public void handle(PacketContext context) {
        context.enqueueWork(() -> {
            ServerPlayer player = context.sender();
            if (player == null)
                return;
            Level level = player.level();
            if (!level.isLoaded(pos) || player.distanceToSqr(Vec3.atCenterOf(pos)) > 64 * 64)
                return;
            if (mount != null) {
                if (!(level.getBlockEntity(pos) instanceof ControlRackHolder host))
                    return;
                HostedControl mounted = host.installedAt(mount);
                if (mounted instanceof EdgewiseVoltmeterControl voltmeter)
                    voltmeter.configure(face);
                else if (mounted instanceof EdgewiseProceduralGaugeControl gauge) {
                    if (supply != null)
                        gauge.setSupply(supply);
                    gauge.configure(face);
                }
                return;
            }
            if (!(level.getBlockEntity(pos) instanceof EdgewiseGaugeBlockEntity gauge))
                return;
            if (supply != null && gauge instanceof EdgewiseProceduralGaugeBlockEntity receiver)
                receiver.setSupply(supply);
            gauge.setCustomFace(face);
        });
    }
}
