package com.nectariich.create_flippies.content.computer.net;

import static com.nectariich.create_flippies.content.computer.net.Tcp.ACK;
import static com.nectariich.create_flippies.content.computer.net.Tcp.FIN;
import static com.nectariich.create_flippies.content.computer.net.Tcp.PSH;

import java.io.IOException;

/**
 * One passive TCP connection to the HTTP server (spec 12.4.2; RFC 9293): in-order receive, a
 * sliding window send limited by the peer's window, go-back-N retransmission when nothing is
 * acknowledged for {@link Tcp#RTO_TICKS} ticks, FIN and RST.
 *
 * The send side counts in positions rather than sequence numbers: position 0 is our SYN,
 * position 1 + k byte k of the HTTP output, and {@code end + 1} our FIN once HTTP is closing.
 * A sequence number is {@code iss + position}, so wrapping is only ever a subtraction of ints and
 * the output may run past 4 GiB.
 */
final class TcpConnection {

    enum State { SYN_RECEIVED, ESTABLISHED }

    final long peerMac;
    final int peerIp, peerPort;
    final int iss;
    private final int irs;
    /** The peer's window scale shift (RFC 7323), 0 when it sent none. */
    private final int peerShift;
    /** The peer offered window scaling, so our SYN-ACK carries the option too. */
    final boolean windowScaling;
    private final int mss;
    final HttpSession http;
    private final Tcp tcp;

    State state = State.SYN_RECEIVED;
    long sndUna, sndNxt, sndMax;
    long peerWindow;
    /** The next send may put one byte past a zero window (a window probe, RFC 9293 3.8.6.1). */
    private boolean probe;
    int rcvNxt;
    boolean finReceived;
    /** An ACK is owed for something received and no segment has carried it yet. */
    boolean ackDue;
    /** Finished: the table drops it once any owed ACK is out. */
    boolean closed;
    private int rtoTicks, retries, idleTicks;
    private final byte[] buf = new byte[Tcp.MSS];

    /**
     * From the peer's SYN: its sequence number {@code irs}, its window (never scaled in a SYN,
     * RFC 7323 2.2), its MSS, and its window shift or -1 when it offered no scaling.
     */
    TcpConnection(Tcp tcp, HttpSession http, long peerMac, int peerIp, int peerPort, int iss, int irs, int peerWindow,
            int peerMss, int peerShift) {
        this.tcp = tcp;
        this.http = http;
        this.peerMac = peerMac;
        this.peerIp = peerIp;
        this.peerPort = peerPort;
        this.iss = iss;
        this.irs = irs;
        this.rcvNxt = irs + 1;
        this.peerWindow = peerWindow;
        this.mss = Math.min(Tcp.MSS, peerMss);
        this.peerShift = Math.max(peerShift, 0);
        this.windowScaling = peerShift >= 0;
    }

    int seq(long position) {
        return iss + (int) position;
    }

    /** Our FIN's position, or -1 while HTTP may still add output. */
    private long finPosition() {
        return http.closing() ? http.end() + 1 : -1;
    }

    private boolean finAcked() {
        long fin = finPosition();
        return fin >= 0 && sndUna > fin;
    }

    /** Sent and not acknowledged, our SYN and FIN included. */
    private boolean outstanding() {
        return sndMax > sndUna;
    }

    /** The SYN-ACK went out (or was queued to): the SYN takes position 0. */
    void synSent() {
        sndNxt = Math.max(sndNxt, 1);
        sndMax = Math.max(sndMax, 1);
    }

    // ---- receive ----

    void segment(Tcp.Segment s) {
        idleTicks = 0;
        if (s.has(Tcp.RST)) {
            // RST inside the receive window closes; others are ignored (blind resets)
            int d = s.seq() - rcvNxt;
            if (d >= 0 && d < Tcp.RCV_WINDOW) {
                tcp.purge(this);
                closed = true;
                ackDue = false;
            }
            return;
        }
        if (s.has(Tcp.SYN)) {
            if (state == State.SYN_RECEIVED && s.seq() == irs)
                tcp.sendSynAck(this);
            else
                ackDue = true; // a challenge ACK (RFC 5961 4.2): a peer that restarted answers it with RST
            return;
        }
        if (!s.has(ACK))
            return;
        if (state == State.SYN_RECEIVED) {
            if (s.ack() != iss + 1) {
                tcp.sendReset(this, s.ack());
                return;
            }
            state = State.ESTABLISHED;
            sndUna = 1;
            sndNxt = Math.max(sndNxt, 1);
            retries = 0;
            rtoTicks = 0;
            peerWindow = (long) s.window() << peerShift;
        } else {
            long position = sndUna + (s.ack() - seq(sndUna));
            if (position > sndMax) {
                ackDue = true;
                return;
            }
            if (position >= sndUna) {
                if (position > sndUna) {
                    sndUna = position;
                    retries = 0;
                    rtoTicks = 0;
                    http.acked(Math.min(position - 1, http.end()));
                    if (sndNxt < sndUna)
                        sndNxt = sndUna;
                } else if (s.window() == 0) {
                    retries = 0; // the peer answers our probes: it is there, just full
                }
                peerWindow = (long) s.window() << peerShift;
            }
        }
        boolean fin = s.has(FIN);
        int length = s.length();
        if (length == 0 && !fin)
            return;
        int skip = rcvNxt - s.seq();
        ackDue = true;
        if (skip < 0)
            return; // out of order: dropped, the duplicate ACK asks for the gap again
        int taken = 0;
        if (skip < length && !finReceived) {
            // HTTP may take less while its responses wait: the rest is dropped and comes again
            taken = http.received(s.frame(), s.data() + skip, length - skip);
            rcvNxt += taken;
        }
        if (fin && !finReceived && skip + taken == length) {
            rcvNxt++;
            finReceived = true;
            http.inputEnded();
        }
    }

    /** Both FINs sent and acknowledged: done. */
    void checkFinished() {
        if (finReceived && finAcked())
            closed = true;
    }

    // ---- send ----

    /** Sends what the window and the modem's queue allow, then our FIN once the output is done. */
    void pump() {
        if (state != State.ESTABLISHED || closed)
            return;
        long dataEnd = http.end() + 1, fin = finPosition();
        long limit = sndUna + Math.max(peerWindow, probe ? 1 : 0);
        try {
            while (tcp.room()) {
                long n = Math.max(0, Math.min(mss, Math.min(dataEnd - sndNxt, limit - sndNxt)));
                boolean sendFin = fin >= 0 && sndNxt + n == fin;
                if (n == 0 && !sendFin)
                    break;
                int length = (int) n;
                for (int got = 0; got < length; ) {
                    int r = http.read(sndNxt - 1 + got, buf, got, length - got);
                    if (r <= 0)
                        throw new IOException("HTTP output ended early");
                    got += r;
                }
                int flags = ACK | (length > 0 && sndNxt + length == dataEnd ? PSH : 0) | (sendFin ? FIN : 0);
                tcp.send(this, seq(sndNxt), flags, buf, length);
                sndNxt += length + (sendFin ? 1 : 0);
                sndMax = Math.max(sndMax, sndNxt);
                probe = false;
                ackDue = false;
                if (sendFin)
                    break;
            }
        } catch (IOException e) {
            abort();
        }
    }

    /** Gives up: what still waits for the peer is dropped, a RST sent, files closed. */
    void abort() {
        tcp.purge(this);
        tcp.sendReset(this, seq(sndNxt));
        closed = true;
        ackDue = false;
    }

    // ---- timers ----

    /**
     * One tick: the retransmission timer (frozen while {@code backlog}, frames still waiting in
     * the modem because the peer's card refused them), and the idle timeout.
     */
    void tick(boolean backlog) {
        if (closed)
            return;
        idleTicks++;
        boolean zeroWindow = state == State.ESTABLISHED && peerWindow == 0 && sndNxt < http.end() + 1;
        if (!outstanding() && !zeroWindow) {
            rtoTicks = 0;
        } else if (!backlog && ++rtoTicks >= Tcp.RTO_TICKS) {
            rtoTicks = 0;
            if (++retries > Tcp.MAX_RETRIES) {
                abort();
                return;
            }
            tcp.retransmitted();
            sndNxt = sndUna;
            if (state == State.SYN_RECEIVED)
                tcp.sendSynAck(this);
            else
                probe = peerWindow == 0;
        }
        if (idleTicks >= Tcp.IDLE_TICKS) {
            idleTicks = 0;
            if (finAcked())
                closed = true; // the peer never sent its FIN
            else if (!http.closing() && !outstanding())
                http.stop(); // an idle keep-alive connection: close it from our side
        }
    }
}
