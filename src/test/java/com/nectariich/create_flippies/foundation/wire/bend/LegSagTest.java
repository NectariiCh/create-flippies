package com.nectariich.create_flippies.foundation.wire.bend;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.phys.Vec3;

import org.junit.jupiter.api.Test;
import org.patryk3211.powergrid.electricity.wire.CurveParameters;

class LegSagTest {

    /** Power Grid's copper wire. */
    private static final double H = 1.01, V = 1.2, T = 0.0625;

    @Test
    void sagOfOneIsTheWireTypesOwnCurve() {
        assertEquals(H, LegSag.horizontal(H, 1));
        assertEquals(V, LegSag.vertical(V, 1));
        BendGeometry.LegCurves curves = LegSag.curves(H, V, new float[] {1, 1, 1});
        assertEquals(H, curves.horizontal(2));
        assertEquals(V, curves.vertical(0));
    }

    @Test
    void coefficientsMatchPowerPlantGrid() {
        // h' = 1 + (h - 1)k, v' = clamp(1 + (v - 1)k, 1, 3)
        assertEquals(1 + 0.01 * 3, LegSag.horizontal(H, 3), 1e-12);
        assertEquals(1 + 0.2 * 3, LegSag.vertical(V, 3), 1e-12);
        assertEquals(LegSag.MAX_VERTICAL, LegSag.vertical(V, 50), 1e-12);
        assertEquals(0.03, LegSag.minSag(H), 1e-9);
        assertEquals(60, LegSag.maxSag(H), 1e-9);
        assertEquals(60, LegSag.clamp(H, 1000), 1e-9);
    }

    @Test
    void solveFindsTheSagThroughATargetHeight() {
        Vec3 a = new Vec3(0, 70, 0), b = new Vec3(10, 70, 0);
        double k = 4;
        CurveParameters curve = LegSag.curve(a, b, H, V, T, k);
        double y = a.y + curve.apply(1.5);
        double solved = LegSag.solve(H, V, T, a, b, 1.5, y);
        assertEquals(k, solved, 1e-3);
        assertTrue(LegSag.depth(LegSag.curve(a, b, H, V, T, 8), a, b)
                > LegSag.depth(LegSag.curve(a, b, H, V, T, 2), a, b));
    }

    @Test
    void insertSplitsALegIntoTwoThatKeepItsSag() {
        float[] sags = {2, 3, 4};
        assertArrayEquals(new float[] {2, 3, 3, 4}, LegSag.apply(WireBends.Op.INSERT, sags, 1, null));
        assertArrayEquals(new float[] {2, 2, 3, 4}, LegSag.apply(WireBends.Op.INSERT, sags, 0, null));
        assertArrayEquals(new float[] {2, 3, 4, 4}, LegSag.apply(WireBends.Op.INSERT, sags, 2, null));
        assertArrayEquals(new float[] {2, 3, 4}, sags, "input left alone");
    }

    @Test
    void removeMergesTwoLegsByLengthWeightedLogSag() {
        List<Vec3> path = List.of(new Vec3(0, 0, 0), new Vec3(3, 0, 0), new Vec3(4, 0, 0), new Vec3(9, 0, 0));
        float[] sags = {2, 1, 8};
        // Point 1 sits between legs 1 (1 block) and 2 (5 blocks)
        float[] merged = LegSag.apply(WireBends.Op.REMOVE, sags, 1, path);
        assertEquals(2, merged.length);
        assertEquals(2f, merged[0]);
        assertEquals((float) Math.exp((1 * Math.log(1) + 5 * Math.log(8)) / 6), merged[1], 1e-6);
        // Two equal legs merge exactly
        assertEquals(3f, LegSag.apply(WireBends.Op.REMOVE, new float[] {3, 3}, 0,
                List.of(Vec3.ZERO, new Vec3(1, 0, 0), new Vec3(2, 0, 0)))[0]);
    }

    @Test
    void moveChangesNothingAndReverseFlips() {
        float[] sags = {2, 3};
        assertArrayEquals(sags, LegSag.apply(WireBends.Op.MOVE, sags, 0, null));
        assertArrayEquals(new float[] {3, 2}, LegSag.reversed(sags));
    }

    @Test
    void normaliseFillsAWrongLengthWithTheFirstValue() {
        float[] sags = {2, 3};
        assertTrue(sags == LegSag.normalise(sags, 2));
        assertArrayEquals(new float[] {2, 2, 2}, LegSag.normalise(sags, 3));
        assertArrayEquals(new float[] {1, 1}, LegSag.normalise(null, 2));
    }

    @Test
    void savedOnlyWhenAnyLegIsNotDefault() {
        CompoundTag tag = new CompoundTag();
        LegSag.write(tag, new float[] {1, 1});
        assertFalse(tag.contains(LegSag.NBT_KEY));
        assertNull(LegSag.read(tag));
        LegSag.write(tag, new float[] {1, 2.5f});
        assertArrayEquals(new float[] {1, 2.5f}, LegSag.read(tag));
    }

    @Test
    void unbentSagUsesPowerPlantGridsFormat() {
        CompoundTag tag = new CompoundTag();
        LegSag.writePpg(tag, 1);
        assertFalse(tag.contains(LegSag.PPG_SAG_KEY));
        assertEquals(1f, LegSag.readPpg(tag));
        LegSag.writePpg(tag, 0.5f);
        assertEquals(0.5f, tag.getFloat("PPGSag"));
        assertEquals(0.5f, LegSag.readPpg(tag));
    }
}
