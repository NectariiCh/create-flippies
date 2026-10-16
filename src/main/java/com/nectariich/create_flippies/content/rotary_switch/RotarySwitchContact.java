package com.nectariich.create_flippies.content.rotary_switch;

import com.nectariich.create_flippies.CreateFlippies;

import net.minecraft.network.chat.Component;

import org.patryk3211.powergrid.electricity.base.IDecoratedTerminal;

/**
 * One kind of stud on a rotary switch's contact rack: what it is called, what
 * colour it is wired in, and where it stands.
 *
 * Every section of every switch has a common, and then one stud per place the
 * common can land. Which studs a section has is the {@link RotarySwitchKind}'s to
 * say — a two-position switch transfers between a normally-closed and a
 * normally-open stud, a three-position one selects between three — but what a stud
 * is called and where it sits on the rack is the same on every switch that has it,
 * which is what this states.
 *
 * The two-position switch's studs are named for what they do at rest and the
 * three-position switch's for the way the handle is turned to reach them. That is
 * not a whim. Normally-open and normally-closed only mean anything against a
 * resting position, and a centre-selecting switch has three of them; the printed
 * fronts read {@code − N +} and {@code HND OFF AUT} from left to right, so naming
 * the studs after the turn is the only naming that survives having a third.
 *
 * The z given here is the stud's centre in the authored frame, which the model
 * draws at 7, 8 and 9 across the rack's face. Commons stand alone on the opposite
 * face and are given the same 8 as the neutral stud they sit across from.
 */
public enum RotarySwitchContact {

    /** The wiper the section switches from, alone on the rack's -x face. */
    COMMON("cm", IDecoratedTerminal.GRAY, 8, true),

    /** Made when a two-position switch is turned to its second detent. */
    NORMALLY_OPEN("no", IDecoratedTerminal.GREEN, 7, false),

    /** Made when a two-position switch is at rest. */
    NORMALLY_CLOSED("nc", IDecoratedTerminal.RED, 9, false),

    /** Made when a three-position switch is turned anticlockwise. */
    COUNTER_CLOCKWISE("ccw", IDecoratedTerminal.GREEN, 7, false),

    /** Made when a three-position switch is in its middle detent. */
    NEUTRAL("n", IDecoratedTerminal.BLUE, 8, false),

    /** Made when a three-position switch is turned clockwise. */
    CLOCKWISE("cw", IDecoratedTerminal.RED, 9, false);

    private static final String LANG_PREFIX = "gui." + CreateFlippies.MOD_ID + ".rotary_switch.terminal.";

    private final String id;
    private final int color;
    private final double z;
    private final boolean common;

    RotarySwitchContact(String id, int color, double z, boolean common) {
        this.id = id;
        this.color = color;
        this.z = z;
        this.common = common;
    }

    /** This contact's half of the model element names, and its lang key. */
    public String id() {
        return id;
    }

    public int color() {
        return color;
    }

    /** Centre of the stud across the rack's face, in the authored frame. */
    public double z() {
        return z;
    }

    /** Whether this stands on the rack's -x face rather than sharing the +x one. */
    public boolean isCommon() {
        return common;
    }

    /** What a wire-holding player sees, e.g. {@code B Clockwise}. */
    public Component name(String section) {
        return Component.translatable(LANG_PREFIX + id, section);
    }
}
