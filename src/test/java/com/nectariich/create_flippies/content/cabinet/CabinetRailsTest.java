package com.nectariich.create_flippies.content.cabinet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;

import com.nectariich.create_flippies.content.cabinet.CabinetRails.Problem;

import org.junit.jupiter.api.Test;

/** Laying rails on a back plate: overlaps, the walls, and turning two clicks into a rail. */
class CabinetRailsTest {

    /** 32 x 32 px, 8 deep: the back plate runs y -15..15, z -7..23. */
    private static final CabinetSpec BIG = new CabinetSpec(32, 32, 8, 0xCBD0CC, 0, CabinetSpec.Hinge.LEFT).clamp();

    private static CabinetRail level(int id, int start, int centre, int length) {
        return new CabinetRail(id, false, start, centre, length);
    }

    private static CabinetRail upright(int id, int start, int centre, int length) {
        return new CabinetRail(id, true, start, centre, length);
    }

    @Test
    void plateIsWhereTheTestThinks() {
        CabinetGeometry.BackPlane plate = CabinetGeometry.backPlane(BIG);
        assertEquals(-15, plate.y0());
        assertEquals(15, plate.y1());
        assertEquals(-7, plate.z0());
        assertEquals(23, plate.z1());
    }

    @Test
    void parallelRailsMayTouch() {
        List<CabinetRail> rails = List.of(level(0, -7, 0, 30));
        // 3.5 px wide: 4 px apart leaves half a pixel, 3 apart overlaps
        assertEquals(Problem.NONE, CabinetRails.validate(BIG, rails, level(1, -7, 4, 30)));
        assertEquals(Problem.NONE, CabinetRails.validate(BIG, rails, level(1, -7, -4, 30)));
        assertEquals(Problem.CROWDED, CabinetRails.validate(BIG, rails, level(1, -7, 3, 30)));
        assertEquals(Problem.CROWDED, CabinetRails.validate(BIG, rails, level(1, 10, -1, 3)));
        // Not crowded where they do not run side by side
        List<CabinetRail> half = List.of(level(0, -7, 0, 10));
        assertEquals(Problem.NONE, CabinetRails.validate(BIG, half, level(1, 3, 1, 10)));
        assertEquals(Problem.CROWDED, CabinetRails.validate(BIG, half, level(1, 2, 1, 10)));
    }

    @Test
    void railsEndToEndAreFine() {
        List<CabinetRail> rails = List.of(level(0, -7, 0, 10));
        assertEquals(Problem.NONE, CabinetRails.validate(BIG, rails, level(1, 3, 0, 10)));
        assertEquals(Problem.CROWDED, CabinetRails.validate(BIG, rails, level(1, 2, 0, 10)));
    }

    @Test
    void railsDoNotCrossButMayButt() {
        List<CabinetRail> rails = List.of(level(0, -7, 0, 30));
        assertEquals(Problem.CROWDED, CabinetRails.validate(BIG, rails, upright(1, -15, 8, 30)));
        // An upright one standing on the level one, and one just into it
        assertEquals(Problem.NONE, CabinetRails.validate(BIG, rails, upright(1, 2, 8, 11)));
        assertEquals(Problem.CROWDED, CabinetRails.validate(BIG, rails, upright(1, 1, 8, 11)));
        // Beside the level rail's end
        List<CabinetRail> stub = List.of(level(0, -7, 0, 10));
        assertEquals(Problem.NONE, CabinetRails.validate(BIG, stub, upright(1, -15, 5, 30)));
        assertEquals(Problem.CROWDED, CabinetRails.validate(BIG, stub, upright(1, -15, 4, 30)));
    }

    @Test
    void railStaysInsideTheCabinet() {
        List<CabinetRail> none = List.of();
        // Across: its own width in from the top and bottom, no more
        assertEquals(Problem.NONE, CabinetRails.validate(BIG, none, level(0, -7, 13, 30)));
        assertEquals(Problem.OFF_PLATE, CabinetRails.validate(BIG, none, level(0, -7, 14, 30)));
        assertEquals(Problem.NONE, CabinetRails.validate(BIG, none, level(0, -7, -13, 30)));
        assertEquals(Problem.OFF_PLATE, CabinetRails.validate(BIG, none, level(0, -7, -14, 30)));
        // Along: right up to the side walls, not through them
        assertEquals(Problem.OFF_PLATE, CabinetRails.validate(BIG, none, level(0, -8, 0, 31)));
        assertEquals(Problem.OFF_PLATE, CabinetRails.validate(BIG, none, level(0, -7, 0, 31)));
        assertEquals(Problem.NONE, CabinetRails.validate(BIG, none, upright(0, -15, -5, 30)));
        assertEquals(Problem.OFF_PLATE, CabinetRails.validate(BIG, none, upright(0, -15, -6, 30)));
        assertEquals(Problem.NONE, CabinetRails.validate(BIG, none, upright(0, -15, 21, 30)));
        assertEquals(Problem.OFF_PLATE, CabinetRails.validate(BIG, none, upright(0, -15, 22, 30)));
    }

    @Test
    void smallCabinetTakesNoRail() {
        // 5 px tall: the plate is 3, narrower than a rail
        CabinetSpec low = new CabinetSpec(32, 5, 8, 0xCBD0CC, 0, CabinetSpec.Hinge.LEFT).clamp();
        CabinetRail rail = CabinetRails.fromTwoPoints(low, 0, 0, -5, 0, 20);
        assertEquals(Problem.OFF_PLATE, CabinetRails.validate(low, List.of(), rail));
        // 6 px tall: one level rail fits, on the middle line
        CabinetSpec fits = new CabinetSpec(32, 6, 8, 0xCBD0CC, 0, CabinetSpec.Hinge.LEFT).clamp();
        CabinetGeometry.BackPlane plate = CabinetGeometry.backPlane(fits);
        int middle = (int) ((plate.y0() + plate.y1()) / 2);
        assertEquals(Problem.NONE,
                CabinetRails.validate(fits, List.of(), CabinetRails.fromTwoPoints(fits, 0, middle, -5, middle, 20)));
    }

    @Test
    void tooShortAndTooMany() {
        assertEquals(Problem.TOO_SHORT, CabinetRails.validate(BIG, List.of(), level(0, 0, 0, 0)));
        List<CabinetRail> full = new ArrayList<>();
        for (int i = 0; i < CabinetRails.MAX_RAILS; i++)
            full.add(level(i, 0, 0, 1));
        assertEquals(Problem.TOO_MANY, CabinetRails.validate(BIG, full, level(99, 0, 0, 1)));
    }

    @Test
    void dominantAxisPicksTheRun() {
        CabinetRail rail = CabinetRails.fromTwoPoints(BIG, 3, 0.4, 0.2, 2.0, 9.7);
        assertFalse(rail.vertical());
        assertEquals(3, rail.id());
        assertEquals(0, rail.centre());
        assertEquals(0, rail.start());
        assertEquals(10, rail.end());

        CabinetRail up = CabinetRails.fromTwoPoints(BIG, 0, 5.5, 4.4, -6.2, 6.0);
        assertTrue(up.vertical());
        assertEquals(4, up.centre());
        assertEquals(-7, up.start());
        assertEquals(6, up.end());
    }

    @Test
    void draggedBackwardsIsTheSameRail() {
        CabinetRail forwards = CabinetRails.fromTwoPoints(BIG, 0, 0, 1.5, 0, 12.5);
        CabinetRail backwards = CabinetRails.fromTwoPoints(BIG, 0, 0, 12.5, 0, 1.5);
        assertEquals(forwards.start(), backwards.start());
        assertEquals(forwards.length(), backwards.length());
        assertEquals(12, forwards.length());
    }

    @Test
    void oneClickIsOneModule() {
        CabinetRail rail = CabinetRails.fromTwoPoints(BIG, 0, 0.3, 5.2, 0.3, 5.2);
        assertEquals(1, rail.length());
        assertEquals(5, rail.start());
    }

    @Test
    void dragPastTheWallStopsAtIt() {
        CabinetRail rail = CabinetRails.fromTwoPoints(BIG, 0, 0, 10, 0, 60);
        assertEquals(23, rail.end());
        CabinetRail down = CabinetRails.fromTwoPoints(BIG, 0, 0, 4, -40, 4);
        assertEquals(-15, down.start());
        assertEquals(Problem.NONE, CabinetRails.validate(BIG, List.of(), rail));
    }

    @Test
    void bandIsTheRailsOwnWidth() {
        double[] band = CabinetRails.band(level(0, 2, 5, 6));
        assertEquals(3.25, band[0]);
        assertEquals(2, band[1]);
        assertEquals(6.75, band[2]);
        assertEquals(8, band[3]);
    }

    @Test
    void savesAndLoads() {
        CabinetRail rail = upright(7, -3, 9, 12);
        assertEquals(rail, CabinetRail.load(rail.save()));
    }
}
