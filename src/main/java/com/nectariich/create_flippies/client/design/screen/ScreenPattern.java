package com.nectariich.create_flippies.client.design.screen;

import java.util.List;

import com.nectariich.create_flippies.content.design_workbench.DesignText;
import com.nectariich.create_flippies.content.display.element.ElementKind;
import com.nectariich.create_flippies.content.display.element.ScreenElement;
import com.nectariich.create_flippies.content.display.element.Viewport;

/**
 * What the workbench's preview shows on a screen's glass: the layout being authored,
 * or in its place one of three test patterns that show where the viewport really is
 * once placed, whatever the layout holds. A solid rectangle shows its extent, a hollow
 * one its edges (and whether they hang off the glass), and two paragraphs of lorem
 * ipsum, wrapped and fitted, how lettering sits and reads there.
 *
 * Preview only: a pattern is never saved and never reaches a placed screen. The
 * patterns are built as ordinary elements over the whole viewport and drawn by the same
 * renderer as everything else, backlit.
 */
public enum ScreenPattern {
    LAYOUT, SOLID, HOLLOW, LOREM;

    /** Two fixed paragraphs, each within one element's text cap. */
    private static final String[] LOREM_TEXT = {
            "Lorem ipsum dolor sit amet, consectetur adipiscing elit, sed do eiusmod tempor incididunt ut labore "
                    + "et dolore magna aliqua. Ut enim ad minim veniam, quis nostrud exercitation ullamco laboris "
                    + "nisi ut aliquip ex ea commodo consequat.",
            "Duis aute irure dolor in reprehenderit in voluptate velit esse cillum dolore eu fugiat nulla "
                    + "pariatur. Excepteur sint occaecat cupidatat non proident, sunt in culpa qui officia deserunt "
                    + "mollit anim id est laborum." };

    /** A hollow pattern's outline: half a pixel, or a fiftieth of the shorter side when that is more. */
    private static final double HOLLOW_LEAST_PIXELS = 0.5, HOLLOW_SHARE = 0.02;

    private static final int COLOUR = ScreenElement.DEFAULT_COLOUR;

    public ScreenPattern next() {
        return values()[(ordinal() + 1) % values().length];
    }

    /** Whether the renderer is to break lettering over several lines: the lorem paragraphs only. */
    public boolean wraps() {
        return this == LOREM;
    }

    /** The pattern's elements over the viewport, back to front; null for {@link #LAYOUT}, which shows the layout. */
    public List<ScreenElement> elements(Viewport viewport) {
        int w = viewport.width(), h = viewport.height();
        return switch (this) {
        case LAYOUT -> null;
        case SOLID -> List.of(element(0, ElementKind.FILL, 0, 0, w, h, 0, ""));
        case HOLLOW -> {
            double shorter = Math.min(w, h) / (double) Viewport.UNITS_PER_PIXEL;
            double pixels = Math.max(HOLLOW_LEAST_PIXELS, HOLLOW_SHARE * shorter);
            int stroke = (int) Math.round(pixels * Viewport.UNITS_PER_PIXEL);
            yield List.of(element(0, ElementKind.FRAME, 0, 0, w, h, stroke, ""));
        }
        case LOREM -> {
            int top = h / 2;
            yield List.of(element(0, ElementKind.TEXT, 0, 0, w, top, 0, LOREM_TEXT[0]),
                    element(1, ElementKind.TEXT, 0, top, w, Math.max(1, h - top), 0, LOREM_TEXT[1]));
        }
        };
    }

    private static ScreenElement element(int id, ElementKind kind, int x, int y, int w, int h, int stroke,
            String text) {
        return new ScreenElement(id, kind, x, y, w, h, COLOUR, 0, stroke, text, DesignText.DEFAULT_FONT, false, false,
                ScreenElement.FIT, 0, ScreenElement.ALIGN_START, ScreenElement.ALIGN_START);
    }
}
