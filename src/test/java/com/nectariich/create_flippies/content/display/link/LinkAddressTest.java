package com.nectariich.create_flippies.content.display.link;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * A link's packed target: an old plain line number still means that row's first
 * element, every field round-trips to the edge of its bits without touching the
 * others, and the packed value is never negative.
 */
class LinkAddressTest {

    @Test
    void anOldLineIsThatRowsFirstElement() {
        for (int line : new int[] { 0, 1, 3, 15, 200, LinkAddress.MAX_ROW }) {
            assertEquals(line, LinkAddress.row(line));
            assertEquals(0, LinkAddress.element(line));
            assertEquals(0, LinkAddress.generation(line));
            assertEquals(line, LinkAddress.pack(line, 0, 0));
        }
    }

    @Test
    void eachFieldRoundTripsAtItsLimits() {
        int[] rows = { 0, 1, 0x7FFF, LinkAddress.MAX_ROW };
        int[] sevens = { 0, 1, 63, 127 };
        for (int row : rows)
            for (int element : sevens)
                for (int generation : sevens) {
                    int line = LinkAddress.pack(row, element, generation);
                    assertEquals(row, LinkAddress.row(line));
                    assertEquals(element, LinkAddress.element(line));
                    assertEquals(generation, LinkAddress.generation(line));
                    assertTrue(line >= 0, "bit 31 stays clear");
                }
    }

    @Test
    void outOfRangeFieldsAreClampedNotSpilled() {
        int line = LinkAddress.pack(LinkAddress.MAX_ROW + 5, 128, 200);
        assertEquals(LinkAddress.MAX_ROW, LinkAddress.row(line));
        assertEquals(127, LinkAddress.element(line));
        assertEquals(127, LinkAddress.generation(line));
        assertTrue(line >= 0);
        assertEquals(0, LinkAddress.pack(-1, -1, -1));
        assertEquals(0, LinkAddress.pack(Integer.MIN_VALUE, 0, 0));

        // Whatever a client sends decodes to fields in range
        assertEquals(LinkAddress.MAX_ROW, LinkAddress.row(-1));
        assertEquals(127, LinkAddress.element(-1));
        assertEquals(127, LinkAddress.generation(-1));
    }
}
