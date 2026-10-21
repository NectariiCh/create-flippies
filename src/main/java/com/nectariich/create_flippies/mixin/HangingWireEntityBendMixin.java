package com.nectariich.create_flippies.mixin;

import java.util.List;

import com.nectariich.create_flippies.foundation.wire.bend.BendGeometry;
import com.nectariich.create_flippies.foundation.wire.bend.BendableWire;
import com.nectariich.create_flippies.foundation.wire.bend.WireBendService;
import com.nectariich.create_flippies.foundation.wire.bend.WireBends;
import com.nectariich.create_flippies.foundation.wire.duct.LegacyDuctKeys;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import org.patryk3211.powergrid.electricity.wire.HangingWireEntity;
import org.patryk3211.powergrid.electricity.wire.IWireEndpoint;
import org.patryk3211.powergrid.electricity.wire.registry.WireItemEntry;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Gives Power Grid's hanging wire bend points (see {@link WireBends}).
 *
 * The wire stays the one entity, the one electrical connection and the one stack
 * of items it always was; only its shape changes. Each hook here takes over from
 * Power Grid only while the wire has at least one point, so an unbent wire goes
 * down exactly the code path it always did:
 * <ul>
 * <li>the save gains a list of points, read before Power Grid's own read so the
 * wire's first box and first connection already know them, and shifted by the
 * same offset Power Grid applies to the endpoints of a wire moved with its
 * structure;</li>
 * <li>the box covers every leg and its sag, which is what both the server's
 * reach check for a wire cutter and the client's pick pre-check go by;</li>
 * <li>touching a bare wire is tested against the legs, not the straight curve;</li>
 * <li>swapping the ends over reverses the points, so the path still runs from the
 * first end to the second.</li>
 * </ul>
 * The resistance and the save write live on {@code BaseWireEntity}, where Power
 * Grid declares them; see {@link BaseWireEntityBendMixin}.
 *
 * WIRE DUCTS 2: wire ducts carry placeable wires only. A hanging wire saved while
 * ducts still routed hanging wires loads as a plain one ({@link LegacyDuctKeys}).
 */
@Mixin(value = HangingWireEntity.class, remap = false)
public abstract class HangingWireEntityBendMixin implements BendableWire {

    @Unique
    private List<Vec3> create_flippies$bends = List.of();

    @Unique
    private HangingWireEntity create_flippies$self() {
        return (HangingWireEntity) (Object) this;
    }

    /**
     * Null only while the entity is being constructed: Minecraft's constructor asks
     * for a box before this mixin's field initialiser has run.
     */
    @Override
    public List<Vec3> bendPoints() {
        List<Vec3> points = create_flippies$bends;
        return points == null ? List.of() : points;
    }

    @Override
    public void setBendPoints(List<Vec3> points) {
        create_flippies$bends = List.copyOf(points);
        HangingWireEntity self = create_flippies$self();
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
        return entry == null ? 1.01 : entry.horizontalCoefficient();
    }

    @Override
    public double bendVerticalCoefficient() {
        WireItemEntry entry = create_flippies$entry();
        return entry == null ? 1.2 : entry.verticalCoefficient();
    }

    @Override
    public double bendThickness() {
        WireItemEntry entry = create_flippies$entry();
        return entry == null ? 0.0625 : entry.wireThickness();
    }

    @Override
    public double bendMaxSpan() {
        WireItemEntry entry = create_flippies$entry();
        return entry == null ? 0 : entry.maximumLength();
    }

    /**
     * Power Grid makes the connection while the save is still being read, before the
     * terminal positions are known, so the ends fall back on the endpoints there.
     */
    @Override
    public double bendResistanceScale() {
        if (bendPoints().isEmpty())
            return 1;
        HangingWireEntity self = create_flippies$self();
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

    /** The connection is remade so that it picks up the resistance of the new length. */
    @Override
    public void bendsCommitted() {
        HangingWireEntity self = create_flippies$self();
        if (!self.level().isClientSide && self.isAlive())
            self.makeWire();
    }

    @Inject(method = "readAdditionalSaveData(Lnet/minecraft/nbt/CompoundTag;)V", at = @At("HEAD"), remap = true)
    private void create_flippies$readBends(CompoundTag tag, CallbackInfo ci) {
        LegacyDuctKeys.strip(tag);
        List<Vec3> points = WireBends.read(tag);
        HangingWireEntity self = create_flippies$self();
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
            cir.setReturnValue(BendGeometry.bounds(path, 0, bendHorizontalCoefficient(),
                    bendVerticalCoefficient(), bendThickness()));
    }

    /**
     * Placed after Power Grid's own checks of who can be shocked at all, at the
     * point it starts measuring against the curve.
     */
    @Inject(method = "isConnectedTo(Lnet/minecraft/world/entity/LivingEntity;)Z",
            at = @At(value = "FIELD", opcode = org.objectweb.asm.Opcodes.GETFIELD, ordinal = 0,
                    target = "Lorg/patryk3211/powergrid/electricity/wire/HangingWireEntity;"
                            + "curveParams:Lorg/patryk3211/powergrid/electricity/wire/CurveParameters;"),
            cancellable = true)
    private void create_flippies$touchesBent(LivingEntity entity, CallbackInfoReturnable<Boolean> cir) {
        if (bendPoints().isEmpty())
            return;
        List<Vec3> path = bendPath();
        if (path != null)
            cir.setReturnValue(BendGeometry.touches(path, 0, bendHorizontalCoefficient(),
                    bendVerticalCoefficient(), bendThickness(), entity.getBoundingBox()));
    }

    @Inject(method = "flipEndpoints()V", at = @At("HEAD"))
    private void create_flippies$flipBends(CallbackInfo ci) {
        create_flippies$bends = WireBends.reversed(bendPoints());
    }

    @Inject(method = "flipEndpoints()V", at = @At("TAIL"))
    private void create_flippies$syncFlippedBends(CallbackInfo ci) {
        if (!bendPoints().isEmpty())
            WireBendService.sync(create_flippies$self());
    }
}
