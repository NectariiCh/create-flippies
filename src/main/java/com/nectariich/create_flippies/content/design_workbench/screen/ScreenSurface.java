package com.nectariich.create_flippies.content.design_workbench.screen;

import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Set;

import com.nectariich.create_flippies.content.design_workbench.SurfacePlacement;
import com.nectariich.create_flippies.content.display.element.ScreenElement;
import com.nectariich.create_flippies.content.display.element.ScreenElements;
import com.nectariich.create_flippies.content.display.element.Viewport;
import com.nectariich.create_flippies.content.procedural_gauge.SupplyVoltage;
import com.nectariich.create_flippies.foundation.control.ControlFootprint;
import com.nectariich.create_flippies.foundation.grid.PlacementGrid;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.FloatTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;

/**
 * What makes a design on an imported model a screen: which submesh of the model the
 * screen is, where on that submesh the screen's viewport sits, what supply it is
 * rated for, and the layout a newly placed screen starts with.
 *
 * <h2>Where the viewport is</h2>
 *
 * A screen is placed on its submesh exactly as a gauge's face is on its own, with the
 * same numbers ({@link SurfacePlacement}): {@link #uv} is the rectangle of the
 * submesh's UV space the screen fills, and {@link #placement} moves, scales and turns
 * it within that rectangle, mirrored either way by {@link #flipX} and {@link #flipY}.
 * What a gauge's face does with those numbers is make a material; a screen draws
 * elements in front of the submesh instead, so it also needs to know where the UV
 * rectangle is in the model. That is {@link #frame}: the plane the client fitted to
 * the submesh's faces when the design was made (only a client can read the model),
 * as an origin and the change in position per unit of U and per unit of V, in
 * authored pixels. Position is then affine in UV, P = O + u·A + v·B, with V running
 * down the image as everywhere else.
 *
 * The viewport is the UV rectangle's physical rectangle, |A|·du by |B|·dv pixels, then
 * in order: scaled about its centre, offset by the offset times the face size (right
 * and up), mirrored about its centre, and turned clockwise about its centre. The turn
 * is a rigid one in the face's plane, not in texture space, so lettering never
 * shears; it agrees with a gauge's baked turn on a square face and at quarter turns.
 * With no turn and no flip, the viewport covers exactly the part of the rectangle a
 * gauge's face would ({@link #uvAt} against {@link SurfacePlacement#uvTransform}).
 *
 * The viewport is rounded to tenths of a pixel, the unit elements are laid out in
 * (see {@link Viewport}), and everything here measures from that rounded viewport.
 *
 * <h2>The layout</h2>
 *
 * {@link #layout} is the elements the workbench authored, in exactly the format a
 * display keeps them, laid out in this surface's viewport. A placed screen copies it
 * and from then on has its own. Its generations are dropped, since nothing can link
 * to a design, so two designs that look the same write the same tag.
 *
 * Immutable and safe on both sides; {@link #read} sanitises anything a client sends,
 * and the constructor sanitises anything else. The arrays compare by identity, so
 * equality is by {@link #write tag}, as for a gauge's dial.
 */
public final class ScreenSurface {

    public static final String TAG = "ScreenSurface";

    /**
     * How far past its own cell a screen's UV rectangle may reach, in authored pixels:
     * as far as a footprint may. It bounds where the rectangle's corners are, not the
     * frame's numbers: those are per whole UV unit and extrapolated to UV (0, 0), so a
     * screen that fills a corner of its texture has axes and an origin well past this.
     */
    public static final double MAX_REACH = ControlFootprint.REACH_BUDGET;

    /** What a fresh screen is rated for, and what a tag with no rating reads as. */
    public static final SupplyVoltage DEFAULT_SUPPLY = SupplyVoltage.V12;

    private static final int MAX_FACE = 2 * 128 + 1;

    /**
     * The least area, in square pixels per square UV unit, a frame's two axes may span,
     * so a frame fitted to a sliver or to a line is refused rather than giving a
     * viewport of nothing.
     */
    private static final double MIN_AREA = 1e-6;

    /**
     * The frame a screen with no submesh yet has: the whole front, 16 pixels square,
     * a pixel above the plate, x right and z down as the workbench's top view shows it.
     */
    private static final float[] DEFAULT_FRAME = { 0, 1, 0, 16, 0, 0, 0, 0, 16 };

    /** The element key ("part#material") of the submesh that is the screen, or "" for none yet. */
    public final String face;
    private final float[] uv;
    private final float[] placement;
    public final boolean flipX, flipY;
    /** {originX, originY, originZ, dP/du (x, y, z), dP/dv (x, y, z)}, in authored pixels. */
    private final float[] frame;
    public final SupplyVoltage supply;
    /** The layout, as its sanitised tag: what {@link #layout} copies from and {@link #write} writes. */
    private final CompoundTag layout;
    private final Viewport viewport;

    /**
     * @param layout the authored elements, or null for {@link ScreenElements#defaults}; copied,
     *               fitted to this surface's viewport and stripped of its generations
     */
    public ScreenSurface(String face, float[] uv, float[] placement, boolean flipX, boolean flipY, float[] frame,
            SupplyVoltage supply, ScreenElements layout) {
        this.face = face == null || face.length() > MAX_FACE || face.indexOf('#') <= 0
                || face.indexOf('#') == face.length() - 1 ? "" : face;
        this.uv = SurfacePlacement.sanitiseUv(uv);
        this.placement = SurfacePlacement.sanitisePlacement(placement);
        this.flipX = flipX;
        this.flipY = flipY;
        this.frame = sanitiseFrame(frame, this.uv);
        this.supply = supply == null ? DEFAULT_SUPPLY : supply;
        this.viewport = Viewport.ofPixels(faceWidth() * this.placement[2], faceHeight() * this.placement[3]);
        CompoundTag tag = new CompoundTag();
        if (layout != null)
            layout.write(tag);
        tag.remove("Gen");
        // Through the tag, so whatever was handed in is held to exactly what a read
        // would hold it to: ids, kinds, fonts, the text budget, and boxes in this viewport
        ScreenElements held = new ScreenElements();
        held.read(tag, viewport, null);
        this.layout = new CompoundTag();
        held.write(this.layout);
    }

    /** A fresh screen: no submesh yet, the default frame, one fitted line of lettering. */
    public static ScreenSurface defaults() {
        return new ScreenSurface("", null, null, false, false, null, DEFAULT_SUPPLY, null);
    }

    /** Whether a submesh has been given the screen. */
    public boolean hasFace() {
        return !face.isEmpty();
    }

    public float[] uv() {
        return uv.clone();
    }

    public float[] placement() {
        return placement.clone();
    }

    /** Degrees clockwise the viewport is turned about its centre. */
    public float rotation() {
        return placement[4];
    }

    public float[] frame() {
        return frame.clone();
    }

    /**
     * Whether the frame this surface was given was refused and replaced by the
     * placeholder (see {@link #sanitiseFrame}): one that puts the UV rectangle out of
     * reach, as a frame fitted to part of a texture does once the rectangle is the
     * whole texture. Null was asked for when {@code given} is null.
     */
    public boolean refusedFrame(float[] given) {
        return given != null && !Arrays.equals(given, frame);
    }

    /** The authored layout, as a collection of its own. */
    public ScreenElements layout() {
        ScreenElements elements = new ScreenElements();
        copyLayoutInto(elements);
        return elements;
    }

    /**
     * Replaces everything in {@code elements} with the authored layout, in place: how
     * a placed screen starts, since a display's collection is never swapped for another.
     */
    public void copyLayoutInto(ScreenElements elements) {
        elements.read(layout, viewport, null);
    }

    /** This surface with another layout, fitted to its viewport. */
    public ScreenSurface withLayout(ScreenElements layout) {
        return new ScreenSurface(face, uv, placement, flipX, flipY, frame, supply, layout);
    }

    /** The design store hashes of the font files the layout's lettering uses, each once. */
    public Set<String> fontHashes() {
        Set<String> hashes = new LinkedHashSet<>();
        for (ScreenElement element : layout().snapshot()) {
            String hash = ScreenElement.ttfHash(element.font());
            if (hash != null)
                hashes.add(hash);
        }
        return hashes;
    }

    /** The design store hashes of the pictures the layout's SVG elements show, each once. */
    public Set<String> artHashes() {
        Set<String> hashes = new LinkedHashSet<>();
        for (ScreenElement element : layout().snapshot())
            if (!element.art().isEmpty())
                hashes.add(element.art());
        return hashes;
    }

    // ---------------------------------------------------------- the viewport

    /** How wide the UV rectangle is in the model, in pixels: the face before any placement. */
    public double faceWidth() {
        return length(frame[3], frame[4], frame[5]) * (uv[2] - uv[0]);
    }

    /** How tall the UV rectangle is in the model, in pixels. */
    public double faceHeight() {
        return length(frame[6], frame[7], frame[8]) * (uv[3] - uv[1]);
    }

    /** The rectangle elements are laid out in: the face's size times the placement's scale. */
    public Viewport viewport() {
        return viewport;
    }

    /**
     * The point of the face's UV space under a point of the viewport, in pixels from
     * its top left, +x right and +y down. Returned as {u, v}.
     */
    public double[] uvAt(double x, double y) {
        double faceWidth = faceWidth(), faceHeight = faceHeight();
        double lx = x - viewport.widthPixels() / 2, ly = y - viewport.heightPixels() / 2;
        if (flipX)
            lx = -lx;
        if (flipY)
            ly = -ly;
        // Clockwise as seen, which in a frame whose y runs down is the usual rotation
        double angle = Math.toRadians(placement[4]), cos = Math.cos(angle), sin = Math.sin(angle);
        double rx = lx * cos - ly * sin, ry = lx * sin + ly * cos;
        double across = 0.5 + placement[0] + rx / faceWidth;
        double down = 0.5 - placement[1] + ry / faceHeight;
        return new double[] { uv[0] + across * (uv[2] - uv[0]), uv[1] + down * (uv[3] - uv[1]) };
    }

    /** Where a point of the viewport is in the model, in authored pixels. */
    public double[] pointAt(double x, double y) {
        double[] at = uvAt(x, y);
        return new double[] { frame[0] + at[0] * frame[3] + at[1] * frame[6],
                frame[1] + at[0] * frame[4] + at[1] * frame[7], frame[2] + at[0] * frame[5] + at[1] * frame[8] };
    }

    /**
     * The viewport as a frame of authored space, for a renderer to pose by: the
     * position of its top left corner, then how far one pixel right and one pixel
     * down move, each {x, y, z}. Exact, since position is affine in the viewport.
     */
    public double[] viewportFrame() {
        double[] origin = pointAt(0, 0), right = pointAt(1, 0), down = pointAt(0, 1);
        return new double[] { origin[0], origin[1], origin[2], right[0] - origin[0], right[1] - origin[1],
                right[2] - origin[2], down[0] - origin[0], down[1] - origin[1], down[2] - origin[2] };
    }

    /**
     * Whether the viewport, placed, reaches past the UV rectangle it is placed in: the
     * workbench warns about it, since the content then hangs off the face.
     */
    public boolean overhangs() {
        return overhangs(uv);
    }

    /**
     * Whether the viewport, placed, reaches past {@code within}, a UV rectangle
     * {u0, v0, u1, v1}: the part of the texture the glass actually covers, which is
     * less than {@link #uv} once a screen is placed over its whole texture.
     */
    public boolean overhangs(float[] within) {
        double w = viewport.widthPixels(), h = viewport.heightPixels(), slack = 1e-4;
        for (double[] corner : new double[][] { { 0, 0 }, { w, 0 }, { 0, h }, { w, h } }) {
            double[] at = uvAt(corner[0], corner[1]);
            if (at[0] < within[0] - slack || at[0] > within[2] + slack || at[1] < within[1] - slack
                    || at[1] > within[3] + slack)
                return true;
        }
        return false;
    }

    // -------------------------------------------------------------------- nbt

    public CompoundTag write() {
        CompoundTag tag = new CompoundTag();
        if (hasFace())
            tag.putString("Face", face);
        tag.put("Uv", floats(uv));
        tag.put("Placement", floats(placement));
        tag.putBoolean("FlipX", flipX);
        tag.putBoolean("FlipY", flipY);
        tag.put("Frame", floats(frame));
        tag.putString("Supply", supply.getSerializedName());
        tag.put("Layout", layout.copy());
        return tag;
    }

    /**
     * Reads a surface and makes it safe to use, whoever wrote it: a UV rectangle or
     * placement of the wrong length or unusable is the default, as for a gauge; a
     * frame of the wrong length, or holding anything not finite, or whose axes span
     * no area, or that puts the UV rectangle past {@link #MAX_REACH}, is the default
     * frame; an unknown supply is {@link #DEFAULT_SUPPLY}; and a missing
     * layout is the default one.
     */
    public static ScreenSurface read(CompoundTag tag) {
        ScreenElements layout = null;
        if (tag.contains("Layout", Tag.TAG_COMPOUND) && tag.getCompound("Layout").contains("Elements", Tag.TAG_LIST)) {
            // Read loosely here: the constructor fits it to the viewport the rest of
            // the tag gives, which is not known yet
            layout = new ScreenElements();
            layout.read(tag.getCompound("Layout"), new Viewport(Short.MAX_VALUE, Short.MAX_VALUE), null);
        }
        SupplyVoltage supply = DEFAULT_SUPPLY;
        for (SupplyVoltage value : SupplyVoltage.values())
            if (value.getSerializedName().equals(tag.getString("Supply")))
                supply = value;
        return new ScreenSurface(tag.getString("Face"), readFloats(tag.getList("Uv", Tag.TAG_FLOAT), 4),
                readFloats(tag.getList("Placement", Tag.TAG_FLOAT), 5), tag.getBoolean("FlipX"),
                tag.getBoolean("FlipY"), readFloats(tag.getList("Frame", Tag.TAG_FLOAT), 9), supply, layout);
    }

    /**
     * A frame held to something a viewport can be laid on: nine finite values whose two
     * axes are not parallel or of no length, and which put every corner of the
     * (sanitised) UV rectangle {@code uv} within {@link #MAX_REACH} of the cell; the
     * default frame otherwise. Refused whole rather than clamped: the numbers are per
     * UV unit, so clamping any of them would shrink, move or turn a screen that is
     * fine where it actually is, and a frame the workbench fitted to a real submesh
     * is never out of reach, since the model it was fitted to is not.
     */
    static float[] sanitiseFrame(float[] frame, float[] uv) {
        if (frame == null || frame.length != 9)
            return DEFAULT_FRAME.clone();
        float[] held = new float[9];
        for (int i = 0; i < 9; i++) {
            if (!Float.isFinite(frame[i]))
                return DEFAULT_FRAME.clone();
            held[i] = frame[i];
        }
        double low = -MAX_REACH, high = PlacementGrid.PIXELS_PER_BLOCK + MAX_REACH;
        for (double u : new double[] { uv[0], uv[2] })
            for (double v : new double[] { uv[1], uv[3] })
                for (int axis = 0; axis < 3; axis++) {
                    // In doubles, so values near the float limits cannot overflow to a pass
                    double at = (double) held[axis] + u * held[3 + axis] + v * held[6 + axis];
                    if (!(at >= low && at <= high))
                        return DEFAULT_FRAME.clone();
                }
        double cx = held[4] * held[8] - held[5] * held[7];
        double cy = held[5] * held[6] - held[3] * held[8];
        double cz = held[3] * held[7] - held[4] * held[6];
        if (!(length(cx, cy, cz) >= MIN_AREA))
            return DEFAULT_FRAME.clone();
        return held;
    }

    private static double length(double x, double y, double z) {
        return Math.sqrt(x * x + y * y + z * z);
    }

    private static ListTag floats(float[] values) {
        ListTag list = new ListTag();
        for (float value : values)
            list.add(FloatTag.valueOf(value));
        return list;
    }

    /** The list's values when it has exactly {@code count}, or null for the default. */
    private static float[] readFloats(ListTag list, int count) {
        if (list.size() != count)
            return null;
        float[] values = new float[count];
        for (int i = 0; i < count; i++)
            values[i] = list.getFloat(i);
        return values;
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof ScreenSurface surface && write().equals(surface.write());
    }

    @Override
    public int hashCode() {
        return write().hashCode();
    }
}
