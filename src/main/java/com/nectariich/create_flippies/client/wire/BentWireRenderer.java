package com.nectariich.create_flippies.client.wire;

import java.util.List;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.nectariich.create_flippies.foundation.wire.bend.BendGeometry;
import com.nectariich.create_flippies.foundation.wire.bend.BendableWire;
import com.nectariich.create_flippies.foundation.wire.bend.WireSweep;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.phys.Vec3;

import org.patryk3211.powergrid.collections.ModdedConfigs;
import org.patryk3211.powergrid.electricity.wire.HangingWireEntity;
import org.patryk3211.powergrid.electricity.wire.HangingWireRenderer;
import org.patryk3211.powergrid.electricity.wire.registry.WireItemEntry;

/**
 * Draws a bent wire: every leg hangs as its own catenary, and the whole path is one
 * tube ({@link WireSweep}) in Power Grid's look (its texture, colour, level of detail
 * and light sampled per piece), so the legs meet flush at each bend point and the
 * texture runs on unbroken from one leg into the next.
 */
public final class BentWireRenderer {

    private BentWireRenderer() {}

    /** The light a piece of wire is drawn with, from where it is in the world. */
    @FunctionalInterface
    public interface LightAt {
        int at(Vec3 world);
    }

    /** Power Grid's level of detail for a wire at {@code pos}: how long its pieces are, and whether it is drawn flat. */
    public record Detail(float segmentSize, boolean simple) {

        public static Detail of(Vec3 pos) {
            float segmentSize = 0.5f;
            boolean simple = !Minecraft.useFancyGraphics();
            if (ModdedConfigs.client().wireLOD.get() && Minecraft.getInstance().player != null) {
                double distance = Minecraft.getInstance().player.position().distanceToSqr(pos);
                if (distance > 4096) {
                    segmentSize = 3f;
                    simple = true;
                } else if (distance > 1024) {
                    segmentSize = 1.5f;
                }
            }
            return new Detail(segmentSize, simple);
        }
    }

    /** Light sampled from the level at each piece, as Power Grid lights a wire. */
    public static LightAt levelLight(Level level) {
        return at -> {
            BlockPos pos = BlockPos.containing(at);
            return LightTexture.pack(level.getBrightness(LightLayer.BLOCK, pos), level.getBrightness(LightLayer.SKY, pos));
        };
    }

    /** @return whether it drew the wire; false leaves it to Power Grid */
    public static boolean render(HangingWireEntity entity, PoseStack ms, MultiBufferSource buffers) {
        BendableWire wire = (BendableWire) entity;
        List<Vec3> path = wire.bendPath();
        WireItemEntry entry = entity.getWireEntry();
        if (path == null || entry == null)
            return false;
        // Power Grid draws nothing for a wire that has burnt out
        if (entity.isOverheated())
            return true;

        Vec3 pos = entity.position();
        Detail detail = Detail.of(pos);
        RenderType type = detail.simple() ? RenderType.entityCutoutNoCull(entry.texture())
                : RenderType.entityCutout(entry.texture());
        double h = entry.horizontalCoefficient(), v = entry.verticalCoefficient(), t = entry.wireThickness();
        List<WireSweep.Strip> strips = WireSweep.sweep(path, 0, h, v, t, detail.segmentSize(),
                firstCross(path, h, v, t));
        draw(ms, buffers.getBuffer(type), strips, pos, levelLight(entity.level()), entity.getColor() | 0xFF000000,
                (float) t, entity.getId() / 16f, detail.simple());
        return true;
    }

    /** The way Power Grid would turn the cross-section of the path's first leg, for the tube to start out the same. */
    public static Vec3 firstCross(List<Vec3> path, double horizontal, double vertical, double thickness) {
        for (int leg = 0; leg + 1 < path.size(); leg++)
            if (path.get(leg).distanceToSqr(path.get(leg + 1)) >= 1e-10)
                return BendGeometry.curve(path.get(leg), path.get(leg + 1), horizontal, vertical, thickness).cross1;
        return null;
    }

    /**
     * Draws tubes in Power Grid's wire style, relative to {@code origin}: four
     * textured sides (or, simple, two crossed planes) per piece, and a cap on each
     * open end of a tube.
     */
    public static void draw(PoseStack ms, VertexConsumer buffer, List<WireSweep.Strip> strips, Vec3 origin,
            LightAt light, int color, float thickness, float thicknessOffset, boolean simple) {
        PoseStack.Pose pose = ms.last();
        for (WireSweep.Strip strip : strips) {
            List<WireSweep.Ring> rings = strip.rings();
            for (int i = 1; i < rings.size(); i++) {
                WireSweep.Ring a = rings.get(i - 1), b = rings.get(i);
                int lit = light.at(a.at().add(b.at()).scale(0.5));
                Vec3 pa = a.at().subtract(origin), pb = b.at().subtract(origin);
                float length = (float) (b.offset() - a.offset()), offset = (float) a.offset();
                if (simple) {
                    plane(pose, buffer, lit, color, pa, pb, a.cross1(), b.cross1(), thickness, thicknessOffset,
                            length, offset);
                    plane(pose, buffer, lit, color, pa, pb, a.cross2(), b.cross2(), thickness, thicknessOffset,
                            length, offset);
                    continue;
                }
                Vec3 a1 = a.cross1(), a2 = a.cross2(), b1 = b.cross1(), b2 = b.cross2();
                side(pose, buffer, lit, color, pa, pb, a1, a2.reverse(), b1, b2.reverse(), thickness, thicknessOffset,
                        length, offset);
                side(pose, buffer, lit, color, pa, pb, a1.reverse(), a2, b1.reverse(), b2, thickness, thicknessOffset,
                        length, offset);
                side(pose, buffer, lit, color, pa, pb, a2, a1, b2, b1, thickness, thicknessOffset, length, offset);
                side(pose, buffer, lit, color, pa, pb, a2.reverse(), a1.reverse(), b2.reverse(), b1.reverse(),
                        thickness, thicknessOffset, length, offset);
            }
            if (simple || rings.size() < 2)
                continue;
            WireSweep.Ring first = strip.first(), second = rings.get(1);
            WireSweep.Ring last = strip.last(), beforeLast = rings.get(rings.size() - 2);
            cap(pose, buffer, light.at(first.at()), color, first, first.at().subtract(second.at()), origin, thickness,
                    thicknessOffset);
            cap(pose, buffer, light.at(last.at()), color, last, last.at().subtract(beforeLast.at()), origin,
                    thickness, thicknessOffset);
        }
    }

    /** One side of a piece, between corners {@code a} and {@code b} of each end; as Power Grid lays its sides out. */
    private static void side(PoseStack.Pose pose, VertexConsumer buffer, int light, int color, Vec3 from, Vec3 to,
            Vec3 fromA, Vec3 fromB, Vec3 toA, Vec3 toB, float thickness, float thicknessOffset, float length,
            float offset) {
        Vec3 normal = fromA.add(fromB).add(toA).add(toB);
        HangingWireRenderer.quad(pose, buffer, light, color, from.x + fromA.x, from.y + fromA.y, from.z + fromA.z,
                from.x + fromB.x, from.y + fromB.y, from.z + fromB.z, to.x + toA.x, to.y + toA.y, to.z + toA.z,
                to.x + toB.x, to.y + toB.y, to.z + toB.z, normal.x, normal.y, normal.z, thickness, thicknessOffset,
                length, offset);
    }

    /** One of the two crossed planes of a piece drawn simple. */
    private static void plane(PoseStack.Pose pose, VertexConsumer buffer, int light, int color, Vec3 from, Vec3 to,
            Vec3 fromCross, Vec3 toCross, float thickness, float thicknessOffset, float length, float offset) {
        HangingWireRenderer.quad(pose, buffer, light, color, from.x + fromCross.x, from.y + fromCross.y,
                from.z + fromCross.z, from.x - fromCross.x, from.y - fromCross.y, from.z - fromCross.z,
                to.x + toCross.x, to.y + toCross.y, to.z + toCross.z, to.x - toCross.x, to.y - toCross.y,
                to.z - toCross.z, thickness, thicknessOffset, length, offset);
    }

    /** Closes an open end of a tube, facing {@code outward}. */
    private static void cap(PoseStack.Pose pose, VertexConsumer buffer, int light, int color, WireSweep.Ring ring,
            Vec3 outward, Vec3 origin, float thickness, float thicknessOffset) {
        Vec3 p = ring.at().subtract(origin);
        Vec3 c1 = ring.cross1(), c2 = ring.cross2();
        HangingWireRenderer.quad(pose, buffer, light, color, p.x + c1.x, p.y + c1.y, p.z + c1.z, p.x + c2.x,
                p.y + c2.y, p.z + c2.z, p.x - c2.x, p.y - c2.y, p.z - c2.z, p.x - c1.x, p.y - c1.y, p.z - c1.z,
                outward.x, outward.y, outward.z, thickness, thicknessOffset, thickness, thicknessOffset);
    }
}
