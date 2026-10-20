package com.nectariich.create_flippies.content.design_workbench;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import com.nectariich.create_flippies.content.design_workbench.blueprint.Blueprint;
import com.nectariich.create_flippies.content.indicator.IndicatorAction;
import com.nectariich.create_flippies.content.indicator.IndicatorRating;

import net.minecraft.nbt.CompoundTag;
import org.junit.jupiter.api.Test;

/**
 * A design's ambient occlusion settings: what they save and read back, that a design
 * which never touched them writes and reads exactly as before, and that whatever a
 * tag holds is held in range.
 */
class DesignOcclusionTest {

    private static final String HASH = "0123456789abcdef0123456789abcdef";

    private static ControlDesign design(DesignOcclusion occlusion) {
        return new ControlDesign(DesignKind.SWITCH_2P, HASH, "Knob", List.of("Knob"), List.of(), 4, 6, 0, 6,
                ControlDesign.defaultPoses(DesignKind.SWITCH_2P), null, new double[] { 6, 0, 6, 10, 2, 10 },
                new double[] { 7, 0, 7, 9, 2, 9 }, IndicatorAction.SPRING_RETURN, IndicatorRating.V24, List.of(),
                0, null, null, occlusion);
    }

    @Test
    void defaultsAreWhatDesignsAlwaysHad() {
        // Hypergraphical's strong bake, shown at 1.5 per instance
        assertEquals(96, DesignOcclusion.DEFAULT.rays());
        assertEquals(0.3f, DesignOcclusion.DEFAULT.radius());
        assertEquals(1.5f, DesignOcclusion.DEFAULT.instanceStrength());
        assertFalse(DesignOcclusion.DEFAULT.ownBake());
    }

    @Test
    void roundTrips() {
        DesignOcclusion occlusion = new DesignOcclusion(true, 0.75f, 0.12f, 160);
        ControlDesign read = ControlDesign.read(design(occlusion).write());
        assertEquals(occlusion, read.occlusion());
        assertTrue(read.occlusion().ownBake());
        assertEquals("r160_12", read.occlusion().bakeKey());

        DesignOcclusion off = DesignOcclusion.DEFAULT.withEnabled(false);
        ControlDesign readOff = ControlDesign.read(design(off).write());
        assertEquals(off, readOff.occlusion());
        assertEquals(0, readOff.occlusion().instanceStrength());
        // Off needs no bake whatever its radius says
        assertFalse(off.withRadius(0.8f).ownBake());

        // and a blueprint renamed keeps them
        Blueprint blueprint = new Blueprint(java.util.UUID.randomUUID(), "Knob", "", "me", 0, 0,
                design(occlusion)).withName("Mine");
        assertEquals("Mine", blueprint.design().name());
        assertEquals(occlusion, blueprint.design().occlusion());
        assertEquals(occlusion, Blueprint.read(blueprint.write()).design().occlusion());
    }

    @Test
    void defaultsWriteNoTag() {
        CompoundTag tag = design(null).write();
        assertFalse(tag.contains(DesignOcclusion.TAG));
        assertEquals(DesignOcclusion.DEFAULT, ControlDesign.read(tag).occlusion());
        assertEquals(tag, design(DesignOcclusion.DEFAULT).write());
        // Strength alone needs no bake of its own but is still saved
        DesignOcclusion lighter = DesignOcclusion.DEFAULT.withStrength(1);
        assertFalse(lighter.ownBake());
        assertTrue(design(lighter).write().contains(DesignOcclusion.TAG));
    }

    @Test
    void oldTagsReadAsDefaults() {
        CompoundTag tag = design(DesignOcclusion.DEFAULT).write();
        tag.remove(DesignOcclusion.TAG);
        assertEquals(DesignOcclusion.DEFAULT, ControlDesign.read(tag).occlusion());
        // A tag missing some values reads the rest as the defaults'
        tag.put(DesignOcclusion.TAG, new CompoundTag());
        assertEquals(DesignOcclusion.DEFAULT, ControlDesign.read(tag).occlusion());
    }

    @Test
    void clampsWhateverATagHolds() {
        CompoundTag settings = new CompoundTag();
        settings.putBoolean("Enabled", true);
        settings.putFloat("Strength", 9);
        settings.putFloat("Radius", -3);
        settings.putInt("Rays", 100_000);
        CompoundTag tag = design(null).write();
        tag.put(DesignOcclusion.TAG, settings);
        DesignOcclusion read = ControlDesign.read(tag).occlusion();
        assertEquals(DesignOcclusion.MAX_STRENGTH, read.strength());
        assertEquals(DesignOcclusion.MIN_RADIUS, read.radius());
        assertEquals(160, read.rays());

        settings.putFloat("Strength", Float.NaN);
        settings.putFloat("Radius", Float.POSITIVE_INFINITY);
        settings.putInt("Rays", 70);
        read = ControlDesign.read(tag).occlusion();
        assertEquals(1.5f, read.strength());
        assertEquals(0.3f, read.radius());
        assertEquals(64, read.rays());

        // Kept to hundredths, so a slider's value and the same value read back compare equal
        DesignOcclusion fine = new DesignOcclusion(true, 0.123456f, 0.33333f, 96);
        assertEquals(0.12f, fine.strength());
        assertEquals(0.33f, fine.radius());
        assertEquals(fine, ControlDesign.read(design(fine).write()).occlusion());
        assertNotEquals(DesignOcclusion.DEFAULT.bakeKey(), fine.bakeKey());
    }
}
