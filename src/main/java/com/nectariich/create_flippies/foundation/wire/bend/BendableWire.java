package com.nectariich.create_flippies.foundation.wire.bend;

import java.util.List;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;

/**
 * A hanging wire that can carry bend points: Power Grid's hanging wire, power cord
 * and string light cord (through mixins) and this mod's data cord. Everything bend-related reads and edits a wire
 * through this, so the input, the overlay, the packets and the hit tests never need
 * to know which kind they hold.
 *
 * The methods are named for bends so that, added to Power Grid's entity by a mixin,
 * they cannot collide with anything it already has.
 */
public interface BendableWire {

    /** The bend points, in path order from {@link #bendStart}; empty for an unbent wire. Never modified. */
    List<Vec3> bendPoints();

    /**
     * Replaces the points and refits the entity's box around them. On the server this
     * alone changes nothing for anyone else; see {@code WireBendService} for an edit
     * that is saved, synced and reflected in the wire's resistance.
     */
    void setBendPoints(List<Vec3> points);

    /** The first end of the path, or null while it is not yet known. */
    Vec3 bendStart();

    /** The second end of the path, or null while it is not yet known. */
    Vec3 bendEnd();

    /** The curve coefficients and thickness the wire hangs with, as Power Grid's wire types give them. */
    double bendHorizontalCoefficient();

    double bendVerticalCoefficient();

    double bendThickness();

    /** The longest straight span the wire's type allows, blocks. */
    double bendMaxSpan();

    /**
     * How much the bends multiply the wire's resistance; see
     * {@link WireBends#resistanceScale}. Exactly 1 for an unbent wire.
     */
    default double bendResistanceScale() {
        return WireBends.resistanceScale(bendStart(), bendPoints(), bendEnd());
    }

    /**
     * Which legs of the path run inside a wire duct (bit {@code i}: the leg from path
     * point {@code i}); see {@code DuctRoute}. Zero for a wire through no duct, which
     * is every wire but a Power Grid hanging wire routed through one.
     */
    default long bendDuctLegs() {
        return 0;
    }

    /**
     * Replaces the points and the marks of which legs run in ducts together. Only a
     * wire that can be routed through ducts keeps the marks; any other takes the
     * points alone.
     */
    default void setBendRoute(List<Vec3> points, long ductLegs) {
        setBendPoints(points);
    }

    /** Whether any leg runs inside a duct; such a wire's points are fixed by its ducts and cannot be bent by hand. */
    default boolean bendDucted() {
        return bendDuctLegs() != 0;
    }

    /** Called on the server once an edit is final, for a wire whose behaviour depends on its shape. */
    default void bendsCommitted() {}

    /**
     * Whether a click at {@code at} on the wire belongs to the wire's own handling
     * rather than to bending, as where a Power Grid cord is pulled off its socket.
     */
    default boolean bendClickReserved(Vec3 at) {
        return false;
    }

    default Entity bendEntity() {
        return (Entity) this;
    }

    /** The whole path, ends included, or null while an end is not known. */
    default List<Vec3> bendPath() {
        Vec3 start = bendStart();
        Vec3 end = bendEnd();
        if (start == null || end == null)
            return null;
        return WireBends.path(start, bendPoints(), end);
    }
}
