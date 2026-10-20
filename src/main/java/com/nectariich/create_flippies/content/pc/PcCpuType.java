package com.nectariich.create_flippies.content.pc;

import com.nectariich.create_flippies.content.computer.cpu.CpuModel;

/**
 * The CPU items (spec 11.2.3): one model at one clock each. Adding one is a row
 * here, an item model and a lang line.
 *
 * Watts are the chip's draw at the PC's internal rails, idle (waiting for an
 * interrupt) and busy, blended by {@code Machine.busyFraction()} (spec 11.2.4).
 */
public enum PcCpuType {
    RV32I_10MHZ("cpu_rv32i_10mhz", CpuModel.RV32I, 10_000_000, 0.5, 2),
    RV32I_25MHZ("cpu_rv32i_25mhz", CpuModel.RV32I, 25_000_000, 1, 4),
    RV32I_50MHZ("cpu_rv32i_50mhz", CpuModel.RV32I, 50_000_000, 2, 8),
    M68000_8MHZ("cpu_68000_8mhz", CpuModel.CPU_68000, 8_000_000, 0.8, 1.5),
    M68000_12_5MHZ("cpu_68000_12_5mhz", CpuModel.CPU_68000, 12_500_000, 1, 2),
    M68040_25MHZ("cpu_68040_25mhz", CpuModel.CPU_68040, 25_000_000, 2, 5),
    M68040_33MHZ("cpu_68040_33mhz", CpuModel.CPU_68040, 33_000_000, 2.5, 6);

    private final String id;
    private final CpuModel model;
    private final int clockHz;
    private final double idleWatts;
    private final double busyWatts;

    PcCpuType(String id, CpuModel model, int clockHz, double idleWatts, double busyWatts) {
        this.id = id;
        this.model = model;
        this.clockHz = clockHz;
        this.idleWatts = idleWatts;
        this.busyWatts = busyWatts;
    }

    public String id() {
        return id;
    }

    public CpuModel model() {
        return model;
    }

    public int clockHz() {
        return clockHz;
    }

    public double idleWatts() {
        return idleWatts;
    }

    public double busyWatts() {
        return busyWatts;
    }

    /** Only the 68040 has an FPU, so only its items carry the strict FPU option. */
    public boolean hasStrictFpuOption() {
        return model == CpuModel.CPU_68040;
    }

    /** "RV32I @ 25 MHz", "68000 @ 12.5 MHz". */
    public String displayName() {
        double mhz = clockHz / 1e6;
        String clock = mhz == Math.rint(mhz) ? Long.toString((long) mhz) : Double.toString(mhz);
        return model.displayName() + " @ " + clock + " MHz";
    }
}
