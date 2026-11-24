package com.nectariich.create_flippies.content.din_rail;

import com.nectariich.create_flippies.foundation.SurfaceMount;
import com.nectariich.create_flippies.foundation.control.ControlMount;
import com.nectariich.create_flippies.foundation.control.ControlRackHolder;
import com.nectariich.create_flippies.foundation.control.ControlType;
import com.nectariich.create_flippies.foundation.grid.PlacementGrid;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Direction.Axis;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;

/**
 * How gear sits on a DIN rail, whoever is holding the rail.
 *
 * There are two rails in the game: the block, which carries one or two rails
 * across its own cell, and a rail screwed to the back plate of an electrical
 * cabinet, which runs as far as the player drew it and is carried by invisible
 * host blocks in the cells it passes through. Both seat gear the same way — a
 * breaker snaps to whole modules along the rail, a half-module relay mount to half
 * modules, and the gear is centred on the rail's centre line — so the arithmetic
 * lives here once and both ask it.
 *
 * Everything is in the frame a rail is authored in, the one {@link SurfaceMount}
 * describes: {@code x} along the rail, {@code y} up off the mounting plane,
 * {@code z} across. The block's rails lie on the floor of their own cell; a
 * cabinet's lie on the back plate, which stands a pixel (or more) off the face of
 * the cell it is in, and that lift is the one thing the block never needs. It is
 * carried by the mount's depth anchor, which a cardinal mount otherwise always
 * leaves on the face.
 */
public final class DinRailSeat {

    private DinRailSeat() {}

    /**
     * Where gear {@code width} modules wide starts when a click lands {@code clicked}
     * along a rail running from {@code from} to {@code to}, all in authored pixels.
     *
     * The module clicked is where the gear starts, and it is pulled back far enough
     * that a wide breaker aimed at the end of the rail still lands on it rather than
     * being refused for hanging off. Modules are whole pixels, so a breaker lands on
     * a pixel and cannot land between two; gear of a fractional width snaps to half
     * modules instead, which is still a whole number of a mount's sixteenths.
     */
    public static double snapStart(double clicked, double width, double from, double to) {
        double step = width == Math.rint(width) ? 1 : 0.5;
        return Mth.clamp(Math.floor(clicked / step) * step, from, to - width);
    }

    /**
     * The point on the mounting plane that gear starting at {@code start} along a
     * rail centred {@code railCentre} across is centred on, in the host cell's
     * pixels. The gear straddles the middle of the modules it occupies, which for an
     * even count is a module boundary and for an odd one the middle of a module.
     *
     * The point may lie outside the cell along the rail: a cabinet's rail runs on
     * through its neighbours, and the gear then belongs to whichever cell its middle
     * is in.
     */
    public static double[] target(Direction facing, int rotation, double railCentre, double start, double width) {
        return SurfaceMount.point(start + width / 2, 0, railCentre, facing, rotation);
    }

    /**
     * The mount that centres gear on {@code target}, a point on the mounting plane
     * in the host cell's pixels, with the plate lifted {@code plateOffsetUnits}
     * sixteenths of a pixel off the cell's face.
     *
     * Worked out by asking where the orientation puts the point the gear should be
     * centred on, and where it puts the point a centred mount would leave it at; the
     * difference is what the anchors have to make up. Deriving it rather than
     * reasoning about which way round u and v run is the whole point — the answer
     * is right for all twelve orientations without a table, and it stays right if
     * {@link PlacementGrid}'s in-plane axes are ever redefined.
     *
     * Anchors are whole sixteenths of a pixel. At one and two rails to a block the
     * centres are 8, or 4 and 12, and a cabinet's rails run on whole pixels, so
     * every one of them lands on that lattice exactly and nothing is rounded away.
     *
     * The lift moves the plate against the mount's facing, which points into the
     * surface: a lift of nothing is {@link ControlMount#ON_FACE}, exactly the mount
     * a rail block has always made.
     */
    public static ControlMount seat(Direction facing, int rotation, double[] target, int plateOffsetUnits) {
        double[] middle = SurfaceMount.point(PlacementGrid.PIXELS_PER_BLOCK / 2.0, 0,
                PlacementGrid.PIXELS_PER_BLOCK / 2.0, facing, rotation);
        return new ControlMount(facing, 0,
                anchor(target, middle, PlacementGrid.uAxis(facing.getAxis())),
                anchor(target, middle, PlacementGrid.vAxis(facing.getAxis())),
                ControlMount.ON_FACE - plateOffsetUnits,
                rotation);
    }

    private static int anchor(double[] target, double[] middle, Axis axis) {
        int index = axis.choose(0, 1, 2);
        return ControlMount.CENTRED
                + (int) Math.round((target[index] - middle[index]) * ControlMount.UNITS_PER_PIXEL);
    }

    /**
     * Clips gear of {@code type} on at {@code mount} in {@code host}, which is at
     * {@code pos}, taking one from {@code held} unless the player is in creative.
     *
     * A null mount is a click that seats nothing and passes on. A refused one is
     * still consumed: the rack has told the player why, and letting the click fall
     * through would place the item against the rail instead.
     */
    public static InteractionResult clipOn(Level level, ControlRackHolder host, BlockPos pos, ControlType type,
            ControlMount mount, Player player, ItemStack held) {
        if (mount == null)
            return InteractionResult.PASS;
        if (level.isClientSide)
            return InteractionResult.SUCCESS;
        if (!host.install(type, mount, player).ok())
            return InteractionResult.CONSUME;

        level.playSound(null, pos, level.getBlockState(pos).getSoundType().getPlaceSound(), SoundSource.BLOCKS, 1.0f,
                1.2f);
        if (!player.isCreative())
            held.shrink(1);
        return InteractionResult.CONSUME;
    }
}
