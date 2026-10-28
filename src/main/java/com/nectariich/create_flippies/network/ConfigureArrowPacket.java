package com.nectariich.create_flippies.network;

import com.nectariich.create_flippies.content.schematic_arrow.ArrowSettings;
import com.nectariich.create_flippies.content.screwdriver.ScrewdriverItem;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;

/**
 * The schematic arrow menu, applied to the screwdriver the sender is holding.
 *
 * Nothing in it needs checking beyond what reading it already does: the style
 * clamps its width, height and head sizes to the ranges the menu offers and cuts its
 * colour to an opaque 24-bit one, and the choices are enums read by index with a
 * fallback. The arrow itself
 * is checked again when it is put on a panel.
 */
public class ConfigureArrowPacket implements FlippiesPacket {

    private final InteractionHand hand;
    private final ArrowSettings settings;

    public ConfigureArrowPacket(InteractionHand hand, ArrowSettings settings) {
        this.hand = hand;
        this.settings = settings;
    }

    public ConfigureArrowPacket(FriendlyByteBuf buffer) {
        hand = buffer.readEnum(InteractionHand.class);
        settings = ArrowSettings.read(buffer);
    }

    public void encode(FriendlyByteBuf buffer) {
        buffer.writeEnum(hand);
        settings.write(buffer);
    }

    public ArrowSettings settings() {
        return settings;
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
