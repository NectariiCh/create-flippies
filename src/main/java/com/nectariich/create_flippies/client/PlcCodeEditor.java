package com.nectariich.create_flippies.client;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.narration.NarratedElementType;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.util.Mth;

/**
 * A small code editor for the PLC's menu: monospaced, line-numbered, coloured, and
 * without soft wrapping, so a line on the screen is a line in the sketch and a
 * compiler's "line 12" is the twelfth row of the gutter.
 *
 * Vanilla's {@code MultiLineEditBox} was the alternative and is the wrong shape for
 * code: it wraps long lines, which makes line numbers meaningless, it draws in the
 * proportional font, which makes indentation ragged, and its text field is private,
 * so neither can be fixed from outside. This is a plain list of lines and a cursor,
 * which is all an editor for a few thousand lines of sketch needs.
 *
 * <h2>What it does</h2>
 *
 * Typing, selection by keyboard and mouse (double click for a word), cut, copy and
 * paste through the system clipboard, select all, undo and redo, Home toggling
 * between the indentation and the margin, word jumps on Ctrl, Tab and Shift+Tab
 * indenting by two spaces — the Arduino IDE's step — over one line or a selection,
 * Enter carrying the indentation over and adding a step after an opening brace.
 * Tabs in pasted text are expanded to spaces, so what lines up here lines up in the
 * compiler's column numbers too.
 *
 * <h2>Monospace from a proportional font</h2>
 *
 * Minecraft's font is proportional, but its ASCII glyphs are all at most five pixels
 * wide plus one of spacing, so every character is drawn centred in a six-pixel cell.
 * {@code i} and {@code W} then occupy the same width, columns line up, and a click
 * resolves to a column by division.
 *
 * Every visible character is drawn separately, inside one {@link GuiGraphics#drawManaged}
 * so they go to the GPU as one batch rather than one flush each.
 */
public class PlcCodeEditor extends AbstractWidget {

    public static final int CHAR_WIDTH = 6;
    public static final int LINE_HEIGHT = 10;
    private static final int PADDING = 3;
    private static final int INDENT = 2;
    private static final int TAB_STOP = 4;
    private static final int UNDO_DEPTH = 100;
    /** Keystrokes closer together than this are undone as one. */
    private static final long UNDO_COALESCE_MS = 1000;
    private static final long DOUBLE_CLICK_MS = 250;

    private static final int BACKGROUND = 0xFF141414;
    private static final int GUTTER = 0xFF1E1E1E;
    private static final int BORDER = 0xFF606060;
    private static final int BORDER_FOCUSED = 0xFFC0C0C0;
    private static final int LINE_NUMBER = 0xFF707070;
    private static final int CURRENT_LINE = 0xFF262626;
    private static final int SELECTION = 0xFF264F78;
    private static final int CURSOR = 0xFFE0E0E0;

    // GLFW key codes, named here rather than pulling in LWJGL for a dozen constants
    private static final int KEY_TAB = 258;
    private static final int KEY_ENTER = 257;
    private static final int KEY_KP_ENTER = 335;
    private static final int KEY_BACKSPACE = 259;
    private static final int KEY_DELETE = 261;
    private static final int KEY_RIGHT = 262;
    private static final int KEY_LEFT = 263;
    private static final int KEY_DOWN = 264;
    private static final int KEY_UP = 265;
    private static final int KEY_PAGE_UP = 266;
    private static final int KEY_PAGE_DOWN = 267;
    private static final int KEY_HOME = 268;
    private static final int KEY_END = 269;
    private static final int KEY_Y = 89;
    private static final int KEY_Z = 90;

    private final Font font;
    private final int maxChars;

    private final List<String> lines = new ArrayList<>();
    private int cursorLine;
    private int cursorCol;
    private int anchorLine;
    private int anchorCol;
    /** The column Up and Down aim for, which survives passing through shorter lines. */
    private int wantedCol;
    private int scrollLine;
    private int scrollCol;

    private boolean dragging;
    private long lastClickMs;
    private int lastClickLine = -1;
    private int lastClickCol = -1;

    private record Snapshot(String text, int line, int col) {}

    private enum EditKind { TYPE, OTHER }

    private final Deque<Snapshot> undo = new ArrayDeque<>();
    private final Deque<Snapshot> redo = new ArrayDeque<>();
    private EditKind lastEdit = EditKind.OTHER;
    private long lastEditMs;

    private Runnable changeListener = () -> {};

    /** Lines to tint, 1-based, by colour; set from diagnostics. */
    private final Map<Integer, Integer> markers = new HashMap<>();

    /** One colour class per character per line, rebuilt lazily after an edit. */
    private final List<byte[]> colours = new ArrayList<>();
    private boolean coloursStale = true;

    public PlcCodeEditor(Font font, int x, int y, int width, int height, int maxChars, Component message) {
        super(x, y, width, height, message);
        this.font = font;
        this.maxChars = maxChars;
        lines.add("");
    }

    // ------------------------------------------------------------ the text ---

    public void setChangeListener(Runnable listener) {
        changeListener = listener;
    }

    public String getValue() {
        return String.join("\n", lines);
    }

    /** Replaces everything, forgetting the undo history; for loading a sketch. */
    public void setValue(String text) {
        loadText(normalise(text, 0));
        cursorLine = cursorCol = anchorLine = anchorCol = wantedCol = 0;
        scrollLine = scrollCol = 0;
        undo.clear();
        redo.clear();
        lastEdit = EditKind.OTHER;
    }

    private void loadText(String text) {
        if (text.length() > maxChars)
            text = text.substring(0, maxChars);
        lines.clear();
        int start = 0;
        while (true) {
            int newline = text.indexOf('\n', start);
            if (newline < 0) {
                lines.add(text.substring(start));
                break;
            }
            lines.add(text.substring(start, newline));
            start = newline + 1;
        }
        coloursStale = true;
    }

    private int length() {
        int total = lines.size() - 1;
        for (String line : lines)
            total += line.length();
        return total;
    }

    public void setMarkers(Map<Integer, Integer> lineColours) {
        markers.clear();
        markers.putAll(lineColours);
    }

    /** Puts the cursor at the start of a 1-based line and scrolls it into the middle of the view. */
    public void goToLine(int line, int column) {
        cursorLine = Mth.clamp(line - 1, 0, lines.size() - 1);
        cursorCol = Mth.clamp(column - 1, 0, lines.get(cursorLine).length());
        anchorLine = cursorLine;
        anchorCol = cursorCol;
        wantedCol = cursorCol;
        scrollLine = Math.max(0, cursorLine - visibleRows() / 2);
        clampScroll();
        scrollToCursor();
    }

    /**
     * Strips carriage returns and every control character but the newline, and
     * expands tabs to the next {@link #TAB_STOP}. {@code column} is where the text
     * will start, so a tab pasted mid-line still lands on a stop.
     */
    private static String normalise(String text, int column) {
        StringBuilder out = new StringBuilder(text.length());
        int col = column;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '\n') {
                out.append(c);
                col = 0;
            } else if (c == '\t') {
                int spaces = TAB_STOP - col % TAB_STOP;
                out.append(" ".repeat(spaces));
                col += spaces;
            } else if (c >= ' ' && c != 127) {
                out.append(c);
                col++;
            }
        }
        return out.toString();
    }

    // ---------------------------------------------------------- selection ---

    private boolean hasSelection() {
        return cursorLine != anchorLine || cursorCol != anchorCol;
    }

    private boolean anchorFirst() {
        return anchorLine < cursorLine || anchorLine == cursorLine && anchorCol < cursorCol;
    }

    private int startLine() {
        return anchorFirst() ? anchorLine : cursorLine;
    }

    private int startCol() {
        return anchorFirst() ? anchorCol : cursorCol;
    }

    private int endLine() {
        return anchorFirst() ? cursorLine : anchorLine;
    }

    private int endCol() {
        return anchorFirst() ? cursorCol : anchorCol;
    }

    private String selectedText() {
        if (!hasSelection())
            return "";
        int sl = startLine(), sc = startCol(), el = endLine(), ec = endCol();
        if (sl == el)
            return lines.get(sl).substring(sc, ec);
        StringBuilder out = new StringBuilder(lines.get(sl).substring(sc));
        for (int line = sl + 1; line < el; line++)
            out.append('\n').append(lines.get(line));
        out.append('\n').append(lines.get(el), 0, ec);
        return out.toString();
    }

    private void deleteSelection() {
        if (!hasSelection())
            return;
        int sl = startLine(), sc = startCol(), el = endLine(), ec = endCol();
        String merged = lines.get(sl).substring(0, sc) + lines.get(el).substring(ec);
        for (int line = el; line > sl; line--)
            lines.remove(line);
        lines.set(sl, merged);
        cursorLine = anchorLine = sl;
        cursorCol = anchorCol = sc;
        coloursStale = true;
    }

    private void collapse() {
        anchorLine = cursorLine;
        anchorCol = cursorCol;
    }

    private void selectAll() {
        anchorLine = 0;
        anchorCol = 0;
        cursorLine = lines.size() - 1;
        cursorCol = lines.get(cursorLine).length();
    }

    // -------------------------------------------------------------- edits ---

    private void beforeEdit(EditKind kind) {
        long now = Util.getMillis();
        boolean coalesce = kind == EditKind.TYPE && lastEdit == EditKind.TYPE && now - lastEditMs < UNDO_COALESCE_MS;
        if (!coalesce) {
            undo.push(new Snapshot(getValue(), cursorLine, cursorCol));
            while (undo.size() > UNDO_DEPTH)
                undo.removeLast();
        }
        redo.clear();
        lastEdit = kind;
        lastEditMs = now;
    }

    private void afterEdit() {
        coloursStale = true;
        wantedCol = cursorCol;
        scrollToCursor();
        changeListener.run();
    }

    /** Inserts text at the cursor, replacing the selection, within the character limit. */
    private void insert(String raw, EditKind kind) {
        beforeEdit(kind);
        deleteSelection();
        String text = normalise(raw, cursorCol);
        int room = maxChars - length();
        if (text.length() > room)
            text = text.substring(0, Math.max(0, room));
        if (!text.isEmpty()) {
            String line = lines.get(cursorLine);
            String before = line.substring(0, cursorCol);
            String after = line.substring(cursorCol);
            String[] parts = text.split("\n", -1);
            if (parts.length == 1) {
                lines.set(cursorLine, before + parts[0] + after);
                cursorCol += parts[0].length();
            } else {
                lines.set(cursorLine, before + parts[0]);
                for (int i = 1; i < parts.length - 1; i++)
                    lines.add(cursorLine + i, parts[i]);
                String last = parts[parts.length - 1];
                lines.add(cursorLine + parts.length - 1, last + after);
                cursorLine += parts.length - 1;
                cursorCol = last.length();
            }
        }
        collapse();
        afterEdit();
    }

    private void newline() {
        String line = lines.get(cursorLine);
        int indent = 0;
        while (indent < line.length() && line.charAt(indent) == ' ')
            indent++;
        indent = Math.min(indent, cursorCol);
        String before = line.substring(0, cursorCol).stripTrailing();
        if (before.endsWith("{"))
            indent += INDENT;
        insert("\n" + " ".repeat(indent), EditKind.OTHER);
    }

    private void backspace(boolean word) {
        if (hasSelection()) {
            beforeEdit(EditKind.OTHER);
            deleteSelection();
            afterEdit();
            return;
        }
        if (cursorLine == 0 && cursorCol == 0)
            return;
        beforeEdit(EditKind.OTHER);
        if (cursorCol == 0) {
            String line = lines.remove(cursorLine);
            cursorLine--;
            cursorCol = lines.get(cursorLine).length();
            lines.set(cursorLine, lines.get(cursorLine) + line);
        } else {
            String line = lines.get(cursorLine);
            int to = word ? wordLeft(line, cursorCol) : cursorCol - 1;
            // Inside the indentation, one step back to the previous stop
            if (!word && line.substring(0, cursorCol).isBlank())
                to = Math.max(0, (cursorCol - 1) / INDENT * INDENT);
            lines.set(cursorLine, line.substring(0, to) + line.substring(cursorCol));
            cursorCol = to;
        }
        collapse();
        afterEdit();
    }

    private void delete(boolean word) {
        if (hasSelection()) {
            beforeEdit(EditKind.OTHER);
            deleteSelection();
            afterEdit();
            return;
        }
        String line = lines.get(cursorLine);
        if (cursorCol == line.length() && cursorLine == lines.size() - 1)
            return;
        beforeEdit(EditKind.OTHER);
        if (cursorCol == line.length()) {
            lines.set(cursorLine, line + lines.remove(cursorLine + 1));
        } else {
            int to = word ? wordRight(line, cursorCol) : cursorCol + 1;
            lines.set(cursorLine, line.substring(0, cursorCol) + line.substring(to));
        }
        collapse();
        afterEdit();
    }

    /** Tab and Shift+Tab: by one step, over every line the selection touches. */
    private void indent(boolean out) {
        boolean block = hasSelection() && startLine() != endLine();
        if (!block && !out) {
            insert(" ".repeat(INDENT - cursorCol % INDENT), EditKind.OTHER);
            return;
        }
        beforeEdit(EditKind.OTHER);
        int from = block ? startLine() : cursorLine;
        int to = block ? endLine() : cursorLine;
        // A selection ending at the very start of a line does not include that line
        if (block && (anchorFirst() ? cursorCol : anchorCol) == 0)
            to--;
        for (int line = from; line <= to; line++) {
            String text = lines.get(line);
            int shift;
            if (out) {
                int spaces = 0;
                while (spaces < INDENT && spaces < text.length() && text.charAt(spaces) == ' ')
                    spaces++;
                lines.set(line, text.substring(spaces));
                shift = -spaces;
            } else {
                if (length() + INDENT > maxChars)
                    continue;
                lines.set(line, " ".repeat(INDENT) + text);
                shift = INDENT;
            }
            if (line == cursorLine)
                cursorCol = Math.max(0, cursorCol + shift);
            if (line == anchorLine)
                anchorCol = Math.max(0, anchorCol + shift);
        }
        afterEdit();
    }

    private void restore(Deque<Snapshot> from, Deque<Snapshot> to) {
        if (from.isEmpty())
            return;
        to.push(new Snapshot(getValue(), cursorLine, cursorCol));
        Snapshot snapshot = from.pop();
        loadText(snapshot.text());
        cursorLine = Mth.clamp(snapshot.line(), 0, lines.size() - 1);
        cursorCol = Mth.clamp(snapshot.col(), 0, lines.get(cursorLine).length());
        collapse();
        lastEdit = EditKind.OTHER;
        afterEdit();
    }

    // ------------------------------------------------------------ movement ---

    private static boolean isWord(char c) {
        return Character.isLetterOrDigit(c) || c == '_';
    }

    private static int wordLeft(String line, int col) {
        int i = col;
        while (i > 0 && line.charAt(i - 1) == ' ')
            i--;
        if (i > 0 && isWord(line.charAt(i - 1))) {
            while (i > 0 && isWord(line.charAt(i - 1)))
                i--;
        } else if (i > 0) {
            i--;
        }
        return i;
    }

    private static int wordRight(String line, int col) {
        int i = col;
        if (i < line.length() && isWord(line.charAt(i))) {
            while (i < line.length() && isWord(line.charAt(i)))
                i++;
        } else if (i < line.length()) {
            i++;
        }
        while (i < line.length() && line.charAt(i) == ' ')
            i++;
        return i;
    }

    private void moveTo(int line, int col, boolean select, boolean keepWanted) {
        cursorLine = Mth.clamp(line, 0, lines.size() - 1);
        cursorCol = Mth.clamp(col, 0, lines.get(cursorLine).length());
        if (!select)
            collapse();
        if (!keepWanted)
            wantedCol = cursorCol;
        scrollToCursor();
    }

    private void moveVertically(int rows, boolean select) {
        int line = Mth.clamp(cursorLine + rows, 0, lines.size() - 1);
        moveTo(line, Math.min(wantedCol, lines.get(line).length()), select, true);
    }

    // ------------------------------------------------------------ geometry ---

    private int gutterWidth() {
        return String.valueOf(lines.size()).length() * CHAR_WIDTH + 2 * PADDING + 2;
    }

    private int textLeft() {
        return getX() + gutterWidth() + PADDING;
    }

    private int textTop() {
        return getY() + PADDING;
    }

    public int visibleRows() {
        return Math.max(1, (height - 2 * PADDING) / LINE_HEIGHT);
    }

    private int visibleCols() {
        return Math.max(1, (getX() + width - PADDING - textLeft()) / CHAR_WIDTH);
    }

    private void clampScroll() {
        scrollLine = Mth.clamp(scrollLine, 0, Math.max(0, lines.size() - visibleRows()));
        scrollCol = Math.max(0, scrollCol);
    }

    private void scrollToCursor() {
        int rows = visibleRows();
        if (cursorLine < scrollLine)
            scrollLine = cursorLine;
        else if (cursorLine >= scrollLine + rows)
            scrollLine = cursorLine - rows + 1;
        int cols = visibleCols();
        if (cursorCol < scrollCol)
            scrollCol = Math.max(0, cursorCol - 4);
        else if (cursorCol >= scrollCol + cols)
            scrollCol = cursorCol - cols + 4;
        clampScroll();
    }

    private int lineAt(double mouseY) {
        return Mth.clamp(scrollLine + (int) Math.floor((mouseY - textTop()) / LINE_HEIGHT), 0, lines.size() - 1);
    }

    private int colAt(int line, double mouseX) {
        int col = scrollCol + (int) Math.round((mouseX - textLeft()) / CHAR_WIDTH);
        return Mth.clamp(col, 0, lines.get(line).length());
    }

    // -------------------------------------------------------------- input ---

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (!isFocused())
            return false;
        boolean shift = Screen.hasShiftDown();
        boolean ctrl = Screen.hasControlDown();

        if (Screen.isSelectAll(keyCode)) {
            selectAll();
            return true;
        }
        if (Screen.isCopy(keyCode)) {
            if (hasSelection())
                Minecraft.getInstance().keyboardHandler.setClipboard(selectedText());
            return true;
        }
        if (Screen.isCut(keyCode)) {
            if (hasSelection()) {
                Minecraft.getInstance().keyboardHandler.setClipboard(selectedText());
                beforeEdit(EditKind.OTHER);
                deleteSelection();
                afterEdit();
            }
            return true;
        }
        if (Screen.isPaste(keyCode)) {
            insert(Minecraft.getInstance().keyboardHandler.getClipboard(), EditKind.OTHER);
            return true;
        }
        if (ctrl && !Screen.hasAltDown() && (keyCode == KEY_Z || keyCode == KEY_Y)) {
            if (keyCode == KEY_Y || shift)
                restore(redo, undo);
            else
                restore(undo, redo);
            return true;
        }

        switch (keyCode) {
            case KEY_ENTER, KEY_KP_ENTER -> newline();
            case KEY_TAB -> indent(shift);
            case KEY_BACKSPACE -> backspace(ctrl);
            case KEY_DELETE -> delete(ctrl);
            case KEY_LEFT -> {
                if (hasSelection() && !shift)
                    moveTo(startLine(), startCol(), false, false);
                else if (cursorCol > 0)
                    moveTo(cursorLine, ctrl ? wordLeft(lines.get(cursorLine), cursorCol) : cursorCol - 1, shift, false);
                else if (cursorLine > 0)
                    moveTo(cursorLine - 1, lines.get(cursorLine - 1).length(), shift, false);
            }
            case KEY_RIGHT -> {
                String line = lines.get(cursorLine);
                if (hasSelection() && !shift)
                    moveTo(endLine(), endCol(), false, false);
                else if (cursorCol < line.length())
                    moveTo(cursorLine, ctrl ? wordRight(line, cursorCol) : cursorCol + 1, shift, false);
                else if (cursorLine < lines.size() - 1)
                    moveTo(cursorLine + 1, 0, shift, false);
            }
            case KEY_UP -> moveVertically(-1, shift);
            case KEY_DOWN -> moveVertically(1, shift);
            case KEY_PAGE_UP -> moveVertically(-visibleRows(), shift);
            case KEY_PAGE_DOWN -> moveVertically(visibleRows(), shift);
            case KEY_HOME -> {
                if (ctrl) {
                    moveTo(0, 0, shift, false);
                } else {
                    String line = lines.get(cursorLine);
                    int indent = line.length() - line.stripLeading().length();
                    moveTo(cursorLine, cursorCol == indent ? 0 : indent, shift, false);
                }
            }
            case KEY_END -> {
                if (ctrl)
                    moveTo(lines.size() - 1, lines.get(lines.size() - 1).length(), shift, false);
                else
                    moveTo(cursorLine, lines.get(cursorLine).length(), shift, false);
            }
            default -> {
                // Let the screen have Escape and anything else unclaimed
                return false;
            }
        }
        return true;
    }

    @Override
    public boolean charTyped(char c, int modifiers) {
        if (!isFocused() || c < ' ' || c == 127)
            return false;
        insert(String.valueOf(c), EditKind.TYPE);
        return true;
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (!visible || !isMouseOver(mouseX, mouseY) || button != 0)
            return false;
        setFocused(true);
        int line = lineAt(mouseY);
        int col = colAt(line, mouseX);
        long now = Util.getMillis();
        if (now - lastClickMs < DOUBLE_CLICK_MS && line == lastClickLine && col == lastClickCol) {
            String text = lines.get(line);
            int from = col, to = col;
            while (from > 0 && isWord(text.charAt(from - 1)))
                from--;
            while (to < text.length() && isWord(text.charAt(to)))
                to++;
            anchorLine = line;
            anchorCol = from;
            moveTo(line, to, true, false);
            dragging = false;
        } else {
            moveTo(line, col, Screen.hasShiftDown(), false);
            dragging = true;
        }
        lastClickMs = now;
        lastClickLine = line;
        lastClickCol = col;
        return true;
    }

    @Override
    public boolean mouseDragged(double mouseX, double mouseY, int button, double dragX, double dragY) {
        if (!dragging || button != 0)
            return false;
        int line = lineAt(mouseY);
        moveTo(line, colAt(line, mouseX), true, false);
        return true;
    }

    @Override
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        dragging = false;
        return super.mouseReleased(mouseX, mouseY, button);
    }

    @Override
    //? if forge {
    public boolean mouseScrolled(double mouseX, double mouseY, double delta) {
    //?} else {
    /*public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double delta) {
    *///?}
        if (!visible || !isMouseOver(mouseX, mouseY))
            return false;
        if (Screen.hasShiftDown())
            scrollCol -= (int) Math.signum(delta) * 8;
        else
            scrollLine -= (int) Math.signum(delta) * 3;
        clampScroll();
        return true;
    }

    // ------------------------------------------------------------ drawing ---

    @Override
    protected void renderWidget(GuiGraphics graphics, int mouseX, int mouseY, float partialTicks) {
        int x = getX(), y = getY();
        graphics.fill(x, y, x + width, y + height, isFocused() ? BORDER_FOCUSED : BORDER);
        graphics.fill(x + 1, y + 1, x + width - 1, y + height - 1, BACKGROUND);
        int gutterRight = x + gutterWidth();
        graphics.fill(x + 1, y + 1, gutterRight, y + height - 1, GUTTER);

        if (coloursStale)
            recolour();

        int rows = visibleRows();
        int cols = visibleCols();
        int left = textLeft();
        int top = textTop();
        int last = Math.min(lines.size(), scrollLine + rows);

        graphics.enableScissor(x + 1, y + 1, x + width - 1, y + height - 1);
        for (int line = scrollLine; line < last; line++) {
            int rowY = top + (line - scrollLine) * LINE_HEIGHT;
            Integer marker = markers.get(line + 1);
            if (marker != null)
                graphics.fill(gutterRight, rowY - 1, x + width - 1, rowY + LINE_HEIGHT - 1, marker);
            else if (line == cursorLine && isFocused() && !hasSelection())
                graphics.fill(gutterRight, rowY - 1, x + width - 1, rowY + LINE_HEIGHT - 1, CURRENT_LINE);
            drawSelection(graphics, line, rowY, left, cols);
        }

        // Every glyph of the page in one batch; see the class comment
        graphics.drawManaged(() -> {
            for (int line = scrollLine; line < last; line++) {
                int rowY = top + (line - scrollLine) * LINE_HEIGHT;
                String number = String.valueOf(line + 1);
                Integer marker = markers.get(line + 1);
                graphics.drawString(font, number, gutterRight - PADDING - font.width(number), rowY,
                        marker != null ? (marker | 0xFF000000) : LINE_NUMBER, false);

                String text = lines.get(line);
                byte[] classes = colours.get(line);
                int end = Math.min(text.length(), scrollCol + cols + 1);
                for (int col = scrollCol; col < end; col++) {
                    char c = text.charAt(col);
                    if (c == ' ')
                        continue;
                    FormattedCharSequence glyph = FormattedCharSequence.codepoint(c, Style.EMPTY);
                    int glyphX = left + (col - scrollCol) * CHAR_WIDTH + (CHAR_WIDTH - font.width(glyph) + 1) / 2;
                    graphics.drawString(font, glyph, glyphX, rowY, PlcSyntax.colour(classes[col]), false);
                }
            }
        });

        if (isFocused() && (Util.getMillis() / 500) % 2 == 0 && cursorLine >= scrollLine
                && cursorLine < scrollLine + rows && cursorCol >= scrollCol) {
            int cx = left + (cursorCol - scrollCol) * CHAR_WIDTH;
            int cy = top + (cursorLine - scrollLine) * LINE_HEIGHT;
            graphics.fill(cx, cy - 1, cx + 1, cy + LINE_HEIGHT - 1, CURSOR);
        }
        graphics.disableScissor();

        // A scroll bar, only when there is anywhere to scroll to
        if (lines.size() > rows) {
            int trackTop = y + 1, trackBottom = y + height - 1;
            int track = trackBottom - trackTop;
            int thumb = Math.max(8, track * rows / lines.size());
            int thumbTop = trackTop + (track - thumb) * scrollLine / Math.max(1, lines.size() - rows);
            graphics.fill(x + width - 3, thumbTop, x + width - 1, thumbTop + thumb, 0xFF808080);
        }
    }

    private void drawSelection(GuiGraphics graphics, int line, int rowY, int left, int cols) {
        if (!hasSelection() || line < startLine() || line > endLine())
            return;
        int from = line == startLine() ? startCol() : 0;
        int to = line == endLine() ? endCol() : lines.get(line).length() + 1;
        from = Math.max(from - scrollCol, 0);
        to = Math.min(to - scrollCol, cols + 1);
        if (to <= from)
            return;
        graphics.fill(left + from * CHAR_WIDTH, rowY - 1, left + to * CHAR_WIDTH, rowY + LINE_HEIGHT - 1, SELECTION);
    }

    private void recolour() {
        colours.clear();
        boolean inComment = false;
        for (String line : lines) {
            byte[] classes = new byte[line.length()];
            inComment = PlcSyntax.classify(line, classes, inComment);
            colours.add(classes);
        }
        coloursStale = false;
    }

    @Override
    protected void updateWidgetNarration(NarrationElementOutput output) {
        output.add(NarratedElementType.TITLE, getMessage());
    }

    /** The highlighter's own, small, line-at-a-time lexer for the colours above. */
    static final class PlcSyntax {

        static final byte PLAIN = 0, KEYWORD = 1, TYPE = 2, FUNCTION = 3, CONSTANT = 4, NUMBER = 5, STRING = 6,
                COMMENT = 7, PREPROCESSOR = 8;

        private static final int[] PALETTE = {
                0xFFD4D4D4, 0xFF569CD6, 0xFF4EC9B0, 0xFFDCDCAA, 0xFF4FC1FF, 0xFFB5CEA8, 0xFFCE9178, 0xFF6A9955,
                0xFFC586C0 };

        private static final Set<String> KEYWORDS = Set.of("if", "else", "for", "while", "do", "switch", "case",
                "default", "break", "continue", "return", "goto", "sizeof", "struct", "union", "enum", "class",
                "typedef", "using", "const", "volatile", "static", "extern", "register", "inline", "constexpr", "new",
                "delete", "true", "false", "nullptr", "NULL", "this", "auto", "operator", "template", "typename",
                "namespace", "public", "private", "protected", "static_cast", "reinterpret_cast", "const_cast",
                "PROGMEM");

        private static final Set<String> TYPES = Set.of("void", "bool", "boolean", "char", "short", "int", "long",
                "float", "double", "signed", "unsigned", "byte", "word", "size_t", "String", "int8_t", "uint8_t",
                "int16_t", "uint16_t", "int32_t", "uint32_t", "int64_t", "uint64_t");

        private static final Set<String> FUNCTIONS = Set.of("setup", "loop", "pinMode", "digitalWrite",
                "digitalRead", "digitalToggle", "analogRead", "analogWrite", "analogReadResolution",
                "analogWriteResolution", "analogWriteFrequency", "analogReference", "delay", "delayMicroseconds",
                "millis", "micros", "attachInterrupt", "detachInterrupt", "digitalPinToInterrupt", "interrupts",
                "noInterrupts", "tone", "noTone", "pulseIn", "pulseInLong", "shiftOut", "shiftIn", "map", "constrain",
                "min", "max", "abs", "random", "randomSeed", "bitRead", "bitWrite", "bitSet", "bitClear", "bit",
                "lowByte", "highByte", "F");

        private static final Set<String> CONSTANTS = Set.of("HIGH", "LOW", "INPUT", "OUTPUT", "INPUT_PULLUP",
                "INPUT_PULLDOWN", "INPUT_ANALOG", "OUTPUT_OPEN_DRAIN", "LED_BUILTIN", "RISING", "FALLING", "CHANGE",
                "LSBFIRST", "MSBFIRST", "DI0", "DI1", "DI2", "DI3", "DI4", "DI5", "DI6", "DI7", "DO0", "DO1", "DO2",
                "DO3", "DO4", "DO5", "DO6", "DO7", "STLED", "PI", "HALF_PI", "TWO_PI", "DEG_TO_RAD", "RAD_TO_DEG",
                "EULER", "F_CPU");

        private PlcSyntax() {}

        static int colour(byte cls) {
            return PALETTE[cls];
        }

        /**
         * Fills one class per character of {@code line}.
         *
         * @param inComment whether the line starts inside a block comment
         * @return whether it ends inside one
         */
        static boolean classify(String line, byte[] out, boolean inComment) {
            int n = line.length();
            int i = 0;
            if (!inComment && line.stripLeading().startsWith("#")) {
                int hash = line.indexOf('#');
                int end = hash + 1;
                while (end < n && Character.isLetter(line.charAt(end)))
                    end++;
                fill(out, hash, end, PREPROCESSOR);
                i = end;
            }
            while (i < n) {
                char c = line.charAt(i);
                if (inComment) {
                    int close = line.indexOf("*/", i);
                    int end = close < 0 ? n : close + 2;
                    fill(out, i, end, COMMENT);
                    i = end;
                    inComment = close < 0;
                } else if (c == '/' && i + 1 < n && line.charAt(i + 1) == '/') {
                    fill(out, i, n, COMMENT);
                    i = n;
                } else if (c == '/' && i + 1 < n && line.charAt(i + 1) == '*') {
                    fill(out, i, i + 2, COMMENT);
                    i += 2;
                    inComment = true;
                } else if (c == '"' || c == '\'') {
                    int end = i + 1;
                    while (end < n && line.charAt(end) != c)
                        end += line.charAt(end) == '\\' ? 2 : 1;
                    end = Math.min(n, end + 1);
                    fill(out, i, end, STRING);
                    i = end;
                } else if (Character.isDigit(c)) {
                    int end = i + 1;
                    while (end < n && (Character.isLetterOrDigit(line.charAt(end)) || line.charAt(end) == '.'))
                        end++;
                    fill(out, i, end, NUMBER);
                    i = end;
                } else if (Character.isLetter(c) || c == '_') {
                    int end = i + 1;
                    while (end < n && isWord(line.charAt(end)))
                        end++;
                    String word = line.substring(i, end);
                    byte cls = KEYWORDS.contains(word) ? KEYWORD
                            : TYPES.contains(word) ? TYPE
                            : CONSTANTS.contains(word) ? CONSTANT
                            : FUNCTIONS.contains(word) ? FUNCTION
                            : PLAIN;
                    fill(out, i, end, cls);
                    i = end;
                } else {
                    i++;
                }
            }
            return inComment;
        }

        private static void fill(byte[] out, int from, int to, byte cls) {
            for (int i = Math.max(0, from); i < Math.min(out.length, to); i++)
                out[i] = cls;
        }
    }
}
