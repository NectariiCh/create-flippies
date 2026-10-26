package com.nectariich.create_flippies.content.pc;

import com.nectariich.create_flippies.content.computer.device.Uart16550;
import com.nectariich.create_flippies.content.computer.net.FrameEndpoint;
import com.nectariich.create_flippies.content.computer.net.Modem;

/**
 * Moving data between two linked ports in the scheduler's exchange phase (spec
 * 11.2.10). Only machine objects here, no world, so the moves are tested with real
 * machines.
 */
public final class PcLinkExchange {

    /** Bytes buffered each way on one COM cable. */
    public static final int COM_BUFFER = 4096;

    private PcLinkExchange() {}

    /**
     * Ethernet between two powered ends (two PCs, or a PC and a modem, spec 12.4.2):
     * the link is up, and every frame each one sent this tick is delivered to the
     * other (in send order, A's first).
     */
    public static void ethernet(FrameEndpoint a, FrameEndpoint b) {
        a.setLink(true);
        b.setLink(true);
        move(a, b);
        move(b, a);
    }

    /** An end whose cable is missing or whose peer is off or unloaded. */
    public static void ethernetDown(FrameEndpoint end) {
        end.setLink(false);
        // Nothing can be sent while the link is down (the card refuses at send time),
        // so this only clears what was queued in the tick the link went down
        while (end.takeOutgoing() != null) {
        }
    }

    /**
     * A PC to modem link for one tick (spec 12.4.2, 12.4.5): {@code pc} is the PC's card while
     * the PC is loaded and running, else null. The modem is powered over the cord, so with no
     * running PC its link is down; with one, its frames move both ways and its timers run.
     */
    public static void modem(Modem modem, FrameEndpoint pc) {
        if (pc == null) {
            ethernetDown(modem);
            return;
        }
        modem.exchange(pc);
        modem.tick();
    }

    private static void move(FrameEndpoint from, FrameEndpoint to) {
        byte[] frame;
        while ((frame = from.takeOutgoing()) != null)
            to.deliver(frame, 0, frame.length);
    }

    /**
     * One direction of a COM cable's buffer: bytes the sender gave that the receiver
     * has not taken yet. The oldest are dropped past {@link #COM_BUFFER}.
     */
    public static final class ComBuffer {
        private final byte[] data = new byte[COM_BUFFER];
        private int length;

        public int length() {
            return length;
        }

        /** The buffered bytes, oldest first. */
        byte[] contents() {
            return java.util.Arrays.copyOf(data, length);
        }

        /** Takes what {@code from} transmitted, then gives {@code to} what it will accept. */
        void pump(Uart16550 from, Uart16550 to) {
            byte[] chunk = new byte[COM_BUFFER];
            int read;
            while ((read = from.hostRead(chunk, 0, chunk.length)) > 0)
                offer(chunk, read);
            if (length == 0)
                return;
            int taken = to.hostWrite(data, 0, length);
            System.arraycopy(data, taken, data, 0, length - taken);
            length -= taken;
        }

        /** Adds bytes the sender gave, dropping the oldest past {@link #COM_BUFFER}. */
        void offer(byte[] bytes, int count) {
            if (count >= COM_BUFFER) {
                System.arraycopy(bytes, count - COM_BUFFER, data, 0, COM_BUFFER);
                length = COM_BUFFER;
                return;
            }
            int overflow = length + count - COM_BUFFER;
            if (overflow > 0) {
                System.arraycopy(data, overflow, data, 0, length - overflow);
                length -= overflow;
            }
            System.arraycopy(bytes, 0, data, length, count);
            length += count;
        }
    }

    /** A COM cable's two buffers. */
    public static final class ComCable {
        public final ComBuffer aToB = new ComBuffer();
        public final ComBuffer bToA = new ComBuffer();
    }

    /** A COM cable between two UARTs (two PCs, or two ports of one: a loopback cable). */
    public static void com(Uart16550 a, Uart16550 b, ComCable cable) {
        a.setHostConnected(true);
        b.setHostConnected(true);
        cable.aToB.pump(a, b);
        cable.bToA.pump(b, a);
    }
}
