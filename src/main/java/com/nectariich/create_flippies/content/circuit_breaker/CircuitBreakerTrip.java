package com.nectariich.create_flippies.content.circuit_breaker;

import net.minecraft.ChatFormatting;

/**
 * Which of a type C breaker's two elements threw the latch, if either did.
 *
 * Worth keeping apart rather than reporting one undifferentiated "tripped",
 * because the two mean opposite things to whoever is stood in front of the panel
 * wondering what happened. A thermal trip is a circuit that works, asked to do a
 * little more than it was built for, and the cure is a bigger breaker or a
 * smaller load. A magnetic trip is a fault: something is shorted, and closing the
 * breaker again on it will do exactly the same thing a tenth of a second later.
 */
public enum CircuitBreakerTrip {

    /** Nothing tripped it. Either it is made, or somebody opened it by hand. */
    NONE("open", ChatFormatting.GRAY),

    /** The bimetal got there: a load over the rating, carried for long enough. */
    THERMAL("tripped_overload", ChatFormatting.GOLD),

    /** The coil got there: a fault current, opened on the spot. */
    MAGNETIC("tripped_short", ChatFormatting.RED);

    private final String stateKey;
    private final ChatFormatting color;

    CircuitBreakerTrip(String stateKey, ChatFormatting color) {
        this.stateKey = stateKey;
        this.color = color;
    }

    /** The last part of the goggle tooltip's key for this state. */
    public String stateKey() {
        return stateKey;
    }

    public ChatFormatting color() {
        return color;
    }

    /** The cause a save names, or {@link #NONE} if it names one this build does not have. */
    public static CircuitBreakerTrip byName(String name) {
        for (CircuitBreakerTrip cause : values())
            if (cause.name().equals(name))
                return cause;
        return NONE;
    }
}
