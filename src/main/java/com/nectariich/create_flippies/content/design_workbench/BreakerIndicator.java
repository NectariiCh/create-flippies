package com.nectariich.create_flippies.content.design_workbench;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import com.nectariich.create_flippies.content.rotary_switch.RotarySwitchContact;
import com.nectariich.create_flippies.content.rotary_switch.RotarySwitchKind;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.FloatTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.util.Mth;

/**
 * The flag of a designed breaker control switch: the parts of its model that show
 * which way it was last thrown, how they stand each way, and what its contacts
 * follow.
 *
 * A breaker control switch is a three-position switch whose handle springs back to
 * the centre from either side. Turning it clockwise latches the flag clockwise, and
 * turning it counter-clockwise latches it counter-clockwise; the flag stays where it
 * was latched while the handle goes back. The flag is a body of its own, turned
 * about a pivot of its own, exactly the way a switch's moving parts are posed by
 * {@link DesignMotion}: Euler degrees, Y first, then X, then Z.
 *
 * The latch and the contacts are worked out here from plain numbers, so the rules
 * can be tested without a level and the placed control and the workbench preview
 * agree about them.
 *
 * @param parts the model parts that make up the flag; never any of the moving parts
 * @param poses the flag's two poses, {@link #CCW} first and {@link #CW} second, each
 *              {x, y, z} in degrees
 * @param pivot where the flag turns about, in pixels, or null for
 *              {@link DesignMotion#DEFAULT_PIVOT}
 * @param mode  what the contacts follow
 */
public record BreakerIndicator(List<String> parts, float[][] poses, float[] pivot, BreakerMode mode) {

    public static final String TAG = "Indicator";

    /** The flag's two latched states, which are also the indexes of its poses. */
    public static final int CCW = 0, CW = 1;

    /**
     * Where the flag stands each way when a design sets nothing: the same quarter
     * turns about the mounting axis the handle makes to either side.
     */
    public static final float[][] DEFAULT_POSES = { { 0, 45, 0 }, { 0, -45, 0 } };

    /**
     * Everything is copied and held to the same limits as the rest of a design: at
     * most {@link ControlDesign#MAX_MOVING_PARTS} distinct parts, exactly two finite
     * poses within half a turn about every axis, and a finite pivot within reach.
     */
    public BreakerIndicator {
        Set<String> distinct = new LinkedHashSet<>();
        if (parts != null)
            for (String part : parts)
                if (part != null && distinct.size() < ControlDesign.MAX_MOVING_PARTS)
                    distinct.add(part.length() > ControlDesign.MAX_PART ? part.substring(0, ControlDesign.MAX_PART)
                            : part);
        parts = List.copyOf(distinct);
        float[][] clean = defaultPoses();
        if (poses != null)
            for (int i = 0; i < clean.length && i < poses.length; i++)
                if (poses[i] != null && poses[i].length == 3)
                    for (int axis = 0; axis < 3; axis++)
                        clean[i][axis] = Float.isFinite(poses[i][axis])
                                ? Mth.clamp(poses[i][axis], -ControlDesign.MAX_ANGLE, ControlDesign.MAX_ANGLE)
                                : 0;
        poses = clean;
        if (pivot != null) {
            if (pivot.length != 3) {
                pivot = null;
            } else {
                float[] out = new float[3];
                for (int axis = 0; axis < 3; axis++)
                    out[axis] = Float.isFinite(pivot[axis])
                            ? (float) Mth.clamp(pivot[axis], ControlDesign.MIN_COORD, ControlDesign.MAX_COORD)
                            : DesignMotion.DEFAULT_PIVOT[axis];
                pivot = out;
            }
        }
        if (mode == null)
            mode = BreakerMode.THREE_POSITION;
    }

    /** A flag with no parts yet, standing as the handle does, following the handle. */
    public static BreakerIndicator defaults() {
        return new BreakerIndicator(List.of(), defaultPoses(), null, BreakerMode.THREE_POSITION);
    }

    /** A fresh copy of {@link #DEFAULT_POSES}, safe to edit. */
    public static float[][] defaultPoses() {
        float[][] poses = new float[DEFAULT_POSES.length][];
        for (int i = 0; i < poses.length; i++)
            poses[i] = DEFAULT_POSES[i].clone();
        return poses;
    }

    /** The same flag without any of {@code moving}: a part either turns with the handle or is the flag, not both. */
    public BreakerIndicator without(Collection<String> moving) {
        if (moving.isEmpty() || parts.stream().noneMatch(moving::contains))
            return this;
        return new BreakerIndicator(parts.stream().filter(part -> !moving.contains(part)).toList(), poses, pivot,
                mode);
    }

    // ---------------------------------------------------------------- the latch

    /**
     * The flag's state after the handle has come to rest at {@code position}: turned
     * clockwise (a negative handle angle, as {@link RotarySwitchKind#handleAngle}
     * measures them) latches it clockwise, turned counter-clockwise latches it
     * counter-clockwise, and the centre leaves it as it was.
     */
    public static int latch(RotarySwitchKind kind, int latched, int position) {
        float angle = kind.handleAngle(position);
        if (angle < 0)
            return CW;
        if (angle > 0)
            return CCW;
        return latched == CW ? CW : CCW;
    }

    /**
     * Which of the kind's studs every section's common is on, or -1 for none. In
     * {@link BreakerMode#THREE_POSITION} that is the handle's stud, as on a plain
     * three-position switch. In {@link BreakerMode#SWITCHOVER} it is the stud the flag
     * is latched towards, the clockwise one or the counter-clockwise one, whatever the
     * handle is doing, so the neutral stud is never made.
     */
    public static int madeStud(BreakerMode mode, RotarySwitchKind kind, int position, int latched) {
        if (mode != BreakerMode.SWITCHOVER)
            return kind.madeStud(position);
        RotarySwitchContact wanted = latched == CW ? RotarySwitchContact.CLOCKWISE
                : RotarySwitchContact.COUNTER_CLOCKWISE;
        RotarySwitchContact[] studs = kind.studs();
        for (int stud = 0; stud < studs.length; stud++)
            if (studs[stud] == wanted)
                return stud;
        return -1;
    }

    /** A latched state off the wire or out of a save, as one of the two there are. */
    public static int sanitiseLatch(int latched) {
        return latched == CW ? CW : CCW;
    }

    // ---------------------------------------------------------------- saving

    public CompoundTag write() {
        CompoundTag tag = new CompoundTag();
        ListTag list = new ListTag();
        for (String part : parts)
            list.add(StringTag.valueOf(part));
        tag.put("Parts", list);
        ListTag flat = new ListTag();
        for (float[] pose : poses)
            for (float value : pose)
                flat.add(FloatTag.valueOf(value));
        tag.put("Poses", flat);
        if (pivot != null) {
            ListTag pivotList = new ListTag();
            for (float value : pivot)
                pivotList.add(FloatTag.valueOf(value));
            tag.put("Pivot", pivotList);
        }
        tag.putString("Mode", mode.id());
        return tag;
    }

    /**
     * Reads a flag, whoever wrote it. Poses that are missing are the defaults, a pivot
     * that is not three numbers is none, and an unknown mode follows the handle; the
     * constructor holds everything else to its limits.
     */
    public static BreakerIndicator read(CompoundTag tag) {
        ListTag list = tag.getList("Parts", Tag.TAG_STRING);
        List<String> parts = new ArrayList<>();
        for (int i = 0; i < list.size() && i < ControlDesign.MAX_MOVING_PARTS * 4; i++)
            parts.add(list.getString(i));
        float[][] poses = defaultPoses();
        ListTag flat = tag.getList("Poses", Tag.TAG_FLOAT);
        for (int i = 0; i < poses.length; i++) {
            if (flat.size() < (i + 1) * 3)
                break;
            for (int axis = 0; axis < 3; axis++)
                poses[i][axis] = flat.getFloat(i * 3 + axis);
        }
        ListTag pivotList = tag.getList("Pivot", Tag.TAG_FLOAT);
        float[] pivot = pivotList.size() == 3
                ? new float[] { pivotList.getFloat(0), pivotList.getFloat(1), pivotList.getFloat(2) }
                : null;
        return new BreakerIndicator(parts, poses, pivot, BreakerMode.of(tag.getString("Mode")));
    }
}
