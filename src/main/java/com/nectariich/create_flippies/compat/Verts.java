package com.nectariich.create_flippies.compat;

import org.joml.Matrix3f;
import org.joml.Matrix4f;
//? if neoforge {
/*import org.joml.Vector3f;
*///?}

import com.mojang.blaze3d.vertex.VertexConsumer;
//? if forge {
import com.mojang.blaze3d.vertex.BufferBuilder;
//?} else {
/*import com.mojang.blaze3d.vertex.ByteBufferBuilder;
*///?}

import net.minecraft.client.renderer.MultiBufferSource;

/**
 * One vertex of each shape Flippies draws, on both versions.
 *
 * 1.21.1 replaced the {@code vertex(...).color(...)...endVertex()} chain with
 * {@code addVertex(...).setColor(...)...}, with no end call. Each method here writes
 * exactly the elements its vertex format needs, in the order 1.20.1 wrote them.
 * Colours are 0..255 components. A normal is transformed by {@code normal}, the pose's
 * normal matrix, as 1.20.1's {@code normal(Matrix3f, x, y, z)} did.
 */
public final class Verts {

    private Verts() {}

    /** Position and colour ({@code POSITION_COLOR}). */
    public static void posColor(VertexConsumer vc, Matrix4f pose, float x, float y, float z,
            int r, int g, int b, int a) {
        //? if forge {
        vc.vertex(pose, x, y, z).color(r, g, b, a).endVertex();
        //?} else {
        /*vc.addVertex(pose, x, y, z).setColor(r, g, b, a);
        *///?}
    }

    /** Position, colour and light ({@code POSITION_COLOR_LIGHTMAP}). */
    public static void posColorLight(VertexConsumer vc, Matrix4f pose, float x, float y, float z,
            int r, int g, int b, int a, int light) {
        //? if forge {
        vc.vertex(pose, x, y, z).color(r, g, b, a).uv2(light).endVertex();
        //?} else {
        /*vc.addVertex(pose, x, y, z).setColor(r, g, b, a).setLight(light);
        *///?}
    }

    /** Position, colour, texture and light ({@code POSITION_COLOR_TEX_LIGHTMAP}). */
    public static void posColorUvLight(VertexConsumer vc, Matrix4f pose, float x, float y, float z,
            int r, int g, int b, int a, float u, float v, int light) {
        //? if forge {
        vc.vertex(pose, x, y, z).color(r, g, b, a).uv(u, v).uv2(light).endVertex();
        //?} else {
        /*vc.addVertex(pose, x, y, z).setColor(r, g, b, a).setUv(u, v).setLight(light);
        *///?}
    }

    /** A line end: position, colour and the line's direction as its normal ({@code RenderType.lines()}). */
    public static void line(VertexConsumer vc, Matrix4f pose, Matrix3f normal, float x, float y, float z,
            int r, int g, int b, int a, float nx, float ny, float nz) {
        //? if forge {
        vc.vertex(pose, x, y, z).color(r, g, b, a).normal(normal, nx, ny, nz).endVertex();
        //?} else {
        /*Vector3f n = normal.transform(new Vector3f(nx, ny, nz));
        vc.addVertex(pose, x, y, z).setColor(r, g, b, a).setNormal(n.x(), n.y(), n.z());
        *///?}
    }

    /** A full block vertex: position, colour, texture, overlay, light and normal ({@code NEW_ENTITY}, {@code BLOCK}). */
    public static void block(VertexConsumer vc, Matrix4f pose, Matrix3f normal, float x, float y, float z,
            int r, int g, int b, int a, float u, float v, int overlay, int light, float nx, float ny, float nz) {
        //? if forge {
        vc.vertex(pose, x, y, z).color(r, g, b, a).uv(u, v).overlayCoords(overlay).uv2(light)
                .normal(normal, nx, ny, nz).endVertex();
        //?} else {
        /*Vector3f n = normal.transform(new Vector3f(nx, ny, nz));
        vc.addVertex(pose, x, y, z).setColor(r, g, b, a).setUv(u, v).setOverlay(overlay).setLight(light)
                .setNormal(n.x(), n.y(), n.z());
        *///?}
    }

    /** {@code MultiBufferSource.immediate} over a fresh buffer of {@code size} bytes. */
    public static MultiBufferSource.BufferSource immediate(int size) {
        //? if forge {
        return MultiBufferSource.immediate(new BufferBuilder(size));
        //?} else {
        /*return MultiBufferSource.immediate(new ByteBufferBuilder(size));
        *///?}
    }
}
