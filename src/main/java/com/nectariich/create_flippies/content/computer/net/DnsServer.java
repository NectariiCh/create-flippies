package com.nectariich.create_flippies.content.computer.net;

import static com.nectariich.create_flippies.content.computer.net.Packets.put16;
import static com.nectariich.create_flippies.content.computer.net.Packets.put32;
import static com.nectariich.create_flippies.content.computer.net.Packets.u16;
import static com.nectariich.create_flippies.content.computer.net.Packets.u8;

import java.util.Locale;

/**
 * The modem's DNS server over UDP (spec 12.4.1, 12.4.2; RFC 1035): A records for the catalogue's
 * hosts, all {@link Modem#IP}; such a host asked for another type (AAAA, MX, ...) gets an empty
 * answer; every other name NXDOMAIN. One question per query; anything else gets FORMERR, other
 * opcodes NOTIMP. Answers are authoritative; the additional section (EDNS) is ignored.
 */
final class DnsServer {

    static final int PORT = 53;
    static final int TYPE_A = 1, TYPE_AAAA = 28, TYPE_ANY = 255, CLASS_IN = 1, CLASS_ANY = 255;
    static final int RCODE_FORMERR = 1, RCODE_NXDOMAIN = 3, RCODE_NOTIMP = 4;
    /** Time to live of an answer, seconds. */
    static final int TTL = 3600;
    private static final int HEADER = 12;

    private DnsServer() {
    }

    /** The response to the query of {@code length} bytes at {@code at}, or null to drop it (too short, or a response). */
    static byte[] answer(byte[] b, int at, int length, Catalogue catalogue) {
        if (length < HEADER || (u8(b, at + 2) & 0x80) != 0)
            return null;
        int opcode = u8(b, at + 2) >>> 3 & 0xF;
        if (opcode != 0)
            return header(b, at, 0, RCODE_NOTIMP, 0, 0);
        if (u16(b, at + 4) != 1)
            return header(b, at, 0, RCODE_FORMERR, 0, 0);
        StringBuilder name = new StringBuilder();
        int i = at + HEADER, end = at + length;
        while (true) {
            if (i >= end)
                return header(b, at, 0, RCODE_FORMERR, 0, 0);
            int label = u8(b, i++);
            if (label == 0)
                break;
            // No compression pointers in a question we parse, and RFC 1035 limits: 63 per label, 255 per name
            if (label > 63 || i + label > end || i - (at + HEADER) + label > 255)
                return header(b, at, 0, RCODE_FORMERR, 0, 0);
            if (name.length() > 0)
                name.append('.');
            for (int k = 0; k < label; k++)
                name.append((char) u8(b, i + k));
            i += label;
        }
        if (i + 4 > end)
            return header(b, at, 0, RCODE_FORMERR, 0, 0);
        int qtype = u16(b, i), qclass = u16(b, i + 2);
        int question = i + 4 - (at + HEADER);
        String host = name.toString().toLowerCase(Locale.ROOT);
        if (!catalogue.hasHost(host))
            return withQuestion(b, at, question, RCODE_NXDOMAIN, false);
        boolean a = (qtype == TYPE_A || qtype == TYPE_ANY) && (qclass == CLASS_IN || qclass == CLASS_ANY);
        return withQuestion(b, at, question, 0, a);
    }

    /** Header, the question copied, and the A record when {@code a}. */
    private static byte[] withQuestion(byte[] q, int at, int question, int rcode, boolean a) {
        byte[] head = header(q, at, 1, rcode, a ? 1 : 0, question + (a ? 16 : 0));
        System.arraycopy(q, at + HEADER, head, HEADER, question);
        if (a) {
            int o = HEADER + question;
            put16(head, o, 0xC000 | HEADER);
            put16(head, o + 2, TYPE_A);
            put16(head, o + 4, CLASS_IN);
            put32(head, o + 6, TTL);
            put16(head, o + 10, 4);
            put32(head, o + 12, Modem.IP);
        }
        return head;
    }

    /** A response header for the query at {@code at}, QR and AA set, RD copied, RA set, then {@code extra} zero bytes. */
    private static byte[] header(byte[] q, int at, int qdcount, int rcode, int ancount, int extra) {
        byte[] r = new byte[HEADER + extra];
        r[0] = q[at];
        r[1] = q[at + 1];
        r[2] = (byte) (0x84 | u8(q, at + 2) & 0x79);
        r[3] = (byte) (0x80 | rcode);
        put16(r, 4, qdcount);
        put16(r, 6, ancount);
        return r;
    }
}
