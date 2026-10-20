package com.nectariich.create_flippies.client.wire;

import org.joml.Matrix3f;
import org.joml.Matrix4f;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.nectariich.create_flippies.content.wire_duct.WireDuctEntity;
import com.nectariich.create_flippies.foundation.wire.duct.DuctSegment;
import com.nectariich.create_flippies.foundation.wire.duct.DuctSize;

import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * Draws a stretch of wire duct: a plain box in concrete grey, with the lid's two
 * seams drawn as darker lines along it. Six faces and two strips per stretch, all
 * in one buffer, so a room full of ducts costs next to nothing.
 */
public class WireDuctRenderer extends EntityRenderer<WireDuctEntity> {

    private static final ResourceLocation TEXTURE = new ResourceLocation("minecraft",
            "textures/block/light_gray_concrete.png");

    /** How far in from the lid's edge a seam runs, and how wide it is, blocks. */
    private static final double SEAM_INSET = 0.5 / 16, SEAM_WIDTH = 0.35 / 16;

    public WireDuctRenderer(EntityRendererProvider.Context context) {
        super(context);
    }

    @Override
    public ResourceLocation getTextureLocation(WireDuctEntity entity) {
        return TEXTURE;
    }

    @Override
    public void render(WireDuctEntity entity, float yaw, float partialTicks, PoseStack ms, MultiBufferSource buffers,
            int light) {
        DuctSegment segment = entity.segment();
        if (segment == null)
            return;
        Vec3 pos = entity.position();
        AABB box = entity.ductBox().move(-pos.x, -pos.y, -pos.z);
        VertexConsumer buffer = buffers.getBuffer(RenderType.entityCutout(TEXTURE));
        PoseStack.Pose pose = ms.last();
        int body = entity.size() == DuctSize.LARGE ? 0xFFB4B4B4 : 0xFFD2D2D2;
        box(pose, buffer, box, body, light);

        // The lid's seams: two thin darker strips just above the lid, along the duct
        Vec3 n = segment.normal();
        Vec3 lat = segment.lateral();
        Vec3 dir = segment.direction();
        Vec3 center = box.getCenter();
        double halfDepth = segment.size().depth() / 2 + 0.002;
        double halfWidth = segment.size().width() / 2;
        double halfLength = Math.abs(dir.x) * box.getXsize() / 2 + Math.abs(dir.y) * box.getYsize() / 2
                + Math.abs(dir.z) * box.getZsize() / 2;
        int seam = 0xFF6E6E6E;
        for (int side = -1; side <= 1; side += 2) {
            double mid = side * (halfWidth - SEAM_INSET);
            Vec3 c = center.add(n.scale(halfDepth)).add(lat.scale(mid));
            Vec3 along = dir.scale(halfLength);
            Vec3 across = lat.scale(SEAM_WIDTH / 2);
            quad(pose, buffer, c.subtract(along).subtract(across), c.add(along).subtract(across),
                    c.add(along).add(across), c.subtract(along).add(across), n, seam, light);
        }
    }

    private static void box(PoseStack.Pose pose, VertexConsumer buffer, AABB b, int color, int light) {
        Vec3 p000 = new Vec3(b.minX, b.minY, b.minZ), p100 = new Vec3(b.maxX, b.minY, b.minZ);
        Vec3 p010 = new Vec3(b.minX, b.maxY, b.minZ), p110 = new Vec3(b.maxX, b.maxY, b.minZ);
        Vec3 p001 = new Vec3(b.minX, b.minY, b.maxZ), p101 = new Vec3(b.maxX, b.minY, b.maxZ);
        Vec3 p011 = new Vec3(b.minX, b.maxY, b.maxZ), p111 = new Vec3(b.maxX, b.maxY, b.maxZ);
        quad(pose, buffer, p010, p011, p111, p110, new Vec3(0, 1, 0), color, light);
        quad(pose, buffer, p000, p100, p101, p001, new Vec3(0, -1, 0), color, light);
        quad(pose, buffer, p000, p010, p110, p100, new Vec3(0, 0, -1), color, light);
        quad(pose, buffer, p001, p101, p111, p011, new Vec3(0, 0, 1), color, light);
        quad(pose, buffer, p000, p001, p011, p010, new Vec3(-1, 0, 0), color, light);
        quad(pose, buffer, p100, p110, p111, p101, new Vec3(1, 0, 0), color, light);
    }

    /**
     * One quad, wound so that it faces {@code normal}, textured by its own size in
     * blocks so the concrete keeps its scale on a duct of any length.
     */
    private static void quad(PoseStack.Pose pose, VertexConsumer buffer, Vec3 a, Vec3 b, Vec3 c, Vec3 d,
            Vec3 normal, int color, int light) {
        Vec3 face = b.subtract(a).cross(c.subtract(a));
        if (face.dot(normal) < 0) {
            Vec3 t = b;
            b = d;
            d = t;
        }
        float u = (float) a.distanceTo(b), v = (float) a.distanceTo(d);
        vertex(pose, buffer, a, 0, 0, normal, color, light);
        vertex(pose, buffer, b, u, 0, normal, color, light);
        vertex(pose, buffer, c, u, v, normal, color, light);
        vertex(pose, buffer, d, 0, v, normal, color, light);
    }

    private static void vertex(PoseStack.Pose pose, VertexConsumer buffer, Vec3 p, float u, float v, Vec3 n,
            int color, int light) {
        Matrix4f matrix = pose.pose();
        Matrix3f normal = pose.normal();
        buffer.vertex(matrix, (float) p.x, (float) p.y, (float) p.z)
                .color((color >> 16) & 0xFF, (color >> 8) & 0xFF, color & 0xFF, (color >>> 24) & 0xFF)
                .uv(u, v)
                .overlayCoords(OverlayTexture.NO_OVERLAY)
                .uv2(light)
                .normal(normal, (float) n.x, (float) n.y, (float) n.z)
                .endVertex();
    }
}
