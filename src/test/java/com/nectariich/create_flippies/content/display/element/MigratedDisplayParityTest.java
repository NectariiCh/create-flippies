package com.nectariich.create_flippies.content.display.element;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;

import com.nectariich.create_flippies.content.display.DisplayForm;
import com.nectariich.create_flippies.content.display.DisplayPrint;
import com.nectariich.create_flippies.content.label.PanelLabelFont;

import net.minecraft.nbt.CompoundTag;

import org.junit.jupiter.api.Test;

/**
 * A display saved as one line, before elements, must print exactly as it did: the
 * element it migrates to is laid out by {@link TextFit} at the same size and cut at
 * the same width the single-line renderer worked out from {@link DisplayPrint}, and
 * promises a display link the same number of columns. Checked on every form, at every
 * turn, fitted and at fixed sizes, for lines from one unit wide to far too long.
 */
class MigratedDisplayParityTest {

    private static final int[] WIDTHS = { 1, 7, 30, 61, 120, 400, 5000 };

    @Test
    void migratedLinesPrintAtTheSameSizeAndCutAsBefore() {
        for (DisplayForm form : DisplayForm.values())
            for (int quarters = 0; quarters < DisplayPrint.TURNS; quarters++)
                for (int size : sizes(form))
                    for (int width : WIDTHS)
                        check(form, quarters, size, width);
    }

    /**
     * A display placed fresh starts as an empty single-line save migrates: at the
     * form's default height, not fitted, so it prints and promises links what a new
     * display always did.
     */
    @Test
    void newDisplaysStartAtTheFormsDefaultHeight() {
        for (DisplayForm form : DisplayForm.values()) {
            ScreenElements elements = new ScreenElements();
            elements.read(new CompoundTag(), Viewport.of(form), form);
            DisplayPrint before = DisplayPrint.defaultFor(form);
            ScreenElement element = elements.get(0);
            assertEquals(before.size(), element.size(), form.id());
            assertEquals(before.columns(form), element.columns(), form.id());
        }
    }

    private static int[] sizes(DisplayForm form) {
        return new int[] { DisplayPrint.FIT, DisplayPrint.defaultFor(form).size(), 1, 5, 13, 30, 1000 };
    }

    private static void check(DisplayForm form, int quarters, int size, int width) {
        DisplayPrint print = new DisplayPrint(PanelLabelFont.FALLBACK, false, false, size, quarters,
                DisplayPrint.DEFAULT_COLOUR);
        CompoundTag tag = new CompoundTag();
        tag.putString("Text", "x");
        print.write(tag);
        ScreenElement element = ScreenElements.migrate(tag, Viewport.of(form), form);
        String where = form.id() + " q" + quarters + " size " + size + " width " + width;

        // What the single-line renderer did: fitted, the glass's height or what the
        // line's width allows, whichever is less (never below 1); fixed, the size
        // capped to the glass. Then cut to the glass's length at that size
        int before = print.fitted()
                ? Math.max(1, Math.min(print.sizeOn(form), DisplayPrint.widthLimit(form, quarters, width)))
                : print.sizeOn(form);
        int cutBefore = DisplayPrint.textWidthUnits(form, quarters, before);

        double[] cutAt = new double[1];
        TextFit.Layout layout = TextFit.layout(fixedWidth(width, cutAt), "x", element.size(), element.along(),
                element.across(), false);
        assertEquals(before, layout.size(), where);
        assertEquals(cutBefore, (int) cutAt[0], where);
        assertEquals(print.columns(form), element.columns(), where);
    }

    /** A font in which the line is {@code width} units wide; records the width it is cut to. */
    private static TextFit.Measurer fixedWidth(int width, double[] cutAt) {
        return new TextFit.Measurer() {
            @Override
            public double width(String text) {
                return width;
            }

            @Override
            public String head(String text, double maxWidth) {
                cutAt[0] = maxWidth;
                return text;
            }

            @Override
            public List<String> split(String text, double maxWidth) {
                return List.of(text);
            }

            @Override
            public double lineHeight() {
                return 9;
            }
        };
    }
}
