package com.nectariich.create_flippies.content.plc.mcu;

import java.util.List;

/**
 * Everything the Arduino core contributes at compile time: a prelude of C source
 * (what Arduino.h would declare as macros, constants, typedefs and inline
 * functions) that is preprocessed ahead of every sketch, and the builtins with
 * their prototypes. Plus enough to size the image as the real toolchain would.
 */
public interface ArduinoLibrary {

    /** C source preprocessed before the sketch, as if by an implicit {@code #include <Arduino.h>}. */
    String prelude();

    List<Builtin> builtins();

    /** Flash an empty sketch already costs: vector table, HAL, core, startup, libc bits. */
    int coreFlashBytes();

    /** SRAM the core itself already uses in .data + .bss before any sketch global. */
    int coreRamBytes();

    /** Extra flash the linker pulls in when a sketch references this builtin by name. */
    int flashBytesFor(String builtinName);
}
