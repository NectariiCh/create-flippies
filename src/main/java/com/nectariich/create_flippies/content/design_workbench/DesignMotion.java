package com.nectariich.create_flippies.content.design_workbench;

import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import com.nectariich.create_flippies.content.rotary_switch.RotarySwitchKind;

import net.minecraft.util.Mth;

/**
 * How a design's moving parts move: the transform that carries them, in authored
 * pixels, from where the model puts them to where they stand at a position.
 *
 * Pure arithmetic, so the server can box the sweep of a handle with exactly the
 * motion the client draws it with. Positions are fractional: 0 is the first pose,
 * {@code positions - 1} the last, and anything between is a blend of the two poses
 * either side, which is what lets a renderer ease a handle between detents.
 *
 * A switch's pose is a rotation about its {@link ControlDesign#pivot() pivot}, given
 * as Euler degrees and applied Y first, then X, then Z. Y comes first so a pose
 * that only turns about Y is exactly the old single angle about the mounting axis,
 * and designs made before poses existed move as they always did. Poses are blended
 * by spherical interpolation, so a handle swings along the short arc rather than
 * wobbling through each Euler axis in turn. A button's pose is a translation, blended
 * in a straight line.
 *
 * A gauge's needle has two poses, at the minimum and at the maximum of its scale,
 * and its position is the reading's progress along the scale, a little past either
 * end when it pegs. A sliding needle's poses are translations, as a button's are; a
 * turning one's are rotations about the pivot like a switch's, but blended angle by
 * angle rather than along the short arc, since a dial's sweep is often more than half
 * a turn.
 */
public final class DesignMotion {

    /** The middle of the mounting plate: where a switch turns when its design names no pivot. */
    public static final float[] DEFAULT_PIVOT = { 8, 0, 8 };

    /** How finely {@link #sweep} samples the motion between two poses, and a gauge needle's whole travel. */
    private static final int SWEEP_STEPS = 8, NEEDLE_SWEEP_STEPS = 32;

    /** How far past either end of its scale a gauge needle can be put. */
    private static final float NEEDLE_OVERSWING = 0.1f;

    private DesignMotion() {}

    /**
     * Where the moving parts stand at {@code position}, as a transform of authored
     * space. Clamped to the design's positions, so an eased value that overshoots a
     * little still lands on the end pose.
     */
    public static Matrix4f pose(ControlDesign design, float position) {
        return pose(design.kind(), design.slides(), design.poses(), design.pivot(), position);
    }

    /**
     * The same as {@link #pose(ControlDesign, float)}, from the parts of a design
     * that move it, for a workbench that has not made the design yet.
     *
     * @param pivot the switch's pivot, or null for {@link #DEFAULT_PIVOT}
     */
    public static Matrix4f pose(DesignKind kind, float[][] poses, float[] pivot, float position) {
        return pose(kind, kind.isButton(), poses, pivot, position);
    }

    /**
     * The same, saying whether the poses are translations ({@code slide}) or
     * rotations, which a gauge's needle can be either of.
     */
    public static Matrix4f pose(DesignKind kind, boolean slide, float[][] poses, float[] pivot, float position) {
        int last = poses.length - 1;
        if (last < 0)
            return new Matrix4f();
        boolean needle = kind.isGauge();
        float overswing = needle ? NEEDLE_OVERSWING : 0;
        float clamped = Float.isFinite(position) ? Mth.clamp(position, -overswing, last + overswing) : 0;
        int from = Mth.clamp((int) Math.floor(clamped), 0, Math.max(0, last - 1));
        int to = Math.min(from + 1, last);
        float t = clamped - from;
        float[] a = poses[from], b = poses[to];

        if (slide)
            return new Matrix4f().translation(Mth.lerp(t, a[0], b[0]), Mth.lerp(t, a[1], b[1]),
                    Mth.lerp(t, a[2], b[2]));

        Quaternionf rotation = needle
                ? rotation(new float[] { Mth.lerp(t, a[0], b[0]), Mth.lerp(t, a[1], b[1]), Mth.lerp(t, a[2], b[2]) })
                : rotation(a).slerp(rotation(b), t);
        float[] centre = pivot != null ? pivot : DEFAULT_PIVOT;
        return new Matrix4f().translation(centre[0], centre[1], centre[2]).rotate(rotation)
                .translate(-centre[0], -centre[1], -centre[2]);
    }

    /**
     * Where a breaker's flag stands at {@code position}: 0 is latched
     * counter-clockwise, 1 clockwise, and anything between is a blend of the two, so
     * a renderer can ease the flag over as it does the handle. The flag turns about
     * its own pivot exactly as a switch's moving parts turn about theirs. Nothing
     * moves for a design with no flag.
     */
    public static Matrix4f indicatorPose(ControlDesign design, float position) {
        BreakerIndicator indicator = design.indicator();
        if (indicator == null)
            return new Matrix4f();
        return pose(design.kind(), indicator.poses(), indicator.pivot(), position);
    }

    /** A switch pose's rotation: Y, then X, then Z, each in degrees. */
    public static Quaternionf rotation(float[] degrees) {
        return new Quaternionf().rotateY((float) Math.toRadians(degrees[1]))
                .rotateX((float) Math.toRadians(degrees[0])).rotateZ((float) Math.toRadians(degrees[2]));
    }

    /**
     * The fractional position a switch's handle angle stands for. The control eases
     * a single angle between its detents ({@code RotarySwitchControl.handleAngle}),
     * so this undoes the detent angles piecewise linearly: halfway between the
     * angles of two detents is halfway between their poses. An angle past either end
     * is the end position.
     */
    public static float switchPosition(RotarySwitchKind kind, float handleAngle) {
        int positions = kind.positions();
        if (positions < 2 || !Float.isFinite(handleAngle))
            return 0;
        for (int i = 0; i < positions - 1; i++) {
            float from = kind.handleAngle(i), to = kind.handleAngle(i + 1);
            if (from == to)
                continue;
            float t = (handleAngle - from) / (to - from);
            if (t >= 0 && t <= 1)
                return i + t;
        }
        float first = kind.handleAngle(0), last = kind.handleAngle(positions - 1);
        return Math.abs(handleAngle - first) <= Math.abs(handleAngle - last) ? 0 : positions - 1;
    }

    /** The position of a button: pressed is the second pose. A renderer may ease between them. */
    public static float buttonPosition(boolean pressed) {
        return pressed ? 1 : 0;
    }

    /**
     * Everywhere {@code box} goes as the design moves through all its positions: the
     * box's corners carried by the pose at every eighth of the way between poses,
     * boxed again. Fine enough that a handle cannot swing noticeably outside it
     * between samples, which is what keeps a neighbour from being placed in its way.
     */
    public static double[] sweep(double[] box, ControlDesign design) {
        return sweep(box, design.kind(), design.slides(), design.poses(), design.pivot());
    }

    /** The same as {@link #sweep(double[], ControlDesign)}, from the parts of a design that move it. */
    public static double[] sweep(double[] box, DesignKind kind, float[][] poses, float[] pivot) {
        return sweep(box, kind, kind.isButton(), poses, pivot);
    }

    /**
     * The same, saying whether the poses are translations. A gauge needle is swept
     * more finely, since one pair of poses can be most of a turn apart, and a little
     * past both ends, where it pegs.
     */
    public static double[] sweep(double[] box, DesignKind kind, boolean slide, float[][] poses, float[] pivot) {
        double[] out = { Double.MAX_VALUE, Double.MAX_VALUE, Double.MAX_VALUE, -Double.MAX_VALUE,
                -Double.MAX_VALUE, -Double.MAX_VALUE };
        int steps = kind.isGauge() ? NEEDLE_SWEEP_STEPS : SWEEP_STEPS;
        int samples = Math.max(0, poses.length - 1) * steps;
        for (int step = 0; step <= samples; step++)
            union(out, transform(box, pose(kind, slide, poses, pivot, (float) step / steps)));
        if (kind.isGauge()) {
            union(out, transform(box, pose(kind, slide, poses, pivot, -NEEDLE_OVERSWING)));
            union(out, transform(box, pose(kind, slide, poses, pivot, poses.length - 1 + NEEDLE_OVERSWING)));
        }
        return out;
    }

    /** A box carried by a transform of authored space: its eight corners, boxed again. */
    public static double[] transform(double[] box, Matrix4f transform) {
        double[] out = { Double.MAX_VALUE, Double.MAX_VALUE, Double.MAX_VALUE, -Double.MAX_VALUE,
                -Double.MAX_VALUE, -Double.MAX_VALUE };
        Vector3f corner = new Vector3f();
        for (int c = 0; c < 8; c++) {
            corner.set((c & 1) == 0 ? box[0] : box[3], (c & 2) == 0 ? box[1] : box[4],
                    (c & 4) == 0 ? box[2] : box[5]);
            transform.transformPosition(corner);
            out[0] = Math.min(out[0], corner.x);
            out[1] = Math.min(out[1], corner.y);
            out[2] = Math.min(out[2], corner.z);
            out[3] = Math.max(out[3], corner.x);
            out[4] = Math.max(out[4], corner.y);
            out[5] = Math.max(out[5], corner.z);
        }
        return out;
    }

    /** Grows {@code into} to hold {@code box} as well. */
    private static void union(double[] into, double[] box) {
        for (int axis = 0; axis < 3; axis++) {
            into[axis] = Math.min(into[axis], box[axis]);
            into[axis + 3] = Math.max(into[axis + 3], box[axis + 3]);
        }
    }
}
