package com.nectariich.create_flippies.mixin;

import java.util.List;

import com.nectariich.create_flippies.foundation.wire.bend.BendGeometry;
import com.nectariich.create_flippies.foundation.wire.bend.BendableWire;
import com.nectariich.create_flippies.foundation.wire.bend.WireBends;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import org.patryk3211.powergrid.electricity.light.string.StringLightCordEntity;
import org.patryk3211.powergrid.electricity.wire.IWireEndpoint;
import org.patryk3211.powergrid.electricity.wire.WireEndpointType;
import org.patryk3211.powergrid.electricity.wire.powercord.CordEntity;
import org.patryk3211.powergrid.electricity.wire.registry.WireItemEntry;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * WIRE BENDS 2: gives Power Grid's cords bend points, as {@link HangingWireEntityBendMixin}
 * does its hanging wires. That covers the power cord ({@code powergrid:cord}) and the
 * string light cord ({@code powergrid:string_light_cord}), which extends it.
 *
 * A cord is the same one entity and one pair of connections with the points; only
 * its shape changes, and every hook here steps aside while it has none:
 * <ul>
 * <li>the save gains the points, read before Power Grid's own read so the first
 * connection already knows them, and shifted with a cord moved with its structure;</li>
 * <li>the box covers every leg and its sag;</li>
 * <li>the plugs, split leads and the pull-off-the-socket click stay at the cord's
 * true ends, the first leg's start and the last leg's end;</li>
 * <li>a string light cord clears the light blocks of its old shape before taking a
 * new one; its next light update places them along the new one (see
 * {@link StringLightCordEntityBendMixin}).</li>
 * </ul>
 * The resistance and the save write live on {@code BaseWireEntity}; see
 * {@link BaseWireEntityBendMixin}. A cord is never routed through wire ducts.
 */
@Mixin(value = CordEntity.class, remap = false)
public abstract class CordEntityBendMixin implements BendableWire {

    @Unique
    private List<Vec3> create_flippies$bends = List.of();

    @Unique
    private CordEntity create_flippies$self() {
        return (CordEntity) (Object) this;
    }

    /** Null only while the entity is being constructed, as on the hanging wire. */
    @Override
    public List<Vec3> bendPoints() {
        List<Vec3> points = create_flippies$bends;
        return points == null ? List.of() : points;
    }

    @Override
    public void setBendPoints(List<Vec3> points) {
        CordEntity self = create_flippies$self();
        // The lights of the old shape go out; the cord lights the new one on its own
        if (self instanceof StringLightCordEntity lights && !self.level().isClientSide && !bendPoints().equals(points)
                && self.getEndpoint1() != null && self.getEndpoint2() != null)
            lights.setLightBlocks(Blocks.AIR.defaultBlockState());
        create_flippies$bends = List.copyOf(points);
        // Re-setting the position is what refits an entity's box
        if (self.terminalPos1 != null && self.terminalPos2 != null)
            self.setPos(self.getX(), self.getY(), self.getZ());
    }

    @Override
    public Vec3 bendStart() {
        return create_flippies$self().terminalPos1;
    }

    @Override
    public Vec3 bendEnd() {
        return create_flippies$self().terminalPos2;
    }

    @Unique
    private WireItemEntry create_flippies$entry() {
        return create_flippies$self().getWireEntry();
    }

    @Override
    public double bendHorizontalCoefficient() {
        WireItemEntry entry = create_flippies$entry();
        return entry == null ? 1.005 : entry.horizontalCoefficient();
    }

    @Override
    public double bendVerticalCoefficient() {
        WireItemEntry entry = create_flippies$entry();
        return entry == null ? 1.005 : entry.verticalCoefficient();
    }

    @Override
    public double bendThickness() {
        WireItemEntry entry = create_flippies$entry();
        return entry == null ? 0.125 : entry.wireThickness();
    }

    @Override
    public double bendMaxSpan() {
        WireItemEntry entry = create_flippies$entry();
        return entry == null ? 0 : entry.maximumLength();
    }

    /** The connections are made while the save is still being read, so the ends fall back on the endpoints. */
    @Override
    public double bendResistanceScale() {
        if (bendPoints().isEmpty())
            return 1;
        CordEntity self = create_flippies$self();
        Vec3 start = self.terminalPos1;
        Vec3 end = self.terminalPos2;
        Level level = self.level();
        if (start == null)
            start = create_flippies$position(self.getEndpoint1(), level);
        if (end == null)
            end = create_flippies$position(self.getEndpoint2(), level);
        return WireBends.resistanceScale(start, create_flippies$bends, end);
    }

    @Unique
    private static Vec3 create_flippies$position(IWireEndpoint endpoint, Level level) {
        return endpoint == null || level == null ? null : endpoint.getExactPosition(level);
    }

    /**
     * The connections are remade so that they pick up the resistance of the new
     * length. Dropped first: the string light cord's own remake does not drop the
     * ones it had.
     */
    @Override
    public void bendsCommitted() {
        CordEntity self = create_flippies$self();
        if (!self.level().isClientSide && self.isAlive()) {
            self.dropWire();
            self.makeWire();
        }
    }

    /** Power Grid pulls a cord off a socket clicked within half a block of it; bending leaves that alone. */
    @Override
    public boolean bendClickReserved(Vec3 at) {
        CordEntity self = create_flippies$self();
        return create_flippies$nearSocket(self.getEndpoint1(), at, self.level())
                || create_flippies$nearSocket(self.getEndpoint2(), at, self.level());
    }

    @Unique
    private static boolean create_flippies$nearSocket(IWireEndpoint endpoint, Vec3 at, Level level) {
        return endpoint != null && endpoint.type() == WireEndpointType.SOCKET
                && at.distanceToSqr(endpoint.getExactPosition(level)) < 0.25;
    }

    @Inject(method = "readAdditionalSaveData(Lnet/minecraft/nbt/CompoundTag;)V", at = @At("HEAD"), remap = true)
    private void create_flippies$readBends(CompoundTag tag, CallbackInfo ci) {
        List<Vec3> points = WireBends.read(tag);
        CordEntity self = create_flippies$self();
        // The same correction Power Grid makes to the endpoints, and on the same side
        if (!points.isEmpty() && !self.level().isClientSide && tag.contains("LastKnownPos")) {
            BlockPos last = NbtUtils.readBlockPos(tag.getCompound("LastKnownPos"));
            BlockPos now = self.blockPosition();
            if (!last.equals(now))
                points = WireBends.offset(points, Vec3.atLowerCornerOf(now.subtract(last)));
        }
        create_flippies$bends = points;
    }

    @Inject(method = "makeBoundingBox()Lnet/minecraft/world/phys/AABB;", at = @At("HEAD"), cancellable = true,
            remap = true)
    private void create_flippies$bentBox(CallbackInfoReturnable<AABB> cir) {
        if (bendPoints().isEmpty())
            return;
        List<Vec3> path = bendPath();
        if (path != null)
            cir.setReturnValue(BendGeometry.bounds(path, bendHorizontalCoefficient(), bendVerticalCoefficient(),
                    bendThickness()));
    }
}
