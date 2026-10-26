package com.nectariich.create_flippies.content.design_workbench.screen;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import com.nectariich.create_flippies.content.display.element.ElementKind;
import com.nectariich.create_flippies.content.display.element.TestElements;
import com.nectariich.create_flippies.content.display.element.Viewport;
import com.nectariich.create_flippies.content.procedural_gauge.SupplyVoltage;

/**
 * The workbench's screen draft: its frame is held in the model's units and carried to
 * authored pixels by the design's scale and offset, and a saved surface comes back to
 * the same draft.
 */
class ScreenDraftTest {

    private static ScreenDraft fitted() {
        ScreenDraft draft = ScreenDraft.fresh();
        draft.face = "body#glass";
        draft.uv = new float[] { 0, 0, 1, 1 };
        // A 0.5 by 0.25 unit face in the model's y = 0.1 plane, u along x, v along z
        draft.frame = new double[] { -0.25, 0.1, -0.125, 0.5, 0, 0, 0, 0, 0.25 };
        draft.supply = SupplyVoltage.V5;
        return draft;
    }

    @Test
    void theFrameIsCarriedToAuthoredPixelsByTheDesignsScaleAndOffset() {
        ScreenDraft draft = fitted();
        ScreenSurface surface = draft.build(32, 8, 1, 8);
        assertArrayEquals(new float[] { 0, 4.2f, 4, 16, 0, 0, 0, 0, 8 }, surface.frame(), 1e-5f);
        assertEquals(16, surface.faceWidth(), 1e-5);
        assertEquals(8, surface.faceHeight(), 1e-5);
        assertEquals(new Viewport(160, 80), surface.viewport());
        assertEquals(SupplyVoltage.V5, surface.supply);
        assertEquals(surface.viewport(), draft.shape(32, 8, 1, 8).viewport());
        assertTrue(draft.shape(32, 8, 1, 8).layout().isEmpty());
    }

    @Test
    void aSavedSurfaceComesBackToTheSameDraft() {
        ScreenDraft draft = fitted();
        TestElements.add(draft.layout, ElementKind.FILL, new Viewport(160, 80));
        draft.placement = new float[] { 0.1f, -0.2f, 0.5f, 0.75f, 15 };
        draft.flipX = true;
        ScreenSurface saved = ScreenSurface.read(draft.build(32, 8, 1, 8).write());
        ScreenDraft back = ScreenDraft.of(saved, 32, 8, 1, 8);
        assertEquals("body#glass", back.face());
        assertArrayEquals(draft.frame, back.frame, 1e-6);
        assertArrayEquals(draft.placement, back.placement, 1e-6f);
        assertTrue(back.flipX);
        assertFalse(back.flipY);
        assertEquals(SupplyVoltage.V5, back.supply);
        assertEquals(draft.layout.size(), back.layout.size());
        assertEquals(saved, back.build(32, 8, 1, 8));
        // Not known until fitted again
        assertTrue(Double.isNaN(back.residual));
    }

    @Test
    void aScreenWithNoGlassKeepsNoFrame() {
        ScreenDraft fresh = ScreenDraft.fresh();
        assertEquals("", fresh.face());
        assertNull(fresh.frame);
        assertNull(fresh.authoredFrame(32, 8, 1, 8));
        assertNull(ScreenDraft.of(fresh.build(32, 8, 1, 8), 32, 8, 1, 8).frame);
    }

    @Test
    void curvedIsMeasuredInPixelsAtTheDesignsScale() {
        ScreenDraft draft = fitted();
        assertFalse(draft.curved(32, 0.05));
        draft.residual = 0.001;
        assertFalse(draft.curved(32, 0.05));
        assertTrue(draft.curved(64, 0.05));
    }

    @Test
    void aWholeTextureOverPartOfItIsRefusedRatherThanMovedToThePlaceholder() {
        // A 16 px glass over u 0.75 to 1 of its texture, 64 px per UV unit: the whole
        // texture would run from x = -48, past what a screen may reach
        ScreenDraft draft = ScreenDraft.fresh();
        draft.face = "body#glass";
        draft.uv = new float[] { 0.75f, 0, 1, 1 };
        draft.glassUv = draft.uv.clone();
        draft.frame = new double[] { -48, 1, 0, 64, 0, 0, 0, 0, 16 };
        float[] given = draft.authoredFrame(1, 0, 0, 0);
        ScreenSurface fitted = draft.shape(1, 0, 0, 0);
        assertFalse(fitted.refusedFrame(given));
        assertFalse(fitted.overhangs(draft.glassUv));

        draft.setUv(null);
        ScreenSurface whole = draft.shape(1, 0, 0, 0);
        assertTrue(whole.refusedFrame(given), "the fitted plane does not reach the whole texture");
        assertFalse(whole.refusedFrame(null), "no frame was asked for");

        // Back in reach (a glass over the whole texture), the viewport still hangs off
        // the part the glass covers when only that part is glass
        draft.frame = new double[] { 0, 1, 0, 16, 0, 0, 0, 0, 16 };
        draft.glassUv = new float[] { 0.75f, 0, 1, 1 };
        ScreenSurface wide = draft.shape(1, 0, 0, 0);
        assertFalse(wide.refusedFrame(draft.authoredFrame(1, 0, 0, 0)));
        assertFalse(wide.overhangs(), "within the rectangle it is placed in");
        assertTrue(wide.overhangs(draft.glassUv), "past the glass");
    }
}
