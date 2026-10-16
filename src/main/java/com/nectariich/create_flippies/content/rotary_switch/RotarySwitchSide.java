package com.nectariich.create_flippies.content.rotary_switch;

/**
 * Which half of a switch's front a hand has been put on, and what pushing there
 * does.
 *
 * A rotary switch is not a toggle: it is turned, and turning has a direction. You
 * push the side away from where the switch is to go — press the left of the front
 * and it turns to the right, press the right and it turns back — which is what
 * pushing a knob really does, and which is the only gesture that means anything
 * on a switch with three detents.
 *
 * Left and right are the printed front's own, not the player's: the marking arc is
 * painted on the body, so the side the arc calls left has to stay the left however
 * the switch is mounted or whichever way up the player's head is. It is authored
 * -X, which is where {@link RotarySwitchAim} resolves a hit to.
 *
 * The detent step follows from the handle angles. Both switches number their
 * detents clockwise as seen from the front — the two-position switch runs 0 then
 * -45 degrees, the three-position one +45, 0, -45 — so a clockwise push is one
 * detent up and an anticlockwise push is one down.
 */
public enum RotarySwitchSide {

    /** Pushed on the left, so the switch turns clockwise, onto the next detent. */
    LEFT(1, "<"),

    /** Pushed on the right, so the switch turns back anticlockwise. */
    RIGHT(-1, ">");

    private final int detentStep;
    private final String glyph;

    RotarySwitchSide(int detentStep, String glyph) {
        this.detentStep = detentStep;
        this.glyph = glyph;
    }

    /** How the detent number moves when this side is pushed. */
    public int detentStep() {
        return detentStep;
    }

    /**
     * What the crosshair becomes while this side is under it, drawn by
     * {@code RotarySwitchCrosshair}. The glyph names the side being pushed rather
     * than the way the handle will travel, so it always points at the half of the
     * switch the click will land on — which is what lets one arrow stand for both
     * push modes, since the half under the crosshair is the same either way.
     */
    public String glyph() {
        return glyph;
    }

    /** The other half of the front, and so the other way round the handle goes. */
    public RotarySwitchSide opposite() {
        return this == LEFT ? RIGHT : LEFT;
    }
}
