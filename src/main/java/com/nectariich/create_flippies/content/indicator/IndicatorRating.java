package com.nectariich.create_flippies.content.indicator;

import net.minecraft.network.chat.Component;

/**
 * What voltage an indicator lamp is built for, and therefore what load it puts on
 * the line it is watching.
 *
 * This is half of what a block is — see {@link IndicatorKind}, which pairs it with
 * the cap size. It cannot be per-lamp state the way
 * the lens colour is: the rating is the resistance the lamp presents, so changing
 * it in place would rewrite a live circuit's load, and it is what a wire is
 * connected to rather than something painted on afterwards. Wiring a 6 V lamp
 * across 230 V is a mistake the player is allowed to make, and it shows — the lamp
 * is simply at full brightness with far more current through it than its rating.
 *
 * The resistance follows from the rating and one fixed wattage rather than being
 * stated per variant, because that is what makes the five behave like the same
 * lamp built for different lines: each draws its rated power at its rated voltage.
 * A pilot lamp is meant to be nearly free to hang on a circuit, so the wattage is
 * small enough that a lamp does not noticeably load what it is indicating.
 */
public enum IndicatorRating {

    V6("6v", 6),
    V12("12v", 12),
    V24("24v", 24),
    V125("125v", 125),
    V230("230v", 230);

    /** What a lamp of any rating draws at exactly its rated voltage, in watts. */
    public static final float WATTS = 1f;

    private final String id;
    private final float volts;
    private final float resistance;

    IndicatorRating(String id, float volts) {
        this.id = id;
        this.volts = volts;
        this.resistance = volts * volts / WATTS;
    }

    /** This rating's half of the block id, and what a save holds. */
    public String id() {
        return id;
    }

    /** The voltage across the lamp at which it is at full brightness. */
    public float volts() {
        return volts;
    }

    /** Ohms between the two terminals, so that {@link #WATTS} flows at {@link #volts}. */
    public float resistance() {
        return resistance;
    }

    public Component label() {
        return Component.translatable("gui.create_flippies.indicator.rating." + id);
    }

    /** The rating saved under this id, or {@code fallback} if it is unknown. */
    public static IndicatorRating of(String id, IndicatorRating fallback) {
        for (IndicatorRating rating : values())
            if (rating.id.equals(id))
                return rating;
        return fallback;
    }
}
