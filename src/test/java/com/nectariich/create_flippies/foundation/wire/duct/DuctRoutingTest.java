package com.nectariich.create_flippies.foundation.wire.duct;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;

import com.nectariich.create_flippies.foundation.wire.bend.WireBends;

import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.DoubleTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import org.junit.jupiter.api.Test;

/**
 * Power Grid placeable wires run through ducts on the floor of a room at y = 64:
 * duct A runs east from (2, 2) to (6, 2), duct B turns south from there to (6, 6),
 * and duct C lies apart at (10, 2) to (12, 2). A wire lies on a face half a pixel
 * off it, in the duct as on the floor.
 *
 * A wire is laid as the player lays it: in through a duct's side with a click, its
 * end then held to the duct and set at each further click on it ("drag, set a
 * point"), and out through the side facing whatever is clicked next.
 */
class DuctRoutingTest {

    private static final double H = DuctSize.SMALL.depth() / 2;
    private static final double W = DuctSize.SMALL.width();
    private static final double Y = 64 + H;
    /** Where a wire's centre runs over the floor. */
    private static final double FLOOR = 64 + DuctWires.WIRE_DEPTH;
    /** Where a stroke lies: just over the lid. */
    private static final double LID = 64 + DuctSize.SMALL.depth() + DuctWires.STROKE_CLEARANCE;
    private static final double PX = 1 / 16.0;
    private static final double UNIT = DuctWires.UNIT;

    private static final DuctSegment A = new DuctSegment(new Vec3(2, Y, 2), new Vec3(6, Y, 2), Direction.UP,
            DuctSize.SMALL);
    private static final DuctSegment B = new DuctSegment(new Vec3(6, Y, 2), new Vec3(6, Y, 6), Direction.UP,
            DuctSize.SMALL);
    private static final DuctSegment C = new DuctSegment(new Vec3(10, Y, 2), new Vec3(12, Y, 2), Direction.UP,
            DuctSize.SMALL);

    /** The ducts as placed: A cut back at the corner by half a width, B run on by as much. */
    private static final DuctWires.Duct DA = new DuctWires.Duct(A, A.box(0, -W / 2));
    private static final DuctWires.Duct DB = new DuctWires.Duct(B, B.box(W / 2, 0));
    private static final DuctWires.Duct DC = DuctWires.Duct.of(C);
    private static final List<DuctWires.Duct> ROOM = List.of(DA, DB, DC);

    /** The end of a wire laid on the floor north of A. */
    private static final Vec3 START = new Vec3(4 + PX / 2, FLOOR, PX / 2);

    /** A wire being laid: its path so far, its gauge and what names it. */
    private record Wire(List<Vec3> path, int awg, long tie) {

        Vec3 end() {
            return path.get(path.size() - 1);
        }
    }

    private static Wire wire(Vec3 start, int awg, long tie) {
        List<Vec3> path = new ArrayList<>();
        path.add(start);
        return new Wire(path, awg, tie);
    }

    private static void assertVec(Vec3 expected, Vec3 actual) {
        assertEquals(expected.x, actual.x, 1e-6, "x of " + actual);
        assertEquals(expected.y, actual.y, 1e-6, "y of " + actual);
        assertEquals(expected.z, actual.z, 1e-6, "z of " + actual);
    }

    /** A chain of steps from the wire's end through {@code targets}, appended to it, as Power Grid lays it. */
    private static void lay(Wire wire, List<Vec3> targets, Direction.Axis last) {
        Vec3 from = wire.end();
        List<DuctWires.Step> steps = DuctWires.steps(from, targets, last);
        List<Vec3> added = DuctWires.path(from, steps);
        wire.path().addAll(added.subList(1, added.size()));
    }

    private static Direction.Axis across(DuctSegment s) {
        Vec3 l = s.lateral();
        return Direction.getNearest(l.x, l.y, l.z).getAxis();
    }

    /** Takes a wire into {@code s} at {@code click}, as a click on the duct there does; whether it fit. */
    private static DuctWires.Fit enter(Wire wire, DuctSegment s, Vec3 click, List<DuctWires.Occupant> present) {
        Vec3 center = DuctWires.snapOnCentre(s, click);
        int side = DuctWires.sideToward(s, center, wire.end());
        double along = s.along(center);
        DuctWires.Fit fit = DuctWires.fit(s.size(), wire.awg(),
                List.of(new DuctWires.Occupant(s, along, along, 0, 1, wire.awg(), wire.tie())), present);
        if (fit == DuctWires.Fit.OK)
            lay(wire, DuctWires.entry(s, center, side, DuctWires.entryOffset(s, along, side, present)), across(s));
        return fit;
    }

    /**
     * With a wire's end held in a duct, the point the crosshair drags it to along
     * {@code chain} (the last one being the duct looked at), set with a click: the
     * wire slides there keeping its place across the duct. Whether it fit.
     */
    private static DuctWires.Fit setPoint(Wire wire, List<DuctSegment> chain, Vec3 eye, Vec3 look,
            List<DuctWires.Occupant> present) {
        DuctSegment target = chain.get(chain.size() - 1);
        Vec3 dragged = DuctWires.snapOnCentre(target, DuctWires.nearestToSight(target, eye, look));
        return slide(wire, chain, dragged, present);
    }

    private static DuctWires.Fit slide(Wire wire, List<DuctSegment> chain, Vec3 click,
            List<DuctWires.Occupant> present) {
        Vec3 from = wire.end();
        DuctPass pass = DuctNetwork.route(chain, from, DuctWires.snapOnCentre(chain.get(chain.size() - 1), click), 0);
        List<Vec3> points = pass.withOffset(DuctWires.slideOffset(pass, from)).wirePoints();
        List<Vec3> way = new ArrayList<>(List.of(from));
        way.addAll(points);
        List<DuctWires.Occupant> adding = new ArrayList<>();
        for (DuctSegment s : chain)
            adding.addAll(DuctWires.occupancy(s, way, wire.awg(), wire.tie()));
        DuctWires.Fit fit = DuctWires.fit(pass.size(), wire.awg(), adding, present);
        if (fit == DuctWires.Fit.OK)
            lay(wire, points, null);
        return fit;
    }

    /** Takes a wire whose end is in {@code s} out through the side facing {@code toward}. */
    private static Vec3 exit(Wire wire, DuctSegment s, Vec3 toward) {
        Vec3 center = s.project(wire.end());
        Vec3 out = DuctWires.beside(s, center, DuctWires.sideToward(s, center, toward));
        lay(wire, List.of(out), null);
        return out;
    }

    private static List<DuctWires.Occupant> occupants(List<DuctSegment> ducts, Wire... wires) {
        List<DuctWires.Occupant> out = new ArrayList<>();
        for (Wire wire : wires)
            for (DuctSegment s : ducts)
                out.addAll(DuctWires.occupancy(s, wire.path(), wire.awg(), wire.tie()));
        return out;
    }

    private static List<DuctWires.Occupant> occupants(Wire... wires) {
        return occupants(List.of(A, B, C), wires);
    }

    /** Slots as the renderer finds them: each stretch laid out among every wire there. */
    private static DuctWires.Slots slots(long tie, List<DuctWires.Occupant> present) {
        return (s, at) -> DuctWires.slotIn(DuctWires.layout(s, present), tie, s, at);
    }

    private static List<DuctWires.Dash> lines(Wire wire, List<DuctWires.Duct> ducts, List<DuctWires.Occupant> all) {
        return DuctWires.lines(wire.path(), DuctWires.hidden(wire.path(), DuctWires.holding(wire.path(), ducts)),
                slots(wire.tie(), all), 0.05);
    }

    private static double volume(AABB a, AABB b) {
        double x = Math.min(a.maxX, b.maxX) - Math.max(a.minX, b.minX);
        double y = Math.min(a.maxY, b.maxY) - Math.max(a.minY, b.minY);
        double z = Math.min(a.maxZ, b.maxZ) - Math.max(a.minZ, b.minZ);
        return x <= 1e-9 || y <= 1e-9 || z <= 1e-9 ? 0 : x * y * z;
    }

    // Going in

    @Test
    void aClickOnAnEmptyDuctTakesTheWireInThroughTheSideFacingItToTheCentre() {
        Wire wire = wire(START, 12, 1);
        assertEquals(DuctWires.Fit.OK, enter(wire, A, new Vec3(4.01, 64.1, 2.02), List.of()));
        Vec3 beside = wire.path().get(1), in = wire.path().get(2);
        assertVec(new Vec3(4 + PX / 2, FLOOR, 2 - W / 2 - DuctWires.SIDE_GAP), beside);
        assertVec(new Vec3(4 + PX / 2, FLOOR, 2), in);
        assertFalse(DuctWires.holds(DA, beside));
        assertTrue(DuctWires.holds(DA, in));
        // It is one chain of axis-aligned steps, on Power Grid's own grid
        List<DuctWires.Step> steps = DuctWires.steps(START, wire.path().subList(1, wire.path().size()),
                Direction.Axis.Z);
        for (DuctWires.Step step : steps)
            assertEquals(Direction.SOUTH, step.direction());
        assertEquals(List.of(DA), DuctWires.holding(wire.path(), ROOM));
    }

    @Test
    void aSecondWireGoesInJustOutsideTheFirstOnItsOwnSideSoTheyNeverCross() {
        Wire first = wire(START, 12, 1);
        enter(first, A, new Vec3(4, 64.1, 2), List.of());
        Wire north = wire(new Vec3(3 + PX / 2, FLOOR, PX / 2), 12, 2);
        enter(north, A, new Vec3(3, 64.1, 2), occupants(first));
        // A's lateral points north, so the north one is one unit further that way
        assertEquals(2 - UNIT, north.end().z, 1e-9);
        Wire south = wire(new Vec3(5 + PX / 2, FLOOR, 4 + PX / 2), 12, 3);
        enter(south, A, new Vec3(5, 64.1, 2), occupants(first, north));
        assertEquals(2 + UNIT, south.end().z, 1e-9);
    }

    // Held to the duct: drag, set a point, again, and out

    @Test
    void theEndFollowsTheCrosshairAlongTheDuctAndAClickSetsThePoint() {
        Wire wire = wire(START, 12, 1);
        enter(wire, A, new Vec3(4, 64.1, 2), List.of());
        // Standing south of A looking down at x = 5.3 a little off the duct: the end is dragged to x = 5.3
        Vec3 eye = new Vec3(5.3, 65.62, 4);
        Vec3 look = new Vec3(5.3, 64, 2.3).subtract(eye).normalize();
        Vec3 dragged = DuctWires.nearestToSight(A, eye, look);
        assertEquals(5.3, dragged.x, 1e-6);
        assertEquals(DuctWires.Fit.OK, setPoint(wire, List.of(A), eye, look, List.of()));
        // Set on the centre of the pixel it falls in
        assertVec(new Vec3(5.28125, FLOOR, 2), wire.end());
        // A look past the end of the duct stops at its end
        Vec3 far = DuctWires.nearestToSight(A, new Vec3(9, 65.6, 4), new Vec3(1, -0.2, 0).normalize());
        assertEquals(6, far.x, 1e-9);
    }

    @Test
    void setPointsFollowJoinsAndTurnsAndKeepTheWireOnItsSide() {
        Wire wire = wire(START, 12, 1);
        enter(wire, A, new Vec3(4, 64.1, 2), List.of());
        Wire outer = wire(new Vec3(3 + PX / 2, FLOOR, PX / 2), 12, 2);
        enter(outer, A, new Vec3(3, 64.1, 2), occupants(wire));
        // First point on A, then on round the corner onto B
        assertEquals(DuctWires.Fit.OK, slide(wire, List.of(A), new Vec3(5, 64.1, 2), occupants(outer)));
        assertEquals(DuctWires.Fit.OK, slide(wire, List.of(A, B), new Vec3(6, 64.1, 5), occupants(outer)));
        assertEquals(DuctWires.Fit.OK, slide(outer, List.of(A, B), new Vec3(6, 64.1, 5), occupants(wire)));
        // A's north side is the outside of the turn south; outside it stays, on B's east side
        assertEquals(6 + UNIT, outer.end().x, 1e-9);
        assertEquals(6, wire.end().x, 1e-9);
        assertEquals(List.of(DA, DB), DuctWires.holding(wire.path(), ROOM));
    }

    @Test
    void clickingElsewhereTakesTheWireOutSidewaysAndItStaysOneChain() {
        Wire wire = wire(START, 12, 1);
        enter(wire, A, new Vec3(4, 64.1, 2), List.of());
        slide(wire, List.of(A, B), new Vec3(6, 64.1, 5), List.of());
        Vec3 end = wire.end();
        // On to a terminal east of B: out through B's east side
        Vec3 out = exit(wire, B, new Vec3(9, 65, 5));
        assertVec(new Vec3(6 + W / 2 + DuctWires.SIDE_GAP, FLOOR, end.z), out);
        assertFalse(DuctWires.holds(DB, out));
        // And on along the floor as Power Grid would lay it
        lay(wire, List.of(new Vec3(9 + PX / 2, FLOOR, end.z)), null);
        for (int i = 0; i + 1 < wire.path().size(); i++) {
            Vec3 d = wire.path().get(i + 1).subtract(wire.path().get(i));
            int axes = (Math.abs(d.x) > 1e-9 ? 1 : 0) + (Math.abs(d.y) > 1e-9 ? 1 : 0) + (Math.abs(d.z) > 1e-9 ? 1 : 0);
            assertEquals(1, axes, "every step runs along one axis");
        }
        List<DuctWires.Span> visible = DuctWires.visible(wire.path(),
                DuctWires.hidden(wire.path(), List.of(DA, DB)));
        assertVec(START, visible.get(0).from());
        assertVec(wire.end(), visible.get(visible.size() - 1).to());
    }

    @Test
    void aWireLeavesADuctOnAnyFaceThroughTheSideFacingWhereItGoes() {
        for (Direction face : Direction.values()) {
            Direction.Axis[] inPlane = DuctPlacement.inPlane(face.getAxis());
            Vec3 n = Vec3.atLowerCornerOf(face.getNormal());
            Vec3 along = Vec3.atLowerCornerOf(Direction.fromAxisAndDirection(inPlane[0],
                    Direction.AxisDirection.POSITIVE).getNormal());
            // The block face the duct lies on passes through (8, 64, 8)
            Vec3 onFace = new Vec3(8, 64, 8);
            Vec3 from = onFace.add(n.scale(H));
            DuctSegment s = new DuctSegment(from, from.add(along.scale(3)), face, DuctSize.SMALL);
            DuctWires.Duct d = DuctWires.Duct.of(s);
            Vec3 lat = s.lateral();
            for (int side : new int[] {1, -1}) {
                // A wire on the face, beside the duct on this side
                Vec3 start = onFace.add(n.scale(DuctWires.WIRE_DEPTH)).add(along.scale(1 + PX / 2))
                        .add(lat.scale(side * 0.5 + PX / 2));
                Wire wire = wire(start, 12, 7);
                assertEquals(DuctWires.Fit.OK, enter(wire, s, start.subtract(lat.scale(side * 0.5)), List.of()),
                        face + " " + side);
                assertTrue(DuctWires.holds(d, wire.end()), face + ": in");
                slide(wire, List.of(s), from.add(along.scale(2.5)), List.of());
                Vec3 end = wire.end();
                // Clicked: something on the far side of the duct from where the wire came in
                Vec3 target = end.add(lat.scale(-side * 2));
                Vec3 out = exit(wire, s, target);
                assertFalse(DuctWires.holds(d, out), face + ": out");
                assertEquals(DuctWires.WIRE_DEPTH, out.subtract(onFace).dot(n), 1e-9, face + ": on the face");
                assertEquals(-side * (W / 2 + DuctWires.SIDE_GAP), s.lateralOffset(out), 1e-9,
                        face + ": through the side facing the target");
                Vec3 step = out.subtract(end);
                assertEquals(step.length(), Math.abs(step.dot(lat)), 1e-9, face + ": straight across");
                assertEquals(List.of(d), DuctWires.holding(wire.path(), List.of(d)));
            }
        }
    }

    // Capacity by cross-section

    @Test
    void gaugesAreReadFromTheWireAndPowerGridsOwnCountAsTwelve() {
        assertEquals(4, DuctWires.awgOf("create_flippies:copper_wire_4awg"));
        assertEquals(2, DuctWires.awgOf("powerplantgrid:insulated_golden_wire_2awg"));
        assertEquals(0, DuctWires.awgOf("create_flippies:iron_wire_0awg"));
        assertEquals(12, DuctWires.awgOf("powergrid:wire"));
        assertEquals(12, DuctWires.awgOf("powergrid:insulated_copper_wire"));
        assertEquals(12, DuctWires.awgOf("somemod:mystery_wire"));
        // Thicker gauge, bigger wire: 4 AWG is about 5.19 mm across
        assertEquals(5.189, DuctWires.awgDiameter(4), 1e-3);
        assertTrue(DuctWires.area(2) > DuctWires.area(4));
    }

    /** Wires of one gauge entered side by side into A at one point until one does not fit; how many fit. */
    private static int fill(DuctSegment s, int awg) {
        List<DuctWires.Occupant> present = new ArrayList<>();
        Vec3 click = s.from().add(s.direction().scale(2));
        for (int i = 0; i < 1000; i++) {
            Wire wire = wire(click.add(s.lateral().scale(1)).add(0, DuctWires.WIRE_DEPTH - s.size().depth() / 2, 0)
                    .add(s.direction().scale(PX / 2)), awg, 100 + i);
            DuctWires.Fit fit = enter(wire, s, click, present);
            if (fit != DuctWires.Fit.OK)
                return i;
            slide(wire, List.of(s), s.to(), present);
            present.addAll(DuctWires.occupancy(s, wire.path(), awg, wire.tie()));
        }
        return -1;
    }

    @Test
    void aRegularDuctHoldsOneFourGaugeWiresWorth() {
        DuctSegment large = new DuctSegment(new Vec3(2, 64 + DuctSize.LARGE.depth() / 2, 2),
                new Vec3(6, 64 + DuctSize.LARGE.depth() / 2, 2), Direction.UP, DuctSize.LARGE);
        // One 4 AWG fits a regular duct; a second does not
        assertEquals(1, fill(A, 4));
        // Thinner wires fit more: sixteen of 16 AWG, six of Power Grid's own 12 AWG
        assertEquals(16, fill(A, 16));
        assertEquals(6, fill(A, 12));
        // A 2 AWG wire is too thick for the regular duct alone, and fits the large one
        Wire thick = wire(START, 2, 1);
        assertEquals(DuctWires.Fit.TOO_THICK, enter(thick, A, new Vec3(4, 64.1, 2), List.of()));
        assertEquals(1, fill(large, 2));
        assertEquals(10, fill(large, 12));
    }

    @Test
    void aFullStretchRefusesASlideThroughItButNotAWireThatStopsShort() {
        Wire full = wire(new Vec3(5 + PX / 2, FLOOR, PX / 2), 4, 1);
        enter(full, A, new Vec3(5, 64.1, 2), List.of());
        slide(full, List.of(A), new Vec3(5.9, 64.1, 2), List.of());
        Wire other = wire(START, 16, 2);
        // In behind it is fine: nothing there yet
        assertEquals(DuctWires.Fit.OK, enter(other, A, new Vec3(3, 64.1, 2), occupants(full)));
        assertEquals(DuctWires.Fit.OK, slide(other, List.of(A), new Vec3(4.5, 64.1, 2), occupants(full)));
        // On past where the 4 AWG wire fills the duct is not
        assertEquals(DuctWires.Fit.FULL, slide(other, List.of(A), new Vec3(5.5, 64.1, 2), occupants(full)));
    }

    // Strokes on the lid

    @Test
    void strokesLieSideBySideAcrossTheLidEachAsWideAsItsGauge() {
        List<DuctWires.Occupant> present = List.of(
                new DuctWires.Occupant(A, 0, 4, -UNIT, 1, 12, 1),
                new DuctWires.Occupant(A, 0, 4, 0, 1, 8, 2),
                new DuctWires.Occupant(A, 0, 4, UNIT, -1, 16, 3));
        List<DuctWires.Slot> slots = DuctWires.layout(A, present);
        assertEquals(3, slots.size());
        double usable = W - 2 * DuctWires.STROKE_MARGIN;
        double total = 0, last = Double.NEGATIVE_INFINITY;
        for (DuctWires.Slot slot : slots) {
            // In the order the wires run in, none past the lid's margin
            assertTrue(slot.offset() > last);
            last = slot.offset();
            assertTrue(Math.abs(slot.offset()) + slot.width() / 2 <= usable / 2 + 1e-9);
            total += slot.width() / DuctWires.STROKE_FILL;
            // Each as wide as its diameter, at one scale for all
            assertEquals(slots.get(0).width() / slots.get(0).occupant().diameter(),
                    slot.width() / slot.occupant().diameter(), 1e-9);
        }
        // Centred on the lid, and filling what is usable of it, since together they are more than one 4 AWG wide
        double left = slots.get(0).offset() - slots.get(0).width() / DuctWires.STROKE_FILL / 2;
        double right = slots.get(2).offset() + slots.get(2).width() / DuctWires.STROKE_FILL / 2;
        assertEquals(-left, right, 1e-9);
        assertEquals(usable, total, 1e-9);

        // A 4 AWG wire alone fills the regular duct's lid; a 16 AWG alone takes its own share of it
        DuctWires.Slot alone = DuctWires.layout(A, List.of(new DuctWires.Occupant(A, 0, 4, 0, 1, 4, 1))).get(0);
        assertEquals(usable * DuctWires.STROKE_FILL, alone.width(), 1e-9);
        DuctWires.Slot thin = DuctWires.layout(A, List.of(new DuctWires.Occupant(A, 0, 4, 0, 1, 16, 1))).get(0);
        assertEquals(usable * DuctWires.STROKE_FILL * DuctWires.awgDiameter(16) / DuctWires.awgDiameter(4),
                thin.width(), 1e-9);

        // Sixteen of 16 AWG shrink alike to fit
        List<DuctWires.Occupant> many = new ArrayList<>();
        for (int i = 0; i < 16; i++)
            many.add(new DuctWires.Occupant(A, 0, 4, (i - 8) * UNIT / 4, 1, 16, i));
        double sum = 0;
        for (DuctWires.Slot slot : DuctWires.layout(A, many))
            sum += slot.width() / DuctWires.STROKE_FILL;
        assertEquals(usable, sum, 1e-9);
    }

    @Test
    void wiresRunningExactlyTogetherKeepOneOrderInEveryStretch() {
        // Same offset, travelling one way along A and on along B: the order follows each wire's own frame
        DuctWires.Occupant a1 = new DuctWires.Occupant(A, 0, 4, 0, 1, 12, 5);
        DuctWires.Occupant a2 = new DuctWires.Occupant(A, 0, 4, 0, 1, 12, 9);
        // B run the other way, so its lateral is the travel's mirror: the order mirrors with it
        DuctSegment back = B.reversed();
        DuctWires.Occupant b1 = new DuctWires.Occupant(back, 0, 4, 0, -1, 12, 5);
        DuctWires.Occupant b2 = new DuctWires.Occupant(back, 0, 4, 0, -1, 12, 9);
        List<DuctWires.Slot> onA = DuctWires.layout(A, List.of(a2, a1));
        List<DuctWires.Slot> onB = DuctWires.layout(back, List.of(b2, b1));
        assertEquals(5, onA.get(0).occupant().tie());
        assertEquals(9, onB.get(0).occupant().tie());
    }

    @Test
    void strokesAreLiftedOntoTheLidAndPlacedInTheirSlots() {
        Wire first = wire(START, 12, 1);
        enter(first, A, new Vec3(4, 64.1, 2), List.of());
        slide(first, List.of(A), new Vec3(5.5, 64.1, 2), List.of());
        Wire second = wire(new Vec3(3 + PX / 2, FLOOR, 4 + PX / 2), 12, 2);
        enter(second, A, new Vec3(3, 64.1, 2), occupants(first));
        slide(second, List.of(A), new Vec3(5.5, 64.1, 2), occupants(first));
        List<DuctWires.Occupant> all = occupants(first, second);
        List<DuctWires.Slot> layout = DuctWires.layout(A, all);
        for (Wire wire : List.of(first, second)) {
            DuctWires.Slot slot = DuctWires.slotIn(layout, wire.tie(), A, new Vec3(5, Y, 2));
            List<DuctWires.Dash> dashes = DuctWires.strokes(wire.path(),
                    DuctWires.hidden(wire.path(), List.of(DA)), slots(wire.tie(), all), 0.05);
            assertFalse(dashes.isEmpty());
            for (DuctWires.Dash dash : dashes) {
                assertEquals(LID, dash.from().y, 1e-9);
                assertEquals(LID, dash.to().y, 1e-9);
            }
            // The run along A lies on the wire's slot
            DuctWires.Dash run = dashes.get(dashes.size() - 1);
            assertEquals(2 - slot.offset(), run.to().z, 1e-9);
            assertEquals(slot.width(), run.width(), 1e-9);
        }
        // The second came in from the south, so its stroke is south of the first's
        DuctWires.Slot s1 = DuctWires.slotIn(layout, 1, A, new Vec3(5, Y, 2));
        DuctWires.Slot s2 = DuctWires.slotIn(layout, 2, A, new Vec3(5, Y, 2));
        assertTrue(s2.offset() < s1.offset());
        // The lid point above each stroke picks its wire
        assertEquals(2, DuctWires.slotAt(layout, A, new Vec3(5, LID, 2 - s2.offset())).occupant().tie());
        assertEquals(1, DuctWires.slotAt(layout, A, new Vec3(5, LID, 2 - s1.offset())).occupant().tie());
    }

    @Test
    void aStrokeRoundACornerMeetsItselfSquareWithNoGapAndNothingPast() {
        Wire wire = wire(START, 12, 1);
        enter(wire, A, new Vec3(4, 64.1, 2), List.of());
        slide(wire, List.of(A, B), new Vec3(6, 64.1, 5), List.of());
        List<DuctWires.Occupant> all = occupants(wire);
        List<DuctWires.Dash> lines = lines(wire, ROOM, all);
        // In through A's side, along A (on through B's corner square, one line), along B
        assertEquals(3, lines.size());
        DuctWires.Slot onA = DuctWires.slotIn(DuctWires.layout(A, all), 1, A, new Vec3(5, Y, 2));
        DuctWires.Slot onB = DuctWires.slotIn(DuctWires.layout(B, all), 1, B, new Vec3(6, Y, 4));
        double za = 2 - onA.offset(), xb = 6 + onB.offset();
        DuctWires.Dash in = lines.get(0), corner = lines.get(1), alongB = lines.get(2);
        // Going in: from A's wall to the slot, and half the slot's width on to fill the turn
        assertEquals(2 - W / 2, in.from().z, 1e-9);
        assertEquals(za + onA.width() / 2, in.to().z, 1e-9);
        assertEquals(4 + PX / 2 - onA.width() / 2, corner.from().x, 1e-9);
        assertEquals(za, corner.from().z, 1e-9);
        assertEquals(za, corner.to().z, 1e-9);
        // The turn: A's stroke runs on to B's, plus half of B's width; B's starts half of A's width back
        assertEquals(xb + onB.width() / 2, corner.to().x, 1e-9);
        assertEquals(xb, alongB.from().x, 1e-9);
        assertEquals(za - onA.width() / 2, alongB.from().z, 1e-9);
        // So the outer corner of the two strokes is exactly the corner of the square they share: nothing past it
        assertTrue(corner.to().x <= 6 + W / 2 + 1e-9);
        assertTrue(alongB.from().z >= 2 - W / 2 - 1e-9);
        // And every line lies flat on the lid
        for (DuctWires.Dash line : lines)
            assertEquals(LID, line.from().y, 1e-9);
    }

    @Test
    void twoWiresRoundACornerKeepInsideAndOutsideAndNeverCross() {
        Wire inner = wire(new Vec3(4 + PX / 2, FLOOR, 4 + PX / 2), 12, 1);
        enter(inner, A, new Vec3(4, 64.1, 2), List.of());
        Wire outer = wire(new Vec3(3 + PX / 2, FLOOR, PX / 2), 16, 2);
        enter(outer, A, new Vec3(3, 64.1, 2), occupants(inner));
        slide(inner, List.of(A, B), new Vec3(6, 64.1, 5), occupants(outer));
        slide(outer, List.of(A, B), new Vec3(6, 64.1, 5), occupants(inner));
        List<DuctWires.Occupant> all = occupants(inner, outer);
        List<DuctWires.Dash> li = lines(inner, ROOM, all), lo = lines(outer, ROOM, all);
        assertEquals(3, li.size());
        assertEquals(3, lo.size());
        // Along A the outer one is north (further from the inside of the turn), along B east
        assertTrue(lo.get(1).from().z < li.get(1).from().z);
        assertTrue(lo.get(2).to().x > li.get(2).to().x);
        // The inner one turns before the outer one and comes out of the turn inside it: no crossing
        assertTrue(li.get(1).to().x < lo.get(1).to().x);
        assertTrue(li.get(2).from().z > lo.get(2).from().z);
    }

    @Test
    void fromWallToFloorTheWireHugsBothFacesAndItsStrokeTurnsAtTheLidsEdge() {
        DuctSegment wall = new DuctSegment(new Vec3(4 + H, 66, 2), new Vec3(4 + H, Y, 2), Direction.EAST,
                DuctSize.SMALL);
        DuctSegment floor = new DuctSegment(new Vec3(4 + H, Y, 2), new Vec3(7, Y, 2), Direction.UP, DuctSize.SMALL);
        DuctPass pass = DuctNetwork.route(List.of(wall, floor), new Vec3(4 + H, 65.5, 2), new Vec3(6, Y, 2), UNIT * 2);
        List<Vec3> points = pass.wirePoints();
        assertEquals(4 + DuctWires.WIRE_DEPTH, points.get(0).x, 1e-9);
        assertVec(new Vec3(4 + DuctWires.WIRE_DEPTH, FLOOR, points.get(0).z), points.get(1));
        assertEquals(FLOOR, points.get(2).y, 1e-9);
        // One sideways direction serves both legs, so the wire keeps its place across the duct
        assertEquals(points.get(0).z, points.get(2).z, 1e-9);

        double[] ext = DuctNetwork.jointExtensions(wall, floor);
        DuctWires.Duct dw = new DuctWires.Duct(wall, wall.box(0, ext[0]));
        DuctWires.Duct df = new DuctWires.Duct(floor, floor.box(ext[1], 0));
        List<Vec3> path = new ArrayList<>(points);
        List<DuctWires.Occupant> all = new ArrayList<>();
        all.addAll(DuctWires.occupancy(wall, path, 12, 1));
        all.addAll(DuctWires.occupancy(floor, path, 12, 1));
        List<DuctWires.Dash> lines = DuctWires.lines(path, DuctWires.hidden(path, List.of(dw, df)),
                (s, at) -> DuctWires.slotIn(DuctWires.layout(s, all), 1, s, at), 0.05);
        DuctWires.Dash down = lines.get(0), out = lines.get(lines.size() - 1);
        double lidX = 4 + DuctSize.SMALL.depth() + DuctWires.STROKE_CLEARANCE;
        // Down the wall's lid to the edge where it meets the floor duct's lid, and out along that from the same edge
        assertEquals(lidX, down.from().x, 1e-9);
        assertVec(new Vec3(lidX, LID, down.to().z), down.to());
        assertEquals(2, lines.size());
        assertVec(down.to(), out.from());
        assertEquals(down.to().z, out.to().z, 1e-9);
    }

    // The ducts' own shape at a turn

    @Test
    void aTurnInOneFaceTilesItsCornerOnceWithNoOverlap() {
        double[] corner = DuctNetwork.jointExtensions(A, B);
        assertEquals(-W / 2, corner[0], 1e-9);
        assertEquals(W / 2, corner[1], 1e-9);
        AABB a = A.box(0, corner[0]);
        AABB b = B.box(corner[1], 0);
        assertEquals(0, volume(a, b), 1e-12);
        assertEquals(a.maxX, b.minX, 1e-9);
        // The corner square is B's, and its outer edges are flush with A's sides
        assertTrue(b.contains(new Vec3(6, Y, 2)));
        assertEquals(a.minZ, b.minZ, 1e-9);
        DuctSegment onward = new DuctSegment(new Vec3(6, Y, 2), new Vec3(8, Y, 2), Direction.UP, DuctSize.SMALL);
        assertEquals(0, DuctNetwork.jointExtensions(A, onward)[0], 0);
    }

    @Test
    void aTurnFromWallToFloorTilesTheInsideCornerWithNoGapAndNoOverlap() {
        DuctSegment wall = new DuctSegment(new Vec3(4 + H, 66, 2), new Vec3(4 + H, Y, 2), Direction.EAST,
                DuctSize.SMALL);
        DuctSegment floor = new DuctSegment(new Vec3(4 + H, Y, 2), new Vec3(7, Y, 2), Direction.UP, DuctSize.SMALL);
        for (boolean wallFirst : new boolean[] {true, false}) {
            double[] ext = wallFirst ? DuctNetwork.jointExtensions(wall, floor) : DuctNetwork.jointExtensions(floor, wall);
            double wallExt = wallFirst ? ext[0] : ext[1], floorExt = wallFirst ? ext[1] : ext[0];
            AABB w = wall.box(0, wallExt), f = floor.box(floorExt, 0);
            assertEquals(0, volume(w, f), 1e-12, "no doubled faces");
            // The corner block of the L, wall face to floor face, lid to lid, is filled by one of them
            double d = DuctSize.SMALL.depth();
            for (Vec3 p : new Vec3[] {new Vec3(4.01, 64.01, 2), new Vec3(4 + d - 0.01, 64 + d - 0.01, 2),
                    new Vec3(4.01, 64 + d - 0.01, 2), new Vec3(4 + d - 0.01, 64.01, 2)})
                assertTrue(w.contains(p) || f.contains(p), "no gap at " + p);
            // The outside of the L is flat: nothing sticks out past either lid
            assertEquals(4 + d, w.maxX, 1e-9);
            assertEquals(64 + d, f.maxY, 1e-9);
            assertEquals(64, Math.min(w.minY, f.minY), 1e-9);
            assertEquals(4, Math.min(w.minX, f.minX), 1e-9);
        }
    }

    // The network

    @Test
    void ductsThatAreNotJoinedOrDifferInSizeGiveNoWay() {
        List<DuctSegment> room = List.of(A, B, C);
        assertNull(DuctNetwork.route(room, 0, new Vec3(3, Y, 2), 2, new Vec3(11, Y, 2), 0));
        DuctSegment large = new DuctSegment(new Vec3(6, Y, 6), new Vec3(8, Y, 6), Direction.UP, DuctSize.LARGE);
        assertNull(B.jointWith(large));
        assertNull(DuctNetwork.chain(List.of(A, B, large), 0, 2));
    }

    @Test
    void thereIsNoLimitOnHowManyDuctsAWireSlidesThrough() {
        List<DuctSegment> pieces = DuctPlacement.plan(new Vec3(0, Y, 2), new Vec3(3200, Y, 2), Direction.UP,
                DuctSize.SMALL);
        assertEquals(200, pieces.size());
        List<Integer> chain = DuctNetwork.chain(pieces, 0, 199);
        assertNotNull(chain);
        assertEquals(200, chain.size());
        DuctPass pass = DuctNetwork.route(pieces, 0, new Vec3(1, Y, 2), 199, new Vec3(3199, Y, 2), 0);
        assertEquals(3198, pass.length(), 1e-6);
    }

    @Test
    void aWireMerelyCrossingUnderADuctIsNotInItAndTakesNoRoom() {
        Wire routed = wire(START, 12, 1);
        enter(routed, A, new Vec3(4, 64.1, 2), List.of());
        List<Vec3> crossing = List.of(new Vec3(3 + PX / 2, FLOOR, PX / 2), new Vec3(3 + PX / 2, FLOOR, 3.5));
        assertEquals(List.of(DA), DuctWires.holding(routed.path(), List.of(DA)));
        assertTrue(DuctWires.holding(crossing, List.of(DA)).isEmpty());
        assertTrue(DuctWires.occupancy(A, crossing, 4, 9).isEmpty());
        // The routed one, though only turned in, already takes its share at that point
        assertEquals(1, DuctWires.occupancy(A, routed.path(), 12, 1).size());
    }

    @Test
    void aWireIsPricedAndResistedByItsWholeLengthInDuctsAndOut() {
        double itemsPerMeter = 0.5, resistancePerItem = 0.00062832958;
        Wire wire = wire(new Vec3(1 + PX / 2, FLOOR, PX / 2), 12, 1);
        lay(wire, List.of(START), null);
        double total = WireBends.length(wire.path());
        int items = DuctWires.itemsToAdd(true, 0, total, itemsPerMeter, 0);

        int mark = wire.path().size();
        enter(wire, A, new Vec3(4, 64.1, 2), List.of());
        double added = WireBends.length(wire.path().subList(mark - 1, wire.path().size()));
        items += DuctWires.itemsToAdd(false, total, added, itemsPerMeter, items);
        total += added;

        mark = wire.path().size();
        slide(wire, List.of(A, B), new Vec3(6, 64.1, 5), List.of());
        added = WireBends.length(wire.path().subList(mark - 1, wire.path().size()));
        items += DuctWires.itemsToAdd(false, total, added, itemsPerMeter, items);
        total += added;

        mark = wire.path().size();
        Vec3 end = wire.end();
        exit(wire, B, new Vec3(9, 64, end.z));
        lay(wire, List.of(new Vec3(9 + PX / 2, FLOOR, end.z)), null);
        added = WireBends.length(wire.path().subList(mark - 1, wire.path().size()));
        items += DuctWires.itemsToAdd(false, total, added, itemsPerMeter, items);
        total += added;

        assertEquals(WireBends.length(wire.path()), total, 1e-9);
        int oneGo = DuctWires.itemsToAdd(true, 0, total, itemsPerMeter, 0);
        assertEquals(oneGo, items);
        assertEquals(resistancePerItem * oneGo, resistancePerItem * items, 1e-12);
        assertTrue(total > 10, "the slide through both ducts counts");
    }

    // Finding things by section

    @Test
    void theSectionIndexFindsALongThingFarFromWhereItStarts() {
        SectionIndex<String> index = new SectionIndex<>();
        String wire = "wire";
        index.put(wire, List.of(new AABB(0, 64, 0, 100, 64.2, 0.2)));
        int version = index.version();
        assertEquals(List.of(wire), index.query(new AABB(90, 64, 0, 90.5, 64.5, 0.5)));
        assertTrue(index.query(new AABB(0, 100, 0, 1, 101, 1)).isEmpty());
        index.put(wire, List.of(new AABB(0, 64, 0, 10, 64.2, 0.2)));
        assertTrue(index.query(new AABB(90, 64, 0, 90.5, 64.5, 0.5)).isEmpty());
        index.remove(wire);
        assertTrue(index.query(new AABB(0, 64, 0, 1, 65, 1)).isEmpty());
        assertTrue(index.version() > version);
        assertEquals(0, index.size());
    }

    // Old saves

    @Test
    void aHangingWireSavedWhileDuctsRoutedItLoadsAsAPlainHangingWire() {
        CompoundTag tag = new CompoundTag();
        ListTag points = new ListTag();
        for (double v : new double[] {1, 65, 1, 2, 64.1, 1})
            points.add(DoubleTag.valueOf(v));
        tag.put(LegacyDuctKeys.PATH, points);
        tag.putLong(LegacyDuctKeys.LEGS, 0b10);
        tag.putString("Other", "kept");
        assertTrue(LegacyDuctKeys.strip(tag));
        assertFalse(tag.contains(LegacyDuctKeys.PATH));
        assertFalse(tag.contains(LegacyDuctKeys.LEGS));
        assertEquals("kept", tag.getString("Other"));
        assertTrue(WireBends.read(tag).isEmpty());
        assertFalse(LegacyDuctKeys.strip(tag));
    }
}
