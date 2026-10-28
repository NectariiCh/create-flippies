package com.nectariich.create_flippies.client.display.editor;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;

import org.lwjgl.glfw.GLFW;

import com.nectariich.create_flippies.compat.EditBoxes;
import com.nectariich.create_flippies.content.display.element.ElementKind;
import com.nectariich.create_flippies.content.display.element.ScreenElement;

import net.minecraft.Util;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/**
 * The display editor's list of elements, front at the top as a layers panel has it,
 * so the row at the top is the element drawn over all the others.
 *
 * <p>Each row names its element (its name, or its slot, kind letter and a snippet of
 * its text) and carries two toggles: the eye hides the element from the editor's view
 * only ({@link EditorContext#hidden}, never saved), the padlock locks it for everyone
 * (a saved field, one undo step). Selection is the editor's one {@link Selection}, so
 * picking here picks on the glass and the other way round; locked elements, which the
 * glass will not pick, can be picked here, which is how they are unlocked or edited.
 *
 * <p>Rows are dragged to restack them (all the selected rows when a selected row is
 * dragged, keeping their order), a double-click renames one in place, and a right-click
 * opens the same menu the glass offers over an element. A small toolbar above the list
 * raises and lowers the selection a place at a time.
 */
public final class HierarchyMenu implements EditorMenu {

    private static final int ROW = 12;
    private static final int TOOLBAR = 14;
    /** Width of each toggle column at the left of a row. */
    private static final int TOGGLE = 10;
    /** How far the pointer must move with a row held before it is a drag rather than a click. */
    private static final int DRAG_SLOP = 3;
    private static final long DOUBLE_CLICK_MILLIS = 300;
    /** Characters of a text or bar's text shown after its id when it has no name. */
    private static final int SNIPPET = 24;

    private static final int BACKGROUND = 0xFF181C20;
    private static final int ROW_OVER = 0xFF262C33;
    private static final int ROW_SELECTED = 0xFF3A6EA5;
    private static final int DROP_LINE = 0xFFFFD040;
    private static final int TEXT = 0xFFE0E0E0;
    private static final int TEXT_DIM = 0xFF707070;
    private static final int BUTTON = 0xFF2C333B;
    private static final int BUTTON_OVER = 0xFF3A444F;

    private final EditorContext context;

    private int x, y, width, height;
    /** Pixels the list is scrolled down by. */
    private int scroll;

    /** The ids as the rows show them this frame, front first. */
    private List<Integer> rows = List.of();

    /** Selection state last seen, so a change made elsewhere scrolls the primary into view. */
    private int seenVersion = -1;
    private int seenPrimary = -1;

    /**
     * The element whose row a left press landed on, or -1; it is a drag once the pointer
     * has moved {@link #DRAG_SLOP}. Kept by id, not by row, because the rows are rebuilt
     * every frame and an element arriving from the server shifts them while the button is held.
     */
    private int pressed = -1;
    private double pressY;
    private boolean dragging;
    /** A press on an already selected row without modifiers selects only that row on release, unless it dragged. */
    private boolean selectOnRelease;
    /** Gap the drag would drop into: 0 above the first row, rows.size() below the last. */
    private int dropGap = -1;

    private int lastClickId = -1;
    private long lastClickTime;

    /** The inline rename field and the element it names, or null and -1 when not renaming. */
    private EditBox rename;
    private int renaming = -1;

    public HierarchyMenu(EditorContext context) {
        this.context = context;
    }

    @Override
    public String id() {
        return "hierarchy";
    }

    @Override
    public Component title() {
        return Component.translatable("gui.create_flippies.display_editor.hierarchy");
    }

    @Override
    public void layout(int x, int y, int width, int height) {
        this.x = x;
        this.y = y;
        this.width = width;
        this.height = height;
    }

    // ---- Drawing

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTicks) {
        refreshRows();
        followSelection();
        clampScroll();

        graphics.fill(x, y, x + width, y + height, BACKGROUND);
        renderToolbar(graphics, mouseX, mouseY);

        int top = listTop();
        int bottom = y + height;
        boolean overList = mouseX >= x && mouseX < x + width && mouseY >= top && mouseY < bottom;
        for (int i = 0; i < rows.size(); i++) {
            int rowY = top + i * ROW - scroll;
            if (rowY + ROW <= top || rowY >= bottom)
                continue;
            int id = rows.get(i);
            ScreenElement element = context.session.shown(id);
            if (element == null)
                continue;
            boolean selected = context.selection.contains(id);
            boolean hidden = context.hidden.contains(id);
            if (selected)
                graphics.fill(x, rowY, x + width, rowY + ROW, ROW_SELECTED);
            else if (overList && !dragging && mouseY >= rowY && mouseY < rowY + ROW)
                graphics.fill(x, rowY, x + width, rowY + ROW, ROW_OVER);

            drawEye(graphics, x + 1, rowY, hidden);
            drawLock(graphics, x + TOGGLE + 2, rowY + 2, element.locked());

            if (renaming == id)
                continue;
            int labelX = x + 2 * TOGGLE + 4;
            String label = context.font.plainSubstrByWidth(label(element), Math.max(0, x + width - 2 - labelX));
            graphics.drawString(context.font, label, labelX, rowY + 2, hidden ? TEXT_DIM : TEXT, false);
        }

        if (dragging && dropGap >= 0) {
            int lineY = Math.max(top, Math.min(bottom - 1, top + dropGap * ROW - scroll));
            graphics.fill(x + 1, lineY - 1, x + width - 1, lineY + 1, DROP_LINE);
        }

        if (rename != null) {
            int index = rows.indexOf(renaming);
            if (index < 0) {
                // The element went while being renamed: nothing to name any more
                cancelRename();
            } else {
                rename.setX(x + 2 * TOGGLE + 3);
                rename.setY(top + index * ROW - scroll);
                rename.render(graphics, mouseX, mouseY, partialTicks);
            }
        }
    }

    private void renderToolbar(GuiGraphics graphics, int mouseX, int mouseY) {
        EditorCommands commands = context.commands();
        renderButton(graphics, raiseX(), buttonWidth(), Component.translatable(
                "gui.create_flippies.display_editor.raise"), commands.canRaise(), mouseX, mouseY);
        renderButton(graphics, lowerX(), buttonWidth(), Component.translatable(
                "gui.create_flippies.display_editor.lower"), commands.canLower(), mouseX, mouseY);
        graphics.fill(x, y + TOOLBAR - 1, x + width, y + TOOLBAR, 0xFF000000);
    }

    private void renderButton(GuiGraphics graphics, int bx, int bw, Component label, boolean enabled, int mouseX,
            int mouseY) {
        boolean over = enabled && mouseX >= bx && mouseX < bx + bw && mouseY >= y + 1 && mouseY < y + TOOLBAR - 2;
        graphics.fill(bx, y + 1, bx + bw, y + TOOLBAR - 2, over ? BUTTON_OVER : BUTTON);
        int textX = bx + (bw - context.font.width(label)) / 2;
        graphics.drawString(context.font, label, textX, y + 3, enabled ? TEXT : TEXT_DIM, false);
    }

    /** An eye: a filled dot when shown, a hollow one when hidden from the editor. */
    private void drawEye(GuiGraphics graphics, int ex, int rowY, boolean hidden) {
        graphics.drawString(context.font, hidden ? "○" : "●", ex + 2, rowY + 2,
                hidden ? TEXT_DIM : TEXT, false);
    }

    /** A small padlock drawn in fills, bright and shut when locked, faint and open when not. */
    private static void drawLock(GuiGraphics graphics, int lx, int ly, boolean locked) {
        int colour = locked ? 0xFFFFD040 : 0xFF4A525A;
        // Body
        graphics.fill(lx, ly + 4, lx + 7, ly + 9, colour);
        // Shackle: both legs when shut, only the left one when open, and the top bar lifted
        int shackleTop = locked ? ly : ly - 1;
        graphics.fill(lx + 1, shackleTop, lx + 6, shackleTop + 1, colour);
        graphics.fill(lx + 1, shackleTop, lx + 2, ly + 4, colour);
        if (locked)
            graphics.fill(lx + 5, shackleTop, lx + 6, ly + 4, colour);
    }

    /** What a row says: the name, or "#id K" and a snippet of a text or bar's text. */
    static String label(ScreenElement element) {
        if (!element.name().isEmpty())
            return element.name();
        String label = "#" + element.id() + " " + element.kind().letter();
        if ((element.kind() == ElementKind.TEXT || element.kind() == ElementKind.BAR) && !element.text().isEmpty()) {
            String text = element.text().replace('\n', ' ');
            if (text.length() > SNIPPET)
                text = text.substring(0, SNIPPET) + "...";
            label += " " + text;
        }
        return label;
    }

    // ---- Geometry

    private int listTop() {
        return y + TOOLBAR;
    }

    private int buttonWidth() {
        return Math.max(10, (width - 6) / 2);
    }

    private int raiseX() {
        return x + 2;
    }

    private int lowerX() {
        return x + 4 + buttonWidth();
    }

    /** The row index under a screen y, or -1. */
    private int rowAt(double my) {
        if (my < listTop() || my >= y + height)
            return -1;
        int index = (int) Math.floor((my - listTop() + scroll) / ROW);
        return index >= 0 && index < rows.size() ? index : -1;
    }

    /** The gap nearest a screen y: 0 above the first row up to rows.size() below the last. */
    private int gapAt(double my) {
        int gap = (int) Math.round((my - listTop() + scroll) / ROW);
        return Math.max(0, Math.min(rows.size(), gap));
    }

    private void refreshRows() {
        List<ScreenElement> shown = context.session.shown();
        List<Integer> ids = new ArrayList<>(shown.size());
        for (int i = shown.size() - 1; i >= 0; i--)
            ids.add(shown.get(i).id());
        rows = ids;
    }

    private void clampScroll() {
        int listHeight = height - TOOLBAR;
        scroll = Math.max(0, Math.min(scroll, rows.size() * ROW - listHeight));
    }

    /** Scrolls the primary selection into view when the selection changed and it is someone new. */
    private void followSelection() {
        int version = context.selection.version();
        if (version == seenVersion)
            return;
        seenVersion = version;
        int primary = context.selection.primary();
        if (primary == seenPrimary)
            return;
        seenPrimary = primary;
        int index = rows.indexOf(primary);
        if (index < 0)
            return;
        int listHeight = height - TOOLBAR;
        int rowTop = index * ROW;
        if (rowTop < scroll)
            scroll = rowTop;
        else if (rowTop + ROW > scroll + listHeight)
            scroll = rowTop + ROW - listHeight;
    }

    // ---- Input

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        refreshRows();
        if (rename != null) {
            if (rename.isMouseOver(mouseX, mouseY))
                return rename.mouseClicked(mouseX, mouseY, button);
            commitRename();
        }

        if (mouseY < listTop()) {
            if (button == GLFW.GLFW_MOUSE_BUTTON_LEFT && mouseY >= y + 1 && mouseY < y + TOOLBAR - 2) {
                EditorCommands commands = context.commands();
                if (mouseX >= raiseX() && mouseX < raiseX() + buttonWidth() && commands.canRaise())
                    commands.raise();
                else if (mouseX >= lowerX() && mouseX < lowerX() + buttonWidth() && commands.canLower())
                    commands.lower();
            }
            return true;
        }

        int index = rowAt(mouseY);
        if (index < 0) {
            // Empty space under the rows: a plain click lets go of the selection, as on the glass
            if (button == GLFW.GLFW_MOUSE_BUTTON_LEFT && !Screen.hasShiftDown() && !Screen.hasControlDown())
                context.selection.clear();
            return true;
        }
        int id = rows.get(index);
        ScreenElement element = context.session.shown(id);
        if (element == null)
            return true;

        if (button == GLFW.GLFW_MOUSE_BUTTON_RIGHT) {
            if (!context.selection.contains(id))
                context.selection.only(id);
            openContextMenu(mouseX, mouseY);
            return true;
        }
        if (button != GLFW.GLFW_MOUSE_BUTTON_LEFT)
            return true;

        if (mouseX < x + TOGGLE + 1) {
            if (!context.hidden.remove(id))
                context.hidden.add(id);
            return true;
        }
        if (mouseX < x + 2 * TOGGLE + 3) {
            context.session.edit(Map.of(id, element.toBuilder().locked(!element.locked()).build()), null);
            return true;
        }

        long now = Util.getMillis();
        boolean doubleClick = id == lastClickId && now - lastClickTime < DOUBLE_CLICK_MILLIS;
        lastClickId = doubleClick ? -1 : id;
        lastClickTime = now;
        if (doubleClick && !Screen.hasShiftDown() && !Screen.hasControlDown()) {
            context.selection.only(id);
            startRename(element, index);
            return true;
        }

        selectOnRelease = false;
        if (Screen.hasShiftDown()) {
            selectRange(index);
        } else if (Screen.hasControlDown()) {
            context.selection.toggle(id);
        } else if (context.selection.contains(id) && context.selection.size() > 1) {
            // Kept for now so the whole selection can be dragged; narrowed on release if it was a click
            selectOnRelease = true;
        } else {
            context.selection.only(id);
        }
        pressed = id;
        pressY = mouseY;
        dragging = false;
        dropGap = -1;
        return true;
    }

    /** Selects the rows from the primary one to this one, inclusive, the clicked row becoming primary. */
    private void selectRange(int index) {
        int from = rows.indexOf(context.selection.primary());
        if (from < 0) {
            context.selection.only(rows.get(index));
            return;
        }
        List<Integer> range = new ArrayList<>();
        int step = index >= from ? 1 : -1;
        for (int i = from; i != index + step; i += step)
            range.add(rows.get(i));
        context.selection.set(range);
    }

    @Override
    public boolean mouseDragged(double mouseX, double mouseY, int button, double dragX, double dragY) {
        if (pressed < 0 || button != GLFW.GLFW_MOUSE_BUTTON_LEFT)
            return false;
        if (!dragging && Math.abs(mouseY - pressY) < DRAG_SLOP)
            return true;
        dragging = true;
        // Held near either edge of the list, it scrolls so a row can be taken further than the view
        int listHeight = height - TOOLBAR;
        if (mouseY < listTop() + ROW / 2)
            scroll -= 2;
        else if (mouseY > listTop() + listHeight - ROW / 2)
            scroll += 2;
        clampScroll();
        dropGap = gapAt(mouseY);
        return true;
    }

    @Override
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        if (pressed < 0 || button != GLFW.GLFW_MOUSE_BUTTON_LEFT)
            return false;
        int id = pressed;
        pressed = -1;
        if (dragging) {
            dragging = false;
            int gap = dropGap;
            dropGap = -1;
            if (gap >= 0 && rows.contains(id)) {
                List<Integer> moved = context.selection.contains(id) ? selectedInRowOrder() : List.of(id);
                List<Integer> order = restackOrder(rows, moved, gap);
                if (!order.isEmpty())
                    context.session.restack(order);
            }
        } else if (selectOnRelease && rows.contains(id)) {
            context.selection.only(id);
        }
        selectOnRelease = false;
        return true;
    }

    private List<Integer> selectedInRowOrder() {
        List<Integer> selected = new ArrayList<>();
        for (int id : rows)
            if (context.selection.contains(id))
                selected.add(id);
        return selected;
    }

    /**
     * The drawing order (back to front, as {@link EditSession#restack} takes it) after
     * moving {@code moved} into the gap {@code gap} of {@code rows}, which are front
     * first: gap 0 is above the top row, rows.size() below the bottom one. The moved
     * rows keep their order among themselves. Empty when nothing would move.
     */
    static List<Integer> restackOrder(List<Integer> rows, Collection<Integer> moved, int gap) {
        List<Integer> movedInOrder = new ArrayList<>();
        for (int id : rows)
            if (moved.contains(id))
                movedInOrder.add(id);
        if (movedInOrder.isEmpty())
            return List.of();
        List<Integer> rest = new ArrayList<>();
        int insert = 0;
        for (int i = 0; i < rows.size(); i++) {
            int id = rows.get(i);
            if (movedInOrder.contains(id))
                continue;
            if (i < gap)
                insert++;
            rest.add(id);
        }
        rest.addAll(insert, movedInOrder);
        if (rest.equals(rows))
            return List.of();
        List<Integer> order = new ArrayList<>(rest.size());
        for (int i = rest.size() - 1; i >= 0; i--)
            order.add(rest.get(i));
        return order;
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double delta) {
        if (rows.size() * ROW <= height - TOOLBAR)
            return false;
        scroll -= (int) Math.round(delta * ROW * 2);
        clampScroll();
        return true;
    }

    private void openContextMenu(double mouseX, double mouseY) {
        EditorCommands commands = context.commands();
        List<ContextMenu.Entry> entries = new ArrayList<>();
        entries.add(entry("cut", true, commands::cut));
        entries.add(entry("copy", true, commands::copy));
        entries.add(entry("paste", commands.canPaste(), commands::paste));
        entries.add(entry("duplicate", true, commands::duplicate));
        entries.add(entry("delete", true, commands::delete));
        entries.add(null);
        entries.add(entry("bring_to_front", commands.canRaise(), commands::bringToFront));
        entries.add(entry("raise", commands.canRaise(), commands::raise));
        entries.add(entry("lower", commands.canLower(), commands::lower));
        entries.add(entry("send_to_back", commands.canLower(), commands::sendToBack));
        context.openContextMenu(mouseX, mouseY, entries);
    }

    private static ContextMenu.Entry entry(String key, boolean enabled, Runnable action) {
        // the glass's own entries, so both right-click menus read the same
        return new ContextMenu.Entry(Component.translatable("gui.create_flippies.display_editor.menu." + key),
                enabled, action);
    }

    // ---- Renaming

    private void startRename(ScreenElement element, int index) {
        int fieldX = x + 2 * TOGGLE + 3;
        rename = new EditBox(context.font, fieldX, listTop() + index * ROW - scroll, Math.max(20, x + width - 1
                - fieldX), ROW, Component.translatable("gui.create_flippies.display_editor.name"));
        rename.setMaxLength(ScreenElement.MAX_NAME);
        rename.setValue(element.name());
        rename.setFocused(true);
        EditBoxes.cursorToEnd(rename);
        rename.setHighlightPos(0);
        renaming = element.id();
    }

    /** Names the element what the field says, an empty field clearing the name; one undo step. */
    private void commitRename() {
        if (rename == null)
            return;
        int id = renaming;
        String name = ScreenElement.sanitiseName(rename.getValue());
        cancelRename();
        ScreenElement element = context.session.shown(id);
        if (element != null && !element.name().equals(name))
            context.session.edit(Map.of(id, element.toBuilder().name(name).build()), null);
    }

    private void cancelRename() {
        rename = null;
        renaming = -1;
    }

    @Override
    public boolean keyPressed(int key, int scanCode, int modifiers) {
        if (rename == null)
            return false;
        if (key == GLFW.GLFW_KEY_ENTER || key == GLFW.GLFW_KEY_KP_ENTER) {
            commitRename();
            return true;
        }
        if (key == GLFW.GLFW_KEY_ESCAPE) {
            cancelRename();
            return true;
        }
        rename.keyPressed(key, scanCode, modifiers);
        // The field has the keyboard: nothing typed into it may reach the editor's shortcuts
        return true;
    }

    @Override
    public boolean charTyped(char character, int modifiers) {
        if (rename == null)
            return false;
        rename.charTyped(character, modifiers);
        return true;
    }

    @Override
    public boolean hasTextFocus() {
        return rename != null;
    }

    @Override
    public void clearFocus() {
        commitRename();
    }

    @Override
    public void tick() {
        if (rename != null)
            EditBoxes.tick(rename);
    }
}
