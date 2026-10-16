package com.nectariich.create_flippies.network;

import java.util.function.Supplier;

import com.nectariich.create_flippies.content.display.AlphanumDisplay;
import com.nectariich.create_flippies.content.display.AlphanumDisplayBlock;
import com.nectariich.create_flippies.content.display.AlphanumDisplayBlockEntity;
import com.nectariich.create_flippies.content.display.AlphanumDisplayControl;
import com.nectariich.create_flippies.content.display.DisplayPrint;
import com.nectariich.create_flippies.foundation.control.ControlMount;

import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.network.NetworkEvent;

/**
 * Applies the editor's lettering to one alphanumeric display: the face, the weight
 * and slant, the character height, the turn and the colour.
 *
 * One packet serves both forms the device takes, the way
 * {@link ConfigureIndicatorPacket} does. A null mount means the block at {@code pos}
 * is the display; a mount means {@code pos} is a control host and the display is the
 * control mounted there. The payload, the reach check and the handling are identical,
 * and the only difference is which object is asked to take the settings.
 *
 * What is on the screen is deliberately not here and never travels. A display shows
 * what a link sent it, so a client that could write to one directly could put any
 * text on any panel in the world from a menu that never shows the line at all.
 *
 * The turn of the <em>device</em> is carried here rather than as a packet of its own,
 * because the menu is where it is asked for. A mounted display has nothing to apply
 * it to — a control's facing and quarter turn are its mount, fixed when it was
 * installed — so a turn arriving with a mount is ignored rather than refused. It is
 * relative, one detent per click, because that is what a wrench turn has always been
 * and it saves the menu tracking a state it does not own. The turn of the
 * <em>lettering</em> is a different thing entirely and travels inside the print.
 *
 * Nothing on the wire is trusted: every field of the print is guarded by
 * {@link DisplayPrint#decode}.
 */
public class ConfigureAlphanumDisplayPacket {

    private final BlockPos pos;
    private final DisplayPrint print;
    private final boolean turn;
    private final ControlMount mount;

    public ConfigureAlphanumDisplayPacket(BlockPos pos, DisplayPrint print, boolean turn, ControlMount mount) {
        this.pos = pos;
        this.print = print;
        this.turn = turn;
        this.mount = mount;
    }

    public ConfigureAlphanumDisplayPacket(FriendlyByteBuf buffer) {
        pos = buffer.readBlockPos();
        print = DisplayPrint.decode(buffer);
        turn = buffer.readBoolean();
        mount = buffer.readBoolean() ? ControlMount.read(buffer) : null;
    }

    public void encode(FriendlyByteBuf buffer) {
        buffer.writeBlockPos(pos);
        print.encode(buffer);
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
                AlphanumDisplayControl display = AlphanumDisplay.at(level.getBlockEntity(pos), mount);
                if (display != null)
                    display.configure(print);
                return;
            }
            if (!(level.getBlockEntity(pos) instanceof AlphanumDisplayBlockEntity display))
                return;
            display.configure(print);
            if (turn)
                AlphanumDisplayBlock.turn(level, pos);
        });
        context.get().setPacketHandled(true);
    }
}
