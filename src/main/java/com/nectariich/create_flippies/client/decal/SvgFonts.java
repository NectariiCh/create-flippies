package com.nectariich.create_flippies.client.decal;

import java.awt.Font;
import java.awt.Shape;
import java.awt.font.FontRenderContext;
import java.awt.font.GlyphVector;
import java.awt.font.LineMetrics;
import java.awt.font.TextAttribute;
import java.awt.geom.AffineTransform;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The fonts an SVG's text is drawn in, found among the ones installed on this
 * computer, and the outlines of their glyphs.
 *
 * A drawing names its fonts the way its editor knows them: a family and a weight
 * from Figma or Inkscape, a PostScript name such as {@code ArialMT} or
 * {@code MyriadPro-Regular} from Illustrator. Each is tried in a few spellings, and
 * a font that is nowhere to be found is drawn in the generic family it falls back to
 * rather than not at all, which is what a browser does with it too.
 *
 * Glyphs are laid out at one large size and scaled down, so that their outlines are
 * the font's own and not hinted to some size's pixels. Only AWT's fonts and
 * geometry are used, never a window, so this works headless.
 */
final class SvgFonts {

    /** The size every font is laid out at before its outlines are scaled to the size asked for. */
    private static final float SIZE = 100;

    private static final FontRenderContext CONTEXT = new FontRenderContext(null, true, true);

    private static final Map<TextAttribute, Object> SHAPING = Map.of(TextAttribute.KERNING,
            TextAttribute.KERNING_ON, TextAttribute.LIGATURES, TextAttribute.LIGATURES_ON);

    private static final String[] WEIGHTS = { "Thin", "ExtraLight", "Light", "Regular", "Medium", "SemiBold", "Bold",
            "ExtraBold", "Black" };

    private static final Map<String, Face> FACES = new ConcurrentHashMap<>();

    /** A font found for a drawing's {@code font-family}, and whether it is a stand-in for one not installed. */
    record Face(Font font, boolean substituted) {}

    /** A run of text laid out from its start on the baseline, in the size asked for. */
    record Laid(Shape outline, double advance, double ascent, double descent, double xHeight) {}

    private SvgFonts() {}

    /**
     * The font for a {@code font-family} list, a {@code font-weight} and a
     * {@code font-style}, any of which may be null.
     */
    static Face face(String families, String weight, String style) {
        int number = weight(weight);
        boolean italic = style != null && (style.startsWith("italic") || style.startsWith("oblique"));
        String key = (families == null ? "" : families) + '\0' + number + '\0' + italic;
        return FACES.computeIfAbsent(key, k -> find(families, number, italic));
    }

    private static Face find(String families, int weight, boolean italic) {
        int awtStyle = (weight >= 600 ? Font.BOLD : Font.PLAIN) | (italic ? Font.ITALIC : 0);
        boolean named = false;
        for (String family : families(families)) {
            String generic = generic(family);
            if (generic != null)
                return new Face(new Font(generic, awtStyle, 1).deriveFont(SHAPING).deriveFont(SIZE), named);
            named = true;
            Font font = installed(family, weight, italic, awtStyle);
            if (font != null)
                return new Face(font.deriveFont(SHAPING).deriveFont(SIZE), false);
        }
        return new Face(new Font(Font.SANS_SERIF, awtStyle, 1).deriveFont(SHAPING).deriveFont(SIZE), named);
    }

    /** The names in a {@code font-family} list, unquoted, in order. */
    static List<String> families(String value) {
        List<String> names = new ArrayList<>();
        if (value == null)
            return names;
        for (String part : value.split(",")) {
            String name = part.trim();
            if (name.length() >= 2 && (name.charAt(0) == '\'' || name.charAt(0) == '"')
                    && name.charAt(name.length() - 1) == name.charAt(0))
                name = name.substring(1, name.length() - 1).trim();
            if (!name.isEmpty())
                names.add(name);
        }
        return names;
    }

    /** The logical font a CSS generic family stands for, or null for a name that is not one. */
    private static String generic(String family) {
        return switch (family.toLowerCase(Locale.ROOT)) {
            case "serif", "ui-serif" -> Font.SERIF;
            case "monospace", "ui-monospace" -> Font.MONOSPACED;
            case "sans-serif", "system-ui", "ui-sans-serif", "-apple-system", "cursive", "fantasy", "ui-rounded" ->
                    Font.SANS_SERIF;
            default -> null;
        };
    }

    /**
     * An installed font by any of the names a drawing may know it by: the weight's
     * own face first where the weight is not plain or bold (Figma's {@code 600} is a
     * SemiBold face, not a bold one), then the family, then the PostScript name
     * taken apart.
     */
    private static Font installed(String family, int weight, boolean italic, int awtStyle) {
        List<String> faces = new ArrayList<>();
        if (weight != 400 && weight != 700) {
            String face = WEIGHTS[Math.max(0, Math.min(8, weight / 100 - 1))] + (italic ? "Italic" : "");
            faces.add(family + "-" + face);
            faces.add(family + " " + face);
        }
        for (String name : faces) {
            Font font = font(name, Font.PLAIN);
            if (font != null)
                return font;
        }
        Font font = font(family, awtStyle);
        if (font != null)
            return font;
        // ArialMT, Arial-BoldMT, MyriadPro-Regular: the family is the part before the
        // dash, without the vendor's suffix and with its words spaced out
        String bare = family.replaceAll("(PSMT|MT|PS)$", "");
        int dash = bare.indexOf('-');
        if (dash > 0)
            bare = bare.substring(0, dash);
        for (String name : new String[] { bare, bare.replaceAll("(?<=[a-z])(?=[A-Z])", " ") }) {
            if (name.equals(family))
                continue;
            font = font(name, awtStyle);
            if (font != null)
                return font;
        }
        return null;
    }

    /** A font by name, or null where AWT has nothing by that name and would hand back its default. */
    private static Font font(String name, int style) {
        Font font = new Font(name, style, 1);
        String family = font.getFamily(Locale.ROOT);
        return Font.DIALOG.equalsIgnoreCase(family) && !Font.DIALOG.equalsIgnoreCase(name) ? null : font;
    }

    /** A CSS {@code font-weight} as a number from 100 to 900. */
    static int weight(String value) {
        if (value == null)
            return 400;
        return switch (value.trim().toLowerCase(Locale.ROOT)) {
            case "bold", "bolder" -> 700;
            case "normal", "lighter", "" -> 400;
            default -> {
                try {
                    yield Math.max(100, Math.min(900, (int) Math.round(Double.parseDouble(value.trim()))));
                } catch (NumberFormatException e) {
                    yield 400;
                }
            }
        };
    }

    /**
     * Lays a run of text out in a font at a size, kerned and with its ligatures. A
     * run the font has no glyphs for is drawn in the logical font of the same style,
     * which falls back across the system's fonts for scripts a single font lacks.
     */
    static Laid layout(Font font, String text, double size) {
        if (font.canDisplayUpTo(text) != -1)
            font = new Font(Font.SANS_SERIF, font.getStyle(), 1).deriveFont(SHAPING).deriveFont(SIZE);
        char[] chars = text.toCharArray();
        GlyphVector glyphs = font.layoutGlyphVector(CONTEXT, chars, 0, chars.length, Font.LAYOUT_LEFT_TO_RIGHT);
        double scale = size / SIZE;
        Shape outline = AffineTransform.getScaleInstance(scale, scale).createTransformedShape(glyphs.getOutline());
        double advance = glyphs.getGlyphPosition(glyphs.getNumGlyphs()).getX() * scale;
        LineMetrics metrics = font.getLineMetrics(text, CONTEXT);
        double xHeight = font.createGlyphVector(CONTEXT, "x").getVisualBounds().getHeight() * scale;
        return new Laid(outline, advance, metrics.getAscent() * scale, metrics.getDescent() * scale, xHeight);
    }
}
