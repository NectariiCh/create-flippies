package com.nectariich.create_flippies.content.data_cord;

import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * A block entity with data ports a data cord can plug into (spec 11.2.5): the PC
 * case and the keyboard now, the monitor and the mouse later.
 *
 * Ports are numbered from 0 and the numbering is saved in links, so a host's port
 * order is frozen.
 */
public interface DataPortHost {

    int dataPortCount();

    PortType portType(int port);

    PortRole portRole(int port);

    /** Where a cord attached to this port starts, in world space: the middle of the port's outer face. */
    Vec3 portOrigin(int port);

    /** The port's click box, in world space. */
    AABB portBox(int port);

    /** The outward direction of the port's face, a unit vector in world space; the plug points along it. */
    Vec3 portNormal(int port);

    /** The link on this port, or null. */
    DataLink link(int port);

    /** Sets or, with null, clears the link on this port. The host saves and syncs it. */
    void setLink(int port, DataLink link);

    /** The port whose box contains {@code worldPoint}, nudged by a hair so a point on a face counts; -1 if none. */
    default int portAt(Vec3 worldPoint) {
        int best = -1;
        double bestVolume = Double.MAX_VALUE;
        for (int port = 0; port < dataPortCount(); port++) {
            AABB box = portBox(port).inflate(1e-3);
            if (!box.contains(worldPoint))
                continue;
            double volume = box.getXsize() * box.getYsize() * box.getZsize();
            if (volume < bestVolume) {
                best = port;
                bestVolume = volume;
            }
        }
        return best;
    }
}
