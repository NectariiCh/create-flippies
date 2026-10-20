package com.nectariich.create_flippies.content.computer.device;

import static com.nectariich.create_flippies.content.computer.device.HddController.*;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The HDD controller (spec 3.8) on a bus with 64 KiB of RAM and 512 KiB of VRAM, at 10 MHz: a
 * one-sector command completes 500 + 100 us = 6000 cycles after it is written.
 */
class HddControllerTest {

    private static final int RAM = DeviceRig.RAM_BASE;

    private DeviceRig rig;
    private HddController hdd;
    private SparseDiskImage disk;
    private byte[] ram;
    private byte[] vram;
    private long[] vramDirty;
    private final List<int[]> ramWrites = new ArrayList<>();
    private final DeviceRig.Line line = new DeviceRig.Line();

    @BeforeEach
    void setUp() {
        rig = new DeviceRig();
        ram = new byte[64 * 1024];
        vram = new byte[512 * 1024];
        vramDirty = new long[2];
        rig.bus.setRam(ram, RAM);
        rig.bus.setVram(vram, DeviceRig.VRAM_BASE, vramDirty);
        rig.bus.setRamWriteListener((a, n) -> ramWrites.add(new int[] { a, n }));
        hdd = new HddController(rig.clock, 10_000_000, rig.bus, line);
        disk = new SparseDiskImage(1024 * 1024);
        hdd.setDisk(disk);
        rig.map(hdd, 0x1000);
    }

    private static byte[] pattern(int n, int seed) {
        byte[] b = new byte[n];
        for (int i = 0; i < n; i++)
            b[i] = (byte) (i * 31 + seed);
        return b;
    }

    private void issue(int cmd, int lba, int count, int addr) {
        rig.w32(LBA, lba);
        rig.w32(COUNT, count);
        rig.w32(DMA_ADDR, addr);
        rig.w32(COMMAND, cmd);
    }

    @Test
    void readCompletesExactlyAtTheScheduledCycle() {
        disk.write(5, 1, pattern(512, 7), 0);
        rig.w32(IRQ_ENABLE, 1);
        rig.at(1000);
        rig.clock.setHorizon(Long.MAX_VALUE);
        issue(CMD_READ, 5, 1, RAM + 0x100);
        assertEquals(7000, rig.clock.horizon(), "the command write lowers the horizon to the completion");
        assertEquals(7000, hdd.nextEventCycle());
        assertEquals(STATUS_BUSY | STATUS_MEDIA, rig.r32(STATUS));
        rig.at(6999);
        assertEquals(STATUS_BUSY | STATUS_MEDIA, rig.r32(STATUS));
        assertEquals(0, ram[0x100], "no data before completion");
        assertFalse(line.level);
        rig.at(7000);
        assertEquals(STATUS_DONE | STATUS_MEDIA, rig.r32(STATUS));
        assertArrayEquals(pattern(512, 7), Arrays.copyOfRange(ram, 0x100, 0x300));
        assertTrue(line.level);
        assertEquals(1, ramWrites.size(), "the RAM write listener saw the DMA");
        assertArrayEquals(new int[] { RAM + 0x100, 512 }, ramWrites.get(0));
        assertEquals(Long.MAX_VALUE, hdd.nextEventCycle());
        rig.w32(STATUS, STATUS_DONE);
        assertFalse(line.level);
        assertEquals(STATUS_MEDIA, rig.r32(STATUS));
    }

    @Test
    void completionWithoutAnAccessViaAdvanceTo() {
        issue(CMD_READ, 0, 2, RAM);
        assertEquals(7000, hdd.nextEventCycle(), "500 + 2 * 100 us, whatever IRQ_ENABLE says");
        hdd.advanceTo(7000);
        assertFalse(hdd.busy());
    }

    @Test
    void writeThenReadRoundTrip() {
        byte[] data = pattern(1024, 3);
        System.arraycopy(data, 0, ram, 0x2000, 1024);
        issue(CMD_WRITE, 7, 2, RAM + 0x2000);
        rig.at(100_000);
        assertEquals(STATUS_DONE | STATUS_MEDIA, rig.r32(STATUS));
        byte[] onDisk = new byte[1024];
        disk.read(7, 2, onDisk, 0);
        assertArrayEquals(data, onDisk);
        assertTrue(ramWrites.isEmpty(), "a disk write does not write RAM");

        rig.w32(STATUS, STATUS_DONE);
        issue(CMD_READ, 7, 2, RAM + 0x8000);
        rig.at(200_000);
        assertEquals(STATUS_DONE | STATUS_MEDIA, rig.r32(STATUS));
        assertArrayEquals(data, Arrays.copyOfRange(ram, 0x8000, 0x8400));
    }

    @Test
    void readIntoVramMarksPagesDirty() {
        disk.write(0, 2, pattern(1024, 1), 0);
        issue(CMD_READ, 0, 2, DeviceRig.VRAM_BASE + 0x1F00);
        rig.at(100_000);
        rig.r32(STATUS);
        assertEquals(0b110L, vramDirty[0]);
        assertEquals(pattern(1024, 1)[0], vram[0x1F00]);
    }

    private void assertError(int code) {
        assertEquals(STATUS_ERROR | STATUS_MEDIA, rig.r32(STATUS), "error " + code + " completes at once");
        assertEquals(code, rig.r32(ERROR_CODE));
    }

    @Test
    void errorCodes() {
        rig.w32(IRQ_ENABLE, 1);
        issue(9, 0, 1, RAM);
        assertError(ERR_BAD_COMMAND);
        assertTrue(line.level, "an invalid command completes at once with ERROR");
        rig.w32(STATUS, STATUS_ERROR);
        assertFalse(line.level);

        issue(CMD_READ, 0, 0, RAM);
        assertError(ERR_BAD_COUNT);
        issue(CMD_READ, 0, 257, RAM);
        assertError(ERR_BAD_COUNT);
        issue(CMD_READ, 2047, 2, RAM);
        assertError(ERR_LBA_RANGE);
        issue(CMD_READ, -1, 1, RAM);
        assertError(ERR_LBA_RANGE);
        issue(CMD_READ, 0, 1, RAM + 64 * 1024 - 256);
        assertError(ERR_DMA_RANGE);
        issue(CMD_READ, 0, 1, 0x4000_0000);
        assertError(ERR_DMA_RANGE);
        issue(CMD_IDENTIFY, 0, 0, 0x4000_0000);
        assertError(ERR_DMA_RANGE);

        hdd.setDisk(null);
        issue(CMD_READ, 0, 1, RAM);
        assertEquals(STATUS_ERROR, rig.r32(STATUS));
        assertEquals(ERR_NO_MEDIA, rig.r32(ERROR_CODE));
        assertEquals(0, rig.r32(CAPACITY));
    }

    @Test
    void commandWhileBusyIsRejected() {
        issue(CMD_READ, 0, 1, RAM);
        rig.at(10);
        rig.w32(COMMAND, CMD_WRITE);
        assertEquals(STATUS_BUSY | STATUS_ERROR | STATUS_MEDIA, rig.r32(STATUS));
        assertEquals(ERR_BUSY, rig.r32(ERROR_CODE));
        rig.at(6000);
        assertEquals(STATUS_DONE | STATUS_ERROR | STATUS_MEDIA, rig.r32(STATUS), "the first command still completes");
        rig.w32(STATUS, STATUS_DONE | STATUS_ERROR);
        issue(CMD_READ, 0, 1, RAM);
        assertEquals(ERR_NONE, rig.r32(ERROR_CODE), "a new valid command clears the code");
    }

    @Test
    void registerChangesWhileBusyDoNotAffectTheTransfer() {
        disk.write(3, 1, pattern(512, 9), 0);
        issue(CMD_READ, 3, 1, RAM);
        rig.w32(LBA, 0);
        rig.w32(DMA_ADDR, RAM + 0x4000);
        rig.at(6000);
        rig.r32(STATUS);
        assertArrayEquals(pattern(512, 9), Arrays.copyOf(ram, 512));
    }

    @Test
    void identifyBlock() {
        Arrays.fill(ram, 0, 512, (byte) 0x77);
        issue(CMD_IDENTIFY, 0, 0, RAM);
        rig.at(6000);
        assertEquals(STATUS_DONE | STATUS_MEDIA, rig.r32(STATUS), "500 + 100 us");
        assertEquals("FLHD", new String(ram, 0, 4, StandardCharsets.US_ASCII));
        assertEquals(2048, ram[4] & 0xFF | (ram[5] & 0xFF) << 8 | (ram[6] & 0xFF) << 16 | (ram[7] & 0xFF) << 24);
        assertEquals(512, ram[8] & 0xFF | (ram[9] & 0xFF) << 8);
        String model = new String(ram, 16, 32, StandardCharsets.US_ASCII);
        assertEquals(String.format("%-32s", "FLIPPIES HDD 1M"), model);
        for (int i = 12; i < 16; i++)
            assertEquals(0, ram[i]);
        for (int i = 48; i < 512; i++)
            assertEquals(0, ram[i], "rest zero at " + i);
    }

    @Test
    void flushCompletesAfterTheCommandLatency() {
        issue(CMD_FLUSH, 0, 0, 0);
        assertEquals(5000, hdd.nextEventCycle());
        rig.at(5000);
        assertEquals(STATUS_DONE | STATUS_MEDIA, rig.r32(STATUS));
    }

    @Test
    void fixedRegisters() {
        assertEquals(2048, rig.r32(CAPACITY));
        assertEquals(512, rig.r32(SECTOR_SIZE));
        rig.w32(LBA, 0x1234);
        rig.w16(LBA + 2, 0x5678);
        assertEquals(0x5678_1234, rig.r32(LBA));
        assertEquals("FLIPPIES HDD 1M", hdd.modelName());
    }

    @Test
    void resetDropsTheCommandInFlight() {
        rig.w32(IRQ_ENABLE, 1);
        issue(CMD_READ, 0, 1, RAM);
        hdd.reset();
        assertEquals(STATUS_MEDIA, rig.r32(STATUS));
        assertEquals(Long.MAX_VALUE, hdd.nextEventCycle());
        rig.at(100_000);
        assertEquals(STATUS_MEDIA, rig.r32(STATUS));
        assertFalse(line.level);
    }

    @Test
    void diskDirtyChunksFollowWrites() {
        System.arraycopy(pattern(512, 2), 0, ram, 0, 512);
        issue(CMD_WRITE, 300, 1, RAM);
        rig.at(10_000);
        rig.r32(STATUS);
        assertArrayEquals(new int[] { 2 }, disk.dirtyChunks(), "sector 300 is in chunk 2");
        assertEquals(1, disk.allocatedChunks());
    }
}
