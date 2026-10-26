package com.nectariich.create_flippies.client;

import com.nectariich.create_flippies.CreateFlippies;
import com.nectariich.create_flippies.content.decal.DecalSelection;
import com.nectariich.create_flippies.content.decal.PanelDecal;
import com.nectariich.create_flippies.content.panel.LargePanelBlockEntity;
import com.nectariich.create_flippies.content.panel.PanelAim;
import com.nectariich.create_flippies.content.panel.PanelChamfer;
import com.nectariich.create_flippies.content.panel.PanelEdit;
import com.nectariich.create_flippies.content.panel.PanelFold;
import com.nectariich.create_flippies.content.panel.PanelPoint;
import com.nectariich.create_flippies.content.screwdriver.GridCorner;
import com.nectariich.create_flippies.foundation.panel.PanelCut;
import com.nectariich.create_flippies.foundation.panel.PanelDefinition;
import com.nectariich.create_flippies.foundation.panel.PanelPlane;
import com.nectariich.create_flippies.content.screwdriver.ScrewdriverActions;
import com.nectariich.create_flippies.content.screwdriver.ScrewdriverAim;
import com.nectariich.create_flippies.content.screwdriver.ScrewdriverItem;
import com.nectariich.create_flippies.content.screwdriver.ScrewdriverMode;
import com.nectariich.create_flippies.content.screwdriver.ScrewdriverSettings;
import com.nectariich.create_flippies.content.wire_duct.WireDuctItem;
import com.nectariich.create_flippies.foundation.control.ControlTypes;
import com.nectariich.create_flippies.foundation.control.SpannedControlType;
import com.nectariich.create_flippies.foundation.protection.BreakArea;
import com.nectariich.create_flippies.foundation.protection.ProtectionIndex;
import com.nectariich.create_flippies.foundation.grid.FaceRegion;
import com.nectariich.create_flippies.foundation.grid.GridIndex;
import com.nectariich.create_flippies.foundation.grid.PlacementGrid;

import java.util.Locale;

import net.createmod.catnip.outliner.Outliner;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/**
 * What the player is about to snap a control to, drawn on the surface they are
 * aiming at.
 *
 * A placement aid that cannot be seen is a guessing game: the cells of mode 1 are
 * lines on an ordinary block that has no markings of any kind, and mode 2 asks for
 * two corners to the pixel. So the overlay is not decoration, it is the only thing
 * that makes the tool aimable.
 *
 * The geometry comes from {@link ScrewdriverAim}, the same call the server makes
 * when the click arrives, which is the point: a preview computed independently
 * would agree everywhere except at cell boundaries, and a cell boundary is exactly
 * where a careful player aims.
 *
 * Nothing is rendered here directly. Catnip's {@link Outliner} is already ticked
 * and drawn by Ponder's own client hooks, so shapes only have to be pushed into it
 * once a tick and they fade of their own accord when the tool is put away — which
 * means there is no tear-down path to get wrong, and no render event of ours to
 * keep in step with anybody else's.
 *
 * The keys are plain objects held in a fixed array rather than composed strings.
 * An outline is refreshed by being shown again under the same key, so the keys have
 * to be stable across ticks; making them stable and allocation-free at once is what
 * the array is for, and it doubles as the budget that stops a face ruled sixty-four
 * ways from filling the outliner.
 */
@Mod.EventBusSubscriber(modid = CreateFlippies.MOD_ID, value = Dist.CLIENT)
public final class ScrewdriverOverlay {

    /**
     * How far off the surface the lines are drawn. Enough to clear half a line
     * width so nothing z-fights with the block face, and small enough — a third of
     * a pixel — that the ruling still reads as being on the wall.
     */
    private static final double PROUD = 0.02;

    private static final float LINE_WIDTH = 1 / 64f;

    /** The ruling the next click would place into. */
    private static final int RULING_COLOUR = 0x6FA8DC;

    /** The one cell of it the crosshair is in. */
    private static final int MOUNT_COLOUR = 0xFFFFFF;

    /** Constraining grids already in the world. */
    private static final int STORED_COLOUR = 0x8FC96B;

    /** The rectangle between a picked corner and the crosshair. */
    private static final int PENDING_COLOUR = 0xE8B84B;

    /** Boxes the tool will not mine through. */
    private static final int PROTECTED_COLOUR = 0xD05B5B;

    /**
     * How far a protected box is drawn outside the blocks it covers, in blocks.
     * Enough to clear the block's own edges rather than z-fight along them.
     */
    private static final double AREA_OUTSET = 0.01;

    /** How many chunks either way are searched for areas to draw. */
    private static final int AREA_RANGE = 1;

    private static final int LINE_BUDGET = 160;

    private static final Object[] LINE_KEYS = lineKeys();
    private static final Object[] AREA_KEYS = areaKeys();
    private static final Object[] MOUNT_KEYS = keys(4);
    private static final Object[] PENDING_KEYS = keys(4);
    private static final Object PENDING_KEY = new Object();
    private static final Object CROSSHAIR_KEY = new Object();
    private static final Object CORNER_KEY = new Object();
    private static final Object[] EDGE_KEYS = keys(2);
    private static final Object[] PAINTED_KEYS = keys(4);
    private static final Object PAINT_TOP_KEY = new Object();

    /** As many protected areas as are drawn at once before the rest are left out. */
    private static final int AREA_BUDGET = 32;

    /** Half the side of the cube marking a snapped corner, in blocks. */
    private static final double MARKER_RADIUS = 1.5 / PlacementGrid.PIXELS_PER_BLOCK;

    private static final String MESSAGE_PREFIX = "message." + CreateFlippies.MOD_ID + ".screwdriver.";

    private ScrewdriverOverlay() {}

    @SubscribeEvent
    public static void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END)
            return;
        Minecraft minecraft = Minecraft.getInstance();
        LocalPlayer player = minecraft.player;
        if (player == null || minecraft.level == null)
            return;
        InteractionHand hand = ScrewdriverItem.heldBy(player);
        if (hand == null)
            return;
        ScrewdriverSettings settings = ScrewdriverSettings.read(player.getItemInHand(hand));
        // SCHEMATIC ARROWS: taking a colour for the arrow menu outlines the arrow it would come from, whatever the mode
        if (com.nectariich.create_flippies.client.schematic_arrow.ArrowColourPick.active()) {
            com.nectariich.create_flippies.client.schematic_arrow.ArrowOverlay.drawPick(player);
            return;
        }
        // The idle mode has nothing to preview, and saying so here is the only place
        // it can be said. Every path below draws something: with no ruling of its own
        // the mode falls back to one cell per face, so the tool that does nothing was
        // outlining the whole face of whatever the player looked at.
        if (settings.mode() == ScrewdriverMode.NONE)
            return;

        // The dynamic gesture resolves a second corner against planes rather than
        // against blocks, so it has to keep drawing when the crosshair is on nothing
        // at all — which is exactly the case midair placement exists for. An edit is
        // the same for the same reason: a click aimed through a missing tile hits
        // whatever is behind the wall, or nothing. Every other mode needs a block and
        // gives up without one.
        BlockHitResult aimed = minecraft.hitResult instanceof BlockHitResult block
                && block.getType() == HitResult.Type.BLOCK ? block : null;
        if (settings.mode() == ScrewdriverMode.DEFINE_DYNAMIC_BLOCK) {
            drawDynamic(player, settings, aimed);
            return;
        }
        if (settings.mode() == ScrewdriverMode.EDIT_PANEL) {
            drawEdit(player, settings);
            return;
        }
        // SCHEMATIC ARROWS: a half-drawn arrow is still drawn with the crosshair off the panel
        if (settings.mode() == ScrewdriverMode.SCHEMATIC_ARROW) {
            com.nectariich.create_flippies.client.schematic_arrow.ArrowOverlay.draw(player, hand, settings, aimed);
            return;
        }
        if (aimed == null)
            return;
        BlockHitResult hit = aimed;
        GridIndex index = GridIndex.of(minecraft.level);
        BlockPos panel = hit.getBlockPos();
        Direction face = hit.getDirection();
        if (settings.mode() == ScrewdriverMode.PAINT_DECAL) {
            drawPaint(player, hand, settings, hit);
            return;
        }
        // Protected boxes are drawn in the mode that draws them and nowhere else. A
        // red box across the panel every time the tool comes out says nothing the
        // player does not already know, and it hides the ruling they are aiming at;
        // the message above the hotbar is what explains a click that did nothing.
        if (settings.mode() == ScrewdriverMode.DEFINE_BREAK_PROTECTION) {
            drawProtection(player, settings, panel);
            return;
        }

        // A wire duct is laid on the face whichever placement mode is set and draws
        // its own preview (WireDuctInput); a cell ruling would only mislead
        if (WireDuctItem.placing(player) != null)
            return;

        // A control that is drawn to size is aimed corner to corner rather than into
        // a cell, so it wants mode 2's preview whichever placement mode is set
        ScrewdriverAim.Surface surface = ScrewdriverAim.surfaceAt(player.level(), panel, face, hit.getLocation(),
                player.getEyePosition());
        boolean toSize = spanned(player, hand);
        if (settings.mode() == ScrewdriverMode.DEFINE_GRID || toSize)
            drawRectangle(index, settings, surface, panel, hit.getLocation(), toSize);
        else
            drawPlacement(index, settings, surface, panel, face, hit.getLocation());
    }

    // Mode 4

    /**
     * The protected boxes nearby, the block under the crosshair, and the box the
     * next click would protect.
     *
     * Everything near the player is drawn rather than only what is aimed at, because
     * in this mode the areas are the thing being worked on: a box that cannot be seen
     * cannot be aimed at to remove, and one already covering the wall is the reason a
     * second one is not needed.
     */
    private static void drawProtection(LocalPlayer player, ScrewdriverSettings settings, BlockPos clicked) {
        int key = 0;
        for (BreakArea area : ProtectionIndex.of(player.level()).near(clicked, AREA_RANGE)) {
            if (key >= AREA_KEYS.length)
                break;
            show(AREA_KEYS[key++], area.box().inflate(AREA_OUTSET), PROTECTED_COLOUR);
        }

        show(CROSSHAIR_KEY, blockBox(clicked), PENDING_COLOUR);
        BlockPos first = settings.areaCorner();
        if (first != null)
            show(PENDING_KEY, blockBox(first).minmax(blockBox(clicked)), PENDING_COLOUR);
    }

    /** One block as a box, drawn a hair proud so it does not z-fight with the block. */
    private static AABB blockBox(BlockPos pos) {
        return new AABB(pos).inflate(AREA_OUTSET);
    }

    // Painting

    /**
     * The corner picked so far, the rectangle to the crosshair, and inside it the
     * rectangle the picture would actually fill, with a mark at the middle of what
     * will be its top edge, which on a floor is the only way to tell which way up it
     * goes on.
     *
     * Before a corner is picked, the picture under the crosshair is outlined instead,
     * in the colour of things the tool would take away: that is the one a left click
     * would remove, and on a wall of overlapping paint it is not obvious which.
     */
    private static void drawPaint(LocalPlayer player, InteractionHand hand, ScrewdriverSettings settings,
            BlockHitResult hit) {
        BlockPos clicked = hit.getBlockPos();
        Vec3 point = hit.getLocation();
        LargePanelBlockEntity cell = LargePanelBlockEntity.cellAt(player.level(), clicked);
        if (cell == null || cell.nearest(point) == null)
            return;
        ScrewdriverAim.Surface surface = ScrewdriverAim.surfaceAt(player.level(), clicked, hit.getDirection(),
                point, player.getEyePosition());
        PanelPlane plane = surface.plane();
        double proud = proudOf(surface);

        GridCorner crosshair = GridCorner.snapped(plane, point, ScrewdriverActions.PAINT_STEP);
        show(CROSSHAIR_KEY, marker(crosshair.toVec(proud)), PENDING_COLOUR);

        GridCorner picked = settings.corner();
        if (picked == null) {
            PanelDecal under = cell.decalAt(plane, surface.depth(), point);
            if (under != null)
                rectangle(PAINTED_KEYS, FaceRegion.spanned(plane, under.minU(), under.minV(), under.maxU(),
                        under.maxV()).corners(proud), PROTECTED_COLOUR);
            return;
        }
        show(CORNER_KEY, marker(picked.toVec(proud)), PENDING_COLOUR);
        if (!picked.sharesPlaneWith(crosshair))
            return;
        rectangle(PENDING_KEYS, FaceRegion.spanned(plane, Math.min(picked.u(), crosshair.u()),
                Math.min(picked.v(), crosshair.v()), Math.max(picked.u(), crosshair.u()),
                Math.max(picked.v(), crosshair.v())).corners(proud), PENDING_COLOUR);

        DecalSelection selection = DecalSelection.read(player.getItemInHand(hand));
        if (!selection.hasArt() || picked.u() == crosshair.u() || picked.v() == crosshair.v())
            return;
        int quarters = settings.turn() & 3;
        int[] fit = PanelDecal.fit(picked.u(), picked.v(), crosshair.u(), crosshair.v(), selection.width(),
                selection.height(), quarters);
        rectangle(MOUNT_KEYS, FaceRegion.spanned(plane, fit[0], fit[1], fit[2], fit[3]).corners(proud),
                MOUNT_COLOUR);
        PanelDecal preview = new PanelDecal(0, selection.art(), plane, 0, fit[0], fit[1], fit[2], fit[3], quarters,
                PanelDecal.OWN_COLOURS);
        double[] top = new double[2];
        preview.map(0.5, 0, top);
        show(PAINT_TOP_KEY, marker(plane.at(top[0], top[1], proud)), MOUNT_COLOUR);
    }

    // Modes 1 and 3

    private static void drawPlacement(GridIndex index, ScrewdriverSettings settings,
            ScrewdriverAim.Surface surface, BlockPos panel, Direction face, Vec3 point) {
        double proud = proudOf(surface);
        int keys = 0;
        // Mode 3 places inside a constraining grid, so the grids themselves have to
        // be visible or there is no way to tell why a face refuses a control
        if (settings.mode() == ScrewdriverMode.CONSTRAINED_PLACEMENT)
            keys = drawStoredGrids(index, panel, surface.plane(), proud, keys);

        ScrewdriverAim.Target target = ScrewdriverAim.resolve(index, settings, surface, panel, face, point);
        if (target == null)
            return;
        keys = drawRuling(target.region(), proud, RULING_COLOUR, keys);
        rectangle(MOUNT_KEYS, target.region().cellCorners(target.cell(), proud), MOUNT_COLOUR);
    }

    /**
     * How far off the plane to draw a ruling so that it lands on the surface rather
     * than inside it.
     *
     * A panel's plate hangs behind its own plane, so a ruling on the front needs only
     * a hair and any more would float it off the wall. The back is the same plane
     * held the other way round, and in that frame the plate is in front of it — so a
     * hair is two pixels deep inside the plate, which is what put rulings inside the
     * panel.
     *
     * The distance to clear is exactly the depth the surface already carries: it is
     * nought for a front and the plate's thickness for a back, which is the same
     * number for the same reason. Which side a panel calls its front is not something
     * the player chose — a chamfer takes its facing from the two panels it was sprung
     * between — so this cannot be left to the stored facing.
     */
    private static double proudOf(ScrewdriverAim.Surface surface) {
        return surface.depth() + PROUD;
    }

    /** Whether the hand that is not holding the tool holds a control drawn to size. */
    private static boolean spanned(LocalPlayer player, InteractionHand hand) {
        return ControlTypes.forItem(
                player.getItemInHand(ScrewdriverItem.otherHand(hand))) instanceof SpannedControlType;
    }

    // Mode 2, and anything else aimed corner to corner

    private static void drawRectangle(GridIndex index, ScrewdriverSettings settings,
            ScrewdriverAim.Surface surface, BlockPos panel, Vec3 point, boolean within) {
        PanelPlane plane = surface.plane();
        double proud = proudOf(surface);
        drawStoredGrids(index, panel, plane, proud, 0);

        GridCorner crosshair = ScrewdriverAim.corner(index, settings, plane, panel, point, within);
        show(CROSSHAIR_KEY, marker(crosshair.toVec(proud)), PENDING_COLOUR);

        GridCorner picked = settings.corner();
        if (picked == null)
            return;
        show(CORNER_KEY, marker(picked.toVec(proud)), PENDING_COLOUR);
        if (!picked.sharesPlaneWith(crosshair))
            return;
        FaceRegion pending = FaceRegion.spanned(plane, Math.min(picked.u(), crosshair.u()),
                Math.min(picked.v(), crosshair.v()), Math.max(picked.u(), crosshair.u()),
                Math.max(picked.v(), crosshair.v()));
        rectangle(PENDING_KEYS, pending.corners(proud), PENDING_COLOUR);
    }

    // The dynamic block gesture

    /**
     * The corner picked so far, the corner the crosshair resolves to, and the
     * rectangle between them — the same three marks mode 2 draws for a grid, which is
     * what makes the gesture read as the same gesture.
     *
     * The rectangle is four lines rather than a box because a panel at forty-five
     * degrees is not one: an {@code AABB} would be its bounding box, which on a
     * diagonal is a square twice the area of the panel and says nothing about where
     * the panel is.
     *
     * Everything comes from {@link PanelAim} using the player's own eye and look, the
     * same inputs the server resolves from, so what is drawn is what would be built.
     */
    private static void drawDynamic(LocalPlayer player, ScrewdriverSettings settings, BlockHitResult aimed) {
        if (settings.kind().picksPanels()) {
            drawChamfer(player, settings, aimed);
            return;
        }
        if (settings.kind().picksEdge()) {
            drawFold(player, settings, aimed);
            return;
        }

        PanelPoint first = settings.firstPoint();
        if (first == null) {
            // Nothing is decided yet, and the first corner has to be on a face, so
            // there is nothing to show while the crosshair is on nothing
            if (aimed != null)
                show(CROSSHAIR_KEY, marker(PanelPoint.snapped(aimed.getLocation()).toVec()), PENDING_COLOUR);
            return;
        }

        show(CORNER_KEY, marker(first.toVec()), PENDING_COLOUR);
        PanelAim.Resolution second = PanelAim.resolve(player, first, settings.yaw());
        if (second == null)
            return;
        show(CROSSHAIR_KEY, marker(second.point()), PENDING_COLOUR);

        PanelDefinition pending = PanelAim.between(first, second);
        if (pending == null)
            return;
        outline(pending, PENDING_COLOUR, 0);
        report(player, pending);
    }

    /**
     * The chamfer gesture: the panel picked to spring from, the one under the
     * crosshair, and the corner that would be built between them.
     *
     * Three outlines rather than two corners and a rectangle, because this gesture
     * picks whole panels rather than points, and what the player has to be able to see
     * is which two they have named — the chamfer itself follows from that and is
     * shown as the answer, in the colour a resolved mount is drawn in.
     */
    private static void drawChamfer(LocalPlayer player, ScrewdriverSettings settings, BlockHitResult aimed) {
        PanelDefinition here = aimed == null ? null
                : LargePanelBlockEntity.panelAt(player.level(), aimed.getBlockPos(), aimed.getLocation());
        BlockPos picked = settings.firstPanel();
        PanelDefinition from = picked == null ? null
                : LargePanelBlockEntity.panelAt(player.level(), picked, Vec3.atCenterOf(picked));

        int keys = 0;
        if (from != null)
            keys = outline(from, STORED_COLOUR, keys);
        if (here != null && !here.equals(from))
            keys = outline(here, PENDING_COLOUR, keys);
        if (from == null || here == null)
            return;

        PanelChamfer.Joint joint = PanelChamfer.between(from, here);
        if (joint.panel() == null) {
            // Only once a second panel is actually aimed at, or every sweep of the
            // crosshair across open air would blank the message
            status(player, Component.translatable(MESSAGE_PREFIX + "chamfer_" + joint.problem().key()),
                    ChatFormatting.RED);
            return;
        }
        outline(joint.panel(), MOUNT_COLOUR, keys);
        report(player, joint.panel());
    }

    /**
     * The fold gesture: the panel and edge picked so far, and the rectangle the second
     * click would build square to it.
     *
     * Both edges the fold could turn about are drawn, because the choice between them
     * is the crosshair's and the player has to be able to see that there is a choice at
     * all. The one the aim has landed on is picked out in the colour a resolved mount
     * is drawn in, so looking about swaps which edge is lit before the click commits to
     * either.
     */
    private static void drawFold(LocalPlayer player, ScrewdriverSettings settings, BlockHitResult aimed) {
        PanelPoint held = settings.firstPoint();
        BlockPos picked = settings.firstPanel();
        PanelDefinition from = picked == null || held == null ? null
                : LargePanelBlockEntity.panelAt(player.level(), picked, held.toVec());

        if (from == null) {
            if (aimed == null)
                return;
            PanelDefinition here = LargePanelBlockEntity.panelAt(player.level(), aimed.getBlockPos(),
                    aimed.getLocation());
            if (here == null)
                return;
            outline(here, STORED_COLOUR, 0);
            edges(here, aimed.getLocation(), null);
            return;
        }

        int keys = outline(from, STORED_COLOUR, 0);
        show(CORNER_KEY, marker(held.toVec()), PENDING_COLOUR);

        PanelFold.Joint joint = PanelFold.between(from, held.toVec(), player);
        edges(from, held.toVec(), joint.hinge());
        if (joint.panel() == null)
            return;
        outline(joint.panel(), PENDING_COLOUR, keys);
        report(player, joint.panel());
    }

    // Editing a panel that is already built

    /**
     * The panel under the crosshair, the holes already in it, and the rectangle the
     * right button would change.
     *
     * The holes are drawn as well as the panel, and they are the reason this preview
     * is worth having at all: a tile that has been taken out of a wall is a gap you
     * can see through, but one taken out of a wall with something behind it is a gap
     * you cannot, and putting it back means aiming at a rectangle with no edges.
     *
     * Only the right button's outcome is drawn. The left button undoes it — see
     * {@link PanelEdit#plan} — so the two rectangles are the same one for a tile and
     * mirror images of each other for an edge, and drawing both would be two answers
     * to a question with one.
     */
    private static void drawEdit(LocalPlayer player, ScrewdriverSettings settings) {
        PanelEdit.Target target = PanelEdit.aim(player.level(), player, settings.editPanel());
        if (target == null)
            return;

        PanelDefinition panel = target.panel();
        int keys = outline(panel, STORED_COLOUR, 0);
        for (PanelCut hole : panel.cuts())
            keys = rectangle(panel.plane(), hole, STORED_COLOUR, keys);

        PanelEdit.Change change = PanelEdit.plan(target, settings.editKind(), settings.step(), true);
        if (!change.ok()) {
            status(player, Component.translatable(MESSAGE_PREFIX + "edit_" + change.problem().key()),
                    ChatFormatting.RED);
            return;
        }
        rectangle(panel.plane(), change.area(), MOUNT_COLOUR, keys);
    }

    /**
     * One rectangle on a plane, in plane units, as the four lines between its corners.
     *
     * @return the next free line key
     */
    private static int rectangle(PanelPlane plane, PanelCut area, int colour, int keys) {
        if (area == null || keys + 4 > LINE_KEYS.length)
            return keys;
        double scale = PanelPlane.UNITS_PER_BLOCK;
        Vec3 a = plane.at(area.minU() / scale, area.minV() / scale, PROUD);
        Vec3 b = plane.at(area.maxU() / scale, area.minV() / scale, PROUD);
        Vec3 c = plane.at(area.maxU() / scale, area.maxV() / scale, PROUD);
        Vec3 d = plane.at(area.minU() / scale, area.maxV() / scale, PROUD);
        line(LINE_KEYS[keys++], a, b, colour);
        line(LINE_KEYS[keys++], b, c, colour);
        line(LINE_KEYS[keys++], c, d, colour);
        line(LINE_KEYS[keys++], d, a, colour);
        return keys;
    }

    /** Both edges a fold may hinge on, with whichever the crosshair chose picked out. */
    private static void edges(PanelDefinition panel, Vec3 point, PanelFold.Hinge chosen) {
        PanelFold.Hinge[] candidates = PanelFold.candidates(panel, point);
        for (int i = 0; i < candidates.length && i < EDGE_KEYS.length; i++)
            edge(EDGE_KEYS[i], candidates[i], candidates[i].equals(chosen) ? MOUNT_COLOUR : PENDING_COLOUR);
    }

    /** The hinge itself, as the single line it is. */
    private static void edge(Object key, PanelFold.Hinge hinge, int colour) {
        if (hinge == null)
            return;
        PanelDefinition source = hinge.source();
        PanelPlane plane = source.plane();
        double scale = PanelPlane.UNITS_PER_BLOCK;
        double at = hinge.at() / scale;
        Vec3 from = hinge.alongV() ? plane.at(at, source.minV() / scale, PROUD)
                : plane.at(source.minU() / scale, at, PROUD);
        Vec3 to = hinge.alongV() ? plane.at(at, source.maxV() / scale, PROUD)
                : plane.at(source.maxU() / scale, at, PROUD);
        line(key, from, to, colour);
    }

    /**
     * How big the pending panel is, above the hotbar, or why it will be refused.
     *
     * The size is worth saying because it is the one thing the outline cannot: a
     * rectangle drawn in the air reads as roughly so many blocks, and a panel is
     * usually being matched to something that is exactly so many. The refusal is shown
     * in the same place because a panel that is about to be turned down should say so
     * while there is still time to drag it smaller, not after the click.
     */
    private static void report(LocalPlayer player, PanelDefinition panel) {
        PanelDefinition.Problem problem = panel.check();
        if (!problem.ok()) {
            status(player, Component.translatable(MESSAGE_PREFIX + "panel_" + problem.name().toLowerCase(Locale.ROOT),
                    PanelDefinition.MAX_CELLS), ChatFormatting.RED);
            return;
        }
        status(player, Component.translatable(MESSAGE_PREFIX + "panel_size", blocks(panel.spanU()),
                blocks(panel.spanV()), panel.cellCount()), ChatFormatting.AQUA);
    }

    private static void status(LocalPlayer player, Component text, ChatFormatting colour) {
        player.displayClientMessage(text.copy().withStyle(colour), true);
    }

    /**
     * A span in offset units, as blocks, with no more decimals than it needs.
     *
     * Blocks rather than the pixels the rest of the tool reports in, because a panel
     * is the one thing here measured against the room rather than against a face. Every
     * span is a whole number of pixels, so four decimals is exact and the trailing
     * zeros are the only thing being dropped.
     */
    private static String blocks(int units) {
        String text = String.format(Locale.ROOT, "%.4f", units / (double) PanelPlane.UNITS_PER_BLOCK);
        text = text.replaceAll("0+$", "");
        return text.endsWith(".") ? text.substring(0, text.length() - 1) : text;
    }

    /**
     * The four edges of a panel, on its own plane rather than on an axis.
     *
     * @return the next free line key
     */
    private static int outline(PanelDefinition panel, int colour, int keys) {
        PanelPlane plane = panel.plane();
        double scale = PanelPlane.UNITS_PER_BLOCK;
        double lowU = panel.minU() / scale;
        double highU = panel.maxU() / scale;
        double lowV = panel.minV() / scale;
        double highV = panel.maxV() / scale;

        Vec3 a = plane.at(lowU, lowV, PROUD);
        Vec3 b = plane.at(highU, lowV, PROUD);
        Vec3 c = plane.at(highU, highV, PROUD);
        Vec3 d = plane.at(lowU, highV, PROUD);
        line(LINE_KEYS[keys++], a, b, colour);
        line(LINE_KEYS[keys++], b, c, colour);
        line(LINE_KEYS[keys++], c, d, colour);
        line(LINE_KEYS[keys++], d, a, colour);
        return keys;
    }

    /** A picked point, as a small cube to draw. */
    private static AABB marker(Vec3 centre) {
        return new AABB(centre, centre).inflate(MARKER_RADIUS);
    }

    // Drawing

    /**
     * The rulings of every constraining grid touching the face, innermost last so
     * that a nested grid draws over its parent.
     *
     * @return the next free line key
     */
    private static int drawStoredGrids(GridIndex index, BlockPos panel, PanelPlane surface, double proud, int keys) {
        for (PlacementGrid grid : index.onSurface(panel, surface))
            keys = drawRuling(FaceRegion.of(grid), proud, STORED_COLOUR, keys);
        return keys;
    }

    /** Four corners as the four lines between them, which a turned rectangle needs. */
    private static void rectangle(Object[] keys, Vec3[] corners, int colour) {
        for (int corner = 0; corner < corners.length; corner++)
            line(keys[corner], corners[corner], corners[(corner + 1) % corners.length], colour);
    }

    /**
     * One region's lines: every column boundary and every row boundary, edges
     * included. Lines rather than a box per cell, because a sixteen-by-sixteen face
     * is thirty-four lines and two hundred and fifty-six boxes.
     *
     * @return the next free line key
     */
    private static int drawRuling(FaceRegion region, double proud, int colour, int keys) {
        for (int column = 0; column <= region.columns() && keys < LINE_BUDGET; column++) {
            double u = region.columnEdge(column);
            line(LINE_KEYS[keys++], region.point(u, region.minV(), proud), region.point(u, region.maxV(), proud),
                    colour);
        }
        for (int row = 0; row <= region.rows() && keys < LINE_BUDGET; row++) {
            double v = region.rowEdge(row);
            line(LINE_KEYS[keys++], region.point(region.minU(), v, proud), region.point(region.maxU(), v, proud),
                    colour);
        }
        return keys;
    }

    private static void line(Object key, Vec3 from, Vec3 to, int colour) {
        Outliner.getInstance().showLine(key, from, to).colored(colour).lineWidth(LINE_WIDTH);
    }

    private static void show(Object key, AABB box, int colour) {
        Outliner.getInstance().showAABB(key, box).colored(colour).lineWidth(LINE_WIDTH);
    }

    private static Object[] lineKeys() {
        return keys(LINE_BUDGET);
    }

    private static Object[] areaKeys() {
        return keys(AREA_BUDGET);
    }

    private static Object[] keys(int count) {
        Object[] keys = new Object[count];
        for (int i = 0; i < keys.length; i++)
            keys[i] = new Object();
        return keys;
    }
}
