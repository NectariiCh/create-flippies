package com.nectariich.create_flippies.content.computer.bus;

/** One level interrupt line, as a device drives it. May be called while the CPU is inside run. */
@FunctionalInterface
public interface IrqSink {

    IrqSink NONE = level -> { };

    void set(boolean level);
}
