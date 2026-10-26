package com.nectariich.create_flippies.registry;

import com.nectariich.create_flippies.CreateFlippies;
import com.nectariich.create_flippies.content.gauge.UniversalGaugeBlockEntity;
import com.nectariich.create_flippies.content.din_rail.DinRailBlockEntity;
import com.nectariich.create_flippies.content.display.AlphanumDisplayBlockEntity;
import com.nectariich.create_flippies.content.host.ControlHostBlockEntity;
import com.nectariich.create_flippies.content.indicator.IndicatorBlockEntity;
import com.nectariich.create_flippies.content.panel.LargePanelBlockEntity;
import com.nectariich.create_flippies.content.rotary_switch.RotarySwitchBlockEntity;
import com.nectariich.create_flippies.content.procedural_gauge.SquareProceduralGaugeBlockEntity;
import com.nectariich.create_flippies.content.procedural_gauge.ProceduralVoltmeterBlockEntity;
import com.nectariich.create_flippies.content.procedural_gauge.EdgewiseVoltmeterBlockEntity;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;
import com.nectariich.create_flippies.content.pc.PcCaseBlockEntity;

public class ModBlockEntities {
    public static final DeferredRegister<BlockEntityType<?>> BLOCK_ENTITIES =
            DeferredRegister.create(ForgeRegistries.BLOCK_ENTITY_TYPES, CreateFlippies.MOD_ID);

    public static final RegistryObject<BlockEntityType<UniversalGaugeBlockEntity>> UNIVERSAL_GAUGE =
            BLOCK_ENTITIES.register("universal_gauge", () -> BlockEntityType.Builder
                    .of(UniversalGaugeBlockEntity::new, ModBlocks.UNIVERSAL_GAUGE.get())
                    .build(null));

    public static final RegistryObject<BlockEntityType<ProceduralVoltmeterBlockEntity>> SQUARE_PROCEDURAL_VOLTMETER =
            BLOCK_ENTITIES.register("square_procedural_voltmeter", () -> BlockEntityType.Builder
                    .of(ProceduralVoltmeterBlockEntity::new,
                            ModBlocks.SQUARE_PROCEDURAL_VOLTMETERS.values().stream()
                                    .map(RegistryObject::get)
                                    .toArray(Block[]::new))
                    .build(null));

    public static final RegistryObject<BlockEntityType<SquareProceduralGaugeBlockEntity>> SQUARE_PROCEDURAL_GAUGE =
            BLOCK_ENTITIES.register("square_procedural_gauge", () -> BlockEntityType.Builder
                    .of(SquareProceduralGaugeBlockEntity::new, ModBlocks.SQUARE_PROCEDURAL_GAUGE.get())
                    .build(null));

    public static final RegistryObject<BlockEntityType<EdgewiseVoltmeterBlockEntity>> EDGEWISE_PROCEDURAL_VOLTMETER =
            BLOCK_ENTITIES.register("edgewise_procedural_voltmeter", () -> BlockEntityType.Builder
                    .of(EdgewiseVoltmeterBlockEntity::new,
                            ModBlocks.EDGEWISE_PROCEDURAL_VOLTMETERS.values().stream()
                                    .map(RegistryObject::get)
                                    .toArray(Block[]::new))
                    .build(null));

    // EDGEWISE GAUGES
    public static final RegistryObject<BlockEntityType<com.nectariich.create_flippies.content.procedural_gauge
            .EdgewiseProceduralGaugeBlockEntity>> EDGEWISE_PROCEDURAL_GAUGE =
            BLOCK_ENTITIES.register("edgewise_procedural_gauge", () -> BlockEntityType.Builder
                    .of(com.nectariich.create_flippies.content.procedural_gauge
                            .EdgewiseProceduralGaugeBlockEntity::new, ModBlocks.EDGEWISE_PROCEDURAL_GAUGE.get())
                    .build(null));
    // EDGEWISE GAUGES end

    public static final RegistryObject<BlockEntityType<RotarySwitchBlockEntity>> ROTARY_SWITCH =
            BLOCK_ENTITIES.register("rotary_switch", () -> BlockEntityType.Builder
                    .of(RotarySwitchBlockEntity::new,
                            ModBlocks.ROTARY_SWITCHES.values().stream()
                                    .map(RegistryObject::get)
                                    .toArray(Block[]::new))
                    .build(null));

    public static final RegistryObject<BlockEntityType<IndicatorBlockEntity>> INDICATOR =
            BLOCK_ENTITIES.register("indicator", () -> BlockEntityType.Builder
                    .of(IndicatorBlockEntity::new,
                            ModBlocks.INDICATORS.values().stream()
                                    .map(RegistryObject::get)
                                    .toArray(Block[]::new))
                    .build(null));

    public static final RegistryObject<BlockEntityType<AlphanumDisplayBlockEntity>> ALPHANUM_DISPLAY =
            BLOCK_ENTITIES.register("alphanum_display", () -> BlockEntityType.Builder
                    .of(AlphanumDisplayBlockEntity::new,
                            ModBlocks.ALPHANUM_DISPLAYS.values().stream()
                                    .map(RegistryObject::get)
                                    .toArray(Block[]::new))
                    .build(null));

    public static final RegistryObject<BlockEntityType<ControlHostBlockEntity>> CONTROL_HOST =
            BLOCK_ENTITIES.register("control_host", () -> BlockEntityType.Builder
                    .of(ControlHostBlockEntity::new, ModBlocks.CONTROL_HOST.get())
                    .build(null));

    public static final RegistryObject<BlockEntityType<LargePanelBlockEntity>> LARGE_PANEL =
            BLOCK_ENTITIES.register("large_panel", () -> BlockEntityType.Builder
                    .of(LargePanelBlockEntity::new, ModBlocks.LARGE_PANEL.get())
                    .build(null));

    public static final RegistryObject<BlockEntityType<DinRailBlockEntity>> DIN_RAIL =
            BLOCK_ENTITIES.register("din_rail", () -> BlockEntityType.Builder
                    .of(DinRailBlockEntity::new, ModBlocks.DIN_RAIL.get())
                    .build(null));

    // PC
    public static final RegistryObject<BlockEntityType<PcCaseBlockEntity>> PC_CASE =
            BLOCK_ENTITIES.register("pc_case", () -> BlockEntityType.Builder
                    .of(PcCaseBlockEntity::new, ModBlocks.PC_CASE.get())
                    .build(null));

    public static final RegistryObject<BlockEntityType<
            com.nectariich.create_flippies.content.modem.NetworkModemBlockEntity>> NETWORK_MODEM =
            BLOCK_ENTITIES.register("network_modem", () -> BlockEntityType.Builder
                    .of(com.nectariich.create_flippies.content.modem.NetworkModemBlockEntity::new,
                            ModBlocks.NETWORK_MODEM.get())
                    .build(null));

    // PC end


    public static void register(IEventBus modBus) {
        BLOCK_ENTITIES.register(modBus);
    }
}
