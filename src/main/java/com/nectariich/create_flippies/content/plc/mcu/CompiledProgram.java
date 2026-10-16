package com.nectariich.create_flippies.content.plc.mcu;

/**
 * A linked image, ready to be flashed. Immutable and shareable: every CPU made from
 * it gets its own SRAM.
 */
public interface CompiledProgram {

    /** .text + .rodata + .data initialisers, including the core; what "Sketch uses N bytes" reports. */
    int flashBytes();

    /** .data + .bss, including the core; what "Global variables use N bytes" reports. */
    int staticRamBytes();

    /** The source it was built from, verbatim. */
    String source();

    /** A fresh CPU running this image, in reset. */
    CpuCore createCpu(McuEnvironment environment, CycleLedger ledger);
}
