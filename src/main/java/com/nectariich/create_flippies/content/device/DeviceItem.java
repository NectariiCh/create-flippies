package com.nectariich.create_flippies.content.device;

import java.util.function.BiFunction;

import net.minecraft.core.Direction;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * The item a {@link DeviceEntity} is placed from. Used on the top of something, it
 * stands the device there: its position on the pixel grid nearest the point clicked,
 * its front turned towards the player to the nearest 11.25 degrees. It goes only
 * where it touches nothing solid, block or device, and nobody stands.
 */
public class DeviceItem extends Item {

    private final BiFunction<EntityType<?>, Level, ? extends DeviceEntity> factory;
    private final java.util.function.Supplier<? extends EntityType<?>> type;

    public DeviceItem(java.util.function.Supplier<? extends EntityType<?>> type,
            BiFunction<EntityType<?>, Level, ? extends DeviceEntity> factory, Properties properties) {
        super(properties);
        this.type = type;
        this.factory = factory;
    }

    /** One pixel. */
    public static final double GRID = 1 / 16.0;

    /** Where a device placed by a click at {@code at} on a top face would stand. */
    public static Vec3 snap(Vec3 at) {
        return new Vec3(Math.round(at.x / GRID) * GRID, Math.round(at.y / GRID) * GRID, Math.round(at.z / GRID) * GRID);
    }

    /** The turn a device placed by {@code player} takes: its front towards them. */
    public static int stepFor(Player player) {
        return DeviceShape.stepFor(player.getYRot() + 180);
    }

    public EntityType<?> entityType() {
        return type.get();
    }

    /** A device of this item's kind, not yet in the level, as it would be placed. */
    public DeviceEntity preview(Level level, Vec3 at, int step) {
        DeviceEntity device = factory.apply(type.get(), level);
        device.place(at, step);
        return device;
    }

    /** Whether {@code device}, not yet in the level, may go where it is. */
    public static boolean fits(Level level, DeviceEntity device) {
        VoxelShape shape = device.collisionShape();
        for (AABB box : shape.toAabbs())
            if (!level.noCollision(null, box.deflate(1e-6)))
                return false;
        return level.isUnobstructed(null, shape);
    }

    @Override
    public InteractionResult useOn(UseOnContext context) {
        Level level = context.getLevel();
        Player player = context.getPlayer();
        if (player == null || context.getClickedFace() != Direction.UP)
            return InteractionResult.PASS;
        DeviceEntity device = preview(level, snap(context.getClickLocation()), stepFor(player));
        if (!fits(level, device))
            return InteractionResult.FAIL;
        if (level.isClientSide)
            return InteractionResult.SUCCESS;
        if (!level.addFreshEntity(device))
            return InteractionResult.FAIL;
        SoundType sound = device.sound();
        Vec3 at = device.position();
        level.playSound(null, at.x, at.y, at.z, sound.getPlaceSound(), SoundSource.BLOCKS, (sound.getVolume() + 1) / 2,
                sound.getPitch() * 0.8f);
        ItemStack stack = context.getItemInHand();
        if (!player.getAbilities().instabuild)
            stack.shrink(1);
        return InteractionResult.CONSUME;
    }
}
