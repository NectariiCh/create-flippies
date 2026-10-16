package com.nectariich.create_flippies.network;

import java.util.function.Supplier;

import com.nectariich.create_flippies.content.procedural_gauge.SquareProceduralGaugeBlockEntity;
import com.nectariich.create_flippies.content.procedural_gauge.GaugeFaceDefinition;
import com.nectariich.create_flippies.content.procedural_gauge.ProceduralGaugeBlockEntity;
import com.nectariich.create_flippies.content.procedural_gauge.ProceduralVoltmeterControl;
import com.nectariich.create_flippies.content.procedural_gauge.SquareProceduralGaugeControl;
import com.nectariich.create_flippies.foundation.control.HostedControl;
import com.nectariich.create_flippies.content.procedural_gauge.SupplyVoltage;
import com.nectariich.create_flippies.foundation.control.ControlMount;
import com.nectariich.create_flippies.foundation.control.ControlRackHolder;

import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.network.NetworkEvent;

/**
 * Applies the dial customization menu's result to a procedural gauge: either a full
 * face definition or a reset to the variant's default, plus (for the receiver
 * variant) the rated supply voltage.
 *
 * One packet serves both forms the gauge takes. A null mount means the block at
 * {@code pos} is the gauge, which is how the wrench menu has always worked; a mount
 * means {@code pos} is a control host and the gauge is the control mounted there.
 * The two are one packet rather than two because the payload, the reach check and
 * the sanitising are identical, and the only difference is which object is asked to
 * take the face.
 *
 * A mounted gauge is always a voltmeter — the link-driven Square Procedural Gauge is
 * deliberately not mountable — so a supply voltage sent with a mount has nothing to
 * apply to and is ignored rather than refused.
 */
public class ConfigureProceduralGaugePacket {

    private final BlockPos pos;
    private final boolean reset;
    private final GaugeFaceDefinition face;
    private final SupplyVoltage supply;
    private final ControlMount mount;

    /** face == null resets to the default; supply == null leaves it unchanged. */
    public ConfigureProceduralGaugePacket(BlockPos pos, GaugeFaceDefinition face, SupplyVoltage supply) {
        this(pos, face, supply, null);
    }

    /** As above, for the gauge mounted at {@code mount} in the host block at {@code pos}. */
    public ConfigureProceduralGaugePacket(BlockPos pos, GaugeFaceDefinition face, SupplyVoltage supply,
            ControlMount mount) {
        this.pos = pos;
        this.reset = face == null;
        this.face = face;
        this.supply = supply;
        this.mount = mount;
    }

    public ConfigureProceduralGaugePacket(FriendlyByteBuf buffer) {
        pos = buffer.readBlockPos();
        reset = buffer.readBoolean();
        face = reset ? null : GaugeFaceDefinition.read(buffer);
        supply = buffer.readBoolean() ? buffer.readEnum(SupplyVoltage.class) : null;
        mount = buffer.readBoolean() ? ControlMount.read(buffer) : null;
    }

    public void encode(FriendlyByteBuf buffer) {
        buffer.writeBlockPos(pos);
        buffer.writeBoolean(reset);
        if (!reset)
            face.write(buffer);
        buffer.writeBoolean(supply != null);
        if (supply != null)
            buffer.writeEnum(supply);
        buffer.writeBoolean(mount != null);
        if (mount != null)
            mount.write(buffer);
    }

    public void handle(Supplier<NetworkEvent.Context> context) {
        context.get().enqueueWork(() -> {
            ServerPlayer player = context.get().getSender();
            if (player == null)
                return;
            Level level = player.level();
            if (!level.isLoaded(pos) || player.distanceToSqr(Vec3.atCenterOf(pos)) > 64 * 64)
                return;
            if (mount != null) {
                if (!(level.getBlockEntity(pos) instanceof ControlRackHolder host))
                    return;
                HostedControl mounted = host.installedAt(mount);
                if (mounted instanceof ProceduralVoltmeterControl voltmeter)
                    voltmeter.configure(face);
                else if (mounted instanceof SquareProceduralGaugeControl gauge) {
                    if (supply != null)
                        gauge.setSupply(supply);
                    gauge.configure(face);
                }
                return;
            }
            if (!(level.getBlockEntity(pos) instanceof ProceduralGaugeBlockEntity gauge))
                return;
            if (supply != null && gauge instanceof SquareProceduralGaugeBlockEntity receiver)
                receiver.setSupply(supply);
            gauge.setCustomFace(face);
        });
        context.get().setPacketHandled(true);
    }
}
