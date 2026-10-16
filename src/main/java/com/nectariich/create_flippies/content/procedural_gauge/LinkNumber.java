package com.nectariich.create_flippies.content.procedural_gauge;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Pulls the number out of a line a display link delivered.
 *
 * A link sends text, not values — whatever the source it is reading formatted for a
 * sign. So a gauge has to find a number inside a string that may be "1,234.5 RF/t"
 * or "Level: 82%" or nothing of the sort, and both the block form and the mounted
 * form have to find the same one or a gauge would read differently depending on
 * which it was.
 */
final class LinkNumber {

    private static final Pattern NUMBER = Pattern.compile("-?\\d+(?:\\.\\d+)?");

    private LinkNumber() {}

    /** The first number in {@code text}, or null if there is none in it. */
    static Double in(String text) {
        // Thousands separators go first, so a value like "1,234.5" parses whole
        // rather than stopping at the comma and reading as one
        Matcher matcher = NUMBER.matcher(text.replace(",", ""));
        if (!matcher.find())
            return null;
        try {
            return Double.parseDouble(matcher.group());
        } catch (NumberFormatException ignored) {
            return null;
        }
    }
}
