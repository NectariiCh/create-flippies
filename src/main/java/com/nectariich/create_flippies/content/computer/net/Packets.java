package com.nectariich.create_flippies.content.computer.net;

/**
 * Byte-level helpers for the modem's stack (spec 12.4.2): big-endian fields (network byte order),
 * the Internet checksum, and the Ethernet II and IPv4 headers it writes. Frames carry no preamble
 * and no FCS, as on the PC's card (11.1.7).
 */
public final class Packets {

    private Packets() {
    }

    /** Ethernet II header length and the type values the modem handles. */
    public static final int ETH_HEADER = 14, TYPE_IPV4 = 0x0800, TYPE_ARP = 0x0806;
    /** IPv4 header without options, and the protocol numbers the modem handles (RFC 790). */
    public static final int IP_HEADER = 20, PROTO_ICMP = 1, PROTO_TCP = 6, PROTO_UDP = 17;
    public static final int UDP_HEADER = 8;
    public static final long BROADCAST_MAC = 0xFFFF_FFFF_FFFFL;
    /** 255.255.255.255, the limited broadcast address. */
    public static final int BROADCAST_IP = -1;

    public static int u8(byte[] b, int at) {
        return b[at] & 0xFF;
    }

    public static int u16(byte[] b, int at) {
        return (b[at] & 0xFF) << 8 | b[at + 1] & 0xFF;
    }

    public static int i32(byte[] b, int at) {
        return u16(b, at) << 16 | u16(b, at + 2);
    }

    /** 48 bits from {@code at}, byte 0 in bits 47..40 (the MAC layout of {@code Nic}). */
    public static long mac(byte[] b, int at) {
        return (long) u16(b, at) << 32 | i32(b, at + 2) & 0xFFFF_FFFFL;
    }

    public static void put16(byte[] b, int at, int v) {
        b[at] = (byte) (v >>> 8);
        b[at + 1] = (byte) v;
    }

    public static void put32(byte[] b, int at, int v) {
        put16(b, at, v >>> 16);
        put16(b, at + 2, v);
    }

    public static void putMac(byte[] b, int at, long mac) {
        put16(b, at, (int) (mac >>> 32));
        put32(b, at + 2, (int) mac);
    }

    /** {@code acc} plus the big-endian 16-bit words of {@code length} bytes from {@code at}, an odd last byte padded with 0. */
    public static int sum(byte[] b, int at, int length, int acc) {
        int end = at + (length & ~1);
        for (int i = at; i < end; i += 2)
            acc += u16(b, i);
        if ((length & 1) != 0)
            acc += (b[end] & 0xFF) << 8;
        return acc;
    }

    /** A sum folded to 16 bits with end-around carry (one's complement addition, RFC 1071). */
    public static int fold(int s) {
        while (s >>> 16 != 0)
            s = (s & 0xFFFF) + (s >>> 16);
        return s;
    }

    /** The Internet checksum (RFC 1071) of {@code length} bytes from {@code at}. */
    public static int checksum(byte[] b, int at, int length) {
        return ~fold(sum(b, at, length, 0)) & 0xFFFF;
    }

    /** The unfolded sum of the IPv4 pseudo-header for TCP and UDP (RFC 793, 768). */
    public static int pseudoSum(int src, int dst, int protocol, int length) {
        return (src >>> 16) + (src & 0xFFFF) + (dst >>> 16) + (dst & 0xFFFF) + protocol + length;
    }

    /**
     * A new frame of {@code ETH_HEADER + IP_HEADER + payload} bytes with the Ethernet and IPv4
     * headers filled in (no options, don't fragment, TTL 64, header checksum set). The caller
     * writes the payload at {@code ETH_HEADER + IP_HEADER} and its checksum.
     */
    public static byte[] ipv4Frame(long dstMac, long srcMac, int src, int dst, int protocol, int id, int payload) {
        byte[] f = new byte[ETH_HEADER + IP_HEADER + payload];
        putMac(f, 0, dstMac);
        putMac(f, 6, srcMac);
        put16(f, 12, TYPE_IPV4);
        f[14] = 0x45;
        put16(f, 16, IP_HEADER + payload);
        put16(f, 18, id);
        put16(f, 20, 0x4000);
        f[22] = 64;
        f[23] = (byte) protocol;
        put32(f, 26, src);
        put32(f, 30, dst);
        put16(f, 24, checksum(f, ETH_HEADER, IP_HEADER));
        return f;
    }

    /** Dotted-quad text of an IPv4 address, for messages. */
    public static String ipText(int ip) {
        return (ip >>> 24) + "." + (ip >>> 16 & 0xFF) + "." + (ip >>> 8 & 0xFF) + "." + (ip & 0xFF);
    }
}
