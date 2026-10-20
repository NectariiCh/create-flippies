package com.nectariich.create_flippies.content.computer.machine;

import com.nectariich.create_flippies.content.computer.cpu.CpuModel;

/** The standard boot ROM of each model (spec 10.8.3): {@link BootRom} for RV32I, {@link M68kBootRom} for the 68k. */
public final class BootRoms {

    private BootRoms() {
    }

    /** A fresh copy of the model's ROM image; UnsupportedOperationException for a model without one. */
    public static byte[] image(CpuModel model) {
        return switch (model) {
            case RV32I -> BootRom.image();
            case CPU_68000, CPU_68040 -> M68kBootRom.image(model);
            default -> throw new UnsupportedOperationException(model.displayName() + " has no boot ROM yet");
        };
    }
}
