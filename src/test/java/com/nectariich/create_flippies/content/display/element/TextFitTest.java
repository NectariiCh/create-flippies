package com.nectariich.create_flippies.content.display.element;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

/**
 * The layout arithmetic against a fake monospace font, six units an advance and nine
 * a line: fitted lines fill the box the shorter way, fixed sizes are capped to the
 * box and cut, and wrapped text finds the largest size whose lines all fit.
 */
class TextFitTest {

    /** Every character six units wide, lines nine apart, wrapping on character count. */
    private static final TextFit.Measurer MONO = new TextFit.Measurer() {
        @Override
        public double width(String text) {
            return text.length() * 6.0;
        }

        @Override
        public String head(String text, double maxWidth) {
            return text.substring(0, Math.min(text.length(), (int) (maxWidth / 6)));
        }

        @Override
        public List<String> split(String text, double maxWidth) {
            int per = Math.max(1, (int) (maxWidth / 6));
            List<String> lines = new ArrayList<>();
            for (int i = 0; i < text.length(); i += per)
                lines.add(text.substring(i, Math.min(text.length(), i + per)));
            return lines;
        }

        @Override
        public double lineHeight() {
            return 9;
        }
    };

    @Test
    void aFittedLineIsAsLargeAsTheShorterWayAllows() {
        // 4 characters = 24 units. A 120-tenth box holds them up to size 120 * 8 / 24 = 40
        TextFit.Layout wide = TextFit.layout(MONO, "1234", ScreenElement.FIT, 120, 60, false);
        assertEquals(40, wide.size());
        assertEquals(List.of("1234"), wide.lines());

        // A short box caps it at its own height
        TextFit.Layout low = TextFit.layout(MONO, "1234", ScreenElement.FIT, 120, 25, false);
        assertEquals(25, low.size());

        // Empty text fills the height and draws nothing
        TextFit.Layout empty = TextFit.layout(MONO, "", ScreenElement.FIT, 120, 60, false);
        assertEquals(60, empty.size());
        assertTrue(empty.lines().isEmpty());
    }

    @Test
    void aFixedSizeIsCappedToTheBoxAndCut() {
        // Size 20 gives 120 * 8 / 20 = 48 units of room: eight characters
        TextFit.Layout cut = TextFit.layout(MONO, "ABCDEFGHIJKL", 20, 120, 60, false);
        assertEquals(20, cut.size());
        assertEquals(List.of("ABCDEFGH"), cut.lines());

        TextFit.Layout capped = TextFit.layout(MONO, "AB", 500, 120, 60, false);
        assertEquals(60, capped.size(), "never taller than the box");
    }

    @Test
    void wrappedTextFindsTheLargestSizeThatHoldsEveryLine() {
        String text = "abcdefghijkl";
        TextFit.Layout fit = TextFit.layout(MONO, text, ScreenElement.FIT, 120, 120, true);
        int size = fit.size();
        // Fits at the chosen size: every character placed, no line too long, rows inside the box
        assertEquals(text, String.join("", fit.lines()));
        double room = TextFit.room(120, size);
        for (String line : fit.lines())
            assertTrue(MONO.width(line) <= room);
        assertTrue(((fit.lines().size() - 1) * 9 + 8) * size / 8.0 <= 120);
        // And not at one size larger
        List<String> larger = MONO.split(text, TextFit.room(120, size + 1));
        assertTrue(((larger.size() - 1) * 9 + 8) * (size + 1) / 8.0 > 120);
    }

    @Test
    void wrappedLinesPastTheBottomAreDropped() {
        // Size 40: 24 units of room (four characters) and rows 40 tenths tall, 45 apart:
        // a 100-tenth box shows two of the three lines
        TextFit.Layout layout = TextFit.layout(MONO, "abcdefghijkl", 40, 120, 100, true);
        assertEquals(40, layout.size());
        assertEquals(List.of("abcd", "efgh"), layout.lines());
        assertEquals(2, TextFit.linesThatFit(9, 100, 40));
        assertEquals(1, TextFit.linesThatFit(9, 10, 40), "always at least one");
    }
}
