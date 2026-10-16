package com.nectariich.create_flippies.content.din_rail;

import com.nectariich.create_flippies.foundation.control.ControlType;

/**
 * Marks a {@link ControlType} as gear that clips to a DIN rail.
 *
 * A rail hosts controls through exactly the same machinery a panel does, and
 * {@code ControlTypes.forItem} will happily name the kind for any control item at
 * all — so without this a rotary switch or a gauge held against a rail would be
 * clipped to it, hanging off a rail with no hooks and nothing to hook onto.
 *
 * A marker rather than a method on {@code ControlType} because the question is
 * the rail's and not every host's: a panel takes anything, and a kind that wanted
 * to answer for both would be describing the same fact twice.
 */
public interface DinRailMountable {

    /**
     * How many of the rail's sixteen modules one of these occupies, which is what
     * the rail needs to know to seat it: a three module breaker is centred a
     * module and a half along from where it starts, and only fits at fourteen of
     * the sixteen positions.
     */
    int modules();

    /**
     * How far along the rail one of these reaches, in modules. The whole modules
     * of {@link #modules()} for nearly everything; slim gear such as a relay mount
     * is half a module, and the rail seats it at half-module positions so two of
     * them share one module the way they pack on a real rail.
     */
    default double width() {
        return modules();
    }
}
