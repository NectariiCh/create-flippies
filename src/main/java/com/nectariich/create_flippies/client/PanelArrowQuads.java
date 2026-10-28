package com.nectariich.create_flippies.client;

import java.util.List;

import com.nectariich.create_flippies.client.schematic_arrow.ArrowExtrusion;
import com.nectariich.create_flippies.client.schematic_arrow.ArrowMesh;
import com.nectariich.create_flippies.client.schematic_arrow.ArrowMeshes;
import com.nectariich.create_flippies.content.schematic_arrow.CellArrows;
import com.nectariich.create_flippies.content.schematic_arrow.PanelArrow;
import com.simibubi.create.foundation.model.BakedQuadHelper;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.core.Direction;
import net.minecraft.world.inventory.InventoryMenu;
import net.minecraft.world.phys.Vec3;

/**
 * The schematic arrows on one panel cell, as quads for the chunk mesh.
 *
 * Baked with the plate for the reason paint is (see {@link PanelPaintQuads}): a wall
 * of arrows costs nothing once its chunk is built. The shape of each arrow comes out
 * of a cache, so a rebuild costs a cut against the cell and some arithmetic per
 * arrow. Drawn with the paint's plain white sprite, coloured per vertex, and lit by
 * {@link PanelQuadLight} with each face's own normal, so the top is shaded like the
 * plate under it and each wall like a block face pointing the way it does.
 */
final class PanelArrowQuads {

    private static final int VERTICES = 4;

    private PanelArrowQuads() {}

    static void build(CellArrows arrows, PanelQuadLight light, List<BakedQuad> into) {
        TextureAtlasSprite sprite = Minecraft.getInstance().getModelManager().getAtlas(InventoryMenu.BLOCK_ATLAS)
                .getSprite(PanelPaintQuads.PAINT);
        // The sprite centre: 1.21 takes the position as 0..1 instead of 0..16
        //? if forge {
        float spriteU = sprite.getU(8);
        float spriteV = sprite.getV(8);
        //?} else {
        /*float spriteU = sprite.getU(0.5f);
        float spriteV = sprite.getV(0.5f);
        *///?}
        List<PanelArrow> list = arrows.arrows();
        for (int index = 0; index < list.size(); index++) {
            PanelArrow arrow = list.get(index);
            ArrowMesh mesh = ArrowMeshes.of(arrow);
            if (mesh == null)
                continue;
            ArrowExtrusion.build(arrow, mesh, arrows.cell(), ArrowExtrusion.stacked(list, index),
                    face -> emit(face, sprite, spriteU, spriteV, light, into));
        }
    }

    /** A convex polygon as a fan of quads, a triangle being a quad with its last corner doubled. */
    private static void emit(ArrowExtrusion.Face face, TextureAtlasSprite sprite, float spriteU, float spriteV,
            PanelQuadLight light, List<BakedQuad> into) {
        List<double[]> points = face.polygon();
        Vec3 normal = face.normal();
        Direction named = Direction.getNearest((float) normal.x, (float) normal.y, (float) normal.z);
        int colour = abgr(face.colour());
        int count = points.size();
        for (int i = 1; i + 1 < count; i += 2) {
            double[][] corners = { points.get(0), points.get(i), points.get(i + 1),
                    i + 2 < count ? points.get(i + 2) : points.get(i + 1) };
            int stride = BakedQuadHelper.VERTEX_STRIDE;
            int[] vertices = new int[VERTICES * stride];
            for (int k = 0; k < VERTICES; k++) {
                double[] p = corners[k];
                BakedQuadHelper.setXYZ(vertices, k, new Vec3(p[0], p[1], p[2]));
                vertices[k * stride + BakedQuadHelper.COLOR_OFFSET] = colour;
                BakedQuadHelper.setU(vertices, k, spriteU);
                BakedQuadHelper.setV(vertices, k, spriteV);
                BakedQuadHelper.setNormalXYZ(vertices, k, normal);
            }
            BakedQuad quad = new BakedQuad(vertices, -1, named, sprite, true);
            into.add(light == null ? quad : light.apply(quad, normal));
        }
    }

    /** ARGB as a baked vertex holds it, which is red in the lowest byte. */
    private static int abgr(int argb) {
        return (argb & 0xFF000000) | (argb & 0xFF) << 16 | (argb & 0xFF00) | (argb >> 16 & 0xFF);
    }
}
