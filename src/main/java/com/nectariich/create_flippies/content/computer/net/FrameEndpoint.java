package com.nectariich.create_flippies.content.computer.net;

/**
 * One end of an ethernet cord, as the host's exchange phase drives it (spec 12.4.2): a PC's
 * network card or a modem. Frames are Ethernet II without preamble and FCS. Every method is
 * called between ticks, on the thread that runs the endpoint, so PC to PC and PC to modem links
 * share one code path.
 */
public interface FrameEndpoint {

    /** The oldest frame this end sent, exact length, or null when it has nothing to send. */
    byte[] takeOutgoing();

    /**
     * A frame from the other end, {@code length} bytes of {@code frame} from {@code offset}. The
     * endpoint copies what it keeps. False if the frame was dropped (receive queue full, receiver
     * off, not addressed to it, bad length).
     */
    boolean deliver(byte[] frame, int offset, int length);

    /**
     * Whether {@link #deliver} would have room for a frame now. A sender that keeps what does not
     * fit (the modem, 12.4.2) asks first, so a frame it only holds back is not counted as dropped.
     */
    default boolean canReceive() {
        return true;
    }

    /** Cable connected and the other end powered. */
    void setLink(boolean up);
}
