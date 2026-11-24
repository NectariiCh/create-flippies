package com.nectariich.create_flippies.network;

import com.nectariich.create_flippies.content.cabinet.CabinetCutSettings;
import com.nectariich.create_flippies.content.screwdriver.ScrewdriverItem;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;

/** The cabinet mode's part of the screwdriver menu, applied to the stack in the sender's hand. */
public class ConfigureCabinetCutPacket implements FlippiesPacket {

    private final InteractionHand hand;
    private final CabinetCutSettings settings;

    public ConfigureCabinetCutPacket(InteractionHand hand, CabinetCutSettings settings) {
        this.hand = hand;
        this.settings = settings;
    }

    public ConfigureCabinetCutPacket(FriendlyByteBuf buffer) {
        hand = buffer.readEnum(InteractionHand.class);
        int kind = buffer.readByte();
        int size = buffer.readVarInt();
        CabinetCutSettings.Kind[] kinds = CabinetCutSettings.Kind.values();
        settings = new CabinetCutSettings(kind >= 0 && kind < kinds.length ? kinds[kind] : null, size);
    }

    public void encode(FriendlyByteBuf buffer) {
        buffer.writeEnum(hand);
        buffer.writeByte(settings.kind().ordinal());
        buffer.writeVarInt(settings.halfPixels());
    }

    public void handle(PacketContext context) {
        context.enqueueWork(() -> {
            ServerPlayer player = context.sender();
            if (player == null)
                return;
            ItemStack stack = player.getItemInHand(hand);
            if (ScrewdriverItem.isScrewdriver(stack))
                settings.apply(stack);
        });
    }
}
