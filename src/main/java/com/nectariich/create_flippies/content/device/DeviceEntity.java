package com.nectariich.create_flippies.content.device;

import net.minecraft.core.particles.ItemParticleOption;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.effect.MobEffectUtil;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.enchantment.EnchantmentHelper;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.material.PushReaction;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.VoxelShape;
import net.minecraftforge.entity.IEntityAdditionalSpawnData;
import net.minecraftforge.network.NetworkHooks;

/**
 * A device that stands in the world like a block but is an entity, so that it can
 * sit anywhere on the pixel grid, across block borders and in cells other non-full
 * blocks take, turned in steps of 11.25 degrees ({@link DeviceShape}).
 *
 * It behaves as a block would. It is solid: things stand on it and bump into it
 * ({@code LevelDeviceCollisionMixin} hands its {@link #collisionShape} to the
 * world's entity collisions). It is not one of the game's targets and draws no
 * outline; this mod traces the crosshair against its turned boxes itself
 * ({@code GameRendererDevicePickMixin}) and makes it the target, so a click on it
 * goes to {@link #interactAt}. Breaking it takes holding the attack button as long
 * as a block of its {@link #hardness} by hand ({@link #breakTicks}); an attack does
 * nothing else to it.
 *
 * It never moves and has no physics: placed once, sent once.
 */
public abstract class DeviceEntity extends Entity implements IEntityAdditionalSpawnData {

    private int step;
    /** The collision shape at {@link #shapeAt}, in world space. */
    private VoxelShape worldShape;
    private Vec3 shapeAt;

    protected DeviceEntity(EntityType<?> type, Level level) {
        super(type, level);
        noPhysics = true;
        blocksBuilding = true;
    }

    public abstract DeviceShape shape();

    /** The item it is placed from and drops as. */
    public abstract ItemStack asItem();

    /** A block's destroy time this one takes as long to break as. */
    public abstract float hardness();

    public abstract SoundType sound();

    public int step() {
        return step;
    }

    /** Puts it at {@code position} turned {@code step}; before it is added to the level. */
    public void place(Vec3 position, int step) {
        this.step = Math.floorMod(step, DeviceShape.STEPS);
        setYRot(DeviceShape.yaw(this.step));
        setPos(position.x, position.y, position.z);
    }

    // Geometry

    /** A model point, px, in world space. */
    public Vec3 toWorld(Vec3 modelPx) {
        return position().add(shape().toLocal(modelPx, step));
    }

    /** A world point in model space, px. */
    public Vec3 toModel(Vec3 world) {
        return shape().toModel(world.subtract(position()), step);
    }

    /** A direction in the model, in world space. */
    public Vec3 directionToWorld(Vec3 modelDirection) {
        return shape().directionToLocal(modelDirection, step);
    }

    /** The turn applied to the model, degrees about +y, for drawing it. */
    public float turnDegrees() {
        return (float) shape().turnDegrees(step);
    }

    /** What it collides with, in world space. */
    public VoxelShape collisionShape() {
        Vec3 at = position();
        if (worldShape == null || !at.equals(shapeAt)) {
            worldShape = shape().localShape(step).move(at.x, at.y, at.z);
            shapeAt = at;
        }
        return worldShape;
    }

    /** Where the segment from {@code from} to {@code to} first meets it, or null. */
    public DeviceShape.Hit clip(Vec3 from, Vec3 to) {
        Vec3 at = position();
        return shape().clip(from.subtract(at), to.subtract(at), step);
    }

    @Override
    protected AABB makeBoundingBox() {
        return shape().localBounds(step).move(position());
    }

    // Not a mob, not a target, not moved

    @Override
    protected void defineSynchedData() {}

    @Override
    public void tick() {
        // No physics and no fire, water or portals: it only does what a device does
        deviceTick();
    }

    protected void deviceTick() {}

    @Override
    public boolean canBeCollidedWith() {
        return true;
    }

    @Override
    public boolean isPickable() {
        return false;
    }

    @Override
    public boolean isPushable() {
        return false;
    }

    @Override
    public boolean isAttackable() {
        return false;
    }

    @Override
    public boolean hurt(DamageSource source, float amount) {
        return false;
    }

    @Override
    public boolean ignoreExplosion() {
        return true;
    }

    @Override
    public boolean canChangeDimensions() {
        return false;
    }

    @Override
    public PushReaction getPistonPushReaction() {
        return PushReaction.IGNORE;
    }

    @Override
    public boolean isIgnoringBlockTriggers() {
        return true;
    }

    @Override
    public ItemStack getPickResult() {
        return asItem();
    }

    @Override
    public boolean shouldRenderAtSqrDistance(double distance) {
        double range = 96;
        return distance < range * range;
    }

    // Breaking

    /**
     * Ticks of holding the attack button that break it for {@code player}: a block
     * of {@link #hardness} broken by hand, with haste, mining fatigue, water and
     * being off the ground counted as vanilla counts them.
     */
    public int breakTicks(Player player) {
        float speed = 1;
        if (MobEffectUtil.hasDigSpeed(player))
            speed *= 1 + (MobEffectUtil.getDigSpeedAmplification(player) + 1) * 0.2f;
        if (player.hasEffect(MobEffects.DIG_SLOWDOWN)) {
            int amplifier = player.getEffect(MobEffects.DIG_SLOWDOWN).getAmplifier();
            speed *= switch (amplifier) {
                case 0 -> 0.3f;
                case 1 -> 0.09f;
                case 2 -> 0.0027f;
                default -> 8.1e-4f;
            };
        }
        if (player.isEyeInFluidType(net.minecraftforge.common.ForgeMod.WATER_TYPE.get())
                && !EnchantmentHelper.hasAquaAffinity(player))
            speed /= 5;
        if (!player.onGround())
            speed /= 5;
        float perTick = speed / hardness() / 30;
        return perTick <= 0 ? Integer.MAX_VALUE : (int) Math.ceil(1 / perTick);
    }

    /** Broken by {@code player} (null for nobody): its own clean-up, the item unless in creative, then gone. */
    public void breakBy(Player player) {
        if (level().isClientSide || isRemoved())
            return;
        onBroken();
        Vec3 centre = getBoundingBox().getCenter();
        if (player == null || !player.getAbilities().instabuild)
            spawnAtLocation(asItem(), (float) (centre.y - getY()));
        SoundType sound = sound();
        level().playSound(null, centre.x, centre.y, centre.z, sound.getBreakSound(), SoundSource.BLOCKS,
                (sound.getVolume() + 1) / 2, sound.getPitch() * 0.8f);
        if (level() instanceof ServerLevel server)
            server.sendParticles(new ItemParticleOption(ParticleTypes.ITEM, asItem()), centre.x, centre.y, centre.z,
                    16, getBbWidth() / 4, getBbHeight() / 4, getBbWidth() / 4, 0.05);
        discard();
    }

    /** About to be broken: let go of whatever it is joined to. Server only. */
    protected void onBroken() {}

    // Lifecycle

    @Override
    public void onAddedToWorld() {
        super.onAddedToWorld();
        added();
    }

    @Override
    public void onRemovedFromWorld() {
        super.onRemovedFromWorld();
        RemovalReason reason = getRemovalReason();
        // A client never destroys one: it only loses sight of it, whatever reason it was given
        removed(!level().isClientSide && reason != null && reason.shouldDestroy());
    }

    /** In its level, loaded or new. */
    protected void added() {}

    /** Out of its level: destroyed, or unloaded with its chunk. */
    protected void removed(boolean destroyed) {}

    // Saving and spawning

    @Override
    protected void readAdditionalSaveData(CompoundTag tag) {
        step = Math.floorMod(tag.getInt("Step"), DeviceShape.STEPS);
        setYRot(DeviceShape.yaw(step));
        setBoundingBox(makeBoundingBox());
    }

    @Override
    protected void addAdditionalSaveData(CompoundTag tag) {
        tag.putInt("Step", step);
    }

    @Override
    public void writeSpawnData(FriendlyByteBuf buffer) {
        buffer.writeByte(step);
    }

    @Override
    public void readSpawnData(FriendlyByteBuf buffer) {
        step = Math.floorMod(buffer.readByte(), DeviceShape.STEPS);
        setYRot(DeviceShape.yaw(step));
        setBoundingBox(makeBoundingBox());
    }

    @Override
    public Packet<ClientGamePacketListener> getAddEntityPacket() {
        return NetworkHooks.getEntitySpawningPacket(this);
    }

    /** The device at this entity id in {@code level}, if it is one. */
    public static DeviceEntity byId(Level level, int id) {
        Entity entity = level.getEntity(id);
        return entity instanceof DeviceEntity device ? device : null;
    }
}
