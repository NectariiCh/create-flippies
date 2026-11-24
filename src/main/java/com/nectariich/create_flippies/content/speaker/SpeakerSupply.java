package com.nectariich.create_flippies.content.speaker;

import net.minecraft.network.chat.Component;

/**
 * The supply an alarm speaker's amplifier runs from, across {@code VCC} and
 * {@code GND}.
 *
 * Its own enum rather than more constants on
 * {@link com.nectariich.create_flippies.content.procedural_gauge.SupplyVoltage}:
 * that one's values are the gauges' and the displays' block variants, so adding the
 * mains ratings there would add mains gauges too.
 *
 * The low-voltage ratings take the gauges' tolerance — a quarter either way — so a
 * rail that runs a 24 V gauge runs a 24 V speaker. The two mains ratings take the
 * mod's window for mains electronics instead, stated outright rather than as a
 * fraction: 95 to 135 V for 120 V (so a 125 V line is fine) and 195 to 260 V for
 * 230 V (so a 240 V line is). Outside its window the amplifier is simply off: an
 * undervolted speaker is silent rather than quiet, and an overvolted one is not
 * damaged, because nothing else in the mod is either.
 */
public enum SpeakerSupply {

    V12("12v", 12),
    V24("24v", 24),
    V48("48v", 48),
    V120("120v", 120, 95, 135),
    V230("230v", 230, 195, 260);

    /** The low-voltage ratings' window, as a fraction either side of the rating. */
    private static final float TOLERANCE = 0.25f;

    /** What the amplifier draws at exactly its rating, in watts. */
    private static final float WATTS = 3.0f;

    private final String id;
    private final float rated;
    private final float min;
    private final float max;

    SpeakerSupply(String id, float rated) {
        this(id, rated, rated * (1 - TOLERANCE), rated * (1 + TOLERANCE));
    }

    SpeakerSupply(String id, float rated, float min, float max) {
        this.id = id;
        this.rated = rated;
        this.min = min;
        this.max = max;
    }

    public String id() {
        return id;
    }

    public float rated() {
        return rated;
    }

    /** Ohms across {@code VCC} and {@code GND}. */
    public float loadResistance() {
        return rated * rated / WATTS;
    }

    /** Whether the amplifier runs at this supply; the sign is not meaningful. */
    public boolean accepts(float volts) {
        float magnitude = Math.abs(volts);
        return magnitude >= min && magnitude <= max;
    }

    public Component label() {
        return Component.translatable("gui.create_flippies.alarm_speaker.supply." + id);
    }
}
