package com.nectariich.create_flippies.registry;

import com.nectariich.create_flippies.CreateFlippies;
import com.nectariich.create_flippies.content.procedural_gauge.VoltmeterRange;
import java.util.Collections;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.Map;

import com.nectariich.create_flippies.content.circuit_breaker.CircuitBreakerType;
import com.nectariich.create_flippies.content.design_workbench.DesignKind;
import com.nectariich.create_flippies.content.design_workbench.DesignedControlItem;
import com.nectariich.create_flippies.content.display.DisplayKind;
import com.nectariich.create_flippies.content.indicator.IndicatorKind;
import com.nectariich.create_flippies.content.psu.PsuType;
import com.nectariich.create_flippies.content.relay.RelayType;
import com.nectariich.create_flippies.content.terminal_block.TerminalBlockType;
import com.nectariich.create_flippies.content.screwdriver.ScrewdriverItem;
import com.nectariich.create_flippies.content.rotary_switch.RotarySwitchKind;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;
import com.nectariich.create_flippies.content.data_cord.DataCordItem;
import com.nectariich.create_flippies.content.pc.FloppyDiskItem;
import com.nectariich.create_flippies.content.pc.PcCpuItem;
import com.nectariich.create_flippies.content.pc.PcCpuType;
import com.nectariich.create_flippies.content.pc.PcHddItem;
import com.nectariich.create_flippies.content.pc.PcHddType;
import com.nectariich.create_flippies.content.pc.PcMotherboardItem;
import com.nectariich.create_flippies.content.pc.PcPartItem;
import com.nectariich.create_flippies.content.pc.PcRamItem;
import com.nectariich.create_flippies.content.pc.PcRamType;

public class ModItems {
    public static final DeferredRegister<Item> ITEMS =
            DeferredRegister.create(ForgeRegistries.ITEMS, CreateFlippies.MOD_ID);

    public static final RegistryObject<Item> SCREWDRIVER = ITEMS.register("screwdriver",
            () -> new ScrewdriverItem(new Item.Properties().stacksTo(1)));

    public static final RegistryObject<Item> UNIVERSAL_GAUGE = ITEMS.register("universal_gauge",
            () -> new BlockItem(ModBlocks.UNIVERSAL_GAUGE.get(), new Item.Properties()));

    static {
        for (VoltmeterRange range : VoltmeterRange.values())
            ITEMS.register(range.blockId(),
                    () -> new BlockItem(ModBlocks.SQUARE_PROCEDURAL_VOLTMETERS.get(range).get(),
                            new Item.Properties()));
    }

    public static final RegistryObject<Item> SQUARE_PROCEDURAL_GAUGE = ITEMS.register("square_procedural_gauge",
            () -> new BlockItem(ModBlocks.SQUARE_PROCEDURAL_GAUGE.get(), new Item.Properties()));

    static {
        for (VoltmeterRange range : VoltmeterRange.values())
            ITEMS.register(range.edgewiseBlockId(),
                    () -> new BlockItem(ModBlocks.EDGEWISE_PROCEDURAL_VOLTMETERS.get(range).get(),
                            new Item.Properties()));
    }

    // EDGEWISE GAUGES
    public static final RegistryObject<Item> EDGEWISE_PROCEDURAL_GAUGE = ITEMS.register("edgewise_procedural_gauge",
            () -> new BlockItem(ModBlocks.EDGEWISE_PROCEDURAL_GAUGE.get(), new Item.Properties()));
    // EDGEWISE GAUGES end

    static {
        for (RotarySwitchKind kind : RotarySwitchKind.values())
            ITEMS.register(kind.blockId(),
                    () -> new BlockItem(ModBlocks.ROTARY_SWITCHES.get(kind).get(), new Item.Properties()));
    }

    static {
        for (IndicatorKind kind : IndicatorKind.values())
            ITEMS.register(kind.blockId(),
                    () -> new BlockItem(ModBlocks.INDICATORS.get(kind).get(), new Item.Properties()));
    }

    static {
        for (DisplayKind kind : DisplayKind.values())
            ITEMS.register(kind.blockId(),
                    () -> new BlockItem(ModBlocks.ALPHANUM_DISPLAYS.get(kind).get(), new Item.Properties()));
    }

    static {
        for (com.nectariich.create_flippies.content.speaker.SpeakerKind kind
                : com.nectariich.create_flippies.content.speaker.SpeakerKind.values())
            ITEMS.register(kind.blockId(),
                    () -> new BlockItem(ModBlocks.ALARM_SPEAKERS.get(kind).get(), new Item.Properties()));
    }

    /**
     * One rail. Placing it puts a single rail on the wall; right-clicking a rail
     * already there with another adds to it, up to three.
     */
    public static final RegistryObject<Item> DIN_RAIL = ITEMS.register("din_rail",
            () -> new BlockItem(ModBlocks.DIN_RAIL.get(), new Item.Properties()));

    /**
     * A plain item for the same reason a breaker is: the DIN PLC exists only
     * clipped to a rail, so there is no block form for an item to place. What makes
     * it mountable is that {@link com.nectariich.create_flippies.content.plc.PlcSmallControlType}
     * names it from {@code asItem}.
     */
    public static final RegistryObject<Item> PLC_S32F103_D_DIN = ITEMS.register("plc_s32f103-d_din",
            () -> new Item(new Item.Properties()));

    /** The panel-mount PLC is an ordinary block, and places bare-handed. */
    public static final RegistryObject<Item> PLC_SMALL_PANEL = ITEMS.register("plc_small_panel",
            () -> new BlockItem(ModBlocks.PLC_SMALL_PANEL.get(), new Item.Properties()));

    /**
     * A breaker is a plain item for the same reason a panel label is: it exists
     * only clipped to a rail, so there is no block form for an item to place. What
     * makes it mountable is that its control kind names it from {@code asItem}.
     */
    public static final Map<String, RegistryObject<Item>> CIRCUIT_BREAKERS = registerCircuitBreakers();

    private static Map<String, RegistryObject<Item>> registerCircuitBreakers() {
        Map<String, RegistryObject<Item>> items = new LinkedHashMap<>();
        for (CircuitBreakerType type : CircuitBreakerType.all())
            items.put(type.id(), ITEMS.register(type.id(), () -> new Item(new Item.Properties())));
        return Collections.unmodifiableMap(items);
    }

    /**
     * A power supply is a plain item for the same reason a breaker is: it exists
     * only clipped to a rail. One per row of PsuType's table, keyed by its id.
     */
    public static final Map<String, RegistryObject<Item>> PSUS = registerPsus();

    private static Map<String, RegistryObject<Item>> registerPsus() {
        Map<String, RegistryObject<Item>> items = new LinkedHashMap<>();
        for (PsuType type : PsuType.all())
            items.put(type.id(), ITEMS.register(type.id(), () -> new Item(new Item.Properties())));
        return Collections.unmodifiableMap(items);
    }

    /**
     * The 5-pin relay mount, a plain item for the same reason a breaker is: it
     * exists only clipped to a rail. It clips on empty; see {@link #RELAYS}.
     */
    public static final RegistryObject<Item> DIN_RELAY_MOUNT_5PIN = ITEMS.register("din_relay_mount_5pin",
            () -> new Item(new Item.Properties()));

    /** The 8-pin relay mount, for the same reason; it takes only 8-pin relays. */
    public static final RegistryObject<Item> DIN_RELAY_MOUNT_8PIN = ITEMS.register("din_relay_mount_8pin",
            () -> new Item(new Item.Properties()));

    /**
     * The plug-in relays, one per row of RelayType's table, keyed by its id. Not
     * controls of their own: right-clicking a mount already on a rail with one
     * pushes it in.
     */
    public static final Map<String, RegistryObject<Item>> RELAYS = registerRelays();

    private static Map<String, RegistryObject<Item>> registerRelays() {
        Map<String, RegistryObject<Item>> items = new LinkedHashMap<>();
        for (RelayType type : RelayType.all())
            items.put(type.id(), ITEMS.register(type.id(), () -> new Item(new Item.Properties())));
        return Collections.unmodifiableMap(items);
    }

    /**
     * The DIN terminal blocks, plain items for the same reason a breaker is: they
     * exist only clipped to a rail. One per row of TerminalBlockType's table, keyed
     * by its id.
     */
    public static final Map<String, RegistryObject<Item>> TERMINAL_BLOCKS = registerTerminalBlocks();

    private static Map<String, RegistryObject<Item>> registerTerminalBlocks() {
        Map<String, RegistryObject<Item>> items = new LinkedHashMap<>();
        for (TerminalBlockType type : TerminalBlockType.all())
            items.put(type.id(), ITEMS.register(type.id(), () -> new Item(new Item.Properties())));
        return Collections.unmodifiableMap(items);
    }

    /**
     * A plain item and not a BlockItem: a panel label only exists mounted into a
     * host block with the screwdriver, so there is no block form for an item to
     * place. What makes it mountable is that its control kind names it from
     * {@code asItem}, which is what the screwdriver looks up in the player's other
     * hand.
     */
    public static final RegistryObject<Item> PANEL_LABEL = ITEMS.register("panel_label",
            () -> new Item(new Item.Properties()));

    public static final RegistryObject<Item> DESIGN_WORKBENCH = ITEMS.register("design_workbench",
            () -> new BlockItem(ModBlocks.DESIGN_WORKBENCH.get(), new Item.Properties()));

    /**
     * The controls the Design Workbench makes, one item per kind of control a design
     * is built on. What each one looks like is in its NBT, so a bare one means
     * nothing and they are kept out of the creative tab.
     */
    public static final Map<DesignKind, RegistryObject<Item>> DESIGNED_CONTROLS = registerDesignedControls();

    private static Map<DesignKind, RegistryObject<Item>> registerDesignedControls() {
        Map<DesignKind, RegistryObject<Item>> items = new EnumMap<>(DesignKind.class);
        for (DesignKind kind : DesignKind.values())
            items.put(kind, ITEMS.register(kind.itemId(),
                    () -> new DesignedControlItem(kind, new Item.Properties().stacksTo(16))));
        return Collections.unmodifiableMap(items);
    }

    // PC
    /** The PC case, placed with its front panel on and nothing inside (docs/PC_SPEC.md 11.2.3). */
    public static final RegistryObject<Item> PC_CASE = ITEMS.register("pc_case",
            () -> new BlockItem(ModBlocks.PC_CASE.get(), new Item.Properties()));

    public static final RegistryObject<Item> PC_FRONT_PANEL = ITEMS.register("pc_front_panel",
            () -> new PcPartItem(PcPartItem.Kind.FRONT_PANEL, new Item.Properties().stacksTo(16)));

    public static final RegistryObject<Item> PC_MOTHERBOARD = ITEMS.register("pc_motherboard",
            () -> new PcMotherboardItem(new Item.Properties().stacksTo(1)));

    /** One item per row of each family's table, keyed by the row. */
    public static final Map<PcCpuType, RegistryObject<Item>> PC_CPUS = registerPcFamily(PcCpuType.class,
            PcCpuType::id, type -> new PcCpuItem(type, new Item.Properties().stacksTo(1)));
    public static final Map<PcRamType, RegistryObject<Item>> PC_RAM = registerPcFamily(PcRamType.class,
            PcRamType::id, type -> new PcRamItem(type, new Item.Properties().stacksTo(16)));
    public static final Map<PcHddType, RegistryObject<Item>> PC_HDDS = registerPcFamily(PcHddType.class,
            PcHddType::id, type -> new PcHddItem(type, new Item.Properties().stacksTo(1)));

    public static final RegistryObject<Item> FLOPPY_DISK = ITEMS.register("floppy_disk",
            () -> new FloppyDiskItem(new Item.Properties().stacksTo(1)));

    public static final RegistryObject<Item> DATA_CORD = ITEMS.register("data_cord",
            () -> new DataCordItem(new Item.Properties()));

    public static final RegistryObject<Item> KEYBOARD = ITEMS.register("keyboard",
            () -> new BlockItem(ModBlocks.KEYBOARD.get(), new Item.Properties()));

    private static <E extends Enum<E>> Map<E, RegistryObject<Item>> registerPcFamily(Class<E> family,
            java.util.function.Function<E, String> id, java.util.function.Function<E, Item> item) {
        Map<E, RegistryObject<Item>> items = new EnumMap<>(family);
        for (E row : family.getEnumConstants())
            items.put(row, ITEMS.register(id.apply(row), () -> item.apply(row)));
        return Collections.unmodifiableMap(items);
    }
    // PC end

    // CRT
    public static final RegistryObject<Item> CRT = ITEMS.register("crt",
            () -> new BlockItem(ModBlocks.CRT.get(), new Item.Properties()));
    // CRT end

    // WIRE DUCTS
    public static final RegistryObject<Item> WIRE_DUCT = ITEMS.register("wire_duct",
            () -> new com.nectariich.create_flippies.content.wire_duct.WireDuctItem(
                    com.nectariich.create_flippies.foundation.wire.duct.DuctSize.SMALL, new Item.Properties()));

    public static final RegistryObject<Item> LARGE_WIRE_DUCT = ITEMS.register("large_wire_duct",
            () -> new com.nectariich.create_flippies.content.wire_duct.WireDuctItem(
                    com.nectariich.create_flippies.foundation.wire.duct.DuctSize.LARGE, new Item.Properties()));

    public static Item wireDuct(com.nectariich.create_flippies.foundation.wire.duct.DuctSize size) {
        return size == com.nectariich.create_flippies.foundation.wire.duct.DuctSize.LARGE ? LARGE_WIRE_DUCT.get()
                : WIRE_DUCT.get();
    }
    // WIRE DUCTS end

    public static void register(IEventBus modBus) {
        ITEMS.register(modBus);
    }
}
