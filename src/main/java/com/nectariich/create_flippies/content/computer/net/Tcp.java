package com.nectariich.create_flippies.content.computer.net;

import static com.nectariich.create_flippies.content.computer.net.Packets.ETH_HEADER;
import static com.nectariich.create_flippies.content.computer.net.Packets.IP_HEADER;
import static com.nectariich.create_flippies.content.computer.net.Packets.PROTO_TCP;
import static com.nectariich.create_flippies.content.computer.net.Packets.fold;
import static com.nectariich.create_flippies.content.computer.net.Packets.i32;
import static com.nectariich.create_flippies.content.computer.net.Packets.mac;
import static com.nectariich.create_flippies.content.computer.net.Packets.pseudoSum;
import static com.nectariich.create_flippies.content.computer.net.Packets.put16;
import static com.nectariich.create_flippies.content.computer.net.Packets.put32;
import static com.nectariich.create_flippies.content.computer.net.Packets.sum;
import static com.nectariich.create_flippies.content.computer.net.Packets.u16;
import static com.nectariich.create_flippies.content.computer.net.Packets.u8;

import java.util.ArrayList;
import java.util.List;

/**
 * The modem's TCP (spec 12.4.2; RFC 9293): a passive open on port 80 only, at most
 * {@link #MAX_CONNECTIONS} connections, each an {@link HttpSession}. Segments to any other port,
 * or for no connection, get a RST. The MSS option and, when the client offers it, window scaling
 * (RFC 7323, our shift 0) are the only options; SACK and timestamps are not offered.
 */
final class Tcp {

    static final int HTTP_PORT = 80;
    /** Our MSS: 1500-byte IP packets on the card's 1514-byte frames (spec 12.4.2). */
    static final int MSS = 1460;
    static final int MAX_CONNECTIONS = 8;
    /** Ticks without an ACK before the unacknowledged data is sent again (spec 12.4.2). */
    static final int RTO_TICKS = 20;
    /** Retransmissions in a row without progress before the connection is given up (about 10 s). */
    static final int MAX_RETRIES = 10;
    /** Ticks without a segment from the peer before an idle connection is closed (one minute). */
    static final int IDLE_TICKS = 1200;
    /** Our receive window; HTTP takes bytes as they come, so it never shrinks. */
    static final int RCV_WINDOW = 65535;
    /** The MSS assumed when the peer sends none (RFC 9293 3.7.1). */
    static final int DEFAULT_MSS = 536;
    /** The largest window shift (RFC 7323 2.3). */
    private static final int MAX_SHIFT = 14;
    /** Header flags. */
    static final int FIN = 1, SYN = 2, RST = 4, PSH = 8, ACK = 16;
    private static final int OPT_END = 0, OPT_NOP = 1, OPT_MSS = 2, OPT_WSCALE = 3;

    /** A received segment: header fields and where its data sits in {@code frame}. */
    record Segment(long srcMac, int srcIp, int srcPort, int dstPort, int seq, int ack, int flags, int window,
            byte[] frame, int data, int length, int options, int optionsLength) {
        boolean has(int flag) {
            return (flags & flag) != 0;
        }
    }

    private final Modem modem;
    private final Catalogue catalogue;
    private final List<TcpConnection> connections = new ArrayList<>();
    /** The next initial sequence number; the first is close to wrapping so wrap-around is exercised early. */
    private int nextIss = 0xFFFF_F000;
    private int rotate;
    private long retransmissions;

    Tcp(Modem modem, Catalogue catalogue) {
        this.modem = modem;
        this.catalogue = catalogue;
    }

    int connectionCount() {
        return connections.size();
    }

    /** Retransmission timeouts so far (each sends the window again from the oldest unacknowledged byte). */
    long retransmissions() {
        return retransmissions;
    }

    void retransmitted() {
        retransmissions++;
    }

    boolean room() {
        return modem.room();
    }

    /** A TCP segment to us: the IPv4 header at {@code ETH_HEADER} of {@code f}, {@code ihl} long, {@code length} bytes of TCP after it, checksum already good. */
    void receive(byte[] f, int ihl, int length) {
        int at = ETH_HEADER + ihl;
        if (length < 20)
            return;
        int offset = (u8(f, at + 12) >>> 4) * 4;
        if (offset < 20 || offset > length)
            return;
        Segment s = new Segment(mac(f, 6), i32(f, 26), u16(f, at), u16(f, at + 2), i32(f, at + 4), i32(f, at + 8),
                u8(f, at + 13), u16(f, at + 14), f, at + offset, length - offset, at + 20, offset - 20);
        TcpConnection c = s.dstPort() == HTTP_PORT ? find(s.srcIp(), s.srcPort()) : null;
        if (c == null) {
            if (s.dstPort() == HTTP_PORT && s.has(SYN) && !s.has(ACK) && !s.has(RST))
                open(s);
            else
                refuse(s);
            return;
        }
        c.segment(s);
        c.checkFinished();
        if (!c.closed)
            c.pump();
        settle(c);
    }

    private TcpConnection find(int ip, int port) {
        for (TcpConnection c : connections)
            if (c.peerIp == ip && c.peerPort == port)
                return c;
        return null;
    }

    /** A SYN on port 80: a new connection, or nothing when the table is full (the client's SYN retries take it later, as a full listen queue does). */
    private void open(Segment s) {
        if (connections.size() >= MAX_CONNECTIONS)
            return;
        int mss = DEFAULT_MSS, shift = -1;
        byte[] f = s.frame();
        for (int i = s.options(), end = i + s.optionsLength(); i < end; ) {
            int kind = u8(f, i);
            if (kind == OPT_END)
                break;
            if (kind == OPT_NOP) {
                i++;
                continue;
            }
            if (i + 1 >= end || u8(f, i + 1) < 2 || i + u8(f, i + 1) > end)
                break;
            int len = u8(f, i + 1);
            if (kind == OPT_MSS && len == 4)
                mss = Math.max(1, u16(f, i + 2));
            else if (kind == OPT_WSCALE && len == 3)
                shift = Math.min(MAX_SHIFT, u8(f, i + 2));
            i += len;
        }
        int iss = nextIss;
        nextIss += 0x0102_0305;
        TcpConnection c = new TcpConnection(this, new HttpSession(catalogue), s.srcMac(), s.srcIp(), s.srcPort(), iss,
                s.seq(), s.window(), mss, shift);
        connections.add(c);
        sendSynAck(c);
    }

    /**
     * The answer to a segment no connection takes (RFC 9293 3.10.7.1): nothing for a RST; for an
     * ACK a RST at the acknowledged number; else a RST acknowledging the segment.
     */
    private void refuse(Segment s) {
        if (s.has(RST))
            return;
        if (s.has(ACK)) {
            modem.queue(segment(s.srcMac(), s.srcIp(), s.dstPort(), s.srcPort(), s.ack(), 0, RST, null, null, 0));
            return;
        }
        int seqLength = s.length() + (s.has(SYN) ? 1 : 0) + (s.has(FIN) ? 1 : 0);
        modem.queue(segment(s.srcMac(), s.srcIp(), s.dstPort(), s.srcPort(), 0, s.seq() + seqLength, RST | ACK, null, null, 0));
    }

    // ---- sending ----

    void sendSynAck(TcpConnection c) {
        byte[] options = c.windowScaling
                ? new byte[] { OPT_MSS, 4, (byte) (MSS >>> 8), (byte) MSS, OPT_NOP, OPT_WSCALE, 3, 0 }
                : new byte[] { OPT_MSS, 4, (byte) (MSS >>> 8), (byte) MSS };
        modem.queue(segment(c.peerMac, c.peerIp, HTTP_PORT, c.peerPort, c.iss, c.rcvNxt, SYN | ACK, options, null, 0));
        c.synSent();
    }

    /** A data, FIN or bare ACK segment of {@code c}; data goes only when {@link #room} said so. */
    void send(TcpConnection c, int seq, int flags, byte[] data, int length) {
        modem.queue(segment(c.peerMac, c.peerIp, HTTP_PORT, c.peerPort, seq, c.rcvNxt, flags, null, data, length));
    }

    void sendAck(TcpConnection c) {
        send(c, c.seq(c.sndNxt), ACK, null, 0);
        c.ackDue = false;
    }

    void sendReset(TcpConnection c, int seq) {
        modem.queue(segment(c.peerMac, c.peerIp, HTTP_PORT, c.peerPort, seq, 0, RST, null, null, 0));
    }

    /** Drops the frames still queued for {@code c}, after a reset: they could only draw more resets. */
    void purge(TcpConnection c) {
        modem.unqueue(f -> u8(f, 23) == PROTO_TCP && i32(f, 30) == c.peerIp && u16(f, ETH_HEADER + IP_HEADER + 2) == c.peerPort);
    }

    /** A whole frame: Ethernet, IPv4, TCP with {@code options} (null for none), then {@code length} bytes of {@code data}. */
    private byte[] segment(long dstMac, int dstIp, int srcPort, int dstPort, int seq, int ack, int flags,
            byte[] options, byte[] data, int length) {
        int header = 20 + (options == null ? 0 : options.length);
        byte[] f = Packets.ipv4Frame(dstMac, modem.macAddress(), Modem.IP, dstIp, PROTO_TCP, modem.nextIpId(), header + length);
        int at = ETH_HEADER + IP_HEADER;
        put16(f, at, srcPort);
        put16(f, at + 2, dstPort);
        put32(f, at + 4, seq);
        put32(f, at + 8, ack);
        f[at + 12] = (byte) (header / 4 << 4);
        f[at + 13] = (byte) flags;
        put16(f, at + 14, RCV_WINDOW);
        if (options != null)
            System.arraycopy(options, 0, f, at + 20, options.length);
        if (length > 0)
            System.arraycopy(data, 0, f, at + header, length);
        int sum = pseudoSum(Modem.IP, dstIp, PROTO_TCP, header + length) + sum(f, at, header + length, 0);
        put16(f, at + 16, ~fold(sum) & 0xFFFF);
        return f;
    }

    // ---- ticks ----

    /** Timers of every connection, then sending. {@code backlog}: frames refused by the peer's card wait in the modem. */
    void tick(boolean backlog) {
        for (TcpConnection c : connections)
            c.tick(backlog);
        pump();
    }

    /** Lets every connection send, starting one further along each time so none is starved. */
    void pump() {
        int n = connections.size();
        for (int k = 0; k < n; k++) {
            TcpConnection c = connections.get((rotate + k) % n);
            c.checkFinished();
            c.pump();
        }
        rotate = n == 0 ? 0 : (rotate + 1) % n;
        for (int k = connections.size() - 1; k >= 0; k--)
            settle(connections.get(k));
    }

    /** Sends an owed ACK, and drops the connection once it is finished. */
    private void settle(TcpConnection c) {
        if (c.ackDue)
            sendAck(c);
        if (c.closed) {
            c.http.close();
            connections.remove(c);
        }
    }

    /** The link went down: every connection is forgotten without a word (spec 12.4.5). */
    void dropAll() {
        for (TcpConnection c : connections)
            c.http.close();
        connections.clear();
    }
}
