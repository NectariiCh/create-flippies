package com.nectariich.create_flippies.client.design;

import com.nectariich.create_flippies.CreateFlippies;

import net.minecraft.client.Minecraft;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.ClientPlayerNetworkEvent;
import net.minecraftforge.client.event.RenderHandEvent;
import net.minecraftforge.client.event.RenderLevelStageEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.level.LevelEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/** The frame and connection hooks the designed controls' models and the workbench preview need. */
@Mod.EventBusSubscriber(modid = CreateFlippies.MOD_ID, value = Dist.CLIENT)
public final class DesignClientEvents {

    private DesignClientEvents() {}

    @SubscribeEvent
    public static void onRenderTick(TickEvent.RenderTickEvent event) {
        if (event.phase == TickEvent.Phase.END) {
            DesignedModels.sweep();
            ClientDesigns.sweepVariants();
        }
    }

    @SubscribeEvent
    public static void onRenderLevel(RenderLevelStageEvent event) {
        RenderLevelStageEvent.Stage stage = event.getStage();
        if (stage == RenderLevelStageEvent.Stage.AFTER_SKY) {
            // Before the block entity pass: placed controls' text is recorded from here
            DesignTextRenderer.beginLevel();
        } else if (stage == RenderLevelStageEvent.Stage.AFTER_TRANSLUCENT_BLOCKS) {
            // After Hypergraphical's opaque pass, which always comes before translucent
            // blocks, so the text of placed controls and of the preview lands on the model
            DesignTextRenderer.flushPlaced(Minecraft.getInstance().renderBuffers().bufferSource());
            DesignPreview.render(event.getPoseStack(), event.getCamera().getPosition());
        }
    }

    @SubscribeEvent
    public static void onLevelUnload(LevelEvent.Unload event) {
        if (event.getLevel().isClientSide()) {
            DesignedModels.clear();
            DesignPreview.hide();
            DesignCamera.reset();
        }
    }

    /** The camera is out of the player's head, so their hand is not in front of it. */
    @SubscribeEvent
    public static void onRenderHand(RenderHandEvent event) {
        if (DesignCamera.active())
            event.setCanceled(true);
    }

    @SubscribeEvent
    public static void onLogout(ClientPlayerNetworkEvent.LoggingOut event) {
        ClientDesigns.onLogout();
        com.nectariich.create_flippies.client.decal.ClientDecals.onLogout();
    }
}
