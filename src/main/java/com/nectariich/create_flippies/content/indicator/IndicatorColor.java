package com.nectariich.create_flippies.content.indicator;

import java.util.Locale;

import com.nectariich.create_flippies.CreateFlippies;

import net.minecraft.network.chat.Component;

/**
 * What colour lens or cap is fitted to an indicator.
 *
 * Per-unit state a screwdriver can change, not a block of its own, for the same
 * reason a rotary switch's printed front is: the lens is the one thing about an
 * indicator that a panel builder decides late and changes their mind about, and
 * nothing electrical or geometric follows from it. A unit already screwed into a
 * grid of others can be recoloured without being taken out.
 *
 * The first five are the standard pilot-light colours. Each has an unlit and a lit
 * model behind it — a lens with nothing across it is dark glass, and one that is lit
 * is the same hue turned up — and the pair is chosen at draw time from the voltage
 * the lamp is actually seeing, so the state is never written down anywhere.
 *
 * The last two are not lens colours at all. A black or grey cap is what an
 * unilluminated pushbutton wears, and it is the one thing on this list that says
 * something about the unit rather than about how it looks: fitting one to a lamp
 * that is meant to be watched would be a lamp nobody can see light. So they are
 * offered only while the unit is a button — see {@link #fits} — and a unit that is
 * taken back to {@link IndicatorAction#NO_CLICK} while wearing one is put back on the
 * default lens rather than left invisible.
 *
 * The id is this colour's half of the model and texture names and is what a save
 * holds, so it must stay as it is for as long as worlds do.
 */
public enum IndicatorColor {

    RED, AMBER, GREEN, BLUE, WHITE,

    /** Button only; see the class comment. */
    BLACK, GREY;

    private static final String LANG_PREFIX = "gui." + CreateFlippies.MOD_ID + ".indicator.color.";

    /** The models' stem: authored plate-down, like everything else panel-mounted. */
    private static final String STEM = "block/indicator-";

    /** This colour's half of the model and texture names, and what a save holds. */
    public String id() {
        return name().toLowerCase(Locale.ROOT);
    }

    /** What a freshly placed unit is fitted with, and the lens the item is painted. */
    public static IndicatorColor defaultColor() {
        return RED;
    }

    /** Whether this is a cap rather than a lens, and so only offered on a button. */
    public boolean buttonOnly() {
        return this == BLACK || this == GREY;
    }

    /** Whether an indicator doing this may wear this colour. */
    public boolean fits(IndicatorAction action) {
        return !buttonOnly() || action.clickable();
    }

    /**
     * The colour a unit doing this ends up wearing: whatever was asked for if it
     * fits, and the default lens if it does not.
     *
     * Applied wherever either of the two is set rather than only where the colour is,
     * because taking a button back to a plain lamp is the case that matters: the
     * black cap it was wearing would otherwise stay on and leave an indicator that
     * cannot be seen to light.
     */
    public static IndicatorColor sanitise(IndicatorColor color, IndicatorAction action) {
        return color.fits(action) ? color : defaultColor();
    }

    /** The next lens this unit can wear, skipping the ones its action forbids. */
    public IndicatorColor next(IndicatorAction action) {
        IndicatorColor[] all = values();
        for (int step = 1; step <= all.length; step++) {
            IndicatorColor candidate = all[(ordinal() + step) % all.length];
            if (candidate.fits(action))
                return candidate;
        }
        return this;
    }

    /**
     * The unit's body in this colour: barrel, lamp contacts and the three pairs of
     * button contacts, everything but the cap.
     *
     * Drawn separately from the lens so that a lit lamp can glow in an unlit room
     * without the body glowing with it — the body takes the light falling on the
     * panel and the lens does not. Nothing about the body changes when the lamp
     * lights or the cap is pushed, so there is one per size and colour rather than
     * one per state.
     */
    public String bodyModel(IndicatorSize size) {
        return STEM + size.id() + "-" + id() + "-body";
    }

    /**
     * The cap alone, dark or lit. This is the part drawn at full bright when the
     * lamp is on, which is what makes it bright of itself rather than lit by
     * something; the unit still throws no light of its own into the world. It is also
     * the part that moves: a pressed button's cap is drawn a little further into the
     * panel, and the body behind it does not follow.
     */
    public String lensModel(IndicatorSize size, boolean lit) {
        return STEM + size.id() + "-" + id() + (lit ? "-lit" : "") + "-lens";
    }

    public Component label() {
        return Component.translatable(LANG_PREFIX + id());
    }

    /** The colour saved under this id, or {@code fallback} if it is unknown. */
    public static IndicatorColor of(String id, IndicatorColor fallback) {
        for (IndicatorColor color : values())
            if (color.id().equals(id))
                return color;
        return fallback;
    }
}
