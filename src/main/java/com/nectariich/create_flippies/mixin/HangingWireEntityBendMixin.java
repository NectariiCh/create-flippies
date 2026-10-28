package com.nectariich.create_flippies.mixin;

import java.util.Arrays;
import java.util.List;

import com.nectariich.create_flippies.content.wire_duct.DuctIndex;
import com.nectariich.create_flippies.content.wire_duct.DuctedWires;
import com.nectariich.create_flippies.foundation.wire.bend.BendGeometry;
import com.nectariich.create_flippies.foundation.wire.bend.BendableWire;
import com.nectariich.create_flippies.foundation.wire.bend.LegSag;
import com.nectariich.create_flippies.foundation.wire.bend.WireBendService;
import com.nectariich.create_flippies.foundation.wire.bend.WireBends;
import com.nectariich.create_flippies.foundation.wire.bend.WireSagCompat;
import com.nectariich.create_flippies.foundation.wire.gauge.WireGaugeCompat;
import com.nectariich.create_flippies.foundation.wire.duct.LegacyDuctKeys;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import org.patryk3211.powergrid.electricity.wire.CurveParameters;
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
 * WIRE DUCTS 2: a hanging wire saved while ducts first routed hanging wires loads
 * as a plain one ({@link LegacyDuctKeys}).
 *
 * A hanging wire routed through ducts now is one whose bend points lie in them
 * ({@code HangingDuctRoute}): the legs between those points run straight and
 * hidden ({@link #bendDuctLegs}), and the wire is filed by its path so the ducts
 * count it ({@link DuctIndex}).
 *
 * WIRE SAG: each leg also has a sag of its own ({@link LegSag}), one value per leg,
 * kept the length of the path whatever changes the points. An unbent wire's one value
 * is Power Plant Grid's whole-wire sag, in its format and under its key, so the two
 * mods agree on what a straight wire looks like:
 * <ul>
 * <li>without Power Plant Grid, Flippies reads and writes {@code PPGSag} itself and
 * bends Power Grid's curve to it ({@link #create_flippies$applySag}), as Power Plant
 * Grid would;</li>
 * <li>with it, the unbent wire's sag is Power Plant Grid's alone ({@link
 * WireSagCompat}): Flippies neither reads, writes nor applies it, and only reads it
 * to seed the legs of a wire it is about to bend.</li>
 * </ul>
 * A bent wire's legs are saved under {@link LegSag#NBT_KEY} and drawn by Flippies.
 */
@Mixin(value = HangingWireEntity.class, remap = false)
public abstract class HangingWireEntityBendMixin implements BendableWire {

    @Unique
    private List<Vec3> create_flippies$bends = List.of();

    /** One sag per leg, {@code points + 1}. Null only while the entity is being constructed, as above. */
    @Unique
    private float[] create_flippies$legSag = LegSag.normalise(null, 1);

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
        create_flippies$legSag = LegSag.normalise(create_flippies$legSag, points.size() + 1);
        HangingWireEntity self = create_flippies$self();
        // Re-setting the position is what refits an entity's box
        if (self.terminalPos1 != null && self.terminalPos2 != null)
            self.setPos(self.getX(), self.getY(), self.getZ());
        DuctIndex.update(self);
    }

    /**
     * An unbent wire with Power Plant Grid installed hangs at its sag, not at the one
     * Flippies keeps, so that is what it reads as (and what its first bend point
     * seeds both legs with).
     */
    @Override
    public float[] bendLegSags() {
        if (bendPoints().isEmpty() && WireGaugeCompat.ppgLoaded())
            return new float[] {WireSagCompat.ppgSag(create_flippies$self())};
        float[] sags = LegSag.normalise(create_flippies$legSag, bendPoints().size() + 1);
        return Arrays.copyOf(sags, sags.length);
    }

    /**
     * An unbent wire has its curve rebuilt, so the sag reaches Power Grid's own
     * drawing, picking and touching ({@link #create_flippies$applySag}); a bent one
     * has its box refitted, the legs being drawn from the values themselves.
     */
    @Override
    public void setBendLegSags(float[] sags) {
        create_flippies$legSag = LegSag.normalise(sags == null ? null : Arrays.copyOf(sags, sags.length), bendPoints().size() + 1);
        HangingWireEntity self = create_flippies$self();
        if (self.terminalPos1 == null || self.terminalPos2 == null)
            return;
        if (bendPoints().isEmpty()) {
            if (!WireGaugeCompat.ppgLoaded() && create_flippies$entry() != null)
                self.updateCurveParams();
        } else {
            self.setPos(self.getX(), self.getY(), self.getZ());
        }
    }

    @Override
    public BendGeometry.LegCurves bendCurves() {
        return LegSag.curves(bendHorizontalCoefficient(), bendVerticalCoefficient(), bendLegSags());
    }

    @Override
    public long bendDuctLegs() {
        if (bendPoints().isEmpty())
            return 0;
        return DuctedWires.ductLegs(create_flippies$self().level(), bendPath());
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
            //? if forge {
            BlockPos last = NbtUtils.readBlockPos(tag.getCompound("LastKnownPos"));
            //?} else {
            /*BlockPos last = NbtUtils.readBlockPos(tag, "LastKnownPos").orElse(self.blockPosition());
            *///?}
            BlockPos now = self.blockPosition();
            if (!last.equals(now))
                points = WireBends.offset(points, Vec3.atLowerCornerOf(now.subtract(last)));
        }
        create_flippies$bends = points;
        // WIRE SAG: a missing value reads as 1, as Power Plant Grid reads its own. An
        // unbent wire's lives under Power Plant Grid's key, and is only Flippies' to
        // read while Power Plant Grid is not there to read it
        float[] sags = LegSag.read(tag);
        if (points.isEmpty())
            sags = new float[] {WireGaugeCompat.ppgLoaded() ? LegSag.DEFAULT : LegSag.readPpg(tag)};
        create_flippies$legSag = LegSag.normalise(sags, points.size() + 1);
        // A client reads the save again once the wire is in its level (Power Grid's
        // extra data), after it joined unbent and so unfiled
        DuctIndex.update(self);
    }

    @Inject(method = "makeBoundingBox()Lnet/minecraft/world/phys/AABB;", at = @At("HEAD"), cancellable = true,
            remap = true)
    private void create_flippies$bentBox(CallbackInfoReturnable<AABB> cir) {
        if (bendPoints().isEmpty())
            return;
        List<Vec3> path = bendPath();
        if (path != null)
            cir.setReturnValue(BendGeometry.bounds(path, bendDuctLegs(), bendCurves(), bendThickness()));
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
            cir.setReturnValue(BendGeometry.touches(path, bendDuctLegs(), bendCurves(), bendThickness(),
                    entity.getBoundingBox()));
    }

    @Inject(method = "flipEndpoints()V", at = @At("HEAD"))
    private void create_flippies$flipBends(CallbackInfo ci) {
        create_flippies$bends = WireBends.reversed(bendPoints());
        create_flippies$legSag = LegSag.reversed(LegSag.normalise(create_flippies$legSag, bendPoints().size() + 1));
    }

    @Inject(method = "flipEndpoints()V", at = @At("TAIL"))
    private void create_flippies$syncFlippedBends(CallbackInfo ci) {
        if (!bendPoints().isEmpty())
            WireBendService.sync(create_flippies$self());
    }

    /**
     * WIRE SAG: an unbent wire's sag, bent into Power Grid's own curve whenever it is
     * rebuilt, so Power Grid draws, picks and touches it as usual; and its box refitted
     * round the new curve (on the client Power Grid sizes it from the curve). This is
     * what Power Plant Grid's own mixin does, and is done only while it is not
     * installed, so exactly one mod ever bends the curve. A bent wire is drawn and hit
     * along its legs and leaves Power Grid's curve alone.
     */
    @Inject(method = "updateCurveParams()V", at = @At("TAIL"))
    private void create_flippies$applySag(CallbackInfo ci) {
        if (WireGaugeCompat.ppgLoaded() || !bendPoints().isEmpty())
            return;
        float[] sags = create_flippies$legSag;
        float k = sags == null || sags.length == 0 ? LegSag.DEFAULT : sags[0];
        HangingWireEntity self = create_flippies$self();
        WireItemEntry entry = create_flippies$entry();
        if (k == LegSag.DEFAULT || entry == null || self.terminalPos1 == null || self.terminalPos2 == null)
            return;
        //? if forge {
        self.curveParams = new CurveParameters(self.terminalPos1, self.terminalPos2,
                LegSag.horizontal(entry.horizontalCoefficient(), k), LegSag.vertical(entry.verticalCoefficient(), k),
                entry.wireThickness());
        //?} else {
        /*self.curveParams = BendGeometry.curve(self.terminalPos1, self.terminalPos2,
                LegSag.horizontal(entry.horizontalCoefficient(), k), LegSag.vertical(entry.verticalCoefficient(), k),
                entry.wireThickness());
        *///?}
        // Re-setting the position is what refits an entity's box
        self.setPos(self.getX(), self.getY(), self.getZ());
    }
}
