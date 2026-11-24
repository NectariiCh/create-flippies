package com.nectariich.create_flippies.content.din_rail;

import java.util.LinkedHashSet;
import java.util.Set;

import com.nectariich.create_flippies.foundation.control.ControlMount;
import com.nectariich.create_flippies.foundation.control.ControlRackHolder;
import com.nectariich.create_flippies.foundation.control.HostedControl;
import com.nectariich.create_flippies.foundation.grid.PlacementGrid;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;

/**
 * The gear clipped right up against a piece of gear on the same rail, wherever the
 * rail runs: within a rail block, across into the next rail block, or along a
 * cabinet's rail through its host cells.
 *
 * Gear is centred on the middle of the modules it takes, so two pieces touch when
 * their middles are half their widths apart along the rail, on the same line
 * across it and the same plane, running the same way. Found by asking the cells
 * such a neighbour's middle could fall in and matching their controls against
 * that, so nothing needs to be told when gear is added or taken away.
 */
public final class RailNeighbours {

    private static final double PX = PlacementGrid.PIXELS_PER_BLOCK;
    private static final double EPSILON = 1.0e-3;

    /** Narrowest and widest gear this looks for, in modules. */
    private static final double MIN_WIDTH = 0.5;
    private static final double MAX_WIDTH = 1.0;

    /** Just off the plate, so a middle on a cell's face is never ambiguous about which cell it is in. */
    private static final double PROBE_Y = 1.5;

    private RailNeighbours() {}

    /**
     * The gear touching {@code width}-module gear at {@code mount}, in the host at
     * {@code pos}, on its {@code side} (−1 towards authored −x, +1 towards +x), or
     * null. Only gear up to a module wide is found.
     */
    public static HostedControl beside(Level level, BlockPos pos, ControlMount mount, double width, int side) {
        if (level == null || mount == null)
            return null;
        double[] own = world(pos, mount, 8);
        double[] unit = sub(world(pos, mount, 9), own);

        Set<BlockPos> cells = new LinkedHashSet<>();
        for (double other : new double[] { MIN_WIDTH, MAX_WIDTH }) {
            double reach = side * (width + other) / 2;
            cells.add(BlockPos.containing((own[0] + unit[0] * reach) / PX, (own[1] + unit[1] * reach) / PX,
                    (own[2] + unit[2] * reach) / PX));
        }
        for (BlockPos cell : cells) {
            if (!level.isLoaded(cell) || !(level.getBlockEntity(cell) instanceof ControlRackHolder host))
                continue;
            for (HostedControl control : host.controls()) {
                ControlMount other = control.mount();
                if (other == null || other.face() != mount.face()
                        || !(control.type() instanceof DinRailMountable gear))
                    continue;
                double[] middle = world(cell, other, 8);
                double[] delta = sub(middle, own);
                double along = dot(delta, unit);
                double[] perp = { delta[0] - unit[0] * along, delta[1] - unit[1] * along,
                        delta[2] - unit[2] * along };
                if (dot(perp, perp) > EPSILON * EPSILON)
                    continue;
                if (Math.abs(along - side * (width + gear.width()) / 2) > EPSILON)
                    continue;
                if (dot(sub(world(cell, other, 9), middle), unit) < 0.5)
                    continue;
                return control;
            }
        }
        return null;
    }

    /** An authored point of a control on its centre line, in world pixels. */
    private static double[] world(BlockPos pos, ControlMount mount, double x) {
        double[] p = mount.point(x, PROBE_Y, 8);
        return new double[] { pos.getX() * PX + p[0], pos.getY() * PX + p[1], pos.getZ() * PX + p[2] };
    }

    private static double[] sub(double[] a, double[] b) {
        return new double[] { a[0] - b[0], a[1] - b[1], a[2] - b[2] };
    }

    private static double dot(double[] a, double[] b) {
        return a[0] * b[0] + a[1] * b[1] + a[2] * b[2];
    }
}
