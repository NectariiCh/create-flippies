package com.nectariich.create_flippies.content.pc;

import java.util.Locale;
import java.util.Set;

/**
 * What may go into and come out of a case, and when (spec 11.2.3). Pure: the case
 * asks with what it holds and what it is doing, and {@code PcInteractions} acts on
 * the answer.
 *
 * - The front panel must be off to reach anything inside.
 * - The PC must be off (no machine) for the motherboard, CPU, RAM and HDD.
 * - The CPU, RAM and HDD sit on the motherboard: they need it in, and it cannot
 *   come out while any of them is in.
 * - The floppy disk goes in and out at any time, panel on or off, PC on or off.
 */
public final class PcSlotRules {

    /** Why an install or removal was refused, or {@link #OK}. */
    public enum Refusal {
        OK,
        PANEL_ON,
        PANEL_OFF,
        RUNNING,
        NEEDS_MOTHERBOARD,
        MOTHERBOARD_IN_USE,
        OCCUPIED,
        EMPTY;

        public String langKey() {
            return "gui.create_flippies.pc.refusal." + name().toLowerCase(Locale.ROOT);
        }
    }

    private PcSlotRules() {}

    /** Whether a part may go into {@code slot}, given which slots are filled. */
    public static Refusal canInstall(PcSlot slot, Set<PcSlot> filled, boolean running) {
        if (filled.contains(slot))
            return Refusal.OCCUPIED;
        if (slot == PcSlot.FLOPPY)
            return Refusal.OK;
        // The panel itself goes back only onto an open case, which is the occupancy test above
        if (slot == PcSlot.FRONT_PANEL)
            return Refusal.OK;
        if (filled.contains(PcSlot.FRONT_PANEL))
            return Refusal.PANEL_ON;
        if (running)
            return Refusal.RUNNING;
        if (slot.onMotherboard() && !filled.contains(PcSlot.MOTHERBOARD))
            return Refusal.NEEDS_MOTHERBOARD;
        return Refusal.OK;
    }

    /** Whether the part in {@code slot} may come out. */
    public static Refusal canRemove(PcSlot slot, Set<PcSlot> filled, boolean running) {
        if (!filled.contains(slot))
            return Refusal.EMPTY;
        if (slot == PcSlot.FLOPPY || slot == PcSlot.FRONT_PANEL)
            return Refusal.OK;
        if (filled.contains(PcSlot.FRONT_PANEL))
            return Refusal.PANEL_ON;
        if (running)
            return Refusal.RUNNING;
        if (slot == PcSlot.MOTHERBOARD)
            for (PcSlot other : PcSlot.values())
                if (other.onMotherboard() && filled.contains(other))
                    return Refusal.MOTHERBOARD_IN_USE;
        return Refusal.OK;
    }

    /**
     * The RAM slot a stick goes into: the aimed one if it is empty, else the first
     * free one, else the aimed one (which then refuses as occupied). {@code aimed}
     * may be null when the click was not on a stick.
     */
    public static PcSlot ramSlotFor(PcSlot aimed, Set<PcSlot> filled) {
        if (aimed != null && aimed.isRam() && !filled.contains(aimed))
            return aimed;
        if (!filled.contains(PcSlot.RAM_A))
            return PcSlot.RAM_A;
        if (!filled.contains(PcSlot.RAM_B))
            return PcSlot.RAM_B;
        return aimed != null && aimed.isRam() ? aimed : PcSlot.RAM_A;
    }
}
