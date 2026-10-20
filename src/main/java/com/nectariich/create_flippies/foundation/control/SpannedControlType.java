package com.nectariich.create_flippies.foundation.control;

import com.nectariich.create_flippies.foundation.grid.PlacementGrid;
import com.nectariich.create_flippies.foundation.panel.PanelPlane;

/**
 * A kind of control the player sizes as they place it, by picking two corners on
 * the surface rather than one point in a cell.
 *
 * Nearly every control is a piece of gear that comes in whatever size it comes in:
 * a switch front is four pixels across because that is how it is built, and the
 * kind is the right place to say so. Printing is the exception. A legend is
 * whatever length the thing it names wants it to be, and a panel with a caption
 * over each instrument and a heading over each group needs several sizes of the
 * same article, so making a kind per size would be a catalogue of near-duplicates
 * that still would not cover the next panel.
 *
 * So a spanned control's footprint belongs to the instance rather than to the kind
 * — {@link HostedControl#footprint} is what everything asks — and the kind's own
 * {@link ControlType#footprint} answers for the default one, which is what an
 * item's tooltip and a preview have to draw before any rectangle exists.
 *
 * The extents handed over are in the authored frame rather than the plane's,
 * because that is the frame the footprint is written in; the placement path turns
 * the drawn rectangle into it with {@link ControlMount#swapsPlaneAxes}.
 */
public interface SpannedControlType {

    /**
     * Steps of span in one pixel, which is the resolution the corners of a drawn
     * rectangle snap to.
     *
     * Half a pixel rather than a whole one, which doubles the lattice along each axis
     * and so gives four times as many rectangles between any two points. What that
     * buys is the thing whole pixels could not do: a caption half a pixel narrower
     * than the gauge beside it, or a strip whose ends line up with the middle of a
     * cell rather than with its edges. Nothing downstream had to give way for it —
     * every span below is simply counted in these steps instead of in pixels, and the
     * one place the unit shows is where a label is drawn, which already worked in
     * fractions of a pixel because the text is scaled by a float.
     *
     * It stays a lattice, and that is what stops it going finer still: a span is an
     * integer so that both ends of one rectangle land on the same ruling however the
     * player drew it, and the plate's own geometry is stated in the same steps.
     */
    int SPANS_PER_PIXEL = 2;

    /** One step of span in {@link PanelPlane#UNITS_PER_BLOCK}ths, which is what a corner snaps to. */
    int UNITS_PER_SPAN = PanelPlane.UNITS_PER_PIXEL / SPANS_PER_PIXEL;

    /** The smallest span worth drawing: below half a pixel there is nothing to see. */
    int MIN_SPAN = 1;

    /**
     * The largest span, in steps. Two blocks; with its centre anywhere on the
     * host's face that reaches at most one block out of the cell, well inside
     * {@link ControlFootprint#REACH_BUDGET}.
     */
    int MAX_SPAN = 2 * PlacementGrid.PIXELS_PER_BLOCK * SPANS_PER_PIXEL;

    /** The same bound in whole pixels, which is the unit a message about it speaks in. */
    static int maxPixels() {
        return MAX_SPAN / SPANS_PER_PIXEL;
    }

    /** A span in steps as the pixels it covers, which may be a half. */
    static double pixels(int span) {
        return span / (double) SPANS_PER_PIXEL;
    }

    /**
     * A fresh control of this kind covering {@code width} pixels along the authored
     * x axis and {@code height} along z, in its default state otherwise.
     */
    HostedControl createSpanning(int width, int height);
}
