package com.nectariich.create_flippies.content.computer.cpu.m68k;

import com.nectariich.create_flippies.content.computer.cpu.CpuModel;

import java.util.Objects;

/**
 * How a 68k CPU is built (spec 10.3.1): the model and, for the 68040, whether the FPU is strict.
 * Saved state records both, and loading into a CPU built differently fails (spec 10.6).
 *
 * @param model     {@link CpuModel#CPU_68000} or {@link CpuModel#CPU_68040}
 * @param strictFpu 68040 only: the instructions and data types the real chip leaves to its
 *                  floating-point support package trap (vectors 11 and 55) as on bare
 *                  hardware, instead of being emulated (spec 10.4). A machine configuration
 *                  option, default off.
 */
public record M68kOptions(CpuModel model, boolean strictFpu) {

    public M68kOptions {
        Objects.requireNonNull(model, "model");
        if (model != CpuModel.CPU_68000 && model != CpuModel.CPU_68040)
            throw new IllegalArgumentException("not a 68k model: " + model.displayName());
        if (strictFpu && model != CpuModel.CPU_68040)
            throw new IllegalArgumentException("strict FPU needs a 68040, not a " + model.displayName());
    }

    public static M68kOptions m68000() {
        return new M68kOptions(CpuModel.CPU_68000, false);
    }

    public static M68kOptions m68040(boolean strictFpu) {
        return new M68kOptions(CpuModel.CPU_68040, strictFpu);
    }

    /** Whether the model has the built-in FPU (only the 68040). */
    public boolean hasFpu() {
        return model == CpuModel.CPU_68040;
    }
}
