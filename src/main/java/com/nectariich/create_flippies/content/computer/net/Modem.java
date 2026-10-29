package com.nectariich.create_flippies.content.computer.net;

import static com.nectariich.create_flippies.content.computer.net.Packets.BROADCAST_MAC;
import static com.nectariich.create_flippies.content.computer.net.Packets.ETH_HEADER;
import static com.nectariich.create_flippies.content.computer.net.Packets.IP_HEADER;
import static com.nectariich.create_flippies.content.computer.net.Packets.PROTO_ICMP;
import static com.nectariich.create_flippies.content.computer.net.Packets.PROTO_TCP;
import static com.nectariich.create_flippies.content.computer.net.Packets.PROTO_UDP;
import static com.nectariich.create_flippies.content.computer.net.Packets.TYPE_ARP;
import static com.nectariich.create_flippies.content.computer.net.Packets.TYPE_IPV4;
import static com.nectariich.create_flippies.content.computer.net.Packets.UDP_HEADER;
import static com.nectariich.create_flippies.content.computer.net.Packets.fold;
import static com.nectariich.create_flippies.content.computer.net.Packets.i32;
import static com.nectariich.create_flippies.content.computer.net.Packets.mac;
import static com.nectariich.create_flippies.content.computer.net.Packets.pseudoSum;
import static com.nectariich.create_flippies.content.computer.net.Packets.put16;
import static com.nectariich.create_flippies.content.computer.net.Packets.put32;
import static com.nectariich.create_flippies.content.computer.net.Packets.putMac;
import static com.nectariich.create_flippies.content.computer.net.Packets.sum;
import static com.nectariich.create_flippies.content.computer.net.Packets.u16;
import static com.nectariich.create_flippies.content.computer.net.Packets.u8;

import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Objects;
import java.util.function.Predicate;

/**
 * The network modem (spec 12.4): a small IPv4 host at {@link #IP} that is the PC's whole
 * "internet". It answers ARP and ping, hands out {@link #CLIENT_IP} by DHCP, resolves the
 * catalogue's hosts by DNS, and serves their files over HTTP on TCP port 80. Nothing leaves it.
 *
 * Frames from the PC are handled as they are delivered, so replies are queued at once; the
 * timers (TCP retransmission, idle connections) run in {@link #tick}, once per server tick after
 * frames move. On a PC-to-modem link the host calls {@link #exchange} then {@link #tick}:
 * frames the PC's card refuses (its receive queue full) stay queued here for the next tick, and
 * the TCP timers wait while they do, so the link loses nothing. {@code PcLinkExchange.ethernet}
 * works too, but a refused frame is then lost and TCP sends it again after
 * {@link Tcp#RTO_TICKS} ticks.
 *
 * Not thread-safe: every call on the thread that runs the exchange, between ticks. Keeps no state
 * worth saving (spec 12.4.5).
 */
public final class Modem implements FrameEndpoint {

    /** 10.88.0.1/24, the modem; 10.88.0.2, the one address its DHCP server hands out (spec 12.4.1). */
    public static final int IP = 0x0A58_0001, CLIENT_IP = 0x0A58_0002, NETMASK = 0xFFFF_FF00;
    private static final int SUBNET_BROADCAST = IP | ~NETMASK;
    /** Frames of TCP data the modem queues for the PC at most; as many as the PC's card holds (12.3). */
    public static final int OUT_FRAMES = 256;
    /** Beyond {@link #OUT_FRAMES}, room for replies that carry no data (ACKs, ARP, DNS); more are dropped. */
    private static final int OUT_LIMIT = OUT_FRAMES + 64;
    /** Exchanges in a row in which the PC's card took nothing before what waits is dropped (its receiver may be off). */
    public static final int HOLD_TICKS = 20;
    private static final int MIN_FRAME = 14, MAX_FRAME = 1514;
    private static final int ARP_LENGTH = 28, ICMP_ECHO_REPLY = 0, ICMP_ECHO_REQUEST = 8;

    private final long mac;
    private final Catalogue catalogue;
    private final ArrayDeque<byte[]> out = new ArrayDeque<>();
    private final DhcpServer dhcp = new DhcpServer();
    private final Tcp tcp;

    private boolean link;
    private boolean backlog;
    private int stalled;
    private int ipId;
    private long framesIn, framesOut, framesDropped;

    /** {@code mac} is 48 bits, byte 0 (the first on the wire) in bits 47..40. */
    public Modem(long mac, Catalogue catalogue) {
        if (mac < 0 || mac >>> 48 != 0)
            throw new IllegalArgumentException("MAC address must fit 48 bits: " + Long.toHexString(mac));
        this.mac = mac;
        this.catalogue = Objects.requireNonNull(catalogue, "catalogue");
        this.tcp = new Tcp(this, catalogue);
    }

    public long macAddress() {
        return mac;
    }

    public boolean link() {
        return link;
    }

    /** Frames taken in and sent out since creation; the block's activity light watches these. */
    public long framesIn() {
        return framesIn;
    }

    public long framesOut() {
        return framesOut;
    }

    /** Frames dropped here: the queue was full, or the PC's card took nothing for {@link #HOLD_TICKS} exchanges. */
    public long framesDropped() {
        return framesDropped;
    }

    /** Frames waiting for the PC. */
    public int outgoingCount() {
        return out.size();
    }

    public int connectionCount() {
        return tcp.connectionCount();
    }

    /** TCP retransmission timeouts so far. */
    public long retransmissions() {
        return tcp.retransmissions();
    }

    /** The MAC holding the DHCP lease, or -1. */
    public long leaseHolder() {
        return dhcp.leaseHolder();
    }

    // ---- FrameEndpoint ----

    /** Link down drops whatever waits and every TCP connection (the PC went away). */
    @Override
    public void setLink(boolean up) {
        if (up == link)
            return;
        link = up;
        if (!up) {
            framesDropped += out.size();
            out.clear();
            backlog = false;
            stalled = 0;
            tcp.dropAll();
        }
    }

    @Override
    public byte[] takeOutgoing() {
        byte[] f = out.poll();
        if (f != null)
            framesOut++;
        return f;
    }

    /** Handles the frame at once. False when the link is down, the length is out of range, or it is not for us (our MAC or broadcast). */
    @Override
    public boolean deliver(byte[] frame, int offset, int length) {
        Objects.checkFromIndexSize(offset, length, frame.length);
        if (!link || length < MIN_FRAME || length > MAX_FRAME)
            return false;
        long dst = mac(frame, offset);
        if (dst != mac && dst != BROADCAST_MAC)
            return false;
        framesIn++;
        byte[] f = Arrays.copyOfRange(frame, offset, offset + length);
        switch (u16(f, 12)) {
            case TYPE_ARP -> arp(f);
            case TYPE_IPV4 -> ipv4(f);
            default -> { }
        }
        return true;
    }

    // ---- the host's side ----

    /**
     * One tick's frames on a PC-to-modem link, both ways: the PC's into the modem, then the
     * modem's to the PC until its card has no room or refuses one, which waits for the next tick
     * with all after it. Asking first keeps the card from counting a held frame as dropped. Then
     * call {@link #tick}.
     */
    public void exchange(FrameEndpoint pc) {
        setLink(true);
        pc.setLink(true);
        for (byte[] f; (f = pc.takeOutgoing()) != null; )
            deliver(f, 0, f.length);
        int moved = 0;
        while (!out.isEmpty()) {
            byte[] f = out.peek();
            if (!pc.canReceive() || !pc.deliver(f, 0, f.length))
                break;
            out.poll();
            framesOut++;
            moved++;
        }
        backlog = !out.isEmpty();
        if (backlog && moved == 0 && ++stalled >= HOLD_TICKS) {
            // The card has taken nothing for a second: its receiver is off or the guest stopped
            // reading. Waiting longer only delays; TCP sends again what matters
            framesDropped += out.size();
            out.clear();
            backlog = false;
        }
        if (!backlog || moved > 0)
            stalled = 0;
    }

    /** Timers, then sending what TCP may: once per server tick, after frames moved. */
    public void tick() {
        if (!link)
            return;
        tcp.tick(backlog);
    }

    // ---- ARP (RFC 826) ----

    private void arp(byte[] f) {
        int a = ETH_HEADER;
        if (f.length < a + ARP_LENGTH || u16(f, a) != 1 || u16(f, a + 2) != TYPE_IPV4 || u8(f, a + 4) != 6
                || u8(f, a + 5) != 4 || u16(f, a + 6) != 1 || i32(f, a + 24) != IP)
            return;
        long senderMac = mac(f, a + 8);
        int senderIp = i32(f, a + 14);
        byte[] r = new byte[ETH_HEADER + ARP_LENGTH];
        putMac(r, 0, senderMac);
        putMac(r, 6, mac);
        put16(r, 12, TYPE_ARP);
        System.arraycopy(f, a, r, a, 6);
        put16(r, a + 6, 2);
        putMac(r, a + 8, mac);
        put32(r, a + 14, IP);
        putMac(r, a + 18, senderMac);
        put32(r, a + 24, senderIp);
        queue(r);
    }

    // ---- IPv4 ----

    /** Checks the header (version, lengths, checksum, not a fragment) and hands the payload on; anything wrong is dropped. */
    private void ipv4(byte[] f) {
        if (f.length < ETH_HEADER + IP_HEADER || (u8(f, 14) & 0xF0) != 0x40)
            return;
        int ihl = (u8(f, 14) & 0x0F) * 4, total = u16(f, 16);
        if (ihl < IP_HEADER || total < ihl || ETH_HEADER + total > f.length || fold(sum(f, ETH_HEADER, ihl, 0)) != 0xFFFF)
            return;
        // No reassembly: fragments are dropped (MF set or an offset)
        if ((u16(f, 20) & 0x3FFF) != 0)
            return;
        int src = i32(f, 26), dst = i32(f, 30), length = total - ihl, at = ETH_HEADER + ihl;
        boolean ours = dst == IP, broadcast = dst == Packets.BROADCAST_IP || dst == SUBNET_BROADCAST;
        switch (u8(f, 23)) {
            case PROTO_ICMP -> {
                if (ours)
                    icmp(f, src, at, length);
            }
            case PROTO_UDP -> {
                if (ours || broadcast)
                    udp(f, src, dst, at, length);
            }
            case PROTO_TCP -> {
                if (ours && fold(pseudoSum(src, dst, PROTO_TCP, length) + sum(f, at, length, 0)) == 0xFFFF)
                    tcp.receive(f, ihl, length);
            }
            default -> { }
        }
    }

    /** Echo request to echo reply, same identifier, sequence and data (RFC 792). */
    private void icmp(byte[] f, int src, int at, int length) {
        if (length < 8 || u8(f, at) != ICMP_ECHO_REQUEST || u8(f, at + 1) != 0 || fold(sum(f, at, length, 0)) != 0xFFFF)
            return;
        byte[] r = Packets.ipv4Frame(mac(f, 6), mac, IP, src, PROTO_ICMP, nextIpId(), length);
        int o = ETH_HEADER + IP_HEADER;
        System.arraycopy(f, at, r, o, length);
        r[o] = ICMP_ECHO_REPLY;
        put16(r, o + 2, 0);
        put16(r, o + 2, Packets.checksum(r, o, length));
        queue(r);
    }

    private void udp(byte[] f, int src, int dst, int at, int length) {
        if (length < UDP_HEADER)
            return;
        int udpLength = u16(f, at + 4);
        if (udpLength < UDP_HEADER || udpLength > length)
            return;
        // A checksum of 0 means the sender computed none (RFC 768)
        if (u16(f, at + 6) != 0 && fold(pseudoSum(src, dst, PROTO_UDP, udpLength) + sum(f, at, udpLength, 0)) != 0xFFFF)
            return;
        int srcPort = u16(f, at), dstPort = u16(f, at + 2), data = at + UDP_HEADER, dataLength = udpLength - UDP_HEADER;
        if (dstPort == DhcpServer.SERVER_PORT && srcPort == DhcpServer.CLIENT_PORT) {
            DhcpServer.Reply r = dhcp.handle(f, data, dataLength);
            if (r != null)
                sendUdp(r.dstMac(), r.dstIp(), DhcpServer.SERVER_PORT, DhcpServer.CLIENT_PORT, r.payload());
        } else if (dstPort == DnsServer.PORT && dst == IP) {
            byte[] r = DnsServer.answer(f, data, dataLength, catalogue);
            if (r != null)
                sendUdp(mac(f, 6), src, DnsServer.PORT, srcPort, r);
        }
    }

    private void sendUdp(long dstMac, int dstIp, int srcPort, int dstPort, byte[] payload) {
        int length = UDP_HEADER + payload.length;
        byte[] r = Packets.ipv4Frame(dstMac, mac, IP, dstIp, PROTO_UDP, nextIpId(), length);
        int o = ETH_HEADER + IP_HEADER;
        put16(r, o, srcPort);
        put16(r, o + 2, dstPort);
        put16(r, o + 4, length);
        System.arraycopy(payload, 0, r, o + UDP_HEADER, payload.length);
        int c = ~fold(pseudoSum(IP, dstIp, PROTO_UDP, length) + sum(r, o, length, 0)) & 0xFFFF;
        // A computed 0 is sent as all ones, since 0 means "no checksum" (RFC 768)
        put16(r, o + 6, c == 0 ? 0xFFFF : c);
        queue(r);
    }

    // ---- for Tcp ----

    /** Room for another frame of TCP data. */
    boolean room() {
        return out.size() < OUT_FRAMES;
    }

    /** Queues a frame for the PC; dropped (and counted) past {@link #OUT_LIMIT}. */
    void queue(byte[] f) {
        if (out.size() >= OUT_LIMIT) {
            framesDropped++;
            return;
        }
        out.add(f);
    }

    /** Drops the queued frames that {@code which} picks. */
    void unqueue(Predicate<byte[]> which) {
        out.removeIf(which);
    }

    int nextIpId() {
        return ipId++ & 0xFFFF;
    }
}
