package com.nectariich.create_flippies.content.computer.net;

import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.function.Predicate;

/**
 * The PC's end of the cord for tests without a real card: frames from {@link TestClient#outbox}
 * go out, frames for it wait in a receive queue of {@link #capacity} frames (a full one refuses,
 * as {@code Nic} does) until {@link #process} hands them to the client. {@link #lose} drops
 * frames on the wire (accepted, never seen).
 */
final class ClientEndpoint implements FrameEndpoint {

    final TestClient client;
    final ArrayDeque<byte[]> rx = new ArrayDeque<>();
    int capacity = Integer.MAX_VALUE;
    Predicate<byte[]> lose = f -> false;
    int refused, lost;

    ClientEndpoint(TestClient client) {
        this.client = client;
    }

    @Override
    public byte[] takeOutgoing() {
        return client.outbox.isEmpty() ? null : client.outbox.remove(0);
    }

    @Override
    public boolean deliver(byte[] frame, int offset, int length) {
        if (rx.size() >= capacity) {
            refused++;
            return false;
        }
        byte[] f = Arrays.copyOfRange(frame, offset, offset + length);
        NetFrames.assertChecksums(f);
        if (lose.test(f)) {
            lost++;
            return true;
        }
        rx.add(f);
        return true;
    }

    @Override
    public boolean canReceive() {
        if (rx.size() < capacity)
            return true;
        refused++;
        return false;
    }

    @Override
    public void setLink(boolean up) {
    }

    /** The client takes up to {@code n} queued frames, then acknowledges what it got. */
    void process(int n) {
        for (int i = 0; i < n && !rx.isEmpty(); i++)
            client.receive(rx.poll());
        client.flushAck();
    }

    /** One server tick: the exchange, the modem's tick, then the client runs and takes up to {@code n} frames. */
    void step(Modem modem, int n) {
        modem.exchange(this);
        modem.tick();
        process(n);
    }
}
