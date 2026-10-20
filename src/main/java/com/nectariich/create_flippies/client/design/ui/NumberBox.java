package com.nectariich.create_flippies.client.design.ui;

import java.util.Locale;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.network.chat.Component;

/**
 * A text box for one number, as the workbench uses them everywhere: digits, a sign
 * and a point only, the label as its tooltip, and every finite value typed handed to
 * a setter at once. A value the screen writes in itself with {@link #setNumber}
 * does not come back through the setter, so filling the boxes after Fit or a load
 * does not run the change twice.
 */
public final class NumberBox extends EditBox {

    /** Takes a value typed into a box. */
    @FunctionalInterface
    public interface Setter {
        void set(float value);
    }

    private boolean filling;

    public NumberBox(Font font, int x, int y, int width, float value, Setter setter, Component label) {
        // One pixel in from the row on each side, so its border lines up with the buttons'
        super(font, x + 1, y + 1, width - 2, 18, label);
        setMaxLength(10);
        setFilter(text -> text.matches("-?\\d*(\\.\\d*)?"));
        setValue(format(value));
        setTooltip(Tooltip.create(label));
        setResponder(text -> {
            if (filling)
                return;
            try {
                float parsed = Float.parseFloat(text);
                if (Float.isFinite(parsed))
                    setter.set(parsed);
            } catch (NumberFormatException ignored) {
                // A lone "-" or "." on the way to a number
            }
        });
    }

    /** Shows a value without handing it to the setter. */
    public void setNumber(float value) {
        filling = true;
        setValue(format(value));
        filling = false;
    }

    /** A number as the boxes show it: up to three decimals, without trailing zeros. */
    public static String format(float value) {
        String text = String.format(Locale.ROOT, "%.3f", value);
        text = text.replaceAll("0+$", "");
        text = text.endsWith(".") ? text.substring(0, text.length() - 1) : text;
        return text.equals("-0") ? "0" : text;
    }
}
