package com.nectariich.create_flippies.client;

import java.util.ArrayList;
import java.util.List;

import com.nectariich.create_flippies.content.decal.CellPaint;
import com.nectariich.create_flippies.content.panel.LargePanelBlockEntity;
import com.nectariich.create_flippies.content.panel.PanelTile;
import com.nectariich.create_flippies.content.schematic_arrow.CellArrows;
import com.simibubi.create.content.decoration.copycat.CopycatModel;
import com.simibubi.create.foundation.model.BakedModelHelper;
import com.simibubi.create.foundation.model.BakedQuadHelper;

import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.BlockAndTintGetter;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

import net.minecraftforge.client.model.data.ModelData;
import net.minecraftforge.client.model.data.ModelProperty;
import net.minecraftforge.client.ChunkRenderTypeSet;

/**
 * Draws one cell of a large panel wearing its material.
 *
 * The material lookup and the cropping are Create's: {@link CopycatModel} already
 * knows how to reach a material through model data, and
 * {@link BakedModelHelper#cropAndMove} already knows how to cut one of its quads
 * down to a box. What this adds is where the piece goes — which is a
 * {@link PanelTile}, and a tile carries a turn as well as a position.
 *
 * <h2>Why the texture tiles rather than stretching</h2>
 *
 * Because a tile asks the material for one block's worth of quads and crops them,
 * exactly as a single copycat panel does. A panel twenty blocks long is twenty tiles
 * each doing that independently, so the material repeats once per block with no seam
 * and nothing is ever scaled. On a diagonal the repeat is measured along the panel
 * rather than along the world grid, which is the only way it comes out square: the
 * two grids are a factor of root two apart, and tiling by the wrong one is exactly
 * the stretch this avoids.
 *
 * <h2>Why {@link #getQuads} is overridden rather than {@code getCroppedQuads}</h2>
 *
 * Because {@code getCroppedQuads} cannot see the plate. Create calls it with the
 * <em>material's</em> model data — the one it built by asking the material's own
 * model what it wants, through a filtered view of the world — and not with the model
 * data of the block being drawn. So a property this mod adds in
 * {@link #gatherModelData} arrives in the outer data, which the material's data has
 * never heard of, and reading it back in {@code getCroppedQuads} yields null every
 * time. There is no way round it from the outside either:
 * {@code BakedModelWrapperWithData.getModelData} is final and builds the wrapped
 * data privately, so the plate cannot be smuggled into it.
 *
 * The path that <em>is</em> handed the outer data is {@code getQuads}, so that is
 * the one taken. Little is given up by taking it: the occlusion culling it skips is
 * for a copycat's faces meeting a neighbour's, and a plate does not have faces on
 * the block boundary to cull.
 *
 * <h2>Everything on the unculled side</h2>
 *
 * A cell's plate is a slab floating inside its block rather than a face of it, and
 * on a diagonal it does not lie along any world axis at all, so none of it belongs to
 * a cardinal face and none of it may be culled against a neighbour. So every quad is
 * gathered from every side of the material and emitted on {@code side == null}. The
 * cost is that a panel buried in a wall still draws; the alternative is a panel that
 * vanishes wherever something is behind it.
 */
public class LargePanelModel extends CopycatModel {

    /** Gathered once: the material is asked for its quads on each of these in turn. */
    private static final Direction[] SIDES = Direction.values();

    private static final int VERTICES = 4;

    /**
     * How the cell is lit, taken while there is still a level to ask.
     *
     * Needed because {@code getQuads} is given neither a world nor a position, and
     * every part of lighting a panel wants one: the shade a turned panel takes is not
     * one of the six Minecraft keeps for the six faces, and the light and the corner
     * darkening are read from the blocks around the cell. See {@link PanelQuadLight}.
     */
    private static final ModelProperty<PanelQuadLight> LIGHT = new ModelProperty<>();

    /** The layer paint is drawn in: its colours are opaque and its texture is a plain white square. */
    private static final RenderType PAINT_LAYER = RenderType.solid();

    public LargePanelModel(BakedModel wrapped) {
        super(wrapped);
    }

    /**
     * Never — which is not the same as saying a panel is flatly lit.
     *
     * Minecraft's smooth lighting is a shading of a quad by the blocks around the face
     * it lies on, and a panel plate does not lie on one: it floats inside its cell, two
     * pixels thick, and on a turned panel it does not even run along a world axis. Asked
     * to shade it anyway, Minecraft reads four brightnesses from around whichever
     * cardinal face is nearest and hands them to the four corners of the quad's bounding
     * box, which on a turned or clipped plate are not the four vertices it has. The
     * result is bands and blotches that answer to nothing in the world.
     *
     * So the offer is declined and the work is done properly instead: {@link
     * PanelQuadLight} lights each vertex from where that vertex is, out of the same
     * blocks Minecraft would have read, and the panel comes out smoothly lit across a
     * cell and across the seam into the next one. Saying no here is what keeps
     * Minecraft from lighting it a second and worse time on top.
     */
    @Override
    public boolean useAmbientOcclusion() {
        return false;
    }

    @Override
    public boolean useAmbientOcclusion(BlockState state) {
        return false;
    }

    @Override
    public boolean useAmbientOcclusion(BlockState state, RenderType renderType) {
        return false;
    }

    /**
     * Carries the plate across from the block entity, which the inherited gathering
     * does not do.
     *
     * {@code BakedModelWrapperWithData.getModelData} builds from a fresh builder, so
     * nothing the block entity supplied survives unless something copies it. Create
     * copies the material and the occlusion it works out for itself, and nothing
     * else. So the plate is copied here, and without this the model is handed no
     * boxes, emits no quads, and the panel is invisible while still colliding.
     */
    @Override
    protected ModelData.Builder gatherModelData(ModelData.Builder builder, BlockAndTintGetter level, BlockPos pos,
            BlockState state, ModelData blockEntityData) {
        super.gatherModelData(builder, level, pos, state, blockEntityData);
        PanelTile[] skin = blockEntityData.get(LargePanelBlockEntity.SKIN);
        if (skin != null)
            builder.with(LargePanelBlockEntity.SKIN, skin);
        CellPaint paint = blockEntityData.get(LargePanelBlockEntity.PAINT);
        if (paint != null)
            builder.with(LargePanelBlockEntity.PAINT, paint);
        // SCHEMATIC ARROWS
        CellArrows arrows = blockEntityData.get(LargePanelBlockEntity.ARROWS);
        if (arrows != null)
            builder.with(LargePanelBlockEntity.ARROWS, arrows);
        builder.with(LIGHT, PanelQuadLight.around(level, pos));
        return builder;
    }

    /**
     * The layers the material draws in, not the ones the empty base model would.
     *
     * The chunk builder asks the model which layers to visit and skips it in every
     * other, so a panel wearing glass would be culled out of the translucent pass and
     * simply not appear. The base model has no geometry to have an opinion, so the
     * question is forwarded to whatever is being worn.
     */
    @Override
    public ChunkRenderTypeSet getRenderTypes(BlockState state, RandomSource rand, ModelData data) {
        BlockState material = CopycatModel.getMaterial(data);
        ChunkRenderTypeSet worn = material == null || material.isAir() ? super.getRenderTypes(state, rand, data)
                : CopycatModel.getModelOf(material).getRenderTypes(material, rand, ModelData.EMPTY);
        // Paint is drawn in the solid layer whatever the plate is made of, so a cell
        // that has any has to be visited there even when it is wearing glass
        // SCHEMATIC ARROWS: arrows are drawn in the same layer as paint, for the same reason
        return data.get(LargePanelBlockEntity.PAINT) == null && data.get(LargePanelBlockEntity.ARROWS) == null ? worn
                : ChunkRenderTypeSet.union(worn, ChunkRenderTypeSet.of(PAINT_LAYER));
    }

    /**
     * The plate, wearing the material, on the unculled side and nowhere else.
     *
     * The layer is checked against the material rather than assumed, because the
     * chunk builder walks every layer the model claimed and this model claims
     * whatever the material claims: emitting the same quads in each would draw the
     * panel two or three times over, once solid and once translucent.
     */
    @Override
    public List<BakedQuad> getQuads(BlockState state, Direction side, RandomSource rand, ModelData data,
            RenderType renderType) {
        if (side != null)
            return List.of();

        List<BakedQuad> gathered = new ArrayList<>();
        PanelQuadLight light = data.get(LIGHT);
        plate(data, rand, renderType, light, gathered);
        CellPaint paint = data.get(LargePanelBlockEntity.PAINT);
        if (paint != null && (renderType == null || renderType == PAINT_LAYER))
            PanelPaintQuads.build(paint, light, gathered);
        // SCHEMATIC ARROWS
        CellArrows arrows = data.get(LargePanelBlockEntity.ARROWS);
        if (arrows != null && (renderType == null || renderType == PAINT_LAYER))
            PanelArrowQuads.build(arrows, light, gathered);
        return gathered;
    }

    /** The plate's own quads, in the layers its material draws in. */
    private static void plate(ModelData data, RandomSource rand, RenderType renderType, PanelQuadLight light,
            List<BakedQuad> gathered) {
        PanelTile[] skin = data.get(LargePanelBlockEntity.SKIN);
        BlockState material = CopycatModel.getMaterial(data);
        if (skin == null || skin.length == 0 || material == null || material.isAir())
            return;

        BakedModel source = CopycatModel.getModelOf(material);
        if (renderType != null && !source.getRenderTypes(material, rand, ModelData.EMPTY).contains(renderType))
            return;

        for (PanelTile tile : skin) {
            crop(source, material, null, rand, renderType, tile, light, gathered);
            for (Direction face : SIDES)
                crop(source, material, face, rand, renderType, tile, light, gathered);
        }
    }

    /**
     * Never reached: {@link #getQuads} is overridden, so Create's path into this is
     * not taken. It exists because {@link CopycatModel} declares it abstract, and it
     * returns nothing rather than guessing because the argument it would need — the
     * plate — is not among the ones it is given. See the class comment.
     */
    @Override
    protected List<BakedQuad> getCroppedQuads(BlockState state, Direction side, RandomSource rand, BlockState material,
            ModelData data, RenderType renderType) {
        return List.of();
    }

    /**
     * The material's quads for one side, cut down to one tile and turned into place.
     *
     * A tile that carries a clip is one whose share of its cell is not a box — a flat
     * panel lying at forty-five degrees is the only one — and it is placed oversized
     * and then trimmed. See {@link PanelQuadClip}.
     *
     * Lighting comes after the trimming rather than before it, because a quad that has
     * been cut has vertices the original did not, in places the original never reached,
     * and it is the pieces that are drawn. Lighting first would leave those vertices
     * carrying the light of the corner they were cut from.
     */
    private static void crop(BakedModel source, BlockState material, Direction side, RandomSource rand,
            RenderType renderType, PanelTile tile, PanelQuadLight light, List<BakedQuad> into) {
        for (BakedQuad quad : source.getQuads(material, side, rand, ModelData.EMPTY, renderType)) {
            Vec3 outward = outward(quad, tile);
            BakedQuad placed = place(quad, tile, outward);
            int first = into.size();
            if (tile.clip() == null)
                into.add(placed);
            else
                PanelQuadClip.clip(placed, tile.clip(), into);
            if (light != null)
                for (int piece = first; piece < into.size(); piece++)
                    into.set(piece, light.apply(into.get(piece), outward));
        }
    }

    /**
     * Which side of the plate a quad is on, stated as the way that side faces.
     *
     * A quad of the material's own front, once turned, points somewhere within a right
     * angle of the plate's front, so the sign of the dot product is enough to tell the
     * two sides apart. The edges come out as front, their normals being square to both:
     * two pixels of rim is not worth a third answer, and the front is the one a panel is
     * lit by.
     */
    private static Vec3 outward(BakedQuad quad, PanelTile tile) {
        Vec3 facing = tile.turn(Vec3.atLowerCornerOf(quad.getDirection().getNormal()));
        Vec3 front = tile.alongDepth().scale(-1);
        return facing.dot(front) >= 0 ? front : front.scale(-1);
    }

    /**
     * One quad of the material, cut to a tile and moved onto the panel.
     *
     * The cut is Create's, and it is the reason the material keeps its own textures,
     * tints and shading rather than being reduced to a colour. What is added is the
     * turn: every vertex and every normal goes through the tile's frame, so a panel
     * at forty-five degrees is drawn at forty-five degrees instead of as the
     * staircase its collision shape has to be. Nothing quantises the angle — the
     * twenty-two-and-a-half-degree step people know from Minecraft models belongs to
     * the JSON format, and a quad built here never goes near it.
     *
     * The stated {@link Direction} is not the quad's own facing but the panel's,
     * front or back according to which side of the plate the quad is on, and that is
     * deliberate. It is what the chunk builder lights the quad by — flat lighting reads
     * the block one step along it — so a quad claiming its own facing on a turned panel
     * would be lit from whichever neighbour its normal happened to round towards. Two
     * cells of one panel rounding differently is a seam with a step of brightness in
     * it, and an edge quad pointing into the wall the panel is mounted on is a strip of
     * black.
     *
     * Naming the panel's own facing instead lights the whole panel from the block in
     * front of it, which is the block a flat plate is in fact lit by, and lights every
     * cell of it from a block of the same kind. The two-pixel edges take the front
     * shade too, which is worth the uniformity at that size.
     *
     * What the name is wrong for is how bright to draw it: the shade of a cardinal face
     * is not the shade of a panel between two of them, and one light for the whole quad
     * is not smooth lighting. Both are put right by hand, per vertex, and the name is
     * what they are corrected from — and what a renderer that ignores the correction
     * falls back to. See {@link PanelQuadLight}.
     */
    private static BakedQuad place(BakedQuad quad, PanelTile tile, Vec3 outward) {
        int[] vertices = BakedModelHelper.cropAndMove(quad.getVertices(), quad.getSprite(), tile.source(), Vec3.ZERO);
        for (int vertex = 0; vertex < VERTICES; vertex++) {
            BakedQuadHelper.setXYZ(vertices, vertex, tile.place(BakedQuadHelper.getXYZ(vertices, vertex)));
            BakedQuadHelper.setNormalXYZ(vertices, vertex, tile.turn(BakedQuadHelper.getNormalXYZ(vertices, vertex)));
        }
        if (tile.mirrored())
            reverse(vertices);

        Direction named = Direction.getNearest((float) outward.x, (float) outward.y, (float) outward.z);
        return new BakedQuad(vertices, quad.getTintIndex(), named, quad.getSprite(), quad.isShade());
    }

    /**
     * Reverses a quad's winding in place, which is all a mirrored frame costs.
     *
     * Only the order of the four vertices changes; each keeps its own position and
     * its own texture coordinate, so the quad covers exactly the same ground and
     * wears exactly the same piece of texture. It is simply traversed the other way,
     * which is what decides whether Minecraft treats it as facing the viewer or
     * throws it away as a back face.
     */
    private static void reverse(int[] vertices) {
        int stride = BakedQuadHelper.VERTEX_STRIDE;
        int[] swap = new int[stride];
        for (int vertex = 0; vertex < VERTICES / 2; vertex++) {
            int near = vertex * stride;
            int far = (VERTICES - 1 - vertex) * stride;
            System.arraycopy(vertices, near, swap, 0, stride);
            System.arraycopy(vertices, far, vertices, near, stride);
            System.arraycopy(swap, 0, vertices, far, stride);
        }
    }
}
