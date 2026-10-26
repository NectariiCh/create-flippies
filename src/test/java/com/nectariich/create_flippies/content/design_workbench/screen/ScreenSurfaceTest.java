package com.nectariich.create_flippies.content.design_workbench.screen;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Set;

import com.nectariich.create_flippies.content.design_workbench.ControlDesign;
import com.nectariich.create_flippies.content.design_workbench.DesignKind;
import com.nectariich.create_flippies.content.design_workbench.DesignText;
import com.nectariich.create_flippies.content.design_workbench.SurfacePlacement;
import com.nectariich.create_flippies.content.design_workbench.gauge.GaugeDial;
import com.nectariich.create_flippies.content.display.element.ElementKind;
import com.nectariich.create_flippies.content.display.element.ScreenElement;
import com.nectariich.create_flippies.content.display.element.ScreenElements;
import com.nectariich.create_flippies.content.display.element.TestElements;
import com.nectariich.create_flippies.content.display.element.Viewport;
import com.nectariich.create_flippies.content.indicator.IndicatorAction;
import com.nectariich.create_flippies.content.indicator.IndicatorRating;
import com.nectariich.create_flippies.content.procedural_gauge.SupplyVoltage;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.FloatTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import org.junit.jupiter.api.Test;

/**
 * A designed screen's surface: what it holds to whatever a client sends, where its
 * viewport lands on the model under each part of the placement, that it agrees with a
 * gauge's face where the two should, its trip through a design's tag, and the layout
 * it carries for a placed screen to start from. Also that adding screens changed
 * nothing any other design or a gauge's dial writes.
 */
class ScreenSurfaceTest {

    private static final String HASH = "0".repeat(32);
    private static final String FONT_HASH = "ab".repeat(16);
    private static final double EPS = 1e-4;

    /** A face 16 px square on the plate, x right and z down, over the whole UV square. */
    private static final float[] SQUARE = { 0, 1, 0, 16, 0, 0, 0, 0, 16 };

    /** The whole UV square. */
    private static final float[] FULL = { 0, 0, 1, 1 };

    @Test
    void anythingUnusableIsHeldOrRefused() {
        ScreenSurface wild = new ScreenSurface("nohash", new float[] { 0.5f, 0, 0.5f, 1 },
                new float[] { Float.NaN, 0, 0, Float.POSITIVE_INFINITY, 1000 }, false, false,
                new float[] { 1, 2, 3 }, null, null);
        assertEquals("", wild.face);
        assertFalse(wild.hasFace());
        assertArrayEquals(new float[] { 0, 0, 1, 1 }, wild.uv());
        assertArrayEquals(new float[] { 0, 0, SurfacePlacement.MIN_SCALE, 1, 360 }, wild.placement());
        assertArrayEquals(ScreenSurface.defaults().frame(), wild.frame());
        assertEquals(ScreenSurface.DEFAULT_SUPPLY, wild.supply);

        // Anything not finite in the frame refuses all of it
        float[] nan = SQUARE.clone();
        nan[4] = Float.NaN;
        assertArrayEquals(ScreenSurface.defaults().frame(), ScreenSurface.sanitiseFrame(nan, FULL));
        // So do axes that span no area: parallel, or one of no length
        assertArrayEquals(ScreenSurface.defaults().frame(),
                ScreenSurface.sanitiseFrame(new float[] { 0, 0, 0, 4, 0, 0, 8, 0, 0 }, FULL));
        assertArrayEquals(ScreenSurface.defaults().frame(),
                ScreenSurface.sanitiseFrame(new float[] { 0, 0, 0, 4, 0, 0, 0, 0, 0 }, FULL));
        // One that puts the UV rectangle out of reach is refused whole
        assertArrayEquals(ScreenSurface.defaults().frame(),
                ScreenSurface.sanitiseFrame(new float[] { 1000, -1000, 0, 500, 0, 0, 0, 0, 16 }, FULL));
        assertArrayEquals(ScreenSurface.defaults().frame(),
                ScreenSurface.sanitiseFrame(new float[] { 0, 1, 0, 16, 0, 0, 0, 0, 16 }, new float[] { 0, 0, 4, 1 }));
    }

    @Test
    void aScreenInACornerOfItsTextureKeepsItsSizeAndPlace() {
        // A 16 px face over u 0.75..1 of its texture: 64 px per U unit, and an origin
        // extrapolated 48 px left of the face, both past the reach budget as numbers
        float[] uv = { 0.75f, 0, 1, 0.25f };
        float[] frame = { -48, 1, 0, 64, 0, 0, 0, 0, 64 };
        assertArrayEquals(frame, ScreenSurface.sanitiseFrame(frame, uv));
        ScreenSurface surface = new ScreenSurface("screen#glass", uv, null, false, false, frame, null, null);
        assertEquals(16, surface.faceWidth(), EPS);
        assertEquals(16, surface.faceHeight(), EPS);
        double[] corner = surface.pointAt(0, 0);
        assertEquals(0, corner[0], EPS);
        assertEquals(1, corner[1], EPS);
        assertEquals(0, corner[2], EPS);
    }

    @Test
    void aHostileTagReadsAsSomethingSafe() {
        CompoundTag tag = new CompoundTag();
        tag.putString("Face", "#");
        ListTag frame = new ListTag();
        for (int i = 0; i < 9; i++)
            frame.add(FloatTag.valueOf(i == 3 ? Float.POSITIVE_INFINITY : 1));
        tag.put("Frame", frame);
        tag.putString("Supply", "1000v");
        CompoundTag layout = new CompoundTag();
        ListTag elements = new ListTag();
        CompoundTag huge = new ScreenElement(200, ElementKind.FILL, -5, -5, 30000, 30000, 0x123456, 0, 10, "", null,
                false, false, 0, 0, 1, 1).write();
        huge.putByte("I", (byte) 3);
        elements.add(huge);
        elements.add(huge.copy());
        layout.put("Elements", elements);
        tag.put("Layout", layout);

        ScreenSurface read = ScreenSurface.read(tag);
        assertEquals("", read.face);
        assertArrayEquals(ScreenSurface.defaults().frame(), read.frame());
        assertEquals(ScreenSurface.DEFAULT_SUPPLY, read.supply);
        // One element left of the two claiming slot 3, fitted to the viewport
        List<ScreenElement> held = read.layout().snapshot();
        assertEquals(1, held.size());
        ScreenElement element = held.get(0);
        assertEquals(3, element.id());
        Viewport viewport = read.viewport();
        assertTrue(element.x() >= 0 && element.y() >= 0);
        assertTrue(element.x() + element.w() <= viewport.width());
        assertTrue(element.y() + element.h() <= viewport.height());

        // An empty tag is the default screen
        assertEquals(ScreenSurface.defaults(), ScreenSurface.read(new CompoundTag()));
    }

    @Test
    void theViewportIsTheFaceTimesTheScale() {
        // 20 px per U across, 10 px per V down, over half the U range: a 10 x 10 face
        ScreenSurface surface = new ScreenSurface("Screen#glass", new float[] { 0.25f, 0, 0.75f, 1 },
                new float[] { 0, 0, 1.5f, 0.5f, 0 }, false, false, new float[] { 0, 1, 0, 20, 0, 0, 0, 0, 10 },
                SupplyVoltage.V5, null);
        assertEquals(10, surface.faceWidth(), EPS);
        assertEquals(10, surface.faceHeight(), EPS);
        assertEquals(new Viewport(150, 50), surface.viewport());
        assertTrue(surface.hasFace());
        // The default layout is one fitted line of lettering over the whole of it
        ScreenElement line = surface.layout().snapshot().get(0);
        assertEquals(ElementKind.TEXT, line.kind());
        assertEquals(150, line.w());
        assertEquals(50, line.h());
    }

    @Test
    void theCornersLandWhereThePlacementPutsThem() {
        ScreenSurface plain = surface(new float[] { 0, 0, 1, 1, 0 }, false, false);
        assertPoint(new double[] { 0, 1, 0 }, plain.pointAt(0, 0));
        assertPoint(new double[] { 16, 1, 0 }, plain.pointAt(16, 0));
        assertPoint(new double[] { 0, 1, 16 }, plain.pointAt(0, 16));
        assertPoint(new double[] { 16, 1, 16 }, plain.pointAt(16, 16));
        assertFalse(plain.overhangs());

        // Half size, moved a quarter of the face right and up: the centre goes to (12, 4)
        ScreenSurface moved = surface(new float[] { 0.25f, 0.25f, 0.5f, 0.5f, 0 }, false, false);
        assertEquals(new Viewport(80, 80), moved.viewport());
        assertPoint(new double[] { 12, 1, 4 }, moved.pointAt(4, 4));
        assertPoint(new double[] { 8, 1, 0 }, moved.pointAt(0, 0));
        assertFalse(moved.overhangs());
        assertTrue(surface(new float[] { 0.5f, 0, 1, 1, 0 }, false, false).overhangs());

        // A quarter turn clockwise as seen: the viewport's top left goes to the face's top right
        ScreenSurface turned = surface(new float[] { 0, 0, 1, 1, 90 }, false, false);
        assertPoint(new double[] { 16, 1, 0 }, turned.pointAt(0, 0));
        assertPoint(new double[] { 16, 1, 16 }, turned.pointAt(16, 0));
        assertPoint(new double[] { 0, 1, 0 }, turned.pointAt(0, 16));

        // Mirrored across: the viewport's left is the face's right
        ScreenSurface flipped = surface(new float[] { 0, 0, 1, 1, 0 }, true, false);
        assertPoint(new double[] { 16, 1, 0 }, flipped.pointAt(0, 0));
        assertPoint(new double[] { 16, 1, 16 }, surface(new float[] { 0, 0, 1, 1, 0 }, true, true).pointAt(0, 0));

        // The pose a renderer takes: corner, then a pixel right, then a pixel down
        double[] frame = turned.viewportFrame();
        assertPoint(new double[] { 16, 1, 0 }, new double[] { frame[0], frame[1], frame[2] });
        assertPoint(new double[] { 0, 0, 1 }, new double[] { frame[3], frame[4], frame[5] });
        assertPoint(new double[] { -1, 0, 0 }, new double[] { frame[6], frame[7], frame[8] });
    }

    /** Turned rigidly: a non-square face keeps its pixels square under any turn. */
    @Test
    void aTurnIsRigidOnANonSquareFace() {
        ScreenSurface wide = new ScreenSurface("Screen#glass", null, new float[] { 0, 0, 0.5f, 0.5f, 30 }, false,
                false, new float[] { 0, 1, 0, 24, 0, 0, 0, 0, 8 }, null, null);
        double[] frame = wide.viewportFrame();
        assertEquals(1, Math.hypot(Math.hypot(frame[3], frame[4]), frame[5]), EPS);
        assertEquals(1, Math.hypot(Math.hypot(frame[6], frame[7]), frame[8]), EPS);
        assertEquals(0, frame[3] * frame[6] + frame[4] * frame[7] + frame[5] * frame[8], EPS);
    }

    /**
     * With no turn, the viewport covers exactly what a gauge's face would on the same
     * rectangle with the same placement: its corners are the UVs the gauge's material
     * transform sends to the texture's corners. Flips included.
     */
    @Test
    void anUnturnedViewportAgreesWithAGaugesFace() {
        float[] uv = { 0.25f, 0.25f, 0.75f, 0.75f };
        float[] placement = { 0.1f, -0.2f, 0.5f, 0.75f, 0 };
        for (int flips = 0; flips < 4; flips++) {
            boolean flipX = (flips & 1) != 0, flipY = (flips & 2) != 0;
            ScreenSurface surface = new ScreenSurface("Screen#glass", uv, placement, flipX, flipY, SQUARE, null,
                    null);
            // An 8 x 8 face at half and three quarters: exactly 4 x 6 px, no rounding
            assertEquals(new Viewport(40, 60), surface.viewport());
            float[] material = new GaugeDial(GaugeDial.Quantity.VOLTAGE, null, null, "Screen#glass", uv, placement,
                    flipX, flipY, 0, false, true).uvTransform();
            assertArrayEquals(material, SurfacePlacement.uvTransform(uv, placement, flipX, flipY));
            for (int corner = 0; corner < 4; corner++) {
                double tx = corner & 1, ty = corner >> 1;
                double[] at = surface.uvAt(tx * 4, ty * 6);
                assertEquals(tx, at[0] * material[0] + material[2], EPS, "u at corner " + corner);
                assertEquals(ty, at[1] * material[1] + material[3], EPS, "v at corner " + corner);
            }
        }
    }

    @Test
    void equalByValue() {
        ScreenSurface a = surface(new float[] { 0.1f, 0, 1, 1, 15 }, true, false);
        ScreenSurface b = surface(new float[] { 0.1f, 0, 1, 1, 15 }, true, false);
        assertEquals(a, b);
        assertEquals(a.hashCode(), b.hashCode());
        assertEquals(a, ScreenSurface.read(a.write()));
        assertNotEquals(a, new ScreenSurface(a.face, a.uv(), a.placement(), a.flipX, a.flipY, a.frame(),
                SupplyVoltage.V24, a.layout()));
        assertNotEquals(a, a.withLayout(new ScreenElements()));
    }

    @Test
    void theLayoutIsCopiedFittedAndHasNoGenerations() {
        ScreenElements authored = new ScreenElements();
        Viewport big = new Viewport(400, 400);
        ScreenElement first = TestElements.add(authored, ElementKind.TEXT, big);
        authored.remove(first.id());
        ScreenElement fill = TestElements.add(authored, ElementKind.FILL, big);
        assertEquals(1, authored.generation(fill.id()));

        ScreenSurface surface = surface(new float[] { 0, 0, 1, 1, 0 }, false, false).withLayout(authored);
        assertFalse(surface.write().getCompound("Layout").contains("Gen"));
        ScreenElements layout = surface.layout();
        assertEquals(0, layout.generation(fill.id()));
        assertEquals(1, layout.size());
        // Fitted into the 16 px viewport from the 40 px one it was made in
        ScreenElement held = layout.get(fill.id());
        assertTrue(held.x() + held.w() <= 160 && held.y() + held.h() <= 160);

        // Each copy is its own: editing one leaves the surface as it was
        layout.remove(fill.id());
        assertEquals(1, surface.layout().size());
        ScreenElements into = ScreenElements.defaults(big);
        surface.copyLayoutInto(into);
        assertEquals(surface.layout().snapshot(), into.snapshot());
        // An emptied layout stays empty rather than becoming the default again
        assertTrue(ScreenSurface.read(surface.withLayout(new ScreenElements()).write()).layout().isEmpty());
    }

    @Test
    void aScreenDesignHasOnePoseAndNothingMoving() {
        ScreenElements authored = layoutOf(new ScreenElement(4, ElementKind.TEXT, 0, 0, 100, 40, 0xFF00FF00, 0, 10,
                "PRESSURE", DesignText.TTF_PREFIX + FONT_HASH, false, false, 0, 0, 0, 2));
        ScreenSurface surface = surface(new float[] { 0, 0, 1, 1, 0 }, false, false).withLayout(authored);
        ControlDesign design = ControlDesign.read(screenDesign(surface).write());
        assertEquals(DesignKind.SCREEN, design.kind());
        assertEquals(1, design.positions());
        assertEquals(1, ControlDesign.positions(DesignKind.SCREEN));
        assertTrue(design.movingParts().isEmpty());
        assertFalse(design.slides());
        assertNull(design.gauge());
        assertNull(design.rack());
        assertEquals(surface, design.screen());
        assertEquals("PRESSURE", design.screen().layout().get(4).text());
        // The layout's font travels with the design, as its printed text's do
        assertEquals(Set.of(FONT_HASH), design.fontHashes());

        // A screen always has a surface, the default one when it was given none
        ControlDesign bare = ControlDesign.read(screenDesign(null).write());
        assertEquals(ScreenSurface.defaults(), bare.screen());
        assertTrue(bare.write().contains(ScreenSurface.TAG, Tag.TAG_COMPOUND));
        assertTrue(DesignKind.SCREEN.isScreen());
        assertNull(DesignKind.SCREEN.rotary());
        assertFalse(DesignKind.SCREEN.isGenerated());
        assertEquals(16, DesignKind.SCREEN.frontWidth());
        assertEquals(DesignKind.SCREEN, DesignKind.of("screen"));
    }

    /** No other kind takes a surface, so every design made before screens writes the tag it always did. */
    @Test
    void otherKindsWriteNoSurface() {
        for (DesignKind kind : DesignKind.values()) {
            if (kind.isScreen())
                continue;
            ControlDesign design = new ControlDesign(kind, HASH, "Other", List.of("Knob"), List.of(), 1, 0, 0, 0,
                    ControlDesign.defaultPoses(kind), null, new double[] { 0, 0, 0, 16, 2, 16 },
                    new double[] { 6, 0, 6, 10, 2, 10 }, IndicatorAction.SPRING_RETURN, IndicatorRating.V24,
                    List.of(), 0, null, null, null, null, null, ScreenSurface.defaults());
            assertNull(design.screen(), kind.id());
            CompoundTag tag = design.write();
            assertFalse(tag.contains(ScreenSurface.TAG), kind.id());
            // The constructor without a surface writes exactly the same
            assertEquals(tag, new ControlDesign(kind, HASH, "Other", List.of("Knob"), List.of(), 1, 0, 0, 0,
                    ControlDesign.defaultPoses(kind), null, new double[] { 0, 0, 0, 16, 2, 16 },
                    new double[] { 6, 0, 6, 10, 2, 10 }, IndicatorAction.SPRING_RETURN, IndicatorRating.V24,
                    List.of(), 0, null, null, null, null, null).write(), kind.id());
            // And a surface smuggled into another kind's tag is not read
            tag.put(ScreenSurface.TAG, ScreenSurface.defaults().write());
            assertNull(ControlDesign.read(tag).screen(), kind.id());
        }
    }

    /** A gauge's dial writes what it wrote before its placement moved into SurfacePlacement. */
    @Test
    void aGaugesDialTagIsUnchanged() {
        GaugeDial dial = new GaugeDial(GaugeDial.Quantity.CURRENT, null, null, "Dial#face",
                new float[] { 0.75f, 0.1f, 0.25f, 0.9f }, new float[] { 0.1f, -0.2f, 0, Float.NaN, 400 }, true,
                false, 12, true, false);
        CompoundTag tag = dial.write();
        assertEquals(Set.of("Quantity", "Dial", "Face", "Uv", "Placement", "NeedleZero", "Reverse", "Slide",
                "FlipX", "FlipY"), tag.getAllKeys());
        assertArrayEquals(new float[] { 0.25f, 0.1f, 0.75f, 0.9f }, floats(tag.getList("Uv", Tag.TAG_FLOAT)));
        assertArrayEquals(new float[] { 0.1f, -0.2f, GaugeDial.MIN_SCALE, 1, 360 },
                floats(tag.getList("Placement", Tag.TAG_FLOAT)));
        assertEquals(dial, GaugeDial.read(tag, false));
        assertNotNull(dial.dial);
    }

    private static ScreenSurface surface(float[] placement, boolean flipX, boolean flipY) {
        return new ScreenSurface("Screen#glass", null, placement, flipX, flipY, SQUARE, null, null);
    }

    private static ControlDesign screenDesign(ScreenSurface surface) {
        return new ControlDesign(DesignKind.SCREEN, HASH, "Panel", List.of(), List.of(), 1, 0, 0, 0,
                ControlDesign.defaultPoses(DesignKind.SCREEN), null, new double[] { 0, 0, 0, 16, 2, 16 },
                new double[] { 6, 0, 6, 10, 2, 10 }, IndicatorAction.SPRING_RETURN, IndicatorRating.V24, List.of(),
                0, null, null, null, null, null, surface);
    }

    private static ScreenElements layoutOf(ScreenElement... elements) {
        CompoundTag tag = new CompoundTag();
        ListTag list = new ListTag();
        for (ScreenElement element : elements)
            list.add(element.write());
        tag.put("Elements", list);
        ScreenElements layout = new ScreenElements();
        layout.read(tag, new Viewport(Short.MAX_VALUE, Short.MAX_VALUE), null);
        return layout;
    }

    private static float[] floats(ListTag list) {
        float[] values = new float[list.size()];
        for (int i = 0; i < values.length; i++)
            values[i] = list.getFloat(i);
        return values;
    }

    private static void assertPoint(double[] expected, double[] actual) {
        assertEquals(expected.length, actual.length);
        for (int i = 0; i < expected.length; i++)
            assertEquals(expected[i], actual[i], EPS, "axis " + i);
    }
}
