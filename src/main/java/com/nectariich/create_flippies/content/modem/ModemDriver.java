package com.nectariich.create_flippies.content.modem;

import java.util.function.Consumer;
import java.util.function.Supplier;

import com.nectariich.create_flippies.content.computer.net.FrameEndpoint;
import com.nectariich.create_flippies.content.computer.net.Modem;
import com.nectariich.create_flippies.content.pc.PcLinkExchange;

/**
 * A network modem block's server side without the game (spec 12.4.5): the {@link Modem}, made
 * when a running PC first drives it, the tick's exchange with that PC, a failure in the modem's
 * stack, and the lights. {@link NetworkModemBlockEntity} owns one and syncs the lights.
 *
 * A throw from the modem stops this modem alone: it is dropped, its link and the PC's go down, and
 * a fresh one is made after {@link #RETRY_TICKS}. A failure is reported at most once per
 * {@link #QUIET_TICKS}. A throw from the PC's card is the PC's: it is rethrown as it came, so the
 * PC crashes as it would on a PC to PC link.
 */
public final class ModemDriver {

    /** Ticks the activity light keeps blinking after the last frame. */
    static final int ACTIVITY_TICKS = 6;
    /** Ticks a failed modem stays off before a fresh one is made (5 s). */
    static final int RETRY_TICKS = 100;
    /** Ticks after a reported failure in which further ones are not reported (5 min). */
    static final int QUIET_TICKS = 6000;

    private final Supplier<Modem> factory;
    private final Consumer<Throwable> report;
    private final Card card = new Card();

    private Modem modem;
    private long lastFrames;
    private long activityUntil = Long.MIN_VALUE;
    private long retryAt = Long.MIN_VALUE;
    private long quietUntil = Long.MIN_VALUE;
    private boolean powered;
    private boolean active;

    /** {@code factory} makes the modem (it is called inside the failure handling); {@code report} logs a failure. */
    public ModemDriver(Supplier<Modem> factory, Consumer<Throwable> report) {
        this.factory = factory;
        this.report = report;
    }

    /**
     * One tick of the link at game time {@code now}: {@code pc} is the linked PC's card while that
     * PC is loaded and running, else null (no power, the link goes down). True when a light changed.
     */
    public boolean exchange(FrameEndpoint pc, long now) {
        if (modem == null && (pc == null || now < retryAt))
            return lights(false, false);
        card.pc = pc;
        try {
            if (modem == null)
                modem = factory.get();
            PcLinkExchange.modem(modem, pc == null ? null : card);
        } catch (CardFault fault) {
            throw fault.cause;
        } catch (RuntimeException | StackOverflowError e) {
            if (now >= quietUntil) {
                report.accept(e);
                quietUntil = now + QUIET_TICKS;
            }
            retryAt = now + RETRY_TICKS;
            close();
            if (pc != null)
                PcLinkExchange.ethernetDown(pc);
            return lights(false, false);
        } finally {
            card.pc = null;
        }
        long frames = modem.framesIn() + modem.framesOut();
        if (frames != lastFrames)
            activityUntil = now + ACTIVITY_TICKS;
        lastFrames = frames;
        return lights(pc != null, modem.link() && now < activityUntil);
    }

    /**
     * Unloaded, broken or failed: the modem goes with its link taken down first, so its TCP
     * connections and the files they serve are closed now rather than when it is collected.
     */
    public void close() {
        Modem m = modem;
        modem = null;
        if (m == null)
            return;
        try {
            m.setLink(false);
        } catch (RuntimeException e) {
            // A modem that just failed may fail again here; it is dropped either way
        }
    }

    private boolean lights(boolean powered, boolean active) {
        if (powered == this.powered && active == this.active)
            return false;
        this.powered = powered;
        this.active = active;
        return true;
    }

    /** Power, and link with it: the link is up exactly while a running PC powers the modem. */
    public boolean powered() {
        return powered;
    }

    /** Frames moved in the last few ticks: the link light blinks. */
    public boolean active() {
        return active;
    }

    /** The modem itself, for tests and diagnostics; null until a PC first drove it, and after a failure. */
    public Modem modem() {
        return modem;
    }

    /** A throw from the PC's card, carried out past the modem's failure handling. */
    private static final class CardFault extends RuntimeException {
        final RuntimeException cause;

        CardFault(RuntimeException cause) {
            super(null, cause, false, false);
            this.cause = cause;
        }
    }

    /** The PC's card as the modem sees it: what it throws is marked as the card's. */
    private static final class Card implements FrameEndpoint {
        FrameEndpoint pc;

        @Override
        public byte[] takeOutgoing() {
            try {
                return pc.takeOutgoing();
            } catch (RuntimeException e) {
                throw new CardFault(e);
            }
        }

        @Override
        public boolean deliver(byte[] frame, int offset, int length) {
            try {
                return pc.deliver(frame, offset, length);
            } catch (RuntimeException e) {
                throw new CardFault(e);
            }
        }

        @Override
        public boolean canReceive() {
            try {
                return pc.canReceive();
            } catch (RuntimeException e) {
                throw new CardFault(e);
            }
        }

        @Override
        public void setLink(boolean up) {
            try {
                pc.setLink(up);
            } catch (RuntimeException e) {
                throw new CardFault(e);
            }
        }
    }
}
