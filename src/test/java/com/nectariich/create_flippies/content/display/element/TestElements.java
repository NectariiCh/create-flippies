package com.nectariich.create_flippies.content.display.element;

import java.util.List;

/**
 * Builds displays for tests the way the editor does: a new element of a kind in the
 * lowest free slot ({@link ScreenElements#freeIds}), put whole in front of everything
 * ({@link ScreenElements#put}) with the slot's own generation.
 */
public final class TestElements {

    private TestElements() {}

    /** A new element of that kind in the lowest free slot, in front; null when every slot is taken. */
    public static ScreenElement add(ScreenElements elements, ElementKind kind, Viewport viewport) {
        List<Integer> free = elements.freeIds(1);
        if (free.isEmpty())
            return null;
        int id = free.get(0);
        elements.put(ScreenElement.create(id, kind, viewport), elements.size(), elements.generation(id), viewport,
                hash -> true, ScreenElements.MAX_TOTAL_TEXT);
        return elements.get(id);
    }
}
