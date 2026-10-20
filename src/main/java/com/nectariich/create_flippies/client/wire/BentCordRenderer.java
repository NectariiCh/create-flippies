package com.nectariich.create_flippies.client.wire;

import java.util.List;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.nectariich.create_flippies.foundation.wire.bend.BendGeometry;
import com.nectariich.create_flippies.foundation.wire.bend.BendableWire;
import com.nectariich.create_flippies.foundation.wire.bend.WireBends;
import com.nectariich.create_flippies.foundation.wire.bend.WireSweep;

import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.core.Direction;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

import org.patryk3211.powergrid.electricity.wire.HangingWireRenderer;
import org.patryk3211.powergrid.electricity.wire.IWireEndpoint;
import org.patryk3211.powergrid.electricity.wire.powercord.AutoCordEndpoint;
import org.patryk3211.powergrid.electricity.wire.powercord.CordEntity;
import org.patryk3211.powergrid.electricity.wire.powercord.SocketEndpoint;
import org.patryk3211.powergrid.electricity.wire.powercord.SplitCordEndpoint;
import org.patryk3211.powergrid.electricity.wire.registry.WireItemEntry;

/**
 * WIRE BENDS 2: draws a bent Power Grid cord, power or string light, the way its own
 * renderer draws an unbent one, with the cord itself as one tube along every leg
 * ({@link BentWireRenderer}):
 * <ul>
 * <li>a plug at each end in a socket or a block, at the cord's true ends: where the
 * first leg starts and the last leg ends;</li>
 * <li>at a split end, the cord's first (or last) piece replaced by its two thin
 * coloured leads, as Power Grid draws them;</li>
 * <li>Power Grid's per-piece hook called at places spread evenly along the whole
 * bent cord, which is where a string light cord hangs its bulbs.</li>
 * </ul>
 */
public final class BentCordRenderer {

    private BentCordRenderer() {}

    /** Power Grid's plug, drawn relative to the cord. */
    @FunctionalInterface
    public interface Plug {
        void draw(BlockState state, Direction facing, double x, double y, double z, int light);
    }

    /** Power Grid's hook for one piece of a cord, relative to the cord. */
    @FunctionalInterface
    public interface Hook {
        void piece(double x1, double y1, double z1, double x2, double y2, double z2, double offset, double length,
                boolean first, boolean last, int light);
    }

    /** The colours of a split end's two leads, as Power Grid has them. */
    private static final int LEAD_1 = -5231066, LEAD_2 = -12827478;

    /** @return whether it drew the cord; false leaves it to Power Grid */
    public static boolean render(CordEntity entity, PoseStack ms, MultiBufferSource buffers, Plug plug, Hook hook) {
        BendableWire wire = (BendableWire) entity;
        List<Vec3> path = wire.bendPath();
        WireItemEntry entry = entity.getWireEntry();
        if (path == null || entry == null || entity.curveParams == null)
            return false;
        // Power Grid draws nothing for a cord that has burnt out
        if (entity.isOverheated())
            return true;

        Vec3 pos = entity.position();
        BentWireRenderer.Detail detail = BentWireRenderer.Detail.of(pos);
        double h = entry.horizontalCoefficient(), v = entry.verticalCoefficient(), t = entry.wireThickness();
        double step = detail.segmentSize();
        List<Vec3> line = WireSweep.polyline(path, 0, h, v, t, step);
        if (line.size() < 2)
            return false;
        Level level = entity.level();
        BentWireRenderer.LightAt light = BentWireRenderer.levelLight(level);
        float thicknessOffset = entity.getId() / 16f;
        boolean simple = detail.simple();
        RenderType type = RenderType.entityCutoutNoCull(entry.texture());

        List<WireSweep.Strip> strips = WireSweep.sweep(path, 0, h, v, t, step, BentWireRenderer.firstCross(path, h, v,
                t));
        IWireEndpoint end1 = entity.getEndpoint1(), end2 = entity.getEndpoint2();
        boolean split1 = end1 instanceof SplitCordEndpoint, split2 = end2 instanceof SplitCordEndpoint;
        if (split1)
            strips = WireSweep.withoutFirstPiece(strips);
        if (split2)
            strips = WireSweep.withoutLastPiece(strips);
        BentWireRenderer.draw(ms, buffers.getBuffer(type), strips, pos, light, entity.getColor() | 0xFF000000,
                (float) t, thicknessOffset, simple);

        int n = line.size();
        Vec3 start = line.get(0), second = line.get(1), beforeEnd = line.get(n - 2), end = line.get(n - 1);
        double total = WireBends.length(line);
        int startLight = light.at(start.add(second).scale(0.5));
        int endLight = light.at(beforeEnd.add(end).scale(0.5));
        if (end1 instanceof SplitCordEndpoint split)
            leads(ms, buffers.getBuffer(type), level, split, second, legNormal(path, h, v, t, true), pos, true,
                    start.distanceTo(second), 0, (float) t, thicknessOffset, startLight, simple);
        else
            plug(plug, level, end1, start.subtract(pos), startLight);
        if (end2 instanceof SplitCordEndpoint split)
            leads(ms, buffers.getBuffer(type), level, split, beforeEnd, legNormal(path, h, v, t, false), pos, false,
                    beforeEnd.distanceTo(end), total - beforeEnd.distanceTo(end), (float) t, thicknessOffset,
                    endLight, simple);
        else
            plug(plug, level, end2, end.subtract(pos), endLight);

        // Power Grid's hook, for a string light cord's bulbs: spread over the whole cord as over one curve
        int count = Math.max((int) Math.round(total / step), 5);
        List<Vec3> spots = WireSweep.divide(line, count);
        double along = 0;
        for (int k = 1; k < spots.size(); k++) {
            Vec3 a = spots.get(k - 1), b = spots.get(k);
            double length = a.distanceTo(b);
            boolean first = k == 1, last = k == spots.size() - 1;
            if (!(first && split1) && !(last && split2)) {
                Vec3 ra = a.subtract(pos), rb = b.subtract(pos);
                hook.piece(ra.x, ra.y, ra.z, rb.x, rb.y, rb.z, along, length, first, last,
                        light.at(a.add(b).scale(0.5)));
            }
            along += length;
        }
        return true;
    }

    /** Power Grid's plug for an end in a socket or in a block that takes a cord. */
    private static void plug(Plug plug, Level level, IWireEndpoint endpoint, Vec3 at, int light) {
        if (endpoint instanceof SocketEndpoint socket) {
            plug.draw(level.getBlockState(socket.getPosition()), socket.getFacing(level), at.x, at.y, at.z, light);
        } else if (endpoint instanceof AutoCordEndpoint auto) {
            Direction facing = auto.getPlugFacing();
            if (facing != null)
                plug.draw(level.getBlockState(auto.getPosition()), facing.getOpposite(), at.x, at.y, at.z, light);
        }
    }

    /** The horizontal way the first (or last) leg runs, as Power Grid's curve gives it. */
    private static Vec3 legNormal(List<Vec3> path, double h, double v, double t, boolean first) {
        int legs = path.size() - 1;
        for (int i = 0; i < legs; i++) {
            int leg = first ? i : legs - 1 - i;
            Vec3 from = path.get(leg), to = path.get(leg + 1);
            if (from.distanceToSqr(to) >= 1e-10)
                return BendGeometry.curve(from, to, h, v, t).getNormal();
        }
        return Vec3.ZERO;
    }

    /**
     * A split end's two thin leads, from its two terminals to {@code joint}, the far
     * end of the cord's first piece (or the near end of its last): Power Grid's own
     * drawing of them, with {@code normal} the way the cord runs from that end.
     */
    private static void leads(PoseStack ms, VertexConsumer buffer, Level level, SplitCordEndpoint split, Vec3 joint,
            Vec3 normal, Vec3 pos, boolean atStart, double length, double offset, float thickness,
            float thicknessOffset, int light, boolean simple) {
        Vec3 j = joint.subtract(pos);
        Vec3 nudge = normal.scale(atStart ? 1 / 32.0 : -1 / 32.0);
        Vec3[] terminals = {split.getEndpoint1().getExactPosition(level), split.getEndpoint2().getExactPosition(level)};
        for (int i = 0; i < 2; i++) {
            Vec3 terminal = terminals[i].subtract(pos);
            Vec3 direction = joint.subtract(terminals[i]);
            Vec3 v1 = new Vec3(1 - direction.x, 1 - direction.y, 1 - direction.z);
            Vec3 small1 = v1.cross(direction).normalize().scale(thickness * 0.25);
            Vec3 small2 = small1.cross(direction).normalize().scale(thickness * 0.25);
            Vec3 side = small1.add(small2).scale(i == 0 ? -0.5 : 0.5);
            Vec3 meet = j.add(side).add(nudge);
            Vec3 from = atStart ? terminal : meet, to = atStart ? meet : terminal;
            HangingWireRenderer.renderSegment(ms, buffer, from.x, from.y, from.z, to.x, to.y, to.z, small1, small2,
                    light, i == 0 ? LEAD_1 : LEAD_2, thickness * 0.5f, thicknessOffset, (float) (length * 2),
                    (float) offset, simple);
        }
    }
}
