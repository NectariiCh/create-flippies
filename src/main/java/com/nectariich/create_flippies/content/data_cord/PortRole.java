package com.nectariich.create_flippies.content.data_cord;

/**
 * Which end of a link a port is (spec 11.2.5). Two ports pair when they are both
 * PEER (PC to PC: ethernet, COM), or one is HOST and the other DEVICE (a case's
 * PS/2 port and a keyboard), or one is SOURCE and the other SINK (a case's VGA
 * port and a monitor).
 */
public enum PortRole {
    PEER,
    HOST,
    DEVICE,
    SOURCE,
    SINK;

    /** The role this one pairs with. */
    public PortRole partner() {
        return switch (this) {
            case PEER -> PEER;
            case HOST -> DEVICE;
            case DEVICE -> HOST;
            case SOURCE -> SINK;
            case SINK -> SOURCE;
        };
    }
}
