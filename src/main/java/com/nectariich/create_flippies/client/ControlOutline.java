package com.nectariich.create_flippies.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.nectariich.create_flippies.CreateFlippies;
import com.nectariich.create_flippies.foundation.control.ControlRackHolder;

import it.unimi.dsi.fastutil.doubles.DoubleArrayList;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RenderHighlightEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/**
 * Draws the black wire outline on a block carrying controls, from edges worked out
 * once rather than once a frame.
 *
 * <h2>Why this exists</h2>
 *
 * {@code LevelRenderer.renderHitOutline} asks the block under the crosshair for its
 * shape and calls {@link VoxelShape#forAllEdges} on it, every frame, for as long as
 * the crosshair stays there. That call is not proportional to the number of edges it
 * hands back. A {@code VoxelShape} is a bit set over a discrete grid whose side along
 * each axis is the number of distinct coordinates its boxes have on that axis, and
 * the edge walk visits every cell of that grid three times over, once per axis,
 * testing four neighbours at each. The grid is the whole cost and the edges are
 * incidental.
 *
 * A panel cell is the block that makes that ruinous. Empty and on a diagonal it is
 * sixteen staircase slices of plate, its grid is sixteen by one by sixteen, and the
 * walk measures a sixth of a millisecond — which is why an unpopulated chamfer feels
 * perfectly fine and why feeling fine says nothing about a populated one. Put a
 * dozen indicator units and their labels on it — a lens, a barrel and eight contact
 * studs each, every one of them turned forty-five degrees so it shares almost no
 * coordinate with its neighbours — and the grid is sixty-five by twenty-five by
 * sixty, and the same walk measures 28 milliseconds. Two dozen units in one cell
 * measures 56. That is the whole of the reported bug: a panel that renders, collides
 * and picks perfectly well and costs two frames to point at.
 *
 * It is also why hiding the interface made it go away, which looked like evidence
 * against the outline and is not. {@code GameRenderer.shouldRenderBlockOutline}
 * refuses twice over: once because F1 sets {@code hideGui}, and once because a
 * spectator only gets an outline on a block with a menu to open. Create's goggle
 * overlay bails out on exactly the same two conditions, so the two suspects are
 * hidden by the same two gestures and neither observation separates them. Timing
 * them does: what the goggle overlay adds is the hit test that decides which control
 * the crosshair is on and one control's worth of tooltip, and the hit test measures
 * forty microseconds over forty-eight controls — seven hundred times less than the
 * edge walk. Taking the goggles off and looking again is the one-second version of
 * that measurement, and it leaves the lag exactly where it was.
 *
 * <h2>What it does instead</h2>
 *
 * The shape of a block carrying controls is already cached and rebuilt only when
 * something moves, so the edges of that shape are worth keeping for exactly as long
 * as the shape is. They are kept against the shape object itself. Keying on the
 * object rather than on the block position is what makes this safe to leave alone — a
 * shape that has been rebuilt is a different object and misses, so there is no
 * invalidation to get wrong and no way to draw an outline that has gone stale. The
 * walk then costs its 28 milliseconds once, on the frame the crosshair arrives, and
 * the frames after it cost nine microseconds.
 *
 * <h2>Why a handful of shapes and not one</h2>
 *
 * One slot was the first answer and it was wrong, on the reasoning that only one
 * block is highlighted at a time and the crosshair rests on it for many frames. Both
 * halves are true and the conclusion does not follow. A panel large enough to be worth
 * any of this is several cells, the instruments on it are a couple of pixels apart,
 * and reading or pressing them means moving the crosshair from one to the next all
 * day — so it crosses a cell boundary every second or two, and with one slot every
 * one of those crossings is a fresh walk of a cell that was walked a moment ago. That
 * is a 30 to 70 millisecond stall, which is three to six dropped frames, arriving
 * whenever the player moves the mouse across their own panel. Remembering the last
 * {@link #REMEMBERED} shapes costs a linear scan of eight references and about twenty
 * kilobytes a cell, and a crossing back onto a cell already walked then costs the
 * scan and nothing else.
 *
 * Eight is chosen to cover the cells of one panel in front of the player rather than
 * every cell in the world, and the array is a ring: when it is full the oldest entry
 * is overwritten, so a shape that has been rebuilt is dropped in its turn instead of
 * being held for as long as the client runs.
 *
 * Everything else is vanilla's, reproduced rather than reinterpreted: the same line
 * type, the same black at four tenths alpha, the same normals, the same offset from
 * the camera. The event is cancelled so vanilla does not draw the outline a second
 * time on top of this one.
 *
 * Only blocks of ours are taken over, and only the ones that can carry controls.
 * Everything else in the world keeps the vanilla path, where the grids are small and
 * the walk costs nothing worth caching.
 */
@Mod.EventBusSubscriber(modid = CreateFlippies.MOD_ID, value = Dist.CLIENT)
public final class ControlOutline {

    /** Vanilla's outline colour, and the alpha it draws it at. */
    private static final float ALPHA = 0.4f;

    /** How many shapes' edges are kept at once; see the class comment. */
    private static final int REMEMBERED = 8;

    /**
     * The shapes the edges below were walked from, held by identity and compared by
     * it. Render thread only, which is what lets these be plain fields: the highlight
     * is drawn from one place on one thread, and a miss costs a walk rather than a
     * wrong answer.
     */
    private static final VoxelShape[] walked = new VoxelShape[REMEMBERED];

    /** The ends of every edge, six doubles at a time, in the block's own coordinates. */
    private static final double[][] walkedEdges = new double[REMEMBERED][];

    /** Where the next shape walked is written, and so which entry it displaces. */
    private static int next;

    private ControlOutline() {}

    @SubscribeEvent
    public static void drawControlOutline(RenderHighlightEvent.Block event) {
        Minecraft minecraft = Minecraft.getInstance();
        ClientLevel level = minecraft.level;
        if (level == null)
            return;

        BlockPos pos = event.getTarget().getBlockPos();
        // Only the blocks whose shapes are expensive enough to be worth this, and
        // never outside the border, where vanilla would draw nothing either
        if (!(level.getBlockEntity(pos) instanceof ControlRackHolder) || !level.getWorldBorder().isWithinBounds(pos))
            return;

        VoxelShape shape = level.getBlockState(pos).getShape(level, pos,
                CollisionContext.of(event.getCamera().getEntity()));
        if (shape.isEmpty()) {
            // Nothing to draw, but vanilla would have walked the shape to find that
            // out, so the frame is still worth saving
            event.setCanceled(true);
            return;
        }

        draw(event, pos, edgesOf(shape));
        event.setCanceled(true);
    }

    /**
     * The shape's edges, walked only when it is not one of the shapes they were last
     * walked from. See the class comment for how many are kept and why the shape
     * itself is the key.
     */
    private static double[] edgesOf(VoxelShape shape) {
        for (int slot = 0; slot < REMEMBERED; slot++)
            if (walked[slot] == shape)
                return walkedEdges[slot];

        DoubleArrayList gathered = new DoubleArrayList();
        shape.forAllEdges((x1, y1, z1, x2, y2, z2) -> {
            gathered.add(x1);
            gathered.add(y1);
            gathered.add(z1);
            gathered.add(x2);
            gathered.add(y2);
            gathered.add(z2);
        });

        double[] walk = gathered.toDoubleArray();
        walked[next] = shape;
        walkedEdges[next] = walk;
        next = (next + 1) % REMEMBERED;
        return walk;
    }

    /**
     * The same lines {@code LevelRenderer.renderShape} would have emitted: two
     * vertices an edge, black, with the edge's own direction as the normal, offset
     * from the camera the way everything drawn in world space is.
     */
    private static void draw(RenderHighlightEvent.Block event, BlockPos pos, double[] edges) {
        Vec3 camera = event.getCamera().getPosition();
        double dx = pos.getX() - camera.x;
        double dy = pos.getY() - camera.y;
        double dz = pos.getZ() - camera.z;

        PoseStack.Pose pose = event.getPoseStack().last();
        VertexConsumer lines = event.getMultiBufferSource().getBuffer(RenderType.lines());
        for (int at = 0; at + 6 <= edges.length; at += 6) {
            double x1 = edges[at];
            double y1 = edges[at + 1];
            double z1 = edges[at + 2];
            double x2 = edges[at + 3];
            double y2 = edges[at + 4];
            double z2 = edges[at + 5];

            float nx = (float) (x2 - x1);
            float ny = (float) (y2 - y1);
            float nz = (float) (z2 - z1);
            float length = Mth.sqrt(nx * nx + ny * ny + nz * nz);
            nx /= length;
            ny /= length;
            nz /= length;

            lines.vertex(pose.pose(), (float) (x1 + dx), (float) (y1 + dy), (float) (z1 + dz))
                    .color(0, 0, 0, ALPHA).normal(pose.normal(), nx, ny, nz).endVertex();
            lines.vertex(pose.pose(), (float) (x2 + dx), (float) (y2 + dy), (float) (z2 + dz))
                    .color(0, 0, 0, ALPHA).normal(pose.normal(), nx, ny, nz).endVertex();
        }
    }
}
