package com.nectariich.create_flippies.network;

import java.util.List;
import java.util.function.Supplier;

import com.nectariich.create_flippies.content.plc.PlcSmallControl;
import com.nectariich.create_flippies.content.plc.PlcUploads;
import com.nectariich.create_flippies.foundation.control.ControlMount;

import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;

/**
 * One piece of a sketch on its way from a PLC's menu to the server.
 *
 * A sketch can be larger than a serverbound packet may be, so the menu splits its
 * UTF-8 into numbered chunks of one transfer and {@link PlcUploads} puts them back
 * together. When the last one arrives the PLC is checked again — still there, still
 * within reach, and this player still allowed to change it — and handed the text,
 * with {@code flash} saying whether to compile and flash it or only to keep it as the
 * editor's contents, which is what the menu sends as it closes.
 *
 * The chunk is read against {@link PlcUploads#CHUNK_BYTES}, so an oversized one is
 * refused by the decoder before anything is allocated for it. A flash is also held to
 * {@link PlcUploads#FLASH_COOLDOWN_TICKS} per player, because each one starts a
 * compile; one asked for sooner keeps the text and is answered busy.
 */
public class PlcUploadPacket {

    private final BlockPos pos;
    private final ControlMount mount;
    private final int transfer;
    private final int index;
    private final int count;
    private final boolean flash;
    private final byte[] chunk;

    public PlcUploadPacket(BlockPos pos, ControlMount mount, int transfer, int index, int count, boolean flash,
            byte[] chunk) {
        this.pos = pos;
        this.mount = mount;
        this.transfer = transfer;
        this.index = index;
        this.count = count;
        this.flash = flash;
        this.chunk = chunk;
    }

    public PlcUploadPacket(FriendlyByteBuf buffer) {
        pos = buffer.readBlockPos();
        mount = ControlMount.read(buffer);
        transfer = buffer.readVarInt();
        index = buffer.readVarInt();
        count = buffer.readVarInt();
        flash = buffer.readBoolean();
        chunk = buffer.readByteArray(PlcUploads.CHUNK_BYTES);
    }

    public void encode(FriendlyByteBuf buffer) {
        buffer.writeBlockPos(pos);
        mount.write(buffer);
        buffer.writeVarInt(transfer);
        buffer.writeVarInt(index);
        buffer.writeVarInt(count);
        buffer.writeBoolean(flash);
        buffer.writeByteArray(chunk);
    }

    public void handle(Supplier<NetworkEvent.Context> context) {
        context.get().enqueueWork(() -> {
            ServerPlayer player = context.get().getSender();
            // Checked per chunk as well as at the end, so nobody can park a buffer
            // here for a PLC they could not have opened
            if (PlcSmallControl.reachable(player, pos, mount, true) == null)
                return;
            PlcUploads.Finished finished = PlcUploads.accept(player, transfer, index, count, flash, pos, mount, chunk);
            if (finished == null)
                return;
            PlcSmallControl plc = PlcSmallControl.reachable(player, finished.pos(), finished.mount(), true);
            if (plc == null)
                return;
            if (finished.flash() && !PlcUploads.mayFlash(player, player.server.getTickCount())) {
                plc.upload(finished.source(), false, player);
                plc.reply(player, PlcUploadResultPacket.Outcome.BUSY, List.of());
                return;
            }
            plc.upload(finished.source(), finished.flash(), player);
        });
        context.get().setPacketHandled(true);
    }
}
