package com.nectariich.create_flippies.content.computer.machine;

/** Where a machine is in its power cycle (spec 1.5). */
public enum MachineState {
    /** Not powered: never powered on, or switched off by the host. */
    OFF,
    /** Powered and running (or idle in its wait instruction). */
    RUNNING,
    /** Powered, but the CPU stopped for good until reset (for example a double fault). */
    HALTED,
    /** The guest powered the machine off through the system controller; see {@link Machine#exitCode()}. */
    OFF_BY_GUEST
}
