package com.nectariich.create_flippies.content.keyboard;

import com.nectariich.create_flippies.content.computer.device.Ps2Key;

/**
 * The wire form of a key event in {@code KeyboardKeysPacket} (spec 11.2.12):
 * {@code Ps2Key} ordinal << 1 | down. The ordinal is why {@code Ps2Key}'s order is
 * frozen.
 */
public final class KeyboardEvents {

    /** Events per packet at most. */
    public static final int MAX_PER_PACKET = 64;

    private KeyboardEvents() {}

    public static short encode(Ps2Key key, boolean down) {
        return (short) (key.ordinal() << 1 | (down ? 1 : 0));
    }

    /** The key of an event, or null for an ordinal this build does not know. */
    public static Ps2Key key(short event) {
        int ordinal = (event & 0xFFFF) >>> 1;
        Ps2Key[] keys = Ps2Key.values();
        return ordinal < keys.length ? keys[ordinal] : null;
    }

    public static boolean down(short event) {
        return (event & 1) != 0;
    }
}
