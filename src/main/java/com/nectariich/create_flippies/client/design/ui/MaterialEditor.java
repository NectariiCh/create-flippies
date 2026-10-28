package com.nectariich.create_flippies.client.design.ui;

import static com.nectariich.create_flippies.client.design.ui.Lang.t;

import java.util.Locale;
import java.util.function.Consumer;

import com.nectariich.create_flippies.client.design.DesignMaterials;
import com.nectariich.create_flippies.client.design.DesignPreview;
import com.nectariich.create_flippies.compat.Checkboxes;
import com.nectariich.create_flippies.content.design_workbench.ControlDesign;
import com.nectariich.create_flippies.content.design_workbench.DesignMaterial;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Checkbox;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;

/**
 * The material editor: a window over the preview for one material element of the
 * model (the faces of one part drawn with one material), opened by double-clicking
 * the element in the parts list. A colour picker (saturation and value in the square,
 * hue in the bar, or a hex code), and sliders for how metallic, rough, opaque and
 * glowing it is and how much ambient occlusion it shows.
 *
 * On a button there is a second colour, the one the element turns while the lamp
 * lights it: Automatic keeps it the surface colour, and picking one turns Automatic
 * off. It only shows on elements the lamp lights (an "L" on the element or its part).
 *
 * On a gauge there is a checkbox that prints the gauge's face (its dial, baked by
 * the face generator for its kind) on this element in place of its material, taking
 * it off whichever element had it. The colour and sliders still apply: the colour
 * multiplies the dial, so it is left white to show the dial as printed.
 *
 * On a screen the same checkbox makes this element the screen's glass, whose content
 * is drawn in front of it.
 *
 * Every change goes straight into the design and the preview, so the window stays
 * open while the player looks; Reset puts the element back to how its model draws
 * it. The window is dragged about by its title bar, and opens where it was left
 * last, kept on screen. Like the save dialog, it lives inside the workbench screen, and its widgets are
 * laid out again whenever the screen's are.
 */
public final class MaterialEditor {

    public static final int WIDTH = 212;

    private static final int PICKER_W = 150, PICKER_H = 64, HUE_W = 12, SWATCH = 22;

    private final WorkbenchHost host;
    private final String part, material;

    /** The element as it is drawn now, edited or not. */
    private DesignMaterial current;

    /** Whether the picker edits the lit colour rather than the surface's; only on a button. */
    private boolean editingLit;

    /** The picked colour as hue, saturation and value, 0..1 each, kept so a grey keeps its hue. */
    private float hue, saturation, value;

    private int left, top, pickerX, pickerY;

    /** Where the window was dragged to, kept for the next one opened; MIN_VALUE until it is moved. */
    private static int placedLeft = Integer.MIN_VALUE, placedTop = Integer.MIN_VALUE;

    /** How tall the title bar is, which is where the window is dragged from. */
    private static final int TITLE_H = 28;

    /** The screen's size, to keep the window on it, and the widgets laid out, to move with it. */
    private int screenWidth, screenHeight;
    private final java.util.List<AbstractWidget> widgets = new java.util.ArrayList<>();

    /** Where in the title bar the window was grabbed. */
    private double grabX, grabY;

    private EditBox hex;
    private boolean settingHex;

    /** What the mouse is dragging: the square, the hue bar, the window by its title, or nothing. */
    private enum Drag {
        NONE, SQUARE, HUE, WINDOW
    }

    private Drag drag = Drag.NONE;

    public MaterialEditor(WorkbenchHost host, String part, String material) {
        this.host = host;
        this.part = part;
        this.material = material;
        load();
    }

    public String part() {
        return part;
    }

    public String material() {
        return material;
    }

    private String key() {
        return ControlDesign.elementKey(part, material);
    }

    /** Reads the element: its edit, or how its model draws it, or plain white while the model is loading. */
    private void load() {
        WorkbenchState state = host.state();
        DesignMaterial edit = state.materials.get(key());
        if (edit == null)
            edit = DesignMaterials.initial(DesignPreview.drawnMesh(), state.kind, part, material);
        current = edit != null ? edit : new DesignMaterial(0xFFFFFF, 1, 0, 0.5f, 0, 1, DesignMaterial.AUTO);
        syncHsv();
    }

    /** Whether the lit colour is offered: a button, and an element its lamp lights. */
    private boolean showsLit() {
        WorkbenchState state = host.state();
        return state.kind.isButton() && (state.lights.contains(part) || state.lights.contains(key()));
    }

    private int pickedColor() {
        return editingLit && showsLit() ? current.effectiveLitColor() : current.color();
    }

    private void syncHsv() {
        int rgb = pickedColor();
        float[] hsv = toHsv(rgb);
        // A grey has no hue of its own: keep the one the bar shows
        if (hsv[1] > 0)
            hue = hsv[0];
        saturation = hsv[1];
        value = hsv[2];
    }

    /** Takes an edit: into the design, and on to the preview. */
    private void set(DesignMaterial edited) {
        current = edited;
        host.state().materials.put(key(), edited);
        host.changed();
    }

    private void setPicked(int rgb) {
        if (editingLit && showsLit())
            set(current.withLitColor(rgb));
        else
            set(current.withColor(rgb));
        if (hex != null && !settingHex) {
            settingHex = true;
            hex.setValue(hexOf(rgb));
            settingHex = false;
        }
    }

    // ---------------------------------------------------------------- layout

    /** Lays out the widgets, on the preview side of the screen, to the right. */
    public void init(Font font, int width, int height, Consumer<AbstractWidget> adder) {
        screenWidth = width;
        screenHeight = height;
        widgets.clear();
        Consumer<AbstractWidget> add = widget -> {
            widgets.add(widget);
            adder.accept(widget);
        };
        if (!showsLit())
            editingLit = false;
        if (placedLeft == Integer.MIN_VALUE) {
            left = Math.max(Panel.X + Panel.WIDTH + 12, width - WIDTH - 8);
            top = Math.max(4, Math.min(24, height - height() - 4));
        } else {
            left = placedLeft;
            top = placedTop;
        }
        left = clampLeft(left);
        top = clampTop(top);
        int x = left + 8, w = WIDTH - 16, half = (w - 4) / 2;
        int y = top + 30;

        if (showsDial()) {
            String key = host.state().kind.isScreen() ? "material.screen" : "material.dial";
            Checkbox dial = Checkboxes.create(x, y, w, 20, t(key), host.state().isFace(part, material), selected -> {
                if (selected)
                    host.state().assignFace(part, material);
                else
                    host.state().clearFace();
                host.rebuild();
            });
            dial.setTooltip(Tooltip.create(t(key + ".tooltip")));
            add.accept(dial);
            y += 22;
        }

        if (showsLit()) {
            Button surface = Button.builder(t("material.surface"), b -> {
                editingLit = false;
                syncHsv();
                host.rebuild();
            }).bounds(x, y, half, 20).tooltip(Tooltip.create(t("material.surface.tooltip"))).build();
            surface.active = editingLit;
            add.accept(surface);
            Button lit = Button.builder(t("material.lit"), b -> {
                editingLit = true;
                syncHsv();
                host.rebuild();
            }).bounds(x + half + 4, y, half, 20).tooltip(Tooltip.create(t("material.lit.tooltip"))).build();
            lit.active = !editingLit;
            add.accept(lit);
            y += 24;
            if (editingLit) {
                add.accept(Checkboxes.create(x, y, w, 20, t("material.lit.auto"),
                        current.litColor() == DesignMaterial.AUTO, selected -> {
                            set(current.withLitColor(selected ? DesignMaterial.AUTO : current.color()));
                            syncHsv();
                            host.rebuild();
                        }));
                y += 22;
            }
        }

        pickerX = x;
        pickerY = y;
        y += PICKER_H + 6;

        hex = new EditBox(font, x + 1, y + 1, 70, 18, t("material.hex"));
        hex.setMaxLength(7);
        hex.setValue(hexOf(pickedColor()));
        hex.setTooltip(Tooltip.create(t("material.hex.tooltip")));
        hex.setResponder(text -> {
            if (settingHex)
                return;
            Integer rgb = parseHex(text);
            if (rgb == null)
                return;
            settingHex = true;
            setPicked(rgb);
            syncHsv();
            settingHex = false;
        });
        add.accept(hex);
        y += 24;

        add.accept(slider(x, y, half, "material.metallic", current.metallic(), 1, v -> set(current.withMetallic(v))));
        add.accept(slider(x + half + 4, y, half, "material.roughness", current.roughness(), 1,
                v -> set(current.withRoughness(v))));
        y += 22;
        add.accept(slider(x, y, half, "material.opacity", current.opacity(), 1, v -> set(current.withOpacity(v))));
        add.accept(slider(x + half + 4, y, half, "material.glow", current.glow(), DesignMaterial.MAX_GLOW,
                v -> set(current.withGlow(v))));
        y += 22;
        add.accept(slider(x, y, half, "material.occlusion", current.occlusion(), 1,
                v -> set(current.withOcclusion(v))));
        add.accept(Button.builder(t("material.reset"), b -> {
            host.state().materials.remove(key());
            load();
            host.rebuild();
        }).bounds(x + half + 4, y, half, 20).tooltip(Tooltip.create(t("material.reset.tooltip"))).build());
        y += 24;
        add.accept(Button.builder(Component.translatable("gui.done"), b -> host.closeMaterialEditor())
                .bounds(x, y, w, 20).build());
    }

    /** Whether the face checkbox is offered: on a gauge, for its dial, and on a screen, for its glass. */
    private boolean showsDial() {
        return host.state().kind.isGauge() || host.state().kind.isScreen();
    }

    private int height() {
        return 30 + (showsDial() ? 22 : 0) + (showsLit() ? 24 + (editingLit ? 22 : 0) : 0) + PICKER_H + 6 + 24 + 22 * 3 + 2 + 20 + 8;
    }

    /** A slider from 0 to {@code max}, shown as a percentage of 1. */
    private AbstractSliderButton slider(int x, int y, int w, String key, float start, float max,
            Consumer<Float> changed) {
        AbstractSliderButton slider = new AbstractSliderButton(x, y, w, 20, label(key, start), start / max) {
            @Override
            protected void updateMessage() {
                setMessage(label(key, (float) value * max));
            }

            @Override
            protected void applyValue() {
                changed.accept((float) value * max);
            }
        };
        slider.setTooltip(Tooltip.create(t(key + ".tooltip")));
        return slider;
    }

    private static Component label(String key, float amount) {
        return t(key, Math.round(amount * 100));
    }

    // ---------------------------------------------------------------- input

    /** Whether a point is on the window, so a click there goes nowhere else. */
    public boolean contains(double mouseX, double mouseY) {
        return mouseX >= left && mouseX < left + WIDTH && mouseY >= top && mouseY < top + height();
    }

    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (button != 0)
            return contains(mouseX, mouseY);
        if (inside(mouseX, mouseY, left, top, WIDTH, TITLE_H)) {
            drag = Drag.WINDOW;
            grabX = mouseX - left;
            grabY = mouseY - top;
            return true;
        }
        if (inside(mouseX, mouseY, pickerX, pickerY, PICKER_W, PICKER_H))
            drag = Drag.SQUARE;
        else if (inside(mouseX, mouseY, hueX(), pickerY, HUE_W, PICKER_H))
            drag = Drag.HUE;
        else
            return contains(mouseX, mouseY);
        pick(mouseX, mouseY);
        return true;
    }

    public boolean dragging() {
        return drag != Drag.NONE;
    }

    public boolean mouseDragged(double mouseX, double mouseY) {
        if (drag == Drag.NONE)
            return false;
        if (drag == Drag.WINDOW)
            moveTo((int) Math.round(mouseX - grabX), (int) Math.round(mouseY - grabY));
        else
            pick(mouseX, mouseY);
        return true;
    }

    /**
     * Moves the window, kept wholly on screen, and its widgets with it. They are moved
     * where they are rather than laid out again, which would rebuild the whole screen
     * on every step of the drag.
     */
    private void moveTo(int newLeft, int newTop) {
        newLeft = clampLeft(newLeft);
        newTop = clampTop(newTop);
        int dx = newLeft - left, dy = newTop - top;
        if (dx == 0 && dy == 0)
            return;
        left = newLeft;
        top = newTop;
        pickerX += dx;
        pickerY += dy;
        for (AbstractWidget widget : widgets) {
            widget.setX(widget.getX() + dx);
            widget.setY(widget.getY() + dy);
        }
        placedLeft = left;
        placedTop = top;
    }

    /** The window's widgets, which the screen draws on top of everything and gives clicks on the window to first. */
    public java.util.List<AbstractWidget> widgets() {
        return widgets;
    }

    private int clampLeft(int x) {
        return Mth.clamp(x, 0, Math.max(0, screenWidth - WIDTH));
    }

    private int clampTop(int y) {
        return Mth.clamp(y, 0, Math.max(0, screenHeight - height()));
    }

    public boolean mouseReleased() {
        boolean was = drag != Drag.NONE;
        drag = Drag.NONE;
        return was;
    }

    private void pick(double mouseX, double mouseY) {
        if (drag == Drag.SQUARE) {
            saturation = Mth.clamp((float) (mouseX - pickerX) / (PICKER_W - 1), 0, 1);
            value = 1 - Mth.clamp((float) (mouseY - pickerY) / (PICKER_H - 1), 0, 1);
        } else {
            hue = Mth.clamp((float) (mouseY - pickerY) / (PICKER_H - 1), 0, 0.999f);
        }
        // Picking a lit colour turns Automatic off, and the checkbox says so once laid out again
        boolean wasAuto = editingLit && current.litColor() == DesignMaterial.AUTO;
        setPicked(fromHsv(hue, saturation, value));
        if (wasAuto)
            host.rebuild();
    }

    private int hueX() {
        return pickerX + PICKER_W + 6;
    }

    private static boolean inside(double mouseX, double mouseY, int x, int y, int w, int h) {
        return mouseX >= x && mouseX < x + w && mouseY >= y && mouseY < y + h;
    }

    // ---------------------------------------------------------------- drawing

    /** The window's frame, title and picker, drawn under its widgets. */
    public void render(GuiGraphics graphics, Font font) {
        graphics.fill(left, top, left + WIDTH, top + height(), 0xF0181C22);
        graphics.fill(left + 1, top + 1, left + WIDTH - 1, top + TITLE_H, 0xFF22282F);
        graphics.renderOutline(left, top, WIDTH, height(), 0xFF606870);
        // Grip dots at the right of the title bar, where it is dragged from
        for (int i = 0; i < 3; i++)
            for (int j = 0; j < 2; j++)
                graphics.fill(left + WIDTH - 14 + j * 4, top + 8 + i * 4, left + WIDTH - 12 + j * 4,
                        top + 10 + i * 4, 0xFF707880);
        int x = left + 8;
        graphics.drawString(font, t("material.title"), x, top + 7, 0xFFFFFF);
        String name = part + " / " + material;
        // Clear of the grip dots
        if (font.width(name) > WIDTH - 26)
            name = font.plainSubstrByWidth(name, WIDTH - 26 - font.width("..")) + "..";
        graphics.drawString(font, name, x, top + 18, 0xA0A0A0, false);

        // The square: across, white to the hue; down, bright to black. Drawn in columns,
        // each a vertical gradient, which is as smooth as the screen's pixels
        int hueRgb = fromHsv(hue, 1, 1);
        for (int i = 0; i < PICKER_W; i++) {
            float s = i / (float) (PICKER_W - 1);
            int upper = 0xFF000000 | mix(0xFFFFFF, hueRgb, s);
            graphics.fillGradient(pickerX + i, pickerY, pickerX + i + 1, pickerY + PICKER_H, upper, 0xFF000000);
        }
        graphics.renderOutline(pickerX - 1, pickerY - 1, PICKER_W + 2, PICKER_H + 2, 0xFF404448);
        int cx = pickerX + Math.round(saturation * (PICKER_W - 1));
        int cy = pickerY + Math.round((1 - value) * (PICKER_H - 1));
        graphics.renderOutline(cx - 2, cy - 2, 5, 5, value > 0.5f ? 0xFF000000 : 0xFFFFFFFF);

        // The hue bar, top to bottom, in six gradient steps
        int hx = hueX();
        for (int step = 0; step < 6; step++) {
            int y0 = pickerY + step * PICKER_H / 6, y1 = pickerY + (step + 1) * PICKER_H / 6;
            graphics.fillGradient(hx, y0, hx + HUE_W, y1, 0xFF000000 | fromHsv(step / 6f, 1, 1),
                    0xFF000000 | fromHsv((step + 1) / 6f % 1, 1, 1));
        }
        graphics.renderOutline(hx - 1, pickerY - 1, HUE_W + 2, PICKER_H + 2, 0xFF404448);
        int hy = pickerY + Math.round(hue * (PICKER_H - 1));
        graphics.fill(hx - 2, hy - 1, hx + HUE_W + 2, hy + 1, 0xFFFFFFFF);

        // The colour as it is now, beside the hex code
        int sx = left + WIDTH - 8 - SWATCH, sy = pickerY + PICKER_H + 6;
        graphics.fill(sx - 1, sy - 1, sx + SWATCH + 1, sy + 21, 0xFF404448);
        graphics.fill(sx, sy, sx + SWATCH, sy + 20, 0xFF000000 | pickedColor());
        if (editingLit && current.litColor() == DesignMaterial.AUTO)
            graphics.drawString(font, t("material.lit.auto.short"), sx - 4 - font.width(t("material.lit.auto.short")),
                    sy + 6, 0xA0A0A0, false);
    }

    private static int mix(int from, int to, float t) {
        int r = Math.round(Mth.lerp(t, from >> 16 & 0xFF, to >> 16 & 0xFF));
        int g = Math.round(Mth.lerp(t, from >> 8 & 0xFF, to >> 8 & 0xFF));
        int b = Math.round(Mth.lerp(t, from & 0xFF, to & 0xFF));
        return r << 16 | g << 8 | b;
    }

    /** Hue, saturation and value, 0..1 each, of a 0xRRGGBB colour. */
    public static float[] toHsv(int rgb) {
        float r = (rgb >> 16 & 0xFF) / 255f, g = (rgb >> 8 & 0xFF) / 255f, b = (rgb & 0xFF) / 255f;
        float max = Math.max(r, Math.max(g, b)), min = Math.min(r, Math.min(g, b)), range = max - min;
        float h = 0;
        if (range > 0) {
            if (max == r)
                h = ((g - b) / range) / 6;
            else if (max == g)
                h = ((b - r) / range + 2) / 6;
            else
                h = ((r - g) / range + 4) / 6;
            if (h < 0)
                h += 1;
        }
        return new float[] { h, max > 0 ? range / max : 0, max };
    }

    /** The 0xRRGGBB colour of a hue, saturation and value, 0..1 each. */
    public static int fromHsv(float h, float s, float v) {
        float sector = (h - (float) Math.floor(h)) * 6;
        int i = (int) sector % 6;
        float f = sector - (int) sector;
        float p = v * (1 - s), q = v * (1 - s * f), t = v * (1 - s * (1 - f));
        float r, g, b;
        switch (i) {
        case 0 -> { r = v; g = t; b = p; }
        case 1 -> { r = q; g = v; b = p; }
        case 2 -> { r = p; g = v; b = t; }
        case 3 -> { r = p; g = q; b = v; }
        case 4 -> { r = t; g = p; b = v; }
        default -> { r = v; g = p; b = q; }
        }
        return Math.round(r * 255) << 16 | Math.round(g * 255) << 8 | Math.round(b * 255);
    }

    static String hexOf(int rgb) {
        return String.format(Locale.ROOT, "#%06X", rgb & 0xFFFFFF);
    }

    /** A colour from "#RRGGBB" or "RRGGBB", or null for anything else. */
    static Integer parseHex(String text) {
        String digits = text.trim();
        if (digits.startsWith("#"))
            digits = digits.substring(1);
        if (!digits.matches("[0-9a-fA-F]{6}"))
            return null;
        return Integer.parseInt(digits, 16);
    }
}
