package com.nectariich.create_flippies.content.screwdriver;

import com.nectariich.create_flippies.CreateFlippies;
import com.nectariich.create_flippies.foundation.protection.BreakArea;
import com.nectariich.create_flippies.foundation.protection.ProtectionIndex;

import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Player;
import net.minecraftforge.event.level.BlockEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/**
 * What a protected area actually does: it stops the player holding a screwdriver
 * from breaking blocks inside it.
 *
 * Holding the tool is the whole of the condition, and that is the point rather than
 * a limitation. This is not world protection — it makes no claim against other
 * players, it is not a permission system, and a player with the tool in their chest
 * mines exactly as before. It is a guard on the one hand that is deliberately being
 * waved at a wall of instruments: the screwdriver is used up close, against panels
 * that took a while to build, and one mistimed click — instant, in creative — used
 * to take a wall of them out. The tool used to answer that by refusing to mine at
 * all, which cost the player the ability to clear the wall they were working on;
 * this answers it by refusing to mine the parts they have said are finished.
 *
 * The client is asked the same question from
 * {@link ScrewdriverInteractions#onLeftClickBlock} so the swing stops before the
 * block visibly breaks. The server answering it here as well is what makes it true
 * rather than a client's opinion — {@code BreakEvent} is the last gate every path
 * to a broken block passes through, creative's instant break included.
 */
@Mod.EventBusSubscriber(modid = CreateFlippies.MOD_ID)
public final class BreakProtection {

    private static final String MESSAGE = "message." + CreateFlippies.MOD_ID + ".screwdriver.protected";

    private BreakProtection() {}

    /** The area stopping this player breaking that block, or null if nothing is. */
    public static BreakArea stopping(Player player, BlockPos pos) {
        if (player == null || ScrewdriverItem.heldBy(player) == null)
            return null;
        return ProtectionIndex.of(player.level()).at(pos);
    }

    public static boolean stops(Player player, BlockPos pos) {
        return stopping(player, pos) != null;
    }

    /**
     * Says why the click did nothing, above the hotbar where a refusal belongs.
     * Called on the client, because that is the side that stopped the swing and the
     * side the message would have to be sent to anyway.
     */
    public static void refuse(Player player) {
        player.displayClientMessage(Component.translatable(MESSAGE).withStyle(ChatFormatting.RED), true);
    }

    @SubscribeEvent
    public static void onBreak(BlockEvent.BreakEvent event) {
        if (stops(event.getPlayer(), event.getPos()))
            event.setCanceled(true);
    }
}
