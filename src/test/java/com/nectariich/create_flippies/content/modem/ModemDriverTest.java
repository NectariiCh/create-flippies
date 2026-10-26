package com.nectariich.create_flippies.content.modem;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
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
 * A network modem block's server side (docs/PC_SPEC.md 12.4.5) without the game: the lights, a
 * failing modem kept off and reported once, a throw from the PC's card left to the PC, and the
 * link taken down when the block goes.
 */
class ModemDriverTest {

    private static final long PC_MAC = 0x02_11_22_33_44_55L, MODEM_MAC = 0x02_4D_4F_44_45_4DL;

    /** A PC's card reduced to its queues. */
    private static class Card implements FrameEndpoint {
        final ArrayDeque<byte[]> toSend = new ArrayDeque<>();
        final List<byte[]> received = new ArrayList<>();
        boolean link;

        @Override
        public byte[] takeOutgoing() {
            return toSend.poll();
        }

        @Override
        public boolean deliver(byte[] frame, int offset, int length) {
            received.add(java.util.Arrays.copyOfRange(frame, offset, offset + length));
            return true;
        }

        @Override
        public void setLink(boolean up) {
            link = up;
        }
    }

    private final List<Throwable> reported = new ArrayList<>();
    private int made;

    private ModemDriver driver() {
        return new ModemDriver(() -> {
            made++;
            return new Modem(MODEM_MAC, new Catalogue(new MemoryContent().put("get.flip/index.txt", "")));
        }, reported::add);
    }

    /** "Who has 10.88.0.1? Tell 10.88.0.2", broadcast. */
    private static byte[] arpRequest() {
        byte[] f = new byte[42];
        for (int i = 0; i < 6; i++)
            f[i] = (byte) 0xFF;
        for (int i = 0; i < 6; i++)
            f[6 + i] = f[22 + i] = (byte) (PC_MAC >>> (40 - 8 * i));
        f[12] = 0x08;
        f[13] = 0x06;
        byte[] arp = {0, 1, 8, 0, 6, 4, 0, 1};
        System.arraycopy(arp, 0, f, 14, arp.length);
        for (int i = 0; i < 4; i++) {
            f[28 + i] = (byte) (Modem.CLIENT_IP >>> (24 - 8 * i));
            f[38 + i] = (byte) (Modem.IP >>> (24 - 8 * i));
        }
        return f;
    }

    @Test
    void aRunningPcPowersItAndTrafficBlinksTheLinkLight() {
        ModemDriver d = driver();
        Card card = new Card();
        assertFalse(d.exchange(null, 0), "never driven: dark, nothing made");
        assertNull(d.modem());
        card.toSend.add(arpRequest());
        assertTrue(d.exchange(card, 1));
        assertTrue(d.powered());
        assertTrue(d.active());
        assertTrue(card.link);
        assertEquals(1, card.received.size(), "the ARP reply");
        assertFalse(d.exchange(card, 1 + ModemDriver.ACTIVITY_TICKS - 1), "still blinking");
        assertTrue(d.exchange(card, 1 + ModemDriver.ACTIVITY_TICKS), "quiet: the blinking stops");
        assertTrue(d.powered());
        assertFalse(d.active());
        assertTrue(d.exchange(null, 20), "the PC went off");
        assertFalse(d.powered());
        assertFalse(d.modem().link());
        assertEquals(1, made);
    }

    @Test
    void aFailingModemStaysOffForAWhileAndIsReportedOncePerQuietSpell() {
        ModemDriver d = new ModemDriver(() -> {
            made++;
            throw new IllegalStateException("modem bug");
        }, reported::add);
        Card card = new Card();
        card.link = true;
        for (long now = 0; now < 3 * ModemDriver.RETRY_TICKS; now++) {
            d.exchange(card, now);
            assertFalse(card.link, "the PC's link is down while the modem is off");
            assertFalse(d.powered());
        }
        assertEquals(3, made, "made again once per retry, not every tick");
        assertEquals(1, reported.size(), "reported once");
        // Past the quiet spell the next failure is reported again
        for (long now = ModemDriver.QUIET_TICKS; now < ModemDriver.QUIET_TICKS + ModemDriver.RETRY_TICKS; now++)
            d.exchange(card, now);
        assertEquals(2, reported.size());
    }

    @Test
    void aThrowFromThePcsCardIsThePcsToHandle() {
        ModemDriver d = driver();
        IllegalStateException bug = new IllegalStateException("card bug");
        Card card = new Card() {
            @Override
            public byte[] takeOutgoing() {
                throw bug;
            }
        };
        assertSame(bug, assertThrows(IllegalStateException.class, () -> d.exchange(card, 0)));
        assertTrue(reported.isEmpty(), "not blamed on the modem");
        assertNotNull(d.modem(), "the modem carries on");
    }

    @Test
    void closingTakesTheLinkDownAndANewModemIsMadeWhenDrivenAgain() {
        ModemDriver d = driver();
        Card card = new Card();
        card.toSend.add(arpRequest());
        d.exchange(card, 0);
        Modem first = d.modem();
        assertTrue(first.link());
        d.close();
        assertNull(d.modem());
        assertFalse(first.link(), "its connections are dropped now, not when it is collected");
        d.exchange(card, 1);
        assertNotSame(first, d.modem());
        assertTrue(d.modem().link());
    }
}
