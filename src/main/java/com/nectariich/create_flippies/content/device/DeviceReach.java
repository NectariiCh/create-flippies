package com.nectariich.create_flippies.content.device;

import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * How far any device has reached from its position, so the game can be made to find it.
 *
 * Vanilla looks for entities touching a box only in the sections whose positions could
 * be near it: two blocks out sideways, four below and none above
 * ({@code EntitySectionStorage.forEachAccessibleNonEmptySection}). That suits mobs,
 * whose feet are their position. A five-block cabinet with its position in the middle
 * of its back reaches well past that, and where a section boundary falls between the
 * two its walls could be walked through, its door not clicked and its top not stood
 * on. So the search is widened by the furthest any device has reached, once one has
 * reached further than vanilla allows for ({@code EntitySectionStorageReachMixin}).
 *
 * It only grows, for the life of the game: a smaller cabinet later does not shrink it,
 * which costs a few more sections scanned and never a missed entity.
 */
public final class DeviceReach {

    /** Reaches this small are left to vanilla's own margin. */
    private static final double VANILLA = 1.5;

    private static volatile double margin;

    private DeviceReach() {}

    /** Notes a device at {@code position} filling {@code bounds}. */
    public static void note(Vec3 position, AABB bounds) {
        double reach = Math.max(Math.max(Math.max(position.x - bounds.minX, bounds.maxX - position.x),
                Math.max(position.y - bounds.minY, bounds.maxY - position.y)),
                Math.max(position.z - bounds.minZ, bounds.maxZ - position.z));
        if (reach > VANILLA && reach > margin && Double.isFinite(reach))
            margin = Math.ceil(reach);
    }

    /** How much further than vanilla to look round a box, blocks: none until a device has needed it. */
    public static double margin() {
        return margin;
    }
}
