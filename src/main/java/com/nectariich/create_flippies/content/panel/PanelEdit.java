package com.nectariich.create_flippies.content.panel;

import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;

import com.nectariich.create_flippies.foundation.panel.PanelCut;
import com.nectariich.create_flippies.foundation.panel.PanelDefinition;
import com.nectariich.create_flippies.foundation.panel.PanelPlane;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

/**
 * What an edit of an existing panel would change, worked out once and used by both
 * the overlay that draws it and the click that carries it out.
 *
 * This is {@code ScrewdriverAim}'s bargain applied to panels: a preview computed one
 * way and an edit computed another would agree everywhere except at the edges of a
 * tile, and the edge of a tile is exactly where somebody trimming a wall is aiming.
 * Both sides call {@link #aim} and {@link #plan} with the player and the tool's
 * settings, and neither touches a side-specific class.
 *
 * <h2>Why the aim is not the block under the crosshair</h2>
 *
 * Because half of what this mode is for is putting back a tile that is not there. A
 * click on a hole in a wall hits whatever is behind the wall, or nothing at all, so
 * a gesture that started from the block the ray struck could remove a tile and never
 * restore one.
 *
 * So the panel is found first and the crosshair is projected onto it afterwards.
 * Which panels are worth asking about is the only part of this that is a search, and
 * it is a short one: whatever lies along the ray, plus the panel the tool last
 * worked on. That second source is what makes a hole of any size workable — the wall
 * you have just taken four tiles out of is still the wall the tool is holding, so
 * aiming into the gap and clicking puts them back one at a time.
 *
 * Whichever candidate the ray meets nearest wins, so aiming at plate always means
 * the plate you are looking at, whatever the tool happens to remember.
 */
public final class PanelEdit {

    /** How far a panel may be from the eye to be edited, in blocks, matching the tool's other picks. */
    public static final double REACH = 8;

    /** How far along the ray panels are collected, in blocks. */
    private static final double STEP = 0.5;

    /**
     * Below this the ray runs along the panel rather than into it, and the
     * intersection is either everywhere or nowhere. A thousandth is far under any aim
     * a player can hold and far over the arithmetic.
     */
    private static final double GRAZING = 1.0e-3;

    /** Where on a panel the crosshair lands: the panel, the point, and where that is on it. */
    public record Target(PanelDefinition panel, Vec3 point, double u, double v) {}

    /** Why an edit would do nothing, or {@link #NONE} if it would. */
    public enum Problem {
        NONE,
        /** Nothing the tool can reach is a panel. */
        NO_PANEL,
        /** That piece of the panel has already been taken out. */
        ALREADY_GONE,
        /** There is nothing missing there to put back. */
        NOTHING_THERE,
        /** The panel would have nothing left of it at all. */
        NOTHING_LEFT,
        /** Shortening it that far would leave no panel. */
        TOO_SMALL,
        TOO_LARGE,
        TOO_MANY_CELLS,
        /** More separate holes than one panel will carry; see {@link PanelDefinition#MAX_CUTS}. */
        TOO_MANY_HOLES;

        public boolean ok() {
            return this == NONE;
        }

        public String key() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    /**
     * One edit: the panel as it is, as it would be, and the rectangle that would
     * change — which is what the preview draws and what a message reports.
     */
    public record Change(PanelDefinition was, PanelDefinition now, PanelCut area, Problem problem) {

        public boolean ok() {
            return problem.ok() && now != null;
        }
    }

    private PanelEdit() {}

    // Finding the panel

    /**
     * The panel the player is pointing at and where on it, or null if they are
     * pointing at none.
     *
     * {@code remembered} is a cell of the panel the tool last worked on, or null. It
     * is only ever a hint: a panel actually under the crosshair always wins, because
     * the candidates are compared by how far along the ray each is met.
     */
    public static Target aim(Level level, Player player, BlockPos remembered) {
        Vec3 eye = player.getEyePosition();
        Vec3 look = player.getLookAngle();

        Set<PanelDefinition> candidates = new LinkedHashSet<>();
        gather(level, remembered, candidates);
        for (double along = 0; along <= REACH; along += STEP) {
            BlockPos here = BlockPos.containing(eye.add(look.scale(along)));
            gather(level, here, candidates);
            // One block of slack either way, which is what lets a ray that goes
            // cleanly through a missing tile still find the tiles around it
            for (Direction side : Direction.values())
                gather(level, here.relative(side), candidates);
        }

        Target best = null;
        double nearest = Double.MAX_VALUE;
        for (PanelDefinition panel : candidates) {
            Target struck = strike(panel, eye, look);
            if (struck == null)
                continue;
            double distance = struck.point().distanceToSqr(eye);
            if (distance < nearest) {
                nearest = distance;
                best = struck;
            }
        }
        return best;
    }

    private static void gather(Level level, BlockPos pos, Set<PanelDefinition> found) {
        if (pos == null || !level.isLoaded(pos))
            return;
        LargePanelBlockEntity cell = LargePanelBlockEntity.cellAt(level, pos);
        if (cell != null)
            found.addAll(cell.panels());
    }

    /**
     * Where a ray meets one panel's rectangle, or null if it misses it.
     *
     * The midsurface is what is intersected, half a thickness behind the plane, which
     * is the same surface {@link PanelDefinition#forEachCell} walks. Taking the front
     * face instead would put the aim two pixels off along the plate at a grazing
     * angle, and two pixels is an eighth of a tile.
     *
     * Which side the panel faces makes no difference: depth is signed and the
     * solution is the same from either side, so a wall can be trimmed from the back
     * as readily as from the front.
     */
    private static Target strike(PanelDefinition panel, Vec3 eye, Vec3 look) {
        PanelPlane plane = panel.plane();
        double along = look.dot(plane.outward());
        if (Math.abs(along) < GRAZING)
            return null;

        double scale = PanelPlane.UNITS_PER_BLOCK;
        double mid = -PanelDefinition.THICKNESS / scale / 2;
        double distance = (mid - plane.depth(eye)) / along;
        if (distance <= 0 || distance > REACH)
            return null;

        Vec3 point = eye.add(look.scale(distance));
        double u = plane.u(point) * scale;
        double v = plane.v(point) * scale;
        if (u < panel.minU() || u > panel.maxU() || v < panel.minV() || v > panel.maxV())
            return null;
        return new Target(panel, point, u, v);
    }

    // Working out the edit

    /**
     * What a click would do to the panel that is aimed at.
     *
     * The right button does whatever the kind is named for and the left button undoes
     * it, which is the same bargain the rest of the tool strikes — rule a grid and
     * unrule it, protect a box and give it back. Which of the two takes material away
     * is therefore the kind's business and not the button's: taking a tile out is the
     * thing {@link PanelEditKind#TILE} is for, and so is <em>growing</em> a panel for
     * {@link PanelEditKind#EDGE}.
     *
     * @param size    the tool's size, in pixels, which the sized kinds use and
     *                {@link PanelEditKind#TILE} ignores
     * @param primary whether this is the right button
     */
    public static Change plan(Target target, PanelEditKind kind, int size, boolean primary) {
        if (target == null)
            return refused(Problem.NO_PANEL);
        return switch (kind) {
            case TILE -> tile(target, primary);
            case HOLE -> hole(target, size, primary);
            case EDGE -> edge(target, size, primary);
        };
    }

    /** One square of the panel's own lattice, taken out or put back. */
    private static Change tile(Target target, boolean removing) {
        // The right button is the one that takes the tile out, so the flag reads
        // straight through here
        int units = PanelPlane.UNITS_PER_BLOCK;
        int lowU = (int) Math.floor(target.u() / units) * units;
        int lowV = (int) Math.floor(target.v() / units) * units;
        PanelCut tile = new PanelCut(lowU, lowV, lowU + units, lowV + units);
        return cutOrFill(target.panel(), tile, removing);
    }

    /**
     * A square hole of the tool's size, on the pixel lattice.
     *
     * Counted from the pixel the crosshair is in rather than centred on the crosshair
     * itself, so that the hole lands on the pixel lines whatever fraction of a pixel
     * the aim was at — which is what lets two holes drilled a block apart line up. An
     * odd size is centred on the aimed pixel and an even one leans low, which is the
     * only way a lattice can share a centre between two pixels.
     */
    private static Change hole(Target target, int size, boolean drilling) {
        int step = PanelPlane.UNITS_PER_PIXEL;
        int lowU = ((int) Math.floor(target.u() / step) - (size - 1) / 2) * step;
        int lowV = ((int) Math.floor(target.v() / step) - (size - 1) / 2) * step;
        PanelCut hole = new PanelCut(lowU, lowV, lowU + size * step, lowV + size * step);
        if (drilling)
            return cutOrFill(target.panel(), hole, true);

        // Filling takes the whole hole rather than a square of it: the size the tool
        // happens to be set to now has nothing to do with the size it was drilled at,
        // and a fill that left a ring of the old hole behind would read as a failure
        PanelDefinition panel = target.panel();
        PanelCut there = panel.cutAt(target.u(), target.v());
        PanelDefinition now = panel.fillAt(target.u(), target.v());
        return now == null ? refused(Problem.NOTHING_THERE) : new Change(panel, now, there, Problem.NONE);
    }

    /** The shared half of the two kinds that take a rectangle out of the plate. */
    private static Change cutOrFill(PanelDefinition panel, PanelCut area, boolean removing) {
        PanelCut within = area.clippedTo(panel);
        if (within == null)
            return refused(Problem.NO_PANEL);

        PanelDefinition now = removing ? panel.cut(within) : panel.uncut(within);
        if (now == null)
            return refused(panel.cuts().size() >= PanelDefinition.MAX_CUTS ? Problem.TOO_MANY_HOLES
                    : removing ? Problem.ALREADY_GONE : Problem.NOTHING_THERE);
        if (removing && now.pieces().isEmpty())
            return refused(Problem.NOTHING_LEFT);
        return new Change(panel, now, within, Problem.NONE);
    }

    /**
     * The nearest edge of the panel, moved by the tool's size.
     *
     * Nearest in the plane's own units, which is the same comparison on a diagonal as
     * on a straight panel — the aim is already projected onto the plane, so nothing
     * here has to know which way the panel is turned.
     */
    private static Change edge(Target target, int size, boolean growing) {
        PanelDefinition panel = target.panel();
        int step = size * PanelPlane.UNITS_PER_PIXEL;
        double[] gaps = { target.u() - panel.minU(), panel.maxU() - target.u(), target.v() - panel.minV(),
                panel.maxV() - target.v() };

        int nearest = 0;
        for (int side = 1; side < gaps.length; side++)
            if (gaps[side] < gaps[nearest])
                nearest = side;

        // Out on a right click and in on a left one, whichever edge it is, so that the
        // two buttons always mean grow and shrink rather than low and high
        int move = growing ? -step : step;
        int minU = panel.minU() + (nearest == 0 ? move : 0);
        int maxU = panel.maxU() - (nearest == 1 ? move : 0);
        int minV = panel.minV() + (nearest == 2 ? move : 0);
        int maxV = panel.maxV() - (nearest == 3 ? move : 0);

        PanelDefinition now = panel.withBounds(minU, minV, maxU, maxV);
        PanelDefinition.Problem problem = now.check();
        if (!problem.ok())
            return refused(translate(problem));

        PanelCut area = nearest <= 1
                ? PanelCut.between(nearest == 0 ? panel.minU() : panel.maxU(), minV, nearest == 0 ? minU : maxU, maxV)
                : PanelCut.between(minU, nearest == 2 ? panel.minV() : panel.maxV(), maxU, nearest == 2 ? minV : maxV);
        return new Change(panel, now, area, Problem.NONE);
    }

    /** A panel's own refusal as one of this gesture's, since both end up in one message. */
    private static Problem translate(PanelDefinition.Problem problem) {
        return switch (problem) {
            case DEGENERATE -> Problem.TOO_SMALL;
            case TOO_LARGE -> Problem.TOO_LARGE;
            case TOO_MANY_CELLS -> Problem.TOO_MANY_CELLS;
            case NOTHING_LEFT -> Problem.NOTHING_LEFT;
            default -> Problem.NO_PANEL;
        };
    }

    private static Change refused(Problem problem) {
        return new Change(null, null, null, problem);
    }
}
