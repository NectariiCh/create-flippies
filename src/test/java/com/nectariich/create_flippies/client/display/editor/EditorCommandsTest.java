package com.nectariich.create_flippies.client.display.editor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Function;

import com.nectariich.create_flippies.client.display.ElementEditTarget;
import com.nectariich.create_flippies.content.decal.DecalArt;
import com.nectariich.create_flippies.content.display.element.ElementKind;
import com.nectariich.create_flippies.content.display.element.ScreenElement;
import com.nectariich.create_flippies.content.display.element.ScreenElements;
import com.nectariich.create_flippies.content.display.element.Viewport;

import net.minecraft.network.chat.Component;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The editor's commands over a local layout, with the system clipboard and the picture
 * lookup swapped for test doubles: copy and paste (offset only back onto the source),
 * cut and duplicate, the cap, the four restacking commands and nudging.
 */
class EditorCommandsTest {

    private static final Viewport VIEWPORT = EditSessionTest.VIEWPORT;

    private final EditorCommands.Clipboard savedClipboard = EditorCommands.clipboard;
    private final Function<String, DecalArt> savedArts = EditorCommands.arts;

    private String clip;
    private final List<Component> statuses = new ArrayList<>();
    private EditSessionTest.Recording target;
    private EditorContext context;
    private EditorCommands commands;
    private String sourceKey = "display:A";

    @BeforeEach
    void setUp() {
        EditorCommands.clipboard = new EditorCommands.Clipboard() {
            @Override
            public String get() {
                return clip;
            }

            @Override
            public void set(String text) {
                clip = text;
            }
        };
        target = new EditSessionTest.Recording();
        for (int id = 0; id < 4; id++)
            target.elements.put(ScreenElement.create(id, ElementKind.FILL, 10 * id, 10, 50, 50), id, 0, VIEWPORT,
                    hash -> true, ScreenElements.MAX_TOTAL_TEXT);
        context = new EditorContext(host(), null, new EditorContext.Services() {
            @Override
            public void openContextMenu(double x, double y, List<ContextMenu.Entry> entries) {}

            @Override
            public void status(Component message) {
                statuses.add(message);
            }

            @Override
            public void importSvg(Consumer<String> done) {}
        });
        commands = context.commands();
    }

    @AfterEach
    void tearDown() {
        EditorCommands.clipboard = savedClipboard;
        EditorCommands.arts = savedArts;
    }

    private EditorHost host() {
        return new EditorHost() {
            @Override
            public ElementEditTarget target() {
                return target;
            }

            @Override
            public GlassView view() {
                return null;
            }

            @Override
            public Object owner() {
                return target.elements;
            }

            @Override
            public String sourceKey() {
                return sourceKey;
            }

            @Override
            public boolean canRotate() {
                return false;
            }

            @Override
            public void rotate() {}

            @Override
            public void close() {}

            @Override
            public Component title() {
                return Component.literal("test");
            }
        };
    }

    private List<Integer> order() {
        return target.elements.snapshot().stream().map(ScreenElement::id).toList();
    }

    @Test
    void pasteOntoTheSameDisplayIsOffset() {
        context.selection.set(List.of(1, 2));
        commands.copy();
        assertTrue(commands.canPaste());
        commands.paste();
        assertEquals(List.of(4, 5), context.selection.ids());
        assertEquals(20, target.elements.get(4).x());
        assertEquals(20, target.elements.get(4).y());
        assertEquals(30, target.elements.get(5).x());
        assertEquals(List.of(0, 1, 2, 3, 4, 5), order());
        assertTrue(context.session.undo());
        assertNull(target.elements.get(4), "a paste is one step");
        assertNull(target.elements.get(5));
    }

    @Test
    void pasteOntoAnotherDisplayKeepsPositions() {
        context.selection.set(List.of(1));
        commands.copy();
        sourceKey = "display:B";
        commands.paste();
        assertEquals(10, target.elements.get(4).x());
        assertEquals(10, target.elements.get(4).y());
    }

    @Test
    void pasteIsClampedToTheViewport() {
        clip = ElementClipboard.write(List.of(ScreenElement.create(9, ElementKind.FILL, 5000, 5000, 50, 50)), "other");
        commands.paste();
        ScreenElement pasted = target.elements.get(4);
        assertEquals(VIEWPORT.width() - 50, pasted.x());
        assertEquals(VIEWPORT.height() - 50, pasted.y());
    }

    @Test
    void pasteRefusedPastTheCap() {
        List<ScreenElement> many = new ArrayList<>();
        for (int i = 0; i < ScreenElements.MAX - 3; i++)
            many.add(ScreenElement.create(i, ElementKind.FILL, VIEWPORT));
        clip = ElementClipboard.write(many, "other");
        commands.paste();
        assertEquals(4, target.elements.size());
        assertEquals(1, statuses.size());
        assertFalse(context.session.canUndo());
    }

    @Test
    void garbageClipboardDoesNothing() {
        clip = "not elements";
        assertFalse(commands.canPaste());
        commands.paste();
        assertEquals(4, target.elements.size());
    }

    @Test
    void cutCopiesThenDeletesInOneStep() {
        context.selection.set(List.of(2));
        int generation = target.elements.generation(2);
        commands.cut();
        assertNull(target.elements.get(2));
        assertNotNull(ElementClipboard.read(clip));
        assertTrue(context.selection.isEmpty());
        context.session.undo();
        assertNotNull(target.elements.get(2));
        assertEquals(generation, target.elements.generation(2));
        assertFalse(context.session.canUndo());
    }

    @Test
    void duplicateLeavesTheClipboardAlone() {
        clip = "untouched";
        context.selection.set(List.of(0));
        commands.duplicate();
        assertEquals("untouched", clip);
        assertEquals(10, target.elements.get(4).x());
        assertEquals(20, target.elements.get(4).y());
        assertEquals(List.of(4), context.selection.ids());
    }

    @Test
    void lockedElementsStillCopyAndDelete() {
        target.elements.setFields(1, ScreenElement.FIELD_LOCKED,
                target.elements.get(1).toBuilder().locked(true).build().fields(), VIEWPORT, hash -> true);
        context.selection.set(List.of(1));
        commands.copy();
        assertNotNull(clip);
        commands.delete();
        assertNull(target.elements.get(1));
    }

    @Test
    void restackingCommands() {
        context.selection.set(List.of(0, 1));
        commands.raise();
        assertEquals(List.of(2, 0, 1, 3), order(), "a block rises a place as one");
        commands.bringToFront();
        assertEquals(List.of(2, 3, 0, 1), order());
        assertFalse(commands.canRaise());
        assertTrue(commands.canLower());
        commands.lower();
        assertEquals(List.of(2, 0, 1, 3), order());
        commands.sendToBack();
        assertEquals(List.of(0, 1, 2, 3), order());
        assertFalse(commands.canLower());
        context.session.undo();
        assertEquals(List.of(2, 0, 1, 3), order());
    }

    @Test
    void raiseWithAGap() {
        context.selection.set(List.of(0, 2));
        commands.raise();
        assertEquals(List.of(1, 0, 3, 2), order());
    }

    @Test
    void nudgeIsOneStepPerPressAndStopsAtTheEdge() {
        context.selection.set(List.of(0, 3));
        commands.nudge(-5, 0);
        context.session.flush();
        assertEquals(0, target.elements.get(0).x(), "the group stops when its outermost reaches the edge");
        assertEquals(30, target.elements.get(3).x());
        commands.nudge(0, 10);
        context.session.flush();
        assertEquals(20, target.elements.get(0).y());
        context.session.undo();
        assertEquals(10, target.elements.get(0).y());
        context.session.undo();
        assertEquals(0, target.elements.get(0).x());
        assertEquals(30, target.elements.get(3).x(), "a nudge that could not move did not make a step");
    }

    @Test
    void addAtCentresAndSelects() {
        commands.addAt(ElementKind.ELLIPSE, 200, 100, null);
        ScreenElement added = target.elements.get(4);
        assertEquals(ElementKind.ELLIPSE, added.kind());
        assertEquals(200, added.x() + added.w() / 2);
        assertEquals(100, added.y() + added.h() / 2);
        assertEquals(List.of(4), context.selection.ids());
        commands.addAt(ElementKind.FILL, 0, 0, null);
        assertEquals(0, target.elements.get(5).x(), "clamped");
    }

    @Test
    void addSvgKeepsThePictureAspect() {
        EditorCommands.arts = hash -> new DecalArt("pic", 4, 1, List.of());
        commands.addAt(ElementKind.SVG, 200, 100, "0123456789abcdef0123456789abcdef");
        ScreenElement added = target.elements.get(4);
        assertEquals("0123456789abcdef0123456789abcdef", added.art());
        assertEquals(4 * added.h(), added.w());
    }
}
