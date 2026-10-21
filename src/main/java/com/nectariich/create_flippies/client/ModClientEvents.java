package com.nectariich.create_flippies.client;

import com.nectariich.create_flippies.CreateFlippies;
import com.nectariich.create_flippies.registry.ModBlockEntities;

import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.EntityRenderersEvent;
import net.minecraft.client.renderer.block.BlockModelShaper;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.client.resources.model.ModelResourceLocation;
import net.minecraftforge.client.event.ModelEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.lifecycle.FMLClientSetupEvent;
import net.minecraft.world.level.block.state.BlockState;
import com.nectariich.create_flippies.registry.ModBlocks;

@Mod.EventBusSubscriber(modid = CreateFlippies.MOD_ID, value = Dist.CLIENT, bus = Mod.EventBusSubscriber.Bus.MOD)
public class ModClientEvents {

    /**
     * Wraps the panel's baked model in the one that draws its material.
     *
     * A copycat is normally set up by Create's Registrate, which this mod does not
     * use, so the swap is done by hand at the one point Forge offers it. The blank
     * variant is the only one a panel has — it carries no block state at all, every
     * difference between two cells being in their block entities.
     */
    @SubscribeEvent
    public static void wrapPanelModel(ModelEvent.ModifyBakingResult event) {
        int wrapped = 0;
        for (BlockState state : ModBlocks.LARGE_PANEL.get().getStateDefinition().getPossibleStates()) {
            ModelResourceLocation key = BlockModelShaper.stateToModelLocation(state);
            BakedModel baked = event.getModels().get(key);
            if (baked == null)
                continue;
            event.getModels().put(key, new LargePanelModel(baked));
            wrapped++;
        }
        // A panel whose model was not wrapped is a block with a hitbox and no
        // appearance, which looks like a rendering bug anywhere but here
        if (wrapped == 0)
            CreateFlippies.LOGGER.error("No baked model found for the large panel; it will render as nothing");
        else
            CreateFlippies.LOGGER.info("Wrapped {} large panel model(s)", wrapped);
    }

    @SubscribeEvent
    public static void registerRenderers(EntityRenderersEvent.RegisterRenderers event) {
        event.registerBlockEntityRenderer(ModBlockEntities.UNIVERSAL_GAUGE.get(), UniversalGaugeRenderer::new);
        event.registerBlockEntityRenderer(ModBlockEntities.SQUARE_PROCEDURAL_VOLTMETER.get(),
                ProceduralGaugeRenderer::new);
        event.registerBlockEntityRenderer(ModBlockEntities.SQUARE_PROCEDURAL_GAUGE.get(),
                ProceduralGaugeRenderer::new);
        event.registerBlockEntityRenderer(ModBlockEntities.EDGEWISE_PROCEDURAL_VOLTMETER.get(),
                EdgewiseGaugeRenderer::new);
        // EDGEWISE GAUGES
        event.registerBlockEntityRenderer(ModBlockEntities.EDGEWISE_PROCEDURAL_GAUGE.get(),
                EdgewiseGaugeRenderer::new);
        // EDGEWISE GAUGES end
        event.registerBlockEntityRenderer(ModBlockEntities.ROTARY_SWITCH.get(), RotarySwitchRenderer::new);
        event.registerBlockEntityRenderer(ModBlockEntities.INDICATOR.get(), IndicatorRenderer::new);
        event.registerBlockEntityRenderer(ModBlockEntities.ALPHANUM_DISPLAY.get(),
                AlphanumDisplayRenderer::new);
        // A host has no block model, so its renderer is the only thing that draws
        // anything mounted in it
        event.registerBlockEntityRenderer(ModBlockEntities.CONTROL_HOST.get(), ControlHostRenderer::new);
        // A rail has a block model of its own, but nothing clipped to it does
        event.registerBlockEntityRenderer(ModBlockEntities.DIN_RAIL.get(), ControlHostRenderer::new);
        // A panel cell carries its own controls on a chamfer, and draws them the same way
        event.registerBlockEntityRenderer(ModBlockEntities.LARGE_PANEL.get(), ControlHostRenderer::new);
        // PC
        event.registerBlockEntityRenderer(ModBlockEntities.PC_CASE.get(),
                com.nectariich.create_flippies.client.pc.PcCaseRenderer::new);
        event.registerEntityRenderer(com.nectariich.create_flippies.registry.ModEntityTypes.DATA_CORD.get(),
                com.nectariich.create_flippies.client.pc.DataCordRenderer::new);
        // PC end
        // CRT
        event.registerBlockEntityRenderer(ModBlockEntities.CRT.get(),
                com.nectariich.create_flippies.client.pc.CrtRenderer::new);
        // CRT end
        // WIRE DUCTS
        event.registerEntityRenderer(com.nectariich.create_flippies.registry.ModEntityTypes.WIRE_DUCT.get(),
                com.nectariich.create_flippies.client.wire.WireDuctRenderer::new);
        // WIRE DUCTS end
    }

    // WIRE DUCTS 2
    /** A duct item shows the colour of the duct it lays. */
    @SubscribeEvent
    public static void registerItemColors(net.minecraftforge.client.event.RegisterColorHandlersEvent.Item event) {
        event.register((stack, tint) -> tint == 0
                ? 0xFF000000 | com.nectariich.create_flippies.content.wire_duct.WireDuctItem.color(stack) : -1,
                com.nectariich.create_flippies.registry.ModItems.WIRE_DUCT.get(),
                com.nectariich.create_flippies.registry.ModItems.LARGE_WIRE_DUCT.get());
    }
    // WIRE DUCTS 2 end

    /**
     * A mounted control is drawn by a renderer looked up on this side alone; see
     * {@link ControlRenderers} for why the control abstraction cannot carry one
     * itself. Client setup runs after common setup, which is where the control kinds
     * these are keyed on are registered.
     */
    @SubscribeEvent
    public static void registerControlRenderers(FMLClientSetupEvent event) {
        RotarySwitchControlRenderer.register();
        PanelLabelRenderer.register();
        MountedGaugeRenderer.register();
        MountedEdgewiseGaugeRenderer.register();
        CircuitBreakerControlRenderer.register();
        IndicatorControlRenderer.register();
        PlcSmallControlRenderer.register();
        PsuControlRenderer.register();
        RelayMountControlRenderer.register();
        TerminalBlockControlRenderer.register();
        AlphanumDisplayControlRenderer.register();
        com.nectariich.create_flippies.client.design.DesignedControlRenderer.register();
        com.nectariich.create_flippies.client.design.ClientDesigns.setup();
    }
}
