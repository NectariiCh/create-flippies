package com.nectariich.create_flippies.content.display;

import java.util.Collections;
import java.util.EnumMap;
import java.util.Map;

import com.nectariich.create_flippies.content.procedural_gauge.SupplyVoltage;

import net.minecraft.network.chat.Component;

/**
 * What kind of alphanumeric display this is: which body it is built in, and what
 * voltage its supply rail wants.
 *
 * These are the two things about a display that cannot be changed once it is placed,
 * so they are what the blocks and the items are, one apiece — the same division
 * {@link com.nectariich.create_flippies.content.indicator.IndicatorKind} makes, for
 * the same two reasons. The rated voltage is the resistance the device presents and
 * therefore the load on whatever rail is feeding it, which cannot be rewritten under
 * a live wire; the form is the glass, which is the footprint. Everything else a
 * display is — what is printed on it, how tall the lettering is, what colour it
 * glows — is per-unit state a screwdriver sets, because that is what a panel builder
 * decides late and changes their mind about.
 *
 * The cross product is written out rather than derived, as the indicators' is: an
 * enum constant is what a registry entry, an EnumMap and a save can all be keyed on,
 * and there is no way to name one that does not exist. Adding a form means adding
 * its three constants here, which is the point at which the three new blocks come
 * into being.
 */
public enum DisplayKind {

    BRDRL_1P5_SQ_5V(DisplayForm.BRDRL_1P5_SQ, SupplyVoltage.V5),
    BRDRL_1P5_SQ_12V(DisplayForm.BRDRL_1P5_SQ, SupplyVoltage.V12),
    BRDRL_1P5_SQ_24V(DisplayForm.BRDRL_1P5_SQ, SupplyVoltage.V24),
    BRDRL_1X1P5_5V(DisplayForm.BRDRL_1X1P5, SupplyVoltage.V5),
    BRDRL_1X1P5_12V(DisplayForm.BRDRL_1X1P5, SupplyVoltage.V12),
    BRDRL_1X1P5_24V(DisplayForm.BRDRL_1X1P5, SupplyVoltage.V24),
    BRDRL_1X2_5V(DisplayForm.BRDRL_1X2, SupplyVoltage.V5),
    BRDRL_1X2_12V(DisplayForm.BRDRL_1X2, SupplyVoltage.V12),
    BRDRL_1X2_24V(DisplayForm.BRDRL_1X2, SupplyVoltage.V24),
    BRDRL_2X3_5V(DisplayForm.BRDRL_2X3, SupplyVoltage.V5),
    BRDRL_2X3_12V(DisplayForm.BRDRL_2X3, SupplyVoltage.V12),
    BRDRL_2X3_24V(DisplayForm.BRDRL_2X3, SupplyVoltage.V24),
    BRDRL_3X4_5V(DisplayForm.BRDRL_3X4, SupplyVoltage.V5),
    BRDRL_3X4_12V(DisplayForm.BRDRL_3X4, SupplyVoltage.V12),
    BRDRL_3X4_24V(DisplayForm.BRDRL_3X4, SupplyVoltage.V24),
    BRDRL_3X5_5V(DisplayForm.BRDRL_3X5, SupplyVoltage.V5),
    BRDRL_3X5_12V(DisplayForm.BRDRL_3X5, SupplyVoltage.V12),
    BRDRL_3X5_24V(DisplayForm.BRDRL_3X5, SupplyVoltage.V24),
    BRDRL_4X5_5V(DisplayForm.BRDRL_4X5, SupplyVoltage.V5),
    BRDRL_4X5_12V(DisplayForm.BRDRL_4X5, SupplyVoltage.V12),
    BRDRL_4X5_24V(DisplayForm.BRDRL_4X5, SupplyVoltage.V24),
    BRDRL_4X6_5V(DisplayForm.BRDRL_4X6, SupplyVoltage.V5),
    BRDRL_4X6_12V(DisplayForm.BRDRL_4X6, SupplyVoltage.V12),
    BRDRL_4X6_24V(DisplayForm.BRDRL_4X6, SupplyVoltage.V24),
    BRDRL_5X8_5V(DisplayForm.BRDRL_5X8, SupplyVoltage.V5),
    BRDRL_5X8_12V(DisplayForm.BRDRL_5X8, SupplyVoltage.V12),
    BRDRL_5X8_24V(DisplayForm.BRDRL_5X8, SupplyVoltage.V24),
    BRDRL_4X10_5V(DisplayForm.BRDRL_4X10, SupplyVoltage.V5),
    BRDRL_4X10_12V(DisplayForm.BRDRL_4X10, SupplyVoltage.V12),
    BRDRL_4X10_24V(DisplayForm.BRDRL_4X10, SupplyVoltage.V24),
    BRDRL_6X10_5V(DisplayForm.BRDRL_6X10, SupplyVoltage.V5),
    BRDRL_6X10_12V(DisplayForm.BRDRL_6X10, SupplyVoltage.V12),
    BRDRL_6X10_24V(DisplayForm.BRDRL_6X10, SupplyVoltage.V24),
    BRDRL_8X12_5V(DisplayForm.BRDRL_8X12, SupplyVoltage.V5),
    BRDRL_8X12_12V(DisplayForm.BRDRL_8X12, SupplyVoltage.V12),
    BRDRL_8X12_24V(DisplayForm.BRDRL_8X12, SupplyVoltage.V24),
    BRDRL_8X15_5V(DisplayForm.BRDRL_8X15, SupplyVoltage.V5),
    BRDRL_8X15_12V(DisplayForm.BRDRL_8X15, SupplyVoltage.V12),
    BRDRL_8X15_24V(DisplayForm.BRDRL_8X15, SupplyVoltage.V24),
    BRD_1P5_SQ_5V(DisplayForm.BRD_1P5_SQ, SupplyVoltage.V5),
    BRD_1P5_SQ_12V(DisplayForm.BRD_1P5_SQ, SupplyVoltage.V12),
    BRD_1P5_SQ_24V(DisplayForm.BRD_1P5_SQ, SupplyVoltage.V24),
    BRD_1X1P5_5V(DisplayForm.BRD_1X1P5, SupplyVoltage.V5),
    BRD_1X1P5_12V(DisplayForm.BRD_1X1P5, SupplyVoltage.V12),
    BRD_1X1P5_24V(DisplayForm.BRD_1X1P5, SupplyVoltage.V24),
    BRD_1X2_5V(DisplayForm.BRD_1X2, SupplyVoltage.V5),
    BRD_1X2_12V(DisplayForm.BRD_1X2, SupplyVoltage.V12),
    BRD_1X2_24V(DisplayForm.BRD_1X2, SupplyVoltage.V24),
    BRD_2X3_5V(DisplayForm.BRD_2X3, SupplyVoltage.V5),
    BRD_2X3_12V(DisplayForm.BRD_2X3, SupplyVoltage.V12),
    BRD_2X3_24V(DisplayForm.BRD_2X3, SupplyVoltage.V24),
    BRD_3X4_5V(DisplayForm.BRD_3X4, SupplyVoltage.V5),
    BRD_3X4_12V(DisplayForm.BRD_3X4, SupplyVoltage.V12),
    BRD_3X4_24V(DisplayForm.BRD_3X4, SupplyVoltage.V24),
    BRD_3X5_5V(DisplayForm.BRD_3X5, SupplyVoltage.V5),
    BRD_3X5_12V(DisplayForm.BRD_3X5, SupplyVoltage.V12),
    BRD_3X5_24V(DisplayForm.BRD_3X5, SupplyVoltage.V24),
    BRD_4X5_5V(DisplayForm.BRD_4X5, SupplyVoltage.V5),
    BRD_4X5_12V(DisplayForm.BRD_4X5, SupplyVoltage.V12),
    BRD_4X5_24V(DisplayForm.BRD_4X5, SupplyVoltage.V24),
    BRD_4X6_5V(DisplayForm.BRD_4X6, SupplyVoltage.V5),
    BRD_4X6_12V(DisplayForm.BRD_4X6, SupplyVoltage.V12),
    BRD_4X6_24V(DisplayForm.BRD_4X6, SupplyVoltage.V24),
    BRD_5X8_5V(DisplayForm.BRD_5X8, SupplyVoltage.V5),
    BRD_5X8_12V(DisplayForm.BRD_5X8, SupplyVoltage.V12),
    BRD_5X8_24V(DisplayForm.BRD_5X8, SupplyVoltage.V24),
    BRD_4X10_5V(DisplayForm.BRD_4X10, SupplyVoltage.V5),
    BRD_4X10_12V(DisplayForm.BRD_4X10, SupplyVoltage.V12),
    BRD_4X10_24V(DisplayForm.BRD_4X10, SupplyVoltage.V24),
    BRD_6X10_5V(DisplayForm.BRD_6X10, SupplyVoltage.V5),
    BRD_6X10_12V(DisplayForm.BRD_6X10, SupplyVoltage.V12),
    BRD_6X10_24V(DisplayForm.BRD_6X10, SupplyVoltage.V24),
    BRD_8X12_5V(DisplayForm.BRD_8X12, SupplyVoltage.V5),
    BRD_8X12_12V(DisplayForm.BRD_8X12, SupplyVoltage.V12),
    BRD_8X12_24V(DisplayForm.BRD_8X12, SupplyVoltage.V24),
    BRD_8X15_5V(DisplayForm.BRD_8X15, SupplyVoltage.V5),
    BRD_8X15_12V(DisplayForm.BRD_8X15, SupplyVoltage.V12),
    BRD_8X15_24V(DisplayForm.BRD_8X15, SupplyVoltage.V24),
    LIGHT_SHADE_SHORT_1P5_SQ_5V(DisplayForm.LIGHT_SHADE_SHORT_1P5_SQ, SupplyVoltage.V5),
    LIGHT_SHADE_SHORT_1P5_SQ_12V(DisplayForm.LIGHT_SHADE_SHORT_1P5_SQ, SupplyVoltage.V12),
    LIGHT_SHADE_SHORT_1P5_SQ_24V(DisplayForm.LIGHT_SHADE_SHORT_1P5_SQ, SupplyVoltage.V24),
    LIGHT_SHADE_SHORT_1X1P5_5V(DisplayForm.LIGHT_SHADE_SHORT_1X1P5, SupplyVoltage.V5),
    LIGHT_SHADE_SHORT_1X1P5_12V(DisplayForm.LIGHT_SHADE_SHORT_1X1P5, SupplyVoltage.V12),
    LIGHT_SHADE_SHORT_1X1P5_24V(DisplayForm.LIGHT_SHADE_SHORT_1X1P5, SupplyVoltage.V24),
    LIGHT_SHADE_SHORT_1X2_5V(DisplayForm.LIGHT_SHADE_SHORT_1X2, SupplyVoltage.V5),
    LIGHT_SHADE_SHORT_1X2_12V(DisplayForm.LIGHT_SHADE_SHORT_1X2, SupplyVoltage.V12),
    LIGHT_SHADE_SHORT_1X2_24V(DisplayForm.LIGHT_SHADE_SHORT_1X2, SupplyVoltage.V24),
    LIGHT_SHADE_SHORT_2X3_5V(DisplayForm.LIGHT_SHADE_SHORT_2X3, SupplyVoltage.V5),
    LIGHT_SHADE_SHORT_2X3_12V(DisplayForm.LIGHT_SHADE_SHORT_2X3, SupplyVoltage.V12),
    LIGHT_SHADE_SHORT_2X3_24V(DisplayForm.LIGHT_SHADE_SHORT_2X3, SupplyVoltage.V24),
    LIGHT_SHADE_SHORT_3X4_5V(DisplayForm.LIGHT_SHADE_SHORT_3X4, SupplyVoltage.V5),
    LIGHT_SHADE_SHORT_3X4_12V(DisplayForm.LIGHT_SHADE_SHORT_3X4, SupplyVoltage.V12),
    LIGHT_SHADE_SHORT_3X4_24V(DisplayForm.LIGHT_SHADE_SHORT_3X4, SupplyVoltage.V24),
    LIGHT_SHADE_SHORT_3X5_5V(DisplayForm.LIGHT_SHADE_SHORT_3X5, SupplyVoltage.V5),
    LIGHT_SHADE_SHORT_3X5_12V(DisplayForm.LIGHT_SHADE_SHORT_3X5, SupplyVoltage.V12),
    LIGHT_SHADE_SHORT_3X5_24V(DisplayForm.LIGHT_SHADE_SHORT_3X5, SupplyVoltage.V24),
    LIGHT_SHADE_SHORT_4X5_5V(DisplayForm.LIGHT_SHADE_SHORT_4X5, SupplyVoltage.V5),
    LIGHT_SHADE_SHORT_4X5_12V(DisplayForm.LIGHT_SHADE_SHORT_4X5, SupplyVoltage.V12),
    LIGHT_SHADE_SHORT_4X5_24V(DisplayForm.LIGHT_SHADE_SHORT_4X5, SupplyVoltage.V24),
    LIGHT_SHADE_SHORT_4X6_5V(DisplayForm.LIGHT_SHADE_SHORT_4X6, SupplyVoltage.V5),
    LIGHT_SHADE_SHORT_4X6_12V(DisplayForm.LIGHT_SHADE_SHORT_4X6, SupplyVoltage.V12),
    LIGHT_SHADE_SHORT_4X6_24V(DisplayForm.LIGHT_SHADE_SHORT_4X6, SupplyVoltage.V24),
    LIGHT_SHADE_SHORT_5X8_5V(DisplayForm.LIGHT_SHADE_SHORT_5X8, SupplyVoltage.V5),
    LIGHT_SHADE_SHORT_5X8_12V(DisplayForm.LIGHT_SHADE_SHORT_5X8, SupplyVoltage.V12),
    LIGHT_SHADE_SHORT_5X8_24V(DisplayForm.LIGHT_SHADE_SHORT_5X8, SupplyVoltage.V24),
    LIGHT_SHADE_SHORT_4X10_5V(DisplayForm.LIGHT_SHADE_SHORT_4X10, SupplyVoltage.V5),
    LIGHT_SHADE_SHORT_4X10_12V(DisplayForm.LIGHT_SHADE_SHORT_4X10, SupplyVoltage.V12),
    LIGHT_SHADE_SHORT_4X10_24V(DisplayForm.LIGHT_SHADE_SHORT_4X10, SupplyVoltage.V24),
    LIGHT_SHADE_SHORT_6X10_5V(DisplayForm.LIGHT_SHADE_SHORT_6X10, SupplyVoltage.V5),
    LIGHT_SHADE_SHORT_6X10_12V(DisplayForm.LIGHT_SHADE_SHORT_6X10, SupplyVoltage.V12),
    LIGHT_SHADE_SHORT_6X10_24V(DisplayForm.LIGHT_SHADE_SHORT_6X10, SupplyVoltage.V24),
    LIGHT_SHADE_SHORT_8X12_5V(DisplayForm.LIGHT_SHADE_SHORT_8X12, SupplyVoltage.V5),
    LIGHT_SHADE_SHORT_8X12_12V(DisplayForm.LIGHT_SHADE_SHORT_8X12, SupplyVoltage.V12),
    LIGHT_SHADE_SHORT_8X12_24V(DisplayForm.LIGHT_SHADE_SHORT_8X12, SupplyVoltage.V24),
    LIGHT_SHADE_SHORT_8X15_5V(DisplayForm.LIGHT_SHADE_SHORT_8X15, SupplyVoltage.V5),
    LIGHT_SHADE_SHORT_8X15_12V(DisplayForm.LIGHT_SHADE_SHORT_8X15, SupplyVoltage.V12),
    LIGHT_SHADE_SHORT_8X15_24V(DisplayForm.LIGHT_SHADE_SHORT_8X15, SupplyVoltage.V24),
    LIGHT_SHADE_LONG_1P5_SQ_5V(DisplayForm.LIGHT_SHADE_LONG_1P5_SQ, SupplyVoltage.V5),
    LIGHT_SHADE_LONG_1P5_SQ_12V(DisplayForm.LIGHT_SHADE_LONG_1P5_SQ, SupplyVoltage.V12),
    LIGHT_SHADE_LONG_1P5_SQ_24V(DisplayForm.LIGHT_SHADE_LONG_1P5_SQ, SupplyVoltage.V24),
    LIGHT_SHADE_LONG_1X1P5_5V(DisplayForm.LIGHT_SHADE_LONG_1X1P5, SupplyVoltage.V5),
    LIGHT_SHADE_LONG_1X1P5_12V(DisplayForm.LIGHT_SHADE_LONG_1X1P5, SupplyVoltage.V12),
    LIGHT_SHADE_LONG_1X1P5_24V(DisplayForm.LIGHT_SHADE_LONG_1X1P5, SupplyVoltage.V24),
    LIGHT_SHADE_LONG_1X2_5V(DisplayForm.LIGHT_SHADE_LONG_1X2, SupplyVoltage.V5),
    LIGHT_SHADE_LONG_1X2_12V(DisplayForm.LIGHT_SHADE_LONG_1X2, SupplyVoltage.V12),
    LIGHT_SHADE_LONG_1X2_24V(DisplayForm.LIGHT_SHADE_LONG_1X2, SupplyVoltage.V24),
    LIGHT_SHADE_LONG_2X3_5V(DisplayForm.LIGHT_SHADE_LONG_2X3, SupplyVoltage.V5),
    LIGHT_SHADE_LONG_2X3_12V(DisplayForm.LIGHT_SHADE_LONG_2X3, SupplyVoltage.V12),
    LIGHT_SHADE_LONG_2X3_24V(DisplayForm.LIGHT_SHADE_LONG_2X3, SupplyVoltage.V24),
    LIGHT_SHADE_LONG_3X4_5V(DisplayForm.LIGHT_SHADE_LONG_3X4, SupplyVoltage.V5),
    LIGHT_SHADE_LONG_3X4_12V(DisplayForm.LIGHT_SHADE_LONG_3X4, SupplyVoltage.V12),
    LIGHT_SHADE_LONG_3X4_24V(DisplayForm.LIGHT_SHADE_LONG_3X4, SupplyVoltage.V24),
    LIGHT_SHADE_LONG_3X5_5V(DisplayForm.LIGHT_SHADE_LONG_3X5, SupplyVoltage.V5),
    LIGHT_SHADE_LONG_3X5_12V(DisplayForm.LIGHT_SHADE_LONG_3X5, SupplyVoltage.V12),
    LIGHT_SHADE_LONG_3X5_24V(DisplayForm.LIGHT_SHADE_LONG_3X5, SupplyVoltage.V24),
    LIGHT_SHADE_LONG_4X5_5V(DisplayForm.LIGHT_SHADE_LONG_4X5, SupplyVoltage.V5),
    LIGHT_SHADE_LONG_4X5_12V(DisplayForm.LIGHT_SHADE_LONG_4X5, SupplyVoltage.V12),
    LIGHT_SHADE_LONG_4X5_24V(DisplayForm.LIGHT_SHADE_LONG_4X5, SupplyVoltage.V24),
    LIGHT_SHADE_LONG_4X6_5V(DisplayForm.LIGHT_SHADE_LONG_4X6, SupplyVoltage.V5),
    LIGHT_SHADE_LONG_4X6_12V(DisplayForm.LIGHT_SHADE_LONG_4X6, SupplyVoltage.V12),
    LIGHT_SHADE_LONG_4X6_24V(DisplayForm.LIGHT_SHADE_LONG_4X6, SupplyVoltage.V24),
    LIGHT_SHADE_LONG_5X8_5V(DisplayForm.LIGHT_SHADE_LONG_5X8, SupplyVoltage.V5),
    LIGHT_SHADE_LONG_5X8_12V(DisplayForm.LIGHT_SHADE_LONG_5X8, SupplyVoltage.V12),
    LIGHT_SHADE_LONG_5X8_24V(DisplayForm.LIGHT_SHADE_LONG_5X8, SupplyVoltage.V24),
    LIGHT_SHADE_LONG_4X10_5V(DisplayForm.LIGHT_SHADE_LONG_4X10, SupplyVoltage.V5),
    LIGHT_SHADE_LONG_4X10_12V(DisplayForm.LIGHT_SHADE_LONG_4X10, SupplyVoltage.V12),
    LIGHT_SHADE_LONG_4X10_24V(DisplayForm.LIGHT_SHADE_LONG_4X10, SupplyVoltage.V24),
    LIGHT_SHADE_LONG_6X10_5V(DisplayForm.LIGHT_SHADE_LONG_6X10, SupplyVoltage.V5),
    LIGHT_SHADE_LONG_6X10_12V(DisplayForm.LIGHT_SHADE_LONG_6X10, SupplyVoltage.V12),
    LIGHT_SHADE_LONG_6X10_24V(DisplayForm.LIGHT_SHADE_LONG_6X10, SupplyVoltage.V24),
    LIGHT_SHADE_LONG_8X12_5V(DisplayForm.LIGHT_SHADE_LONG_8X12, SupplyVoltage.V5),
    LIGHT_SHADE_LONG_8X12_12V(DisplayForm.LIGHT_SHADE_LONG_8X12, SupplyVoltage.V12),
    LIGHT_SHADE_LONG_8X12_24V(DisplayForm.LIGHT_SHADE_LONG_8X12, SupplyVoltage.V24),
    LIGHT_SHADE_LONG_8X15_5V(DisplayForm.LIGHT_SHADE_LONG_8X15, SupplyVoltage.V5),
    LIGHT_SHADE_LONG_8X15_12V(DisplayForm.LIGHT_SHADE_LONG_8X15, SupplyVoltage.V12),
    LIGHT_SHADE_LONG_8X15_24V(DisplayForm.LIGHT_SHADE_LONG_8X15, SupplyVoltage.V24);

    /** Every kind by the pair it is made of, so the two can be looked up as one. */
    private static final Map<DisplayForm, Map<SupplyVoltage, DisplayKind>> BY_PAIR = byPair();

    private final DisplayForm form;
    private final SupplyVoltage supply;

    DisplayKind(DisplayForm form, SupplyVoltage supply) {
        this.form = form;
        this.supply = supply;
    }

    public DisplayForm form() {
        return form;
    }

    public SupplyVoltage supply() {
        return supply;
    }

    /** Registry name of this kind's block and item, and its mounted kind's name. */
    public String blockId() {
        return "oled_display_" + form.id() + "_" + supply.getSerializedName();
    }

    public static DisplayKind of(DisplayForm form, SupplyVoltage supply) {
        return BY_PAIR.get(form).get(supply);
    }

    /** What this kind is called, which is its form and its rating and nothing else. */
    public Component label() {
        return Component.translatable("block.create_flippies." + blockId());
    }

    private static Map<DisplayForm, Map<SupplyVoltage, DisplayKind>> byPair() {
        Map<DisplayForm, Map<SupplyVoltage, DisplayKind>> pairs = new EnumMap<>(DisplayForm.class);
        for (DisplayForm form : DisplayForm.values())
            pairs.put(form, new EnumMap<>(SupplyVoltage.class));
        for (DisplayKind kind : values())
            pairs.get(kind.form).put(kind.supply, kind);
        pairs.replaceAll((form, bySupply) -> Collections.unmodifiableMap(bySupply));
        return Collections.unmodifiableMap(pairs);
    }
}
