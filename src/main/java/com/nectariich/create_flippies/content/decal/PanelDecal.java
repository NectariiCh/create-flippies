package com.nectariich.create_flippies.content.decal;

import java.util.List;

import com.nectariich.create_flippies.content.design_workbench.ControlDesign;
import com.nectariich.create_flippies.foundation.panel.PanelDefinition;
import com.nectariich.create_flippies.foundation.panel.PanelPlane;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.phys.Vec3;

/**
 * One picture painted onto a panel: which picture, which surface, and the
 * rectangle of it the picture fills.
 *
 * <h2>Where it is kept</h2>
 *
 * In every panel cell the rectangle crosses, identically, which is the rule a
 * {@link PanelDefinition} follows and for the same reasons: there is no store to
 * keep in step with the blocks, and what a cell draws is decided by what that cell
 * holds. Paint does not belong to a panel so much as to the plate it is on, so it
 * is held beside the panels rather than inside their definitions. A definition is
 * what every cell of a panel has to agree on to the bit, and making paint part of
 * it would have every brush stroke rewrite the whole panel and every edit of the
 * panel carry the paint along by hand.
 *
 * What follows from keeping it per cell is the behaviour paint ought to have. A
 * tile unscrewed out of the panel takes its share of the picture with it, a hole
 * drilled through the picture shows as a hole, and breaking the panel leaves
 * nothing behind. Paint that runs past the edge of the plate is simply not drawn
 * there: every cell clips the picture to the plate it actually holds.
 *
 * <h2>Which side</h2>
 *
 * Either face of a plate can be painted. The front is the panel's own plane at no
 * depth; the back is the same plane turned round, a plate's thickness further on,
 * which is exactly how {@code ScrewdriverAim.Surface} states the two and so how
 * the click arrives. {@link #cellPlane} gives back the panel's own plane for both,
 * which is what decides which cells the paint is in and which plates it sits on.
 *
 * <h2>Which way up</h2>
 *
 * A picture is drawn the right way round as seen from the side it is painted on.
 * The in-plane axes come from the plane and not from which way it faces, so the
 * back of a plate sees u running the wrong way; {@link #rightSign} is how the
 * mapping finds that out rather than painting a mirror image.
 *
 * @param id       the same in every cell, which is how one picture is found again to be taken off
 * @param art      the picture, by the hash it is stored under
 * @param plane    the surface painted, facing the side the paint is on
 * @param depth    how far that surface is from the plane, in offset units: nought, or a plate's thickness
 * @param quarters clockwise quarter turns of the picture, as its viewer sees it
 * @param tint     opaque ARGB to paint every part of the picture in, or {@link #OWN_COLOURS}
 */
public record PanelDecal(long id, String art, PanelPlane plane, int depth, int minU, int minV, int maxU, int maxV,
        int quarters, int tint) {

    /** The tint that is no tint: the picture keeps the colours it was drawn in. */
    public static final int OWN_COLOURS = 0;

    /** The most one cell holds; past this the paint would cost more to rebuild than it is worth. */
    public static final int MAX_PER_CELL = 32;

    /** The most blocks one picture may be painted across. */
    public static final int MAX_CELLS = 1024;

    /**
     * The smallest a picture may be painted, in offset units along its short side: a
     * pixel. Smaller than that and nothing of it can be told apart.
     */
    public static final int MIN_SIZE = PanelPlane.UNITS_PER_PIXEL;

    /** Why a rectangle could not be painted, or {@link #NONE}. */
    public enum Problem {
        NONE,
        TOO_SMALL,
        TOO_LARGE,
        TOO_MANY_CELLS;

        public boolean ok() {
            return this == NONE;
        }

        public String key() {
            return name().toLowerCase(java.util.Locale.ROOT);
        }
    }

    /** The plane of the panel this paint is on, whichever side of it the paint is. */
    public PanelPlane cellPlane() {
        return depth == 0 ? plane : plane.flipped();
    }

    /** Whether the paint is on this surface, as {@code ScrewdriverAim.Surface} names one. */
    public boolean isOn(PanelPlane surface, double depthBlocks) {
        return plane.equals(surface)
                && Math.abs(depth - depthBlocks * PanelPlane.UNITS_PER_BLOCK) < 0.5;
    }

    /** Whether a point of the surface, in blocks, is within the painted rectangle. */
    public boolean holds(double u, double v) {
        double scale = PanelPlane.UNITS_PER_BLOCK;
        return u >= minU / scale && u <= maxU / scale && v >= minV / scale && v <= maxV / scale;
    }

    /** The cells the rectangle crosses, which are the cells that hold it. */
    public List<BlockPos> cells() {
        return area().cells();
    }

    public Problem check() {
        int spanU = maxU - minU;
        int spanV = maxV - minV;
        if (Math.min(spanU, spanV) < MIN_SIZE)
            return Problem.TOO_SMALL;
        if (spanU > PanelDefinition.MAX_SPAN || spanV > PanelDefinition.MAX_SPAN)
            return Problem.TOO_LARGE;
        return area().cellCount() > MAX_CELLS ? Problem.TOO_MANY_CELLS : Problem.NONE;
    }

    /**
     * The rectangle as a panel of its own, which is only ever walked and never built.
     * A panel and its paint cover the same cells because both are a rectangle on the
     * same plane with the same plate behind it, so the walk panels already have is
     * the right one here too.
     */
    private PanelDefinition area() {
        return new PanelDefinition(cellPlane(), minU, minV, maxU, maxV);
    }

    /**
     * The largest rectangle a picture of that shape fills inside the one drawn, centred
     * in it, in offset units as {@code minU, minV, maxU, maxV}.
     *
     * The picture keeps its proportions rather than being stretched to whatever was
     * drawn, which is what anyone painting a logo expects; the two corners only say
     * how much room it has. A quarter turn swaps which of its sides runs along u.
     */
    public static int[] fit(int uA, int vA, int uB, int vB, float artWidth, float artHeight, int quarters) {
        int minU = Math.min(uA, uB);
        int minV = Math.min(vA, vB);
        int spanU = Math.abs(uB - uA);
        int spanV = Math.abs(vB - vA);
        boolean turned = (quarters & 1) != 0;
        double aspect = turned ? artHeight / (double) artWidth : artWidth / (double) artHeight;
        int width = spanU;
        int height = spanV;
        if (spanV * aspect > spanU)
            height = (int) Math.round(spanU / aspect);
        else
            width = (int) Math.round(spanV * aspect);
        int left = minU + (spanU - width) / 2;
        int bottom = minV + (spanV - height) / 2;
        return new int[] { left, bottom, left + width, bottom + height };
    }

    /**
     * Whether the picture's right runs along u (one) or against it (minus one), as seen
     * from the side it is painted on.
     *
     * The viewer's right is up crossed with the direction toward them, and up is v on
     * every plane that has an up. A floor has none, so v stands in for it there, and
     * the quarter turn is how the player sets a floor picture the way they want it.
     */
    public int rightSign() {
        Vec3 right = plane.tangentV().cross(plane.outward());
        return right.dot(plane.tangentU()) >= 0 ? 1 : -1;
    }

    /**
     * Where a point of the picture lands on the surface, as {@code u, v} in blocks.
     *
     * {@code x} and {@code y} are fractions of the picture's box, across and down as
     * the picture has them; the turn is applied first, then the result is laid out
     * along the viewer's right and down.
     */
    public void map(double x, double y, double[] into) {
        double across;
        double down;
        switch (quarters & 3) {
            case 1 -> {
                across = 1 - y;
                down = x;
            }
            case 2 -> {
                across = 1 - x;
                down = 1 - y;
            }
            case 3 -> {
                across = y;
                down = 1 - x;
            }
            default -> {
                across = x;
                down = y;
            }
        }
        double scale = PanelPlane.UNITS_PER_BLOCK;
        double width = (maxU - minU) / scale;
        double height = (maxV - minV) / scale;
        into[0] = rightSign() > 0 ? minU / scale + across * width : maxU / scale - across * width;
        into[1] = maxV / scale - down * height;
    }

    /** The paint drawn in a single colour instead of its own. */
    public boolean tinted() {
        return tint != OWN_COLOURS;
    }

    public CompoundTag save() {
        CompoundTag tag = new CompoundTag();
        tag.putLong("Id", id);
        tag.putString("Art", art);
        plane.save(tag);
        tag.putInt("Depth", depth);
        tag.putInt("MinU", minU);
        tag.putInt("MinV", minV);
        tag.putInt("MaxU", maxU);
        tag.putInt("MaxV", maxV);
        tag.putByte("Quarters", (byte) quarters);
        tag.putInt("Tint", tint);
        return tag;
    }

    /** Null when the tag does not hold paint this build can draw, which is how a damaged save is survived. */
    public static PanelDecal load(CompoundTag tag) {
        PanelPlane plane = PanelPlane.load(tag);
        String art = tag.getString("Art");
        if (plane == null || !plane.check().ok() || !ControlDesign.isHash(art) || !tag.contains("MaxV"))
            return null;
        int depth = tag.getInt("Depth") == 0 ? 0 : PanelDefinition.THICKNESS;
        int tint = tag.getInt("Tint");
        return new PanelDecal(tag.getLong("Id"), art, plane, depth, tag.getInt("MinU"), tag.getInt("MinV"),
                tag.getInt("MaxU"), tag.getInt("MaxV"), tag.getByte("Quarters") & 3,
                tint == OWN_COLOURS ? OWN_COLOURS : tint | 0xFF000000);
    }
}
