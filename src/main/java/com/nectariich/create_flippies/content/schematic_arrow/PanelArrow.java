package com.nectariich.create_flippies.content.schematic_arrow;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;

import com.nectariich.create_flippies.content.decal.PanelDecal;
import com.nectariich.create_flippies.content.design_workbench.ControlDesign;
import com.nectariich.create_flippies.foundation.panel.PanelDefinition;
import com.nectariich.create_flippies.foundation.panel.PanelPlane;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.IntArrayTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.FriendlyByteBuf;

/**
 * One schematic arrow raised off a panel: a flat shape, drawn in game as a polyline
 * or imported from an SVG, standing a little proud of the plate with a coloured top
 * and sides, like the mimic bus strips on a real control panel.
 *
 * It is kept exactly the way paint is (see {@link PanelDecal}): in every panel cell
 * its bounding rectangle crosses, identically, on the surface and side it was put on.
 * So it goes with the plate, a tile unscrewed takes its share, breaking the panel
 * leaves nothing behind, and every cell draws only its own part.
 *
 * <h2>Two kinds of shape</h2>
 *
 * A <em>drawn</em> arrow holds its polyline, in offset units on the surface, and its
 * outline is worked out from that and the {@link ArrowStyle} wherever it is wanted
 * ({@link ArrowOutline}). A <em>stamped</em> one holds the hash of an imported
 * picture and the rectangle and turn it was fitted into, the same three things a
 * decal holds, and is the picture's own filled shapes raised. Exactly one of
 * {@link #points} and {@link #art} is non-empty.
 *
 * @param depth    nought for the front of the plate, the plate's thickness for its back
 * @param points   u and v of each point of a drawn arrow, interleaved; empty for a stamped one
 * @param art      the picture of a stamped arrow, by hash; empty for a drawn one
 * @param quarters clockwise quarter turns of a stamped picture, as its viewer sees it
 */
public record PanelArrow(long id, PanelPlane plane, int depth, ArrowStyle style, int[] points, String art,
        int minU, int minV, int maxU, int maxV, int quarters) {

    /** The most arrows one cell holds, the same budget as paint. */
    public static final int MAX_PER_CELL = 32;

    /** The most blocks one arrow may cross. */
    public static final int MAX_CELLS = PanelDecal.MAX_CELLS;

    /** The most points a drawn arrow may have. */
    public static final int MAX_POINTS = 64;

    /** Why an arrow could not be put on, or {@link #NONE}. */
    public enum Problem {
        NONE,
        TOO_SHORT,
        TOO_MANY_POINTS,
        TOO_LARGE,
        TOO_MANY_CELLS;

        public boolean ok() {
            return this == NONE;
        }

        public String key() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    public PanelArrow {
        points = points == null ? new int[0] : points;
        art = art == null ? "" : art;
    }

    public static PanelArrow drawn(long id, PanelPlane plane, int depth, ArrowStyle style, int[] points) {
        return new PanelArrow(id, plane, depth, style, points.clone(), "", 0, 0, 0, 0, 0);
    }

    public static PanelArrow stamped(long id, PanelPlane plane, int depth, ArrowStyle style, String art,
            int[] rectangle, int quarters) {
        return new PanelArrow(id, plane, depth, style, new int[0], art, rectangle[0], rectangle[1], rectangle[2],
                rectangle[3], quarters & 3);
    }

    public boolean isStamped() {
        return !art.isEmpty();
    }

    public int pointCount() {
        return points.length / 2;
    }

    /** The plane of the panel this arrow is on, whichever side of it the arrow is. */
    public PanelPlane cellPlane() {
        return depth == 0 ? plane : plane.flipped();
    }

    /** Whether the arrow is on this surface, as {@code ScrewdriverAim.Surface} names one. */
    public boolean isOn(PanelPlane surface, double depthBlocks) {
        return plane.equals(surface) && Math.abs(depth - depthBlocks * PanelPlane.UNITS_PER_BLOCK) < 0.5;
    }

    /**
     * A rectangle, in offset units as {@code minU, minV, maxU, maxV}, that holds the
     * whole arrow: never short, sometimes a little generous for a drawn one, which
     * at worst puts it in a cell that then draws none of it.
     */
    public int[] bounds() {
        if (isStamped())
            return new int[] { minU, minV, maxU, maxV };
        if (points.length < 2)
            return new int[] { 0, 0, 0, 0 };
        int reach = (int) Math.ceil(ArrowOutline.reach(style.width(), style.headSize()));
        long lowU = Long.MAX_VALUE;
        long lowV = Long.MAX_VALUE;
        long highU = Long.MIN_VALUE;
        long highV = Long.MIN_VALUE;
        for (int i = 0; i + 1 < points.length; i += 2) {
            lowU = Math.min(lowU, points[i]);
            highU = Math.max(highU, points[i]);
            lowV = Math.min(lowV, points[i + 1]);
            highV = Math.max(highV, points[i + 1]);
        }
        return new int[] { (int) (lowU - reach), (int) (lowV - reach), (int) (highU + reach), (int) (highV + reach) };
    }

    /** The cells the bounds cross, which are the cells that hold it. */
    public List<BlockPos> cells() {
        return area().cells();
    }

    public Problem check() {
        if (isStamped()) {
            if (!ControlDesign.isHash(art) || Math.min(maxU - minU, maxV - minV) < PanelDecal.MIN_SIZE)
                return Problem.TOO_SHORT;
        } else {
            if (points.length % 2 != 0 || points.length > 2 * MAX_POINTS)
                return Problem.TOO_MANY_POINTS;
            if (!ArrowOutline.fits(doubles(), style.width(), style.start(), style.end(),
                    style.headSize()))
                return Problem.TOO_SHORT;
        }
        int[] bounds = bounds();
        if ((long) bounds[2] - bounds[0] > PanelDefinition.MAX_SPAN
                || (long) bounds[3] - bounds[1] > PanelDefinition.MAX_SPAN)
            return Problem.TOO_LARGE;
        return area().cellCount() > MAX_CELLS ? Problem.TOO_MANY_CELLS : Problem.NONE;
    }

    private PanelDefinition area() {
        int[] bounds = bounds();
        return new PanelDefinition(cellPlane(), bounds[0], bounds[1], bounds[2], bounds[3]);
    }

    /** The outline of a drawn arrow, in offset units; empty for a stamped one. */
    public ArrowOutline.Shape outline() {
        return isStamped() ? ArrowOutline.Shape.NONE : ArrowOutline.of(points, style);
    }

    /**
     * Whether a point of the surface, in blocks, is on the arrow: inside the drawn
     * outline, or inside the rectangle a picture was fitted to.
     */
    public boolean holds(double u, double v) {
        double scale = PanelPlane.UNITS_PER_BLOCK;
        if (isStamped())
            return u >= minU / scale && u <= maxU / scale && v >= minV / scale && v <= maxV / scale;
        int[] bounds = bounds();
        if (u < bounds[0] / scale || u > bounds[2] / scale || v < bounds[1] / scale || v > bounds[3] / scale)
            return false;
        return ArrowOutline.contains(outline().rings(), u * scale, v * scale);
    }

    /** The same arrow slid along its surface by whole offset units. */
    public PanelArrow moved(int du, int dv) {
        int[] shifted = points.clone();
        for (int i = 0; i + 1 < shifted.length; i += 2) {
            shifted[i] += du;
            shifted[i + 1] += dv;
        }
        return new PanelArrow(id, plane, depth, style, shifted, art, isStamped() ? minU + du : 0,
                isStamped() ? minV + dv : 0, isStamped() ? maxU + du : 0, isStamped() ? maxV + dv : 0, quarters);
    }

    /**
     * A stamped arrow's picture as paint would lay it, which is what maps a point of
     * the picture onto the surface: the same rectangle, turn and handedness. Never
     * drawn as paint.
     */
    public PanelDecal picture() {
        return new PanelDecal(id, art, plane, depth, minU, minV, maxU, maxV, quarters, PanelDecal.OWN_COLOURS);
    }

    public double[] doubles() {
        double[] p = new double[points.length];
        for (int i = 0; i < points.length; i++)
            p[i] = points[i];
        return p;
    }

    public CompoundTag save() {
        CompoundTag tag = new CompoundTag();
        tag.putLong("Id", id);
        plane.save(tag);
        tag.putInt("Depth", depth);
        CompoundTag look = new CompoundTag();
        style.save(look);
        tag.put("Style", look);
        if (isStamped()) {
            tag.putString("Art", art);
            tag.putInt("MinU", minU);
            tag.putInt("MinV", minV);
            tag.putInt("MaxU", maxU);
            tag.putInt("MaxV", maxV);
            tag.putByte("Quarters", (byte) quarters);
        } else {
            tag.put("Points", new IntArrayTag(points));
        }
        return tag;
    }

    /** Null when the tag does not hold an arrow this build can draw, which is how a damaged save is survived. */
    public static PanelArrow load(CompoundTag tag) {
        PanelPlane plane = PanelPlane.load(tag);
        if (plane == null || !plane.check().ok())
            return null;
        int depth = tag.getInt("Depth") == 0 ? 0 : PanelDefinition.THICKNESS;
        ArrowStyle style = ArrowStyle.load(tag.getCompound("Style"));
        long id = tag.getLong("Id");
        if (tag.contains("Art", Tag.TAG_STRING)) {
            String art = tag.getString("Art");
            if (!ControlDesign.isHash(art))
                return null;
            return new PanelArrow(id, plane, depth, style, new int[0], art, tag.getInt("MinU"), tag.getInt("MinV"),
                    tag.getInt("MaxU"), tag.getInt("MaxV"), tag.getByte("Quarters") & 3);
        }
        int[] points = tag.getIntArray("Points");
        if (points.length < 4 || points.length % 2 != 0 || points.length > 2 * MAX_POINTS)
            return null;
        return new PanelArrow(id, plane, depth, style, points, "", 0, 0, 0, 0, 0);
    }

    public void write(FriendlyByteBuf buffer) {
        buffer.writeNbt(save());
    }

    /** Null for anything {@link #load} would turn away. */
    public static PanelArrow read(FriendlyByteBuf buffer) {
        CompoundTag tag = buffer.readNbt();
        return tag == null ? null : load(tag);
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof PanelArrow arrow && id == arrow.id && depth == arrow.depth
                && minU == arrow.minU && minV == arrow.minV && maxU == arrow.maxU && maxV == arrow.maxV
                && quarters == arrow.quarters && plane.equals(arrow.plane) && style.equals(arrow.style)
                && art.equals(arrow.art) && Arrays.equals(points, arrow.points);
    }

    @Override
    public int hashCode() {
        int hash = Long.hashCode(id);
        hash = 31 * hash + plane.hashCode();
        hash = 31 * hash + depth;
        hash = 31 * hash + style.hashCode();
        hash = 31 * hash + Arrays.hashCode(points);
        hash = 31 * hash + art.hashCode();
        hash = 31 * hash + minU;
        hash = 31 * hash + minV;
        hash = 31 * hash + maxU;
        hash = 31 * hash + maxV;
        return 31 * hash + quarters;
    }

    @Override
    public String toString() {
        return "PanelArrow[" + id + ", " + plane + ", " + (isStamped() ? art : pointCount() + " points") + "]";
    }
}
