package com.nectariich.create_flippies.client.display.editor;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.Util;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/**
 * The display editor's tool strip: a row of square icon buttons floating at the bottom
 * edge of the screen, one per {@link Tool} plus Import SVG, over the world view and
 * centred on the whole screen (as the display is), whatever the sidebars are doing.
 * Hovering one names it, with its key.
 *
 * The icons are drawn pixel by pixel from the little maps below, so they need no
 * texture and stay crisp at any GUI scale.
 *
 * A click picks a tool for one shape; a second click on the same button within
 * {@value #DOUBLE_CLICK_MILLIS} ms locks it until Select is picked or V is pressed
 * ({@link EditorContext#pickTool}). Import SVG is not a tool of its own: it opens the
 * file picker, and once the picture is stored the SVG tool is picked to drag its box.
 */
public final class ToolStrip {

    private static final long DOUBLE_CLICK_MILLIS = 300;
    private static final int HEIGHT = 20, PAD = 2, GAP = 2, MARGIN = 6, ICON = 12;
    private static final int BACKGROUND = 0xE0181C21, BUTTON = 0xFF2A3038, HOVER = 0xFF39414B, PICKED = 0xFF3A6EA5,
            LOCK = 0xFFFFB020;

    /** One button: a tool, or null for Import SVG, and its icon, a row of text per pixel row, # for ink. */
    private record Button(Tool tool, String[] icon, Component hint) {}

    private static final String[] SELECT = {
            "..#.........",
            "..##........",
            "..###.......",
            "..####......",
            "..#####.....",
            "..######....",
            "..#######...",
            "..########..",
            "..####......",
            "..#..##.....",
            ".....##.....",
            "......##....",
    };
    private static final String[] TEXT = {
            "............",
            ".##########.",
            ".##########.",
            ".##..##..##.",
            ".....##.....",
            ".....##.....",
            ".....##.....",
            ".....##.....",
            ".....##.....",
            ".....##.....",
            "....####....",
            "............",
    };
    private static final String[] RECTANGLE = {
            "............",
            "............",
            ".##########.",
            ".##########.",
            ".##########.",
            ".##########.",
            ".##########.",
            ".##########.",
            ".##########.",
            ".##########.",
            "............",
            "............",
    };
    private static final String[] ELLIPSE = {
            "............",
            "....####....",
            "..########..",
            "..########..",
            ".##########.",
            ".##########.",
            ".##########.",
            ".##########.",
            "..########..",
            "..########..",
            "....####....",
            "............",
    };
    private static final String[] LINE = {
            "..........##",
            ".........###",
            "........###.",
            ".......###..",
            "......###...",
            ".....###....",
            "....###.....",
            "...###......",
            "..###.......",
            ".###........",
            "###.........",
            "##..........",
    };
    private static final String[] BAR = {
            "............",
            "............",
            "............",
            "############",
            "#######....#",
            "#######....#",
            "#######....#",
            "#######....#",
            "############",
            "............",
            "............",
            "............",
    };
    private static final String[] IMPORT_SVG = {
            "............",
            "############",
            "#..........#",
            "#.......##.#",
            "#.......##.#",
            "#..........#",
            "#...#......#",
            "#..###...#.#",
            "#.#####.####",
            "############",
            "............",
            "............",
    };

    private final EditorContext context;
    private final List<Button> buttons = new ArrayList<>();
    private final int[] lefts, widths;
    private int x, y, width;

    private int lastClicked = -1;
    private long lastClickTime;

    public ToolStrip(EditorContext context) {
        this.context = context;
        for (Tool tool : Tool.values()) {
            // The SVG tool is reached through Import SVG, which picks the file first
            if (tool == Tool.SVG)
                continue;
            String key = tool.name().toLowerCase(java.util.Locale.ROOT);
            buttons.add(new Button(tool, icon(tool),
                    Component.translatable("gui.create_flippies.display_editor.tool." + key + ".hint", tool.label)));
        }
        buttons.add(new Button(null, IMPORT_SVG,
                Component.translatable("gui.create_flippies.display_editor.tool.import_svg.hint")));
        lefts = new int[buttons.size()];
        widths = new int[buttons.size()];
    }

    /** Places the strip for a screen of that size; call before every frame and before input. */
    public void layout(int screenWidth, int screenHeight) {
        int total = PAD;
        for (int i = 0; i < buttons.size(); i++) {
            widths[i] = HEIGHT;
            lefts[i] = total;
            total += widths[i] + GAP;
        }
        width = total - GAP + PAD;
        x = (screenWidth - width) / 2;
        y = screenHeight - MARGIN - HEIGHT - 2 * GAP;
    }

    public boolean contains(double mx, double my) {
        return mx >= x && mx < x + width && my >= y && my < y + HEIGHT + 2 * GAP;
    }

    public void render(GuiGraphics graphics, int mouseX, int mouseY) {
        graphics.fill(x - 1, y - 1, x + width + 1, y + HEIGHT + 2 * GAP + 1, 0xFF000000);
        graphics.fill(x, y, x + width, y + HEIGHT + 2 * GAP, BACKGROUND);
        int hovered = buttonAt(mouseX, mouseY);
        for (int i = 0; i < buttons.size(); i++) {
            Button button = buttons.get(i);
            int bx = x + lefts[i], by = y + GAP;
            boolean picked = button.tool() != null ? context.tool == button.tool()
                    : context.tool == Tool.SVG;
            graphics.fill(bx, by, bx + widths[i], by + HEIGHT, picked ? PICKED : i == hovered ? HOVER : BUTTON);
            drawIcon(graphics, button.icon(), bx + (HEIGHT - ICON) / 2, by + (HEIGHT - ICON) / 2);
            // The lock marker: a bar along the foot of the locked tool's button
            if (picked && context.toolLocked)
                graphics.fill(bx + 1, by + HEIGHT - 2, bx + widths[i] - 1, by + HEIGHT, LOCK);
        }
        if (hovered >= 0) {
            Button button = buttons.get(hovered);
            Component hint = button.hint();
            if (button.tool() != null && button.tool() == context.tool && context.toolLocked)
                hint = Component.translatable("gui.create_flippies.display_editor.tool.locked", hint);
            graphics.renderTooltip(context.font, hint, mouseX, mouseY);
        }
    }

    /** A left click inside the strip; true when it was over the strip (always taken there). */
    public boolean mouseClicked(double mx, double my, int button) {
        if (!contains(mx, my))
            return false;
        if (button != 0)
            return true;
        int index = buttonAt(mx, my);
        if (index < 0)
            return true;
        long now = Util.getMillis();
        boolean twice = index == lastClicked && now - lastClickTime < DOUBLE_CLICK_MILLIS;
        lastClicked = index;
        lastClickTime = now;
        Tool tool = buttons.get(index).tool();
        if (tool == null) {
            // A double click on Import SVG would open the picker twice
            if (!twice)
                importSvg();
            return true;
        }
        context.pickTool(tool, twice);
        return true;
    }

    /**
     * The tools' keys: V, T, R, E, L, B (V also unlocks). Not with Ctrl, Cmd or Alt
     * held, which belong to the editor's commands (Ctrl+V pastes). True when taken.
     */
    public boolean keyPressed(int key) {
        if (Screen.hasControlDown() || Screen.hasAltDown())
            return false;
        for (Tool tool : Tool.values()) {
            if (tool.key >= 0 && tool.key == key) {
                context.pickTool(tool, false);
                return true;
            }
        }
        return false;
    }

    private static String[] icon(Tool tool) {
        return switch (tool) {
            case SELECT -> SELECT;
            case TEXT -> TEXT;
            case RECTANGLE -> RECTANGLE;
            case ELLIPSE -> ELLIPSE;
            case LINE -> LINE;
            case BAR -> BAR;
            case SVG -> IMPORT_SVG;
        };
    }

    /** An icon's ink, a run of pixels at a time. */
    private static void drawIcon(GuiGraphics graphics, String[] icon, int left, int top) {
        for (int row = 0; row < icon.length; row++) {
            String line = icon[row];
            int start = -1;
            for (int col = 0; col <= line.length(); col++) {
                boolean ink = col < line.length() && line.charAt(col) == '#';
                if (ink && start < 0)
                    start = col;
                else if (!ink && start >= 0) {
                    graphics.fill(left + start, top + row, left + col, top + row + 1, 0xFFFFFFFF);
                    start = -1;
                }
            }
        }
    }

    private void importSvg() {
        context.importSvg(hash -> {
            context.pendingArt = hash;
            context.pickTool(Tool.SVG, false);
        });
    }

    private int buttonAt(double mx, double my) {
        if (my < y + GAP || my >= y + GAP + HEIGHT)
            return -1;
        for (int i = 0; i < buttons.size(); i++)
            if (mx >= x + lefts[i] && mx < x + lefts[i] + widths[i])
                return i;
        return -1;
    }
}
