package com.nectariich.create_flippies.content.computer.net;

import static com.nectariich.create_flippies.content.computer.net.NetFrames.*;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** The modem's ARP, ping, DHCP and DNS (spec 12.4.1, 12.4.2), frame by frame. */
class ModemTest {

    private final MemoryContent content = new MemoryContent().put("get.flip/index.txt", "file /a.txt 3 A\n")
            .put("deb.flip/dists/x", "x");
    private Modem modem;

    @BeforeEach
    void setUp() {
        modem = new Modem(MODEM_MAC, new Catalogue(content));
        modem.setLink(true);
    }

    /** Delivers {@code f} and returns every frame the modem queued in answer. */
    private List<byte[]> send(byte[] f) {
        assertTrue(modem.deliver(f, 0, f.length));
        List<byte[]> out = new ArrayList<>();
        for (byte[] r; (r = modem.takeOutgoing()) != null; )
            out.add(r);
        return out;
    }

    private static int u16(byte[] b, int at) {
        return (b[at] & 0xFF) << 8 | b[at + 1] & 0xFF;
    }

    private static int i32(byte[] b, int at) {
        return u16(b, at) << 16 | u16(b, at + 2);
    }

    private static long mac(byte[] b, int at) {
        return (long) u16(b, at) << 32 | i32(b, at + 2) & 0xFFFF_FFFFL;
    }

    // ---- framing ----

    @Test
    void refusesFramesNotForIt() {
        byte[] f = arpRequest(PC_MAC, PC_IP, MODEM_IP);
        Packets.putMac(f, 0, 0x02_00_00_00_00_01L);
        assertFalse(modem.deliver(f, 0, f.length), "another unicast MAC");
        assertFalse(modem.deliver(new byte[13], 0, 13), "shorter than a header");
        assertFalse(modem.deliver(new byte[1515], 0, 1515), "longer than 1514");
        byte[] ok = arpRequest(PC_MAC, PC_IP, MODEM_IP);
        byte[] padded = new byte[ok.length + 10];
        System.arraycopy(ok, 0, padded, 5, ok.length);
        assertTrue(modem.deliver(padded, 5, ok.length), "an offset into a larger array");
        assertEquals(1, modem.outgoingCount());
        assertThrows(IndexOutOfBoundsException.class, () -> modem.deliver(ok, 1, ok.length));
        modem.setLink(false);
        assertEquals(0, modem.outgoingCount(), "link down drops what waits");
        assertFalse(modem.deliver(ok, 0, ok.length), "link down");
        assertEquals(MODEM_MAC, modem.macAddress());
        assertThrows(IllegalArgumentException.class, () -> new Modem(1L << 48, new Catalogue(content)));
    }

    @Test
    void aTcpSegmentShorterThanItsHeaderIsDropped() {
        // A valid checksum (the first word makes the sum come out) on a segment that ends before
        // the data offset byte, the frame ending where the total length says
        for (int n = 2; n < 20; n++) {
            byte[] t = new byte[n];
            int sum = ones(t, 0, n, pseudo(PC_IP, MODEM_IP, Packets.PROTO_TCP, n));
            Packets.put16(t, 0, ~sum & 0xFFFF);
            byte[] f = ipv4(PC_MAC, MODEM_MAC, PC_IP, MODEM_IP, Packets.PROTO_TCP, t);
            assertEquals(0xFFFF, ones(f, 34, n, pseudo(PC_IP, MODEM_IP, Packets.PROTO_TCP, n)), "the checksum is good");
            assertEquals(List.of(), send(f), "a " + n + "-byte segment gets no answer");
        }
    }

    // ---- ARP ----

    @Test
    void arpRequestForTheModemGetsAReply() {
        List<byte[]> out = send(arpRequest(PC_MAC, PC_IP, MODEM_IP));
        assertEquals(1, out.size());
        byte[] r = out.get(0);
        assertEquals(42, r.length);
        assertEquals(PC_MAC, mac(r, 0));
        assertEquals(MODEM_MAC, mac(r, 6));
        assertEquals(0x0806, u16(r, 12));
        assertEquals(1, u16(r, 14), "hardware type ethernet");
        assertEquals(0x0800, u16(r, 16));
        assertEquals(6, r[18]);
        assertEquals(4, r[19]);
        assertEquals(2, u16(r, 20), "reply");
        assertEquals(MODEM_MAC, mac(r, 22));
        assertEquals(MODEM_IP, i32(r, 28));
        assertEquals(PC_MAC, mac(r, 32));
        assertEquals(PC_IP, i32(r, 38));
        // A DHCP client's probe for its new address (sender 0.0.0.0) is not ours to answer
        assertEquals(0, send(arpRequest(PC_MAC, 0, PC_IP)).size());
        byte[] reply = arpRequest(PC_MAC, PC_IP, MODEM_IP);
        Packets.put16(reply, 20, 2);
        assertEquals(0, send(reply).size(), "an ARP reply needs no answer");
    }

    // ---- ICMP ----

    @Test
    void pingGetsAnEchoReply() {
        byte[] icmp = new byte[8 + 56];
        icmp[0] = 8;
        Packets.put16(icmp, 4, 0x1234);
        Packets.put16(icmp, 6, 7);
        for (int i = 8; i < icmp.length; i++)
            icmp[i] = (byte) i;
        Packets.put16(icmp, 2, ~ones(icmp, 0, icmp.length, 0) & 0xFFFF);
        List<byte[]> out = send(ipv4(PC_MAC, MODEM_MAC, PC_IP, MODEM_IP, 1, icmp));
        assertEquals(1, out.size());
        byte[] r = out.get(0);
        assertChecksums(r);
        assertEquals(PC_MAC, mac(r, 0));
        assertEquals(MODEM_IP, ipSrc(r));
        assertEquals(PC_IP, ipDst(r));
        assertEquals(1, proto(r));
        assertEquals(0, r[34], "echo reply");
        assertArrayEquals(Arrays.copyOfRange(icmp, 4, icmp.length), Arrays.copyOfRange(r, 38, r.length), "id, sequence, data");
        icmp[10] ^= 1;
        assertEquals(0, send(ipv4(PC_MAC, MODEM_MAC, PC_IP, MODEM_IP, 1, icmp)).size(), "bad checksum");
        icmp[10] ^= 1;
        assertEquals(0, send(ipv4(PC_MAC, MODEM_MAC, PC_IP, 0x0808_0808, 1, icmp)).size(), "nothing leaves the modem");
        byte[] corrupt = ipv4(PC_MAC, MODEM_MAC, PC_IP, MODEM_IP, 1, icmp);
        corrupt[22] = 1; // TTL changed after the header checksum was made
        assertEquals(0, send(corrupt).size(), "bad IPv4 header checksum");
        byte[] fragment = ipv4(PC_MAC, MODEM_MAC, PC_IP, MODEM_IP, 1, icmp);
        Packets.put16(fragment, 20, 0x2000);
        Packets.put16(fragment, 24, 0);
        Packets.put16(fragment, 24, ~ones(fragment, 14, 20, 0) & 0xFFFF);
        assertEquals(0, send(fragment).size(), "fragments are not reassembled");
    }

    // ---- DHCP ----

    private byte[] dhcp(int type, int xid, boolean broadcast, int ciaddr, int requested, int server, int replies) {
        byte[] m = TestClient.dhcpMessage(type, xid, broadcast, ciaddr, requested, server);
        List<byte[]> out = send(udp(PC_MAC, Packets.BROADCAST_MAC, ciaddr, ciaddr == 0 ? -1 : MODEM_IP, 68, 67, m));
        assertEquals(replies, out.size());
        if (replies == 0)
            return null;
        byte[] r = out.get(0);
        assertChecksums(r);
        assertEquals(17, proto(r));
        assertEquals(67, u16(r, 34));
        assertEquals(68, u16(r, 36));
        assertEquals(MODEM_IP, ipSrc(r));
        return r;
    }

    private static void assertOption(byte[] m, int code, byte[] expected) {
        assertArrayEquals(expected, TestClient.dhcpOption(m, code), "option " + code);
    }

    private static byte[] be32(int v) {
        return new byte[] { (byte) (v >>> 24), (byte) (v >>> 16), (byte) (v >>> 8), (byte) v };
    }

    @Test
    void dhcpDiscoverOfferRequestAck() {
        byte[] f = dhcp(1, 0xCAFE_0001, false, 0, 0, 0, 1);
        assertEquals(PC_MAC, mac(f, 0), "unicast to the client's hardware address");
        assertEquals(PC_IP, ipDst(f), "at the offered address");
        byte[] m = udpData(f);
        assertEquals(300, m.length, "padded to a BOOTP message");
        assertEquals(2, m[0], "BOOTREPLY");
        assertEquals(0xCAFE_0001, i32(m, 4), "xid echoed");
        assertEquals(0, i32(m, 12), "ciaddr");
        assertEquals(PC_IP, i32(m, 16), "yiaddr 10.88.0.2");
        assertEquals(PC_MAC, mac(m, 28));
        assertEquals(0x6382_5363, i32(m, 236));
        assertOption(m, 53, new byte[] { 2 });
        assertOption(m, 54, be32(MODEM_IP));
        assertOption(m, 51, be32(86_400));
        assertOption(m, 1, be32(0xFFFF_FF00));
        assertOption(m, 3, be32(MODEM_IP));
        assertOption(m, 6, be32(MODEM_IP));

        f = dhcp(1, 2, true, 0, 0, 0, 1);
        assertEquals(Packets.BROADCAST_MAC, mac(f, 0), "the broadcast flag asks for a broadcast reply");
        assertEquals(-1, ipDst(f));

        assertEquals(-1, modem.leaseHolder());
        m = udpData(dhcp(3, 3, false, 0, PC_IP, MODEM_IP, 1));
        assertOption(m, 53, new byte[] { 5 });
        assertEquals(PC_IP, i32(m, 16));
        assertOption(m, 51, be32(86_400));
        assertEquals(PC_MAC, modem.leaseHolder());

        // Renewing: unicast from the leased address, ciaddr set, no option 50 or 54
        f = dhcp(3, 4, false, PC_IP, 0, 0, 1);
        assertEquals(PC_IP, ipDst(f));
        m = udpData(f);
        assertOption(m, 53, new byte[] { 5 });
        assertEquals(PC_IP, i32(m, 12), "ciaddr kept in the ACK");

        // Another address, or none, is refused with a broadcast NAK
        f = dhcp(3, 5, false, 0, 0x0A58_0009, 0, 1);
        assertEquals(Packets.BROADCAST_MAC, mac(f, 0));
        m = udpData(f);
        assertOption(m, 53, new byte[] { 6 });
        assertEquals(0, i32(m, 16), "no yiaddr in a NAK");
        assertEquals(null, TestClient.dhcpOption(m, 51));
        assertOption(udpData(dhcp(3, 6, false, 0, 0, 0, 1)), 53, new byte[] { 6 });
        f = dhcp(3, 10, false, 0x0A58_0009, 0, 0, 1);
        assertEquals(Packets.BROADCAST_MAC, mac(f, 0), "a renewal of a wrong address: NAK, broadcast");
        assertEquals(0, i32(udpData(f), 12), "ciaddr 0 in a NAK (RFC 2131 table 3)");

        dhcp(3, 7, false, 0, PC_IP, 0x0A00_0001, 0); // chose another server
        dhcp(7, 8, false, PC_IP, 0, MODEM_IP, 0);
        assertEquals(-1, modem.leaseHolder(), "RELEASE ends the lease, unanswered");
        dhcp(4, 9, false, 0, PC_IP, MODEM_IP, 0); // DECLINE: nothing to say
    }

    @Test
    void dhcpIgnoresMalformedMessages() {
        byte[] m = TestClient.dhcpMessage(1, 1, false, 0, 0, 0);
        Packets.put32(m, 236, 0);
        assertEquals(0, send(udp(PC_MAC, Packets.BROADCAST_MAC, 0, -1, 68, 67, m)).size(), "no magic cookie");
        m = TestClient.dhcpMessage(1, 1, false, 0, 0, 0);
        m[0] = 2;
        assertEquals(0, send(udp(PC_MAC, Packets.BROADCAST_MAC, 0, -1, 68, 67, m)).size(), "a reply, not a request");
        m = TestClient.dhcpMessage(1, 1, false, 0, 0, 0);
        m[241] = (byte) 200; // option length past the end
        assertEquals(0, send(udp(PC_MAC, Packets.BROADCAST_MAC, 0, -1, 68, 67, Arrays.copyOf(m, 245))).size());
        byte[] f = udp(PC_MAC, Packets.BROADCAST_MAC, 0, -1, 68, 67, TestClient.dhcpMessage(1, 1, false, 0, 0, 0));
        f[f.length - 1] ^= 0x55;
        assertEquals(0, send(f).size(), "bad UDP checksum");
        f = udp(PC_MAC, Packets.BROADCAST_MAC, 0, -1, 68, 67, TestClient.dhcpMessage(1, 1, false, 0, 0, 0));
        Packets.put16(f, 40, 0);
        assertEquals(1, send(f).size(), "a UDP checksum of 0 is not checked");
    }

    // ---- DNS ----

    private byte[] dns(byte[] query, int replies) {
        List<byte[]> out = send(udp(PC_MAC, MODEM_MAC, PC_IP, MODEM_IP, 5353, 53, query));
        assertEquals(replies, out.size());
        if (replies == 0)
            return null;
        byte[] f = out.get(0);
        assertChecksums(f);
        assertEquals(53, u16(f, 34));
        assertEquals(5353, u16(f, 36));
        assertEquals(PC_IP, ipDst(f));
        return udpData(f);
    }

    @Test
    void dnsAnswersCatalogueHostsAndNxdomainOtherwise() {
        byte[] q = TestClient.dnsQuery(0xBEEF, "get.flip", 1);
        byte[] r = dns(q, 1);
        assertEquals(0xBEEF, u16(r, 0));
        assertEquals(0x85, r[2] & 0xFF, "QR, AA, RD copied");
        assertEquals(0x80, r[3] & 0xFF, "RA, NOERROR");
        assertEquals(1, u16(r, 4));
        assertEquals(1, u16(r, 6));
        assertEquals(0, u16(r, 8));
        assertEquals(0, u16(r, 10));
        assertArrayEquals(Arrays.copyOfRange(q, 12, q.length), Arrays.copyOfRange(r, 12, q.length), "question copied");
        int a = q.length;
        assertEquals(0xC00C, u16(r, a), "name points at the question");
        assertEquals(1, u16(r, a + 2));
        assertEquals(1, u16(r, a + 4));
        assertEquals(3600, i32(r, a + 6));
        assertEquals(4, u16(r, a + 10));
        assertEquals(MODEM_IP, i32(r, a + 12));
        assertEquals(a + 16, r.length);

        r = dns(TestClient.dnsQuery(1, "DEB.Flip", 1), 1);
        assertEquals(1, u16(r, 6), "names are case-insensitive");

        r = dns(TestClient.dnsQuery(2, "get.flip", 28), 1);
        assertEquals(0x80, r[3] & 0xFF, "AAAA for a known host: NOERROR");
        assertEquals(0, u16(r, 6), "with no answer");

        for (String name : new String[] { "example.com", "nope.flip", "flip", "www.get.flip" }) {
            r = dns(TestClient.dnsQuery(3, name, 1), 1);
            assertEquals(0x83, r[3] & 0xFF, name + ": NXDOMAIN");
            assertEquals(0, u16(r, 6));
            assertEquals(1, u16(r, 4));
        }
    }

    @Test
    void dnsMalformedQueries() {
        byte[] two = TestClient.dnsQuery(4, "get.flip", 1);
        two[5] = 2;
        byte[] r = dns(two, 1);
        assertEquals(0x81, r[3] & 0xFF, "two questions: FORMERR");
        assertEquals(12, r.length);
        byte[] cut = Arrays.copyOf(TestClient.dnsQuery(5, "get.flip", 1), 20);
        assertEquals(0x81, dns(cut, 1)[3] & 0xFF, "question cut short");
        byte[] status = TestClient.dnsQuery(6, "get.flip", 1);
        status[2] = 0x10; // opcode 2, STATUS
        assertEquals(0x84, dns(status, 1)[3] & 0xFF, "NOTIMP");
        byte[] response = TestClient.dnsQuery(7, "get.flip", 1);
        response[2] = (byte) 0x80;
        dns(response, 0);
        dns(new byte[11], 0);
        // To another address, or the DNS port of a broadcast: not ours
        assertEquals(0, send(udp(PC_MAC, MODEM_MAC, PC_IP, 0x0808_0808, 5353, 53, TestClient.dnsQuery(8, "get.flip", 1))).size());
    }
}
