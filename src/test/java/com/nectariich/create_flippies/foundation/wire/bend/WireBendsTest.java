package com.nectariich.create_flippies.foundation.wire.bend;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;

import io.netty.buffer.Unpooled;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.world.phys.Vec3;

import org.junit.jupiter.api.Test;

class WireBendsTest {

    private static final Vec3 A = new Vec3(0, 10, 0);
    private static final Vec3 B = new Vec3(8, 10, 0);
    private static final Vec3 EYE = new Vec3(4, 11, 2);

    @Test
    void pathRunsFromStartThroughPointsToEnd() {
        Vec3 p = new Vec3(2, 9, 1), q = new Vec3(6, 9, -1);
        assertEquals(List.of(A, p, q, B), WireBends.path(A, List.of(p, q), B));
        assertEquals(List.of(A, B), WireBends.path(A, List.of(), B));
    }

    @Test
    void insertingOnALegPutsThePointBetweenThatLegsEnds() {
        Vec3 p = new Vec3(2, 9, 0), q = new Vec3(6, 9, 0);
        List<Vec3> points = List.of(p, q);
        Vec3 mid = new Vec3(4, 8, 0);
        // Leg 1 runs from p to q, so a point inserted on it lands between them
        List<Vec3> after = WireBends.apply(WireBends.Op.INSERT, points, 1, mid);
        assertEquals(List.of(p, mid, q), after);
        // Leg 0 is the first end to p; the last leg (2) is q to the second end
        assertEquals(List.of(mid, p, q), WireBends.apply(WireBends.Op.INSERT, points, 0, mid));
        assertEquals(List.of(p, q, mid), WireBends.apply(WireBends.Op.INSERT, points, 2, mid));
        // The input is left alone
        assertEquals(List.of(p, q), points);
    }

    @Test
    void moveAndRemove() {
        Vec3 p = new Vec3(2, 9, 0), q = new Vec3(6, 9, 0), r = new Vec3(5, 5, 5);
        assertEquals(List.of(p, r), WireBends.apply(WireBends.Op.MOVE, List.of(p, q), 1, r));
        assertEquals(List.of(q), WireBends.apply(WireBends.Op.REMOVE, List.of(p, q), 0, null));
    }

    @Test
    void lengthAndResistanceFollowTheBentPath() {
        assertEquals(8, WireBends.length(List.of(A, B)), 1e-9);
        assertEquals(1, WireBends.resistanceScale(A, List.of(), B), 0);
        // A point 3 up from the middle makes two 5-block legs out of an 8-block span
        Vec3 apex = new Vec3(4, 13, 0);
        assertEquals(10, WireBends.length(WireBends.path(A, List.of(apex), B)), 1e-9);
        assertEquals(10 / 8.0, WireBends.resistanceScale(A, List.of(apex), B), 1e-9);
        // A point on the straight line costs nothing, and the scale never drops below 1
        assertEquals(1, WireBends.resistanceScale(A, List.of(new Vec3(4, 10, 0)), B), 1e-9);
    }

    @Test
    void nbtRoundTrip() {
        List<Vec3> points = List.of(new Vec3(1.25, -3.5, 7.125), new Vec3(-100.0625, 64, 0.001));
        CompoundTag tag = new CompoundTag();
        WireBends.write(tag, points);
        assertEquals(points, WireBends.read(tag));
        // An unbent wire writes nothing and reads back as unbent
        CompoundTag empty = new CompoundTag();
        WireBends.write(empty, List.of());
        assertTrue(empty.isEmpty());
        assertEquals(List.of(), WireBends.read(empty));
    }

    @Test
    void nbtReadIsCappedAndSkipsBrokenEntries() {
        List<Vec3> many = new ArrayList<>();
        for (int i = 0; i < WireBends.MAX_POINTS + 5; i++)
            many.add(new Vec3(i, i, i));
        CompoundTag tag = new CompoundTag();
        WireBends.write(tag, many);
        assertEquals(WireBends.MAX_POINTS, WireBends.read(tag).size());

        CompoundTag nan = new CompoundTag();
        WireBends.write(nan, List.of(new Vec3(Double.NaN, 0, 0), new Vec3(1, 2, 3)));
        assertEquals(List.of(new Vec3(1, 2, 3)), WireBends.read(nan));
    }

    @Test
    void bufferRoundTrip() {
        List<Vec3> points = List.of(new Vec3(1, 2, 3), new Vec3(-4.5, 6.25, 1e-3));
        FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());
        WireBends.write(buffer, points);
        assertEquals(points, WireBends.read(buffer));

        FriendlyByteBuf hostile = new FriendlyByteBuf(Unpooled.buffer());
        hostile.writeVarInt(WireBends.MAX_POINTS + 1);
        assertThrows(IllegalArgumentException.class, () -> WireBends.read(hostile));
    }

    @Test
    void offsetFollowsAStructureMoveTheWayPowerGridShiftsEndpoints() {
        Vec3 p = new Vec3(1, 2, 3);
        CompoundTag saved = NbtUtils.writeBlockPos(new net.minecraft.core.BlockPos(10, 64, 10));
        net.minecraft.core.BlockPos last = NbtUtils.readBlockPos(saved);
        net.minecraft.core.BlockPos now = new net.minecraft.core.BlockPos(15, 60, 10);
        Vec3 by = Vec3.atLowerCornerOf(now.subtract(last));
        assertEquals(List.of(new Vec3(6, -2, 3)), WireBends.offset(List.of(p), by));
    }

    @Test
    void reversedForFlippedEnds() {
        Vec3 p = new Vec3(1, 0, 0), q = new Vec3(2, 0, 0);
        assertEquals(List.of(q, p), WireBends.reversed(List.of(p, q)));
    }

    // Server validation

    private static WireBends.Verdict check(WireBends.Op op, List<Vec3> points, int index, Vec3 at) {
        return WireBends.check(op, points, index, at, A, B, 24, EYE, 4.5);
    }

    @Test
    void acceptsAnOrdinaryEdit() {
        Vec3 at = new Vec3(4, 9.5, 0);
        assertEquals(WireBends.Verdict.OK, check(WireBends.Op.INSERT, List.of(), 0, at));
        assertEquals(WireBends.Verdict.OK, check(WireBends.Op.MOVE, List.of(at), 0, at.add(0, -1, 0)));
        assertEquals(WireBends.Verdict.OK, check(WireBends.Op.REMOVE, List.of(at), 0, null));
    }

    @Test
    void refusesBadIndices() {
        Vec3 at = new Vec3(4, 9.5, 0);
        assertEquals(WireBends.Verdict.BAD_INDEX, check(WireBends.Op.INSERT, List.of(), 1, at));
        assertEquals(WireBends.Verdict.BAD_INDEX, check(WireBends.Op.INSERT, List.of(), -1, at));
        assertEquals(WireBends.Verdict.BAD_INDEX, check(WireBends.Op.MOVE, List.of(), 0, at));
        assertEquals(WireBends.Verdict.BAD_INDEX, check(WireBends.Op.REMOVE, List.of(at), 1, null));
    }

    @Test
    void capsThePointCount() {
        List<Vec3> full = new ArrayList<>();
        for (int i = 0; i < WireBends.MAX_POINTS; i++)
            full.add(new Vec3(i * 0.5, 9.5, 0));
        assertEquals(WireBends.Verdict.TOO_MANY, check(WireBends.Op.INSERT, full, 0, new Vec3(4, 9.5, 0)));
        // A full wire can still have its points moved
        assertEquals(WireBends.Verdict.OK, check(WireBends.Op.MOVE, full, 3, new Vec3(1.5, 9, 0)));
    }

    @Test
    void refusesWhatIsOutOfReachOrNotANumber() {
        assertEquals(WireBends.Verdict.OUT_OF_REACH,
                check(WireBends.Op.INSERT, List.of(), 0, EYE.add(0, 0, 4.5 + WireBends.REACH_MARGIN + 0.01)));
        assertEquals(WireBends.Verdict.OK,
                check(WireBends.Op.INSERT, List.of(), 0, EYE.add(0, 0, 4.5 + WireBends.REACH_MARGIN - 0.01)));
        assertEquals(WireBends.Verdict.NOT_FINITE,
                check(WireBends.Op.MOVE, List.of(EYE), 0, new Vec3(Double.NaN, 0, 0)));
        assertEquals(WireBends.Verdict.NOT_FINITE, check(WireBends.Op.INSERT, List.of(), 0, null));
    }

    @Test
    void refusesAPathTooLongForTheWireType() {
        // Span 8, type maximum 4: the path may be up to max(8, 4) * 1.5 = 12
        Vec3 ok = new Vec3(4, 10 + Math.sqrt(36 - 16) - 0.01, 0);
        Vec3 tooFar = new Vec3(4, 10 + Math.sqrt(36 - 16) + 0.01, 0);
        Vec3 eye = new Vec3(4, 13, 0);
        assertEquals(WireBends.Verdict.OK,
                WireBends.check(WireBends.Op.INSERT, List.of(), 0, ok, A, B, 4, eye, 10));
        assertEquals(WireBends.Verdict.TOO_LONG,
                WireBends.check(WireBends.Op.INSERT, List.of(), 0, tooFar, A, B, 4, eye, 10));
        assertEquals(12, WireBends.maxPathLength(8, 4), 1e-9);
        assertEquals(36, WireBends.maxPathLength(8, 24), 1e-9);
    }
}
