package com.nectariich.create_flippies.content.display.link;

import java.util.ArrayList;
import java.util.List;

import com.nectariich.create_flippies.content.display.element.ElementDisplay;
import com.nectariich.create_flippies.content.display.element.ScreenElement;
import com.nectariich.create_flippies.content.display.element.ScreenElements;
import com.nectariich.create_flippies.foundation.control.HostElementBudget;
import com.nectariich.create_flippies.foundation.control.LinkDrivenControl;

/**
 * Where the lines of one display link transfer land, for any target made of rows of
 * {@link LinkDrivenControl}s: a host block's controls in reading order, or the one
 * display of a block-form alphanumeric display.
 *
 * Kept apart from the targets, and free of anything but the controls themselves, so
 * that the rule can be tested without a level or a link. The rule:
 * <ul>
 * <li>The link's {@code targetLine} is a {@link LinkAddress}: a row, and on a row
 * that shows elements, the lettering element or bar the link starts at and that slot's
 * generation.</li>
 * <li>On a plain row (a label, a gauge) consecutive lines fill consecutive rows, as
 * they always have: what a sign does with its four rows.</li>
 * <li>On an element display, consecutive lines first fill its lettering elements and
 * bars in id order from the chosen one, and only then carry on into the following rows. Id
 * order rather than drawing order, so restacking a screen never reroutes a link.</li>
 * <li>A following row takes one line however many elements it has, and on an element
 * display that line goes to its first lettering element or bar. One line a row is what
 * every row took before elements existed, so the lines a label further down the
 * panel receives do not depend on how many elements the display above it holds.</li>
 * <li>A link whose chosen element is gone, or whose slot has since been freed and
 * taken again (the generation no longer matches), writes nothing at all. The link is
 * broken until someone picks again, and spraying its text over whatever now sits in
 * that slot or the rows below would be worse than a blank.</li>
 * </ul>
 * {@link #stats} answers with exactly the number of lines {@link #route} can place,
 * which is the {@code maxRows} a source is told, so a multi-line source never builds
 * lines that would be thrown away.
 */
public final class LinkRouting {

    /**
     * What a link is told when the row or element it names is not there: one line of a
     * width that suits most sources, so its editor still offers something sensible.
     * The same figure an empty panel always reported.
     */
    public static final int FALLBACK_ROWS = 1, FALLBACK_COLUMNS = 16;

    /** What {@link #stats} answers: how many lines will land and how wide to make them. */
    public record Stats(int rows, int columns) {}

    private LinkRouting() {}

    /** Delivers {@code text} for a link whose packed target is {@code line}. */
    public static void route(List<? extends LinkDrivenControl> rows, int line, List<String> text) {
        int row = LinkAddress.row(line);
        if (row >= rows.size() || text.isEmpty())
            return;
        int next = 0;
        LinkDrivenControl anchor = rows.get(row);
        if (anchor.isElementDisplay()) {
            ElementDisplay display = (ElementDisplay) anchor;
            List<ScreenElement> targets = fromChosen(display, line);
            if (targets == null)
                return;
            ScreenElements elements = display.elements();
            int textCap = HostElementBudget.textCap(display);
            boolean changed = false;
            for (ScreenElement target : targets) {
                if (next >= text.size())
                    break;
                changed |= elements.acceptLinked(target.id(), elements.generation(target.id()), text.get(next++),
                        textCap);
            }
            // One save and sync for the whole display, however many elements moved
            if (changed)
                display.elementsChanged();
        } else {
            anchor.acceptLine(text.get(next++));
        }
        for (int index = row + 1; index < rows.size() && next < text.size(); index++)
            acceptFirst(rows.get(index), text.get(next++));
    }

    /**
     * How many lines a link aimed at {@code line} can place and how wide they may be.
     *
     * On an element display: the lettering elements from the chosen one, then one line
     * per following row, as wide as the chosen element. On a plain row: what the host
     * always reported, every row and the most generous of their widths, so links that
     * have only ever pointed at labels and gauges see no change at all. A row or element
     * that is not there gets {@link #FALLBACK_ROWS} by {@link #FALLBACK_COLUMNS}.
     */
    public static Stats stats(List<? extends LinkDrivenControl> rows, int line) {
        int row = LinkAddress.row(line);
        if (row >= rows.size())
            return fallback();
        LinkDrivenControl anchor = rows.get(row);
        if (!anchor.isElementDisplay())
            return new Stats(Math.max(1, rows.size()), widest(rows));
        List<ScreenElement> targets = fromChosen((ElementDisplay) anchor, line);
        if (targets == null)
            return fallback();
        int columns = anchor.linkColumns(LinkAddress.element(line));
        return new Stats(targets.size() + rows.size() - row - 1, Math.max(1, columns));
    }

    /**
     * The elements a link fills on the display it is aimed at (lettering and bars), in
     * id order from the chosen one; null when the chosen one is not such an element of
     * the generation the link names, which is a broken link.
     */
    public static List<ScreenElement> fromChosen(ElementDisplay display, int line) {
        ScreenElements elements = display.elements();
        int id = LinkAddress.element(line);
        ScreenElement chosen = elements.get(id);
        if (chosen == null || !chosen.kind().linked() || elements.generation(id) != LinkAddress.generation(line))
            return null;
        List<ScreenElement> targets = new ArrayList<>();
        for (ScreenElement text : elements.linkTargets())
            if (text.id() >= id)
                targets.add(text);
        return targets;
    }

    /**
     * A following row's one line: a plain row takes it as it always has, an element
     * display gives it to its first lettering element or bar, and one with none swallows it,
     * so the rows below still get the lines they would have had.
     */
    private static void acceptFirst(LinkDrivenControl row, String text) {
        if (!row.isElementDisplay()) {
            row.acceptLine(text);
            return;
        }
        List<ScreenElement> texts = ((ElementDisplay) row).elements().linkTargets();
        if (texts.isEmpty())
            return;
        int id = texts.get(0).id();
        row.acceptLine(id, ((ElementDisplay) row).elements().generation(id), text);
    }

    /**
     * The most generous width of any row, which is what a host told every link before
     * a link could pick an element; see {@code ControlHostDisplayTarget}.
     */
    private static int widest(List<? extends LinkDrivenControl> rows) {
        int columns = 0;
        for (LinkDrivenControl row : rows)
            columns = Math.max(columns, row.linkColumns());
        return Math.max(1, columns);
    }

    private static Stats fallback() {
        return new Stats(FALLBACK_ROWS, FALLBACK_COLUMNS);
    }
}
