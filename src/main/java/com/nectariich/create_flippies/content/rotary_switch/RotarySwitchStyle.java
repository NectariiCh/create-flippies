package com.nectariich.create_flippies.content.rotary_switch;

import java.util.Locale;

import com.nectariich.create_flippies.CreateFlippies;

import net.minecraft.network.chat.Component;

/**
 * One of the interchangeable looks a rotary switch's parts come in.
 *
 * The same three letters serve two independent choices — the paint on the body
 * and the shape of the handle — because the two are the same kind of decision and
 * are drawn from the same set of authored parts. A switch may wear paint B with a
 * style-A handle; nothing pairs them.
 *
 * More letters are expected. Adding one here is a new constant, the authored parts
 * named after it — a body model and front texture per marking, and a handle model
 * and texture — and the letter listed on whichever {@link RotarySwitchSize}s those
 * parts were drawn for, since that is what the wrench menu cycles.
 */
public enum RotarySwitchStyle {

    A, B, C;

    private static final String LANG_PREFIX = "gui." + CreateFlippies.MOD_ID + ".rotary_switch.style.";

    /** This style's half of the model and texture names, and what a save holds. */
    public String id() {
        return name().toLowerCase(Locale.ROOT);
    }

    /** Named in lang rather than by its letter, so a style can be given a real name later. */
    public Component label() {
        return Component.translatable(LANG_PREFIX + id());
    }

    public static RotarySwitchStyle of(String id, RotarySwitchStyle fallback) {
        for (RotarySwitchStyle style : values())
            if (style.id().equals(id))
                return style;
        return fallback;
    }
}
