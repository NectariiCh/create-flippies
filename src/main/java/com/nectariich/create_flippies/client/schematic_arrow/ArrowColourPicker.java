package com.nectariich.create_flippies.client.schematic_arrow;

import java.util.function.IntConsumer;

import com.nectariich.create_flippies.client.design.ui.MaterialEditor;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.util.Mth;

/**
 * The colour picker the arrow menu's swatch opens: saturation and value in the
 * square, hue in the bar beside it, drawn and picked the way the material editor's
 * is. Every pick goes straight to the menu, which fills the hex box with it.
 *
 * The hue, saturation and value are kept rather than worked out from the colour
 * each time, so a grey or a black keeps the hue the bar shows.
 */
final class ArrowColourPicker {

    private static final int SQUARE_W = 120, SQUARE_H = 64, HUE_W = 12, PAD = 6;

    static final int WIDTH = PAD + SQUARE_W + 6 + HUE_W + PAD;
    static final int HEIGHT = PAD + SQUARE_H + PAD;

    private final int x, y;
    private final IntConsumer onPick;
    private float hue, saturation, value;

    private enum Drag {
        NONE, SQUARE, HUE
    }

    private Drag drag = Drag.NONE;

    ArrowColourPicker(int x, int y, int rgb, IntConsumer onPick) {
        this.x = x;
        this.y = y;
        this.onPick = onPick;
        sync(rgb);
    }

    /** Takes a colour set elsewhere, the hex box, without a pick of its own. */
    void sync(int rgb) {
        float[] hsv = MaterialEditor.toHsv(rgb & 0xFFFFFF);
        if (hsv[1] > 0)
            hue = hsv[0];
        saturation = hsv[1];
        value = hsv[2];
    }

    boolean contains(double mouseX, double mouseY) {
        return inside(mouseX, mouseY, x, y, WIDTH, HEIGHT);
    }

    boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (button != 0)
            return contains(mouseX, mouseY);
        if (inside(mouseX, mouseY, squareX(), squareY(), SQUARE_W, SQUARE_H))
            drag = Drag.SQUARE;
        else if (inside(mouseX, mouseY, hueX(), squareY(), HUE_W, SQUARE_H))
            drag = Drag.HUE;
        else
            return contains(mouseX, mouseY);
        pick(mouseX, mouseY);
        return true;
    }

    boolean mouseDragged(double mouseX, double mouseY) {
        if (drag == Drag.NONE)
            return false;
        pick(mouseX, mouseY);
        return true;
    }

    boolean mouseReleased() {
        boolean was = drag != Drag.NONE;
        drag = Drag.NONE;
        return was;
    }

    private void pick(double mouseX, double mouseY) {
        if (drag == Drag.SQUARE) {
            saturation = Mth.clamp((float) (mouseX - squareX()) / (SQUARE_W - 1), 0, 1);
            value = 1 - Mth.clamp((float) (mouseY - squareY()) / (SQUARE_H - 1), 0, 1);
        } else {
            hue = Mth.clamp((float) (mouseY - squareY()) / (SQUARE_H - 1), 0, 0.999f);
        }
        onPick.accept(0xFF000000 | MaterialEditor.fromHsv(hue, saturation, value));
    }

    void render(GuiGraphics graphics) {
        graphics.pose().pushPose();
        // Over the menu's buttons and their text
        graphics.pose().translate(0, 0, 300);
        graphics.fill(x, y, x + WIDTH, y + HEIGHT, 0xF0181C22);
        graphics.renderOutline(x, y, WIDTH, HEIGHT, 0xFF606870);

        int sx = squareX(), sy = squareY();
        int hueRgb = MaterialEditor.fromHsv(hue, 1, 1);
        for (int i = 0; i < SQUARE_W; i++) {
            float s = i / (float) (SQUARE_W - 1);
            int upper = 0xFF000000 | mix(0xFFFFFF, hueRgb, s);
            graphics.fillGradient(sx + i, sy, sx + i + 1, sy + SQUARE_H, upper, 0xFF000000);
        }
        graphics.renderOutline(sx - 1, sy - 1, SQUARE_W + 2, SQUARE_H + 2, 0xFF404448);
        int cx = sx + Math.round(saturation * (SQUARE_W - 1));
        int cy = sy + Math.round((1 - value) * (SQUARE_H - 1));
        graphics.renderOutline(cx - 2, cy - 2, 5, 5, value > 0.5f ? 0xFF000000 : 0xFFFFFFFF);

        int hx = hueX();
        for (int step = 0; step < 6; step++) {
            int y0 = sy + step * SQUARE_H / 6, y1 = sy + (step + 1) * SQUARE_H / 6;
            graphics.fillGradient(hx, y0, hx + HUE_W, y1, 0xFF000000 | MaterialEditor.fromHsv(step / 6f, 1, 1),
                    0xFF000000 | MaterialEditor.fromHsv((step + 1) / 6f % 1, 1, 1));
        }
        graphics.renderOutline(hx - 1, sy - 1, HUE_W + 2, SQUARE_H + 2, 0xFF404448);
        int hy = sy + Math.round(hue * (SQUARE_H - 1));
        graphics.fill(hx - 2, hy - 1, hx + HUE_W + 2, hy + 1, 0xFFFFFFFF);
        graphics.pose().popPose();
    }

    private int squareX() {
        return x + PAD;
    }

    private int squareY() {
        return y + PAD;
    }

    private int hueX() {
        return squareX() + SQUARE_W + 6;
    }

    private static int mix(int from, int to, float t) {
        int r = Math.round(Mth.lerp(t, from >> 16 & 0xFF, to >> 16 & 0xFF));
        int g = Math.round(Mth.lerp(t, from >> 8 & 0xFF, to >> 8 & 0xFF));
        int b = Math.round(Mth.lerp(t, from & 0xFF, to & 0xFF));
        return r << 16 | g << 8 | b;
    }

    private static boolean inside(double mouseX, double mouseY, int x, int y, int w, int h) {
        return mouseX >= x && mouseX < x + w && mouseY >= y && mouseY < y + h;
    }
}
