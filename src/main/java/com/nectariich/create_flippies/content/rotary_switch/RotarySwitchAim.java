package com.nectariich.create_flippies.content.rotary_switch;

import com.nectariich.create_flippies.foundation.control.ControlRackHolder;
import com.nectariich.create_flippies.foundation.SurfaceMount;
import com.nectariich.create_flippies.foundation.control.ControlMount;
import com.nectariich.create_flippies.foundation.control.HostedControl;
import com.nectariich.create_flippies.foundation.grid.PlacementGrid;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

/**
 * Which half of a rotary switch's front a point in the world falls on.
 *
 * One place answers it for all four callers that need to agree: the switch block's
 * own right-click, a host panel's right-click on the switch mounted in it, and the
 * client's crosshair for either. A crosshair that promised one direction while the
 * click did the other would be worse than no crosshair at all, so the arrow the
 * player sees and the turn the server makes come out of the same call — the same
 * arrangement {@code ScrewdriverOverlay} has with {@code ScrewdriverAim}.
 *
 * Everything is decided in the authored model frame, undoing whatever facing,
 * quarter turn and mount displacement put the switch where it is. That frame is
 * where the front is a known 4x4 square about the axis at (8, 8) and where left and
 * right mean what the printed markings mean.
 */
public final class RotarySwitchAim {

    /**
     * The mounting axis, and so the line the front is split down — and the middle of
     * the front, which is what a drag measures its distance from. Public because
     * {@code RotarySwitchDragInput} needs the same number: a threshold worked out
     * from a different centre than the one a click is judged against would put the
     * two gestures' idea of "left" a pixel apart.
     */
    public static final double AXIS = 8;

    private RotarySwitchAim() {}

    /**
     * The half of the switch under this hit, or null if the hit is not on a
     * switch's front at all.
     *
     * Null covers three cases that all have to do nothing: the block is not a
     * switch, no switch is mounted where the panel was clicked, and the hit is on
     * the back of the panel. The last one matters — the contact rack and its studs
     * hang through the wall into the next cell, and they are wired from back there,
     * not operated. A switch is turned from the front.
     */
    public static RotarySwitchSide sideAt(BlockGetter level, BlockHitResult hit) {
        BlockPos pos = hit.getBlockPos();
        Vec3 local = hit.getLocation().subtract(Vec3.atLowerCornerOf(pos));
        BlockState state = level.getBlockState(pos);
        if (state.getBlock() instanceof RotarySwitchBlock)
            return sideAt(local, state.getValue(RotarySwitchBlock.FACING),
                    state.getValue(RotarySwitchBlock.ROTATION));
        if (level.getBlockEntity(pos) instanceof ControlRackHolder host) {
            HostedControl control = host.controlAt(local);
            if (control instanceof RotarySwitchControl)
                return sideAt(local, control.mount());
        }
        return null;
    }

    /** As above, for a switch that is a block of its own, aimed by its block state. */
    public static RotarySwitchSide sideAt(Vec3 local, Direction facing, int rotation) {
        double[] p = pixels(local);
        return sideOf(SurfaceMount.localPoint(p[0], p[1], p[2], facing, rotation));
    }

    /** As above, for a switch mounted inside a host block. */
    public static RotarySwitchSide sideAt(Vec3 local, ControlMount mount) {
        double[] p = pixels(local);
        return sideOf(mount.localPoint(p[0], p[1], p[2]));
    }

    /**
     * An authored point's side of the axis, or null if it is behind the panel.
     *
     * The front lives at authored y from 0 to 1.75 — plate then handle — and every
     * part of the switch that is not the front is below zero, so the sign of y is
     * the whole of the front-or-back test. No dead band is left along the axis: the
     * front is four pixels across and half of it is little enough to aim at already.
     */
    private static RotarySwitchSide sideOf(double[] authored) {
        if (authored[1] < 0)
            return null;
        return authored[0] < AXIS ? RotarySwitchSide.LEFT : RotarySwitchSide.RIGHT;
    }

    /** A point in block units, in the pixels the authored frame is stated in. */
    private static double[] pixels(Vec3 local) {
        double scale = PlacementGrid.PIXELS_PER_BLOCK;
        return new double[] { local.x * scale, local.y * scale, local.z * scale };
    }
}
