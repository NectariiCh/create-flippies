package com.nectariich.create_flippies.network;

import java.util.function.Supplier;

import com.nectariich.create_flippies.content.design_workbench.DesignTransfers;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;

/** One chunk of a model the server is sending a client that asked for it. */
public class DesignDataPacket {

    private final String hash;
    private final int index;
    private final int total;
    private final byte[] bytes;

    public DesignDataPacket(String hash, int index, int total, byte[] bytes) {
        this.hash = hash;
        this.index = index;
        this.total = total;
        this.bytes = bytes;
    }

    public DesignDataPacket(FriendlyByteBuf buffer) {
        hash = buffer.readUtf(32);
        index = buffer.readVarInt();
        total = buffer.readVarInt();
        bytes = buffer.readByteArray(DesignTransfers.DOWNLOAD_CHUNK);
    }

    public void encode(FriendlyByteBuf buffer) {
        buffer.writeUtf(hash, 32);
        buffer.writeVarInt(index);
        buffer.writeVarInt(total);
        buffer.writeByteArray(bytes);
    }

    public void handle(Supplier<NetworkEvent.Context> context) {
        context.get().enqueueWork(() -> DistExecutor.unsafeRunWhenOn(Dist.CLIENT,
                () -> () -> com.nectariich.create_flippies.client.design.ClientDesigns.receive(hash, index, total,
                        bytes)));
        context.get().setPacketHandled(true);
    }
}
