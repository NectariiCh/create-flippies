package com.nectariich.create_flippies.client.pc;

/**
 * Whether an Escape press releases the keyboard (spec 11.2.12). Pure: press time,
 * whether the key is Escape, the window; no Minecraft types, so it is tested alone.
 *
 * A single Escape is a normal key for the PC (SEND). An Escape press within the
 * window of the previous Escape press, with no other key pressed in between,
 * releases the capture (RELEASE); that second press is never sent. After a release
 * the count starts over, so a third quick Escape is a first one again.
 */
public final class KeyboardReleaseDetector {

    /** What the screen does with a press. */
    public enum Action {
        SEND,
        RELEASE
    }

    private boolean armed;
    private long lastEscapeMillis;

    public Action press(boolean escape, long nowMillis, long windowMillis) {
        if (!escape) {
            armed = false;
            return Action.SEND;
        }
        if (armed && nowMillis - lastEscapeMillis <= windowMillis) {
            armed = false;
            return Action.RELEASE;
        }
        armed = true;
        lastEscapeMillis = nowMillis;
        return Action.SEND;
    }
}
