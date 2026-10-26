package com.nectariich.create_flippies.client.display.editor;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.nectariich.create_flippies.client.display.editor.EditorLayoutConfig.Side;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;

/**
 * The display editor's two sidebars and the menus stacked in them. Each sidebar is an
 * opaque column at a screen edge: a thin header with its collapse button, then its menus
 * top to bottom, each a title bar (with a + or - button to collapse it) over its content.
 * The editor's middle area, the glass, is what lies between them ({@link #middleLeft},
 * {@link #middleRight}).
 *
 * Everything here is chrome: a sidebar collapses to a strip at the screen edge, its inner
 * edge is dragged to change its width, the lines between stacked menus are dragged to
 * share the height, and a menu is dragged by its title bar to another place in either
 * sidebar, where the half of the menu under the cursor (or the gap between two) decides
 * where it goes. The menus draw and handle only their content, clipped by a scissor; input
 * whose point is in a content area goes to that menu, and the drags and releases of a
 * press go to the menu the press began in, wherever the cursor has gone.
 *
 * A right-click on a title bar offers the same moves as a menu: to the other sidebar, and
 * collapse or expand; the editor shows it ({@link #onContextMenu}).
 *
 * The arrangement is an {@link EditorLayoutConfig}, read when the layout is made and
 * written back after every change the player makes, so the screwdriver and workbench
 * editors share it.
 */
public final class EditorLayout {

    /** A collapsed sidebar's strip, the sidebar header, a menu title bar, the split between menus. */
    private static final int STRIP = 10, HEADER = 11, TITLE = 12, GAP = 3;
    /** How far either side of a sidebar's inner edge grabs it. */
    private static final int EDGE_GRAB = 3;
    /** How far the cursor must move before a press on a title bar becomes a menu drag. */
    private static final int DRAG_START = 3;
    /** The least an expanded menu is shrunk to by dragging a split. */
    private static final int MIN_MENU = TITLE + 16;
    /** Mouse coordinates that are over nothing, for menus drawn while the cursor is busy elsewhere. */
    private static final int NOWHERE = -10000;

    private static final int PANEL = 0xFF1A1E23, HEADER_FILL = 0xFF20252B, TITLE_FILL = 0xFF2A3038,
            TITLE_HOVER = 0xFF323944, ACCENT = 0xFF3A6EA5, DROP_FILL = 0x553A6EA5, EDGE = 0xFF0C0E11,
            SPLIT = 0xFF111418, TEXT = 0xFFFFFF, DIM = 0x8A94A0, HINT = 0x5C6670;

    private static final String LANG = "gui.create_flippies.display_editor.layout.";
    private static final Component EMPTY_HINT = Component.translatable(LANG + "empty");

    /** One menu where it is this frame. */
    private static final class Placed {
        final EditorMenu menu;
        final Side side;
        final int index, x0, x1, top, height;
        final boolean collapsed;

        Placed(EditorMenu menu, Side side, int index, int x0, int x1, int top, int height, boolean collapsed) {
            this.menu = menu;
            this.side = side;
            this.index = index;
            this.x0 = x0;
            this.x1 = x1;
            this.top = top;
            this.height = height;
            this.collapsed = collapsed;
        }

        int bottom() {
            return top + height;
        }

        int contentTop() {
            return top + TITLE;
        }

        int contentHeight() {
            return collapsed ? 0 : Math.max(0, height - TITLE);
        }

        boolean in(double x, double y) {
            return x >= x0 && x < x1 && y >= top && y < bottom();
        }

        boolean inTitle(double x, double y) {
            return x >= x0 && x < x1 && y >= top && y < top + TITLE;
        }

        boolean inToggle(double x, double y) {
            return inTitle(x, y) && x >= x1 - TITLE;
        }

        boolean inContent(double x, double y) {
            return contentHeight() > 0 && x >= x0 && x < x1 && y >= contentTop() && y < bottom();
        }
    }

    private enum Press {
        NONE, MENU_PENDING, MENU, WIDTH, SPLIT, CONTENT, CHROME
    }

    private final Font font;
    private final Map<String, EditorMenu> menus = new LinkedHashMap<>();
    private final EditorLayoutConfig config;
    private final Path file;
    private boolean dirty;

    private int screenWidth, top, bottom;
    /** Each side's column this frame: the sidebar when expanded, the strip when collapsed. */
    private final Map<Side, int[]> column = new EnumMap<>(Side.class);
    private final Map<Side, List<Placed>> stacks = new EnumMap<>(Side.class);

    private Press press = Press.NONE;
    private int pressButton;
    private double pressX, pressY;
    /** The menu a content press or a title drag is about. */
    private Placed pressed;
    private Side widthSide;
    /** The split being dragged: the expanded menus above and below it, as they were at the press. */
    private Placed splitAbove, splitBelow;
    /** The index of the menu whose bottom edge is the split being dragged. */
    private int splitIndex;
    private double mouseX, mouseY;
    /** Opens a right-click menu at a point; the editor's, so it draws over everything. */
    private ContextMenuOpener contextMenus = (x, y, entries) -> {};

    /** Shows a right-click menu at (x, y) in GUI coordinates. */
    @FunctionalInterface
    public interface ContextMenuOpener {
        void open(double x, double y, List<ContextMenu.Entry> entries);
    }

    public EditorLayout(Font font, List<EditorMenu> menus) {
        this(font, menus, EditorLayoutConfig.defaultPath());
    }

    /** With the layout kept in a given file, for tests and tools. */
    public EditorLayout(Font font, List<EditorMenu> menus, Path file) {
        this.font = font;
        this.file = file;
        for (EditorMenu menu : menus)
            this.menus.put(menu.id(), menu);
        this.config = EditorLayoutConfig.load(file);
        config.normalise(new ArrayList<>(this.menus.keySet()));
        for (Side side : Side.values()) {
            column.put(side, new int[] { 0, 0 });
            stacks.put(side, new ArrayList<>());
        }
    }

    /** Where a title bar's right-click menu opens; nothing opens one until this is set. */
    public void onContextMenu(ContextMenuOpener opener) {
        this.contextMenus = opener;
    }

    // ---- geometry ----

    /**
     * Places the sidebars on a screen of this size, between {@code top} (below the editor's
     * top bar) and {@code bottom}. Call on every resize; the layout also re-places itself
     * whenever the player changes it.
     */
    public void layout(int screenWidth, int screenHeight, int top, int bottom) {
        this.screenWidth = screenWidth;
        this.top = top;
        this.bottom = bottom;
        relayout();
    }

    private void relayout() {
        for (Side side : Side.values()) {
            int w = config.collapsed(side) ? STRIP : EditorLayoutConfig.clampWidth(config.width(side), screenWidth);
            column.put(side, side == Side.LEFT ? new int[] { 0, w } : new int[] { screenWidth - w, screenWidth });
            List<Placed> stack = stacks.get(side);
            stack.clear();
            if (config.collapsed(side))
                continue;
            List<String> ids = config.menus(side);
            int n = ids.size();
            if (n == 0)
                continue;
            float[] weights = new float[n];
            boolean[] folded = new boolean[n];
            for (int i = 0; i < n; i++) {
                weights[i] = config.weight(ids.get(i));
                folded[i] = config.menuCollapsed(ids.get(i));
            }
            int available = bottom - top - HEADER - GAP * (n - 1);
            int[] heights = EditorLayoutConfig.stackHeights(available, weights, folded, TITLE);
            int[] col = column.get(side);
            int x0 = side == Side.LEFT ? col[0] : col[0] + 1, x1 = side == Side.LEFT ? col[1] - 1 : col[1];
            int y = top + HEADER;
            for (int i = 0; i < n; i++) {
                stack.add(new Placed(menus.get(ids.get(i)), side, i, x0, x1, y, heights[i], folded[i]));
                y += heights[i] + GAP;
            }
        }
    }

    /** The x where the middle area begins: the left sidebar's (or strip's) inner edge. */
    public int middleLeft() {
        return column.get(Side.LEFT)[1];
    }

    /** The x where the middle area ends: the right sidebar's (or strip's) inner edge. */
    public int middleRight() {
        return column.get(Side.RIGHT)[0];
    }

    /** Whether the point is over a sidebar or a strip, so the glass must not get it. */
    public boolean contains(double x, double y) {
        return sideAt(x, y) != null;
    }

    private Side sideAt(double x, double y) {
        if (y < top || y >= bottom)
            return null;
        for (Side side : Side.values()) {
            int[] col = column.get(side);
            if (x >= col[0] && x < col[1])
                return side;
        }
        return null;
    }

    private Placed placedAt(double x, double y) {
        for (Side side : Side.values())
            for (Placed placed : stacks.get(side))
                if (placed.in(x, y))
                    return placed;
        return null;
    }

    /** Which side's inner edge the point grabs, if any. */
    private Side edgeAt(double x, double y) {
        if (y < top || y >= bottom)
            return null;
        if (!config.collapsed(Side.LEFT) && Math.abs(x - column.get(Side.LEFT)[1]) <= EDGE_GRAB)
            return Side.LEFT;
        if (!config.collapsed(Side.RIGHT) && Math.abs(x - column.get(Side.RIGHT)[0]) <= EDGE_GRAB)
            return Side.RIGHT;
        return null;
    }

    /** The split under the point: the index of the menu whose bottom it is, or -1. */
    private int splitAt(Side side, double x, double y) {
        List<Placed> stack = stacks.get(side);
        for (int i = 0; i + 1 < stack.size(); i++) {
            Placed placed = stack.get(i);
            if (x >= placed.x0 && x < placed.x1 && y >= placed.bottom() - 1 && y < placed.bottom() + GAP + 1)
                return splitPair(stack, i) == null ? -1 : i;
        }
        return -1;
    }

    /** The nearest expanded menus above and below a split, or null when either side has none. */
    private static Placed[] splitPair(List<Placed> stack, int split) {
        Placed above = null, below = null;
        for (int i = split; i >= 0 && above == null; i--)
            if (!stack.get(i).collapsed)
                above = stack.get(i);
        for (int i = split + 1; i < stack.size() && below == null; i++)
            if (!stack.get(i).collapsed)
                below = stack.get(i);
        return above == null || below == null ? null : new Placed[] { above, below };
    }

    private int[] headerToggle(Side side) {
        int[] col = column.get(side);
        if (config.collapsed(side))
            return new int[] { col[0], top, col[1], bottom };
        return side == Side.LEFT ? new int[] { col[1] - 1 - HEADER, top, col[1] - 1, top + HEADER }
                : new int[] { col[0] + 1, top, col[0] + 1 + HEADER, top + HEADER };
    }

    private static boolean in(int[] rect, double x, double y) {
        return x >= rect[0] && x < rect[2] && y >= rect[1] && y < rect[3];
    }

    // ---- drawing ----

    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTicks) {
        this.mouseX = mouseX;
        this.mouseY = mouseY;
        relayout();
        boolean busy = press != Press.NONE && press != Press.CONTENT;
        Side edge = press == Press.WIDTH ? widthSide : press == Press.NONE ? edgeAt(mouseX, mouseY) : null;
        for (Side side : Side.values())
            renderSide(graphics, side, mouseX, mouseY, partialTicks, busy, edge == side);
        if (press == Press.MENU)
            renderDrag(graphics, mouseX, mouseY);
    }

    private void renderSide(GuiGraphics graphics, Side side, int mouseX, int mouseY, float partialTicks,
            boolean busy, boolean edgeHot) {
        int[] col = column.get(side);
        graphics.fill(col[0], top, col[1], bottom, PANEL);
        int inner = side == Side.LEFT ? col[1] - 1 : col[0];
        graphics.fill(inner, top, inner + 1, bottom, edgeHot ? ACCENT : EDGE);
        int[] toggle = headerToggle(side);
        boolean toggleHot = !busy && in(toggle, mouseX, mouseY);
        if (config.collapsed(side)) {
            if (toggleHot)
                graphics.fill(col[0], top, col[1], bottom, TITLE_HOVER);
            graphics.fill(inner, top, inner + 1, bottom, EDGE);
            String arrow = side == Side.LEFT ? ">" : "<";
            graphics.drawString(font, arrow, (col[0] + col[1] - font.width(arrow)) / 2, top + 3, TEXT, false);
            return;
        }
        graphics.fill(col[0], top, col[1], top + HEADER, HEADER_FILL);
        if (toggleHot)
            graphics.fill(toggle[0], toggle[1], toggle[2], toggle[3], TITLE_HOVER);
        String arrow = side == Side.LEFT ? "<" : ">";
        graphics.drawString(font, arrow, (toggle[0] + toggle[2] - font.width(arrow)) / 2, top + 2,
                toggleHot ? TEXT : DIM, false);

        List<Placed> stack = stacks.get(side);
        if (stack.isEmpty()) {
            int x0 = col[0] + 4, width = col[1] - col[0] - 8;
            if (width > 0)
                graphics.drawWordWrap(font, EMPTY_HINT, x0, top + HEADER + 6, width, HINT);
            return;
        }
        int hotSplit = busy ? -1 : splitAt(side, mouseX, mouseY);
        if (press == Press.SPLIT && splitAbove.side == side)
            hotSplit = splitIndex;
        for (Placed placed : stack) {
            boolean last = placed.index == stack.size() - 1;
            if (!last)
                graphics.fill(placed.x0, placed.bottom(), placed.x1, placed.bottom() + GAP,
                        placed.index == hotSplit ? ACCENT : SPLIT);
            renderMenu(graphics, placed, mouseX, mouseY, partialTicks, busy);
        }
    }

    private void renderMenu(GuiGraphics graphics, Placed placed, int mouseX, int mouseY, float partialTicks,
            boolean busy) {
        boolean dragged = press == Press.MENU && pressed != null && pressed.menu == placed.menu;
        boolean titleHot = !busy && placed.inTitle(mouseX, mouseY);
        graphics.fill(placed.x0, placed.top, placed.x1, placed.top + TITLE, titleHot ? TITLE_HOVER : TITLE_FILL);
        int toggleX = placed.x1 - TITLE;
        if (!busy && placed.inToggle(mouseX, mouseY))
            graphics.fill(toggleX, placed.top, placed.x1, placed.top + TITLE, ACCENT);
        String sign = placed.collapsed ? "+" : "-";
        graphics.drawString(font, sign, toggleX + (TITLE - font.width(sign)) / 2 + 1, placed.top + 2, TEXT, false);
        String title = font.plainSubstrByWidth(placed.menu.title().getString(), Math.max(0, toggleX - placed.x0 - 6));
        graphics.drawString(font, title, placed.x0 + 4, placed.top + 2, dragged ? DIM : TEXT, false);

        int height = placed.contentHeight();
        if (height <= 0)
            return;
        int y0 = placed.contentTop(), y1 = Math.min(bottom, y0 + height);
        if (y1 <= y0)
            return;
        // While the cursor is busy with chrome, the menus see it nowhere, so they show no hover
        boolean blind = busy || (press == Press.CONTENT && pressed != null && pressed.menu != placed.menu);
        placed.menu.layout(placed.x0, y0, placed.x1 - placed.x0, y1 - y0);
        graphics.enableScissor(placed.x0, y0, placed.x1, y1);
        placed.menu.render(graphics, blind ? NOWHERE : mouseX, blind ? NOWHERE : mouseY, partialTicks);
        graphics.disableScissor();
    }

    /** The dragged menu's title following the cursor, and where it would land. */
    private void renderDrag(GuiGraphics graphics, int mouseX, int mouseY) {
        graphics.pose().pushPose();
        graphics.pose().translate(0, 0, 300);
        DropTarget drop = dropTarget(mouseX, mouseY);
        if (drop != null)
            graphics.fill(drop.rect[0], drop.rect[1], drop.rect[2], drop.rect[3], drop.line ? ACCENT : DROP_FILL);
        int width = Math.max(60, pressed.x1 - pressed.x0);
        int x0 = (int) (mouseX - (pressX - pressed.x0)), y0 = (int) (mouseY - (pressY - pressed.top));
        x0 = Math.max(0, Math.min(screenWidth - width, x0));
        graphics.fill(x0 - 1, y0 - 1, x0 + width + 1, y0 + TITLE + 1, 0xC0000000);
        graphics.fill(x0, y0, x0 + width, y0 + TITLE, 0xC03A6EA5);
        String title = font.plainSubstrByWidth(pressed.menu.title().getString(), width - 8);
        graphics.drawString(font, title, x0 + 4, y0 + 2, TEXT, false);
        graphics.pose().popPose();
    }

    /** Where a dragged menu would go, and what to draw to show it. */
    private record DropTarget(Side side, int slot, int[] rect, boolean line) {}

    private DropTarget dropTarget(double x, double y) {
        Side side = sideAt(x, y);
        if (side == null)
            return null;
        int[] col = column.get(side);
        if (config.collapsed(side))
            return new DropTarget(side, config.menus(side).size(), new int[] { col[0], top, col[1], bottom }, false);
        List<Placed> stack = stacks.get(side);
        if (stack.isEmpty())
            return new DropTarget(side, 0, new int[] { col[0], top + HEADER, col[1], bottom }, false);
        int[] tops = new int[stack.size()], heights = new int[stack.size()];
        for (int i = 0; i < stack.size(); i++) {
            tops[i] = stack.get(i).top;
            heights[i] = stack.get(i).height;
        }
        int slot = EditorLayoutConfig.dropSlot(tops, heights, y);
        for (Placed placed : stack) {
            if (y < placed.top || y >= placed.bottom())
                continue;
            int mid = placed.top + placed.height / 2;
            int[] rect = slot == placed.index ? new int[] { placed.x0, placed.top, placed.x1, mid }
                    : new int[] { placed.x0, mid, placed.x1, placed.bottom() };
            return new DropTarget(side, slot, rect, false);
        }
        // In a gap, the header, or below the last menu: a line where it would go
        int lineY = slot < stack.size() ? stack.get(slot).top - 2 : stack.get(stack.size() - 1).bottom();
        return new DropTarget(side, slot, new int[] { col[0], lineY, col[1], lineY + 2 }, true);
    }

    // ---- input ----

    public boolean mouseClicked(double x, double y, int button) {
        mouseX = x;
        mouseY = y;
        if (press != Press.NONE)
            return true;
        // An inner edge grabs a few pixels either side of its line, over the glass and over
        // a menu's content alike; only the toggle buttons it brushes keep their own presses
        Side edge = button == 0 ? edgeAt(x, y) : null;
        if (edge != null && !onToggle(x, y)) {
            clearFocus();
            pressButton = button;
            pressX = x;
            pressY = y;
            press = Press.WIDTH;
            widthSide = edge;
            return true;
        }
        if (!contains(x, y)) {
            // A press anywhere outside the sidebars lets go of the keyboard
            clearFocus();
            return false;
        }
        pressButton = button;
        pressX = x;
        pressY = y;
        Side side = sideAt(x, y);
        Placed placed = placedAt(x, y);
        if (placed != null && placed.inContent(x, y)) {
            focusOnly(placed.menu);
            press = Press.CONTENT;
            pressed = placed;
            placed.menu.mouseClicked(x, y, button);
            return true;
        }
        clearFocus();
        press = Press.CHROME;
        if (button == 1 && placed != null && placed.inTitle(x, y)) {
            openTitleMenu(placed, x, y);
            return true;
        }
        if (button != 0)
            return true;
        if (in(headerToggle(side), x, y)) {
            config.setCollapsed(side, !config.collapsed(side));
            changed();
            return true;
        }
        int split = splitAt(side, x, y);
        if (split >= 0) {
            Placed[] pair = splitPair(stacks.get(side), split);
            press = Press.SPLIT;
            splitIndex = split;
            splitAbove = pair[0];
            splitBelow = pair[1];
            return true;
        }
        if (placed != null && placed.inToggle(x, y)) {
            String id = placed.menu.id();
            config.setMenuCollapsed(id, !config.menuCollapsed(id));
            changed();
            return true;
        }
        if (placed != null && placed.inTitle(x, y)) {
            press = Press.MENU_PENDING;
            pressed = placed;
        }
        return true;
    }

    /** Whether the point is on a sidebar's collapse button or a menu's + or - button. */
    private boolean onToggle(double x, double y) {
        Side side = sideAt(x, y);
        if (side == null)
            return false;
        if (in(headerToggle(side), x, y))
            return true;
        Placed placed = placedAt(x, y);
        return placed != null && placed.inToggle(x, y);
    }

    /** A title bar's right-click menu: move the menu to the other sidebar, and collapse or expand it. */
    private void openTitleMenu(Placed placed, double x, double y) {
        String id = placed.menu.id();
        Side other = placed.side.other();
        List<ContextMenu.Entry> entries = new ArrayList<>();
        entries.add(new ContextMenu.Entry(Component.translatable(LANG + "move_other"), true, () -> {
            config.move(id, other, config.menus(other).size());
            // As a drop onto a collapsed strip does, the move opens it so the menu is seen landing
            config.setCollapsed(other, false);
            changed();
        }));
        boolean collapsed = config.menuCollapsed(id);
        entries.add(new ContextMenu.Entry(Component.translatable(LANG + (collapsed ? "expand" : "collapse")), true,
                () -> {
                    config.setMenuCollapsed(id, !config.menuCollapsed(id));
                    changed();
                }));
        contextMenus.open(x, y, entries);
    }

    public boolean mouseDragged(double x, double y, int button, double dragX, double dragY) {
        mouseX = x;
        mouseY = y;
        switch (press) {
            case NONE:
                return false;
            case CONTENT:
                pressed.menu.mouseDragged(x, y, button, dragX, dragY);
                return true;
            case MENU_PENDING:
                if (Math.abs(x - pressX) >= DRAG_START || Math.abs(y - pressY) >= DRAG_START)
                    press = Press.MENU;
                return true;
            case WIDTH: {
                int width = widthSide == Side.LEFT ? (int) Math.round(x) : screenWidth - (int) Math.round(x);
                config.setWidth(widthSide, EditorLayoutConfig.clampWidth(width, screenWidth));
                dirty = true;
                relayout();
                return true;
            }
            case SPLIT: {
                float[] weights = EditorLayoutConfig.resizePair(config.weight(splitAbove.menu.id()),
                        config.weight(splitBelow.menu.id()), splitAbove.height, splitBelow.height,
                        (int) Math.round(y - pressY), MIN_MENU);
                config.setWeight(splitAbove.menu.id(), weights[0]);
                config.setWeight(splitBelow.menu.id(), weights[1]);
                dirty = true;
                relayout();
                // The menus have moved, so the next step is measured from where they are now
                Placed[] pair = refind(splitAbove, splitBelow);
                if (pair != null) {
                    pressY = y;
                    splitAbove = pair[0];
                    splitBelow = pair[1];
                }
                return true;
            }
            default:
                return true;
        }
    }

    private Placed[] refind(Placed above, Placed below) {
        Placed a = null, b = null;
        for (Placed placed : stacks.get(above.side)) {
            if (placed.menu == above.menu)
                a = placed;
            if (placed.menu == below.menu)
                b = placed;
        }
        return a == null || b == null ? null : new Placed[] { a, b };
    }

    public boolean mouseReleased(double x, double y, int button) {
        mouseX = x;
        mouseY = y;
        if (press == Press.NONE)
            return false;
        if (button != pressButton)
            return true;
        Press was = press;
        Placed menu = pressed;
        press = Press.NONE;
        pressed = null;
        splitAbove = splitBelow = null;
        switch (was) {
            case CONTENT -> menu.menu.mouseReleased(x, y, button);
            case MENU -> {
                DropTarget drop = dropTarget(x, y);
                if (drop != null) {
                    config.move(menu.menu.id(), drop.side, drop.slot);
                    // Dropping onto a collapsed strip opens it, so the menu is seen landing
                    config.setCollapsed(drop.side, false);
                    changed();
                }
            }
            case WIDTH, SPLIT -> changed();
            default -> {
            }
        }
        return true;
    }

    public boolean mouseScrolled(double x, double y, double delta) {
        if (!contains(x, y))
            return false;
        Placed placed = placedAt(x, y);
        if (placed != null && placed.inContent(x, y))
            placed.menu.mouseScrolled(x, y, delta);
        return true;
    }

    public boolean keyPressed(int key, int scanCode, int modifiers) {
        EditorMenu focused = focused();
        return focused != null && focused.keyPressed(key, scanCode, modifiers);
    }

    public boolean charTyped(char character, int modifiers) {
        EditorMenu focused = focused();
        return focused != null && focused.charTyped(character, modifiers);
    }

    private EditorMenu focused() {
        for (EditorMenu menu : menus.values())
            if (menu.hasTextFocus())
                return menu;
        return null;
    }

    public boolean hasTextFocus() {
        return focused() != null;
    }

    public void clearFocus() {
        for (EditorMenu menu : menus.values())
            menu.clearFocus();
    }

    private void focusOnly(EditorMenu keep) {
        for (EditorMenu menu : menus.values())
            if (menu != keep)
                menu.clearFocus();
    }

    public void tick() {
        for (EditorMenu menu : menus.values())
            menu.tick();
    }

    private void changed() {
        dirty = true;
        relayout();
        save();
    }

    /** Writes the layout if the player changed it since it was last written. */
    public void save() {
        if (!dirty)
            return;
        config.save(file);
        dirty = false;
    }
}
