package com.nectariich.create_flippies.content.pc;

/** The hard disks (spec 11.2.3). Capacities are binary, so every one is a whole number of 64 KiB chunks. */
public enum PcHddType {
    HDD_16M("hdd_16m", 16L * 1024 * 1024),
    HDD_64M("hdd_64m", 64L * 1024 * 1024),
    HDD_256M("hdd_256m", 256L * 1024 * 1024),
    HDD_1G("hdd_1g", 1024L * 1024 * 1024);

    private final String id;
    private final long bytes;

    PcHddType(String id, long bytes) {
        this.id = id;
        this.bytes = bytes;
    }

    public String id() {
        return id;
    }

    public long bytes() {
        return bytes;
    }
}
