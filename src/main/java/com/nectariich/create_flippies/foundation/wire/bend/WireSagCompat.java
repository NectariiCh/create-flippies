package com.nectariich.create_flippies.foundation.wire.bend;

import java.lang.reflect.Method;

import com.nectariich.create_flippies.CreateFlippies;
import com.nectariich.create_flippies.foundation.wire.gauge.WireGaugeCompat;

import net.minecraft.world.entity.Entity;

import org.patryk3211.powergrid.electricity.wire.HangingWireEntity;

/**
 * Power Plant Grid's whole-wire sag, reached from Flippies, which has no compile
 * dependency on it.
 *
 * With Power Plant Grid installed an unbent hanging wire's sag is its alone: it
 * saves it ({@code PPGSag}), re-sags a wire split by its insulators and clamps as one
 * chain, and handles the click that drags it. Flippies only takes over once the wire
 * is bent, and keeps a sag per leg of its own ({@link LegSag}). The two meet only
 * where a wire crosses between the two states, and that is all this is for:
 * <ul>
 * <li>the first bend point seeds both new legs with the sag Power Plant Grid had
 * given the wire, so bending it does not snap it back to Power Grid's shape;</li>
 * <li>removing the last point hands the merged sag back to Power Plant Grid
 * ({@link #handOver}).</li>
 * </ul>
 * Power Plant Grid adds {@code powerplantgrid$sag()} and {@code powerplantgrid$setSag}
 * to every Power Grid wire through its {@code IWireOriginalSpan} mixin interface; they
 * are found once by name. If that ever fails (Power Plant Grid renamed them), one
 * warning is logged, a wire reads as sag 1, and nothing is handed back: bent legs
 * still work, a wire just starts and ends its bent life at Power Grid's shape.
 */
public final class WireSagCompat {

    private static final String SPAN_INTERFACE = "com.powerplantgrid.electricity.IWireOriginalSpan";

    private static boolean resolved;
    private static Method getter;
    private static Method setter;

    private WireSagCompat() {}

    private static boolean resolve() {
        if (!resolved) {
            resolved = true;
            try {
                Class<?> span = Class.forName(SPAN_INTERFACE, false, WireSagCompat.class.getClassLoader());
                getter = span.getMethod("powerplantgrid$sag");
                setter = span.getMethod("powerplantgrid$setSag", float.class);
            } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
                getter = null;
                setter = null;
                CreateFlippies.LOGGER.warn("Could not reach Power Plant Grid's wire sag; bent wires start at Power Grid's shape", e);
            }
        }
        return getter != null;
    }

    /** Power Plant Grid's sag on a wire, or 1 without it (or for anything that is not a Power Grid wire). */
    public static float ppgSag(Entity entity) {
        if (!WireGaugeCompat.ppgLoaded() || !resolve() || !getter.getDeclaringClass().isInstance(entity))
            return LegSag.DEFAULT;
        try {
            float k = (float) getter.invoke(entity);
            return LegSag.valid(k) ? k : LegSag.DEFAULT;
        } catch (ReflectiveOperationException | RuntimeException e) {
            return LegSag.DEFAULT;
        }
    }

    /** Sets Power Plant Grid's sag on a wire; nothing without it. */
    public static void setPpgSag(Entity entity, float k) {
        if (!WireGaugeCompat.ppgLoaded() || !resolve() || !setter.getDeclaringClass().isInstance(entity))
            return;
        try {
            setter.invoke(entity, k);
        } catch (ReflectiveOperationException | RuntimeException e) {
            CreateFlippies.LOGGER.debug("Could not set Power Plant Grid's sag on {}", entity, e);
        }
    }

    /**
     * A hanging wire that has just lost its last bend point goes back to Power Plant
     * Grid with sag {@code k}: its field is set, Power Grid rebuilds the curve (and
     * Power Plant Grid's own mixin bends it to {@code k}), and on the server every
     * client tracking the wire is sent the save again, which carries {@code PPGSag}.
     * Without Power Plant Grid the wire keeps {@code k} as its one leg, so this does
     * nothing.
     */
    public static void handOver(Entity entity, float k) {
        if (!WireGaugeCompat.ppgLoaded() || !(entity instanceof HangingWireEntity wire))
            return;
        setPpgSag(entity, k);
        if (wire.terminalPos1 != null && wire.terminalPos2 != null && wire.getWireEntry() != null)
            wire.updateCurveParams();
        if (!entity.level().isClientSide)
            wire.sendExtraData();
    }
}
