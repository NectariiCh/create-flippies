package com.nectariich.create_flippies.content.keyboard;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import com.nectariich.create_flippies.content.computer.device.Ps2Key;

/**
 * Who is typing on one keyboard and which keys they hold (spec 11.2.12). Pure, so
 * the rules are tested without a server: one typist at a time, events from anyone
 * else ignored, and every end of a capture hands back the held keys so the server
 * can send their key-ups and no key sticks.
 */
public final class KeyboardCapture {

    private UUID typist;
    private final Set<Ps2Key> held = new LinkedHashSet<>();

    public UUID typist() {
        return typist;
    }

    /** Grants the keyboard to {@code player} unless someone else is typing; true if they now have it. */
    public boolean request(UUID player) {
        if (typist != null && !typist.equals(player))
            return false;
        typist = player;
        return true;
    }

    public boolean isTypist(UUID player) {
        return typist != null && typist.equals(player);
    }

    /**
     * One event from {@code player}: true if it should reach the PC. Ignored unless
     * they are the typist; a key-up for a key not held is ignored too, so a capture
     * started with a key already down does not send a stray break.
     */
    public boolean accept(UUID player, Ps2Key key, boolean down) {
        if (!isTypist(player))
            return false;
        if (down)
            return held.add(key);
        return held.remove(key);
    }

    /** Ends the capture: the keys still held, oldest first, for the server to release. */
    public List<Ps2Key> release() {
        List<Ps2Key> up = new ArrayList<>(held);
        held.clear();
        typist = null;
        return up;
    }
}
