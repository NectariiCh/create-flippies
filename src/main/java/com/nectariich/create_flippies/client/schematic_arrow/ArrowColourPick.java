package com.nectariich.create_flippies.client.schematic_arrow;

import com.nectariich.create_flippies.CreateFlippies;
import com.nectariich.create_flippies.content.panel.LargePanelBlockEntity;
import com.nectariich.create_flippies.content.schematic_arrow.PanelArrow;
import com.nectariich.create_flippies.content.screwdriver.ScrewdriverAim;
import com.nectariich.create_flippies.content.screwdriver.ScrewdriverItem;

import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.PauseScreen;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.Component;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.InputEvent;
import net.minecraftforge.client.event.ScreenEvent;
//? if forge {
import net.minecraftforge.event.TickEvent;
//?} else {
/*import net.neoforged.neoforge.client.event.ClientTickEvent;
*///?}
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/**
 * Taking the colour of an arrow already on a panel, for the arrow menu.
 *
 * The menu steps aside while the player looks for the arrow: it is closed but kept,
 * settings and all, and opened again by a right click on an arrow, which hands it that
 * arrow's colour, or by Esc, which hands it nothing. Esc would open the pause menu with
 * no screen up, so the pause menu is turned back into the arrow menu on its way in.
 *
 * Everything here is the client's: the arrows are read from the cells the client
 * already draws, and the colour only reaches the server when the menu is closed with
 * Done, like any other change in it. While picking, both clicks are swallowed, so
 * looking for a colour never draws, moves or takes off an arrow. Any other screen
 * opening, or the screwdriver leaving the hand the menu was opened for, gives up.
 */
@Mod.EventBusSubscriber(modid = CreateFlippies.MOD_ID, value = Dist.CLIENT)
public final class ArrowColourPick {

    private static final String LANG_PREFIX = "gui." + CreateFlippies.MOD_ID + ".screwdriver.arrow.";

    /** The menu waiting for a colour, or null when nothing is being picked. */
    private static ArrowScreen waiting;

    private ArrowColourPick() {}

    public static boolean active() {
        return waiting != null;
    }

    /** Puts the menu aside until an arrow is right-clicked or Esc is pressed. */
    static void start(ArrowScreen screen) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player == null)
            return;
        waiting = screen;
        minecraft.setScreen(null);
        minecraft.player.displayClientMessage(Component.translatable(LANG_PREFIX + "pick.hint")
                .withStyle(ChatFormatting.AQUA), true);
    }

    /** The arrow under the crosshair, the one a right click would take the colour of; or null. */
    public static PanelArrow arrowUnder(LocalPlayer player) {
        Minecraft minecraft = Minecraft.getInstance();
        if (!(minecraft.hitResult instanceof BlockHitResult hit) || hit.getType() != HitResult.Type.BLOCK)
            return null;
        LargePanelBlockEntity cell = LargePanelBlockEntity.cellAt(player.level(), hit.getBlockPos());
        if (cell == null || cell.nearest(hit.getLocation()) == null)
            return null;
        ScrewdriverAim.Surface surface = ScrewdriverAim.surfaceAt(player.level(), hit.getBlockPos(),
                hit.getDirection(), hit.getLocation(), player.getEyePosition());
        return cell.arrowAt(surface.plane(), surface.depth(), hit.getLocation());
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void onClick(InputEvent.InteractionKeyMappingTriggered event) {
        if (waiting == null)
            return;
        event.setSwingHand(false);
        event.setCanceled(true);
        LocalPlayer player = Minecraft.getInstance().player;
        if (!event.isUseItem() || player == null)
            return;
        PanelArrow arrow = arrowUnder(player);
        if (arrow == null) {
            player.displayClientMessage(Component.translatable(LANG_PREFIX + "pick.none")
                    .withStyle(ChatFormatting.RED), true);
            return;
        }
        ArrowScreen screen = waiting;
        waiting = null;
        screen.takeColour(arrow.style().colour());
        Minecraft.getInstance().setScreen(screen);
    }

    /** Esc, which would open the pause menu, goes back to the arrow menu; any other screen gives up. */
    @SubscribeEvent
    public static void onScreenOpening(ScreenEvent.Opening event) {
        if (waiting == null || event.getNewScreen() == null)
            return;
        ArrowScreen screen = waiting;
        waiting = null;
        if (event.getNewScreen() instanceof PauseScreen)
            event.setNewScreen(screen);
    }

    @SubscribeEvent
    //? if forge {
    public static void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END || waiting == null)
    //?} else {
    /*public static void onClientTick(ClientTickEvent.Post event) {
        if (waiting == null)
    *///?}
            return;
        LocalPlayer player = Minecraft.getInstance().player;
        if (player == null || !ScrewdriverItem.isScrewdriver(player.getItemInHand(waiting.hand())))
            waiting = null;
    }
}
