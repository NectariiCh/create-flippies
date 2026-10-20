package com.nectariich.create_flippies.content.schematic_arrow;

import java.util.Locale;

/** What a right click does in the schematic arrow mode. */
public enum ArrowAction {

    /** Click points to draw a polyline; click the last point again to finish. */
    DRAW,

    /** Click two corners to fit the imported SVG between them and raise it. */
    STAMP,

    /** Click an arrow to pick it up, then click where that point should go. */
    MOVE;

    public ArrowAction next() {
        return values()[(ordinal() + 1) % values().length];
    }

    public String key() {
        return name().toLowerCase(Locale.ROOT);
    }

    public static ArrowAction byIndex(int index) {
        return index >= 0 && index < values().length ? values()[index] : DRAW;
    }
}
