package com.nectariich.create_flippies.content.plc.mcu;

/**
 * The board's Arduino pin numbering, frozen: the compiler's prelude, the chip and
 * the board all agree on it, and saved sketches depend on it.
 *
 * 0..7 are PA0..PA7, wired to DI0..DI7 through the 24 V input front ends.
 * 8..15 are PB0..PB7, wired to DO0..DO7 through the high-side output drivers.
 * 16 is PA11, the STATUS line, wired to the STLED terminal (LED_BUILTIN).
 * 17.. are the remaining LQFP48 GPIOs, which exist on the chip but reach no
 * terminal: PA8, PA9, PA10, PA12, PA13, PA14, PA15, PB8..PB15, PC13, PC14, PC15.
 * A0..A7 are aliases of 0..7 (ADC12_IN0..IN7).
 */
public final class ArduinoPins {

    /** Port index (0 = GPIOA, 1 = GPIOB, 2 = GPIOC) times 16 plus the bit, per Arduino pin number. */
    public static final int[] PORT_PIN = {
            0x00, 0x01, 0x02, 0x03, 0x04, 0x05, 0x06, 0x07,
            0x10, 0x11, 0x12, 0x13, 0x14, 0x15, 0x16, 0x17,
            0x0B,
            0x08, 0x09, 0x0A, 0x0C, 0x0D, 0x0E, 0x0F,
            0x18, 0x19, 0x1A, 0x1B, 0x1C, 0x1D, 0x1E, 0x1F,
            0x2D, 0x2E, 0x2F };

    public static final int COUNT = PORT_PIN.length;

    public static final int FIRST_DI = 0;
    public static final int FIRST_DO = 8;
    public static final int STATUS = 16;
    public static final int IO_PER_BANK = 8;

    /** The STM32 name of a pin, "PA0" to "PC15". */
    public static String name(int arduinoPin) {
        int pp = PORT_PIN[arduinoPin];
        return "P" + (char) ('A' + (pp >> 4)) + (pp & 0xF);
    }

    /** The Arduino number for an STM32 port and bit, or -1 if this package has none. */
    public static int fromPortPin(int port, int bit) {
        int pp = port * 16 + bit;
        for (int i = 0; i < COUNT; i++)
            if (PORT_PIN[i] == pp)
                return i;
        return -1;
    }

    private ArduinoPins() {}
}
