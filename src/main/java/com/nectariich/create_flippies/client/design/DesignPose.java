package com.nectariich.create_flippies.client.design;

import com.nectariich.create_flippies.foundation.SurfaceMount;
import com.nectariich.create_flippies.foundation.control.ControlMount;

import dev.nectariich.hypergraphical.api.ModelInstance;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;

/**
 * Where an imported model goes, in the world, for a Hypergraphical instance.
 *
 * The same chain every control is drawn through, {@code T(offset) . Ry(yaw) .
 * Rx(tilt) . Ry(spin)} about the middle of the host cell, with the model put into
 * authored space first by its design's scale and offset, and its moving parts then
 * carried by their pose. A Hypergraphical instance rotates about the model's origin,
 * so every rotation about some other point is folded into the instance's position:
 * the model origin's authored place, moved by the pose and turned about the middle,
 * is where the instance sits.
 */
public final class DesignPose {

    private static final float PIXEL = 1 / 16f;

    private DesignPose() {}

    /** The mount's whole rotation, with the handle turned {@code handleDegrees} about the mounting axis. */
    public static Quaternionf rotation(ControlMount mount, float handleDegrees) {
        return new Quaternionf()
                .rotateY((float) Math.toRadians(SurfaceMount.yawDegrees(mount.face(), mount.yaw())))
                .rotateX((float) Math.toRadians(SurfaceMount.tiltDegrees(mount.face())))
                .rotateY((float) Math.toRadians(SurfaceMount.spinDegrees(mount.rotation()) + handleDegrees));
    }

    /** The host cell's min corner, moved by the mount's displacement. */
    public static Vec3 origin(BlockPos host, ControlMount mount) {
        return Vec3.atLowerCornerOf(host).add(mount.offset());
    }

    /**
     * Places an instance whose model goes into authored space by {@code scale}
     * pixels per model unit and the offset in pixels, is then moved by
     * {@code authoredMotion} (a rigid transform of authored pixels, such as
     * {@link com.nectariich.create_flippies.content.design_workbench.DesignMotion#pose
     * DesignMotion.pose}, or identity for the body), and is finally turned by
     * {@code mount} about the middle of the cell whose min corner is {@code origin}.
     *
     * The whole chain is a rotation, a uniform scale and a translation, which is
     * exactly what an instance holds: the rotation is the mount's times the motion's,
     * the scale is the design's in blocks, and the position is where the chain takes
     * the model's origin.
     */
    public static void apply(ModelInstance instance, Vec3 origin, Quaternionf mount, Matrix4f authoredMotion,
            float scale, float offsetX, float offsetY, float offsetZ) {
        // The model's origin, in authored pixels, after the motion
        Vector3f local = authoredMotion.transformPosition(new Vector3f(offsetX, offsetY, offsetZ));
        // In blocks about the cell's middle, then turned with the mount
        local.mul(PIXEL).sub(0.5f, 0.5f, 0.5f);
        mount.transform(local);
        instance.setPosition(origin.x + 0.5 + local.x, origin.y + 0.5 + local.y, origin.z + 0.5 + local.z);
        instance.setRotation(new Quaternionf(mount).mul(authoredMotion.getNormalizedRotation(new Quaternionf())));
        instance.setScale(scale * PIXEL);
    }
}
