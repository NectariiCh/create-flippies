package com.nectariich.create_flippies.content.label;

import com.nectariich.create_flippies.foundation.control.ControlRackHolder;
import com.nectariich.create_flippies.foundation.control.ControlFootprint;
import com.nectariich.create_flippies.foundation.control.ControlMount;
import com.nectariich.create_flippies.foundation.control.HostedControl;
import com.nectariich.create_flippies.foundation.control.SpannedControlType;
import com.nectariich.create_flippies.foundation.grid.PlacementGrid;

//? if forge {
import net.minecraft.SharedConstants;
//?} else {
/*import net.minecraft.util.StringUtil;
*///?}
import net.minecraft.util.Mth;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.phys.Vec3;

/**
 * Everything about a panel label that both sides of the connection have to agree
 * on: the strip of face it reserves, how the text on it is measured, and what a
 * player is allowed to write on one. The order a display link addresses several of
 * them in is not here, because a link numbers labels and gauges as one list — see
 * {@code ControlHostDisplayTarget}.
 *
 * The strip and the text metrics live together here on purpose. A label is nothing
 * but text on a panel, so the space it reserves is the rectangle the text occupies;
 * if the renderer worked out its own extent from its own numbers, the two would
 * drift apart, and a renderer disagreeing with a hitbox is a bug this project has
 * had twice already.
 *
 * Unlike every other control, that rectangle is not fixed by the kind: the player
 * draws it corner to corner and the label is made to fit — see
 * {@link SpannedControlType} for why printing is the one article that works that
 * way. So the geometry below is a function of a width and a height rather than a
 * set of constants, and {@link PanelLabelControl} holds the pair for one label.
 * What cannot vary is where the numbers are measured from: every box is centred on
 * the mount, which is what lets the drawn rectangle, the footprint and the text
 * anchor all come from the same two numbers.
 *
 * How the text is set inside that rectangle — which font, how tall, whether it is
 * fitted to the box — is {@link PanelLabelPrint}, and costs nothing on a dedicated
 * server: it is an enum and three integers, not a client type.
 */
public final class PanelLabel {

    private static final double PIXELS = PlacementGrid.PIXELS_PER_BLOCK;
    private static final double MIDDLE = PIXELS / 2.0;

    /**
     * The strip a label claims when nothing has said otherwise, in the half-pixel
     * steps a drawn rectangle is measured in — see
     * {@link SpannedControlType#SPANS_PER_PIXEL}. Seven pixels by two is the caption
     * beside one instrument: two fit side by side across a block with a pixel to
     * spare, and eight stack down it. It is what the kind reports as its footprint,
     * which is what a preview has to draw before any rectangle exists, and what a
     * label saved before spans existed reads back as.
     */
    public static final int DEFAULT_WIDTH = 7 * SpannedControlType.SPANS_PER_PIXEL;
    public static final int DEFAULT_HEIGHT = 2 * SpannedControlType.SPANS_PER_PIXEL;

    /**
     * How far the strip stands off the panel. Only a thin backing plate is drawn
     * there — a legend is ink on the panel, and a thick plate would hide the block
     * it is printed on — so this is what makes the label solid enough to aim at, and
     * what lifts the text clear of whatever is behind it.
     */
    public static final double PROUD = 0.25;

    /**
     * How much further a label stands off for each label it is laid over, in pixels.
     *
     * Two strips on one surface that overlap would otherwise put two plates and two
     * legends at exactly the same depth, and whichever the depth buffer happened to
     * prefer would flicker through the other. So a label drawn over others sits this
     * much proud of the highest of them, which is enough to settle the tie at any
     * distance a panel is read from and too little to look like a raised sign.
     */
    public static final double STACK_STEP = 0.1;

    /**
     * The most labels deep a stack is honoured. A label laid over one already this
     * high goes in at the same height rather than higher still: past this the strip
     * would be a block of wood rather than a legend, and nobody stacks sixteen.
     */
    public static final int MAX_LAYER = 16;

    /** A layer as it is safe to hold, from a save or anywhere else. */
    public static int layer(int layer) {
        return Mth.clamp(layer, 0, MAX_LAYER);
    }

    /** How far a label on that layer stands off the panel, in pixels. Layer zero is {@link #PROUD}. */
    public static double standOff(int layer) {
        return PROUD + STACK_STEP * layer(layer);
    }

    /** The strip as an authored pixel box in the {@code SurfaceMount} frame. */
    public static double[] plate(double width, double height) {
        return plate(width, height, PROUD);
    }

    /** The same strip standing {@code proud} pixels off the panel; see {@link #STACK_STEP}. */
    public static double[] plate(double width, double height, double proud) {
        return new double[] {
                MIDDLE - width / 2, 0, MIDDLE - height / 2,
                MIDDLE + width / 2, proud, MIDDLE + height / 2 };
    }

    /**
     * The space a label of that size takes up, which is the strip and nothing else.
     * The spans arrive in half pixels and the plate is authored in whole ones, which
     * is the one place the finer lattice has to be converted rather than carried.
     */
    public static ControlFootprint footprint(int width, int height) {
        return footprint(width, height, 0);
    }

    /** The same for a label on that layer of a stack, whose strip stands further off. */
    public static ControlFootprint footprint(int width, int height, int layer) {
        return ControlFootprint.of(
                plate(SpannedControlType.pixels(width), SpannedControlType.pixels(height), standOff(layer)));
    }

    /** The kind's own footprint; see {@link #DEFAULT_WIDTH}. */
    public static final ControlFootprint DEFAULT_FOOTPRINT = footprint(DEFAULT_WIDTH, DEFAULT_HEIGHT);

    /**
     * A span as it is safe to hold: whole half pixels, and within what a control may
     * cover. The upper bound is not the real limit — how far a strip reaches out of
     * its cell depends on where its centre landed as well as on how long it is — so
     * a rectangle drawn wider than the host will accept is still refused at
     * placement. This only stops a save or a packet describing a strip the length of
     * the world.
     */
    public static int span(int steps) {
        return Mth.clamp(steps, SpannedControlType.MIN_SPAN, SpannedControlType.MAX_SPAN);
    }

    /**
     * The middle of the strip's outer surface, in block units within the host cell,
     * which is where the text is centred. Every strip is centred on its mount, so
     * this is the same point whatever size the label was drawn at.
     */
    public static final Vec3 TEXT_ANCHOR = textAnchor(0);

    /** The same point for a label on that layer of a stack, on top of its own strip. */
    public static Vec3 textAnchor(int layer) {
        return new Vec3(MIDDLE / PIXELS, standOff(layer) / PIXELS, MIDDLE / PIXELS);
    }

    /** The drawn height of a glyph in font space; the ninth unit of a line is the gap below it. */
    public static final int GLYPH_HEIGHT = 8;

    /** Kept off the ends of the strip so two labels side by side do not run together. */
    public static final double TEXT_INSET = 0.5;

    /**
     * The most a stored legend may be. Generous next to what a small strip can show,
     * because the editor holds the whole thing and a legend trimmed on entry could
     * never be read back — a label narrowed after it was written still has its full
     * legend underneath; and short enough that a label is no use as somewhere to
     * park kilobytes of text on someone else's server. Two blocks of the smallest
     * text holds about forty characters, so this sits just above what the largest
     * strip can print and far below anything worth abusing.
     */
    public static final int MAX_LENGTH = 48;

    /**
     * What a label carries until somebody writes on it. A blank legend draws nothing
     * at all, and a control that cannot be seen reads as a failed placement rather
     * than as an empty one. Drawing a placeholder the label does not actually hold
     * was rejected instead: it would be indistinguishable from real text, and it
     * would come back every time a legend was cleared on purpose.
     */
    public static final String PLACEHOLDER = "LABEL";

    /**
     * How much of the chosen colour survives to the panel.
     *
     * A legend is printed on a panel, and printed ink is darker than the swatch it
     * was mixed from: the font is drawn unshaded at full brightness, so a colour
     * taken straight from a picker comes out as a lit sign rather than as lettering.
     * A quarter off sits the text down onto the block behind it while leaving every
     * colour recognisably the one that was asked for.
     */
    private static final float INK = 0.75f;

    /**
     * The label mounted at exactly this mount, or null if there is none.
     *
     * A mount identifies a control within its host because no two controls may
     * occupy the same space, which makes it the one handle on a label that a packet
     * can carry without depending on the order the host happens to hold its controls
     * in. An index into that list would have been shorter and is exactly what breaks
     * the day the host sorts differently.
     */
    public static PanelLabelControl at(BlockEntity blockEntity, ControlMount mount) {
        if (!(blockEntity instanceof ControlRackHolder host))
            return null;
        for (HostedControl control : host.controls())
            if (control instanceof PanelLabelControl label && mount.equals(label.mount()))
                return label;
        return null;
    }

    /**
     * A legend as it is safe to store and to draw: no formatting codes, no control
     * characters, and no longer than {@link #MAX_LENGTH}.
     *
     * The section sign has to go rather than be escaped. The stored string is drawn
     * straight through the font and put back into the editor unescaped, and it is
     * what one player writes for every other player to read, so a legacy formatting
     * code left in it would let anyone put obfuscated or invisible text on someone
     * else's panel. Vanilla's own chat filter is exactly this test, so it is used rather
     * than a second one written here.
     */
    public static String sanitise(String text) {
        //? if forge {
        String filtered = SharedConstants.filterText(text);
        //?} else {
        /*String filtered = StringUtil.filterText(text);
        *///?}
        if (filtered.length() <= MAX_LENGTH)
            return filtered;
        // Cutting between the halves of a surrogate pair leaves a string that is not
        // text at all, so the pair goes rather than half of it
        int end = Character.isHighSurrogate(filtered.charAt(MAX_LENGTH - 1)) ? MAX_LENGTH - 1 : MAX_LENGTH;
        return filtered.substring(0, end);
    }

    /** A colour a label can actually be seen in: whatever was asked for, opaque. */
    public static int sanitiseColour(int argb) {
        return argb | 0xFF000000;
    }

    /**
     * The colour of the plate itself when nobody has picked one.
     *
     * The plate is drawn from a white material and tinted, so this is a real grey
     * rather than "no tint": it is the shade the plate was before it could be
     * coloured at all, which is what a label saved before then has to keep looking
     * like. Every other colour is a tint of the same white, so what the player picks
     * comes out as itself less whatever the material's own shading takes off — the
     * same relation {@link #ink} states for the lettering.
     */
    public static final int DEFAULT_PLATE_COLOUR = 0xFF9B9B92;

    /**
     * The colour a chosen one is actually printed in; see {@link #INK}.
     *
     * Applied where the text is drawn rather than where it is stored, so the editor
     * puts back the colour that was picked instead of the darker one it came out as
     * — storing it would walk a label towards black every time it was opened.
     */
    public static int ink(int argb) {
        int red = (int) (((argb >> 16) & 0xFF) * INK);
        int green = (int) (((argb >> 8) & 0xFF) * INK);
        int blue = (int) ((argb & 0xFF) * INK);
        return (argb & 0xFF000000) | (red << 16) | (green << 8) | blue;
    }
}
