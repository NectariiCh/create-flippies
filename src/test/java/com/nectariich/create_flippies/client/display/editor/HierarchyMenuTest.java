package com.nectariich.create_flippies.client.display.editor;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;

import org.junit.jupiter.api.Test;

/**
 * Dragging rows in the hierarchy: the rows are front first, the order handed to the
 * session is back to front, and the moved rows land in the gap keeping their order.
 */
class HierarchyMenuTest {

    // Front first: 4 is drawn over everything, 1 under everything
    private static final List<Integer> ROWS = List.of(4, 3, 2, 1);

    @Test
    void oneRowToTheTop() {
        assertEquals(List.of(2, 3, 4, 1), HierarchyMenu.restackOrder(ROWS, List.of(1), 0));
    }

    @Test
    void oneRowToTheBottom() {
        assertEquals(List.of(4, 1, 2, 3), HierarchyMenu.restackOrder(ROWS, List.of(4), 4));
    }

    @Test
    void severalRowsKeepTheirOrder() {
        // Rows 4 and 2, given out of order, dropped below 1
        assertEquals(List.of(2, 4, 1, 3), HierarchyMenu.restackOrder(ROWS, List.of(2, 4), 4));
        // The same into the gap between 3 and 2
        assertEquals(List.of(1, 2, 4, 3), HierarchyMenu.restackOrder(ROWS, List.of(2, 4), 2));
    }

    @Test
    void droppingWhereItWasIsNothing() {
        assertEquals(List.of(), HierarchyMenu.restackOrder(ROWS, List.of(3), 1));
        assertEquals(List.of(), HierarchyMenu.restackOrder(ROWS, List.of(3), 2));
        assertEquals(List.of(), HierarchyMenu.restackOrder(ROWS, List.of(), 0));
    }
}
