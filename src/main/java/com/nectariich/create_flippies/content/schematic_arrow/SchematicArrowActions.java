package com.nectariich.create_flippies.content.schematic_arrow;

import java.util.ArrayList;
import java.util.List;

import com.nectariich.create_flippies.CreateFlippies;
import com.nectariich.create_flippies.content.decal.DecalSelection;
import com.nectariich.create_flippies.content.decal.PanelDecal;
import com.nectariich.create_flippies.content.panel.LargePanelBlockEntity;
import com.nectariich.create_flippies.content.screwdriver.GridCorner;
import com.nectariich.create_flippies.content.screwdriver.ScrewdriverActions;
import com.nectariich.create_flippies.content.screwdriver.ScrewdriverAim;
import com.nectariich.create_flippies.content.screwdriver.ScrewdriverSettings;
import com.nectariich.create_flippies.foundation.panel.PanelPlane;

import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;

/**
 * What a screwdriver click does in the schematic arrow mode, once the server has it.
 *
 * Reached through the same click packet as every other mode, so the player has
 * already been checked for holding the tool and for being within reach of the
 * block clicked. Every point is snapped here from the click rather than taken from
 * the client, and every arrow is checked against its limits before any cell takes
 * it, so a client can ask for nothing a player could not draw.
 *
 * <ul>
 * <li>Drawing: each right click adds a point; a right click on the last point again
 * finishes the arrow. A left click takes the last point back.</li>
 * <li>Stamping: two right clicks are the corners the imported picture is fitted
 * between, the same gesture as painting it.</li>
 * <li>Moving: a right click picks an arrow up by the point clicked, a second puts that
 * point where it lands. A left click puts it down again unmoved.</li>
 * <li>With nothing in progress, a left click takes the arrow under the crosshair off,
 * from every cell it crosses.</li>
 * </ul>
 */
public final class SchematicArrowActions {

    private static final String MESSAGE_PREFIX = "message." + CreateFlippies.MOD_ID + ".screwdriver.arrow_";

    private SchematicArrowActions() {}

    /** A right click. */
    public static void use(ServerPlayer player, ServerLevel level, ItemStack tool, ScrewdriverSettings settings,
            BlockPos clicked, Direction face, Vec3 point) {
        if (LargePanelBlockEntity.panelAt(level, clicked, point) == null) {
            refuse(player, "not_a_panel");
            return;
        }
        ScrewdriverAim.Surface surface = ScrewdriverAim.surfaceAt(level, clicked, face, point,
                player.getEyePosition());
        ArrowSettings arrows = ArrowSettings.read(tool);
        switch (arrows.action()) {
            case DRAW -> draw(player, level, tool, arrows, surface, clicked, point);
            case STAMP -> stamp(player, level, tool, settings, arrows, surface, clicked, point);
            case MOVE -> move(player, level, tool, arrows, surface, clicked, point);
        }
    }

    /** A left click. */
    public static void remove(ServerPlayer player, ServerLevel level, ItemStack tool, BlockPos clicked,
            Direction face, Vec3 point) {
        ArrowDraft draft = ArrowDraft.read(tool);
        if (draft != null) {
            ArrowDraft less = draft.less();
            ArrowDraft.write(tool, less);
            confirm(player, less == null ? "cancelled" : "point_removed", less == null ? 0 : less.pointCount());
            return;
        }
        if (ArrowGrab.read(tool) != null) {
            ArrowGrab.write(tool, null);
            confirm(player, "move_cancelled");
            return;
        }
        if (ScrewdriverSettings.read(tool).corner() != null && ArrowSettings.read(tool).action() == ArrowAction.STAMP) {
            ScrewdriverSettings.setCorner(tool, null);
            confirm(player, "cancelled", 0);
            return;
        }
        LargePanelBlockEntity cell = LargePanelBlockEntity.cellAt(level, clicked);
        ScrewdriverAim.Surface surface = ScrewdriverAim.surfaceAt(level, clicked, face, point,
                player.getEyePosition());
        PanelArrow arrow = cell == null ? null : cell.arrowAt(surface.plane(), surface.depth(), point);
        if (arrow == null) {
            refuse(player, "none_here");
            return;
        }
        // Break protection guards the blocks, not the arrows on them; arrow mode
        // claims the left click, so the block under it is never broken here
        takeOff(level, cell, arrow);
        level.playSound(null, clicked, SoundEvents.ITEM_FRAME_REMOVE_ITEM, SoundSource.BLOCKS, 1, 1);
        confirm(player, "removed");
    }

    // Drawing

    /** Where a click on a surface puts the next point of the arrow, given the points before it. */
    public static int[] pointFor(ScrewdriverAim.Surface surface, Vec3 point, ArrowDraft draft, ArrowSnap snap) {
        PanelPlane plane = surface.plane();
        int[] previous = draft == null || !draft.isOn(plane, depthOf(surface)) ? null : draft.last();
        return ArrowSnapping.snap(previous, plane.u(point), plane.v(point), surface.originU(), surface.originV(),
                snap);
    }

    public static int depthOf(ScrewdriverAim.Surface surface) {
        return (int) Math.round(surface.depth() * PanelPlane.UNITS_PER_BLOCK);
    }

    private static void draw(ServerPlayer player, ServerLevel level, ItemStack tool, ArrowSettings settings,
            ScrewdriverAim.Surface surface, BlockPos clicked, Vec3 point) {
        int depth = depthOf(surface);
        ArrowDraft draft = ArrowDraft.read(tool);
        int[] picked = pointFor(surface, point, draft, settings.snap());
        if (draft == null || !draft.isOn(surface.plane(), depth)) {
            ArrowDraft.write(tool, new ArrowDraft(surface.plane(), depth, picked));
            confirm(player, draft == null ? "started" : "restarted");
            return;
        }
        int[] last = draft.last();
        if (picked[0] != last[0] || picked[1] != last[1]) {
            if (draft.pointCount() >= PanelArrow.MAX_POINTS) {
                refuse(player, "too_many_points", PanelArrow.MAX_POINTS);
                return;
            }
            ArrowDraft.write(tool, draft.plus(picked));
            confirm(player, "point", draft.pointCount() + 1);
            return;
        }
        // The last point clicked again: the arrow is finished
        if (draft.pointCount() < 2) {
            refuse(player, "too_short");
            return;
        }
        PanelArrow arrow = PanelArrow.drawn(level.random.nextLong(), draft.plane(), draft.depth(), settings.style(),
                draft.points());
        if (put(player, level, arrow)) {
            ArrowDraft.write(tool, null);
            level.playSound(null, clicked, SoundEvents.ITEM_FRAME_PLACE, SoundSource.BLOCKS, 1, 1);
            confirm(player, "done", arrow.pointCount());
        }
    }

    // Stamping

    private static void stamp(ServerPlayer player, ServerLevel level, ItemStack tool, ScrewdriverSettings settings,
            ArrowSettings arrows, ScrewdriverAim.Surface surface, BlockPos clicked, Vec3 point) {
        DecalSelection selection = DecalSelection.read(tool);
        if (!selection.hasArt()) {
            refuse(player, "no_art");
            return;
        }
        GridCorner picked = GridCorner.snapped(surface.plane(), point, ScrewdriverActions.PAINT_STEP);
        GridCorner first = settings.corner();
        if (first == null || !first.sharesPlaneWith(picked)) {
            ScrewdriverSettings.setCorner(tool, picked);
            confirm(player, "first_corner");
            return;
        }
        if (first.u() == picked.u() || first.v() == picked.v()) {
            refuse(player, "too_short");
            return;
        }
        int quarters = settings.turn() & 3;
        int[] rectangle = PanelDecal.fit(first.u(), first.v(), picked.u(), picked.v(), selection.width(),
                selection.height(), quarters);
        PanelArrow arrow = PanelArrow.stamped(level.random.nextLong(), surface.plane(), depthOf(surface),
                arrows.style(), selection.art(), rectangle, quarters);
        if (put(player, level, arrow)) {
            ScrewdriverSettings.setCorner(tool, null);
            level.playSound(null, clicked, SoundEvents.ITEM_FRAME_PLACE, SoundSource.BLOCKS, 1, 1);
            confirm(player, "stamped", GridCorner.pixels(rectangle[2] - rectangle[0]),
                    GridCorner.pixels(rectangle[3] - rectangle[1]));
        }
    }

    // Moving

    private static void move(ServerPlayer player, ServerLevel level, ItemStack tool, ArrowSettings settings,
            ScrewdriverAim.Surface surface, BlockPos clicked, Vec3 point) {
        int depth = depthOf(surface);
        ArrowGrab grab = ArrowGrab.read(tool);
        if (grab == null) {
            LargePanelBlockEntity cell = LargePanelBlockEntity.cellAt(level, clicked);
            PanelArrow arrow = cell == null ? null : cell.arrowAt(surface.plane(), surface.depth(), point);
            if (arrow == null) {
                refuse(player, "none_here");
                return;
            }
            int[] at = pointFor(surface, point, null, settings.snap() == ArrowSnap.FREE ? ArrowSnap.FREE
                    : ArrowSnap.GRID);
            ArrowGrab.write(tool, new ArrowGrab(arrow.id(), clicked.immutable(), surface.plane(), depth, at[0], at[1]));
            confirm(player, "grabbed");
            return;
        }
        if (!grab.plane().equals(surface.plane()) || grab.depth() != depth) {
            refuse(player, "move_other_surface");
            return;
        }
        LargePanelBlockEntity from = LargePanelBlockEntity.cellAt(level, grab.cell());
        PanelArrow arrow = from == null ? null : from.arrow(grab.id());
        if (arrow == null) {
            ArrowGrab.write(tool, null);
            refuse(player, "none_here");
            return;
        }
        int[] to = pointFor(surface, point, null, settings.snap() == ArrowSnap.FREE ? ArrowSnap.FREE : ArrowSnap.GRID);
        PanelArrow moved = arrow.moved(to[0] - grab.u(), to[1] - grab.v());
        if (moved.equals(arrow)) {
            ArrowGrab.write(tool, null);
            confirm(player, "move_cancelled");
            return;
        }
        PanelArrow.Problem problem = moved.check();
        if (!problem.ok()) {
            refuse(player, problem.key(), PanelArrow.MAX_CELLS);
            return;
        }
        takeOff(level, from, arrow);
        if (!put(player, level, moved)) {
            // Somewhere it will not go: back where it was
            put(player, level, arrow);
            return;
        }
        ArrowGrab.write(tool, null);
        level.playSound(null, clicked, SoundEvents.ITEM_FRAME_PLACE, SoundSource.BLOCKS, 1, 1);
        confirm(player, "moved");
    }

    // Cells

    /**
     * Puts an arrow into every cell it crosses that has plate on its surface, after
     * checking it; whether any cell took it. Says why when none did.
     */
    static boolean put(Player player, ServerLevel level, PanelArrow arrow) {
        PanelArrow.Problem problem = arrow.check();
        if (!problem.ok()) {
            refuse(player, problem.key(), problem == PanelArrow.Problem.TOO_MANY_POINTS ? PanelArrow.MAX_POINTS
                    : PanelArrow.MAX_CELLS);
            return false;
        }
        int placed = 0;
        boolean full = false;
        for (BlockPos cell : arrow.cells()) {
            LargePanelBlockEntity held = LargePanelBlockEntity.cellAt(level, cell);
            if (held == null)
                continue;
            if (held.addArrow(arrow))
                placed++;
            else
                full |= held.arrows().size() >= PanelArrow.MAX_PER_CELL;
        }
        if (placed == 0)
            refuse(player, full ? "full" : "not_a_panel");
        return placed > 0;
    }

    /** Takes an arrow off the cell it was found in and every other cell it crosses. */
    static void takeOff(ServerLevel level, LargePanelBlockEntity found, PanelArrow arrow) {
        found.removeArrow(arrow.id());
        List<BlockPos> cells = new ArrayList<>(arrow.cells());
        for (BlockPos other : cells) {
            LargePanelBlockEntity held = LargePanelBlockEntity.cellAt(level, other);
            if (held != null)
                held.removeArrow(arrow.id());
        }
    }

    // Saying so

    private static void refuse(Player player, String key, Object... arguments) {
        player.displayClientMessage(
                Component.translatable(MESSAGE_PREFIX + key, arguments).withStyle(ChatFormatting.RED), true);
    }

    private static void confirm(Player player, String key, Object... arguments) {
        player.displayClientMessage(
                Component.translatable(MESSAGE_PREFIX + key, arguments).withStyle(ChatFormatting.AQUA), true);
    }
}
