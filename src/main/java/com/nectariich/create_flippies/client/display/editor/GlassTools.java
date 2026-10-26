package com.nectariich.create_flippies.client.display.editor;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.mojang.math.Axis;
import com.nectariich.create_flippies.content.display.element.ElementKind;
import com.nectariich.create_flippies.content.display.element.ElementPaint;
import com.nectariich.create_flippies.content.display.element.ScreenElement;
import com.nectariich.create_flippies.content.display.element.Viewport;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/**
 * Direct manipulation on the display editor's glass: picking elements, dragging them,
 * resizing them, drawing new ones with the tool strip's tools, dragging gradient
 * handles, and the right-click menus; plus the overlay that shows all of it over the
 * world view.
 *
 * <h2>Coordinates</h2>
 *
 * Input arrives in the screen's GUI coordinates and is turned into the viewport's
 * tenths of a pixel through the {@link GlassView}, which is exact once the camera is
 * in place; while it is not ({@link GlassView#ready}) the glass takes nothing. All the
 * work is done in tenths, with {@link Snapping} deciding where a dragged coordinate
 * lands, and only distances the hand feels (how near a handle the pointer must be,
 * how far it moves before a press becomes a drag, how close a snap line pulls) are
 * measured in GUI pixels.
 *
 * <h2>Gestures</h2>
 *
 * A press is not a drag until the pointer has moved {@value #DRAG_START} GUI pixels;
 * until then a release is a click. Every drag that changes elements is one undo step:
 * it opens a {@link EditSession#beginGesture gesture} when it starts and closes it when
 * the button comes up. Each edit is computed from the elements as they were when the
 * press began, never from the last frame, so rounding never accumulates over a drag.
 *
 * <h2>Who wins a press</h2>
 *
 * Gradient handles (only while the inspector's fill section is open) before resize and
 * line end handles, those before elements, elements before empty glass. Hidden and
 * locked elements are never hit: hidden ones are not drawn, and locked ones are
 * selected from the hierarchy only.
 */
public final class GlassTools {

    /** How far (GUI px) a press must move to become a drag. */
    private static final double DRAG_START = 2;
    /** How near (GUI px) a viewport line pulls a dragged coordinate onto it. */
    private static final double SNAP_PIXELS = 4;
    /** How near (GUI px) a handle's centre the pointer must be to take it. */
    private static final double HANDLE_REACH = 4;
    /** The least distance (GUI px) a line can be picked from, however thin it is. */
    private static final double LINE_REACH = 3;
    /** Whole pixels, in tenths: the grid things snap to unless Alt is held. */
    private static final int PIXEL = Viewport.UNITS_PER_PIXEL;

    private static final int OUTLINE = 0x40FFFFFF, HOVERED = 0xB0FFFFFF, SELECTED = 0xFF3AA0FF,
            MARQUEE_FILL = 0x203AA0FF, GUIDE = 0xD0FF40C0, VIEWPORT = 0x30FFFFFF, HANDLE = 0xFFFFFFFF,
            HANDLE_EDGE = 0xFF10161C, GRADIENT = 0xFF22D3EE;

    /**
     * Where each resize handle sits along the box, as fractions across and down:
     * the four corners clockwise from the top left, then the four edges clockwise from
     * the top. 0 moves the left or top edge, 1 the right or bottom, a half neither.
     */
    private static final double[] HANDLE_U = { 0, 1, 1, 0, 0.5, 1, 0.5, 0 };
    private static final double[] HANDLE_V = { 0, 0, 1, 1, 0, 0.5, 1, 0.5 };

    private enum Mode {
        NONE,
        /** Pressed on an element with Select: a click picks, a drag moves. */
        PRESS_ELEMENT,
        /** Pressed on empty glass with Select: a click clears, a drag draws a selection rectangle. */
        PRESS_EMPTY,
        MARQUEE, MOVE, RESIZE, LINE_END, GRADIENT,
        /** A drawing tool's press: a click places a default element, a drag defines one. */
        DRAW
    }

    private final EditorContext context;

    private int width, height;
    private Mode mode = Mode.NONE;
    private boolean dragging, gestureOpen;
    private double pressX, pressY;
    private double[] pressAt;

    /** The element pressed, the handle taken, and the element as it was at the press. */
    private int pressedId = -1, handle = -1;
    private boolean pressedWasSelected;
    private ScreenElement original;
    /** For a move: every element moving, as it was at the press, and their bounds. */
    private final Map<Integer, ScreenElement> originals = new LinkedHashMap<>();
    private int groupX, groupY, groupW, groupH, lastDx, lastDy;
    /** For a selection rectangle: what was selected before it, kept with Shift. */
    private List<Integer> marqueeBase = List.of();
    private double[] marqueeAt;
    /** For a drawing tool: the snapped anchor and far corner (or a line's ends), in tenths. */
    private int drawX1, drawY1, drawX2, drawY2;
    /** The viewport lines the current drag has snapped to, for guides; NO_LINE for none. */
    private int guideX = Snapping.NO_LINE, guideY = Snapping.NO_LINE;

    public GlassTools(EditorContext context) {
        this.context = context;
    }

    /** Whether a press or drag is in progress, which the editor should not interrupt. */
    public boolean busy() {
        return mode != Mode.NONE;
    }

    /** Drops whatever press is in progress, closing its gesture; for when the editor closes mid-drag. */
    public void cancel() {
        if (gestureOpen)
            context.session.endGesture();
        reset();
    }

    // Input

    public boolean mouseClicked(double mx, double my, int button) {
        if (!context.view.ready() || busy() || context.session.live() == null)
            return false;
        double[] at = toViewport(mx, my);
        if (at == null)
            return false;
        if (button == 1) {
            openMenu(mx, my, at);
            return true;
        }
        if (button != 0)
            return false;
        pressX = mx;
        pressY = my;
        pressAt = at;
        dragging = false;

        if (context.tool != Tool.SELECT) {
            if (context.tool == Tool.SVG && context.pendingArt == null)
                return true;
            Viewport viewport = context.session.viewport();
            drawX1 = drawX2 = Snapping.clamp(snapX(at[0]).value(), 0, viewport.width());
            drawY1 = drawY2 = Snapping.clamp(snapY(at[1]).value(), 0, viewport.height());
            mode = Mode.DRAW;
            return true;
        }

        ScreenElement gradient = gradientTarget();
        if (gradient != null) {
            int taken = handleAt(gradientHandles(gradient), mx, my);
            if (taken >= 0) {
                begin(Mode.GRADIENT, gradient, taken);
                return true;
            }
        }
        ScreenElement single = singleSelected();
        if (single != null) {
            int taken = handleAt(single.kind() == ElementKind.LINE ? lineHandles(single) : boxHandles(single), mx, my);
            if (taken >= 0) {
                begin(single.kind() == ElementKind.LINE ? Mode.LINE_END : Mode.RESIZE, single, taken);
                return true;
            }
        }

        ScreenElement hit = elementAt(mx, my);
        Selection selection = context.selection;
        if (hit == null) {
            mode = Mode.PRESS_EMPTY;
            return true;
        }
        pressedId = hit.id();
        pressedWasSelected = selection.contains(hit.id());
        if (Screen.hasControlDown())
            selection.toggle(hit.id());
        else if (Screen.hasShiftDown())
            selection.add(hit.id());
        else if (!pressedWasSelected)
            selection.only(hit.id());
        mode = Mode.PRESS_ELEMENT;
        return true;
    }

    public boolean mouseDragged(double mx, double my, int button, double dragX, double dragY) {
        if (mode == Mode.NONE || button != 0)
            return false;
        if (!context.view.ready() || context.session.live() == null)
            return true;
        if (!dragging && Math.hypot(mx - pressX, my - pressY) < DRAG_START)
            return true;
        double[] at = toViewport(mx, my);
        if (at == null)
            return true;
        boolean starting = !dragging;
        dragging = true;
        switch (mode) {
            case PRESS_ELEMENT -> {
                if (starting && startMove())
                    moveTo(at);
            }
            case MOVE -> moveTo(at);
            case PRESS_EMPTY -> {
                mode = Mode.MARQUEE;
                marqueeBase = Screen.hasShiftDown() || Screen.hasControlDown() ? context.selection.ids() : List.of();
                marqueeTo(at);
            }
            case MARQUEE -> marqueeTo(at);
            case RESIZE -> {
                openGesture();
                resizeTo(at);
            }
            case LINE_END -> {
                openGesture();
                lineEndTo(at);
            }
            case GRADIENT -> {
                openGesture();
                gradientTo(at);
            }
            case DRAW -> drawTo(at);
            default -> {
            }
        }
        return true;
    }

    public boolean mouseReleased(double mx, double my, int button) {
        if (mode == Mode.NONE || button != 0)
            return false;
        try {
            if (!dragging)
                click();
            else if (mode == Mode.DRAW)
                finishDrawing();
        } finally {
            if (gestureOpen)
                context.session.endGesture();
            reset();
        }
        return true;
    }

    /** A press that never became a drag. */
    private void click() {
        Selection selection = context.selection;
        switch (mode) {
            case PRESS_EMPTY -> {
                if (!Screen.hasShiftDown() && !Screen.hasControlDown())
                    selection.clear();
            }
            case PRESS_ELEMENT -> {
                // A plain click on a member of a group picks it alone; the press kept the
                // group so that a drag would have moved all of it
                if (pressedWasSelected && !Screen.hasShiftDown() && !Screen.hasControlDown())
                    selection.only(pressedId);
            }
            case DRAW -> {
                if (context.tool.kind != null && context.view.ready()) {
                    String art = context.tool == Tool.SVG ? context.pendingArt : null;
                    context.commands().addAt(context.tool.kind, drawX1, drawY1, art);
                    context.shapeDone();
                }
            }
            default -> {
            }
        }
    }

    private void reset() {
        mode = Mode.NONE;
        dragging = false;
        gestureOpen = false;
        pressedId = -1;
        handle = -1;
        original = null;
        originals.clear();
        marqueeBase = List.of();
        marqueeAt = null;
        guideX = guideY = Snapping.NO_LINE;
    }

    private void begin(Mode mode, ScreenElement element, int handle) {
        this.mode = mode;
        this.original = element;
        this.pressedId = element.id();
        this.handle = handle;
    }

    private void openGesture() {
        if (!gestureOpen) {
            context.session.beginGesture();
            gestureOpen = true;
        }
    }

    // Moving

    /** Takes the selected elements that can move; false when there are none (a Ctrl-click that deselected). */
    private boolean startMove() {
        originals.clear();
        int left = Integer.MAX_VALUE, top = Integer.MAX_VALUE, right = Integer.MIN_VALUE, bottom = Integer.MIN_VALUE;
        for (int id : context.selection.ids()) {
            ScreenElement element = context.session.shown(id);
            if (element == null || element.locked() || context.hidden.contains(id))
                continue;
            originals.put(id, element);
            left = Math.min(left, element.x());
            top = Math.min(top, element.y());
            right = Math.max(right, element.x() + element.w());
            bottom = Math.max(bottom, element.y() + element.h());
        }
        if (originals.isEmpty() || !context.selection.contains(pressedId)) {
            // Dragging off an element just deselected (or a locked one) does nothing until release
            originals.clear();
            return false;
        }
        groupX = left;
        groupY = top;
        groupW = right - left;
        groupH = bottom - top;
        lastDx = lastDy = 0;
        mode = Mode.MOVE;
        openGesture();
        return true;
    }

    private void moveTo(double[] at) {
        if (originals.isEmpty())
            return;
        Viewport viewport = context.session.viewport();
        double x = groupX + at[0] - pressAt[0], y = groupY + at[1] - pressAt[1];
        Snapping.Snap sx = Screen.hasAltDown() ? new Snapping.Snap(Snapping.grid(x, 1), Snapping.NO_LINE)
                : Snapping.span(x, groupW, PIXEL, Snapping.lines(viewport.width()), threshold());
        Snapping.Snap sy = Screen.hasAltDown() ? new Snapping.Snap(Snapping.grid(y, 1), Snapping.NO_LINE)
                : Snapping.span(y, groupH, PIXEL, Snapping.lines(viewport.height()), threshold());
        int nx = Snapping.clamp(sx.value(), 0, viewport.width() - groupW);
        int ny = Snapping.clamp(sy.value(), 0, viewport.height() - groupH);
        guideX = nx == sx.value() ? sx.line() : Snapping.NO_LINE;
        guideY = ny == sy.value() ? sy.line() : Snapping.NO_LINE;
        int dx = nx - groupX, dy = ny - groupY;
        if (dx == lastDx && dy == lastDy)
            return;
        lastDx = dx;
        lastDy = dy;
        Map<Integer, ScreenElement> after = new LinkedHashMap<>();
        for (ScreenElement element : originals.values())
            after.put(element.id(), element.withBox(element.x() + dx, element.y() + dy, element.w(), element.h()));
        context.session.edit(after, null);
    }

    // Selection rectangle

    private void marqueeTo(double[] at) {
        marqueeAt = at;
        double left = Math.min(at[0], pressAt[0]), right = Math.max(at[0], pressAt[0]);
        double top = Math.min(at[1], pressAt[1]), bottom = Math.max(at[1], pressAt[1]);
        Set<Integer> picked = new LinkedHashSet<>(marqueeBase);
        for (ScreenElement element : context.session.shown()) {
            if (!pickable(element))
                continue;
            if (element.x() <= right && element.x() + element.w() >= left && element.y() <= bottom
                    && element.y() + element.h() >= top)
                picked.add(element.id());
        }
        context.selection.set(new ArrayList<>(picked));
    }

    // Resizing

    private void resizeTo(double[] at) {
        if (original == null)
            return;
        Viewport viewport = context.session.viewport();
        int vw = viewport.width(), vh = viewport.height();
        double u = HANDLE_U[handle], v = HANDLE_V[handle];
        double left = original.x(), top = original.y();
        double right = left + original.w(), bottom = top + original.h();
        Snapping.Snap sx = snapX(at[0]), sy = snapY(at[1]);
        guideX = u != 0.5 ? sx.line() : Snapping.NO_LINE;
        guideY = v != 0.5 ? sy.line() : Snapping.NO_LINE;
        int px = Snapping.clamp(sx.value(), 0, vw), py = Snapping.clamp(sy.value(), 0, vh);
        if (u == 0)
            left = px;
        else if (u == 1)
            right = px;
        if (v == 0)
            top = py;
        else if (v == 1)
            bottom = py;

        if (Screen.hasShiftDown() && original.w() > 0 && original.h() > 0) {
            double aspect = original.w() / (double) original.h();
            guideX = guideY = Snapping.NO_LINE;
            if (u != 0.5 && v != 0.5) {
                // A corner: the opposite corner stays, the box keeps its shape
                double ax = u == 0 ? right : left, ay = v == 0 ? bottom : top;
                double[] d = Snapping.keepAspect((u == 0 ? left : right) - ax, (v == 0 ? top : bottom) - ay, aspect);
                double f = Snapping.fitFraction(ax, ay, d[0], d[1], vw, vh);
                double cx = ax + d[0] * f, cy = ay + d[1] * f;
                if (u == 0)
                    left = cx;
                else
                    right = cx;
                if (v == 0)
                    top = cy;
                else
                    bottom = cy;
            } else if (u != 0.5) {
                // A side edge: the height follows, about the box's middle
                double centre = original.y() + original.h() / 2.0;
                double across = Math.abs(right - left), down = across / aspect;
                double most = 2 * Math.min(centre, vh - centre);
                if (down > most) {
                    down = most;
                    across = down * aspect;
                    if (u == 0)
                        left = right - Math.copySign(across, right - left);
                    else
                        right = left + Math.copySign(across, right - left);
                }
                top = centre - down / 2;
                bottom = centre + down / 2;
            } else {
                double centre = original.x() + original.w() / 2.0;
                double down = Math.abs(bottom - top), across = down * aspect;
                double most = 2 * Math.min(centre, vw - centre);
                if (across > most) {
                    across = most;
                    down = across / aspect;
                    if (v == 0)
                        top = bottom - Math.copySign(down, bottom - top);
                    else
                        bottom = top + Math.copySign(down, bottom - top);
                }
                left = centre - across / 2;
                right = centre + across / 2;
            }
        }

        // An edge dragged past its opposite flips the box rather than stopping it
        int x1 = (int) Math.round(Math.min(left, right)), x2 = (int) Math.round(Math.max(left, right));
        int y1 = (int) Math.round(Math.min(top, bottom)), y2 = (int) Math.round(Math.max(top, bottom));
        x1 = Snapping.clamp(x1, 0, vw - 1);
        y1 = Snapping.clamp(y1, 0, vh - 1);
        int w = Snapping.clamp(x2 - x1, 1, vw - x1), h = Snapping.clamp(y2 - y1, 1, vh - y1);
        ScreenElement now = context.session.shown(original.id());
        if (now == null)
            return;
        ScreenElement resized = now.withBox(x1, y1, w, h);
        if (!resized.equals(now))
            context.session.edit(Map.of(resized.id(), resized), null);
    }

    // Line ends

    private void lineEndTo(double[] at) {
        if (original == null)
            return;
        Viewport viewport = context.session.viewport();
        int[] fixed = handle == 0 ? original.lineEnd() : original.lineStart();
        int px, py;
        if (Screen.hasShiftDown()) {
            int[] p = diagonalFrom(fixed[0], fixed[1], at, viewport);
            px = p[0];
            py = p[1];
            guideX = guideY = Snapping.NO_LINE;
        } else {
            Snapping.Snap sx = snapX(at[0]), sy = snapY(at[1]);
            px = Snapping.clamp(sx.value(), 0, viewport.width());
            py = Snapping.clamp(sy.value(), 0, viewport.height());
            guideX = px == sx.value() ? sx.line() : Snapping.NO_LINE;
            guideY = py == sy.value() ? sy.line() : Snapping.NO_LINE;
        }
        ScreenElement now = context.session.shown(original.id());
        if (now == null)
            return;
        ScreenElement moved = handle == 0 ? now.withEnds(px, py, fixed[0], fixed[1])
                : now.withEnds(fixed[0], fixed[1], px, py);
        if (!moved.equals(now))
            context.session.edit(Map.of(moved.id(), moved), null);
    }

    /** The pointer turned to the nearest 45 degrees about (fx, fy), on the grid and inside the viewport. */
    private int[] diagonalFrom(int fx, int fy, double[] at, Viewport viewport) {
        double[] d = Snapping.diagonal(at[0] - fx, at[1] - fy);
        double f = Snapping.fitFraction(fx, fy, d[0], d[1], viewport.width(), viewport.height());
        int step = step();
        int dx = Snapping.grid(d[0] * f, step), dy = Snapping.grid(d[1] * f, step);
        // Both legs of a diagonal the same length, whatever the grid did to either
        if (dx != 0 && dy != 0) {
            int leg = Math.min(Math.abs(dx), Math.abs(dy));
            dx = Integer.signum(dx) * leg;
            dy = Integer.signum(dy) * leg;
        }
        return new int[] { Snapping.clamp(fx + dx, 0, viewport.width()),
                Snapping.clamp(fy + dy, 0, viewport.height()) };
    }

    // Gradient handles

    private void gradientTo(double[] pointer) {
        if (original == null)
            return;
        ScreenElement now = context.session.shown(original.id());
        if (now == null)
            return;
        ElementPaint paint = now.paint();
        // Worked out in the paint's own frame, which a bar turns with its quarter turns
        double[] at = toPaintFrame(original, pointer[0], pointer[1]);
        double w = Math.max(1, paintWidth(original)), h = Math.max(1, paintHeight(original));
        ElementPaint changed;
        if (paint.type() == ElementPaint.Type.LINEAR) {
            double dx = at[0] - w / 2, dy = at[1] - h / 2;
            if (handle == 0) {
                dx = -dx;
                dy = -dy;
            }
            if (dx == 0 && dy == 0)
                return;
            if (Screen.hasShiftDown()) {
                double[] d = Snapping.diagonal(dx, dy);
                dx = d[0];
                dy = d[1];
            }
            changed = paint.withAngle((int) Math.round(Math.toDegrees(Math.atan2(dy, dx))));
        } else if (handle == 0) {
            int cx = (int) Math.round(at[0] / w * ElementPaint.ONE);
            int cy = (int) Math.round(at[1] / h * ElementPaint.ONE);
            changed = paint.withCentre(cx, cy, paint.radius());
        } else {
            double cx = paint.cx() / (double) ElementPaint.ONE * w;
            double cy = paint.cy() / (double) ElementPaint.ONE * h;
            double du = (at[0] - cx) / w, dv = (at[1] - cy) / h;
            int radius = (int) Math.round(Math.hypot(du, dv) * ElementPaint.ONE);
            changed = paint.withCentre(paint.cx(), paint.cy(), radius);
        }
        if (!changed.equals(paint))
            context.session.edit(Map.of(now.id(), now.toBuilder().paint(changed).build()), null);
    }

    /**
     * The one selected element whose gradient the glass offers handles for, while the
     * inspector's fill section is open: a rectangle, a filled ellipse or a bar painted
     * with a linear or radial gradient.
     */
    private ScreenElement gradientTarget() {
        if (!context.fillOpen)
            return null;
        ScreenElement element = singleSelected();
        if (element == null)
            return null;
        ElementKind kind = element.kind();
        boolean filled = kind == ElementKind.BAR
                || (kind == ElementKind.FILL || kind == ElementKind.ELLIPSE) && !element.outline();
        ElementPaint.Type type = element.paint().type();
        return filled && (type == ElementPaint.Type.LINEAR || type == ElementPaint.Type.RADIAL) ? element : null;
    }

    /**
     * The gradient's handles, in the viewport's tenths: a linear one's start and end,
     * across the box through its middle at the paint's angle (as far as the box reaches
     * that way), or a radial one's centre and a point at its radius to the right. Both
     * are worked out in the paint's own frame and then turned with the box, the way the
     * renderer paints a bar's fill.
     */
    static double[][] gradientHandles(ScreenElement element) {
        ElementPaint paint = element.paint();
        double w = paintWidth(element), h = paintHeight(element);
        double[] a, b;
        if (paint.type() == ElementPaint.Type.LINEAR) {
            double radians = Math.toRadians(paint.angle());
            double dx = Math.cos(radians), dy = Math.sin(radians);
            double half = (Math.abs(dx) * w + Math.abs(dy) * h) / 2;
            a = new double[] { w / 2 - dx * half, h / 2 - dy * half };
            b = new double[] { w / 2 + dx * half, h / 2 + dy * half };
        } else {
            double cx = paint.cx() / (double) ElementPaint.ONE * w;
            double cy = paint.cy() / (double) ElementPaint.ONE * h;
            a = new double[] { cx, cy };
            b = new double[] { cx + paint.radius() / (double) ElementPaint.ONE * w, cy };
        }
        return new double[][] { fromPaintFrame(element, a[0], a[1]), fromPaintFrame(element, b[0], b[1]) };
    }

    /**
     * The paint's frame: the element's box as the renderer lays its fill out, from its top
     * left (0, 0) to ({@link #paintWidth}, {@link #paintHeight}), turned
     * about the box's centre by its quarter turns. Only a turning kind (of the painted
     * ones, the bar) is turned; for the rest it is the box itself. In tenths both ways.
     */
    static double[] fromPaintFrame(ScreenElement element, double u, double v) {
        int[] turn = quarterTurn(element);
        double lu = u - paintWidth(element) / 2.0, lv = v - paintHeight(element) / 2.0;
        return new double[] { element.x() + element.w() / 2.0 + lu * turn[0] - lv * turn[1],
                element.y() + element.h() / 2.0 + lu * turn[1] + lv * turn[0] };
    }

    /** The viewport point (tenths) in the paint's frame; the inverse of {@link #fromPaintFrame}. */
    static double[] toPaintFrame(ScreenElement element, double x, double y) {
        int[] turn = quarterTurn(element);
        double gx = x - (element.x() + element.w() / 2.0), gy = y - (element.y() + element.h() / 2.0);
        return new double[] { paintWidth(element) / 2.0 + gx * turn[0] + gy * turn[1],
                paintHeight(element) / 2.0 - gx * turn[1] + gy * turn[0] };
    }

    /** How far the paint's frame runs along: the box's length along a turning kind, else its width. */
    private static int paintWidth(ScreenElement element) {
        return element.kind().turns() ? element.along() : element.w();
    }

    private static int paintHeight(ScreenElement element) {
        return element.kind().turns() ? element.across() : element.h();
    }

    /**
     * The cosine and sine of the renderer's turn for this element, exact: it turns by
     * minus a quarter per quarter turn (see {@code ScreenElementRenderer.turnedFrame}).
     */
    private static int[] quarterTurn(ScreenElement element) {
        int q = element.kind().turns() ? Math.floorMod(element.quarters(), 4) : 0;
        return switch (q) {
            case 1 -> new int[] { 0, -1 };
            case 2 -> new int[] { -1, 0 };
            case 3 -> new int[] { 0, 1 };
            default -> new int[] { 1, 0 };
        };
    }

    // Drawing

    private void drawTo(double[] at) {
        Viewport viewport = context.session.viewport();
        int vw = viewport.width(), vh = viewport.height();
        boolean shift = Screen.hasShiftDown();
        if (shift && context.tool == Tool.LINE) {
            int[] p = diagonalFrom(drawX1, drawY1, at, viewport);
            drawX2 = p[0];
            drawY2 = p[1];
            guideX = guideY = Snapping.NO_LINE;
            return;
        }
        Snapping.Snap sx = snapX(at[0]), sy = snapY(at[1]);
        int px = Snapping.clamp(sx.value(), 0, vw), py = Snapping.clamp(sy.value(), 0, vh);
        guideX = px == sx.value() ? sx.line() : Snapping.NO_LINE;
        guideY = py == sy.value() ? sy.line() : Snapping.NO_LINE;
        if (shift) {
            // Squares and circles
            double[] d = Snapping.keepAspect(px - drawX1, py - drawY1, 1);
            double f = Snapping.fitFraction(drawX1, drawY1, d[0], d[1], vw, vh);
            px = drawX1 + (int) Math.round(d[0] * f);
            py = drawY1 + (int) Math.round(d[1] * f);
            guideX = guideY = Snapping.NO_LINE;
        }
        drawX2 = px;
        drawY2 = py;
    }

    private void finishDrawing() {
        ElementKind kind = context.tool.kind;
        if (kind == null || context.tool == Tool.SVG && context.pendingArt == null)
            return;
        List<Integer> free = context.session.freeIds(1);
        if (free.isEmpty()) {
            context.status(Component.translatable("gui.create_flippies.display_editor.full", ScreenElement.MAX_ID));
            return;
        }
        int id = free.get(0);
        ScreenElement element;
        if (kind == ElementKind.LINE) {
            element = ScreenElement.create(id, kind, 0, 0, 0, 0).withEnds(drawX1, drawY1, drawX2, drawY2);
        } else {
            int x = Math.min(drawX1, drawX2), y = Math.min(drawY1, drawY2);
            element = ScreenElement.create(id, kind, x, y, Math.max(1, Math.abs(drawX2 - drawX1)),
                    Math.max(1, Math.abs(drawY2 - drawY1)));
            if (kind == ElementKind.SVG)
                element = element.toBuilder().art(context.pendingArt).build();
        }
        context.session.put(List.of(element.clampedTo(context.session.viewport())));
        context.selection.only(id);
        context.shapeDone();
    }

    // Right-click menus

    private void openMenu(double mx, double my, double[] at) {
        EditorCommands commands = context.commands();
        List<ContextMenu.Entry> entries = new ArrayList<>();
        ScreenElement hit = elementAt(mx, my);
        if (hit != null) {
            if (!context.selection.contains(hit.id()))
                context.selection.only(hit.id());
            entries.add(entry("cut", true, commands::cut));
            entries.add(entry("copy", true, commands::copy));
            entries.add(entry("paste", commands.canPaste(), commands::paste));
            entries.add(entry("duplicate", true, commands::duplicate));
            entries.add(entry("delete", true, commands::delete));
            entries.add(null);
            entries.add(entry("bring_to_front", commands.canRaise(), commands::bringToFront));
            entries.add(entry("raise", commands.canRaise(), commands::raise));
            entries.add(entry("lower", commands.canLower(), commands::lower));
            entries.add(entry("send_to_back", commands.canLower(), commands::sendToBack));
        } else {
            double vx = at[0], vy = at[1];
            entries.add(entry("paste", commands.canPaste(), commands::paste));
            entries.add(null);
            entries.add(entry("add_text", true, () -> commands.addAt(ElementKind.TEXT, vx, vy, null)));
            entries.add(entry("add_rectangle", true, () -> commands.addAt(ElementKind.FILL, vx, vy, null)));
            entries.add(entry("add_frame", true, () -> commands.addAt(ElementKind.FRAME, vx, vy, null)));
            entries.add(entry("add_ellipse", true, () -> commands.addAt(ElementKind.ELLIPSE, vx, vy, null)));
            entries.add(entry("add_line", true, () -> commands.addAt(ElementKind.LINE, vx, vy, null)));
            entries.add(entry("add_bar", true, () -> commands.addAt(ElementKind.BAR, vx, vy, null)));
            entries.add(entry("add_svg", true,
                    () -> context.importSvg(hash -> commands.addAt(ElementKind.SVG, vx, vy, hash))));
        }
        context.openContextMenu(mx, my, entries);
    }

    private static ContextMenu.Entry entry(String key, boolean enabled, Runnable action) {
        return new ContextMenu.Entry(Component.translatable("gui.create_flippies.display_editor.menu." + key), enabled,
                action);
    }

    // Hit testing

    private boolean pickable(ScreenElement element) {
        return !element.locked() && !context.hidden.contains(element.id());
    }

    /** The frontmost pickable element under a screen point, or null. */
    private ScreenElement elementAt(double mx, double my) {
        List<ScreenElement> shown = context.session.shown();
        for (int i = shown.size() - 1; i >= 0; i--) {
            ScreenElement element = shown.get(i);
            if (pickable(element) && hits(element, mx, my))
                return element;
        }
        return null;
    }

    private boolean hits(ScreenElement element, double mx, double my) {
        if (element.kind() == ElementKind.LINE) {
            double[] a = toScreen(element.lineStart()), b = toScreen(element.lineEnd());
            if (a == null || b == null)
                return false;
            double reach = Math.max(element.stroke() / 2.0 / tenthsPerPixel(), LINE_REACH);
            return segmentDistance(mx, my, a[0], a[1], b[0], b[1]) <= reach;
        }
        double[] a = toScreen(element.x(), element.y());
        double[] b = toScreen(element.x() + element.w(), element.y() + element.h());
        if (a == null || b == null)
            return false;
        return mx >= Math.min(a[0], b[0]) && mx <= Math.max(a[0], b[0]) && my >= Math.min(a[1], b[1])
                && my <= Math.max(a[1], b[1]);
    }

    private static double segmentDistance(double px, double py, double ax, double ay, double bx, double by) {
        double dx = bx - ax, dy = by - ay;
        double length = dx * dx + dy * dy;
        double t = length == 0 ? 0 : Math.max(0, Math.min(1, ((px - ax) * dx + (py - ay) * dy) / length));
        return Math.hypot(px - (ax + t * dx), py - (ay + t * dy));
    }

    /** The one selected element, when exactly one is and it can be worked on the glass. */
    private ScreenElement singleSelected() {
        if (context.selection.size() != 1)
            return null;
        ScreenElement element = context.session.shown(context.selection.primary());
        return element != null && pickable(element) ? element : null;
    }

    private static double[][] boxHandles(ScreenElement element) {
        double[][] handles = new double[HANDLE_U.length][];
        for (int i = 0; i < handles.length; i++)
            handles[i] = new double[] { element.x() + HANDLE_U[i] * element.w(),
                    element.y() + HANDLE_V[i] * element.h() };
        return handles;
    }

    private static double[][] lineHandles(ScreenElement element) {
        int[] start = element.lineStart(), end = element.lineEnd();
        return new double[][] { { start[0], start[1] }, { end[0], end[1] } };
    }

    /** Which of these handles (in tenths) a screen point takes, nearest first; -1 for none. */
    private int handleAt(double[][] handles, double mx, double my) {
        int best = -1;
        double bestDistance = HANDLE_REACH;
        // Last first, so where handles coincide (a tiny box) the corner wins over the edge
        for (int i = handles.length - 1; i >= 0; i--) {
            double[] s = toScreen(handles[i][0], handles[i][1]);
            if (s == null)
                continue;
            double distance = Math.max(Math.abs(mx - s[0]), Math.abs(my - s[1]));
            if (distance <= bestDistance) {
                best = i;
                bestDistance = distance;
            }
        }
        return best;
    }

    // Mapping and snapping

    private int screenWidth() {
        return width > 0 ? width : Minecraft.getInstance().getWindow().getGuiScaledWidth();
    }

    private int screenHeight() {
        return height > 0 ? height : Minecraft.getInstance().getWindow().getGuiScaledHeight();
    }

    private double[] toViewport(double gx, double gy) {
        return context.view.toViewport(gx, gy, screenWidth(), screenHeight());
    }

    private double[] toScreen(double vx, double vy) {
        return context.view.toScreen(vx, vy, screenWidth(), screenHeight());
    }

    private double[] toScreen(int[] point) {
        return toScreen(point[0], point[1]);
    }

    /** Tenths of a pixel across one GUI pixel at the current zoom. */
    private double tenthsPerPixel() {
        double[] a = toViewport(0, 0), b = toViewport(1, 0);
        if (a == null || b == null)
            return PIXEL;
        double d = Math.hypot(b[0] - a[0], b[1] - a[1]);
        return d > 0 ? d : PIXEL;
    }

    private double threshold() {
        return SNAP_PIXELS * tenthsPerPixel();
    }

    /** Tenths with Alt held, whole pixels otherwise. */
    private static int step() {
        return Screen.hasAltDown() ? 1 : PIXEL;
    }

    /**
     * A coordinate across: onto the viewport's edges or centre line when near one,
     * else to whole pixels; with Alt, only to tenths, so it can be put anywhere.
     */
    private Snapping.Snap snapX(double value) {
        if (Screen.hasAltDown())
            return new Snapping.Snap(Snapping.grid(value, 1), Snapping.NO_LINE);
        return Snapping.point(value, PIXEL, Snapping.lines(context.session.viewport().width()), threshold());
    }

    private Snapping.Snap snapY(double value) {
        if (Screen.hasAltDown())
            return new Snapping.Snap(Snapping.grid(value, 1), Snapping.NO_LINE);
        return Snapping.point(value, PIXEL, Snapping.lines(context.session.viewport().height()), threshold());
    }

    // Drawing the overlay

    /** Draws the overlay over the world view; {@code width} and {@code height} are the screen's. */
    public void render(GuiGraphics graphics, int mouseX, int mouseY, int width, int height) {
        this.width = width;
        this.height = height;
        if (!context.view.ready() || context.session.live() == null)
            return;
        Viewport viewport = context.session.viewport();
        double[] topLeft = toScreen(0, 0), bottomRight = toScreen(viewport.width(), viewport.height());
        if (topLeft == null || bottomRight == null)
            return;
        outline(graphics, topLeft[0], topLeft[1], bottomRight[0], bottomRight[1], VIEWPORT);

        ScreenElement hovered = mode == Mode.NONE && context.tool == Tool.SELECT ? elementAt(mouseX, mouseY) : null;
        for (ScreenElement element : context.session.shown()) {
            if (context.hidden.contains(element.id()))
                continue;
            boolean selected = context.selection.contains(element.id());
            int colour = selected ? SELECTED : hovered != null && hovered.id() == element.id() ? HOVERED : OUTLINE;
            elementOutline(graphics, element, colour);
        }

        // Guides along the viewport lines the drag has snapped to
        if (guideX != Snapping.NO_LINE) {
            double[] s = toScreen(guideX, 0), e = toScreen(guideX, viewport.height());
            if (s != null && e != null)
                line(graphics, s[0], s[1], e[0], e[1], 1, GUIDE);
        }
        if (guideY != Snapping.NO_LINE) {
            double[] s = toScreen(0, guideY), e = toScreen(viewport.width(), guideY);
            if (s != null && e != null)
                line(graphics, s[0], s[1], e[0], e[1], 1, GUIDE);
        }

        if (mode == Mode.MARQUEE && marqueeAt != null) {
            double[] a = toScreen(pressAt[0], pressAt[1]), b = toScreen(marqueeAt[0], marqueeAt[1]);
            if (a != null && b != null) {
                rect(graphics, Math.min(a[0], b[0]), Math.min(a[1], b[1]), Math.max(a[0], b[0]),
                        Math.max(a[1], b[1]), MARQUEE_FILL);
                outline(graphics, a[0], a[1], b[0], b[1], SELECTED);
            }
        }

        if (mode == Mode.DRAW && dragging) {
            double[] a = toScreen(drawX1, drawY1), b = toScreen(drawX2, drawY2);
            if (a != null && b != null) {
                if (context.tool == Tool.LINE) {
                    line(graphics, a[0], a[1], b[0], b[1], 1, SELECTED);
                } else {
                    rect(graphics, Math.min(a[0], b[0]), Math.min(a[1], b[1]), Math.max(a[0], b[0]),
                            Math.max(a[1], b[1]), MARQUEE_FILL);
                    outline(graphics, a[0], a[1], b[0], b[1], SELECTED);
                }
            }
        }

        ScreenElement gradient = mode == Mode.NONE || mode == Mode.GRADIENT ? gradientTarget() : null;
        ScreenElement single = singleSelected();
        if (single != null && (mode == Mode.NONE || mode == Mode.RESIZE || mode == Mode.LINE_END)) {
            double[][] handles = single.kind() == ElementKind.LINE ? lineHandles(single) : boxHandles(single);
            for (double[] handle : handles) {
                double[] s = toScreen(handle[0], handle[1]);
                if (s != null)
                    squareHandle(graphics, s[0], s[1]);
            }
        }
        if (gradient != null)
            drawGradientHandles(graphics, gradient);
    }

    private void drawGradientHandles(GuiGraphics graphics, ScreenElement element) {
        double[][] handles = gradientHandles(element);
        double[] a = toScreen(handles[0][0], handles[0][1]), b = toScreen(handles[1][0], handles[1][1]);
        if (a == null || b == null)
            return;
        line(graphics, a[0], a[1], b[0], b[1], 1, HANDLE_EDGE);
        line(graphics, a[0], a[1], b[0], b[1], 0.5, GRADIENT);
        // The start (or the centre) hollow, the end (or the radius) full
        disc(graphics, a[0], a[1], 4, HANDLE_EDGE);
        disc(graphics, a[0], a[1], 3, GRADIENT);
        disc(graphics, a[0], a[1], 1.5, HANDLE_EDGE);
        disc(graphics, b[0], b[1], 4, HANDLE_EDGE);
        disc(graphics, b[0], b[1], 3, GRADIENT);
    }

    private void elementOutline(GuiGraphics graphics, ScreenElement element, int colour) {
        if (element.kind() == ElementKind.LINE) {
            double[] a = toScreen(element.lineStart()), b = toScreen(element.lineEnd());
            if (a != null && b != null)
                line(graphics, a[0], a[1], b[0], b[1], 1, colour);
            return;
        }
        double[] a = toScreen(element.x(), element.y());
        double[] b = toScreen(element.x() + element.w(), element.y() + element.h());
        if (a != null && b != null)
            outline(graphics, a[0], a[1], b[0], b[1], colour);
    }

    private static void squareHandle(GuiGraphics graphics, double x, double y) {
        rect(graphics, x - 3, y - 3, x + 3, y + 3, HANDLE_EDGE);
        rect(graphics, x - 2, y - 2, x + 2, y + 2, HANDLE);
    }

    /** A filled rectangle at fractional GUI coordinates, which {@link GuiGraphics#fill} rounds away. */
    private static void rect(GuiGraphics graphics, double x1, double y1, double x2, double y2, int colour) {
        if (x2 <= x1 || y2 <= y1)
            return;
        graphics.pose().pushPose();
        graphics.pose().translate(x1, y1, 0);
        graphics.pose().scale((float) (x2 - x1), (float) (y2 - y1), 1);
        graphics.fill(0, 0, 1, 1, colour);
        graphics.pose().popPose();
    }

    /** A one GUI pixel outline just inside the rectangle between two corners, in either order. */
    private static void outline(GuiGraphics graphics, double ax, double ay, double bx, double by, int colour) {
        double x1 = Math.min(ax, bx), x2 = Math.max(ax, bx), y1 = Math.min(ay, by), y2 = Math.max(ay, by);
        // Never thinner than a line, so a one-tenth box still shows
        x2 = Math.max(x2, x1 + 1);
        y2 = Math.max(y2, y1 + 1);
        double t = Math.min(1, Math.min(x2 - x1, y2 - y1) / 2);
        rect(graphics, x1, y1, x2, y1 + t, colour);
        rect(graphics, x1, y2 - t, x2, y2, colour);
        rect(graphics, x1, y1 + t, x1 + t, y2 - t, colour);
        rect(graphics, x2 - t, y1 + t, x2, y2 - t, colour);
    }

    private static void line(GuiGraphics graphics, double x1, double y1, double x2, double y2, double thickness,
            int colour) {
        double dx = x2 - x1, dy = y2 - y1;
        double length = Math.hypot(dx, dy);
        if (length < 1e-6)
            return;
        graphics.pose().pushPose();
        graphics.pose().translate(x1, y1, 0);
        graphics.pose().mulPose(Axis.ZP.rotation((float) Math.atan2(dy, dx)));
        graphics.pose().translate(0, -thickness / 2, 0);
        graphics.pose().scale((float) length, (float) thickness, 1);
        graphics.fill(0, 0, 1, 1, colour);
        graphics.pose().popPose();
    }

    /** A round dot, a row of rectangles at a time. */
    private static void disc(GuiGraphics graphics, double cx, double cy, double radius, int colour) {
        int rows = (int) Math.ceil(2 * radius);
        double top = cy - rows / 2.0;
        for (int i = 0; i < rows; i++) {
            double mid = top + i + 0.5 - cy;
            double half = Math.sqrt(Math.max(0, radius * radius - mid * mid));
            rect(graphics, cx - half, top + i, cx + half, top + i + 1, colour);
        }
    }
}
