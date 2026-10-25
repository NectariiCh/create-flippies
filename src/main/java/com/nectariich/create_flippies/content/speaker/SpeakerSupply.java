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
 * The tolerance is the gauges' — a quarter either way — so a rail that runs a 24 V
 * gauge runs a 24 V speaker. Outside it the amplifier is simply off: an
 * undervolted speaker is silent rather than quiet, and an overvolted one is not
 * damaged, because nothing else in the mod is either.
 */
public enum SpeakerSupply {

    V12("12v", 12),
    V24("24v", 24),
    V48("48v", 48),
    V120("120v", 120),
    V240("240v", 240);

    private static final float TOLERANCE = 0.25f;

    /** What the amplifier draws at exactly its rating, in watts. */
    private static final float WATTS = 3.0f;

    private final String id;
    private final float rated;

    SpeakerSupply(String id, float rated) {
        this.id = id;
        this.rated = rated;
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
        return magnitude >= rated * (1 - TOLERANCE) && magnitude <= rated * (1 + TOLERANCE);
    }

    public Component label() {
        return Component.translatable("gui.create_flippies.alarm_speaker.supply." + id);
    }
}
