package com.nectariich.create_flippies.client.display.editor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import com.nectariich.create_flippies.content.display.element.ElementKind;
import com.nectariich.create_flippies.content.display.element.ElementPaint;
import com.nectariich.create_flippies.content.display.element.ScreenElement;
import com.nectariich.create_flippies.content.display.element.Viewport;

import org.junit.jupiter.api.Test;

/** Elements survive a trip through the clipboard as text, and text that is not elements reads as nothing. */
class ElementClipboardTest {

    private static final Viewport VIEWPORT = new Viewport(400, 200);

    @Test
    void roundTrip() {
        ScreenElement text = ScreenElement.create(3, ElementKind.TEXT, VIEWPORT).toBuilder().text("Hello \"world\"")
                .name("title").locked(true).build();
        ScreenElement line = ScreenElement.create(7, ElementKind.LINE, VIEWPORT).withEnds(300, 10, 20, 150);
        ScreenElement fill = ScreenElement.create(0, ElementKind.FILL, 5, 6, 70, 80).toBuilder()
                .paint(ElementPaint.SOLID).build();
        String written = ElementClipboard.write(List.of(text, line, fill), "display@1,2,3");
        assertTrue(written.startsWith(ElementClipboard.MARKER + "\n"));
        ElementClipboard.Contents contents = ElementClipboard.read(written);
        assertNotNull(contents);
        assertEquals("display@1,2,3", contents.source());
        assertEquals(List.of(text, line, fill), contents.elements());
    }

    @Test
    void toleratesSurroundingWhitespace() {
        ScreenElement fill = ScreenElement.create(0, ElementKind.FILL, VIEWPORT);
        ElementClipboard.Contents contents = ElementClipboard.read("  " + ElementClipboard.write(List.of(fill), "a")
                + "\n\n");
        assertNotNull(contents);
        assertEquals(List.of(fill), contents.elements());
    }

    @Test
    void rejectsGarbage() {
        assertNull(ElementClipboard.read(null));
        assertNull(ElementClipboard.read(""));
        assertNull(ElementClipboard.read("hello"));
        assertNull(ElementClipboard.read("{Elements:[{I:0b}]}"), "no marker");
        assertNull(ElementClipboard.read(ElementClipboard.MARKER + "\n{Elements:[{I:0b"), "broken SNBT");
        assertNull(ElementClipboard.read(ElementClipboard.MARKER + "\n{Other:1}"), "no element list");
        assertNull(ElementClipboard.read(ElementClipboard.MARKER + "\n[1,2,3]"), "not a compound");
    }

    @Test
    void dropsElementsThatCannotBe() {
        ElementClipboard.Contents contents = ElementClipboard.read(ElementClipboard.MARKER
                + "\n{Source:\"x\",Elements:[{I:-3b,K:0b},{I:2b,K:99b},{I:4b,K:1b,X:10s,Y:10s,W:20s,H:20s}]}");
        assertNotNull(contents);
        assertEquals(1, contents.elements().size());
        assertEquals(4, contents.elements().get(0).id());
    }
}
