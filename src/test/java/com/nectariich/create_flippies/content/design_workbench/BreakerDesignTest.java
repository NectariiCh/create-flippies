package com.nectariich.create_flippies.content.design_workbench;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.nectariich.create_flippies.client.design.DesignGeometry;
import com.nectariich.create_flippies.content.design_workbench.blueprint.Blueprint;
import com.nectariich.create_flippies.content.indicator.IndicatorAction;
import com.nectariich.create_flippies.content.indicator.IndicatorRating;
import com.nectariich.create_flippies.content.rotary_switch.RotarySwitchContact;
import com.nectariich.create_flippies.content.rotary_switch.RotarySwitchKind;

import dev.nectariich.hypergraphical.api.MeshInfo;
import dev.nectariich.hypergraphical.mesh.MeshBounds;
import net.minecraft.nbt.CompoundTag;
import org.junit.jupiter.api.Test;

/**
 * The breaker control switch's design and rules, short of the game: what its design
 * saves and reads back, the springs it always has, how its flag latches, which stud
 * each of its modes makes, and the room its flag takes on the panel.
 */
class BreakerDesignTest {

    private static final String HASH = "0123456789abcdef0123456789abcdef";

    private static final RotarySwitchKind KIND = RotarySwitchKind.THREE_POSITION;

    private static ControlDesign design(DesignKind kind, List<String> moving, int springs,
            BreakerIndicator indicator) {
        return new ControlDesign(kind, HASH, "Breaker", moving, List.of(), 4, 6, 0, 6,
                ControlDesign.defaultPoses(kind), null, new double[] { 6, 0, 6, 10, 2, 10 },
                new double[] { 7, 0, 7, 9, 2, 9 }, IndicatorAction.SPRING_RETURN, IndicatorRating.V24, List.of(),
                springs, null, indicator);
    }

    @Test
    void isAThreePositionSwitch() {
        assertEquals(DesignKind.BKR, DesignKind.of("bkr"));
        assertEquals(KIND, DesignKind.BKR.rotary());
        assertTrue(DesignKind.BKR.isBreaker());
        assertFalse(DesignKind.SWITCH_3P.isBreaker());
        assertEquals("designed_bkr", DesignKind.BKR.itemId());
        assertEquals(3, ControlDesign.positions(DesignKind.BKR));
    }

    @Test
    void roundTripsItsFlag() {
        BreakerIndicator indicator = new BreakerIndicator(List.of("Flag", "Knob"),
                new float[][] { { 10, 30, 0 }, { -10, -60, 5 } }, new float[] { 8, 1, 9 }, BreakerMode.SWITCHOVER);
        ControlDesign design = design(DesignKind.BKR, List.of("Knob"), 0, indicator);

        // The handle's part is never the flag as well
        assertEquals(List.of("Flag"), design.indicator().parts());

        CompoundTag tag = design.write();
        assertTrue(tag.contains(BreakerIndicator.TAG));
        ControlDesign read = ControlDesign.read(tag);
        assertEquals(tag, read.write());
        assertEquals(List.of("Flag"), read.indicator().parts());
        assertArrayEquals(new float[] { 10, 30, 0 }, read.indicator().poses()[BreakerIndicator.CCW], 1e-6f);
        assertArrayEquals(new float[] { -10, -60, 5 }, read.indicator().poses()[BreakerIndicator.CW], 1e-6f);
        assertArrayEquals(new float[] { 8, 1, 9 }, read.indicator().pivot(), 1e-6f);
        assertEquals(BreakerMode.SWITCHOVER, read.indicator().mode());

        // A blueprint carries it, renamed or not
        Blueprint blueprint = new Blueprint(java.util.UUID.randomUUID(), "Breaker", "", "me", 1, 2, read);
        assertEquals(blueprint.write(), Blueprint.read(blueprint.write()).write());
        assertEquals(BreakerMode.SWITCHOVER, blueprint.withName("Other").design().indicator().mode());
    }

    @Test
    void alwaysSpringsFromBothSides() {
        ControlDesign design = design(DesignKind.BKR, List.of("Knob"), 0, null);
        assertEquals(0b101, design.springs());
        CompoundTag tag = design.write();
        tag.putInt("Springs", 0);
        assertEquals(0b101, ControlDesign.read(tag).springs());
        tag.remove("Springs");
        assertEquals(0b101, ControlDesign.read(tag).springs());
        // and a plain three-position switch still springs only where it is told to
        assertEquals(0, design(DesignKind.SWITCH_3P, List.of("Knob"), 0, null).springs());
    }

    @Test
    void readsDefaultsWhereNothingWasSaved() {
        CompoundTag tag = design(DesignKind.BKR, List.of("Knob"), 0, null).write();
        tag.remove(BreakerIndicator.TAG);
        BreakerIndicator indicator = ControlDesign.read(tag).indicator();
        assertNotNull(indicator);
        assertEquals(List.of(), indicator.parts());
        assertArrayEquals(new float[] { 0, 45, 0 }, indicator.poses()[BreakerIndicator.CCW], 1e-6f);
        assertArrayEquals(new float[] { 0, -45, 0 }, indicator.poses()[BreakerIndicator.CW], 1e-6f);
        assertNull(indicator.pivot());
        assertEquals(BreakerMode.THREE_POSITION, indicator.mode());

        // An empty flag tag reads the same way
        tag.put(BreakerIndicator.TAG, new CompoundTag());
        assertEquals(BreakerIndicator.defaults().write(), ControlDesign.read(tag).indicator().write());
    }

    @Test
    void otherKindsHaveNoFlagAndWriteTheTagTheyAlwaysDid() {
        BreakerIndicator indicator = new BreakerIndicator(List.of("Flag"), null, null, BreakerMode.SWITCHOVER);
        for (DesignKind kind : List.of(DesignKind.SWITCH_2P, DesignKind.SWITCH_3P, DesignKind.BUTTON_2P)) {
            ControlDesign design = design(kind, List.of("Knob"), 0, indicator);
            assertNull(design.indicator());
            CompoundTag tag = design.write();
            assertFalse(tag.contains(BreakerIndicator.TAG));
            // and a flag smuggled into the tag of one is dropped
            tag.put(BreakerIndicator.TAG, indicator.write());
            assertNull(ControlDesign.read(tag).indicator());
        }
    }

    @Test
    void holdsAFlagToItsLimits() {
        CompoundTag tag = design(DesignKind.BKR, List.of("Knob"), 0, null).write();
        CompoundTag wild = new BreakerIndicator(List.of("Flag"), null, null, null).write();
        net.minecraft.nbt.ListTag poses = new net.minecraft.nbt.ListTag();
        for (float value : new float[] { 500, Float.NaN, -500, 1, 2, 3, 99, 99, 99 })
            poses.add(net.minecraft.nbt.FloatTag.valueOf(value));
        wild.put("Poses", poses);
        net.minecraft.nbt.ListTag pivot = new net.minecraft.nbt.ListTag();
        for (float value : new float[] { 100, Float.NaN, -100 })
            pivot.add(net.minecraft.nbt.FloatTag.valueOf(value));
        wild.put("Pivot", pivot);
        net.minecraft.nbt.ListTag parts = new net.minecraft.nbt.ListTag();
        for (int i = 0; i < 200; i++)
            parts.add(net.minecraft.nbt.StringTag.valueOf("part" + i));
        wild.put("Parts", parts);
        wild.putString("Mode", "sideways");
        tag.put(BreakerIndicator.TAG, wild);

        BreakerIndicator read = ControlDesign.read(tag).indicator();
        assertEquals(ControlDesign.MAX_MOVING_PARTS, read.parts().size());
        assertEquals(2, read.poses().length);
        assertArrayEquals(new float[] { 180, 0, -180 }, read.poses()[0], 1e-6f);
        assertArrayEquals(new float[] { 1, 2, 3 }, read.poses()[1], 1e-6f);
        assertArrayEquals(new float[] { 24, DesignMotion.DEFAULT_PIVOT[1], -8 }, read.pivot(), 1e-6f);
        assertEquals(BreakerMode.THREE_POSITION, read.mode());
    }

    @Test
    void latchesTheWayTheHandleWasThrown() {
        int ccw = 0, centre = 1, cw = 2;
        assertEquals(BreakerIndicator.CW, BreakerIndicator.latch(KIND, BreakerIndicator.CCW, cw));
        assertEquals(BreakerIndicator.CW, BreakerIndicator.latch(KIND, BreakerIndicator.CW, centre));
        assertEquals(BreakerIndicator.CCW, BreakerIndicator.latch(KIND, BreakerIndicator.CW, ccw));
        assertEquals(BreakerIndicator.CCW, BreakerIndicator.latch(KIND, BreakerIndicator.CCW, centre));
        assertEquals(BreakerIndicator.CCW, BreakerIndicator.sanitiseLatch(7));
        assertEquals(BreakerIndicator.CW, BreakerIndicator.sanitiseLatch(BreakerIndicator.CW));

        // Thrown clockwise and let go, then counter-clockwise and let go
        int latched = BreakerIndicator.CCW;
        for (int position : new int[] { cw, centre })
            latched = BreakerIndicator.latch(KIND, latched, position);
        assertEquals(BreakerIndicator.CW, latched);
        for (int position : new int[] { ccw, centre })
            latched = BreakerIndicator.latch(KIND, latched, position);
        assertEquals(BreakerIndicator.CCW, latched);
    }

    @Test
    void makesTheStudItsModeSays() {
        RotarySwitchContact[] studs = KIND.studs();
        for (int position = 0; position < KIND.positions(); position++)
            for (int latched : new int[] { BreakerIndicator.CCW, BreakerIndicator.CW }) {
                // Following the handle is exactly the three-position switch
                assertEquals(KIND.madeStud(position),
                        BreakerIndicator.madeStud(BreakerMode.THREE_POSITION, KIND, position, latched));
                // Following the flag never makes the neutral stud
                int made = BreakerIndicator.madeStud(BreakerMode.SWITCHOVER, KIND, position, latched);
                assertEquals(latched == BreakerIndicator.CW ? RotarySwitchContact.CLOCKWISE
                        : RotarySwitchContact.COUNTER_CLOCKWISE, studs[made]);
            }
    }

    @Test
    void frontCoversTheFlagsSwing() {
        Map<String, MeshBounds> parts = new LinkedHashMap<>();
        parts.put("Base", new MeshBounds(6, 0, 6, 10, 0.5f, 10));
        parts.put("Knob", new MeshBounds(7.8f, 0.5f, 7, 8.2f, 2, 9));
        // A flag reaching out along +z from the mounting axis
        parts.put("Flag", new MeshBounds(7.5f, 0.5f, 8, 8.5f, 1, 12));
        MeshBounds all = MeshBounds.unionOf(parts, parts.keySet());
        MeshInfo info = new MeshInfo(all, parts, 12);
        float[][] poses = ControlDesign.defaultPoses(DesignKind.BKR);

        double[] still = DesignGeometry.compute(DesignKind.BKR, info, Set.of("Knob"), 1, 0, 0, 0, poses, null)
                .front();
        assertArrayEquals(new double[] { 6, 0, 6, 10, 2, 12 }, still, 1e-5);

        double[] swung = DesignGeometry.compute(DesignKind.BKR, info, Set.of("Knob"), 1, 0, 0, 0, poses, null,
                Set.of("Flag"), BreakerIndicator.defaultPoses(), null).front();
        // At 45 degrees either way the flag's far corners stand (0.5 + 4) * 0.707 off
        // the axis on x, well past the base
        double reach = 4.5 * Math.sqrt(0.5);
        assertEquals(8 + reach, swung[3], 1e-4);
        assertEquals(8 - reach, swung[0], 1e-4);
        assertTrue(swung[5] >= 12 - 1e-4, "the flag passes upright on its way over");

        // A part that moves is the handle's, not the flag's
        double[] moving = DesignGeometry.compute(DesignKind.BKR, info, Set.of("Knob", "Flag"), 1, 0, 0, 0, poses,
                null, Set.of("Flag"), BreakerIndicator.defaultPoses(), null).front();
        double[] asHandle = DesignGeometry.compute(DesignKind.BKR, info, Set.of("Knob", "Flag"), 1, 0, 0, 0,
                poses, null).front();
        assertArrayEquals(asHandle, moving, 1e-9);
    }
}
