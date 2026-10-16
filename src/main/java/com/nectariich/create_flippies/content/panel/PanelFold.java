package com.nectariich.create_flippies.content.panel;

import java.util.Locale;

import com.nectariich.create_flippies.foundation.panel.PanelDefinition;
import com.nectariich.create_flippies.foundation.panel.PanelPlane;

import net.minecraft.core.Direction;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;

/**
 * A panel folded square off the edge of another.
 *
 * The corner a chamfer cuts across, this one turns: click an edge of a panel and the
 * next panel stands perpendicular to it, hinged on that edge. It is the other half of
 * the same problem — a join that has to be exact and cannot be aimed at, because what
 * makes it exact is where a plane sits to a fraction of a pixel and no crosshair
 * resolves that.
 *
 * <h2>Where the fold plane comes from</h2>
 *
 * The edge is a line, and it is the front surface of the source panel that has it. A
 * plane perpendicular to the source and containing that line is unique — there is no
 * choice of which way to turn, because turning ninety degrees either way about the
 * hinge arrives at the same plane — so nothing has to be picked and nothing has to be
 * guessed. It is then turned to face the player, which decides which side of it the
 * new plate hangs on and so whether the corner comes out inside or outside.
 *
 * Both panels hang their plate behind their own surface, and both surfaces pass
 * through the hinge, so the two meet along it with no step whichever corner it is.
 *
 * <h2>Why there are two hinges and not one</h2>
 *
 * A panel has four edges, and they come in two pairs that point the fold different
 * ways: the edges running along v turn it about one axis, the ones along u about the
 * other. Through the place the player clicked there are three planes a panel may lie
 * in — the source's own, and one per pair — so folding offers the <em>two others</em>.
 * That is the whole point of the gesture on a floor: an edge of a flat panel can raise
 * a wall in either of the two vertical planes, and an edge of a wall can throw out
 * either a perpendicular wall or a floor.
 *
 * Picking one of the two by proximity alone was the first try and it was wrong, for a
 * reason a long panel makes plain: on a rectangle much wider than it is deep, nearly
 * every point is nearest a long edge, so the short one was all but unreachable and the
 * fold only ever went one way. So proximity narrows each <em>pair</em> to its nearer
 * member — which of two parallel edges is meant is not in doubt, it is the one the
 * player is standing at — and the second click decides between the two survivors the
 * way {@link PanelAim} decides among its three: by which plane the crosshair actually
 * reaches first. Whichever surface the player is pointing at is the one they get.
 *
 * <h2>Which corner is pinned</h2>
 *
 * Where the player clicked. The click picks the place along the hinge, and that place
 * becomes a corner of the new rectangle; the second click sets the opposite one, as it
 * does for a panel drawn freehand. So the fold is variable in both directions and
 * flush regardless of what either comes out as — the flushness is in the plane, not in
 * the size.
 */
public final class PanelFold {

    /** How close two unit vectors must be to count as the same direction. */
    private static final double TOLERANCE = 1.0e-6;

    /** A ray this close to parallel with a fold plane is treated as missing it. */
    private static final double PARALLEL = 1.0e-4;

    /** How far the crosshair may reach for the far corner, in blocks. */
    public static final double REACH = 64;

    /** Why a fold was refused, or {@link #NONE} if it was not. */
    public enum Problem {
        NONE,
        /** No panel was clicked. */
        NO_PANEL,
        /** Neither plane square to that panel is one a panel can lie on. */
        NO_PLANE,
        /** The crosshair is on neither of them. */
        OFF_PLANE,
        /** The two corners span nothing. */
        DEGENERATE;

        public boolean ok() {
            return this == NONE;
        }

        public String key() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    /**
     * An edge of a panel: which pair it belongs to, and where it sits.
     *
     * @param source  the panel the edge belongs to
     * @param alongV  whether the edge runs along v, which is what an end of the run is
     * @param at      the u of that end, or the v of it if the edge runs along u
     */
    public record Hinge(PanelDefinition source, boolean alongV, int at) {}

    /**
     * A fold, or the reason there is none.
     *
     * The hinge is reported alongside it, because which of the two the crosshair chose
     * is no longer something the caller can work out for itself, and the overlay has to
     * draw the edge the fold actually turned about.
     */
    public record Joint(PanelDefinition panel, Hinge hinge, Problem problem) {}

    private PanelFold() {}

    /**
     * The two edges a fold off this panel may hinge on: the nearer of the pair running
     * along v, and the nearer of the pair running along u.
     *
     * Distance is measured along the plane rather than through space, so an edge is
     * picked by where on the panel the crosshair is and not by how the player is
     * standing relative to it.
     */
    public static Hinge[] candidates(PanelDefinition panel, Vec3 point) {
        if (panel == null)
            return new Hinge[0];
        PanelPlane plane = panel.plane();
        double scale = PanelPlane.UNITS_PER_BLOCK;
        double u = plane.u(point) * scale;
        double v = plane.v(point) * scale;

        int endU = Math.abs(u - panel.minU()) <= Math.abs(u - panel.maxU()) ? panel.minU() : panel.maxU();
        int endV = Math.abs(v - panel.minV()) <= Math.abs(v - panel.maxV()) ? panel.minV() : panel.maxV();
        return new Hinge[] { new Hinge(panel, true, endU), new Hinge(panel, false, endV) };
    }

    /**
     * The plane a fold off this edge would lie on, turned to face the player, or null
     * if there is no such plane a panel can use.
     */
    public static PanelPlane planeOf(Hinge hinge, Vec3 eye) {
        if (hinge == null)
            return null;
        PanelPlane source = hinge.source().plane();
        Vec3 along = hinge.alongV() ? source.tangentV() : source.tangentU();
        Vec3 square = hinge.alongV() ? source.tangentU() : source.tangentV();

        PanelPlane plane = matching(square, along, on(hinge));
        if (plane == null)
            return null;
        // Which side is the front decides which way the plate hangs, and a plate
        // hanging away from the player is a plate inside whatever they were pointing at
        return plane.depth(eye) < 0 ? plane.flipped() : plane;
    }

    /** A point on the hinge, which is what the fold plane is threaded through. */
    private static Vec3 on(Hinge hinge) {
        PanelDefinition source = hinge.source();
        double scale = PanelPlane.UNITS_PER_BLOCK;
        double middle = (hinge.alongV() ? source.minV() + source.maxV() : source.minU() + source.maxU()) / 2.0 / scale;
        return hinge.alongV() ? source.plane().at(hinge.at() / scale, middle, 0)
                : source.plane().at(middle, hinge.at() / scale, 0);
    }

    /**
     * The plane with a given outward normal and a given in-plane axis, through a point.
     *
     * Found by trying the facings rather than by deriving one, because there are only
     * twelve and the derivation would have to reproduce, exactly, the choice of axes
     * {@link PanelPlane} already makes — and any disagreement between the two would
     * show up as a panel a fraction of a turn out of true, which is the one thing a
     * fold exists to avoid.
     */
    private static PanelPlane matching(Vec3 outward, Vec3 along, Vec3 through) {
        for (Direction face : Direction.values())
            for (int yaw = 0; yaw < PanelPlane.YAW_STEPS; yaw++) {
                PanelPlane bare = new PanelPlane(face, yaw, 0);
                if (!bare.check().ok() || bare.outward().distanceToSqr(outward) > TOLERANCE)
                    continue;
                if (!parallel(bare.tangentU(), along) && !parallel(bare.tangentV(), along))
                    continue;
                return PanelPlane.through(face, yaw, through);
            }
        return null;
    }

    private static boolean parallel(Vec3 axis, Vec3 other) {
        return Math.abs(Math.abs(axis.dot(other)) - 1) < TOLERANCE;
    }

    /**
     * The fold the crosshair is drawing, or the reason there is none.
     *
     * Both candidate hinges are put to the ray and the nearer hit wins, which is the
     * rule {@link PanelAim} already uses and the only one the player can see: the
     * surface their crosshair reaches first is the one they are pointing at. Nothing
     * else distinguishes the two — both are square to the source and both are flush
     * with it — so there is no better tie to break.
     *
     * @param clicked where on the panel the first click landed, which is what pins the
     *                near corner once a plane has been chosen
     */
    public static Joint between(PanelDefinition from, Vec3 clicked, Vec3 eye, Vec3 look) {
        if (from == null)
            return refused(Problem.NO_PANEL);

        Hinge hinge = null;
        PanelPlane plane = null;
        Vec3 far = null;
        double nearest = Double.MAX_VALUE;
        boolean anyPlane = false;

        for (Hinge candidate : candidates(from, clicked)) {
            PanelPlane surface = planeOf(candidate, eye);
            if (surface == null)
                continue;
            anyPlane = true;
            // The plane faces the player, so the eye is in front of it and the ray has
            // to be closing on it for the hit to be ahead rather than behind
            double closing = -look.dot(surface.outward());
            if (closing < PARALLEL)
                continue;
            double distance = surface.depth(eye) / closing;
            if (distance <= 0 || distance > REACH || distance >= nearest)
                continue;
            nearest = distance;
            hinge = candidate;
            plane = surface;
            far = eye.add(look.scale(distance));
        }

        if (!anyPlane)
            return refused(Problem.NO_PLANE);
        if (hinge == null)
            return refused(Problem.OFF_PLANE);

        // The pinned corner is the click, put exactly on the hinge: the click was on
        // the source panel and so a hair off the fold plane, and a corner a hair off is
        // a corner that would round the wrong way
        int firstU = units(plane.u(clicked));
        int firstV = units(plane.v(clicked));
        int secondU = firstU + snap(plane.u(far) - plane.u(clicked));
        int secondV = firstV + snap(plane.v(far) - plane.v(clicked));
        if (firstU == secondU || firstV == secondV)
            return new Joint(null, hinge, Problem.DEGENERATE);
        return new Joint(PanelDefinition.between(plane, firstU, firstV, secondU, secondV), hinge, Problem.NONE);
    }

    /** The same, from a player, which is where both sides get the ray. */
    public static Joint between(PanelDefinition from, Vec3 clicked, Player player) {
        return between(from, clicked, player.getEyePosition(), player.getLookAngle());
    }

    private static Joint refused(Problem problem) {
        return new Joint(null, null, problem);
    }

    private static int units(double blocks) {
        return (int) Math.round(blocks * PanelPlane.UNITS_PER_BLOCK);
    }

    /** A span in blocks, rounded to whole pixels and returned in offset units. */
    private static int snap(double blocks) {
        int perPixel = PanelPlane.UNITS_PER_PIXEL;
        return (int) Math.round(blocks * PanelPlane.UNITS_PER_BLOCK / perPixel) * perPixel;
    }
}
