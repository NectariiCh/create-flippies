package com.nectariich.create_flippies.client.display.editor;

import java.util.function.DoubleSupplier;

import net.minecraft.client.gui.GuiGraphics;

/**
 * The display editor's zoom: a minus and a plus button at the bottom right of the
 * middle area, with the zoom written above them as a percentage of the fitted one.
 * It hugs the right sidebar's inner edge (the editor tells it where that is every
 * frame, so it follows the sidebar as it resizes or collapses).
 *
 * Each press (or Ctrl or Cmd with minus or plus, or a notch of the wheel over it)
 * steps to the next of a fixed ladder of percentages, from {@value #LEAST}% (far out)
 * to {@value #MOST}% (close in), so the zoom always reads as a round number and 100%
 * is always one of the stops. The view stays centred on the display; this only sets
 * {@link GlassView#setZoom}.
 */
public final class ZoomControl {

    private static final int[] STEPS = { 25, 33, 50, 67, 100, 150, 200, 300, 400, 600, 800, 1200, 1600 };
    private static final int LEAST = 25, MOST = 1600;
    private static final int BUTTON = 16, GAP = 2, PAD = 3, LABEL = 10, MARGIN = 6;
    private static final int WIDTH = 2 * BUTTON + GAP + 2 * PAD, HEIGHT = LABEL + BUTTON + 2 * PAD;
    private static final int BACKGROUND = 0xE0181C21, FACE = 0xFF2A3038, HOVER = 0xFF39414B, DISABLED = 0xFF5A6068;

    private final EditorContext context;
    private final DoubleSupplier fit;
    private int x, y;

    /** {@code fit} is the zoom that fits the display between the sidebars: 100%. */
    public ZoomControl(EditorContext context, DoubleSupplier fit) {
        this.context = context;
        this.fit = fit;
    }

    /** Puts the control's right edge against {@code rightEdge} and its foot at {@code bottom}. */
    public void layout(int rightEdge, int bottom) {
        x = rightEdge - MARGIN - WIDTH;
        y = bottom - MARGIN - HEIGHT;
    }

    public boolean contains(double mx, double my) {
        return mx >= x && mx < x + WIDTH && my >= y && my < y + HEIGHT;
    }

    public void render(GuiGraphics graphics, int mouseX, int mouseY) {
        graphics.fill(x - 1, y - 1, x + WIDTH + 1, y + HEIGHT + 1, 0xFF000000);
        graphics.fill(x, y, x + WIDTH, y + HEIGHT, BACKGROUND);
        double percent = percent();
        String label = Double.isNaN(percent) ? "-" : Math.round(percent) + "%";
        graphics.drawString(context.font, label, x + (WIDTH - context.font.width(label)) / 2, y + PAD + 1, 0xFFFFFF,
                false);
        button(graphics, outX(), "-", canStep(-1), mouseX, mouseY);
        button(graphics, inX(), "+", canStep(1), mouseX, mouseY);
    }

    private void button(GuiGraphics graphics, int bx, String sign, boolean enabled, int mouseX, int mouseY) {
        int by = buttonY();
        boolean hovered = enabled && mouseX >= bx && mouseX < bx + BUTTON && mouseY >= by && mouseY < by + BUTTON;
        graphics.fill(bx, by, bx + BUTTON, by + BUTTON, hovered ? HOVER : FACE);
        // Drawn rather than lettered, so both signs sit dead centre at any font
        int colour = enabled ? 0xFFFFFFFF : DISABLED;
        int cx = bx + BUTTON / 2, cy = by + BUTTON / 2;
        graphics.fill(cx - 4, cy - 1, cx + 4, cy + 1, colour);
        if (sign.equals("+"))
            graphics.fill(cx - 1, cy - 4, cx + 1, cy + 4, colour);
    }

    public boolean mouseClicked(double mx, double my, int button) {
        if (!contains(mx, my))
            return false;
        if (button == 0 && my >= buttonY() && my < buttonY() + BUTTON) {
            if (mx >= outX() && mx < outX() + BUTTON)
                step(-1);
            else if (mx >= inX() && mx < inX() + BUTTON)
                step(1);
        }
        return true;
    }

    public boolean mouseScrolled(double mx, double my, double delta) {
        if (!contains(mx, my))
            return false;
        if (delta != 0)
            step(delta > 0 ? 1 : -1);
        return true;
    }

    /** Steps the zoom one stop in (1) or out (-1) of wherever it is now. */
    public void step(int direction) {
        double reference = fit.getAsDouble();
        double percent = percent();
        if (!(reference > 0) || Double.isNaN(percent))
            return;
        int next = next(percent, direction);
        if (next > 0)
            context.view.setZoom(reference * 100 / next);
    }

    private boolean canStep(int direction) {
        double percent = percent();
        return !Double.isNaN(percent) && next(percent, direction) > 0;
    }

    /**
     * The first stop past {@code percent} that way, or 0 at the end of the ladder. A
     * zoom a hair off a stop (the fit changing under it) counts as on it, so a press
     * never lands on the stop it is already showing.
     */
    static int next(double percent, int direction) {
        if (direction > 0) {
            for (int stop : STEPS)
                if (stop > percent + 0.5)
                    return stop;
        } else {
            for (int i = STEPS.length - 1; i >= 0; i--)
                if (STEPS[i] < percent - 0.5)
                    return STEPS[i];
        }
        return 0;
    }

    /** The zoom as a percentage of the fitted one, or NaN before either is known. */
    private double percent() {
        double reference = fit.getAsDouble(), zoom = context.view.zoom();
        return reference > 0 && zoom > 0 ? reference / zoom * 100 : Double.NaN;
    }

    private int outX() {
        return x + PAD;
    }

    private int inX() {
        return x + PAD + BUTTON + GAP;
    }

    private int buttonY() {
        return y + PAD + LABEL;
    }
}
