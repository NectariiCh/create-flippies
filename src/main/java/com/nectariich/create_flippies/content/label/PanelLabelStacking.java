package com.nectariich.create_flippies.content.label;

import java.util.ArrayList;
import java.util.List;

import com.nectariich.create_flippies.foundation.control.ControlFootprint;
import com.nectariich.create_flippies.foundation.control.ControlMount;
import com.nectariich.create_flippies.foundation.control.ControlRackHolder;
import com.nectariich.create_flippies.foundation.control.HostedControl;

import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * Which layer a new label goes on: one above the highest label its strip is laid
 * over, or the panel itself when it covers none.
 *
 * Labels may overlap. The host refuses only a control centred exactly where another
 * already is (see {@code ControlRack.refusal}), so a strip drawn across part of an
 * older one goes on, and before stacking it went on at the same depth, where the two
 * plates and the two legends fought over every pixel they shared. A strip is also
 * free to overhang its cell, so the label it covers may well be hosted by the block
 * next door; the search below looks at every host the two could share a surface in.
 *
 * Overlap means sharing area on one surface, which is the only overlap that can
 * flicker: the same face and turn, the plates in the same plane, and the strips'
 * extents meeting along both of its axes by more than a rounding. Strips that only
 * touch along an edge are side by side, not stacked.
 */
public final class PanelLabelStacking {

    /** Below anything the mount lattice can express, which is 1/256 of a block. */
    private static final double SLACK = 1.0e-6;

    /**
     * How far round the new strip to look for hosts, in blocks. A strip reaches at
     * most a block from its centre along each of its own axes, and on a chamfer those
     * axes are diagonal, so a covered label's centre can be up to root two of a block
     * off the new strip along a world axis.
     */
    private static final double SEARCH = 1.5;

    /** A label already mounted, as the rule needs it: where its host is and what it covers. */
    public record Placed(Vec3 host, ControlMount mount, ControlFootprint footprint, int layer) {}

    private PanelLabelStacking() {}

    /** The layer for a label about to go on at {@code mount} in the host at {@code host}. */
    public static int layerFor(BlockGetter level, BlockPos host, ControlMount mount, ControlFootprint footprint) {
        AABB around = footprint.bounds(mount).move(host).inflate(SEARCH);
        List<Placed> nearby = new ArrayList<>();
        for (BlockPos pos : BlockPos.betweenClosed(Mth.floor(around.minX), Mth.floor(around.minY),
                Mth.floor(around.minZ), Mth.floor(around.maxX), Mth.floor(around.maxY), Mth.floor(around.maxZ)))
            if (level.getBlockEntity(pos) instanceof ControlRackHolder holder)
                for (HostedControl control : holder.controls())
                    if (control instanceof PanelLabelControl label)
                        nearby.add(new Placed(Vec3.atLowerCornerOf(pos), label.mount(), label.footprint(),
                                label.layer()));
        return layerOver(Vec3.atLowerCornerOf(host), mount, footprint, nearby);
    }

    /** The whole rule, over labels already gathered; see the class comment. */
    public static int layerOver(Vec3 host, ControlMount mount, ControlFootprint footprint, List<Placed> others) {
        int top = -1;
        for (Placed other : others)
            if (overlaps(host, mount, footprint, other.host(), other.mount(), other.footprint()))
                top = Math.max(top, other.layer());
        return PanelLabel.layer(top + 1);
    }

    /** Whether two strips share area on one surface, each given with the corner of its host block. */
    public static boolean overlaps(Vec3 host, ControlMount mount, ControlFootprint footprint, Vec3 otherHost,
            ControlMount other, ControlFootprint otherFootprint) {
        if (mount.face() != other.face() || mount.yaw() != other.yaw())
            return false;
        Vec3 apart = otherHost.add(other.centre()).subtract(host.add(mount.centre()));
        if (Math.abs(apart.dot(mount.alongDepth())) > SLACK)
            return false;
        return meet(host, mount, footprint, otherHost, other, otherFootprint, mount.alongU())
                && meet(host, mount, footprint, otherHost, other, otherFootprint, mount.alongV());
    }

    /** Whether the two strips' extents along one axis of the surface share more than an edge. */
    private static boolean meet(Vec3 host, ControlMount mount, ControlFootprint footprint, Vec3 otherHost,
            ControlMount other, ControlFootprint otherFootprint, Vec3 along) {
        double[] mine = footprint.range(mount, along);
        double[] theirs = otherFootprint.range(other, along);
        double shift = otherHost.subtract(host).dot(along);
        return mine[0] < theirs[1] + shift - SLACK && theirs[0] + shift < mine[1] - SLACK;
    }
}
