package com.nectariich.create_flippies.client.display.editor;

import com.nectariich.create_flippies.CreateFlippies;

import net.minecraftforge.api.distmarker.Dist;
//? if forge {
import net.minecraftforge.client.event.RenderGuiOverlayEvent;
//?} else {
/*import net.neoforged.neoforge.client.event.RenderGuiLayerEvent;
*///?}
import net.minecraftforge.client.event.RenderHandEvent;
import net.minecraftforge.client.event.RenderHighlightEvent;
import net.minecraftforge.client.event.ViewportEvent;
import net.minecraftforge.event.level.LevelEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/**
 * The frame hooks {@link EditorCamera} needs: the roll that levels the display on
 * screen, and everything that would otherwise stand between the player and the glass
 * while the camera is out of their head (the HUD, the hand, and the outline of the
 * block they clicked, which is the display itself).
 */
@Mod.EventBusSubscriber(modid = CreateFlippies.MOD_ID, value = Dist.CLIENT)
public final class EditorCameraEvents {

    private EditorCameraEvents() {}

    // NeoForge fires the camera angles event inside Camera.setup, before CameraMixin
    // replaces the rotation, so there the roll is set by CameraMixin itself
    //? if forge {
    /** Last, so the roll the display needs is the one the view gets. */
    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void onCameraAngles(ViewportEvent.ComputeCameraAngles event) {
        if (EditorCamera.active())
            event.setRoll(EditorCamera.roll(event.getRoll()));
    }
    //?}

    @SubscribeEvent
    //? if forge {
    public static void onRenderOverlay(RenderGuiOverlayEvent.Pre event) {
    //?} else {
    /*public static void onRenderOverlay(RenderGuiLayerEvent.Pre event) {
    *///?}
        if (EditorCamera.active())
            event.setCanceled(true);
    }

    @SubscribeEvent
    public static void onRenderHand(RenderHandEvent event) {
        if (EditorCamera.active())
            event.setCanceled(true);
    }

    /** Only the block outline: an entity's highlight cannot be cancelled, and is only drawn for debugging. */
    @SubscribeEvent
    public static void onBlockHighlight(RenderHighlightEvent.Block event) {
        if (EditorCamera.active())
            event.setCanceled(true);
    }

    @SubscribeEvent
    public static void onLevelUnload(LevelEvent.Unload event) {
        if (event.getLevel().isClientSide())
            EditorCamera.reset();
    }
}
