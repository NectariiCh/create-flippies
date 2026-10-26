package com.nectariich.create_flippies.foundation.wire.entity;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.WeakHashMap;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

/**
 * An entity with Power Grid terminals: it owns an {@link EntityElectricHolder} and
 * says where each terminal is. Power Grid finds it through
 * {@link EntityWireEndpoint}, by UUID, in the hosts each level has loaded.
 */
public interface EntityElectricHost {

    EntityElectricHolder electric();

    /** Where terminal {@code terminal} is, in world space: where a cord on it ends. */
    Vec3 terminalPosition(int terminal);

    /** The hosts each level has loaded, by UUID; both sides, filled as hosts are added and removed. */
    Map<Level, Map<UUID, EntityElectricHost>> LOADED = new WeakHashMap<>();

    /** The loaded host with this UUID in {@code level}, or null. */
    static EntityElectricHost find(Level level, UUID id) {
        synchronized (LOADED) {
            Map<UUID, EntityElectricHost> hosts = LOADED.get(level);
            return hosts == null ? null : hosts.get(id);
        }
    }

    static void added(Entity entity) {
        if (!(entity instanceof EntityElectricHost host))
            return;
        synchronized (LOADED) {
            LOADED.computeIfAbsent(entity.level(), l -> new HashMap<>()).put(entity.getUUID(), host);
        }
    }

    static void removed(Entity entity) {
        synchronized (LOADED) {
            Map<UUID, EntityElectricHost> hosts = LOADED.get(entity.level());
            if (hosts != null && hosts.get(entity.getUUID()) == entity)
                hosts.remove(entity.getUUID());
        }
    }
}
