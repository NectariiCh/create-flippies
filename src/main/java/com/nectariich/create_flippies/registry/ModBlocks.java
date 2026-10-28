package com.nectariich.create_flippies.registry;

import java.util.Collections;
import java.util.EnumMap;
import java.util.Map;
import java.util.function.Supplier;

import com.nectariich.create_flippies.CreateFlippies;
import com.nectariich.create_flippies.content.design_workbench.DesignWorkbenchBlock;
import com.nectariich.create_flippies.content.din_rail.DinRailBlock;
import com.nectariich.create_flippies.content.display.AlphanumDisplayBlock;
import com.nectariich.create_flippies.content.speaker.AlarmSpeakerBlock;
import com.nectariich.create_flippies.content.speaker.SpeakerKind;
import com.nectariich.create_flippies.content.display.DisplayKind;
import com.nectariich.create_flippies.content.gauge.UniversalGaugeBlock;
import com.nectariich.create_flippies.content.host.ControlHostBlock;
import com.nectariich.create_flippies.content.indicator.IndicatorBlock;
import com.nectariich.create_flippies.content.panel.LargePanelBlock;
import com.nectariich.create_flippies.content.indicator.IndicatorKind;
import com.nectariich.create_flippies.content.rotary_switch.RotarySwitchBlock;
import com.nectariich.create_flippies.content.rotary_switch.RotarySwitchKind;
import com.nectariich.create_flippies.content.procedural_gauge.SquareProceduralGaugeBlock;
import com.nectariich.create_flippies.content.procedural_gauge.ProceduralVoltmeterBlock;
import com.nectariich.create_flippies.content.procedural_gauge.EdgewiseVoltmeterBlock;
import com.nectariich.create_flippies.content.procedural_gauge.VoltmeterRange;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.material.MapColor;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.registries.DeferredRegister;
import com.nectariich.create_flippies.content.pc.PcCaseBlock;

public class ModBlocks {
    public static final DeferredRegister<Block> BLOCKS =
            DeferredRegister.create(Registries.BLOCK, CreateFlippies.MOD_ID);

    public static final Supplier<Block> UNIVERSAL_GAUGE = BLOCKS.register("universal_gauge",
            () -> new UniversalGaugeBlock(BlockBehaviour.Properties.of()
                    .mapColor(MapColor.PODZOL)
                    .strength(1.0f)
                    .sound(SoundType.WOOD)
                    .noOcclusion()));

    /** One Square Procedural Voltmeter block per measurement range. */
    public static final Map<VoltmeterRange, Supplier<Block>> SQUARE_PROCEDURAL_VOLTMETERS = registerVoltmeters();

    public static final Supplier<Block> SQUARE_PROCEDURAL_GAUGE = BLOCKS.register("square_procedural_gauge",
            () -> new SquareProceduralGaugeBlock(panelProperties()));

    public static final Map<VoltmeterRange, Supplier<Block>> EDGEWISE_PROCEDURAL_VOLTMETERS =
            registerEdgewiseVoltmeters();

    // EDGEWISE GAUGES
    public static final Supplier<Block> EDGEWISE_PROCEDURAL_GAUGE = BLOCKS.register("edgewise_procedural_gauge",
            () -> new com.nectariich.create_flippies.content.procedural_gauge.EdgewiseProceduralGaugeBlock(
                    panelProperties()));
    // EDGEWISE GAUGES end

    /** One Rotary Switch block per number of detents; the rest is appearance. */
    public static final Map<RotarySwitchKind, Supplier<Block>> ROTARY_SWITCHES = registerRotarySwitches();

    /**
     * One Indicator block per cap size and rated voltage. Nothing else about one is
     * among them: the lens colour, whether the cap clicks and what is printed on it
     * are per-unit state a screwdriver sets, and nothing electrical or geometric
     * follows from any of them.
     */
    public static final Map<IndicatorKind, Supplier<Block>> INDICATORS = registerIndicators();

    /**
     * One Alphanumeric Display block per body and rated supply voltage. Nothing else
     * about one is among them: how the line it is sent is set — the face, the
     * character height, the colour — is per-unit state a screwdriver sets, and
     * nothing electrical or geometric follows from any of it.
     */
    public static final Map<DisplayKind, Supplier<Block>> ALPHANUM_DISPLAYS = registerDisplays();

    /**
     * One Alarm Speaker block per channel input rating and supply rating, both of
     * them resistances on a live line. What it plays is per-unit screwdriver state.
     */
    public static final Map<SpeakerKind, Supplier<Block>> ALARM_SPEAKERS = registerSpeakers();

    /**
     * Rail for panel gear to clip onto. One block for all three rail counts: a
     * rail is added to one already on the wall rather than placed as its own
     * kind, so the count is a block state and not a registry entry.
     */
    public static final Supplier<Block> DIN_RAIL = BLOCKS.register("din_rail",
            () -> new DinRailBlock(panelProperties()));

    /**
     * The frame that holds screwdriver-mounted controls. It has no item: it is put
     * into the world by mounting a control and taken out by removing the last one,
     * so a stack of them would mean nothing.
     */
    public static final Supplier<Block> CONTROL_HOST = BLOCKS.register("control_host",
            () -> new ControlHostBlock(panelProperties()));

    /**
     * One cell of a large panel. It has no item, for the reason the host has none:
     * a panel is put into the world a whole rectangle at a time by the screwdriver
     * and comes out the same way, so a stack of single cells would mean nothing.
     */
    public static final Supplier<Block> LARGE_PANEL = BLOCKS.register("large_panel",
            () -> new LargePanelBlock(panelProperties()));

    /** The bench custom controls are designed at. Wood, like the crafting table it is built from. */
    public static final Supplier<Block> DESIGN_WORKBENCH = BLOCKS.register("design_workbench",
            () -> new DesignWorkbenchBlock(BlockBehaviour.Properties.of()
                    .mapColor(MapColor.WOOD)
                    .strength(2.5f)
                    .sound(SoundType.WOOD)));

    private static Map<RotarySwitchKind, Supplier<Block>> registerRotarySwitches() {
        Map<RotarySwitchKind, Supplier<Block>> map = new EnumMap<>(RotarySwitchKind.class);
        for (RotarySwitchKind kind : RotarySwitchKind.values())
            map.put(kind, BLOCKS.register(kind.blockId(),
                    () -> new RotarySwitchBlock(kind, panelProperties())));
        return Collections.unmodifiableMap(map);
    }

    private static Map<IndicatorKind, Supplier<Block>> registerIndicators() {
        Map<IndicatorKind, Supplier<Block>> map = new EnumMap<>(IndicatorKind.class);
        for (IndicatorKind kind : IndicatorKind.values())
            map.put(kind, BLOCKS.register(kind.blockId(),
                    () -> new IndicatorBlock(kind, panelProperties())));
        return Collections.unmodifiableMap(map);
    }

    private static Map<DisplayKind, Supplier<Block>> registerDisplays() {
        Map<DisplayKind, Supplier<Block>> map = new EnumMap<>(DisplayKind.class);
        for (DisplayKind kind : DisplayKind.values())
            map.put(kind, BLOCKS.register(kind.blockId(),
                    () -> new AlphanumDisplayBlock(kind, panelProperties())));
        return Collections.unmodifiableMap(map);
    }

    private static Map<SpeakerKind, Supplier<Block>> registerSpeakers() {
        Map<SpeakerKind, Supplier<Block>> map = new EnumMap<>(SpeakerKind.class);
        for (SpeakerKind kind : SpeakerKind.values())
            map.put(kind, BLOCKS.register(kind.blockId(),
                    () -> new AlarmSpeakerBlock(kind, panelProperties())));
        return Collections.unmodifiableMap(map);
    }

    private static Map<VoltmeterRange, Supplier<Block>> registerVoltmeters() {
        Map<VoltmeterRange, Supplier<Block>> map = new EnumMap<>(VoltmeterRange.class);
        for (VoltmeterRange range : VoltmeterRange.values())
            map.put(range, BLOCKS.register(range.blockId(),
                    () -> new ProceduralVoltmeterBlock(range, panelProperties())));
        return Collections.unmodifiableMap(map);
    }

    private static Map<VoltmeterRange, Supplier<Block>> registerEdgewiseVoltmeters() {
        Map<VoltmeterRange, Supplier<Block>> map = new EnumMap<>(VoltmeterRange.class);
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
    public static final Supplier<Block> PC_CASE = BLOCKS.register("pc_case",
            () -> new PcCaseBlock(BlockBehaviour.Properties.of()
                    .mapColor(MapColor.COLOR_LIGHT_GRAY)
                    .strength(1.5f)
                    .sound(SoundType.METAL)
                    .noOcclusion()));

    /** The network modem (docs/PC_SPEC.md 12.4.5), powered over its ethernet cord. */
    public static final Supplier<Block> NETWORK_MODEM = BLOCKS.register("network_modem",
            () -> new com.nectariich.create_flippies.content.modem.NetworkModemBlock(BlockBehaviour.Properties.of()
                    .mapColor(MapColor.COLOR_GRAY)
                    .strength(1.0f)
                    .sound(SoundType.METAL)
                    .noOcclusion()));
    // PC end

    public static void register(IEventBus modBus) {
        BLOCKS.register(modBus);
    }
}
