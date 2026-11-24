package com.nectariich.create_flippies.content.indicator;

import net.minecraft.network.chat.Component;

/**
 * What voltage an indicator lamp is built for, and therefore what load it puts on
 * the line it is watching and across what range it lights properly.
 *
 * This is half of what a block is — see {@link IndicatorKind}, which pairs it with
 * the cap size. It cannot be per-lamp state the way
 * the lens colour is: the rating is the resistance the lamp presents, so changing
 * it in place would rewrite a live circuit's load, and it is what a wire is
 * connected to rather than something painted on afterwards. Wiring a 5 V lamp
 * across 250 V is a mistake the player is allowed to make, and it shows — the lamp
 * is simply at full brightness with far more current through it than its rating.
 *
 * <h2>The ladder</h2>
 *
 * 5, 12, 24 and 48 V for control circuits, and 125 V and 250 V for mains. A pilot
 * lamp is a minimally electronic part, so its mains ratings are the ones printed on
 * switchgear — 125 and 250 — rather than the 120 and 230 a supply or a screen is
 * sold as: the higher figure is what lets one part go on either side of a nominal
 * line and on its high days.
 *
 * <h2>The operating window</h2>
 *
 * Each rating lights at full brightness across a window rather than at one voltage,
 * because a real line is never exactly its nominal. The mains windows are stated:
 * 85–140 V for a 125 V lamp and 175–285 V for a 250 V one. The control ratings take
 * the same proportions, 70% to 115% of rated — 3.5–5.75, 8.4–13.8, 16.8–27.6 and
 * 33.6–55.2 V — so every rating forgives the same sag and the same surge. Below the
 * window the lamp dims in proportion; above it the lamp is no brighter, only harder
 * driven — see {@link IndicatorBlockEntity#brightness(float, IndicatorRating)}.
 *
 * The resistance follows from the rating and one fixed wattage rather than being
 * stated per variant, because that is what makes the six behave like the same
 * lamp built for different lines: each draws its rated power at its rated voltage.
 * A pilot lamp is meant to be nearly free to hang on a circuit, so the wattage is
 * small enough that a lamp does not noticeably load what it is indicating.
 */
public enum IndicatorRating {

    V5("5v", 5, 3.5f, 5.75f),
    V12("12v", 12, 8.4f, 13.8f),
    V24("24v", 24, 16.8f, 27.6f),
    V48("48v", 48, 33.6f, 55.2f),
    V125("125v", 125, 85, 140),
    V250("250v", 250, 175, 285);

    /** What a lamp of any rating draws at exactly its rated voltage, in watts. */
    public static final float WATTS = 1f;

    private final String id;
    private final float volts;
    private final float minVolts;
    private final float maxVolts;
    private final float resistance;

    IndicatorRating(String id, float volts, float minVolts, float maxVolts) {
        this.id = id;
        this.volts = volts;
        this.minVolts = minVolts;
        this.maxVolts = maxVolts;
        this.resistance = volts * volts / WATTS;
    }

    /** This rating's half of the block id, and what a save holds. */
    public String id() {
        return id;
    }

    /** The voltage the lamp is built for, which its load is worked out from. */
    public float volts() {
        return volts;
    }

    /** The lowest voltage at which the lamp is still at full brightness. */
    public float minVolts() {
        return minVolts;
    }

    /** The highest voltage the lamp is built to sit at; above it, it is overdriven. */
    public float maxVolts() {
        return maxVolts;
    }

    /** Ohms between the two terminals, so that {@link #WATTS} flows at {@link #volts}. */
    public float resistance() {
        return resistance;
    }

    public Component label() {
        return Component.translatable("gui.create_flippies.indicator.rating." + id);
    }

    /**
     * The rating saved under this id, or {@code fallback} if it is unknown.
     *
     * The two ratings retired for the ladder are read as their nearest successors —
     * 6 V as 5 V, 230 V as 250 V — so a workbench design saved before the change
     * keeps a lamp of the same class rather than falling back to the default.
     */
    public static IndicatorRating of(String id, IndicatorRating fallback) {
        for (IndicatorRating rating : values())
            if (rating.id.equals(id))
                return rating;
        return switch (id) {
            case "6v" -> V5;
            case "230v" -> V250;
            default -> fallback;
        };
    }
}
