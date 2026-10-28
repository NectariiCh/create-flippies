package com.nectariich.create_flippies.network;

import net.minecraft.network.FriendlyByteBuf;

/**
 * One of Flippies' packets, in a form neither loader owns. {@link ModPackets} wraps
 * it for Forge's channel on 1.20.1 and as a NeoForge payload on 1.21.1.
 *
 * Every packet also has a constructor taking a {@link FriendlyByteBuf}, which is what
 * reads it; {@link #encode} must write exactly what that constructor reads.
 */
public interface FlippiesPacket {

    void encode(FriendlyByteBuf buffer);

    /**
     * Called on the network thread on both versions. Anything touching the game goes
     * through {@link PacketContext#enqueueWork}.
     */
    void handle(PacketContext context);
}
