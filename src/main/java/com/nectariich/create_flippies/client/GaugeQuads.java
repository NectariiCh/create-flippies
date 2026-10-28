package com.nectariich.create_flippies.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.nectariich.create_flippies.compat.Verts;

import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.resources.ResourceLocation;

import org.joml.Matrix3f;
import org.joml.Matrix4f;

/**
 * Single quads for the procedurally drawn gauges: a baked face texture stretched
 * over a rectangle, or a flat coloured rectangle for a pointer. Corners are given in
 * ring order, and the texture's top left lands on the first, its top right on the
 * second. Drawn without culling, so the ring may wind either way.
 */
public final class GaugeQuads {

    private GaugeQuads() {}

    /** The whole of {@code texture} over the quad {@code a b c d}. */
    public static void textured(PoseStack ms, MultiBufferSource buffer, ResourceLocation texture, int light,
            float[] a, float[] b, float[] c, float[] d, float nx, float ny, float nz) {
        quad(ms, buffer.getBuffer(RenderType.entityCutoutNoCull(texture)), 0xFFFFFFFF, light, a, b, c, d, nx, ny,
                nz);
    }

    /** The quad {@code a b c d} in one colour. */
    public static void flat(PoseStack ms, MultiBufferSource buffer, int argb, int light, float[] a, float[] b,
            float[] c, float[] d, float nx, float ny, float nz) {
        quad(ms, buffer.getBuffer(RenderType.entityCutoutNoCull(EdgewiseFaceBaker.white())), argb, light, a, b, c,
                d, nx, ny, nz);
    }

    private static void quad(PoseStack ms, VertexConsumer vertices, int argb, int light, float[] a, float[] b,
            float[] c, float[] d, float nx, float ny, float nz) {
        Matrix4f pose = ms.last().pose();
        Matrix3f normal = ms.last().normal();
        vertex(vertices, pose, normal, argb, light, a, 0, 0, nx, ny, nz);
        vertex(vertices, pose, normal, argb, light, b, 1, 0, nx, ny, nz);
        vertex(vertices, pose, normal, argb, light, c, 1, 1, nx, ny, nz);
        vertex(vertices, pose, normal, argb, light, d, 0, 1, nx, ny, nz);
    }

    private static void vertex(VertexConsumer vertices, Matrix4f pose, Matrix3f normal, int argb, int light,
            float[] p, float u, float v, float nx, float ny, float nz) {
        Verts.block(vertices, pose, normal, p[0], p[1], p[2],
                (argb >> 16) & 0xFF, (argb >> 8) & 0xFF, argb & 0xFF, (argb >>> 24) & 0xFF,
                u, v, OverlayTexture.NO_OVERLAY, light, nx, ny, nz);
    }
}
