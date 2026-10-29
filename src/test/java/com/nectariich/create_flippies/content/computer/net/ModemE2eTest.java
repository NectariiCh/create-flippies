package com.nectariich.create_flippies.content.computer.net;

import static com.nectariich.create_flippies.content.computer.device.Nic.*;
import static com.nectariich.create_flippies.content.computer.net.NetFrames.*;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.nectariich.create_flippies.content.computer.bus.IrqSink;
import com.nectariich.create_flippies.content.computer.bus.SystemBus;
import com.nectariich.create_flippies.content.computer.device.Nic;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.Random;
import java.util.function.BooleanSupplier;

import org.junit.jupiter.api.Test;

/**
 * The modem against a real network card (spec 12.3, 12.4, 12.11): the scripted client stack
 * plays the guest, driving the card through its registers on a big-endian bus as the netinstall
 * floppy will (TX_CSUM fills every checksum, RX_INFO checks the modem's), and the test runs
 * ticks as the game does: {@link Modem#exchange}, {@link Modem#tick}, then the guest. The guest
 * stops reading for some ticks so the card's receive queue fills: the modem holds what the card
 * refuses and the download arrives whole without a retransmission.
 */
class ModemE2eTest {

    private static final int NIC = 0x4000_0000, TXF = 0x1000, RXF = 0x2000;

    private final SystemBus bus = new SystemBus(ByteOrder.BIG_ENDIAN);
    private final byte[] ram = new byte[64 * 1024];
    private final Nic nic = new Nic(bus, IrqSink.NONE, PC_MAC);
    private final TestClient client = new TestClient();
    private final Random random = new Random(2026);
    private Modem modem;
    private int ticks;
    /** Ticks after which the modem still held frames the card had no room for. */
    private int heldTicks;
    /** Frames the guest reads per tick; 0 to stall. */
    private int perTick = Integer.MAX_VALUE;

    /** A 2 MiB disk image with three all-zero chunks, as an .fdi stored deflated, and the index listing it. */
    private static final byte[] RAW = rawImage();
    private static final byte[] FDI = Fdi.write(RAW, "Test image");

    private static byte[] rawImage() {
        byte[] raw = pattern(32 * 65536, 77);
        for (int zero : new int[] { 3, 7, 8 })
            Arrays.fill(raw, zero * 65536, (zero + 1) * 65536, (byte) 0);
        return raw;
    }

    private void setUp() {
        bus.setRam(ram, 0);
        bus.mapDevice(NIC, 0x1000, nic);
        nic.reset();
        bus.write32(NIC + CTRL, CTRL_RX_ENABLE | CTRL_TX_CSUM);
        String index = "# test catalogue\nimage /images/test.fdi " + FDI.length + " Test image\nfile /readme.txt 6 Read me\n";
        MemoryContent content = new MemoryContent().put("get.flip/index.txt", index).put("get.flip/readme.txt", "hello\n")
                .putAll(DeflatedChunks.store("get.flip/images/test.fdi", FDI));
        modem = new Modem(MODEM_MAC, new Catalogue(content));
    }

    /** Sends through the card with its checksum fields zeroed, so TX_CSUM must fill them. */
    private void transmit(byte[] frame) {
        byte[] f = frame.clone();
        if (proto(f) >= 0) {
            Packets.put16(f, 24, 0);
            if (proto(f) == 6)
                Packets.put16(f, 50, 0);
            else if (proto(f) == 17)
                Packets.put16(f, 40, 0);
        }
        System.arraycopy(f, 0, ram, TXF, f.length);
        bus.write32(NIC + TX_ADDR, TXF);
        bus.write32(NIC + TX_LEN, f.length);
        bus.write32(NIC + TX_CMD, TX_SEND);
        assertEquals(ERR_NONE, bus.read32(NIC + ERROR_CODE));
    }

    /** The guest's turn: read up to {@link #perTick} frames, checking RX_INFO, then send what the client wants to. */
    private void guest() {
        for (int n = 0; n < perTick && bus.read32(NIC + RX_COUNT) > 0; n++) {
            int length = bus.read32(NIC + RX_LEN), info = bus.read32(NIC + RX_INFO);
            bus.write32(NIC + RX_ADDR, RXF);
            bus.write32(NIC + RX_CMD, RX_COPY);
            byte[] f = Arrays.copyOfRange(ram, RXF, RXF + length);
            int proto = proto(f);
            if (proto == 6 || proto == 17)
                assertEquals(RX_INFO_IPV4 | RX_INFO_IP_OK | RX_INFO_L4_OK, info, "the card finds the modem's checksums good");
            else if (proto >= 0)
                assertEquals(RX_INFO_IPV4 | RX_INFO_IP_OK, info);
            client.receive(f);
        }
        client.flushAck();
        while (!client.outbox.isEmpty())
            transmit(client.outbox.remove(0));
    }

    private void tick() {
        modem.exchange(nic);
        if (modem.outgoingCount() > 0)
            heldTicks++;
        modem.tick();
        guest();
        ticks++;
    }

    private void until(BooleanSupplier done, int max) {
        for (int i = 0; !done.getAsBoolean(); i++) {
            assertTrue(i < max, "not done after " + max + " ticks");
            tick();
        }
    }

    private static int be32(byte[] b, int at) {
        return Packets.i32(b, at);
    }

    @Test
    void dhcpDnsAndADownloadThroughTheCard() throws IOException {
        setUp();
        tick();
        assertEquals(STATUS_LINK, bus.read32(NIC + STATUS) & STATUS_LINK);

        // DHCP: DISCOVER, OFFER, REQUEST, ACK
        client.dhcp(1, 0x1111, false, 0, 0, 0);
        until(() -> client.dhcpReplies.size() == 1, 5);
        byte[] offer = client.dhcpReplies.get(0);
        int offered = be32(offer, 16);
        assertEquals(PC_IP, offered);
        assertEquals(MODEM_IP, be32(TestClient.dhcpOption(offer, 54), 0));
        client.dhcp(3, 0x1111, false, 0, offered, MODEM_IP);
        until(() -> client.dhcpReplies.size() == 2, 5);
        byte[] ack = client.dhcpReplies.get(1);
        assertEquals(5, TestClient.dhcpOption(ack, 53)[0]);
        assertEquals(MODEM_IP, be32(TestClient.dhcpOption(ack, 3), 0), "router");
        assertEquals(MODEM_IP, be32(TestClient.dhcpOption(ack, 6), 0), "DNS");
        assertEquals(PC_MAC, modem.leaseHolder());

        // ARP for the router, then names
        client.outbox.add(arpRequest(PC_MAC, PC_IP, MODEM_IP));
        int seen = client.inbox.size();
        until(() -> client.inbox.size() > seen, 5);
        assertEquals(MODEM_MAC, Packets.mac(client.inbox.get(seen), 22), "the router's MAC");
        client.dns(0x0101, "get.flip", 1);
        client.dns(0x0102, "missing.flip", 1);
        until(() -> client.dnsReplies.size() == 2, 5);
        byte[] hit = client.dnsReplies.get(0), miss = client.dnsReplies.get(1);
        assertEquals(1, Packets.u16(hit, 6));
        assertEquals(MODEM_IP, be32(hit, hit.length - 4));
        assertEquals(3, miss[3] & 0x0F, "NXDOMAIN");

        // TCP with a 1 MiB window: more than the card's 256 frames may be in flight
        client.window = 65535 << 4;
        client.windowShift = 4;
        client.connect();
        until(() -> client.established, 5);
        client.send("GET /index.txt HTTP/1.1\r\nHost: get.flip\r\nUser-Agent: netinstall\r\n\r\n");
        until(() -> client.response(0, false) != null, 10);
        TestClient.HttpReply index = client.response(0, false);
        List<CatalogueIndex.Entry> entries = CatalogueIndex.parse(new String(index.body(), StandardCharsets.UTF_8));
        CatalogueIndex.Entry image = entries.get(0);
        assertEquals(new CatalogueIndex.Entry("image", "/images/test.fdi", FDI.length, "Test image"), image);

        // The download, with the guest stalling now and then
        client.send("GET " + image.path() + " HTTP/1.1\r\nHost: get.flip\r\n\r\n");
        int from = index.end();
        for (int i = 0; client.response(from, false) == null; i++) {
            assertTrue(i < 4000, "download stalled");
            perTick = random.nextInt(5) == 0 ? 0 : 20 + random.nextInt(300);
            tick();
        }
        perTick = Integer.MAX_VALUE;
        TestClient.HttpReply download = client.response(from, false);
        assertEquals(200, download.status());
        assertEquals(String.valueOf(FDI.length), download.header("content-length"));
        assertArrayEquals(FDI, download.body());
        assertArrayEquals(RAW, Fdi.readImage(new ByteArrayInputStream(download.body())));
        assertTrue(heldTicks > 0, "the card's queue was full at times, so the modem held frames");
        assertEquals(0, bus.read32(NIC + RX_DROPPED), "held frames are not counted as dropped");
        assertEquals(0, bus.read32(NIC + STATUS) & STATUS_RX_OVERFLOW);
        assertEquals(0, modem.retransmissions(), "nothing lost on the link");
        assertEquals(0, modem.framesDropped());
        assertEquals(0, client.outOfOrder);

        // A resumed download from the third present chunk
        Fdi.Header h = Fdi.readHeader(new ByteArrayInputStream(FDI));
        long resume = h.dataOffset(2);
        client.send("GET /images/test.fdi HTTP/1.1\r\nHost: get.flip\r\nRange: bytes=" + resume + "-\r\n\r\n");
        int from2 = download.end();
        until(() -> client.response(from2, false) != null, 400);
        TestClient.HttpReply rest = client.response(from2, false);
        assertEquals(206, rest.status());
        assertEquals("bytes " + resume + "-" + (FDI.length - 1) + "/" + FDI.length, rest.header("content-range"));
        assertArrayEquals(Arrays.copyOfRange(FDI, (int) resume, FDI.length), rest.body());

        // Close: the client's FIN, the modem's, both acknowledged
        client.fin();
        until(() -> client.finReceived, 10);
        tick();
        tick();
        assertEquals(0, modem.connectionCount());
        assertTrue(ticks < 4500);
    }

    @Test
    void pingThroughTheCard() {
        setUp();
        tick();
        byte[] icmp = new byte[40];
        icmp[0] = 8;
        Packets.put16(icmp, 6, 1);
        Packets.put16(icmp, 2, Packets.checksum(icmp, 0, icmp.length));
        client.outbox.add(ipv4(PC_MAC, MODEM_MAC, PC_IP, MODEM_IP, 1, icmp));
        until(() -> !client.inbox.isEmpty(), 3);
        byte[] reply = client.inbox.get(0);
        assertEquals(1, proto(reply));
        assertEquals(0, reply[34]);
        assertEquals(1, Packets.u16(reply, 40));
    }

    @Test
    void aCardWithItsReceiverOffDoesNotHoldTheModemForever() {
        setUp();
        bus.write32(NIC + CTRL, CTRL_TX_CSUM);
        tick();
        client.outbox.add(arpRequest(PC_MAC, PC_IP, MODEM_IP));
        tick(); // the guest sends it
        tick(); // the modem answers; the card refuses
        assertEquals(1, modem.outgoingCount(), "refused, so it waits");
        for (int i = 0; i < Modem.HOLD_TICKS; i++)
            tick();
        assertEquals(0, modem.outgoingCount(), "dropped after a second of refusals");
        assertEquals(1, modem.framesDropped());
    }
}
