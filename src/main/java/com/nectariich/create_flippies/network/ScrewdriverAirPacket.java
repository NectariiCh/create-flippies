package com.nectariich.create_flippies.network;

import java.util.function.Supplier;

import com.nectariich.create_flippies.content.screwdriver.ScrewdriverActions;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraftforge.network.NetworkEvent;

/**
 * A screwdriver click that hit nothing, which the block interaction events never
 * report.
 *
 * It carries the hand, which button it was, and no position at all — and that is the
 * point. The second corner of a dynamic block is resolved against three mathematical
 * planes rather than against anything in the world, and a panel edit is resolved
 * against the panels themselves, so the only thing either resolution needs is the ray
 * — and the server already knows where the sender is looking. Sending coordinates
 * would add a number the server would have to distrust and check against the one it
 * can derive itself.
 *
 * Both buttons come this way. A right click on nothing is what finishes a panel
 * standing in the air; a left click on nothing is what fills in a hole while looking
 * through it at the sky, which is the one gesture with no block anywhere to name.
 */
public class ScrewdriverAirPacket {

    private final InteractionHand hand;
    private final boolean removal;

    public ScrewdriverAirPacket(InteractionHand hand, boolean removal) {
        this.hand = hand;
        this.removal = removal;
    }

    public ScrewdriverAirPacket(FriendlyByteBuf buffer) {
        hand = buffer.readEnum(InteractionHand.class);
        removal = buffer.readBoolean();
    }

    public void encode(FriendlyByteBuf buffer) {
        buffer.writeEnum(hand);
        buffer.writeBoolean(removal);
    }

    public void handle(Supplier<NetworkEvent.Context> context) {
        context.get().enqueueWork(() -> {
            ServerPlayer player = context.get().getSender();
            if (player == null)
                return;
            if (removal)
                ScrewdriverActions.removeInAir(player, hand);
            else
                ScrewdriverActions.useInAir(player, hand);
        });
        context.get().setPacketHandled(true);
    }
}
