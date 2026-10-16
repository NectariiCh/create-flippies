package com.nectariich.create_flippies.content.circuit_breaker;

import com.nectariich.create_flippies.CreateFlippies;
import com.nectariich.create_flippies.foundation.control.ControlFootprint;

import net.minecraft.network.chat.Component;

import org.patryk3211.powergrid.electricity.base.IDecoratedTerminal;

/**
 * The authored geometry of the CB-DIN-WO breakers, shared by the kinds that
 * mount them, the footprints that reserve their space, the terminals they present
 * and the renderer that draws them.
 *
 * These numbers mirror the exports in {@code models/block/CB*-DIN-WO-*.json} and
 * are re-derived from them by {@code tools/gen_circuit_breaker.py} — that script
 * prints every figure below as it splits them, so an export that moves something
 * is caught by running it and comparing.
 *
 * <h2>What varies and what does not</h2>
 *
 * Only two things change between a one, two and three pole breaker: how wide it
 * is, and how many contacts it has. Depth off the panel, height across the rail,
 * where the switch hinges and how far it sweeps are the same on all of them,
 * which is why almost everything here is a constant and the rest takes a module
 * count.
 *
 * <h2>Frames</h2>
 *
 * The exports author against {@code x = 0}, which is handy to edit but not where
 * the mount frame wants it: a
 * {@link com.nectariich.create_flippies.foundation.control.ControlMount}
 * displaces a control from the middle of a face, so an authored model has to
 * straddle the middle for an anchor of {@code CENTRED} to leave it alone. How far
 * it moves is the breaker's own width — see {@link #shiftX} — and everything here
 * is in that shifted frame.
 *
 * The breakers are authored plate-down like everything else in this mod:
 * {@code y} is depth off the panel, {@code z} runs across the rail and is the
 * breaker's height, and {@code x} is the module width that runs along the rail.
 */
public final class CircuitBreakerModel {

    private CircuitBreakerModel() {}

    /** One module of width, the 17.5mm a single pole of DIN gear occupies. */
    public static final double MODULE_WIDTH = 1.0;

    /** The breaker's depth off the panel, and its height across the rail. */
    public static final double MIN_Y = 0.25;
    public static final double MIN_Z = 5.25;
    public static final double MAX_Z = 10.75;

    /** The front panel of the breaker: the top of the housing, and what you look at. */
    private static final double FRONT_PANEL = 3.5;

    /**
     * How far below the front panel the switch hinges, putting the pivot just
     * inside the case where a real toggle's is.
     *
     * It was a pixel and a half at first, on the reasoning that a deeper hinge
     * throws the lever further. It does — far too far: the lever swung out of the
     * housing and through the panel it is meant to poke out of. Settled at a fifth
     * of a pixel by eye on 2026-08-25. Mirrors {@code PIVOT_DROP} in the
     * generator; set both to zero if the drop is ever baked into the exports.
     */
    public static final double PIVOT_DROP = 0.2;

    /** Where the switch swings about. The same on every pole count. */
    public static final double PIVOT_Y = FRONT_PANEL - PIVOT_DROP;
    public static final double PIVOT_Z = 8.25;

    /**
     * The furthest corner of the switch from the pivot, so the radius of the disc
     * it sweeps. With the hinge just under the front panel the lever swings within
     * a pixel of it, so the swept z of 7.44..9.06 sits well inside the breaker's
     * own 5.25..10.75 and the footprint never has to allow for it.
     */
    private static final double SWEEP_RADIUS = 0.8139;

    /** The top of everything, the switch's sweep included. */
    public static final double MAX_Y = PIVOT_Y + SWEEP_RADIUS;

    /** Where the switch stands when the breaker is made, and when it is not. */
    public static final float CLOSED_ANGLE = -45;
    public static final float OPEN_ANGLE = 45;

    /**
     * How fast the switch eases towards closed, as a fraction of the remaining
     * angle per tick.
     *
     * A breaker does not glide, it goes over centre and slams — so this is far
     * brisker than the rotary switch's handle. At this rate the throw is within a
     * degree of done in three ticks, an eighth of a second, which reads as a snap
     * while still being carried smoothly between ticks by the partial-tick lerp.
     */
    public static final float CLOSE_EASING = 0.8f;

    /**
     * The same, for the way back out — and far faster, because closing and opening
     * are not the same motion. Closing is a hand pushing a lever over centre;
     * opening is the latch letting go and the spring that was loaded on the way in
     * throwing it, which is the same movement whether an element tripped it or a
     * thumb did. That is over before the eye follows it.
     *
     * A tick is the finest this can be, so nearly all of the travel is spent in
     * one: what is left after that is a fifth of a degree, and the lerp carries
     * even the single tick across smoothly enough to see which way it went.
     */
    public static final float OPEN_EASING = 0.95f;

    /**
     * How far a breaker of this many modules is moved to straddle the middle of
     * the block: 7.5, 7 and 6.5 pixels for one, two and three.
     */
    public static double shiftX(int modules) {
        return 8 - modules * MODULE_WIDTH / 2;
    }

    public static double minX(int modules) {
        return shiftX(modules);
    }

    public static double maxX(int modules) {
        return shiftX(modules) + modules * MODULE_WIDTH;
    }

    /**
     * The breaker's own body, as one box, which is the space it reserves.
     *
     * This was briefly the module plus a fudge for the mount lattice, because
     * three rails to a block put them 5.333px apart while a breaker is 5.5px tall
     * — so two of them back to back on neighbouring rails overlapped and the
     * second was refused. Two rails to a block ended that: the pitch is 8px, the
     * centres are 4 and 12 and land exactly on the lattice, and a 5.5px breaker
     * clears its neighbour by two and a half pixels. So the footprint is the
     * honest thing.
     *
     * Along the rail it is the modules the breaker occupies, which is what stops
     * two of them sharing one. Modules are a pixel apart and
     * {@code AABB.intersects} is strict about touching faces, so breakers on
     * neighbouring modules pack tight and never overlap. MAX_Y carries the
     * switch's whole sweep, not the body's top.
     */
    public static ControlFootprint footprint(int modules) {
        return ControlFootprint
                .of(new double[] { minX(modules), MIN_Y, MIN_Z, maxX(modules), MAX_Y, MAX_Z });
    }

    /**
     * The contact studs of every pole, grown into something a crosshair can land
     * on, in the order the terminals are numbered: line then load, pole by pole.
     *
     * The exports draw each stud a quarter pixel proud and half a pixel square,
     * and Power Grid resolves a terminal from the exact spot clicked, so the drawn
     * stud is far too small a target. Each box reaches a quarter pixel further out
     * from the body and is padded across it, but is kept inside its own module's
     * pixel: a box wider than the module would overlap the neighbouring pole's,
     * and a wire aimed at one would land on the other.
     */
    public static double[][] terminalBoxes(int modules) {
        double shift = shiftX(modules);
        double[][] boxes = new double[modules * 2][];
        for (int module = 0; module < modules; module++) {
            double lo = shift + module * MODULE_WIDTH + 0.1;
            double hi = shift + module * MODULE_WIDTH + 0.9;
            boxes[line(module)] = new double[] { lo, 1.1, 5.0, hi, 1.9, 5.5 };
            boxes[load(module)] = new double[] { lo, 1.1, 10.5, hi, 1.9, 11.0 };
        }
        return boxes;
    }

    /**
     * Where a wire should land on each stud: the middle of its outward face, as
     * the exports draw it.
     *
     * Stated rather than left to Power Grid, which derives an origin from the
     * midpoint of the box it was given — and the boxes above are grown outwards on
     * one side only, which drags that midpoint off the stud.
     */
    public static double[][] terminalOrigins(int modules) {
        double shift = shiftX(modules);
        double[][] origins = new double[modules * 2][];
        for (int module = 0; module < modules; module++) {
            double middle = shift + module * MODULE_WIDTH + MODULE_WIDTH / 2;
            origins[line(module)] = new double[] { middle, 1.5, 5.25 };
            origins[load(module)] = new double[] { middle, 1.5, 10.75 };
        }
        return origins;
    }

    /** Index of a pole's line terminal, at the top of the breaker. */
    public static int line(int pole) {
        return pole * 2;
    }

    /** Index of a pole's load terminal, at the bottom. */
    public static int load(int pole) {
        return pole * 2 + 1;
    }

    public static int[] terminalColors(int modules) {
        int[] colors = new int[modules * 2];
        for (int module = 0; module < modules; module++) {
            colors[line(module)] = IDecoratedTerminal.RED;
            colors[load(module)] = IDecoratedTerminal.BLUE;
        }
        return colors;
    }

    private static final String TERMINAL_PREFIX = "gui." + CreateFlippies.MOD_ID + ".circuit_breaker.terminal.";

    /**
     * What each contact is called. A single pole breaker's contacts are just Line
     * and Load; on a multi-pole one they are numbered, because a tooltip that said
     * "Line" three times would not say which pole it was on.
     */
    public static Component[] terminalNames(int modules) {
        Component[] names = new Component[modules * 2];
        for (int module = 0; module < modules; module++) {
            if (modules == 1) {
                names[line(0)] = Component.translatable(TERMINAL_PREFIX + "line");
                names[load(0)] = Component.translatable(TERMINAL_PREFIX + "load");
                continue;
            }
            names[line(module)] = Component.translatable(TERMINAL_PREFIX + "line_n", module + 1);
            names[load(module)] = Component.translatable(TERMINAL_PREFIX + "load_n", module + 1);
        }
        return names;
    }
}
