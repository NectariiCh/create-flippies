package com.nectariich.create_flippies.content.indicator;

import com.nectariich.create_flippies.foundation.SurfaceMount;
import com.nectariich.create_flippies.foundation.control.ControlMount;
import com.nectariich.create_flippies.foundation.control.ControlRackHolder;
import com.nectariich.create_flippies.foundation.control.HostedControl;
import com.nectariich.create_flippies.foundation.grid.PlacementGrid;

import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * Whether a click landed on an indicator's cap, and on which of the two forms an
 * indicator comes in.
 *
 * Asked by the client, which decides whether to take the use key away from whatever
 * else it would have done, and by the server, which decides whether the packet that
 * followed means anything. Both go through here rather than each testing its own
 * way, for the reason {@code RotarySwitchAim} exists: a client that thought it was
 * on a button where the server thought it was not would swallow clicks that do
 * nothing at all.
 *
 * Only the cap counts. Everything else the unit has — the barrel, and the eight
 * contacts behind the panel — is wired rather than operated, and a click there is a
 * wire connection that Power Grid answers long before this is asked. So the test is
 * the cap box and nothing wider, which is also what makes it honest on a panel: two
 * units mounted a pixel and three quarters apart have caps a pixel across, and a
 * press must not carry to the neighbour whose contacts happen to reach nearer.
 */
public final class IndicatorAim {

    /**
     * How much slack the cap box is given, in block units.
     *
     * A click that hits the front face of the cap lands exactly on the boundary of
     * the box, and the point has been through a float on its way from the ray trace.
     * A thousandth of a block is far below the pixel the cap is across and far above
     * anything a float can lose.
     */
    private static final double SLACK = 1.0e-3;

    private IndicatorAim() {}

    /**
     * The indicator whose cap is under {@code local}, or null if there is none.
     *
     * {@code local} is the aim as an offset within the block at {@code pos}, in block
     * units — the same point a host resolves a control from.
     */
    public static Target at(Level level, BlockPos pos, Vec3 local) {
        if (level == null || !level.isLoaded(pos))
            return null;
        BlockState state = level.getBlockState(pos);
        if (state.getBlock() instanceof IndicatorBlock block) {
            if (!(level.getBlockEntity(pos) instanceof IndicatorBlockEntity unit))
                return null;
            double[] cap = SurfaceMount.box(block.getSize().lens(), state.getValue(IndicatorBlock.FACING),
                    state.getValue(IndicatorBlock.ROTATION));
            return holds(cap, local) ? new Target(unit, null) : null;
        }
        if (!(level.getBlockEntity(pos) instanceof ControlRackHolder host))
            return null;
        HostedControl control = host.controlAt(local);
        if (!(control instanceof IndicatorControl unit))
            return null;
        ControlMount mount = unit.mount();
        return holds(mount.box(unit.capBox()), local) ? new Target(null, unit) : null;
    }

    /** Whether a click there is worth taking away from whatever else it would do. */
    public static boolean isButton(Level level, BlockPos pos, Vec3 local) {
        Target target = at(level, pos, local);
        return target != null && target.action().clickable();
    }

    /** An authored pixel box, in block units, holds a point with a little to spare. */
    private static boolean holds(double[] pixels, Vec3 local) {
        double scale = PlacementGrid.PIXELS_PER_BLOCK;
        return new AABB(pixels[0] / scale, pixels[1] / scale, pixels[2] / scale,
                pixels[3] / scale, pixels[4] / scale, pixels[5] / scale).inflate(SLACK).contains(local);
    }

    /**
     * One indicator, whichever form it is in.
     *
     * Exactly one of the two is set. A pair rather than an interface both forms
     * implement, because the two share no supertype worth inventing — one is a block
     * entity and the other is an object a host owns — and what is wanted of them here
     * is three calls.
     */
    public record Target(IndicatorBlockEntity block, IndicatorControl control) {

        public IndicatorAction action() {
            return block != null ? block.action() : control.action();
        }

        public boolean isPressed() {
            return block != null ? block.isPressed() : control.isPressed();
        }

        /** Server-side: the use key has gone down on this cap. */
        public void press(Player player) {
            if (block != null)
                block.press(player);
            else
                control.press(player);
        }

        /** Server-side: the use key has come up. */
        public void release(Player player) {
            if (block != null)
                block.release(player);
            else
                control.release(player);
        }
    }
}
