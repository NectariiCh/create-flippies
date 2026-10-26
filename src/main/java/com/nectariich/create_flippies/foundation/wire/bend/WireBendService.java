package com.nectariich.create_flippies.foundation.wire.bend;

import java.util.List;

import com.nectariich.create_flippies.foundation.wire.gauge.WireGaugeCompat;
import com.nectariich.create_flippies.network.ModPackets;
import com.nectariich.create_flippies.network.WireBendsSyncPacket;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.network.PacketDistributor;

import org.patryk3211.powergrid.electricity.wire.HangingWireEntity;
import org.patryk3211.powergrid.utility.PlayerUtilities;

/**
 * The server's side of bending: checks an edit a player asked for, makes it, and
 * tells every client watching the wire. Nothing a client says about a wire's points
 * is taken on trust; a refused edit is answered with the points as they really are,
 * so the client that predicted it falls back into step.
 *
 * WIRE SAG: the same goes for the sag of each leg ({@link LegSag}), which every
 * edit of the points carries along ({@link #applyEdit}) and which a player can set
 * on its own ({@link #setLegSag}).
 */
public final class WireBendService {

    private WireBendService() {}

    /**
     * One edit from one player. {@code commit} marks the last move of a drag, and is
     * implied by an insert or a removal: it is when a wire whose behaviour depends
     * on its shape is told the shape has settled.
     */
    public static void edit(ServerPlayer player, int entityId, WireBends.Op op, int index, Vec3 position,
            boolean commit) {
        Level level = player.level();
        Entity entity = level.getEntity(entityId);
        if (!(entity instanceof BendableWire wire) || !entity.isAlive())
            return;
        boolean settles = commit || op != WireBends.Op.MOVE;
        if (!WireBendItems.holdsWireItem(player) || !level.mayInteract(player, entity.blockPosition())) {
            refuse(player, wire, null, settles);
            return;
        }
        Vec3 start = wire.bendStart();
        Vec3 end = wire.bendEnd();
        if (start == null || end == null)
            return;
        List<Vec3> points = wire.bendPoints();
        WireBends.Verdict verdict = WireBends.check(op, points, index, position, start, end, wire.bendMaxSpan(),
                player.getEyePosition(), PlayerUtilities.getReachDistance(player));
        if (verdict != WireBends.Verdict.OK) {
            refuse(player, wire, verdict, settles);
            return;
        }
        applyEdit(wire, op, index, position);
        sync(entity);
        if (settles)
            wire.bendsCommitted();
    }

    /**
     * Makes one edit of a wire's points, on either side (the client predicts its own),
     * and keeps the legs' sag in step: a new point's two legs both keep the sag of the
     * leg it split, a removed point's two legs merge ({@link LegSag#apply}).
     *
     * With Power Plant Grid installed an unbent wire's sag is its own (see
     * {@link WireSagCompat}): the first point seeds both legs from it, which
     * {@link BendableWire#bendLegSags} already reads, and removing the last point hands
     * the merged sag back to it.
     */
    public static void applyEdit(BendableWire wire, WireBends.Op op, int index, Vec3 position) {
        List<Vec3> points = wire.bendPoints();
        float[] sags = wire.bendLegSags();
        List<Vec3> before = wire.bendPath();
        List<Vec3> next = WireBends.apply(op, points, index, position);
        wire.setBendPoints(next);
        if (sags == null || op == WireBends.Op.MOVE)
            return;
        float[] after = LegSag.apply(op, sags, index, before);
        wire.setBendLegSags(after);
        if (!points.isEmpty() && next.isEmpty() && after.length > 0)
            WireSagCompat.handOver(wire.bendEntity(), after[0]);
    }

    /**
     * A player setting the sag of one leg of a hanging wire, as dragged by hand
     * ({@code WireSagInput}). The checks are Power Plant Grid's for its own sag (the
     * same wire item in hand, the eye near the wire) and the bend edits' (the player
     * may build there); a ducted leg hangs straight whatever its sag, so it has none to
     * set. With Power Plant Grid installed an unbent wire's sag is Power Plant Grid's,
     * so only a bent one is taken. A refused value is answered with the wire as it is.
     * {@code pointCount} is how many bend points the sender saw: any other count means
     * the legs have shifted since, and the value would land on the wrong leg. Sag never
     * changes resistance, so the connection is left alone.
     */
    public static void setLegSag(ServerPlayer player, int entityId, int leg, int pointCount, float sag) {
        Level level = player.level();
        Entity entity = level.getEntity(entityId);
        if (!(entity instanceof HangingWireEntity hanging) || !(entity instanceof BendableWire wire)
                || !entity.isAlive())
            return;
        float[] sags = wire.bendLegSags();
        List<Vec3> path = wire.bendPath();
        if (sags == null || path == null)
            return;
        boolean ok = Float.isFinite(sag) && sag > 0
                && player.getMainHandItem().getItem() == hanging.getItem()
                && level.mayInteract(player, entity.blockPosition())
                && wire.bendPoints().size() == pointCount
                && leg >= 0 && leg + 1 < path.size() && leg < sags.length
                && !BendGeometry.isDucted(wire.bendDuctLegs(), leg)
                && !(WireGaugeCompat.ppgLoaded() && wire.bendPoints().isEmpty());
        if (ok) {
            double range = PlayerUtilities.getReachDistance(player) + WireBends.REACH_MARGIN;
            ok = legDistanceSqr(player.getEyePosition(), wire, path, leg, sags[leg]) <= range * range;
        }
        if (!ok) {
            ModPackets.CHANNEL.send(PacketDistributor.PLAYER.with(() -> player), syncPacket(entity, wire));
            return;
        }
        sags[leg] = (float) LegSag.clamp(wire.bendHorizontalCoefficient(), sag);
        wire.setBendLegSags(sags);
        sync(entity);
    }

    /**
     * How near the eye is to a leg: to its straight chord, as Power Plant Grid measures,
     * or to the curve it hangs in now, whichever is nearer, so a deeply sagging leg
     * can still be reached where it is drawn.
     */
    private static double legDistanceSqr(Vec3 eye, BendableWire wire, List<Vec3> path, int leg, float sag) {
        Vec3 from = path.get(leg), to = path.get(leg + 1);
        double best = segmentDistanceSqr(eye, from, to);
        List<Vec3> vertices = BendGeometry.legVertices(from, to,
                LegSag.horizontal(wire.bendHorizontalCoefficient(), sag),
                LegSag.vertical(wire.bendVerticalCoefficient(), sag), wire.bendThickness(),
                BendGeometry.SAMPLE_STEP * 2);
        for (int i = 1; i < vertices.size(); i++)
            best = Math.min(best, segmentDistanceSqr(eye, vertices.get(i - 1), vertices.get(i)));
        return best;
    }

    private static double segmentDistanceSqr(Vec3 p, Vec3 a, Vec3 b) {
        Vec3 ab = b.subtract(a);
        double length = ab.lengthSqr();
        double t = length < 1e-12 ? 0 : Math.max(0, Math.min(1, p.subtract(a).dot(ab) / length));
        return p.distanceToSqr(a.add(ab.scale(t)));
    }

    private static WireBendsSyncPacket syncPacket(Entity entity, BendableWire wire) {
        return new WireBendsSyncPacket(entity.getId(), wire.bendPoints(), wire.bendLegSags());
    }

    private static void refuse(ServerPlayer player, BendableWire wire, WireBends.Verdict verdict, boolean settles) {
        ModPackets.CHANNEL.send(PacketDistributor.PLAYER.with(() -> player), syncPacket(wire.bendEntity(), wire));
        if (verdict != null && verdict != WireBends.Verdict.BAD_INDEX && verdict != WireBends.Verdict.NOT_FINITE)
            player.displayClientMessage(Component.translatable(verdict.langKey()).withStyle(ChatFormatting.RED), true);
        // Earlier moves of the same drag were made, so the drag still settles
        if (settles)
            wire.bendsCommitted();
    }

    /** Sends a wire's points to every client tracking it. */
    public static void sync(Entity entity) {
        if (entity.level().isClientSide || !(entity instanceof BendableWire wire))
            return;
        ModPackets.CHANNEL.send(PacketDistributor.TRACKING_ENTITY.with(() -> entity), syncPacket(entity, wire));
    }
}
