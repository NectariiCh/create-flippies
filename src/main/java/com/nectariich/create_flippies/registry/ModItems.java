package com.nectariich.create_flippies.registry;

import com.nectariich.create_flippies.CreateFlippies;
import com.nectariich.create_flippies.content.procedural_gauge.VoltmeterRange;
import java.util.Collections;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Supplier;

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
import net.minecraft.core.registries.Registries;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.registries.DeferredRegister;
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
            DeferredRegister.create(Registries.ITEM, CreateFlippies.MOD_ID);

    public static final Supplier<Item> SCREWDRIVER = ITEMS.register("screwdriver",
            () -> new ScrewdriverItem(new Item.Properties().stacksTo(1)));

    public static final Supplier<Item> UNIVERSAL_GAUGE = ITEMS.register("universal_gauge",
            () -> new BlockItem(ModBlocks.UNIVERSAL_GAUGE.get(), new Item.Properties()));

    static {
        for (VoltmeterRange range : VoltmeterRange.values())
            ITEMS.register(range.blockId(),
                    () -> new BlockItem(ModBlocks.SQUARE_PROCEDURAL_VOLTMETERS.get(range).get(),
                            new Item.Properties()));
    }

    public static final Supplier<Item> SQUARE_PROCEDURAL_GAUGE = ITEMS.register("square_procedural_gauge",
            () -> new BlockItem(ModBlocks.SQUARE_PROCEDURAL_GAUGE.get(), new Item.Properties()));

    static {
        for (VoltmeterRange range : VoltmeterRange.values())
            ITEMS.register(range.edgewiseBlockId(),
                    () -> new BlockItem(ModBlocks.EDGEWISE_PROCEDURAL_VOLTMETERS.get(range).get(),
                            new Item.Properties()));
    }

    // EDGEWISE GAUGES
    public static final Supplier<Item> EDGEWISE_PROCEDURAL_GAUGE = ITEMS.register("edgewise_procedural_gauge",
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

    /**
     * One rail. Placing it puts a single rail on the wall; right-clicking a rail
     * already there with another adds to it, up to three.
     */
    public static final Supplier<Item> DIN_RAIL = ITEMS.register("din_rail",
            () -> new BlockItem(ModBlocks.DIN_RAIL.get(), new Item.Properties()));

    /**
     * Plain items for the same reason a breaker is: the PLC exists only as a
     * control, clipped to a rail or screwed to a panel, so there is no block form
     * for either item to place. What makes them mountable is that
     * {@link com.nectariich.create_flippies.content.plc.PlcSmallControlType} has a
     * kind for each mounting, and each kind names its item from {@code asItem}.
     */
    public static final Supplier<Item> PLC_S32F103_D_DIN = ITEMS.register("plc_s32f103-d_din",
            () -> new Item(new Item.Properties()));

    public static final Supplier<Item> PLC_S32F103_D_PANEL = ITEMS.register("plc_s32f103-d_panel",
            () -> new Item(new Item.Properties()));

    /**
     * A breaker is a plain item for the same reason a panel label is: it exists
     * only clipped to a rail, so there is no block form for an item to place. What
     * makes it mountable is that its control kind names it from {@code asItem}.
     */
    public static final Map<String, Supplier<Item>> CIRCUIT_BREAKERS = registerCircuitBreakers();

    private static Map<String, Supplier<Item>> registerCircuitBreakers() {
        Map<String, Supplier<Item>> items = new LinkedHashMap<>();
        for (CircuitBreakerType type : CircuitBreakerType.all())
            items.put(type.id(), ITEMS.register(type.id(), () -> new Item(new Item.Properties())));
        return Collections.unmodifiableMap(items);
    }

    /**
     * A power supply is a plain item for the same reason a breaker is: it exists
     * only clipped to a rail. One per row of PsuType's table, keyed by its id.
     */
    public static final Map<String, Supplier<Item>> PSUS = registerPsus();

    private static Map<String, Supplier<Item>> registerPsus() {
        Map<String, Supplier<Item>> items = new LinkedHashMap<>();
        for (PsuType type : PsuType.all())
            items.put(type.id(), ITEMS.register(type.id(), () -> new Item(new Item.Properties())));
        return Collections.unmodifiableMap(items);
    }

    /**
     * The 5-pin relay mount, a plain item for the same reason a breaker is: it
     * exists only clipped to a rail. It clips on empty; see {@link #RELAYS}.
     */
    public static final Supplier<Item> DIN_RELAY_MOUNT_5PIN = ITEMS.register("din_relay_mount_5pin",
            () -> new Item(new Item.Properties()));

    /** The 8-pin relay mount, for the same reason; it takes only 8-pin relays. */
    public static final Supplier<Item> DIN_RELAY_MOUNT_8PIN = ITEMS.register("din_relay_mount_8pin",
            () -> new Item(new Item.Properties()));

    /**
     * The plug-in relays, one per row of RelayType's table, keyed by its id. Not
     * controls of their own: right-clicking a mount already on a rail with one
     * pushes it in.
     */
    public static final Map<String, Supplier<Item>> RELAYS = registerRelays();

    private static Map<String, Supplier<Item>> registerRelays() {
        Map<String, Supplier<Item>> items = new LinkedHashMap<>();
        for (RelayType type : RelayType.all())
            items.put(type.id(), ITEMS.register(type.id(), () -> new Item(new Item.Properties())));
        return Collections.unmodifiableMap(items);
    }

    /**
     * The DIN terminal blocks, plain items for the same reason a breaker is: they
     * exist only clipped to a rail. One per row of TerminalBlockType's table, keyed
     * by its id.
     */
    public static final Map<String, Supplier<Item>> TERMINAL_BLOCKS = registerTerminalBlocks();

    private static Map<String, Supplier<Item>> registerTerminalBlocks() {
        Map<String, Supplier<Item>> items = new LinkedHashMap<>();
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
    public static final Supplier<Item> PANEL_LABEL = ITEMS.register("panel_label",
            () -> new Item(new Item.Properties()));

    public static final Supplier<Item> DESIGN_WORKBENCH = ITEMS.register("design_workbench",
            () -> new BlockItem(ModBlocks.DESIGN_WORKBENCH.get(), new Item.Properties()));

    /**
     * The controls the Design Workbench makes, one item per kind of control a design
     * is built on. What each one looks like is in its NBT, so a bare one means
     * nothing and they are kept out of the creative tab.
     */
    public static final Map<DesignKind, Supplier<Item>> DESIGNED_CONTROLS = registerDesignedControls();

    private static Map<DesignKind, Supplier<Item>> registerDesignedControls() {
        Map<DesignKind, Supplier<Item>> items = new EnumMap<>(DesignKind.class);
        for (DesignKind kind : DesignKind.values())
            items.put(kind, ITEMS.register(kind.itemId(),
                    () -> new DesignedControlItem(kind, new Item.Properties().stacksTo(16))));
        return Collections.unmodifiableMap(items);
    }

    // PC
    /** The PC case, placed with its front panel on and nothing inside (docs/PC_SPEC.md 11.2.3). */
    public static final Supplier<Item> PC_CASE = ITEMS.register("pc_case",
            () -> new BlockItem(ModBlocks.PC_CASE.get(), new Item.Properties()));

    public static final Supplier<Item> PC_FRONT_PANEL = ITEMS.register("pc_front_panel",
            () -> new PcPartItem(PcPartItem.Kind.FRONT_PANEL, new Item.Properties().stacksTo(16)));

    public static final Supplier<Item> PC_MOTHERBOARD = ITEMS.register("pc_motherboard",
            () -> new PcMotherboardItem(new Item.Properties().stacksTo(1)));

    /** One item per row of each family's table, keyed by the row. */
    public static final Map<PcCpuType, Supplier<Item>> PC_CPUS = registerPcFamily(PcCpuType.class,
            PcCpuType::id, type -> new PcCpuItem(type, new Item.Properties().stacksTo(1)));
    public static final Map<PcRamType, Supplier<Item>> PC_RAM = registerPcFamily(PcRamType.class,
            PcRamType::id, type -> new PcRamItem(type, new Item.Properties().stacksTo(16)));
    public static final Map<PcHddType, Supplier<Item>> PC_HDDS = registerPcFamily(PcHddType.class,
            PcHddType::id, type -> new PcHddItem(type, new Item.Properties().stacksTo(1)));

    public static final Supplier<Item> FLOPPY_DISK = ITEMS.register("floppy_disk",
            () -> new FloppyDiskItem(new Item.Properties().stacksTo(1)));

    public static final Supplier<Item> DATA_CORD = ITEMS.register("data_cord",
            () -> new DataCordItem(new Item.Properties()));

    /** The network modem (docs/PC_SPEC.md 12.4.5). */
    public static final Supplier<Item> NETWORK_MODEM = ITEMS.register("network_modem",
            () -> new BlockItem(ModBlocks.NETWORK_MODEM.get(), new Item.Properties()));

    public static final Supplier<Item> KEYBOARD = ITEMS.register("keyboard",
            () -> new com.nectariich.create_flippies.content.device.DeviceItem(ModEntityTypes.KEYBOARD,
                    com.nectariich.create_flippies.content.keyboard.KeyboardEntity::new, new Item.Properties()));

    public static final Supplier<Item> KEYBOARD_AND_MOUSE = ITEMS.register("keyboard_and_mouse",
            () -> new com.nectariich.create_flippies.content.device.DeviceItem(ModEntityTypes.KEYBOARD_AND_MOUSE,
                    com.nectariich.create_flippies.content.keyboard.KeyboardMouseEntity::new, new Item.Properties()));

    private static <E extends Enum<E>> Map<E, Supplier<Item>> registerPcFamily(Class<E> family,
            java.util.function.Function<E, String> id, java.util.function.Function<E, Item> item) {
        Map<E, Supplier<Item>> items = new EnumMap<>(family);
        for (E row : family.getEnumConstants())
            items.put(row, ITEMS.register(id.apply(row), () -> item.apply(row)));
        return Collections.unmodifiableMap(items);
    }
    // PC end

    // CRT
    public static final Supplier<Item> CRT = ITEMS.register("crt",
            () -> new com.nectariich.create_flippies.content.device.DeviceItem(ModEntityTypes.CRT,
                    com.nectariich.create_flippies.content.crt.CrtEntity::new, new Item.Properties()));
    // CRT end

    // WIRE DUCTS
    /** One duct item per size, {@code wire_duct_<size>}, thinnest first. */
    public static final java.util.Map<com.nectariich.create_flippies.foundation.wire.duct.DuctSize,
            Supplier<Item>> WIRE_DUCTS = wireDucts();

    private static java.util.Map<com.nectariich.create_flippies.foundation.wire.duct.DuctSize,
            Supplier<Item>> wireDucts() {
        var ducts = new java.util.EnumMap<com.nectariich.create_flippies.foundation.wire.duct.DuctSize,
                Supplier<Item>>(com.nectariich.create_flippies.foundation.wire.duct.DuctSize.class);
        for (var size : com.nectariich.create_flippies.foundation.wire.duct.DuctSize.values())
            ducts.put(size, ITEMS.register("wire_duct_" + size.id(),
                    () -> new com.nectariich.create_flippies.content.wire_duct.WireDuctItem(size,
                            new Item.Properties())));
        return ducts;
    }

    public static Item wireDuct(com.nectariich.create_flippies.foundation.wire.duct.DuctSize size) {
        return WIRE_DUCTS.get(size).get();
    }
    // WIRE DUCTS end

    public static void register(IEventBus modBus) {
        ITEMS.register(modBus);
    }
}
