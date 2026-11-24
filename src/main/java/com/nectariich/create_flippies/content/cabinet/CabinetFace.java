package com.nectariich.create_flippies.content.cabinet;

import com.nectariich.create_flippies.content.screwdriver.ScrewdriverAim;
import com.nectariich.create_flippies.foundation.panel.PanelPlane;

import net.minecraft.core.Direction;
import net.minecraft.world.phys.Vec3;

/**
 * The outside of one wall of a cabinet, or of its door, as the screwdriver mounts
 * controls on it: a plane in the world, like a block face, and the rectangle of it
 * the wall covers.
 *
 * A cabinet only ever faces one of the four horizontal directions and its door only
 * turns a quarter, so every face of it lies in a plane square to the world's axes and
 * a control on it is mounted exactly as one on a block face is. What is different is
 * where it is hosted when the face is the shut door: see {@link #place}.
 *
 * @param open the pose of the door the face was taken in; only means anything for the door
 * @param u0 the rectangle the wall covers, in the plane's own u and v, blocks
 */
public record CabinetFace(ElectricalCabinetEntity cabinet, CabinetWall wall, boolean open, PanelPlane plane,
        double u0, double v0, double u1, double v1) {

    private static final double E = 1e-4;

    /** The outside of {@code wall} of {@code cabinet} as it stands. */
    public static CabinetFace of(ElectricalCabinetEntity cabinet, CabinetWall wall) {
        CabinetSpec spec = cabinet.spec();
        boolean open = cabinet.doorOpen();
        CabinetWall.Frame frame = wall.frame(spec, open);
        CabinetWall.Range full = wall.full(spec);
        if (full.empty())
            return null;
        // The outside is the face at t = 0; every frame's n points into the cabinet
        Vec3 out = cabinet.directionToWorld(frame.n().reverse());
        Direction facing = Direction.getNearest(out.x, out.y, out.z);
        Vec3 a = cabinet.toWorld(frame.at(full.u0(), full.v0(), 0));
        Vec3 b = cabinet.toWorld(frame.at(full.u1(), full.v1(), 0));
        PanelPlane plane = PanelPlane.through(facing, 0, a);
        double ua = plane.u(a), ub = plane.u(b), va = plane.v(a), vb = plane.v(b);
        return new CabinetFace(cabinet, wall, open, plane, Math.min(ua, ub), Math.min(va, vb), Math.max(ua, ub),
                Math.max(va, vb));
    }

    /** The world point at {@code (u, v)} of the wall's own coordinates, px, on its outside. */
    public Vec3 point(double u, double v) {
        return cabinet.toWorld(wall.frame(cabinet.spec(), open).at(u, v, 0));
    }

    /** Whether {@code (u, v)} of the plane is on the wall. */
    public boolean holds(double u, double v) {
        return u >= u0 - E && u <= u1 + E && v >= v0 - E && v <= v1 + E;
    }

    /** As a surface for the screwdriver to rule, its squares counted from the wall's corner. */
    public ScrewdriverAim.Surface surface() {
        return new ScrewdriverAim.Surface(plane, 0, u0, v0, this);
    }

    /** Whether this is the door. */
    public boolean door() {
        return wall == CabinetWall.DOOR;
    }

    /**
     * Where a control centred at {@code (u, v)} on this face goes. On anything but the
     * shut door that is simply there. On the shut door it is where that point is when
     * the door is open, because that is where the door's controls are hosted and
     * wired; the host draws it back on the shut door ({@link CabinetDoorLink}). The
     * turn is the same in both poses: a quarter turn about the vertical keeps up up.
     */
    public ScrewdriverAim.Placement place(double u, double v, double depth, int turn) {
        return place(u, v, depth, turn, null);
    }

    /** As {@link #place(double, double, double, int)}, hosted by {@code host} when it is not null. */
    public ScrewdriverAim.Placement place(double u, double v, double depth, int turn,
            net.minecraft.core.BlockPos host) {
        if (!door() || open)
            return ScrewdriverAim.at(plane, u, v, depth, turn, host);
        CabinetDoorLink link = cabinet.doorLink();
        Vec3 there = link.toOpen(plane.at(u, v, 0));
        PanelPlane openPlane = PanelPlane.through(link.directionToOpen(plane.face()), 0, there);
        return ScrewdriverAim.at(openPlane, openPlane.u(there), openPlane.v(there), depth, turn, host);
    }
}
