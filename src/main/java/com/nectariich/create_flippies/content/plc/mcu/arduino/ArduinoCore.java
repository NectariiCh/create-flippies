package com.nectariich.create_flippies.content.plc.mcu.arduino;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.nectariich.create_flippies.content.plc.mcu.ArduinoLibrary;
import com.nectariich.create_flippies.content.plc.mcu.Builtin;
import com.nectariich.create_flippies.content.plc.mcu.CycleClass;

/**
 * The STM32duino core for this board, as a library: the prelude a sketch is compiled
 * against and the functions it can call.
 *
 * Base Arduino is all here — digital, analog and advanced I/O, time, maths,
 * characters, random numbers, bits and bytes, external interrupts — together with the
 * C library calls sketches routinely make. Everything that moves data off the board
 * is not: no Serial (or any UART), SPI, Wire/I2C, USB, CAN, EEPROM, Stream or Print,
 * and no HardwareTimer class. shiftIn/shiftOut stay, being GPIO bit-banging, as does
 * pulseIn. malloc and new belong to the language runtime, which owns the heap.
 *
 * Builtins take the chip as {@code Invocation.environment()}, which must be a
 * {@link com.nectariich.create_flippies.content.plc.mcu.chip.Stm32f103}.
 */
public final class ArduinoCore implements ArduinoLibrary {

    public static final ArduinoCore INSTANCE = new ArduinoCore();

    /**
     * An empty sketch, as arduino-cli reports it for STM32duino with U(S)ART support
     * disabled and newlib-nano: vector table (0x130 bytes on the F103xB), startup,
     * SystemClock_Config, HAL init/RCC/GPIO/Cortex, the core's init and main, SysTick,
     * newlib-nano's start-up and the ctype table.
     */
    private static final int CORE_FLASH = 9_340;

    /**
     * The same build's .data + .bss: SystemCoreClock and uwTick, the core's pin
     * configuration bitmaps, HardwareTimer handles, the EXTI callback table
     * (16 std::function slots) and newlib's reentrancy struct.
     */
    private static final int CORE_RAM = 852;

    private final String prelude;
    private final List<Builtin> builtins;
    private final Map<String, Integer> flash;

    private ArduinoCore() {
        prelude = Prelude.build();
        List<Fn> all = new ArrayList<>();
        IoBuiltins.register(all);
        TimeBuiltins.register(all);
        MathBuiltins.register(all);
        CharBuiltins.register(all);
        LibcBuiltins.register(all);
        all.add(new Fn("float __builtin_inff(void)", 0, c -> {
            c.charge(CycleClass.ALU, 1);
            c.returnFloat(Float.POSITIVE_INFINITY);
        }));
        all.add(new Fn("float __builtin_nanf(const char* tag)", 0, c -> {
            c.charge(CycleClass.ALU, 1);
            c.returnFloat(Float.NaN);
        }));
        all.add(new Fn("double __builtin_huge_val(void)", 0, c -> {
            c.charge(CycleClass.ALU, 2);
            c.returnDouble(Double.POSITIVE_INFINITY);
        }));
        Map<String, Integer> sizes = new HashMap<>();
        for (Fn fn : all)
            sizes.merge(fn.name(), fn.flashBytes(), Math::max);
        builtins = Collections.unmodifiableList(new ArrayList<>(all));
        flash = Map.copyOf(sizes);
    }

    @Override
    public String prelude() {
        return prelude;
    }

    @Override
    public List<Builtin> builtins() {
        return builtins;
    }

    @Override
    public int coreFlashBytes() {
        return CORE_FLASH;
    }

    @Override
    public int coreRamBytes() {
        return CORE_RAM;
    }

    /**
     * What the linker adds when the sketch calls this function, by name (overloads
     * share their code, so a name is counted once). Functions that share a large
     * dependency split it: analogWrite and tone each carry about half of
     * HardwareTimer (~4.4 KB together), so a sketch using one or both is within about
     * 2 KB of the real figure.
     */
    @Override
    public int flashBytesFor(String builtinName) {
        return flash.getOrDefault(builtinName, 0);
    }
}
