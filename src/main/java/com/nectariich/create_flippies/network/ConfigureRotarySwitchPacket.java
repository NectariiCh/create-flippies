package com.nectariich.create_flippies.network;

import java.util.function.Supplier;

import com.nectariich.create_flippies.foundation.control.ControlRackHolder;
import com.nectariich.create_flippies.content.rotary_switch.RotarySwitchAppearance;
import com.nectariich.create_flippies.content.rotary_switch.RotarySwitchBlock;
import com.nectariich.create_flippies.content.rotary_switch.RotarySwitchBlockEntity;
import com.nectariich.create_flippies.content.rotary_switch.RotarySwitchControl;
import com.nectariich.create_flippies.foundation.control.ControlMount;
import com.nectariich.create_flippies.foundation.control.HostedControl;

import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.network.NetworkEvent;

/**
 * Applies the appearance menu's result to a rotary switch: the front it is printed
 * with, the paint on the body, the handle fitted to it, and — for the block form —
 * a quarter turn in its panel.
 *
 * One packet serves both forms the switch takes, the way
 * {@link ConfigureProceduralGaugePacket} does. A null mount means the block at
 * {@code pos} is the switch; a mount means {@code pos} is a control host and the
 * switch is the control mounted there. The payload, the reach check and the
 * sanitising are identical, and the only difference is which object is asked to
 * take the look.
 *
 * The turn is carried here rather than as a packet of its own because the menu is
 * where it is asked for, and a mounted switch has nothing to apply it to: a
 * control's facing and quarter turn are its mount, fixed when it was installed, so
 * a turn arriving with a mount is ignored rather than refused — again as the gauge
 * packet does with a supply voltage a voltmeter has no use for. It is relative,
 * one detent of the block's rotation per click, because that is what a wrench turn
 * has always been and it saves the menu having to track a state it does not own.
 *
 * The appearance arrives unchecked and is put through
 * {@link RotarySwitchAppearance#forKind} by whichever switch takes it, so a client
 * naming a three-position front for a two-position switch changes nothing.
 */
public class ConfigureRotarySwitchPacket {

    private final BlockPos pos;
    private final RotarySwitchAppearance appearance;
    private final boolean turn;
    private final ControlMount mount;

    /** For the switch that is the block at {@code pos}. */
    public ConfigureRotarySwitchPacket(BlockPos pos, RotarySwitchAppearance appearance, boolean turn) {
        this(pos, appearance, turn, null);
    }

    /** As above, for the switch mounted at {@code mount} in the host block at {@code pos}. */
    public ConfigureRotarySwitchPacket(BlockPos pos, RotarySwitchAppearance appearance, boolean turn,
            ControlMount mount) {
        this.pos = pos;
        this.appearance = appearance;
        this.turn = turn;
        this.mount = mount;
    }

    public ConfigureRotarySwitchPacket(FriendlyByteBuf buffer) {
        pos = buffer.readBlockPos();
        appearance = RotarySwitchAppearance.read(buffer);
        turn = buffer.readBoolean();
        mount = buffer.readBoolean() ? ControlMount.read(buffer) : null;
    }

    public void encode(FriendlyByteBuf buffer) {
        buffer.writeBlockPos(pos);
        appearance.write(buffer);
        buffer.writeBoolean(turn);
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
                if (mounted instanceof RotarySwitchControl rotary)
                    rotary.setAppearance(appearance);
                return;
            }
            if (!(level.getBlockEntity(pos) instanceof RotarySwitchBlockEntity rotary))
                return;
            rotary.setAppearance(appearance);
            if (turn)
                RotarySwitchBlock.turn(level, pos);
        });
        context.get().setPacketHandled(true);
    }
}
