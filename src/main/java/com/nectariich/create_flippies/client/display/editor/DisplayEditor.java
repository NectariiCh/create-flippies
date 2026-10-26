package com.nectariich.create_flippies.client.display.editor;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;

import com.nectariich.create_flippies.client.decal.ClientDecals;
import com.nectariich.create_flippies.client.design.DesignImporter;
import com.nectariich.create_flippies.client.display.ElementPreview;
import com.nectariich.create_flippies.content.display.element.ElementKind;
import com.nectariich.create_flippies.content.display.element.ScreenElement;
import com.nectariich.create_flippies.content.display.element.ScreenElements;
import com.nectariich.create_flippies.content.display.element.Viewport;

import net.minecraft.ChatFormatting;
import net.minecraft.Util;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;

/**
 * The visual display editor: the display seen through a camera ({@link GlassView}),
 * with the glass tools over it, a sidebar on either side holding the menus, a top bar
 * (Done, Rotate, and the summary line), the tool strip along the bottom and the zoom
 * buttons at the bottom right of the middle area.
 *
 * This class is the frame that holds the parts together; it owns no editing of its
 * own. It makes the one {@link EditorContext} the parts share, lays them out, draws
 * them in order (glass overlay first, context menu last) and hands input to them in
 * the opposite order, so whatever is drawn on top gets the first say. It also gives
 * the editor's own keyboard shortcuts their meaning, and tells the renderer what to
 * draw for the edited display ({@link ElementPreview}): the session's version of it,
 * hidden elements left out, empty lettering as a dimmed placeholder and bars at their
 * preview values.
 *
 * Two hosts run it, the screwdriver menu over a display in the world and the Design
 * Workbench over a screen's starting layout ({@link EditorHost}); they call
 * {@link #init} on every resize, {@link #tick}, {@link #render}, the input methods,
 * and {@link #close} once, however the editor ends.
 */
public final class DisplayEditor implements EditorContext.Services {

    private static final String LANG = "gui.create_flippies.display_editor.";

    /** The top bar's height, and its buttons' height and width. */
    private static final int TOP_BAR = 20, BUTTON_HEIGHT = 16, BUTTON_WIDTH = 60;
    /** What the tool strip takes from the bottom of the screen, which the fitted display stays above. */
    private static final int STRIP_ROOM = 28;
    /** How long a status message stays up. */
    private static final long STATUS_MILLIS = 4000;

    private static final int BAR_FILL = 0xFF1A1E23, BAR_EDGE = 0xFF0C0E11, SUMMARY = 0xA0A8B0;
    /** The colour an empty text element's placeholder is drawn in: dim, so it never passes for content. */
    private static final int PLACEHOLDER = 0xFF5A6068;

    private final EditorHost host;
    private final Font font;
    private final EditorContext context;
    private final EditorLayout layout;
    private final GlassTools glass;
    private final ToolStrip strip;
    private final ZoomControl zoom;
    private Button done, rotate;

    private ContextMenu contextMenu;
    private Component status = Component.empty();
    private long statusUntil;

    private int width, height;
    /** The zoom that fits the display between the sidebars: 100%. NaN until first worked out. */
    private double fit = Double.NaN;
    /** Whether the zoom has been fitted to the ready view; it is only ever fitted once. */
    private boolean fitted;
    private boolean closed;

    public DisplayEditor(EditorHost host, Font font) {
        this.host = host;
        this.font = font;
        this.context = new EditorContext(host, font, this);
        this.layout = new EditorLayout(font,
                List.of(new HierarchyMenu(context), new InspectorMenu(context), new PlaceholderMenu(font)));
        layout.onContextMenu(this::openContextMenu);
        // A put that never lands would otherwise just not appear: say so, and why when it is a picture
        context.session.onRefused(element -> status(Component.translatable(LANG
                + (element.art().isEmpty() ? "put_refused" : "put_refused_art")).withStyle(ChatFormatting.RED)));
        this.glass = new GlassTools(context);
        this.strip = new ToolStrip(context);
        this.zoom = new ZoomControl(context, () -> Double.isNaN(fit) ? context.view.zoom() : fit);
        ElementPreview.begin(host.owner(), live -> previewed());
        context.view.open();
    }

    // ---- lifecycle ----

    /** Lays everything out for a screen of this size; on opening and on every resize. */
    public void init(int width, int height) {
        this.width = width;
        this.height = height;
        layout.layout(width, height, TOP_BAR, height);
        int top = (TOP_BAR - BUTTON_HEIGHT) / 2;
        int doneX = width - BUTTON_WIDTH - 4;
        done = Button.builder(Component.translatable("gui.done"), b -> host.close())
                .bounds(doneX, top, BUTTON_WIDTH, BUTTON_HEIGHT).build();
        Component rotateLabel = Component.translatable(LANG + "rotate");
        int rotateWidth = Math.max(BUTTON_WIDTH, font.width(rotateLabel) + 12);
        rotate = Button.builder(rotateLabel, b -> host.rotate())
                .bounds(doneX - rotateWidth - 4, top, rotateWidth, BUTTON_HEIGHT).build();
        rotate.visible = host.canRotate();
        placeFloating();
        // Fitted ahead of the camera's arrival, so the flight lands at about the right
        // zoom; fitted for good once the view is ready (see tick)
        if (!fitted) {
            fit = fitZoom();
            context.view.setZoom(fit);
        }
    }

    public void tick() {
        if (closed)
            return;
        if (!host.alive() || context.session.live() == null) {
            host.close();
            return;
        }
        ElementPreview.retarget(host.owner());
        context.session.tick();
        layout.tick();
        // Once only: from here on the zoom is the player's
        if (!fitted && context.view.ready() && width > 0) {
            fitted = true;
            fit = fitZoom();
            context.view.setZoom(fit);
        }
        // A selection may name elements that have gone (someone else deleted them)
        dropMissing();
    }

    /**
     * Ends the editor: sends anything still waiting, stops drawing the edited display
     * the editor's way, keeps the layout and flies the camera back. Safe to call twice.
     */
    public void close() {
        if (closed)
            return;
        closed = true;
        glass.cancel();
        context.session.flush();
        ElementPreview.end();
        layout.save();
        context.view.close();
    }

    // ---- drawing ----

    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTicks) {
        placeFloating();
        // Nothing under an open context menu shows as hovered
        boolean overMenu = contextMenu != null && contextMenu.contains(mouseX, mouseY);
        int mx = overMenu ? -1 : mouseX, my = overMenu ? -1 : mouseY;

        glass.render(graphics, mx, my, width, height);
        renderTopBar(graphics, mx, my, partialTicks);
        layout.render(graphics, mx, my, partialTicks);
        strip.render(graphics, mx, my);
        zoom.render(graphics, mx, my);
        renderStatus(graphics);
        if (contextMenu != null)
            contextMenu.render(graphics, font, mouseX, mouseY);
    }

    private void renderTopBar(GuiGraphics graphics, int mouseX, int mouseY, float partialTicks) {
        graphics.fill(0, 0, width, TOP_BAR, BAR_FILL);
        graphics.fill(0, TOP_BAR - 1, width, TOP_BAR, BAR_EDGE);
        int textY = (TOP_BAR - 8) / 2;
        graphics.drawString(font, host.title(), 6, textY, 0xFFFFFF, false);
        int summaryX = 6 + font.width(host.title()) + 12;
        int room = (rotate.visible ? rotate.getX() : done.getX()) - 8 - summaryX;
        if (room > 0)
            graphics.drawString(font, font.plainSubstrByWidth(summary(context.session.live(),
                    context.session.viewport()).getString(), room), summaryX, textY, SUMMARY, false);
        if (rotate.visible)
            rotate.render(graphics, mouseX, mouseY, partialTicks);
        done.render(graphics, mouseX, mouseY, partialTicks);
    }

    /** The last status message, above the tool strip, while it lasts. */
    private void renderStatus(GuiGraphics graphics) {
        if (Util.getMillis() > statusUntil || status.getString().isEmpty())
            return;
        int w = font.width(status);
        int x = (width - w) / 2, y = height - STRIP_ROOM - 14;
        graphics.pose().pushPose();
        graphics.pose().translate(0, 0, 300);
        graphics.fill(x - 4, y - 3, x + w + 4, y + 11, 0xE0181C21);
        graphics.drawString(font, status, x, y, 0xFFFFFF, false);
        graphics.pose().popPose();
    }

    /**
     * One line describing the display: its size, how many elements it holds of the
     * {@value ScreenElements#MAX} it may, and how much of the text budget its lettering
     * uses. The workbench's Screen tab shows the same line.
     */
    public static Component summary(ScreenElements elements, Viewport viewport) {
        return Component.translatable(LANG + "summary",
                pixels(viewport.widthPixels()) + " × " + pixels(viewport.heightPixels()),
                elements == null ? 0 : elements.size(), ScreenElements.MAX,
                elements == null ? 0 : elements.textLength(), ScreenElements.MAX_TOTAL_TEXT);
    }

    private static String pixels(double pixels) {
        double tenth = Math.round(pixels * 10) / 10.0;
        return tenth == Math.rint(tenth) ? Long.toString((long) tenth) : Double.toString(tenth);
    }

    // ---- input ----

    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (contextMenu != null) {
            // A click anywhere closes it; one on an entry runs it first
            ContextMenu menu = contextMenu;
            contextMenu = null;
            if (menu.contains(mouseX, mouseY))
                menu.click(mouseX, mouseY);
            return true;
        }
        if (mouseY < TOP_BAR) {
            layout.clearFocus();
            if (rotate.visible && rotate.mouseClicked(mouseX, mouseY, button))
                return true;
            done.mouseClicked(mouseX, mouseY, button);
            return true;
        }
        if (layout.mouseClicked(mouseX, mouseY, button))
            return true;
        if (strip.mouseClicked(mouseX, mouseY, button))
            return true;
        if (zoom.mouseClicked(mouseX, mouseY, button))
            return true;
        return glass.mouseClicked(mouseX, mouseY, button);
    }

    public boolean mouseDragged(double mouseX, double mouseY, int button, double dragX, double dragY) {
        // Each goes to whatever the press began on, wherever the pointer is now
        if (layout.mouseDragged(mouseX, mouseY, button, dragX, dragY))
            return true;
        return glass.mouseDragged(mouseX, mouseY, button, dragX, dragY);
    }

    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        boolean taken = layout.mouseReleased(mouseX, mouseY, button);
        taken |= glass.mouseReleased(mouseX, mouseY, button);
        return taken;
    }

    public boolean mouseScrolled(double mouseX, double mouseY, double delta) {
        if (contextMenu != null)
            return true;
        if (layout.mouseScrolled(mouseX, mouseY, delta))
            return true;
        return zoom.mouseScrolled(mouseX, mouseY, delta);
    }

    public boolean keyPressed(int key, int scanCode, int modifiers) {
        if (key == GLFW.GLFW_KEY_ESCAPE) {
            if (contextMenu != null) {
                contextMenu = null;
            } else if (layout.hasTextFocus() && layout.keyPressed(key, scanCode, modifiers)) {
                // A menu wanted Esc itself (the hierarchy cancels a rename with it)
            } else {
                // Otherwise Esc closes the editor even from a field; what was typed is
                // already an edit, and closing sends it
                layout.clearFocus();
                host.close();
            }
            return true;
        }
        if (layout.hasTextFocus()) {
            // Whatever the field does not want is still not a shortcut while typing
            layout.keyPressed(key, scanCode, modifiers);
            return true;
        }
        if (glass.busy())
            return false;
        EditorCommands commands = context.commands();
        // Ctrl or Cmd with plus or minus zooms, either held on a Mac; plus is the = key
        // unshifted, as browsers take it
        if ((modifiers & (GLFW.GLFW_MOD_CONTROL | GLFW.GLFW_MOD_SUPER)) != 0 && !Screen.hasAltDown()) {
            if (key == GLFW.GLFW_KEY_EQUAL || key == GLFW.GLFW_KEY_KP_ADD) {
                zoom.step(1);
                return true;
            }
            if (key == GLFW.GLFW_KEY_MINUS || key == GLFW.GLFW_KEY_KP_SUBTRACT) {
                zoom.step(-1);
                return true;
            }
        }
        if (Screen.hasControlDown() && !Screen.hasAltDown()) {
            switch (key) {
            case GLFW.GLFW_KEY_Z -> {
                if (Screen.hasShiftDown())
                    redo();
                else
                    undo();
            }
            case GLFW.GLFW_KEY_Y -> redo();
            case GLFW.GLFW_KEY_X -> commands.cut();
            case GLFW.GLFW_KEY_C -> commands.copy();
            case GLFW.GLFW_KEY_V -> commands.paste();
            case GLFW.GLFW_KEY_D -> commands.duplicate();
            default -> {
                return false;
            }
            }
            return true;
        }
        switch (key) {
        case GLFW.GLFW_KEY_DELETE, GLFW.GLFW_KEY_BACKSPACE -> {
            commands.delete();
            return true;
        }
        case GLFW.GLFW_KEY_LEFT, GLFW.GLFW_KEY_RIGHT, GLFW.GLFW_KEY_UP, GLFW.GLFW_KEY_DOWN -> {
            // A pixel; Alt a tenth of one, Shift ten
            int step = Screen.hasAltDown() ? 1
                    : Viewport.UNITS_PER_PIXEL * (Screen.hasShiftDown() ? 10 : 1);
            int dx = key == GLFW.GLFW_KEY_LEFT ? -step : key == GLFW.GLFW_KEY_RIGHT ? step : 0;
            int dy = key == GLFW.GLFW_KEY_UP ? -step : key == GLFW.GLFW_KEY_DOWN ? step : 0;
            commands.nudge(dx, dy);
            return true;
        }
        default -> {
            return strip.keyPressed(key);
        }
        }
    }

    public boolean charTyped(char character, int modifiers) {
        return layout.hasTextFocus() && layout.charTyped(character, modifiers);
    }

    private void undo() {
        context.session.undo();
        dropMissing();
    }

    private void redo() {
        context.session.redo();
        dropMissing();
    }

    /** Keeps the selection to elements that exist, after an undo or anyone's delete. */
    private void dropMissing() {
        Selection selection = context.selection;
        if (selection.isEmpty())
            return;
        List<Integer> kept = new ArrayList<>();
        for (int id : selection.ids())
            if (context.session.shown(id) != null)
                kept.add(id);
        if (kept.size() != selection.size())
            selection.set(kept);
    }

    // ---- services ----

    @Override
    public void openContextMenu(double x, double y, List<ContextMenu.Entry> entries) {
        layout.clearFocus();
        contextMenu = new ContextMenu(font, entries, x, y, width, height);
    }

    @Override
    public void status(Component message) {
        status = message;
        statusUntil = Util.getMillis() + STATUS_MILLIS;
    }

    /**
     * The system file picker, then the import on a background thread
     * ({@link ClientDecals#importForDisplay}), which stores the picture here and uploads
     * it to the world's design store before {@code done} hears its hash, so the edit
     * that names it finds it there.
     */
    @Override
    public void importSvg(Consumer<String> done) {
        DesignImporter.browseSvg(paths -> {
            if (paths.isEmpty() || closed)
                return;
            status(Component.translatable(LANG + "svg.importing", paths.get(0).getFileName().toString()));
            ClientDecals.importForDisplay(paths.get(0), imported -> {
                if (closed)
                    return;
                Component message = Component.translatable(LANG + "svg.imported", imported.name(),
                        imported.triangles());
                if (!imported.warnings().isEmpty())
                    message = message.copy().append(": ").append(String.join("; ", imported.warnings()));
                status(message);
                done.accept(imported.hash());
            }, error -> {
                if (!closed)
                    status(Component.translatable(LANG + "svg.failed", error).withStyle(ChatFormatting.RED));
            });
        });
    }

    // ---- internals ----

    /** Puts the tool strip and the zoom buttons where the sidebars leave them; every frame. */
    private void placeFloating() {
        strip.layout(width, height);
        zoom.layout(layout.middleRight(), height);
    }

    /** The zoom that fits the display between the sidebars, below the top bar and above the tool strip. */
    private double fitZoom() {
        return context.view.fitZoom(layout.middleLeft(), layout.middleRight(), TOP_BAR, height - STRIP_ROOM, width,
                height);
    }

    /**
     * What the renderer draws for the edited display: the session's version of it
     * (edits not yet sent included), less the hidden elements, with empty lettering as
     * a dimmed placeholder (so an empty box is never invisible) and bars at their
     * preview values.
     */
    private List<ScreenElement> previewed() {
        List<ScreenElement> shown = context.session.shown();
        List<ScreenElement> out = new ArrayList<>(shown.size());
        for (ScreenElement element : shown) {
            int id = element.id();
            if (context.hidden.contains(id))
                continue;
            if (element.kind() == ElementKind.TEXT && element.text().isEmpty()) {
                String placeholder = element.name().isEmpty()
                        ? Component.translatable(LANG + "placeholder_text", id).getString() : element.name();
                element = element.toBuilder().text(placeholder).colour(PLACEHOLDER).build();
            } else if (element.kind() == ElementKind.BAR && context.barPreview.containsKey(id)) {
                float value = context.barPreview.get(id);
                element = element.toBuilder().text(String.format(Locale.ROOT, "%.4f", value)).build();
            }
            out.add(element);
        }
        return out;
    }
}
