package com.nectariich.create_flippies.content.speaker;

import net.minecraft.network.chat.Component;

/**
 * What kind of alarm speaker this is: which logic level its channel inputs take and
 * which supply its amplifier runs from.
 *
 * Both are resistances on a live line, so both are what a block is and neither is
 * screwdriver state — the division every rated device in the mod makes. Everything
 * the speaker <em>plays</em> is per-unit state; see {@link SpeakerSettings}.
 *
 * Written out rather than derived, as the displays' and indicators' kinds are, so a
 * registry entry, an EnumMap and a save can all be keyed on one constant.
 */
public enum SpeakerKind {

    IN5V_12V(SpeakerInput.V5, SpeakerSupply.V12),
    IN5V_24V(SpeakerInput.V5, SpeakerSupply.V24),
    IN5V_48V(SpeakerInput.V5, SpeakerSupply.V48),
    IN5V_120V(SpeakerInput.V5, SpeakerSupply.V120),
    IN5V_240V(SpeakerInput.V5, SpeakerSupply.V240),
    IN12V_12V(SpeakerInput.V12, SpeakerSupply.V12),
    IN12V_24V(SpeakerInput.V12, SpeakerSupply.V24),
    IN12V_48V(SpeakerInput.V12, SpeakerSupply.V48),
    IN12V_120V(SpeakerInput.V12, SpeakerSupply.V120),
    IN12V_240V(SpeakerInput.V12, SpeakerSupply.V240),
    IN24V_12V(SpeakerInput.V24, SpeakerSupply.V12),
    IN24V_24V(SpeakerInput.V24, SpeakerSupply.V24),
    IN24V_48V(SpeakerInput.V24, SpeakerSupply.V48),
    IN24V_120V(SpeakerInput.V24, SpeakerSupply.V120),
    IN24V_240V(SpeakerInput.V24, SpeakerSupply.V240);

    /** What a speaker falls back to when a block entity outlives its block. */
    public static final SpeakerKind FALLBACK = IN24V_24V;

    private final SpeakerInput input;
    private final SpeakerSupply supply;

    SpeakerKind(SpeakerInput input, SpeakerSupply supply) {
        this.input = input;
        this.supply = supply;
    }

    public SpeakerInput input() {
        return input;
    }

    public SpeakerSupply supply() {
        return supply;
    }

    /** Registry name of this kind's block and item, and its mounted kind's name. */
    public String blockId() {
        return "speaker_1_alarm_in" + input.id() + "_" + supply.id();
    }

    public Component label() {
        return Component.translatable("block.create_flippies." + blockId());
    }
}
