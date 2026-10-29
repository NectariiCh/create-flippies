package com.nectariich.create_flippies.client.wire;

import org.joml.Matrix3f;
import org.joml.Matrix4f;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.nectariich.create_flippies.CreateFlippies;
import com.nectariich.create_flippies.compat.Verts;
import com.nectariich.create_flippies.content.wire_duct.WireDuctEntity;

import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * Draws a stretch of wire duct: a plain box in the duct's colour (a flat white
 * texture tinted by it), nothing else on it. Each face carries its true normal, so
 * the game lights it as it lights any entity, and nothing here shades it.
 *
 * Joined stretches tile their corners exactly ({@code DuctNetwork.jointExtensions}),
 * so a run with turns reads as one solid shape: the faces two boxes share lie inside
 * that shape, facing into each other, and are never seen. Six faces per stretch, all
 * in one buffer, so a room full of ducts costs next to nothing.
 */
public class WireDuctRenderer extends EntityRenderer<WireDuctEntity> {

    private static final ResourceLocation TEXTURE = CreateFlippies.asResource("textures/entity/wire_duct.png");

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
        if (entity.segment() == null)
            return;
        Vec3 pos = entity.position();
        AABB box = entity.ductBox().move(-pos.x, -pos.y, -pos.z);
        VertexConsumer buffer = buffers.getBuffer(RenderType.entitySolid(TEXTURE));
        box(ms.last(), buffer, box, 0xFF000000 | entity.color(), light);
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

    /** One quad, wound so that it faces {@code normal}, in an opaque ARGB colour. */
    static void quad(PoseStack.Pose pose, VertexConsumer buffer, Vec3 a, Vec3 b, Vec3 c, Vec3 d, Vec3 normal,
            int color, int light) {
        Vec3 face = b.subtract(a).cross(c.subtract(a));
        if (face.dot(normal) < 0) {
            Vec3 t = b;
            b = d;
            d = t;
        }
        vertex(pose, buffer, a, 0, 0, normal, color, light);
        vertex(pose, buffer, b, 1, 0, normal, color, light);
        vertex(pose, buffer, c, 1, 1, normal, color, light);
        vertex(pose, buffer, d, 0, 1, normal, color, light);
    }

    private static void vertex(PoseStack.Pose pose, VertexConsumer buffer, Vec3 p, float u, float v, Vec3 n,
            int color, int light) {
        Matrix4f matrix = pose.pose();
        Matrix3f normal = pose.normal();
        Verts.block(buffer, matrix, normal, (float) p.x, (float) p.y, (float) p.z,
                (color >> 16) & 0xFF, (color >> 8) & 0xFF, color & 0xFF, (color >>> 24) & 0xFF,
                u, v, OverlayTexture.NO_OVERLAY, light, (float) n.x, (float) n.y, (float) n.z);
    }
}
