package com.nectariich.create_flippies.content.data_cord;

import java.util.UUID;

import com.nectariich.create_flippies.registry.ModItems;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Containers;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.phys.Vec3;

/**
 * Making and breaking data cords (spec 11.2.5). A cord is three things kept in step:
 * the entity that draws it and can be hit, and a {@link DataLink} in each endpoint
 * block entity. The cord entity's UUID is the links' {@code cordId}, so either end
 * can find the entity.
 *
 * The item comes back exactly once, from whoever breaks the cord: an attack on the
 * entity, or the block at either end being broken. A link found stale when a chunk
 * loads (its peer is gone or does not hold it back) is only cleared, because the
 * peer's breaking already dropped the item.
 */
public final class DataCords {

    private DataCords() {}

    /** The port host at {@code pos}, if its chunk is loaded and it is one. */
    public static DataPortHost hostAt(Level level, BlockPos pos) {
        if (!level.isLoaded(pos))
            return null;
        return level.getBlockEntity(pos) instanceof DataPortHost host ? host : null;
    }

    private static BlockPos posOf(DataPortHost host) {
        return ((BlockEntity) host).getBlockPos();
    }

    /** Joins two ports the caller has checked with {@link PortCompatibility}; spawns the cord. */
    public static DataCordEntity connect(ServerLevel level, DataPortHost a, int portA, DataPortHost b, int portB) {
        UUID id = UUID.randomUUID();
        DataCordEntity cord = DataCordEntity.create(level, id, posOf(a), portA, a, posOf(b), portB, b);
        if (!level.addFreshEntity(cord))
            return null;
        a.setLink(portA, new DataLink(posOf(b), portB, id));
        b.setLink(portB, new DataLink(posOf(a), portA, id));
        return cord;
    }

    /** An attack on the cord: both links cleared, the item dropped where it was hit, the entity gone. */
    public static void breakCord(DataCordEntity cord, Vec3 dropAt) {
        Level level = cord.level();
        clearIfHeld(level, cord.posA(), cord.portA(), cord.getUUID());
        clearIfHeld(level, cord.posB(), cord.portB(), cord.getUUID());
        Containers.dropItemStack(level, dropAt.x, dropAt.y, dropAt.z, new ItemStack(ModItems.DATA_CORD.get()));
        cord.discard();
    }

    /** A host is going: each of its cords drops once, the far ends forget it, the entities go. */
    public static void breakAll(DataPortHost host) {
        BlockEntity be = (BlockEntity) host;
        Level level = be.getLevel();
        if (level == null || level.isClientSide)
            return;
        BlockPos pos = be.getBlockPos();
        for (int port = 0; port < host.dataPortCount(); port++) {
            DataLink link = host.link(port);
            if (link == null)
                continue;
            clearIfHeld(level, link.peerPos(), link.peerPort(), link.cordId());
            if (level instanceof ServerLevel server) {
                Entity entity = server.getEntity(link.cordId());
                if (entity instanceof DataCordEntity)
                    entity.discard();
            }
            Containers.dropItemStack(level, pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5,
                    new ItemStack(ModItems.DATA_CORD.get()));
            host.setLink(port, null);
        }
    }

    /**
     * Clears every link of {@code host} whose loaded peer does not hold it back
     * (block gone, port mismatched, other cord). Peers not loaded are left alone.
     */
    public static void validateLinks(DataPortHost host) {
        BlockEntity be = (BlockEntity) host;
        Level level = be.getLevel();
        if (level == null || level.isClientSide)
            return;
        for (int port = 0; port < host.dataPortCount(); port++) {
            DataLink link = host.link(port);
            if (link == null || !level.isLoaded(link.peerPos()))
                continue;
            DataPortHost peer = hostAt(level, link.peerPos());
            boolean valid = peer != null && link.peerPort() < peer.dataPortCount()
                    && link.pairsWith(be.getBlockPos(), port, peer.link(link.peerPort()));
            if (!valid)
                host.setLink(port, null);
        }
    }

    private static void clearIfHeld(Level level, BlockPos pos, int port, UUID cordId) {
        DataPortHost host = hostAt(level, pos);
        if (host == null || port >= host.dataPortCount())
            return;
        DataLink link = host.link(port);
        if (link != null && link.cordId().equals(cordId))
            host.setLink(port, null);
    }

    /** Whether the link a cord stands for is still held at {@code pos} port {@code port}; null if not loaded. */
    static Boolean holds(Level level, BlockPos pos, int port, UUID cordId) {
        if (!level.isLoaded(pos))
            return null;
        DataPortHost host = hostAt(level, pos);
        if (host == null || port >= host.dataPortCount())
            return false;
        DataLink link = host.link(port);
        return link != null && link.cordId().equals(cordId);
    }
}
