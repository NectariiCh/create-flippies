package com.nectariich.create_flippies.content.computer.machine;

/**
 * The RV32I machine's fixed facts: memory map, limits, CPU pins and interrupt controller lines.
 * Shared by the CPU, the platform and the tests so all three agree on one set of numbers.
 */
public final class Rv32Map {

    private Rv32Map() {
    }

    /** Boot ROM window start (spec 4.1); 0x0..0xFFF stays unmapped so null pointers fault. */
    public static final int ROM_BASE = 0x0000_1000;
    /** Boot ROM window, 60 KiB; the image is zero-padded to it (spec 4.1). */
    public static final int ROM_SIZE = 0xF000;
    /** Default reset PC, the first ROM byte (spec 4.1, 4.5). */
    public static final int RESET_PC = ROM_BASE;

    /** System controller window (spec 4.1, 3.1). */
    public static final int SYSCON_BASE = 0x0010_0000;
    /** CLINT window start (spec 4.1, 4.2). */
    public static final int CLINT_BASE = 0x0200_0000;
    /** CLINT window size, the one window larger than DEVICE_WINDOW (spec 4.1, 4.2). */
    public static final int CLINT_SIZE = 0x1_0000;
    /** Interrupt controller window (spec 4.1, 3.2). */
    public static final int PIC_BASE = 0x0C00_0000;
    /** COM1 window (spec 4.1, 3.5). */
    public static final int COM1_BASE = 0x1000_0000;
    /** COM2 window (spec 4.1, 3.5). */
    public static final int COM2_BASE = 0x1000_1000;
    /** PS/2 controller window (spec 4.1, 3.6). */
    public static final int PS2_BASE = 0x1000_2000;
    /** HDD controller window (spec 4.1, 3.8). */
    public static final int HDD_BASE = 0x1000_3000;
    /** VGA control window (spec 4.1, 3.7). */
    public static final int VGA_BASE = 0x1000_4000;
    /** Interval timer window (spec 4.1, 3.3). */
    public static final int TIMER_BASE = 0x1000_5000;
    /** RTC window (spec 4.1, 3.4). */
    public static final int RTC_BASE = 0x1000_6000;
    /** COM3 window (spec 11.1.1, 11.1.2). */
    public static final int COM3_BASE = 0x1000_7000;
    /** Floppy controller window (spec 11.1.1, 11.1.5). */
    public static final int FDC_BASE = 0x1000_8000;
    /** Network card window (spec 11.1.1, 11.1.7). */
    public static final int NIC_BASE = 0x1000_9000;
    /** VGA display 1 control window (spec 11.1.1, 11.1.3); {@link #VGA_BASE} is display 0's. */
    public static final int VGA1_BASE = 0x1000_A000;
    /** VGA display 2 control window (spec 11.1.1, 11.1.3). */
    public static final int VGA2_BASE = 0x1000_B000;
    /** Window size of every device except the CLINT (spec 4.1). */
    public static final int DEVICE_WINDOW = 0x1000;

    /** VRAM start, also display 0's framebuffer (spec 4.1, 11.1.1). */
    public static final int VRAM_BASE = 0x2000_0000;
    /** VRAM size, 1.5 MiB: three displays of {@link #VRAM_DISPLAY_SIZE} (spec 11.1.1). */
    public static final int VRAM_SIZE = 0x18_0000;
    /** One display's framebuffer, 512 KiB (spec 11.1.3). */
    public static final int VRAM_DISPLAY_SIZE = 0x8_0000;
    /** RAM start; negative as a signed int, compare unsigned (spec 4.1). */
    public static final int RAM_BASE = 0x8000_0000;
    /** Smallest RAM the platform accepts (spec 4.1, 1.1). */
    public static final int RAM_MIN = 64 * 1024;
    /** Largest RAM the platform accepts (spec 11.1.10); RAM ends at 0x87FF_FFFF at most. */
    public static final int RAM_MAX = 128 * 1024 * 1024;

    /** Slowest clock the platform accepts, a whole number of MHz (spec 7.5). */
    public static final int CLOCK_MIN_HZ = 1_000_000;
    /** Fastest clock the platform accepts (spec 7.5). */
    public static final int CLOCK_MAX_HZ = 100_000_000;

    /** CPU pin for the machine software interrupt, mip bit 3, driven by CLINT MSIP (spec 4.3). */
    public static final int IRQ_PIN_MSIP = 3;
    /** CPU pin for the machine timer interrupt, mip bit 7, driven by CLINT MTIP (spec 4.3). */
    public static final int IRQ_PIN_MTIP = 7;
    /** CPU pin for the machine external interrupt, mip bit 11, driven by controller output 0 (spec 4.3). */
    public static final int IRQ_PIN_MEIP = 11;

    /** Interrupt controller line of COM1 (spec 3.2). */
    public static final int LINE_COM1 = 1;
    /** Interrupt controller line of COM2 (spec 3.2). */
    public static final int LINE_COM2 = 2;
    /** Interrupt controller line of PS/2 port 0, the keyboard by default (spec 3.2, 11.1.4). */
    public static final int LINE_KEYBOARD = 3;
    /** Interrupt controller line of PS/2 port 1, the mouse by default (spec 3.2, 11.1.4). */
    public static final int LINE_MOUSE = 4;
    /** Interrupt controller line of the HDD controller (spec 3.2). */
    public static final int LINE_HDD = 5;
    /** Interrupt controller line of the VGA vertical blank (spec 3.2). */
    public static final int LINE_VGA = 6;
    /** Interrupt controller line of the interval timer (spec 3.2). */
    public static final int LINE_TIMER = 7;
    /** Interrupt controller line of COM3 (spec 11.1.1). */
    public static final int LINE_COM3 = 8;
    /** Interrupt controller line of the floppy controller (spec 11.1.1). */
    public static final int LINE_FDC = 9;
    /** Interrupt controller line of the network card (spec 11.1.1). */
    public static final int LINE_NIC = 10;
    /** Interrupt controller line of VGA display 1's vertical blank (spec 11.1.1). */
    public static final int LINE_VGA1 = 11;
    /** Interrupt controller line of VGA display 2's vertical blank (spec 11.1.1). */
    public static final int LINE_VGA2 = 12;

    /** Display {@code n}'s framebuffer address (0..2): VRAM_BASE + n * 512 KiB (spec 11.1.1). */
    public static int vramDisplayBase(int n) {
        return VRAM_BASE + vramDisplayOffset(n);
    }

    /** Display {@code n}'s slice offset inside the VRAM array (0..2). */
    public static int vramDisplayOffset(int n) {
        if (n < 0 || n > 2)
            throw new IllegalArgumentException("no display " + n);
        return n * VRAM_DISPLAY_SIZE;
    }

    /** Extra CPU cycles for any device register access; memory regions cost nothing extra (spec 1.2, 4.6). */
    public static final int MMIO_EXTRA_CYCLES = 2;
}
