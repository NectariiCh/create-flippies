package com.nectariich.create_flippies.client.schematic_arrow;

import java.util.List;

import com.nectariich.create_flippies.content.decal.DecalSelection;
import com.nectariich.create_flippies.content.decal.PanelDecal;
import com.nectariich.create_flippies.content.panel.LargePanelBlockEntity;
import com.nectariich.create_flippies.content.schematic_arrow.ArrowAction;
import com.nectariich.create_flippies.content.schematic_arrow.ArrowDraft;
import com.nectariich.create_flippies.content.schematic_arrow.ArrowGrab;
import com.nectariich.create_flippies.content.schematic_arrow.ArrowSettings;
import com.nectariich.create_flippies.content.schematic_arrow.ArrowSnap;
import com.nectariich.create_flippies.content.schematic_arrow.PanelArrow;
import com.nectariich.create_flippies.content.schematic_arrow.SchematicArrowActions;
import com.nectariich.create_flippies.content.screwdriver.GridCorner;
import com.nectariich.create_flippies.content.screwdriver.ScrewdriverActions;
import com.nectariich.create_flippies.content.screwdriver.ScrewdriverAim;
import com.nectariich.create_flippies.content.screwdriver.ScrewdriverSettings;
import com.nectariich.create_flippies.foundation.panel.PanelPlane;

import net.createmod.catnip.outliner.Outliner;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

/**
 * The live preview of the schematic arrow mode, drawn through Catnip's outliner the
 * way the rest of the screwdriver's overlay is (see {@code ScrewdriverOverlay}).
 *
 * While drawing, the outline the arrow would have if the next click went where the
 * crosshair is, at the height it would stand, with a mark on every point so far. The
 * point under the crosshair is snapped by the same call the server makes, so what is
 * drawn is what the click will add. With nothing in progress, the arrow under the
 * crosshair is outlined in the colour of things a left click takes away.
 */
public final class ArrowOverlay {

    private static final float LINE_WIDTH = 1 / 64f;

    /** Lifted this far above the arrow's top, in blocks, so the lines are not inside it. */
    private static final double PROUD = 0.01;

    private static final int PENDING_COLOUR = 0xE8B84B;
    private static final int PREVIEW_COLOUR = 0xFFFFFF;
    private static final int REMOVE_COLOUR = 0xD05B5B;
    private static final int PICK_COLOUR = 0x6FA8DC;

    private static final double MARKER_RADIUS = 1.0 / 16;

    private static final int LINE_BUDGET = 512;
    private static final Object[] LINE_KEYS = keys(LINE_BUDGET);
    private static final Object[] MARKER_KEYS = keys(PanelArrow.MAX_POINTS + 2);

    private ArrowOverlay() {}

    /** One tick of the preview. {@code hit} is the block the crosshair is on, or null. */
    public static void draw(LocalPlayer player, InteractionHand hand, ScrewdriverSettings settings,
            BlockHitResult hit) {
        ItemStack tool = player.getItemInHand(hand);
        ArrowSettings arrows = ArrowSettings.read(tool);
        ArrowDraft draft = ArrowDraft.read(tool);
        ArrowGrab grab = ArrowGrab.read(tool);
        ScrewdriverAim.Surface surface = null;
        LargePanelBlockEntity cell = null;
        if (hit != null) {
            cell = LargePanelBlockEntity.cellAt(player.level(), hit.getBlockPos());
            if (cell != null && cell.nearest(hit.getLocation()) != null)
                surface = ScrewdriverAim.surfaceAt(player.level(), hit.getBlockPos(), hit.getDirection(),
                        hit.getLocation(), player.getEyePosition());
        }

        Lines lines = new Lines();
        if (draft != null) {
            drawDraft(arrows, draft, surface, hit, lines);
            return;
        }
        if (grab != null) {
            drawGrab(player, arrows, grab, surface, hit, lines);
            return;
        }
        if (surface == null)
            return;
        Vec3 point = hit.getLocation();
        if (arrows.action() == ArrowAction.STAMP) {
            drawStamp(tool, settings, surface, point, lines);
            return;
        }
        PanelArrow under = cell.arrowAt(surface.plane(), surface.depth(), point);
        if (under != null)
            outline(under, arrows.action() == ArrowAction.MOVE ? PICK_COLOUR : REMOVE_COLOUR, lines);
        if (arrows.action() == ArrowAction.DRAW) {
            int[] first = SchematicArrowActions.pointFor(surface, point, null, arrows.snap());
            marker(0, surface.plane(), first, surface.depth(), PENDING_COLOUR);
        }
    }

    private static void drawDraft(ArrowSettings arrows, ArrowDraft draft, ScrewdriverAim.Surface surface,
            BlockHitResult hit, Lines lines) {
        double depth = draft.depth() / (double) PanelPlane.UNITS_PER_BLOCK;
        int[] points = draft.points();
        if (surface != null && surface.plane().equals(draft.plane())
                && SchematicArrowActions.depthOf(surface) == draft.depth()) {
            int[] next = SchematicArrowActions.pointFor(surface, hit.getLocation(), draft, arrows.snap());
            int[] last = draft.last();
            if (next[0] != last[0] || next[1] != last[1])
                points = draft.plus(next).points();
        }
        for (int i = 0; i * 2 < points.length && i < MARKER_KEYS.length; i++)
            marker(i, draft.plane(), new int[] { points[2 * i], points[2 * i + 1] }, depth,
                    i * 2 + 2 == draft.points().length ? PENDING_COLOUR : PREVIEW_COLOUR);
        PanelArrow preview = PanelArrow.drawn(0, draft.plane(), draft.depth(), arrows.style(), points);
        if (preview.pointCount() >= 2 && !preview.outline().isEmpty()) {
            outline(preview, PENDING_COLOUR, lines);
            return;
        }
        // Too short yet for its heads: the centre line, so the player still sees where it goes
        double height = depth + PROUD;
        for (int i = 0; i + 3 < points.length; i += 2)
            lines.line(draft.plane().at(points[i] / 256.0, points[i + 1] / 256.0, height),
                    draft.plane().at(points[i + 2] / 256.0, points[i + 3] / 256.0, height), REMOVE_COLOUR);
    }

    private static void drawGrab(LocalPlayer player, ArrowSettings arrows, ArrowGrab grab,
            ScrewdriverAim.Surface surface, BlockHitResult hit, Lines lines) {
        LargePanelBlockEntity from = LargePanelBlockEntity.cellAt(player.level(), grab.cell());
        PanelArrow arrow = from == null ? null : from.arrow(grab.id());
        if (arrow == null)
            return;
        outline(arrow, PICK_COLOUR, lines);
        if (surface == null || !surface.plane().equals(grab.plane())
                || SchematicArrowActions.depthOf(surface) != grab.depth())
            return;
        int[] to = SchematicArrowActions.pointFor(surface, hit.getLocation(), null,
                arrows.snap() == ArrowSnap.FREE ? ArrowSnap.FREE : ArrowSnap.GRID);
        outline(arrow.moved(to[0] - grab.u(), to[1] - grab.v()), PENDING_COLOUR, lines);
    }

    private static void drawStamp(ItemStack tool, ScrewdriverSettings settings, ScrewdriverAim.Surface surface,
            Vec3 point, Lines lines) {
        PanelPlane plane = surface.plane();
        GridCorner crosshair = GridCorner.snapped(plane, point, ScrewdriverActions.PAINT_STEP);
        marker(0, plane, new int[] { crosshair.u(), crosshair.v() }, surface.depth(), PENDING_COLOUR);
        GridCorner picked = settings.corner();
        if (picked == null || !picked.sharesPlaneWith(crosshair))
            return;
        marker(1, plane, new int[] { picked.u(), picked.v() }, surface.depth(), PENDING_COLOUR);
        rectangle(plane, surface.depth() + PROUD, Math.min(picked.u(), crosshair.u()),
                Math.min(picked.v(), crosshair.v()), Math.max(picked.u(), crosshair.u()),
                Math.max(picked.v(), crosshair.v()), PENDING_COLOUR, lines);
        DecalSelection selection = DecalSelection.read(tool);
        if (!selection.hasArt() || picked.u() == crosshair.u() || picked.v() == crosshair.v())
            return;
        int[] fit = PanelDecal.fit(picked.u(), picked.v(), crosshair.u(), crosshair.v(), selection.width(),
                selection.height(), settings.turn() & 3);
        rectangle(plane, surface.depth() + PROUD, fit[0], fit[1], fit[2], fit[3], PREVIEW_COLOUR, lines);
    }

    /** An arrow's outline at the height of its top: its rings, or the rectangle a stamped one fills. */
    private static void outline(PanelArrow arrow, int colour, Lines lines) {
        double scale = PanelPlane.UNITS_PER_BLOCK;
        double height = (arrow.depth() + arrow.style().height()) / scale + PROUD;
        PanelPlane plane = arrow.plane();
        if (arrow.isStamped()) {
            rectangle(plane, height, arrow.minU(), arrow.minV(), arrow.maxU(), arrow.maxV(), colour, lines);
            return;
        }
        List<double[]> rings = arrow.outline().rings();
        for (double[] ring : rings) {
            int m = ring.length / 2;
            for (int i = 0; i < m; i++) {
                int j = (i + 1) % m;
                lines.line(plane.at(ring[2 * i] / scale, ring[2 * i + 1] / scale, height),
                        plane.at(ring[2 * j] / scale, ring[2 * j + 1] / scale, height), colour);
            }
        }
    }

    private static void rectangle(PanelPlane plane, double height, int minU, int minV, int maxU, int maxV,
            int colour, Lines lines) {
        double scale = PanelPlane.UNITS_PER_BLOCK;
        Vec3 a = plane.at(minU / scale, minV / scale, height);
        Vec3 b = plane.at(maxU / scale, minV / scale, height);
        Vec3 c = plane.at(maxU / scale, maxV / scale, height);
        Vec3 d = plane.at(minU / scale, maxV / scale, height);
        lines.line(a, b, colour);
        lines.line(b, c, colour);
        lines.line(c, d, colour);
        lines.line(d, a, colour);
    }

    private static void marker(int key, PanelPlane plane, int[] point, double depth, int colour) {
        double scale = PanelPlane.UNITS_PER_BLOCK;
        Vec3 centre = plane.at(point[0] / scale, point[1] / scale, depth + PROUD);
        Outliner.getInstance().showAABB(MARKER_KEYS[key], new AABB(centre, centre).inflate(MARKER_RADIUS))
                .colored(colour).lineWidth(LINE_WIDTH);
    }

    /** Hands out line keys in order for one tick, up to the budget. */
    private static final class Lines {

        private int next;

        void line(Vec3 from, Vec3 to, int colour) {
            if (next >= LINE_KEYS.length)
                return;
            Outliner.getInstance().showLine(LINE_KEYS[next++], from, to).colored(colour).lineWidth(LINE_WIDTH);
        }
    }

    private static Object[] keys(int count) {
        Object[] keys = new Object[count];
        for (int i = 0; i < keys.length; i++)
            keys[i] = new Object();
        return keys;
    }
}
