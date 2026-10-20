package com.nectariich.create_flippies.client.design;

import java.util.Map;
import java.util.Set;

import com.nectariich.create_flippies.content.design_workbench.DesignKind;
import com.nectariich.create_flippies.content.design_workbench.DesignMotion;

import dev.nectariich.hypergraphical.api.MeshInfo;
import dev.nectariich.hypergraphical.mesh.MeshBounds;

/**
 * Turns a model's bounds, as Hypergraphical measured them, into the boxes a designed
 * control is picked and placed by: everything in front of the plate, and the parts a
 * press lands on. Both are in authored pixels, the model's units times the scale and
 * moved by the offset.
 *
 * The front covers everywhere the moving parts go, the way the original switch's
 * footprint covers the disc its arm sweeps, so a neighbour cannot be placed where a
 * handle will turn into it or a button will travel into it. The motion is
 * {@link DesignMotion}'s, the same one the control is drawn with.
 */
public final class DesignGeometry {

    public record Boxes(double[] front, double[] moving) {}

    private DesignGeometry() {}

    public static double[] authored(MeshBounds bounds, float scale, float offsetX, float offsetY, float offsetZ) {
        return new double[] { offsetX + scale * bounds.minX(), offsetY + scale * bounds.minY(),
                offsetZ + scale * bounds.minZ(), offsetX + scale * bounds.maxX(), offsetY + scale * bounds.maxY(),
                offsetZ + scale * bounds.maxZ() };
    }

    /**
     * The boxes of a design in progress.
     *
     * The moving box is the union of the moving parts, or the whole model when none
     * of them is in it. A switch's is where its parts are modelled, since that is
     * only an outline for the workbench; a button's is where its parts stand
     * released, because that is where a press has to land. The front is the body
     * (every other part) together with the moving box swept through every pose.
     *
     * @param poses one pose per position, as {@link DesignMotion} reads them
     * @param pivot a switch's pivot in pixels, or null for the default
     */
    public static Boxes compute(DesignKind kind, MeshInfo info, Set<String> movingParts, float scale,
            float offsetX, float offsetY, float offsetZ, float[][] poses, float[] pivot) {
        return compute(kind, kind.isButton(), info, movingParts, scale, offsetX, offsetY, offsetZ, poses, pivot,
                Set.of(), null, null);
    }

    /**
     * The same, saying whether the poses are translations: a gauge's needle can
     * slide as well as turn. A gauge's moving box is its needle where it is modelled.
     */
    public static Boxes compute(DesignKind kind, boolean slide, MeshInfo info, Set<String> movingParts,
            float scale, float offsetX, float offsetY, float offsetZ, float[][] poses, float[] pivot) {
        return compute(kind, slide, info, movingParts, scale, offsetX, offsetY, offsetZ, poses, pivot, Set.of(),
                null, null);
    }

    /**
     * The same, for a design with a breaker's flag as well: the flag's parts are
     * taken out of the body and swept through both of the flag's poses about its own
     * pivot instead, so the front covers wherever the flag can stand and everywhere
     * it passes on the way. A part that is moving is never counted as the flag.
     *
     * @param indicatorParts  the flag's parts, empty for none
     * @param indicatorPoses  the flag's two poses, as {@link DesignMotion} reads them;
     *                        ignored when there are no flag parts
     * @param indicatorPivot  the flag's pivot in pixels, or null for the default
     */
    public static Boxes compute(DesignKind kind, MeshInfo info, Set<String> movingParts, float scale,
            float offsetX, float offsetY, float offsetZ, float[][] poses, float[] pivot,
            Set<String> indicatorParts, float[][] indicatorPoses, float[] indicatorPivot) {
        return compute(kind, kind.isButton(), info, movingParts, scale, offsetX, offsetY, offsetZ, poses, pivot,
                indicatorParts, indicatorPoses, indicatorPivot);
    }

    private static Boxes compute(DesignKind kind, boolean slide, MeshInfo info, Set<String> movingParts,
            float scale, float offsetX, float offsetY, float offsetZ, float[][] poses, float[] pivot,
            Set<String> indicatorParts, float[][] indicatorPoses, float[] indicatorPivot) {
        MeshBounds moving = null, body = null, indicator = null;
        for (Map.Entry<String, MeshBounds> part : info.parts().entrySet()) {
            MeshBounds bounds = part.getValue();
            if (movingParts.contains(part.getKey()))
                moving = moving == null ? bounds : moving.union(bounds);
            else if (indicatorPoses != null && indicatorParts.contains(part.getKey()))
                indicator = indicator == null ? bounds : indicator.union(bounds);
            else
                body = body == null ? bounds : body.union(bounds);
        }
        if (moving == null)
            moving = info.bounds();

        double[] modelled = authored(moving, scale, offsetX, offsetY, offsetZ);
        double[] front = DesignMotion.sweep(modelled, kind, slide, poses, pivot);
        double[] movingBox = kind.isButton() && poses.length > 0
                ? DesignMotion.transform(modelled, DesignMotion.pose(kind, poses, pivot, 0))
                : modelled;
        if (body != null)
            front = union(front, authored(body, scale, offsetX, offsetY, offsetZ));
        if (indicator != null)
            front = union(front, DesignMotion.sweep(authored(indicator, scale, offsetX, offsetY, offsetZ), kind,
                    indicatorPoses, indicatorPivot));
        return new Boxes(front, movingBox);
    }

    /**
     * How high the model stands at a point of the plate: the top of the highest
     * part whose box, seen from above, covers ({@code x}, {@code z}), or 0 where none
     * does. Where a new text box goes, so it starts on the surface under it rather
     * than buried in it.
     *
     * @param visibleParts the parts to consider, or null for all of them
     */
    public static double topAt(MeshInfo info, Set<String> visibleParts, float scale, float offsetX, float offsetY,
            float offsetZ, double x, double z) {
        double top = 0;
        for (Map.Entry<String, MeshBounds> part : info.parts().entrySet()) {
            if (visibleParts != null && !visibleParts.contains(part.getKey()))
                continue;
            double[] box = authored(part.getValue(), scale, offsetX, offsetY, offsetZ);
            if (x >= box[0] && x <= box[3] && z >= box[2] && z <= box[5])
                top = Math.max(top, box[4]);
        }
        return top;
    }

    static double[] union(double[] a, double[] b) {
        return new double[] { Math.min(a[0], b[0]), Math.min(a[1], b[1]), Math.min(a[2], b[2]),
                Math.max(a[3], b[3]), Math.max(a[4], b[4]), Math.max(a[5], b[5]) };
    }
}
