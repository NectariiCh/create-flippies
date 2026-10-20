package com.nectariich.create_flippies.registry;

import java.util.Collections;
import java.util.EnumMap;
import java.util.Map;

import com.nectariich.create_flippies.CreateFlippies;
import com.nectariich.create_flippies.content.design_workbench.DesignWorkbenchBlock;
import com.nectariich.create_flippies.content.din_rail.DinRailBlock;
import com.nectariich.create_flippies.content.display.AlphanumDisplayBlock;
import com.nectariich.create_flippies.content.display.DisplayKind;
import com.nectariich.create_flippies.content.gauge.UniversalGaugeBlock;
import com.nectariich.create_flippies.content.host.ControlHostBlock;
import com.nectariich.create_flippies.content.plc.PlcSmallPanelBlock;
import com.nectariich.create_flippies.content.indicator.IndicatorBlock;
import com.nectariich.create_flippies.content.panel.LargePanelBlock;
import com.nectariich.create_flippies.content.indicator.IndicatorKind;
import com.nectariich.create_flippies.content.rotary_switch.RotarySwitchBlock;
import com.nectariich.create_flippies.content.rotary_switch.RotarySwitchKind;
import com.nectariich.create_flippies.content.procedural_gauge.SquareProceduralGaugeBlock;
import com.nectariich.create_flippies.content.procedural_gauge.ProceduralVoltmeterBlock;
import com.nectariich.create_flippies.content.procedural_gauge.EdgewiseVoltmeterBlock;
import com.nectariich.create_flippies.content.procedural_gauge.VoltmeterRange;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.material.MapColor;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;
import com.nectariich.create_flippies.content.keyboard.KeyboardBlock;
import com.nectariich.create_flippies.content.pc.PcCaseBlock;

public class ModBlocks {
    public static final DeferredRegister<Block> BLOCKS =
            DeferredRegister.create(ForgeRegistries.BLOCKS, CreateFlippies.MOD_ID);

    public static final RegistryObject<Block> UNIVERSAL_GAUGE = BLOCKS.register("universal_gauge",
            () -> new UniversalGaugeBlock(BlockBehaviour.Properties.of()
                    .mapColor(MapColor.PODZOL)
                    .strength(1.0f)
                    .sound(SoundType.WOOD)
                    .noOcclusion()));

    /** One Square Procedural Voltmeter block per measurement range. */
    public static final Map<VoltmeterRange, RegistryObject<Block>> SQUARE_PROCEDURAL_VOLTMETERS = registerVoltmeters();

    public static final RegistryObject<Block> SQUARE_PROCEDURAL_GAUGE = BLOCKS.register("square_procedural_gauge",
            () -> new SquareProceduralGaugeBlock(panelProperties()));

    public static final Map<VoltmeterRange, RegistryObject<Block>> EDGEWISE_PROCEDURAL_VOLTMETERS =
            registerEdgewiseVoltmeters();

    // EDGEWISE GAUGES
    public static final RegistryObject<Block> EDGEWISE_PROCEDURAL_GAUGE = BLOCKS.register("edgewise_procedural_gauge",
            () -> new com.nectariich.create_flippies.content.procedural_gauge.EdgewiseProceduralGaugeBlock(
                    panelProperties()));
    // EDGEWISE GAUGES end

    /** One Rotary Switch block per number of detents; the rest is appearance. */
    public static final Map<RotarySwitchKind, RegistryObject<Block>> ROTARY_SWITCHES = registerRotarySwitches();

    /**
     * One Indicator block per cap size and rated voltage. Nothing else about one is
     * among them: the lens colour, whether the cap clicks and what is printed on it
     * are per-unit state a screwdriver sets, and nothing electrical or geometric
     * follows from any of them.
     */
    public static final Map<IndicatorKind, RegistryObject<Block>> INDICATORS = registerIndicators();

    /**
     * One Alphanumeric Display block per body and rated supply voltage. Nothing else
     * about one is among them: how the line it is sent is set — the face, the
     * character height, the colour — is per-unit state a screwdriver sets, and
     * nothing electrical or geometric follows from any of it.
     */
    public static final Map<DisplayKind, RegistryObject<Block>> ALPHANUM_DISPLAYS = registerDisplays();

    /**
     * Rail for panel gear to clip onto. One block for all three rail counts: a
     * rail is added to one already on the wall rather than placed as its own
     * kind, so the count is a block state and not a registry entry.
     */
    public static final RegistryObject<Block> DIN_RAIL = BLOCKS.register("din_rail",
            () -> new DinRailBlock(panelProperties()));

    /**
     * The frame that holds screwdriver-mounted controls. It has no item: it is put
     * into the world by mounting a control and taken out by removing the last one,
     * so a stack of them would mean nothing.
     */
    public static final RegistryObject<Block> CONTROL_HOST = BLOCKS.register("control_host",
            () -> new ControlHostBlock(panelProperties()));

    /**
     * One cell of a large panel. It has no item, for the reason the host has none:
     * a panel is put into the world a whole rectangle at a time by the screwdriver
     * and comes out the same way, so a stack of single cells would mean nothing.
     */
    public static final RegistryObject<Block> LARGE_PANEL = BLOCKS.register("large_panel",
            () -> new LargePanelBlock(panelProperties()));

    /**
     * The panel-mount small PLC. There is no block for the DIN one: that exists
     * only clipped to a rail, so it is a control kind and a plain item, the same
     * arrangement the breakers use.
     */
    public static final RegistryObject<Block> PLC_SMALL_PANEL = BLOCKS.register("plc_small_panel",
            () -> new PlcSmallPanelBlock(panelProperties()));

    /** The bench custom controls are designed at. Wood, like the crafting table it is built from. */
    public static final RegistryObject<Block> DESIGN_WORKBENCH = BLOCKS.register("design_workbench",
            () -> new DesignWorkbenchBlock(BlockBehaviour.Properties.of()
                    .mapColor(MapColor.WOOD)
                    .strength(2.5f)
                    .sound(SoundType.WOOD)));

    private static Map<RotarySwitchKind, RegistryObject<Block>> registerRotarySwitches() {
        Map<RotarySwitchKind, RegistryObject<Block>> map = new EnumMap<>(RotarySwitchKind.class);
        for (RotarySwitchKind kind : RotarySwitchKind.values())
            map.put(kind, BLOCKS.register(kind.blockId(),
                    () -> new RotarySwitchBlock(kind, panelProperties())));
        return Collections.unmodifiableMap(map);
    }

    private static Map<IndicatorKind, RegistryObject<Block>> registerIndicators() {
        Map<IndicatorKind, RegistryObject<Block>> map = new EnumMap<>(IndicatorKind.class);
        for (IndicatorKind kind : IndicatorKind.values())
            map.put(kind, BLOCKS.register(kind.blockId(),
                    () -> new IndicatorBlock(kind, panelProperties())));
        return Collections.unmodifiableMap(map);
    }

    private static Map<DisplayKind, RegistryObject<Block>> registerDisplays() {
        Map<DisplayKind, RegistryObject<Block>> map = new EnumMap<>(DisplayKind.class);
        for (DisplayKind kind : DisplayKind.values())
            map.put(kind, BLOCKS.register(kind.blockId(),
                    () -> new AlphanumDisplayBlock(kind, panelProperties())));
        return Collections.unmodifiableMap(map);
    }

    private static Map<VoltmeterRange, RegistryObject<Block>> registerVoltmeters() {
        Map<VoltmeterRange, RegistryObject<Block>> map = new EnumMap<>(VoltmeterRange.class);
        for (VoltmeterRange range : VoltmeterRange.values())
            map.put(range, BLOCKS.register(range.blockId(),
                    () -> new ProceduralVoltmeterBlock(range, panelProperties())));
        return Collections.unmodifiableMap(map);
    }

    private static Map<VoltmeterRange, RegistryObject<Block>> registerEdgewiseVoltmeters() {
        Map<VoltmeterRange, RegistryObject<Block>> map = new EnumMap<>(VoltmeterRange.class);
        for (VoltmeterRange range : VoltmeterRange.values())
            map.put(range, BLOCKS.register(range.edgewiseBlockId(),
                    () -> new EdgewiseVoltmeterBlock(range, panelProperties())));
        return Collections.unmodifiableMap(map);
    }

    /** Shared look and feel of the panel-mounted devices. */
    private static BlockBehaviour.Properties panelProperties() {
        return BlockBehaviour.Properties.of()
                .mapColor(MapColor.COLOR_GRAY)
                .strength(1.0f)
                .sound(SoundType.METAL)
                .noOcclusion();
    }

    // PC
    /** The PC case (docs/PC_SPEC.md 11.2.2). Its parts are items it holds, not block states. */
    public static final RegistryObject<Block> PC_CASE = BLOCKS.register("pc_case",
            () -> new PcCaseBlock(BlockBehaviour.Properties.of()
                    .mapColor(MapColor.COLOR_LIGHT_GRAY)
                    .strength(1.5f)
                    .sound(SoundType.METAL)
                    .noOcclusion()));

    /** The keyboard (docs/PC_SPEC.md 11.2.12), typed on through a data cord to a case. */
    public static final RegistryObject<Block> KEYBOARD = BLOCKS.register("keyboard",
            () -> new KeyboardBlock(BlockBehaviour.Properties.of()
                    .mapColor(MapColor.COLOR_LIGHT_GRAY)
                    .strength(0.8f)
                    .sound(SoundType.STONE)
                    .noOcclusion()));
    // PC end

    // CRT
    /** The CRT monitor (docs/PC_SPEC.md 11.2.14): shows a case's VGA display on its screen. */
    public static final RegistryObject<Block> CRT = BLOCKS.register("crt",
            () -> new com.nectariich.create_flippies.content.crt.CrtBlock(BlockBehaviour.Properties.of()
                    .mapColor(MapColor.COLOR_LIGHT_GRAY)
                    .strength(1.5f)
                    .sound(SoundType.METAL)
                    .noOcclusion()));
    // CRT end

    public static void register(IEventBus modBus) {
        BLOCKS.register(modBus);
    }
}
