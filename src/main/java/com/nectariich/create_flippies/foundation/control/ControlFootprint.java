package com.nectariich.create_flippies.foundation.control;

import java.util.ArrayList;
import java.util.List;

import com.nectariich.create_flippies.foundation.IProtrudingHitbox;
import com.nectariich.create_flippies.foundation.ShapeUnion;
import com.nectariich.create_flippies.foundation.grid.PlacementGrid;

import net.minecraft.core.Direction.Axis;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * Every scrap of space one kind of control takes up, as authored pixel boxes in
 * the {@link com.nectariich.create_flippies.foundation.SurfaceMount} frame.
 *
 * This is what decides whether two controls may share a block, so it has to be
 * the real occupied volume and not the drawn one. Two things are easy to leave
 * out and both make the panel feel broken rather than crowded:
 *
 * <ul>
 * <li>Anything that moves has to be given its swept volume, not its pose. A
 * rotary switch handle at rest looks like a thin arm; over the turn of the switch
 * it sweeps a disc, and a neighbour placed in the gap beside the resting arm would
 * be struck by it. A square about the mounting axis is used rather than the disc
 * itself, and it is exactly invariant under the quarter turns a mount can apply,
 * so the same envelope is occupied at every rotation.</li>
 * <li>Whatever passes through the panel — the contact rack, the terminals on it —
 * occupies the cell on the far side, where the next panel's controls may be
 * reaching back through from the other direction. Nothing about the boxes
 * themselves says which cell they end up in, so the volumes are simply reported in
 * the host's coordinates and may lie outside it, and the caller compares them
 * across cells.</li>
 * </ul>
 *
 * The footprint is per kind rather than per instance: a mount only moves it.
 *
 * The one limit below is not a preference. Geometry outside a block's own cell is
 * invisible to vanilla ray tracing and only becomes pickable through
 * {@link IProtrudingHitbox}, whose picker tests the cells around each cell the ray
 * passes through. Half a block is what it tests out to, and also what the server
 * will allow: it refuses an interaction further than that from the face of the cell
 * it thinks was clicked. So a footprint may reach that far out of its cell along
 * every axis at once, and no further along any of them.
 *
 * A footprint used to be barred from leaving its cell along more than one axis,
 * because the picker only tested the six face-adjacent cells and a corner in a
 * diagonal cell would have been unpickable with no error anywhere. That is what
 * stopped a control being mounted at the edge of a face or across two panel
 * blocks, since every control already spends one axis reaching through the panel.
 * The picker tests the whole surrounding neighbourhood now, so the rule is gone
 * along with it, and what a control may not leave is instead the face or the grid
 * it was placed within — a question only the placement path can answer.
 */
public record ControlFootprint(double[][] boxes) {

    /**
     * How far, in pixels, a footprint may reach out of its host cell along the one
     * axis it is allowed to. The rotary switch's rear rack already spends 7.5 of
     * it, so in practice nothing may be both deep and wide.
     */
    public static final double REACH_BUDGET = PlacementGrid.PIXELS_PER_BLOCK / 2.0;

    private static final double PIXEL = 1.0 / PlacementGrid.PIXELS_PER_BLOCK;

    /**
     * A square envelope about the mounting axis, covering everything a part
     * sweeping about that axis can reach. Authored in the mounted frame, so the
     * axis is y and the square is in x and z.
     */
    public static double[] sweptAboutAxis(double radius, double minY, double maxY) {
        double half = PlacementGrid.PIXELS_PER_BLOCK / 2.0;
        return new double[] { half - radius, minY, half - radius, half + radius, maxY, half + radius };
    }

    /** The occupied volumes in block units, relative to the origin of the host cell. */
    public AABB[] volumes(ControlMount mount) {
        AABB[] volumes = new AABB[boxes.length];
        for (int i = 0; i < boxes.length; i++) {
            double[] b = mount.box(boxes[i]);
            volumes[i] = new AABB(b[0] * PIXEL, b[1] * PIXEL, b[2] * PIXEL, b[3] * PIXEL, b[4] * PIXEL, b[5] * PIXEL);
        }
        return volumes;
    }

    /**
     * The same volumes as an outline shape, for picking and for the block's own shape.
     *
     * Left unoptimised, because a mounted control's shape is never handed to anything
     * outside: {@link ControlRack#shape} joins every control in the cell into the one
     * shape the block answers with, and optimising each of them first is work that
     * join immediately throws away. See {@link ShapeUnion}.
     */
    public VoxelShape shape(ControlMount mount) {
        List<VoxelShape> parts = new ArrayList<>(boxes.length);
        for (double[] authored : boxes) {
            double[] b = mount.box(authored);
            parts.add(Block.box(b[0], b[1], b[2], b[3], b[4], b[5]));
        }
        return ShapeUnion.raw(parts);
    }

    /**
     * The interval this footprint occupies along a direction, in blocks, as
     * {@code min, max}.
     *
     * Every corner of every authored box is turned and projected, rather than the
     * bounding box being projected, and on a chamfer the difference is threefold.
     * A box drawn round a turned control is larger than the control by up to root
     * two, and projecting that box onto an axis which is itself turned inflates it
     * again — a switch four and a half pixels across measures thirteen and a half
     * that way, which refuses every ruling finer than one cell per block.
     *
     * That is not a fault in {@link #bounds}, which is right for what it is for:
     * a hitbox really is the axis-aligned box, because that is all a
     * {@code VoxelShape} can hold, and how far one reaches out of its cell is
     * genuinely a question about the box. This is for the other question — whether
     * the control fits the rectangle it was aimed into — where what matters is the
     * room the control actually takes on the surface.
     */
    public double[] range(ControlMount mount, Vec3 along) {
        double low = Double.MAX_VALUE;
        double high = -Double.MAX_VALUE;
        for (double[] authored : boxes)
            for (int corner = 0; corner < 8; corner++) {
                double[] point = mount.point(authored[(corner & 1) * 3], authored[((corner >> 1) & 1) * 3 + 1],
                        authored[((corner >> 2) & 1) * 3 + 2]);
                double at = (point[0] * along.x + point[1] * along.y + point[2] * along.z) * PIXEL;
                low = Math.min(low, at);
                high = Math.max(high, at);
            }
        return new double[] { low, high };
    }

    /** The furthest the footprint reaches out of its cell along any axis, in pixels. */
    public double reach(ControlMount mount) {
        AABB bounds = bounds(mount);
        double reach = 0;
        for (Axis axis : Axis.values()) {
            reach = Math.max(reach, -min(bounds, axis));
            reach = Math.max(reach, max(bounds, axis) - 1);
        }
        return reach * PlacementGrid.PIXELS_PER_BLOCK;
    }

    /**
     * Whether two mounted footprints share any space. Volume against volume, not
     * cell against cell: a rotary switch's front plate is four pixels across and
     * would occupy sixteen cells of a sixteen-by-sixteen grid, so an occupancy test
     * on cells would refuse most of a face that is in fact free.
     *
     * {@code shift} is the other control's host cell measured from this one, so
     * controls in neighbouring blocks can be compared without moving either into
     * world coordinates.
     */
    public boolean intersects(ControlMount mount, ControlFootprint other, ControlMount otherMount, double shiftX,
            double shiftY, double shiftZ) {
        AABB[] mine = volumes(mount);
        for (AABB theirs : other.volumes(otherMount)) {
            AABB moved = theirs.move(shiftX, shiftY, shiftZ);
            for (AABB box : mine)
                if (box.intersects(moved))
                    return true;
        }
        return false;
    }

    /** The single box containing the whole footprint, in block units. */
    public AABB bounds(ControlMount mount) {
        AABB[] volumes = volumes(mount);
        AABB bounds = volumes[0];
        for (int i = 1; i < volumes.length; i++)
            bounds = bounds.minmax(volumes[i]);
        return bounds;
    }

    /** The authored boxes of several parts, gathered into one footprint. */
    public static ControlFootprint of(double[]... parts) {
        return new ControlFootprint(parts);
    }

    /** This footprint with another group of authored boxes added to it. */
    public ControlFootprint plus(double[][] more) {
        List<double[]> gathered = new ArrayList<>(boxes.length + more.length);
        for (double[] part : boxes)
            gathered.add(part);
        for (double[] part : more)
            gathered.add(part);
        return new ControlFootprint(gathered.toArray(double[][]::new));
    }

    private static double min(AABB box, Axis axis) {
        return axis.choose(box.minX, box.minY, box.minZ);
    }

    private static double max(AABB box, Axis axis) {
        return axis.choose(box.maxX, box.maxY, box.maxZ);
    }
}
