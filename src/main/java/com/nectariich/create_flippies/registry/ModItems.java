package com.nectariich.create_flippies.registry;

import com.nectariich.create_flippies.CreateFlippies;
import com.nectariich.create_flippies.content.procedural_gauge.VoltmeterRange;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

import com.nectariich.create_flippies.content.circuit_breaker.CircuitBreakerType;
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

    public static void register(IEventBus modBus) {
        ITEMS.register(modBus);
    }
}
