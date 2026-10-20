package com.nectariich.create_flippies.foundation.control;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;

import com.nectariich.create_flippies.content.indicator.IndicatorControlType;
import com.nectariich.create_flippies.content.indicator.IndicatorKind;

import net.minecraft.core.Direction;

import org.junit.jupiter.api.Test;

/**
 * A control may not be mounted exactly on top of another in the same block.
 *
 * The regression this guards: a large panel cell hosts its own controls, and a second
 * screwdriver click on a lamp already mounted there resolved to the panel's surface
 * and the same grid cell, so a fresh lamp went in at the identical mount. Everything
 * that picks a control (the menu, the wires, the press, the goggles) took the older
 * one underneath, while the newer one was drawn over it, so the lamp the player could
 * see neither lit nor took a colour. The mounts below are the ones a world with that
 * panel had saved.
 */
class ControlRackStackingTest {

    private static final IndicatorControlType LAMP = IndicatorControlType.of(IndicatorKind.MED_12V);

    /** A lamp on a large panel cell whose plate runs through the middle of the block. */
    private static final ControlMount ON_PANEL = new ControlMount(Direction.WEST, 0, 248, 168, 192, 0);

    private final List<HostedControl> controls = new ArrayList<>();
    private final TerminalSlots slots = new TerminalSlots();

    private void mount(ControlMount mount) {
        HostedControl control = LAMP.create();
        control.installAt(null, mount, slots.allocate(LAMP.terminalCount()));
        controls.add(control);
    }

    private PlacementRefusal place(ControlMount mount) {
        return ControlRack.refusal(controls, slots, LAMP.footprint(), LAMP.terminalCount(), mount);
    }

    @Test
    void anEmptyCellTakesTheLamp() {
        assertEquals(PlacementRefusal.NONE, place(ON_PANEL));
    }

    @Test
    void aSecondLampOnTheSameSpotIsRefused() {
        mount(ON_PANEL);
        assertEquals(PlacementRefusal.OVERLAPS, place(ON_PANEL));
    }

    @Test
    void turningTheSecondOneDoesNotGetItIn() {
        mount(ON_PANEL);
        ControlMount turned = new ControlMount(Direction.WEST, 0, 248, 168, 192, 1);
        assertEquals(PlacementRefusal.OVERLAPS, place(turned));
    }

    @Test
    void theNextCellAlongIsStillFree() {
        // The lamps on that panel stood three pixels apart, which is 48 anchor units
        mount(ON_PANEL);
        assertEquals(PlacementRefusal.NONE, place(new ControlMount(Direction.WEST, 0, 200, 168, 192, 0)));
    }

    @Test
    void sharingAPlaceIsTheSurfaceAndTheCentre() {
        assertTrue(ON_PANEL.sharesPlaceWith(new ControlMount(Direction.WEST, 0, 248, 168, 192, 3)));
        assertFalse(ON_PANEL.sharesPlaceWith(new ControlMount(Direction.WEST, 0, 249, 168, 192, 0)));
        assertFalse(ON_PANEL.sharesPlaceWith(new ControlMount(Direction.WEST, 0, 248, 169, 192, 0)));
        assertFalse(ON_PANEL.sharesPlaceWith(new ControlMount(Direction.WEST, 0, 248, 168, 224, 0)));
        assertFalse(ON_PANEL.sharesPlaceWith(new ControlMount(Direction.EAST, 0, 248, 168, 192, 0)));
        assertFalse(ON_PANEL.sharesPlaceWith(new ControlMount(Direction.WEST, 1, 248, 168, 192, 0)));
    }
}
