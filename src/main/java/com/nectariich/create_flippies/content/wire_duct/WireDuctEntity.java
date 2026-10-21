package com.nectariich.create_flippies.content.wire_duct;

import java.util.UUID;

import com.nectariich.create_flippies.foundation.wire.duct.DuctColor;
import com.nectariich.create_flippies.foundation.wire.duct.DuctSegment;
import com.nectariich.create_flippies.foundation.wire.duct.DuctSize;
import com.nectariich.create_flippies.registry.ModEntityTypes;
import com.nectariich.create_flippies.registry.ModItems;

import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.material.PushReaction;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.entity.IEntityAdditionalSpawnData;
import net.minecraftforge.network.NetworkHooks;

/**
 * One straight stretch of wire duct lying flat on a face ({@link DuctSegment}). It
 * carries nothing electrical and belongs to no terminal; placeable wires run
 * through it keep their own way and only lie along it.
 *
 * An entity rather than a block, so that it can sit on any face at any pixel
 * without taking up the space in front of it. Placement cuts a duct at every
 * section border, so each stretch lives wholly in one chunk and is at most 16
 * blocks long; the stretches laid by one placement share a run id, which is what
 * a dye recolours together. Its colour is plain RGB, white unless dyed.
 *
 * The game never targets it (it is not pickable): it would otherwise steal clicks
 * from the block it lies on. This mod picks it itself, client side, for running
 * wires through it, dyeing it and breaking it ({@code WireDuctInput}).
 */
public class WireDuctEntity extends Entity implements IEntityAdditionalSpawnData {

    /** How far the box reaches past each end; see {@code DuctNetwork.jointExtensions}. */
    private static final EntityDataAccessor<Float> EXTEND_FROM =
            SynchedEntityData.defineId(WireDuctEntity.class, EntityDataSerializers.FLOAT);
    private static final EntityDataAccessor<Float> EXTEND_TO =
            SynchedEntityData.defineId(WireDuctEntity.class, EntityDataSerializers.FLOAT);
    private static final EntityDataAccessor<Integer> COLOR =
            SynchedEntityData.defineId(WireDuctEntity.class, EntityDataSerializers.INT);

    private Vec3 from;
    private Vec3 to;
    private Direction face = Direction.UP;
    private DuctSize size = DuctSize.SMALL;
    /** The duct items this stretch cost, and gives back. */
    private int items = 1;
    /** Shared by the stretches laid together by one placement. */
    private UUID run = UUID.randomUUID();
    private DuctSegment segment;

    public WireDuctEntity(EntityType<?> type, Level level) {
        super(type, level);
        noPhysics = true;
    }

    public static WireDuctEntity create(Level level, DuctSegment segment, int items, UUID run, int color) {
        WireDuctEntity duct = new WireDuctEntity(ModEntityTypes.WIRE_DUCT.get(), level);
        duct.from = segment.from();
        duct.to = segment.to();
        duct.face = segment.face();
        duct.size = segment.size();
        duct.items = items;
        duct.run = run;
        duct.entityData.set(COLOR, color & 0xFFFFFF);
        duct.placed();
        return duct;
    }

    /** Null only before the entity has been read or created. */
    public DuctSegment segment() {
        return segment;
    }

    public DuctSize size() {
        return size;
    }

    public int items() {
        return items;
    }

    public UUID run() {
        return run;
    }

    /** Plain RGB. */
    public int color() {
        return entityData.get(COLOR);
    }

    public void setColor(int color) {
        entityData.set(COLOR, color & 0xFFFFFF);
    }

    /** The duct items this stretch is made of, in its colour. */
    public ItemStack asItems(int count) {
        return WireDuctItem.withColor(new ItemStack(ModItems.wireDuct(size), count), color());
    }

    public float extendFrom() {
        return entityData.get(EXTEND_FROM);
    }

    public float extendTo() {
        return entityData.get(EXTEND_TO);
    }

    /** Sets how far the box reaches past the end at {@code end}, if that is one of this stretch's ends. */
    public void setExtension(Vec3 end, double extension) {
        if (segment == null)
            return;
        if (end.distanceToSqr(from) < 1e-8)
            entityData.set(EXTEND_FROM, (float) extension);
        else if (end.distanceToSqr(to) < 1e-8)
            entityData.set(EXTEND_TO, (float) extension);
    }

    /** The box drawn and hit: the channel with its ends extended or cut back. */
    public AABB ductBox() {
        return segment == null ? getBoundingBox() : segment.box(extendFrom(), extendTo());
    }

    private void placed() {
        segment = new DuctSegment(from, to, face, size);
        Vec3 mid = from.add(to).scale(0.5);
        setPos(mid.x, mid.y, mid.z);
    }

    @Override
    protected AABB makeBoundingBox() {
        if (segment == null)
            return super.makeBoundingBox();
        double reach = size.width() / 2;
        return segment.box(reach, reach);
    }

    @Override
    protected void defineSynchedData() {
        entityData.define(EXTEND_FROM, 0f);
        entityData.define(EXTEND_TO, 0f);
        entityData.define(COLOR, DuctColor.WHITE);
    }

    /** Whatever changes about a duct after it is placed changes how the wires in it are drawn. */
    @Override
    public void onSyncedDataUpdated(EntityDataAccessor<?> accessor) {
        super.onSyncedDataUpdated(accessor);
        if (level() != null && (accessor == EXTEND_FROM || accessor == EXTEND_TO || accessor == COLOR))
            DuctIndex.touch(level());
    }

    @Override
    public void tick() {
        // Nothing moves a duct, and nothing about it changes by itself
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
    public PushReaction getPistonPushReaction() {
        return PushReaction.IGNORE;
    }

    @Override
    public boolean isIgnoringBlockTriggers() {
        return true;
    }

    @Override
    public ItemStack getPickResult() {
        return asItems(1);
    }

    @Override
    public boolean shouldRenderAtSqrDistance(double distance) {
        double range = 96;
        return distance < range * range;
    }

    // Saving and spawning

    @Override
    protected void readAdditionalSaveData(CompoundTag tag) {
        from = readVec(tag, "From");
        to = readVec(tag, "To");
        int f = tag.getInt("Face");
        face = f >= 0 && f < 6 ? Direction.from3DDataValue(f) : Direction.UP;
        size = DuctSize.byOrdinal(tag.getInt("Size"));
        items = Math.max(1, tag.getInt("Items"));
        entityData.set(EXTEND_FROM, tag.getFloat("ExtendFrom"));
        entityData.set(EXTEND_TO, tag.getFloat("ExtendTo"));
        entityData.set(COLOR, DuctColor.read(tag));
        // A duct saved before runs existed is a run of its own
        if (tag.hasUUID("Run"))
            run = tag.getUUID("Run");
        placed();
    }

    @Override
    protected void addAdditionalSaveData(CompoundTag tag) {
        if (segment == null)
            return;
        writeVec(tag, "From", from);
        writeVec(tag, "To", to);
        tag.putInt("Face", face.get3DDataValue());
        tag.putInt("Size", size.ordinal());
        tag.putInt("Items", items);
        tag.putFloat("ExtendFrom", extendFrom());
        tag.putFloat("ExtendTo", extendTo());
        DuctColor.write(tag, color());
        tag.putUUID("Run", run);
    }

    private static Vec3 readVec(CompoundTag tag, String key) {
        CompoundTag v = tag.getCompound(key);
        return new Vec3(v.getDouble("X"), v.getDouble("Y"), v.getDouble("Z"));
    }

    private static void writeVec(CompoundTag tag, String key, Vec3 vec) {
        CompoundTag v = new CompoundTag();
        v.putDouble("X", vec.x);
        v.putDouble("Y", vec.y);
        v.putDouble("Z", vec.z);
        tag.put(key, v);
    }

    @Override
    public void writeSpawnData(FriendlyByteBuf buffer) {
        for (Vec3 v : new Vec3[] {from, to}) {
            buffer.writeDouble(v.x);
            buffer.writeDouble(v.y);
            buffer.writeDouble(v.z);
        }
        buffer.writeByte(face.get3DDataValue());
        buffer.writeByte(size.ordinal());
    }

    @Override
    public void readSpawnData(FriendlyByteBuf buffer) {
        from = new Vec3(buffer.readDouble(), buffer.readDouble(), buffer.readDouble());
        to = new Vec3(buffer.readDouble(), buffer.readDouble(), buffer.readDouble());
        int f = buffer.readByte();
        face = f >= 0 && f < 6 ? Direction.from3DDataValue(f) : Direction.UP;
        size = DuctSize.byOrdinal(buffer.readByte());
        placed();
    }

    @Override
    public Packet<ClientGamePacketListener> getAddEntityPacket() {
        return NetworkHooks.getEntitySpawningPacket(this);
    }
}
