package com.nectariich.create_flippies.client;

import java.util.Collections;
import java.util.EnumMap;
import java.util.Map;
import java.util.function.BiFunction;
import java.util.function.Function;

import com.nectariich.create_flippies.CreateFlippies;
import com.nectariich.create_flippies.content.circuit_breaker.CircuitBreakerType;
import com.nectariich.create_flippies.content.display.DisplayForm;
import com.nectariich.create_flippies.content.indicator.IndicatorColor;
import com.nectariich.create_flippies.content.indicator.IndicatorSize;
import com.nectariich.create_flippies.content.psu.PsuType;
import com.nectariich.create_flippies.content.relay.RelayMountType;
import com.nectariich.create_flippies.content.relay.RelayType;
import com.nectariich.create_flippies.content.terminal_block.TerminalBlockType;
import com.nectariich.create_flippies.content.rotary_switch.RotarySwitchAppearance;
import com.nectariich.create_flippies.content.rotary_switch.RotarySwitchKind;
import com.nectariich.create_flippies.content.rotary_switch.RotarySwitchMarking;
import com.nectariich.create_flippies.content.rotary_switch.RotarySwitchSize;
import com.nectariich.create_flippies.content.rotary_switch.RotarySwitchStyle;

import dev.engine_room.flywheel.lib.model.baked.PartialModel;
import net.minecraft.resources.ResourceLocation;

public class ModPartialModels {

    public static final PartialModel GAUGE_NEEDLE = PartialModel.of(CreateFlippies.asResource("block/gauge_needle"));

    // A gauge mounted inside a host block has no block state for a block model to be
    // baked from, so the body the standalone gauges get from their block states has
    // to be a partial as well. All nine of them share the one model.
    public static final PartialModel SQUARE_PROCEDURAL_GAUGE_BODY =
            PartialModel.of(CreateFlippies.asResource("block/square_procedural_gauge"));

    // The edgewise voltmeters likewise, all eight on the one model
    public static final PartialModel EDGEWISE_PROCEDURAL_GAUGE_BODY =
            PartialModel.of(CreateFlippies.asResource("block/edgewise_procedural_gauge"));

    // A label draws its own backing plate, for the same reason: it exists only as a
    // mounted control, so there is no block state anywhere to bake it from.
    public static final PartialModel PANEL_LABEL_PLATE =
            PartialModel.of(CreateFlippies.asResource("block/panel_label_plate"));

    // The PLC likewise, in both mountings: it is only ever a control, clipped to a
    // rail or screwed to a panel, so there is no block state to bake either from.
    // The panel one is the DIN body lowered onto the plate, with ears for clips.
    public static final PartialModel PLC_S32F103_D_DIN =
            PartialModel.of(CreateFlippies.asResource("block/plc_s32f103-d_din"));
    public static final PartialModel PLC_S32F103_D_PANEL =
            PartialModel.of(CreateFlippies.asResource("block/plc_s32f103-d_panel"));

    // The rotary switches have no baked block model at all: body and handle are
    // both drawn from partials, because a block state variant can neither reach
    // every orientation they place in nor turn a handle 45 degrees — and because
    // which body and handle a switch wears is per-switch state that no block state
    // carries.
    //
    // Every combination is claimed up front. A PartialModel has to exist before the
    // models are baked to be baked at all, so these cannot be looked up on demand
    // when a wrench picks one: it is the whole table or nothing.
    //
    // Bodies are keyed by marking first because a marking belongs to exactly one
    // switch and so already names the size the paint has to have been authored at;
    // handles carry no marking, so they are keyed by that size directly. Only the
    // paints and handles a size really has are claimed — asking for a partial whose
    // model does not exist bakes the missing-model cube instead.
    private static final Map<RotarySwitchMarking, Map<RotarySwitchStyle, PartialModel>> ROTARY_SWITCH_BODIES =
            rotarySwitchBodies();
    private static final Map<RotarySwitchSize, Map<RotarySwitchStyle, PartialModel>> ROTARY_SWITCH_HANDLES =
            rotarySwitchHandles();

    public static PartialModel rotarySwitchBody(RotarySwitchAppearance appearance) {
        return ROTARY_SWITCH_BODIES.get(appearance.marking()).get(appearance.paint());
    }

    public static PartialModel rotarySwitchHandle(RotarySwitchAppearance appearance) {
        return ROTARY_SWITCH_HANDLES.get(appearance.size()).get(appearance.handle());
    }

    private static Map<RotarySwitchMarking, Map<RotarySwitchStyle, PartialModel>> rotarySwitchBodies() {
        Map<RotarySwitchMarking, Map<RotarySwitchStyle, PartialModel>> bodies =
                new EnumMap<>(RotarySwitchMarking.class);
        for (RotarySwitchMarking marking : RotarySwitchMarking.values()) {
            Map<RotarySwitchStyle, PartialModel> byPaint = new EnumMap<>(RotarySwitchStyle.class);
            for (RotarySwitchStyle paint : marking.kind().size().styles()) {
                RotarySwitchAppearance look = new RotarySwitchAppearance(marking, paint, paint);
                byPaint.put(paint, PartialModel.of(CreateFlippies.asResource(look.bodyModel())));
            }
            bodies.put(marking, Collections.unmodifiableMap(byPaint));
        }
        return Collections.unmodifiableMap(bodies);
    }

    private static Map<RotarySwitchSize, Map<RotarySwitchStyle, PartialModel>> rotarySwitchHandles() {
        Map<RotarySwitchSize, Map<RotarySwitchStyle, PartialModel>> handles =
                new EnumMap<>(RotarySwitchSize.class);
        for (RotarySwitchKind kind : RotarySwitchKind.values()) {
            RotarySwitchSize size = kind.size();
            if (handles.containsKey(size))
                continue;
            Map<RotarySwitchStyle, PartialModel> byStyle = new EnumMap<>(RotarySwitchStyle.class);
            for (RotarySwitchStyle style : size.styles()) {
                RotarySwitchAppearance look = new RotarySwitchAppearance(kind.defaultMarking(), style, style);
                byStyle.put(style, PartialModel.of(CreateFlippies.asResource(look.handleModel())));
            }
            handles.put(size, Collections.unmodifiableMap(byStyle));
        }
        return Collections.unmodifiableMap(handles);
    }

    // The indicator lamps have no baked block model either, and for one reason more
    // than the switches: which lamp is drawn depends on a live voltage as well as on
    // the lens fitted, and a block state can carry neither.
    //
    // Body and lens are separate partials rather than one lamp apiece, because they
    // are drawn at different light levels: a lit lamp's lens goes in at full bright
    // so that it is bright of itself in an unlit room, while the body takes whatever
    // light is falling on the panel. Only the lens differs between a lamp's two
    // states, so there is one body per colour and a lens per colour and state — and
    // all of them are claimed up front, because a lamp lights and goes out long
    // after baking is over.
    private static final Map<IndicatorSize, Map<IndicatorColor, PartialModel>> INDICATOR_BODIES =
            indicatorParts((size, color) -> color.bodyModel(size));
    private static final Map<IndicatorSize, Map<IndicatorColor, PartialModel>> INDICATOR_LENSES =
            indicatorParts((size, color) -> color.lensModel(size, false));
    private static final Map<IndicatorSize, Map<IndicatorColor, PartialModel>> INDICATOR_LENSES_LIT =
            indicatorParts((size, color) -> color.lensModel(size, true));

    public static PartialModel indicatorBody(IndicatorSize size, IndicatorColor color) {
        return INDICATOR_BODIES.get(size).get(color);
    }

    public static PartialModel indicatorLens(IndicatorSize size, IndicatorColor color, boolean lit) {
        return (lit ? INDICATOR_LENSES_LIT : INDICATOR_LENSES).get(size).get(color);
    }

    /** One partial per size and colour, named by whichever part of the unit is wanted. */
    private static Map<IndicatorSize, Map<IndicatorColor, PartialModel>> indicatorParts(
            BiFunction<IndicatorSize, IndicatorColor, String> name) {
        Map<IndicatorSize, Map<IndicatorColor, PartialModel>> parts = new EnumMap<>(IndicatorSize.class);
        for (IndicatorSize size : IndicatorSize.values()) {
            Map<IndicatorColor, PartialModel> byColor = new EnumMap<>(IndicatorColor.class);
            for (IndicatorColor color : IndicatorColor.values())
                byColor.put(color, PartialModel.of(CreateFlippies.asResource(name.apply(size, color))));
            parts.put(size, Collections.unmodifiableMap(byColor));
        }
        return Collections.unmodifiableMap(parts);
    }

    // The alphanumeric displays have no baked block model either, for the lamps'
    // reasons and one of their own: the glass is drawn at a different light level
    // when the backlight is driven, which is a live voltage; the device reaches
    // orientations a block state variant cannot express; and what is on the screen is
    // drawn with the font and is not geometry at all.
    //
    // Glass and body are separate partials because they go in at different light
    // levels — a backlit screen glows of itself in an unlit room while the barrel
    // behind it takes whatever light is falling on the panel. Every form is claimed
    // up front, because a backlight comes on long after baking is over.
    private static final Map<DisplayForm, PartialModel> DISPLAY_SCREENS = displayParts(DisplayForm::screenModel);
    private static final Map<DisplayForm, PartialModel> DISPLAY_BODIES = displayParts(DisplayForm::bodyModel);

    public static PartialModel displayScreen(DisplayForm form) {
        return DISPLAY_SCREENS.get(form);
    }

    public static PartialModel displayBody(DisplayForm form) {
        return DISPLAY_BODIES.get(form);
    }

    private static Map<DisplayForm, PartialModel> displayParts(Function<DisplayForm, String> name) {
        Map<DisplayForm, PartialModel> parts = new EnumMap<>(DisplayForm.class);
        for (DisplayForm form : DisplayForm.values())
            parts.put(form, PartialModel.of(CreateFlippies.asResource(name.apply(form))));
        return Collections.unmodifiableMap(parts);
    }

    // A breaker exists only clipped to a rail, so it has no block state either, and
    // its switch is a second partial because it swings on a hinge of its own. One
    // pair per rating and pole count, keyed by the kind that draws them.
    public static final Map<CircuitBreakerType, PartialModel> CIRCUIT_BREAKER_BODIES =
            breakerParts(CircuitBreakerType::bodyModel);
    public static final Map<CircuitBreakerType, PartialModel> CIRCUIT_BREAKER_SWITCHES =
            breakerParts(CircuitBreakerType::switchModel);

    private static Map<CircuitBreakerType, PartialModel> breakerParts(
            java.util.function.Function<CircuitBreakerType, String> model) {
        Map<CircuitBreakerType, PartialModel> parts = new java.util.HashMap<>();
        for (CircuitBreakerType type : CircuitBreakerType.all())
            parts.put(type, PartialModel.of(CreateFlippies.asResource(model.apply(type))));
        return Collections.unmodifiableMap(parts);
    }

    // A power supply is likewise only ever clipped to a rail, and is one model with
    // nothing that moves: one partial per row of PsuType's table.
    public static final Map<PsuType, PartialModel> PSUS = psuParts();

    private static Map<PsuType, PartialModel> psuParts() {
        Map<PsuType, PartialModel> parts = new java.util.HashMap<>();
        for (PsuType type : PsuType.all())
            parts.put(type, PartialModel.of(CreateFlippies.asResource(type.model())));
        return Collections.unmodifiableMap(parts);
    }

    // The relay mounts are likewise only on a rail: the empty mount always, one per
    // socket, and the relay fitted in it on top, one per row of RelayType's table.
    public static final Map<RelayMountType, PartialModel> RELAY_MOUNTS = relayMountParts();
    public static final Map<RelayType, PartialModel> RELAYS = relayParts();

    private static Map<RelayMountType, PartialModel> relayMountParts() {
        Map<RelayMountType, PartialModel> parts = new java.util.HashMap<>();
        for (RelayMountType type : RelayMountType.all())
            parts.put(type, PartialModel.of(CreateFlippies.asResource(type.bodyModel())));
        return Collections.unmodifiableMap(parts);
    }

    private static Map<RelayType, PartialModel> relayParts() {
        Map<RelayType, PartialModel> parts = new java.util.HashMap<>();
        for (RelayType type : RelayType.all())
            parts.put(type, PartialModel.of(CreateFlippies.asResource(type.model())));
        return Collections.unmodifiableMap(parts);
    }

    // A terminal block is likewise only on a rail, and nothing on it moves: one
    // partial per row of TerminalBlockType's table.
    public static final Map<TerminalBlockType, PartialModel> TERMINAL_BLOCKS = terminalBlockParts();

    private static Map<TerminalBlockType, PartialModel> terminalBlockParts() {
        Map<TerminalBlockType, PartialModel> parts = new java.util.HashMap<>();
        for (TerminalBlockType type : TerminalBlockType.all())
            parts.put(type, PartialModel.of(CreateFlippies.asResource(type.model())));
        return Collections.unmodifiableMap(parts);
    }

    // Power Grid draws its thermometer casing through the block model and only the
    // needles as partials; the universal gauge's own model is empty, so it needs
    // both as partials. PartialModel.of interns by location, so asking for the
    // needle here hands back the very instance Power Grid registered.
    public static final PartialModel THERMOMETER_BASE = powerGrid("block/thermometer/base");
    public static final PartialModel THERMOMETER_NEEDLE = powerGrid("block/thermometer/needle");

    private static PartialModel powerGrid(String path) {
        return PartialModel.of(new ResourceLocation("powergrid", path));
    }

    /** Classloads the partials; must run during client mod construction. */
    // The rears designed switches are drawn with: the switch bodies with their front
    // plates cut off, since an imported model stands in for the plate and handle.
    // Designed buttons use the indicator body as it is, which is already only the rear.
    private static final Map<RotarySwitchKind, PartialModel> DESIGNED_REARS = Map.of(
            RotarySwitchKind.TWO_POSITION, PartialModel.of(CreateFlippies.asResource("block/rotary-switch-med-2p-rear")),
            RotarySwitchKind.THREE_POSITION, PartialModel.of(CreateFlippies.asResource("block/rotary-switch-med-3p-rear")));

    public static PartialModel designedRear(RotarySwitchKind kind) {
        return DESIGNED_REARS.get(kind);
    }

    // WORKBENCH GAUGE: the rear designed gauges are drawn with, the Square gauge's
    // barrel and studs alone, since an imported model stands in for its face plate
    public static final PartialModel DESIGNED_GAUGE_REAR =
            PartialModel.of(CreateFlippies.asResource("block/designed_gauge_rear"));
    // WORKBENCH GAUGE end

    // PC
    // The PC case's parts that change (docs/PC_SPEC.md 11.2.1), struck from pc-case-1.json by
    // tools/gen_pc_case.py. Each keeps its authored coordinates, so the renderer only turns it.
    public static final PartialModel PC_FRONT_PANEL = pcPartial("front_panel");
    public static final PartialModel PC_MOTHERBOARD = pcPartial("motherboard");
    public static final PartialModel PC_CPU = pcPartial("cpu");
    public static final PartialModel PC_RAM_A = pcPartial("ram_a");
    public static final PartialModel PC_RAM_B = pcPartial("ram_b");
    public static final PartialModel PC_HDD = pcPartial("hdd");
    public static final PartialModel PC_FLOPPY_DISK = pcPartial("floppy_disk");
    public static final PartialModel PC_POWER_BUTTON = pcPartial("power_button");
    public static final PartialModel PC_RESET_BUTTON = pcPartial("reset_button");
    public static final PartialModel PC_POWER_LED = pcPartial("power_led");
    public static final PartialModel PC_ERROR_LED = pcPartial("error_led");
    public static final PartialModel PC_POWER_PLUG = pcPartial("power_plug");
    // The network modem's two lights (docs/PC_SPEC.md 12.4.5), cut from its placeholder model
    public static final PartialModel MODEM_POWER_LED = PartialModel.of(CreateFlippies.asResource("block/network_modem/power_led"));
    public static final PartialModel MODEM_LINK_LED = PartialModel.of(CreateFlippies.asResource("block/network_modem/link_led"));
    private static final Map<com.nectariich.create_flippies.content.data_cord.PortType, PartialModel> DATA_PLUGS =
            dataPlugs();

    private static PartialModel pcPartial(String name) {
        return PartialModel.of(CreateFlippies.asResource("block/pc_case/" + name));
    }

    private static Map<com.nectariich.create_flippies.content.data_cord.PortType, PartialModel> dataPlugs() {
        Map<com.nectariich.create_flippies.content.data_cord.PortType, PartialModel> plugs =
                new EnumMap<>(com.nectariich.create_flippies.content.data_cord.PortType.class);
        for (com.nectariich.create_flippies.content.data_cord.PortType type
                : com.nectariich.create_flippies.content.data_cord.PortType.values())
            plugs.put(type, pcPartial("data_plug_" + type.name().toLowerCase(java.util.Locale.ROOT)));
        return Collections.unmodifiableMap(plugs);
    }

    public static PartialModel dataPlug(com.nectariich.create_flippies.content.data_cord.PortType type) {
        return DATA_PLUGS.get(type);
    }

    public static PartialModel pcPart(com.nectariich.create_flippies.content.pc.PcSlot slot) {
        return switch (slot) {
            case FRONT_PANEL -> PC_FRONT_PANEL;
            case MOTHERBOARD -> PC_MOTHERBOARD;
            case CPU -> PC_CPU;
            case RAM_A -> PC_RAM_A;
            case RAM_B -> PC_RAM_B;
            case HDD -> PC_HDD;
            case FLOPPY -> PC_FLOPPY_DISK;
        };
    }
    // PC end

    // CRT
    // The plug drawn behind the CRT's socket while Power Grid's cord is in it, from tools/gen_crt.py
    public static final PartialModel CRT_POWER_PLUG = PartialModel.of(CreateFlippies.asResource("block/crt/power_plug"));
    // The CRT and the keyboard are entities, drawn from their models
    public static final PartialModel CRT = PartialModel.of(CreateFlippies.asResource("block/crt"));
    public static final PartialModel KEYBOARD = PartialModel.of(CreateFlippies.asResource("block/keyboard"));
    public static final PartialModel KEYBOARD_AND_MOUSE = PartialModel.of(
            CreateFlippies.asResource("block/keyboard_and_mouse"));
    // CRT end

    public static void init() {}
}
