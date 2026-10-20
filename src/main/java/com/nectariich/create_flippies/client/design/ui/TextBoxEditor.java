package com.nectariich.create_flippies.client.design.ui;

import static com.nectariich.create_flippies.client.design.ui.Lang.t;

import com.nectariich.create_flippies.client.design.DesignCamera;
import com.nectariich.create_flippies.client.design.DesignGeometry;
import com.nectariich.create_flippies.content.design_workbench.ControlDesign;
import com.nectariich.create_flippies.content.design_workbench.DesignText;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;

/**
 * Drawing and arranging text boxes on the preview, in the workbench's top view.
 *
 * A left drag on empty plate draws a new box; a click on a box selects it; a drag
 * inside the selected box moves it; a drag on one of its corner handles resizes it.
 * Right or middle drag pans the view and the wheel zooms about the cursor. Every
 * point is read through {@link DesignCamera#screenToPlate}, which is exact in the top
 * view, so a box lands where it was drawn; nothing is taken while the camera is still
 * on its way there.
 *
 * Every box, including one "on the moving part", is edited in the unposed frame:
 * the workbench shows the moving parts unposed while this editor is active
 * ({@code DesignPreview.REST}), so the drawn text, its outline and the rectangle
 * tested here coincide. A posed preview would carry a moving part's text away from
 * the rectangle the mouse works on.
 *
 * Corners snap to an eighth of a pixel, and boxes are held to the reach every design
 * box is held to. A new box starts on the surface under its middle, the top of the
 * highest visible part there, so its text is not buried in the model.
 */
public final class TextBoxEditor {

    /** How close to a corner handle, in GUI pixels, a press resizes rather than moves. */
    private static final double HANDLE_REACH = 4;

    /** A drawn box smaller than this, in pixels, was a click rather than a drag. */
    private static final float MIN_SIZE = 0.5f;

    private static final float MIN_COORD = -8, MAX_COORD = 24;

    private enum Mode { NONE, CREATE, MOVE, RESIZE, PAN }

    private final WorkbenchHost host;
    private Mode mode = Mode.NONE;

    /** Where the drag started on the plate, in pixels. */
    private double startX, startZ;
    private double currentX, currentZ;

    /** The selected box's rectangle when a move or resize started: minX, minZ, maxX, maxZ. */
    private float[] original;

    /** The corner being dragged: bit 0 set for the max-x side, bit 1 for the max-z side. */
    private int corner;

    public TextBoxEditor(WorkbenchHost host) {
        this.host = host;
    }

    public boolean dragging() {
        return mode != Mode.NONE;
    }

    private static float snap(double value) {
        float snapped = Math.round(value * 8) / 8f;
        return Math.max(MIN_COORD, Math.min(MAX_COORD, snapped));
    }

    public boolean mouseClicked(double mouseX, double mouseY, int button, int width, int height) {
        if (button == 1 || button == 2) {
            if (!DesignCamera.isTop())
                return false;
            mode = Mode.PAN;
            return true;
        }
        if (button != 0)
            return false;
        double[] plate = DesignCamera.screenToPlate(mouseX, mouseY, width, height);
        if (plate == null)
            return false;
        WorkbenchState state = host.state();
        startX = currentX = plate[0];
        startZ = currentZ = plate[1];

        TextDraft selected = state.selectedText();
        if (selected != null) {
            int handle = handleAt(selected, mouseX, mouseY, width, height);
            if (handle >= 0) {
                corner = handle;
                original = rect(selected);
                mode = Mode.RESIZE;
                return true;
            }
            if (selected.contains(plate[0], plate[1])) {
                original = rect(selected);
                mode = Mode.MOVE;
                return true;
            }
        }
        // The topmost box under the cursor: the last drawn is drawn over the others
        for (int i = state.texts.size() - 1; i >= 0; i--)
            if (state.texts.get(i).contains(plate[0], plate[1])) {
                state.selected = i;
                original = rect(state.texts.get(i));
                mode = Mode.MOVE;
                host.rebuild();
                return true;
            }
        mode = Mode.CREATE;
        return true;
    }

    public boolean mouseDragged(double mouseX, double mouseY, int button, double dragX, double dragY, int width,
            int height) {
        if (mode == Mode.NONE)
            return false;
        if (mode == Mode.PAN) {
            DesignCamera.pan(dragX, dragY, width, height);
            return true;
        }
        double[] plate = DesignCamera.screenToPlate(mouseX, mouseY, width, height);
        if (plate == null)
            return true;
        currentX = plate[0];
        currentZ = plate[1];
        TextDraft selected = host.state().selectedText();
        if (mode == Mode.MOVE && selected != null) {
            // Moved whole, by whole eighths, and stopped at the reach rather than squashed
            float dx = Math.round((currentX - startX) * 8) / 8f, dz = Math.round((currentZ - startZ) * 8) / 8f;
            dx = Math.max(MIN_COORD - original[0], Math.min(MAX_COORD - original[2], dx));
            dz = Math.max(MIN_COORD - original[1], Math.min(MAX_COORD - original[3], dz));
            selected.x1 = original[0] + dx;
            selected.z1 = original[1] + dz;
            selected.x2 = original[2] + dx;
            selected.z2 = original[3] + dz;
            host.changed();
        } else if (mode == Mode.RESIZE && selected != null) {
            float x = snap(currentX), z = snap(currentZ);
            selected.x1 = (corner & 1) == 0 ? x : original[0];
            selected.x2 = (corner & 1) == 0 ? original[2] : x;
            selected.z1 = (corner & 2) == 0 ? z : original[1];
            selected.z2 = (corner & 2) == 0 ? original[3] : z;
            host.changed();
        }
        return true;
    }

    public boolean mouseReleased(double mouseX, double mouseY, int button, int width, int height) {
        if (mode == Mode.NONE)
            return false;
        Mode was = mode;
        mode = Mode.NONE;
        WorkbenchState state = host.state();
        if (was == Mode.CREATE) {
            float x1 = snap(Math.min(startX, currentX)), x2 = snap(Math.max(startX, currentX));
            float z1 = snap(Math.min(startZ, currentZ)), z2 = snap(Math.max(startZ, currentZ));
            if (x2 - x1 >= MIN_SIZE && z2 - z1 >= MIN_SIZE && state.texts.size() < ControlDesign.MAX_TEXTS) {
                float y = state.info == null ? 0
                        : (float) DesignGeometry.topAt(state.info, state.visibleParts(), state.scale(),
                                state.offsetX, state.offsetY, state.offsetZ, (x1 + x2) / 2, (z1 + z2) / 2);
                DesignText text = DesignText.create(x1, z1, x2, z2, Math.min(16, y));
                state.texts.add(TextDraft.of(text));
                state.selected = state.texts.size() - 1;
            } else {
                // A click on empty plate lets go of the selection
                state.selected = -1;
            }
            host.rebuild();
        } else if (was == Mode.RESIZE || was == Mode.MOVE) {
            TextDraft selected = state.selectedText();
            if (selected != null)
                selected.normalise();
            host.changed();
        }
        return true;
    }

    /** Zooms the top view about the cursor; a notch is about 15%. */
    public boolean mouseScrolled(double mouseX, double mouseY, double delta, int width, int height) {
        if (!DesignCamera.isTop())
            return false;
        DesignCamera.zoomAbout(mouseX, mouseY, width, height, Math.pow(1.15, -delta));
        return true;
    }

    private static float[] rect(TextDraft text) {
        return new float[] { text.minX(), text.minZ(), text.maxX(), text.maxZ() };
    }

    /** The corner handle of a box under the cursor, as {@link #corner} numbers them, or -1. */
    private static int handleAt(TextDraft text, double mouseX, double mouseY, int width, int height) {
        for (int c = 0; c < 4; c++) {
            double[] screen = DesignCamera.plateToScreen((c & 1) == 0 ? text.minX() : text.maxX(),
                    (c & 2) == 0 ? text.minZ() : text.maxZ(), width, height);
            if (screen != null && Math.abs(screen[0] - mouseX) <= HANDLE_REACH
                    && Math.abs(screen[1] - mouseY) <= HANDLE_REACH)
                return c;
        }
        return -1;
    }

    /**
     * Draws the boxes over the preview: every box's outline faintly, the selected
     * one brightly with its corner handles, and the box being drawn. Then a line of
     * help at the bottom of the preview.
     */
    public void render(GuiGraphics graphics, Font font, int width, int height) {
        if (!DesignCamera.topReady())
            return;
        WorkbenchState state = host.state();
        for (int i = 0; i < state.texts.size(); i++) {
            TextDraft text = state.texts.get(i);
            boolean selected = i == state.selected;
            outline(graphics, text.minX(), text.minZ(), text.maxX(), text.maxZ(), width, height,
                    selected ? 0xFFFFD040 : 0x80FFFFFF);
            if (selected)
                for (int c = 0; c < 4; c++) {
                    double[] screen = DesignCamera.plateToScreen((c & 1) == 0 ? text.minX() : text.maxX(),
                            (c & 2) == 0 ? text.minZ() : text.maxZ(), width, height);
                    if (screen == null)
                        continue;
                    int x = (int) Math.round(screen[0]), y = (int) Math.round(screen[1]);
                    graphics.fill(x - 2, y - 2, x + 3, y + 3, 0xFF000000);
                    graphics.fill(x - 1, y - 1, x + 2, y + 2, 0xFFFFD040);
                }
        }
        if (mode == Mode.CREATE)
            outline(graphics, Math.min(startX, currentX), Math.min(startZ, currentZ), Math.max(startX, currentX),
                    Math.max(startZ, currentZ), width, height, 0xFF60D0FF);

        var help = t("text.view_help");
        int left = Panel.X + Panel.WIDTH + 12;
        graphics.drawString(font, help, Math.max(left, width - 6 - font.width(help)), height - 14, 0xC0C0C0);
    }

    private static void outline(GuiGraphics graphics, double minX, double minZ, double maxX, double maxZ, int width,
            int height, int color) {
        double[] a = DesignCamera.plateToScreen(minX, minZ, width, height);
        double[] b = DesignCamera.plateToScreen(maxX, maxZ, width, height);
        if (a == null || b == null)
            return;
        int x1 = (int) Math.floor(Math.min(a[0], b[0])), y1 = (int) Math.floor(Math.min(a[1], b[1]));
        int x2 = (int) Math.ceil(Math.max(a[0], b[0])), y2 = (int) Math.ceil(Math.max(a[1], b[1]));
        graphics.renderOutline(x1, y1, Math.max(1, x2 - x1), Math.max(1, y2 - y1), color);
    }
}
