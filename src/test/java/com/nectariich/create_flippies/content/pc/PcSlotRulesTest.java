package com.nectariich.create_flippies.content.pc;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.EnumSet;
import java.util.Set;

import com.nectariich.create_flippies.content.pc.PcSlotRules.Refusal;

import org.junit.jupiter.api.Test;

class PcSlotRulesTest {

    private static Set<PcSlot> of(PcSlot... slots) {
        Set<PcSlot> set = EnumSet.noneOf(PcSlot.class);
        set.addAll(java.util.List.of(slots));
        return set;
    }

    @Test
    void panelMustBeOffForInternalParts() {
        assertEquals(Refusal.PANEL_ON, PcSlotRules.canInstall(PcSlot.MOTHERBOARD, of(PcSlot.FRONT_PANEL), false));
        assertEquals(Refusal.OK, PcSlotRules.canInstall(PcSlot.MOTHERBOARD, of(), false));
        assertEquals(Refusal.PANEL_ON, PcSlotRules.canRemove(PcSlot.CPU,
                of(PcSlot.FRONT_PANEL, PcSlot.MOTHERBOARD, PcSlot.CPU), false));
        // The panel itself comes off and goes back on whatever is inside, running or not
        assertEquals(Refusal.OK, PcSlotRules.canRemove(PcSlot.FRONT_PANEL, of(PcSlot.FRONT_PANEL), true));
        assertEquals(Refusal.OK, PcSlotRules.canInstall(PcSlot.FRONT_PANEL, of(PcSlot.MOTHERBOARD), true));
    }

    @Test
    void pcMustBeOffForInternalParts() {
        assertEquals(Refusal.RUNNING, PcSlotRules.canInstall(PcSlot.HDD, of(PcSlot.MOTHERBOARD), true));
        assertEquals(Refusal.RUNNING, PcSlotRules.canRemove(PcSlot.RAM_A, of(PcSlot.MOTHERBOARD, PcSlot.RAM_A), true));
        assertEquals(Refusal.OK, PcSlotRules.canRemove(PcSlot.RAM_A, of(PcSlot.MOTHERBOARD, PcSlot.RAM_A), false));
    }

    @Test
    void motherboardFirstAndLast() {
        for (PcSlot slot : new PcSlot[] {PcSlot.CPU, PcSlot.RAM_A, PcSlot.RAM_B, PcSlot.HDD})
            assertEquals(Refusal.NEEDS_MOTHERBOARD, PcSlotRules.canInstall(slot, of(), false), slot.name());
        assertEquals(Refusal.MOTHERBOARD_IN_USE,
                PcSlotRules.canRemove(PcSlot.MOTHERBOARD, of(PcSlot.MOTHERBOARD, PcSlot.HDD), false));
        assertEquals(Refusal.OK, PcSlotRules.canRemove(PcSlot.MOTHERBOARD, of(PcSlot.MOTHERBOARD), false));
        assertEquals(Refusal.OCCUPIED, PcSlotRules.canInstall(PcSlot.MOTHERBOARD, of(PcSlot.MOTHERBOARD), false));
        assertEquals(Refusal.EMPTY, PcSlotRules.canRemove(PcSlot.CPU, of(PcSlot.MOTHERBOARD), false));
    }

    @Test
    void ramSlotChoice() {
        assertEquals(PcSlot.RAM_B, PcSlotRules.ramSlotFor(PcSlot.RAM_B, of(PcSlot.MOTHERBOARD)));
        assertEquals(PcSlot.RAM_A, PcSlotRules.ramSlotFor(null, of(PcSlot.MOTHERBOARD)));
        assertEquals(PcSlot.RAM_B, PcSlotRules.ramSlotFor(PcSlot.RAM_A, of(PcSlot.MOTHERBOARD, PcSlot.RAM_A)));
        assertEquals(PcSlot.RAM_B, PcSlotRules.ramSlotFor(PcSlot.CPU, of(PcSlot.MOTHERBOARD, PcSlot.RAM_A)));
        // Both full: the aimed one, which then refuses as occupied
        Set<PcSlot> full = of(PcSlot.MOTHERBOARD, PcSlot.RAM_A, PcSlot.RAM_B);
        assertEquals(PcSlot.RAM_B, PcSlotRules.ramSlotFor(PcSlot.RAM_B, full));
        assertEquals(Refusal.OCCUPIED, PcSlotRules.canInstall(PcSlotRules.ramSlotFor(PcSlot.RAM_B, full), full, false));
    }

    @Test
    void floppyAlwaysAllowed() {
        for (boolean running : new boolean[] {false, true})
            for (Set<PcSlot> filled : java.util.List.of(of(), of(PcSlot.FRONT_PANEL), of(PcSlot.FRONT_PANEL,
                    PcSlot.MOTHERBOARD, PcSlot.CPU))) {
                assertEquals(Refusal.OK, PcSlotRules.canInstall(PcSlot.FLOPPY, filled, running));
                Set<PcSlot> withDisk = EnumSet.copyOf(filled.isEmpty() ? of(PcSlot.FLOPPY) : filled);
                withDisk.add(PcSlot.FLOPPY);
                assertEquals(Refusal.OK, PcSlotRules.canRemove(PcSlot.FLOPPY, withDisk, running));
            }
        assertEquals(Refusal.OCCUPIED, PcSlotRules.canInstall(PcSlot.FLOPPY, of(PcSlot.FLOPPY), false));
    }
}
