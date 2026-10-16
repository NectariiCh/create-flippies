package com.nectariich.create_flippies.content.indicator;

import java.util.Collections;
import java.util.EnumMap;
import java.util.Map;

import net.minecraft.network.chat.Component;

/**
 * What kind of indicator this is: how big its cap is, and what voltage its lamp is
 * built for.
 *
 * This is what the blocks and the items are, one apiece, because these are the two
 * things about an indicator that cannot be changed once it is placed. The rating is
 * the resistance the lamp presents and therefore the load on whatever it is
 * watching; the size is the cap, which is the footprint, and a footprint that
 * changed under a mounted control would be a control nobody had checked against its
 * neighbours. Everything else an indicator is — the lens colour, whether it clicks,
 * the legend printed on it — is per-unit state a screwdriver sets, for the reason
 * {@link com.nectariich.create_flippies.content.rotary_switch.RotarySwitchAppearance}
 * gives: those are what a panel builder decides late and changes their mind about.
 *
 * The cross product is written out rather than derived, the way
 * {@link com.nectariich.create_flippies.content.rotary_switch.RotarySwitchKind}
 * writes out its own: an enum constant is what a registry entry, an EnumMap and a
 * save can all be keyed on, and there is no way to name one that does not exist.
 */
public enum IndicatorKind {

    MED_6V(IndicatorSize.MED, IndicatorRating.V6),
    MED_12V(IndicatorSize.MED, IndicatorRating.V12),
    MED_24V(IndicatorSize.MED, IndicatorRating.V24),
    MED_125V(IndicatorSize.MED, IndicatorRating.V125),
    MED_230V(IndicatorSize.MED, IndicatorRating.V230),

    LARGE_6V(IndicatorSize.LARGE, IndicatorRating.V6),
    LARGE_12V(IndicatorSize.LARGE, IndicatorRating.V12),
    LARGE_24V(IndicatorSize.LARGE, IndicatorRating.V24),
    LARGE_125V(IndicatorSize.LARGE, IndicatorRating.V125),
    LARGE_230V(IndicatorSize.LARGE, IndicatorRating.V230);

    /** Every kind by the pair it is made of, so the two can be looked up as one. */
    private static final Map<IndicatorSize, Map<IndicatorRating, IndicatorKind>> BY_PAIR = byPair();

    private final IndicatorSize size;
    private final IndicatorRating rating;

    IndicatorKind(IndicatorSize size, IndicatorRating rating) {
        this.size = size;
        this.rating = rating;
    }

    public IndicatorSize size() {
        return size;
    }

    public IndicatorRating rating() {
        return rating;
    }

    /** Registry name of this kind's block and item, and its mounted kind's name. */
    public String blockId() {
        return "indicator_" + size.id() + "_" + rating.id();
    }

    public static IndicatorKind of(IndicatorSize size, IndicatorRating rating) {
        return BY_PAIR.get(size).get(rating);
    }

    /** What this kind is called, which is its size and its rating and nothing else. */
    public Component label() {
        return Component.translatable("block.create_flippies." + blockId());
    }

    private static Map<IndicatorSize, Map<IndicatorRating, IndicatorKind>> byPair() {
        Map<IndicatorSize, Map<IndicatorRating, IndicatorKind>> pairs = new EnumMap<>(IndicatorSize.class);
        for (IndicatorSize size : IndicatorSize.values())
            pairs.put(size, new EnumMap<>(IndicatorRating.class));
        for (IndicatorKind kind : values())
            pairs.get(kind.size).put(kind.rating, kind);
        pairs.replaceAll((size, byRating) -> Collections.unmodifiableMap(byRating));
        return Collections.unmodifiableMap(pairs);
    }
}
