package com.nectariich.create_flippies.content.computer.bus;

/** An access the bus cannot complete. Thrown on the slow path only; carries no stack trace. */
public final class BusFault extends RuntimeException {

    public final int address;
    public final boolean write;

    public BusFault(int address, boolean write, String reason) {
        super(reason, null, false, false);
        this.address = address;
        this.write = write;
    }
}
