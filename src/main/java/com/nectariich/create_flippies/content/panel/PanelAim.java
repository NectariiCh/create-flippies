package com.nectariich.create_flippies.content.panel;

import com.nectariich.create_flippies.foundation.panel.PanelDefinition;
import com.nectariich.create_flippies.foundation.panel.PanelPlane;

import net.minecraft.core.Direction;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;

/**
 * Where a dynamic block would go, worked out once and used by both the overlay that
 * draws it and the click that builds it.
 *
 * The same reason {@link com.nectariich.create_flippies.content.screwdriver.ScrewdriverAim}
 * exists: a preview that computed the rectangle one way and a placement that computed
 * it another would be right almost always and wrong exactly where the player is being
 * careful. Nothing here touches the world, so the client and the server can call it
 * with the same arguments and get the same answer — and the arguments are the
 * player's own eye and look, which the server knows for itself, so a client cannot
 * lie about where it aimed.
 *
 * <h2>The two clicks are not symmetrical</h2>
 *
 * The first click picks a point on a block face and nothing more; see
 * {@link PanelPoint}. It is the second click that decides which plane the panel lies
 * in, and it decides by asking where the crosshair actually is.
 *
 * Through one point there are exactly three planes a panel may lie in — one per
 * allowed normal, which is the three axes for a cardinal panel and the two diagonals
 * plus the vertical for a turned one. The crosshair ray is intersected with all
 * three and the nearest hit in front of the player wins. Nearest rather than any
 * other rule because it is the one the player can see: whichever surface their
 * crosshair reaches first is the one they are pointing at.
 *
 * The planes are mathematical rather than block faces, so the second corner does not
 * need anything to be there. Pointing into open air still lands on whichever plane
 * the ray crosses, which is what makes a panel that stands free of any wall
 * placeable at all.
 */
public final class PanelAim {

    /**
     * How far the crosshair may reach for a second corner, in blocks. Generous,
     * because a panel is meant to be large and the far corner of one may be well
     * beyond arm's length; the rectangle is bounded by {@link PanelDefinition#check}
     * rather than here. It also keeps a ray that is nearly parallel to a plane from
     * resolving somewhere absurd.
     */
    public static final double REACH = 64;

    /** A ray this close to parallel with a plane is treated as missing it. */
    private static final double PARALLEL = 1.0e-4;

    /** A resolved second corner: the plane it chose, and where on it the ray landed. */
    public record Resolution(PanelPlane plane, Vec3 point) {}

    private PanelAim() {}

    /**
     * The plane and point the crosshair picks for a second corner, or null if it
     * picks none — which happens only when the player is looking parallel to all
     * three planes at once, or past the reach.
     */
    public static Resolution resolve(Vec3 eye, Vec3 look, PanelPoint first, int yaw) {
        Resolution nearest = null;
        double nearestDistance = Double.MAX_VALUE;

        for (PanelPlane candidate : candidates(first, yaw, eye)) {
            // The plane faces the player, so the eye is in front of it and the ray has
            // to be closing on it for the hit to be ahead rather than behind
            double closing = -dot(look, candidate.outward());
            if (closing < PARALLEL)
                continue;
            double distance = candidate.depth(eye) / closing;
            if (distance <= 0 || distance > REACH || distance >= nearestDistance)
                continue;
            nearestDistance = distance;
            nearest = new Resolution(candidate, eye.add(look.scale(distance)));
        }
        return nearest;
    }

    /** The same, from a player, which is where both sides get the ray. */
    public static Resolution resolve(Player player, PanelPoint first, int yaw) {
        return resolve(player.getEyePosition(), player.getLookAngle(), first, yaw);
    }

    /**
     * The three planes through a point that a panel may lie in, each turned to face
     * the player so that "in front" means what it says.
     *
     * A cardinal panel may stand on either horizontal axis or lie flat; a turned one
     * may stand on either diagonal or lie flat. Either way it is three, and the flat
     * one is in both sets because a floor or a ceiling has no yaw to turn.
     */
    private static PanelPlane[] candidates(PanelPoint first, int yaw, Vec3 eye) {
        // North and east name the two horizontal families, not two of four
        // directions: at a yaw of one they become the two diagonals, and either way
        // the facing is corrected below to whichever side the player is on
        Direction[] faces = { Direction.NORTH, Direction.EAST, Direction.UP };
        PanelPlane[] planes = new PanelPlane[faces.length];
        for (int i = 0; i < faces.length; i++) {
            // The flat plane takes the yaw too. It cannot be turned out of true by it —
            // spinning a floor about the vertical leaves the floor where it was — so
            // what the yaw turns there is u and v, and the panel comes out lying at
            // forty-five degrees to the block grid on an ordinary block boundary
            PanelPlane bare = new PanelPlane(faces[i], yaw, 0);
            PanelPlane through = bare.withOffset(first.latticeDot(bare.latticeNormal()));
            planes[i] = facing(through, eye);
        }
        return planes;
    }

    /**
     * The same plane, turned so its front is the side the player is on.
     *
     * Which side is the front decides which way the plate hangs, and a plate hanging
     * away from the player is a plate inside whatever they were pointing at.
     */
    private static PanelPlane facing(PanelPlane plane, Vec3 eye) {
        return plane.depth(eye) < 0 ? plane.flipped() : plane;
    }

    /**
     * The rectangle between the picked corner and a resolved second one, or null if
     * the two span nothing worth building.
     *
     * The first corner is exact: the plane was built through it, so its position along
     * the plane needs no rounding and the panel starts precisely where the player
     * clicked. Only the span is snapped, and it is snapped to whole pixels, so the
     * panel is a clean size <em>and</em> keeps its corner.
     *
     * The plate is then moved a thickness towards the player, which puts its back on
     * the plane through the first corner: flush with the face that corner was picked
     * on, and in open air, exactly where the crosshair was.
     */
    public static PanelDefinition between(PanelPoint first, Resolution second) {
        if (first == null || second == null)
            return null;
        PanelPlane plane = second.plane();
        Vec3 corner = first.toVec();

        int firstU = units(plane.u(corner));
        int firstV = units(plane.v(corner));
        int secondU = firstU + snapSpan(plane.u(second.point()) - plane.u(corner));
        int secondV = firstV + snapSpan(plane.v(second.point()) - plane.v(corner));
        if (firstU == secondU || firstV == secondV)
            return null;

        PanelPlane surface = plane.withOffset(plane.offset() + thicknessShift(plane));
        return PanelDefinition.between(surface, firstU, firstV, secondU, secondV);
    }

    /**
     * How much to add to an offset to move the plane one plate thickness towards its
     * own front, in offset units.
     *
     * Not simply the thickness. An offset is measured along the lattice normal, and a
     * diagonal's lattice normal is root two long, so adding the thickness raw would
     * move a diagonal plane by only a root two of it — which left the plate a
     * thirty-seventh of a block inside the wall it was supposed to sit flush against.
     * Scaling by the normal's length makes the move a real distance on every plane.
     */
    private static int thicknessShift(PanelPlane plane) {
        return (int) Math.round(PanelDefinition.THICKNESS * plane.latticeLength()) * plane.outwardSign();
    }

    private static int units(double blocks) {
        return (int) Math.round(blocks * PanelPlane.UNITS_PER_BLOCK);
    }

    /** A span in blocks, rounded to whole pixels and returned in offset units. */
    private static int snapSpan(double blocks) {
        int perPixel = PanelPlane.UNITS_PER_PIXEL;
        return (int) Math.round(blocks * PanelPlane.UNITS_PER_BLOCK / perPixel) * perPixel;
    }

    private static double dot(Vec3 a, Vec3 b) {
        return a.x * b.x + a.y * b.y + a.z * b.z;
    }
}
