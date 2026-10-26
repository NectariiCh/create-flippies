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

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.function.Function;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** The network card (spec 11.1.7, 12.3) on a little-endian bus with 64 KiB of RAM. */
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
        assertEquals(ERR_TX_FULL, rig.r32(ERROR_CODE), "the 257th frame does not fit");
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

    // ---- faster ethernet (spec 12.3) ----

    private static final int UDP = 17, TCP = 6;

    /**
     * An IPv4 frame to us from 10.88.0.1 to 10.88.0.2 with checksums 0: a header of {@code ihl}
     * bytes (options are NOPs), then a {@code proto} segment of {@code l4} bytes with its header
     * filled in (UDP length, TCP data offset) and a payload pattern.
     */
    private static byte[] ipv4(int proto, int ihl, int l4, int seed) {
        byte[] f = frame(MAC, ihl + l4, 0);
        Arrays.fill(f, 14, f.length, (byte) 0);
        f[14] = (byte) (0x40 | ihl / 4);
        f[16] = (byte) ((ihl + l4) >> 8);
        f[17] = (byte) (ihl + l4);
        f[18] = 0x12;
        f[19] = (byte) seed;
        f[20] = 0x40; // DF
        f[22] = 64;
        f[23] = (byte) proto;
        byte[] addresses = { 10, 88, 0, 1, 10, 88, 0, 2 };
        System.arraycopy(addresses, 0, f, 26, 8);
        for (int i = 34; i < 14 + ihl; i++)
            f[i] = 1;
        int s = 14 + ihl;
        f[s] = 0x30;
        f[s + 1] = 0x39;
        f[s + 3] = 80;
        int body = proto == TCP ? 20 : 8;
        if (proto == UDP) {
            f[s + 4] = (byte) (l4 >> 8);
            f[s + 5] = (byte) l4;
        } else {
            f[s + 12] = 0x50;
        }
        for (int i = s + body; i < f.length; i++)
            f[i] = (byte) (i * 13 + seed);
        return f;
    }

    /** RFC 1071 by the book: the one's complement of the one's complement sum of 16-bit words. */
    private static int referenceChecksum(byte[] data) {
        long sum = 0;
        for (int i = 0; i < data.length; i += 2)
            sum += (data[i] & 0xFF) << 8 | (i + 1 < data.length ? data[i + 1] & 0xFF : 0);
        while (sum > 0xFFFF)
            sum = (sum & 0xFFFF) + (sum >> 16);
        return (int) ~sum & 0xFFFF;
    }

    private static int field(byte[] f, int at) {
        return (f[at] & 0xFF) << 8 | f[at + 1] & 0xFF;
    }

    private static void setField(byte[] f, int at, int v) {
        f[at] = (byte) (v >> 8);
        f[at + 1] = (byte) v;
    }

    /** The reference IPv4 header checksum of {@code f}, its field taken as 0. */
    private static int referenceHeader(byte[] f) {
        int ihl = (f[14] & 0xF) * 4;
        byte[] h = Arrays.copyOfRange(f, 14, 14 + ihl);
        h[10] = h[11] = 0;
        return referenceChecksum(h);
    }

    /** The reference TCP or UDP checksum of {@code f} over pseudo-header and segment, its field taken as 0. */
    private static int referenceSegment(byte[] f) {
        int ihl = (f[14] & 0xF) * 4, at = 14 + ihl, proto = f[23] & 0xFF;
        int length = proto == UDP ? field(f, at + 4) : field(f, 16) - ihl;
        byte[] p = new byte[12 + length];
        System.arraycopy(f, 26, p, 0, 8);
        p[9] = (byte) proto;
        p[10] = (byte) (length >> 8);
        p[11] = (byte) length;
        System.arraycopy(f, at, p, 12, length);
        p[12 + (proto == UDP ? 6 : 16)] = p[13 + (proto == UDP ? 6 : 16)] = 0;
        return referenceChecksum(p);
    }

    private static int checksumAt(byte[] f) {
        return 14 + (f[14] & 0xF) * 4 + ((f[23] & 0xFF) == UDP ? 6 : 16);
    }

    /** {@code f} with both checksums filled in by the reference sums. */
    private static byte[] withChecksums(byte[] f) {
        byte[] g = f.clone();
        setField(g, 24, referenceHeader(g));
        int c = referenceSegment(g);
        setField(g, checksumAt(g), (g[23] & 0xFF) == UDP && c == 0 ? 0xFFFF : c);
        return g;
    }

    /** RX_INFO of the oldest frame after delivering {@code f}, then the frame dropped. */
    private int infoOf(byte[] f) {
        assertTrue(nic.deliver(f, 0, f.length));
        int info = rig.r32(RX_INFO);
        rig.w32(RX_CMD, RX_DROP);
        return info;
    }

    @Test
    void rxInfoOnGoodAndCorruptFrames() {
        int all = RX_INFO_IPV4 | RX_INFO_IP_OK | RX_INFO_L4_OK;
        assertEquals(0x38, RX_INFO);
        assertEquals(0x3C, SIZE);
        assertEquals(SIZE, nic.size());
        rig.w32(CTRL, CTRL_RX_ENABLE);
        assertEquals(0, rig.r32(RX_INFO), "no frame");

        byte[] udp = withChecksums(ipv4(UDP, 20, 1480, 1));
        byte[] tcp = withChecksums(ipv4(TCP, 20, 1480, 2));
        assertEquals(1514, udp.length);
        assertEquals(all, infoOf(udp), "UDP, good");
        assertEquals(all, infoOf(tcp), "TCP, good");
        assertEquals(all, infoOf(withChecksums(ipv4(UDP, 24, 9, 3))), "options, odd length");
        assertEquals(all, infoOf(withChecksums(ipv4(TCP, 60, 21, 4))), "longest header, odd length");
        byte[] padded = Arrays.copyOf(withChecksums(ipv4(UDP, 20, 8, 5)), 60);
        Arrays.fill(padded, 42, 60, (byte) 0x5A);
        assertEquals(all, infoOf(padded), "padding after the IPv4 total length is ignored");

        byte[] payload = udp.clone();
        payload[1000] ^= 1;
        assertEquals(RX_INFO_IPV4 | RX_INFO_IP_OK, infoOf(payload), "a corrupt UDP payload");
        byte[] segment = tcp.clone();
        segment[14 + 20 + 16] ^= (byte) 0x80;
        assertEquals(RX_INFO_IPV4 | RX_INFO_IP_OK, infoOf(segment), "a corrupt TCP checksum");
        byte[] header = udp.clone();
        header[22]--;
        assertEquals(RX_INFO_IPV4, infoOf(header), "a corrupt IPv4 header (TTL)");
        byte[] zero = udp.clone();
        setField(zero, 14 + 20 + 6, 0);
        assertEquals(RX_INFO_IPV4 | RX_INFO_IP_OK, infoOf(zero), "a UDP checksum of 0 is not checked");
        byte[] fragment = ipv4(UDP, 20, 100, 6);
        fragment[20] = 0x20; // MF
        fragment = withChecksums(fragment);
        assertEquals(RX_INFO_IPV4 | RX_INFO_IP_OK, infoOf(fragment), "a fragment's segment is not checked");
        byte[] icmp = ipv4(1, 20, 64, 7);
        setField(icmp, 24, referenceHeader(icmp));
        assertEquals(RX_INFO_IPV4 | RX_INFO_IP_OK, infoOf(icmp), "neither TCP nor UDP");
        byte[] truncated = Arrays.copyOf(udp, 1000);
        assertEquals(RX_INFO_IPV4, infoOf(truncated), "total length past the frame's end");
        byte[] v6 = udp.clone();
        v6[14] = 0x65;
        assertEquals(RX_INFO_IPV4, infoOf(v6), "version not 4");
        assertEquals(RX_INFO_IPV4, infoOf(Arrays.copyOf(udp, 30)), "too short for an IPv4 header");
        byte[] arp = udp.clone();
        arp[13] = 0x06;
        assertEquals(0, infoOf(arp), "ARP");

        // The oldest frame's, recomputed as the queue moves; byte reads see the same value
        nic.deliver(header, 0, header.length);
        nic.deliver(udp, 0, udp.length);
        assertEquals(RX_INFO_IPV4, rig.r32(RX_INFO));
        rig.w32(RX_CMD, RX_DROP);
        assertEquals(all, rig.bus.read8(DeviceRig.BASE + RX_INFO));
    }

    @Test
    void txCsumFillsTheChecksums() {
        nic.setLink(true);
        rig.w32(CTRL, 0xFF);
        assertEquals(0x3F, rig.r32(CTRL), "CTRL keeps bits 5..0");
        rig.w32(CTRL, CTRL_TX_CSUM);

        // The worked example from the IPv4 header checksum literature: 45 00 00 73 ... b8 61
        byte[] known = ipv4(UDP, 20, 95, 0);
        byte[] header = { 0x45, 0x00, 0x00, 0x73, 0x00, 0x00, 0x40, 0x00, 0x40, 0x11, 0x00, 0x00, (byte) 0xC0,
                (byte) 0xA8, 0x00, 0x01, (byte) 0xC0, (byte) 0xA8, 0x00, (byte) 0xC7 };
        System.arraycopy(header, 0, known, 14, 20);
        assertEquals(0xB861, referenceHeader(known), "the reference sum");
        transmit(known, 0x100);
        byte[] sent = nic.takeOutgoing();
        assertEquals(0xB861, field(sent, 24));
        assertArrayEquals(known, Arrays.copyOfRange(ram, 0x100, 0x100 + known.length), "guest memory is not touched");

        for (byte[] f : new byte[][] { ipv4(UDP, 20, 1480, 1), ipv4(TCP, 20, 1480, 2), ipv4(UDP, 32, 33, 3),
                ipv4(TCP, 60, 21, 4) }) {
            byte[] garbage = f.clone();
            setField(garbage, 24, 0xABCD);
            setField(garbage, checksumAt(garbage), 0x1234);
            transmit(garbage, 0x100);
            sent = nic.takeOutgoing();
            assertArrayEquals(withChecksums(f), sent, "both sums match the reference, whatever was in the fields");
            assertEquals(RX_INFO_IPV4 | RX_INFO_IP_OK | RX_INFO_L4_OK, sentInfo(sent), "and RX_INFO agrees");
        }

        // A UDP segment whose sum comes to 0 is sent as 0xFFFF: add the checksum into the payload
        // (one's complement), which makes the new sum all ones
        byte[] f = ipv4(UDP, 20, 100, 9);
        int c = referenceSegment(f);
        int w = field(f, 60) + c;
        setField(f, 60, (w & 0xFFFF) + (w >> 16));
        assertEquals(0, referenceSegment(f), "the sum now comes to 0");
        transmit(f, 0x100);
        sent = nic.takeOutgoing();
        assertEquals(0xFFFF, field(sent, checksumAt(sent)), "UDP sends a computed 0 as all ones");
        assertEquals(RX_INFO_IPV4 | RX_INFO_IP_OK | RX_INFO_L4_OK, sentInfo(sent));

        // A fragment gets only its header checksum; other frames go out unchanged
        byte[] fragment = ipv4(UDP, 20, 100, 10);
        fragment[21] = 8; // offset 64
        transmit(fragment, 0x100);
        sent = nic.takeOutgoing();
        assertEquals(referenceHeader(fragment), field(sent, 24));
        assertEquals(0, field(sent, checksumAt(sent)));
        byte[] arp = ipv4(UDP, 20, 100, 11);
        arp[13] = 0x06;
        transmit(arp, 0x100);
        assertArrayEquals(arp, nic.takeOutgoing());
        byte[] truncated = Arrays.copyOf(ipv4(UDP, 20, 1480, 12), 600);
        transmit(truncated, 0x100);
        assertArrayEquals(truncated, nic.takeOutgoing(), "an IPv4 total length past the frame's end");

        rig.w32(CTRL, 0);
        byte[] plain = ipv4(UDP, 20, 100, 13);
        transmit(plain, 0x100);
        assertArrayEquals(plain, nic.takeOutgoing(), "TX_CSUM off sends the bytes as they are");
    }

    @Test
    void aUdpSegmentShorterThanItsHeaderIsNotChecked() {
        // IPv4 to protocol UDP whose payload ends before the UDP length field, the frame ending
        // where the total length says: neither RX_INFO nor TX_CSUM may read past it
        nic.setLink(true);
        for (int l4 = 0; l4 < 8; l4++) {
            byte[] f = Arrays.copyOf(ipv4(UDP, 20, 8, l4), 14 + 20 + l4);
            setField(f, 16, 20 + l4);
            setField(f, 24, referenceHeader(f));
            rig.w32(CTRL, CTRL_RX_ENABLE);
            assertEquals(RX_INFO_IPV4 | RX_INFO_IP_OK, infoOf(f), "a " + l4 + "-byte UDP segment");
            rig.w32(CTRL, CTRL_TX_CSUM);
            byte[] garbage = f.clone();
            setField(garbage, 24, 0xABCD);
            transmit(garbage, 0x100);
            assertArrayEquals(f, nic.takeOutgoing(), "only the header checksum is filled in");
        }
    }

    @Test
    void canReceiveAsksWithoutCountingADrop() {
        assertFalse(nic.canReceive(), "the receiver is off");
        rig.w32(CTRL, CTRL_RX_ENABLE);
        for (int i = 0; i < QUEUE_FRAMES; i++) {
            assertTrue(nic.canReceive());
            assertTrue(nic.deliver(frame(MAC, 46, i), 0, 60));
        }
        assertFalse(nic.canReceive(), "the queue is full");
        assertEquals(STATUS_RX_READY, rig.r32(STATUS), "asking sets no RX_OVERFLOW");
        assertEquals(0, rig.r32(RX_DROPPED), "and counts nothing");
        rig.w32(RX_CMD, RX_DROP);
        assertTrue(nic.canReceive());
    }

    /** RX_INFO for {@code f} on a second card, so this one's queues stay as they are. */
    private static int sentInfo(byte[] f) {
        DeviceRig other = new DeviceRig();
        Nic n = new Nic(other.bus, IrqSink.NONE, MAC);
        n.write(CTRL, 4, CTRL_RX_ENABLE);
        assertTrue(n.deliver(f, 0, f.length));
        return n.read(RX_INFO, 4);
    }

    @Test
    void queuesHold256FramesEachWay() {
        assertEquals(256, QUEUE_FRAMES);
        nic.setLink(true);
        rig.w32(CTRL, CTRL_RX_ENABLE);
        List<byte[]> sent = new ArrayList<>();
        for (int i = 0; i < 256; i++) {
            byte[] f = frame(MAC, 1500, i);
            f[14] = (byte) i;
            sent.add(f);
            transmit(f, 0x100);
            assertEquals(ERR_NONE, rig.r32(ERROR_CODE), "frame " + i);
            assertTrue(nic.deliver(f, 0, f.length), "frame " + i);
        }
        transmit(frame(MAC, 1500, 0), 0x100);
        assertEquals(ERR_TX_FULL, rig.r32(ERROR_CODE));
        assertFalse(nic.deliver(frame(MAC, 1500, 0), 0, 1514));
        assertEquals(256, nic.outgoingCount());
        assertEquals(256, rig.r32(RX_COUNT));
        for (int i = 0; i < 256; i++) {
            assertArrayEquals(sent.get(i), nic.takeOutgoing(), "oldest first");
            rig.w32(RX_ADDR, RAM + 0x1000);
            rig.w32(RX_CMD, RX_COPY);
            assertArrayEquals(sent.get(i), Arrays.copyOfRange(ram, 0x1000, 0x1000 + 1514));
        }
        assertEquals(0, rig.r32(RX_COUNT));
    }

    /**
     * Version 1 of the card's state written field by field, as the build with 32-frame queues
     * wrote it: {@code tx} and {@code rx} frames queued.
     */
    private static byte[] version1State(List<byte[]> tx, List<byte[]> rx) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(bytes);
        out.writeInt(1);
        out.writeLong(MAC);
        out.writeInt(CTRL_RX_ENABLE | CTRL_IRQ_RX);
        out.writeBoolean(true);     // TX_DONE
        out.writeBoolean(false);    // TX_ERROR
        out.writeBoolean(true);     // RX_OVERFLOW
        out.writeBoolean(true);     // LINK
        out.writeBoolean(false);    // LINK_CHANGED
        out.writeInt(RAM + 0x100);  // TX_ADDR
        out.writeInt(60);           // TX_LEN
        out.writeInt(RAM + 0x2000); // RX_ADDR
        out.writeInt(ERR_NONE);
        out.writeInt(7);            // RX_DROPPED
        out.writeBoolean(true);     // line level
        for (List<byte[]> queue : List.of(tx, rx)) {
            out.writeInt(queue.size());
            for (byte[] f : queue) {
                out.writeShort(f.length);
                out.write(f);
            }
        }
        return bytes.toByteArray();
    }

    @Test
    void stateSavedWith32QueuedFramesStillLoads() throws IOException {
        List<byte[]> tx = new ArrayList<>(), rx = new ArrayList<>();
        for (int i = 0; i < 32; i++) {
            tx.add(frame(0x02_00_00_00_00_01L, 46 + i, i));
            rx.add(frame(MAC, 1500, 100 + i));
        }
        byte[] old = version1State(tx, rx);
        DeviceStateTest.load(nic, old);
        assertArrayEquals(old, DeviceStateTest.save(nic), "the format is unchanged");
        assertEquals(STATUS_RX_READY | STATUS_TX_DONE | STATUS_RX_OVERFLOW | STATUS_LINK, rig.r32(STATUS));
        assertEquals(7, rig.r32(RX_DROPPED));
        assertEquals(32, rig.r32(RX_COUNT));
        for (int i = 0; i < 32; i++) {
            assertArrayEquals(tx.get(i), nic.takeOutgoing());
            rig.w32(RX_CMD, RX_COPY);
            assertArrayEquals(rx.get(i), Arrays.copyOfRange(ram, 0x2000, 0x2000 + 1514));
        }
        // and the queues now take up to 256 more after a loaded state
        for (int i = 0; i < QUEUE_FRAMES; i++)
            assertTrue(nic.deliver(frame(MAC, 46, i), 0, 60));

        List<byte[]> tooMany = new ArrayList<>();
        for (int i = 0; i <= QUEUE_FRAMES; i++)
            tooMany.add(frame(MAC, 0, i));
        assertThrows(StateFormatException.class, () -> DeviceStateTest.load(new Nic(rig.bus, line, MAC),
                version1State(List.of(), tooMany)), "more frames than a queue holds");
    }
}
