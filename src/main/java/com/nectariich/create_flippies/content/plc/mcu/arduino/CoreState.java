package com.nectariich.create_flippies.content.plc.mcu.arduino;

import com.nectariich.create_flippies.content.plc.mcu.ArduinoPins;
import com.nectariich.create_flippies.content.plc.mcu.Invocation;
import com.nectariich.create_flippies.content.plc.mcu.chip.Stm32f103;

/**
 * The STM32duino core's own globals: on the chip they sit in the core's .data/.bss
 * (counted in {@link ArduinoCore#coreRamBytes()}), so the chip keeps them for us and
 * drops them on every reset.
 */
final class CoreState {

    /** {@code g_digPinConfigured}: digitalWrite/digitalRead do nothing to a pin pinMode has not set up. */
    final boolean[] digitalConfigured = new boolean[ArduinoPins.COUNT];

    /** {@code g_anOutputPinConfigured}: the pin has had PWM started on it by analogWrite. */
    final boolean[] pwmConfigured = new boolean[ArduinoPins.COUNT];

    int readResolution = 10;
    int internalReadResolution = 12;
    int writeResolution = 8;
    int internalWriteResolution = 8;
    long writeFrequency = 1000;

    /** newlib's {@code _REENT->_new._reent._rand_next}, shared by rand() and random(). */
    long randNext = 1;

    /** strtok's saved position. */
    long strtokNext;

    static CoreState of(Invocation call) {
        return chip(call).runtimeState(CoreState::new);
    }

    static Stm32f103 chip(Invocation call) {
        return (Stm32f103) call.environment();
    }
}
