package com.nectariich.create_flippies.content.display.element;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.function.Predicate;

import com.nectariich.create_flippies.content.display.DisplayForm;
import com.nectariich.create_flippies.content.display.DisplayPrint;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;

/**
 * Everything one display shows: up to {@link #MAX} elements in drawing order, back to
 * front, each in a stable slot, plus a generation per slot.
 *
 * Shared by every display that draws elements (both forms of the alphanumeric
 * display and the designed screen) and by the workbench's authored screen layout,
 * which is copied into a screen when one is placed. Pure common code: the server owns
 * the edits, the client reads the same tags in place on every sync, and nothing here
 * measures a font (see {@link TextFit} for where that is done).
 *
 * <h2>Ids and generations</h2>
 *
 * An element's {@link ScreenElement#id} is the slot a display link addresses, so it
 * never changes while the element exists, whatever happens to the drawing order.
 * The editor fills the lowest free slots ({@link #freeIds}), which means a deleted
 * element's slot is soon reused, and a link that pointed at the old element must not quietly start
 * writing into the new one. Each slot therefore has a seven-bit generation, bumped
 * when its element is removed and packed into the link beside the id (see
 * {@link com.nectariich.create_flippies.content.display.link.LinkAddress}); a link
 * whose generation does not match writes nothing. Seven bits wrap after 128 deletes
 * of the same slot, which is a link that has been dead a very long time.
 *
 * <h2>The text budget</h2>
 *
 * Each element holds at most {@link ScreenElement#MAX_TEXT} characters and the whole
 * display at most {@link #MAX_TOTAL_TEXT}. The second cap is what keeps a display's
 * sync tag bounded: every edit and every link write goes through here, and text that
 * would take the display over is cut to what is left rather than refused, the way a
 * single element's text is cut to its own cap. A display mounted in a host may be
 * held to less than its own cap, since all the displays in one host share a budget
 * too ({@code HostElementBudget}); the edits that can grow text take that lower cap
 * as {@code textCap}, and the overloads without it use {@link #MAX_TOTAL_TEXT}.
 *
 * <h2>Format</h2>
 *
 * {@code Elements} is always written, even empty, so a display whose owner deleted
 * every element reloads empty instead of being migrated back to one. {@code Gen} is
 * written only once any generation has moved. Neither the legacy keys nor anything
 * else is written, which is what makes {@link #read}'s migration run once.
 */
public final class ScreenElements {

    /** Elements per display. */
    public static final int MAX = ScreenElement.MAX_ID;

    /** Characters summed over every element of one display. */
    public static final int MAX_TOTAL_TEXT = 4096;

    /** Generations are seven bits, the width a link has room for. */
    public static final int GENERATION_MASK = 0x7F;

    private final List<ScreenElement> order = new ArrayList<>();
    private final byte[] generations = new byte[MAX];

    /** A display with nothing on it. */
    public ScreenElements() {}

    /**
     * What a display ships with: one piece of lettering over the whole viewport,
     * fitted, in the default font, white. The OLEDs do not start here: they start
     * from an empty single-line save (see {@link #read}), which keeps their per-form
     * default height.
     */
    public static ScreenElements defaults(Viewport viewport) {
        ScreenElements elements = new ScreenElements();
        elements.order.add(ScreenElement.create(0, ElementKind.TEXT, viewport));
        return elements;
    }

    public int size() {
        return order.size();
    }

    public boolean isEmpty() {
        return order.isEmpty();
    }

    /** The element in that slot, or null when the slot is free. */
    public ScreenElement get(int id) {
        int index = indexOf(id);
        return index < 0 ? null : order.get(index);
    }

    /** Where in the drawing order that element is, 0 at the back; -1 when it does not exist. */
    public int indexOf(int id) {
        for (int i = 0; i < order.size(); i++)
            if (order.get(i).id() == id)
                return i;
        return -1;
    }

    /** That slot's generation, whether or not anything is in it now. */
    public int generation(int id) {
        return id >= 0 && id < MAX ? generations[id] & GENERATION_MASK : 0;
    }

    /** The elements in drawing order, back to front, as an immutable list a renderer can keep. */
    public List<ScreenElement> snapshot() {
        return List.copyOf(order);
    }

    /**
     * The lettering elements in id order, which is the order a multi-line display link
     * fills them in: stable however the drawing order is shuffled.
     */
    public List<ScreenElement> texts() {
        return order.stream().filter(element -> element.kind() == ElementKind.TEXT)
                .sorted(Comparator.comparingInt(ScreenElement::id)).toList();
    }

    /**
     * The elements a display link writes to, lettering and bars, in id order: the
     * order a multi-line link fills them in, stable however the drawing order is
     * shuffled.
     */
    public List<ScreenElement> linkTargets() {
        return order.stream().filter(element -> element.kind().linked())
                .sorted(Comparator.comparingInt(ScreenElement::id)).toList();
    }

    /** The slots nothing is in, lowest first, at most {@code count} of them. */
    public List<Integer> freeIds(int count) {
        boolean[] used = new boolean[MAX];
        for (ScreenElement element : order)
            used[element.id()] = true;
        List<Integer> free = new ArrayList<>();
        for (int id = 0; id < MAX && free.size() < count; id++)
            if (!used[id])
                free.add(id);
        return free;
    }

    /**
     * Puts an element whole into its own slot, at {@code index} in the drawing order
     * (clamped to the list), with that slot's generation set to {@code generation}:
     * how the editor places what it draws or pastes (in slots it chose from what it
     * sees), and how an undo brings back a deleted element exactly, so that display
     * links made against it work again. Refused when the slot is taken, which is
     * another player having got there first, and when the element names a font or a
     * picture the store does not hold ({@code stored}, by hash). The box is fitted to
     * the viewport and the text to the budget.
     */
    public boolean put(ScreenElement element, int index, int generation, Viewport viewport,
            Predicate<String> stored, int textCap) {
        if (element == null || get(element.id()) != null)
            return false;
        String font = ScreenElement.ttfHash(element.font());
        if (font != null && !stored.test(font) || !element.art().isEmpty() && !stored.test(element.art()))
            return false;
        ScreenElement placed = element.clampedTo(viewport);
        String text = ScreenElement.cut(placed.text(), budget(-1, textCap));
        if (!text.equals(placed.text()))
            placed = placed.withText(text);
        order.add(Math.max(0, Math.min(index, order.size())), placed);
        generations[placed.id()] = (byte) (generation & GENERATION_MASK);
        return true;
    }

    /**
     * Frees that slot and moves its generation on, so whatever link pointed at the
     * element now points at nothing. False when there was no such element.
     */
    public boolean remove(int id) {
        int index = indexOf(id);
        if (index < 0)
            return false;
        order.remove(index);
        generations[id] = (byte) ((generations[id] + 1) & GENERATION_MASK);
        return true;
    }

    /**
     * Sets the fields named in {@code mask} on that element from {@code values} (raw,
     * as decoded; see {@link ScreenElement.Fields}), leaving the rest as they are,
     * then fits the box to the viewport and the text to the budget. A TTF font or a
     * picture is taken only when {@code storedFont} says its file is in the store
     * (tested with the hash); otherwise the element keeps the one it had, because a
     * file the server cannot hand to other players would draw as nothing. Returns
     * whether anything changed.
     */
    public boolean setFields(int id, int mask, ScreenElement.Fields values, Viewport viewport,
            Predicate<String> storedFont) {
        return setFields(id, mask, values, viewport, storedFont, MAX_TOTAL_TEXT);
    }

    /**
     * {@link #setFields(int, int, ScreenElement.Fields, Viewport, Predicate)}, with the
     * text held to {@code textCap} in all.
     */
    public boolean setFields(int id, int mask, ScreenElement.Fields values, Viewport viewport,
            Predicate<String> storedFont, int textCap) {
        int index = indexOf(id);
        if (index < 0)
            return false;
        ScreenElement current = order.get(index);
        ScreenElement next = current.merged(values, mask);
        String hash = ScreenElement.ttfHash(next.font());
        if (hash != null && !next.font().equals(current.font()) && !storedFont.test(hash))
            next = next.withFont(current.font());
        if (!next.art().isEmpty() && !next.art().equals(current.art()) && !storedFont.test(next.art()))
            next = next.toBuilder().art(current.art()).build();
        next = next.clampedTo(viewport);
        if ((mask & ScreenElement.FIELD_TEXT) != 0)
            next = next.withText(ScreenElement.cut(next.text(), budget(id, textCap)));
        if (next.equals(current))
            return false;
        order.set(index, next);
        return true;
    }

    /**
     * What a display link sends to that element, lettering or a bar: replaces its
     * text, typed or linked, when the generation is the one the link was made against. Returns whether the
     * text changed, so a source sending the same reading every tick costs no sync.
     */
    public boolean acceptLinked(int id, int generation, String text) {
        return acceptLinked(id, generation, text, MAX_TOTAL_TEXT);
    }

    /** {@link #acceptLinked(int, int, String)}, with the display's text held to {@code textCap} in all. */
    public boolean acceptLinked(int id, int generation, String text, int textCap) {
        int index = indexOf(id);
        if (index < 0 || generation(id) != (generation & GENERATION_MASK))
            return false;
        ScreenElement current = order.get(index);
        if (!current.kind().linked())
            return false;
        String next = ScreenElement.cut(ScreenElement.sanitiseText(text), budget(id, textCap));
        if (next.equals(current.text()))
            return false;
        order.set(index, current.withText(next));
        return true;
    }

    /** Moves that element to that place in the drawing order, clamped to the list. */
    public boolean zTo(int id, int index) {
        int from = indexOf(id);
        if (from < 0)
            return false;
        int to = Math.max(0, Math.min(index, order.size() - 1));
        if (to == from)
            return false;
        order.add(to, order.remove(from));
        return true;
    }

    /**
     * Fits every box to a viewport that has changed size since the elements were
     * placed. Returns whether any moved.
     */
    public boolean clampTo(Viewport viewport) {
        boolean changed = false;
        for (int i = 0; i < order.size(); i++) {
            ScreenElement clamped = order.get(i).clampedTo(viewport);
            if (clamped != order.get(i)) {
                order.set(i, clamped);
                changed = true;
            }
        }
        return changed;
    }

    public void write(CompoundTag tag) {
        ListTag list = new ListTag();
        for (ScreenElement element : order)
            list.add(element.write());
        tag.put("Elements", list);
        for (byte generation : generations)
            if (generation != 0) {
                tag.putByteArray("Gen", generations.clone());
                return;
            }
        tag.remove("Gen");
    }

    /**
     * Replaces everything with what the tag holds, in place, which is how the client
     * takes every sync.
     *
     * A tag with no {@code Elements} is a display saved before elements existed. When
     * {@code legacyForm} is given, its one line becomes element 0, field by field,
     * from {@link DisplayPrint}'s keys and {@code Text}: the whole viewport, centred,
     * the same height, turn, colour, weight and slant, and the panel-label font ids
     * mapped onto the design-font names that mean the same face. Without a form the
     * display starts from {@link #defaults}.
     *
     * Anything a tag could lie about is sanitised: entries with a slot out of range,
     * a kind this build does not know or a slot already used are dropped, entries past
     * {@link #MAX} are ignored, every box is fitted to the viewport and the text is
     * cut to the budget in drawing order.
     */
    public void read(CompoundTag tag, Viewport viewport, DisplayForm legacyForm) {
        order.clear();
        Arrays.fill(generations, (byte) 0);
        if (!tag.contains("Elements", Tag.TAG_LIST)) {
            order.addAll(legacyForm == null ? defaults(viewport).order : List.of(migrate(tag, viewport, legacyForm)));
            return;
        }
        boolean[] used = new boolean[MAX];
        int budget = MAX_TOTAL_TEXT;
        ListTag list = tag.getList("Elements", Tag.TAG_COMPOUND);
        for (int i = 0; i < list.size() && order.size() < MAX; i++) {
            ScreenElement element = ScreenElement.read(list.getCompound(i), viewport);
            if (element == null || used[element.id()])
                continue;
            used[element.id()] = true;
            element = element.clampedTo(viewport);
            String text = ScreenElement.cut(element.text(), budget);
            budget -= text.length();
            order.add(text.equals(element.text()) ? element : element.withText(text));
        }
        byte[] stored = tag.getByteArray("Gen");
        for (int i = 0; i < Math.min(stored.length, MAX); i++)
            generations[i] = (byte) (stored[i] & GENERATION_MASK);
    }

    /** Element 0 as a display saved before elements existed was showing it. */
    static ScreenElement migrate(CompoundTag tag, Viewport viewport, DisplayForm form) {
        DisplayPrint print = DisplayPrint.read(tag, form);
        return new ScreenElement(0, ElementKind.TEXT, 0, 0, viewport.width(), viewport.height(), print.colour(), 0,
                ScreenElement.DEFAULT_STROKE, tag.getString("Text"), print.font().font().toString(), print.bold(),
                print.italic(), print.size(), print.quarters(), ScreenElement.ALIGN_CENTRE,
                ScreenElement.ALIGN_CENTRE);
    }

    /** Characters of text summed over every element. */
    public int textLength() {
        int used = 0;
        for (ScreenElement element : order)
            used += element.text().length();
        return used;
    }

    /**
     * Characters left for that element's text: the cap ({@link #MAX_TOTAL_TEXT}, or
     * {@code textCap} when lower) less everyone else's ({@code -1}: everyone's).
     */
    private int budget(int id, int textCap) {
        int used = 0;
        for (ScreenElement element : order)
            if (element.id() != id)
                used += element.text().length();
        return Math.max(0, Math.min(MAX_TOTAL_TEXT, textCap) - used);
    }
}
