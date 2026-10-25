package com.nectariich.create_flippies.content.speaker;

import net.minecraft.network.chat.Component;

/**
 * The logic level an alarm speaker's channel inputs are built for.
 *
 * Half of what a speaker block is, with {@link SpeakerSupply} the other half: it is
 * the input resistance on every channel pin, and therefore the load on whatever is
 * driving it, so it cannot change under a live wire — the reason
 * {@link com.nectariich.create_flippies.content.display.DisplayKind} gives for its
 * own rating.
 *
 * A channel reads as high once it stands at half its rating above {@code GND}, the
 * threshold a real logic input has, so a 24 V input is not fooled by a 5 V signal
 * and a 5 V input does not need a perfectly regulated one. Nothing stops an input
 * being driven over its rating; it simply reads high.
 */
public enum SpeakerInput {

    V5("5v", 5),
    V12("12v", 12),
    V24("24v", 24);

    /** What a channel input draws at exactly its rating, in watts: a signal, not a load. */
    private static final float INPUT_WATTS = 0.05f;

    /** The fraction of the rating at which an input reads as high. */
    private static final float THRESHOLD = 0.5f;

    private final String id;
    private final float rated;

    SpeakerInput(String id, float rated) {
        this.id = id;
        this.rated = rated;
    }

    public String id() {
        return id;
    }

    public float rated() {
        return rated;
    }

    /** Ohms from each channel's bus to {@code GND}. */
    public float inputResistance() {
        return rated * rated / INPUT_WATTS;
    }

    /** Whether a channel at this voltage above {@code GND} reads as high. */
    public boolean high(float volts) {
        return volts >= rated * THRESHOLD;
    }

    public Component label() {
        return Component.translatable("gui.create_flippies.alarm_speaker.input." + id);
    }
}
