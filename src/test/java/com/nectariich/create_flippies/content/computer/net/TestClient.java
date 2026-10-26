package com.nectariich.create_flippies.content.computer.net;

import static com.nectariich.create_flippies.content.computer.net.NetFrames.*;
import static com.nectariich.create_flippies.content.computer.net.Packets.ETH_HEADER;
import static com.nectariich.create_flippies.content.computer.net.Packets.IP_HEADER;
import static com.nectariich.create_flippies.content.computer.net.Packets.PROTO_TCP;
import static com.nectariich.create_flippies.content.computer.net.Packets.PROTO_UDP;
import static com.nectariich.create_flippies.content.computer.net.Packets.put16;
import static com.nectariich.create_flippies.content.computer.net.Packets.put32;
import static com.nectariich.create_flippies.content.computer.net.Packets.putMac;
import static com.nectariich.create_flippies.content.computer.net.Packets.u16;
import static com.nectariich.create_flippies.content.computer.net.Packets.u8;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * A tiny scripted client stack, as the netinstall floppy will have (spec 12.5): DHCP and DNS
 * messages, one TCP connection with in-order receive and a fixed window, and an HTTP response
 * reader. Frames it sends collect in {@link #outbox}; frames for it go to {@link #receive}.
 */
final class TestClient {

    static final int FIN = 1, SYN = 2, RST = 4, PSH = 8, ACK = 16;

    final List<byte[]> outbox = new ArrayList<>();
    /** Every frame received, in order. */
    final List<byte[]> inbox = new ArrayList<>();

    // DHCP and DNS replies, newest last
    final List<byte[]> dhcpReplies = new ArrayList<>();
    final List<byte[]> dnsReplies = new ArrayList<>();

    // TCP
    int port = 40000;
    int window = 65535;
    /** The window shift we offer in our SYN, or -1 for none. */
    int windowShift = -1;
    /** The MSS we offer. */
    int mss = 1460;
    int iss = 0x1000;
    int sndNxt;
    int rcvNxt;
    boolean synAckSeen, established, finReceived, resetReceived;
    int peerMss = -1, peerShift = -1;
    final ByteArrayOutputStream stream = new ByteArrayOutputStream();
    private boolean ackOwed;
    int segmentsIn, outOfOrder;

    // ---- DHCP ----

    static byte[] dhcpMessage(int type, int xid, boolean broadcast, int ciaddr, int requested, int server) {
        byte[] m = new byte[300];
        m[0] = 1;
        m[1] = 1;
        m[2] = 6;
        put32(m, 4, xid);
        put16(m, 10, broadcast ? 0x8000 : 0);
        put32(m, 12, ciaddr);
        putMac(m, 28, PC_MAC);
        put32(m, 236, 0x6382_5363);
        int o = 240;
        m[o++] = 53;
        m[o++] = 1;
        m[o++] = (byte) type;
        if (requested != 0) {
            m[o++] = 50;
            m[o++] = 4;
            put32(m, o, requested);
            o += 4;
        }
        if (server != 0) {
            m[o++] = 54;
            m[o++] = 4;
            put32(m, o, server);
            o += 4;
        }
        m[o++] = 55; // parameter request list, ignored by the modem
        m[o++] = 3;
        m[o++] = 1;
        m[o++] = 3;
        m[o++] = 6;
        m[o] = (byte) 255;
        return m;
    }

    void dhcp(int type, int xid, boolean broadcast, int ciaddr, int requested, int server) {
        outbox.add(udp(PC_MAC, Packets.BROADCAST_MAC, ciaddr, ciaddr == 0 ? -1 : MODEM_IP, 68, 67,
                dhcpMessage(type, xid, broadcast, ciaddr, requested, server)));
    }

    /** Option {@code code} of a DHCP reply as bytes, or null. */
    static byte[] dhcpOption(byte[] m, int code) {
        for (int i = 240; i < m.length && u8(m, i) != 255; ) {
            if (u8(m, i) == 0) {
                i++;
                continue;
            }
            int len = u8(m, i + 1);
            if (u8(m, i) == code)
                return java.util.Arrays.copyOfRange(m, i + 2, i + 2 + len);
            i += 2 + len;
        }
        return null;
    }

    // ---- DNS ----

    static byte[] dnsQuery(int id, String name, int qtype) {
        ByteArrayOutputStream q = new ByteArrayOutputStream();
        q.write(id >>> 8);
        q.write(id);
        q.write(0x01); // RD
        q.write(0);
        q.writeBytes(new byte[] { 0, 1, 0, 0, 0, 0, 0, 0 });
        for (String label : name.split("\\.")) {
            q.write(label.length());
            q.writeBytes(label.getBytes(StandardCharsets.US_ASCII));
        }
        q.write(0);
        q.write(qtype >>> 8);
        q.write(qtype);
        q.write(0);
        q.write(1);
        return q.toByteArray();
    }

    void dns(int id, String name, int qtype) {
        outbox.add(udp(PC_MAC, MODEM_MAC, PC_IP, MODEM_IP, 5353, 53, dnsQuery(id, name, qtype)));
    }

    // ---- TCP ----

    /** Sends a SYN with our MSS and, when {@link #windowShift} is set, window scaling. */
    void connect() {
        byte[] options = windowShift < 0 ? new byte[] { 2, 4, (byte) (mss >>> 8), (byte) mss }
                : new byte[] { 2, 4, (byte) (mss >>> 8), (byte) mss, 1, 3, 3, (byte) windowShift };
        outbox.add(tcp(port, 80, iss, 0, SYN, Math.min(window, 65535), options, new byte[0]));
        sndNxt = iss + 1;
    }

    void send(byte[] data) {
        outbox.add(tcp(port, 80, sndNxt, rcvNxt, ACK | PSH, advertised(), new byte[0], data));
        sndNxt += data.length;
        ackOwed = false;
    }

    void send(String text) {
        send(text.getBytes(StandardCharsets.ISO_8859_1));
    }

    void fin() {
        outbox.add(tcp(port, 80, sndNxt, rcvNxt, ACK | FIN, advertised(), new byte[0], new byte[0]));
        sndNxt++;
        ackOwed = false;
    }

    void reset() {
        outbox.add(tcp(port, 80, sndNxt, 0, RST, 0, new byte[0], new byte[0]));
    }

    void ack() {
        outbox.add(tcp(port, 80, sndNxt, rcvNxt, ACK, advertised(), new byte[0], new byte[0]));
        ackOwed = false;
    }

    /** One ACK for everything received since the last one, if anything was. */
    void flushAck() {
        if (ackOwed)
            ack();
    }

    private int advertised() {
        return windowShift < 0 || !synAckSeen || peerShift < 0 ? Math.min(window, 65535) : window >> windowShift;
    }

    // ---- receive ----

    void receive(byte[] f) {
        inbox.add(f);
        int proto = proto(f);
        if (proto == PROTO_UDP) {
            int at = ETH_HEADER + IP_HEADER, srcPort = u16(f, at);
            if (srcPort == 67)
                dhcpReplies.add(udpData(f));
            else if (srcPort == 53)
                dnsReplies.add(udpData(f));
        } else if (proto == PROTO_TCP && tcpDstPort(f) == port) {
            tcpSegment(f);
        }
    }

    private void tcpSegment(byte[] f) {
        segmentsIn++;
        int flags = tcpFlags(f);
        if ((flags & RST) != 0) {
            resetReceived = true;
            return;
        }
        if ((flags & SYN) != 0) {
            if ((flags & ACK) == 0 || tcpAck(f) != iss + 1)
                return;
            synAckSeen = true;
            rcvNxt = tcpSeq(f) + 1;
            readOptions(f);
            if (peerShift < 0)
                windowShift = -1;
            established = true;
            ack();
            return;
        }
        byte[] data = tcpData(f);
        int seq = tcpSeq(f);
        if (data.length > 0 || (flags & FIN) != 0) {
            ackOwed = true;
            if (seq != rcvNxt) {
                outOfOrder++;
                return;
            }
            stream.writeBytes(data);
            rcvNxt += data.length;
            if ((flags & FIN) != 0 && !finReceived) {
                finReceived = true;
                rcvNxt++;
            }
        }
    }

    private void readOptions(byte[] f) {
        int at = ETH_HEADER + IP_HEADER, end = at + tcpHeaderLength(f);
        for (int i = at + 20; i < end; ) {
            int kind = u8(f, i);
            if (kind == 0)
                break;
            if (kind == 1) {
                i++;
                continue;
            }
            if (kind == 2)
                peerMss = u16(f, i + 2);
            if (kind == 3)
                peerShift = u8(f, i + 2);
            i += u8(f, i + 1);
        }
    }

    // ---- HTTP ----

    /** One HTTP response read from the stream: status, headers (lower-case names), body. */
    record HttpReply(int status, Map<String, String> headers, byte[] body, int end) {
        String header(String name) {
            return headers.get(name);
        }
    }

    /** The response starting at {@code from} of {@link #stream}, or null if it has not all arrived; {@code head} for a HEAD request (no body). */
    HttpReply response(int from, boolean head) {
        byte[] s = stream.toByteArray();
        int end = -1;
        for (int i = from; i + 3 < s.length; i++)
            if (s[i] == '\r' && s[i + 1] == '\n' && s[i + 2] == '\r' && s[i + 3] == '\n') {
                end = i + 4;
                break;
            }
        if (end < 0)
            return null;
        String[] lines = new String(s, from, end - from, StandardCharsets.ISO_8859_1).split("\r\n");
        int status = Integer.parseInt(lines[0].split(" ")[1]);
        Map<String, String> headers = new LinkedHashMap<>();
        for (int i = 1; i < lines.length; i++) {
            int c = lines[i].indexOf(':');
            headers.put(lines[i].substring(0, c).toLowerCase(Locale.ROOT), lines[i].substring(c + 1).trim());
        }
        int length = head ? 0 : Integer.parseInt(headers.getOrDefault("content-length", "0"));
        if (s.length < end + length)
            return null;
        return new HttpReply(status, headers, java.util.Arrays.copyOfRange(s, end, end + length), end + length);
    }
}
