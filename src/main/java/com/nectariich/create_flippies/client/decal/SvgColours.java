package com.nectariich.create_flippies.client.decal;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * CSS colours as an SVG writes them: hex in its four lengths, {@code rgb()} and
 * {@code rgba()} with numbers or percentages, {@code hsl()} and {@code hsla()},
 * and the named colours.
 */
final class SvgColours {

    /** What a colour that cannot be read, or that is {@code none}, comes back as. */
    static final long NONE = -1;

    private static final Map<String, Integer> NAMED = new HashMap<>();

    static {
        String table = "aliceblue f0f8ff antiquewhite faebd7 aqua 00ffff aquamarine 7fffd4 azure f0ffff "
                + "beige f5f5dc bisque ffe4c4 black 000000 blanchedalmond ffebcd blue 0000ff blueviolet 8a2be2 "
                + "brown a52a2a burlywood deb887 cadetblue 5f9ea0 chartreuse 7fff00 chocolate d2691e "
                + "coral ff7f50 cornflowerblue 6495ed cornsilk fff8dc crimson dc143c cyan 00ffff "
                + "darkblue 00008b darkcyan 008b8b darkgoldenrod b8860b darkgray a9a9a9 darkgreen 006400 "
                + "darkgrey a9a9a9 darkkhaki bdb76b darkmagenta 8b008b darkolivegreen 556b2f "
                + "darkorange ff8c00 darkorchid 9932cc darkred 8b0000 darksalmon e9967a darkseagreen 8fbc8f "
                + "darkslateblue 483d8b darkslategray 2f4f4f darkslategrey 2f4f4f darkturquoise 00ced1 "
                + "darkviolet 9400d3 deeppink ff1493 deepskyblue 00bfff dimgray 696969 dimgrey 696969 "
                + "dodgerblue 1e90ff firebrick b22222 floralwhite fffaf0 forestgreen 228b22 fuchsia ff00ff "
                + "gainsboro dcdcdc ghostwhite f8f8ff gold ffd700 goldenrod daa520 gray 808080 grey 808080 "
                + "green 008000 greenyellow adff2f honeydew f0fff0 hotpink ff69b4 indianred cd5c5c "
                + "indigo 4b0082 ivory fffff0 khaki f0e68c lavender e6e6fa lavenderblush fff0f5 "
                + "lawngreen 7cfc00 lemonchiffon fffacd lightblue add8e6 lightcoral f08080 lightcyan e0ffff "
                + "lightgoldenrodyellow fafad2 lightgray d3d3d3 lightgreen 90ee90 lightgrey d3d3d3 "
                + "lightpink ffb6c1 lightsalmon ffa07a lightseagreen 20b2aa lightskyblue 87cefa "
                + "lightslategray 778899 lightslategrey 778899 lightsteelblue b0c4de lightyellow ffffe0 "
                + "lime 00ff00 limegreen 32cd32 linen faf0e6 magenta ff00ff maroon 800000 "
                + "mediumaquamarine 66cdaa mediumblue 0000cd mediumorchid ba55d3 mediumpurple 9370db "
                + "mediumseagreen 3cb371 mediumslateblue 7b68ee mediumspringgreen 00fa9a "
                + "mediumturquoise 48d1cc mediumvioletred c71585 midnightblue 191970 mintcream f5fffa "
                + "mistyrose ffe4e1 moccasin ffe4b5 navajowhite ffdead navy 000080 oldlace fdf5e6 "
                + "olive 808000 olivedrab 6b8e23 orange ffa500 orangered ff4500 orchid da70d6 "
                + "palegoldenrod eee8aa palegreen 98fb98 paleturquoise afeeee palevioletred db7093 "
                + "papayawhip ffefd5 peachpuff ffdab9 peru cd853f pink ffc0cb plum dda0dd powderblue b0e0e6 "
                + "purple 800080 rebeccapurple 663399 red ff0000 rosybrown bc8f8f royalblue 4169e1 "
                + "saddlebrown 8b4513 salmon fa8072 sandybrown f4a460 seagreen 2e8b57 seashell fff5ee "
                + "sienna a0522d silver c0c0c0 skyblue 87ceeb slateblue 6a5acd slategray 708090 "
                + "slategrey 708090 snow fffafa springgreen 00ff7f steelblue 4682b4 tan d2b48c teal 008080 "
                + "thistle d8bfd8 tomato ff6347 turquoise 40e0d0 violet ee82ee wheat f5deb3 white ffffff "
                + "whitesmoke f5f5f5 yellow ffff00 yellowgreen 9acd32";
        String[] words = table.split(" ");
        for (int i = 0; i + 1 < words.length; i += 2)
            NAMED.put(words[i], Integer.parseInt(words[i + 1], 16));
    }

    private SvgColours() {}

    /**
     * A colour as ARGB in the low thirty-two bits, or {@link #NONE}. {@code current}
     * is what {@code currentColor} stands for.
     */
    static long parse(String value, long current) {
        if (value == null)
            return NONE;
        String text = value.trim().toLowerCase(Locale.ROOT);
        if (text.isEmpty() || text.equals("none") || text.equals("transparent"))
            return NONE;
        if (text.equals("currentcolor"))
            return current;
        // A panel is only ever seen in the light, so of the pair the light one is used
        if (text.startsWith("light-dark(") && text.endsWith(")"))
            return parse(firstArgument(text.substring("light-dark(".length(), text.length() - 1)), current);
        if (text.startsWith("#"))
            return hex(text.substring(1));
        if (text.startsWith("rgb"))
            return rgb(arguments(text));
        if (text.startsWith("hsl"))
            return hsl(arguments(text));
        Integer named = NAMED.get(text);
        return named == null ? NONE : 0xFF000000L | named;
    }

    /** Whether a value says outright that nothing is painted, as against being a colour that could not be read. */
    static boolean isNone(String value) {
        String text = value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
        return text.isEmpty() || text.equals("none") || text.equals("transparent");
    }

    /** The first argument of a function's argument list, minding the parentheses of any nested call. */
    private static String firstArgument(String arguments) {
        int depth = 0;
        for (int i = 0; i < arguments.length(); i++) {
            char c = arguments.charAt(i);
            if (c == '(')
                depth++;
            else if (c == ')')
                depth--;
            else if (c == ',' && depth == 0)
                return arguments.substring(0, i);
        }
        return arguments;
    }

    private static long hex(String digits) {
        try {
            int value = Integer.parseUnsignedInt(digits, 16);
            return switch (digits.length()) {
                case 3 -> 0xFF000000L | expand(value >> 8) << 16 | expand(value >> 4) << 8 | expand(value);
                case 4 -> (long) expand(value) << 24 | expand(value >> 12) << 16 | expand(value >> 8) << 8
                        | expand(value >> 4);
                case 6 -> 0xFF000000L | value;
                case 8 -> ((long) (value & 0xFF) << 24) | (value >>> 8);
                default -> NONE;
            };
        } catch (NumberFormatException e) {
            return NONE;
        }
    }

    private static int expand(int nibble) {
        int n = nibble & 0xF;
        return n << 4 | n;
    }

    private static String[] arguments(String text) {
        int open = text.indexOf('(');
        int close = text.lastIndexOf(')');
        if (open < 0 || close < open)
            return new String[0];
        return text.substring(open + 1, close).trim().split("[\\s,/]+");
    }

    private static long rgb(String[] parts) {
        if (parts.length < 3)
            return NONE;
        try {
            int r = channel(parts[0]);
            int g = channel(parts[1]);
            int b = channel(parts[2]);
            int a = parts.length > 3 ? alpha(parts[3]) : 255;
            return (long) a << 24 | r << 16 | g << 8 | b;
        } catch (NumberFormatException e) {
            return NONE;
        }
    }

    private static long hsl(String[] parts) {
        if (parts.length < 3)
            return NONE;
        try {
            double h = ((Double.parseDouble(parts[0].replace("deg", "")) % 360) + 360) % 360 / 360;
            double s = fraction(parts[1]);
            double l = fraction(parts[2]);
            int a = parts.length > 3 ? alpha(parts[3]) : 255;
            double q = l < 0.5 ? l * (1 + s) : l + s - l * s;
            double p = 2 * l - q;
            int r = (int) Math.round(hue(p, q, h + 1.0 / 3) * 255);
            int g = (int) Math.round(hue(p, q, h) * 255);
            int b = (int) Math.round(hue(p, q, h - 1.0 / 3) * 255);
            return (long) a << 24 | r << 16 | g << 8 | b;
        } catch (NumberFormatException e) {
            return NONE;
        }
    }

    private static double hue(double p, double q, double t) {
        if (t < 0)
            t += 1;
        if (t > 1)
            t -= 1;
        if (t < 1.0 / 6)
            return p + (q - p) * 6 * t;
        if (t < 0.5)
            return q;
        if (t < 2.0 / 3)
            return p + (q - p) * (2.0 / 3 - t) * 6;
        return p;
    }

    private static int channel(String part) {
        double value = part.endsWith("%") ? Double.parseDouble(part.substring(0, part.length() - 1)) * 2.55
                : Double.parseDouble(part);
        return (int) Math.max(0, Math.min(255, Math.round(value)));
    }

    private static int alpha(String part) {
        return (int) Math.round(Math.max(0, Math.min(1, fraction(part))) * 255);
    }

    private static double fraction(String part) {
        return part.endsWith("%") ? Double.parseDouble(part.substring(0, part.length() - 1)) / 100
                : Double.parseDouble(part);
    }
}
