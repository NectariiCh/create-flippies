package com.nectariich.create_flippies.content.screwdriver;

import java.util.Locale;

import com.nectariich.create_flippies.CreateFlippies;

import net.minecraft.network.chat.Component;

/**
 * What a {@link ScrewdriverMode#DEFINE_DYNAMIC_BLOCK} click builds.
 *
 * The mode is one gesture — two corners on a surface, and a facing that may be
 * turned off the cardinals — and several things want it. A panel is the first, a
 * printed legend is the obvious second, and the reason to name the family now
 * rather than write a mode per member is that the gesture is the expensive part:
 * the corner picking, the projection onto the plane, the preview and the refusals
 * are the same whatever comes out at the end, and a second mode would be a second
 * copy of all of it that would then have to be kept in step.
 *
 * A kind is deliberately not a block or an item. What the click produces may be one
 * block, a rectangle of them, or something mounted into a host that is not a block
 * at all, and the only thing they have in common is the rectangle they were drawn
 * with.
 */
public enum DynamicBlockKind {

    /**
     * A large panel: a plate over the whole rectangle, cut into one block per cell,
     * every cell wearing whatever material it is later given.
     */
    PANEL,

    /**
     * A large panel at forty-five degrees filling the corner between two straight
     * ones, derived from them rather than drawn: see
     * {@link com.nectariich.create_flippies.content.panel.PanelChamfer}. The two
     * clicks pick panels instead of points, which is a different gesture wearing the
     * same mode — worth it because everything around the gesture, the mode, the
     * pending state, the preview and the refusals, is shared.
     */
    CHAMFER,

    /**
     * A panel standing square to the edge of another and hinged on it, derived from
     * that edge rather than drawn: see
     * {@link com.nectariich.create_flippies.content.panel.PanelFold}. The first click
     * picks an edge and a place along it, the second the opposite corner, so the size
     * is the player and the plane is the geometry.
     */
    FOLD;

    private static final String LANG_PREFIX = "gui." + CreateFlippies.MOD_ID + ".screwdriver.dynamic.";

    /**
     * Whether the player chooses the facing for this kind.
     *
     * A chamfer is always turned and never asked about: its facing is the one between
     * the two panels it joins, and there is nothing for a yaw button to say.
     */
    public boolean allowsDiagonal() {
        return this == PANEL;
    }

    /** Whether the two clicks pick existing panels rather than points in space. */
    public boolean picksPanels() {
        return this == CHAMFER;
    }

    /** Whether the first click picks an edge of an existing panel to build from. */
    public boolean picksEdge() {
        return this == FOLD;
    }

    public DynamicBlockKind next() {
        return values()[(ordinal() + 1) % values().length];
    }

    public Component label() {
        return Component.translatable(LANG_PREFIX + name().toLowerCase(Locale.ROOT));
    }
}
