package com.nectariich.create_flippies.content.plc.mcu;

/**
 * The Cortex-M3 taking a fault the sketch cannot recover from. Thrown by memory
 * accesses and by the VM. STM32duino's default handler is an infinite loop, so the
 * CPU stops executing the sketch and stays powered, outputs frozen where they were.
 */
public class HardFault extends RuntimeException {

    public enum Kind {
        /** Access outside any mapped region (BusFault, escalated). */
        BUS_FAULT,
        /** Write to flash. */
        MEM_MANAGE,
        /** Bad function pointer, call through null, unaligned LDRD/STRD/LDM (UsageFault). */
        USAGE_FAULT,
        /** The stack pointer ran below the bottom of SRAM. */
        STACK_OVERFLOW,
        /** abort(), a failed assert, or the runtime library refusing to continue. */
        ABORT
    }

    private final Kind kind;
    private final long address;

    public HardFault(Kind kind, long address, String message) {
        super(message);
        this.kind = kind;
        this.address = address;
    }

    public Kind kind() {
        return kind;
    }

    /** The faulting address, or -1 when there is none. */
    public long address() {
        return address;
    }
}
