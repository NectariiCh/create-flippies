package com.nectariich.create_flippies.client.display;

import java.util.List;
import java.util.function.UnaryOperator;

import com.nectariich.create_flippies.content.display.element.ScreenElement;

/**
 * Lets the display editor show its own version of the display it is editing: the
 * element being dragged where the mouse has it before the edit is sent, hidden
 * elements left out, empty lettering shown as a dimmed placeholder, a bar at its
 * preview value.
 *
 * The renderer asks {@link #shown} for every display it draws, naming the display by
 * its owner (the live {@code ScreenElements}, or the workbench preview's key); every
 * display but the one being edited gets its own elements back untouched. One display
 * at most is edited at a time, since the editor is a screen.
 */
public final class ElementPreview {

    private static Object owner;
    private static UnaryOperator<List<ScreenElement>> source;

    private ElementPreview() {}

    /** From now on the display {@code owner} names is drawn as {@code source} makes its elements. */
    public static void begin(Object owner, UnaryOperator<List<ScreenElement>> source) {
        ElementPreview.owner = owner;
        ElementPreview.source = source;
    }

    /** Re-points the preview at a display whose live elements were replaced by a sync. */
    public static void retarget(Object owner) {
        if (source != null)
            ElementPreview.owner = owner;
    }

    public static void end() {
        owner = null;
        source = null;
    }

    /** Whether the display {@code owner} names is the one being edited. */
    public static boolean editing(Object owner) {
        return owner != null && owner == ElementPreview.owner;
    }

    /** What to draw for that display: the editor's version of it, or {@code live} itself. */
    public static List<ScreenElement> shown(Object owner, List<ScreenElement> live) {
        return editing(owner) ? source.apply(live) : live;
    }
}
