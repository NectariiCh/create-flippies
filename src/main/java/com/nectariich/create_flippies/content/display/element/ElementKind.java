package com.nectariich.create_flippies.content.display.element;

/**
 * What one element of a display is: lettering, a shape, a line, imported art or a
 * bar that shows a value.
 *
 * <h2>Adding one</h2>
 *
 * A new kind is a new constant at the end and nothing else: the kind is stored by
 * {@link #ordinal}, and a save or packet naming a kind this build does not know is
 * skipped rather than guessed at (see {@link #byOrdinal}). Every kind is drawn inside
 * its box and never past it, so clipping against the viewport is the box's own
 * clamp, whatever the kind.
 */
public enum ElementKind {

    /** Lettering: typed in the menu, replaced by whatever a display link writes. */
    TEXT('T'),

    /** A rectangle, solid or hollow (an outline {@link ScreenElement#stroke} thick), its corners optionally rounded. */
    FILL('F'),

    /**
     * A hollow rectangle, as older saves name it: an element made or read as one is a
     * {@link #FILL} with its outline on, so no element is ever of this kind.
     */
    FRAME('R'),

    /** An ellipse filling its box: solid, or an outline {@link ScreenElement#stroke} thick. */
    ELLIPSE('E'),

    /**
     * A straight line between two corners of its box, {@link ScreenElement#stroke}
     * thick, with an arrow head at either end or both.
     */
    LINE('L'),

    /** A picture imported from an SVG file and stored in the design store by hash, stretched to the box. */
    SVG('S'),

    /** A bar that fills its box as far as the number in its text is along its range; a display link target. */
    BAR('B');

    private final char letter;

    ElementKind(char letter) {
        this.letter = letter;
    }

    /** The one letter the element list shows for this kind. */
    public char letter() {
        return letter;
    }

    /** Whether a display link writes to this kind: lettering and bars, which both hold text. */
    public boolean linked() {
        return this == TEXT || this == BAR;
    }

    /** Whether this kind can be turned a quarter at a time. */
    public boolean turns() {
        return this == TEXT || this == BAR || this == SVG;
    }

    /** Guards a value off the wire or out of a save: null for one this build does not know. */
    public static ElementKind byOrdinal(int ordinal) {
        ElementKind[] all = values();
        return ordinal >= 0 && ordinal < all.length ? all[ordinal] : null;
    }
}
