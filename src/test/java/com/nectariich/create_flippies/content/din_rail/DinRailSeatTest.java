package com.nectariich.create_flippies.content.din_rail;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.nectariich.create_flippies.foundation.SurfaceMount;
import com.nectariich.create_flippies.foundation.control.ControlMount;
import com.nectariich.create_flippies.foundation.grid.PlacementGrid;

import net.minecraft.core.Direction;
import net.minecraft.core.Direction.Axis;
import net.minecraft.util.Mth;

import org.junit.jupiter.api.Test;

/**
 * {@link DinRailSeat} against the arithmetic the rail block used before it was
 * pulled out, copied here verbatim: every facing, both turns, one and two rails,
 * every rail, every half-module start and every width gear comes in must seat
 * exactly as it always did, and every click must snap to the same start.
 */
class DinRailSeatTest {

    /** Widths of real gear (relay mount 0.5, breakers 1-4, PSUs and the PLC wider) and some odd ones. */
    private static final double[] WIDTHS = {0.5, 1, 1.5, 2, 3, 4, 5, 6, 8, 9};

    // The block rail's arithmetic as it stood, from DinRailBlockEntity

    private static double railCentre(int rails, int index) {
        return 16.0 / rails * (index + 0.5);
    }

    private static ControlMount legacyMountFor(Direction facing, int rotation, int rails, int rail, double start,
            double width) {
        double railCentre = railCentre(rails, rail);
        double[] target = SurfaceMount.point(start + width / 2, 0, railCentre, facing, rotation);
        double[] middle = SurfaceMount.point(PlacementGrid.PIXELS_PER_BLOCK / 2.0, 0,
                PlacementGrid.PIXELS_PER_BLOCK / 2.0, facing, rotation);
        return ControlMount.onFace(facing,
                legacyAnchor(target, middle, PlacementGrid.uAxis(facing.getAxis())),
                legacyAnchor(target, middle, PlacementGrid.vAxis(facing.getAxis())),
                rotation);
    }

    private static int legacyAnchor(double[] target, double[] middle, Axis axis) {
        int index = axis.choose(0, 1, 2);
        return ControlMount.CENTRED
                + (int) Math.round((target[index] - middle[index]) * ControlMount.UNITS_PER_PIXEL);
    }

    private static double legacyStart(double clicked, double width) {
        double step = width == Math.rint(width) ? 1 : 0.5;
        return Mth.clamp(Math.floor(clicked / step) * step, 0, DinRailBlock.MODULES - width);
    }

    @Test
    void seatsEveryRailExactlyAsTheBlockDid() {
        int compared = 0;
        for (Direction facing : Direction.values())
            for (int rotation = 0; rotation < 2; rotation++)
                for (int rails = 1; rails <= DinRailBlock.MAX_RAILS; rails++)
                    for (int rail = 0; rail < rails; rail++)
                        for (double width : WIDTHS)
                            for (double start = 0; start + width <= 16; start += 0.5) {
                                ControlMount legacy = legacyMountFor(facing, rotation, rails, rail, start, width);
                                ControlMount seated = DinRailSeat.seat(facing, rotation, DinRailSeat.target(facing,
                                        rotation, railCentre(rails, rail), start, width), 0);
                                assertEquals(legacy, seated, facing + " r" + rotation + " " + rails + " rails, rail "
                                        + rail + ", start " + start + ", width " + width);
                                compared++;
                            }
        assertTrue(compared > 1000, "compared only " + compared);
    }

    @Test
    void snapsEveryClickToTheSameStart() {
        for (double width : WIDTHS)
            for (double clicked = -2; clicked <= 18; clicked += 1 / 64.0)
                assertEquals(legacyStart(clicked, width), DinRailSeat.snapStart(clicked, width, 0, DinRailBlock.MODULES),
                        "click " + clicked + ", width " + width);
    }

    /** A lift moves the plate back along the facing and leaves the in-plane anchors where they were. */
    @Test
    void liftOnlyMovesTheDepthAnchor() {
        for (Direction facing : Direction.values()) {
            double[] target = DinRailSeat.target(facing, 1, 8, 3, 2);
            ControlMount flush = DinRailSeat.seat(facing, 1, target, 0);
            ControlMount lifted = DinRailSeat.seat(facing, 1, target, ControlMount.UNITS_PER_PIXEL);
            assertEquals(flush.anchorU(), lifted.anchorU());
            assertEquals(flush.anchorV(), lifted.anchorV());
            assertEquals(ControlMount.ON_FACE - ControlMount.UNITS_PER_PIXEL, lifted.anchorD());
            assertTrue(lifted.isOnFace());
            // The plate's centre stands one pixel out of the face, against the facing
            double[] a = flush.point(8, 0, 8), b = lifted.point(8, 0, 8);
            assertEquals(-facing.getStepX(), b[0] - a[0], 1e-9);
            assertEquals(-facing.getStepY(), b[1] - a[1], 1e-9);
            assertEquals(-facing.getStepZ(), b[2] - a[2], 1e-9);
        }
    }
}
