package com.nectariich.create_flippies.content.data_cord;

import java.util.List;
import java.util.UUID;

import com.nectariich.create_flippies.foundation.wire.bend.BendGeometry;
import com.nectariich.create_flippies.foundation.wire.bend.BendableWire;
import com.nectariich.create_flippies.foundation.wire.bend.WireBends;

import com.nectariich.create_flippies.registry.ModEntityTypes;
import com.nectariich.create_flippies.registry.ModItems;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.material.PushReaction;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.entity.IEntityAdditionalSpawnData;
import net.minecraftforge.network.NetworkHooks;

import org.patryk3211.powergrid.electricity.wire.CurveParameters;
import org.patryk3211.powergrid.utility.IComplexRaycast;

/**
 * A data cord hanging between two ports (spec 11.2.5): only for drawing and for
 * breaking. What it connects is held by the two endpoint block entities as
 * {@link DataLink}s, so the per-tick exchange never needs this entity's chunk.
 *
 * Its UUID is the links' {@code cordId}. The ends are fixed when it is made, so
 * they travel once in the spawn data, as the plug origins and the outward normals
 * of the two ports. The hanging curve is Power Grid's own ({@link CurveParameters},
 * with the copper cord's coefficients), so it sags like their cords.
 *
 * It is hit precisely through Power Grid's {@link IComplexRaycast} hook, and is
 * otherwise not pickable, so its bounding box never blocks a click on a block
 * behind it. Attacking it breaks it.
 *
 * It can be bent like a Power Grid hanging wire ({@link BendableWire}): each leg
 * between bend points then hangs, is hit and is boxed on its own, and it is still
 * the one cord with the one item.
 */
public class DataCordEntity extends Entity implements IEntityAdditionalSpawnData, IComplexRaycast, BendableWire {

    /**
     * Power Grid's copper cord's curve coefficients (wire_types/copper_cord.json),
     * at the thickness of a 16 AWG wire (wire_types/insulated_copper_wire_16awg.json).
     */
    public static final double CURVE_COEFFICIENT = 1.005, THICKNESS = 0.03117158;
    /** How far a plug stands out of its port, blocks (gen_pc_case.py's 0.5 px). */
    public static final double PLUG_DEPTH = 0.5 / 16;
    /** Ticks between checks that both ends still hold this cord. */
    private static final int CHECK_INTERVAL = 40;

    private BlockPos posA = BlockPos.ZERO, posB = BlockPos.ZERO;
    private int portA, portB;
    private PortType type = PortType.COM;
    private Vec3 originA = Vec3.ZERO, originB = Vec3.ZERO;
    private Vec3 normalA = new Vec3(1, 0, 0), normalB = new Vec3(1, 0, 0);
    private CurveParameters curve;
    /** Null only while Minecraft's constructor runs, before field initialisers; see {@link #bendPoints}. */
    private List<Vec3> bends = List.of();

    public DataCordEntity(EntityType<?> type, Level level) {
        super(type, level);
        noPhysics = true;
    }

    static DataCordEntity create(ServerLevel level, UUID id, BlockPos posA, int portA, DataPortHost a, BlockPos posB,
            int portB, DataPortHost b) {
        DataCordEntity cord = new DataCordEntity(ModEntityTypes.DATA_CORD.get(), level);
        cord.setUUID(id);
        cord.posA = posA;
        cord.portA = portA;
        cord.posB = posB;
        cord.portB = portB;
        cord.type = a.portType(portA);
        cord.originA = a.portOrigin(portA);
        cord.originB = b.portOrigin(portB);
        cord.normalA = a.portNormal(portA);
        cord.normalB = b.portNormal(portB);
        cord.placed();
        return cord;
    }

    public BlockPos posA() {
        return posA;
    }

    public BlockPos posB() {
        return posB;
    }

    public int portA() {
        return portA;
    }

    public int portB() {
        return portB;
    }

    public PortType portType() {
        return type;
    }

    public Vec3 originA() {
        return originA;
    }

    public Vec3 originB() {
        return originB;
    }

    public Vec3 normalA() {
        return normalA;
    }

    public Vec3 normalB() {
        return normalB;
    }

    /** Where the cord leaves plug A: its outer end. */
    public Vec3 endA() {
        return originA.add(normalA.scale(PLUG_DEPTH));
    }

    public Vec3 endB() {
        return originB.add(normalB.scale(PLUG_DEPTH));
    }

    /** The curve in coordinates relative to the entity's position, as Power Grid's renderer takes it. */
    public CurveParameters curve() {
        if (curve == null)
            curve = new CurveParameters(endA(), endB(), CURVE_COEFFICIENT, CURVE_COEFFICIENT, THICKNESS);
        return curve;
    }

    /** Positions the entity between the ends, which also rebuilds its box. */
    private void placed() {
        curve = null;
        Vec3 a = endA(), b = endB();
        setPos((a.x + b.x) / 2, (a.y + b.y) / 2, (a.z + b.z) / 2);
    }

    @Override
    protected AABB makeBoundingBox() {
        if (originA == null || originB == null)
            return super.makeBoundingBox();
        if (!bendPoints().isEmpty())
            return BendGeometry.bounds(bendPath(), CURVE_COEFFICIENT, CURVE_COEFFICIENT, THICKNESS);
        Vec3 a = endA(), b = endB();
        AABB box = new AABB(a, b);
        // The sag: the curve's lowest point below the lower end
        CurveParameters c = curve();
        double[] lowest = {Math.min(a.y, b.y)};
        double baseY = a.y;
        c.runForPoints(16, (x, y, z) -> lowest[0] = Math.min(lowest[0], baseY + y));
        return box.setMinY(Math.min(box.minY, lowest[0])).inflate(0.1);
    }

    @Override
    protected void defineSynchedData() {}

    @Override
    public void tick() {
        super.tick();
        if (level().isClientSide || tickCount % CHECK_INTERVAL != 0)
            return;
        Boolean heldA = DataCords.holds(level(), posA, portA, getUUID());
        Boolean heldB = DataCords.holds(level(), posB, portB, getUUID());
        // Either end let go while this was unloaded: the item was dropped by whoever did
        if (Boolean.FALSE.equals(heldA) || Boolean.FALSE.equals(heldB))
            discard();
    }

    @Override
    public boolean skipAttackInteraction(Entity attacker) {
        if (!level().isClientSide && isAlive()) {
            Vec3 at = attacker instanceof Player player ? player.getEyePosition() : position();
            DataCords.breakCord(this, at.lerp(position(), 0.5));
        }
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
    public PushReaction getPistonPushReaction() {
        return PushReaction.IGNORE;
    }

    @Override
    public ItemStack getPickResult() {
        return new ItemStack(ModItems.DATA_CORD.get());
    }

    @Override
    public boolean shouldRenderAtSqrDistance(double distance) {
        double size = getBoundingBox().getSize() * 64 + 64;
        return distance < size * size;
    }

    /**
     * The point where the ray from {@code from} to {@code to} passes within the
     * cord's thickness of it, or null. The curve is walked as short straight
     * segments, which at this thickness is closer than anyone can aim.
     */
    @Override
    public Vec3 raycast(Vec3 from, Vec3 to) {
        if (!bendPoints().isEmpty())
            return BendGeometry.raycast(from, to, bendPath(), CURVE_COEFFICIENT, CURVE_COEFFICIENT, THICKNESS);
        CurveParameters c = curve();
        Vec3 base = endA();
        Vec3 dir = to.subtract(from);
        double length = dir.length();
        if (length < 1e-6)
            return null;
        Vec3 unit = dir.scale(1 / length);
        double[] best = {Double.MAX_VALUE};
        Vec3[] hit = {null};
        double reach = THICKNESS;
        double midX = (endA().x + endB().x) / 2, midZ = (endA().z + endB().z) / 2;
        c.runForSegments((x1, y1, z1, x2, y2, z2, offset, segment) -> {
            Vec3 p = new Vec3(x1 + midX, y1 + base.y, z1 + midZ);
            Vec3 q = new Vec3(x2 + midX, y2 + base.y, z2 + midZ);
            double[] t = closest(from, unit, p, q);
            if (t == null || t[0] < 0 || t[0] > length)
                return;
            Vec3 onRay = from.add(unit.scale(t[0]));
            Vec3 onCord = p.add(q.subtract(p).scale(t[1]));
            if (onRay.distanceToSqr(onCord) < reach * reach && t[0] < best[0]) {
                best[0] = t[0];
                hit[0] = onRay;
            }
        }, 0.25);
        return hit[0];
    }

    /** Closest approach of a ray (origin, unit direction) and a segment: {ray distance, segment fraction}. */
    private static double[] closest(Vec3 origin, Vec3 unit, Vec3 p, Vec3 q) {
        Vec3 seg = q.subtract(p);
        Vec3 w = origin.subtract(p);
        double b = unit.dot(seg), c = seg.dot(seg), d = unit.dot(w), e = seg.dot(w);
        double denom = c - b * b;
        double s = denom < 1e-9 ? 0 : Math.max(0, Math.min(1, (e - b * d) / denom));
        double t = s * b - d;
        return c < 1e-12 ? null : new double[] {t, s};
    }

    // Bending

    @Override
    public List<Vec3> bendPoints() {
        return bends == null ? List.of() : bends;
    }

    @Override
    public void setBendPoints(List<Vec3> points) {
        bends = List.copyOf(points);
        setPos(getX(), getY(), getZ());
    }

    @Override
    public Vec3 bendStart() {
        return endA();
    }

    @Override
    public Vec3 bendEnd() {
        return endB();
    }

    @Override
    public double bendHorizontalCoefficient() {
        return CURVE_COEFFICIENT;
    }

    @Override
    public double bendVerticalCoefficient() {
        return CURVE_COEFFICIENT;
    }

    @Override
    public double bendThickness() {
        return THICKNESS;
    }

    @Override
    public double bendMaxSpan() {
        return PortCompatibility.MAX_CORD_BLOCKS;
    }

    // Saving and spawning

    @Override
    protected void readAdditionalSaveData(CompoundTag tag) {
        posA = NbtUtils.readBlockPos(tag.getCompound("PosA"));
        posB = NbtUtils.readBlockPos(tag.getCompound("PosB"));
        portA = tag.getInt("PortA");
        portB = tag.getInt("PortB");
        PortType read = PortType.byOrdinal(tag.getInt("Type"));
        type = read == null ? PortType.COM : read;
        originA = readVec(tag, "OriginA");
        originB = readVec(tag, "OriginB");
        normalA = readVec(tag, "NormalA");
        normalB = readVec(tag, "NormalB");
        bends = WireBends.read(tag);
        placed();
    }

    @Override
    protected void addAdditionalSaveData(CompoundTag tag) {
        tag.put("PosA", NbtUtils.writeBlockPos(posA));
        tag.put("PosB", NbtUtils.writeBlockPos(posB));
        tag.putInt("PortA", portA);
        tag.putInt("PortB", portB);
        tag.putInt("Type", type.ordinal());
        writeVec(tag, "OriginA", originA);
        writeVec(tag, "OriginB", originB);
        writeVec(tag, "NormalA", normalA);
        writeVec(tag, "NormalB", normalB);
        WireBends.write(tag, bendPoints());
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
        buffer.writeBlockPos(posA);
        buffer.writeBlockPos(posB);
        buffer.writeVarInt(portA);
        buffer.writeVarInt(portB);
        buffer.writeVarInt(type.ordinal());
        for (Vec3 v : new Vec3[] {originA, originB, normalA, normalB}) {
            buffer.writeDouble(v.x);
            buffer.writeDouble(v.y);
            buffer.writeDouble(v.z);
        }
        WireBends.write(buffer, bendPoints());
    }

    @Override
    public void readSpawnData(FriendlyByteBuf buffer) {
        posA = buffer.readBlockPos();
        posB = buffer.readBlockPos();
        portA = buffer.readVarInt();
        portB = buffer.readVarInt();
        PortType read = PortType.byOrdinal(buffer.readVarInt());
        type = read == null ? PortType.COM : read;
        originA = new Vec3(buffer.readDouble(), buffer.readDouble(), buffer.readDouble());
        originB = new Vec3(buffer.readDouble(), buffer.readDouble(), buffer.readDouble());
        normalA = new Vec3(buffer.readDouble(), buffer.readDouble(), buffer.readDouble());
        normalB = new Vec3(buffer.readDouble(), buffer.readDouble(), buffer.readDouble());
        bends = WireBends.read(buffer);
        placed();
    }

    @Override
    public Packet<ClientGamePacketListener> getAddEntityPacket() {
        return NetworkHooks.getEntitySpawningPacket(this);
    }
}
