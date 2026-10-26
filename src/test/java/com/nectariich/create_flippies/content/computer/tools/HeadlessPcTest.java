package com.nectariich.create_flippies.content.computer.tools;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.nectariich.create_flippies.content.computer.asm.AsmOptions;
import com.nectariich.create_flippies.content.computer.asm.AsmResult;
import com.nectariich.create_flippies.content.computer.asm.m68k.M68kAssembler;
import com.nectariich.create_flippies.content.computer.asm.m68k.M68kTarget;
import com.nectariich.create_flippies.content.computer.cpu.CpuModel;
import com.nectariich.create_flippies.content.computer.device.PagedDiskImage;
import com.nectariich.create_flippies.content.computer.machine.M68kBootRom;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

import javax.imageio.ImageIO;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The headless runner (spec 12.9) end to end: a 68040 boot floppy that talks to the modem, the
 * HDD and COM1 and powers off with a code, and Cog Maze on the RV32I PC if its build output is
 * present. Each runs through {@link HeadlessPc#run(String[], java.io.InputStream,
 * java.io.OutputStream, PrintStream)}, as {@code main} does.
 */
class HeadlessPcTest {

    @TempDir
    Path dir;

    /**
     * Prints a line through the ROM, asks the modem for 10.88.0.1's MAC by ARP and checks the
     * reply, writes its own boot sector to HDD sector 3, echoes one byte from COM1, then powers
     * off with failure code 42 once COM1 has sent everything. Any check failing powers off with 7.
     */
    private static final String M68040_BOOT = """
            SYSCON  equ $FFF00000
            COM1    equ $FFF02000
            HDD     equ $FFF05000
            NIC     equ $FFF0B000
            TXF     equ $8000
            RXF     equ $9000
                    .text
            _start: move.l  a1,a6               ; the ROM's print_string
                    lea     hello(pc),a0
                    jsr     (a6)
                    lea     NIC,a1
                    move.l  #1,(a1)             ; CTRL: RX_ENABLE
                    lea     TXF,a0
                    moveq   #5,d1
                    moveq   #0,d3
            1:      move.b  #$FF,0(a0,d3.w)     ; destination: broadcast
                    move.b  8(a1,d3.w),6(a0,d3.w)   ; source: our MAC, byte d3 at offset 8 + d3
                    move.b  8(a1,d3.w),22(a0,d3.w)  ; ARP sender hardware address
                    addq.w  #1,d3
                    dbra    d1,1b
                    move.w  #$0806,12(a0)       ; ARP
                    move.l  #$00010800,14(a0)   ; Ethernet, IPv4
                    move.l  #$06040001,18(a0)   ; address lengths, request
                    move.l  #$0A580002,28(a0)   ; sender 10.88.0.2
                    move.l  #$0A580001,38(a0)   ; target 10.88.0.1
                    move.l  a0,$10(a1)          ; TX_ADDR
                    move.l  #60,$14(a1)         ; TX_LEN
                    move.l  #1,$18(a1)          ; TX_CMD: send
            2:      tst.l   $2C(a1)             ; RX_COUNT
                    beq.s   2b
                    move.l  #RXF,$24(a1)        ; RX_ADDR
                    move.l  #1,$28(a1)          ; RX_CMD: copy
                    lea     RXF,a0
                    cmpi.w  #$0806,12(a0)
                    bne     bad
                    cmpi.w  #2,20(a0)           ; a reply
                    bne     bad
                    cmpi.l  #$0A580001,28(a0)   ; from 10.88.0.1
                    bne     bad
                    lea     arpok(pc),a0
                    jsr     (a6)
                    lea     HDD,a2              ; this boot sector to LBA 3
                    move.l  #3,8(a2)
                    move.l  #1,$0C(a2)
                    move.l  #_start,$10(a2)
                    move.l  #2,(a2)             ; WRITE
            3:      move.l  4(a2),d0
                    and.l   #6,d0
                    beq.s   3b
                    move.l  #6,4(a2)
                    btst    #2,d0
                    bne.s   bad
                    lea     COM1,a2
            4:      btst    #0,5(a2)            ; LSR: data ready
                    beq.s   4b
                    move.b  (a2),key
                    lea     keymsg(pc),a0
                    jsr     (a6)
            5:      btst    #6,5(a2)            ; LSR: everything sent
                    beq.s   5b
                    move.l  #$002A3333,SYSCON   ; fail, code 42
            6:      bra.s   6b
            bad:    move.l  #$00073333,SYSCON
            7:      bra.s   7b
            hello:  dc.b    "headless 68040",13,10,0
            arpok:  dc.b    "arp ok",13,10,0
            keymsg: dc.b    "key="
            key:    dc.b    "?",13,10,0
                    org     510
                    dc.b    $55,$AA
            """;

    @Test
    void m68040FloppyTalksToModemHddAndCom1() throws IOException {
        AsmResult r = M68kAssembler.assemble(M68040_BOOT, AsmOptions.at(M68kBootRom.BOOT_ADDRESS), M68kTarget.M68040);
        assertTrue(r.ok(), r.diagnostics().toString());
        byte[] sector = r.image().flatten(M68kBootRom.BOOT_ADDRESS, 512);
        Path floppy = dir.resolve("boot.img");
        byte[] image = new byte[(int) PagedDiskImage.FLOPPY_CAPACITY];
        System.arraycopy(sector, 0, image, 0, 512);
        Files.write(floppy, image);
        Path hdd = dir.resolve("hdd.img");
        Path catalogue = Files.createDirectories(dir.resolve("internet/get.flip"));
        Files.writeString(catalogue.resolve("index.txt"), "file /hello.txt 3 Hello\n");
        Path shot = dir.resolve("screen.png");

        Run run = run("x", "--cpu", "68040", "--ram", "4M", "--floppy", floppy.toString(), "--hdd", hdd.toString(),
                "--hdd-size", "1M", "--catalogue", dir.resolve("internet").toString(), "--epoch", "1700000000",
                "--max-seconds", "20", "--screenshot", shot.toString());

        assertEquals(42, run.status, run.err);
        assertTrue(run.out.contains("headless 68040\r\narp ok\r\nkey=x\r\n"), run.out);
        assertTrue(run.err.contains("guest powered off, code 0x002A"), run.err);
        byte[] written = Files.readAllBytes(hdd);
        assertEquals(1 << 20, written.length);
        assertArrayEquals(sector, Arrays.copyOfRange(written, 3 * 512, 4 * 512));
        // text mode is saved as text, beside the PNG name
        String screen = Files.readString(dir.resolve("screen.txt"));
        assertTrue(screen.contains("arp ok") && screen.contains("key=x"), screen);
    }

    @Test
    void readOnlyKeepsTheImageFilesUnchanged() throws IOException {
        AsmResult r = M68kAssembler.assemble(M68040_BOOT, AsmOptions.at(M68kBootRom.BOOT_ADDRESS), M68kTarget.M68040);
        assertTrue(r.ok(), r.diagnostics().toString());
        Path floppy = dir.resolve("boot.img");
        Files.write(floppy, r.image().flatten(M68kBootRom.BOOT_ADDRESS, 512));   // short: the rest reads as zeros
        Path hdd = dir.resolve("hdd.img");
        Files.write(hdd, new byte[64 * 1024]);

        Run run = run("y", "--floppy", floppy.toString(), "--hdd", hdd.toString(), "--readonly", "--modem",
                "--max-seconds", "20");

        assertEquals(42, run.status, run.err);
        assertTrue(run.out.contains("key=y"), run.out);
        assertArrayEquals(new byte[64 * 1024], Files.readAllBytes(hdd));
        assertEquals(512, Files.size(floppy));
    }

    @Test
    void withoutAModemTheGuestWaitsUntilTheTimeLimit() throws IOException {
        AsmResult r = M68kAssembler.assemble(M68040_BOOT, AsmOptions.at(M68kBootRom.BOOT_ADDRESS), M68kTarget.M68040);
        assertTrue(r.ok(), r.diagnostics().toString());
        Path floppy = dir.resolve("boot.img");
        Files.write(floppy, r.image().flatten(M68kBootRom.BOOT_ADDRESS, 512));

        Run run = run("", "--floppy", floppy.toString(), "--max-seconds", "0.5", "--readonly");

        assertEquals(HeadlessPc.EXIT_TIME_LIMIT, run.status, run.err);
        assertTrue(run.out.contains("headless 68040") && !run.out.contains("arp ok"), run.out);
        assertTrue(run.err.contains("after 0.50 s emulated"), run.err);
    }

    /**
     * A tiny "kernel" for --kernel: checks that it starts in supervisor mode with interrupts
     * masked and paging off, walks the bootinfo at _end (0x3000, the end of its only segment),
     * checks the machine type, prints the initrd's text (BI_RAMDISK) and the command line, then
     * runs ILLEGAL, which the boot ROM's vector stub reports before powering off with 0xDEAD.
     */
    private static final String KERNEL = """
            SYSCON  equ $FFF00000
            COM1    equ $FFF02000
                    .text
            _start: move.w  sr,d0
                    and.w   #$2700,d0
                    cmp.w   #$2700,d0           ; supervisor, interrupts masked
                    bne     bad
                    movec   tc,d0
                    tst.l   d0
                    bne     bad                 ; paging off
                    move.b  #$03,COM1+3         ; LCR: 8N1
                    lea     $3000,a0            ; _end: the bootinfo
            next:   move.w  (a0),d0             ; tag
                    beq.s   done
                    move.w  2(a0),d1            ; size of the record
                    cmp.w   #1,d0               ; BI_MACHTYPE
                    bne.s   1f
                    cmp.l   #15,4(a0)           ; MACH_FLIPPIES
                    bne     bad
            1:      cmp.w   #6,d0               ; BI_RAMDISK: the initrd's text
                    bne.s   2f
                    move.l  4(a0),a1
                    bsr     puts
            2:      cmp.w   #7,d0               ; BI_COMMAND_LINE
                    bne.s   3f
                    lea     4(a0),a1
                    bsr     puts
            3:      adda.w  d1,a0
                    bra.s   next
            done:   illegal                     ; to the boot ROM's stub for vector 4
            bad:    move.l  #$00073333,SYSCON
            4:      bra.s   4b
            puts:   move.b  (a1)+,d2
                    beq.s   6f
                    lea     COM1,a2
            5:      btst    #5,5(a2)            ; LSR: room in the transmitter
                    beq.s   5b
                    move.b  d2,(a2)
                    bra.s   puts
            6:      rts
            """;

    @Test
    void kernelOptionLoadsAnElfWithBootinfoAndInitrd() throws IOException {
        AsmResult r = M68kAssembler.assemble(KERNEL, AsmOptions.at(0x1000), M68kTarget.M68040);
        assertTrue(r.ok(), r.diagnostics().toString());
        byte[] code = r.image().flatten(0x1000, 0x100);
        Path kernel = dir.resolve("vmlinux");
        Files.write(kernel, new TestElf().entry(0x1000).put(0x1000, code)
                .segment(0, 0, 0x1000 + code.length, 0x3000).build());
        Path initrd = dir.resolve("initrd");
        Files.write(initrd, "hello from the initrd\r\n\0".getBytes(StandardCharsets.ISO_8859_1));

        Run run = run("", "--ram", "4M", "--kernel", kernel.toString(), "--initrd", initrd.toString(),
                "--cmdline", "console=ttyS0 test=1", "--max-seconds", "5");

        assertEquals(0xAD, run.status, run.err);
        assertTrue(run.out.startsWith("hello from the initrd\r\nconsole=ttyS0 test=1TRAP vector=04 pc=000010"), run.out);
        assertTrue(run.err.contains("headless: kernel entry 0x1000, bootinfo 0x3000 to 0x3"), run.err);
        assertTrue(run.err.contains("guest powered off, code 0xDEAD"), run.err);
    }

    @Test
    void kernelSetupErrorsAreUsageErrors() throws IOException {
        Path notElf = Files.writeString(dir.resolve("notelf"), "not an ELF file");
        Run run = run("", "--kernel", notElf.toString());
        assertEquals(HeadlessPc.EXIT_USAGE, run.status);
        assertTrue(run.err.contains("not an ELF file"), run.err);
        assertEquals(HeadlessPc.EXIT_USAGE, run("", "--kernel", dir.resolve("missing").toString()).status);
    }

    /**
     * Fills display 0 red in 640x480x8, enables both PS/2 ports and the mouse's reporting, then
     * prints every byte from port 0 as "kXX " and from port 1 as "mXX " on COM1. The keyboard's
     * make code for A turns the screen green.
     */
    private static final String PS2_ECHO = """
            COM1    equ $FFF02000
            PS2     equ $FFF04000
            VGA     equ $FFF06000
            VRAM    equ $FFE00000
                    .text
            _start: lea     COM1,a5
                    move.l  #3,VGA              ; MODE: 640x480, 8 bits
                    move.l  #$04040404,d0       ; red
                    bsr     fill
                    lea     PS2,a4
                    moveq   #2,d0
                    move.l  d0,8(a4)            ; port 0 CTRL: enabled, polled
                    move.l  d0,$18(a4)          ; port 1 CTRL: enabled, polled
                    move.l  #$F4,$10(a4)        ; mouse: start reporting
            loop:   btst    #0,4(a4)            ; port 0 STATUS: a byte is ready
                    beq.s   1f
                    move.l  (a4),d1             ; DATA
                    moveq   #$6B,d2             ; "k"
                    bsr.s   hex
                    cmp.b   #$1C,d1             ; A
                    bne.s   1f
                    move.l  #$02020202,d0       ; green
                    bsr.s   fill
            1:      btst    #0,$14(a4)          ; port 1
                    beq.s   loop
                    move.l  $10(a4),d1
                    moveq   #$6D,d2             ; "m"
                    bsr.s   hex
                    bra.s   loop
            hex:    bsr.s   putc                ; d2, then d1's low byte in hex and a space
                    move.b  d1,d3
                    lsr.b   #4,d3
                    bsr.s   digit
                    move.b  d1,d3
                    bsr.s   digit
                    moveq   #$20,d2
            putc:   btst    #5,5(a5)            ; LSR: room in the transmitter
                    beq.s   putc
                    move.b  d2,(a5)
                    rts
            digit:  and.w   #$F,d3
                    move.b  hexd(pc,d3.w),d2
                    bra.s   putc
            fill:   lea     VRAM,a0
                    move.l  #76799,d3           ; 640 * 480 / 4 - 1
            2:      move.l  d0,(a0)+
                    subq.l  #1,d3
                    bpl.s   2b
                    rts
            hexd:   dc.b    "0123456789ABCDEF"
                    org     510
                    dc.b    $55,$AA
            """;

    @Test
    void inputScriptTypesMovesTheMouseAndTakesScreenshots() throws IOException {
        AsmResult r = M68kAssembler.assemble(PS2_ECHO, AsmOptions.at(M68kBootRom.BOOT_ADDRESS), M68kTarget.M68040);
        assertTrue(r.ok(), r.diagnostics().toString());
        Path floppy = dir.resolve("boot.img");
        Files.write(floppy, r.image().flatten(M68kBootRom.BOOT_ADDRESS, 512));
        Path script = Files.writeString(dir.resolve("input.txt"), """
                # the boot sector runs from about 0.45 s
                0.9 screenshot red.png
                1 type aA
                1.2 move 3 -2
                +0.1 buttons 1
                +0.1 buttons 0
                """);
        Path green = dir.resolve("green.png");

        Run run = run("", "--floppy", floppy.toString(), "--readonly", "--max-seconds", "2", "--input", script.toString(),
                "--screenshot", green.toString(), "--screenshot-at", "1.6");

        assertEquals(HeadlessPc.EXIT_TIME_LIMIT, run.status, run.err);
        // a, then Shift A: make, break, Shift make, make, break, Shift break
        assertTrue(run.out.contains("k1C kF0 k1C k12 k1C kF0 k1C kF0 k12 "), run.out);
        // the reporting command's acknowledgement, then a move of (3, -2) and the left button down and up
        assertTrue(run.out.contains("mFA "), run.out);
        assertTrue(run.out.contains("m28 m03 mFE m09 m00 m00 m08 m00 m00 "), run.out);
        assertTrue(run.err.contains("display 0 saved to " + dir.resolve("red.png") + " at 0.90 s"), run.err);
        assertTrue(run.err.contains("display 0 saved to " + green + " at 1.60 s"), run.err);
        assertScreen(dir.resolve("red.png"), 0xAA0000);
        assertScreen(green, 0x00AA00);
    }

    private static void assertScreen(Path png, int rgb) throws IOException {
        BufferedImage img = ImageIO.read(png.toFile());
        assertEquals(640, img.getWidth());
        assertEquals(480, img.getHeight());
        assertEquals(rgb, img.getRGB(0, 0) & 0xFFFFFF);
        assertEquals(rgb, img.getRGB(639, 479) & 0xFFFFFF);
    }

    @Test
    void scriptErrorsAreUsageErrors() throws IOException {
        Path script = Files.writeString(dir.resolve("bad.txt"), "1 key A\n2 key NOPE\n");
        Run run = run("", "--input", script.toString(), "--max-seconds", "1");
        assertEquals(HeadlessPc.EXIT_USAGE, run.status);
        assertTrue(run.err.contains("line 2: no key NOPE"), run.err);
        assertEquals(HeadlessPc.EXIT_USAGE, run("", "--input", dir.resolve("missing.txt").toString()).status);
    }

    /** Cog Maze (guest/cogmaze) boots from its floppy on the RV32I PC and draws in 320x200x8. */
    @Test
    void cogMazeBootsOnRv32i() throws IOException {
        Path built = Path.of("guest/cogmaze/out/cogmaze.img");
        assumeTrue(Files.isRegularFile(built), "guest/cogmaze/out/cogmaze.img is not built");
        Path floppy = Files.copy(built, dir.resolve("cogmaze.img"));
        Path shot = dir.resolve("cogmaze.png");

        Run run = run("", "--cpu", "rv32i", "--clock", "25MHz", "--ram", "4M", "--floppy", floppy.toString(),
                "--max-seconds", "3", "--screenshot", shot.toString());

        assertEquals(HeadlessPc.EXIT_TIME_LIMIT, run.status, run.err);
        assertTrue(run.out.contains("Loading Cog Maze"), run.out);
        BufferedImage img = ImageIO.read(shot.toFile());
        assertEquals(320, img.getWidth());
        assertEquals(200, img.getHeight());
        Set<Integer> colours = new HashSet<>();
        for (int y = 0; y < 200; y++)
            for (int x = 0; x < 320; x++)
                colours.add(img.getRGB(x, y));
        assertTrue(colours.size() > 4, "only " + colours.size() + " colours on screen");
    }

    @Test
    void options() {
        HeadlessPc.Options o = HeadlessPc.Options.parse("--cpu", "rv32i", "--clock=12.5M", "--ram", "64KiB",
                "--mac", "02-00-00-00-00-2a", "--max-seconds", "1.5", "--catalogue", "web");
        assertEquals(CpuModel.RV32I, o.cpu());
        assertEquals(12_500_000, o.clockHz());
        assertEquals(64 * 1024, o.ramBytes());
        assertEquals(0x02_00_00_00_00_2AL, o.mac());
        assertEquals(1.5, o.maxSeconds());
        assertTrue(o.modem(), "a catalogue means a modem");
        assertNull(o.epochSeconds());

        HeadlessPc.Options d = HeadlessPc.Options.parse();
        assertEquals(CpuModel.CPU_68040, d.cpu());
        assertEquals(25_000_000, d.clockHz());
        assertEquals(HeadlessPc.DEFAULT_RAM, d.ramBytes());
        assertEquals(HeadlessPc.DEFAULT_MAC, d.mac());
        assertEquals(1L << 30, HeadlessPc.Options.parse("--hdd", "x", "--hdd-size", "1G").hddCreateBytes());
        HeadlessPc.Options s = HeadlessPc.Options.parse("--share", "files");
        assertEquals(Path.of("files"), s.share());
        assertTrue(s.modem(), "a share means a modem");
        assertNull(d.share());

        assertThrows(IllegalArgumentException.class, () -> HeadlessPc.Options.parse("--cpu", "8086"));
        assertThrows(IllegalArgumentException.class, () -> HeadlessPc.Options.parse("--ram", "4X"));
        assertThrows(IllegalArgumentException.class, () -> HeadlessPc.Options.parse("--display", "3"));
        assertThrows(IllegalArgumentException.class, () -> HeadlessPc.Options.parse("--hdd-size", "1M"));
        assertThrows(IllegalArgumentException.class, () -> HeadlessPc.Options.parse("--max-seconds"));
        assertThrows(IllegalArgumentException.class, () -> HeadlessPc.Options.parse("--bogus", "1"));

        HeadlessPc.Options k = HeadlessPc.Options.parse("--kernel", "vmlinux", "--initrd", "initrd.cpio");
        assertEquals(Path.of("vmlinux"), k.kernel());
        assertEquals(Path.of("initrd.cpio"), k.initrd());
        assertEquals(HeadlessPc.DEFAULT_CMDLINE, k.cmdline());
        assertEquals("root=/dev/fhda3", HeadlessPc.Options.parse("--kernel", "k", "--cmdline", "root=/dev/fhda3").cmdline());
        assertNull(d.kernel());
        assertNull(d.cmdline());
        assertThrows(IllegalArgumentException.class, () -> HeadlessPc.Options.parse("--initrd", "x"));
        assertThrows(IllegalArgumentException.class, () -> HeadlessPc.Options.parse("--cmdline", "x"));
        assertThrows(IllegalArgumentException.class, () -> HeadlessPc.Options.parse("--cpu", "68000", "--kernel", "k"));

        HeadlessPc.Options i = HeadlessPc.Options.parse("--input", "keys.txt", "--screenshot", "s.png",
                "--screenshot-at", "2.5");
        assertEquals(Path.of("keys.txt"), i.input());
        assertEquals(2.5, i.screenshotAt());
        assertNull(d.input());
        assertEquals(0, d.screenshotAt());
        assertThrows(IllegalArgumentException.class, () -> HeadlessPc.Options.parse("--screenshot-at", "1"));
        assertThrows(IllegalArgumentException.class,
                () -> HeadlessPc.Options.parse("--screenshot", "s.png", "--screenshot-at", "0"));
    }

    @Test
    void badSetupIsAUsageError() {
        assertEquals(HeadlessPc.EXIT_USAGE, run("", "--ram", "3M", "--cpu", "68040", "--clock", "1MHz").status);
        assertEquals(HeadlessPc.EXIT_USAGE, run("", "--hdd", dir.resolve("missing.img").toString()).status);
        assertEquals(HeadlessPc.EXIT_USAGE, run("", "--frobnicate").status);
    }

    private record Run(int status, String out, String err) {}

    private static Run run(String stdin, String... args) {
        ByteArrayOutputStream out = new ByteArrayOutputStream(), err = new ByteArrayOutputStream();
        int status = HeadlessPc.run(args, new ByteArrayInputStream(stdin.getBytes(StandardCharsets.ISO_8859_1)), out,
                new PrintStream(err, true, StandardCharsets.UTF_8));
        return new Run(status, out.toString(StandardCharsets.ISO_8859_1), err.toString(StandardCharsets.UTF_8));
    }
}
