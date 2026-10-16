package com.nectariich.create_flippies.content.plc.mcu;

/**
 * The part the small PLC is built round, STM32F103CBT6 (LQFP48), as numbers.
 *
 * Everything in this package is plain Java with no Minecraft, Forge or Power Grid
 * import, so it can be compiled and tested on its own. Keep it that way.
 */
public final class Stm32f103cb {

    /** SYSCLK from the 8 MHz HSE through the PLL, x9. Cortex-M3, no FPU. */
    public static final long CPU_HZ = 72_000_000L;

    public static final long FLASH_BASE = 0x0800_0000L;
    public static final int FLASH_SIZE = 128 * 1024;

    public static final long SRAM_BASE = 0x2000_0000L;
    public static final int SRAM_SIZE = 20 * 1024;

    /** Flash wait states at 72 MHz (FLASH_ACR LATENCY = 2). */
    public static final int FLASH_WAIT_STATES = 2;

    /** STM32duino's linker script minimums: _Min_Heap_Size and _Min_Stack_Size. */
    public static final int MIN_HEAP = 0x200;
    public static final int MIN_STACK = 0x400;

    public static long nanosToCycles(long nanos) {
        return nanos * (CPU_HZ / 1_000_000L) / 1000L;
    }

    public static long cyclesToNanos(long cycles) {
        return cycles * 1000L / (CPU_HZ / 1_000_000L);
    }

    private Stm32f103cb() {}
}
