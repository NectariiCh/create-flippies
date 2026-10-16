package com.nectariich.create_flippies.foundation.panel;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.FriendlyByteBuf;

/**
 * A rectangle of a panel that is not there: a tile taken out of it, or a hole
 * drilled through it.
 *
 * It is stated in the panel's own plane coordinates, in
 * {@link PanelPlane#UNITS_PER_BLOCK}ths and absolute along the plane rather than
 * relative to the panel — the same frame {@link PanelDefinition} states its own
 * edges in. That is what lets a panel be extended or shortened without touching its
 * holes: the wall moves and the cable pass-through stays where it was drilled,
 * because neither ever knew about the other.
 *
 * <h2>Why a list of rectangles rather than a mask</h2>
 *
 * Because what the player does to a panel is rectangular, and because the geometry
 * downstream is. A panel with holes is drawn and collided with as the union of
 * whatever rectangles are left over — see {@link PanelDefinition#pieces} — so
 * holding the holes as rectangles means the leftovers come out as rectangles too,
 * by a coordinate sweep rather than by marching a bitmap. A mask would have had to
 * pick a resolution, and the two resolutions that matter here differ by sixteen: a
 * tile is a block and a drilled hole is a pixel.
 *
 * Cuts never overlap the outside of their panel — one is clipped to the rectangle
 * when it is added and again when the panel is shortened — so a cut list is always
 * a description of the same panel it is attached to.
 */
public record PanelCut(int minU, int minV, int maxU, int maxV) {

    /** The rectangle between two corners, in either order. */
    public static PanelCut between(int uA, int vA, int uB, int vB) {
        return new PanelCut(Math.min(uA, uB), Math.min(vA, vB), Math.max(uA, uB), Math.max(vA, vB));
    }

    /** Whether this rectangle has nothing in it, which is what a clip can leave behind. */
    public boolean isEmpty() {
        return maxU <= minU || maxV <= minV;
    }

    /** This rectangle cut down to another, or null if the two do not meet. */
    public PanelCut clippedTo(int lowU, int lowV, int highU, int highV) {
        PanelCut clipped = new PanelCut(Math.max(minU, lowU), Math.max(minV, lowV), Math.min(maxU, highU),
                Math.min(maxV, highV));
        return clipped.isEmpty() ? null : clipped;
    }

    /** The same, cut down to a panel's own rectangle. */
    public PanelCut clippedTo(PanelDefinition panel) {
        return clippedTo(panel.minU(), panel.minV(), panel.maxU(), panel.maxV());
    }

    /** Whether a point on the plane, in plane units, is inside this rectangle. */
    public boolean holds(double u, double v) {
        return u >= minU && u <= maxU && v >= minV && v <= maxV;
    }

    /** Whether this rectangle contains the whole of another. */
    public boolean covers(PanelCut other) {
        return minU <= other.minU && minV <= other.minV && maxU >= other.maxU && maxV >= other.maxV;
    }

    /** Whether the two share any area at all; touching along an edge does not count. */
    public boolean overlaps(PanelCut other) {
        return Math.min(maxU, other.maxU) > Math.max(minU, other.minU)
                && Math.min(maxV, other.maxV) > Math.max(minV, other.minV);
    }

    /**
     * The two rectangles as one, or null where their union is not a rectangle.
     *
     * Used when a cut is added, so that taking out a row of tiles one click at a time
     * leaves one cut rather than a row of them. Nothing depends on it — the geometry
     * is the same either way — but everything downstream is quadratic in how many
     * cuts a panel carries, and a wall someone has opened a doorway in should not
     * cost more than a wall with a doorway drawn in one go.
     */
    public PanelCut union(PanelCut other) {
        if (minU == other.minU && maxU == other.maxU && meets(minV, maxV, other.minV, other.maxV))
            return new PanelCut(minU, Math.min(minV, other.minV), maxU, Math.max(maxV, other.maxV));
        if (minV == other.minV && maxV == other.maxV && meets(minU, maxU, other.minU, other.maxU))
            return new PanelCut(Math.min(minU, other.minU), minV, Math.max(maxU, other.maxU), maxV);
        return null;
    }

    /** Whether two intervals overlap or touch, which is what lets two rectangles join. */
    private static boolean meets(int lowA, int highA, int lowB, int highB) {
        return Math.min(highA, highB) >= Math.max(lowA, lowB);
    }

    /**
     * What is left of this rectangle once another is taken out of it: nothing, itself,
     * or up to four rectangles around the overlap.
     *
     * Cut into a band below, a band above and then the two sides of what is left, so
     * that the pieces never overlap and the wide ones come out whole. A restored tile
     * in the middle of a drilled slot goes this way, which is the only thing that asks
     * for it.
     */
    public List<PanelCut> minus(PanelCut other) {
        if (!overlaps(other))
            return List.of(this);
        int lowU = Math.max(minU, other.minU);
        int highU = Math.min(maxU, other.maxU);
        int lowV = Math.max(minV, other.minV);
        int highV = Math.min(maxV, other.maxV);

        List<PanelCut> left = new ArrayList<>(4);
        if (minV < lowV)
            left.add(new PanelCut(minU, minV, maxU, lowV));
        if (highV < maxV)
            left.add(new PanelCut(minU, highV, maxU, maxV));
        if (minU < lowU)
            left.add(new PanelCut(minU, lowV, lowU, highV));
        if (highU < maxU)
            left.add(new PanelCut(highU, lowV, maxU, highV));
        return left;
    }

    public CompoundTag save() {
        CompoundTag tag = new CompoundTag();
        tag.putInt("MinU", minU);
        tag.putInt("MinV", minV);
        tag.putInt("MaxU", maxU);
        tag.putInt("MaxV", maxV);
        return tag;
    }

    /** Null where the tag holds no rectangle, which is how a truncated save is survived. */
    public static PanelCut load(CompoundTag tag) {
        if (!tag.contains("MinU"))
            return null;
        PanelCut cut = new PanelCut(tag.getInt("MinU"), tag.getInt("MinV"), tag.getInt("MaxU"), tag.getInt("MaxV"));
        return cut.isEmpty() ? null : cut;
    }

    public void write(FriendlyByteBuf buffer) {
        buffer.writeVarInt(minU);
        buffer.writeVarInt(minV);
        buffer.writeVarInt(maxU);
        buffer.writeVarInt(maxV);
    }

    public static PanelCut read(FriendlyByteBuf buffer) {
        return new PanelCut(buffer.readVarInt(), buffer.readVarInt(), buffer.readVarInt(), buffer.readVarInt());
    }
}
