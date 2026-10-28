package com.nectariich.create_flippies.client.display;

import java.util.List;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.math.Axis;
import com.nectariich.create_flippies.CreateFlippies;
import com.nectariich.create_flippies.client.decal.ClientDecals;
import com.nectariich.create_flippies.client.design.ClientDesigns;
import com.nectariich.create_flippies.client.design.DesignFonts;
import com.nectariich.create_flippies.compat.Verts;
import com.nectariich.create_flippies.content.decal.DecalArt;
import com.nectariich.create_flippies.content.display.element.BarSettings;
import com.nectariich.create_flippies.content.display.element.ElementKind;
import com.nectariich.create_flippies.content.display.element.ElementPaint;
import com.nectariich.create_flippies.content.display.element.ScreenElement;
import com.nectariich.create_flippies.content.display.element.TextFit;
import com.nectariich.create_flippies.content.display.element.Viewport;
import com.nectariich.create_flippies.content.label.PanelLabel;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;
import net.minecraft.resources.ResourceLocation;
import org.joml.Matrix4f;

/**
 * Draws a display's {@link ScreenElement}s: one renderer for every display that has
 * them, whatever it is mounted on and wherever its viewport has been posed.
 *
 * Every display is named by an owner (its live {@code ScreenElements}, or a key of
 * the workbench preview's), which is what a bar's eased fill is remembered by
 * ({@link BarMotion}) and what lets the display editor draw its own version of the
 * display it is editing ({@link ElementPreview}).
 *
 * The caller poses the stack so that the viewport's top left corner is the origin,
 * +x runs right, +y runs down, and a unit is one pixel; everything here is in that
 * frame. Elements are drawn in list order, back to front, each on the viewport's own
 * plane through {@link ScreenRenderTypes}, which is what makes that order exact; see
 * there for why nothing writes depth.
 *
 * <h2>Power and light</h2>
 *
 * A display without its supply shows nothing, and so does one with nothing visible on
 * it (no elements, or only lettering with no text and no highlight): in both cases not
 * one buffer is asked for, because a request is what ends whatever batch the caller
 * had open. With the supply, a backlit display draws at full bright in the chosen
 * colours; an unlit one draws at the panel's own light and at {@link #UNLIT_INK} of
 * each colour, so it can be read in a lit room and vanishes in a dark one.
 *
 * <h2>Lettering</h2>
 *
 * Laid out by {@link TextFit} through {@link TextLayoutCache}, then placed in its box:
 * turned a quarter at a time about the box's centre, and aligned along and across the
 * line within the box as the element says. Centred, unturned lettering over the whole
 * viewport is drawn with exactly the transform and the cut the single-line display
 * used, so a display migrated from before elements looks as it did.
 *
 * <h2>Shapes and paint</h2>
 *
 * Every shape is a convex polygon in the one fill type ({@link ShapeGeometry}): a
 * rounded rectangle is its corners' arcs, an ellipse a ring of points, a frame or an
 * outlined ellipse the band between two such outlines, a line a quad with its heads.
 * A solid fill is one colour; a linear gradient is cut into slabs at its stops, inside
 * each of which blending the vertex colours is the gradient exactly; a radial one is
 * cut into a fine lattice coloured at every point. Triangles go into the quad type as
 * quads with their last corner twice.
 *
 * <h2>Bars and pictures</h2>
 *
 * A bar and a picture are laid out in their own turned frame, as lettering is: the box
 * along and across the turn, centred on the box's middle. A bar draws its track (the
 * highlight) over the whole box and its fill as far along as its value is along its
 * range, from the side its direction names; its gradient spans the whole box, so the
 * fill uncovers it as it grows, and with bands its one colour is the band its shown
 * value is in. A picture stretches its triangles over the box, in its own colours or
 * all in the element's (tinted); until its file has arrived it draws nothing.
 */
public final class ScreenElementRenderer {

    /** How much of a colour survives on a display whose backlight is off. */
    public static final float UNLIT_INK = 0.4f;

    /** A bitmap glyph's capitals, in its font units: the height a TTF's capitals are scaled to match. */
    private static final float BITMAP_CAP = 7;

    private static final float GLYPH = PanelLabel.GLYPH_HEIGHT;

    private static final float QUARTER = 90;

    private static final float TENTH = 1f / Viewport.UNITS_PER_PIXEL;

    private static final NoDepthWriteBuffers BUFFERS = new NoDepthWriteBuffers();

    /**
     * A display's own short-lived batch. Drawn and ended on the spot, so the elements
     * reach the screen in the order they were submitted whatever the caller's buffer
     * source would have done with them (a shader mod's buffer source, for one, groups
     * draws by type, which would sort every fill under every piece of lettering).
     */
    private static final MultiBufferSource.BufferSource IMMEDIATE =
            Verts.immediate(256);

    private ScreenElementRenderer() {}

    /** How finely a radial gradient's triangles are cut along each side. */
    private static final int RADIAL_CUTS = 10;

    /**
     * Whether drawing these would put anything on the glass. False without the supply,
     * and false when every element is lettering with no text and no highlight.
     */
    public static boolean hasVisible(List<ScreenElement> elements, boolean powered) {
        if (!powered)
            return false;
        for (ScreenElement element : elements)
            if (element.kind() != ElementKind.TEXT || !element.text().isEmpty() || element.highlight() != 0)
                return true;
        return false;
    }

    /** {@link #hasVisible(List, boolean)} for the display {@code owner} names, as the editor may be showing it. */
    public static boolean hasVisible(Object owner, List<ScreenElement> elements, boolean powered) {
        return hasVisible(ElementPreview.shown(owner, elements), powered);
    }

    /**
     * Draws the elements into a batch of their own and ends it at once, for a display
     * drawn in the block entity pass among other geometry.
     *
     * Whatever the caller's buffer source has open is ended first: that is the glass
     * behind the elements, and it has to be on the screen before anything blends over
     * it. Only a {@link MultiBufferSource.BufferSource} can be ended from here, though.
     * Anything else (the game's own wrapper around its buffer source for a block being
     * mined, a contraption's buffer) gets the elements in its own buffers instead, with
     * no viewport depth: the glass may still be pending in there, and depth written in
     * front of it would cut a hole through it where the viewport is. Drawn into the
     * game's wrapper, the first element's type switch ends the glass's batch anyway,
     * so the order still holds; what a buffer that groups by type does with it is up
     * to that buffer.
     */
    public static void drawNow(PoseStack ms, MultiBufferSource buffer, Object owner, List<ScreenElement> elements,
            Viewport viewport, boolean powered, boolean backlit, int light) {
        elements = ElementPreview.shown(owner, elements);
        if (!hasVisible(elements, powered))
            return;
        if (!(buffer instanceof MultiBufferSource.BufferSource source)) {
            draw(ms, buffer, owner, elements, viewport, powered, backlit, light, false, false);
            return;
        }
        source.endLastBatch();
        draw(ms, IMMEDIATE, owner, elements, viewport, powered, backlit, light, false, true);
        IMMEDIATE.endBatch();
    }

    /**
     * Draws the elements into {@code buffer}, back to front, then the viewport's depth.
     * {@code wrap} breaks lettering over several lines, which only the workbench's
     * preview text asks for.
     */
    public static void draw(PoseStack ms, MultiBufferSource buffer, Object owner, List<ScreenElement> elements,
            Viewport viewport, boolean powered, boolean backlit, int light, boolean wrap) {
        draw(ms, buffer, owner, ElementPreview.shown(owner, elements), viewport, powered, backlit, light, wrap, true);
    }

    /** {@code depth}: whether to finish with the viewport's depth; see {@link #drawNow}. */
    private static void draw(PoseStack ms, MultiBufferSource buffer, Object owner, List<ScreenElement> elements,
            Viewport viewport, boolean powered, boolean backlit, int light, boolean wrap, boolean depth) {
        if (!hasVisible(elements, powered))
            return;
        MultiBufferSource out = BUFFERS.wrapping(buffer);
        int lit = backlit ? LightTexture.FULL_BRIGHT : light;
        for (ScreenElement element : elements) {
            switch (element.kind()) {
                case FILL, FRAME -> {
                    if (!element.outline())
                        fill(out, ms.last().pose(), element, backlit, lit);
                    else if (element.radius() > 0)
                        roundedFrame(out, ms.last().pose(), element, ink(element.colour(), backlit), lit);
                    else
                        frame(out, ms.last().pose(), element, ink(element.colour(), backlit), lit);
                }
                case ELLIPSE -> ellipse(out, ms.last().pose(), element, backlit, lit);
                case LINE -> line(out, ms.last().pose(), element, ink(element.colour(), backlit), lit);
                case SVG -> art(ms, out, element, backlit, lit);
                case BAR -> bar(ms, out, owner, element, backlit, lit);
                case TEXT -> text(ms, out, element, backlit, lit, wrap);
            }
        }
        if (!depth)
            return;
        // Nothing above wrote depth, so this is what keeps a later draw on the glass's
        // plane from landing on top of the content; see ScreenRenderTypes
        Matrix4f pose = ms.last().pose();
        VertexConsumer quad = out.getBuffer(ScreenRenderTypes.VIEWPORT_DEPTH);
        float w = viewport.width() * TENTH, h = viewport.height() * TENTH;
        Verts.posColor(quad, pose, 0, 0, 0, 0, 0, 0, 0);
        Verts.posColor(quad, pose, 0, h, 0, 0, 0, 0, 0);
        Verts.posColor(quad, pose, w, h, 0, 0, 0, 0, 0);
        Verts.posColor(quad, pose, w, 0, 0, 0, 0, 0, 0);
    }

    /** Buffers asked for through this renderer since the game started, for checking dark displays ask for none. */
    public static long bufferRequests() {
        return BUFFERS.requests();
    }

    // ---- Shapes ----------------------------------------------------------------

    /** A rectangle, its corners rounded if it says so, in its paint. */
    private static void fill(MultiBufferSource out, Matrix4f pose, ScreenElement element, boolean backlit, int light) {
        if (element.radius() == 0 && !element.paint().gradient()) {
            rect(out, pose, element.x(), element.y(), element.w(), element.h(), ink(element.colour(), backlit), light);
            return;
        }
        float x = element.x() * TENTH, y = element.y() * TENTH, w = element.w() * TENTH, h = element.h() * TENTH;
        float r = element.radius() * TENTH;
        float[] outline = r > 0 ? ShapeGeometry.roundedRect(x, y, w, h, r, ShapeGeometry.cornerSegments(r))
                : new float[] { x, y, x + w, y, x + w, y + h, x, y + h };
        polygon(out.getBuffer(ScreenRenderTypes.FILL), pose, outline, element.paint(), x, y, w, h, element.colour(),
                backlit, light);
    }

    /** A hollow rectangle with rounded corners: the band between its outline and one a stroke inside it. */
    private static void roundedFrame(MultiBufferSource out, Matrix4f pose, ScreenElement element, int argb,
            int light) {
        float x = element.x() * TENTH, y = element.y() * TENTH, w = element.w() * TENTH, h = element.h() * TENTH;
        float r = element.radius() * TENTH, s = Math.min(element.stroke() * TENTH, Math.min(w, h) / 2);
        int segments = ShapeGeometry.cornerSegments(r);
        ring(out.getBuffer(ScreenRenderTypes.FILL), pose, ShapeGeometry.roundedRect(x, y, w, h, r, segments),
                ShapeGeometry.roundedRect(x + s, y + s, w - 2 * s, h - 2 * s, Math.max(0, r - s), segments), argb,
                light);
    }

    /** An ellipse filling the box: in its paint, or as an outline a stroke wide. */
    private static void ellipse(MultiBufferSource out, Matrix4f pose, ScreenElement element, boolean backlit,
            int light) {
        float x = element.x() * TENTH, y = element.y() * TENTH, w = element.w() * TENTH, h = element.h() * TENTH;
        float cx = x + w / 2, cy = y + h / 2;
        int segments = ShapeGeometry.ellipseSegments(w / 2, h / 2);
        float[] outer = ShapeGeometry.ellipse(cx, cy, w / 2, h / 2, segments);
        VertexConsumer vertices = out.getBuffer(ScreenRenderTypes.FILL);
        if (!element.outline()) {
            polygon(vertices, pose, outer, element.paint(), x, y, w, h, element.colour(), backlit, light);
            return;
        }
        float s = Math.min(element.stroke() * TENTH, Math.min(w, h) / 2);
        ring(vertices, pose, outer, ShapeGeometry.ellipse(cx, cy, w / 2 - s, h / 2 - s, segments),
                ink(element.colour(), backlit), light);
    }

    /**
     * A line a stroke thick from its start to its end, with its heads. A filled head's
     * base takes the line's place, so the line stops under it rather than poking
     * through the point; an open head is two strokes back from the point.
     */
    private static void line(MultiBufferSource out, Matrix4f pose, ScreenElement element, int argb, int light) {
        int[] start = element.lineStart(), end = element.lineEnd();
        float sx = start[0] * TENTH, sy = start[1] * TENTH, ex = end[0] * TENTH, ey = end[1] * TENTH;
        float length = (float) Math.hypot(ex - sx, ey - sy);
        if (length < 1e-3f)
            return;
        float ux = (ex - sx) / length, uy = (ey - sy) / length;
        float half = element.stroke() * TENTH / 2;
        boolean atStart = (element.heads() & ScreenElement.HEADS_START) != 0;
        boolean atEnd = (element.heads() & ScreenElement.HEADS_END) != 0;
        // A head is never longer than its share of the line
        float headLength = Math.min(element.headLength() * TENTH, atStart && atEnd ? length / 2 : length);
        float headHalf = element.headWidth() * TENTH / 2;
        boolean filled = element.headStyle() == ScreenElement.HEAD_TRIANGLE;
        VertexConsumer vertices = out.getBuffer(ScreenRenderTypes.FILL);
        // Tucked a little under a filled head, so no seam shows between them
        float trimStart = atStart && filled ? headLength * 0.9f : 0, trimEnd = atEnd && filled ? headLength * 0.9f : 0;
        segment(vertices, pose, sx + ux * trimStart, sy + uy * trimStart, ex - ux * trimEnd, ey - uy * trimEnd, half,
                argb, light);
        if (atStart)
            head(vertices, pose, sx, sy, -ux, -uy, headLength, headHalf, half, filled, argb, light);
        if (atEnd)
            head(vertices, pose, ex, ey, ux, uy, headLength, headHalf, half, filled, argb, light);
    }

    /** One arrow head with its point at (x, y), pointing along (ux, uy). */
    private static void head(VertexConsumer vertices, Matrix4f pose, float x, float y, float ux, float uy,
            float length, float half, float stroke, boolean filled, int argb, int light) {
        float bx = x - ux * length, by = y - uy * length;
        float nx = -uy * half, ny = ux * half;
        if (filled) {
            triangle(vertices, pose, x, y, argb, bx + nx, by + ny, argb, bx - nx, by - ny, argb, light);
            return;
        }
        segment(vertices, pose, x, y, bx + nx, by + ny, stroke, argb, light);
        segment(vertices, pose, x, y, bx - nx, by - ny, stroke, argb, light);
    }

    /** A straight stroke {@code 2 half} wide from one point to another. */
    private static void segment(VertexConsumer vertices, Matrix4f pose, float x0, float y0, float x1, float y1,
            float half, int argb, int light) {
        float length = (float) Math.hypot(x1 - x0, y1 - y0);
        if (length < 1e-4f)
            return;
        float nx = -(y1 - y0) / length * half, ny = (x1 - x0) / length * half;
        quad(vertices, pose, x0 + nx, y0 + ny, x1 + nx, y1 + ny, x1 - nx, y1 - ny, x0 - nx, y0 - ny, argb, light);
    }

    /**
     * A bar: its track over the whole box and its fill as far along as its shown value,
     * in its turned frame.
     */
    private static void bar(PoseStack ms, MultiBufferSource out, Object owner, ScreenElement element,
            boolean backlit, int light) {
        BarSettings settings = element.bar();
        float progress = BarMotion.shown(owner, element, settings.progress(element.value()));
        ms.pushPose();
        turnedFrame(ms, element);
        Matrix4f pose = ms.last().pose();
        float a = element.along() * TENTH / 2, c = element.across() * TENTH / 2;
        VertexConsumer vertices = out.getBuffer(ScreenRenderTypes.FILL);
        if (element.highlight() != 0) {
            int track = ink(element.highlight(), backlit);
            quad(vertices, pose, -a, -c, a, -c, a, c, -a, c, track, light);
        }
        if (progress > 0) {
            float x0 = -a, x1 = a, y0 = -c, y1 = c;
            switch (settings.direction()) {
                case BarSettings.RIGHT_TO_LEFT -> x0 = a - 2 * a * progress;
                case BarSettings.BOTTOM_TO_TOP -> y0 = c - 2 * c * progress;
                case BarSettings.TOP_TO_BOTTOM -> y1 = -c + 2 * c * progress;
                default -> x1 = -a + 2 * a * progress;
            }
            ElementPaint paint = element.paint();
            int colour = element.colour();
            if (paint.type() == ElementPaint.Type.BANDS)
                colour = paint.bandColour(settings.min() + progress * (settings.max() - settings.min()),
                        settings.min(), settings.max(), colour);
            polygon(vertices, pose, new float[] { x0, y0, x1, y0, x1, y1, x0, y1 }, paint, -a, -c, 2 * a, 2 * c,
                    colour, backlit, light);
        }
        ms.popPose();
    }

    /**
     * A picture stretched over the box in its turned frame, in its own colours or
     * tinted the element's. Asked for and left blank until it has arrived.
     */
    private static void art(PoseStack ms, MultiBufferSource out, ScreenElement element, boolean backlit, int light) {
        if (element.art().isEmpty())
            return;
        DecalArt art = ClientDecals.art(element.art());
        if (art == null) {
            ClientDesigns.ensureDecal(element.art());
            return;
        }
        ms.pushPose();
        turnedFrame(ms, element);
        Matrix4f pose = ms.last().pose();
        float a = element.along() * TENTH, c = element.across() * TENTH;
        VertexConsumer vertices = out.getBuffer(ScreenRenderTypes.FILL);
        int tint = ink(element.colour(), backlit);
        for (List<DecalArt.Layer> layers : List.of(art.layers(), art.pixels()))
            for (DecalArt.Layer layer : layers) {
                float[] t = layer.triangles();
                int[] shades = layer.shades();
                int flat = element.tint() ? tint : ink(layer.colour(), backlit);
                for (int i = 0; i + 5 < t.length; i += 6) {
                    int v = i / 2;
                    int c0 = shade(shades, v, flat, element.tint(), tint, backlit);
                    int c1 = shade(shades, v + 1, flat, element.tint(), tint, backlit);
                    int c2 = shade(shades, v + 2, flat, element.tint(), tint, backlit);
                    triangle(vertices, pose, -a / 2 + t[i] * a, -c / 2 + t[i + 1] * c, c0, -a / 2 + t[i + 2] * a,
                            -c / 2 + t[i + 3] * c, c1, -a / 2 + t[i + 4] * a, -c / 2 + t[i + 5] * c, c2, light);
                }
            }
        ms.popPose();
    }

    /** A picture's vertex colour: its shade where it has one, tinted keeping the shade's alpha. */
    private static int shade(int[] shades, int vertex, int flat, boolean tinted, int tint, boolean backlit) {
        if (shades == null || vertex >= shades.length)
            return flat;
        int shade = shades[vertex];
        return tinted ? (shade & 0xFF000000) | (tint & 0xFFFFFF) : ink(shade, backlit);
    }

    /**
     * Poses {@code ms} at the box's middle, turned as the element is, so the box runs
     * from minus half to plus half of {@link ScreenElement#along} across and of
     * {@link ScreenElement#across} down; in pixels.
     */
    private static void turnedFrame(PoseStack ms, ScreenElement element) {
        ms.translate((element.x() + 0.5f * element.w()) * TENTH, (element.y() + 0.5f * element.h()) * TENTH, 0);
        if (element.quarters() != 0)
            ms.mulPose(Axis.ZP.rotationDegrees(-QUARTER * element.quarters()));
    }

    /**
     * A convex polygon in a paint over the box (bx, by, bw, bh), all in pixels: solid
     * in {@code colour}, or shaded by the gradient (see the class comment).
     */
    private static void polygon(VertexConsumer vertices, Matrix4f pose, float[] outline, ElementPaint paint,
            float bx, float by, float bw, float bh, int colour, boolean backlit, int light) {
        if (outline.length < 6)
            return;
        if (!paint.gradient() || bw <= 0 || bh <= 0) {
            fan(vertices, pose, outline, ink(colour, backlit), light);
            return;
        }
        double aspect = bw / bh;
        if (paint.type() == ElementPaint.Type.LINEAR) {
            // Where a point is along the gradient is affine in the point; slab by slab
            // between the stops the colour is linear, so the vertices carry it exactly
            double p0 = paint.position(0, 0, aspect);
            double a = (paint.position(1, 0, aspect) - p0) / bw, b = (paint.position(0, 1, aspect) - p0) / bh;
            double c = p0 - a * bx - b * by;
            List<ElementPaint.Stop> stops = paint.stops();
            for (int i = 0; i <= stops.size(); i++) {
                double low = i == 0 ? Double.NEGATIVE_INFINITY : stops.get(i - 1).at() / (double) ElementPaint.ONE;
                double high = i == stops.size() ? Double.POSITIVE_INFINITY
                        : stops.get(i).at() / (double) ElementPaint.ONE;
                float[] piece = ShapeGeometry.slab(outline, a, b, c, low, high);
                if (piece.length < 6)
                    continue;
                int[] colours = new int[piece.length / 2];
                for (int k = 0; k < colours.length; k++)
                    colours[k] = ink(paint.colourAt(a * piece[2 * k] + b * piece[2 * k + 1] + c), backlit);
                for (int k = 1; k + 1 < colours.length; k++)
                    triangle(vertices, pose, piece[0], piece[1], colours[0], piece[2 * k], piece[2 * k + 1], colours[k],
                            piece[2 * k + 2], piece[2 * k + 3], colours[k + 1], light);
            }
            return;
        }
        // Radial: a fan from the middle, each of its triangles cut into a lattice
        float[] middle = ShapeGeometry.centroid(outline);
        int n = outline.length / 2;
        for (int k = 0; k < n; k++) {
            float px = outline[2 * k], py = outline[2 * k + 1];
            float qx = outline[2 * ((k + 1) % n)], qy = outline[2 * ((k + 1) % n) + 1];
            for (int i = 0; i < RADIAL_CUTS; i++)
                for (int j = 0; j < RADIAL_CUTS - i; j++) {
                    latticeTriangle(vertices, pose, middle, px, py, qx, qy, i, j, i + 1, j, i, j + 1, paint, bx, by,
                            bw, bh, aspect, backlit, light);
                    if (i + j < RADIAL_CUTS - 1)
                        latticeTriangle(vertices, pose, middle, px, py, qx, qy, i + 1, j, i + 1, j + 1, i, j + 1,
                                paint, bx, by, bw, bh, aspect, backlit, light);
                }
        }
    }

    /** One small triangle of a fan triangle's lattice, coloured by the gradient at each corner. */
    private static void latticeTriangle(VertexConsumer vertices, Matrix4f pose, float[] m, float px, float py,
            float qx, float qy, int i0, int j0, int i1, int j1, int i2, int j2, ElementPaint paint, float bx,
            float by, float bw, float bh, double aspect, boolean backlit, int light) {
        float[] a = lattice(m, px, py, qx, qy, i0, j0), b = lattice(m, px, py, qx, qy, i1, j1),
                c = lattice(m, px, py, qx, qy, i2, j2);
        triangle(vertices, pose, a[0], a[1], gradientAt(paint, a, bx, by, bw, bh, aspect, backlit), b[0], b[1],
                gradientAt(paint, b, bx, by, bw, bh, aspect, backlit), c[0], c[1],
                gradientAt(paint, c, bx, by, bw, bh, aspect, backlit), light);
    }

    private static float[] lattice(float[] m, float px, float py, float qx, float qy, int i, int j) {
        float u = i / (float) RADIAL_CUTS, v = j / (float) RADIAL_CUTS;
        return new float[] { m[0] + (px - m[0]) * u + (qx - m[0]) * v, m[1] + (py - m[1]) * u + (qy - m[1]) * v };
    }

    private static int gradientAt(ElementPaint paint, float[] point, float bx, float by, float bw, float bh,
            double aspect, boolean backlit) {
        return ink(paint.colourAt(paint.position((point[0] - bx) / bw, (point[1] - by) / bh, aspect)), backlit);
    }

    /** A convex polygon in one colour, as a fan from its first point. */
    private static void fan(VertexConsumer vertices, Matrix4f pose, float[] outline, int argb, int light) {
        for (int k = 2; k + 3 < outline.length; k += 2)
            triangle(vertices, pose, outline[0], outline[1], argb, outline[k], outline[k + 1], argb, outline[k + 2],
                    outline[k + 3], argb, light);
    }

    /** The band between two outlines with as many points each, in step. */
    private static void ring(VertexConsumer vertices, Matrix4f pose, float[] outer, float[] inner, int argb,
            int light) {
        int n = Math.min(outer.length, inner.length) / 2;
        for (int i = 0; i < n; i++) {
            int j = (i + 1) % n;
            quad(vertices, pose, outer[2 * i], outer[2 * i + 1], outer[2 * j], outer[2 * j + 1], inner[2 * j],
                    inner[2 * j + 1], inner[2 * i], inner[2 * i + 1], argb, light);
        }
    }

    private static void quad(VertexConsumer vertices, Matrix4f pose, float x0, float y0, float x1, float y1,
            float x2, float y2, float x3, float y3, int argb, int light) {
        vertex(vertices, pose, x0, y0, argb, light);
        vertex(vertices, pose, x1, y1, argb, light);
        vertex(vertices, pose, x2, y2, argb, light);
        vertex(vertices, pose, x3, y3, argb, light);
    }

    /** A triangle in the quad type: a quad whose last corner is its third twice. */
    private static void triangle(VertexConsumer vertices, Matrix4f pose, float ax, float ay, int ac, float bx,
            float by, int bc, float cx, float cy, int cc, int light) {
        vertex(vertices, pose, ax, ay, ac, light);
        vertex(vertices, pose, bx, by, bc, light);
        vertex(vertices, pose, cx, cy, cc, light);
        vertex(vertices, pose, cx, cy, cc, light);
    }

    private static void vertex(VertexConsumer vertices, Matrix4f pose, float x, float y, int argb, int light) {
        Verts.posColorLight(vertices, pose, x, y, 0, (argb >> 16) & 0xFF, (argb >> 8) & 0xFF, argb & 0xFF,
                argb >>> 24, light);
    }

    /** A hollow rectangle: a bar along the top and the bottom, and the sides between them. */
    private static void frame(MultiBufferSource out, Matrix4f pose, ScreenElement element, int argb, int light) {
        int x = element.x(), y = element.y(), w = element.w(), h = element.h();
        int s = Math.min(element.stroke(), Math.min(w, h));
        rect(out, pose, x, y, w, s, argb, light);
        if (h > s)
            rect(out, pose, x, y + h - Math.min(s, h - s), w, Math.min(s, h - s), argb, light);
        int side = h - 2 * s;
        if (side > 0) {
            rect(out, pose, x, y + s, s, side, argb, light);
            if (w > s)
                rect(out, pose, x + w - Math.min(s, w - s), y + s, Math.min(s, w - s), side, argb, light);
        }
    }

    /** One quad, its corners in tenths of a pixel. */
    private static void rect(MultiBufferSource out, Matrix4f pose, int x, int y, int w, int h, int argb,
            int light) {
        VertexConsumer vertices = out.getBuffer(ScreenRenderTypes.FILL);
        float x0 = x * TENTH, y0 = y * TENTH, x1 = (x + w) * TENTH, y1 = (y + h) * TENTH;
        int a = argb >>> 24, r = (argb >> 16) & 0xFF, g = (argb >> 8) & 0xFF, b = argb & 0xFF;
        Verts.posColorLight(vertices, pose, x0, y0, 0, r, g, b, a, light);
        Verts.posColorLight(vertices, pose, x0, y1, 0, r, g, b, a, light);
        Verts.posColorLight(vertices, pose, x1, y1, 0, r, g, b, a, light);
        Verts.posColorLight(vertices, pose, x1, y0, 0, r, g, b, a, light);
    }

    /**
     * One piece of lettering: its highlight over the box, then its lines.
     *
     * The font's units are scaled so the capitals are the asked height: a bitmap
     * glyph's eight units are {@code size} tenths tall, and a TTF's units are first
     * worth {@link #BITMAP_CAP} over its capital height. Vertical placement centres
     * the line's cell, which for a bitmap font is the whole eight-unit glyph (as the
     * single-line display did) and for a TTF its capitals.
     */
    private static void text(PoseStack ms, MultiBufferSource out, ScreenElement element, boolean backlit, int light,
            boolean wrap) {
        if (element.highlight() != 0)
            rect(out, ms.last().pose(), element.x(), element.y(), element.w(), element.h(),
                    ink(element.highlight(), backlit), light);
        if (element.text().isEmpty())
            return;
        Font font = Minecraft.getInstance().font;
        ResourceLocation fontId = DesignFonts.fontFor(element.font());
        boolean ttf = fontId.getNamespace().equals(CreateFlippies.MOD_ID);
        DesignFonts.Metrics metrics = DesignFonts.metrics(fontId);
        double unitScale = ttf ? BITMAP_CAP / metrics.capHeight() : 1;
        float cellTop = ttf ? metrics.capTop() : 0;
        float cellHeight = ttf ? metrics.capHeight() : GLYPH;
        Style style = Style.EMPTY.withFont(fontId).withBold(element.bold()).withItalic(element.italic());
        int along = element.along(), across = element.across();
        TextFit.Layout layout = TextLayoutCache.layout(element.text(), style, unitScale, element.size(), along,
                across, wrap);
        List<String> lines = layout.lines();
        if (lines.isEmpty() || (lines.size() == 1 && lines.get(0).isEmpty()))
            return;

        // Pixels per unit of this font
        float unit = (float) (layout.size() * TENTH / GLYPH * unitScale);
        float halfAlong = 0.5f * along * TENTH / unit;
        float halfAcross = 0.5f * across * TENTH / unit;
        float step = font.lineHeight;
        float block = (lines.size() - 1) * step + cellHeight;
        float top = switch (element.vAlign()) {
            case ScreenElement.ALIGN_START -> -halfAcross;
            case ScreenElement.ALIGN_END -> halfAcross - block;
            default -> -0.5f * block;
        };

        ms.pushPose();
        ms.translate((element.x() + 0.5f * element.w()) * TENTH, (element.y() + 0.5f * element.h()) * TENTH, 0);
        // The viewport frame already counts y downwards, so the turn that reads
        // anticlockwise to someone facing the glass is the negative one here
        if (element.quarters() != 0)
            ms.mulPose(Axis.ZP.rotationDegrees(-QUARTER * element.quarters()));
        ms.scale(unit, unit, unit);
        Matrix4f pose = ms.last().pose();
        int colour = ink(element.colour(), backlit);
        for (int i = 0; i < lines.size(); i++) {
            Component line = Component.literal(lines.get(i)).withStyle(style);
            // A string's measured width includes the blank column after its last
            // glyph, so aligning on it alone would sit the line half a unit off
            float width = font.width(line) - 1;
            float left = switch (element.hAlign()) {
                case ScreenElement.ALIGN_START -> -halfAlong;
                case ScreenElement.ALIGN_END -> halfAlong - width;
                default -> -0.5f * width;
            };
            font.drawInBatch(line, left, top + i * step - cellTop, colour, false, pose, out,
                    Font.DisplayMode.POLYGON_OFFSET, 0, light);
        }
        ms.popPose();
    }

    /** The colour as drawn: as chosen when backlit, {@link #UNLIT_INK} of it otherwise. */
    private static int ink(int argb, boolean backlit) {
        if (backlit)
            return argb;
        int red = (int) (((argb >> 16) & 0xFF) * UNLIT_INK);
        int green = (int) (((argb >> 8) & 0xFF) * UNLIT_INK);
        int blue = (int) ((argb & 0xFF) * UNLIT_INK);
        return (argb & 0xFF000000) | (red << 16) | (green << 8) | blue;
    }
}
