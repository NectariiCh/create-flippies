package com.nectariich.create_flippies.content.data_cord;

/**
 * What kind of connector a data port is. A data cord joins two ports of the same
 * type only (spec 11.2.5), and the cord is drawn in the type's colour.
 *
 * The declaration order is saved (a cord entity and the item's half-placed tag
 * name a type by ordinal), so append only.
 */
public enum PortType {
    PS2(0x8A4FC8),
    ETHERNET(0xD8B83A),
    COM(0x8C8C8C),
    VGA(0x3A62C8);

    private final int colour;

    PortType(int colour) {
        this.colour = colour;
    }

    /** The cord's colour, 0xRRGGBB: PS/2 purple, ethernet yellow, COM grey, VGA blue. */
    public int colour() {
        return colour;
    }

    public static PortType byOrdinal(int ordinal) {
        PortType[] all = values();
        return ordinal >= 0 && ordinal < all.length ? all[ordinal] : null;
    }
}
