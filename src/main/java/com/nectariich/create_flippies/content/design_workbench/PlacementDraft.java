package com.nectariich.create_flippies.content.design_workbench;

/**
 * Something being edited in the workbench that is placed on a submesh of the model
 * by UV: a gauge's printed face, or a screen's viewport. Both are given a submesh
 * the same way (the parts list's toggle or the material editor's checkbox), fill
 * the same kind of UV rectangle, and are moved, scaled, turned and mirrored within
 * it with the same numbers ({@link SurfacePlacement}), so the workbench edits both
 * with one set of rows; this is what those rows need of either.
 *
 * The placement array is the live one: the rows write single values into it and
 * the preview reads it back, as the dial tab always has.
 */
public interface PlacementDraft {

    /** The submesh, as an element key ({@link ControlDesign#elementKey}), or "" or null for none. */
    String face();

    void setFace(String face);

    /** The UV rectangle {u0, v0, u1, v1}, or null for the whole texture square. */
    float[] uv();

    void setUv(float[] uv);

    /** {offsetX, offsetY, scaleX, scaleY, rotation}, edited in place. */
    float[] placement();

    void setPlacement(float[] placement);

    boolean flipX();

    void setFlipX(boolean flipX);

    boolean flipY();

    void setFlipY(boolean flipY);

    /** The UV rectangle as a saved design will hold it, for showing. */
    default float[] heldUv() {
        return SurfacePlacement.sanitiseUv(uv());
    }

    /** Back to no offset, no scale, no turn and no mirroring. */
    default void resetPlacement() {
        setPlacement(SurfacePlacement.IDENTITY.clone());
        setFlipX(false);
        setFlipY(false);
    }
}
