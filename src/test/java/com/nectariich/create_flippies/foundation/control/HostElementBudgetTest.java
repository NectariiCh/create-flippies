package com.nectariich.create_flippies.foundation.control;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;

import com.nectariich.create_flippies.content.design_workbench.DesignedScreenControlType;
import com.nectariich.create_flippies.content.display.element.ElementDisplay;
import com.nectariich.create_flippies.content.display.element.ElementKind;
import com.nectariich.create_flippies.content.display.element.ScreenElement;
import com.nectariich.create_flippies.content.display.element.ScreenElements;
import com.nectariich.create_flippies.content.display.element.TestElements;
import com.nectariich.create_flippies.content.indicator.IndicatorControlType;
import com.nectariich.create_flippies.content.indicator.IndicatorKind;
import com.nectariich.create_flippies.network.EditScreenElementPacket;

import net.minecraft.core.BlockPos;

import org.junit.jupiter.api.Test;

/**
 * All the displays in one host share a budget of elements and text, so the host's
 * block entity tag stays far under the 2 MiB a client will read: a display that would
 * take the host over is refused as it is mounted, and an add the host has no room for
 * is refused like an add to a full display.
 */
class HostElementBudgetTest {

    private static HostedControl screen(int elements, String text) {
        HostedControl control = DesignedScreenControlType.get().create();
        ScreenElements held = ((ElementDisplay) control).elements();
        while (held.size() < elements)
            TestElements.add(held, ElementKind.TEXT, ((ElementDisplay) control).viewport());
        if (!text.isEmpty())
            held.acceptLinked(0, 0, text);
        return control;
    }

    @Test
    void aDisplayThatWouldTakeTheHostOverIsRefused() {
        List<HostedControl> controls = new ArrayList<>();
        for (int i = 0; i < HostElementBudget.MAX_ELEMENTS / ScreenElements.MAX; i++) {
            HostedControl full = screen(ScreenElements.MAX, "");
            assertTrue(HostElementBudget.fits(controls, full));
            controls.add(full);
        }
        assertFalse(HostElementBudget.fits(controls, screen(1, "")), "every element is spoken for");
        assertTrue(HostElementBudget.fits(controls, IndicatorControlType.of(IndicatorKind.MED_12V).create()),
                "a control with no elements always fits");
    }

    @Test
    void textCountsTowardsTheHostToo() {
        List<HostedControl> controls = new ArrayList<>();
        String line = "x".repeat(ScreenElement.MAX_TEXT);
        while (controls.size() * ScreenElement.MAX_TEXT < HostElementBudget.MAX_TEXT)
            controls.add(screen(1, line));
        assertFalse(HostElementBudget.fits(controls, screen(1, "y")));
        assertTrue(HostElementBudget.fits(controls, screen(1, "")));
    }

    @Test
    void aDisplayInNoHostHasItsOwnCapsOnly() {
        ElementDisplay loose = (ElementDisplay) screen(1, "");
        assertTrue(HostElementBudget.roomForElement(loose));
        assertEquals(ScreenElements.MAX_TOTAL_TEXT, HostElementBudget.textCap(loose));
    }

    @Test
    void anAddTheHostHasNoRoomForIsRefused() {
        ScreenElements elements = new ScreenElements();
        ElementDisplay display = (ElementDisplay) screen(1, "");
        EditScreenElementPacket add = EditScreenElementPacket.put(BlockPos.ZERO, null,
                ScreenElement.create(0, ElementKind.FILL, display.viewport()), 0, 0);
        assertFalse(add.apply(elements, display.viewport(), hash -> false, false, 0).changed());
        assertTrue(elements.isEmpty());
        assertTrue(add.apply(elements, display.viewport(), hash -> false, true, 0).changed());
        assertEquals(ElementKind.FILL, elements.get(0).kind());
    }
}
