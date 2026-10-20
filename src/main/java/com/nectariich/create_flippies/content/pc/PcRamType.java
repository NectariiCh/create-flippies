package com.nectariich.create_flippies.content.pc;

/** The RAM sticks (spec 11.2.3), with their draw at the internal rails (spec 11.2.4). */
public enum PcRamType {
    RAM_512K("ram_512k", 512 * 1024, 0.5),
    RAM_1M("ram_1m", 1024 * 1024, 0.75),
    RAM_4M("ram_4m", 4 * 1024 * 1024, 1.5),
    RAM_16M("ram_16m", 16 * 1024 * 1024, 2.5),
    RAM_64M("ram_64m", 64 * 1024 * 1024, 4);

    private final String id;
    private final int bytes;
    private final double watts;

    PcRamType(String id, int bytes, double watts) {
        this.id = id;
        this.bytes = bytes;
        this.watts = watts;
    }

    public String id() {
        return id;
    }

    public int bytes() {
        return bytes;
    }

    public double watts() {
        return watts;
    }
}
