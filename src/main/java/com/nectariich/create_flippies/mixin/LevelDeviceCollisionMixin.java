package com.nectariich.create_flippies.mixin;

import java.util.List;
import java.util.function.Predicate;

import javax.annotation.Nullable;

import com.google.common.collect.ImmutableList;
import com.nectariich.create_flippies.content.device.DeviceEntity;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySelector;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.shapes.BooleanOp;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

import org.spongepowered.asm.mixin.Mixin;

/**
 * Makes devices ({@link DeviceEntity}) as solid as the blocks they stand in for.
 *
 * Vanilla turns every entity that can be collided with into one box, its bounding
 * box, both for movement ({@code getEntityCollisions}, behind every entity's
 * {@code collide}, sneaking at edges and spawning) and for whether a block may be
 * placed ({@code isUnobstructed}). A turned device's bounding box is much larger
 * than the device, so for devices these hand over their real shape instead; every
 * other entity is treated exactly as vanilla treats it.
 *
 * Both are defaults of {@code CommonLevelAccessor} that {@link Level} does not
 * override and that {@code ServerLevel} and {@code ClientLevel} inherit, so they are
 * overridden here, once, for every level.
 */
@Mixin(Level.class)
public abstract class LevelDeviceCollisionMixin implements LevelAccessor {

    @Override
    public List<VoxelShape> getEntityCollisions(@Nullable Entity entity, AABB box) {
        if (box.getSize() < 1.0E-7)
            return List.of();
        Predicate<Entity> filter = entity == null ? EntitySelector.CAN_BE_COLLIDED_WITH
                : EntitySelector.NO_SPECTATORS.and(entity::canCollideWith);
        List<Entity> found = getEntities(entity, box.inflate(1.0E-7), filter);
        if (found.isEmpty())
            return List.of();
        ImmutableList.Builder<VoxelShape> shapes = ImmutableList.builderWithExpectedSize(found.size());
        for (Entity other : found)
            shapes.add(other instanceof DeviceEntity device ? device.collisionShape()
                    : Shapes.create(other.getBoundingBox()));
        return shapes.build();
    }

    @Override
    public boolean isUnobstructed(@Nullable Entity except, VoxelShape shape) {
        if (shape.isEmpty())
            return true;
        for (Entity other : getEntities(except, shape.bounds())) {
            if (other.isRemoved() || !other.blocksBuilding
                    || except != null && other.isPassengerOfSameVehicle(except))
                continue;
            VoxelShape occupied = other instanceof DeviceEntity device ? device.collisionShape()
                    : Shapes.create(other.getBoundingBox());
            if (Shapes.joinIsNotEmpty(shape, occupied, BooleanOp.AND))
                return false;
        }
        return true;
    }
}
