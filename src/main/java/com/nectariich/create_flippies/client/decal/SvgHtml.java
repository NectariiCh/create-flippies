package com.nectariich.create_flippies.client.decal;

import java.awt.Shape;
import java.awt.geom.AffineTransform;
import java.awt.geom.Area;
import java.awt.geom.Path2D;
import java.awt.geom.Rectangle2D;
import java.awt.geom.RoundRectangle2D;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

/**
 * The static HTML inside an SVG's {@code foreignObject}, laid out as shapes and
 * pictures for the importer to paint.
 *
 * <h2>Why it is laid out here and not rasterised</h2>
 *
 * There is no browser to hand, and Swing's HTML renderer would need a window
 * toolkit the game does not run headless with, understands little more CSS than
 * this does, and would turn crisp text into pixels. Laying the text out with the
 * same glyph outlines SVG text already uses keeps it vector: it is flattened with
 * the rest of the drawing, stays sharp at any size it is painted, and can be raised
 * on a stamped arrow. What this does is CSS normal flow in its simplest form, which
 * is what the HTML editors such as draw.io put in a {@code foreignObject}: blocks
 * stacked down the box with their margins, borders, padding and backgrounds, and
 * inside them words wrapped into lines and aligned. Lists get their markers, line
 * breaks and preformatted text are kept, and {@code img} is a picture like an
 * SVG {@code image}.
 *
 * What is left out: floats, positioning, tables as tables (their cells are stacked
 * as blocks), inline backgrounds and borders, and anything that moves or reacts,
 * which the importer has already taken out of the document.
 *
 * Coordinates are CSS pixels from the top left of the {@code foreignObject}, which
 * are its user units.
 */
final class SvgHtml {

    /** What the layout needs from the importer. */
    interface Host {

        /** An element's own declarations, from its attributes, the style sheets and its {@code style}. */
        Map<String, String> declared(Element element);

        /** A picture's size in pixels, or null where it cannot be read (and that has been said). */
        double[] imageSize(String src);

        void warn(String warning);
    }

    /** Something to paint, in the order it is listed. */
    sealed interface Item permits Fill, Img {}

    /** A shape in one colour, ARGB in the low thirty-two bits, at an opacity. */
    record Fill(Shape shape, long colour, double opacity) implements Item {}

    /** A picture stretched or fitted into a box, as {@code preserveAspectRatio} says. */
    record Img(String src, Rectangle2D box, String aspect, double opacity) implements Item {}

    private static final double FONT_SIZE = 16;

    private static final int MAX_NODES = 20_000;

    private static final int MAX_DEPTH = 48;

    /** Where the opacity everything inside an element is painted at is kept in its style. */
    private static final String OPACITY = "-flippies-opacity";

    /** Properties an element takes from its parent. Decoration is not one in CSS, but it does reach the text inside. */
    private static final Set<String> INHERITED = Set.of("color", "font-family", "font-size", "font-weight",
            "font-style", "text-align", "line-height", "white-space", "list-style-type", "word-spacing", "visibility",
            "text-decoration", "text-decoration-line", OPACITY);

    private static final Set<String> BLOCKS = Set.of("html", "body", "div", "p", "h1", "h2", "h3", "h4", "h5", "h6",
            "ul", "ol", "li", "dl", "dt", "dd", "section", "article", "header", "footer", "nav", "main", "aside",
            "blockquote", "pre", "figure", "figcaption", "address", "hr", "table", "thead", "tbody", "tfoot", "tr",
            "td", "th", "caption", "form", "fieldset", "legend", "center", "details", "summary");

    /** Elements that draw nothing: the head of a document, and what has no static picture of its own. */
    private static final Set<String> SILENT = Set.of("head", "title", "meta", "link", "style", "script", "template",
            "noscript", "base");

    private final Host host;
    private final List<Item> items = new ArrayList<>();
    private final Map<Map<String, String>, Metrics> metrics = new IdentityHashMap<>();
    private int nodes;
    private double lastMargin;
    private double firstBaseline = Double.NaN;

    private SvgHtml(Host host) {
        this.host = host;
    }

    /**
     * Lays out what a {@code foreignObject} holds in a box {@code width} wide, with
     * the text properties it inherits from the drawing around it.
     */
    static List<Item> layout(Element foreignObject, Map<String, String> inherited, double width, Host host) {
        SvgHtml html = new SvgHtml(host);
        Map<String, String> root = new HashMap<>();
        root.put("color", "black");
        root.put("font-family", "serif");
        root.put(OPACITY, "1");
        for (String property : new String[] { "color", "font-family", "font-weight", "font-style" })
            if (inherited.containsKey(property))
                root.put(property, inherited.get(property));
        root.put("font-size", Double.toString(SvgDecalImporter.fontSize(inherited.get("font-size"), FONT_SIZE, null)));
        html.content(foreignObject, root, 0, 0, width, 0);
        return html.items;
    }

    // Blocks

    /** The children of an element laid down from {@code top}; how far down they reach. */
    private double content(Element element, Map<String, String> style, double left, double top, double width,
            int depth) {
        List<Piece> pending = new ArrayList<>();
        boolean[] space = { true };
        double cursor = top;
        lastMargin = 0;
        for (Node node : nodes(element)) {
            if (node.getNodeType() == Node.TEXT_NODE || node.getNodeType() == Node.CDATA_SECTION_NODE) {
                text(node.getNodeValue(), style, pending, space);
                continue;
            }
            if (!(node instanceof Element child))
                continue;
            Map<String, String> own = styleOf(child, style);
            if (own == null)
                continue;
            if (isBlock(child, own)) {
                if (!pending.isEmpty())
                    cursor += flush(pending, style, left, cursor, width);
                space[0] = true;
                cursor += block(child, own, left, cursor, width, depth + 1);
            } else {
                inline(child, own, pending, space, depth + 1);
            }
        }
        if (!pending.isEmpty())
            cursor += flush(pending, style, left, cursor, width);
        return cursor - top;
    }

    /** Lays out the inline content gathered so far; a margin only collapses across it if it came to no lines. */
    private double flush(List<Piece> pending, Map<String, String> style, double left, double top, double width) {
        double height = lines(pending, style, left, top, width);
        if (height > 0)
            lastMargin = 0;
        return height;
    }

    /**
     * One block, its top margin collapsed with the bottom margin of the block before
     * it; how far down it reaches, its own bottom margin included.
     */
    private double block(Element element, Map<String, String> style, double left, double top, double available,
            int depth) {
        if (depth > MAX_DEPTH || ++nodes > MAX_NODES)
            return 0;
        String tag = tag(element);
        if (tag.equals("table") || tag.equals("tr"))
            host.warn("HTML tables are laid out one cell under another");
        double size = fontSize(style);
        double[] margin = box(style, "margin", available, size);
        double[] padding = box(style, "padding", available, size);
        Border border = border(style, available, size);
        boolean borderBox = "border-box".equals(style.get("box-sizing"));
        double frame = padding[1] + padding[3] + border.widths[1] + border.widths[3];
        double width = cssLength(style.get("width"), available, size);
        double contentWidth = Double.isNaN(width) ? available - margin[1] - margin[3] - frame
                : borderBox ? width - frame : width;
        contentWidth = Math.max(0, contentWidth);
        double marginLeft = margin[3];
        if (!Double.isNaN(width) && isAuto(style, "margin", 3) && isAuto(style, "margin", 1))
            marginLeft = (available - contentWidth - frame) / 2;
        double marginTop = Math.max(margin[0], lastMargin) - lastMargin;

        double boxLeft = left + marginLeft;
        double boxTop = top + marginTop;
        double contentLeft = boxLeft + border.widths[3] + padding[3];
        double contentTop = boxTop + border.widths[0] + padding[0];
        int start = items.size();
        double outerBaseline = firstBaseline;
        firstBaseline = Double.NaN;
        double laid = content(element, style, contentLeft, contentTop, contentWidth, depth);
        double height = laid;
        double fixed = cssLength(style.get("height"), Double.NaN, size);
        if (!Double.isNaN(fixed)) {
            height = Math.max(0, borderBox ? fixed - padding[0] - padding[2] - border.widths[0] - border.widths[2]
                    : fixed);
            String display = style.getOrDefault("display", "");
            if (display.contains("flex") && style.getOrDefault("align-items", "").contains("center"))
                shift(start, (height - laid) / 2);
        }
        double boxWidth = contentWidth + frame;
        double boxHeight = height + padding[0] + padding[2] + border.widths[0] + border.widths[2];

        List<Item> under = new ArrayList<>();
        if (!hidden(style))
            decorate(style, border, boxLeft, boxTop, boxWidth, boxHeight, size, available, under);
        if ("list-item".equals(style.get("display")) && !hidden(style))
            marker(element, style, contentLeft, Double.isNaN(firstBaseline)
                    ? contentTop + metrics(style).ascent : firstBaseline, under);
        items.addAll(start, under);
        if (!Double.isNaN(outerBaseline))
            firstBaseline = outerBaseline;
        lastMargin = margin[2];
        return marginTop + boxHeight + margin[2];
    }

    /** Moves everything listed from {@code start} on down by {@code dy}. */
    private void shift(int start, double dy) {
        if (dy == 0)
            return;
        AffineTransform move = AffineTransform.getTranslateInstance(0, dy);
        for (int i = start; i < items.size(); i++) {
            Item item = items.get(i);
            if (item instanceof Fill fill)
                items.set(i, new Fill(move.createTransformedShape(fill.shape()), fill.colour(), fill.opacity()));
            else if (item instanceof Img img)
                items.set(i, new Img(img.src(), move.createTransformedShape(img.box()).getBounds2D(), img.aspect(),
                        img.opacity()));
        }
    }

    /** A block's background and border, which go beneath everything in it. */
    private void decorate(Map<String, String> style, Border border, double x, double y, double width, double height,
            double size, double available, List<Item> into) {
        double opacity = opacity(style);
        double radius = Math.max(0, cssLength(first(style.get("border-radius")), Math.min(width, height), size));
        if (Double.isNaN(radius))
            radius = 0;
        radius = Math.min(radius, Math.min(width, height) / 2);
        long background = background(style);
        if (background != SvgColours.NONE && width > 0 && height > 0)
            into.add(new Fill(radius > 0 ? new RoundRectangle2D.Double(x, y, width, height, 2 * radius, 2 * radius)
                    : new Rectangle2D.Double(x, y, width, height), background, opacity));
        double[] w = border.widths;
        if (radius > 0 && w[0] > 0) {
            Area ring = new Area(new RoundRectangle2D.Double(x, y, width, height, 2 * radius, 2 * radius));
            double inner = Math.max(0, radius - w[0]);
            ring.subtract(new Area(new RoundRectangle2D.Double(x + w[0], y + w[0], width - 2 * w[0],
                    height - 2 * w[0], 2 * inner, 2 * inner)));
            into.add(new Fill(ring, border.colours[0], opacity));
            return;
        }
        if (w[0] > 0)
            into.add(new Fill(new Rectangle2D.Double(x, y, width, w[0]), border.colours[0], opacity));
        if (w[2] > 0)
            into.add(new Fill(new Rectangle2D.Double(x, y + height - w[2], width, w[2]), border.colours[2], opacity));
        if (w[3] > 0)
            into.add(new Fill(new Rectangle2D.Double(x, y + w[0], w[3], height - w[0] - w[2]), border.colours[3],
                    opacity));
        if (w[1] > 0)
            into.add(new Fill(new Rectangle2D.Double(x + width - w[1], y + w[0], w[1], height - w[0] - w[2]),
                    border.colours[1], opacity));
    }

    /** A list item's bullet or number, set just left of its first line. */
    private void marker(Element element, Map<String, String> style, double contentLeft, double baseline,
            List<Item> into) {
        String type = style.getOrDefault("list-style-type", "disc").trim().toLowerCase(Locale.ROOT);
        int number = 1;
        Node parent = element.getParentNode();
        if (parent instanceof Element list && list.hasAttribute("start"))
            number = (int) number(list.getAttribute("start"), 1);
        for (Node at = element.getPreviousSibling(); at != null; at = at.getPreviousSibling())
            if (at instanceof Element sibling && tag(sibling).equals("li"))
                number++;
        String text = switch (type) {
            case "none" -> null;
            case "circle" -> "◦";
            case "square" -> "▪";
            case "decimal", "decimal-leading-zero" -> number + ".";
            case "lower-alpha", "lower-latin" -> (char) ('a' + (number - 1) % 26) + ".";
            case "upper-alpha", "upper-latin" -> (char) ('A' + (number - 1) % 26) + ".";
            default -> "•";
        };
        long colour = colour(style);
        if (text == null || colour == SvgColours.NONE)
            return;
        double size = fontSize(style);
        SvgFonts.Laid laid = SvgFonts.layout(metrics(style).face.font(), text, size);
        double x = contentLeft - size / 2 - laid.advance();
        into.add(new Fill(AffineTransform.getTranslateInstance(x, baseline).createTransformedShape(laid.outline()),
                colour, opacity(style)));
    }

    // Inline content

    /** A piece of a line: a word, a space, a forced break, or a picture. */
    private abstract static class Piece {
        final Map<String, String> style;
        double width;

        Piece(Map<String, String> style) {
            this.style = style;
        }
    }

    private static final class Word extends Piece {
        final String text;
        SvgFonts.Laid laid;

        Word(String text, Map<String, String> style) {
            super(style);
            this.text = text;
        }
    }

    private static final class Space extends Piece {
        final boolean kept;

        Space(Map<String, String> style, boolean kept) {
            super(style);
            this.kept = kept;
        }
    }

    private static final class Break extends Piece {
        Break(Map<String, String> style) {
            super(style);
        }
    }

    private static final class Picture extends Piece {
        final String src;
        final double height;
        final String aspect;

        Picture(Map<String, String> style, String src, double width, double height, String aspect) {
            super(style);
            this.src = src;
            this.width = width;
            this.height = height;
            this.aspect = aspect;
        }
    }

    private void inline(Element element, Map<String, String> style, List<Piece> pending, boolean[] space, int depth) {
        if (depth > MAX_DEPTH || ++nodes > MAX_NODES)
            return;
        String tag = tag(element);
        if (tag.equals("br")) {
            pending.add(new Break(style));
            space[0] = true;
            return;
        }
        if (tag.equals("img")) {
            picture(element, style, pending);
            space[0] = false;
            return;
        }
        if (tag.equals("svg") || tag.equals("math")) {
            host.warn("Drawings and formulas inside HTML are left out");
            return;
        }
        for (Node node : nodes(element)) {
            if (node.getNodeType() == Node.TEXT_NODE || node.getNodeType() == Node.CDATA_SECTION_NODE) {
                text(node.getNodeValue(), style, pending, space);
                continue;
            }
            if (!(node instanceof Element child))
                continue;
            Map<String, String> own = styleOf(child, style);
            if (own == null)
                continue;
            // A block inside an inline element goes on lines of its own
            boolean block = isBlock(child, own);
            if (block)
                pending.add(new Break(own));
            inline(child, own, pending, space, depth + 1);
            if (block) {
                pending.add(new Break(own));
                space[0] = true;
            }
        }
    }

    /** A text node as words and spaces, white space treated as {@code white-space} says. */
    private static void text(String data, Map<String, String> style, List<Piece> pending, boolean[] space) {
        String mode = style.getOrDefault("white-space", "normal").trim();
        boolean keepSpaces = mode.equals("pre") || mode.equals("pre-wrap") || mode.equals("break-spaces");
        boolean keepBreaks = keepSpaces || mode.equals("pre-line");
        StringBuilder word = new StringBuilder();
        for (int i = 0; i < data.length();) {
            int c = data.codePointAt(i);
            i += Character.charCount(c);
            if (c == '\r')
                continue;
            if (c == '\n' && keepBreaks) {
                word = endWord(word, style, pending);
                pending.add(new Break(style));
                space[0] = true;
                continue;
            }
            if (c == ' ' || c == '\t' || c == '\n' || c == '\f') {
                word = endWord(word, style, pending);
                if (keepSpaces) {
                    for (int k = c == '\t' ? 4 : 1; k > 0; k--)
                        pending.add(new Space(style, true));
                } else if (!space[0]) {
                    pending.add(new Space(style, false));
                    space[0] = true;
                }
                continue;
            }
            word.appendCodePoint(c);
            space[0] = false;
        }
        endWord(word, style, pending);
    }

    private static StringBuilder endWord(StringBuilder word, Map<String, String> style, List<Piece> pending) {
        if (word.length() == 0)
            return word;
        pending.add(new Word(word.toString(), style));
        return new StringBuilder();
    }

    /** An {@code img}, at the size its style or attributes give it, else its own. */
    private void picture(Element element, Map<String, String> style, List<Piece> pending) {
        String src = element.getAttribute("src").trim();
        if (src.isEmpty())
            return;
        double size = fontSize(style);
        double width = cssLength(style.get("width"), Double.NaN, size);
        double height = cssLength(style.get("height"), Double.NaN, size);
        if (Double.isNaN(width) && element.hasAttribute("width"))
            width = number(element.getAttribute("width"), Double.NaN);
        if (Double.isNaN(height) && element.hasAttribute("height"))
            height = number(element.getAttribute("height"), Double.NaN);
        if (Double.isNaN(width) || Double.isNaN(height)) {
            double[] natural = host.imageSize(src);
            if (natural == null)
                return;
            if (Double.isNaN(width) && Double.isNaN(height)) {
                width = natural[0];
                height = natural[1];
            } else if (Double.isNaN(width)) {
                width = height * natural[0] / natural[1];
            } else {
                height = width * natural[1] / natural[0];
            }
        }
        if (!(width > 0) || !(height > 0))
            return;
        String aspect = switch (style.getOrDefault("object-fit", "fill").trim()) {
            case "contain", "scale-down" -> "xMidYMid meet";
            case "cover" -> "xMidYMid slice";
            default -> "none";
        };
        pending.add(new Picture(style, src, width, height, aspect));
    }

    /**
     * Words wrapped into lines {@code width} wide from {@code top}, each line as tall
     * as its tallest piece needs, and painted; how far down they reach.
     */
    private double lines(List<Piece> pending, Map<String, String> block, double left, double top, double width) {
        String mode = block.getOrDefault("white-space", "normal").trim();
        boolean wrap = !mode.equals("pre") && !mode.equals("nowrap");
        List<List<Piece>> lines = new ArrayList<>();
        List<Piece> line = new ArrayList<>();
        double x = 0;
        for (Piece piece : pending) {
            if (piece instanceof Break) {
                lines.add(line);
                line = new ArrayList<>();
                x = 0;
                continue;
            }
            measure(piece);
            if (piece instanceof Space space && !space.kept && line.isEmpty())
                continue;
            if (wrap && !(piece instanceof Space) && x + piece.width > width + 1e-6 && hasContent(line)) {
                lines.add(line);
                line = new ArrayList<>();
                x = 0;
            }
            line.add(piece);
            x += piece.width;
        }
        if (!line.isEmpty())
            lines.add(line);

        double y = top;
        for (List<Piece> laid : lines) {
            while (!laid.isEmpty() && laid.get(laid.size() - 1) instanceof Space space && !space.kept)
                laid.remove(laid.size() - 1);
            y += line(laid, block, left, y, width);
        }
        pending.clear();
        return y - top;
    }

    private static boolean hasContent(List<Piece> line) {
        for (Piece piece : line)
            if (!(piece instanceof Space))
                return true;
        return false;
    }

    private void measure(Piece piece) {
        if (piece instanceof Word word) {
            word.laid = SvgFonts.layout(metrics(word.style).face.font(), word.text, fontSize(word.style));
            word.width = word.laid.advance();
        } else if (piece instanceof Space) {
            double spacing = cssLength(piece.style.get("word-spacing"), 0, fontSize(piece.style));
            piece.width = metrics(piece.style).space + (spacing > 0 ? spacing : 0);
        }
    }

    /** One line, painted; its height. */
    private double line(List<Piece> line, Map<String, String> block, double left, double top, double width) {
        Metrics strut = metrics(block);
        double strutHeight = lineHeight(block);
        double above = (strutHeight - strut.ascent - strut.descent) / 2 + strut.ascent;
        double below = strutHeight - above;
        double used = 0;
        for (Piece piece : line) {
            used += piece.width;
            if (piece instanceof Picture picture) {
                above = Math.max(above, picture.height);
                continue;
            }
            Metrics own = metrics(piece.style);
            double height = lineHeight(piece.style);
            double over = (height - own.ascent - own.descent) / 2 + own.ascent;
            above = Math.max(above, over);
            below = Math.max(below, height - over);
        }
        double baseline = top + above;
        if (Double.isNaN(firstBaseline) && !line.isEmpty())
            firstBaseline = baseline;

        String align = block.getOrDefault("text-align", "start").trim();
        double x = left + switch (align) {
            case "center", "-webkit-center" -> (width - used) / 2;
            case "right", "end", "-webkit-right" -> width - used;
            default -> 0;
        };
        Map<String, Path2D.Double> paths = new LinkedHashMap<>();
        Map<String, Fill> paint = new HashMap<>();
        for (Piece piece : line) {
            if (!hidden(piece.style)) {
                if (piece instanceof Picture picture) {
                    items.add(new Img(picture.src, new Rectangle2D.Double(x, baseline - picture.height, picture.width,
                            picture.height), picture.aspect, opacity(piece.style)));
                } else {
                    long colour = colour(piece.style);
                    if (colour != SvgColours.NONE) {
                        double opacity = opacity(piece.style);
                        String key = colour + "/" + opacity;
                        Path2D.Double path = paths.computeIfAbsent(key, k -> new Path2D.Double(Path2D.WIND_NON_ZERO));
                        paint.putIfAbsent(key, new Fill(path, colour, opacity));
                        if (piece instanceof Word word)
                            path.append(AffineTransform.getTranslateInstance(x, baseline)
                                    .createTransformedShape(word.laid.outline()), false);
                        decoration(piece, x, baseline, path);
                    }
                }
            }
            x += piece.width;
        }
        for (String key : paths.keySet())
            items.add(paint.get(key));
        return above + below;
    }

    /** An underline or a line through, as a thin bar under or across a piece. */
    private void decoration(Piece piece, double x, double baseline, Path2D.Double into) {
        String decoration = piece.style.getOrDefault("text-decoration-line",
                piece.style.getOrDefault("text-decoration", ""));
        if (piece.width <= 0 || decoration.isEmpty())
            return;
        double size = fontSize(piece.style);
        double thickness = Math.max(size / 16, 0.5);
        if (decoration.contains("underline"))
            into.append(new Rectangle2D.Double(x, baseline + size * 0.1, piece.width, thickness), false);
        if (decoration.contains("line-through"))
            into.append(new Rectangle2D.Double(x, baseline - size * 0.3, piece.width, thickness), false);
    }

    // Styles

    /** A font's face and the measures a line needs of it, per style. */
    private record Metrics(SvgFonts.Face face, double ascent, double descent, double space) {}

    private Metrics metrics(Map<String, String> style) {
        return metrics.computeIfAbsent(style, s -> {
            SvgFonts.Face face = SvgFonts.face(s.get("font-family"), s.get("font-weight"), s.get("font-style"));
            if (face.substituted())
                host.warn("Some fonts are not installed on this computer and were drawn in a similar one");
            SvgFonts.Laid laid = SvgFonts.layout(face.font(), " ", fontSize(s));
            return new Metrics(face, laid.ascent(), laid.descent(), laid.advance());
        });
    }

    private static double lineHeight(Map<String, String> style) {
        double size = fontSize(style);
        String value = style.getOrDefault("line-height", "normal").trim();
        if (value.equals("normal"))
            return size * 1.2;
        double plain = number(value, Double.NaN);
        if (!Double.isNaN(plain) && !value.endsWith("%"))
            return plain * size;
        double length = cssLength(value, size, size);
        return Double.isNaN(length) ? size * 1.2 : length;
    }

    /**
     * An element's style: what it inherits, under the browser's defaults for its
     * tag, under what it declares. Null for an element that draws nothing.
     */
    private Map<String, String> styleOf(Element element, Map<String, String> parent) {
        String tag = tag(element);
        if (SILENT.contains(tag))
            return null;
        Map<String, String> own = new HashMap<>(defaults(tag));
        if (element.hasAttribute("align"))
            own.put("text-align", element.getAttribute("align").trim().toLowerCase(Locale.ROOT));
        if (element.hasAttribute("bgcolor"))
            own.put("background-color", element.getAttribute("bgcolor"));
        if (tag.equals("font") && element.hasAttribute("face"))
            own.put("font-family", element.getAttribute("face"));
        own.putAll(host.declared(element));
        if ("none".equals(own.get("display")))
            return null;
        Map<String, String> style = new HashMap<>();
        for (String property : INHERITED)
            if (parent.containsKey(property))
                style.put(property, parent.get(property));
        own.forEach((property, value) -> {
            if (!"inherit".equals(value))
                style.put(property, value);
            else if (parent.containsKey(property))
                style.put(property, parent.get(property));
        });
        double parentSize = fontSize(parent);
        style.put("font-size", Double.toString(SvgDecalImporter.fontSize(own.get("font-size"), parentSize, null)));
        style.put(OPACITY, Double.toString(opacity(parent)
                * Math.max(0, Math.min(1, number(own.get("opacity"), 1)))));
        return style;
    }

    /** What a browser's own style sheet gives each tag. */
    private static Map<String, String> defaults(String tag) {
        return switch (tag) {
            case "body" -> Map.of("margin", "8px");
            case "p", "dl" -> Map.of("margin", "1em 0");
            case "h1" -> Map.of("font-size", "2em", "font-weight", "bold", "margin", "0.67em 0");
            case "h2" -> Map.of("font-size", "1.5em", "font-weight", "bold", "margin", "0.83em 0");
            case "h3" -> Map.of("font-size", "1.17em", "font-weight", "bold", "margin", "1em 0");
            case "h4" -> Map.of("font-weight", "bold", "margin", "1.33em 0");
            case "h5" -> Map.of("font-size", "0.83em", "font-weight", "bold", "margin", "1.67em 0");
            case "h6" -> Map.of("font-size", "0.67em", "font-weight", "bold", "margin", "2.33em 0");
            case "ul" -> Map.of("margin", "1em 0", "padding-left", "40px", "list-style-type", "disc");
            case "ol" -> Map.of("margin", "1em 0", "padding-left", "40px", "list-style-type", "decimal");
            case "li" -> Map.of("display", "list-item");
            case "dd" -> Map.of("margin-left", "40px");
            case "blockquote", "figure" -> Map.of("margin", "1em 40px");
            case "pre" -> Map.of("font-family", "monospace", "white-space", "pre", "margin", "1em 0");
            case "hr" -> Map.of("border", "1px solid gray", "margin", "0.5em 0");
            case "b", "strong" -> Map.of("font-weight", "bold");
            case "th" -> Map.of("font-weight", "bold", "text-align", "center");
            case "i", "em", "cite", "var", "dfn" -> Map.of("font-style", "italic");
            case "code", "kbd", "samp", "tt" -> Map.of("font-family", "monospace");
            case "small", "sub", "sup" -> Map.of("font-size", "smaller");
            case "big" -> Map.of("font-size", "larger");
            case "u", "ins" -> Map.of("text-decoration", "underline");
            case "s", "strike", "del" -> Map.of("text-decoration", "line-through");
            case "a" -> Map.of("color", "#0000EE", "text-decoration", "underline");
            case "center" -> Map.of("text-align", "center");
            default -> Map.of();
        };
    }

    private static boolean isBlock(Element element, Map<String, String> style) {
        String display = style.get("display");
        if (display == null)
            return BLOCKS.contains(tag(element));
        return switch (display.trim()) {
            case "block", "flex", "grid", "list-item", "table", "table-row", "table-cell", "table-caption",
                    "flow-root", "table-row-group", "table-header-group", "table-footer-group" -> true;
            default -> false;
        };
    }

    private static boolean hidden(Map<String, String> style) {
        String visibility = style.get("visibility");
        return "hidden".equals(visibility) || "collapse".equals(visibility);
    }

    private static double fontSize(Map<String, String> style) {
        return number(style.get("font-size"), FONT_SIZE);
    }

    private static double opacity(Map<String, String> style) {
        return number(style.get(OPACITY), 1);
    }

    private static long colour(Map<String, String> style) {
        return SvgColours.parse(style.getOrDefault("color", "black"), 0xFF000000L);
    }

    /** A block's background colour, from {@code background-color} or the colour in {@code background}. */
    private long background(Map<String, String> style) {
        long current = colour(style);
        String plain = style.get("background-color");
        if (plain != null) {
            long colour = SvgColours.parse(plain.trim(), current);
            if (colour != SvgColours.NONE)
                return colour;
        }
        String shorthand = style.get("background");
        if (shorthand == null)
            return SvgColours.NONE;
        long found = SvgColours.NONE;
        for (String token : tokens(shorthand)) {
            if (token.contains("url(") || token.contains("gradient(")) {
                host.warn("Background pictures and gradients in HTML are left out");
                continue;
            }
            long colour = SvgColours.parse(token, current);
            if (colour != SvgColours.NONE)
                found = colour;
        }
        return found;
    }

    /** A block's four border widths and colours, top, right, bottom, left. */
    private record Border(double[] widths, long[] colours) {}

    private static Border border(Map<String, String> style, double available, double size) {
        String[] sides = { "top", "right", "bottom", "left" };
        double[] widths = new double[4];
        long[] colours = new long[4];
        long current = colour(style);
        String[] widthBox = expand(style.get("border-width"));
        String[] styleBox = expand(style.get("border-style"));
        String[] colourBox = expand(style.get("border-color"));
        for (int i = 0; i < 4; i++) {
            String width = null;
            String line = null;
            String colour = null;
            for (String shorthand : new String[] { style.get("border"), style.get("border-" + sides[i]) }) {
                if (shorthand == null)
                    continue;
                for (String token : tokens(shorthand)) {
                    if (isLineStyle(token))
                        line = token;
                    else if (borderWidth(token, size) >= 0)
                        width = token;
                    else
                        colour = token;
                }
            }
            if (widthBox != null)
                width = widthBox[i];
            if (styleBox != null)
                line = styleBox[i];
            if (colourBox != null)
                colour = colourBox[i];
            width = style.getOrDefault("border-" + sides[i] + "-width", width);
            line = style.getOrDefault("border-" + sides[i] + "-style", line);
            colour = style.getOrDefault("border-" + sides[i] + "-color", colour);
            if (line == null || line.equals("none") || line.equals("hidden"))
                continue;
            long parsed = colour == null ? current : SvgColours.parse(colour, current);
            if (parsed == SvgColours.NONE)
                continue;
            widths[i] = Math.max(0, borderWidth(width == null ? "medium" : width, size));
            colours[i] = parsed;
        }
        return new Border(widths, colours);
    }

    private static boolean isLineStyle(String token) {
        return switch (token) {
            case "none", "hidden", "solid", "dashed", "dotted", "double", "groove", "ridge", "inset", "outset" -> true;
            default -> false;
        };
    }

    /** A border width in pixels, or minus one for a token that is not one. */
    private static double borderWidth(String token, double size) {
        return switch (token) {
            case "thin" -> 1;
            case "medium" -> 3;
            case "thick" -> 5;
            default -> {
                double length = cssLength(token, 0, size);
                yield Double.isNaN(length) ? -1 : length;
            }
        };
    }

    /** {@code margin} or {@code padding} as top, right, bottom, left, with any side set on its own taking over; auto is nought. */
    private static double[] box(Map<String, String> style, String property, double available, double size) {
        String[] sides = { "top", "right", "bottom", "left" };
        String[] values = expand(style.get(property));
        double[] out = new double[4];
        for (int i = 0; i < 4; i++) {
            String value = style.getOrDefault(property + "-" + sides[i], values == null ? null : values[i]);
            double length = cssLength(value, available, size);
            out[i] = Double.isNaN(length) ? 0 : length;
        }
        return out;
    }

    private static boolean isAuto(Map<String, String> style, String property, int side) {
        String[] sides = { "top", "right", "bottom", "left" };
        String[] values = expand(style.get(property));
        String value = style.getOrDefault(property + "-" + sides[side], values == null ? null : values[side]);
        return "auto".equals(value);
    }

    /** One to four values of a box shorthand as top, right, bottom, left, or null for none. */
    private static String[] expand(String value) {
        if (value == null)
            return null;
        List<String> parts = tokens(value);
        return switch (parts.size()) {
            case 1 -> new String[] { parts.get(0), parts.get(0), parts.get(0), parts.get(0) };
            case 2 -> new String[] { parts.get(0), parts.get(1), parts.get(0), parts.get(1) };
            case 3 -> new String[] { parts.get(0), parts.get(1), parts.get(2), parts.get(1) };
            case 4 -> parts.toArray(new String[0]);
            default -> null;
        };
    }

    /** The words of a value, split at spaces outside brackets, so that {@code rgb(1, 2, 3)} stays one. */
    private static List<String> tokens(String value) {
        List<String> out = new ArrayList<>();
        StringBuilder token = new StringBuilder();
        int depth = 0;
        for (char c : value.trim().toCharArray()) {
            if (c == '(')
                depth++;
            else if (c == ')')
                depth--;
            if (Character.isWhitespace(c) && depth == 0) {
                if (token.length() > 0)
                    out.add(token.toString());
                token.setLength(0);
                continue;
            }
            token.append(c);
        }
        if (token.length() > 0)
            out.add(token.toString());
        return out;
    }

    private static String first(String value) {
        if (value == null)
            return null;
        List<String> parts = tokens(value.split("/")[0]);
        return parts.isEmpty() ? null : parts.get(0);
    }

    /** A CSS length in pixels, or NaN for none, {@code auto}, or one that cannot be read. */
    static double cssLength(String value, double percentOf, double fontSize) {
        if (value == null)
            return Double.NaN;
        String text = value.trim().toLowerCase(Locale.ROOT);
        if (text.endsWith("rem"))
            return number(text.substring(0, text.length() - 3), Double.NaN) * FONT_SIZE;
        if (text.endsWith("em"))
            return number(text.substring(0, text.length() - 2), Double.NaN) * fontSize;
        if (text.endsWith("ex"))
            return number(text.substring(0, text.length() - 2), Double.NaN) * fontSize / 2;
        if (text.endsWith("%") && Double.isNaN(percentOf))
            return Double.NaN;
        return SvgDecalImporter.length(text, Double.NaN, percentOf);
    }

    private static double number(String value, double fallback) {
        if (value == null)
            return fallback;
        try {
            return Double.parseDouble(value.trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static String tag(Element element) {
        String local = element.getLocalName();
        return (local != null ? local : element.getTagName()).toLowerCase(Locale.ROOT);
    }

    private static List<Node> nodes(Element element) {
        NodeList list = element.getChildNodes();
        List<Node> out = new ArrayList<>(list.getLength());
        for (int i = 0; i < list.getLength(); i++)
            out.add(list.item(i));
        return out;
    }
}
