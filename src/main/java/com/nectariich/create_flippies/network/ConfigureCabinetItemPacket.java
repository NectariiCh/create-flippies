package com.nectariich.create_flippies.network;

import com.nectariich.create_flippies.content.cabinet.CabinetSpec;
import com.nectariich.create_flippies.content.cabinet.ElectricalCabinetItem;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;

/**
 * The cabinet menu, applied to the cabinet item the sender is holding.
 *
 * As the screwdriver's menu, it names the hand rather than a slot, and the server
 * re-reads the stack in that hand, so a client can only change a cabinet it holds.
 * The spec is clamped as it is read, so nothing out of range reaches the stack; the
 * whole stack takes it, as a stack is all one kind of cabinet.
 */
public class ConfigureCabinetItemPacket implements FlippiesPacket {

    private final InteractionHand hand;
    private final CabinetSpec spec;

    public ConfigureCabinetItemPacket(InteractionHand hand, CabinetSpec spec) {
        this.hand = hand;
        this.spec = spec;
    }

    public ConfigureCabinetItemPacket(FriendlyByteBuf buffer) {
        hand = buffer.readEnum(InteractionHand.class);
        spec = CabinetSpec.read(buffer);
    }

    public void encode(FriendlyByteBuf buffer) {
        buffer.writeEnum(hand);
        spec.write(buffer);
    }

    public void handle(PacketContext context) {
        context.enqueueWork(() -> {
            ServerPlayer player = context.sender();
            if (player == null)
                return;
            ItemStack stack = player.getItemInHand(hand);
            // Standing or not is the item's, whatever the menu sent
            if (stack.getItem() instanceof ElectricalCabinetItem item)
                spec.withStanding(item.standing()).apply(stack);
        });
    }
}
