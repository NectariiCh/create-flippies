package com.nectariich.create_flippies.content.label;

import java.util.Locale;

import com.nectariich.create_flippies.CreateFlippies;
import com.nectariich.create_flippies.compat.Ids;

import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;

/**
 * The typefaces a panel label can be printed in.
 *
 * These are the fonts the game itself ships and nothing else. A font in Minecraft
 * is a resource — a set of glyph providers under {@code assets/<namespace>/font} —
 * so a face that is not in the pack cannot be named into existence from code; a
 * new one means shipping the glyphs for it. What that buys, though, is that a
 * resource pack adding {@code minecraft:default} in another face re-letters every
 * label in the world, which is the usual way anyone changes typeface in this game.
 *
 * There are two because there are two worth having on a panel: the proportional
 * default, and the fixed-pitch unicode face, whose even advance is what makes a
 * column of legends line up under each other. The game also ships the two runic
 * alphabets — the enchanting table's and the illagers' — and they are deliberately
 * not offered: a legend nobody can read is not a legend.
 *
 * The font travels as a {@link net.minecraft.network.chat.Style}, which is how
 * vanilla addresses fonts everywhere else — signs, books, the chat — so a label
 * gets bold and italic from the same object rather than from a second mechanism of
 * ours.
 */
public enum PanelLabelFont {

    /** The game's own lettering, proportionally spaced. */
    DEFAULT("default"),

    /** The fixed-pitch unicode face: squarer, and every glyph the same width. */
    UNIFORM("uniform");

    /** What a label is printed in until somebody changes it. */
    public static final PanelLabelFont FALLBACK = DEFAULT;

    private static final String LANG_PREFIX = "gui." + CreateFlippies.MOD_ID + ".panel_label.font.";

    private final ResourceLocation font;

    PanelLabelFont(String path) {
        this.font = Ids.of("minecraft", path);
    }

    /** The font resource, as a {@code Style} names it. */
    public ResourceLocation font() {
        return font;
    }

    public String id() {
        return name().toLowerCase(Locale.ROOT);
    }

    public Component label() {
        return Component.translatable(LANG_PREFIX + id());
    }

    /** The next one round, so one button can cycle them. */
    public PanelLabelFont next() {
        PanelLabelFont[] all = values();
        return all[(ordinal() + 1) % all.length];
    }

    /**
     * The font stored under {@code id}, or {@link #FALLBACK} for anything else —
     * including the empty string a label saved before fonts existed reads back as.
     */
    public static PanelLabelFont byId(String id) {
        for (PanelLabelFont font : values())
            if (font.id().equals(id))
                return font;
        return FALLBACK;
    }

    /** Guards a value off the wire, where the ordinal is whatever was sent. */
    public static PanelLabelFont byOrdinal(int ordinal) {
        PanelLabelFont[] all = values();
        return ordinal >= 0 && ordinal < all.length ? all[ordinal] : FALLBACK;
    }
}
