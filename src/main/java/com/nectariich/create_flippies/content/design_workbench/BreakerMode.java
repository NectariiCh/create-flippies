package com.nectariich.create_flippies.content.design_workbench;

import com.nectariich.create_flippies.CreateFlippies;

import net.minecraft.network.chat.Component;

/**
 * What a designed breaker control switch's contacts follow.
 *
 * The switch always has the three-position switch's rack, and its handle always
 * springs back to the centre from either side, so the choice is only which of the
 * two things the player sees the contacts answer to: the handle, or the flag it
 * leaves behind.
 *
 * The id is what a save holds, so it must stay as it is for as long as worlds do.
 */
public enum BreakerMode {

    /**
     * The contacts follow the handle exactly as a three-position switch's do. Since
     * the handle springs back, the clockwise and counter-clockwise studs are only
     * made while it is held over, and the neutral stud the rest of the time.
     */
    THREE_POSITION("three_position"),

    /**
     * The contacts follow the flag: the clockwise stud is made while the flag is
     * latched clockwise, the counter-clockwise stud while it is latched
     * counter-clockwise, and the neutral stud never is.
     */
    SWITCHOVER("switchover");

    private final String id;

    BreakerMode(String id) {
        this.id = id;
    }

    /** This mode's id, and what a save holds. */
    public String id() {
        return id;
    }

    public Component label() {
        return Component.translatable("gui." + CreateFlippies.MOD_ID + ".design_workbench.breaker_mode." + id);
    }

    /** The mode after this one, for a button that cycles through them. */
    public BreakerMode next() {
        return values()[(ordinal() + 1) % values().length];
    }

    /** The mode saved under this id, or {@link #THREE_POSITION} if it is unknown. */
    public static BreakerMode of(String id) {
        for (BreakerMode mode : values())
            if (mode.id.equals(id))
                return mode;
        return THREE_POSITION;
    }
}
