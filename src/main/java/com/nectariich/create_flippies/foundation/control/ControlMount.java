package com.nectariich.create_flippies.foundation.control;

import com.nectariich.create_flippies.foundation.SurfaceMount;
import com.nectariich.create_flippies.foundation.grid.PlacementGrid;
import com.nectariich.create_flippies.foundation.panel.PanelPlane;

import net.minecraft.core.Direction;
import net.minecraft.core.Direction.Axis;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.world.phys.Vec3;

/**
 * Where one control sits inside its host block, and which way up it is.
 *
 * A control is mounted the way {@link SurfaceMount} describes — plate flush with
 * the surface, operator side inside the host's own cell, whatever passes through
 * the panel hanging out of the far side — but unlike a standalone instrument block
 * it is not centred on its cell. So a mount is that convention's {@code facing}
 * and {@code rotation}, plus the eighth turn a chamfer needs, plus a displacement
 * of the control's centre away from the middle of the block.
 *
 * The displacement is stored as three integers in sixteenths of a pixel, a lattice
 * of 1/256 of a block. A double would have done for storage, but hit testing
 * compares raw doubles at full precision on both sides of the connection and both
 * sides derive the terminal boxes independently, so it matters that a mount cannot
 * be reconstructed even one bit differently from how it was written; an integer
 * anchor gives that unconditionally, and the placement checks then run on the same
 * lattice the save holds, so a placement that was accepted can never turn out to
 * overlap after a reload. The lattice is a sixteenth of a pixel because a
 * placement grid may rule a surface into any number of tracks and thirds of a
 * pixel have to land somewhere; a sixteenth is finer than the finest grid the
 * store will accept by a factor of four. It is also
 * {@link PanelPlane#UNITS_PER_BLOCK}, so a panel's surface and a control's
 * position are quantised alike.
 *
 * <h2>The three anchors</h2>
 *
 * {@code u} and {@code v} are the surface's in-plane axes and {@code d} is the
 * way the plate points, all three taken from {@link PanelPlane} rather than
 * redefined — a grid cell's centre is where an anchor comes from and the two have
 * to mean the same thing by construction. Each anchor is a position within the
 * host block along its axis, measured from half a block back of the middle, so
 * {@link #CENTRED} is the middle and {@link #ON_FACE} is half a block forward.
 *
 * The depth anchor is the one a cardinal mount never varies, and the one a
 * chamfer cannot do without. A plane that is a block boundary puts the plate on a
 * face by construction, so {@code d} is always {@link #ON_FACE} there and the
 * whole term vanishes. A diagonal plane is a block boundary nowhere: it cuts
 * through the middle of its cells at a different place along every line, so where
 * the plate sits inside the host is a fact about the surface and has to be
 * carried. {@link SurfaceMount} turns about the middle of the block and a rotation
 * about the middle preserves distance from it, so the plate starts half a block
 * from the middle along {@code d} whatever the facing and the eighth turn are,
 * which is what lets one anchor mean the same thing in both cases.
 *
 * @param face     a cardinal facing of the plate
 * @param yaw      eighths of a turn past it, as {@link PanelPlane#yaw} counts them
 * @param anchorU  the plate's centre along the surface's u, in 1/256 of a block
 * @param anchorV  the same along v
 * @param anchorD  the same along the plate's own facing
 * @param rotation quarter turns of the control about its own mounting axis
 */
public record ControlMount(Direction face, int yaw, int anchorU, int anchorV, int anchorD, int rotation) {

    /** Anchor units in one pixel; see the class comment for why sixteen. */
    public static final int UNITS_PER_PIXEL = 16;

    /** Anchor units across one block, so the exclusive bound on an in-plane anchor. */
    public static final int UNITS_PER_BLOCK = UNITS_PER_PIXEL * PlacementGrid.PIXELS_PER_BLOCK;

    /** The middle of an authored model, in pixels, and so the point every turn holds still. */
    private static final double MIDDLE = PlacementGrid.PIXELS_PER_BLOCK / 2.0;

    /** The anchor of a control centred on its block, which is where a standalone one sits. */
    public static final int CENTRED = UNITS_PER_BLOCK / 2;

    /**
     * The depth anchor of a plate lying on the host's own face, which is where
     * {@link SurfaceMount} puts one and therefore where every cardinal mount keeps it.
     */
    public static final int ON_FACE = UNITS_PER_BLOCK;

    /**
     * How far a turned plate's centre may stray outside its host block, in blocks.
     *
     * Wide enough to cover the nudge the placement uses to decide which side of the
     * surface a block is on, which can leave a centre exactly on a boundary a
     * millionth of a block outside the cell that was chosen for it. A hundredth of a
     * pixel is far below anything that could put the centre in the wrong block for
     * any other reason.
     */
    private static final double SLACK = 1.0e-4;

    public static ControlMount centred(Direction face, int rotation) {
        return new ControlMount(face, 0, CENTRED, CENTRED, ON_FACE, rotation);
    }

    /** A mount on a cardinal face, which is the only kind that needs no depth. */
    public static ControlMount onFace(Direction face, int anchorU, int anchorV, int rotation) {
        return new ControlMount(face, 0, anchorU, anchorV, ON_FACE, rotation);
    }

    /** Whether the plate lies diagonally to the block grid. */
    public boolean isTurned() {
        return yaw != 0;
    }

    /**
     * Whether a plate here would sit exactly where that one does: the same surface and
     * the same centre on it, whichever way up either of them is turned.
     *
     * The quarter turn is left out on purpose. Two controls centred on one point of one
     * surface are a stack however they are turned, and a stack is the one arrangement
     * the rest of the rack cannot tell apart: aiming, a terminal search and a mount
     * looked up from a packet all settle a tie by taking the first control in slot
     * order, while the drawing puts the last one on top. See
     * {@link ControlRack#refusal} for what that did to a panel of lamps.
     */
    public boolean sharesPlaceWith(ControlMount other) {
        return face == other.face && yaw == other.yaw && anchorU == other.anchorU && anchorV == other.anchorV
                && anchorD == other.anchorD;
    }

    /**
     * The surface this plate lies on, as a direction and a turn. The offset is zero
     * and meaningless: where the plane sits in the world is the host block's business
     * and the anchors', and all that is wanted from here is the frame.
     */
    private PanelPlane frame() {
        return new PanelPlane(face, yaw, 0);
    }

    /** The unit vector the u anchor displaces along. */
    public Vec3 alongU() {
        return frame().tangentU();
    }

    /** The unit vector the v anchor displaces along. */
    public Vec3 alongV() {
        return frame().tangentV();
    }

    /** The way the plate points, which is the unit vector the depth anchor displaces along. */
    public Vec3 alongDepth() {
        return frame().outward();
    }

    public Axis uAxis() {
        return PlacementGrid.uAxis(face.getAxis());
    }

    public Axis vAxis() {
        return PlacementGrid.vAxis(face.getAxis());
    }

    /**
     * Whether the centre lies on the host's own face, or inside it when the plate is
     * turned. A control may overhang the edge of the cell, but its centre may not:
     * the block the centre is in is what hosts it, so a centre outside would make a
     * different block the host and leave this one holding a control it cannot be
     * asked about.
     *
     * A cardinal plate's centre sits exactly on a face and so exactly on the
     * boundary, which is why that case is answered from the anchors instead of from
     * the point — the axis across the plate would fail a test of the block it is
     * flush with about half the time, depending only on which of the two faces it is.
     */
    public boolean isOnFace() {
        if (!isTurned())
            return anchorU >= 0 && anchorU < UNITS_PER_BLOCK && anchorV >= 0 && anchorV < UNITS_PER_BLOCK;
        Vec3 centre = centre();
        return inside(centre.x) && inside(centre.y) && inside(centre.z);
    }

    private static boolean inside(double coordinate) {
        return coordinate >= -SLACK && coordinate < 1 + SLACK;
    }

    /**
     * An authored model-space point, in pixels, moved to where this mount puts it in
     * the host block's own pixel space. Coordinates outside 0..16 are ordinary and
     * mean the point is in a neighbouring cell.
     *
     * The turn happens before the displacement, so a control spins about its own
     * mounting axis rather than orbiting the middle of the block.
     */
    public double[] point(double x, double y, double z) {
        double[] moved = SurfaceMount.point(x, y, z, face, yaw, rotation);
        displace(moved, 1);
        return moved;
    }

    /**
     * The authored point this mount puts at {@code (x, y, z)} in the host block's
     * pixel space: the inverse of {@link #point}, and the way back from somewhere
     * in a panel to somewhere on the control mounted there.
     *
     * Wanted whenever a click has to be read as a place on a control rather than
     * merely tested against its boxes — which half of a rotary switch's front was
     * pushed, for one. The displacement is undone first because {@code point}
     * applies it last. A cardinal mount returns bit for bit; a turned one returns
     * within a rounding, for the reason {@link SurfaceMount} gives, and nothing
     * reads a local point that finely.
     */
    public double[] localPoint(double x, double y, double z) {
        double[] p = { x, y, z };
        displace(p, -1);
        return SurfaceMount.localPoint(p[0], p[1], p[2], face, yaw, rotation);
    }

    /**
     * Moves a point by this mount's three anchors, or back by them.
     *
     * Written as a sum over the three axis vectors rather than as three additions
     * to named coordinates, which is what a cardinal mount would allow, because a
     * turned one has no coordinate to name. The cardinal case comes out the same to
     * the bit: its axes are unit vectors along x, y and z, so the two terms that do
     * not apply contribute an exact zero.
     */
    private void displace(double[] p, int sign) {
        Vec3 u = alongU();
        Vec3 v = alongV();
        Vec3 d = alongDepth();
        double alongU = sign * offsetPixels(anchorU);
        double alongV = sign * offsetPixels(anchorV);
        double alongD = sign * depthPixels(anchorD);
        p[0] += u.x * alongU + v.x * alongV + d.x * alongD;
        p[1] += u.y * alongU + v.y * alongV + d.y * alongD;
        p[2] += u.z * alongU + v.z * alongV + d.z * alongD;
    }

    /**
     * An authored pixel box moved to this mount, as
     * {@code minX, minY, minZ, maxX, maxY, maxZ}.
     *
     * All eight corners are turned rather than two opposite ones. A quarter turn
     * takes a box to a box, so two corners settle it there; an eighth turn does not,
     * and two corners of a turned box can both land inside the volume the other six
     * need. What comes back is then the smallest axis-aligned box containing the
     * turned one, which is larger than the control really is — the price of a
     * hitbox lattice that has no diagonals, and paid in a slightly generous click
     * target rather than in anything visible.
     */
    public double[] box(double[] authored) {
        double[] low = { Double.MAX_VALUE, Double.MAX_VALUE, Double.MAX_VALUE };
        double[] high = { -Double.MAX_VALUE, -Double.MAX_VALUE, -Double.MAX_VALUE };
        for (int corner = 0; corner < 8; corner++) {
            double[] p = point(authored[(corner & 1) * 3], authored[((corner >> 1) & 1) * 3 + 1],
                    authored[((corner >> 2) & 1) * 3 + 2]);
            for (int axis = 0; axis < 3; axis++) {
                low[axis] = Math.min(low[axis], p[axis]);
                high[axis] = Math.max(high[axis], p[axis]);
            }
        }
        return new double[] { low[0], low[1], low[2], high[0], high[1], high[2] };
    }

    /**
     * How far this mount moves the control off the middle of its cell, in block
     * units, which is the one thing a renderer needs beyond the orientation
     * {@link SurfaceMount} already supplies.
     *
     * Read out of {@link #point} rather than computed again from the anchors. The
     * orientation leaves the middle of the block where it is, so whatever
     * {@code point} does to it is the displacement and nothing else — and a
     * renderer deriving the offset its own way could disagree with the terminal
     * boxes by a bit, which is a control drawn a hair off the hitbox it is picked
     * by.
     */
    public Vec3 offset() {
        double[] moved = point(MIDDLE, MIDDLE, MIDDLE);
        double scale = PlacementGrid.PIXELS_PER_BLOCK;
        return new Vec3((moved[0] - MIDDLE) / scale, (moved[1] - MIDDLE) / scale, (moved[2] - MIDDLE) / scale);
    }

    /** The centre of the control's mounting plate, in block units within the host cell. */
    public Vec3 centre() {
        double[] p = point(MIDDLE, 0, MIDDLE);
        double scale = PlacementGrid.PIXELS_PER_BLOCK;
        return new Vec3(p[0] / scale, p[1] / scale, p[2] / scale);
    }

    /**
     * Whether this mount's turn exchanges the authored in-plane axes with the
     * surface's own: a box authored {@code w} across in x and {@code h} deep in z
     * covers {@code h} of u and {@code w} of v when it does.
     *
     * Wanted by anything that has to author a box from a rectangle the player drew
     * on a wall, since the rectangle is measured in the surface's axes and the
     * footprint has to be stated in the model's. Read out of {@link #point} rather
     * than worked out again from the face and the rotation, for the reason
     * {@link #offset} is: the answer is whatever the transform actually does, and a
     * second derivation of it is a second thing to keep in step. The step along the
     * authored axis is measured against the surface's u rather than a world axis,
     * because a turned surface has no world axis to measure against — and on a
     * cardinal one the two are the same question.
     */
    public boolean swapsPlaneAxes() {
        double[] origin = point(MIDDLE, 0, MIDDLE);
        double[] alongX = point(MIDDLE + 1, 0, MIDDLE);
        Vec3 u = alongU();
        double travelled = (alongX[0] - origin[0]) * u.x + (alongX[1] - origin[1]) * u.y
                + (alongX[2] - origin[2]) * u.z;
        return Math.abs(travelled) < 0.5;
    }

    public void save(CompoundTag tag) {
        tag.putByte("Face", (byte) face.get3DDataValue());
        if (yaw != 0)
            tag.putByte("Yaw", (byte) yaw);
        tag.putInt("AnchorU", anchorU);
        tag.putInt("AnchorV", anchorV);
        tag.putInt("AnchorD", anchorD);
        tag.putByte("Rotation", (byte) rotation);
    }

    /** Null when the tag holds no mount, which is how a truncated save is survived. */
    public static ControlMount load(CompoundTag tag) {
        if (!tag.contains("AnchorU") || !tag.contains("AnchorV"))
            return null;
        return new ControlMount(Direction.from3DDataValue(tag.getByte("Face")), tag.getByte("Yaw"),
                tag.getInt("AnchorU"), tag.getInt("AnchorV"),
                tag.contains("AnchorD") ? tag.getInt("AnchorD") : ON_FACE, tag.getByte("Rotation"));
    }

    public void write(FriendlyByteBuf buffer) {
        buffer.writeEnum(face);
        buffer.writeByte(yaw);
        buffer.writeVarInt(anchorU);
        buffer.writeVarInt(anchorV);
        buffer.writeVarInt(anchorD);
        buffer.writeByte(rotation);
    }

    public static ControlMount read(FriendlyByteBuf buffer) {
        return new ControlMount(buffer.readEnum(Direction.class), buffer.readByte(), buffer.readVarInt(),
                buffer.readVarInt(), buffer.readVarInt(), buffer.readByte());
    }

    /** How far off the middle of the block an in-plane anchor is, in pixels. */
    private static double offsetPixels(int anchor) {
        return anchor / (double) UNITS_PER_PIXEL - PlacementGrid.PIXELS_PER_BLOCK / 2.0;
    }

    /**
     * How far a depth anchor moves the plate off the face, in pixels — which is
     * nothing at all for a cardinal mount, whose plate is on the face already.
     */
    private static double depthPixels(int anchor) {
        return (anchor - ON_FACE) / (double) UNITS_PER_PIXEL;
    }
}
