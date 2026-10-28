package com.nectariich.create_flippies.network;

import com.nectariich.create_flippies.CreateFlippies;
import com.nectariich.create_flippies.content.decal.DecalArt;
import com.nectariich.create_flippies.content.decal.DecalSelection;
import com.nectariich.create_flippies.content.design_workbench.ControlDesign;
import com.nectariich.create_flippies.content.design_workbench.DesignStore;
import com.nectariich.create_flippies.content.screwdriver.ScrewdriverItem;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;

/**
 * Which picture the screwdriver paints with, in what colour, and whether imports
 * onto it leave out the background, applied to the stack the sender is holding.
 *
 * A blank hash leaves the picture as it is and changes only the options, which is
 * what the menu sends when it closes. A hash names a picture the sender has just
 * uploaded through the design store; the upload's chunks go down the same channel
 * ahead of this, so by the time it is handled the picture is stored or was refused.
 * The picture is then read back out of the store rather than described by the
 * client, so its proportions are the stored picture's own and a hash naming
 * something that is not a picture is turned away.
 */
public class SelectDecalPacket implements FlippiesPacket {

    private final InteractionHand hand;
    private final String art;
    private final int tint;
    private final boolean transparent;

    public SelectDecalPacket(InteractionHand hand, String art, int tint, boolean transparent) {
        this.hand = hand;
        this.art = art;
        this.tint = tint;
        this.transparent = transparent;
    }

    public SelectDecalPacket(FriendlyByteBuf buffer) {
        hand = buffer.readEnum(InteractionHand.class);
        art = buffer.readUtf(32);
        tint = buffer.readInt();
        transparent = buffer.readBoolean();
    }

    public void encode(FriendlyByteBuf buffer) {
        buffer.writeEnum(hand);
        buffer.writeUtf(art, 32);
        buffer.writeInt(tint);
        buffer.writeBoolean(transparent);
    }

    public void handle(PacketContext context) {
        context.enqueueWork(() -> {
            ServerPlayer player = context.sender();
            if (player == null)
                return;
            ItemStack stack = player.getItemInHand(hand);
            if (!ScrewdriverItem.isScrewdriver(stack))
                return;
            DecalSelection selection = DecalSelection.read(stack).withTint(tint).withTransparent(transparent);
            if (ControlDesign.isHash(art)) {
                DecalArt picture = stored(player, art);
                if (picture == null)
                    return;
                selection = selection.withArt(art, picture);
            }
            selection.apply(stack);
        });
    }

    private static DecalArt stored(ServerPlayer player, String art) {
        byte[] blob = DesignStore.get(player.server, art);
        if (!DecalArt.isDecal(blob))
            return null;
        try {
            return DecalArt.decode(blob);
        } catch (Exception e) {
            CreateFlippies.LOGGER.warn("Stored decal {} does not decode: {}", art, e.toString());
            return null;
        }
    }
}
