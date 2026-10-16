package com.nectariich.create_flippies.content.indicator;

import java.util.Locale;

import com.nectariich.create_flippies.CreateFlippies;

import net.minecraft.network.chat.Component;

/**
 * What happens to the three pairs of contacts behind an indicator when its cap is
 * pushed.
 *
 * The contacts are on the unit whatever this says, which is why this is a setting
 * and not three kinds of block. A pilot lamp and a lit pushbutton are the same
 * moulded body with the same rack behind it — the difference is whether the cap
 * moves — and a panel builder decides which one they wanted after the thing is on
 * the wall and wired. So the poles are always there to attach to, and this decides
 * only what closes them.
 *
 * Both closing actions close all three poles together. They are three poles of one
 * button, not three buttons: a real pushbutton carries a stack of contact blocks
 * under one plunger, which is what lets one press break a hold circuit and make two
 * others at the same instant.
 *
 * The id is what a save holds, so it must stay as it is for as long as worlds do.
 */
public enum IndicatorAction {

    /**
     * The cap does not move and the poles stay open. A pilot lamp, which is what
     * every indicator was before there were contacts behind it — so it is the
     * default, and one already on a panel keeps behaving exactly as it did.
     */
    NO_CLICK,

    /**
     * Closed for as long as the player holds the use key on the cap, open the moment
     * they let go.
     *
     * Held rather than pulsed for a fixed time because that is the whole of what a
     * momentary button is for: a jog button runs a motor while it is down, and a
     * fixed pulse would run it for a second whatever the player meant. The server
     * never takes the client's word for how long that is — see
     * {@code IndicatorBlockEntity.pressedBy}, which lets go on its own if the player
     * who pressed it walks away, dies or disconnects.
     */
    SPRING_RETURN,

    /** Closed by a press and opened by the next one: a maintained button. */
    LATCHING;

    private static final String LANG_PREFIX = "gui." + CreateFlippies.MOD_ID + ".indicator.action.";

    /** What an indicator does until somebody says otherwise; see {@link #NO_CLICK}. */
    public static IndicatorAction defaultAction() {
        return NO_CLICK;
    }

    /** This action's id, and what a save holds. */
    public String id() {
        return name().toLowerCase(Locale.ROOT);
    }

    /** Whether the cap moves at all, which is the whole of what a click can mean. */
    public boolean clickable() {
        return this != NO_CLICK;
    }

    /** Whether the contacts follow the key rather than a press of it. */
    public boolean springs() {
        return this == SPRING_RETURN;
    }

    /** The next one round, so one button can cycle them. */
    public IndicatorAction next() {
        return values()[(ordinal() + 1) % values().length];
    }

    public Component label() {
        return Component.translatable(LANG_PREFIX + id());
    }

    /** The action saved under this id, or {@code fallback} if it is unknown. */
    public static IndicatorAction of(String id, IndicatorAction fallback) {
        for (IndicatorAction action : values())
            if (action.id().equals(id))
                return action;
        return fallback;
    }

    /** Guards a value off the wire, where the ordinal is whatever was sent. */
    public static IndicatorAction byOrdinal(int ordinal) {
        IndicatorAction[] all = values();
        return ordinal >= 0 && ordinal < all.length ? all[ordinal] : defaultAction();
    }
}
