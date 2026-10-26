package com.nectariich.create_flippies.content.design_workbench;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import com.nectariich.create_flippies.content.design_workbench.screen.ScreenSurface;
import com.nectariich.create_flippies.content.display.DisplayPins;
import com.nectariich.create_flippies.content.display.element.ElementKind;
import com.nectariich.create_flippies.content.display.element.ScreenElement;
import com.nectariich.create_flippies.content.display.element.ScreenElements;
import com.nectariich.create_flippies.content.display.element.TestElements;
import com.nectariich.create_flippies.content.display.element.Viewport;
import com.nectariich.create_flippies.content.indicator.IndicatorAction;
import com.nectariich.create_flippies.content.indicator.IndicatorRating;
import com.nectariich.create_flippies.content.procedural_gauge.SupplyVoltage;

import net.minecraft.nbt.CompoundTag;
import org.junit.jupiter.api.Test;

/**
 * A designed screen as a mounted control: it starts from its design's authored
 * layout, keeps its own elements from then on through saves and syncs, is laid out
 * in its design's viewport, and stands on the displays' barrel and pins.
 */
class DesignedScreenTest {

    private static final String HASH = "0".repeat(32);

    @Test
    void aPlacedScreenStartsFromItsDesignsLayoutAndThenKeepsItsOwn() {
        ScreenElements authored = new ScreenElements();
        Viewport viewport = surface(null).viewport();
        TestElements.add(authored, ElementKind.FILL, viewport);
        ScreenElement line = TestElements.add(authored, ElementKind.TEXT, viewport);
        ControlDesign design = design(surface(authored));

        DesignedScreenControl control = new DesignedScreenControl(DesignedScreenControlType.get());
        control.mint(design);
        assertEquals(design.screen().layout().snapshot(), control.elements().snapshot());
        assertEquals(design.screen().viewport(), control.viewport());
        assertEquals(SupplyVoltage.V5, control.supply());

        // A link writes into the screen's own copy, never the design's
        assertTrue(control.elements().acceptLinked(line.id(), 0, "42 bar"));
        assertEquals("", control.design().screen().layout().get(line.id()).text());

        // Saved and loaded, the screen keeps what it was told, not the layout
        CompoundTag saved = new CompoundTag();
        control.write(saved);
        DesignedScreenControl loaded = new DesignedScreenControl(DesignedScreenControlType.get());
        loaded.read(saved);
        assertEquals("42 bar", loaded.elements().get(line.id()).text());
        assertEquals(design.write(), loaded.design().write());

        // A sync of the same design reads the elements in place and keeps the footprint
        ScreenElements live = loaded.elements();
        var footprint = loaded.footprint();
        loaded.elements().remove(line.id());
        loaded.read(saved);
        assertSame(live, loaded.elements());
        assertSame(footprint, loaded.footprint());
        assertEquals("42 bar", loaded.elements().get(line.id()).text());

        // A save from before the screen had elements of its own starts from the layout
        CompoundTag designOnly = new CompoundTag();
        designOnly.put(ControlDesign.TAG, design.write());
        DesignedScreenControl old = new DesignedScreenControl(DesignedScreenControlType.get());
        old.read(designOnly);
        assertEquals(design.screen().layout().snapshot(), old.elements().snapshot());
    }

    @Test
    void aScreenWithNoDesignStillHasSomewhereToDraw() {
        DesignedScreenControl blank = new DesignedScreenControl(DesignedScreenControlType.get());
        assertEquals(ScreenSurface.defaults().viewport(), blank.viewport());
        assertEquals(1, blank.elements().size());
        assertEquals(ScreenSurface.DEFAULT_SUPPLY, blank.supply());
        assertEquals(DisplayPins.TERMINALS, DesignedScreenControlType.get().terminalCount());
    }

    @Test
    void itStandsOnTheDisplaysRear() {
        ControlDesign design = design(null);
        DesignedScreenControl control = new DesignedScreenControl(DesignedScreenControlType.get());
        control.mint(design);
        assertNotSame(DesignedScreenControlType.get().footprint(), control.footprint());
        double[] front = design.front();
        assertTrue(java.util.Arrays.deepEquals(DisplayPins.rearFootprint(front).boxes(),
                DesignedFootprints.of(design).boxes()));
        assertTrue(java.util.Arrays.deepEquals(DisplayPins.rearFootprint(front).boxes(), control.footprint().boxes()));
        // The front, then the barrel, three contacts and five unwired pins
        assertEquals(1 + 1 + 3 + 5, control.footprint().boxes().length);
    }

    private static ScreenSurface surface(ScreenElements layout) {
        return new ScreenSurface("Screen#glass", null, null, false, false,
                new float[] { 0, 1, 0, 15, 0, 0, 0, 0, 8 }, SupplyVoltage.V5, layout);
    }

    private static ControlDesign design(ScreenSurface surface) {
        return new ControlDesign(DesignKind.SCREEN, HASH, "Tank", List.of(), List.of(), 1, 0, 0, 0,
                ControlDesign.defaultPoses(DesignKind.SCREEN), null, new double[] { 0, 0, 0, 15, 2, 8 },
                new double[] { 6, 0, 6, 10, 2, 10 }, IndicatorAction.SPRING_RETURN, IndicatorRating.V24, List.of(),
                0, null, null, null, null, null, surface);
    }
}
