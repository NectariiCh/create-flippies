package com.nectariich.create_flippies.network;

import java.util.function.Supplier;

import com.nectariich.create_flippies.content.label.PanelLabel;
import com.nectariich.create_flippies.content.label.PanelLabelControl;
import com.nectariich.create_flippies.content.label.PanelLabelPrint;
import com.nectariich.create_flippies.foundation.control.ControlMount;

import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.network.NetworkEvent;

/**
 * Applies the editor's legend, colour and lettering to one mounted panel label.
 *
 * The strip the label covers is not here and never travels. It was fixed when the
 * label was drawn onto the panel, and a client that could rewrite it could grow a
 * label over its neighbours without anything checking the footprint again.
 *
 * The text is read off the wire with a length limit and then sanitised again on
 * arrival. Neither is belt and braces: the limit is what stops a client sending a
 * megabyte before anything has looked at it, and the sanitising is what stops a
 * client writing formatting codes that its own editor would not have let through.
 */
public class ConfigurePanelLabelPacket {

    /**
     * How much text the decoder will read. Twice the stored limit rather than exactly
     * it, so that a client sending a legend of full length in characters outside the
     * basic plane is trimmed by the same rule as everyone else instead of being
     * dropped by the decoder.
     */
    private static final int WIRE_LIMIT = PanelLabel.MAX_LENGTH * 2;

    private final BlockPos pos;
    private final ControlMount mount;
    private final String text;
    private final int colour;
    private final int plateColour;
    private final PanelLabelPrint print;

    public ConfigurePanelLabelPacket(BlockPos pos, ControlMount mount, String text, int colour, int plateColour,
            PanelLabelPrint print) {
        this.pos = pos;
        this.mount = mount;
        this.text = text;
        this.colour = colour;
        this.plateColour = plateColour;
        this.print = print;
    }

    public ConfigurePanelLabelPacket(FriendlyByteBuf buffer) {
        pos = buffer.readBlockPos();
        mount = ControlMount.read(buffer);
        text = buffer.readUtf(WIRE_LIMIT);
        colour = buffer.readInt();
        plateColour = buffer.readInt();
        print = PanelLabelPrint.decode(buffer);
    }

    public void encode(FriendlyByteBuf buffer) {
        buffer.writeBlockPos(pos);
        mount.write(buffer);
        buffer.writeUtf(text, WIRE_LIMIT);
        buffer.writeInt(colour);
        buffer.writeInt(plateColour);
        print.encode(buffer);
    }

    public void handle(Supplier<NetworkEvent.Context> context) {
        context.get().enqueueWork(() -> {
            ServerPlayer player = context.get().getSender();
            if (player == null)
                return;
            Level level = player.level();
            if (!level.isLoaded(pos) || player.distanceToSqr(Vec3.atCenterOf(pos)) > 64 * 64)
                return;
            PanelLabelControl label = PanelLabel.at(level.getBlockEntity(pos), mount);
            if (label == null)
                return;
            label.configure(PanelLabel.sanitise(text), PanelLabel.sanitiseColour(colour),
                    PanelLabel.sanitiseColour(plateColour), print);
        });
        context.get().setPacketHandled(true);
    }
}
