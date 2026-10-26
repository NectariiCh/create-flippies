package com.nectariich.create_flippies.content.display.link;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.ArrayList;
import java.util.List;

import com.nectariich.create_flippies.content.display.element.ElementDisplay;
import com.nectariich.create_flippies.content.display.element.ElementKind;
import com.nectariich.create_flippies.content.display.element.ScreenElements;
import com.nectariich.create_flippies.content.display.element.TestElements;
import com.nectariich.create_flippies.content.display.element.Viewport;
import com.nectariich.create_flippies.foundation.control.LinkDrivenControl;

import net.minecraft.network.chat.Component;

import org.junit.jupiter.api.Test;

/**
 * Where a link's lines land on a row of link-driven controls: labels and gauges spill
 * row by row exactly as before, an element display fills its lettering elements from
 * the chosen one before spilling on, a column of one-line displays still takes one line
 * each, and a link whose element has gone writes nothing and is told the fallback size.
 */
class LinkRoutingTest {

    private static final Viewport GLASS = Viewport.ofPixels(40, 8);

    @Test
    void labelsSpillRowByRowAsBefore() {
        Plain a = new Plain(8), b = new Plain(20), c = new Plain(5);
        List<LinkDrivenControl> rows = List.of(a, b, c);

        LinkRouting.route(rows, 1, List.of("one", "two", "three"));
        assertEquals(List.of(), a.lines);
        assertEquals(List.of("one"), b.lines);
        assertEquals(List.of("two"), c.lines);

        // Every row, as wide as the widest, wherever the link is aimed
        for (int line = 0; line < 3; line++)
            assertEquals(new LinkRouting.Stats(3, 20), LinkRouting.stats(rows, line));
    }

    @Test
    void elementDisplayFillsFromTheChosenElementThenSpills() {
        Screen screen = new Screen();
        TestElements.add(screen.elements, ElementKind.TEXT, GLASS); // 0
        TestElements.add(screen.elements, ElementKind.FILL, GLASS); // 1, not lettering: skipped
        TestElements.add(screen.elements, ElementKind.TEXT, GLASS); // 2
        TestElements.add(screen.elements, ElementKind.TEXT, GLASS); // 3
        // Drawing order is not filling order
        screen.elements.zTo(3, 0);
        Plain label = new Plain(8);
        List<LinkDrivenControl> rows = List.of(screen, label);

        int line = LinkAddress.pack(0, 2, screen.elements.generation(2));
        LinkRouting.route(rows, line, List.of("a", "b", "c", "d"));
        assertEquals("", screen.elements.get(0).text());
        assertEquals("a", screen.elements.get(2).text());
        assertEquals("b", screen.elements.get(3).text());
        assertEquals(List.of("c"), label.lines);
        assertEquals(1, screen.changes, "one sync for the whole display");

        LinkRouting.Stats stats = LinkRouting.stats(rows, line);
        assertEquals(3, stats.rows());
        assertEquals(screen.elements.get(2).columns(), stats.columns());

        // The same text again changes nothing and costs no sync
        LinkRouting.route(rows, line, List.of("a", "b", "c"));
        assertEquals(1, screen.changes);
    }

    @Test
    void barsJoinTheSequenceAndCanBeChosen() {
        Screen screen = new Screen();
        TestElements.add(screen.elements, ElementKind.TEXT, GLASS); // 0
        TestElements.add(screen.elements, ElementKind.BAR, GLASS); // 1
        TestElements.add(screen.elements, ElementKind.TEXT, GLASS); // 2
        List<LinkDrivenControl> rows = List.of(screen);

        LinkRouting.route(rows, LinkAddress.pack(0, 0, 0), List.of("Pressure", "75 bar", "ok"));
        assertEquals("Pressure", screen.elements.get(0).text());
        assertEquals(75, screen.elements.get(1).value());
        assertEquals("ok", screen.elements.get(2).text());

        int fromBar = LinkAddress.pack(0, 1, screen.elements.generation(1));
        assertEquals(2, LinkRouting.stats(rows, fromBar).rows());
        LinkRouting.route(rows, fromBar, List.of("80", "high"));
        assertEquals(80, screen.elements.get(1).value());
        assertEquals("high", screen.elements.get(2).text());
    }

    @Test
    void aColumnOfOneLineDisplaysStillTakesOneLineEach() {
        List<Screen> displays = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            Screen display = new Screen();
            TestElements.add(display.elements, ElementKind.TEXT, GLASS);
            displays.add(display);
        }
        List<LinkDrivenControl> rows = List.copyOf(displays);

        // A link saved before elements: a plain row number
        LinkRouting.route(rows, 0, List.of("1", "2", "3", "4", "5"));
        for (int i = 0; i < 4; i++)
            assertEquals(String.valueOf(i + 1), displays.get(i).elements.get(0).text());
        assertEquals(4, LinkRouting.stats(rows, 0).rows());
        assertEquals(2, LinkRouting.stats(rows, 2).rows());
    }

    @Test
    void aFollowingDisplayTakesOneLineInItsFirstLetteringElement() {
        Plain label = new Plain(8);
        Screen screen = new Screen();
        TestElements.add(screen.elements, ElementKind.FILL, GLASS); // 0
        TestElements.add(screen.elements, ElementKind.TEXT, GLASS); // 1
        TestElements.add(screen.elements, ElementKind.TEXT, GLASS); // 2
        Screen shapes = new Screen();
        TestElements.add(shapes.elements, ElementKind.FRAME, GLASS);
        Plain last = new Plain(8);
        List<LinkDrivenControl> rows = List.of(label, screen, shapes, last);

        LinkRouting.route(rows, 0, List.of("a", "b", "c", "d"));
        assertEquals(List.of("a"), label.lines);
        assertEquals("b", screen.elements.get(1).text());
        assertEquals("", screen.elements.get(2).text());
        // A display with no lettering swallows its line, so the rows below keep theirs
        assertEquals(List.of("d"), last.lines);
    }

    @Test
    void aStaleElementWritesNothing() {
        Screen screen = new Screen();
        TestElements.add(screen.elements, ElementKind.TEXT, GLASS); // 0
        TestElements.add(screen.elements, ElementKind.TEXT, GLASS); // 1
        TestElements.add(screen.elements, ElementKind.FILL, GLASS); // 2
        Plain label = new Plain(8);
        List<LinkDrivenControl> rows = List.of(screen, label);

        int stale = LinkAddress.pack(0, 1, 0);
        screen.elements.remove(1);
        TestElements.add(screen.elements, ElementKind.TEXT, GLASS); // slot 1 again, generation 1
        LinkRouting.route(rows, stale, List.of("a", "b", "c"));
        assertEquals("", screen.elements.get(0).text());
        assertEquals("", screen.elements.get(1).text());
        assertEquals(List.of(), label.lines);
        assertEquals(0, screen.changes);
        assertEquals(new LinkRouting.Stats(1, 16), LinkRouting.stats(rows, stale));
        assertNull(LinkRouting.fromChosen(screen, stale));

        // A shape is not something a link can write to either
        int shape = LinkAddress.pack(0, 2, 0);
        LinkRouting.route(rows, shape, List.of("a"));
        assertEquals(0, screen.changes);
        assertEquals(new LinkRouting.Stats(1, 16), LinkRouting.stats(rows, shape));
    }

    @Test
    void aMissingRowOrElementFallsBackToOneBySixteen() {
        Screen screen = new Screen();
        TestElements.add(screen.elements, ElementKind.TEXT, GLASS);
        List<LinkDrivenControl> rows = List.of(screen);
        LinkRouting.Stats fallback = new LinkRouting.Stats(LinkRouting.FALLBACK_ROWS, LinkRouting.FALLBACK_COLUMNS);

        assertEquals(fallback, LinkRouting.stats(List.of(), 0));
        assertEquals(fallback, LinkRouting.stats(rows, 3));
        assertEquals(fallback, LinkRouting.stats(rows, LinkAddress.pack(0, 5, 0)));

        LinkRouting.route(rows, 3, List.of("a"));
        LinkRouting.route(List.of(), 0, List.of("a"));
        assertEquals("", screen.elements.get(0).text());
    }

    /** A label or gauge: one line, whatever the link says about elements. */
    private static final class Plain implements LinkDrivenControl {
        final List<String> lines = new ArrayList<>();
        final int columns;

        Plain(int columns) {
            this.columns = columns;
        }

        @Override
        public void acceptLine(String text) {
            lines.add(text);
        }

        @Override
        public int linkColumns() {
            return columns;
        }

        @Override
        public Component linkRowName() {
            return Component.literal("Label");
        }
    }

    /** A display showing elements, counting its syncs. */
    private static final class Screen implements LinkDrivenControl, ElementDisplay {
        final ScreenElements elements = new ScreenElements();
        int changes;

        @Override
        public void acceptLine(String text) {
            acceptLine(0, 0, text);
        }

        @Override
        public int linkColumns() {
            return 1;
        }

        @Override
        public Component linkRowName() {
            return Component.literal("Display");
        }

        @Override
        public ScreenElements elements() {
            return elements;
        }

        @Override
        public Viewport viewport() {
            return GLASS;
        }

        @Override
        public void elementsChanged() {
            changes++;
        }

        @Override
        public boolean isPowered() {
            return true;
        }

        @Override
        public boolean isBacklit() {
            return false;
        }
    }
}
