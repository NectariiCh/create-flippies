package com.nectariich.create_flippies.foundation.wire.duct;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import com.nectariich.create_flippies.foundation.wire.bend.BendGeometry;
import com.nectariich.create_flippies.foundation.wire.bend.WireBends;

import io.netty.buffer.Unpooled;

import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import org.junit.jupiter.api.Test;

/**
 * A wire through ducts on the floor of a room at y = 64: duct A runs east from
 * (0, 0) to (4, 0), duct B turns north from there to (4, 4), and duct C lies apart
 * at (10, 0) to (12, 0).
 */
class DuctRoutingTest {

    private static final double H = DuctSize.SMALL.depth() / 2;
    private static final double Y = 64 + H;
    private static final double T = 0.0625;
    private static final double LIFT = H + T / 2 + DuctPass.STROKE_CLEARANCE;

    private static final DuctSegment A = new DuctSegment(new Vec3(0, Y, 0), new Vec3(4, Y, 0), Direction.UP,
            DuctSize.SMALL);
    private static final DuctSegment B = new DuctSegment(new Vec3(4, Y, 0), new Vec3(4, Y, 4), Direction.UP,
            DuctSize.SMALL);
    private static final DuctSegment C = new DuctSegment(new Vec3(10, Y, 0), new Vec3(12, Y, 0), Direction.UP,
            DuctSize.SMALL);
    private static final List<DuctSegment> ROOM = List.of(A, B, C);

    private static final Vec3 TERMINAL_1 = new Vec3(1, 66, 3);
    private static final Vec3 TERMINAL_2 = new Vec3(7, 66, 3);

    private static void assertVec(Vec3 expected, Vec3 actual) {
        assertEquals(expected.x, actual.x, 1e-6, "x of " + actual);
        assertEquals(expected.y, actual.y, 1e-6, "y of " + actual);
        assertEquals(expected.z, actual.z, 1e-6, "z of " + actual);
    }

    private static DuctPass aroundTheCorner(int lane) {
        return DuctNetwork.route(ROOM, 0, new Vec3(1, Y + 0.05, 0.07), 1, new Vec3(4.1, Y, 3), lane);
    }

    // Following joined ducts

    @Test
    void aWireFollowsJoinedDuctsAroundACorner() {
        DuctPass pass = aroundTheCorner(0);
        assertNotNull(pass);
        // Both clicks are moved onto the centre line, and the joint is the turn
        assertEquals(List.of(new Vec3(1, Y, 0), new Vec3(4, Y, 0), new Vec3(4, Y, 3)), pass.centers());
        assertEquals(List.of(Direction.UP, Direction.UP), pass.faces());
        assertEquals(6, pass.length(), 1e-9);
    }

    @Test
    void slidingAlongOneDuctIsAStraightPass() {
        DuctPass pass = DuctNetwork.route(ROOM, 0, new Vec3(0.5, Y, 0), 0, new Vec3(3.25, Y, 0.1), 0);
        assertEquals(List.of(new Vec3(0.5, Y, 0), new Vec3(3.25, Y, 0)), pass.centers());
        // Clicks past the end stop at the end
        DuctPass clamped = DuctNetwork.route(ROOM, 0, new Vec3(-3, Y, 0), 0, new Vec3(2, Y, 0), 0);
        assertVec(new Vec3(0, Y, 0), clamped.centers().get(0));
    }

    @Test
    void ductsThatAreNotJoinedOrDifferInSizeGiveNoWay() {
        assertNull(DuctNetwork.route(ROOM, 0, new Vec3(1, Y, 0), 2, new Vec3(11, Y, 0), 0));
        DuctSegment large = new DuctSegment(new Vec3(4, Y, 4), new Vec3(8, Y, 4), Direction.UP, DuctSize.LARGE);
        List<DuctSegment> mixed = List.of(A, B, large);
        assertNull(DuctNetwork.route(mixed, 0, new Vec3(1, Y, 0), 2, new Vec3(6, Y, 4), 0));
        assertNull(B.jointWith(large));
    }

    @Test
    void aTurnInOneFaceTilesItsCornerOnce() {
        double half = DuctSize.SMALL.width() / 2;
        double[] corner = DuctNetwork.jointExtensions(A, B);
        assertEquals(-half, corner[0], 1e-9);
        assertEquals(half, corner[1], 1e-9);
        // The cut-back box of A and the extended box of B meet without overlapping
        AABB a = A.box(0, corner[0]);
        AABB b = B.box(corner[1], 0);
        assertEquals(a.maxX, b.minX, 1e-9);
        // In line, or from wall to floor, the boxes simply meet end to end
        DuctSegment onward = new DuctSegment(new Vec3(4, Y, 0), new Vec3(8, Y, 0), Direction.UP, DuctSize.SMALL);
        assertEquals(0, DuctNetwork.jointExtensions(A, onward)[0], 0);
        DuctSegment wall = new DuctSegment(new Vec3(H, 66, 0), new Vec3(H, Y, 0), Direction.EAST, DuctSize.SMALL);
        assertEquals(0, DuctNetwork.jointExtensions(wall, A)[1], 0);
    }

    // Lanes, strokes and the sideways way out

    @Test
    void strokesLieOnTheLidInTheirLaneAndStayParallelAroundATurn() {
        double offset = DuctSize.SMALL.laneOffset(0);
        List<Vec3> lane = aroundTheCorner(0).lanePoints(T);
        // Travelling east on the floor, the lane's sideways direction is north (-Z)
        assertVec(new Vec3(1, Y + LIFT, -offset), lane.get(0));
        assertVec(new Vec3(4 + offset, Y + LIFT, -offset), lane.get(1));
        assertVec(new Vec3(4 + offset, Y + LIFT, 3), lane.get(2));
        // Strokes clear the lid by the wire's radius
        double lid = 64 + DuctSize.SMALL.depth();
        assertTrue(lane.get(0).y - T / 2 > lid);
    }

    @Test
    void fromWallToFloorTheStrokeLiftsOffBothLids() {
        DuctSegment wall = new DuctSegment(new Vec3(H, 66, 0), new Vec3(H, Y, 0), Direction.EAST, DuctSize.SMALL);
        DuctSegment floor = new DuctSegment(new Vec3(H, Y, 0), new Vec3(3, Y, 0), Direction.UP, DuctSize.SMALL);
        DuctPass pass = DuctNetwork.route(List.of(wall, floor), 0, new Vec3(H, 65.5, 0), 1, new Vec3(2, Y, 0), 1);
        List<Vec3> lane = pass.lanePoints(T);
        double side = DuctSize.SMALL.laneOffset(1);
        // Down the wall at the wall lid's depth, then along the floor at the floor lid's height
        assertEquals(H + LIFT, lane.get(0).x, 1e-9);
        assertVec(new Vec3(H + LIFT, Y + LIFT, lane.get(0).z), lane.get(1));
        assertEquals(Y + LIFT, lane.get(2).y, 1e-9);
        // One sideways direction serves both legs, so the lane keeps its place across the duct
        assertEquals(lane.get(0).z, lane.get(2).z, 1e-9);
        assertEquals(Math.abs(side), Math.abs(lane.get(0).z), 1e-9);
    }

    @Test
    void theWireLeavesSidewaysTowardsWhereItGoesNext() {
        DuctPass pass = aroundTheCorner(0);
        List<Vec3> lane = pass.lanePoints(T);
        double half = DuctSize.SMALL.width() / 2;
        // Coming from the south (+Z), it enters across the south edge of duct A
        Vec3 in = pass.edge(true, new Vec3(1, 66, 5), T);
        assertVec(new Vec3(1, Y + LIFT, half), in);
        // From the north it would enter across the north edge
        assertVec(new Vec3(1, Y + LIFT, -half), pass.edge(true, new Vec3(1, 66, -5), T));
        // Leaving duct B towards the east, across its east edge
        Vec3 out = pass.edge(false, new Vec3(9, 66, 3), T);
        assertVec(new Vec3(4 + half, Y + LIFT, 3), out);
        assertEquals(lane.get(2).z, out.z, 1e-9);
    }

    @Test
    void wiresSharingADuctTakeDifferentLanesUntilItIsFull() {
        List<DuctSegment> along = DuctNetwork.segmentsOf(aroundTheCorner(0), ROOM);
        assertEquals(List.of(A, B), along);
        assertEquals(0, DuctNetwork.freeLane(DuctSize.SMALL, along, List.of()));

        List<Vec3> first = DuctRoute.ductedLegs(build(aroundTheCorner(0)).path(TERMINAL_1, TERMINAL_2),
                build(aroundTheCorner(0)).ductLegs());
        assertEquals(1, DuctNetwork.freeLane(DuctSize.SMALL, along, first));
        List<Vec3> both = new ArrayList<>(first);
        both.addAll(DuctRoute.ductedLegs(build(aroundTheCorner(1)).path(TERMINAL_1, TERMINAL_2),
                build(aroundTheCorner(1)).ductLegs()));
        assertEquals(-1, DuctNetwork.freeLane(DuctSize.SMALL, along, both));
        // Side by side: the two lanes lie a lane's width apart
        double gap = aroundTheCorner(1).lanePoints(T).get(0).z - aroundTheCorner(0).lanePoints(T).get(0).z;
        assertEquals(DuctSize.SMALL.width() / DuctSize.SMALL.lanes(), Math.abs(gap), 1e-9);
        // A wire in duct C takes nothing from A and B
        DuctPass inC = DuctNetwork.route(ROOM, 2, new Vec3(10, Y, 0), 2, new Vec3(12, Y, 0), 0);
        assertEquals(0, DuctNetwork.freeLane(DuctSize.SMALL, along, DuctRoute.ductedLegs(
                build(inC).path(TERMINAL_1, TERMINAL_2), build(inC).ductLegs())));
    }

    // The one wire it makes

    private static DuctRoute.Built build(DuctPass pass) {
        return DuctRoute.build(TERMINAL_1, List.of(pass), TERMINAL_2, T);
    }

    @Test
    void theRouteIsOneWireWithItsDuctedLegsMarked() {
        DuctRoute.Built built = build(aroundTheCorner(0));
        List<Vec3> path = built.path(TERMINAL_1, TERMINAL_2);
        // Terminal, side edge in, three lane points, side edge out, terminal
        assertEquals(7, path.size());
        assertEquals(0b011110, built.ductLegs());
        assertFalse(DuctRoute.isDucted(built.ductLegs(), 0));
        assertFalse(DuctRoute.isDucted(built.ductLegs(), 5));
        assertTrue(DuctRoute.isDucted(built.ductLegs(), 2));
        // No passes is a plain straight wire
        DuctRoute.Built plain = DuctRoute.build(TERMINAL_1, List.of(), TERMINAL_2, T);
        assertTrue(plain.points().isEmpty());
        assertEquals(0, plain.ductLegs());
    }

    @Test
    void aWireMayPassThroughSeveralDucts() {
        DuctPass inC = DuctNetwork.route(ROOM, 2, new Vec3(10, Y, 0), 2, new Vec3(12, Y, 0), 0);
        DuctRoute.Built built = DuctRoute.build(TERMINAL_1, List.of(aroundTheCorner(0), inC), TERMINAL_2, T);
        List<Vec3> path = built.path(TERMINAL_1, TERMINAL_2);
        assertEquals(2 + 5 + 4, path.size());
        // The leg between the two ducts hangs; every leg inside one does not
        int between = 5;
        assertFalse(DuctRoute.isDucted(built.ductLegs(), between));
        assertTrue(DuctRoute.isDucted(built.ductLegs(), between + 1));
        assertEquals(DuctRoute.Verdict.OK, DuctRoute.check(built, TERMINAL_1, TERMINAL_2, 2, 16));
    }

    @Test
    void lengthItemsAndResistanceFollowTheWholePath() {
        DuctRoute.Built built = build(aroundTheCorner(0));
        double length = WireBends.length(built.path(TERMINAL_1, TERMINAL_2));
        // Longer than the straight 6 between the terminals: down, along 6 in the duct, and back up
        assertTrue(length > 6 + 2 * 1.8);
        assertEquals(Math.round(length * 2), DuctRoute.itemCount(length, 2));
        assertEquals(1, DuctRoute.itemCount(0.1, 1));
    }

    @Test
    void checkRefusesLongHangingLegsLongRoutesAndTooManyPasses() {
        DuctRoute.Built built = build(aroundTheCorner(0));
        // Each hanging leg is about 3.6 long; the ducted 6 do not count against the span
        assertEquals(DuctRoute.Verdict.OK, DuctRoute.check(built, TERMINAL_1, TERMINAL_2, 1, 4));
        assertEquals(DuctRoute.Verdict.LEG_TOO_LONG, DuctRoute.check(built, TERMINAL_1, TERMINAL_2, 1, 2));
        assertEquals(DuctRoute.Verdict.TOO_MANY,
                DuctRoute.check(built, TERMINAL_1, TERMINAL_2, DuctRoute.MAX_PASSES + 1, 4));
        Vec3 far = new Vec3(400, 66, 3);
        DuctRoute.Built away = DuctRoute.build(TERMINAL_1, List.of(aroundTheCorner(0)), far, T);
        assertEquals(DuctRoute.Verdict.TOO_LONG, DuctRoute.check(away, TERMINAL_1, far, 1, 1000));
    }

    @Test
    void swappingTheEndsReversesTheMarks() {
        assertEquals(0b111100, DuctRoute.reversed(0b011110, 7));
        assertEquals(0b011110, DuctRoute.reversed(DuctRoute.reversed(0b011110, 7), 7));
        assertEquals(0, DuctRoute.reversed(0, 7));
    }

    // Cutting and breaking

    @Test
    void theStrokesInADuctAreThatWiresToHitAndAreShutInByTheDuct() {
        DuctRoute.Built built = build(aroundTheCorner(0));
        List<Vec3> path = built.path(TERMINAL_1, TERMINAL_2);
        long ducted = built.ductLegs();
        // Looking straight down at the strokes in the middle of duct A
        Vec3 mid = path.get(2).add(path.get(3)).scale(0.5);
        Vec3 eye = mid.add(0, 2, 0);
        BendGeometry.Hit hit = BendGeometry.pickWire(eye, new Vec3(0, -1, 0), 5, path, ducted, 1.01, 1.2, T);
        assertNotNull(hit);
        assertEquals(2, hit.leg());
        assertTrue(BendGeometry.isDucted(ducted, hit.leg()));
        // Standing in the duct does not touch the wire; the same leg hanging bare would
        AABB feet = new AABB(mid, mid).inflate(0.3, 0.3, 0.3);
        assertFalse(BendGeometry.touches(path, ducted, 1.01, 1.2, T, feet));
        assertTrue(BendGeometry.touches(List.of(path.get(2), path.get(3)), 0, 1.01, 1.2, T,
                new AABB(path.get(2), path.get(3)).inflate(0.1, 1, 0.1)));
    }

    @Test
    void breakingADuctFindsTheWiresInItAndOnlyThose() {
        DuctRoute.Built built = build(aroundTheCorner(0));
        List<Vec3> legs = DuctRoute.ductedLegs(built.path(TERMINAL_1, TERMINAL_2), built.ductLegs());
        assertTrue(anyLegIn(A, legs));
        assertTrue(anyLegIn(B, legs));
        assertFalse(anyLegIn(C, legs));
    }

    private static boolean anyLegIn(DuctSegment segment, List<Vec3> legs) {
        for (int i = 0; i + 1 < legs.size(); i += 2)
            if (segment.containsLid(legs.get(i).add(legs.get(i + 1)).scale(0.5)))
                return true;
        return false;
    }

    // Saving and sending

    @Test
    void aRoutedWireSurvivesSaveAndSend() {
        DuctRoute.Built built = build(aroundTheCorner(1));
        CompoundTag tag = new CompoundTag();
        DuctRoute.write(tag, built.points(), built.ductLegs());
        assertEquals(built, DuctRoute.read(tag));

        FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());
        DuctRoute.write(buffer, built.points(), built.ductLegs());
        assertEquals(built, DuctRoute.read(buffer));

        // A wire through no duct writes nothing, and reads back as not routed
        CompoundTag plain = new CompoundTag();
        DuctRoute.write(plain, List.of(new Vec3(1, 2, 3)), 0);
        assertTrue(plain.isEmpty());
        assertNull(DuctRoute.read(plain));
    }

    @Test
    void damagedOrHostileRoutesAreRejected() {
        DuctRoute.Built built = build(aroundTheCorner(0));
        CompoundTag tag = new CompoundTag();
        DuctRoute.write(tag, built.points(), built.ductLegs());
        // A mark on a leg the path does not have
        tag.putLong(DuctRoute.NBT_LEGS, 1L << 40);
        assertNull(DuctRoute.read(tag));

        FriendlyByteBuf hostile = new FriendlyByteBuf(Unpooled.buffer());
        hostile.writeVarInt(DuctRoute.MAX_POINTS + 1);
        assertThrows(IllegalArgumentException.class, () -> DuctRoute.read(hostile));
    }

    @Test
    void aRouteBeingPlacedSurvivesTheStack() {
        CompoundTag connection = new CompoundTag();
        connection.putString("Type", "block");
        PendingRoute route = new PendingRoute(connection, List.of(aroundTheCorner(1)),
                new PendingRoute.Open(new UUID(1, 2), new Vec3(10.5, Y, 0)));
        PendingRoute back = PendingRoute.read(route.write());
        assertEquals(route, back);
        assertFalse(back.isEmpty());
        assertTrue(new PendingRoute(connection, List.of(), null).isEmpty());

        // A pass whose lane does not exist in its duct is damage, and spoils the route
        CompoundTag pass = aroundTheCorner(0).write();
        pass.putInt("L", 7);
        assertNull(DuctPass.read(pass));
        assertEquals(aroundTheCorner(0), DuctPass.read(aroundTheCorner(0).write()));
    }
}
