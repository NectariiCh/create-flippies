package com.nectariich.create_flippies.content.computer.machine;

import java.util.List;

/**
 * The 68000 and 68040 machines' fixed facts (spec 10.8): memory map, limits, clocks, CPU pins and
 * interrupt controller lines. Shared by the CPU, the assembler's guest equates, the platform and
 * the tests so all agree on one set of numbers.
 *
 * Addresses are written as the 68040 sees them (32-bit). The 68000 drives only 24 address lines,
 * so it reaches the same places at {@code address & ADDRESS_MASK_68000}: its machine maps the bus
 * at those 24-bit addresses, and guest code written with these 32-bit constants runs on both.
 */
public final class M68kMap {

    private M68kMap() {
    }

    /** The 68000's 24-bit address bus: every bus address is masked with this (spec 10.2.2). */
    public static final int ADDRESS_MASK_68000 = 0x00FF_FFFF;

    /** RAM start; the vector table lives at 0 (spec 10.8.1). */
    public static final int RAM_BASE = 0x0000_0000;
    /** Smallest RAM either machine accepts (spec 1.1). */
    public static final int RAM_MIN = 64 * 1024;
    /** Largest RAM on the 68000, 13.5 MiB: everything below VRAM (0xD8_0000 on its 24-bit bus; spec 11.1.10). */
    public static final int RAM_MAX_68000 = 0xD8_0000;
    /** Largest RAM on the 68040, 128 MiB (spec 11.1.10). */
    public static final int RAM_MAX_68040 = 128 * 1024 * 1024;

    /**
     * Display 0's framebuffer; the 68000 sees it at 0xE0_0000 (spec 10.8.1, 11.1.1). Existing
     * guest code and both boot ROMs use it.
     */
    public static final int VRAM_BASE = 0xFFE0_0000;
    /** One display's framebuffer, 512 KiB (spec 3.7, 11.1.3). */
    public static final int VRAM_SIZE = 0x8_0000;
    /** Same as {@link #VRAM_SIZE}, named as in {@code Rv32Map}. */
    public static final int VRAM_DISPLAY_SIZE = VRAM_SIZE;
    /**
     * The one VRAM range on the bus, 1.5 MiB from here (0xD8_0000 on the 68000), holding the three
     * displays: display 2 at offset 0, display 0 at 0x8_0000, display 1 at 0x10_0000 (spec 11.1.1).
     */
    public static final int VRAM_REGION_BASE = 0xFFD8_0000;
    /** Size of the VRAM range, 1.5 MiB. */
    public static final int VRAM_REGION_SIZE = 0x18_0000;

    /** First device window; the 68000 sees it at 0xF0_0000 (spec 10.8.1). */
    public static final int DEVICE_BASE = 0xFFF0_0000;
    /** Window size of every device (spec 10.8.1). */
    public static final int DEVICE_WINDOW = 0x1000;
    /** System controller window (spec 3.1). */
    public static final int SYSCON_BASE = DEVICE_BASE;
    /** Interrupt controller window (spec 3.2). */
    public static final int PIC_BASE = DEVICE_BASE + 0x1000;
    /** COM1 window (spec 3.5). */
    public static final int COM1_BASE = DEVICE_BASE + 0x2000;
    /** COM2 window (spec 3.5). */
    public static final int COM2_BASE = DEVICE_BASE + 0x3000;
    /** PS/2 controller window (spec 3.6). */
    public static final int PS2_BASE = DEVICE_BASE + 0x4000;
    /** HDD controller window (spec 3.8). */
    public static final int HDD_BASE = DEVICE_BASE + 0x5000;
    /** VGA control window (spec 3.7). */
    public static final int VGA_BASE = DEVICE_BASE + 0x6000;
    /** Interval timer window (spec 3.3); the 68k machines have no CLINT. */
    public static final int TIMER_BASE = DEVICE_BASE + 0x7000;
    /** RTC window (spec 3.4). */
    public static final int RTC_BASE = DEVICE_BASE + 0x8000;
    /** COM3 window (spec 11.1.1). */
    public static final int COM3_BASE = DEVICE_BASE + 0x9000;
    /** Floppy controller window (spec 11.1.1). */
    public static final int FDC_BASE = DEVICE_BASE + 0xA000;
    /** Network card window (spec 11.1.1). */
    public static final int NIC_BASE = DEVICE_BASE + 0xB000;
    /** VGA display 1 control window (spec 11.1.1); {@link #VGA_BASE} is display 0's. */
    public static final int VGA1_BASE = DEVICE_BASE + 0xC000;
    /** VGA display 2 control window (spec 11.1.1). */
    public static final int VGA2_BASE = DEVICE_BASE + 0xD000;

    /** Boot ROM window start; the 68000 sees it at 0xFC_0000 (spec 10.8.1). */
    public static final int ROM_BASE = 0xFFFC_0000;
    /** Boot ROM window, 256 KiB; the image is zero-padded to it. */
    public static final int ROM_SIZE = 0x4_0000;
    /**
     * Bytes of the ROM mirrored at address 0 while the ROM overlay is on (after reset and power-on,
     * until the guest writes SysCon ROM_OVERLAY_OFF), so the CPU reads its reset vectors from ROM.
     */
    public static final int ROM_OVERLAY_SIZE = ROM_SIZE;

    /** Slowest 68000 clock accepted, the slowest real part (spec 2.3). */
    public static final int CLOCK_68000_MIN_HZ = 4_000_000;
    /** Fastest 68000 clock accepted, the fastest real part (spec 2.3). */
    public static final int CLOCK_68000_MAX_HZ = 16_670_000;
    /** The 68000 CPU items (spec 1.1, 2.3). */
    public static final List<Integer> CLOCK_68000_ITEMS_HZ = List.of(8_000_000, 12_500_000);
    /** Slowest 68040 clock accepted, the slowest real part (spec 10.2.3). */
    public static final int CLOCK_68040_MIN_HZ = 25_000_000;
    /** Fastest 68040 clock accepted, the fastest real part (spec 10.2.3). */
    public static final int CLOCK_68040_MAX_HZ = 40_000_000;
    /** The 68040 CPU items (spec 1.1, 10.2.3). */
    public static final List<Integer> CLOCK_68040_ITEMS_HZ = List.of(25_000_000, 33_000_000);

    /**
     * CPU pins are the interrupt priority levels 1..7 (spec 10.8.2): controller output k drives
     * pin k. Pin 7 is the non-maskable level, edge-detected inside the CPU.
     */
    public static final int IRQ_PIN_LEVEL_7 = 7;
    /** Controller output 0, where every line is routed after reset, also drives this pin (spec 10.8.2). */
    public static final int IRQ_PIN_OUTPUT_0 = 4;

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

    /** Display {@code n}'s framebuffer address as the 68040 sees it (0..2): 0xFFE0_0000, 0xFFE8_0000, 0xFFD8_0000. */
    public static int vramDisplayBase(int n) {
        return VRAM_REGION_BASE + vramDisplayOffset(n);
    }

    /** Display {@code n}'s slice offset inside the VRAM array (0..2): 0x8_0000, 0x10_0000, 0. */
    public static int vramDisplayOffset(int n) {
        return switch (n) {
            case 0 -> 0x8_0000;
            case 1 -> 0x10_0000;
            case 2 -> 0;
            default -> throw new IllegalArgumentException("no display " + n);
        };
    }

    /** Extra CPU cycles for any device register access; memory regions cost nothing extra (spec 1.2). */
    public static final int MMIO_EXTRA_CYCLES = 2;
}
