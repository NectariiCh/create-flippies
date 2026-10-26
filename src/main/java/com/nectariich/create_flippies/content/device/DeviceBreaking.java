package com.nectariich.create_flippies.content.device;

import java.util.Map;
import java.util.UUID;
import java.util.WeakHashMap;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;

/**
 * The server's side of breaking devices: a hold starts with a packet, and a packet
 * at its end breaks the device if the player is still in reach and has held long
 * enough. As vanilla allows a block, the hold may come up to 30% short, for lag; in
 * creative a click breaks it at once.
 */
public final class DeviceBreaking {

    /** How far beyond block reach a device may still be broken, blocks, as the server allows for blocks. */
    private static final double REACH_SLACK = 1;

    private record Hold(int entityId, long since) {}

    private static final Map<UUID, Hold> HOLDS = new WeakHashMap<>();

    private DeviceBreaking() {}

    public static void handle(ServerPlayer player, int entityId, boolean finished) {
        DeviceEntity device = DeviceEntity.byId(player.serverLevel(), entityId);
        if (device == null || device.isRemoved() || !inReach(player, device)) {
            HOLDS.remove(player.getUUID());
            return;
        }
        long now = player.serverLevel().getGameTime();
        if (player.getAbilities().instabuild) {
            device.breakBy(player);
            return;
        }
        if (!finished) {
            HOLDS.put(player.getUUID(), new Hold(entityId, now));
            return;
        }
        Hold hold = HOLDS.remove(player.getUUID());
        if (hold == null || hold.entityId() != entityId)
            return;
        if (now - hold.since() >= device.breakTicks(player) * 0.7)
            device.breakBy(player);
    }

    private static boolean inReach(ServerPlayer player, DeviceEntity device) {
        Vec3 eye = player.getEyePosition();
        double reach = player.getBlockReach() + REACH_SLACK;
        return device.getBoundingBox().distanceToSqr(eye) <= reach * reach;
    }
}
