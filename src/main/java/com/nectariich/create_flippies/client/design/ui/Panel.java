package com.nectariich.create_flippies.client.design.ui;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Supplier;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;

/**
 * Lays out the workbench's left panel, one row under another: widgets go to the
 * screen through an adder (the screen's own {@code addRenderableWidget} is
 * protected), and rows of plain text are kept here and drawn by {@link #render}, so a
 * tab can write its whole column top to bottom without the screen knowing what is in
 * it.
 *
 * Text rows are suppliers, read every frame, so a line such as the hitbox size
 * follows the numbers without the widgets being rebuilt.
 */
public final class Panel {

    public static final int X = 10;
    public static final int WIDTH = 184;
    public static final int ROW = 22;
    public static final int LINE = 11;
    public static final int GAP = 4;

    private record Label(int y, Supplier<Component> text, int color) {}

    public final Font font;
    private final Consumer<AbstractWidget> adder;
    private final List<Label> labels = new ArrayList<>();

    /** Where the next row goes. */
    public int y;

    public Panel(Font font, Consumer<AbstractWidget> adder, int y) {
        this.font = font;
        this.adder = adder;
        this.y = y;
    }

    public <T extends AbstractWidget> T add(T widget) {
        adder.accept(widget);
        return widget;
    }

    /** The width of one of {@code columns} equal columns across the panel. */
    public static int columnWidth(int columns) {
        return (WIDTH - GAP * (columns - 1)) / columns;
    }

    /** The left edge of column {@code index} of {@code columns}. */
    public static int column(int index, int columns) {
        return X + index * (columnWidth(columns) + GAP);
    }

    /** A button in column {@code index} of {@code columns} on the current row. */
    public Button button(Component text, int index, int columns, Button.OnPress press) {
        return add(Button.builder(text, press).bounds(column(index, columns), y, columnWidth(columns), 20).build());
    }

    public Button button(Component text, int index, int columns, Component tooltip, Button.OnPress press) {
        Button button = button(text, index, columns, press);
        button.setTooltip(Tooltip.create(tooltip));
        return button;
    }

    /** A number box in column {@code index} of {@code columns} on the current row. */
    public NumberBox number(int index, int columns, float value, NumberBox.Setter setter, Component label) {
        return add(new NumberBox(font, column(index, columns), y, columnWidth(columns), value, setter, label));
    }

    /** Moves to the next row of widgets. */
    public void row() {
        y += ROW;
    }

    /** A line of text, read each frame, then moves under it. */
    public void label(Supplier<Component> text, int color) {
        labels.add(new Label(y, text, color));
        y += LINE;
    }

    public void label(Component text, int color) {
        label(() -> text, color);
    }

    public void gap(int pixels) {
        y += pixels;
    }

    /** Whether a row is inside the panel's scrolling area. */
    @FunctionalInterface
    public interface Clip {
        boolean inView(int y, int height);
    }

    /**
     * Draws the text rows that are in view. A line too long for the panel is cut to
     * its first line, with the whole text as a tooltip.
     */
    public void render(GuiGraphics graphics, Clip clip, int mouseX, int mouseY) {
        for (Label label : labels) {
            if (!clip.inView(label.y, LINE - 1))
                continue;
            Component text = label.text.get();
            if (text == null)
                continue;
            List<FormattedCharSequence> lines = font.split(text, WIDTH);
            if (lines.isEmpty())
                continue;
            graphics.drawString(font, lines.get(0), X, label.y + 1, label.color);
            if (lines.size() > 1 && mouseX >= X && mouseX < X + WIDTH && mouseY >= label.y
                    && mouseY < label.y + LINE)
                graphics.renderTooltip(font, font.split(text, 240), mouseX, mouseY);
        }
    }
}
