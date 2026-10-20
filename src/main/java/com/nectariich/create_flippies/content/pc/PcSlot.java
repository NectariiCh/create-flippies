package com.nectariich.create_flippies.content.pc;

/**
 * The places in a case a part can be (spec 11.2.3, 11.2.8). The order is the
 * saved order of the case's {@code Parts} list and the bit order of the synced
 * parts mask, so append only.
 */
public enum PcSlot {
    FRONT_PANEL,
    MOTHERBOARD,
    CPU,
    RAM_A,
    RAM_B,
    HDD,
    FLOPPY;

    /** The parts that sit inside the case and need the PC off and the panel off. */
    public boolean internal() {
        return this != FRONT_PANEL && this != FLOPPY;
    }

    /** The parts that sit on the motherboard. */
    public boolean onMotherboard() {
        return this == CPU || this == RAM_A || this == RAM_B || this == HDD;
    }

    public boolean isRam() {
        return this == RAM_A || this == RAM_B;
    }

    public int bit() {
        return 1 << ordinal();
    }

    public String langKey() {
        return "gui.create_flippies.pc.slot." + name().toLowerCase(java.util.Locale.ROOT);
    }
}
