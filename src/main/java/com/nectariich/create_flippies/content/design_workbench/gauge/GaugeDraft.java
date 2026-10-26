package com.nectariich.create_flippies.content.design_workbench.gauge;

import java.util.ArrayList;
import java.util.List;

import com.nectariich.create_flippies.content.design_workbench.PlacementDraft;
import com.nectariich.create_flippies.content.procedural_gauge.EdgewiseFaceDefinition;
import com.nectariich.create_flippies.content.procedural_gauge.GaugeFaceDefinition;

/**
 * A gauge being edited in the workbench's gauge mode: the fields of a
 * {@link GaugeDial} as the player edits them, loose and mutable, and built into an
 * immutable one whenever the preview or a save needs it. Both faces are edited
 * through one set of fields, so switching between rotary and edgewise keeps the
 * scale, the labels, the colours and the bands.
 *
 * What the editor does not offer (a dial's tick radii, an edgewise face's tick
 * lengths and label sizes) is kept from the face the draft was made from, so a
 * gauge loaded and saved again loses none of it.
 */
public final class GaugeDraft implements PlacementDraft {

    /** One colour band, in scale values and 0xRRGGBB. */
    public static final class Band {
        public float from, to;
        public int color;

        public Band(float from, float to, int color) {
            this.from = from;
            this.to = to;
            this.color = color;
        }
    }

    public boolean edgewise;
    public GaugeDial.Quantity quantity;

    public float min, max, majorTick, multiplier;
    public int minorTicks, decimals;
    public String name, units;
    public int faceColor, markingColor;

    /** A rotary dial's sweep, in degrees clockwise from 12 o'clock. */
    public float startAngle, endAngle;

    /** An edgewise face's layout. */
    public boolean flipTicks, nameOnSide;

    /**
     * An edgewise face's height over its width, so its printing is not stretched on
     * the submesh it is drawn on; set from that submesh's size when one is picked.
     */
    public float faceAspect;

    public final List<Band> bands = new ArrayList<>();

    /** The submesh the face is drawn on, as an element key, or "" for none; and the UV rectangle it fills. */
    public String face;
    public float[] uv;

    /** Where the face sits within its UV rectangle: {offsetX, offsetY, scaleX, scaleY}. */
    public float[] placement;
    public boolean flipX, flipY;

    /** The needle: a rotary one's modelled angle and direction, an edgewise one's slide or swing. */
    public float needleZero;
    public boolean reverse, slide;

    /** The faces whatever the editor does not offer is kept from. */
    private GaugeFaceDefinition baseDial;
    private EdgewiseFaceDefinition baseEdgewise;

    private GaugeDraft() {}

    public static GaugeDraft of(GaugeDial gauge) {
        GaugeDraft draft = new GaugeDraft();
        draft.edgewise = gauge.isEdgewise();
        draft.quantity = gauge.quantity;
        draft.baseDial = gauge.withKind(false).dial;
        draft.baseEdgewise = gauge.withKind(true).edgewise;
        GaugeFaceDefinition.ColorBand[] bands;
        if (gauge.isEdgewise()) {
            EdgewiseFaceDefinition face = gauge.edgewise;
            draft.scale(face.minValue, face.maxValue, face.majorTickInterval, face.minorTicksPerMajor,
                    face.labelDecimals(), face.labelMultiplier, face.displayName, face.units, face.faceColor,
                    face.markingColor);
            bands = face.bands;
        } else {
            GaugeFaceDefinition face = gauge.dial;
            draft.scale(face.minValue, face.maxValue, face.majorTickInterval, face.minorTicksPerMajor,
                    face.labelDecimals(), face.labelMultiplier, face.displayName, face.units, face.faceColor,
                    face.markingColor);
            bands = face.bands;
        }
        draft.startAngle = draft.baseDial.startAngle;
        draft.endAngle = draft.baseDial.endAngle;
        draft.flipTicks = draft.baseEdgewise.flipTicks;
        draft.nameOnSide = draft.baseEdgewise.nameOnSide;
        draft.faceAspect = draft.baseEdgewise.faceAspect;
        for (GaugeFaceDefinition.ColorBand band : bands)
            draft.bands.add(new Band(band.fromValue(), band.toValue(), band.argb() & 0xFFFFFF));
        draft.face = gauge.face;
        draft.uv = gauge.uv();
        draft.placement = gauge.placement();
        draft.flipX = gauge.flipX;
        draft.flipY = gauge.flipY;
        draft.needleZero = gauge.needleZero;
        draft.reverse = gauge.reverse;
        draft.slide = gauge.slide;
        return draft;
    }

    private void scale(float min, float max, float majorTick, int minorTicks, int decimals, float multiplier,
            String name, String units, int faceColor, int markingColor) {
        this.min = min;
        this.max = max;
        this.majorTick = majorTick;
        this.minorTicks = minorTicks;
        this.decimals = decimals;
        this.multiplier = multiplier;
        this.name = name;
        this.units = units;
        this.faceColor = faceColor & 0xFFFFFF;
        this.markingColor = markingColor & 0xFFFFFF;
    }

    /** Picks pleasant tick spacing and label precision for the range, as the gauge menu's auto scale does. */
    public void autoTicks() {
        GaugeFaceDefinition picked = GaugeFaceDefinition.autoScaled(min, max).build();
        majorTick = picked.majorTickInterval;
        minorTicks = picked.minorTicksPerMajor;
        decimals = picked.labelDecimals();
    }

    public GaugeDial build() {
        String format = GaugeFaceDefinition.formatForDecimals(Math.max(0, Math.min(3, decimals)));
        GaugeFaceDefinition.ColorBand[] built = new GaugeFaceDefinition.ColorBand[Math.min(bands.size(),
                GaugeFaceDefinition.MAX_BANDS)];
        for (int i = 0; i < built.length; i++) {
            Band band = bands.get(i);
            built[i] = new GaugeFaceDefinition.ColorBand(band.from, band.to, 0xFF000000 | band.color);
        }
        if (edgewise) {
            EdgewiseFaceDefinition face = baseEdgewise.toBuilder(min, max)
                    .displayName(name)
                    .units(units)
                    .majorTickInterval(majorTick)
                    .minorTicksPerMajor(minorTicks)
                    .labelFormat(format)
                    .labelMultiplier(multiplier)
                    .faceColor(faceColor)
                    .markingColor(markingColor)
                    .flipTicks(flipTicks)
                    .nameOnSide(nameOnSide)
                    .faceAspect(faceAspect)
                    .bands(built)
                    .build();
            return new GaugeDial(quantity, null, face, face(), uv, placement, flipX, flipY, needleZero, reverse, slide);
        }
        GaugeFaceDefinition face = baseDial.toBuilder(min, max)
                .displayName(name)
                .units(units)
                .majorTickInterval(majorTick)
                .minorTicksPerMajor(Math.max(0, Math.min(20, minorTicks)))
                .labelFormat(format)
                .labelMultiplier(multiplier)
                .faceColor(0xFF000000 | faceColor)
                .markingColor(0xFF000000 | markingColor)
                .sweep(clampAngle(startAngle), clampAngle(endAngle))
                .bands(built)
                .build();
        return new GaugeDial(quantity, face, null, face(), uv, placement, flipX, flipY, needleZero, reverse, slide);
    }

    // ------------------------------------------------- where the face is printed

    @Override
    public String face() {
        return face == null ? "" : face;
    }

    @Override
    public void setFace(String face) {
        this.face = face;
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

    /** Held to the turn and a bit either way that a saved dial is read back with. */
    private static float clampAngle(float degrees) {
        return Float.isFinite(degrees) ? Math.max(-330, Math.min(330, degrees)) : 0;
    }
}
