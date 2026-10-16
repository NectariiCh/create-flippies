package com.nectariich.create_flippies.client;

import com.simibubi.create.foundation.model.BakedQuadHelper;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.Mth;
import net.minecraft.world.level.BlockAndTintGetter;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

/**
 * How bright a panel quad is drawn, read from the blocks around the cell it is in.
 *
 * Three things decide that, and they are gathered together because they are all answers
 * the level has to give while there is still a level to ask: {@code getQuads} is handed
 * neither a world nor a position, so anything the world knows has to be taken in
 * {@code gatherModelData} and carried across in the model data.
 *
 * <ul>
 * <li>The <em>shade</em>: the fixed darkening Minecraft gives a surface for the way it
 * faces. Six values, one per face, blended for a panel that faces between them.</li>
 * <li>The <em>light</em>: torch and sky, sampled per vertex so that it runs smoothly
 * across a cell and across the seam into the next one.</li>
 * <li>The <em>occlusion</em>: how much of what surrounds a vertex is solid, which is the
 * corner darkening that makes smooth lighting look like more than a gradient.</li>
 * </ul>
 *
 * <h2>Why the lighting is done here rather than left to Minecraft</h2>
 *
 * Because Minecraft's smooth lighting is written for a quad that is an axis-aligned
 * rectangle lying on one of the six faces, and a panel plate is neither. It takes the
 * quad's bounding box, reads four brightnesses from the blocks around the face, and
 * hands the four <em>corners of that box</em> to the four vertices in a fixed order.
 * Give it a quad turned to forty-five degrees, or one clipped down to a triangle, and
 * the vertices are matched to corners they are nowhere near: the result is the banding
 * and blotching that made flat lighting the better of the two.
 *
 * So the model declines Minecraft's version — see
 * {@link LargePanelModel#useAmbientOcclusion} — and lights each vertex here instead,
 * from where the vertex actually is. On a quad that <em>does</em> lie flat on a face
 * this reduces to what Minecraft would have computed, because it is the same field
 * sampled the same way; the difference is only that a vertex in the middle of a face,
 * or halfway up a diagonal, now gets the value belonging to that point rather than one
 * belonging to a corner.
 *
 * <h2>Half a block off the surface</h2>
 *
 * A vertex is lit by what is in <em>front</em> of it rather than by what it is embedded
 * in, so each is read at a point {@link #REACH} of a block out along the side of the
 * plate it belongs to. Half a block is what puts that point on the centre plane of the
 * next layer out for a quad sitting on the cell wall, which is the layer Minecraft reads
 * for a face there; and for a plate floating two pixels inside its cell it weights that
 * same layer at seven eighths against the cell's own at one eighth, which is a gentler
 * version of the same answer rather than a different one.
 */
public final class PanelQuadLight {

    /** Gathered once: the shade of each of these is taken while the level is at hand. */
    private static final Direction[] SIDES = Direction.values();

    /** The four corners a baked quad always has. */
    private static final int VERTICES = 4;

    /** Blocks sampled along each axis: the cell, and one to either side of it. */
    private static final int SPAN = 3;

    private static final int CELLS = SPAN * SPAN * SPAN;

    /** How far off the plate its light is read, in blocks. See the class comment. */
    private static final double REACH = 0.5;

    /** A factor within this of one is left alone rather than multiplied through. */
    private static final double FLAT = 1.0e-4;

    /** The full range of a colour channel, which is where a shade is applied. */
    private static final int FULL = 255;

    /** Where the sky half of a packed light sits; the block half is below it. */
    private static final int SKY = 16;

    /** Both halves are sixteen bits wide. */
    private static final int HALF = 0xFFFF;

    /** Both halves are a light level times sixteen, so this is a level of fifteen. */
    private static final int BRIGHTEST = 0xF0;

    /** What Minecraft would shade each face by here, by {@link Direction#ordinal}. */
    private final float[] shades;

    /**
     * Packed light at each of the twenty-seven blocks around the cell, or null when
     * smooth lighting is switched off and there is nothing to interpolate.
     */
    private final int[] lights;

    /** How much each of those blocks darkens what is beside it: one, or a fifth. */
    private final float[] occlusions;

    /** Whether each can be seen into, and so whether its light says anything. */
    private final boolean[] open;

    /** The cell's own light, which is what is left when nothing around it is open. */
    private final int here;

    private PanelQuadLight(float[] shades, int[] lights, float[] occlusions, boolean[] open, int here) {
        this.shades = shades;
        this.lights = lights;
        this.occlusions = occlusions;
        this.open = open;
        this.here = here;
    }

    /**
     * Everything the level has to say about how one cell is lit.
     *
     * The twenty-seven blocks are the cell and its neighbours in every direction, which
     * is exactly the reach a point half a block outside the cell can need and exactly
     * the margin a chunk is compiled with, so nothing here asks for a block the region
     * cannot supply.
     *
     * Only the shades are taken when smooth lighting is off. The rest would be spent
     * computing a gradient nobody asked for, and a panel then keeps the one brightness
     * per quad that the setting means.
     */
    static PanelQuadLight around(BlockAndTintGetter level, BlockPos pos) {
        float[] shades = new float[SIDES.length];
        for (Direction face : SIDES)
            shades[face.ordinal()] = level.getShade(face, true);
        if (!Minecraft.useAmbientOcclusion())
            return new PanelQuadLight(shades, null, null, null, 0);

        int[] lights = new int[CELLS];
        float[] occlusions = new float[CELLS];
        boolean[] open = new boolean[CELLS];
        BlockPos.MutableBlockPos at = new BlockPos.MutableBlockPos();
        for (int x = 0; x < SPAN; x++)
            for (int y = 0; y < SPAN; y++)
                for (int z = 0; z < SPAN; z++) {
                    at.set(pos.getX() + x - 1, pos.getY() + y - 1, pos.getZ() + z - 1);
                    BlockState state = level.getBlockState(at);
                    int cell = cell(x, y, z);
                    lights[cell] = LevelRenderer.getLightColor(level, state, at);
                    occlusions[cell] = state.getShadeBrightness(level, at);
                    open[cell] = !state.isViewBlocking(level, at) || state.getLightBlock(level, at) == 0;
                }
        return new PanelQuadLight(shades, lights, occlusions, open, lights[cell(1, 1, 1)]);
    }

    /** Where one of the twenty-seven blocks sits, counted from the corner below. */
    private static int cell(int x, int y, int z) {
        return (x * SPAN + y) * SPAN + z;
    }

    /**
     * The same quad, lit.
     *
     * {@code outward} is the way the side of the plate the quad belongs to faces, not
     * the quad's own normal: the two-pixel edges of a plate take the front's light along
     * with the front's shade, for the reason given in {@link LargePanelModel}.
     *
     * The shade is folded into the vertex colours rather than stated outright, because
     * the chunk builder will apply the shade of whichever face the quad names and there
     * is no asking it for another; what goes in is the factor that turns the one it will
     * apply into the one the panel wants. The occlusion rides along in the same multiply,
     * which is what it is — a darkening of the surface rather than a change to the light
     * falling on it. Both survive a renderer that has never heard of this mod, which is
     * why they go in the colours and only the light map needs help.
     */
    BakedQuad apply(BakedQuad quad, Vec3 outward) {
        int[] vertices = quad.getVertices();
        float stated = shades[quad.getDirection().ordinal()];
        double correction = quad.isShade() && stated > FLAT ? blend(outward) / stated : 1;

        if (lights == null) {
            for (int vertex = 0; vertex < VERTICES; vertex++)
                darken(vertices, vertex, correction);
            return quad;
        }

        int[] lightmap = new int[VERTICES];
        for (int vertex = 0; vertex < VERTICES; vertex++)
            lightmap[vertex] = sample(vertices, vertex, outward, correction);
        return new Lit(quad, lightmap);
    }

    /**
     * How bright a surface facing this way ought to be drawn.
     *
     * Minecraft has four brightnesses and they belong to the six faces: a wall running
     * east is drawn at eight tenths and one running north at six, so a panel at forty
     * five degrees between them is told to pick one and comes out a whole step away from
     * one of the two walls it joins. On a chamfer, which exists to make that corner
     * disappear, that is the corner drawn back in as a stripe of the wrong shade.
     *
     * So the four are interpolated. The normal's three components are the weights and
     * the faces they point at supply the values, which is the plainest reading of what a
     * shade per axis means, and on an axis it reduces to exactly the face's own value —
     * a straight panel is unchanged to the bit.
     */
    private double blend(Vec3 normal) {
        double x = Math.abs(normal.x);
        double y = Math.abs(normal.y);
        double z = Math.abs(normal.z);
        double total = x + y + z;
        if (total < FLAT)
            return 1;
        return (x * shades[(normal.x < 0 ? Direction.WEST : Direction.EAST).ordinal()]
                + y * shades[(normal.y < 0 ? Direction.DOWN : Direction.UP).ordinal()]
                + z * shades[(normal.z < 0 ? Direction.NORTH : Direction.SOUTH).ordinal()]) / total;
    }

    /**
     * One vertex: the light reaching it, and the darkening that goes with it.
     *
     * Both are read at the same point — the vertex, moved {@link #REACH} out along the
     * plate — and out of the same eight blocks, so they are worked out together rather
     * than walked for twice.
     *
     * The weights are the plain trilinear ones: each of the twenty-seven samples stands
     * for its block's centre, and a point between centres takes from each in proportion
     * to how near it is. Two things follow that are worth having. A point on a cell wall
     * comes out weighted exactly as Minecraft weights it, because averaging the four
     * blocks round a face corner is what trilinear interpolation reduces to there. And
     * the field is a function of where a point is in the world and of nothing else, so
     * two cells meeting at a seam compute the same value for it from opposite sides, and
     * the seam does not show.
     *
     * A block that cannot be seen into is left out of the light and the weights are
     * renormalised over what is left; where that leaves nothing, the cell's own light
     * stands. Otherwise a panel hung on a wall would take a share of its brightness from
     * the inside of the wall, which is black, and wear a dark band along the join. Such a
     * block still counts towards the occlusion — being solid is the whole of what
     * occlusion measures.
     */
    private int sample(int[] vertices, int vertex, Vec3 outward, double correction) {
        Vec3 at = BakedQuadHelper.getXYZ(vertices, vertex).add(outward.scale(REACH));
        double ux = Mth.clamp(at.x + 0.5, 0, SPAN - 1);
        double uy = Mth.clamp(at.y + 0.5, 0, SPAN - 1);
        double uz = Mth.clamp(at.z + 0.5, 0, SPAN - 1);
        int x = Math.min((int) ux, SPAN - 2);
        int y = Math.min((int) uy, SPAN - 2);
        int z = Math.min((int) uz, SPAN - 2);
        double fx = ux - x;
        double fy = uy - y;
        double fz = uz - z;

        double sky = 0;
        double block = 0;
        double lit = 0;
        double occlusion = 0;
        for (int dx = 0; dx <= 1; dx++)
            for (int dy = 0; dy <= 1; dy++)
                for (int dz = 0; dz <= 1; dz++) {
                    double weight = (dx == 0 ? 1 - fx : fx) * (dy == 0 ? 1 - fy : fy) * (dz == 0 ? 1 - fz : fz);
                    if (weight <= 0)
                        continue;
                    int cell = cell(x + dx, y + dy, z + dz);
                    occlusion += weight * occlusions[cell];
                    if (!open[cell])
                        continue;
                    sky += weight * (lights[cell] >>> SKY & HALF);
                    block += weight * (lights[cell] & HALF);
                    lit += weight;
                }

        darken(vertices, vertex, correction * occlusion);
        return lit < FLAT ? here : channel(sky / lit) << SKY | channel(block / lit);
    }

    /** One half of a packed light, rounded and put back in range. */
    private static int channel(double level) {
        return Mth.clamp((int) Math.round(level), 0, BRIGHTEST);
    }

    /**
     * Multiplies one vertex's colour, which is how a brightness the renderer will not
     * apply gets applied anyway.
     *
     * The chunk builder multiplies whatever it computes into the colour already on the
     * vertex, so a factor put there in advance comes through untouched. A quad that
     * carries a tint or a baked colour keeps both: this multiplies, it does not replace.
     *
     * Alpha is left alone. It is not a brightness, and scaling it would fade a panel
     * wearing glass rather than darken it.
     */
    private static void darken(int[] vertices, int vertex, double factor) {
        if (Math.abs(factor - 1) < FLAT)
            return;
        int at = vertex * BakedQuadHelper.VERTEX_STRIDE + BakedQuadHelper.COLOR_OFFSET;
        int packed = vertices[at];
        vertices[at] = (packed & 0xFF000000) | scale(packed >> 16, factor) << 16
                | scale(packed >> 8, factor) << 8 | scale(packed, factor);
    }

    /** One colour channel of a packed vertex colour, scaled and put back in range. */
    private static int scale(int packed, double factor) {
        return Math.min(FULL, (int) Math.round((packed & FULL) * factor));
    }

    /**
     * The light to draw a quad's four vertices with, if it brought its own.
     *
     * Asked of every quad in the world by {@code BakedQuadLightMixin}, which is why it
     * is a type test and nothing more.
     */
    public static int[] lightOf(BakedQuad quad, int[] otherwise) {
        return quad instanceof Lit lit ? lit.light : otherwise;
    }

    /**
     * A quad that has been lit per vertex and says so.
     *
     * There is nowhere in a baked quad to put four light values that would survive —
     * the chunk builder works them out itself and writes them over whatever the vertices
     * held — so the quad carries them beside its vertices and a mixin hands them back at
     * the one point they would otherwise be discarded. A renderer that knows nothing of
     * this sees an ordinary quad and lights it the ordinary way.
     */
    private static final class Lit extends BakedQuad {

        private final int[] light;

        Lit(BakedQuad quad, int[] light) {
            super(quad.getVertices(), quad.getTintIndex(), quad.getDirection(), quad.getSprite(), quad.isShade());
            this.light = light;
        }
    }
}
