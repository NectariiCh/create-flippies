package com.nectariich.create_flippies.content.computer.device;

import static com.nectariich.create_flippies.content.computer.device.Nic.*;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.nectariich.create_flippies.content.computer.bus.IrqSink;
import com.nectariich.create_flippies.content.computer.bus.StateFormatException;
import com.nectariich.create_flippies.content.computer.bus.SystemBus;

import java.io.IOException;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.function.Function;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** The network card (spec 11.1.7) on a little-endian bus with 64 KiB of RAM. */
class NicTest {

    /** 02:11:22:33:44:55, locally administered unicast. */
    private static final long MAC = 0x02_11_22_33_44_55L;
    private static final int RAM = DeviceRig.RAM_BASE;

    private DeviceRig rig;
    private Nic nic;
    private byte[] ram;
    private final DeviceRig.Line line = new DeviceRig.Line();

    @BeforeEach
    void setUp() {
        rig = new DeviceRig();
        ram = new byte[64 * 1024];
        rig.bus.setRam(ram, RAM);
        nic = new Nic(rig.bus, line, MAC);
        nic.reset();
        rig.map(nic, 0x1000);
    }

    /** A frame to {@code dst} from 02:aa:..., type 0x0800, {@code payload} bytes of pattern. */
    private static byte[] frame(long dst, int payload, int seed) {
        byte[] f = new byte[14 + payload];
        for (int i = 0; i < 6; i++) {
            f[i] = (byte) (dst >>> (40 - 8 * i));
            f[6 + i] = (byte) (0x02_AA_BB_CC_DD_EEL >>> (40 - 8 * i));
        }
        f[12] = 0x08;
        for (int i = 0; i < payload; i++)
            f[14 + i] = (byte) (i * 3 + seed);
        return f;
    }

    private void transmit(byte[] f, int at) {
        System.arraycopy(f, 0, ram, at, f.length);
        rig.w32(TX_ADDR, RAM + at);
        rig.w32(TX_LEN, f.length);
        rig.w32(TX_CMD, TX_SEND);
    }

    @Test
    void macRegistersByByteHalfAndWordOnBothByteOrders() {
        for (ByteOrder order : new ByteOrder[] { ByteOrder.LITTLE_ENDIAN, ByteOrder.BIG_ENDIAN }) {
            SystemBus bus = new SystemBus(order);
            Nic n = new Nic(bus, IrqSink.NONE, MAC);
            bus.mapDevice(DeviceRig.BASE, 0x1000, n);
            int base = DeviceRig.BASE;
            assertEquals(0x33_22_11_02, bus.read32(base + MAC_LO), order.toString());
            assertEquals(0x55_44, bus.read32(base + MAC_HI));
            for (int i = 0; i < 4; i++)
                assertEquals((int) (MAC >>> (40 - 8 * i)) & 0xFF, bus.read8(base + MAC_LO + i), "byte " + i);
            assertEquals(0x44, bus.read8(base + MAC_HI));
            assertEquals(0x55, bus.read8(base + MAC_HI + 1));
            assertEquals(0x11_02, bus.read16(base + MAC_LO));
            assertEquals(0x33_22, bus.read16(base + MAC_LO + 2));
            assertEquals(0x55_44, bus.read16(base + MAC_HI));
        }
        assertEquals(MAC, nic.macAddress());
        assertThrows(IllegalArgumentException.class, () -> new Nic(rig.bus, line, 1L << 48));
    }

    @Test
    void transmitCopiesTheFrameAtOnce() {
        nic.setLink(true);
        byte[] f = frame(-1L >>> 16, 100, 1);
        transmit(f, 0x100);
        assertEquals(STATUS_TX_DONE | STATUS_LINK | STATUS_LINK_CHANGED, rig.r32(STATUS));
        assertEquals(ERR_NONE, rig.r32(ERROR_CODE));
        Arrays.fill(ram, (byte) 0);
        assertEquals(1, nic.outgoingCount());
        assertArrayEquals(f, nic.takeOutgoing(), "copied when sent, exact length");
        assertNull(nic.takeOutgoing());
        rig.w32(STATUS, STATUS_TX_DONE | STATUS_LINK_CHANGED);
        assertEquals(STATUS_LINK, rig.r32(STATUS));
        // the shortest and longest frames
        transmit(frame(0, 0, 0), 0);
        transmit(frame(0, 1500, 0), 0);
        assertEquals(2, nic.outgoingCount());
        assertEquals(MIN_FRAME, nic.takeOutgoing().length);
        assertEquals(MAX_FRAME, nic.takeOutgoing().length);
    }

    @Test
    void transmitErrors() {
        rig.w32(TX_ADDR, RAM);
        rig.w32(TX_LEN, 60);
        rig.w32(TX_CMD, TX_SEND);
        assertEquals(ERR_NO_LINK, rig.r32(ERROR_CODE));
        assertEquals(STATUS_TX_ERROR, rig.r32(STATUS));
        assertEquals(0, nic.outgoingCount());
        nic.setLink(true);
        int[][] cases = { { RAM, 13, ERR_BAD_LENGTH }, { RAM, 1515, ERR_BAD_LENGTH }, { RAM + 64 * 1024 - 10, 60, ERR_DMA_RANGE },
                { 0x4000_0000, 60, ERR_DMA_RANGE } };
        for (int[] c : cases) {
            rig.w32(STATUS, STATUS_TX_ERROR);
            rig.w32(TX_ADDR, c[0]);
            rig.w32(TX_LEN, c[1]);
            rig.w32(TX_CMD, TX_SEND);
            assertEquals(c[2], rig.r32(ERROR_CODE), Arrays.toString(c));
            assertTrue((rig.r32(STATUS) & STATUS_TX_ERROR) != 0);
        }
        rig.w32(STATUS, STATUS_TX_ERROR);
        for (int i = 0; i < QUEUE_FRAMES; i++)
            transmit(frame(0, 50, i), 0);
        assertEquals(ERR_NONE, rig.r32(ERROR_CODE));
        transmit(frame(0, 50, 99), 0);
        assertEquals(ERR_TX_FULL, rig.r32(ERROR_CODE), "the 33rd frame does not fit");
        assertEquals(QUEUE_FRAMES, nic.outgoingCount());
        rig.w32(TX_CMD, 2);
        assertEquals(ERR_TX_FULL, rig.r32(ERROR_CODE), "other TX_CMD values are ignored");
    }

    @Test
    void receiveFilter() {
        rig.w32(CTRL, CTRL_RX_ENABLE);
        assertTrue(nic.deliver(frame(MAC, 46, 1), 0, 60), "ours");
        assertTrue(nic.deliver(frame(0xFFFF_FFFF_FFFFL, 46, 2), 0, 60), "broadcast");
        assertTrue(nic.deliver(frame(0x01_00_5E_00_00_01L, 46, 3), 0, 60), "multicast");
        assertFalse(nic.deliver(frame(0x02_11_22_33_44_56L, 46, 4), 0, 60), "someone else's");
        assertEquals(3, rig.r32(RX_COUNT));
        assertEquals(0, rig.r32(RX_DROPPED), "filter misses are not counted");
        rig.w32(CTRL, CTRL_RX_ENABLE | CTRL_PROMISC);
        assertTrue(nic.deliver(frame(0x02_11_22_33_44_56L, 46, 4), 0, 60), "promiscuous takes everything");
        rig.w32(CTRL, 0);
        assertFalse(nic.deliver(frame(MAC, 46, 5), 0, 60));
        assertEquals(1, rig.r32(RX_DROPPED), "a frame arriving with RX_ENABLE off is dropped and counted");
        assertEquals(4, rig.r32(RX_COUNT));
    }

    @Test
    void receiveCopyDropAndBadDma() {
        rig.w32(CTRL, CTRL_RX_ENABLE);
        byte[] big = new byte[2000];
        byte[] a = frame(MAC, 100, 7);
        System.arraycopy(a, 0, big, 300, a.length);
        assertTrue(nic.deliver(big, 300, a.length), "a slice of a larger buffer");
        nic.deliver(frame(MAC, 10, 8), 0, 24);
        assertEquals(STATUS_RX_READY, rig.r32(STATUS));
        assertEquals(114, rig.r32(RX_LEN));
        rig.w32(RX_ADDR, RAM + 64 * 1024 - 50);
        rig.w32(RX_CMD, RX_COPY);
        assertEquals(ERR_DMA_RANGE, rig.r32(ERROR_CODE));
        assertEquals(2, rig.r32(RX_COUNT), "a bad copy keeps the frame");
        rig.w32(RX_ADDR, RAM + 0x400);
        rig.w32(RX_CMD, RX_COPY);
        assertEquals(ERR_NONE, rig.r32(ERROR_CODE));
        assertArrayEquals(a, Arrays.copyOfRange(ram, 0x400, 0x400 + a.length));
        assertEquals(24, rig.r32(RX_LEN));
        rig.w32(RX_CMD, RX_DROP);
        assertEquals(0, rig.r32(RX_LEN));
        assertEquals(0, rig.r32(STATUS) & STATUS_RX_READY);
        rig.w32(RX_CMD, RX_DROP);
        assertEquals(ERR_RX_EMPTY, rig.r32(ERROR_CODE));
        rig.w32(RX_CMD, RX_COPY);
        assertEquals(ERR_RX_EMPTY, rig.r32(ERROR_CODE));
    }

    @Test
    void overflowAndRxDropped() {
        rig.w32(CTRL, CTRL_RX_ENABLE);
        for (int i = 0; i < QUEUE_FRAMES; i++)
            assertTrue(nic.deliver(frame(MAC, 46, i), 0, 60));
        assertFalse(nic.deliver(frame(MAC, 46, 40), 0, 60));
        assertFalse(nic.deliver(frame(MAC, 46, 41), 0, 60));
        assertEquals(STATUS_RX_READY | STATUS_RX_OVERFLOW, rig.r32(STATUS));
        assertEquals(2, rig.r32(RX_DROPPED));
        assertFalse(nic.deliver(new byte[13], 0, 13), "a runt is dropped");
        assertEquals(3, rig.r32(RX_DROPPED));
        rig.w32(STATUS, STATUS_RX_OVERFLOW);
        assertEquals(STATUS_RX_READY, rig.r32(STATUS));
        nic.reset();
        assertEquals(0, rig.r32(RX_DROPPED));
        assertEquals(0, rig.r32(RX_COUNT));
    }

    @Test
    void eachInterruptCondition() {
        rig.w32(CTRL, CTRL_RX_ENABLE | CTRL_IRQ_RX);
        assertFalse(line.level);
        nic.deliver(frame(MAC, 46, 0), 0, 60);
        assertTrue(line.level, "RX_READY");
        rig.w32(RX_CMD, RX_DROP);
        assertFalse(line.level);

        rig.w32(CTRL, CTRL_IRQ_TX);
        transmit(frame(0, 46, 0), 0);
        assertTrue(line.level, "TX_ERROR (no link)");
        rig.w32(STATUS, STATUS_TX_ERROR);
        assertFalse(line.level);
        nic.setLink(true);
        assertFalse(line.level, "link changes are not enabled");
        rig.w32(STATUS, STATUS_LINK_CHANGED);
        transmit(frame(0, 46, 0), 0);
        assertTrue(line.level, "TX_DONE");
        rig.w32(STATUS, STATUS_TX_DONE);
        assertFalse(line.level);

        rig.w32(CTRL, CTRL_IRQ_LINK);
        nic.setLink(true);
        assertFalse(line.level, "no change, no LINK_CHANGED");
        nic.setLink(false);
        assertTrue(line.level, "LINK_CHANGED");
        assertEquals(STATUS_LINK_CHANGED, rig.r32(STATUS));
        rig.w32(STATUS, STATUS_LINK_CHANGED);
        assertFalse(line.level);
        assertEquals(Long.MAX_VALUE, nic.nextEventCycle());
    }

    @Test
    void droppingTheLinkKeepsTheQueues() {
        nic.setLink(true);
        rig.w32(CTRL, CTRL_RX_ENABLE);
        transmit(frame(0, 46, 0), 0);
        nic.deliver(frame(MAC, 46, 0), 0, 60);
        nic.setLink(false);
        assertEquals(1, nic.outgoingCount());
        assertEquals(1, rig.r32(RX_COUNT));
        assertFalse(nic.link());
    }

    @Test
    void savedStateRoundTrip() throws IOException {
        Function<DeviceStateTest.Twin<Nic>, Nic> make = t -> new Nic(t.rig.bus, t.line, MAC);
        DeviceStateTest.Twin<Nic> a = new DeviceStateTest.Twin<>(make, 0x1000);
        a.dev.setLink(true);
        a.rig.w32(CTRL, CTRL_RX_ENABLE | CTRL_IRQ_RX | CTRL_IRQ_TX);
        for (int i = 0; i < 3; i++) {
            byte[] f = frame(0x02_00_00_00_00_01L, 40 + i, i);
            System.arraycopy(f, 0, a.ram, 0x100, f.length);
            a.rig.w32(TX_ADDR, RAM + 0x100);
            a.rig.w32(TX_LEN, f.length);
            a.rig.w32(TX_CMD, TX_SEND);
            a.dev.deliver(frame(MAC, 50 + i, 10 + i), 0, 64 + i);
        }
        a.rig.w32(RX_ADDR, RAM + 0x2000);
        DeviceStateTest.roundTrip(a, make, 0x1000, t -> {
            List<Object> seen = new ArrayList<>();
            t.rig.w32(RX_CMD, RX_COPY);
            seen.add(t.rig.r32(RX_LEN));
            seen.add(t.rig.r32(STATUS));
            byte[] out;
            while ((out = t.dev.takeOutgoing()) != null)
                seen.add(Arrays.toString(out));
            return seen;
        });
        DeviceStateTest.assertRejectsUnknownVersion(new Nic(rig.bus, line, MAC), DeviceStateTest.save(a.dev));
        assertThrows(StateFormatException.class, () -> DeviceStateTest.load(new Nic(rig.bus, line, MAC + 1),
                DeviceStateTest.save(a.dev)), "another card's MAC");
    }
}
