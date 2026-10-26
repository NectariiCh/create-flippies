package com.nectariich.create_flippies.client.display.editor;

import com.nectariich.create_flippies.CreateFlippies;

import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RenderGuiOverlayEvent;
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

    /** Last, so the roll the display needs is the one the view gets. */
    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void onCameraAngles(ViewportEvent.ComputeCameraAngles event) {
        if (EditorCamera.active())
            event.setRoll(EditorCamera.roll(event.getRoll()));
    }

    @SubscribeEvent
    public static void onRenderOverlay(RenderGuiOverlayEvent.Pre event) {
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
