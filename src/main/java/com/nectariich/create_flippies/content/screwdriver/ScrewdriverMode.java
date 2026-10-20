package com.nectariich.create_flippies.content.screwdriver;

import java.util.Locale;

import com.nectariich.create_flippies.CreateFlippies;

import net.minecraft.network.chat.Component;

/**
 * What the screwdriver does with a click, and which rulings it offers while doing
 * it.
 *
 * The modes are one tool rather than several because they are used in sequence on
 * the same surface: rule a face, rule a cell of it finer, fill the cells, then say
 * the result is finished and is not to be mined. Splitting them into separate items
 * would mean four hotbar slots and a swap between every click.
 *
 * The rulings are held here rather than in an enum of their own. They are a plain
 * list of column and row counts, they differ per mode, and the pair is what the
 * item stores and what the grid store already speaks in; an enum would add a name
 * for every pair, a lang key per name, and a migration the first time the list
 * changes, in exchange for nothing.
 *
 * Mode 3 drops the sixteen-by-sixteen ruling and gains the four one-track
 * options. Sixteen tracks across a cell that may already be a quarter of a block
 * gives cells of a quarter of a pixel, which nothing in the catalogue fits and
 * nobody can aim at; a row or column of three or four, on the other hand, is
 * exactly what a cell wants once it is no longer square.
 */
public enum ScrewdriverMode {

    /**
     * Do nothing at all: every click belongs to whatever the player is holding, and
     * the tool is only carried.
     *
     * It is worth a mode because the tool is worth carrying while doing something
     * else. Break protection is a property of holding a screwdriver rather than of
     * any one mode, so a tool set to this still refuses to mine a protected box —
     * which is the one reason to have it in hand while building the rest of the room.
     */
    NONE(new int[][] {}),

    /** Rule the whole face of the block clicked, and mount into one of its cells. */
    FREE_PLACEMENT(new int[][] { { 2, 1 }, { 1, 2 }, { 2, 2 }, { 3, 3 }, { 2, 4 }, { 4, 4 }, { 5, 5 }, { 16, 16 } }),

    /** Pick two corners to the pixel and leave a constraining grid between them. */
    DEFINE_GRID(new int[][] {}),

    /**
     * Pick two corners and build whatever {@link DynamicBlockKind} is selected
     * between them, on a plane that may be turned off the cardinals.
     *
     * It rules nothing, so it offers no divisions: a dynamic block states its own
     * edges the way a spanned control does, and there is no cell to centre anything
     * in. What it carries that no other mode does is a yaw, because it is the only
     * gesture that can build on a surface the world does not already have.
     */
    DEFINE_DYNAMIC_BLOCK(new int[][] {}),

    /**
     * Change a panel that is already built: take a tile out of it, put one back,
     * drill a hole through it, or move one of its edges.
     *
     * The other half of the gesture that builds panels, and a mode rather than a
     * fourth dynamic kind because it is aimed differently from all of them. A panel
     * is built by naming two corners; it is edited by pointing at the panel itself —
     * or at the gap where part of it used to be, which is a click that hits no block
     * at all. See {@link com.nectariich.create_flippies.content.panel.PanelEdit}.
     *
     * It rules nothing, so it offers no divisions: what a click covers is a tile of
     * the panel's own lattice or a square of the size dialled into the tool, neither
     * of which is a cell of anything.
     */
    EDIT_PANEL(new int[][] {}),

    /** Rule one cell of an existing constraining grid, and mount into one of its cells. */
    CONSTRAINED_PLACEMENT(new int[][] { { 2, 1 }, { 1, 2 }, { 2, 2 }, { 3, 3 }, { 2, 4 }, { 4, 4 }, { 5, 5 }, { 3, 1 },
            { 4, 1 }, { 1, 3 }, { 1, 4 } }),

    /**
     * Pick two blocks and protect the box between them from this tool's own left
     * click; left-click a protected block to give it back.
     *
     * It protects blocks and says nothing about the instruments mounted on them.
     * Unscrewing one control off a protected wall is exactly the gesture the tool
     * exists for, and stopping it made the protection cost more than the mistake it
     * was guarding against; {@link #PROTECT_CONTROLS} is how a single instrument is
     * kept instead.
     *
     * It rules nothing, so it offers no divisions. It is a mode of the screwdriver
     * rather than an item of its own because it is the same gesture on the same
     * surface as the rest — two corners and a click — and because what it protects
     * is what this tool was used to build.
     */
    DEFINE_BREAK_PROTECTION(new int[][] {}),

    /**
     * Right-click a mounted control to protect it from being unscrewed; left-click
     * one to release it.
     *
     * One click per control rather than a box, because that is the grain the problem
     * has. A panel is worked on a control at a time and the thing worth keeping is
     * the one that is already right — a gauge dressed with its printing, a switch
     * wired to something — while everything around it is still moving.
     */
    PROTECT_CONTROLS(new int[][] {}),

    /**
     * Paint a picture onto a large panel: pick two corners on the plate and the
     * picture chosen in the menu is fitted between them; left-click a picture to take
     * it off again.
     *
     * A mode of the screwdriver because it is the tool's own gesture (two corners on
     * a surface) on the surfaces the tool builds, and because paint is the last thing
     * done to a panel the tool has just fitted out. It rules nothing.
     */
    PAINT_DECAL(new int[][] {}),

    // SCHEMATIC ARROWS
    /**
     * Raise schematic arrows and mimic strips off a large panel: click points to draw
     * one, stamp an imported SVG between two corners, or pick one up and move it;
     * left-click to take a point back or an arrow off. See
     * {@link com.nectariich.create_flippies.content.schematic_arrow.SchematicArrowActions}.
     */
    SCHEMATIC_ARROW(new int[][] {});
    // SCHEMATIC ARROWS end

    private static final String LANG_PREFIX = "gui." + CreateFlippies.MOD_ID + ".screwdriver.mode.";

    private final int[][] divisions;

    ScrewdriverMode(int[][] divisions) {
        this.divisions = divisions;
    }

    /** Whether a click of this mode mounts a control, which is what needs both hands. */
    public boolean places() {
        return this == FREE_PLACEMENT || this == CONSTRAINED_PLACEMENT;
    }

    /**
     * Whether a right click is the tool's at all, before what is in the other hand
     * is considered. Only {@link #NONE} gives it up outright; the placement modes go
     * on to ask whether that other hand holds a control.
     */
    public boolean claimsRightClick() {
        return this != NONE;
    }

    /**
     * Whether a left click belongs to the tool rather than to mining.
     *
     * The two ruling modes take it: it is how a grid or an area is given back, and
     * neither of them has anything else for that button to do. Editing a panel takes
     * it for the same reason — every edit has an opposite, and the button that undoes
     * a thing is the one that is already in the player's hand. Every other mode leaves
     * it, so that a player fitting out a wall can still clear the wall — which is what
     * break protection is for, since the click they wanted to keep is the same one
     * they wanted to stop hitting the finished part of the panel with.
     */
    public boolean claimsLeftClick() {
        return this == DEFINE_GRID || this == DEFINE_BREAK_PROTECTION || this == PROTECT_CONTROLS
                || this == EDIT_PANEL || this == PAINT_DECAL || this == SCHEMATIC_ARROW;
    }

    /** The rulings this mode offers, as {@code columns, rows} pairs. */
    public int[][] divisions() {
        return divisions;
    }

    public boolean allows(int columns, int rows) {
        for (int[] division : divisions)
            if (division[0] == columns && division[1] == rows)
                return true;
        return false;
    }

    /**
     * The ruling after the given one, wrapping. A pair this mode does not offer —
     * which is what a mode switch leaves behind — cycles to the first, so the
     * button always moves.
     */
    public int[] nextDivision(int columns, int rows) {
        for (int i = 0; i < divisions.length; i++)
            if (divisions[i][0] == columns && divisions[i][1] == rows)
                return divisions[(i + 1) % divisions.length];
        return defaultDivision();
    }

    /** The ruling a mode falls back to, and what a fresh screwdriver carries. */
    public int[] defaultDivision() {
        return divisions.length == 0 ? new int[] { 1, 1 } : divisions[0];
    }

    public ScrewdriverMode next() {
        return values()[(ordinal() + 1) % values().length];
    }

    public Component label() {
        return Component.translatable(LANG_PREFIX + name().toLowerCase(Locale.ROOT));
    }
}
