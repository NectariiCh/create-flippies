package com.nectariich.create_flippies.content.design_workbench.screen;

import com.nectariich.create_flippies.content.design_workbench.PlacementDraft;
import com.nectariich.create_flippies.content.design_workbench.SurfacePlacement;
import com.nectariich.create_flippies.content.display.element.ScreenElements;
import com.nectariich.create_flippies.content.procedural_gauge.SupplyVoltage;

/**
 * A screen being edited in the workbench's screen mode: the fields of a
 * {@link ScreenSurface} as the player edits them, loose and mutable, built into an
 * immutable one whenever the preview or a save needs it, as a gauge's
 * {@code GaugeDraft} is.
 *
 * <h2>The frame is held in model units</h2>
 *
 * A surface's frame is in authored pixels, but the workbench fits it to the model
 * before the model is sized and placed, and the player may size and place it again
 * afterwards. So the draft holds the plane as it was fitted to the model file, in the
 * model's own units, and {@link #build} carries it to authored pixels with the
 * design's scale and offset at that moment, the way the mesh itself is carried. A
 * loaded surface is brought back the other way with the scale and offset it was
 * saved with ({@link #of}).
 *
 * <h2>The layout</h2>
 *
 * {@link #layout} is the live collection the workbench's element editor edits; it is
 * one object for the draft's whole life, so the editor can hold on to it. It is laid
 * out in the viewport of {@link #shape}, and fitted to that viewport only when built
 * into a surface: a scale being typed digit by digit passes through small values on
 * its way, and fitting the live layout to each of them would shrink every box for good.
 */
public final class ScreenDraft implements PlacementDraft {

    /** The submesh, as an element key, or "" for none yet. */
    public String face = "";
    public float[] uv;
    public float[] placement = SurfacePlacement.IDENTITY.clone();
    public boolean flipX, flipY;

    /**
     * The fitted plane, {origin, dP/du, dP/dv}, in the model's units (after its import
     * scale), or null until a submesh is given the screen.
     */
    public double[] frame;

    /**
     * The UV rectangle the glass's faces were found to cover when it was fitted, or
     * null when not known. {@link #uv} is where the screen is placed and may be more
     * (Whole texture), so this is what the viewport must stay within to stay on the
     * glass. A loaded surface takes its saved rectangle as the best it has.
     */
    public float[] glassUv;

    /**
     * How far the submesh's vertices stand off the fitted plane at most, in the model's
     * units: more than a hair means a curved surface, whose content is only approximately
     * on it. NaN when not known (no fit yet, or a loaded design whose model file this
     * client lacks).
     */
    public double residual = Double.NaN;

    public SupplyVoltage supply = ScreenSurface.DEFAULT_SUPPLY;

    public final ScreenElements layout;

    private ScreenDraft(ScreenElements layout) {
        this.layout = layout;
    }

    /** A fresh screen: no submesh, the default frame, the default layout. */
    public static ScreenDraft fresh() {
        return of(ScreenSurface.defaults(), 1, 0, 0, 0);
    }

    /**
     * The draft of a saved surface, its frame taken back to the model's units with the
     * scale and offset the design was saved with. A surface with no submesh keeps no
     * frame: its default one was never fitted to anything.
     */
    public static ScreenDraft of(ScreenSurface surface, float scale, float offsetX, float offsetY, float offsetZ) {
        ScreenDraft draft = new ScreenDraft(surface.layout());
        draft.face = surface.face;
        draft.uv = surface.uv();
        draft.placement = surface.placement();
        draft.flipX = surface.flipX;
        draft.flipY = surface.flipY;
        draft.supply = surface.supply;
        if (surface.hasFace())
            draft.glassUv = surface.uv();
        if (surface.hasFace() && scale > 0) {
            float[] authored = surface.frame();
            float[] offset = { offsetX, offsetY, offsetZ };
            draft.frame = new double[9];
            for (int axis = 0; axis < 3; axis++) {
                draft.frame[axis] = (authored[axis] - offset[axis]) / (double) scale;
                draft.frame[3 + axis] = authored[3 + axis] / (double) scale;
                draft.frame[6 + axis] = authored[6 + axis] / (double) scale;
            }
        }
        return draft;
    }

    /** The frame in authored pixels for a design at that scale and offset, or null for the default frame. */
    public float[] authoredFrame(float scale, float offsetX, float offsetY, float offsetZ) {
        if (frame == null)
            return null;
        float[] offset = { offsetX, offsetY, offsetZ };
        float[] out = new float[9];
        for (int axis = 0; axis < 3; axis++) {
            out[axis] = (float) (offset[axis] + scale * frame[axis]);
            out[3 + axis] = (float) (scale * frame[3 + axis]);
            out[6 + axis] = (float) (scale * frame[6 + axis]);
        }
        return out;
    }

    /** The surface at a design's scale and offset, with the layout fitted to its viewport. */
    public ScreenSurface build(float scale, float offsetX, float offsetY, float offsetZ) {
        return new ScreenSurface(face, uv, placement, flipX, flipY, authoredFrame(scale, offsetX, offsetY, offsetZ),
                supply, layout);
    }

    /**
     * The surface's shape alone (no layout, which is what makes building one cheap),
     * for the viewport, the mapping and the warnings.
     */
    public ScreenSurface shape(float scale, float offsetX, float offsetY, float offsetZ) {
        return new ScreenSurface(face, uv, placement, flipX, flipY, authoredFrame(scale, offsetX, offsetY, offsetZ),
                supply, new ScreenElements());
    }

    /**
     * Whether the fitted surface is curved past what can be ignored, at that scale: more
     * than {@code tolerance} pixels between a vertex and the plane.
     */
    public boolean curved(float scale, double tolerance) {
        return Double.isFinite(residual) && residual * scale > tolerance;
    }

    // ---------------------------------------------------------- PlacementDraft

    @Override
    public String face() {
        return face == null ? "" : face;
    }

    @Override
    public void setFace(String face) {
        this.face = face == null ? "" : face;
    }

    @Override
    public float[] uv() {
        return uv;
    }

    @Override
    public void setUv(float[] uv) {
        this.uv = uv;
    }

    @Override
    public float[] placement() {
        return placement;
    }

    @Override
    public void setPlacement(float[] placement) {
        this.placement = placement;
    }

    @Override
    public boolean flipX() {
        return flipX;
    }

    @Override
    public void setFlipX(boolean flipX) {
        this.flipX = flipX;
    }

    @Override
    public boolean flipY() {
        return flipY;
    }

    @Override
    public void setFlipY(boolean flipY) {
        this.flipY = flipY;
    }
}
