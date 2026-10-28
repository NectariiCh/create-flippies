package com.nectariich.create_flippies.client.wire;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.nectariich.create_flippies.content.wire_duct.DuctIndex;
import com.nectariich.create_flippies.content.wire_duct.DuctedWires;
import com.nectariich.create_flippies.foundation.wire.duct.DuctWires;

import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.phys.Vec3;

import org.patryk3211.powergrid.electricity.wire.BlockWireEntity;
import org.patryk3211.powergrid.electricity.wire.BlockWireRenderer;
import org.patryk3211.powergrid.electricity.wire.registry.WireItemEntry;

/**
 * Draws a Power Grid placeable wire that runs through wire ducts: what lies
 * outside them as Power Grid draws it, in its own texture and colour, and nothing
 * of what lies inside them. Only a preview of a wire being laid also draws its
 * part inside as dashed strokes lying flat on the lids, in the same texture and
 * colour, side by side with the other wires there, each as wide as its gauge
 * ({@link DuctWires#layout}); once the wire is down the duct hides it.
 *
 * What is hidden and where the strokes go is worked out once and kept until the
 * wire's chain or a duct around it changes.
 */
public final class DuctWireRenderer {

    /** A wire drawn through ducts: the parts left showing and the dashes on the lids. */
    public record Drawn(List<Vec3> path, List<DuctWires.Span> visible, List<DuctWires.Dash> strokes) {}

    private record Cached(long key, int version, Drawn drawn) {}

    private static final Map<BlockWireEntity, Cached> CACHE = Collections.synchronizedMap(new WeakHashMap<>());

    private DuctWireRenderer() {}

    /** What to draw of a wire, or null for one that runs in no duct. */
    public static Drawn drawn(BlockWireEntity wire) {
        Level level = wire.level();
        long key = key(wire);
        int version = DuctIndex.version(level);
        Cached cached = CACHE.get(wire);
        if (cached != null && cached.key() == key && cached.version() == version)
            return cached.drawn();
        Drawn drawn = work(level, DuctedWires.path(wire), wire.getWireEntry(),
                DuctedWires.slots(level, DuctedWires.tie(wire), List.of()));
        CACHE.put(wire, new Cached(key, version, drawn));
        return drawn;
    }

    /**
     * What to draw of a path through the ducts of a level, or null when it runs in
     * none; {@code slots} places its strokes among the other wires'.
     */
    public static Drawn work(Level level, List<Vec3> path, WireItemEntry entry, DuctWires.Slots slots) {
        List<DuctWires.Duct> in = DuctedWires.holding(level, path);
        if (in.isEmpty() || entry == null)
            return null;
        List<DuctWires.Piece> hidden = DuctWires.hidden(path, in);
        return new Drawn(path, DuctWires.visible(path, hidden),
                DuctWires.strokes(path, hidden, slots, defaultWidth(entry)));
    }

    /** How wide a stroke is drawn where no slot places it: about as wide as the wire, within reason. */
    public static double defaultWidth(WireItemEntry entry) {
        return Math.max(DuctWires.MIN_STROKE, Math.min(0.1, entry.wireThickness()));
    }

    private static long key(BlockWireEntity wire) {
        Vec3 pos = wire.position();
        long h = Double.hashCode(pos.x);
        h = h * 31 + Double.hashCode(pos.y);
        h = h * 31 + Double.hashCode(pos.z);
        for (BlockWireEntity.Point point : wire.segments)
            h = h * 31 + point.direction.ordinal() * 1_000_003L + point.gridLength;
        return h * 31 + wire.segments.size();
    }

    /** Draws {@code entity} if it runs in a duct; false to leave it to Power Grid. */
    public static boolean render(BlockWireEntity entity, PoseStack ms, MultiBufferSource buffers) {
        WireItemEntry entry = entity.getWireEntry();
        if (entity.isOverheated() || entry == null || entity.segments.isEmpty())
            return false;
        Drawn drawn = drawn(entity);
        if (drawn == null)
            return false;
        VertexConsumer buffer = buffers.getBuffer(RenderType.entitySolid(entry.texture()));
        draw(ms, buffer, entity.level(), entity.position(), drawn, entity.getColor() | 0xFF000000,
                entry.wireThickness(), entity.getId(), false);
        return true;
    }

    /**
     * Draws a wire relative to {@code origin}, where the pose stack stands. A preview
     * is drawn fully lit and with its strokes on the lids; a wire is lit as Power
     * Grid lights it, and shows nothing inside a duct.
     */
    public static void draw(PoseStack ms, VertexConsumer buffer, Level level, Vec3 origin, Drawn drawn, int color,
            float thickness, int uvOffset, boolean preview) {
        Vec3 start = drawn.path().get(0);
        for (DuctWires.Span span : drawn.visible()) {
            Vec3 a = span.from(), b = span.to();
            Direction dir = direction(a, b);
            float length = (float) a.distanceTo(b);
            // Power Grid starts a wire a pixel behind its first point, to tuck it into the terminal
            if (span.leg() == 0 && a.distanceToSqr(start) < 1e-12) {
                a = a.relative(dir, -0.0625);
                length += 0.0625f;
            }
            BlockWireRenderer.renderSegment(ms, buffer, preview ? LightTexture.FULL_BRIGHT : light(level, b, dir),
                    color, a.subtract(origin), dir, thickness, length, uvOffset);
        }
        if (!preview)
            return;
        for (DuctWires.Dash dash : drawn.strokes())
            dash(ms, buffer, origin, dash, color,
                    preview ? LightTexture.FULL_BRIGHT : light(level, dash.to(), direction(dash.from(), dash.to())));
    }

    /**
     * One dash as a flat strip on its lid, relative to {@code origin}, textured
     * along its length as Power Grid textures a wire's top, and lit by the game from
     * the lid's normal.
     */
    public static void dash(PoseStack ms, VertexConsumer buffer, Vec3 origin, DuctWires.Dash dash, int color,
            int light) {
        Vec3 a = dash.from().subtract(origin), b = dash.to().subtract(origin);
        Vec3 d = b.subtract(a);
        double length = d.length();
        if (length < 1e-6)
            return;
        Vec3 n = dash.normal();
        Vec3 across = n.cross(d.scale(1 / length)).scale(dash.width() / 2);
        float u = (float) length, v = (float) dash.width();
        PoseStack.Pose pose = ms.last();
        // Wound counter-clockwise seen from the lid's side
        vertex(pose, buffer, a.subtract(across), 0, 0, n, color, light);
        vertex(pose, buffer, b.subtract(across), u, 0, n, color, light);
        vertex(pose, buffer, b.add(across), u, v, n, color, light);
        vertex(pose, buffer, a.add(across), 0, v, n, color, light);
    }

    private static void vertex(PoseStack.Pose pose, VertexConsumer buffer, Vec3 p, float u, float v, Vec3 n,
            int color, int light) {
        //? if forge {
        buffer.vertex(pose.pose(), (float) p.x, (float) p.y, (float) p.z).color(color).uv(u, v)
                .overlayCoords(OverlayTexture.NO_OVERLAY).uv2(light)
                .normal(pose.normal(), (float) n.x, (float) n.y, (float) n.z).endVertex();
        //?} else {
        /*buffer.addVertex(pose.pose(), (float) p.x, (float) p.y, (float) p.z).setColor(color).setUv(u, v)
                .setOverlay(OverlayTexture.NO_OVERLAY).setLight(light)
                .setNormal(pose, (float) n.x, (float) n.y, (float) n.z);
        *///?}
    }

    private static Direction direction(Vec3 a, Vec3 b) {
        Vec3 d = b.subtract(a);
        return Direction.getNearest(d.x, d.y, d.z);
    }

    /** The brightest light around a point of the wire, sampled the way Power Grid samples it. */
    private static int light(Level level, Vec3 at, Direction along) {
        int best = 0;
        for (Direction dir : Direction.values()) {
            if (dir.getAxis() == along.getAxis())
                continue;
            BlockPos pos = BlockPos.containing(at.add(Vec3.atLowerCornerOf(dir.getNormal()).scale(1 / 16.0)));
            int packed = LightTexture.pack(level.getBrightness(LightLayer.BLOCK, pos),
                    level.getBrightness(LightLayer.SKY, pos));
            if (packed > best)
                best = packed;
        }
        return best;
    }
}
