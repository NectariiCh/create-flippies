package com.nectariich.create_flippies.content.pc;

import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

/**
 * A part that goes into a PC case (spec 11.2.3). What kind of part it is decides
 * the slot; the families with variants subclass this with their data table row.
 */
public class PcPartItem extends Item {

    /** The kinds of part, each with the slot or slots it can go into. */
    public enum Kind {
        FRONT_PANEL,
        MOTHERBOARD,
        CPU,
        RAM,
        HDD,
        FLOPPY
    }

    private final Kind kind;

    public PcPartItem(Kind kind, Properties properties) {
        super(properties);
        this.kind = kind;
    }

    public Kind kind() {
        return kind;
    }

    /** The part kind of a stack, or null if it is not a PC part. */
    public static Kind kindOf(ItemStack stack) {
        return stack.getItem() instanceof PcPartItem part ? part.kind : null;
    }

    /** The slot a part of this kind goes into; RAM answers slot A and the case picks the actual one. */
    public static PcSlot slotOf(Kind kind) {
        return switch (kind) {
            case FRONT_PANEL -> PcSlot.FRONT_PANEL;
            case MOTHERBOARD -> PcSlot.MOTHERBOARD;
            case CPU -> PcSlot.CPU;
            case RAM -> PcSlot.RAM_A;
            case HDD -> PcSlot.HDD;
            case FLOPPY -> PcSlot.FLOPPY;
        };
    }
}
