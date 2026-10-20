package com.nectariich.create_flippies.client.decal;

import java.awt.BasicStroke;
import java.awt.Shape;
import java.awt.geom.AffineTransform;
import java.awt.geom.Area;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Line2D;
import java.awt.geom.Path2D;
import java.awt.geom.PathIterator;
import java.awt.geom.Rectangle2D;
import java.awt.geom.RoundRectangle2D;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.StringReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.GZIPInputStream;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.ErrorHandler;
import org.xml.sax.InputSource;
import org.xml.sax.SAXParseException;

import com.nectariich.create_flippies.content.decal.DecalArt;

/**
 * Turns an SVG file into a {@link DecalArt}: flat colours, no overlaps, cut into
 * triangles.
 *
 * <h2>What is read</h2>
 *
 * The parts of SVG that flat artwork is made of, which is what a logo or a panel
 * legend exported from any vector editor uses: paths and the basic shapes, groups,
 * transforms, nested {@code svg} and {@code use}/{@code symbol}, fills with either
 * fill rule, strokes with their caps, joins and dashes, opacity, clip paths, and
 * styling from attributes, {@code style} and a {@code <style>} sheet of simple
 * selectors.
 *
 * Text is turned into the outlines of its glyphs here, in whichever of the fonts it
 * names is installed on this computer, since a panel has no fonts of its own; see
 * {@link #text}. The outlines are then painted like any other shape.
 *
 * Raster pictures ({@code image}, embedded as {@code data:} URIs or in files beside
 * the drawing) are painted as cells of flat colour, and the static HTML of a
 * {@code foreignObject} is laid out as text and boxes; see {@link SvgImages},
 * {@link SvgHtml} and {@link #image}.
 *
 * What is not read is said so, once, in the warnings: text along a path, masks and
 * filters. Gradients are painted as the average of their stops, which keeps a
 * shaded button the right colour if not the right shading. Anything that moves or
 * reacts (scripts, event handlers, SMIL and CSS animation, embedded frames, media
 * and form controls) is taken out before anything is read, so the drawing is
 * imported as it first appears; see {@link #stripDynamic}.
 *
 * <h2>Without its background</h2>
 *
 * A drawing exported from an editor often carries its page or frame as a filled
 * rectangle under everything else, which on a panel paints a solid block over the
 * plate. Imported as transparent, the paint at the bottom of the picture that
 * covers its whole frame is left out, as many layers of it as there are, up to the
 * first paint that does not; see {@link #dropBackground}. Anything over the
 * artwork, however large, is kept.
 *
 * <h2>Why the picture is flattened</h2>
 *
 * An SVG paints its shapes one on top of another, and a panel cannot: the whole
 * picture lies in one plane a fraction of a pixel off the plate, so any two shapes
 * that overlap would fight over every pixel they share. So the painting is done
 * here instead, with Java2D's {@link Area}: each shape takes away whatever of the
 * shapes beneath it it covers, and what is left at the end is a set of regions
 * that touch but never overlap. A shape painted partly see-through is mixed into
 * what is under it rather than cutting it away, and anything left less than half
 * covered is dropped, since paint on a panel is either there or not.
 *
 * Curves are cut into straight lines as each shape is placed, finely enough to be
 * smooth at the largest size a picture can be painted, so that every operation
 * after that is on polygons, which is both much faster and much more robust than
 * {@code Area} on curves.
 *
 * Runs on any thread and touches nothing of the game's.
 */
public final class SvgDecalImporter {

    /** The largest file accepted, compressed or not. */
    public static final int MAX_FILE_BYTES = 4 * 1024 * 1024;

    /** How far a compressed file may inflate. */
    private static final int MAX_INFLATED = 16 * 1024 * 1024;

    /** Elements visited before the file is called too large, which also bounds a {@code use} that multiplies. */
    private static final int MAX_ELEMENTS = 50_000;

    /** Fills and strokes painted before the picture is called too complex to flatten. */
    private static final int MAX_PAINTS = 6_000;

    private static final int MAX_DEPTH = 64;

    /**
     * How far a flattened curve may stray from the true one, as a fraction of the
     * picture's larger side: an eighth of a pixel on a picture painted at the largest
     * size there is, and less than that on any other. Finer buys nothing a panel can
     * show and costs triangles by the hundred thousand on a detailed drawing.
     */
    private static final double FLATNESS = 1 / 4096.0;

    /** Roughly how many fills and strokes a tile of the flattening is meant to hold; see {@link #flatten}. */
    private static final double PAINTS_PER_TILE = 12;

    /** The most tiles across either side of the frame. */
    private static final int MAX_TILES = 24;

    /** Coverage below which a region is left unpainted; see the class comment. */
    private static final float VISIBLE = 0.5f;

    private static final double ALMOST_OPAQUE = 0.999;

    private static final String XLINK = "http://www.w3.org/1999/xlink";

    private static final String XML = "http://www.w3.org/XML/1998/namespace";

    /** What a font size is when nothing says otherwise, as in a browser. */
    private static final double FONT_SIZE = 16;

    /** How much of the frame a background may leave uncovered and still be taken for one; see {@link #dropBackground}. */
    private static final double BACKGROUND_GAP = 0.01;

    /** The properties that are read from presentation attributes as well as from styles. */
    private static final Set<String> PROPERTIES = Set.of("fill", "fill-opacity", "fill-rule", "stroke",
            "stroke-width", "stroke-opacity", "stroke-linecap", "stroke-linejoin", "stroke-miterlimit",
            "stroke-dasharray", "stroke-dashoffset", "opacity", "display", "visibility", "color", "clip-path",
            "clip-rule", "mask", "stop-color", "stop-opacity", "filter", "font-family", "font-size", "font-weight",
            "font-style", "text-anchor", "letter-spacing", "word-spacing", "dominant-baseline", "white-space");

    /** Properties an element does not take from its parent. */
    private static final Set<String> NOT_INHERITED = Set.of("opacity", "display", "clip-path", "mask", "stop-color",
            "stop-opacity", "filter");

    private static final Pattern TRANSFORM = Pattern
            .compile("(matrix|translate|scale|rotate|skewX|skewY)\\s*\\(([^)]*)\\)");

    private static final Pattern LENGTH = Pattern.compile("\\s*([-+]?(?:\\d+\\.?\\d*|\\.\\d+)(?:[eE][-+]?\\d+)?)\\s*([a-z%]*)\\s*");

    private static final Pattern VAR = Pattern.compile("var\\(\\s*(--[\\w-]+)\\s*(?:,\\s*([^)]*))?\\)");

    private static final Pattern URL = Pattern.compile("url\\(\\s*['\"]?#([^)'\"]+)['\"]?\\s*\\)\\s*(.*)");

    /** What the import came to, and everything about the file that was not taken across. */
    public record Result(DecalArt art, List<String> warnings) {}

    /** One fill or stroke, or with {@code picture} at or above nought the painted cells of that raster picture. */
    private record Paint(Area area, int colour, double alpha, int picture) {}

    private static final class Region {
        Area area;
        final int colour;
        final double alpha;
        final int picture;
        Rectangle2D bounds;
        int cuts;

        Region(Area area, int colour, double alpha, int picture) {
            this.area = area;
            this.colour = colour;
            this.alpha = alpha;
            this.picture = picture;
            this.bounds = area.getBounds2D();
        }

        /**
         * Takes a shape out of this region, compacting it every few cuts.
         *
         * {@code Area} keeps every point an edge was ever split at, so a background cut
         * by a few hundred shapes carries five times the points its outline needs, and
         * every later cut pays for all of them. Rebuilding it from its outline with
         * those points taken out costs about one cut and keeps the rest cheap.
         */
        void cut(Area shape, double tolerance) {
            area.subtract(shape);
            if (++cuts % COMPACT_EVERY == 0 && !area.isEmpty())
                area = compacted(area, tolerance);
            bounds = area.getBounds2D();
        }
    }

    /** How many cuts a region takes between compactions; see {@link Region#cut}. */
    private static final int COMPACT_EVERY = 6;

    private record Compound(String tag, String id, List<String> classes) {}

    /** A selector as its compounds, rightmost last, and whether each is a child of the one before rather than a descendant. */
    private record Selector(List<Compound> compounds, List<Boolean> child, int specificity) {}

    private record Rule(Selector selector, String property, String value, boolean important, int order) {}

    /**
     * A raster picture as it is painted: its cells reduced to a palette, and where
     * cell (0, 0) to (1, 1) lands in the drawing.
     */
    private record Picture(SvgImages.Quantised cells, AffineTransform toUser) {}

    /** Elements that only ever move or react, taken out before anything is read; see {@link #stripDynamic}. */
    private static final Set<String> DYNAMIC = Set.of("script", "animate", "animateTransform", "animateMotion",
            "animateColor", "set", "discard", "iframe", "frame", "frameset", "video", "audio", "canvas", "object",
            "embed", "applet", "input", "button", "select", "textarea", "option", "datalist", "progress", "meter");

    private static final String XHTML = "http://www.w3.org/1999/xhtml";

    private final Document document;
    private final String name;
    private final boolean transparent;
    /** The folder of the file read, where pictures it links to are looked for; null for bytes from nowhere. */
    private final Path folder;
    private final List<Picture> pictures = new ArrayList<>();
    /** Pictures by reference, read once however often they are placed; null for one that could not be. */
    private final Map<String, SvgImages.Pixels> loaded = new HashMap<>();
    /** What each picture shows once everything over it is painted, by picture. */
    private final Map<Integer, List<Area>> shown = new TreeMap<>();
    private long linkedBytes;
    private int cellsLeft = SvgImages.MAX_CELLS_TOTAL;
    private int coloursLeft = SvgImages.MAX_COLOURS_TOTAL;
    private boolean html;
    private final Map<String, Element> ids = new HashMap<>();
    private final List<Rule> rules = new ArrayList<>();
    private final Set<String> warnings = new LinkedHashSet<>();
    private final List<Paint> paints = new ArrayList<>();
    private final Set<Element> using = new HashSet<>();
    private double flatness;
    private int visited;
    private boolean tooComplex;

    private SvgDecalImporter(Document document, String name, boolean transparent, Path folder) {
        this.document = document;
        this.name = name;
        this.transparent = transparent;
        this.folder = folder;
    }

    /**
     * Reads a file the player picked, leaving out its background if {@code transparent}.
     * Pictures it links to by a relative path are read from beside it.
     */
    public static Result read(Path path, boolean transparent) throws IOException {
        if (Files.size(path) > MAX_FILE_BYTES)
            throw new IOException(path.getFileName() + " is larger than 4 MB");
        String file = path.getFileName().toString();
        int dot = file.lastIndexOf('.');
        return parse(Files.readAllBytes(path), dot > 0 ? file.substring(0, dot) : file, transparent,
                path.toAbsolutePath().getParent());
    }

    public static Result parse(byte[] bytes, String name) throws IOException {
        return parse(bytes, name, false);
    }

    public static Result parse(byte[] bytes, String name, boolean transparent) throws IOException {
        return parse(bytes, name, transparent, null);
    }

    /** As {@link #parse(byte[], String, boolean)}, with linked pictures read from {@code folder} if it is not null. */
    static Result parse(byte[] bytes, String name, boolean transparent, Path folder) throws IOException {
        return new SvgDecalImporter(document(inflated(bytes)), name, transparent, folder).run();
    }

    // The document

    private static byte[] inflated(byte[] bytes) throws IOException {
        if (bytes.length < 2 || (bytes[0] & 0xFF) != 0x1F || (bytes[1] & 0xFF) != 0x8B)
            return bytes;
        try (InputStream in = new GZIPInputStream(new ByteArrayInputStream(bytes))) {
            byte[] out = in.readNBytes(MAX_INFLATED + 1);
            if (out.length > MAX_INFLATED)
                throw new IOException("The compressed file inflates past 16 MB");
            return out;
        }
    }

    /**
     * Parses without ever reaching outside the file. An SVG from an editor often
     * names a DTD on the web, and fetching it would stall the import on the network
     * at best; an entity defined inside the file could otherwise be made to expand
     * without end.
     */
    private static Document document(byte[] bytes) throws IOException {
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setNamespaceAware(true);
            factory.setValidating(false);
            factory.setExpandEntityReferences(false);
            factory.setXIncludeAware(false);
            feature(factory, XMLConstants.FEATURE_SECURE_PROCESSING, true);
            feature(factory, "http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
            feature(factory, "http://xml.org/sax/features/external-general-entities", false);
            feature(factory, "http://xml.org/sax/features/external-parameter-entities", false);
            DocumentBuilder builder = factory.newDocumentBuilder();
            builder.setEntityResolver((publicId, systemId) -> new InputSource(new StringReader("")));
            builder.setErrorHandler(new ErrorHandler() {
                @Override
                public void warning(SAXParseException e) {}

                @Override
                public void error(SAXParseException e) {}

                @Override
                public void fatalError(SAXParseException e) throws SAXParseException {
                    throw e;
                }
            });
            return builder.parse(new ByteArrayInputStream(bytes));
        } catch (IOException e) {
            throw e;
        } catch (Exception e) {
            throw new IOException("Not a readable SVG file: " + e.getMessage(), e);
        }
    }

    private static void feature(DocumentBuilderFactory factory, String feature, boolean value) {
        try {
            factory.setFeature(feature, value);
        } catch (Exception ignored) {
            // A parser that does not know the feature does not do what it guards against either
        }
    }

    private Result run() throws IOException {
        Element root = document.getDocumentElement();
        if (root == null || !"svg".equals(tag(root)))
            throw new IOException("The file is not an SVG");
        if (stripDynamic(root))
            warn("Scripts, animations and interactive parts were left out; the drawing is imported as it first appears");
        html = root.getElementsByTagNameNS("*", "foreignObject").getLength() > 0;
        index(root);

        double[] viewBox = SvgPath.numbers(attribute(root, "viewBox"));
        double[] viewport = { 100, 100 };
        Rectangle2D box = null;
        if (viewBox.length == 4 && viewBox[2] > 0 && viewBox[3] > 0) {
            box = new Rectangle2D.Double(viewBox[0], viewBox[1], viewBox[2], viewBox[3]);
        } else {
            double width = length(attribute(root, "width"), -1, 0);
            double height = length(attribute(root, "height"), -1, 0);
            if (width > 0 && height > 0)
                box = new Rectangle2D.Double(0, 0, width, height);
        }
        if (box != null) {
            viewport = new double[] { box.getWidth(), box.getHeight() };
            flatness = Math.max(box.getWidth(), box.getHeight()) * FLATNESS;
        }

        walk(root, defaults(), new AffineTransform(), 1, null, viewport, 0);
        if (tooComplex)
            throw new IOException("The drawing has more than " + MAX_PAINTS + " shapes; merge or simplify it");

        if (paints.isEmpty())
            throw new IOException("Nothing in the file is painted");
        if (box == null)
            box = bounds(paints);
        if (box.isEmpty())
            throw new IOException("Nothing in the file is painted");
        if (transparent && dropBackground(box) && paints.isEmpty())
            throw new IOException("Nothing but the background is painted");
        Map<Integer, List<Area>> layers = flatten(box);
        if (layers.isEmpty() && shown.isEmpty())
            throw new IOException("Nothing painted falls inside the drawing's own frame");

        AffineTransform normalise = new AffineTransform();
        normalise.scale(1 / box.getWidth(), 1 / box.getHeight());
        normalise.translate(-box.getX(), -box.getY());
        List<DecalArt.Layer> triangulated = new ArrayList<>();
        int total = 0;
        for (Map.Entry<Integer, List<Area>> layer : layers.entrySet()) {
            List<float[]> pieces = new ArrayList<>();
            int length = 0;
            for (Area area : layer.getValue()) {
                float[] triangles = triangulate(area.createTransformedArea(normalise));
                total += triangles.length / 6;
                if (total > DecalArt.MAX_TRIANGLES)
                    throw new IOException("The drawing needs more than " + DecalArt.MAX_TRIANGLES
                            + " triangles; simplify it");
                pieces.add(triangles);
                length += triangles.length;
            }
            float[] joined = new float[length];
            int at = 0;
            for (float[] piece : pieces) {
                System.arraycopy(piece, 0, joined, at, piece.length);
                at += piece.length;
            }
            if (joined.length > 0)
                triangulated.add(new DecalArt.Layer(0xFF000000 | layer.getKey(), joined));
        }
        List<DecalArt.Layer> pixels = pixels(normalise, total);
        if (triangulated.isEmpty() && pixels.isEmpty())
            throw new IOException("Nothing in the file is painted");
        if (triangulated.size() + pixels.size() > DecalArt.MAX_LAYERS)
            throw new IOException("The drawing uses more than " + DecalArt.MAX_LAYERS + " colours");
        if (!pixels.isEmpty())
            warn("Pictures are painted as small cells of flat colour, and stamped arrows leave them out");
        return new Result(new DecalArt(name, (float) box.getWidth(), (float) box.getHeight(), triangulated, pixels),
                List.copyOf(warnings));
    }

    /**
     * The raster pictures as layers of the finished picture, one per colour: each
     * picture's runs of cells as rectangles, kept where the flattening left the
     * picture showing.
     *
     * Most pictures are not covered by anything, and then every painted cell shows
     * and is two triangles. Where something is painted over one, or it is clipped,
     * each rectangle is tested against what shows: kept whole, left out, or cut to
     * it where it straddles the edge.
     */
    private List<DecalArt.Layer> pixels(AffineTransform normalise, int vectorTriangles) throws IOException {
        Map<Integer, List<float[]>> byColour = new TreeMap<>();
        int total = vectorTriangles;
        for (Map.Entry<Integer, List<Area>> entry : shown.entrySet()) {
            Picture picture = pictures.get(entry.getKey());
            SvgImages.Quantised cells = picture.cells();
            Area visible = new Area();
            for (Area piece : entry.getValue())
                visible.add(piece);
            AffineTransform toCells;
            try {
                toCells = picture.toUser().createInverse();
            } catch (java.awt.geom.NoninvertibleTransformException e) {
                continue;
            }
            Area inCells = visible.createTransformedArea(toCells);
            boolean whole = Math.abs(area(inCells) - cells.opaque()) < 1e-3;
            AffineTransform toBox = new AffineTransform(normalise);
            toBox.concatenate(picture.toUser());
            for (int[] rect : SvgImages.rects(cells.index(), cells.width(), cells.height())) {
                Rectangle2D cell = new Rectangle2D.Double(rect[0], rect[1], rect[2], rect[3]);
                float[] triangles;
                if (whole || inCells.contains(cell)) {
                    double[] c = { rect[0], rect[1], rect[0] + rect[2], rect[1], rect[0] + rect[2],
                            rect[1] + rect[3], rect[0], rect[1] + rect[3] };
                    toBox.transform(c, 0, c, 0, 4);
                    triangles = new float[] { (float) c[0], (float) c[1], (float) c[2], (float) c[3], (float) c[4],
                            (float) c[5], (float) c[0], (float) c[1], (float) c[4], (float) c[5], (float) c[6],
                            (float) c[7] };
                } else if (inCells.intersects(cell)) {
                    Area part = new Area(cell);
                    part.intersect(inCells);
                    if (part.isEmpty())
                        continue;
                    triangles = triangulate(part.createTransformedArea(toBox));
                } else {
                    continue;
                }
                total += triangles.length / 6;
                if (total > DecalArt.MAX_TRIANGLES)
                    throw new IOException("The drawing needs more than " + DecalArt.MAX_TRIANGLES
                            + " triangles; use smaller pictures or fewer of them");
                byColour.computeIfAbsent(0xFF000000 | cells.palette()[rect[4]], key -> new ArrayList<>())
                        .add(triangles);
            }
        }
        List<DecalArt.Layer> layers = new ArrayList<>();
        byColour.forEach((colour, pieces) -> {
            int length = 0;
            for (float[] piece : pieces)
                length += piece.length;
            float[] joined = new float[length];
            int at = 0;
            for (float[] piece : pieces) {
                System.arraycopy(piece, 0, joined, at, piece.length);
                at += piece.length;
            }
            if (joined.length > 0)
                layers.add(new DecalArt.Layer(colour, joined));
        });
        return layers;
    }

    /**
     * Takes out of the document everything that only moves or reacts, and every
     * event handler; whether there was any. What is left is what a browser shows
     * before any of it runs: an animated attribute keeps its base value, since the
     * animation element that would change it is gone, and a CSS animation is simply
     * never applied, since its property is not one that is read.
     */
    static boolean stripDynamic(Element root) {
        boolean found = false;
        List<Element> doomed = new ArrayList<>();
        List<Element> all = new ArrayList<>();
        all.add(root);
        for (int i = 0; i < all.size(); i++) {
            Element element = all.get(i);
            if (DYNAMIC.contains(tag(element)) || DYNAMIC.contains(tag(element).toLowerCase(Locale.ROOT))) {
                doomed.add(element);
                continue;
            }
            org.w3c.dom.NamedNodeMap attributes = element.getAttributes();
            for (int a = attributes.getLength() - 1; a >= 0; a--) {
                Node attribute = attributes.item(a);
                String local = attribute.getLocalName() != null ? attribute.getLocalName() : attribute.getNodeName();
                if (local.toLowerCase(Locale.ROOT).startsWith("on")) {
                    element.removeAttributeNode((org.w3c.dom.Attr) attribute);
                    found = true;
                }
            }
            String style = element.getAttribute("style").toLowerCase(Locale.ROOT);
            if (style.contains("animation") || style.contains("transition"))
                found = true;
            if ("style".equals(tag(element))) {
                String sheet = element.getTextContent().toLowerCase(Locale.ROOT);
                if (sheet.contains("@keyframes") || sheet.contains("animation") || sheet.contains("transition"))
                    found = true;
            }
            all.addAll(children(element));
        }
        for (Element element : doomed)
            element.getParentNode().removeChild(element);
        return found || !doomed.isEmpty();
    }

    /**
     * Leaves out the background: the paints at the bottom of the picture, from the
     * first up, for as long as each covers the whole frame, give or take a sliver
     * along its edges. Whether any was.
     *
     * Covering the frame rather than being a rectangle is what makes a background,
     * since that is what it does to the plate: Figma's frame fill, Illustrator's
     * artboard rectangle and a hand-written full-size {@code rect} all qualify, and so
     * does one drawn as a path or a little larger than the page. Only from the bottom,
     * because a full-frame paint over the artwork is part of the artwork, and the
     * frame stays as it was so that the picture keeps its proportions.
     */
    private boolean dropBackground(Rectangle2D frame) {
        double whole = frame.getWidth() * frame.getHeight();
        int dropped = 0;
        while (dropped < paints.size()) {
            Area uncovered = new Area(frame);
            uncovered.subtract(paints.get(dropped).area());
            if (area(uncovered) > whole * BACKGROUND_GAP)
                break;
            dropped++;
        }
        paints.subList(0, dropped).clear();
        return dropped > 0;
    }

    /** The area an {@code Area} of straight lines encloses; its holes run the other way round and count against it. */
    private static double area(Area area) {
        double sum = 0;
        double startX = 0;
        double startY = 0;
        double lastX = 0;
        double lastY = 0;
        double[] point = new double[6];
        for (PathIterator iterator = area.getPathIterator(null); !iterator.isDone(); iterator.next()) {
            switch (iterator.currentSegment(point)) {
                case PathIterator.SEG_MOVETO -> {
                    sum += lastX * startY - startX * lastY;
                    startX = lastX = point[0];
                    startY = lastY = point[1];
                }
                case PathIterator.SEG_LINETO -> {
                    sum += lastX * point[1] - point[0] * lastY;
                    lastX = point[0];
                    lastY = point[1];
                }
                case PathIterator.SEG_CLOSE -> {
                    sum += lastX * startY - startX * lastY;
                    lastX = startX;
                    lastY = startY;
                }
                default -> {
                }
            }
        }
        sum += lastX * startY - startX * lastY;
        return Math.abs(sum / 2);
    }

    private static Rectangle2D bounds(List<Paint> paints) {
        Rectangle2D bounds = null;
        for (Paint paint : paints) {
            Rectangle2D here = paint.area().getBounds2D();
            bounds = bounds == null ? here : bounds.createUnion(here);
        }
        return bounds;
    }

    /** Ids for {@code use}, gradients and clip paths, and the rules of every style sheet. */
    private void index(Element element) {
        String id = element.getAttribute("id");
        if (!id.isEmpty())
            ids.putIfAbsent(id, element);
        if ("style".equals(tag(element)))
            parseSheet(element.getTextContent());
        for (Element child : children(element))
            index(child);
    }

    // Walking the tree

    private void walk(Element element, Map<String, String> parent, AffineTransform ctm, double opacity, Area clip,
            double[] viewport, int depth) {
        if (depth > MAX_DEPTH || ++visited > MAX_ELEMENTS) {
            warn("The file is nested too deeply or is too large; the rest of it was left out");
            return;
        }
        String tag = tag(element);
        Map<String, String> own = declared(element);
        if ("none".equals(own.get("display")))
            return;
        Map<String, String> style = inherit(parent, own);
        AffineTransform here = new AffineTransform(ctm);
        String transform = attribute(element, "transform");
        if (transform != null)
            here.concatenate(transform(transform));
        double alpha = opacity * clamp(number(own.get("opacity"), 1));
        Area clipped = clipWith(clip, own.get("clip-path"), here, viewport);
        if (own.containsKey("mask") && !"none".equals(own.get("mask")))
            warn("Masks are ignored");
        if (own.containsKey("filter") && !"none".equals(own.get("filter")))
            warn("Filters such as blurs and shadows are ignored");

        switch (tag) {
            case "svg" -> {
                if (depth == 0) {
                    walkChildren(element, style, here, alpha, clipped, viewport, depth);
                } else {
                    double x = length(attribute(element, "x"), 0, viewport[0]);
                    double y = length(attribute(element, "y"), 0, viewport[1]);
                    double width = length(attribute(element, "width"), viewport[0], viewport[0]);
                    double height = length(attribute(element, "height"), viewport[1], viewport[1]);
                    nested(element, style, here, alpha, clipped, x, y, width, height, depth);
                }
            }
            case "g", "a" -> walkChildren(element, style, here, alpha, clipped, viewport, depth);
            case "switch" -> {
                if (!html) {
                    walkChildren(element, style, here, alpha, clipped, viewport, depth);
                } else {
                    Element chosen = chosen(element);
                    if (chosen != null)
                        walk(chosen, style, here, alpha, clipped, viewport, depth + 1);
                }
            }
            case "use" -> use(element, style, here, alpha, clipped, viewport, depth);
            case "path", "rect", "circle", "ellipse", "line", "polyline", "polygon" -> {
                Shape shape = geometry(element, tag, viewport);
                if (shape != null)
                    paint(shape, style, here, alpha, clipped, viewport);
            }
            case "text" -> text(element, style, here, alpha, clipped, viewport);
            case "image" -> image(element, style, here, alpha, clipped, viewport);
            case "foreignObject" -> foreignObject(element, style, here, alpha, clipped, viewport);
            default -> {
                // defs, symbol, gradients, clip paths, markers, metadata: only ever reached by reference
            }
        }
    }

    private void walkChildren(Element element, Map<String, String> style, AffineTransform ctm, double opacity,
            Area clip, double[] viewport, int depth) {
        for (Element child : children(element))
            walk(child, style, ctm, opacity, clip, viewport, depth + 1);
    }

    /** A nested viewport: an {@code svg} inside the drawing, or a {@code symbol} placed by {@code use}. */
    private void nested(Element element, Map<String, String> style, AffineTransform ctm, double opacity, Area clip,
            double x, double y, double width, double height, int depth) {
        if (width <= 0 || height <= 0)
            return;
        AffineTransform inner = new AffineTransform(ctm);
        inner.concatenate(viewportTransform(element, x, y, width, height));
        // A nested viewport clips what it holds to itself unless told not to
        Area frame = new Area(ctm.createTransformedShape(new Rectangle2D.Double(x, y, width, height)));
        if (clip != null)
            frame.intersect(clip);
        if (!"visible".equals(attribute(element, "overflow")))
            clip = frame;
        double[] viewBox = SvgPath.numbers(attribute(element, "viewBox"));
        double[] viewport = viewBox.length == 4 ? new double[] { viewBox[2], viewBox[3] }
                : new double[] { width, height };
        walkChildren(element, style, inner, opacity, clip, viewport, depth);
    }

    private void use(Element element, Map<String, String> style, AffineTransform ctm, double opacity, Area clip,
            double[] viewport, int depth) {
        Element target = reference(element);
        if (target == null || using.contains(target))
            return;
        double x = length(attribute(element, "x"), 0, viewport[0]);
        double y = length(attribute(element, "y"), 0, viewport[1]);
        using.add(target);
        try {
            String tag = tag(target);
            if ("symbol".equals(tag) || "svg".equals(tag)) {
                double width = length(attribute(element, "width"), length(attribute(target, "width"), viewport[0],
                        viewport[0]), viewport[0]);
                double height = length(attribute(element, "height"), length(attribute(target, "height"),
                        viewport[1], viewport[1]), viewport[1]);
                Map<String, String> inner = inherit(style, declared(target));
                nested(target, inner, ctm, opacity, clip, x, y, width, height, depth + 1);
                return;
            }
            AffineTransform moved = new AffineTransform(ctm);
            moved.translate(x, y);
            walk(target, style, moved, opacity, clip, viewport, depth + 1);
        } finally {
            using.remove(target);
        }
    }

    /** The element an {@code href} names, by either spelling of the attribute. */
    private Element reference(Element element) {
        String href = element.getAttributeNS(XLINK, "href");
        if (href.isEmpty())
            href = element.getAttribute("href");
        if (!href.startsWith("#"))
            return null;
        return ids.get(href.substring(1));
    }

    /** What a {@code viewBox} and {@code preserveAspectRatio} make of a viewport. */
    private static AffineTransform viewportTransform(Element element, double x, double y, double width,
            double height) {
        AffineTransform transform = AffineTransform.getTranslateInstance(x, y);
        double[] viewBox = SvgPath.numbers(attribute(element, "viewBox"));
        if (viewBox.length != 4 || viewBox[2] <= 0 || viewBox[3] <= 0)
            return transform;
        double scaleX = width / viewBox[2];
        double scaleY = height / viewBox[3];
        String aspect = attribute(element, "preserveAspectRatio");
        aspect = aspect == null ? "xmidymid meet" : aspect.toLowerCase(Locale.ROOT);
        if (!aspect.startsWith("none")) {
            double scale = aspect.contains("slice") ? Math.max(scaleX, scaleY) : Math.min(scaleX, scaleY);
            double spareX = width - viewBox[2] * scale;
            double spareY = height - viewBox[3] * scale;
            double alignX = aspect.contains("xmin") ? 0 : aspect.contains("xmax") ? 1 : 0.5;
            double alignY = aspect.contains("ymin") ? 0 : aspect.contains("ymax") ? 1 : 0.5;
            transform.translate(spareX * alignX, spareY * alignY);
            scaleX = scale;
            scaleY = scale;
        }
        transform.scale(scaleX, scaleY);
        transform.translate(-viewBox[0], -viewBox[1]);
        return transform;
    }

    // Pictures and HTML

    /**
     * Which child of a {@code switch} is drawn, in a drawing that holds HTML: the first
     * whose {@code requiredExtensions} this importer has, which is HTML or none.
     *
     * Only there, because a drawing without HTML has been imported with every child
     * of a {@code switch} drawn and is left exactly as it was. Editors that write
     * HTML, draw.io foremost, put it in a {@code switch} with a text fallback after
     * it, and drawing both would print every label twice.
     */
    private static Element chosen(Element element) {
        for (Element child : children(element)) {
            String extensions = attribute(child, "requiredExtensions");
            if (extensions == null || extensions.isBlank() || extensions.trim().equals(XHTML))
                return child;
        }
        return null;
    }

    /**
     * Paints an {@code image}: placed in its viewport by {@code preserveAspectRatio},
     * at its own size where it gives none, clipped to the viewport, and painted as
     * cells (see {@link #picture}).
     */
    private void image(Element element, Map<String, String> style, AffineTransform ctm, double opacity, Area clip,
            double[] viewport) {
        String visibility = style.get("visibility");
        if ("hidden".equals(visibility) || "collapse".equals(visibility))
            return;
        String href = element.getAttributeNS(XLINK, "href");
        if (href.isEmpty())
            href = element.getAttribute("href");
        SvgImages.Pixels pixels = load(href);
        if (pixels == null)
            return;
        double x = length(attribute(element, "x"), 0, viewport[0]);
        double y = length(attribute(element, "y"), 0, viewport[1]);
        double width = length(attribute(element, "width"), -1, viewport[0]);
        double height = length(attribute(element, "height"), -1, viewport[1]);
        if (width < 0 && height < 0) {
            width = pixels.width();
            height = pixels.height();
        } else if (width < 0) {
            width = height * pixels.width() / pixels.height();
        } else if (height < 0) {
            height = width * pixels.height() / pixels.width();
        }
        if (width <= 0 || height <= 0)
            return;
        picture(pixels, SvgImages.placement(x, y, width, height, pixels.width(), pixels.height(),
                attribute(element, "preserveAspectRatio")), new Rectangle2D.Double(x, y, width, height), ctm,
                opacity, clip);
    }

    /**
     * Queues a picture to be painted like any other shape: its painted cells, as one
     * outline, cut what is under them and are cut by what is over them. What of it
     * still shows once the flattening is done is then filled in cell by cell; see
     * {@link #pixels}. A picture partly see-through is painted where it is at least
     * half opaque, in its own colours, the rule the rest of the drawing follows.
     */
    private void picture(SvgImages.Pixels pixels, AffineTransform placed, Rectangle2D window, AffineTransform ctm,
            double opacity, Area clip) {
        if (opacity <= 0.002)
            return;
        if (paints.size() >= MAX_PAINTS) {
            tooComplex = true;
            return;
        }
        int[] grid = SvgImages.cellGrid(pixels.width(), pixels.height(), cellsLeft);
        if (grid == null) {
            warn("The drawing holds more picture than can be painted; the last pictures were left out");
            return;
        }
        if (grid[0] < pixels.width() || grid[1] < pixels.height())
            warn("Large pictures were painted at up to " + SvgImages.MAX_CELLS_SIDE + " cells across");
        SvgImages.Quantised cells = SvgImages.quantise(SvgImages.scaled(pixels, grid[0], grid[1]), opacity,
                Math.max(1, Math.min(SvgImages.MAX_COLOURS, coloursLeft)));
        if (cells.opaque() == 0)
            return;
        AffineTransform toUser = new AffineTransform(ctm);
        toUser.concatenate(placed);
        toUser.scale(pixels.width() / (double) grid[0], pixels.height() / (double) grid[1]);
        Area area = new Area(toUser.createTransformedShape(SvgImages.outline(cells)));
        area.intersect(new Area(ctm.createTransformedShape(window)));
        if (clip != null)
            area.intersect(clip);
        if (area.isEmpty())
            return;
        cellsLeft -= grid[0] * grid[1];
        coloursLeft -= cells.palette().length;
        paints.add(new Paint(area, 0, 1, pictures.size()));
        pictures.add(new Picture(cells, toUser));
    }

    /**
     * A picture by its reference, read once: a {@code data:} URI, or a file beside the
     * drawing. Null, with a warning, for one that is not read, which includes every
     * reference to the network: nothing is ever fetched.
     */
    private SvgImages.Pixels load(String href) {
        if (href == null || href.isBlank())
            return null;
        String key = href.trim();
        if (loaded.containsKey(key))
            return loaded.get(key);
        SvgImages.Pixels pixels = null;
        try {
            if (loaded.size() >= SvgImages.MAX_PICTURES) {
                warn("Only the first " + SvgImages.MAX_PICTURES + " different pictures are painted");
                return null;
            }
            byte[] bytes;
            if (SvgImages.isData(key)) {
                bytes = SvgImages.dataUri(key);
            } else if (SvgImages.isRemote(key)) {
                warn("Pictures on the web are never downloaded; embed them in the file instead");
                loaded.put(key, null);
                return null;
            } else if (key.startsWith("#")) {
                warn("Pictures that point at another part of the drawing are left out");
                loaded.put(key, null);
                return null;
            } else if (folder == null) {
                warn("Linked pictures can only be read from a file on this computer");
                loaded.put(key, null);
                return null;
            } else {
                Path file = SvgImages.linked(key, folder);
                long size = Files.size(file);
                if (linkedBytes + size > SvgImages.MAX_LINKED_TOTAL)
                    throw new IOException("The linked pictures come to more than 16 MB");
                linkedBytes += size;
                bytes = Files.readAllBytes(file);
            }
            pixels = SvgImages.decode(bytes);
            if (SvgImages.animated(bytes))
                warn("Animated pictures are painted as their first frame");
        } catch (IOException e) {
            warn(e.getMessage() + "; that picture was left out");
        }
        loaded.put(key, pixels);
        return pixels;
    }

    /**
     * Lays out and paints a {@code foreignObject}'s HTML in its box, which clips it.
     * The text is drawn as glyph outlines and the boxes as shapes, so all of it is
     * flattened with the rest of the drawing; see {@link SvgHtml} for why.
     */
    private void foreignObject(Element element, Map<String, String> style, AffineTransform ctm, double opacity,
            Area clip, double[] viewport) {
        double x = length(attribute(element, "x"), 0, viewport[0]);
        double y = length(attribute(element, "y"), 0, viewport[1]);
        double width = length(attribute(element, "width"), 0, viewport[0]);
        double height = length(attribute(element, "height"), 0, viewport[1]);
        if (width <= 0 || height <= 0)
            return;
        Area box = new Area(ctm.createTransformedShape(new Rectangle2D.Double(x, y, width, height)));
        if (clip != null)
            box.intersect(clip);
        if (box.isEmpty())
            return;
        AffineTransform inside = new AffineTransform(ctm);
        inside.translate(x, y);
        SvgHtml.Host host = new SvgHtml.Host() {
            @Override
            public Map<String, String> declared(Element html) {
                return SvgDecalImporter.this.declared(html);
            }

            @Override
            public double[] imageSize(String src) {
                SvgImages.Pixels pixels = load(src);
                return pixels == null ? null : new double[] { pixels.width(), pixels.height() };
            }

            @Override
            public void warn(String warning) {
                SvgDecalImporter.this.warn(warning);
            }
        };
        for (SvgHtml.Item item : SvgHtml.layout(element, style, width, host)) {
            if (item instanceof SvgHtml.Fill fill) {
                add(new Path2D.Double(fill.shape()), inside, fill.colour(), opacity * fill.opacity(), box);
            } else if (item instanceof SvgHtml.Img img) {
                SvgImages.Pixels pixels = load(img.src());
                Rectangle2D at = img.box();
                if (pixels != null)
                    picture(pixels, SvgImages.placement(at.getX(), at.getY(), at.getWidth(), at.getHeight(),
                            pixels.width(), pixels.height(), img.aspect()), at, inside, opacity * img.opacity(), box);
            }
        }
    }

    // Text

    /** One character of a text element, with the position its own element or one around it gives it. */
    private static final class Letter {
        final String text;
        final Map<String, String> style;
        Double x;
        Double y;
        Double dx;
        Double dy;

        Letter(String text, Map<String, String> style) {
            this.text = text;
            this.style = style;
        }

        boolean placed() {
            return x != null || y != null || dx != null || dy != null;
        }
    }

    /** A run of glyphs laid out and waiting for its chunk to be anchored. */
    private record Run(Shape outline, Map<String, String> style) {}

    /**
     * Draws a {@code text} element as the outlines of its glyphs.
     *
     * The characters are gathered first, from the element and its {@code tspan}s,
     * with white space collapsed the way a browser does unless {@code xml:space} or
     * {@code white-space} says to keep it, and each given the position the innermost
     * element with an {@code x}, {@code y}, {@code dx} or {@code dy} for it says.
     * They are then laid out in runs of one style, which keeps kerning and ligatures,
     * and each run of runs that starts at a new absolute position is a chunk that
     * {@code text-anchor} moves as a whole. Editors write one chunk per line: Figma
     * and Illustrator a {@code tspan} with its own {@code x} and {@code y}, Inkscape
     * the same with {@code xml:space="preserve"}.
     */
    private void text(Element element, Map<String, String> style, AffineTransform ctm, double opacity, Area clip,
            double[] viewport) {
        Map<String, String> own = new HashMap<>(style);
        own.put("font-size", Double.toString(fontSize(style.get("font-size"), FONT_SIZE, viewport)));
        boolean preserve = preserves(element, own, inheritedSpace(element));
        List<Letter> letters = new ArrayList<>();
        letters(element, own, preserve, viewport, letters, new boolean[] { true }, 0);
        if (!preserve && !letters.isEmpty() && " ".equals(letters.get(letters.size() - 1).text))
            letters.remove(letters.size() - 1);

        Map<Map<String, String>, SvgFonts.Face> faces = new java.util.IdentityHashMap<>();
        List<Run> chunk = new ArrayList<>();
        double penX = 0;
        double penY = 0;
        double chunkStart = 0;
        String anchor = null;
        for (int i = 0; i < letters.size();) {
            Letter first = letters.get(i);
            if (first.x != null || first.y != null || anchor == null) {
                place(chunk, anchor, penX - chunkStart, ctm, opacity, clip, viewport);
                if (first.x != null)
                    penX = first.x;
                if (first.y != null)
                    penY = first.y;
                chunkStart = penX;
                anchor = first.style.getOrDefault("text-anchor", "start");
            }
            penX += first.dx == null ? 0 : first.dx;
            penY += first.dy == null ? 0 : first.dy;

            double size = number(first.style.get("font-size"), FONT_SIZE);
            double letterSpacing = spacing(first.style.get("letter-spacing"), size);
            double wordSpacing = spacing(first.style.get("word-spacing"), size);
            // Spaced letters are laid out one by one, since the spacing goes between them
            int end = i + 1;
            if (letterSpacing == 0 && wordSpacing == 0)
                while (end < letters.size() && letters.get(end).style == first.style && !letters.get(end).placed())
                    end++;
            StringBuilder text = new StringBuilder();
            for (int j = i; j < end; j++)
                text.append(letters.get(j).text);
            i = end;

            SvgFonts.Face face = faces.computeIfAbsent(first.style, s -> SvgFonts.face(s.get("font-family"),
                    s.get("font-weight"), s.get("font-style")));
            if (face.substituted())
                warn("Some fonts are not installed on this computer and were drawn in a similar one");
            SvgFonts.Laid laid = SvgFonts.layout(face.font(), text.toString(), size);
            double baseline = switch (first.style.getOrDefault("dominant-baseline", "auto")) {
                case "central" -> (laid.ascent() - laid.descent()) / 2;
                case "middle" -> laid.xHeight() / 2;
                case "hanging", "text-before-edge", "text-top" -> laid.ascent();
                case "text-after-edge", "text-bottom", "ideographic" -> -laid.descent();
                default -> 0;
            };
            if (size > 0 && !text.toString().isBlank())
                chunk.add(new Run(AffineTransform.getTranslateInstance(penX, penY + baseline)
                        .createTransformedShape(laid.outline()), first.style));
            penX += laid.advance() + letterSpacing + (" ".contentEquals(text) ? wordSpacing : 0);
        }
        place(chunk, anchor, penX - chunkStart, ctm, opacity, clip, viewport);
    }

    /** Paints a chunk of text, moved back by its width or half of it for an anchor at its end or middle. */
    private void place(List<Run> chunk, String anchor, double width, AffineTransform ctm, double opacity, Area clip,
            double[] viewport) {
        double shift = "end".equals(anchor) ? -width : "middle".equals(anchor) ? -width / 2 : 0;
        for (Run run : chunk)
            paint(AffineTransform.getTranslateInstance(shift, 0).createTransformedShape(run.outline()), run.style(),
                    ctm, opacity, clip, viewport);
        chunk.clear();
    }

    /**
     * Gathers the characters of a text element or {@code tspan} into a list, then
     * gives those of them that nothing inside has placed this element's positions.
     * {@code space} is whether the last character kept was a space, across the whole
     * text element, which is what collapsing runs of them needs.
     */
    private void letters(Element element, Map<String, String> style, boolean preserve, double[] viewport,
            List<Letter> into, boolean[] space, int depth) {
        if (depth > MAX_DEPTH)
            return;
        int start = into.size();
        NodeList nodes = element.getChildNodes();
        for (int n = 0; n < nodes.getLength(); n++) {
            Node node = nodes.item(n);
            if (node.getNodeType() == Node.TEXT_NODE || node.getNodeType() == Node.CDATA_SECTION_NODE) {
                characters(node.getNodeValue(), style, preserve, into, space);
                continue;
            }
            if (!(node instanceof Element child))
                continue;
            String tag = tag(child);
            if ("textPath".equals(tag)) {
                warn("Text along a path is not imported; convert it to outlines (paths) in your editor first");
                continue;
            }
            if (!"tspan".equals(tag) && !"a".equals(tag))
                continue;
            Map<String, String> declared = declared(child);
            if ("none".equals(declared.get("display")))
                continue;
            Map<String, String> inner = inherit(style, declared);
            inner.put("font-size", Double.toString(fontSize(declared.get("font-size"),
                    number(style.get("font-size"), FONT_SIZE), viewport)));
            letters(child, inner, preserves(child, inner, preserve), viewport, into, space, depth + 1);
        }

        double[] xs = lengths(attribute(element, "x"), viewport[0]);
        double[] ys = lengths(attribute(element, "y"), viewport[1]);
        double[] dxs = lengths(attribute(element, "dx"), viewport[0]);
        double[] dys = lengths(attribute(element, "dy"), viewport[1]);
        for (int k = 0; start + k < into.size(); k++) {
            Letter letter = into.get(start + k);
            if (letter.x == null && k < xs.length)
                letter.x = xs[k];
            if (letter.y == null && k < ys.length)
                letter.y = ys[k];
            if (letter.dx == null && k < dxs.length)
                letter.dx = dxs[k];
            if (letter.dy == null && k < dys.length)
                letter.dy = dys[k];
        }
    }

    /**
     * A text node's characters, one letter each. Line breaks and tabs are spaces, and
     * unless they are preserved a run of spaces is one and a space at the start of
     * the text is none, which is the CSS white space a browser draws SVG text with.
     */
    private static void characters(String data, Map<String, String> style, boolean preserve, List<Letter> into,
            boolean[] space) {
        for (int i = 0; i < data.length();) {
            int c = data.codePointAt(i);
            i += Character.charCount(c);
            if (c == '\n' || c == '\r' || c == '\t')
                c = ' ';
            if (c == ' ' && space[0] && !preserve)
                continue;
            space[0] = c == ' ';
            into.add(new Letter(new String(Character.toChars(c)), style));
        }
    }

    /** Whether an element keeps its white space as written, by CSS or by {@code xml:space}. */
    private static boolean preserves(Element element, Map<String, String> style, boolean inherited) {
        String whiteSpace = style.get("white-space");
        if (whiteSpace != null)
            return whiteSpace.equals("pre") || whiteSpace.equals("pre-wrap") || whiteSpace.equals("break-spaces");
        String space = element.getAttributeNS(XML, "space");
        if (space.isEmpty())
            space = element.getAttribute("xml:space");
        return space.isEmpty() ? inherited : "preserve".equals(space);
    }

    /** Whether an {@code xml:space} on an element around this one says to keep white space. */
    private static boolean inheritedSpace(Element element) {
        for (Element at = parentOf(element); at != null; at = parentOf(at)) {
            String space = at.getAttributeNS(XML, "space");
            if (space.isEmpty())
                space = at.getAttribute("xml:space");
            if (!space.isEmpty())
                return "preserve".equals(space);
        }
        return false;
    }

    /** A {@code font-size} in user units, where {@code em} and per cent are of the size around it. */
    static double fontSize(String value, double parent, double[] viewport) {
        if (value == null)
            return parent;
        String text = value.trim().toLowerCase(Locale.ROOT);
        double keyword = switch (text) {
            case "xx-small" -> 9;
            case "x-small" -> 10;
            case "small" -> 13;
            case "medium" -> 16;
            case "large" -> 18;
            case "x-large" -> 24;
            case "xx-large" -> 32;
            case "smaller" -> parent / 1.2;
            case "larger" -> parent * 1.2;
            default -> -1;
        };
        if (keyword >= 0)
            return keyword;
        if (text.endsWith("rem"))
            return number(text.substring(0, text.length() - 3), 1) * FONT_SIZE;
        if (text.endsWith("em"))
            return number(text.substring(0, text.length() - 2), 1) * parent;
        double size = length(text, parent, parent);
        return size >= 0 ? size : parent;
    }

    /** A {@code letter-spacing} or {@code word-spacing}, where {@code em} is of the font's size. */
    private static double spacing(String value, double fontSize) {
        if (value == null || "normal".equals(value.trim()))
            return 0;
        String text = value.trim().toLowerCase(Locale.ROOT);
        if (text.endsWith("em") && !text.endsWith("rem"))
            return number(text.substring(0, text.length() - 2), 0) * fontSize;
        return length(text, 0, fontSize);
    }

    /** A list of lengths, such as a text element's {@code x}, in user units. */
    private static double[] lengths(String value, double percentOf) {
        if (value == null || value.isBlank())
            return new double[0];
        String[] parts = value.trim().split("[\\s,]+");
        double[] out = new double[parts.length];
        for (int i = 0; i < parts.length; i++)
            out[i] = length(parts[i], 0, percentOf);
        return out;
    }

    // Shapes

    /** The outline an element draws, in its own coordinates, or null for one that draws nothing. */
    private static Shape geometry(Element element, String tag, double[] viewport) {
        double diagonal = Math.hypot(viewport[0], viewport[1]) / Math.sqrt(2);
        switch (tag) {
            case "path" -> {
                return SvgPath.parse(attribute(element, "d"));
            }
            case "rect" -> {
                double x = length(attribute(element, "x"), 0, viewport[0]);
                double y = length(attribute(element, "y"), 0, viewport[1]);
                double width = length(attribute(element, "width"), 0, viewport[0]);
                double height = length(attribute(element, "height"), 0, viewport[1]);
                if (width <= 0 || height <= 0)
                    return null;
                double rx = length(attribute(element, "rx"), -1, viewport[0]);
                double ry = length(attribute(element, "ry"), -1, viewport[1]);
                if (rx < 0)
                    rx = Math.max(ry, 0);
                if (ry < 0)
                    ry = rx;
                rx = Math.min(rx, width / 2);
                ry = Math.min(ry, height / 2);
                if (rx > 0 && ry > 0)
                    return new RoundRectangle2D.Double(x, y, width, height, 2 * rx, 2 * ry);
                return new Rectangle2D.Double(x, y, width, height);
            }
            case "circle" -> {
                double r = length(attribute(element, "r"), 0, diagonal);
                if (r <= 0)
                    return null;
                double cx = length(attribute(element, "cx"), 0, viewport[0]);
                double cy = length(attribute(element, "cy"), 0, viewport[1]);
                return new Ellipse2D.Double(cx - r, cy - r, 2 * r, 2 * r);
            }
            case "ellipse" -> {
                double rx = length(attribute(element, "rx"), -1, viewport[0]);
                double ry = length(attribute(element, "ry"), -1, viewport[1]);
                if (rx < 0)
                    rx = ry;
                if (ry < 0)
                    ry = rx;
                if (rx <= 0 || ry <= 0)
                    return null;
                double cx = length(attribute(element, "cx"), 0, viewport[0]);
                double cy = length(attribute(element, "cy"), 0, viewport[1]);
                return new Ellipse2D.Double(cx - rx, cy - ry, 2 * rx, 2 * ry);
            }
            case "line" -> {
                return new Line2D.Double(length(attribute(element, "x1"), 0, viewport[0]),
                        length(attribute(element, "y1"), 0, viewport[1]),
                        length(attribute(element, "x2"), 0, viewport[0]),
                        length(attribute(element, "y2"), 0, viewport[1]));
            }
            case "polyline", "polygon" -> {
                double[] points = SvgPath.numbers(attribute(element, "points"));
                if (points.length < 4)
                    return null;
                Path2D.Double path = new Path2D.Double();
                path.moveTo(points[0], points[1]);
                for (int i = 2; i + 1 < points.length; i += 2)
                    path.lineTo(points[i], points[i + 1]);
                if ("polygon".equals(tag))
                    path.closePath();
                return path;
            }
            default -> {
                return null;
            }
        }
    }

    /** Fills and then strokes a shape, which is the order SVG paints them in. */
    private void paint(Shape shape, Map<String, String> style, AffineTransform ctm, double opacity, Area clip,
            double[] viewport) {
        String visibility = style.get("visibility");
        if ("hidden".equals(visibility) || "collapse".equals(visibility))
            return;
        long current = SvgColours.parse(style.getOrDefault("color", "black"), 0xFF000000L);

        long fill = colour(style.getOrDefault("fill", "black"), current, style);
        if (fill != SvgColours.NONE) {
            Path2D.Double path = new Path2D.Double(shape);
            path.setWindingRule("evenodd".equals(style.get("fill-rule")) ? Path2D.WIND_EVEN_ODD
                    : Path2D.WIND_NON_ZERO);
            add(path, ctm, fill, opacity * clamp(number(style.get("fill-opacity"), 1)), clip);
        }

        long stroke = colour(style.getOrDefault("stroke", "none"), current, style);
        double diagonal = Math.hypot(viewport[0], viewport[1]) / Math.sqrt(2);
        double width = length(style.get("stroke-width"), 1, diagonal);
        if (stroke != SvgColours.NONE && width > 0) {
            BasicStroke pen = new BasicStroke((float) width, cap(style.get("stroke-linecap")),
                    join(style.get("stroke-linejoin")),
                    (float) Math.max(1, number(style.get("stroke-miterlimit"), 4)),
                    dashes(style.get("stroke-dasharray"), diagonal),
                    (float) length(style.get("stroke-dashoffset"), 0, diagonal));
            add(pen.createStrokedShape(shape), ctm, stroke,
                    opacity * clamp(number(style.get("stroke-opacity"), 1)), clip);
        }
    }

    /** One fill or stroke, placed, cut into straight lines, clipped and queued for flattening. */
    private void add(Shape shape, AffineTransform ctm, long colour, double opacity, Area clip) {
        double alpha = opacity * ((colour >>> 24) & 0xFF) / 255.0;
        if (alpha <= 0.002)
            return;
        if (paints.size() >= MAX_PAINTS) {
            tooComplex = true;
            return;
        }
        Area area = new Area(straightened(ctm.createTransformedShape(shape)));
        if (clip != null)
            area.intersect(clip);
        if (!area.isEmpty())
            paints.add(new Paint(area, (int) (colour & 0xFFFFFF), alpha, -1));
    }

    /** A shape with its curves replaced by lines, keeping its fill rule. */
    private Path2D.Double straightened(Shape shape) {
        return straightened(shape, tolerance(shape));
    }

    /**
     * How far a placed shape's lines may stray from its curves: a fraction of the
     * picture, or of the shape itself while the picture's size is not yet known.
     */
    private double tolerance(Shape placed) {
        if (flatness > 0)
            return flatness;
        Rectangle2D bounds = placed.getBounds2D();
        return Math.max(1e-9, Math.max(bounds.getWidth(), bounds.getHeight()) * FLATNESS * 4);
    }

    /**
     * A shape as closed polygons, with its curves cut into lines and then every point
     * the lines can do without taken out again.
     *
     * The second half matters as much as the first. Java2D's stroker hands back the
     * outline of a curve as a great many short pieces, and every point of every piece
     * would otherwise go through each cut the flattening makes, which on a detailed
     * emblem is the difference between a second and a minute. Each subpath is closed,
     * because a fill closes it anyway and an outline from the stroker already is.
     */
    private static Path2D.Double straightened(Shape shape, double tolerance) {
        PathIterator iterator = shape.getPathIterator(null, tolerance);
        Path2D.Double path = new Path2D.Double(iterator.getWindingRule());
        List<Double> subpath = new ArrayList<>();
        double[] point = new double[6];
        while (!iterator.isDone()) {
            switch (iterator.currentSegment(point)) {
                case PathIterator.SEG_MOVETO -> {
                    emit(subpath, path, tolerance);
                    subpath.add(point[0]);
                    subpath.add(point[1]);
                }
                case PathIterator.SEG_LINETO -> {
                    if (!subpath.isEmpty()) {
                        subpath.add(point[0]);
                        subpath.add(point[1]);
                    }
                }
                case PathIterator.SEG_CLOSE -> emit(subpath, path, tolerance);
                default -> {
                }
            }
            iterator.next();
        }
        emit(subpath, path, tolerance);
        return path;
    }

    private static void emit(List<Double> subpath, Path2D.Double path, double tolerance) {
        if (subpath.size() >= 6) {
            double[] points = simplified(subpath, tolerance);
            if (points.length >= 6) {
                path.moveTo(points[0], points[1]);
                for (int i = 2; i < points.length; i += 2)
                    path.lineTo(points[i], points[i + 1]);
                path.closePath();
            }
        }
        subpath.clear();
    }

    private static int cap(String value) {
        return switch (value == null ? "" : value) {
            case "round" -> BasicStroke.CAP_ROUND;
            case "square" -> BasicStroke.CAP_SQUARE;
            default -> BasicStroke.CAP_BUTT;
        };
    }

    private static int join(String value) {
        return switch (value == null ? "" : value) {
            case "round" -> BasicStroke.JOIN_ROUND;
            case "bevel" -> BasicStroke.JOIN_BEVEL;
            default -> BasicStroke.JOIN_MITER;
        };
    }

    /** A dash pattern, or null for a solid line: none given, or one Java2D would refuse. */
    private static float[] dashes(String value, double diagonal) {
        if (value == null || value.isBlank() || "none".equals(value.trim()))
            return null;
        String[] parts = value.trim().split("[\\s,]+");
        List<Float> lengths = new ArrayList<>();
        double sum = 0;
        for (String part : parts) {
            double dash = length(part, -1, diagonal);
            if (dash < 0)
                return null;
            lengths.add((float) dash);
            sum += dash;
        }
        if (sum <= 0)
            return null;
        if (lengths.size() % 2 == 1)
            lengths.addAll(new ArrayList<>(lengths));
        float[] out = new float[lengths.size()];
        for (int i = 0; i < out.length; i++)
            out[i] = lengths.get(i);
        return out;
    }

    // Paint

    /**
     * A fill or stroke value as a colour, following a reference to a gradient where
     * there is one and a custom property where it names one.
     *
     * A value that cannot be read at all is painted black with a warning, which is
     * what a browser does with it too: the property falls back to what it inherits,
     * and what a fill inherits by default is black.
     */
    private long colour(String value, long current, Map<String, String> style) {
        value = resolved(value, style);
        Matcher url = URL.matcher(value == null ? "" : value.trim());
        if (!url.matches()) {
            long colour = SvgColours.parse(value, current);
            if (colour == SvgColours.NONE && (value == null || !SvgColours.isNone(value))) {
                warn("Some colours could not be read and were painted black");
                return 0xFF000000L;
            }
            return colour;
        }
        Element target = ids.get(url.group(1));
        String fallback = url.group(2).trim();
        if (target != null && tag(target).endsWith("Gradient")) {
            long average = gradient(target, current);
            if (average != SvgColours.NONE) {
                warn("Gradients are painted as one flat colour");
                return average;
            }
        }
        if (!fallback.isEmpty())
            return SvgColours.parse(fallback, current);
        warn("Patterns and other paint servers are left out");
        return SvgColours.NONE;
    }

    /**
     * A value with its {@code var()} references replaced by what the cascade holds for
     * them, or by their fallback; null where one names a property that is nowhere
     * declared and has no fallback, which is usually a drawing exported from a page
     * whose stylesheet did not come with it.
     */
    private static String resolved(String value, Map<String, String> style) {
        for (int hop = 0; hop < 8 && value.contains("var("); hop++) {
            Matcher var = VAR.matcher(value);
            if (!var.find())
                break;
            String found = style.get(var.group(1));
            if (found == null)
                found = var.group(2) == null ? null : var.group(2).trim();
            if (found == null)
                return null;
            value = value.substring(0, var.start()) + found + value.substring(var.end());
        }
        return value;
    }

    /** The plain average of a gradient's stops, taken from whichever gradient in its chain has them. */
    private long gradient(Element gradient, long current) {
        Element at = gradient;
        for (int hop = 0; hop < 8 && at != null; hop++) {
            double red = 0;
            double green = 0;
            double blue = 0;
            double alpha = 0;
            int count = 0;
            for (Element stop : children(at)) {
                if (!"stop".equals(tag(stop)))
                    continue;
                Map<String, String> style = declared(stop);
                long colour = SvgColours.parse(style.getOrDefault("stop-color", "black"), current);
                if (colour == SvgColours.NONE)
                    colour = 0;
                double opacity = clamp(number(style.get("stop-opacity"), 1)) * ((colour >>> 24) & 0xFF) / 255.0;
                red += (colour >> 16) & 0xFF;
                green += (colour >> 8) & 0xFF;
                blue += colour & 0xFF;
                alpha += opacity;
                count++;
            }
            if (count > 0) {
                int a = (int) Math.round(alpha / count * 255);
                return (long) a << 24 | Math.round(red / count) << 16 | Math.round(green / count) << 8
                        | Math.round(blue / count);
            }
            at = reference(at);
        }
        return SvgColours.NONE;
    }

    // Clipping

    /** The clip so far, narrowed by a {@code clip-path} if the element names one. */
    private Area clipWith(Area clip, String value, AffineTransform ctm, double[] viewport) {
        if (value == null)
            return clip;
        Matcher url = URL.matcher(value.trim());
        if (!url.matches())
            return clip;
        Element path = ids.get(url.group(1));
        if (path == null || !"clipPath".equals(tag(path)))
            return clip;
        if ("objectBoundingBox".equals(attribute(path, "clipPathUnits"))) {
            warn("Clip paths measured against their object are ignored");
            return clip;
        }
        AffineTransform inner = new AffineTransform(ctm);
        String transform = attribute(path, "transform");
        if (transform != null)
            inner.concatenate(transform(transform));
        Area area = new Area();
        for (Element child : children(path))
            clipShape(child, inner, viewport, area, 0);
        if (clip != null)
            area.intersect(clip);
        return area;
    }

    private void clipShape(Element element, AffineTransform ctm, double[] viewport, Area into, int depth) {
        if (depth > 8)
            return;
        Map<String, String> own = declared(element);
        if ("none".equals(own.get("display")))
            return;
        AffineTransform here = new AffineTransform(ctm);
        String transform = attribute(element, "transform");
        if (transform != null)
            here.concatenate(transform(transform));
        String tag = tag(element);
        if ("use".equals(tag)) {
            Element target = reference(element);
            if (target == null || using.contains(target))
                return;
            here.translate(length(attribute(element, "x"), 0, viewport[0]),
                    length(attribute(element, "y"), 0, viewport[1]));
            using.add(target);
            try {
                clipShape(target, here, viewport, into, depth + 1);
            } finally {
                using.remove(target);
            }
            return;
        }
        if ("g".equals(tag)) {
            for (Element child : children(element))
                clipShape(child, here, viewport, into, depth + 1);
            return;
        }
        Shape shape = geometry(element, tag, viewport);
        if (shape == null)
            return;
        Path2D.Double path = new Path2D.Double(shape);
        path.setWindingRule("evenodd".equals(own.get("clip-rule")) ? Path2D.WIND_EVEN_ODD : Path2D.WIND_NON_ZERO);
        into.add(new Area(straightened(here.createTransformedShape(path))));
    }

    // Flattening

    /**
     * Paints every fill and stroke in order and keeps only what shows, as areas by
     * colour, within the picture's frame; see the class comment.
     *
     * The frame is cut into tiles and each is painted on its own. Painting is
     * quadratic in the worst way: a background under a detailed emblem is cut by every
     * shape of the emblem in turn, and grows a hole for each, so the last cut is made
     * against an outline as complicated as the whole drawing. In tiles, each piece of
     * background is cut only by what lies over that piece. The seams cost a few more
     * triangles and are invisible, since both sides of one are the same colour.
     */
    private Map<Integer, List<Area>> flatten(Rectangle2D frame) {
        int across = (int) Math.max(1, Math.min(MAX_TILES, Math.ceil(Math.sqrt(paints.size() / PAINTS_PER_TILE))));
        double width = frame.getWidth() / across;
        double height = frame.getHeight() / across;
        Map<Integer, List<Area>> layers = new TreeMap<>();
        boolean faint = false;
        for (int column = 0; column < across; column++)
            for (int row = 0; row < across; row++) {
                // The last tile ends exactly on the frame rather than a rounding short of it
                double x0 = frame.getX() + column * width;
                double y0 = frame.getY() + row * height;
                double x1 = column == across - 1 ? frame.getMaxX() : x0 + width;
                double y1 = row == across - 1 ? frame.getMaxY() : y0 + height;
                faint |= flattenTile(new Rectangle2D.Double(x0, y0, x1 - x0, y1 - y0), layers);
            }
        if (faint)
            warn("Parts painted less than half opaque were left out");
        return layers;
    }

    /** One tile of {@link #flatten}; whether anything too faint to keep was dropped. */
    private boolean flattenTile(Rectangle2D tile, Map<Integer, List<Area>> layers) {
        Area window = new Area(tile);
        // A small fraction of what the curves were flattened to, so compacting moves
        // nothing that flattening did not already
        double compaction = tolerance(tile) / 8;
        List<Region> regions = new ArrayList<>();
        for (Paint whole : paints) {
            Rectangle2D within = whole.area().getBounds2D();
            if (!within.intersects(tile))
                continue;
            // A copy either way: a region is cut in place, and the same paint is wanted
            // whole by every other tile it reaches
            Area area = new Area(whole.area());
            if (!tile.contains(within)) {
                area.intersect(window);
                if (area.isEmpty())
                    continue;
            }
            Paint paint = new Paint(area, whole.colour(), whole.alpha(), whole.picture());
            Rectangle2D bounds = paint.area().getBounds2D();
            if (paint.alpha() >= ALMOST_OPAQUE) {
                for (Region region : regions)
                    if (region.bounds.intersects(bounds))
                        region.cut(paint.area(), compaction);
                regions.removeIf(region -> region.area.isEmpty());
                regions.add(new Region(paint.area(), paint.colour(), 1, paint.picture()));
                continue;
            }

            Area uncovered = new Area(paint.area());
            List<Region> mixed = new ArrayList<>();
            for (Region region : regions) {
                if (!region.bounds.intersects(bounds))
                    continue;
                Area overlap = new Area(region.area);
                overlap.intersect(paint.area());
                if (overlap.isEmpty())
                    continue;
                uncovered.subtract(overlap);
                if (region.picture >= 0) {
                    // A picture's cells cannot be mixed with one colour, so a see-through
                    // shape over one either covers it or is not there
                    warn("See-through shapes over pictures are painted solid or left out");
                    if (paint.alpha() < VISIBLE)
                        continue;
                    region.cut(paint.area(), compaction);
                    mixed.add(new Region(overlap, paint.colour(), paint.alpha(), -1));
                    continue;
                }
                region.cut(paint.area(), compaction);
                mixed.add(new Region(overlap, mix(paint.colour(), region.colour, paint.alpha()),
                        region.alpha + paint.alpha() * (1 - region.alpha), -1));
            }
            regions.removeIf(region -> region.area.isEmpty());
            regions.addAll(mixed);
            if (!uncovered.isEmpty())
                regions.add(new Region(uncovered, paint.colour(), paint.alpha(), -1));
        }

        Map<Integer, Area> here = new HashMap<>();
        boolean faint = false;
        for (Region region : regions) {
            if (region.picture >= 0) {
                shown.computeIfAbsent(region.picture, picture -> new ArrayList<>()).add(region.area);
                continue;
            }
            if (region.alpha < VISIBLE) {
                faint = true;
                continue;
            }
            here.computeIfAbsent(region.colour, colour -> new Area()).add(region.area);
        }
        here.forEach((colour, area) -> layers.computeIfAbsent(colour, key -> new ArrayList<>()).add(area));
        return faint;
    }

    /** An area rebuilt from its own outline with its redundant points taken out. */
    private static Area compacted(Area area, double tolerance) {
        Path2D.Double path = new Path2D.Double(Path2D.WIND_NON_ZERO);
        List<Double> ring = new ArrayList<>();
        double[] point = new double[6];
        for (PathIterator iterator = area.getPathIterator(null); !iterator.isDone(); iterator.next()) {
            switch (iterator.currentSegment(point)) {
                case PathIterator.SEG_MOVETO -> {
                    emit(ring, path, tolerance);
                    ring.add(point[0]);
                    ring.add(point[1]);
                }
                case PathIterator.SEG_LINETO -> {
                    ring.add(point[0]);
                    ring.add(point[1]);
                }
                case PathIterator.SEG_CLOSE -> emit(ring, path, tolerance);
                default -> {
                }
            }
        }
        emit(ring, path, tolerance);
        return new Area(path);
    }

    private static int mix(int top, int bottom, double alpha) {
        int red = (int) Math.round(((top >> 16) & 0xFF) * alpha + ((bottom >> 16) & 0xFF) * (1 - alpha));
        int green = (int) Math.round(((top >> 8) & 0xFF) * alpha + ((bottom >> 8) & 0xFF) * (1 - alpha));
        int blue = (int) Math.round((top & 0xFF) * alpha + (bottom & 0xFF) * (1 - alpha));
        return red << 16 | green << 8 | blue;
    }

    // Triangles

    /**
     * One colour's area as triangles, in the picture's unit box.
     *
     * An {@code Area} comes out as rings with no indication of which are holes, so
     * they are sorted out by nesting: a ring inside an even number of others is an
     * outline and one inside an odd number is a hole in the smallest outline around
     * it. Each outline is then cut with its holes.
     */
    static float[] triangulate(Area area) {
        List<double[]> rings = rings(area);
        int count = rings.size();
        double[] areas = new double[count];
        double[][] boxes = new double[count][];
        for (int i = 0; i < count; i++) {
            areas[i] = Math.abs(signedArea(rings.get(i)));
            boxes[i] = box(rings.get(i));
        }

        int[] depth = new int[count];
        int[] parent = new int[count];
        double[][] samples = new double[count][];
        for (int i = 0; i < count; i++) {
            double[] sample = samples[i] = sample(rings.get(i));
            parent[i] = -1;
            for (int j = 0; j < count; j++) {
                if (j == i || areas[j] <= areas[i] || !inBox(boxes[j], sample[0], sample[1])
                        || !inside(rings.get(j), sample[0], sample[1]))
                    continue;
                depth[i]++;
            }
        }
        for (int i = 0; i < count; i++) {
            if (depth[i] % 2 == 0)
                continue;
            double[] sample = samples[i];
            double best = Double.MAX_VALUE;
            for (int j = 0; j < count; j++)
                if (depth[j] == depth[i] - 1 && areas[j] > areas[i] && areas[j] < best
                        && inBox(boxes[j], sample[0], sample[1]) && inside(rings.get(j), sample[0], sample[1])) {
                    best = areas[j];
                    parent[i] = j;
                }
        }

        List<Float> out = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            if (depth[i] % 2 != 0)
                continue;
            List<double[]> polygon = new ArrayList<>();
            polygon.add(rings.get(i));
            for (int j = 0; j < count; j++)
                if (parent[j] == i)
                    polygon.add(rings.get(j));
            cut(polygon, out);
        }
        float[] triangles = new float[out.size()];
        for (int i = 0; i < triangles.length; i++)
            triangles[i] = out.get(i);
        return triangles;
    }

    /**
     * A point just inside a ring, for asking which other rings it is inside.
     *
     * Not one of its vertices: rings of an {@code Area} meet at points, a hole
     * touching its outline at a corner being the usual case, and a vertex on another
     * ring's edge is inside it or not by rounding. A hair inward from the middle of
     * the ring's longest edge is on no other ring at all.
     */
    private static double[] sample(double[] ring) {
        int longest = 0;
        double length = -1;
        for (int i = 0; i < ring.length; i += 2) {
            int next = (i + 2) % ring.length;
            double here = Math.hypot(ring[next] - ring[i], ring[next + 1] - ring[i + 1]);
            if (here > length) {
                length = here;
                longest = i;
            }
        }
        int next = (longest + 2) % ring.length;
        double mx = (ring[longest] + ring[next]) / 2;
        double my = (ring[longest + 1] + ring[next + 1]) / 2;
        double step = Math.min(1e-7, length * 1e-3) / Math.max(length, 1e-300);
        double nx = -(ring[next + 1] - ring[longest + 1]) * step;
        double ny = (ring[next] - ring[longest]) * step;
        if (inside(ring, mx + nx, my + ny))
            return new double[] { mx + nx, my + ny };
        return new double[] { mx - nx, my - ny };
    }

    private static void cut(List<double[]> polygon, List<Float> out) {
        int length = 0;
        for (double[] ring : polygon)
            length += ring.length;
        double[] data = new double[length];
        int[] holes = new int[polygon.size() - 1];
        int at = 0;
        for (int r = 0; r < polygon.size(); r++) {
            if (r > 0)
                holes[r - 1] = at / 2;
            double[] ring = polygon.get(r);
            System.arraycopy(ring, 0, data, at, ring.length);
            at += ring.length;
        }
        List<Integer> indices = Earcut.triangulate(data, holes);
        for (int index : indices) {
            out.add((float) data[index * 2]);
            out.add((float) data[index * 2 + 1]);
        }
    }

    /** The closed rings of an area, without repeated points, as flat x, y arrays. */
    private static List<double[]> rings(Area area) {
        List<double[]> rings = new ArrayList<>();
        List<Double> ring = new ArrayList<>();
        double[] point = new double[6];
        for (PathIterator iterator = area.getPathIterator(null, FLATNESS); !iterator.isDone(); iterator.next()) {
            switch (iterator.currentSegment(point)) {
                case PathIterator.SEG_MOVETO -> {
                    finish(ring, rings);
                    ring.add(point[0]);
                    ring.add(point[1]);
                }
                case PathIterator.SEG_LINETO -> {
                    int size = ring.size();
                    if (size >= 2 && ring.get(size - 2) == point[0] && ring.get(size - 1) == point[1])
                        break;
                    ring.add(point[0]);
                    ring.add(point[1]);
                }
                case PathIterator.SEG_CLOSE -> finish(ring, rings);
                default -> {
                }
            }
        }
        finish(ring, rings);
        return rings;
    }

    /** Closes off one ring of an area in the picture's unit box, simplified to within {@link #FLATNESS} of it. */
    private static void finish(List<Double> ring, List<double[]> into) {
        int size = ring.size();
        if (size >= 4 && ring.get(0).equals(ring.get(size - 2)) && ring.get(1).equals(ring.get(size - 1))) {
            ring.remove(size - 1);
            ring.remove(size - 2);
        }
        if (ring.size() >= 6) {
            double[] points = simplified(ring, FLATNESS);
            if (points.length >= 6 && Math.abs(signedArea(points)) > 1e-14)
                into.add(points);
        }
        ring.clear();
    }

    /**
     * A ring with the points it can do without taken out: Douglas and Peucker's
     * simplification, so that no point moves further than the tolerance.
     *
     * Wanted twice over: on the way in, see {@link #straightened}, and on the way out,
     * because the outlines that come out of {@code Area} carry every point that went
     * in. Together they keep a detailed drawing at tens of thousands of triangles
     * rather than hundreds of thousands. A ring is closed, so it is split at its first
     * point and the point furthest from it, and each half is simplified on its own.
     */
    private static double[] simplified(List<Double> ring, double within) {
        int count = ring.size() / 2;
        double[] x = new double[count];
        double[] y = new double[count];
        for (int i = 0; i < count; i++) {
            x[i] = ring.get(2 * i);
            y[i] = ring.get(2 * i + 1);
        }
        int far = 0;
        double farthest = -1;
        for (int i = 1; i < count; i++) {
            double d = (x[i] - x[0]) * (x[i] - x[0]) + (y[i] - y[0]) * (y[i] - y[0]);
            if (d > farthest) {
                farthest = d;
                far = i;
            }
        }
        boolean[] keep = new boolean[count];
        keep[0] = true;
        keep[far] = true;
        double tolerance = within * within;
        simplify(x, y, 0, far, keep, tolerance);
        simplify(x, y, far, count, keep, tolerance);
        int kept = 0;
        for (boolean k : keep)
            if (k)
                kept++;
        double[] out = new double[kept * 2];
        int at = 0;
        for (int i = 0; i < count; i++)
            if (keep[i]) {
                out[at++] = x[i];
                out[at++] = y[i];
            }
        return out;
    }

    /** Keeps whichever point between two strays furthest from the line joining them, if it strays far enough. */
    private static void simplify(double[] x, double[] y, int from, int to, boolean[] keep, double tolerance) {
        int count = x.length;
        int[] stack = { from, to };
        java.util.ArrayDeque<int[]> spans = new java.util.ArrayDeque<>();
        spans.push(stack);
        while (!spans.isEmpty()) {
            int[] span = spans.pop();
            int a = span[0];
            int b = span[1];
            if (b - a < 2)
                continue;
            double ax = x[a];
            double ay = y[a];
            double bx = x[b % count];
            double by = y[b % count];
            double dx = bx - ax;
            double dy = by - ay;
            double length = dx * dx + dy * dy;
            int worst = -1;
            double worstDistance = tolerance;
            for (int i = a + 1; i < b; i++) {
                double distance;
                if (length == 0) {
                    distance = (x[i] - ax) * (x[i] - ax) + (y[i] - ay) * (y[i] - ay);
                } else {
                    double cross = dx * (y[i] - ay) - dy * (x[i] - ax);
                    distance = cross * cross / length;
                }
                if (distance > worstDistance) {
                    worstDistance = distance;
                    worst = i;
                }
            }
            if (worst < 0)
                continue;
            keep[worst] = true;
            spans.push(new int[] { a, worst });
            spans.push(new int[] { worst, b });
        }
    }

    private static double signedArea(double[] ring) {
        double sum = 0;
        for (int i = 0, j = ring.length - 2; i < ring.length; j = i, i += 2)
            sum += (ring[j] - ring[i]) * (ring[i + 1] + ring[j + 1]);
        return sum / 2;
    }

    private static double[] box(double[] ring) {
        double[] box = { Double.MAX_VALUE, Double.MAX_VALUE, -Double.MAX_VALUE, -Double.MAX_VALUE };
        for (int i = 0; i < ring.length; i += 2) {
            box[0] = Math.min(box[0], ring[i]);
            box[1] = Math.min(box[1], ring[i + 1]);
            box[2] = Math.max(box[2], ring[i]);
            box[3] = Math.max(box[3], ring[i + 1]);
        }
        return box;
    }

    private static boolean inBox(double[] box, double x, double y) {
        return x >= box[0] && x <= box[2] && y >= box[1] && y <= box[3];
    }

    /** Even-odd ray casting, which is all a ring that does not cross itself needs. */
    private static boolean inside(double[] ring, double x, double y) {
        boolean inside = false;
        for (int i = 0, j = ring.length - 2; i < ring.length; j = i, i += 2) {
            double xi = ring[i];
            double yi = ring[i + 1];
            double xj = ring[j];
            double yj = ring[j + 1];
            if ((yi > y) != (yj > y) && x < (xj - xi) * (y - yi) / (yj - yi) + xi)
                inside = !inside;
        }
        return inside;
    }

    // Styles

    private static Map<String, String> defaults() {
        Map<String, String> style = new HashMap<>();
        style.put("fill", "black");
        style.put("stroke", "none");
        return style;
    }

    private static Map<String, String> inherit(Map<String, String> parent, Map<String, String> own) {
        Map<String, String> style = new HashMap<>(parent);
        for (String property : NOT_INHERITED)
            style.remove(property);
        own.forEach((property, value) -> {
            if (!"inherit".equals(value))
                style.put(property, value);
        });
        return style;
    }

    /**
     * What an element states for itself, in the cascade's order: presentation
     * attributes, then the style sheet by specificity, then its own {@code style},
     * then anything the sheet marked important.
     */
    private Map<String, String> declared(Element element) {
        Map<String, String> own = new HashMap<>();
        for (String property : PROPERTIES) {
            String value = attribute(element, property);
            if (value != null)
                own.put(property, value.trim());
        }
        if (!rules.isEmpty()) {
            List<Rule> matched = new ArrayList<>();
            for (Rule rule : rules)
                if (matches(rule.selector(), element))
                    matched.add(rule);
            matched.sort((a, b) -> a.selector().specificity() != b.selector().specificity()
                    ? Integer.compare(a.selector().specificity(), b.selector().specificity())
                    : Integer.compare(a.order(), b.order()));
            for (Rule rule : matched)
                if (!rule.important())
                    own.put(rule.property(), rule.value());
            declarations(attribute(element, "style"), own);
            for (Rule rule : matched)
                if (rule.important())
                    own.put(rule.property(), rule.value());
        } else {
            declarations(attribute(element, "style"), own);
        }
        return own;
    }

    private static void declarations(String text, Map<String, String> into) {
        if (text == null)
            return;
        for (String declaration : text.split(";")) {
            int colon = declaration.indexOf(':');
            if (colon <= 0)
                continue;
            String property = declaration.substring(0, colon).trim().toLowerCase(Locale.ROOT);
            String value = declaration.substring(colon + 1).replace("!important", "").trim();
            if (!property.isEmpty() && !value.isEmpty())
                into.put(property, value);
        }
    }

    /**
     * The rules of a style sheet that this can match: compound selectors of a tag,
     * an id and classes, joined by descendant or child combinators. Anything else
     * (pseudo-classes, attribute selectors, siblings) is skipped rule by rule, and
     * at-rules are skipped whole.
     */
    private void parseSheet(String css) {
        if (css == null)
            return;
        String text = css.replaceAll("(?s)/\\*.*?\\*/", "");
        int at = 0;
        while (at < text.length()) {
            int open = text.indexOf('{', at);
            if (open < 0)
                return;
            String prelude = text.substring(at, open).trim();
            int close = matchingBrace(text, open);
            if (close < 0)
                return;
            String body = text.substring(open + 1, close);
            at = close + 1;
            if (prelude.startsWith("@"))
                continue;
            for (String selectorText : prelude.split(",")) {
                Selector selector = selector(selectorText.trim());
                if (selector == null)
                    continue;
                for (String declaration : body.split(";")) {
                    int colon = declaration.indexOf(':');
                    if (colon <= 0)
                        continue;
                    String property = declaration.substring(0, colon).trim().toLowerCase(Locale.ROOT);
                    String value = declaration.substring(colon + 1).trim();
                    boolean important = value.contains("!important");
                    value = value.replace("!important", "").trim();
                    if (!value.isEmpty())
                        rules.add(new Rule(selector, property, value, important, rules.size()));
                }
            }
        }
    }

    private static int matchingBrace(String text, int open) {
        int depth = 0;
        for (int i = open; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '{')
                depth++;
            else if (c == '}' && --depth == 0)
                return i;
        }
        return -1;
    }

    private static Selector selector(String text) {
        // The root of an SVG document is its svg element, and :root is how custom
        // properties for the whole drawing are usually declared
        text = text.replace(":root", "svg");
        if (text.isEmpty() || text.matches(".*[:\\[+~].*"))
            return null;
        String[] tokens = text.replace(">", " > ").trim().split("\\s+");
        List<Compound> compounds = new ArrayList<>();
        List<Boolean> child = new ArrayList<>();
        boolean nextIsChild = false;
        int specificity = 0;
        for (String token : tokens) {
            if (">".equals(token)) {
                nextIsChild = true;
                continue;
            }
            Matcher parts = Pattern.compile("([#.]?)([^#.]+)").matcher(token);
            String tag = null;
            String id = null;
            List<String> classes = new ArrayList<>();
            while (parts.find()) {
                switch (parts.group(1)) {
                    case "#" -> {
                        id = parts.group(2);
                        specificity += 10_000;
                    }
                    case "." -> {
                        classes.add(parts.group(2));
                        specificity += 100;
                    }
                    default -> {
                        tag = parts.group(2);
                        if (!"*".equals(tag))
                            specificity += 1;
                    }
                }
            }
            child.add(nextIsChild);
            compounds.add(new Compound(tag, id, classes));
            nextIsChild = false;
        }
        return compounds.isEmpty() ? null : new Selector(compounds, child, specificity);
    }

    private static boolean matches(Selector selector, Element element) {
        List<Compound> compounds = selector.compounds();
        int last = compounds.size() - 1;
        if (!matches(compounds.get(last), element))
            return false;
        Element at = element;
        for (int i = last - 1; i >= 0; i--) {
            boolean direct = selector.child().get(i + 1);
            Element ancestor = parentOf(at);
            if (direct) {
                if (ancestor == null || !matches(compounds.get(i), ancestor))
                    return false;
                at = ancestor;
                continue;
            }
            while (ancestor != null && !matches(compounds.get(i), ancestor))
                ancestor = parentOf(ancestor);
            if (ancestor == null)
                return false;
            at = ancestor;
        }
        return true;
    }

    private static boolean matches(Compound compound, Element element) {
        if (compound.tag() != null && !"*".equals(compound.tag()) && !compound.tag().equals(tag(element)))
            return false;
        if (compound.id() != null && !compound.id().equals(element.getAttribute("id")))
            return false;
        if (compound.classes().isEmpty())
            return true;
        List<String> classes = List.of(element.getAttribute("class").trim().split("\\s+"));
        return classes.containsAll(compound.classes());
    }

    private static Element parentOf(Element element) {
        Node parent = element.getParentNode();
        return parent instanceof Element up ? up : null;
    }

    // Values

    private static AffineTransform transform(String text) {
        AffineTransform result = new AffineTransform();
        Matcher matcher = TRANSFORM.matcher(text);
        while (matcher.find()) {
            double[] a = SvgPath.numbers(matcher.group(2));
            switch (matcher.group(1)) {
                case "matrix" -> {
                    if (a.length == 6)
                        result.concatenate(new AffineTransform(a[0], a[1], a[2], a[3], a[4], a[5]));
                }
                case "translate" -> {
                    if (a.length >= 1)
                        result.translate(a[0], a.length > 1 ? a[1] : 0);
                }
                case "scale" -> {
                    if (a.length >= 1)
                        result.scale(a[0], a.length > 1 ? a[1] : a[0]);
                }
                case "rotate" -> {
                    if (a.length >= 3)
                        result.rotate(Math.toRadians(a[0]), a[1], a[2]);
                    else if (a.length >= 1)
                        result.rotate(Math.toRadians(a[0]));
                }
                case "skewX" -> {
                    if (a.length >= 1)
                        result.shear(Math.tan(Math.toRadians(a[0])), 0);
                }
                case "skewY" -> {
                    if (a.length >= 1)
                        result.shear(0, Math.tan(Math.toRadians(a[0])));
                }
                default -> {
                }
            }
        }
        return result;
    }

    /** A length in user units; {@code percentOf} is what a hundred per cent is. */
    static double length(String value, double fallback, double percentOf) {
        if (value == null)
            return fallback;
        Matcher matcher = LENGTH.matcher(value.toLowerCase(Locale.ROOT));
        if (!matcher.matches())
            return fallback;
        double number = Double.parseDouble(matcher.group(1));
        return switch (matcher.group(2)) {
            case "", "px" -> number;
            case "pt" -> number * 4 / 3;
            case "pc" -> number * 16;
            case "mm" -> number * 96 / 25.4;
            case "cm" -> number * 96 / 2.54;
            case "in" -> number * 96;
            case "em" -> number * 16;
            case "ex" -> number * 8;
            case "%" -> number * percentOf / 100;
            default -> fallback;
        };
    }

    private static double number(String value, double fallback) {
        if (value == null)
            return fallback;
        String text = value.trim();
        try {
            if (text.endsWith("%"))
                return Double.parseDouble(text.substring(0, text.length() - 1)) / 100;
            return Double.parseDouble(text);
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static double clamp(double value) {
        return value < 0 ? 0 : value > 1 ? 1 : value;
    }

    /** An attribute's value, or null where the element does not have it. */
    private static String attribute(Element element, String name) {
        return element.hasAttribute(name) ? element.getAttribute(name) : null;
    }

    private static String tag(Element element) {
        String local = element.getLocalName();
        return local != null ? local : element.getTagName();
    }

    private static List<Element> children(Element element) {
        NodeList nodes = element.getChildNodes();
        List<Element> children = new ArrayList<>(nodes.getLength());
        for (int i = 0; i < nodes.getLength(); i++)
            if (nodes.item(i) instanceof Element child)
                children.add(child);
        return children.isEmpty() ? Collections.emptyList() : children;
    }

    private void warn(String warning) {
        warnings.add(warning);
    }
}
