package com.nectariich.create_flippies.client.display.editor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.nectariich.create_flippies.client.display.ElementEditTarget;
import com.nectariich.create_flippies.client.display.LocalElementTarget;
import com.nectariich.create_flippies.content.display.element.ElementKind;
import com.nectariich.create_flippies.content.display.element.ScreenElement;
import com.nectariich.create_flippies.content.display.element.ScreenElements;
import com.nectariich.create_flippies.content.display.element.Viewport;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The editor's edit pipeline over a workbench-style local layout: edits show at once and
 * land on the next send, history steps form as the spec says (gestures, typing within a
 * second, depth), and undo and redo put back values, elements (same slot and generation)
 * and drawing order.
 */
class EditSessionTest {

    static final Viewport VIEWPORT = new Viewport(400, 200);

    /** A local target that also notes every call, so tests can see what was sent. */
    static class Recording implements ElementEditTarget {
        final ScreenElements elements = new ScreenElements();
        final LocalElementTarget local = new LocalElementTarget(elements, () -> VIEWPORT, () -> {});
        final List<String> calls = new ArrayList<>();
        boolean gone;

        @Override
        public ScreenElements elements() {
            return gone ? null : elements;
        }

        @Override
        public Viewport viewport() {
            return VIEWPORT;
        }

        @Override
        public void remove(int id) {
            calls.add("remove " + id);
            local.remove(id);
        }

        @Override
        public void set(int id, int mask, ScreenElement.Fields values) {
            calls.add("set " + id + " " + mask);
            local.set(id, mask, values);
        }

        @Override
        public void zTo(int id, int index) {
            calls.add("zTo " + id + " " + index);
            local.zTo(id, index);
        }

        @Override
        public void put(ScreenElement element, int index, int generation) {
            calls.add("put " + element.id() + " " + index + " " + generation);
            local.put(element, index, generation);
        }

        @Override
        public void provideFont(String hash) {
            calls.add("font " + hash);
        }
    }

    Recording target;
    long now;
    EditSession session;

    @BeforeEach
    void setUp() {
        target = new Recording();
        now = 1_000_000;
        session = new EditSession(target, () -> now);
        for (int id = 0; id < 3; id++)
            target.elements.put(ScreenElement.create(id, ElementKind.FILL, 10 * id, 0, 50, 50), id, 0, VIEWPORT,
                    hash -> true, ScreenElements.MAX_TOTAL_TEXT);
    }

    ScreenElement moved(int id, int x) {
        ScreenElement element = session.shown(id);
        return element.withBox(x, element.y(), element.w(), element.h());
    }

    List<Integer> order() {
        return target.elements.snapshot().stream().map(ScreenElement::id).toList();
    }

    @Test
    void editShowsAtOnceAndLandsOnTheNextSend() {
        session.edit(Map.of(1, moved(1, 77)), null);
        assertEquals(77, session.shown(1).x());
        assertEquals(10, target.elements.get(1).x(), "not sent before a tick");
        session.tick();
        assertEquals(77, target.elements.get(1).x());
        assertEquals(List.of("set 1 " + ScreenElement.FIELD_X), target.calls);
    }

    @Test
    void sendsAreThrottled() {
        session.edit(Map.of(1, moved(1, 20)), "drag");
        session.tick();
        session.edit(Map.of(1, moved(1, 30)), "drag");
        session.tick();
        assertEquals(1, target.calls.size(), "a second send within the interval waits");
        assertEquals(30, session.shown(1).x());
        for (int i = 0; i < EditSession.SEND_INTERVAL; i++)
            session.tick();
        assertEquals(2, target.calls.size());
        assertEquals(30, target.elements.get(1).x());
    }

    @Test
    void editIsClampedToTheViewport() {
        session.edit(Map.of(1, moved(1, 5000)), null);
        assertEquals(VIEWPORT.width() - 50, session.shown(1).x());
    }

    @Test
    void typingWithinASecondIsOneStep() {
        session.edit(Map.of(1, moved(1, 20)), "x");
        now += 500;
        session.edit(Map.of(1, moved(1, 30)), "x");
        now += 1500;
        session.edit(Map.of(1, moved(1, 40)), "x");
        session.flush();
        assertTrue(session.undo());
        assertEquals(30, target.elements.get(1).x(), "the late keystroke is its own step");
        assertTrue(session.undo());
        assertEquals(10, target.elements.get(1).x());
        assertFalse(session.undo());
    }

    @Test
    void differentKeysOrNoKeyAreSeparateSteps() {
        session.edit(Map.of(1, moved(1, 20)), "x");
        session.edit(Map.of(1, moved(1, 30)), "y");
        session.edit(Map.of(1, moved(1, 40)), null);
        session.edit(Map.of(1, moved(1, 50)), null);
        int steps = 0;
        while (session.undo())
            steps++;
        assertEquals(4, steps);
        assertEquals(10, target.elements.get(1).x());
    }

    @Test
    void gestureIsOneStep() {
        session.beginGesture();
        for (int x = 11; x < 60; x++) {
            session.edit(Map.of(1, moved(1, x), 2, moved(2, x + 10)), null);
            now += 2000;
            session.tick();
        }
        session.endGesture();
        assertEquals(59, target.elements.get(1).x());
        assertTrue(session.undo());
        assertEquals(10, target.elements.get(1).x());
        assertEquals(20, target.elements.get(2).x());
        assertFalse(session.canUndo());
        assertTrue(session.redo());
        assertEquals(59, target.elements.get(1).x());
        assertEquals(69, target.elements.get(2).x());
    }

    @Test
    void gestureEndingWhereItBeganLeavesNoStep() {
        session.beginGesture();
        session.edit(Map.of(1, moved(1, 30)), null);
        session.edit(Map.of(1, moved(1, 10)), null);
        session.endGesture();
        assertFalse(session.canUndo());
    }

    @Test
    void undoSendsOnlyTheFieldsTheStepChanged() {
        session.edit(Map.of(1, moved(1, 30)), null);
        session.flush();
        // another player recolours it meanwhile
        target.elements.setFields(1, ScreenElement.FIELD_COLOUR,
                target.elements.get(1).toBuilder().colour(0xFF123456).build().fields(), VIEWPORT, hash -> true);
        target.calls.clear();
        session.undo();
        assertEquals(List.of("set 1 " + ScreenElement.FIELD_X), target.calls);
        assertEquals(10, target.elements.get(1).x());
        assertEquals(0xFF123456, target.elements.get(1).colour(), "last write wins only on the undone field");
    }

    @Test
    void putAndUndoRedo() {
        List<Integer> free = session.freeIds(2);
        assertEquals(List.of(3, 4), free);
        session.put(List.of(ScreenElement.create(3, ElementKind.ELLIPSE, VIEWPORT),
                ScreenElement.create(4, ElementKind.LINE, VIEWPORT)));
        assertEquals(List.of(0, 1, 2, 3, 4), order(), "put in front, in the order given");
        int generation = target.elements.generation(3);
        session.undo();
        assertNull(target.elements.get(3));
        assertNull(target.elements.get(4));
        session.redo();
        assertEquals(List.of(0, 1, 2, 3, 4), order());
        assertEquals(generation, target.elements.generation(3));
    }

    @Test
    void putSkipsTakenSlots() {
        session.put(List.of(ScreenElement.create(1, ElementKind.ELLIPSE, VIEWPORT)));
        assertEquals(ElementKind.FILL, target.elements.get(1).kind());
        assertFalse(session.canUndo());
    }

    @Test
    void undoingARemoveRestoresSlotPlaceAndGeneration() {
        ScreenElement one = target.elements.get(1);
        ScreenElement two = target.elements.get(2);
        int g1 = target.elements.generation(1);
        session.remove(List.of(2, 1));
        assertEquals(List.of(0), order());
        assertTrue(target.elements.generation(1) != g1, "a remove moves the generation on");
        session.undo();
        assertEquals(List.of(0, 1, 2), order());
        assertEquals(g1, target.elements.generation(1));
        assertEquals(one, target.elements.get(1));
        assertEquals(two, target.elements.get(2));
        session.redo();
        assertEquals(List.of(0), order());
    }

    @Test
    void restackAndUndo() {
        session.restack(List.of(2, 0, 1));
        assertEquals(List.of(2, 0, 1), order());
        session.undo();
        assertEquals(List.of(0, 1, 2), order());
        session.redo();
        assertEquals(List.of(2, 0, 1), order());
    }

    @Test
    void restackSkipsNoOpMoves() {
        session.restack(List.of(0, 2, 1));
        assertEquals(List.of("zTo 2 1"), target.calls);
        target.calls.clear();
        session.restack(List.of(0, 2, 1));
        assertTrue(target.calls.isEmpty());
        session.restack(List.of(0, 1));
        assertTrue(target.calls.isEmpty(), "an order that does not name every element is ignored");
    }

    @Test
    void depthIsCapped() {
        for (int i = 0; i < EditSession.DEPTH + 20; i++)
            session.edit(Map.of(1, moved(1, 11 + i)), null);
        int steps = 0;
        while (session.undo())
            steps++;
        assertEquals(EditSession.DEPTH, steps);
        assertEquals(30, target.elements.get(1).x(), "the oldest steps were dropped");
    }

    @Test
    void newStepClearsRedo() {
        session.edit(Map.of(1, moved(1, 30)), null);
        session.undo();
        assertTrue(session.canRedo());
        session.edit(Map.of(2, moved(2, 40)), null);
        assertFalse(session.canRedo());
    }

    @Test
    void undoDoesNotCoalesceIntoAnOlderStep() {
        session.edit(Map.of(1, moved(1, 30)), "x");
        session.edit(Map.of(2, moved(2, 40)), null);
        session.undo();
        session.edit(Map.of(1, moved(1, 50)), "x");
        session.undo();
        assertEquals(30, session.shown(1).x(), "the typing after an undo is its own step");
    }

    @Test
    void freeIdsSkipPendingPuts() {
        target.gone = false;
        Recording slow = new Recording() {
            @Override
            public void put(ScreenElement element, int index, int generation) {
                calls.add("put " + element.id());
            }
        };
        EditSession lagging = new EditSession(slow, () -> now);
        lagging.put(List.of(ScreenElement.create(0, ElementKind.FILL, VIEWPORT)));
        assertEquals(List.of(1, 2), lagging.freeIds(2), "slot 0 waits for its put to show up");
        for (int i = 0; i < 3 * EditSession.HOLD_TICKS; i++)
            lagging.tick();
        assertEquals(List.of(0, 1), lagging.freeIds(2), "a put that never showed frees its slot again");
    }

    @Test
    void overrideExpiresWhenTheLiveElementMatches() {
        session.edit(Map.of(1, moved(1, 30)), null);
        session.tick();
        session.tick();
        // someone else moves it after ours landed: the override must not hide that
        target.elements.setFields(1, ScreenElement.FIELD_X, moved(1, 90).fields(), VIEWPORT, hash -> true);
        assertEquals(90, session.shown(1).x());
    }

    @Test
    void overrideHeldUntilHoldTicksThenDropped() {
        Recording deaf = new Recording() {
            @Override
            public void set(int id, int mask, ScreenElement.Fields values) {
                calls.add("set " + id);
            }
        };
        for (int id = 0; id < 2; id++)
            deaf.elements.put(ScreenElement.create(id, ElementKind.FILL, 0, 0, 50, 50), id, 0, VIEWPORT,
                    hash -> true, ScreenElements.MAX_TOTAL_TEXT);
        EditSession held = new EditSession(deaf, () -> now);
        held.edit(Map.of(1, held.shown(1).withBox(30, 0, 50, 50)), null);
        held.tick();
        assertEquals(30, held.shown(1).x(), "held while the server has not answered");
        for (int i = 0; i < EditSession.HOLD_TICKS; i++)
            held.tick();
        assertEquals(0, held.shown(1).x(), "dropped after the hold");
    }

    @Test
    void fontIsProvidedBeforeTheEditNamingIt() {
        target.elements.put(ScreenElement.create(5, ElementKind.TEXT, VIEWPORT), 3, 0, VIEWPORT, hash -> true,
                ScreenElements.MAX_TOTAL_TEXT);
        String font = com.nectariich.create_flippies.content.design_workbench.DesignText.TTF_PREFIX
                + "0123456789abcdef0123456789abcdef";
        session.edit(Map.of(5, session.shown(5).withFont(font)), null);
        session.flush();
        assertEquals("font 0123456789abcdef0123456789abcdef", target.calls.get(0));
        assertTrue(target.calls.get(1).startsWith("set 5"));
    }

    @Test
    void copesWithTheDisplayGone() {
        target.gone = true;
        assertTrue(session.shown().isEmpty());
        assertNull(session.shown(1));
        assertEquals(-1, session.indexOf(1));
        assertTrue(session.freeIds(3).isEmpty());
        session.edit(Map.of(1, ScreenElement.create(1, ElementKind.FILL, VIEWPORT)), null);
        session.put(List.of(ScreenElement.create(9, ElementKind.FILL, VIEWPORT)));
        session.remove(List.of(1));
        session.restack(List.of(2, 1, 0));
        session.tick();
        session.flush();
        assertFalse(session.undo());
        assertTrue(target.calls.isEmpty());
    }

    @Test
    void undoOfAnEditToADeletedElementIsSkipped() {
        session.edit(Map.of(1, moved(1, 30)), null);
        session.flush();
        target.elements.remove(1);
        assertTrue(session.undo());
        assertNull(target.elements.get(1));
        assertNotNull(target.elements.get(2));
    }
}
