package com.nectariich.create_flippies.content.computer.net;

import static com.nectariich.create_flippies.content.computer.net.Packets.ETH_HEADER;
import static com.nectariich.create_flippies.content.computer.net.Packets.IP_HEADER;
import static com.nectariich.create_flippies.content.computer.net.Packets.PROTO_TCP;
import static com.nectariich.create_flippies.content.computer.net.Packets.PROTO_UDP;
import static com.nectariich.create_flippies.content.computer.net.Packets.TYPE_ARP;
import static com.nectariich.create_flippies.content.computer.net.Packets.TYPE_IPV4;
import static com.nectariich.create_flippies.content.computer.net.Packets.i32;
import static com.nectariich.create_flippies.content.computer.net.Packets.put16;
import static com.nectariich.create_flippies.content.computer.net.Packets.put32;
import static com.nectariich.create_flippies.content.computer.net.Packets.putMac;
import static com.nectariich.create_flippies.content.computer.net.Packets.u16;
import static com.nectariich.create_flippies.content.computer.net.Packets.u8;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Arrays;

/**
 * Frames as a client sends them and readers for the modem's replies, written separately from the
 * modem's own code so the tests check it rather than repeat it. The checksums here are a plain
 * RFC 1071 sum, recomputed from scratch.
 */
final class NetFrames {

    private NetFrames() {
    }

    static final long PC_MAC = 0x02_11_22_33_44_55L, MODEM_MAC = 0x02_4D_4F_44_45_4DL;
    static final int MODEM_IP = 0x0A58_0001, PC_IP = 0x0A58_0002;
    static final int TCP_DATA = ETH_HEADER + IP_HEADER + 20;

    /** The RFC 1071 sum of {@code length} bytes from {@code at}, not complemented, plus {@code acc}. */
    static int ones(byte[] b, int at, int length, long acc) {
        for (int i = 0; i < length; i += 2)
            acc += (b[at + i] & 0xFF) << 8 | (i + 1 < length ? b[at + i + 1] & 0xFF : 0);
        while (acc >>> 16 != 0)
            acc = (acc & 0xFFFF) + (acc >>> 16);
        return (int) acc;
    }

    static long pseudo(int src, int dst, int proto, int length) {
        return (src >>> 16) + (src & 0xFFFFL) + (dst >>> 16) + (dst & 0xFFFFL) + proto + length;
    }

    /** An IPv4 frame (no options, ID 0, TTL 64) carrying {@code payload}, header checksum set. */
    static byte[] ipv4(long srcMac, long dstMac, int src, int dst, int proto, byte[] payload) {
        byte[] f = new byte[ETH_HEADER + IP_HEADER + payload.length];
        putMac(f, 0, dstMac);
        putMac(f, 6, srcMac);
        put16(f, 12, TYPE_IPV4);
        f[14] = 0x45;
        put16(f, 16, IP_HEADER + payload.length);
        f[22] = 64;
        f[23] = (byte) proto;
        put32(f, 26, src);
        put32(f, 30, dst);
        put16(f, 24, ~ones(f, 14, 20, 0) & 0xFFFF);
        System.arraycopy(payload, 0, f, ETH_HEADER + IP_HEADER, payload.length);
        return f;
    }

    static byte[] udp(long srcMac, long dstMac, int src, int dst, int srcPort, int dstPort, byte[] data) {
        byte[] u = new byte[8 + data.length];
        put16(u, 0, srcPort);
        put16(u, 2, dstPort);
        put16(u, 4, u.length);
        System.arraycopy(data, 0, u, 8, data.length);
        int c = ~ones(u, 0, u.length, pseudo(src, dst, PROTO_UDP, u.length)) & 0xFFFF;
        put16(u, 6, c == 0 ? 0xFFFF : c);
        return ipv4(srcMac, dstMac, src, dst, PROTO_UDP, u);
    }

    /** A TCP segment from the PC to the modem's port 80 unless {@code dstPort} says otherwise. */
    static byte[] tcp(int srcPort, int dstPort, int seq, int ack, int flags, int window, byte[] options, byte[] data) {
        int header = 20 + options.length;
        byte[] t = new byte[header + data.length];
        put16(t, 0, srcPort);
        put16(t, 2, dstPort);
        put32(t, 4, seq);
        put32(t, 8, ack);
        t[12] = (byte) (header / 4 << 4);
        t[13] = (byte) flags;
        put16(t, 14, window);
        System.arraycopy(options, 0, t, 20, options.length);
        System.arraycopy(data, 0, t, header, data.length);
        put16(t, 16, ~ones(t, 0, t.length, pseudo(PC_IP, MODEM_IP, PROTO_TCP, t.length)) & 0xFFFF);
        return ipv4(PC_MAC, MODEM_MAC, PC_IP, MODEM_IP, PROTO_TCP, t);
    }

    static byte[] arpRequest(long senderMac, int senderIp, int targetIp) {
        byte[] f = new byte[42];
        putMac(f, 0, Packets.BROADCAST_MAC);
        putMac(f, 6, senderMac);
        put16(f, 12, TYPE_ARP);
        put16(f, 14, 1);
        put16(f, 16, TYPE_IPV4);
        f[18] = 6;
        f[19] = 4;
        put16(f, 20, 1);
        putMac(f, 22, senderMac);
        put32(f, 28, senderIp);
        put32(f, 38, targetIp);
        return f;
    }

    // ---- readers ----

    static int proto(byte[] f) {
        return u16(f, 12) == TYPE_IPV4 ? u8(f, 23) : -1;
    }

    static int ipSrc(byte[] f) {
        return i32(f, 26);
    }

    static int ipDst(byte[] f) {
        return i32(f, 30);
    }

    /** The UDP payload of a frame from the modem. */
    static byte[] udpData(byte[] f) {
        int at = ETH_HEADER + IP_HEADER;
        return Arrays.copyOfRange(f, at + 8, at + u16(f, at + 4));
    }

    static int tcpFlags(byte[] f) {
        return u8(f, ETH_HEADER + IP_HEADER + 13);
    }

    static int tcpSeq(byte[] f) {
        return i32(f, ETH_HEADER + IP_HEADER + 4);
    }

    static int tcpAck(byte[] f) {
        return i32(f, ETH_HEADER + IP_HEADER + 8);
    }

    static int tcpDstPort(byte[] f) {
        return u16(f, ETH_HEADER + IP_HEADER + 2);
    }

    static int tcpHeaderLength(byte[] f) {
        return (u8(f, ETH_HEADER + IP_HEADER + 12) >>> 4) * 4;
    }

    static byte[] tcpData(byte[] f) {
        int at = ETH_HEADER + IP_HEADER + tcpHeaderLength(f);
        return Arrays.copyOfRange(f, at, ETH_HEADER + u16(f, 16));
    }

    /** Fails unless the modem's frame has a good IPv4 header checksum and a good TCP or UDP checksum. */
    static void assertChecksums(byte[] f) {
        assertEquals(0xFFFF, ones(f, 14, 20, 0), "IPv4 header checksum");
        int total = u16(f, 16), proto = proto(f);
        if (proto == PROTO_TCP || proto == PROTO_UDP)
            assertEquals(0xFFFF, ones(f, 34, total - 20, pseudo(ipSrc(f), ipDst(f), proto, total - 20)), "L4 checksum");
        else if (proto == Packets.PROTO_ICMP)
            assertEquals(0xFFFF, ones(f, 34, total - 20, 0), "ICMP checksum");
    }

    /** {@code length} pseudo-random bytes, the same for the same seed. */
    static byte[] pattern(int length, int seed) {
        byte[] b = new byte[length];
        int x = seed * 0x9E3779B1 + 1;
        for (int i = 0; i < length; i++) {
            x ^= x << 13;
            x ^= x >>> 17;
            x ^= x << 5;
            b[i] = (byte) x;
        }
        return b;
    }
}
