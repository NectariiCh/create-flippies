package com.nectariich.create_flippies.client.display.editor;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

import com.nectariich.create_flippies.client.decal.ClientDecals;
import com.nectariich.create_flippies.content.decal.DecalArt;
import com.nectariich.create_flippies.content.display.element.ElementKind;
import com.nectariich.create_flippies.content.display.element.ScreenElement;
import com.nectariich.create_flippies.content.display.element.ScreenElements;
import com.nectariich.create_flippies.content.display.element.Viewport;

import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

/**
 * What the keyboard, the context menus and the hierarchy's buttons do to the selection:
 * cut, copy, paste, duplicate, delete, the four restacking commands, nudging, and
 * adding an element of a kind at a point. Every command is one undo step. The clipboard
 * is the system's, as text ({@link ElementClipboard}), so a layout can be copied out of
 * the game and back.
 *
 * Locked elements are handled like any other here: a lock only keeps an element from
 * being picked on the glass, and one picked in the hierarchy copies, deletes and
 * restacks as usual.
 */
public final class EditorCommands {

    /** The system clipboard as text; a seam so tests run without a game. */
    interface Clipboard {
        String get();

        void set(String text);
    }

    /** Where the commands read and write the clipboard. Tests swap it. */
    static Clipboard clipboard = new Clipboard() {
        @Override
        public String get() {
            return Minecraft.getInstance().keyboardHandler.getClipboard();
        }

        @Override
        public void set(String text) {
            Minecraft.getInstance().keyboardHandler.setClipboard(text);
        }
    };

    /** The pictures this client has, by hash, for an SVG's aspect. Tests swap it. */
    static Function<String, DecalArt> arts = hash -> ClientDecals.art(hash);

    /** How far a paste onto its own display, and a duplicate, are moved right and down: a pixel. */
    private static final int OFFSET = Viewport.UNITS_PER_PIXEL;

    private final EditorContext context;

    public EditorCommands(EditorContext context) {
        this.context = context;
    }

    public void copy() {
        List<ScreenElement> elements = selectedElements();
        if (!elements.isEmpty())
            clipboard.set(ElementClipboard.write(elements, context.host.sourceKey()));
    }

    public void cut() {
        if (selectedElements().isEmpty())
            return;
        copy();
        delete();
    }

    /**
     * Pastes what the system clipboard holds, if it is elements: in new slots, where
     * they were, or a pixel right and down when going back onto the display they came
     * from; clamped to the viewport; selected afterwards. Refused with a message when it
     * would take the display past its elements.
     */
    public void paste() {
        ElementClipboard.Contents contents = contents();
        if (contents == null || contents.elements().isEmpty())
            return;
        int offset = contents.source().equals(context.host.sourceKey()) ? OFFSET : 0;
        place(contents.elements(), offset);
    }

    public boolean canPaste() {
        ElementClipboard.Contents contents = contents();
        return contents != null && !contents.elements().isEmpty();
    }

    /** Copies of the selection in new slots, a pixel right and down, selected. */
    public void duplicate() {
        List<ScreenElement> elements = selectedElements();
        if (!elements.isEmpty())
            place(elements, OFFSET);
    }

    public void delete() {
        List<Integer> ids = selectedInOrder();
        if (ids.isEmpty())
            return;
        context.session.remove(ids);
        // a slot freed here may be reused by the next paste, which must not start out hidden
        context.hidden.removeAll(ids);
        ids.forEach(context.barPreview::remove);
        context.selection.clear();
    }

    public void bringToFront() {
        List<Integer> order = order();
        List<Integer> selected = selectedInOrder();
        order.removeAll(selected);
        order.addAll(selected);
        context.session.restack(order);
    }

    /** One place toward the front, each selected element. */
    public void raise() {
        context.session.restack(raised(order()));
    }

    public void lower() {
        context.session.restack(lowered(order()));
    }

    public void sendToBack() {
        List<Integer> order = order();
        List<Integer> selected = selectedInOrder();
        order.removeAll(selected);
        order.addAll(0, selected);
        context.session.restack(order);
    }

    /** Moves the selection by (dx, dy) tenths, clamped to the viewport; one step per key press. */
    public void nudge(int dx, int dy) {
        List<ScreenElement> elements = selectedElements();
        if (elements.isEmpty())
            return;
        // the group moves as one: the step is cut so the outermost element stops at the edge,
        // rather than each element being clamped on its own and the group squashing together
        Viewport viewport = context.session.viewport();
        int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE, maxX = Integer.MIN_VALUE, maxY = Integer.MIN_VALUE;
        for (ScreenElement element : elements) {
            minX = Math.min(minX, element.x());
            minY = Math.min(minY, element.y());
            maxX = Math.max(maxX, element.x() + element.w());
            maxY = Math.max(maxY, element.y() + element.h());
        }
        int mx = Math.max(-minX, Math.min(dx, viewport.width() - maxX));
        int my = Math.max(-minY, Math.min(dy, viewport.height() - maxY));
        if (mx == 0 && my == 0)
            return;
        Map<Integer, ScreenElement> moved = new LinkedHashMap<>();
        for (ScreenElement element : elements)
            moved.put(element.id(), element.withBox(element.x() + mx, element.y() + my, element.w(), element.h()));
        // no coalescing key: each key press is its own step
        context.session.edit(moved, null);
    }

    /**
     * A new element of that kind with its default size, centred on the viewport point
     * (vx, vy) in tenths and clamped to the viewport, selected. For an SVG, {@code art}
     * names the picture's hash; ignored otherwise.
     */
    public void addAt(com.nectariich.create_flippies.content.display.element.ElementKind kind, double vx,
            double vy, String art) {
        List<Integer> free = context.session.freeIds(1);
        if (free.isEmpty()) {
            full();
            return;
        }
        int id = free.get(0);
        Viewport viewport = context.session.viewport();
        ScreenElement element = ScreenElement.create(id, kind, viewport);
        int w = element.w(), h = element.h();
        if (kind == ElementKind.SVG && art != null && !art.isEmpty()) {
            DecalArt picture = arts.apply(art);
            if (picture != null && picture.width() > 0 && picture.height() > 0) {
                // the picture's own shape inside the default box, as large as fits
                double aspect = picture.width() / (double) picture.height();
                if (aspect > w / (double) h)
                    h = Math.max(1, (int) Math.round(w / aspect));
                else
                    w = Math.max(1, (int) Math.round(h * aspect));
            }
            element = element.toBuilder().art(art).build();
        }
        int x = (int) Math.round(vx - w / 2.0);
        int y = (int) Math.round(vy - h / 2.0);
        element = element.withBox(x, y, w, h).clampedTo(viewport);
        context.session.put(List.of(element));
        context.selection.only(id);
    }

    /** Whether the selected elements can move toward the front / back at all. */
    public boolean canRaise() {
        List<Integer> order = order();
        return !raised(order).equals(order);
    }

    public boolean canLower() {
        List<Integer> order = order();
        return !lowered(order).equals(order);
    }

    /** The selected ids that still exist, back to front. */
    public List<Integer> selectedInOrder() {
        List<Integer> ids = new ArrayList<>();
        for (ScreenElement element : context.session.shown())
            if (context.selection.contains(element.id()))
                ids.add(element.id());
        return ids;
    }

    // ---- internals ----

    private List<ScreenElement> selectedElements() {
        List<ScreenElement> elements = new ArrayList<>();
        for (ScreenElement element : context.session.shown())
            if (context.selection.contains(element.id()))
                elements.add(element);
        return elements;
    }

    private ElementClipboard.Contents contents() {
        String text;
        try {
            text = clipboard.get();
        } catch (RuntimeException e) {
            return null;
        }
        return ElementClipboard.read(text);
    }

    /** Puts copies of the elements in new slots, moved by {@code offset} tenths each way, and selects them. */
    private void place(List<ScreenElement> elements, int offset) {
        List<Integer> free = context.session.freeIds(elements.size());
        if (free.size() < elements.size()) {
            full();
            return;
        }
        Viewport viewport = context.session.viewport();
        List<ScreenElement> copies = new ArrayList<>();
        for (int i = 0; i < elements.size(); i++) {
            ScreenElement element = elements.get(i);
            copies.add(element.withId(free.get(i))
                    .withBox(element.x() + offset, element.y() + offset, element.w(), element.h())
                    .clampedTo(viewport));
        }
        context.session.put(copies);
        context.selection.set(free);
    }

    private void full() {
        context.status(Component.translatable("gui.create_flippies.display_editor.full", ScreenElements.MAX));
    }

    /** Every element's id, back to front, as a list to rearrange. */
    private List<Integer> order() {
        List<Integer> order = new ArrayList<>();
        for (ScreenElement element : context.session.shown())
            order.add(element.id());
        return order;
    }

    /**
     * Each selected element swapped with the unselected one just in front of it, from the
     * front back, so a block of selected elements moves up a place as one and keeps its
     * order, and one already at the front stays (with whatever is selected right behind it).
     */
    private List<Integer> raised(List<Integer> order) {
        List<Integer> result = new ArrayList<>(order);
        for (int i = result.size() - 2; i >= 0; i--)
            if (context.selection.contains(result.get(i)) && !context.selection.contains(result.get(i + 1)))
                Collections.swap(result, i, i + 1);
        return result;
    }

    /** As {@link #raised}, toward the back. */
    private List<Integer> lowered(List<Integer> order) {
        List<Integer> result = new ArrayList<>(order);
        for (int i = 1; i < result.size(); i++)
            if (context.selection.contains(result.get(i)) && !context.selection.contains(result.get(i - 1)))
                Collections.swap(result, i, i - 1);
        return result;
    }
}
