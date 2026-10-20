package com.nectariich.create_flippies.foundation.wire.bend;

/**
 * Tells a double right-click on a bend point from two separate ones: the second
 * click has to land on the same point of the same wire within {@link #windowMs} of
 * the first. A double click is consumed, so a third click starts counting afresh.
 */
public final class DoubleClickDetector {

    /** The window a desktop double click is usually given, in milliseconds. */
    public static final long DEFAULT_WINDOW_MS = 350;

    private final long windowMs;
    private int lastEntity = -1;
    private int lastPoint = -1;
    private long lastTime;
    private boolean armed;

    public DoubleClickDetector(long windowMs) {
        this.windowMs = windowMs;
    }

    /** Records a click on point {@code point} of wire {@code entity}; true if it completes a double click. */
    public boolean click(int entity, int point, long nowMs) {
        boolean dbl = armed && entity == lastEntity && point == lastPoint && nowMs >= lastTime
                && nowMs - lastTime <= windowMs;
        if (dbl) {
            reset();
            return true;
        }
        armed = true;
        lastEntity = entity;
        lastPoint = point;
        lastTime = nowMs;
        return false;
    }

    /** Forgets the last click, so the next one cannot complete a double click. */
    public void reset() {
        armed = false;
        lastEntity = -1;
        lastPoint = -1;
    }
}
