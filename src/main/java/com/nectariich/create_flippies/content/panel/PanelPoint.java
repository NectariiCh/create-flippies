package com.nectariich.create_flippies.content.panel;

import com.nectariich.create_flippies.foundation.panel.PanelPlane;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.phys.Vec3;

/**
 * A corner of a dynamic block the player has picked: a point in the world, snapped
 * to the pixel, and nothing else.
 *
 * Deliberately not a point <i>on a plane</i>. The plane is not known when the first
 * corner is picked — it is the second click that decides which of the three planes
 * through this point the panel lies in — so a corner that carried a plane would be
 * carrying a guess. Storing only the point also makes the first click cheap to
 * describe: wherever the crosshair was, rounded.
 *
 * The unit is {@link PanelPlane#UNITS_PER_BLOCK}ths of a block, and the value is
 * always a whole number of pixels. Integers matter here for the same reason they
 * matter to a plane offset: the plane the second click chooses is built through
 * this point, and its offset is this point's coordinates read straight off, with no
 * rounding anywhere. So the corner the player picked is exactly the corner the panel
 * gets.
 */
public record PanelPoint(int x, int y, int z) {

    private static final String X = "X";
    private static final String Y = "Y";
    private static final String Z = "Z";

    /** The corner a click at {@code point} picks: the nearest pixel line on each axis. */
    public static PanelPoint snapped(Vec3 point) {
        return new PanelPoint(snap(point.x), snap(point.y), snap(point.z));
    }

    public Vec3 toVec() {
        double scale = PanelPlane.UNITS_PER_BLOCK;
        return new Vec3(x / scale, y / scale, z / scale);
    }

    /**
     * This point dotted with a lattice normal, in the same units — which is exactly
     * the offset of the plane of that normal through this point, with nothing
     * rounded away.
     */
    public int latticeDot(int[] normal) {
        return normal[0] * x + normal[1] * y + normal[2] * z;
    }

    private static int snap(double blocks) {
        int perPixel = PanelPlane.UNITS_PER_PIXEL;
        return (int) Math.round(blocks * PanelPlane.UNITS_PER_BLOCK / perPixel) * perPixel;
    }

    public CompoundTag save() {
        CompoundTag tag = new CompoundTag();
        tag.putInt(X, x);
        tag.putInt(Y, y);
        tag.putInt(Z, z);
        return tag;
    }

    /** Null when the tag holds no point, which is how "none picked yet" reads. */
    public static PanelPoint load(CompoundTag tag) {
        if (!tag.contains(X))
            return null;
        return new PanelPoint(tag.getInt(X), tag.getInt(Y), tag.getInt(Z));
    }
}
