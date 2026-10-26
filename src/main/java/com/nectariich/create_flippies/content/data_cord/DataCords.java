package com.nectariich.create_flippies.content.data_cord;

import java.util.UUID;

import com.nectariich.create_flippies.registry.ModItems;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Containers;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * Making and breaking data cords (spec 11.2.5). A cord is three things kept in step:
 * the entity that draws it and can be hit, and a {@link DataLink} in each endpoint
 * host. The cord entity's UUID is the links' {@code cordId}, so either end can find
 * the entity.
 *
 * The item comes back exactly once, from whoever breaks the cord: an attack on the
 * entity, or the host at either end being broken. A link found stale when a chunk
 * loads (its peer is gone or does not hold it back) is only cleared, because the
 * peer's breaking already dropped the item.
 */
public final class DataCords {

    private DataCords() {}

    /**
     * Whether the host at {@code address} would be there if it exists: its chunk is
     * loaded and, for an entity host, the chunk's entities are too (they load after
     * the blocks). A host not loaded is neither there nor gone.
     */
    public static boolean loaded(Level level, PortAddress address) {
        if (!level.isLoaded(address.pos()))
            return false;
        if (!address.isEntity())
            return true;
        return !(level instanceof ServerLevel server) || server.areEntitiesLoaded(ChunkPos.asLong(address.pos()));
    }

    /** The port host at {@code address}, if it is loaded and is one. */
    public static DataPortHost hostAt(Level level, PortAddress address) {
        if (!loaded(level, address))
            return null;
        if (!address.isEntity())
            return level.getBlockEntity(address.pos()) instanceof DataPortHost host ? host : null;
        return entity(level, address) instanceof DataPortHost host ? host : null;
    }

    /** The entity an entity address names, if it is loaded. */
    private static Entity entity(Level level, PortAddress address) {
        UUID id = address.entity();
        if (level instanceof ServerLevel server)
            return server.getEntity(id);
        for (Entity e : level.getEntities((Entity) null, new AABB(address.pos()).inflate(1), e -> id.equals(e.getUUID())))
            return e;
        return null;
    }

    /** Joins two ports the caller has checked with {@link PortCompatibility}; spawns the cord. */
    public static DataCordEntity connect(ServerLevel level, DataPortHost a, int portA, DataPortHost b, int portB) {
        UUID id = UUID.randomUUID();
        DataCordEntity cord = DataCordEntity.create(level, id, a.portAddress(), portA, a, b.portAddress(), portB, b);
        if (!level.addFreshEntity(cord))
            return null;
        a.setLink(portA, new DataLink(b.portAddress(), portB, id));
        b.setLink(portB, new DataLink(a.portAddress(), portA, id));
        return cord;
    }

    /** An attack on the cord: both links cleared, the item dropped where it was hit, the entity gone. */
    public static void breakCord(DataCordEntity cord, Vec3 dropAt) {
        Level level = cord.level();
        clearIfHeld(level, cord.addressA(), cord.portA(), cord.getUUID());
        clearIfHeld(level, cord.addressB(), cord.portB(), cord.getUUID());
        Containers.dropItemStack(level, dropAt.x, dropAt.y, dropAt.z, new ItemStack(ModItems.DATA_CORD.get()));
        cord.discard();
    }

    /** A host is going: each of its cords drops once, the far ends forget it, the entities go. */
    public static void breakAll(DataPortHost host) {
        Level level = host.hostLevel();
        if (level == null || level.isClientSide)
            return;
        for (int port = 0; port < host.dataPortCount(); port++) {
            DataLink link = host.link(port);
            if (link == null)
                continue;
            clearIfHeld(level, link.peer(), link.peerPort(), link.cordId());
            if (level instanceof ServerLevel server) {
                Entity entity = server.getEntity(link.cordId());
                if (entity instanceof DataCordEntity)
                    entity.discard();
            }
            Vec3 at = host.portOrigin(port);
            Containers.dropItemStack(level, at.x, at.y, at.z, new ItemStack(ModItems.DATA_CORD.get()));
            host.setLink(port, null);
        }
    }

    /**
     * Clears every link of {@code host} whose loaded peer does not hold it back
     * (host gone, port mismatched, other cord). Peers not loaded are left alone.
     */
    public static void validateLinks(DataPortHost host) {
        Level level = host.hostLevel();
        if (level == null || level.isClientSide)
            return;
        for (int port = 0; port < host.dataPortCount(); port++) {
            DataLink link = host.link(port);
            if (link == null || !loaded(level, link.peer()))
                continue;
            DataPortHost peer = hostAt(level, link.peer());
            boolean valid = peer != null && link.peerPort() < peer.dataPortCount()
                    && link.pairsWith(host.portAddress(), port, peer.link(link.peerPort()));
            if (!valid)
                host.setLink(port, null);
        }
    }

    /**
     * The host on the other end of {@code host}'s port, if it is loaded, is a
     * {@code type} and holds the link back.
     */
    public static <T extends DataPortHost> T peer(DataPortHost host, int port, Class<T> type) {
        Level level = host.hostLevel();
        DataLink link = host.link(port);
        if (level == null || link == null)
            return null;
        DataPortHost peer = hostAt(level, link.peer());
        if (!type.isInstance(peer) || link.peerPort() >= peer.dataPortCount()
                || !link.pairsWith(host.portAddress(), port, peer.link(link.peerPort())))
            return null;
        return type.cast(peer);
    }

    private static void clearIfHeld(Level level, PortAddress address, int port, UUID cordId) {
        DataPortHost host = hostAt(level, address);
        if (host == null || port >= host.dataPortCount())
            return;
        DataLink link = host.link(port);
        if (link != null && link.cordId().equals(cordId))
            host.setLink(port, null);
    }

    /** Whether the link a cord stands for is still held at {@code address} port {@code port}; null if not loaded. */
    static Boolean holds(Level level, PortAddress address, int port, UUID cordId) {
        if (!loaded(level, address))
            return null;
        DataPortHost host = hostAt(level, address);
        if (host == null || port >= host.dataPortCount())
            return false;
        DataLink link = host.link(port);
        return link != null && link.cordId().equals(cordId);
    }
}
