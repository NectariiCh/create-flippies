package com.nectariich.create_flippies.foundation.wire.bend;

import java.util.List;

import com.nectariich.create_flippies.network.ModPackets;
import com.nectariich.create_flippies.network.WireBendsSyncPacket;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.network.PacketDistributor;

import org.patryk3211.powergrid.utility.PlayerUtilities;

/**
 * The server's side of bending: checks an edit a player asked for, makes it, and
 * tells every client watching the wire. Nothing a client says about a wire's points
 * is taken on trust; a refused edit is answered with the points as they really are,
 * so the client that predicted it falls back into step.
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
        wire.setBendPoints(WireBends.apply(op, points, index, position));
        sync(entity);
        if (settles)
            wire.bendsCommitted();
    }

    private static void refuse(ServerPlayer player, BendableWire wire, WireBends.Verdict verdict, boolean settles) {
        ModPackets.CHANNEL.send(PacketDistributor.PLAYER.with(() -> player),
                new WireBendsSyncPacket(wire.bendEntity().getId(), wire.bendPoints()));
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
        ModPackets.CHANNEL.send(PacketDistributor.TRACKING_ENTITY.with(() -> entity),
                new WireBendsSyncPacket(entity.getId(), wire.bendPoints()));
    }
}
