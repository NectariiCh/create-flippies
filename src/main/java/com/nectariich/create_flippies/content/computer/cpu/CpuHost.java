package com.nectariich.create_flippies.content.computer.cpu;

import com.nectariich.create_flippies.content.computer.bus.Bus;
import com.nectariich.create_flippies.content.computer.bus.CycleClock;

import java.util.Objects;
import java.util.function.IntSupplier;

/**
 * Everything a CPU is connected to.
 *
 * @param memory        the memory bus
 * @param io            the port bus (8086/8088 IN/OUT); null for CPUs without port I/O
 * @param interruptAck  supplies the vector number in an interrupt acknowledge cycle (8086/8088);
 *                      null for CPUs that do not ask
 * @param resetDevices  records a request to reset every device (68000 RESET instruction); the
 *                      machine performs it after run returns; null means no-op
 * @param resetPc       where execution starts after reset, for CPUs whose reset PC is not fixed
 *                      by the chip (RV32I); ignored by CPUs that read a reset vector
 * @param strictFpu     68040 only: the FPU traps what the real chip leaves to its support
 *                      package (the machine config's strict FPU option, spec 10.4)
 */
public record CpuHost(CpuModel model, int clockHz, Bus memory, Bus io, CycleClock clock,
                      IntSupplier interruptAck, Runnable resetDevices, int resetPc, boolean strictFpu) {

    public CpuHost {
        Objects.requireNonNull(model, "model");
        Objects.requireNonNull(memory, "memory");
        Objects.requireNonNull(clock, "clock");
        if (clockHz <= 0)
            throw new IllegalArgumentException("clockHz must be positive: " + clockHz);
        if (strictFpu && model != CpuModel.CPU_68040)
            throw new IllegalArgumentException("strict FPU needs a 68040, not a " + model.displayName());
    }

    /** A host whose CPU has no strict FPU (every model but a strict 68040). */
    public CpuHost(CpuModel model, int clockHz, Bus memory, Bus io, CycleClock clock,
                   IntSupplier interruptAck, Runnable resetDevices, int resetPc) {
        this(model, clockHz, memory, io, clock, interruptAck, resetDevices, resetPc, false);
    }

    /** A host with only a memory bus, as RV32I, ARM2, R3000 and tests use. */
    public static CpuHost of(CpuModel model, int clockHz, Bus memory, CycleClock clock, int resetPc) {
        return new CpuHost(model, clockHz, memory, null, clock, null, null, resetPc);
    }
}
