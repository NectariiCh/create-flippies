package com.nectariich.create_flippies.content.screwdriver;

import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

import com.nectariich.create_flippies.CreateFlippies;
import com.nectariich.create_flippies.content.decal.DecalSelection;
import com.nectariich.create_flippies.content.decal.PanelDecal;
import com.nectariich.create_flippies.content.label.PanelLabelControl;
import com.nectariich.create_flippies.content.label.PanelLabelStacking;
import com.nectariich.create_flippies.content.panel.PanelAim;
import com.nectariich.create_flippies.content.panel.PanelPoint;
import com.nectariich.create_flippies.content.panel.LargePanelBlock;
import com.nectariich.create_flippies.content.panel.LargePanelBlockEntity;
import com.nectariich.create_flippies.content.panel.PanelChamfer;
import com.nectariich.create_flippies.content.panel.PanelEdit;
import com.nectariich.create_flippies.content.panel.PanelEditKind;
import com.nectariich.create_flippies.content.panel.PanelFold;
import com.nectariich.create_flippies.content.schematic_arrow.SchematicArrowActions;
import com.nectariich.create_flippies.foundation.panel.PanelDefinition;
import com.nectariich.create_flippies.foundation.panel.PanelPlane;
import com.nectariich.create_flippies.foundation.SurfaceMount;
import com.nectariich.create_flippies.foundation.control.ControlMount;
import com.nectariich.create_flippies.foundation.control.ControlType;
import com.nectariich.create_flippies.foundation.control.ControlTypes;
import com.nectariich.create_flippies.foundation.control.ControlRackHolder;
import com.nectariich.create_flippies.foundation.control.HostedControl;
import com.nectariich.create_flippies.foundation.control.PlacementRefusal;
import com.nectariich.create_flippies.foundation.control.SpannedControlType;
import com.nectariich.create_flippies.foundation.grid.FaceRegion;
import com.nectariich.create_flippies.foundation.grid.GridIndex;
import com.nectariich.create_flippies.foundation.grid.GridStore;
import com.nectariich.create_flippies.foundation.grid.PlacementGrid;
import com.nectariich.create_flippies.foundation.protection.BreakArea;
import com.nectariich.create_flippies.foundation.protection.ProtectionStore;
import com.nectariich.create_flippies.registry.ModBlocks;

import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

/**
 * What a screwdriver click actually does, once the server has it.
 *
 * Everything here runs on the server and only on the server. The client's part is
 * to notice the click, cancel the vanilla interaction and say where the crosshair
 * was; the world is changed here, so a client that lies about the aim gets a
 * refusal rather than a placement, and the mounted control, the grid and the
 * pending corner all come back down the normal channels.
 *
 * The three modes share the aim resolution and nothing else, which is why they are
 * three methods rather than one with branches: mode 2 works in whole pixels on a
 * plane that may cross many blocks, and the two placement modes work in cell
 * centres inside one block. Trying to unify those produced a method whose every
 * line was conditional.
 */
public final class ScrewdriverActions {

    private static final String MESSAGE_PREFIX = "message." + CreateFlippies.MOD_ID + ".screwdriver.";
    private static final String GRID_PREFIX = "message." + CreateFlippies.MOD_ID + ".grid.";

    private ScrewdriverActions() {}

    /**
     * A right click with the screwdriver: rule a rectangle, or mount a control.
     *
     * {@code panel} is the block whose face was clicked, {@code face} that face and
     * {@code point} the hit in world block units.
     */
    public static void use(ServerPlayer player, InteractionHand hand, BlockPos panel, Direction face, Vec3 point) {
        ItemStack stack = player.getItemInHand(hand);
        if (!ScrewdriverItem.isScrewdriver(stack))
            return;
        ScrewdriverSettings settings = ScrewdriverSettings.read(stack);
        ServerLevel level = player.serverLevel();
        switch (settings.mode()) {
            case DEFINE_GRID -> pickCorner(player, level, stack, settings, panel, face, point);
            case DEFINE_DYNAMIC_BLOCK -> pickDynamicCorner(player, level, stack, settings, panel, point);
            case DEFINE_BREAK_PROTECTION -> pickArea(player, level, stack, settings, panel);
            case PROTECT_CONTROLS -> lockControl(player, level, panel, point, true);
            // The click's own block and point are not used: an edit is aimed at a
            // panel, which may be the one the crosshair went through rather than the
            // one it landed on. See PanelEdit
            case EDIT_PANEL -> editPanel(player, level, stack, settings, false);
            case PAINT_DECAL -> paint(player, level, stack, settings, panel, face, point);
            // SCHEMATIC ARROWS
            case SCHEMATIC_ARROW -> SchematicArrowActions.use(player, level, stack, settings, panel, face, point);
            // The idle mode never claims the click, so a packet asking for one is a
            // client that disagrees with this build about what the tool does
            case NONE -> { }
            default -> mount(player, level, hand, stack, settings, panel, face, point);
        }
    }

    /**
     * A left click with the screwdriver, in the modes that claim one: take away the
     * grid or the protected area under the crosshair.
     *
     * The placement modes never reach here — see
     * {@link ScrewdriverMode#claimsLeftClick}. They leave the button to mining and to
     * unscrewing a single control, which is what makes break protection worth having.
     */
    public static void remove(ServerPlayer player, BlockPos clicked, Direction face, Vec3 point) {
        InteractionHand hand = ScrewdriverItem.heldBy(player);
        if (hand == null)
            return;
        ServerLevel level = player.serverLevel();
        ItemStack tool = player.getItemInHand(hand);
        ScrewdriverSettings settings = ScrewdriverSettings.read(tool);
        switch (settings.mode()) {
            case DEFINE_GRID -> removeGrid(player, level, clicked, face, point);
            case DEFINE_BREAK_PROTECTION -> removeArea(player, level, clicked);
            case PROTECT_CONTROLS -> lockControl(player, level, clicked, point, false);
            case EDIT_PANEL -> editPanel(player, level, tool, settings, true);
            case PAINT_DECAL -> unpaint(player, level, clicked, face, point);
            // SCHEMATIC ARROWS
            case SCHEMATIC_ARROW -> SchematicArrowActions.remove(player, level, tool, clicked, face, point);
            default -> { }
        }
    }

    /**
     * Takes away the innermost grid under the crosshair, and only that one.
     *
     * A grid with grids in it is refused rather than taken apart, for the reason
     * {@code GridIndex.removeOne} gives — and because the click that removes a ruling
     * is the same click that removes the ruling underneath it a moment later, so
     * "everything nested inside" is a lot of work to lose to one mistimed press.
     */
    private static void removeGrid(ServerPlayer player, ServerLevel level, BlockPos panel, Direction face, Vec3 point) {
        GridStore store = GridStore.of(level);
        PanelPlane surface = ScrewdriverAim.surfaceAt(level, panel, face, point, player.getEyePosition()).plane();
        ScrewdriverAim.Constraint aimed = ScrewdriverAim.innermostAt(store.index(), panel, surface, point);
        if (aimed == null) {
            refuse(player, "no_grid_here");
            return;
        }
        int nested = store.index().children(aimed.grid().id()).size();
        if (nested > 0) {
            refuse(player, "grid_has_children", nested);
            return;
        }
        if (store.cut(level, aimed.grid().id()))
            confirm(player, "grid_removed");
    }

    /**
     * Protects the control under the crosshair from being unscrewed, or releases it.
     *
     * Aimed at a control rather than at a block, which is what makes it worth having
     * beside the area protection: a panel carries a dozen instruments in one cell and
     * the one that is finished is rarely the whole cell.
     */
    private static void lockControl(ServerPlayer player, ServerLevel level, BlockPos clicked, Vec3 point,
            boolean lock) {
        if (!(level.getBlockEntity(clicked) instanceof ControlRackHolder host)) {
            refuse(player, "no_control_there");
            return;
        }
        HostedControl control = host.controlAt(point.subtract(Vec3.atLowerCornerOf(clicked)));
        if (control == null) {
            refuse(player, "no_control_there");
            return;
        }
        if (control.isLocked() == lock) {
            refuse(player, lock ? "control_already_protected" : "control_not_protected");
            return;
        }
        host.rack().lock(control, lock);
        confirm(player, lock ? "control_protected" : "control_released");
    }

    /** Gives back the smallest protected area over the clicked block, and only that one. */
    private static void removeArea(ServerPlayer player, ServerLevel level, BlockPos clicked) {
        ProtectionStore store = ProtectionStore.of(level);
        BreakArea area = store.index().at(clicked);
        if (area == null) {
            refuse(player, "no_area_here");
            return;
        }
        if (store.release(level, area.id()))
            confirm(player, "area_removed");
    }

    // Mode 2

    private static void pickCorner(ServerPlayer player, ServerLevel level, ItemStack stack,
            ScrewdriverSettings settings, BlockPos panel, Direction face, Vec3 point) {
        PanelPlane plane = ScrewdriverAim.surfaceAt(level, panel, face, point, player.getEyePosition()).plane();
        GridCorner picked = GridCorner.snapped(plane, point);
        GridCorner first = settings.corner();

        if (first == null) {
            ScrewdriverSettings.setCorner(stack, picked);
            confirm(player, "first_corner", picked.pixelU(), picked.pixelV());
            return;
        }
        // A click on another surface is far more likely to be the start of a new
        // rectangle than a mistake, so it replaces the corner instead of refusing
        if (!first.sharesPlaneWith(picked)) {
            ScrewdriverSettings.setCorner(stack, picked);
            confirm(player, "corner_moved", picked.pixelU(), picked.pixelV());
            return;
        }
        // Pixels rather than the finer unit the corner is held in: a grid is ruled in
        // whole pixels, so two corners under one apart span nothing it could hold
        if (first.pixelU() == picked.pixelU() || first.pixelV() == picked.pixelV()) {
            refuse(player, "flat_rectangle");
            return;
        }

        int minU = Math.min(first.pixelU(), picked.pixelU());
        int minV = Math.min(first.pixelV(), picked.pixelV());
        int maxU = Math.max(first.pixelU(), picked.pixelU());
        int maxV = Math.max(first.pixelV(), picked.pixelV());
        PlacementGrid grid = ruled(level, plane, minU, minV, maxU, maxV, settings);
        if (grid == null) {
            refuse(player, "not_in_one_cell");
            return;
        }

        PlacementGrid.Problem problem = GridStore.of(level).place(level, grid);
        if (!problem.ok()) {
            refuse(player, problem);
            return;
        }
        ScrewdriverSettings.setCorner(stack, null);
        confirm(player, "grid_placed", maxU - minU, maxV - minV);
    }

    /**
     * The grid to place for a finished rectangle: nested in whichever cell holds it
     * if any grid does, top level otherwise, and null if a grid holds the rectangle
     * but cannot contain it in one cell.
     *
     * That last case is refused rather than quietly made top level. A rectangle
     * straddling two cells of an existing grid is almost certainly a misclick, and
     * turning it into a grid overlapping the one already there would leave two
     * rulings claiming the same pixels with nothing to choose between them.
     */
    private static PlacementGrid ruled(ServerLevel level, PanelPlane plane, int minU, int minV, int maxU, int maxV,
            ScrewdriverSettings settings) {
        UUID id = UUID.randomUUID();
        GridIndex index = GridStore.of(level).index();
        FaceRegion rectangle = FaceRegion.undivided(plane, minU, minV, maxU, maxV);
        Vec3 centre = rectangle.point((rectangle.minU() + rectangle.maxU()) / 2,
                (rectangle.minV() + rectangle.maxV()) / 2, 0);

        PlacementGrid provisional = PlacementGrid.top(id, plane, minU, minV, maxU, maxV, settings.columns(),
                settings.rows());
        BlockPos centreBlock = provisional.hostBlock((minU + maxU) / 2.0, (minV + maxV) / 2.0);
        ScrewdriverAim.Constraint parent = ScrewdriverAim.innermostAt(index, centreBlock, plane, centre);
        if (parent == null)
            return provisional;

        PlacementGrid nested = PlacementGrid.nested(id, parent.grid(), parent.cell(), minU, minV, maxU, maxV,
                settings.columns(), settings.rows());
        return nested.check(parent.grid()).ok() ? nested : null;
    }

    // Mode 4

    /**
     * A click in the protection mode: pick a block, or protect the box between it
     * and the block picked before.
     *
     * Blocks rather than points on a face, because what is being described is which
     * blocks may not be broken and a block is a block from every side. Clicking the
     * same block twice is therefore not a mistake to refuse — it protects that one
     * block, which is exactly what someone pointing at a single instrument means.
     */
    private static void pickArea(ServerPlayer player, ServerLevel level, ItemStack tool, ScrewdriverSettings settings,
            BlockPos clicked) {
        BlockPos first = settings.areaCorner();
        if (first == null) {
            ScrewdriverSettings.setAreaCorner(tool, clicked);
            confirm(player, "area_first_corner", clicked.getX(), clicked.getY(), clicked.getZ());
            return;
        }

        BreakArea area = BreakArea.between(UUID.randomUUID(), first, clicked);
        if (area.isTooLarge()) {
            refuse(player, "area_too_large", BreakArea.MAX_SPAN);
            return;
        }
        if (!ProtectionStore.of(level).protect(level, area))
            return;
        ScrewdriverSettings.setAreaCorner(tool, null);
        confirm(player, "area_defined", area.spanX(), area.spanY(), area.spanZ());
    }

    // Modes 1 and 3

    private static void mount(ServerPlayer player, ServerLevel level, InteractionHand hand, ItemStack tool,
            ScrewdriverSettings settings, BlockPos panel, Direction face, Vec3 point) {
        InteractionHand holding = ScrewdriverItem.otherHand(hand);
        ControlType type = ControlTypes.forItem(player.getItemInHand(holding));
        if (type == null) {
            refuse(player, "no_control_held");
            return;
        }
        if (type instanceof SpannedControlType spanned) {
            span(player, level, holding, tool, settings, spanned, panel, face, point);
            return;
        }

        ScrewdriverAim.Surface surface = ScrewdriverAim.surfaceAt(level, panel, face, point,
                player.getEyePosition());
        ScrewdriverAim.Target target = ScrewdriverAim.resolve(GridStore.of(level).index(), settings, surface, panel,
                face, point);
        if (target == null) {
            refuse(player, settings.mode() == ScrewdriverMode.CONSTRAINED_PLACEMENT ? "no_grid_here" : "off_face");
            return;
        }

        int turn = turnFor(surface.plane(), player, settings);
        ScrewdriverAim.Placement placement = ScrewdriverAim.placementFor(target, surface.depth(), turn);

        place(player, level, holding, type.create(player.getItemInHand(holding)), placement);
    }

    /**
     * Which way up a control goes on: the turn that reads upright where there is
     * one, plus whatever the player has dialled in.
     *
     * The upright turn is worked out from the cardinal facing the plate is nearest,
     * because that is all it is for — a wall needs none and a floor needs the front
     * pointed at whoever placed it, and an eighth of a turn either way changes
     * neither answer.
     */
    private static int turnFor(PanelPlane surface, ServerPlayer player, ScrewdriverSettings settings) {
        Direction mountFace = surface.face().getOpposite();
        return Math.floorMod(SurfaceMount.rotationForPlacement(mountFace, player.getDirection()) + settings.turn(), 4);
    }

    // The dynamic block gesture

    /**
     * A click in the dynamic block mode: pick the first corner on a face, or resolve
     * the second against the three planes through it and build.
     *
     * The two clicks are not the same gesture and deliberately do not share a code
     * path. The first is a point on a block face, which is the only way to say
     * unambiguously where a panel starts. The second is a direction — whichever of
     * the three planes through that point the crosshair reaches first — and needs no
     * block at all, which is what lets a panel stand free in the air.
     */
    private static void pickDynamicCorner(ServerPlayer player, ServerLevel level, ItemStack tool,
            ScrewdriverSettings settings, BlockPos clicked, Vec3 point) {
        if (settings.kind().picksPanels()) {
            pickChamferPanel(player, level, tool, settings, clicked, point);
            return;
        }
        if (settings.kind().picksEdge()) {
            pickFoldEdge(player, level, tool, settings, clicked, point);
            return;
        }
        if (settings.firstPoint() == null) {
            PanelPoint picked = PanelPoint.snapped(point);
            ScrewdriverSettings.setFirstPoint(tool, picked);
            confirm(player, "first_corner", pixels(picked.x()), pixels(picked.y()), pixels(picked.z()));
            return;
        }
        completeDynamic(player, level, tool, settings);
    }

    /**
     * A click in the chamfer gesture: name the panel to spring from, or name the one
     * to stop at and build.
     *
     * A first panel that has since been broken is treated as though it had never been
     * picked, and this click becomes the new first. The alternative is refusing a
     * click because of something the player already knows is gone.
     */
    private static void pickChamferPanel(ServerPlayer player, ServerLevel level, ItemStack tool,
            ScrewdriverSettings settings, BlockPos clicked, Vec3 point) {
        PanelDefinition here = LargePanelBlockEntity.panelAt(level, clicked, point);
        if (here == null) {
            refuse(player, "chamfer_not_a_panel");
            return;
        }

        BlockPos picked = settings.firstPanel();
        PanelDefinition from = picked == null ? null
                : LargePanelBlockEntity.panelAt(level, picked, Vec3.atCenterOf(picked));
        if (from == null) {
            ScrewdriverSettings.setFirstPanel(tool, clicked);
            confirm(player, "chamfer_first_panel");
            return;
        }

        PanelChamfer.Joint joint = PanelChamfer.between(from, here);
        if (!joint.problem().ok()) {
            refuse(player, "chamfer_" + joint.problem().key());
            return;
        }
        if (!completePanel(player, level, joint.panel()))
            return;
        ScrewdriverSettings.setFirstPanel(tool, null);
    }

    /**
     * A click in the fold gesture: name the edge to hinge on, or set the far corner and
     * build.
     *
     * The edge is remembered as the block that was clicked and the point on it, which
     * are the two pending fields the tool already has. Nothing else is needed: the panel
     * is looked up from the block and the edges from the point, so a first pick survives
     * anything that does not move the panel, and a panel that has since gone simply
     * makes this click the new first one.
     *
     * Which of the two edges the fold turns about is not settled here and is not stored:
     * it is decided by where the second click is aimed, so the same first pick can raise
     * a panel in either of the two planes square to the one clicked.
     */
    private static void pickFoldEdge(ServerPlayer player, ServerLevel level, ItemStack tool,
            ScrewdriverSettings settings, BlockPos clicked, Vec3 point) {
        PanelPoint held = settings.firstPoint();
        BlockPos picked = settings.firstPanel();
        PanelDefinition from = picked == null || held == null ? null
                : LargePanelBlockEntity.panelAt(level, picked, held.toVec());

        if (from == null) {
            if (LargePanelBlockEntity.panelAt(level, clicked, point) == null) {
                refuse(player, "fold_no_panel");
                return;
            }
            ScrewdriverSettings.setFirstPanel(tool, clicked);
            ScrewdriverSettings.setFirstPoint(tool, PanelPoint.snapped(point));
            confirm(player, "fold_edge_picked");
            return;
        }

        PanelFold.Joint joint = PanelFold.between(from, held.toVec(), player);
        if (!joint.problem().ok()) {
            refuse(player, "fold_" + joint.problem().key());
            return;
        }
        if (!completePanel(player, level, joint.panel()))
            return;
        ScrewdriverSettings.setFirstPanel(tool, null);
        ScrewdriverSettings.setFirstPoint(tool, null);
    }

    /**
     * The checks and the building every finished rectangle goes through, wherever the
     * rectangle came from.
     */
    private static boolean completePanel(ServerPlayer player, ServerLevel level, PanelDefinition panel) {
        PanelDefinition.Problem problem = panel.check();
        if (!problem.ok()) {
            refuse(player, "panel_" + problem.name().toLowerCase(Locale.ROOT), PanelDefinition.MAX_CELLS);
            return false;
        }
        return buildPanel(player, level, panel);
    }

    /**
     * A click with nothing under the crosshair, which only the second corner of a
     * dynamic block has any use for.
     *
     * Everything it needs is the player, so there is nothing to validate and nothing
     * a client could have lied about: the ray is the server's own.
     */
    public static void useInAir(ServerPlayer player, InteractionHand hand) {
        ItemStack tool = player.getItemInHand(hand);
        if (!ScrewdriverItem.isScrewdriver(tool))
            return;
        ScrewdriverSettings settings = ScrewdriverSettings.read(tool);
        // An edit is aimed through a hole as readily as at a plate, so the click that
        // hits nothing is as much a click as any other
        if (settings.mode() == ScrewdriverMode.EDIT_PANEL) {
            editPanel(player, player.serverLevel(), tool, settings, false);
            return;
        }
        if (settings.mode() != ScrewdriverMode.DEFINE_DYNAMIC_BLOCK || settings.kind().picksPanels()
                || settings.kind().picksEdge() || settings.firstPoint() == null)
            return;
        completeDynamic(player, player.serverLevel(), tool, settings);
    }

    /**
     * A left click with nothing under the crosshair, which only an edit has any use
     * for: filling a hole in while looking through it at open sky is the one gesture
     * with no block anywhere to name.
     */
    public static void removeInAir(ServerPlayer player, InteractionHand hand) {
        ItemStack tool = player.getItemInHand(hand);
        if (!ScrewdriverItem.isScrewdriver(tool))
            return;
        ScrewdriverSettings settings = ScrewdriverSettings.read(tool);
        if (settings.mode() != ScrewdriverMode.EDIT_PANEL)
            return;
        editPanel(player, player.serverLevel(), tool, settings, true);
    }

    /** The second half of the gesture, wherever the click came from. */
    private static void completeDynamic(ServerPlayer player, ServerLevel level, ItemStack tool,
            ScrewdriverSettings settings) {
        PanelAim.Resolution second = PanelAim.resolve(player, settings.firstPoint(), settings.yaw());
        PanelDefinition panel = PanelAim.between(settings.firstPoint(), second);
        if (panel == null) {
            refuse(player, "flat_rectangle");
            return;
        }

        // Forgotten only on success, the way a refused span is: a rectangle refused
        // for meeting something is nearly always about to be drawn again from the
        // same first corner
        if (completePanel(player, level, panel))
            ScrewdriverSettings.setFirstPoint(tool, null);
    }

    /**
     * Turns a definition into blocks, all of them or none.
     *
     * Every cell is checked before any is placed. A panel that stopped halfway would
     * leave cells that disagree with the definition their neighbours carry, and those
     * cells would then break alone — which is exactly the failure the shared
     * definition exists to prevent.
     */
    private static boolean buildPanel(ServerPlayer player, ServerLevel level, PanelDefinition panel) {
        List<BlockPos> cells = panel.cells();
        for (BlockPos cell : cells) {
            BlockState standing = level.getBlockState(cell);
            if (standing.canBeReplaced())
                continue;
            // A block already holding panels takes another, which is what lets a
            // chamfer spring from a wall that ends mid-block and a fold share the
            // block its hinge runs through
            LargePanelBlockEntity held = LargePanelBlockEntity.cellAt(level, cell);
            if (held != null && !held.holds(panel) && held.panels().size() < LargePanelBlockEntity.MAX_PANELS)
                continue;
            refuse(player, standing.getBlock() instanceof LargePanelBlock ? "panel_meets_panel" : "panel_obstructed",
                    cell.getX(), cell.getY(), cell.getZ());
            return false;
        }

        BlockState state = ModBlocks.LARGE_PANEL.get().defaultBlockState();
        int built = 0;
        for (BlockPos cell : cells) {
            if (!(level.getBlockState(cell).getBlock() instanceof LargePanelBlock))
                level.setBlock(cell, state, Block.UPDATE_ALL);
            LargePanelBlockEntity here = LargePanelBlockEntity.cellAt(level, cell);
            if (here != null && here.addPanel(panel))
                built++;
        }

        // Every cell turned it away, which means a panel already on that surface covers
        // the lot of it. Nothing was placed to undo — a cell can only refuse a panel
        // when it was already a panel cell — so this is a refusal and not a half-build
        if (built == 0) {
            BlockPos first = cells.get(0);
            refuse(player, "panel_meets_panel", first.getX(), first.getY(), first.getZ());
            return false;
        }

        SoundType sound = state.getSoundType();
        level.playSound(null, cells.get(0), sound.getPlaceSound(), SoundSource.BLOCKS, (sound.getVolume() + 1) / 4,
                sound.getPitch());
        confirm(player, "panel_built", built);
        return true;
    }

    /** A dynamic block coordinate as whole pixels, which is what a message wants. */
    private static int pixels(int units) {
        return units / PanelPlane.UNITS_PER_PIXEL;
    }

    // Editing a panel that is already built

    /**
     * A click in the edit mode: work out what it would change and change it.
     *
     * The aim is the player's own ray rather than the block the click named, because
     * half of what this mode does is aimed at a gap where a panel used to be — see
     * {@link PanelEdit}. Nothing in the packet is trusted for it, so there is nothing
     * here for a client to lie about.
     *
     * The panel that was edited is remembered on the tool afterwards, which is what
     * lets the next click find it while the crosshair is pointing through the hole the
     * last one made.
     */
    private static void editPanel(ServerPlayer player, ServerLevel level, ItemStack tool,
            ScrewdriverSettings settings, boolean primary) {
        PanelEditKind kind = settings.editKind();
        PanelEdit.Target target = PanelEdit.aim(level, player, settings.editPanel());
        PanelEdit.Change change = PanelEdit.plan(target, kind, settings.step(), primary);
        if (!change.ok()) {
            refuse(player, "edit_" + change.problem().key());
            return;
        }

        BlockPos at = BlockPos.containing(target.point());
        if (!rebuild(player, level, change.was(), change.now(), at, kind.subtracts(primary)))
            return;

        List<BlockPos> left = change.now().cells();
        ScrewdriverSettings.setEditPanel(tool, left.isEmpty() ? null : left.get(0));
        confirm(player, "edit_" + kind.message(primary));
    }

    /**
     * Moves a panel from one definition to another, all of it or none.
     *
     * Every cell the new definition needs is checked before any block moves, the way
     * {@link #buildPanel} checks before it builds and for the same reason: a panel
     * half changed is a panel whose cells disagree about which rectangle they belong
     * to, and cells that disagree break apart one at a time.
     *
     * The three things that happen to a cell are all here because they have to happen
     * in one pass. A cell that survives has its definition swapped in place rather
     * than removed and re-added — see
     * {@link com.nectariich.create_flippies.content.panel.LargePanelBlockEntity#replacePanel} —
     * a cell that is left with nothing goes away with its controls, and a cell that is
     * new takes on the material the rest of the panel is wearing.
     */
    private static boolean rebuild(ServerPlayer player, ServerLevel level, PanelDefinition was, PanelDefinition now,
            BlockPos at, boolean subtracting) {
        List<BlockPos> before = was.cells();
        Set<BlockPos> had = new HashSet<>(before);
        List<BlockPos> after = now.cells();
        Set<BlockPos> keeping = new HashSet<>(after);

        for (BlockPos cell : after) {
            if (had.contains(cell))
                continue;
            BlockState standing = level.getBlockState(cell);
            if (standing.canBeReplaced())
                continue;
            LargePanelBlockEntity held = LargePanelBlockEntity.cellAt(level, cell);
            if (held != null && held.panels().size() < LargePanelBlockEntity.MAX_PANELS)
                continue;
            refuse(player, standing.getBlock() instanceof LargePanelBlock ? "panel_meets_panel" : "panel_obstructed",
                    cell.getX(), cell.getY(), cell.getZ());
            return false;
        }

        // What the player has said is finished is finished, and an edit takes blocks
        // away as surely as a mistimed swing does. The break event never sees these
        // removals, since they go through removeBlock rather than through mining, so
        // the guard has to be here as well as on the swing. Only a cell that would
        // actually go is asked about: a hole drilled through a protected wall takes no
        // block away and is no more a break than a grid ruled on one is
        for (BlockPos cell : before) {
            if (keeping.contains(cell) || !BreakProtection.stops(player, cell))
                continue;
            LargePanelBlockEntity held = LargePanelBlockEntity.cellAt(level, cell);
            if (held == null || held.panels().size() <= 1) {
                refuse(player, "protected");
                return false;
            }
        }

        // Read before anything is taken away, since the cell it comes from may be one
        // of the ones going
        LargePanelBlockEntity source = null;
        for (BlockPos cell : before) {
            LargePanelBlockEntity held = LargePanelBlockEntity.cellAt(level, cell);
            if (held != null && held.hasCustomMaterial()) {
                source = held;
                break;
            }
        }

        for (BlockPos cell : before) {
            LargePanelBlockEntity held = LargePanelBlockEntity.cellAt(level, cell);
            if (held == null)
                continue;
            if (keeping.contains(cell)) {
                held.replacePanel(was, now);
                continue;
            }
            if (held.removePanel(was) && held.isEmpty())
                LargePanelBlock.demolish(level, cell, player);
        }

        BlockState state = ModBlocks.LARGE_PANEL.get().defaultBlockState();
        for (BlockPos cell : after) {
            if (had.contains(cell))
                continue;
            if (!(level.getBlockState(cell).getBlock() instanceof LargePanelBlock))
                level.setBlock(cell, state, Block.UPDATE_ALL);
            LargePanelBlockEntity here = LargePanelBlockEntity.cellAt(level, cell);
            if (here == null)
                continue;
            here.addPanel(now);
            here.adoptMaterialFrom(source);
        }

        SoundType sound = state.getSoundType();
        level.playSound(null, at, subtracting ? sound.getBreakSound() : sound.getPlaceSound(), SoundSource.BLOCKS,
                (sound.getVolume() + 1) / 4, sound.getPitch());
        return true;
    }

    /**
     * A click with a control that is drawn to size in the other hand: pick a corner,
     * or complete the rectangle and mount a control filling it.
     *
     * The corner is the tool's one pending corner, the same field mode 2 rules grids
     * with. It is the same gesture, and a tool remembering two half-drawn rectangles
     * at once would have to decide which of them a click was finishing.
     *
     * Neither the cell ruling nor the mode has anything to say here. A rectangle
     * states where its own edges are, so there is no cell to centre anything in, and
     * nothing to check it against but the two things that would make it unusable
     * wherever it was drawn: a strip reaching further out of its cell than the picker
     * can follow, and one meeting something already mounted. The host refuses both.
     * That is deliberately laxer than {@link #withinBounds} is about a control aimed
     * into a cell — a legend routinely runs across the block boundaries and the
     * ruling lines of the panel it is naming, and refusing that would make the tool
     * useless for the one thing labels are for.
     */
    private static void span(ServerPlayer player, ServerLevel level, InteractionHand holding, ItemStack tool,
            ScrewdriverSettings settings, SpannedControlType spanned, BlockPos panel, Direction face, Vec3 point) {
        ScrewdriverAim.Surface surface = ScrewdriverAim.surfaceAt(level, panel, face, point,
                player.getEyePosition());
        PanelPlane plane = surface.plane();
        GridCorner picked = ScrewdriverAim.corner(GridStore.of(level).index(), settings, plane, panel, point, true);
        GridCorner first = settings.corner();

        if (first == null) {
            ScrewdriverSettings.setCorner(tool, picked);
            confirm(player, "first_corner", picked.pixelU(), picked.pixelV());
            return;
        }
        // A click on another surface is far more likely to be the start of a new
        // rectangle than a mistake, so it replaces the corner instead of refusing
        if (!first.sharesPlaneWith(picked)) {
            ScrewdriverSettings.setCorner(tool, picked);
            confirm(player, "corner_moved", picked.pixelU(), picked.pixelV());
            return;
        }

        // The corners are held finer than the lattice they snap to so that one snapped
        // inside a ruled cell keeps the cell's own phase, but a control is built in
        // whole half pixels, so the span is what gets rounded and the centre is left
        // where it was drawn
        int spanU = GridCorner.spans(Math.abs(picked.u() - first.u()));
        int spanV = GridCorner.spans(Math.abs(picked.v() - first.v()));
        if (spanU == 0 || spanV == 0) {
            refuse(player, "flat_rectangle");
            return;
        }
        double scale = PanelPlane.UNITS_PER_BLOCK;
        double centreU = (first.u() + picked.u()) / 2.0 / scale;
        double centreV = (first.v() + picked.v()) / 2.0 / scale;

        int turn = turnFor(plane, player, settings);
        ScrewdriverAim.Placement placement = ScrewdriverAim.at(plane, centreU, centreV, surface.depth(), turn);
        ControlMount mount = placement.mount();

        // The rectangle is measured in the plane's axes and the control is authored
        // in its own, which a quarter turn of the mount exchanges
        boolean swap = mount.swapsPlaneAxes();
        int width = swap ? spanV : spanU;
        int height = swap ? spanU : spanV;
        if (width > SpannedControlType.MAX_SPAN || height > SpannedControlType.MAX_SPAN) {
            refuse(player, "rectangle_too_large", SpannedControlType.maxPixels());
            return;
        }

        // A label drawn over others goes on a step proud of the highest of them, so
        // the two are never printed at one depth; see PanelLabelStacking
        HostedControl control = spanned.createSpanning(width, height);
        if (control instanceof PanelLabelControl label)
            label.stackOn(PanelLabelStacking.layerFor(level, placement.host(), mount, label.footprint()));

        // Forgotten only on success: a rectangle refused for meeting something is
        // nearly always about to be drawn again from the same first corner
        if (place(player, level, holding, control, placement))
            ScrewdriverSettings.setCorner(tool, null);
    }

    /**
     * Puts a made control on the panel: opens a host in front of the face if there is
     * none there, mounts it, takes the item and offers the editor.
     *
     * The control is made by the caller rather than here, because a control drawn to
     * size is the only thing that knows how big it is and the host has to check that
     * footprint rather than its kind's.
     *
     * @return whether it went on
     */
    private static boolean place(ServerPlayer player, ServerLevel level, InteractionHand holding, HostedControl control,
            ScrewdriverAim.Placement placement) {
        ControlMount mount = placement.mount();
        BlockPos hostPos = placement.host();
        // Whatever is already there and can carry controls does. On a block face that
        // is a frame, opened here if there is none; on a chamfer it is the panel cell
        // itself, because a diagonal surface runs through the middle of its own blocks
        // and the cell a control's body wants is the very cell the panel is built from
        boolean opened = false;
        if (!(level.getBlockEntity(hostPos) instanceof ControlRackHolder)) {
            if (!openHost(player, level, hostPos)) {
                refuse(player, "obstructed");
                return false;
            }
            opened = true;
        }
        if (!(level.getBlockEntity(hostPos) instanceof ControlRackHolder host)) {
            CreateFlippies.LOGGER.error("Control host at {} has no block entity to mount into", hostPos);
            return false;
        }

        PlacementRefusal refusal = host.install(control, mount, player);
        if (!refusal.ok()) {
            // A host opened for a placement that was then refused would be an empty
            // frame nobody asked for, and an empty host answers for a run of dead
            // terminals
            if (opened && host.controls().isEmpty())
                level.removeBlock(hostPos, false);
            return false;
        }

        if (!player.isCreative())
            player.getItemInHand(holding).shrink(1);
        // Offered as the control goes on rather than left to be found again: a gauge
        // mounted flush in a grid is fiddly to re-aim at, and its printing is the
        // whole reason anyone chose that gauge over another
        control.openEditor(player);
        SoundType sound = level.getBlockState(hostPos).getSoundType();
        level.playSound(null, hostPos, sound.getPlaceSound(), SoundSource.BLOCKS, (sound.getVolume() + 1) / 4,
                sound.getPitch() * 0.8f);
        return true;
    }

    /**
     * Puts an empty host frame in the cell in front of the panel, or answers false
     * if something is already there. Air and the replaceable blocks — grass, snow,
     * water — give way; anything else is the player aiming into a wall, and
     * replacing it would destroy a block they did not mean to break.
     */
    private static boolean openHost(ServerPlayer player, ServerLevel level, BlockPos hostPos) {
        BlockState existing = level.getBlockState(hostPos);
        if (!existing.isAir() && !existing.canBeReplaced())
            return false;
        if (!level.mayInteract(player, hostPos))
            return false;
        return level.setBlock(hostPos, ModBlocks.CONTROL_HOST.get().defaultBlockState(), 3);
    }

    // Painting

    /**
     * The lattice a painted rectangle's corners snap to: half a pixel, the pitch a
     * control drawn to size is built in. Finer than the panel's own pixel so a picture
     * can be centred on something an odd number of pixels wide.
     */
    public static final int PAINT_STEP = SpannedControlType.UNITS_PER_SPAN;

    /**
     * A click in the paint mode: pick a corner on a panel, or paint the chosen picture
     * between it and the corner picked before.
     *
     * The picture is fitted inside the rectangle rather than stretched to it, and turned
     * the quarter turns the menu says. It is written into every cell the rectangle
     * crosses that has plate on that surface; a cell where the plate has been cut away
     * simply takes none, which is the hole showing through the paint.
     */
    private static void paint(ServerPlayer player, ServerLevel level, ItemStack tool, ScrewdriverSettings settings,
            BlockPos clicked, Direction face, Vec3 point) {
        DecalSelection selection = DecalSelection.read(tool);
        if (!selection.hasArt()) {
            refuse(player, "paint_no_art");
            return;
        }
        if (LargePanelBlockEntity.panelAt(level, clicked, point) == null) {
            refuse(player, "paint_not_a_panel");
            return;
        }
        ScrewdriverAim.Surface surface = ScrewdriverAim.surfaceAt(level, clicked, face, point,
                player.getEyePosition());
        GridCorner picked = GridCorner.snapped(surface.plane(), point, PAINT_STEP);
        GridCorner first = settings.corner();
        if (first == null || !first.sharesPlaneWith(picked)) {
            ScrewdriverSettings.setCorner(tool, picked);
            confirm(player, first == null ? "first_corner" : "corner_moved", picked.pixelU(), picked.pixelV());
            return;
        }

        if (first.u() == picked.u() || first.v() == picked.v()) {
            refuse(player, "flat_rectangle");
            return;
        }
        int quarters = settings.turn() & 3;
        int[] rectangle = PanelDecal.fit(first.u(), first.v(), picked.u(), picked.v(), selection.width(),
                selection.height(), quarters);
        int depth = (int) Math.round(surface.depth() * PanelPlane.UNITS_PER_BLOCK);
        PanelDecal decal = new PanelDecal(level.random.nextLong(), selection.art(), surface.plane(), depth,
                rectangle[0], rectangle[1], rectangle[2], rectangle[3], quarters, selection.tint());
        PanelDecal.Problem problem = decal.check();
        if (!problem.ok()) {
            refuse(player, "paint_" + problem.key(), PanelDecal.MAX_CELLS);
            return;
        }

        int painted = 0;
        boolean full = false;
        for (BlockPos cell : decal.cells()) {
            LargePanelBlockEntity held = LargePanelBlockEntity.cellAt(level, cell);
            if (held == null)
                continue;
            if (held.addDecal(decal))
                painted++;
            else
                full |= held.decals().size() >= PanelDecal.MAX_PER_CELL;
        }
        if (painted == 0) {
            refuse(player, full ? "paint_full" : "paint_not_a_panel");
            return;
        }
        // Forgotten only on success, like a rectangle drawn for a label
        ScrewdriverSettings.setCorner(tool, null);
        level.playSound(null, clicked, SoundEvents.DYE_USE, SoundSource.BLOCKS, 1, 1);
        confirm(player, "paint_done", GridCorner.pixels(rectangle[2] - rectangle[0]),
                GridCorner.pixels(rectangle[3] - rectangle[1]));
    }

    /**
     * A left click in the paint mode: take off the topmost picture under the crosshair,
     * all of it, from every cell it was painted across.
     *
     * Protection counts here as it does for an edit. Paint is not a block, but a wall
     * the player has said is finished includes what is painted on it.
     */
    private static void unpaint(ServerPlayer player, ServerLevel level, BlockPos clicked, Direction face,
            Vec3 point) {
        LargePanelBlockEntity cell = LargePanelBlockEntity.cellAt(level, clicked);
        ScrewdriverAim.Surface surface = ScrewdriverAim.surfaceAt(level, clicked, face, point,
                player.getEyePosition());
        PanelDecal decal = cell == null ? null : cell.decalAt(surface.plane(), surface.depth(), point);
        if (decal == null) {
            refuse(player, "paint_none_here");
            return;
        }
        // Break protection guards the blocks, not the paint on them; paint mode
        // claims the left click, so the block under it is never broken here
        cell.removeDecal(decal.id());
        for (BlockPos other : decal.cells()) {
            LargePanelBlockEntity held = LargePanelBlockEntity.cellAt(level, other);
            if (held != null)
                held.removeDecal(decal.id());
        }
        level.playSound(null, clicked, SoundEvents.BRUSH_GENERIC, SoundSource.BLOCKS, 1, 1);
        confirm(player, "paint_removed");
    }

    // Saying so

    private static void refuse(Player player, String key, Object... arguments) {
        player.displayClientMessage(
                Component.translatable(MESSAGE_PREFIX + key, arguments).withStyle(ChatFormatting.RED), true);
    }

    private static void refuse(Player player, PlacementGrid.Problem problem) {
        player.displayClientMessage(
                Component.translatable(GRID_PREFIX + problem.name().toLowerCase(Locale.ROOT))
                        .withStyle(ChatFormatting.RED),
                true);
    }

    private static void confirm(Player player, String key, Object... arguments) {
        player.displayClientMessage(
                Component.translatable(MESSAGE_PREFIX + key, arguments).withStyle(ChatFormatting.AQUA), true);
    }
}
