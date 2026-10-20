package com.nectariich.create_flippies.content.label;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;

import com.nectariich.create_flippies.foundation.control.ControlFootprint;
import com.nectariich.create_flippies.foundation.control.ControlMount;

import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.phys.Vec3;

import org.junit.jupiter.api.Test;

/**
 * A label drawn over others stands a tenth of a pixel proud of the highest of them,
 * so overlapping strips are never printed at one depth. Squares are used where the
 * test would otherwise depend on which authored axis a turn puts along u.
 */
class PanelLabelStackingTest {

    private static final double EPSILON = 1.0e-9;
    private static final Direction FACE = Direction.NORTH;
    private static final Vec3 HOME = Vec3.ZERO;

    private final List<PanelLabelStacking.Placed> mounted = new ArrayList<>();

    /** Anchors in whole pixels on the host's face; sides in pixels, as spans. */
    private static ControlMount at(int pixelU, int pixelV) {
        return ControlMount.onFace(FACE, pixelU * ControlMount.UNITS_PER_PIXEL, pixelV * ControlMount.UNITS_PER_PIXEL,
                0);
    }

    private static ControlFootprint square(int pixels) {
        return PanelLabel.footprint(pixels * 2, pixels * 2);
    }

    private int place(Vec3 host, ControlMount mount, int pixels) {
        int layer = PanelLabelStacking.layerOver(host, mount, square(pixels), mounted);
        mounted.add(new PanelLabelStacking.Placed(host, mount, PanelLabel.footprint(pixels * 2, pixels * 2, layer),
                layer));
        return layer;
    }

    private static PanelLabelControl label(int width, int height) {
        return (PanelLabelControl) PanelLabelControlType.INSTANCE.createSpanning(width, height);
    }

    // The rule

    @Test
    void aLabelOverNothingStaysOnThePanel() {
        assertEquals(0, place(HOME, at(4, 8), 6));
        assertEquals(0, place(HOME, at(12, 8), 6));
        assertEquals(0.25, PanelLabel.standOff(0), EPSILON);
    }

    @Test
    void aLabelOverOneStandsATenthProud() {
        place(HOME, at(8, 8), 6);
        int layer = place(HOME, at(9, 8), 6);
        assertEquals(1, layer);
        assertEquals(0.35, PanelLabel.standOff(layer), EPSILON);
    }

    @Test
    void aThirdOverBothStandsOverTheHigher() {
        place(HOME, at(8, 8), 6);
        place(HOME, at(9, 9), 6);
        assertEquals(2, place(HOME, at(10, 10), 6));
        assertEquals(0.45, PanelLabel.standOff(2), EPSILON);
    }

    @Test
    void aThirdOverOnlyTheLowerStandsOverThat() {
        place(HOME, at(4, 8), 4);
        place(HOME, at(6, 8), 4);
        // Covers the first and not the second, which ends at 8 along u
        assertEquals(1, place(HOME, at(1, 8), 4));
    }

    @Test
    void aCornerOverlapCounts() {
        place(HOME, at(4, 4), 4);
        assertEquals(1, place(HOME, at(7, 7), 4));
    }

    @Test
    void stripsThatOnlyTouchAreSideBySide() {
        place(HOME, at(4, 4), 4);
        assertEquals(0, place(HOME, at(8, 4), 4));
        assertEquals(0, place(HOME, at(4, 8), 4));
    }

    @Test
    void aLabelHostedNextDoorIsStillCovered() {
        ControlMount near = at(15, 8);
        Vec3 nextDoor = near.alongU();
        // 3..27 pixels along u against 12..24 in this host's terms
        place(nextDoor, at(2, 8), 12);
        assertEquals(1, place(HOME, near, 24));
    }

    @Test
    void aLabelHostedNextDoorThatDoesNotReachIsNot() {
        ControlMount near = at(15, 8);
        // 3..27 against 29..33
        place(near.alongU(), at(15, 8), 4);
        assertEquals(0, place(HOME, near, 24));
    }

    @Test
    void anotherSurfaceIsNotAStack() {
        ControlFootprint strip = square(6);
        ControlMount here = at(8, 8);
        assertFalse(PanelLabelStacking.overlaps(HOME, here, strip, HOME,
                ControlMount.onFace(Direction.SOUTH, here.anchorU(), here.anchorV(), 0), strip));
        assertFalse(PanelLabelStacking.overlaps(HOME, here, strip, HOME,
                new ControlMount(FACE, 0, here.anchorU(), here.anchorV(), ControlMount.ON_FACE - 32, 0), strip));
        // The block behind, on the same face, is a different plane too
        assertFalse(PanelLabelStacking.overlaps(HOME, here, strip, here.alongDepth(), here, strip));
        assertTrue(PanelLabelStacking.overlaps(HOME, here, strip, HOME, at(9, 8), strip));
    }

    @Test
    void aStackStopsGrowingAtTheTop() {
        mounted.add(new PanelLabelStacking.Placed(HOME, at(8, 8), square(6), PanelLabel.MAX_LAYER));
        assertEquals(PanelLabel.MAX_LAYER, place(HOME, at(9, 8), 6));
        assertEquals(PanelLabel.MAX_LAYER, PanelLabel.layer(99));
        assertEquals(0, PanelLabel.layer(-3));
        assertEquals(0.25 + 0.1 * 16, PanelLabel.standOff(99), EPSILON);
    }

    // The label's own geometry

    @Test
    void plateFootprintAndAnchorAllStandAsFarOff() {
        PanelLabelControl label = label(14, 4);
        label.stackOn(2);
        double[] box = label.footprint().boxes()[0];
        assertEquals(0, box[1], EPSILON);
        assertEquals(0.45, box[4], EPSILON);
        assertEquals(0.45, label.standOff(), EPSILON);
        assertEquals(0.45 / 16, label.textAnchor().y, EPSILON);
        assertEquals(PanelLabel.TEXT_ANCHOR.x, label.textAnchor().x, EPSILON);
        assertEquals(PanelLabel.TEXT_ANCHOR.z, label.textAnchor().z, EPSILON);
    }

    @Test
    void anUnstackedLabelIsExactlyWhatItWas() {
        PanelLabelControl label = label(14, 4);
        assertEquals(0, label.layer());
        assertEquals(PanelLabel.PROUD, label.footprint().boxes()[0][4], 0);
        assertEquals(PanelLabel.TEXT_ANCHOR, label.textAnchor());
        double[] before = PanelLabel.footprint(14, 4).boxes()[0];
        for (int i = 0; i < 6; i++)
            assertEquals(before[i], label.footprint().boxes()[0][i], 0);
    }

    // Saving

    @Test
    void theLayerSurvivesASave() {
        PanelLabelControl label = label(14, 4);
        label.stackOn(3);
        CompoundTag tag = new CompoundTag();
        label.write(tag);

        PanelLabelControl loaded = label(2, 2);
        loaded.read(tag);
        assertEquals(3, loaded.layer());
        assertEquals(0.55, loaded.footprint().boxes()[0][4], EPSILON);
    }

    @Test
    void aLabelSavedBeforeStackingSitsOnThePanel() {
        PanelLabelControl label = label(14, 4);
        CompoundTag tag = new CompoundTag();
        label.write(tag);
        assertFalse(tag.contains("Layer"));

        PanelLabelControl loaded = label(2, 2);
        loaded.stackOn(5);
        loaded.read(tag);
        assertEquals(0, loaded.layer());
        assertEquals(0.25, loaded.standOff(), 0);
    }

    @Test
    void aSaveClaimingTooHighALayerIsClamped() {
        PanelLabelControl label = label(14, 4);
        CompoundTag tag = new CompoundTag();
        label.write(tag);
        tag.putInt("Layer", 1000);

        label.read(tag);
        assertEquals(PanelLabel.MAX_LAYER, label.layer());
        assertEquals(0.25 + 0.1 * 16, label.footprint().boxes()[0][4], EPSILON);
    }
}
