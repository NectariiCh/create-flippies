package com.nectariich.create_flippies.content.design_workbench.gauge;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import com.nectariich.create_flippies.compat.Ids;
import com.nectariich.create_flippies.content.design_workbench.ControlDesign;
import com.nectariich.create_flippies.content.design_workbench.DesignKind;
import com.nectariich.create_flippies.content.design_workbench.DesignMotion;
import com.nectariich.create_flippies.content.indicator.IndicatorAction;
import com.nectariich.create_flippies.content.indicator.IndicatorRating;
import com.nectariich.create_flippies.content.procedural_gauge.EdgewiseFaceDefinition;
import com.nectariich.create_flippies.content.procedural_gauge.GaugeFaceDefinition;

import dev.nectariich.hypergraphical.mesh.MeshData;
import dev.nectariich.hypergraphical.mesh.obj.ObjImportSettings;
import dev.nectariich.hypergraphical.mesh.obj.ObjMeshReader;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.FloatTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.resources.ResourceLocation;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;

/**
 * The gauges made at the Design Workbench on an imported model: the dial a design
 * carries (its face, the submesh it is printed on, its needle's motion), its trip
 * through a design's tag and through the workbench's draft, designs saved while
 * gauges had a generated case, how the needle is posed by the reading, how an
 * edgewise needle is fitted to the printed scale, and the UV bounds a face is
 * stretched over.
 */
class DesignedGaugeTest {

    private static final String HASH = "0".repeat(32);

    @Test
    void theDefaultsAreBuildableAndOfTheirFace() {
        GaugeDial rotary = GaugeDial.defaults(false);
        GaugeDial edgewise = GaugeDial.defaults(true);
        assertNull(rotary.error());
        assertNull(edgewise.error());
        assertNotNull(rotary.dial);
        assertNull(rotary.edgewise);
        assertTrue(edgewise.isEdgewise());
        assertFalse(rotary.hasFace());
        assertEquals(24, edgewise.maxValue());
        // An edgewise needle slides by default; a rotary one never does
        assertTrue(edgewise.slides());
        assertFalse(rotary.slides());
    }

    @Test
    void changingKindKeepsTheScale() {
        GaugeDial rotary = new GaugeDial(GaugeDial.Quantity.CURRENT,
                GaugeFaceDefinition.builder(-5, 5).units("A").majorTickInterval(1).build(), null, "dial#face",
                new float[] { 0.25f, 0.25f, 0.75f, 0.75f }, 30, true, true);
        GaugeDial edgewise = rotary.withKind(true);
        assertTrue(edgewise.isEdgewise());
        assertEquals(-5, edgewise.minValue());
        assertEquals(5, edgewise.maxValue());
        assertEquals("A", edgewise.units());
        assertEquals(GaugeDial.Quantity.CURRENT, edgewise.quantity);
        assertEquals("dial#face", edgewise.face);
        assertEquals(rotary.write().getString("Quantity"), rotary.withKind(true).withKind(false).write()
                .getString("Quantity"));
        assertEquals(-5, edgewise.withKind(false).minValue());
    }

    @Test
    void reportsAScaleThatCannotBePrinted() {
        assertEquals("gauge.error.range", dial(GaugeFaceDefinition.builder(5, 5).build()).error());
        assertEquals("gauge.error.ticks",
                dial(GaugeFaceDefinition.builder(0, 1000).majorTickInterval(0.001f).build()).error());
    }

    @Test
    void readsBackWhatItWrote() {
        for (boolean edgewise : new boolean[] { false, true }) {
            GaugeDraft draft = GaugeDraft.of(GaugeDial.defaults(edgewise));
            draft.quantity = GaugeDial.Quantity.CURRENT;
            draft.min = -2;
            draft.max = 2;
            draft.majorTick = 0.5f;
            draft.minorTicks = 4;
            draft.decimals = 1;
            draft.units = "A";
            draft.name = "Load";
            draft.face = "body#glass";
            draft.uv = new float[] { 0.5f, 0, 1, 0.5f };
            draft.placement = new float[] { 0.1f, -0.2f, 1.5f, 0.8f, 30 };
            draft.flipX = true;
            draft.needleZero = -90;
            draft.reverse = true;
            draft.slide = false;
            draft.faceAspect = 3;
            draft.bands.add(new GaugeDraft.Band(1, 2, 0xA01010));
            GaugeDial built = draft.build();
            assertNull(built.error());

            GaugeDial read = GaugeDial.read(built.write(), edgewise);
            assertEquals(built, read);
            assertEquals("body#glass", read.face);
            assertArrayEquals(new float[] { 0.5f, 0, 1, 0.5f }, read.uv());
            assertArrayEquals(new float[] { 0.1f, -0.2f, 1.5f, 0.8f, 30 }, read.placement());
            assertTrue(read.flipX);
            assertFalse(read.flipY);
            // The draft loses nothing either
            assertEquals(built.write(), GaugeDraft.of(read).build().write());
        }
    }

    @Test
    void sanitisesWhatAClientSends() {
        CompoundTag tag = GaugeDial.defaults(false).write();
        tag.putString("Face", "no-hash-here");
        ListTag uv = new ListTag();
        for (float value : new float[] { Float.NaN, 0, 1, 1 })
            uv.add(FloatTag.valueOf(value));
        tag.put("Uv", uv);
        tag.putFloat("NeedleZero", Float.POSITIVE_INFINITY);
        GaugeDial read = GaugeDial.read(tag, false);
        assertEquals("", read.face);
        assertArrayEquals(new float[] { 0, 0, 1, 1 }, read.uv());
        assertEquals(0, read.needleZero);

        // A degenerate rectangle is the whole square, and a backwards one is put in order
        assertArrayEquals(new float[] { 0, 0, 1, 1 }, GaugeDial.sanitiseUv(new float[] { 0.5f, 0, 0.5f, 1 }));
        assertArrayEquals(new float[] { 0.2f, 0.1f, 0.6f, 0.9f },
                GaugeDial.sanitiseUv(new float[] { 0.6f, 0.9f, 0.2f, 0.1f }));
    }

    @Test
    void stretchesTheFaceOverItsUvRectangle() {
        GaugeDial gauge = new GaugeDial(null, null, null, "a#b", new float[] { 0.25f, 0.5f, 0.75f, 1 }, 0, false,
                true);
        float[] t = gauge.uvTransform();
        // uv * tiling + offset takes the rectangle's corners to the texture's
        assertEquals(0, 0.25f * t[0] + t[2], 1e-6f);
        assertEquals(1, 0.75f * t[0] + t[2], 1e-6f);
        assertEquals(0, 0.5f * t[1] + t[3], 1e-6f);
        assertEquals(1, 1 * t[1] + t[3], 1e-6f);
    }

    @Test
    void movesAndScalesTheFaceWithinItsUvRectangle() {
        float[] rect = { 0.25f, 0.5f, 0.75f, 1 };
        // Twice as large: the rectangle's centre stays on the texture's, its corners reach a quarter in
        float[] t = new GaugeDial(null, null, null, "a#b", rect, new float[] { 0, 0, 2, 2, 0 }, false, false, 0, false, true)
                .uvTransform();
        assertEquals(0.5f, 0.5f * t[0] + t[2], 1e-6f);
        assertEquals(0.25f, 0.25f * t[0] + t[2], 1e-6f);
        assertEquals(0.75f, 1 * t[1] + t[3], 1e-6f);
        // A quarter right and a quarter up: the rectangle's centre samples left of and below the texture's
        t = new GaugeDial(null, null, null, "a#b", rect, new float[] { 0.25f, 0.25f, 1, 1, 0 }, false, false, 0, false,
                true)
                .uvTransform();
        assertEquals(0.25f, 0.5f * t[0] + t[2], 1e-6f);
        assertEquals(0.75f, 0.75f * t[1] + t[3], 1e-6f);
        // Mirrored left to right: the dial's centre stays put and its left edge samples the texture's right
        t = new GaugeDial(null, null, null, "a#b", rect, null, true, false, 0, false, true).uvTransform();
        assertEquals(0.5f, 0.5f * t[0] + t[2], 1e-6f);
        assertEquals(1, 0.25f * t[0] + t[2], 1e-6f);
        assertEquals(0, 0.5f * t[1] + t[3], 1e-6f);
        // Nonsense is held to what a saved gauge keeps
        assertArrayEquals(new float[] { 0, 99, GaugeDial.MIN_SCALE, 1, -360 },
                GaugeDial.sanitisePlacement(new float[] { Float.NaN, 99, 0, Float.POSITIVE_INFINITY, -1000 }));
        // A large scale is kept as it is
        assertEquals(50, GaugeDial.sanitisePlacement(new float[] { 0, 0, 50, 200, 0 })[2]);
        assertEquals(200, GaugeDial.sanitisePlacement(new float[] { 0, 0, 50, 200, 0 })[3]);
    }

    @Test
    void pegsALittlePastEitherEnd() {
        GaugeDial gauge = GaugeDial.defaults(false);
        assertEquals(0.5f, gauge.progress(12), 1e-6f);
        assertEquals(1 + GaugeDial.OVER, gauge.progress(1000), 1e-6f);
        assertEquals(-GaugeDial.UNDER, gauge.progress(-1000), 1e-6f);
        assertEquals(0, gauge.progress(Float.NaN));
        assertEquals(12, GaugeDial.Quantity.VOLTAGE.reading(12), 1e-6f);
        assertEquals(1 / 0.05f, GaugeDial.Quantity.CURRENT.reading(1), 1e-3f);
        assertEquals(GaugeDial.Quantity.VOLTAGE, GaugeDial.Quantity.of("nonsense"));
    }

    // ------------------------------------------------------------ the design

    @Test
    void travelsOnADesignOfAGaugeKindOnly() {
        GaugeDraft draft = GaugeDraft.of(GaugeDial.defaults(true));
        draft.units = "bar";
        draft.face = "case#face";
        GaugeDial gauge = draft.build();
        float[][] poses = { { 0, 0, 3 }, { 0, 0, -40 } };

        ControlDesign design = design(DesignKind.EDGEWISE_GAUGE, gauge, poses);
        ControlDesign read = ControlDesign.read(design.write());
        // Stable once read: the travel past a block was clamped on the way in
        assertEquals(read.write(), ControlDesign.read(read.write()).write());
        assertEquals("bar", read.gauge().units());
        assertEquals("case#face", read.gauge().face);
        assertEquals(2, read.positions());
        assertTrue(read.slides());
        // A sliding needle may travel a block, no further
        assertEquals(3, read.poses()[0][2]);
        assertEquals(-16, read.poses()[1][2]);

        // Any other kind drops it, and a gauge kind given the other face gets it converted
        assertNull(design(DesignKind.ANNUNCIATOR, gauge, null).gauge());
        assertNull(design(DesignKind.SWITCH_2P, gauge, null).gauge());
        ControlDesign rotary = design(DesignKind.GAUGE, gauge, null);
        assertFalse(rotary.gauge().isEdgewise());
        assertEquals("bar", rotary.gauge().units());
        assertFalse(rotary.slides());

        // A gauge design saved without one reads back with its kind's default
        CompoundTag tag = design.write();
        tag.remove(GaugeDial.TAG);
        assertEquals(GaugeDial.defaults(true), ControlDesign.read(tag).gauge());

        // And a design of another kind writes exactly the tag it did before there were gauges
        assertFalse(design(DesignKind.BUTTON_2P, gauge, null).write().contains(GaugeDial.TAG));
    }

    @Test
    void aRotaryNeedleAlwaysFollowsItsDial() {
        GaugeDial gauge = dial(GaugeFaceDefinition.builder(0, 10).sweep(-135, 135).build());
        // Whatever poses it is given
        ControlDesign design = ControlDesign.read(design(DesignKind.GAUGE, gauge, new float[][] { { 9, 9, 9 },
                { 9, 9, 9 } }).write());
        assertArrayEquals(new float[] { 0, 135, 0 }, design.poses()[0]);
        assertArrayEquals(new float[] { 0, -135, 0 }, design.poses()[1]);
    }

    /**
     * Seen from the front (x right, z down), a needle modelled pointing up the face
     * from its pivot points where the dial's angle says, clockwise from 12 o'clock,
     * all the way round a sweep of more than half a turn.
     */
    @Test
    void turnsTheNeedleToTheDialAngle() {
        GaugeDial gauge = dial(GaugeFaceDefinition.builder(0, 10).sweep(-135, 135).build());
        ControlDesign design = design(DesignKind.GAUGE, gauge, null, new float[] { 8, 1, 8 });
        for (float progress : new float[] { 0, 0.25f, 0.5f, 0.75f, 1, 1 + GaugeDial.OVER }) {
            double angle = Math.toRadians(gauge.dial.progressToAngle(progress));
            Vector3f tip = DesignMotion.pose(design, progress).transformPosition(new Vector3f(8, 1, 4));
            assertEquals(8 + 4 * Math.sin(angle), tip.x, 1e-4, "x at " + progress);
            assertEquals(8 - 4 * Math.cos(angle), tip.z, 1e-4, "z at " + progress);
            assertEquals(1, tip.y, 1e-4);
        }

        // A needle modelled pointing at 3 o'clock, on a mirrored face
        GaugeDial mirrored = new GaugeDial(null, gauge.dial, null, "", null, 90, true, true);
        ControlDesign other = design(DesignKind.GAUGE, mirrored, null, new float[] { 8, 1, 8 });
        Vector3f tip = DesignMotion.pose(other, 0.5f).transformPosition(new Vector3f(12, 1, 8));
        // Half way is 12 o'clock on the dial, which the mirror shows the other way round
        assertEquals(8, tip.x, 1e-4);
        assertEquals(12, tip.z, 1e-4);
    }

    @Test
    void slidesAnEdgewiseNeedleInAStraightLine() {
        GaugeDial gauge = GaugeDial.defaults(true);
        ControlDesign design = design(DesignKind.EDGEWISE_GAUGE, gauge, new float[][] { { 0, 0, 4 }, { 0, 0, -6 } });
        Matrix4f half = DesignMotion.pose(design, 0.5f);
        Vector3f moved = half.transformPosition(new Vector3f(1, 2, 3));
        assertEquals(1, moved.x, 1e-5f);
        assertEquals(2, moved.y, 1e-5f);
        assertEquals(2, moved.z, 1e-5f);
        // And sweeps the whole travel, pegging included
        double[] swept = DesignMotion.sweep(new double[] { 0, 0, 0, 1, 1, 1 }, design);
        assertTrue(swept[2] <= -6 - 0.9);
        assertTrue(swept[5] >= 1 + 4 + 0.9);
    }

    @Test
    void swingsAnEdgewiseNeedleAboutItsPivot() {
        GaugeDial gauge = new GaugeDial(null, null, GaugeDial.defaultEdgewise(), "", null, 0, false, false);
        ControlDesign design = design(DesignKind.EDGEWISE_GAUGE, gauge, new float[][] { { -20, 0, 0 }, { 20, 0, 0 } },
                new float[] { 8, -10, 8 });
        assertFalse(design.slides());
        // Half way between -20 and 20 degrees about x is no turn at all
        Vector3f mid = DesignMotion.pose(design, 0.5f).transformPosition(new Vector3f(8, 2, 8));
        assertEquals(8, mid.z, 1e-4);
        Vector3f end = DesignMotion.pose(design, 1).transformPosition(new Vector3f(8, 2, 8));
        assertEquals(8 + 12 * Math.sin(Math.toRadians(20)), end.z, 1e-4);
    }

    @Test
    void fitsAnEdgewiseNeedleToThePrintedScale() {
        EdgewiseFaceDefinition face = GaugeDial.defaultEdgewise();
        double[] faceBox = { 6, 1, 2, 10, 1.1, 14 };
        double[] needle = { 7, 1.2, 7.5, 9, 1.3, 8.5 };
        float[] ends = GaugeNeedleFit.scaleEnds(face, faceBox);
        assertEquals(2 + face.scaleBottom * 12, ends[0], 1e-5f);
        assertEquals(2 + face.scaleTop * 12, ends[1], 1e-5f);

        float[][] slide = GaugeNeedleFit.slide(face, faceBox, needle);
        assertEquals(ends[0] - 8, slide[0][2], 1e-5f);
        assertEquals(ends[1] - 8, slide[1][2], 1e-5f);

        // Swung on an arm from a pivot well behind the face, the middle of the needle lands on each end
        float[] pivot = { 8, -20, 8 };
        float[][] swing = GaugeNeedleFit.swing(face, faceBox, needle, pivot);
        GaugeDial gauge = new GaugeDial(null, null, face, "", null, 0, false, false);
        ControlDesign design = design(DesignKind.EDGEWISE_GAUGE, gauge, swing, pivot);
        for (int i = 0; i < 2; i++) {
            Vector3f middle = DesignMotion.pose(design, i).transformPosition(new Vector3f(8, 1.25f, 8));
            assertEquals(ends[i], middle.z, 1e-3, "end " + i);
        }
    }

    /**
     * A design saved while gauges had a generated case: its case is still its model,
     * and its quantity and face are carried over, with no face submesh or needle.
     */
    @Test
    void readsAGaugeSavedWithAGeneratedCase() {
        CompoundTag legacy = new CompoundTag();
        legacy.putString("Quantity", "current");
        legacy.putFloat("Width", 8);
        legacy.putFloat("Height", 8);
        legacy.putFloat("Depth", 1.5f);
        legacy.putInt("Case", 0x2E2E30);
        legacy.put("Dial", GaugeFaceDefinition.builder(0, 5).units("A").majorTickInterval(1).build().toNbt());

        ControlDesign design = design(DesignKind.GAUGE, GaugeDial.defaults(false), null);
        CompoundTag tag = design.write();
        tag.remove(GaugeDial.TAG);
        tag.put(GaugeDial.LEGACY_TAG, legacy);
        // The case designs had one position
        ListTag poses = new ListTag();
        for (int i = 0; i < 3; i++)
            poses.add(FloatTag.valueOf(0));
        tag.put("Poses", poses);

        ControlDesign read = ControlDesign.read(tag);
        assertEquals(GaugeDial.Quantity.CURRENT, read.gauge().quantity);
        assertEquals(5, read.gauge().maxValue());
        assertEquals("A", read.gauge().units());
        assertFalse(read.gauge().hasFace());
        assertEquals(2, read.positions());
        // Written again, it is a gauge of today, and the old tag is gone
        CompoundTag again = read.write();
        assertTrue(again.contains(GaugeDial.TAG));
        assertFalse(again.contains(GaugeDial.LEGACY_TAG));

        // An edgewise one keeps its edgewise face
        CompoundTag edge = new CompoundTag();
        edge.put("Edgewise", EdgewiseFaceDefinition.builder(0, 50).units("V").build().toNbt());
        GaugeDial edgewise = GaugeDial.readLegacy(edge, true);
        assertTrue(edgewise.isEdgewise());
        assertEquals(50, edgewise.maxValue());
    }

    // ---------------------------------------------------------------- UVs

    private static final String OBJ = String.join("\n",
            "# a gauge: a case with a glass face, and a needle",
            "mtllib gauge.mtl",
            "v 0 0 0", "v 1 0 0", "v 1 0 1", "v 0 0 1",
            "vt 0.5 0.5", "vt 1 0.5", "vt 1 1", "vt 0.5 1", "vt 0 0", "vt 0.25 0.25",
            "o Case",
            "usemtl black",
            "f 1/5 2/6 3/5",
            "usemtl glass",
            "f 1/1 2/2 3/3 4/4",
            "o Needle",
            "g ignored_once_objects_are_named",
            "usemtl glass",
            "f 1//1 2/-1 3/6",
            "");

    @Test
    void readsTheUvBoundsOfOneSubmesh() throws Exception {
        byte[] obj = OBJ.getBytes(StandardCharsets.UTF_8);
        // V is flipped, as Hypergraphical flips it
        assertArrayEquals(new float[] { 0.5f, 0, 1, 0.5f }, ObjUvBounds.of(obj, "Case", "glass"));
        assertArrayEquals(new float[] { 0, 0.75f, 0.25f, 1 }, ObjUvBounds.of(obj, "Case", "black"));
        // A corner with no texture coordinate counts as (0, 0), unflipped, as Hypergraphical has it
        assertArrayEquals(new float[] { 0, 0, 0.25f, 0.75f }, ObjUvBounds.of(obj, "Needle", "glass"));
        assertNull(ObjUvBounds.of(obj, "Case", "nothing"));
    }

    /** The bounds are those of the very UVs Hypergraphical gives the same submesh. */
    @Test
    void agreesWithHypergraphicalOnUvs() throws Exception {
        byte[] obj = OBJ.getBytes(StandardCharsets.UTF_8);
        MeshData mesh = ObjMeshReader.read(new ByteArrayInputStream(obj),
                Ids.of("create_flippies", "designs/test/gauge.obj"), ObjImportSettings.DEFAULT,
                mtl -> null).mesh();
        int checked = 0;
        for (MeshData.SubMesh sub : mesh.subMeshes()) {
            if (sub.indexCount() == 0)
                continue;
            float u0 = Float.MAX_VALUE, v0 = Float.MAX_VALUE, u1 = -Float.MAX_VALUE, v1 = -Float.MAX_VALUE;
            for (int i = sub.indexOffset(); i < sub.indexOffset() + sub.indexCount(); i++) {
                int at = mesh.indices()[i] * MeshData.STRIDE_FLOATS + 6;
                u0 = Math.min(u0, mesh.vertices()[at]);
                u1 = Math.max(u1, mesh.vertices()[at]);
                v0 = Math.min(v0, mesh.vertices()[at + 1]);
                v1 = Math.max(v1, mesh.vertices()[at + 1]);
            }
            assertArrayEquals(new float[] { u0, v0, u1, v1 }, ObjUvBounds.of(obj, sub.part(), sub.name()), 1e-6f,
                    sub.part() + "#" + sub.name());
            checked++;
        }
        assertEquals(3, checked);
    }

    // ------------------------------------------------------------- helpers

    private static GaugeDial dial(GaugeFaceDefinition face) {
        return new GaugeDial(GaugeDial.Quantity.VOLTAGE, face, null, "", null, 0, false, true);
    }

    private static ControlDesign design(DesignKind kind, GaugeDial gauge, float[][] poses) {
        return design(kind, gauge, poses, null);
    }

    private static ControlDesign design(DesignKind kind, GaugeDial gauge, float[][] poses, float[] pivot) {
        double[] box = { 4, 0, 4, 12, 2, 12 };
        return new ControlDesign(kind, HASH, "Gauge", new ArrayList<>(List.of("Needle")), List.of(), 1, 0, 0, 0,
                poses != null ? poses : ControlDesign.defaultPoses(kind), pivot, box, box,
                IndicatorAction.SPRING_RETURN, IndicatorRating.V24, List.of(), 0, null, null, null, null, gauge);
    }
}
