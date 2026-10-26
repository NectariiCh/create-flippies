package com.nectariich.create_flippies.client.display;

import java.util.Map;
import java.util.WeakHashMap;

import com.nectariich.create_flippies.content.display.element.ScreenElement;
import com.nectariich.create_flippies.content.procedural_gauge.ProceduralGaugeBlockEntity;

import net.minecraft.Util;

/**
 * Where each bar's fill is drawn this frame: eased toward its value the way a gauge's
 * needle is ({@link ProceduralGaugeBlockEntity#NEEDLE_EASING} of the way each tick),
 * or at it for a bar with easing off.
 *
 * Kept per display (by its owner, the object the renderer names it by) and per slot,
 * and driven by the wall clock rather than by ticks, so it is as smooth as the frame
 * rate and needs no ticking of its own. A display that is no longer drawn is let go
 * with its owner.
 */
final class BarMotion {

    private static final float TICKS_PER_MILLI = 20 / 1000f;

    /** A step this long is a display coming back into view: it jumps rather than sweeping from where it was left. */
    private static final long STALE_MILLIS = 1000;

    private static final Map<Object, State> STATES = new WeakHashMap<>();

    private static final class State {
        final float[] shown = new float[ScreenElement.MAX_ID];
        final long[] at = new long[ScreenElement.MAX_ID];
    }

    private BarMotion() {}

    /** How far along its range the bar is drawn, 0 to 1, given how far it should be. */
    static float shown(Object owner, ScreenElement bar, float target) {
        if (owner == null || !bar.bar().easing())
            return target;
        State state = STATES.computeIfAbsent(owner, key -> new State());
        int id = bar.id();
        long now = Util.getMillis();
        long last = state.at[id];
        state.at[id] = now;
        if (last == 0 || now - last > STALE_MILLIS) {
            state.shown[id] = target;
            return target;
        }
        float ticks = (now - last) * TICKS_PER_MILLI;
        float keep = (float) Math.pow(1 - ProceduralGaugeBlockEntity.NEEDLE_EASING, ticks);
        state.shown[id] = target + (state.shown[id] - target) * keep;
        return state.shown[id];
    }
}
