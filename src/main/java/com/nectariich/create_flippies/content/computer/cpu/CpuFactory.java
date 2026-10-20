package com.nectariich.create_flippies.content.computer.cpu;

/** Builds the CPU for a machine; the machine wires its interrupt pins afterwards. */
@FunctionalInterface
public interface CpuFactory {

    Cpu create(CpuHost host);
}
