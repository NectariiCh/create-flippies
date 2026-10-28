package com.nectariich.create_flippies.network;

import javax.annotation.Nullable;

import net.minecraft.server.level.ServerPlayer;

/** What a {@link FlippiesPacket} handler is given about the packet it is handling. */
public interface PacketContext {

    /** The player who sent the packet, or null when the server sent it. */
    @Nullable
    ServerPlayer sender();

    /** Runs {@code work} on the main thread of the side that received the packet. */
    void enqueueWork(Runnable work);
}
