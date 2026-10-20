package com.nectariich.create_flippies.content.computer.device;

import static com.nectariich.create_flippies.content.computer.device.FloppyController.*;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.function.Function;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The floppy controller (spec 11.1.5) on a bus with 64 KiB of RAM and 512 KiB of VRAM, at 10 MHz,
 * so one microsecond is 10 cycles.
 */
class FloppyControllerTest {

    private static final int HZ = 10_000_000;
    private static final int RAM = DeviceRig.RAM_BASE;
    private static final long FLOPPY_BYTES = SECTORS * 512L;

    private DeviceRig rig;
    private FloppyController fdc;
    private PagedDiskImage disk;
    private byte[] ram;
    private byte[] vram;
    private long[] vramDirty;
    private final DeviceRig.Line line = new DeviceRig.Line();

    @BeforeEach
    void setUp() {
        rig = new DeviceRig();
        ram = new byte[64 * 1024];
        vram = new byte[512 * 1024];
        vramDirty = new long[2];
        rig.bus.setRam(ram, RAM);
        rig.bus.setVram(vram, DeviceRig.VRAM_BASE, vramDirty);
        fdc = new FloppyController(rig.clock, HZ, rig.bus, line);
        fdc.reset();
        disk = floppy(null);
        rig.map(fdc, 0x1000);
    }

    /** A blank 1.44 MB disk held in memory. */
    private static PagedDiskImage floppy(UUID id) {
        return new PagedDiskImage(id, FLOPPY_BYTES, chunk -> null, 64);
    }

    private static long us(long micros) {
        return micros * 10;
    }

    private static byte[] pattern(int n, int seed) {
        byte[] b = new byte[n];
        for (int i = 0; i < n; i++)
            b[i] = (byte) (i * 13 + seed);
        return b;
    }

    private void issue(int cmd, int lba, int count, int addr) {
        rig.w32(LBA, lba);
        rig.w32(COUNT, count);
        rig.w32(DMA_ADDR, addr);
        rig.w32(COMMAND, cmd);
    }

    /** Inserts the disk and clears the media change of the insert. */
    private void insert(boolean writeProtected) {
        fdc.insert(disk, writeProtected);
        rig.w32(STATUS, STATUS_MEDIA_CHANGED);
    }

    @Test
    void registersAtPowerOn() {
        assertEquals(STATUS_MEDIA_CHANGED, rig.r32(STATUS), "MEDIA_CHANGED is set at power-on");
        assertEquals(0, rig.r32(CAPACITY));
        assertEquals(512, rig.r32(SECTOR_SIZE));
        assertEquals(80 << 16 | 2 << 8 | 18, rig.r32(GEOMETRY));
        assertEquals(18, rig.r8(GEOMETRY));
        assertEquals(80, rig.r16(GEOMETRY + 2));
        rig.w32(LBA, 0x12345678);
        rig.w16(COUNT, 0x0101);
        rig.w32(DMA_ADDR, RAM + 4);
        rig.w32(IRQ_ENABLE, 0xFF);
        assertEquals(0x12345678, rig.r32(LBA));
        assertEquals(0x0101, rig.r32(COUNT));
        assertEquals(RAM + 4, rig.r32(DMA_ADDR));
        assertEquals(3, rig.r32(IRQ_ENABLE), "two enable bits");
        fdc.insert(disk, true);
        assertEquals(SECTORS, rig.r32(CAPACITY));
        assertEquals(STATUS_MEDIA | STATUS_WRITE_PROTECT | STATUS_MEDIA_CHANGED, rig.r32(STATUS));
        rig.w32(STATUS, STATUS_MEDIA_CHANGED);
        assertEquals(STATUS_MEDIA | STATUS_WRITE_PROTECT, rig.r32(STATUS));
        assertTrue(fdc.writeProtected());
        assertSame(disk, fdc.media());
        assertThrows(IllegalArgumentException.class, () -> fdc.insert(new SparseDiskImage(1 << 20), false),
                "only 1.44 MB media fit");
    }

    @Test
    void readFromColdSpinsUpThenTransfers() {
        disk.write(0, 1, pattern(512, 1), 0);
        insert(false);
        rig.w32(IRQ_ENABLE, IRQ_COMPLETION);
        rig.at(1000);
        rig.clock.setHorizon(Long.MAX_VALUE);
        issue(CMD_READ, 0, 1, RAM + 0x200);
        long done = 1000 + us(FDD_SPINUP_US + FDD_LATENCY_US + FDD_SECTOR_US);
        assertEquals(done, fdc.nextEventCycle());
        assertEquals(done, rig.clock.horizon(), "the command lowers the horizon to its completion");
        assertEquals(STATUS_BUSY | STATUS_MEDIA | STATUS_MOTOR, rig.r32(STATUS));
        assertTrue(fdc.motorOn());
        assertTrue(fdc.takeActivity());
        assertFalse(fdc.takeActivity());
        rig.at(done - 1);
        assertEquals(STATUS_BUSY | STATUS_MEDIA | STATUS_MOTOR, rig.r32(STATUS));
        assertEquals(0, ram[0x200]);
        rig.at(done);
        assertEquals(STATUS_DONE | STATUS_MEDIA | STATUS_MOTOR, rig.r32(STATUS));
        assertArrayEquals(pattern(512, 1), Arrays.copyOfRange(ram, 0x200, 0x400));
        assertTrue(line.level);
        rig.w32(STATUS, STATUS_DONE);
        assertFalse(line.level);
    }

    @Test
    void seekTimeDependsOnTheCylindersMoved() {
        insert(false);
        issue(CMD_SEEK, 0, 0, 0);       // spin up at cylinder 0
        long t = us(FDD_SPINUP_US);
        assertEquals(t, fdc.nextEventCycle(), "a seek to the cylinder under the head only spins up");
        rig.at(t);
        assertEquals(STATUS_DONE | STATUS_MEDIA | STATUS_MOTOR, rig.r32(STATUS));
        // cylinder 5 (LBA 5 * 36 + 7), motor running: 5 steps plus settle
        issue(CMD_SEEK, 5 * 36 + 7, 0, 0);
        assertEquals(t + us(5 * FDD_STEP_US + FDD_SETTLE_US), fdc.nextEventCycle());
        t = fdc.nextEventCycle();
        rig.at(t);
        assertEquals(5, fdc.headCylinder());
        // read 2 sectors at cylinder 2: 3 steps back, settle, latency, two sectors
        issue(CMD_READ, 2 * 36, 2, RAM);
        assertEquals(t + us(3 * FDD_STEP_US + FDD_SETTLE_US + FDD_LATENCY_US + 2 * FDD_SECTOR_US),
                fdc.nextEventCycle());
        rig.at(fdc.nextEventCycle());
        assertEquals(2, fdc.headCylinder());
        // a 64-sector read crossing two cylinders leaves the head over its last sector's cylinder
        issue(CMD_READ, 2 * 36 + 30, 64, RAM);
        rig.at(fdc.nextEventCycle());
        assertEquals((2 * 36 + 30 + 63) / 36, fdc.headCylinder());
        rig.w32(STATUS, STATUS_DONE);
        issue(CMD_SEEK, SECTORS, 0, 0);
        assertEquals(ERR_LBA_RANGE, rig.r32(ERROR_CODE));
    }

    @Test
    void motorHoldsTwoSecondsThenStopsAndSpinsUpAgain() {
        insert(false);
        issue(CMD_READ, 0, 1, RAM);
        long done = fdc.nextEventCycle();
        rig.at(done);
        assertEquals(STATUS_DONE, rig.r32(STATUS) & STATUS_DONE);
        long stop = done + us(FDD_MOTOR_HOLD_US);
        assertEquals(Long.MAX_VALUE, fdc.nextEventCycle(), "the motor stopping changes no line");
        rig.at(stop - 1);
        assertTrue((rig.r32(STATUS) & STATUS_MOTOR) != 0);
        // a command inside the hold needs no spin-up
        issue(CMD_READ, 0, 1, RAM);
        long second = fdc.nextEventCycle();
        assertEquals(stop - 1 + us(FDD_LATENCY_US + FDD_SECTOR_US), second);
        rig.at(second);
        stop = second + us(FDD_MOTOR_HOLD_US);
        rig.at(stop);
        assertEquals(0, rig.r32(STATUS) & STATUS_MOTOR, "stopped by itself after the hold");
        assertFalse(fdc.motorOn());
        issue(CMD_READ, 0, 1, RAM);
        assertEquals(stop + us(FDD_SPINUP_US + FDD_LATENCY_US + FDD_SECTOR_US), fdc.nextEventCycle());
        rig.at(fdc.nextEventCycle());
        rig.w32(STATUS, STATUS_DONE);
        rig.w32(COMMAND, CMD_MOTOR_OFF);
        assertEquals(STATUS_DONE | STATUS_MEDIA, rig.r32(STATUS), "MOTOR_OFF stops the spindle at once");
    }

    @Test
    void writeThroughDmaFromRamAndReadIntoVram() {
        insert(false);
        System.arraycopy(pattern(1024, 5), 0, ram, 0x1000, 1024);
        issue(CMD_WRITE, 100, 2, RAM + 0x1000);
        rig.at(fdc.nextEventCycle());
        assertEquals(STATUS_DONE, rig.r32(STATUS) & (STATUS_DONE | STATUS_ERROR));
        byte[] back = new byte[1024];
        disk.read(100, 2, back, 0);
        assertArrayEquals(pattern(1024, 5), back);
        rig.w32(STATUS, STATUS_DONE);
        issue(CMD_READ, 100, 2, DeviceRig.VRAM_BASE + 0x3000);
        rig.at(fdc.nextEventCycle());
        assertEquals(STATUS_DONE, rig.r32(STATUS) & (STATUS_DONE | STATUS_ERROR));
        assertArrayEquals(pattern(1024, 5), Arrays.copyOfRange(vram, 0x3000, 0x3400));
        assertEquals(1L << 3, vramDirty[0], "DMA marks the VRAM page dirty");
    }

    @Test
    void writeProtectedDiskFailsAtOnceWithCode8() {
        insert(true);
        Arrays.fill(ram, 0, 512, (byte) 0x5A);
        rig.w32(IRQ_ENABLE, IRQ_COMPLETION);
        issue(CMD_WRITE, 3, 1, RAM);
        assertEquals(STATUS_ERROR | STATUS_MEDIA | STATUS_WRITE_PROTECT, rig.r32(STATUS));
        assertEquals(ERR_WRITE_PROTECTED, rig.r32(ERROR_CODE));
        assertEquals(Long.MAX_VALUE, fdc.nextEventCycle());
        assertTrue(line.level);
        byte[] sector = new byte[512];
        disk.read(3, 1, sector, 0);
        assertArrayEquals(new byte[512], sector, "nothing written");
        rig.w32(STATUS, STATUS_ERROR);
        issue(CMD_READ, 3, 1, RAM);
        assertEquals(ERR_NONE, rig.r32(ERROR_CODE), "reading a protected disk is fine");
    }

    @Test
    void errorsCompleteAtOnce() {
        issue(CMD_READ, 0, 1, RAM);
        assertEquals(ERR_NO_MEDIA, rig.r32(ERROR_CODE));
        issue(CMD_SEEK, 0, 0, 0);
        assertEquals(ERR_NO_MEDIA, rig.r32(ERROR_CODE));
        insert(false);
        int[][] cases = {
                { CMD_READ, 0, 0, RAM, ERR_BAD_COUNT },
                { CMD_READ, 0, 257, RAM, ERR_BAD_COUNT },
                { CMD_WRITE, SECTORS - 1, 2, RAM, ERR_LBA_RANGE },
                { CMD_READ, -1, 1, RAM, ERR_LBA_RANGE },
                { CMD_READ, 0, 1, RAM + 64 * 1024 - 256, ERR_DMA_RANGE },
                { CMD_READ, 0, 1, 0x4000_0000, ERR_DMA_RANGE },
                { 9, 0, 1, RAM, ERR_BAD_COMMAND },
                { 0, 0, 1, RAM, ERR_BAD_COMMAND },
        };
        for (int[] c : cases) {
            issue(c[0], c[1], c[2], c[3]);
            assertEquals(c[4], rig.r32(ERROR_CODE), Arrays.toString(c));
            assertEquals(STATUS_ERROR, rig.r32(STATUS) & (STATUS_BUSY | STATUS_DONE | STATUS_ERROR));
            rig.w32(STATUS, STATUS_ERROR);
        }
        issue(CMD_READ, 0, 1, RAM);
        rig.w32(COMMAND, CMD_READ);
        assertEquals(ERR_BUSY, rig.r32(ERROR_CODE));
        assertEquals(STATUS_BUSY | STATUS_ERROR, rig.r32(STATUS) & (STATUS_BUSY | STATUS_ERROR));
        rig.at(fdc.nextEventCycle());
        assertEquals(STATUS_DONE | STATUS_ERROR, rig.r32(STATUS) & (STATUS_DONE | STATUS_ERROR),
                "the command in flight still completes");
    }

    @Test
    void insertAndEjectSetMediaChangedAndItsInterrupt() {
        rig.w32(STATUS, STATUS_MEDIA_CHANGED);
        rig.w32(IRQ_ENABLE, IRQ_MEDIA_CHANGE);
        assertFalse(line.level);
        fdc.insert(disk, false);
        assertTrue(line.level, "insert raises the media change interrupt between ticks");
        assertTrue((rig.r32(STATUS) & STATUS_MEDIA_CHANGED) != 0);
        rig.w32(STATUS, STATUS_MEDIA_CHANGED);
        assertFalse(line.level);
        fdc.eject();
        assertTrue(line.level);
        assertNull(fdc.media());
        assertEquals(STATUS_MEDIA_CHANGED, rig.r32(STATUS));
        rig.w32(IRQ_ENABLE, 0);
        assertFalse(line.level);
    }

    @Test
    void ejectDuringACommandEndsItWithCode9() {
        insert(false);
        disk.write(0, 1, pattern(512, 9), 0);
        issue(CMD_READ, 0, 1, RAM);
        rig.at(1000);
        fdc.eject();
        fdc.insert(disk, false);    // even the same disk back: the command saw a change
        rig.at(fdc.nextEventCycle());
        assertEquals(ERR_MEDIA_CHANGED, rig.r32(ERROR_CODE));
        assertEquals(STATUS_ERROR, rig.r32(STATUS) & (STATUS_DONE | STATUS_ERROR));
        assertArrayEquals(new byte[512], Arrays.copyOf(ram, 512), "no data moved");
    }

    @Test
    void resetDropsTheCommandAndKeepsTheDisk() {
        insert(false);
        issue(CMD_READ, 0, 1, RAM);
        fdc.reset();
        assertEquals(STATUS_MEDIA | STATUS_MEDIA_CHANGED, rig.r32(STATUS));
        assertEquals(Long.MAX_VALUE, fdc.nextEventCycle());
        assertSame(disk, fdc.media());
    }

    // ---- saved state ----

    private static final UUID ID = UUID.fromString("00000000-0000-0000-0000-00000000f10b");

    @Test
    void savedStateRoundTripsWithACommandInFlight() throws IOException {
        PagedDiskImage media = floppy(ID);
        media.write(40, 4, pattern(2048, 3), 0);
        Function<DeviceStateTest.Twin<FloppyController>, FloppyController> make = t -> {
            FloppyController f = new FloppyController(t.rig.clock, HZ, t.rig.bus, t.line);
            f.reset();
            f.insert(media, false);
            return f;
        };
        DeviceStateTest.Twin<FloppyController> a = new DeviceStateTest.Twin<>(make, 0x1000);
        a.rig.w32(IRQ_ENABLE, 3);
        a.rig.w32(LBA, 40);
        a.rig.w32(COUNT, 4);
        a.rig.w32(DMA_ADDR, RAM + 0x800);
        a.rig.w32(COMMAND, CMD_READ);
        a.rig.at(5000);
        DeviceStateTest.roundTrip(a, make, 0x1000, t -> {
            List<Object> seen = new ArrayList<>();
            seen.add(t.dev.nextEventCycle());
            t.rig.at(t.dev.nextEventCycle());
            seen.add(t.rig.r32(STATUS));
            seen.add(t.dev.headCylinder());
            t.rig.at(t.rig.clock.now() + us(FDD_MOTOR_HOLD_US));
            seen.add(t.rig.r32(STATUS));
            return seen;
        });
        DeviceStateTest.assertRejectsUnknownVersion(make.apply(new DeviceStateTest.Twin<>(make, 0x1000)),
                DeviceStateTest.save(a.dev));
    }

    @Test
    void aDifferentDiskOnLoadIsAMediaChangeNotAFailure() throws IOException {
        DiskImage first = floppy(ID);
        fdc.insert(first, false);
        rig.w32(STATUS, STATUS_MEDIA_CHANGED);
        issue(CMD_READ, 0, 1, RAM);
        byte[] state = DeviceStateTest.save(fdc);

        for (DiskImage other : new DiskImage[] { floppy(UUID.randomUUID()), null }) {
            FloppyController b = new FloppyController(rig.clock, HZ, rig.bus, new DeviceRig.Line());
            b.reset();
            if (other != null)
                b.insert(other, false);
            DeviceStateTest.load(b, state);
            assertEquals(STATUS_BUSY, b.read(STATUS, 4) & STATUS_BUSY);
            assertTrue((b.read(STATUS, 4) & STATUS_MEDIA_CHANGED) != 0, "loads as a swap at this moment");
            b.advanceTo(b.nextEventCycle());
            assertEquals(ERR_MEDIA_CHANGED, b.read(ERROR_CODE, 4), "the command in flight ends with code 9");
        }

        FloppyController same = new FloppyController(rig.clock, HZ, rig.bus, new DeviceRig.Line());
        same.reset();
        same.insert(first, false);
        DeviceStateTest.load(same, state);
        assertEquals(0, same.read(STATUS, 4) & STATUS_MEDIA_CHANGED, "the same disk is no change");
        same.advanceTo(same.nextEventCycle());
        assertEquals(ERR_NONE, same.read(ERROR_CODE, 4));
    }
}
