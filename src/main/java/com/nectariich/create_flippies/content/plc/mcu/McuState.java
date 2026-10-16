package com.nectariich.create_flippies.content.plc.mcu;

/** What the PLC is doing, as its menu and tooltip report it. */
public enum McuState {
    /** Supply below brown-out: nothing runs, every output is off. */
    UNPOWERED,
    /** Powered but NRST is held by the RESET terminal. */
    IN_RESET,
    /** Powered with blank flash. */
    NO_PROGRAM,
    /** Being programmed: held in reset until the image is written. */
    PROGRAMMING,
    RUNNING,
    /** In the HardFault loop; outputs frozen where they were. */
    HARD_FAULT
}
