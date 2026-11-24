package com.nectariich.create_flippies.content.device;

import java.util.function.BiFunction;

import net.minecraft.core.BlockPos;
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
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * The item a {@link DeviceEntity} is placed from. Used on the top of something, it
 * stands the device there: its position on the pixel grid nearest the point clicked,
 * its front turned towards the player to the nearest 11.25 degrees. It goes only
 * where it touches nothing solid, block or device, and nobody stands.
 *
 * A device placed some other way (hung on a wall, say) overrides {@link #placementFor},
 * {@link #configure} and {@link #canPlace}; the placement preview asks the same three,
 * so the ghost is always what a click would place.
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

    /** {@code device} was just placed from {@code stack} by {@code player}, before the stack shrinks. Server only. */
    protected void placed(DeviceEntity device, ItemStack stack, Player player) {}

    /** Where a device goes and how it is turned. */
    public record Placement(Vec3 position, int step) {}

    /**
     * Where a click on {@code hit} with {@code stack} would place the device, or null
     * if it cannot be placed from there: by default, only on the top of something.
     */
    public Placement placementFor(Level level, Player player, BlockHitResult hit, ItemStack stack) {
        if (hit.getDirection() != Direction.UP)
            return null;
        return new Placement(snap(hit.getLocation()), stepFor(player));
    }

    /** Gives {@code device}, not yet in the level, whatever {@code stack} carries that changes it. */
    public void configure(DeviceEntity device, ItemStack stack) {}

    /** Whether {@code device}, configured and not yet in the level, may go where it is. */
    public boolean canPlace(Level level, DeviceEntity device) {
        return fits(level, device);
    }

    @Override
    public InteractionResult useOn(UseOnContext context) {
        Player player = context.getPlayer();
        if (player == null)
            return InteractionResult.PASS;
        BlockHitResult hit = new BlockHitResult(context.getClickLocation(), context.getClickedFace(),
                context.getClickedPos(), context.isInside());
        return placeAt(context.getLevel(), player, hit, context.getItemInHand());
    }

    /** Places the device as a click on {@code hit} with {@code stack} would. */
    public InteractionResult placeAt(Level level, Player player, BlockHitResult hit, ItemStack stack) {
        Placement placement = placementFor(level, player, hit, stack);
        if (placement == null)
            return InteractionResult.PASS;
        DeviceEntity device = preview(level, placement.position(), placement.step());
        configure(device, stack);
        if (!canPlace(level, device))
            return InteractionResult.FAIL;
        if (level.isClientSide)
            return InteractionResult.SUCCESS;
        if (!level.addFreshEntity(device))
            return InteractionResult.FAIL;
        placed(device, stack, player);
        SoundType sound = device.sound();
        Vec3 at = device.position();
        level.playSound(null, at.x, at.y, at.z, sound.getPlaceSound(), SoundSource.BLOCKS, (sound.getVolume() + 1) / 2,
                sound.getPitch() * 0.8f);
        if (!player.getAbilities().instabuild)
            stack.shrink(1);
        return InteractionResult.CONSUME;
    }

    /**
     * A click at {@code at}, in the world, on the top of {@code device}, as a click on
     * the top of a block there; null if {@code at} is not on a top face of it, or it
     * carries nothing ({@link DeviceEntity#carriesOnTop}).
     */
    public static BlockHitResult topHit(DeviceEntity device, Vec3 at) {
        if (!device.carriesOnTop())
            return null;
        double e = 1e-4;
        for (AABB box : device.collisionShape().toAabbs())
            if (Math.abs(box.maxY - at.y) < e && at.x > box.minX - e && at.x < box.maxX + e && at.z > box.minZ - e
                    && at.z < box.maxZ + e)
                return new BlockHitResult(at, Direction.UP, BlockPos.containing(at.x, at.y - e, at.z), false);
        return null;
    }
}
