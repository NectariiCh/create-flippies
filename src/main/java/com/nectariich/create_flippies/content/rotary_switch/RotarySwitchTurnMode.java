package com.nectariich.create_flippies.content.rotary_switch;

import com.nectariich.create_flippies.CreateFlippies;

import net.minecraft.network.chat.Component;

/**
 * Which gesture turns a rotary switch.
 *
 * A setting of the player's own, not of the world or the switch: it decides how one
 * client reads its mouse, and the server is told the same thing either way — one
 * notch and its direction. So two players standing at one panel may each be using
 * the gesture they prefer on the same switch, and neither has to be given
 * permission to choose. It is kept in the client config
 * ({@code com.nectariich.create_flippies.foundation.ClientConfig}), which is what
 * makes the choice outlive a restart and stay per-installation.
 *
 * This lives out here rather than beside the gesture that implements it because the
 * config has to name it, and the config is loaded on a dedicated server too, where
 * nothing client-only may be touched.
 *
 * The constant names are what the config file holds, so they are as good as saved
 * data: renaming one silently resets everybody who had chosen it back to the
 * default.
 */
public enum RotarySwitchTurnMode {

    /**
     * A click on one half of the printed front turns the switch the other way; see
     * {@link RotarySwitchSide}. The default, and what a real switch wants.
     */
    PUSH("push"),

    /**
     * The same click, turning the switch towards the half that was pushed rather
     * than away from it: you point at the detent you want and the handle comes to
     * it.
     *
     * Backwards as a piece of physics — nothing turns towards the finger pushing
     * it — but it is how a good many games read a click on a dial, and a player who
     * has those reflexes fights the honest gesture every time. It is a preference
     * about input and nothing else, so it costs nothing to offer: see
     * {@code RotarySwitchPushInput}, which flips the side before it leaves the
     * client and sends the server the same notch either mode would have.
     */
    PUSH_INVERTED("pushInverted"),

    /**
     * Take hold of the handle and slide the crosshair across the front; see
     * {@code RotarySwitchDragInput}. Experimental.
     */
    DRAG("drag");

    private static final String LANG_PREFIX = "commands." + CreateFlippies.MOD_ID + ".turnmode.";

    private final String id;

    RotarySwitchTurnMode(String id) {
        this.id = id;
    }

    /** The word the command is spelled with, and the lang key's last part. */
    public String id() {
        return id;
    }

    /** How the mode is named in chat, e.g. {@code pushing the front}. */
    public Component label() {
        return Component.translatable(LANG_PREFIX + id);
    }

    /** Whether this mode wants a word of warning the first time it is chosen. */
    public boolean isExperimental() {
        return this == DRAG;
    }

    /**
     * Whether a push turns the handle towards the half that was pushed.
     *
     * Asked on the client alone, which is the only side that knows the answer.
     * {@link #PUSH} needs no help — the server works the side out from where the
     * click landed and turns away from it, which is the honest gesture — so it is
     * only the inverted mode that has to say anything, and it does so by sending a
     * notch of its own rather than by telling the server what it prefers.
     */
    public boolean invertsPush() {
        return this == PUSH_INVERTED;
    }
}
