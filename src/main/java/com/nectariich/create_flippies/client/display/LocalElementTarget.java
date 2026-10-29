package com.nectariich.create_flippies.client.display;

import java.util.function.Supplier;

import com.nectariich.create_flippies.content.display.element.ScreenElement;
import com.nectariich.create_flippies.content.display.element.ScreenElements;
import com.nectariich.create_flippies.content.display.element.Viewport;

/**
 * An {@link ElementEditTarget} the client owns outright: the workbench's authored
 * screen layout, which nothing but this client edits until it is saved into a design.
 *
 * Every edit goes straight into the elements through the same
 * {@link ScreenElements} calls the server makes for a placed display, so the layout
 * is held to the same caps, clamping and sanitising. Fonts are taken whatever they name:
 * the files travel with the design when it is saved, as a design's printed text's do.
 */
public final class LocalElementTarget implements ElementEditTarget {

    private final ScreenElements elements;
    private final Supplier<Viewport> viewport;
    private final Runnable changed;

    /**
     * @param viewport read on every edit, since the workbench's placement can resize it
     * @param changed  told after every edit that changed anything (the workbench re-poses its preview)
     */
    public LocalElementTarget(ScreenElements elements, Supplier<Viewport> viewport, Runnable changed) {
        this.elements = elements;
        this.viewport = viewport;
        this.changed = changed;
    }

    @Override
    public ScreenElements elements() {
        return elements;
    }

    @Override
    public Viewport viewport() {
        return viewport.get();
    }

    @Override
    public void remove(int id) {
        report(elements.remove(id));
    }

    @Override
    public void set(int id, int mask, ScreenElement.Fields values) {
        report(elements.setFields(id, mask, values, viewport(), hash -> true));
    }

    @Override
    public void zTo(int id, int index) {
        report(elements.zTo(id, index));
    }

    @Override
    public void put(ScreenElement element, int index, int generation) {
        report(elements.put(element, index, generation, viewport(), hash -> true, ScreenElements.MAX_TOTAL_TEXT));
    }

    private void report(boolean change) {
        if (change)
            changed.run();
    }
}
