package com.nectariich.create_flippies.client.display.editor;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.LongSupplier;

import com.nectariich.create_flippies.client.display.ElementEditTarget;
import com.nectariich.create_flippies.content.display.element.ScreenElement;
import com.nectariich.create_flippies.content.display.element.ScreenElements;
import com.nectariich.create_flippies.content.display.element.Viewport;

/**
 * Every change the display editor makes, as the display will have it a moment from
 * now, and the history to undo it by.
 *
 * <h2>What is shown</h2>
 *
 * An edit is shown at once and sent shortly: {@link #edit} lays the new values over the
 * live elements ({@link #shown}) as an override, which the editor's preview of the
 * display draws, and sends field-scoped sets at most every {@value #SEND_INTERVAL}
 * ticks (a drag of a box is a few packets a second, not one a frame). An override is
 * dropped once the live element matches it, or {@value #HOLD_TICKS} ticks after its
 * last send, so another player's later edit shows. Structural changes (puts, removes,
 * restacking) go at once.
 *
 * <h2>Structural changes in flight</h2>
 *
 * A put or a remove sent to a server takes a round trip to come back in the live
 * elements, and the player does not wait for it: a shape just drawn is selected and can
 * be recoloured, two pastes follow each other, an undo runs straight after a delete.
 * So everything this class answers ({@link #shown}, {@link #indexOf}, the order a
 * restack starts from, the place a put goes) is the display as it will be once what
 * was sent has landed: the live elements less those a remove is in flight for, plus
 * those a put is in flight for, placed where the server will place them. The server
 * applies this client's packets in the order sent, so a set or a move sent for an
 * element whose put is still in flight finds it there. A put that has not shown up
 * after {@value #PUT_HOLD_TICKS} ticks was refused (another player took the slot, or
 * the display lacks a picture or font it names); it is dropped, and the listener given
 * to {@link #onRefused} hears of it.
 *
 * <h2>History</h2>
 *
 * One step per gesture: a drag, a tool's shape, a paste, a menu command, or the typing
 * into one field with less than a second between keystrokes ({@code coalesce} keys the
 * field). At most {@value #DEPTH} steps. Undo sends what restores each element's old
 * values (field-scoped, so last write wins against other players), brings deleted
 * elements back in their own slots with their own generations ({@link ElementEditTarget#put}),
 * takes put ones away, and restacks. Redo replays. The history lives as long as the
 * session, which is as long as the editor is open.
 *
 * <h2>How it is kept</h2>
 *
 * A step is a list of atomic ops, each of which knows its inverse: a set (an element's
 * values before and after), a put and a remove (each the other's inverse, carrying the
 * element, its place and its slot's generation), and a restack (the drawing order before
 * and after). Undo runs a step's inverses back to front, redo runs the step forward.
 *
 * An override covers only the fields this session has set ({@code mask}), laid over
 * whatever the live element has now, so another player's change to a different field
 * (or a display link's new text) shows even while one field is held.
 *
 * Pending sets are flushed before any structural change, so the target always sees an
 * element's sets before that element is removed or restacked, in the order made.
 */
public final class EditSession {

    public static final int SEND_INTERVAL = 5;
    public static final int HOLD_TICKS = 20;
    public static final int DEPTH = 100;
    /** Typing in one field this close together is one step. */
    public static final long COALESCE_MILLIS = 1000;

    /**
     * How long a put that has not shown up yet keeps its slot from {@link #freeIds}: long
     * enough for the round trip, short enough that a put the server refused frees the
     * slot again.
     */
    static final int PUT_HOLD_TICKS = 2 * HOLD_TICKS;

    private final ElementEditTarget target;
    private final LongSupplier clock;

    /** Values this session has set and not yet seen land, by slot. */
    private final Map<Integer, Held> overrides = new HashMap<>();
    /** Fields set and not yet sent, by slot. */
    private final Map<Integer, Integer> dirty = new LinkedHashMap<>();
    /** Puts sent and not yet seen in the live elements, by slot, in the order sent. */
    private final Map<Integer, PendingPut> pendingPuts = new LinkedHashMap<>();
    /** Removes sent while the live elements still show the element, by slot. */
    private final Map<Integer, PendingRemove> pendingRemoves = new HashMap<>();
    /** Hears of each put that never showed up. */
    private Consumer<ScreenElement> refused = element -> {};

    private final Deque<Step> undo = new ArrayDeque<>();
    private final Deque<Step> redo = new ArrayDeque<>();
    /** The step a coalescing edit may still join: the newest, until anything else happens. */
    private Step open;
    private boolean gesture;
    /** The step the open gesture has made, or null until its first change. */
    private Step gestureStep;

    private long ticks;
    private long lastSend = Long.MIN_VALUE / 2;

    public EditSession(ElementEditTarget target) {
        this(target, System::currentTimeMillis);
    }

    /** With a clock for coalescing, in milliseconds; tests drive their own. */
    EditSession(ElementEditTarget target, LongSupplier clock) {
        this.target = target;
        this.clock = clock;
    }

    public ElementEditTarget target() {
        return target;
    }

    /** Who hears of a put the display refused (see the class comment); the editor shows a message. */
    public void onRefused(Consumer<ScreenElement> listener) {
        this.refused = listener;
    }

    /** The live elements, or null once the display has gone. */
    public ScreenElements live() {
        return target.elements();
    }

    public Viewport viewport() {
        return target.viewport();
    }

    /**
     * Back to front, as the display will be once what was sent lands, overrides laid
     * over it. Empty when the display has gone.
     */
    public List<ScreenElement> shown() {
        List<ScreenElement> projected = projected();
        if (overrides.isEmpty())
            return projected;
        List<ScreenElement> shown = new ArrayList<>(projected.size());
        for (ScreenElement element : projected)
            shown.add(over(element));
        return shown;
    }

    /** One element as shown, or null when there is no such element (or it is being removed). */
    public ScreenElement shown(int id) {
        ScreenElement element = projectedElement(id);
        return element == null ? null : over(element);
    }

    /** Where that element is in the drawing order as shown, 0 at the back; -1 when it does not exist. */
    public int indexOf(int id) {
        return ids(projected()).indexOf(id);
    }

    /**
     * Changes elements' fields: each value in {@code after} is the element as it should
     * be (same id and kind), clamped to the viewport here. Shown at once, sent soon.
     * Recorded as one step, merged into the last one when {@code coalesce} is not null,
     * equals the last step's key and the last edit was less than
     * {@link #COALESCE_MILLIS} ago, or when a gesture is open.
     */
    public void edit(Map<Integer, ScreenElement> after, String coalesce) {
        if (live() == null || after.isEmpty())
            return;
        Viewport viewport = viewport();
        long now = clock.getAsLong();
        Step step = null;
        for (Map.Entry<Integer, ScreenElement> entry : after.entrySet()) {
            int id = entry.getKey();
            ScreenElement current = shown(id);
            ScreenElement next = entry.getValue();
            if (current == null || next == null || next.id() != id || next.kind() != current.kind())
                continue;
            next = next.clampedTo(viewport);
            int mask = current.difference(next);
            if (mask == 0)
                continue;
            if (step == null)
                step = stepFor(coalesce, now);
            step.recordSet(id, current, next);
            hold(id, next, mask);
        }
        if (step != null)
            step.lastMillis = now;
    }

    /** Opens a gesture (a drag): every edit until {@link #endGesture} is one step. */
    public void beginGesture() {
        gesture = true;
        gestureStep = null;
    }

    /** Closes the gesture and sends what it left unsent. */
    public void endGesture() {
        if (gesture && gestureStep != null && gestureStep.noop() && undo.peekLast() == gestureStep) {
            // a drag that ended where it began changed nothing worth an undo
            undo.pollLast();
            open = null;
        }
        gesture = false;
        gestureStep = null;
        flush();
    }

    /**
     * Puts new elements, in slots from {@link #freeIds}, in front of everything in the
     * order given (back to front). One step. Elements whose slot is taken are skipped.
     */
    public void put(List<ScreenElement> elements) {
        ScreenElements live = live();
        if (live == null || elements.isEmpty())
            return;
        flush();
        Viewport viewport = viewport();
        Step step = null;
        // In front of what the server will have by then, puts still in flight included,
        // so a second paste lands in front of the first
        List<Integer> shownIds = ids(projected());
        int index = shownIds.size();
        Set<Integer> placed = new HashSet<>(shownIds);
        for (ScreenElement element : elements) {
            int id = element.id();
            if (live.get(id) != null || !placed.add(id))
                continue;
            if (step == null)
                step = stepFor(null, clock.getAsLong());
            Op op = new Put(element.clampedTo(viewport), index++, live.generation(id));
            step.ops.add(op);
            op.apply();
        }
    }

    /** Removes elements. One step, remembering each one's place and generation for undo. */
    public void remove(Collection<Integer> ids) {
        ScreenElements live = live();
        if (live == null || ids.isEmpty())
            return;
        flush();
        // front first, so each remembered place is the one it had with everything behind it
        // still there, and undo (back first) puts each exactly where it was
        List<Integer> shownIds = ids(projected());
        List<Integer> order = new ArrayList<>();
        for (int id : new HashSet<>(ids))
            if (shownIds.contains(id))
                order.add(id);
        order.sort((a, b) -> Integer.compare(shownIds.indexOf(b), shownIds.indexOf(a)));
        Step step = null;
        for (int id : order) {
            ScreenElement element = shown(id);
            if (step == null)
                step = stepFor(null, clock.getAsLong());
            Op op = new Remove(element, shownIds.indexOf(id), generation(id));
            step.ops.add(op);
            op.apply();
            shownIds.remove((Integer) id);
        }
    }

    /**
     * Puts the elements in that drawing order, back to front: {@code order} must name
     * exactly the elements there are. One step. Nothing when it is the order already.
     */
    public void restack(List<Integer> order) {
        if (live() == null)
            return;
        List<Integer> before = ids(projected());
        if (order.size() != before.size() || !new HashSet<>(order).equals(new HashSet<>(before))
                || order.equals(before))
            return;
        flush();
        Op op = new Restack(before, List.copyOf(order));
        stepFor(null, clock.getAsLong()).ops.add(op);
        op.apply();
    }

    /** The slots nothing is in (nor is waiting to be put in), lowest first, at most {@code count}. */
    public List<Integer> freeIds(int count) {
        ScreenElements live = live();
        if (live == null || count <= 0)
            return List.of();
        List<Integer> free = new ArrayList<>();
        for (int id : live.freeIds(ScreenElements.MAX))
            if (free.size() < count && !pendingPuts.containsKey(id))
                free.add(id);
        return free;
    }

    /** That slot's generation as the display will have it once a put in flight for it lands. */
    public int generation(int id) {
        PendingPut put = pendingPuts.get(id);
        if (put != null)
            return put.generation;
        ScreenElements live = live();
        return live == null ? 0 : live.generation(id);
    }

    public boolean canUndo() {
        return undo.stream().anyMatch(step -> !step.noop());
    }

    public boolean canRedo() {
        return !redo.isEmpty();
    }

    /** Undoes the last step; false when there is none. */
    public boolean undo() {
        closeGesture();
        flush();
        Step step;
        do {
            step = undo.pollLast();
        } while (step != null && step.noop());
        open = null;
        if (step == null)
            return false;
        for (int i = step.ops.size() - 1; i >= 0; i--)
            step.ops.get(i).revert();
        flush();
        redo.addLast(step);
        return true;
    }

    public boolean redo() {
        closeGesture();
        flush();
        Step step = redo.pollLast();
        open = null;
        if (step == null)
            return false;
        for (Op op : step.ops)
            op.apply();
        flush();
        push(step);
        return true;
    }

    /** Once a client tick: sends what is due, drops overrides that have landed or run out. */
    public void tick() {
        ticks++;
        if (!dirty.isEmpty() && ticks - lastSend >= SEND_INTERVAL)
            send();
        ScreenElements live = live();
        settle(live);
        Iterator<Map.Entry<Integer, Held>> it = overrides.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<Integer, Held> entry = it.next();
            int id = entry.getKey();
            // held over an element still on its way, until it lands
            if (dirty.containsKey(id) || pendingPuts.containsKey(id))
                continue;
            Held override = entry.getValue();
            ScreenElement element = live == null ? null : live.get(id);
            if (element == null || (element.difference(override.element) & override.mask) == 0
                    || ticks - override.sent >= HOLD_TICKS)
                it.remove();
        }
    }

    /** Sends everything not yet sent, now; the editor calls it when it closes. */
    public void flush() {
        if (!dirty.isEmpty())
            send();
    }

    // ---- internals ----

    /** Lets go of the puts and removes that have landed, and of puts that never will (see the class comment). */
    private void settle(ScreenElements live) {
        Iterator<Map.Entry<Integer, PendingPut>> puts = pendingPuts.entrySet().iterator();
        while (puts.hasNext()) {
            Map.Entry<Integer, PendingPut> entry = puts.next();
            PendingPut put = entry.getValue();
            ScreenElement element = live == null ? null : live.get(entry.getKey());
            if (element == null)
                put.replacing = false;
            if (live == null || element != null && (!put.replacing || element.equals(put.element))) {
                puts.remove();
            } else if (ticks - put.sent >= PUT_HOLD_TICKS) {
                puts.remove();
                overrides.remove(entry.getKey());
                dirty.remove(entry.getKey());
                if (element == null)
                    refused.accept(put.element);
            }
        }
        Iterator<Map.Entry<Integer, PendingRemove>> removes = pendingRemoves.entrySet().iterator();
        while (removes.hasNext()) {
            Map.Entry<Integer, PendingRemove> entry = removes.next();
            PendingRemove remove = entry.getValue();
            boolean present = live != null && live.get(entry.getKey()) != null;
            if (present)
                remove.seen = true;
            if (live == null || remove.seen && !present || ticks - remove.sent >= PUT_HOLD_TICKS)
                removes.remove();
        }
    }

    /**
     * The display as it will be once what was sent lands, back to front, without
     * overrides: the live elements less those being removed or replaced, with each put
     * in flight placed where the server will place it, in the order sent.
     */
    private List<ScreenElement> projected() {
        ScreenElements live = live();
        if (live == null)
            return List.of();
        List<ScreenElement> snapshot = live.snapshot();
        if (pendingPuts.isEmpty() && pendingRemoves.isEmpty())
            return snapshot;
        List<ScreenElement> projected = new ArrayList<>(snapshot.size() + pendingPuts.size());
        for (ScreenElement element : snapshot)
            if (!pendingRemoves.containsKey(element.id()) && !pendingPuts.containsKey(element.id()))
                projected.add(element);
        for (PendingPut put : pendingPuts.values())
            projected.add(Math.max(0, Math.min(put.index, projected.size())), put.element);
        return projected;
    }

    /** One element of {@link #projected}, without overrides, or null. */
    private ScreenElement projectedElement(int id) {
        ScreenElements live = live();
        if (live == null)
            return null;
        PendingPut put = pendingPuts.get(id);
        if (put != null)
            return put.element;
        return pendingRemoves.containsKey(id) ? null : live.get(id);
    }

    /** That live element with this session's held fields over it. */
    private ScreenElement over(ScreenElement element) {
        Held override = overrides.get(element.id());
        if (override == null)
            return element;
        return element.merged(override.element.fields(), override.mask);
    }

    /** Shows {@code next} for those fields at once and marks them to send. */
    private void hold(int id, ScreenElement next, int mask) {
        if ((mask & ScreenElement.FIELD_FONT) != 0)
            provideFont(next);
        if ((mask & ScreenElement.FIELD_ART) != 0 && !next.art().isEmpty())
            target.provideArt(next.art());
        Held override = overrides.computeIfAbsent(id, key -> new Held());
        override.element = next;
        override.mask |= mask;
        override.sent = ticks;
        dirty.merge(id, mask, (a, b) -> a | b);
    }

    private void send() {
        for (Map.Entry<Integer, Integer> entry : dirty.entrySet()) {
            int id = entry.getKey();
            Held override = overrides.get(id);
            // An element whose put is in flight takes sets too: the server reads them after the put
            if (override == null || projectedElement(id) == null)
                continue;
            target.set(id, entry.getValue(), override.element.fields());
            override.sent = ticks;
        }
        dirty.clear();
        lastSend = ticks;
    }

    private void provideFont(ScreenElement element) {
        String hash = element == null ? null : ScreenElement.ttfHash(element.font());
        if (hash != null)
            target.provideFont(hash);
    }

    /** The step a change made now goes into: the open gesture's, the coalescing one, or a new one. */
    private Step stepFor(String coalesce, long now) {
        if (gesture) {
            if (gestureStep == null || undo.peekLast() != gestureStep) {
                gestureStep = new Step(null);
                push(gestureStep);
                redo.clear();
            }
            return gestureStep;
        }
        if (coalesce != null && open != null && undo.peekLast() == open && coalesce.equals(open.key)
                && now - open.lastMillis < COALESCE_MILLIS)
            return open;
        Step step = new Step(coalesce);
        step.lastMillis = now;
        push(step);
        redo.clear();
        open = step;
        return step;
    }

    private void push(Step step) {
        undo.addLast(step);
        while (undo.size() > DEPTH)
            undo.pollFirst();
    }

    private void closeGesture() {
        if (gesture)
            endGesture();
    }

    private static List<Integer> ids(List<ScreenElement> elements) {
        List<Integer> ids = new ArrayList<>(elements.size());
        for (ScreenElement element : elements)
            ids.add(element.id());
        return ids;
    }

    /**
     * Sends the zTo calls that turn the drawing order as shown into {@code wanted}, simulating
     * each locally so no move is sent that would change nothing. Elements that are no
     * longer there are left out of {@code wanted}; ones it does not know (another player
     * added them since) stay in front, in their own order.
     */
    private void restackTo(List<Integer> wanted) {
        if (live() == null)
            return;
        List<Integer> current = ids(projected());
        List<Integer> goal = new ArrayList<>();
        for (int id : wanted)
            if (current.contains(id))
                goal.add(id);
        for (int id : current)
            if (!goal.contains(id))
                goal.add(id);
        for (int i = 0; i < goal.size(); i++) {
            int id = goal.get(i);
            int from = current.indexOf(id);
            if (from == i)
                continue;
            target.zTo(id, i);
            current.add(i, current.remove(from));
        }
    }

    /** A put sent and not yet seen. */
    private static final class PendingPut {
        final ScreenElement element;
        final int index;
        final int generation;
        final long sent;
        /**
         * Whether the live elements still showed an element in that slot when it was sent
         * (an undo bringing back what a remove still in flight takes away): then only that
         * element going, or the put's own element appearing, means it landed.
         */
        boolean replacing;

        PendingPut(ScreenElement element, int index, int generation, long sent, boolean replacing) {
            this.element = element;
            this.index = index;
            this.generation = generation;
            this.sent = sent;
            this.replacing = replacing;
        }
    }

    /** A remove sent while the element was still live. */
    private static final class PendingRemove {
        final long sent;
        /** Whether the element has been seen since, so that its going means the remove landed. */
        boolean seen;

        PendingRemove(long sent, boolean seen) {
            this.sent = sent;
            this.seen = seen;
        }
    }

    /** What this session holds over one live element. */
    private static final class Held {
        ScreenElement element;
        int mask;
        long sent;
    }

    /** One undo step: the ops of one gesture, command or run of typing, in the order made. */
    private final class Step {
        final String key;
        final List<Op> ops = new ArrayList<>();
        long lastMillis;

        Step(String key) {
            this.key = key;
        }

        /** Notes a set, folding it into this step's earlier set of the same element. */
        void recordSet(int id, ScreenElement before, ScreenElement after) {
            for (Op op : ops)
                if (op instanceof SetOp set && set.id == id) {
                    set.after = after;
                    return;
                }
            ops.add(new SetOp(id, before, after));
        }

        boolean noop() {
            for (Op op : ops)
                if (!(op instanceof SetOp set) || set.before.difference(set.after) != 0)
                    return false;
            return true;
        }
    }

    private interface Op {
        void apply();

        void revert();
    }

    /** An element's fields changed; only the ones that differ are ever sent back. */
    private final class SetOp implements Op {
        final int id;
        final ScreenElement before;
        ScreenElement after;

        SetOp(int id, ScreenElement before, ScreenElement after) {
            this.id = id;
            this.before = before;
            this.after = after;
        }

        @Override
        public void apply() {
            restore(after);
        }

        @Override
        public void revert() {
            restore(before);
        }

        private void restore(ScreenElement values) {
            int mask = before.difference(after);
            ScreenElement current = shown(id);
            if (mask == 0 || current == null)
                return;
            hold(id, current.merged(values.fields(), mask).clampedTo(viewport()), mask);
        }
    }

    /** A whole element into its free slot, at a place, with the slot's generation. */
    private final class Put implements Op {
        final ScreenElement element;
        final int index;
        final int generation;

        Put(ScreenElement element, int index, int generation) {
            this.element = element;
            this.index = index;
            this.generation = generation;
        }

        @Override
        public void apply() {
            sendPut(element, index, generation);
        }

        @Override
        public void revert() {
            sendRemove(element.id());
        }
    }

    /** An element taken away, remembered as it was shown, with its place and generation. */
    private final class Remove implements Op {
        final ScreenElement element;
        final int index;
        final int generation;

        Remove(ScreenElement element, int index, int generation) {
            this.element = element;
            this.index = index;
            this.generation = generation;
        }

        @Override
        public void apply() {
            sendRemove(element.id());
        }

        @Override
        public void revert() {
            sendPut(element, index, generation);
        }
    }

    /** The drawing order before and after. */
    private final class Restack implements Op {
        final List<Integer> before;
        final List<Integer> after;

        Restack(List<Integer> before, List<Integer> after) {
            this.before = before;
            this.after = after;
        }

        @Override
        public void apply() {
            restackTo(after);
        }

        @Override
        public void revert() {
            restackTo(before);
        }
    }

    private void sendPut(ScreenElement element, int index, int generation) {
        provideFont(element);
        // A picture this client holds goes to the world's store first, as a font does,
        // so a layout pasted from another world keeps its pictures
        if (!element.art().isEmpty())
            target.provideArt(element.art());
        int id = element.id();
        ScreenElements live = live();
        pendingRemoves.remove(id);
        pendingPuts.remove(id);
        pendingPuts.put(id, new PendingPut(element, index, generation, ticks, live != null && live.get(id) != null));
        target.put(element, index, generation);
    }

    private void sendRemove(int id) {
        overrides.remove(id);
        dirty.remove(id);
        pendingPuts.remove(id);
        ScreenElements live = live();
        pendingRemoves.put(id, new PendingRemove(ticks, live != null && live.get(id) != null));
        target.remove(id);
    }
}
