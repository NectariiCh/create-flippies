package com.nectariich.create_flippies.content.plc.mcu;

/**
 * A function the Arduino core provides, as a C prototype the compiler checks calls
 * against and an implementation the VM calls. Implementations are stateless; any
 * state lives on the chip, reached through {@link Invocation#environment()}.
 */
public interface Builtin {

    /**
     * The C/C++ prototype, e.g. {@code "void digitalWrite(uint32_t ulPin, uint32_t ulVal)"}.
     * Overloads are separate builtins with the same name and different prototypes.
     */
    String prototype();

    void invoke(Invocation call);
}
