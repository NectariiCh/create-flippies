package com.nectariich.create_flippies.network;

import java.util.Arrays;

import com.nectariich.create_flippies.compat.ClientOnly;
import com.nectariich.create_flippies.content.design_workbench.blueprint.BlueprintFiles;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;

/**
 * The world's whole blueprint section, with how the last op went, on its way to a
 * client.
 *
 * Sent packed and, if it has to be, in pieces: a full section is thousands of
 * blueprints at most, which packs small, but nothing about it is bounded tightly
 * enough to promise one packet. Pieces of one sync share a sequence number, and a
 * client that sees a new number before the last piece of the old one drops the old
 * one; packets from one server arrive in order, so that only happens on a sync the
 * newer one replaces anyway.
 */
public class BlueprintSyncPacket implements FlippiesPacket {

    /** Bytes per piece, well under the clientbound limit. */
    public static final int CHUNK = 512 * 1024;

    /** How many pieces a client will put together. */
    public static final int MAX_CHUNKS = 64;

    private static int nextSequence;

    private final int sequence;
    private final int index;
    private final int total;
    private final byte[] bytes;

    public BlueprintSyncPacket(int sequence, int index, int total, byte[] bytes) {
        this.sequence = sequence;
        this.index = index;
        this.total = total;
        this.bytes = bytes;
    }

    public BlueprintSyncPacket(FriendlyByteBuf buffer) {
        sequence = buffer.readVarInt();
        index = buffer.readVarInt();
        total = buffer.readVarInt();
        bytes = buffer.readByteArray(CHUNK);
    }

    /** Server thread: packs a sync payload and sends it to one player, in as many pieces as it takes. */
    public static void send(ServerPlayer player, CompoundTag payload) {
        byte[] packed = BlueprintFiles.pack(payload);
        int total = Math.max(1, (packed.length + CHUNK - 1) / CHUNK);
        int sequence = nextSequence++;
        for (int i = 0; i < total; i++) {
            int from = i * CHUNK;
            ModPackets.sendToPlayer(player, new BlueprintSyncPacket(sequence, i,
                    total, Arrays.copyOfRange(packed, from, Math.min(packed.length, from + CHUNK))));
        }
    }

    public void encode(FriendlyByteBuf buffer) {
        buffer.writeVarInt(sequence);
        buffer.writeVarInt(index);
        buffer.writeVarInt(total);
        buffer.writeByteArray(bytes);
    }

    public void handle(PacketContext context) {
        context.enqueueWork(() -> ClientOnly.run(
                () -> () -> com.nectariich.create_flippies.client.blueprint.ClientBlueprints.receiveSync(sequence,
                        index, total, bytes)));
    }
}
