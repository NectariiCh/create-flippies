package com.nectariich.create_flippies.content.computer.net;

import static com.nectariich.create_flippies.content.computer.net.Packets.BROADCAST_IP;
import static com.nectariich.create_flippies.content.computer.net.Packets.BROADCAST_MAC;
import static com.nectariich.create_flippies.content.computer.net.Packets.i32;
import static com.nectariich.create_flippies.content.computer.net.Packets.mac;
import static com.nectariich.create_flippies.content.computer.net.Packets.put32;
import static com.nectariich.create_flippies.content.computer.net.Packets.u16;
import static com.nectariich.create_flippies.content.computer.net.Packets.u8;

/**
 * The modem's DHCP server (spec 12.4.1, 12.4.2; RFC 2131, options RFC 2132): one address,
 * {@link Modem#CLIENT_IP}, for whoever asks, since one modem serves one PC. DISCOVER gets an
 * OFFER, REQUEST for that address an ACK (any other address a NAK), RELEASE ends the lease.
 * Replies carry the subnet mask, router, DNS server, lease time and server id.
 */
final class DhcpServer {

    static final int SERVER_PORT = 67, CLIENT_PORT = 68;
    /** Message types (option 53, RFC 2132 9.6). */
    static final int DISCOVER = 1, OFFER = 2, REQUEST = 3, DECLINE = 4, ACK = 5, NAK = 6, RELEASE = 7;
    /** Option codes (RFC 2132). */
    static final int OPT_PAD = 0, OPT_SUBNET = 1, OPT_ROUTER = 3, OPT_DNS = 6, OPT_REQUESTED_IP = 50,
            OPT_LEASE = 51, OPT_TYPE = 53, OPT_SERVER_ID = 54, OPT_END = 255;
    /** Fixed BOOTP fields (RFC 2131 2): offsets of xid, flags, ciaddr, yiaddr, giaddr, chaddr, the cookie, the options. */
    static final int XID = 4, FLAGS = 10, CIADDR = 12, YIADDR = 16, GIADDR = 24, CHADDR = 28, COOKIE = 236, OPTIONS = 240;
    static final int MAGIC_COOKIE = 0x6382_5363, FLAG_BROADCAST = 0x8000;
    /** One day (spec 12.4.1). */
    static final int LEASE_SECONDS = 86_400;
    /** Replies are padded to the 300 bytes of a BOOTP message, which old clients expect (RFC 951). */
    private static final int MIN_REPLY = 300;

    /** A reply's payload and where it goes. */
    record Reply(byte[] payload, long dstMac, int dstIp) {
    }

    private long leaseHolder = -1;

    /** The MAC holding the lease, or -1. */
    long leaseHolder() {
        return leaseHolder;
    }

    /** The reply to the message of {@code length} bytes at {@code at} of {@code b}, or null when none is due. */
    Reply handle(byte[] b, int at, int length) {
        if (length < OPTIONS || u8(b, at) != 1 || u8(b, at + 1) != 1 || u8(b, at + 2) != 6
                || i32(b, at + COOKIE) != MAGIC_COOKIE)
            return null;
        int type = -1, requested = 0, server = 0;
        boolean hasRequested = false, hasServer = false;
        for (int i = at + OPTIONS, end = at + length; i < end; ) {
            int code = u8(b, i);
            if (code == OPT_END)
                break;
            if (code == OPT_PAD) {
                i++;
                continue;
            }
            if (i + 1 >= end || i + 2 + u8(b, i + 1) > end)
                return null;
            int len = u8(b, i + 1), v = i + 2;
            if (code == OPT_TYPE && len == 1)
                type = u8(b, v);
            else if (code == OPT_REQUESTED_IP && len == 4) {
                requested = i32(b, v);
                hasRequested = true;
            } else if (code == OPT_SERVER_ID && len == 4) {
                server = i32(b, v);
                hasServer = true;
            }
            i = v + len;
        }
        long client = mac(b, at + CHADDR);
        switch (type) {
            case DISCOVER:
                return reply(b, at, OFFER, false);
            case REQUEST: {
                // A client that chose another server's offer (RFC 2131 4.3.2)
                if (hasServer && server != Modem.IP)
                    return null;
                int wanted = hasRequested ? requested : i32(b, at + CIADDR);
                if (wanted != Modem.CLIENT_IP)
                    return reply(b, at, NAK, false);
                leaseHolder = client;
                return reply(b, at, ACK, i32(b, at + CIADDR) != 0);
            }
            case RELEASE:
                if (client == leaseHolder)
                    leaseHolder = -1;
                return null;
            default:
                return null;
        }
    }

    /**
     * A reply of {@code type} to the request at {@code at}; ciaddr is copied only into an ACK to a
     * client that had one (RFC 2131 table 3). Addressing per RFC 2131 4.1 with no relay: NAK broadcast; to ciaddr when the client has one; broadcast when the client set the
     * broadcast flag; else unicast to the offered address at the client's hardware address.
     */
    private Reply reply(byte[] req, int at, int type, boolean keepCiaddr) {
        byte[] r = new byte[MIN_REPLY];
        r[0] = 2;
        r[1] = 1;
        r[2] = 6;
        System.arraycopy(req, at + XID, r, XID, 4);
        System.arraycopy(req, at + FLAGS, r, FLAGS, 2);
        if (keepCiaddr)
            System.arraycopy(req, at + CIADDR, r, CIADDR, 4);
        if (type != NAK)
            put32(r, YIADDR, Modem.CLIENT_IP);
        System.arraycopy(req, at + GIADDR, r, GIADDR, 4);
        System.arraycopy(req, at + CHADDR, r, CHADDR, 16);
        put32(r, COOKIE, MAGIC_COOKIE);
        int o = OPTIONS;
        o = option(r, o, OPT_TYPE, 1, type);
        o = option(r, o, OPT_SERVER_ID, 4, Modem.IP);
        if (type != NAK) {
            o = option(r, o, OPT_LEASE, 4, LEASE_SECONDS);
            o = option(r, o, OPT_SUBNET, 4, Modem.NETMASK);
            o = option(r, o, OPT_ROUTER, 4, Modem.IP);
            o = option(r, o, OPT_DNS, 4, Modem.IP);
        }
        r[o] = (byte) OPT_END;
        long chaddr = mac(req, at + CHADDR);
        int ciaddr = i32(r, CIADDR);
        if (type == NAK || (u16(req, at + FLAGS) & FLAG_BROADCAST) != 0 && ciaddr == 0)
            return new Reply(r, BROADCAST_MAC, BROADCAST_IP);
        return new Reply(r, chaddr, ciaddr != 0 ? ciaddr : Modem.CLIENT_IP);
    }

    private static int option(byte[] r, int o, int code, int length, int value) {
        r[o] = (byte) code;
        r[o + 1] = (byte) length;
        if (length == 1)
            r[o + 2] = (byte) value;
        else
            put32(r, o + 2, value);
        return o + 2 + length;
    }
}
