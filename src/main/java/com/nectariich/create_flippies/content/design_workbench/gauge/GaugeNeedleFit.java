package com.nectariich.create_flippies.content.design_workbench.gauge;

import com.nectariich.create_flippies.content.procedural_gauge.EdgewiseFaceDefinition;

/**
 * Fits an edgewise gauge's needle to its printed scale: the two poses (at the
 * minimum, then at the maximum) that put the needle level with the ends of the
 * scale the face generator printed, {@link EdgewiseFaceDefinition#scaleBottom} and
 * {@link EdgewiseFaceDefinition#scaleTop}.
 *
 * Everything is in authored pixels. It takes the face as the workbench shows it
 * from the front: lying in the plane of the panel, with the top of the printed face
 * towards the smallest z, which is where the face lands when its submesh's UVs run
 * the same way as the model (V down the face as z grows). The needle is taken to
 * point where the middle of its box is, which is where a slim pointer drawn across
 * the scale points. A face mapped some other way, or a needle whose tip is not its
 * middle, fits by setting the poses by hand.
 */
public final class GaugeNeedleFit {

    private GaugeNeedleFit() {}

    /** Where along z the scale's minimum and maximum are printed on a face occupying {@code faceBox}. */
    public static float[] scaleEnds(EdgewiseFaceDefinition face, double[] faceBox) {
        double top = faceBox[2], height = faceBox[5] - faceBox[2];
        return new float[] { (float) (top + face.scaleBottom * height), (float) (top + face.scaleTop * height) };
    }

    /** A sliding needle's poses: translations along z, in pixels, from where it is modelled to each end. */
    public static float[][] slide(EdgewiseFaceDefinition face, double[] faceBox, double[] needleBox) {
        float[] ends = scaleEnds(face, faceBox);
        float at = (float) (needleBox[2] + needleBox[5]) / 2;
        return new float[][] { { 0, 0, ends[0] - at }, { 0, 0, ends[1] - at } };
    }

    /**
     * A swinging needle's poses: turns about the x axis through {@code pivot}, in
     * degrees, that carry the middle of the needle level with each end of the scale,
     * as DHE's pointer swings on its arm behind the curved face. An end the arm is
     * too short to reach is reached as nearly as it can be.
     */
    public static float[][] swing(EdgewiseFaceDefinition face, double[] faceBox, double[] needleBox, float[] pivot) {
        float[] ends = scaleEnds(face, faceBox);
        double dy = (needleBox[1] + needleBox[4]) / 2 - pivot[1];
        double dz = (needleBox[2] + needleBox[5]) / 2 - pivot[2];
        double arm = Math.sqrt(dy * dy + dz * dz);
        if (arm < 1e-6)
            return new float[][] { { 0, 0, 0 }, { 0, 0, 0 } };
        // A turn about +x by a carries (y, z) at angle b from +y towards +z to angle b + a
        double now = Math.atan2(dz, dy);
        return new float[][] { { angle(ends[0] - pivot[2], arm, dy, now), 0, 0 },
                { angle(ends[1] - pivot[2], arm, dy, now), 0, 0 } };
    }

    private static float angle(double targetZ, double arm, double dy, double now) {
        double reach = Math.asin(Math.max(-1, Math.min(1, targetZ / arm)));
        // An arm hanging below its pivot reaches the same z on the far side of the turn
        if (dy < 0)
            reach = Math.PI - reach;
        double turn = reach - now;
        // The short way round
        while (turn > Math.PI)
            turn -= 2 * Math.PI;
        while (turn < -Math.PI)
            turn += 2 * Math.PI;
        return (float) Math.toDegrees(turn);
    }
}
