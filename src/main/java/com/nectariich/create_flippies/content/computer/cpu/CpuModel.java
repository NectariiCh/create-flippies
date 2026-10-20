package com.nectariich.create_flippies.content.computer.cpu;

import java.nio.ByteOrder;

/** The CPUs a PC can be built with, with the real chips' bus facts. */
public enum CpuModel {
    CPU_8086("8086", 5_000_000, 16, 20, ByteOrder.LITTLE_ENDIAN, false),
    CPU_8088("8088", 4_772_727, 8, 20, ByteOrder.LITTLE_ENDIAN, false),
    CPU_68000("68000", 8_000_000, 16, 24, ByteOrder.BIG_ENDIAN, true),
    CPU_68040("68040", 25_000_000, 32, 32, ByteOrder.BIG_ENDIAN, true),
    ARM2("ARM2", 8_000_000, 32, 26, ByteOrder.LITTLE_ENDIAN, false),
    MIPS_R3000("MIPS R3000", 25_000_000, 32, 32, ByteOrder.LITTLE_ENDIAN, false),
    RV32I("RV32I", 10_000_000, 32, 32, ByteOrder.LITTLE_ENDIAN, true);

    private final String displayName;
    private final int defaultClockHz;
    private final int dataBusBits;
    private final int addressBits;
    private final ByteOrder byteOrder;
    private final boolean emulated;

    CpuModel(String displayName, int defaultClockHz, int dataBusBits, int addressBits,
             ByteOrder byteOrder, boolean emulated) {
        this.displayName = displayName;
        this.defaultClockHz = defaultClockHz;
        this.dataBusBits = dataBusBits;
        this.addressBits = addressBits;
        this.byteOrder = byteOrder;
        this.emulated = emulated;
    }

    public String displayName() { return displayName; }

    public int defaultClockHz() { return defaultClockHz; }

    public int dataBusBits() { return dataBusBits; }

    public int addressBits() { return addressBits; }

    public ByteOrder byteOrder() { return byteOrder; }

    /** Whether an emulator exists yet. */
    public boolean emulated() { return emulated; }
}
