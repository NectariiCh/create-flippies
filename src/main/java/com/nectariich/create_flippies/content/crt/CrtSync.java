package com.nectariich.create_flippies.content.crt;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import com.nectariich.create_flippies.content.pc.PcDisplayView;
import com.nectariich.create_flippies.foundation.ServerConfig;
import com.nectariich.create_flippies.network.CrtFramePacket;
import com.nectariich.create_flippies.network.ModPackets;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;

/**
 * Sends every loaded CRT's picture to the players near it (spec 11.2.14). Runs on
 * the server thread once per tick, right after the PCs ran, so each display is read
 * between machine ticks.
 *
 * Per tick: every CRT captures its source (or clears, with no signal); the players
 * who track the CRT's chunk and are within {@code displaySyncRadius} are its
 * viewers. Each player's CRTs are served nearest first, each at most
 * {@code displaySyncMaxFps} times a second, while the player's byte bucket
 * ({@code displaySyncBytesPerPlayerPerTick}) is above zero. A CRT that had to wait
 * sends the rows that changed meanwhile, all at once, later.
 */
public final class CrtSync {

    private final Set<CrtEntity> loaded = new HashSet<>();
    private final Map<UUID, CrtRateLimit.Bucket> buckets = new HashMap<>();

    public void add(CrtEntity crt) {
        loaded.add(crt);
    }

    public void remove(CrtEntity crt) {
        loaded.remove(crt);
    }

    private record Watch(ServerPlayer player, CrtEntity crt, double distanceSqr) {}

    public void tick(long gameTime) {
        int radius = ServerConfig.displaySyncRadius();
        double radiusSqr = (double) radius * radius;
        int interval = CrtRateLimit.intervalTicks(ServerConfig.displaySyncMaxFps());
        int allowance = ServerConfig.displaySyncBytesPerPlayerPerTick();

        Map<ServerPlayer, List<Watch>> byPlayer = new HashMap<>();
        for (CrtEntity crt : List.copyOf(loaded)) {
            if (crt.isRemoved() || !(crt.level() instanceof ServerLevel level)) {
                loaded.remove(crt);
                continue;
            }
            PcDisplayView source = crt.source();
            CrtStream stream = crt.stream();
            if (source == null) {
                stream.clear();
                continue;
            }
            try {
                stream.capture(source);
            } catch (RuntimeException e) {
                // A display that cannot be read shows nothing this tick; the PC's own guard deals with it
                stream.clear();
                continue;
            }
            Vec3 centre = crt.getBoundingBox().getCenter();
            Set<UUID> watching = new HashSet<>();
            for (ServerPlayer player : level.getChunkSource().chunkMap.getPlayers(crt.chunkPosition(),
                    false)) {
                double d = player.distanceToSqr(centre);
                if (d > radiusSqr)
                    continue;
                watching.add(player.getUUID());
                byPlayer.computeIfAbsent(player, p -> new ArrayList<>()).add(new Watch(player, crt, d));
            }
            stream.retainViewers(watching);
        }

        Set<UUID> online = new HashSet<>();
        for (Map.Entry<ServerPlayer, List<Watch>> entry : byPlayer.entrySet()) {
            ServerPlayer player = entry.getKey();
            online.add(player.getUUID());
            CrtRateLimit.Bucket bucket = buckets.computeIfAbsent(player.getUUID(), id -> new CrtRateLimit.Bucket());
            bucket.refill(allowance);
            List<Watch> watches = entry.getValue();
            watches.sort(Comparator.comparingDouble(Watch::distanceSqr));
            for (Watch w : watches) {
                if (!bucket.canSend())
                    break;
                byte[] payload = w.crt().stream().updateFor(player.getUUID(), gameTime, interval);
                if (payload == null)
                    continue;
                ModPackets.sendToPlayer(player,
                        new CrtFramePacket(w.crt().getId(), payload));
                bucket.spend(payload.length);
            }
        }
        buckets.keySet().retainAll(online);
    }
}
