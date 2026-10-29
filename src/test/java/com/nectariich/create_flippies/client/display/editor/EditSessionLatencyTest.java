package com.nectariich.create_flippies.client.display.editor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.nectariich.create_flippies.client.display.ElementEditTarget;
import com.nectariich.create_flippies.content.display.element.ElementKind;
import com.nectariich.create_flippies.content.display.element.ScreenElement;
import com.nectariich.create_flippies.content.display.element.ScreenElements;
import com.nectariich.create_flippies.content.display.element.Viewport;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The edit pipeline against a display on a server with a round trip: what was sent is
 * applied there in order only when the test delivers it, and the client's elements
 * change only on the sync after. Puts and removes still in flight must count as done
 * for everything the session answers, or selections, stacking order and history go wrong.
 */
class EditSessionLatencyTest {

    static final Viewport VIEWPORT = new Viewport(400, 200);

    /** A server's elements, and the client's copy of them that catches up on {@link #deliver}. */
    static final class Lagging implements ElementEditTarget {
        final ScreenElements server = new ScreenElements();
        ScreenElements client = new ScreenElements();
        final List<Runnable> inFlight = new ArrayList<>();
        /** Hashes the server's store holds; a put naming any other picture is refused. */
        final List<String> stored = new ArrayList<>();
        final List<String> provided = new ArrayList<>();

        @Override
        public ScreenElements elements() {
            return client;
        }

        @Override
        public Viewport viewport() {
            return VIEWPORT;
        }

        @Override
        public void remove(int id) {
            inFlight.add(() -> server.remove(id));
        }

        @Override
        public void set(int id, int mask, ScreenElement.Fields values) {
            inFlight.add(() -> server.setFields(id, mask, values, VIEWPORT, stored::contains));
        }

        @Override
        public void zTo(int id, int index) {
            inFlight.add(() -> server.zTo(id, index));
        }

        @Override
        public void put(ScreenElement element, int index, int generation) {
            inFlight.add(() -> server.put(element, index, generation, VIEWPORT, stored::contains,
                    ScreenElements.MAX_TOTAL_TEXT));
        }

        @Override
        public void provideArt(String hash) {
            provided.add(hash);
        }

        /** The server reads everything sent, in order, and the client gets the sync. */
        void deliver() {
            inFlight.forEach(Runnable::run);
            inFlight.clear();
            sync();
        }

        void sync() {
            ScreenElements copy = new ScreenElements();
            List<ScreenElement> snapshot = server.snapshot();
            for (int i = 0; i < snapshot.size(); i++) {
                ScreenElement element = snapshot.get(i);
                copy.put(element, i, server.generation(element.id()), VIEWPORT, hash -> true,
                        ScreenElements.MAX_TOTAL_TEXT);
            }
            client = copy;
        }
    }

    Lagging target;
    long now;
    EditSession session;

    @BeforeEach
    void setUp() {
        target = new Lagging();
        now = 1_000_000;
        session = new EditSession(target, () -> now);
        for (int id = 0; id < 4; id++)
            target.server.put(ScreenElement.create(id, ElementKind.FILL, 10 * id, 0, 50, 50), id, 0, VIEWPORT,
                    hash -> true, ScreenElements.MAX_TOTAL_TEXT);
        target.sync();
    }

    List<Integer> serverOrder() {
        return target.server.snapshot().stream().map(ScreenElement::id).toList();
    }

    List<Integer> shownOrder() {
        return session.shown().stream().map(ScreenElement::id).toList();
    }

    void ticks(int n) {
        for (int i = 0; i < n; i++)
            session.tick();
    }

    @Test
    void aPutInFlightIsShownAndSelectableAtOnce() {
        session.put(List.of(ScreenElement.create(4, ElementKind.ELLIPSE, VIEWPORT)));
        session.tick();
        assertNotNull(session.shown(4), "a new shape exists for the editor before the server answers");
        assertEquals(4, session.indexOf(4));
        assertEquals(List.of(), session.freeIds(1).stream().filter(id -> id == 4).toList());

        session.edit(Map.of(4, session.shown(4).toBuilder().colour(0xFF00FF00).build()), null);
        ticks(EditSession.SEND_INTERVAL);
        target.deliver();
        session.tick();
        assertEquals(0xFF00FF00, target.server.get(4).colour(), "a set sent before the put landed still lands");
    }

    @Test
    void twoPutsInARowStackInTheOrderMade() {
        session.put(List.of(ScreenElement.create(4, ElementKind.FILL, VIEWPORT)));
        session.put(List.of(ScreenElement.create(5, ElementKind.FILL, VIEWPORT)));
        assertEquals(List.of(0, 1, 2, 3, 4, 5), shownOrder());
        target.deliver();
        assertEquals(List.of(0, 1, 2, 3, 4, 5), serverOrder(), "the second paste in front of the first");
    }

    @Test
    void quickUndoAcrossADeleteRestoresTheOldValues() {
        int before = session.shown(3).colour();
        session.edit(Map.of(3, session.shown(3).toBuilder().colour(0xFF112233).build()), null);
        session.remove(List.of(3));
        target.deliver();
        session.tick();
        assertNull(session.shown(3));

        // Both undos inside one round trip
        assertTrue(session.undo());
        assertNotNull(session.shown(3), "brought back at once, before the server answers");
        assertTrue(session.undo());
        assertEquals(before, session.shown(3).colour());
        target.deliver();
        session.tick();
        assertEquals(before, target.server.get(3).colour(), "the recolour was undone, not skipped");

        // And both redos inside one round trip put it all back
        assertTrue(session.redo());
        assertTrue(session.redo());
        target.deliver();
        assertNull(target.server.get(3));
        assertTrue(session.undo());
        target.deliver();
        session.tick();
        assertEquals(0xFF112233, target.server.get(3).colour());
    }

    @Test
    void restackingRightAfterADeleteMovesWhatWasAsked() {
        session.remove(List.of(0));
        assertEquals(List.of(1, 2, 3), shownOrder());
        // Raise 1 over 2, before the delete has synced
        session.restack(List.of(2, 1, 3));
        target.deliver();
        assertEquals(List.of(2, 1, 3), serverOrder());
    }

    @Test
    void aRefusedPutIsReportedAndItsSlotFreed() {
        List<ScreenElement> refused = new ArrayList<>();
        session.onRefused(refused::add);
        ScreenElement art = ScreenElement.create(4, ElementKind.SVG, VIEWPORT).toBuilder()
                .art("0123456789abcdef0123456789abcdef").build();
        session.put(List.of(art));
        assertEquals(List.of(art.art()), target.provided, "the picture is offered to the store first");
        target.deliver();
        ticks(EditSession.PUT_HOLD_TICKS);
        assertNull(session.shown(4));
        assertEquals(1, refused.size());
        assertEquals(4, refused.get(0).id());
        assertTrue(session.freeIds(ScreenElements.MAX).contains(4));
    }
}
