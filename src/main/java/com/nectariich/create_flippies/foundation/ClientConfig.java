package com.nectariich.create_flippies.foundation;

import com.nectariich.create_flippies.content.rotary_switch.RotarySwitchTurnMode;

import net.minecraftforge.common.ForgeConfigSpec;

/**
 * The mod's client-side settings, written to
 * {@code config/create_flippies-client.toml}.
 *
 * Client rather than common or server on purpose: everything here is a preference
 * about how one player's own game reads their input, so it belongs to the
 * installation and not to the world. On a multiplayer server that is what lets every
 * player choose for themselves, with nothing to synchronise and nobody needing to be
 * an operator to be asked.
 *
 * Nothing client-only is imported, so this class is safe to load on a dedicated
 * server — where the config is simply never loaded, and every accessor below falls
 * back to its default.
 */
public final class ClientConfig {

    public static final ForgeConfigSpec SPEC;

    private static final ForgeConfigSpec.EnumValue<RotarySwitchTurnMode> TURN_MODE;

    static {
        ForgeConfigSpec.Builder builder = new ForgeConfigSpec.Builder();
        TURN_MODE = builder
                .comment("Which gesture turns a rotary switch.",
                        "  PUSH          - click one half of the printed front; the switch turns the other way.",
                        "  PUSH_INVERTED - the same click, but the switch turns towards the half you clicked.",
                        "  DRAG          - hold use on the handle and slide the crosshair across the front.",
                        "                  Still experimental.",
                        "Also settable in game with /flippies turnmode, which writes back to here.")
                .defineEnum("rotarySwitchTurnMode", RotarySwitchTurnMode.PUSH);
        SPEC = builder.build();
    }

    private ClientConfig() {}

    /**
     * The gesture this player turns switches with.
     *
     * Cheap enough to call every frame: a {@code ConfigValue} holds its value once
     * read and only looks again when the file is reloaded, so this is a field read
     * after the first call.
     */
    public static RotarySwitchTurnMode turnMode() {
        return SPEC.isLoaded() ? TURN_MODE.get() : RotarySwitchTurnMode.PUSH;
    }

    /**
     * Chooses the gesture and writes the file, so the choice survives the game
     * closing. Silently does nothing if the config is not loaded, which on a client
     * cannot happen after mod loading and on a server is always true.
     */
    public static void setTurnMode(RotarySwitchTurnMode mode) {
        if (!SPEC.isLoaded())
            return;
        TURN_MODE.set(mode);
        TURN_MODE.save();
    }
}
