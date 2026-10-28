package com.nectariich.create_flippies.network;

import com.nectariich.create_flippies.content.indicator.IndicatorAction;
import com.nectariich.create_flippies.content.indicator.IndicatorBlock;
import com.nectariich.create_flippies.content.indicator.IndicatorBlockEntity;
import com.nectariich.create_flippies.content.indicator.IndicatorColor;
import com.nectariich.create_flippies.content.indicator.IndicatorControl;
import com.nectariich.create_flippies.content.indicator.IndicatorLegend;
import com.nectariich.create_flippies.foundation.control.ControlMount;
import com.nectariich.create_flippies.foundation.control.ControlRackHolder;
import com.nectariich.create_flippies.foundation.control.HostedControl;

import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

/**
 * Applies the configuration menu's result to an indicator: which colour cap is
 * fitted, what pressing it does, what is printed on it, and — for the block form — a
 * quarter turn in its panel.
 *
 * One packet serves both forms the unit takes, the way
 * {@link ConfigureRotarySwitchPacket} does. A null mount means the block at
 * {@code pos} is the unit; a mount means {@code pos} is a control host and the unit
 * is the control mounted there. The payload, the reach check and the handling are
 * identical, and the only difference is which object is asked to take the settings.
 *
 * The turn is carried here rather than as a packet of its own because the menu is
 * where it is asked for, and a mounted unit has nothing to apply it to: a control's
 * facing and quarter turn are its mount, fixed when it was installed, so a turn
 * arriving with a mount is ignored rather than refused. It is relative, one detent of
 * the block's rotation per click, because that is what a wrench turn has always been
 * and it saves the menu having to track a state it does not own.
 *
 * Nothing on the wire is trusted. The legend is filtered and cut to length by
 * {@link IndicatorLegend#decode}, the action is read by ordinal through a guard, and
 * the colour and the action are reconciled by the receiving unit rather than here —
 * a black cap is only allowed on a button, and which of the two the client asked for
 * last is not this packet's business to decide.
 */
public class ConfigureIndicatorPacket implements FlippiesPacket {

    private final BlockPos pos;
    private final IndicatorColor color;
    private final IndicatorAction action;
    private final IndicatorLegend legend;
    private final boolean turn;
    private final ControlMount mount;

    /** As above, for the unit mounted at {@code mount} in the host block at {@code pos}. */
    public ConfigureIndicatorPacket(BlockPos pos, IndicatorColor color, IndicatorAction action,
            IndicatorLegend legend, boolean turn, ControlMount mount) {
        this.pos = pos;
        this.color = color;
        this.action = action;
        this.legend = legend;
        this.turn = turn;
        this.mount = mount;
    }

    public ConfigureIndicatorPacket(FriendlyByteBuf buffer) {
        pos = buffer.readBlockPos();
        color = buffer.readEnum(IndicatorColor.class);
        action = IndicatorAction.byOrdinal(buffer.readByte());
        legend = IndicatorLegend.decode(buffer);
        turn = buffer.readBoolean();
        mount = buffer.readBoolean() ? ControlMount.read(buffer) : null;
    }

    public void encode(FriendlyByteBuf buffer) {
        buffer.writeBlockPos(pos);
        buffer.writeEnum(color);
        buffer.writeByte(action.ordinal());
        legend.encode(buffer);
        buffer.writeBoolean(turn);
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
                if (mounted instanceof IndicatorControl unit)
                    unit.configure(color, action, legend);
                return;
            }
            if (!(level.getBlockEntity(pos) instanceof IndicatorBlockEntity unit))
                return;
            unit.configure(color, action, legend);
            if (turn)
                IndicatorBlock.turn(level, pos);
        });
    }
}
