package com.nectariich.create_flippies.content.computer.net;

import static com.nectariich.create_flippies.content.computer.net.NetFrames.*;
import static com.nectariich.create_flippies.content.computer.net.TestClient.ACK;
import static com.nectariich.create_flippies.content.computer.net.TestClient.FIN;
import static com.nectariich.create_flippies.content.computer.net.TestClient.PSH;
import static com.nectariich.create_flippies.content.computer.net.TestClient.RST;
import static com.nectariich.create_flippies.content.computer.net.TestClient.SYN;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;
import java.util.function.BooleanSupplier;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The modem's TCP (spec 12.4.2, 12.11): handshake and options, windowed transfer across the
 * sequence number wrap, no loss while the PC's queue is full part of the time, retransmission
 * after a lost frame, FIN, RST, the connection limit, zero windows and timeouts.
 */
class TcpTest {

    private static final byte[] BIG = pattern(1 << 20, 1);
    private static final int ALL = Integer.MAX_VALUE;

    private final MemoryContent content = new MemoryContent().put("get.flip/big.bin", BIG).put("get.flip/small.txt", "hello\n")
            .put("get.flip/mid.bin", Arrays.copyOf(BIG, 200_000));
    private Modem modem;
    private TestClient client;
    private ClientEndpoint pc;
    private int ticks;

    @BeforeEach
    void setUp() {
        modem = new Modem(MODEM_MAC, new Catalogue(content));
        client = new TestClient();
        pc = new ClientEndpoint(client);
    }

    private void step(int frames) {
        pc.step(modem, frames);
        ticks++;
    }

    private void steps(int n) {
        for (int i = 0; i < n; i++)
            step(ALL);
    }

    /** Steps until {@code done}, failing after {@code max} ticks. */
    private void until(BooleanSupplier done, int max) {
        for (int i = 0; !done.getAsBoolean(); i++) {
            assertTrue(i < max, "not done after " + max + " ticks");
            step(ALL);
        }
    }

    private void connect() {
        client.connect();
        steps(2);
        assertTrue(client.established);
        assertEquals(1, modem.connectionCount());
    }

    private List<byte[]> tcpFrames() {
        List<byte[]> l = new ArrayList<>();
        for (byte[] f : client.inbox)
            if (proto(f) == 6)
                l.add(f);
        return l;
    }

    private static String get(String path, String... headers) {
        return "GET " + path + " HTTP/1.1\r\nHost: get.flip\r\n" + String.join("", headers) + "\r\n";
    }

    @Test
    void handshakeOffersMss1460AndScalingOnlyWhenAsked() {
        client.connect();
        step(ALL);
        byte[] synAck = tcpFrames().get(0);
        assertEquals(SYN | ACK, tcpFlags(synAck));
        assertEquals(client.iss + 1, tcpAck(synAck));
        assertEquals(0xFFFF_F000, tcpSeq(synAck), "the first ISS sits just below the wrap");
        assertEquals(24, tcpHeaderLength(synAck));
        assertArrayEquals(new byte[] { 2, 4, 0x05, (byte) 0xB4 }, Arrays.copyOfRange(synAck, 54, 58), "MSS 1460, nothing else");
        assertEquals(65535, (synAck[48] & 0xFF) << 8 | synAck[49] & 0xFF, "window");
        assertEquals(PC_MAC, Packets.mac(synAck, 0));
        assertEquals(1, modem.connectionCount());
        step(ALL);
        assertTrue(client.established);

        setUp();
        client.windowShift = 7;
        client.port = 40001;
        client.connect();
        step(ALL);
        synAck = tcpFrames().get(0);
        assertEquals(28, tcpHeaderLength(synAck));
        assertArrayEquals(new byte[] { 2, 4, 0x05, (byte) 0xB4, 1, 3, 3, 0 }, Arrays.copyOfRange(synAck, 54, 62), "MSS, NOP, shift 0");
    }

    @Test
    void windowedTransferAcrossTheSequenceWrap() {
        client.window = 8192;
        connect();
        client.send(get("/big.bin"));
        until(() -> client.response(0, false) != null, 2000);
        TestClient.HttpReply r = client.response(0, false);
        assertEquals(200, r.status());
        assertArrayEquals(BIG, r.body());
        assertEquals(0, modem.retransmissions());
        assertEquals(0, client.outOfOrder);
        boolean high = false, low = false;
        for (byte[] f : tcpFrames()) {
            int length = tcpData(f).length;
            assertTrue(length <= 1460 && f.length <= 1514, "segments within the MSS");
            high |= Integer.compareUnsigned(tcpSeq(f), 0xF000_0000) >= 0;
            low |= Integer.compareUnsigned(tcpSeq(f), 0x0100_0000) < 0;
        }
        assertTrue(high && low, "the sequence numbers wrapped");
    }

    @Test
    void neverMoreThanTheWindowInFlight() {
        client.window = 5000;
        connect();
        client.send(get("/mid.bin"));
        // Every tick the client takes all and acknowledges, so one tick's data is what was in flight
        int last = client.stream.size();
        for (int i = 0; i < 400 && client.response(0, false) == null; i++) {
            step(ALL);
            assertTrue(client.stream.size() - last <= 5000, "at most a window per round trip");
            last = client.stream.size();
        }
        assertArrayEquals(Arrays.copyOf(BIG, 200_000), client.response(0, false).body());
    }

    @Test
    void theClientsMssIsHonoured() {
        client.mss = 536;
        connect();
        client.send(get("/mid.bin"));
        until(() -> client.response(0, false) != null, 2000);
        for (byte[] f : tcpFrames())
            assertTrue(tcpData(f).length <= 536);
    }

    @Test
    void noLossWhileThePcsQueueIsFullPartOfTheTime() {
        client.window = 65535 << 5;
        client.windowShift = 5;
        connect();
        pc.capacity = 48;
        client.send(get("/big.bin"));
        Random random = new Random(12);
        for (int i = 0; client.response(0, false) == null; i++) {
            assertTrue(i < 5000, "transfer stalled");
            step(random.nextInt(4) == 0 ? 0 : random.nextInt(60));
        }
        assertArrayEquals(BIG, client.response(0, false).body());
        assertTrue(pc.refused > 0, "the queue was full at times");
        assertEquals(0, modem.retransmissions(), "nothing was lost, so nothing was sent twice");
        assertEquals(0, modem.framesDropped());
        assertEquals(0, client.outOfOrder);
    }

    @Test
    void withoutTheModemsExchangeRefusedFramesAreLostAndTcpRecovers() {
        connect();
        pc.capacity = 16;
        client.send(get("/mid.bin"));
        Random random = new Random(3);
        for (int i = 0; client.response(0, false) == null; i++) {
            assertTrue(i < 20000, "transfer stalled");
            // PcLinkExchange.ethernet: every frame offered once, a refusal is a loss
            for (byte[] f; (f = pc.takeOutgoing()) != null; )
                modem.deliver(f, 0, f.length);
            for (byte[] f; (f = modem.takeOutgoing()) != null; )
                pc.deliver(f, 0, f.length);
            modem.tick();
            pc.process(random.nextInt(12));
        }
        assertArrayEquals(Arrays.copyOf(BIG, 200_000), client.response(0, false).body());
        assertTrue(modem.retransmissions() > 0);
    }

    @Test
    void aLostFrameIsSentAgainAfter20Ticks() {
        client.window = 32768;
        connect();
        int[] dataFrames = { 0 };
        int[] lostSeq = { 0 };
        pc.lose = f -> {
            if (proto(f) != 6 || tcpData(f).length == 0 || ++dataFrames[0] != 5)
                return false;
            lostSeq[0] = tcpSeq(f);
            return true;
        };
        client.send(get("/mid.bin"));
        int lostAt = -1, resentAt = -1;
        for (int i = 0; client.response(0, false) == null; i++) {
            assertTrue(i < 3000);
            int seen = client.inbox.size();
            step(ALL);
            if (lostAt < 0 && pc.lost == 1)
                lostAt = ticks;
            for (int k = seen; k < client.inbox.size() && lostAt >= 0 && resentAt < 0; k++)
                if (proto(client.inbox.get(k)) == 6 && tcpSeq(client.inbox.get(k)) == lostSeq[0])
                    resentAt = ticks;
        }
        assertArrayEquals(Arrays.copyOf(BIG, 200_000), client.response(0, false).body());
        assertEquals(1, modem.retransmissions());
        assertTrue(client.outOfOrder > 0, "what followed the loss arrived out of order");
        assertTrue(resentAt - lostAt >= 20 && resentAt - lostAt <= 22, "resent " + (resentAt - lostAt) + " ticks after the loss");
    }

    @Test
    void clientFinAfterTheResponseClosesBothWays() {
        connect();
        client.send(get("/small.txt"));
        client.fin();
        until(() -> client.finReceived, 20);
        TestClient.HttpReply r = client.response(0, false);
        assertEquals("hello\n", new String(r.body(), StandardCharsets.US_ASCII));
        steps(1);
        assertEquals(0, modem.connectionCount(), "both FINs acknowledged");
        assertEquals(client.sndNxt, tcpAck(tcpFrames().get(tcpFrames().size() - 1)), "our FIN acknowledged");
    }

    @Test
    void connectionCloseMakesTheModemCloseFirst() {
        connect();
        client.send(get("/small.txt", "Connection: close\r\n"));
        until(() -> client.finReceived, 20);
        assertEquals(200, client.response(0, false).status());
        steps(2);
        assertEquals(1, modem.connectionCount(), "waits for the client's FIN");
        client.fin();
        steps(2);
        assertEquals(0, modem.connectionCount());
        byte[] last = tcpFrames().get(tcpFrames().size() - 1);
        assertEquals(ACK, tcpFlags(last));
        assertEquals(client.sndNxt, tcpAck(last));
    }

    @Test
    void aClientThatNeverSendsItsFinIsForgotten() {
        connect();
        client.send(get("/small.txt", "Connection: close\r\n"));
        until(() -> client.finReceived, 20);
        steps(Tcp.IDLE_TICKS - 5);
        assertEquals(1, modem.connectionCount());
        steps(10);
        assertEquals(0, modem.connectionCount());
    }

    @Test
    void idleKeepAliveConnectionsAreClosedByTheModem() {
        connect();
        client.send(get("/small.txt"));
        steps(3);
        assertFalse(client.finReceived);
        steps(Tcp.IDLE_TICKS);
        assertTrue(client.finReceived, "a FIN after a minute of silence");
        client.fin();
        steps(2);
        assertEquals(0, modem.connectionCount());
    }

    @Test
    void resetClosesTheConnection() {
        connect();
        client.send(get("/big.bin"));
        steps(2);
        int sent = client.inbox.size();
        byte[] blind = tcp(client.port, 80, client.sndNxt + 100_000, 0, RST, 0, new byte[0], new byte[0]);
        assertTrue(modem.deliver(blind, 0, blind.length));
        assertEquals(1, modem.connectionCount(), "a RST outside the window is ignored");
        client.reset();
        step(0);
        assertEquals(0, modem.connectionCount());
        pc.rx.clear();
        steps(30);
        assertEquals(sent, client.inbox.size(), "nothing more after the reset");
    }

    @Test
    void segmentsNoConnectionTakesGetRst() {
        modem.setLink(true);
        byte[] syn = tcp(41000, 81, 0x5000, 0, SYN, 1000, new byte[0], new byte[0]);
        modem.deliver(syn, 0, syn.length);
        byte[] r = modem.takeOutgoing();
        assertChecksums(r);
        assertEquals(RST | ACK, tcpFlags(r), "closed port");
        assertEquals(0, tcpSeq(r));
        assertEquals(0x5001, tcpAck(r));
        byte[] data = tcp(41000, 80, 0x7000, 0x1234_5678, ACK | PSH, 1000, new byte[0], new byte[5]);
        modem.deliver(data, 0, data.length);
        r = modem.takeOutgoing();
        assertEquals(RST, tcpFlags(r), "no such connection");
        assertEquals(0x1234_5678, tcpSeq(r));
        byte[] rst = tcp(41000, 80, 0x7000, 0, RST, 0, new byte[0], new byte[0]);
        modem.deliver(rst, 0, rst.length);
        assertEquals(null, modem.takeOutgoing(), "never a RST for a RST");
        byte[] fin = tcp(41000, 80, 0x7000, 0, FIN, 0, new byte[0], new byte[3]);
        modem.deliver(fin, 0, fin.length);
        assertEquals(0x7004, tcpAck(modem.takeOutgoing()), "data and FIN counted");
        byte[] bad = tcp(41000, 80, 0x7000, 0, SYN, 1000, new byte[0], new byte[0]);
        bad[50] ^= 1; // the TCP checksum
        modem.deliver(bad, 0, bad.length);
        assertEquals(null, modem.takeOutgoing(), "a bad checksum is dropped");
        assertEquals(0, modem.connectionCount());
    }

    @Test
    void atMostEightConnections() {
        modem.setLink(true);
        for (int i = 0; i < 9; i++) {
            byte[] syn = tcp(42000 + i, 80, 100 * i, 0, SYN, 1000, new byte[0], new byte[0]);
            modem.deliver(syn, 0, syn.length);
            byte[] r = modem.takeOutgoing();
            if (i < 8)
                assertEquals(SYN | ACK, tcpFlags(r));
            else
                assertEquals(null, r, "the ninth SYN is dropped; its retry may find room later");
        }
        assertEquals(8, modem.connectionCount());
        byte[] rst = tcp(42000, 80, 1, 0, RST, 0, new byte[0], new byte[0]);
        modem.deliver(rst, 0, rst.length);
        assertEquals(7, modem.connectionCount());
        byte[] syn = tcp(42008, 80, 800, 0, SYN, 1000, new byte[0], new byte[0]);
        modem.deliver(syn, 0, syn.length);
        assertEquals(SYN | ACK, tcpFlags(modem.takeOutgoing()));
    }

    @Test
    void zeroWindowIsProbedThenTheTransferGoesOn() {
        connect();
        client.window = 0;
        client.send(get("/mid.bin"));
        int start = ticks;
        until(() -> client.stream.size() > 0, 40);
        assertTrue(ticks - start >= 20, "the first probe waits for the timer");
        assertEquals(1, client.stream.size(), "one byte past a zero window");
        steps(25);
        assertEquals(2, client.stream.size(), "the next probe");
        client.window = 65535;
        client.ack();
        until(() -> client.response(0, false) != null, 400);
        assertArrayEquals(Arrays.copyOf(BIG, 200_000), client.response(0, false).body());
        assertTrue(modem.connectionCount() == 1, "probes answered do not give up");
    }

    /** Whole HTTP responses the client has, in order. */
    private int responses() {
        int n = 0;
        for (TestClient.HttpReply r = client.response(0, false); r != null; r = client.response(r.end(), false))
            n++;
        return n;
    }

    @Test
    void pipelinedRequestsBehindAClosedWindowStopBeingRead() {
        // A client that pipelines and never takes the answers cannot make the modem queue them
        // without end: past the cap its data is left unacknowledged, to be sent again
        connect();
        client.window = 0;
        String request = get("/small.txt");
        int start = client.sndNxt, total = 100;
        for (int i = 0; i < total; i += 20)
            client.send(request.repeat(20));
        steps(3);
        List<byte[]> frames = tcpFrames();
        int acked = tcpAck(frames.get(frames.size() - 1));
        assertEquals(start + HttpSession.MAX_QUEUED * request.length(), acked, "only the requests under the cap");
        assertEquals(0, client.stream.size());

        // With the window open, the client sends again what was not acknowledged, round by round
        client.window = 65535;
        client.ack();
        for (int round = 0; ; round++) {
            assertTrue(round < 10, "requests left unread");
            int answered = (acked - start) / request.length();
            until(() -> responses() == answered, 100);
            if (answered == total)
                break;
            client.sndNxt = acked;
            for (int left = total - answered; left > 0; left -= 20)
                client.send(request.repeat(Math.min(20, left)));
            steps(3);
            frames = tcpFrames();
            int next = tcpAck(frames.get(frames.size() - 1));
            assertTrue(next - acked <= HttpSession.MAX_QUEUED * request.length(), "never more than the cap at once");
            acked = next;
        }
        assertEquals(1, modem.connectionCount());
        assertEquals(0, modem.retransmissions());
    }

    @Test
    void anUnansweredSynAckIsSentAgainThenGivenUp() {
        client.connect();
        step(ALL);
        client.outbox.clear(); // the handshake ACK is lost, every time
        int synAcks = 1;
        for (int i = 0; i < 20 * (Tcp.MAX_RETRIES + 1) + 5; i++) {
            step(ALL);
            client.outbox.clear();
        }
        for (byte[] f : tcpFrames().subList(1, tcpFrames().size()))
            if (tcpFlags(f) == (SYN | ACK))
                synAcks++;
        assertEquals(1 + Tcp.MAX_RETRIES, synAcks);
        assertEquals(RST, tcpFlags(tcpFrames().get(tcpFrames().size() - 1)), "then a RST");
        assertEquals(0, modem.connectionCount());
    }

    @Test
    void aRestartedClientIsChallengedThenServed() {
        connect();
        int oldRcv = client.rcvNxt;
        TestClient fresh = new TestClient();
        fresh.iss = 0x9000_0000;
        fresh.connect();
        byte[] syn = fresh.outbox.remove(0);
        modem.deliver(syn, 0, syn.length);
        byte[] r = modem.takeOutgoing();
        assertEquals(ACK, tcpFlags(r), "a challenge ACK, not a new connection");
        assertEquals(client.sndNxt, tcpAck(r));
        assertEquals(oldRcv, tcpSeq(r));
        // The restarted client answers the unacceptable ACK with a RST at its ack number
        byte[] rst = tcp(client.port, 80, tcpAck(r), 0, RST, 0, new byte[0], new byte[0]);
        modem.deliver(rst, 0, rst.length);
        assertEquals(0, modem.connectionCount());
        modem.deliver(syn, 0, syn.length);
        assertEquals(SYN | ACK, tcpFlags(modem.takeOutgoing()));
    }

    @Test
    void outOfOrderDataIsDroppedAndTheGapAskedFor() {
        connect();
        byte[] request = get("/small.txt").getBytes(StandardCharsets.US_ASCII);
        byte[] second = tcp(client.port, 80, client.sndNxt + 10, client.rcvNxt, ACK, 65535, new byte[0],
                Arrays.copyOfRange(request, 10, request.length));
        modem.deliver(second, 0, second.length);
        byte[] dup = modem.takeOutgoing();
        assertEquals(ACK, tcpFlags(dup));
        assertEquals(client.sndNxt, tcpAck(dup), "a duplicate ACK at the gap");
        client.send(Arrays.copyOf(request, 10));
        client.send(Arrays.copyOfRange(request, 10, request.length));
        steps(2);
        assertEquals("hello\n", new String(client.response(0, false).body(), StandardCharsets.US_ASCII));
    }

    @Test
    void linkDownForgetsEverything() {
        connect();
        client.send(get("/big.bin"));
        pc.capacity = 0;
        step(0);
        assertTrue(modem.outgoingCount() > 0);
        modem.setLink(false);
        assertEquals(0, modem.connectionCount());
        assertEquals(0, modem.outgoingCount());
        modem.tick();
        assertEquals(0, modem.outgoingCount(), "a modem without link does nothing");
    }
}
