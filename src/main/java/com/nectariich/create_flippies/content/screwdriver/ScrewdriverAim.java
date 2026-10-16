package com.nectariich.create_flippies.content.screwdriver;

import java.util.List;

import com.nectariich.create_flippies.content.panel.LargePanelBlockEntity;
import com.nectariich.create_flippies.foundation.control.ControlMount;
import com.nectariich.create_flippies.foundation.control.SpannedControlType;
import com.nectariich.create_flippies.foundation.grid.FaceRegion;
import com.nectariich.create_flippies.foundation.grid.GridIndex;
import com.nectariich.create_flippies.foundation.grid.PlacementGrid;
import com.nectariich.create_flippies.foundation.panel.PanelDefinition;
import com.nectariich.create_flippies.foundation.panel.PanelPlane;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.phys.Vec3;

/**
 * What the crosshair is on, worked out once and used by both the overlay that
 * draws it and the click that acts on it.
 *
 * This is the whole reason the modes can be trusted. A preview that computed the
 * cell one way and a placement that computed it another would be right almost
 * always and wrong at every cell boundary, which is exactly where a player aims
 * when they are being careful. Everything either side needs — the surface, the
 * region, the cell within it, the grid constraining it — comes out of the methods
 * below, and none of them touches a side-specific class, so the client and the
 * server can each call them with the same arguments and get the same answer.
 *
 * The world is read, through a {@link BlockGetter}, and only to ask what surface a
 * block is presenting. That question cannot be answered from the ray alone: a
 * chamfer's collision shape is a staircase, so the face a ray reports is one of
 * sixteen little steps and says nothing about which way the panel actually faces.
 * Both sides have the block entity and both reach the same answer from it.
 */
public final class ScrewdriverAim {

    /**
     * How far off the surface a point is pushed to decide which block is in front
     * of it and which behind, in blocks. A point on a cardinal plane is exactly on
     * a block boundary and belongs to neither side until it is nudged off; a
     * millionth of a block is enough to settle that and far too little to cross
     * anything else.
     */
    private static final double NUDGE = 1.0e-6;

    /**
     * The flat surface a click landed on, how far off it a control's plate goes,
     * and where the rectangle carrying it starts.
     *
     * The depth is what lets a chamfer be built on from behind. A panel's plate
     * hangs two pixels behind its plane, so the far side of it is two pixels further
     * on — and that surface has no plane of its own that the lattice can hold, since
     * the offset a diagonal would need is irrational. Carrying it as a depth instead
     * costs nothing and is exact: two pixels is a whole number of anchor units, so
     * the mount lands on the lattice either way.
     *
     * The origin is the panel's own low corner and is what a ruling with no grid to
     * follow counts its squares from; see {@link FaceRegion#square}. It is zero and
     * unused for an ordinary block face, which counts its squares from the block.
     */
    public record Surface(PanelPlane plane, double depth, double originU, double originV) {}

    /**
     * The region the crosshair is over and the cell of it under the point, together
     * with the constraining grid that produced the region in mode 3 and null
     * otherwise. The grid is carried along because a placement has to be told which
     * cell of which grid it belongs to, and re-deriving it would be the second
     * implementation this class exists to avoid.
     */
    public record Target(FaceRegion region, int cell, PlacementGrid constraint, int constraintCell) {}

    /** A stored grid and the one cell of it a point falls in. */
    public record Constraint(PlacementGrid grid, int cell) {}

    /**
     * A resolved placement: the block behind the surface, the block that will hold
     * the control, and where in that block it sits.
     *
     * All three travel together because the mount's anchors are measured from the
     * host and mean nothing without it, and because the two blocks are not always
     * different. On a block face they never are the same — the panel is behind the
     * face and the host in front of it — but a chamfer's surface runs through the
     * middle of its blocks, so the cell in front of a point on it is usually the
     * very cell the panel is built from. The caller has to see that rather than
     * infer it.
     */
    public record Placement(BlockPos panel, BlockPos host, ControlMount mount) {}

    private ScrewdriverAim() {}

    /**
     * The surface a click on a block is aiming at.
     *
     * A panel answers with its own plane, and an ordinary block with the face that
     * was clicked. The difference matters for every panel and not only for chamfers:
     * a panel's plane is snapped to the pixel rather than to the block boundary, so a
     * wall built a few pixels proud of its blocks has its surface in the middle of
     * them, and mounting on the block face instead would hang every control off the
     * plate by whatever that difference happened to be. A chamfer is the same rule
     * taken further — its blocks have no face pointing that way at all, and its
     * collision shape is a staircase whose steps point along the world axes, so the
     * face a ray reports says nothing about which way the panel faces.
     *
     * <h2>Which side</h2>
     *
     * Both sides of a panel are its own surface, and which one is decided by where
     * the player is rather than by which face the ray struck. A staircase presents
     * steps pointing four ways from either side, so the struck face cannot tell the
     * two apart; the eye can, and unambiguously — the front is the side the eye is
     * on.
     *
     * The back is the same plane seen the other way, two pixels further on: the
     * plate's own thickness. It is carried as a depth rather than as a plane of its
     * own because a diagonal's back face falls at an irrational offset the lattice
     * cannot hold, while two pixels is a whole number of anchor units and so stays
     * exact. The in-plane axes do not change with the side, since they come from the
     * plane and not from which way it faces, so both sides of one panel are ruled
     * alike.
     */
    public static Surface surfaceAt(BlockGetter level, BlockPos clicked, Direction face, Vec3 point, Vec3 eye) {
        PanelDefinition panel = LargePanelBlockEntity.panelAt(level, clicked, point);
        if (panel == null)
            return new Surface(PanelPlane.ofFace(clicked, face), 0, 0, 0);

        double scale = PanelPlane.UNITS_PER_BLOCK;
        double originU = panel.minU() / scale;
        double originV = panel.minV() / scale;
        PanelPlane plane = panel.plane();
        if (plane.depth(eye) >= 0)
            return new Surface(plane, 0, originU, originV);
        return new Surface(plane.flipped(), PanelDefinition.THICKNESS / scale, originU, originV);
    }

    /**
     * Where a placement mode would put a control, or null if it would put it
     * nowhere: mode 3 aimed away from every constraining grid has no region to
     * subdivide, and saying so is what lets the caller explain itself rather than
     * silently doing nothing.
     *
     * {@code clicked} is the block the ray struck and {@code point} the hit in world
     * block units. The point is read through the surface's own axes throughout, so a
     * hit on the side of a staircase step resolves to the place on the chamfer that
     * is directly behind it, which is where the player was pointing.
     */
    public static Target resolve(GridIndex index, ScrewdriverSettings settings, Surface surface, BlockPos clicked,
            Direction face, Vec3 point) {
        int[] division = settings.division();
        PanelPlane plane = surface.plane();
        FaceRegion region;
        Constraint constraint = null;

        if (settings.mode() == ScrewdriverMode.CONSTRAINED_PLACEMENT) {
            constraint = innermostAt(index, clicked, plane, point);
            if (constraint == null)
                return null;
            region = FaceRegion.cellOf(constraint.grid(), constraint.cell(), division[0], division[1]);
        } else if (plane.isTurned()) {
            region = FaceRegion.square(plane, surface.originU(), surface.originV(), plane.u(point), plane.v(point),
                    division[0], division[1]);
        } else {
            region = FaceRegion.wholeFace(plane, clicked, division[0], division[1]);
        }

        int cell = region.cellAt(point);
        if (cell == FaceRegion.OUTSIDE)
            return null;
        return new Target(region, cell, constraint == null ? null : constraint.grid(),
                constraint == null ? PlacementGrid.NO_CELL : constraint.cell());
    }

    /**
     * The corner a click picks for a rectangle drawn on a surface, snapped inside the
     * ruled cell it landed in where there is one to snap inside.
     *
     * A rectangle is drawn corner to corner and so takes no notice of the cell ruling
     * — that is what lets a legend run across the block boundaries and the lines of
     * the panel it is naming, which is the one thing legends are for. But taking no
     * notice of the ruling is not the same as being unable to line up with it, and
     * until the constraining cell was allowed to move the snap lattice it was the
     * latter: a cell with its edges at thirds of a pixel could not be matched by a
     * rectangle whose corners could only sit on whole ones.
     *
     * So in the mode that places inside a stored grid, the pixel lattice is counted
     * from the cell the crosshair is in rather than from the block behind it, and
     * everywhere else it is counted as it always was. Which cell is the innermost one
     * holding the point — the same cell {@link #resolve} would have subdivided, and
     * the smallest one the player is pointing at.
     *
     * The pitch differs the same way. A control drawn to size is built in half pixels
     * — see {@link SpannedControlType#SPANS_PER_PIXEL} — and so snaps to them; a grid
     * being ruled must stay on the block's own pixel lines, because that is the only
     * lattice {@link PlacementGrid} can hold.
     *
     * @param within whether the rectangle is a control drawn to size, which is what
     *               decides both questions above
     */
    public static GridCorner corner(GridIndex index, ScrewdriverSettings settings, PanelPlane plane, BlockPos clicked,
            Vec3 point, boolean within) {
        int step = within ? SpannedControlType.UNITS_PER_SPAN : PanelPlane.UNITS_PER_PIXEL;
        if (!within || settings.mode() != ScrewdriverMode.CONSTRAINED_PLACEMENT)
            return GridCorner.snapped(plane, point, step);

        // Once a corner is down it is the rectangle's own origin, and the far corner is
        // counted from it so that the two ends cannot end up on lattices a fraction of
        // a pixel apart
        GridCorner first = settings.corner();
        if (first != null && first.plane().equals(plane))
            return GridCorner.snappedFrom(first, point, step);

        Constraint constraint = innermostAt(index, clicked, plane, point);
        if (constraint == null)
            return GridCorner.snapped(plane, point, step);
        double[] cell = constraint.grid().cellRect(constraint.cell());
        return GridCorner.snappedWithin(plane, point, cell[0], cell[1], step);
    }

    /**
     * The deepest grid on that surface containing the point, or null if none does.
     *
     * Deepest rather than first, because grids nest: a point inside a child is
     * inside its parent as well, and the cell the player means is always the
     * smallest one they are pointing at. {@link GridIndex#onSurface} already sorts
     * shallowest first, so the search runs backwards over it.
     */
    public static Constraint innermostAt(GridIndex index, BlockPos clicked, PanelPlane plane, Vec3 point) {
        List<PlacementGrid> grids = index.onSurface(clicked, plane);
        for (int i = grids.size() - 1; i >= 0; i--) {
            PlacementGrid grid = grids.get(i);
            int cell = grid.cellAt(plane.u(point), plane.v(point));
            if (cell != PlacementGrid.OUTSIDE)
                return new Constraint(grid, cell);
        }
        return null;
    }

    /** Which block holds a control aimed into a cell, and where on it. */
    public static Placement placementFor(Target target, double depth, int turn) {
        FaceRegion region = target.region();
        return at(region.plane(), region.cellCentreU(target.cell()), region.cellCentreV(target.cell()), depth, turn);
    }

    /**
     * Where a control centred at a point on a surface goes: the block behind the
     * surface, the block in front that will hold it, and the mount.
     *
     * The block clicked is not necessarily either of them. A grid is ruled in
     * absolute pixels and may span any number of panel blocks, so the cell aimed at
     * can perfectly well be centred over the block next door; and a control is
     * hosted by the block its body is in, because that is the block its mount is
     * measured from and the block asked about it. So both are read back off the
     * centre rather than assumed to be the one under the crosshair. Measuring from
     * the clicked block instead is what used to drag a control back to that block's
     * edge — silently, since the anchor was clamped onto the face rather than
     * refused.
     *
     * The mount's face is the opposite of the surface's: a control is authored
     * plate-down, so the plate points into the panel it is screwed to while the
     * operator side stays in the cell in front. All three anchors are then the same
     * question asked along three axes — how far the centre is from the middle of the
     * host, along u, along v, and along the way the plate points — which is why they
     * are one expression rather than a rule per case. On a block face the third
     * comes out at exactly half a block every time, because that is where a face is.
     */
    public static Placement at(PanelPlane plane, double centreU, double centreV, double depth, int turn) {
        Vec3 centre = plane.at(centreU, centreV, depth);
        Vec3 outward = plane.outward();
        BlockPos host = BlockPos.containing(centre.add(outward.scale(NUDGE)));
        BlockPos panel = BlockPos.containing(centre.subtract(outward.scale(NUDGE)));
        Vec3 from = centre.subtract(Vec3.atCenterOf(host));
        return new Placement(panel, host,
                new ControlMount(plane.face().getOpposite(), plane.yaw(), anchor(from.dot(plane.tangentU())),
                        anchor(from.dot(plane.tangentV())), anchor(-from.dot(outward)), turn));
    }

    /**
     * A displacement from the middle of the host block, in blocks, as a mount
     * anchor. The mount lattice is four times finer than the finest ruling the grid
     * store will accept, so the rounding never moves a control out of the cell it
     * was aimed at.
     */
    private static int anchor(double blocks) {
        return ControlMount.CENTRED + (int) Math.round(blocks * ControlMount.UNITS_PER_BLOCK);
    }
}
