package com.nectariich.create_flippies.client.design.ui;

import com.nectariich.create_flippies.content.design_workbench.DesignText;

/**
 * A text box being edited: the fields of a {@link DesignText}, open to change one at
 * a time as the workbench's widgets are used, and turned back into a sanitised
 * {@link DesignText} whenever the design is built.
 *
 * The rectangle is kept with its corners in any order while it is dragged; the
 * built text always has {@code x1 <= x2} and {@code z1 <= z2}.
 */
public final class TextDraft {

    public String text;
    public float x1, z1, x2, z2, y;
    public boolean onMoving;
    public String font;
    public boolean bold, italic, underline, strikethrough;
    public int color;
    public float size;
    public int align;
    public int quarters;
    public float glow;

    private TextDraft() {}

    public static TextDraft of(DesignText text) {
        TextDraft draft = new TextDraft();
        draft.text = text.text();
        draft.x1 = text.x1();
        draft.z1 = text.z1();
        draft.x2 = text.x2();
        draft.z2 = text.z2();
        draft.y = text.y();
        draft.onMoving = text.onMoving();
        draft.font = text.font();
        draft.bold = text.bold();
        draft.italic = text.italic();
        draft.underline = text.underline();
        draft.strikethrough = text.strikethrough();
        draft.color = text.color();
        draft.size = text.size();
        draft.align = text.align();
        draft.quarters = text.quarters();
        draft.glow = text.glow();
        return draft;
    }

    /**
     * The text as a design holds it, put through {@link DesignText#read} so the
     * workbench shows exactly what a saved design will hold.
     */
    public DesignText build() {
        return DesignText.read(new DesignText(text, minX(), minZ(), maxX(), maxZ(), y, onMoving, font, bold, italic,
                underline, strikethrough, color, size, align, quarters, glow).write());
    }

    public float minX() {
        return Math.min(x1, x2);
    }

    public float maxX() {
        return Math.max(x1, x2);
    }

    public float minZ() {
        return Math.min(z1, z2);
    }

    public float maxZ() {
        return Math.max(z1, z2);
    }

    /** Whether a plate point, in authored pixels, is inside the rectangle. */
    public boolean contains(double x, double z) {
        return x >= minX() && x <= maxX() && z >= minZ() && z <= maxZ();
    }

    /** Puts the corners in order, so the first is the top left in the top view. */
    public void normalise() {
        float minX = minX(), maxX = maxX(), minZ = minZ(), maxZ = maxZ();
        x1 = minX;
        x2 = maxX;
        z1 = minZ;
        z2 = maxZ;
    }
}
