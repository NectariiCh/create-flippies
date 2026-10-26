package com.nectariich.create_flippies.content.pc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;

import com.nectariich.create_flippies.content.computer.net.Catalogue;
import com.nectariich.create_flippies.content.computer.net.FrameEndpoint;
import com.nectariich.create_flippies.content.computer.net.MemoryContent;
import com.nectariich.create_flippies.content.computer.net.Modem;

import org.junit.jupiter.api.Test;

/**
 * The PC to modem link as the exchange phase drives it (docs/PC_SPEC.md 12.4.2, 12.4.5):
 * {@link PcLinkExchange#modem} with the PC's card while the PC runs, with null while it does
 * not, and the modem's 2.5 W on the PC's rails.
 */
class ModemLinkTest {

    private static final long PC_MAC = 0x02_11_22_33_44_55L, MODEM_MAC = 0x02_4D_4F_44_45_4DL;

    /** A PC's card reduced to its queues, with room for {@code room} frames. */
    private static final class Card implements FrameEndpoint {
        final ArrayDeque<byte[]> toSend = new ArrayDeque<>();
        final List<byte[]> received = new ArrayList<>();
        int room = Integer.MAX_VALUE;
        boolean link;

        @Override
        public byte[] takeOutgoing() {
            return toSend.poll();
        }

        @Override
        public boolean deliver(byte[] frame, int offset, int length) {
            if (received.size() >= room)
                return false;
            received.add(java.util.Arrays.copyOfRange(frame, offset, offset + length));
            return true;
        }

        @Override
        public boolean canReceive() {
            return received.size() < room;
        }

        @Override
        public void setLink(boolean up) {
            link = up;
        }
    }

    private static Modem modem() {
        return new Modem(MODEM_MAC, new Catalogue(new MemoryContent().put("get.flip/index.txt", "")));
    }

    /** "Who has 10.88.0.1? Tell 10.88.0.2", broadcast. */
    private static byte[] arpRequest() {
        byte[] f = new byte[42];
        for (int i = 0; i < 6; i++)
            f[i] = (byte) 0xFF;
        putMac(f, 6, PC_MAC);
        f[12] = 0x08;
        f[13] = 0x06;
        byte[] arp = {0, 1, 8, 0, 6, 4, 0, 1};
        System.arraycopy(arp, 0, f, 14, arp.length);
        putMac(f, 22, PC_MAC);
        put32(f, 28, Modem.CLIENT_IP);
        put32(f, 38, Modem.IP);
        return f;
    }

    private static void putMac(byte[] b, int at, long mac) {
        for (int i = 0; i < 6; i++)
            b[at + i] = (byte) (mac >>> (40 - 8 * i));
    }

    private static void put32(byte[] b, int at, int v) {
        for (int i = 0; i < 4; i++)
            b[at + i] = (byte) (v >>> (24 - 8 * i));
    }

    @Test
    void aRunningPcRaisesTheLinkAndGetsItsAnswerTheSameTick() {
        Modem modem = modem();
        Card card = new Card();
        card.toSend.add(arpRequest());
        PcLinkExchange.modem(modem, card);
        assertTrue(modem.link());
        assertTrue(card.link);
        assertEquals(1, card.received.size(), "the ARP reply");
        byte[] reply = card.received.get(0);
        assertEquals(0x0806, (reply[12] & 0xFF) << 8 | reply[13] & 0xFF);
        assertEquals(2, reply[21], "an ARP reply");
        assertEquals(1, modem.framesIn());
        assertEquals(1, modem.framesOut());
    }

    @Test
    void noRunningPcTakesTheLinkDownAndDropsWhatWaits() {
        Modem modem = modem();
        Card card = new Card();
        card.room = 0;
        card.toSend.add(arpRequest());
        PcLinkExchange.modem(modem, card);
        assertEquals(1, modem.outgoingCount(), "held while the card has no room");
        PcLinkExchange.modem(modem, null);
        assertFalse(modem.link());
        assertEquals(0, modem.outgoingCount());
        assertFalse(modem.deliver(arpRequest(), 0, 42), "a modem with no power takes nothing");
        // And up again with the PC
        card.room = Integer.MAX_VALUE;
        card.toSend.add(arpRequest());
        PcLinkExchange.modem(modem, card);
        assertTrue(modem.link());
        assertEquals(1, card.received.size());
    }

    @Test
    void aFrameTheCardRefusesWaitsForTheNextTick() {
        Modem modem = modem();
        Card card = new Card();
        card.room = 0;
        card.toSend.add(arpRequest());
        PcLinkExchange.modem(modem, card);
        assertEquals(0, card.received.size());
        card.room = 1;
        PcLinkExchange.modem(modem, card);
        assertEquals(1, card.received.size(), "not lost, as plain ethernet() would lose it");
        assertEquals(0, modem.framesDropped());
    }

    @Test
    void aLinkedModemAddsTwoAndAHalfWattsToTheRails() {
        PcPower.Draw draw = new PcPower.Draw(true, PcCpuType.values()[0], 0.5, PcRamType.values()[0], null, true,
                false, false);
        assertEquals(PcPower.railWatts(draw), PcPower.railWatts(draw, false), 1e-9);
        assertEquals(PcPower.railWatts(draw) + 2.5, PcPower.railWatts(draw, true), 1e-9);
        assertEquals((PcPower.railWatts(draw) + 2.5) / PcPower.EFFICIENCY,
                PcPower.inputWatts(true, PcPower.railWatts(draw, true)), 1e-9);
    }
}
