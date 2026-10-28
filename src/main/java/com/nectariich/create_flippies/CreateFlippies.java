package com.nectariich.create_flippies;

import com.mojang.logging.LogUtils;
import com.nectariich.create_flippies.content.gauge.UniversalGaugeDisplayTarget;
import com.nectariich.create_flippies.content.circuit_breaker.CircuitBreakerType;
import com.nectariich.create_flippies.content.design_workbench.DesignTransfers;
import com.nectariich.create_flippies.content.design_workbench.DesignedButtonControlType;
import com.nectariich.create_flippies.content.design_workbench.DesignedRackControlType;
import com.nectariich.create_flippies.content.design_workbench.DesignedGaugeControlType;
import com.nectariich.create_flippies.content.design_workbench.DesignedScreenControlType;
import com.nectariich.create_flippies.content.design_workbench.DesignedSwitchControlType;
import com.nectariich.create_flippies.content.display.AlphanumDisplayControlType;
import com.nectariich.create_flippies.content.display.AlphanumDisplayTarget;
import com.nectariich.create_flippies.content.label.PanelLabelControlType;
import com.nectariich.create_flippies.content.plc.PlcSmallControlType;
import com.nectariich.create_flippies.content.psu.PsuType;
import com.nectariich.create_flippies.content.relay.RelayMountType;
import com.nectariich.create_flippies.content.terminal_block.TerminalBlockType;
import com.nectariich.create_flippies.content.host.ControlHostDisplayTarget;
import com.nectariich.create_flippies.content.indicator.IndicatorControlType;
import com.nectariich.create_flippies.content.procedural_gauge.ProceduralGaugeDisplayTarget;
import com.nectariich.create_flippies.content.procedural_gauge.EdgewiseVoltmeterControlType;
import com.nectariich.create_flippies.content.procedural_gauge.ProceduralVoltmeterControlType;
import com.nectariich.create_flippies.content.procedural_gauge.SquareProceduralGaugeControlType;
import com.nectariich.create_flippies.content.rotary_switch.RotarySwitchControlType;
import com.nectariich.create_flippies.compat.ClientOnly;
import com.nectariich.create_flippies.compat.Ids;
import com.nectariich.create_flippies.foundation.ClientConfig;
import com.nectariich.create_flippies.network.ModPackets;
import com.nectariich.create_flippies.registry.ModBlockEntities;
import com.nectariich.create_flippies.registry.ModBlocks;
import com.nectariich.create_flippies.registry.ModCreativeTabs;
import com.nectariich.create_flippies.registry.ModEntityTypes;
import com.nectariich.create_flippies.foundation.ServerConfig;
import com.nectariich.create_flippies.registry.ModItems;
import com.nectariich.create_flippies.registry.ModSounds;
import com.simibubi.create.api.behaviour.display.DisplayTarget;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.lifecycle.FMLClientSetupEvent;
import net.minecraftforge.fml.event.lifecycle.FMLCommonSetupEvent;
import net.minecraftforge.fml.config.ModConfig;
//? if forge {
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
//?} else {
/*import net.neoforged.fml.ModContainer;
*///?}
import org.slf4j.Logger;

@Mod(CreateFlippies.MOD_ID)
public class CreateFlippies {
    public static final String MOD_ID = "create_flippies";
    public static final String NAME = "Create: Flippies";
    public static final Logger LOGGER = LogUtils.getLogger();

    //? if forge {
    public CreateFlippies() {
        FMLJavaModLoadingContext context = FMLJavaModLoadingContext.get();
        IEventBus modBus = context.getModEventBus();
    //?} else {
    /*public CreateFlippies(IEventBus modBus, ModContainer context) {
        // NeoForge registers payloads from an event of their own, not in common setup
        modBus.addListener(ModPackets::register);
    *///?}

        ModBlocks.register(modBus);
        ModItems.register(modBus);
        ModBlockEntities.register(modBus);
        ModCreativeTabs.register(modBus);
        ModSounds.register(modBus);
        // PC
        ModEntityTypes.register(modBus);
        context.registerConfig(ModConfig.Type.SERVER, ServerConfig.SPEC);
        // PC end
        // WIRE GAUGES: registered only without Power Plant Grid, and remapped in saves
        // to and from its wires as it is installed or removed
        com.nectariich.create_flippies.registry.ModWires.register(modBus);
        //? if forge {
        MinecraftForge.EVENT_BUS.register(com.nectariich.create_flippies.foundation.wire.gauge.WireGaugeCompat.class);
        //?} else {
        /*modBus.addListener(com.nectariich.create_flippies.foundation.wire.gauge.WireGaugeCompat::onRegister);
        *///?}
        // WIRE GAUGES end

        // Client type, so the settings are per-installation and never loaded on a
        // dedicated server. ClientConfig imports nothing client-only, so naming it
        // from common code is safe; and registerConfig is an inherited instance
        // method, so the context already in hand does the job.
        context.registerConfig(ModConfig.Type.CLIENT, ClientConfig.SPEC);

        modBus.addListener(this::commonSetup);
        modBus.addListener(this::clientSetup);

        // Flywheel partials must exist before model baking starts
        ClientOnly.run(() -> com.nectariich.create_flippies.client.ModPartialModels::init);

        MinecraftForge.EVENT_BUS.register(this);
    }

    private void commonSetup(final FMLCommonSetupEvent event) {
        //? if forge {
        ModPackets.register();
        //?}
        // Control kinds are named by their saves, so every kind a world might hold
        // has to be in the catalogue before any host block entity is read
        RotarySwitchControlType.register();
        PanelLabelControlType.register();
        ProceduralVoltmeterControlType.register();
        EdgewiseVoltmeterControlType.register();
        IndicatorControlType.register();
        SquareProceduralGaugeControlType.register();
        CircuitBreakerType.register();
        PlcSmallControlType.register();
        PsuType.register();
        RelayMountType.register();
        TerminalBlockType.register();
        AlphanumDisplayControlType.register();
        com.nectariich.create_flippies.content.speaker.AlarmSpeakerControlType.register();
        DesignedSwitchControlType.register();
        DesignedButtonControlType.register();
        DesignedRackControlType.register();
        DesignedGaugeControlType.register();
        DesignedScreenControlType.register();
        // EDGEWISE GAUGES
        com.nectariich.create_flippies.content.procedural_gauge.EdgewiseProceduralGaugeControlType.register();
        event.enqueueWork(() -> DisplayTarget.BY_BLOCK_ENTITY
                .register(ModBlockEntities.EDGEWISE_PROCEDURAL_GAUGE.get(), new ProceduralGaugeDisplayTarget()));
        // EDGEWISE GAUGES end
        event.enqueueWork(() -> {
            DisplayTarget.BY_BLOCK_ENTITY
                    .register(ModBlockEntities.UNIVERSAL_GAUGE.get(), new UniversalGaugeDisplayTarget());
            DisplayTarget.BY_BLOCK_ENTITY
                    .register(ModBlockEntities.SQUARE_PROCEDURAL_GAUGE.get(), new ProceduralGaugeDisplayTarget());
            DisplayTarget.BY_BLOCK_ENTITY
                    .register(ModBlockEntities.ALPHANUM_DISPLAY.get(), new AlphanumDisplayTarget());
            // Every block that can carry controls is a display target, because the
            // labels, gauges and displays mounted in it are; one holding none accepts
            // text and does nothing with it.
            //
            // Both holders have to be named. The target reads its rows off
            // ControlRackHolder and so works for either, but this registry is keyed by
            // block entity type, so a holder left out here is not a target at all —
            // which is what made a gauge screwed to a panel refuse a link while the
            // same gauge on a bare host accepted one. A DIN rail is deliberately not
            // here: it only seats DinRailMountable kinds, and none of those is a
            // LinkDrivenControl, so a rail can never hold a row to write to.
            ControlHostDisplayTarget mountedControls = new ControlHostDisplayTarget();
            DisplayTarget.BY_BLOCK_ENTITY.register(ModBlockEntities.CONTROL_HOST.get(), mountedControls);
            DisplayTarget.BY_BLOCK_ENTITY.register(ModBlockEntities.LARGE_PANEL.get(), mountedControls);
        });
        LOGGER.info("{} initialized", NAME);
    }

    private void clientSetup(final FMLClientSetupEvent event) {
    }

    /** A half-finished model upload is dropped with the player who was sending it. */
    @SubscribeEvent
    public void onLogout(PlayerEvent.PlayerLoggedOutEvent event) {
        if (event.getEntity() instanceof ServerPlayer player)
            DesignTransfers.forget(player);
    }

    public static ResourceLocation asResource(String path) {
        return Ids.of(MOD_ID, path);
    }
}
