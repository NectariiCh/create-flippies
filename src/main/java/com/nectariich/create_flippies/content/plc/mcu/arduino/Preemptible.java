package com.nectariich.create_flippies.content.plc.mcu.arduino;

import com.nectariich.create_flippies.content.plc.mcu.Blocker;
import com.nectariich.create_flippies.content.plc.mcu.CycleClass;
import com.nectariich.create_flippies.content.plc.mcu.CycleLedger;
import com.nectariich.create_flippies.content.plc.mcu.Invocation;
import com.nectariich.create_flippies.content.plc.mcu.McuEnvironment;
import com.nectariich.create_flippies.content.plc.mcu.Memory;
import com.nectariich.create_flippies.content.plc.mcu.chip.Stm32f103;

/**
 * Long builtins that interrupts can preempt, as newlib's code can on the chip.
 *
 * A builtin does its work in one Java call and charges the cycles that work takes. For
 * memset over 12 KB, a printf with a huge width or analogRead's conversion wait that
 * is thousands of cycles charged in one go, and nothing can happen inside it: an EXTI
 * edge applied at the next window start, or a SysTick due halfway, waits for the whole
 * call — up to a millisecond late. On the chip those are ordinary loops (or a polled
 * wait), and an interrupt is taken 12 cycles after it pends.
 *
 * So a wrapped body runs with its charges collected rather than applied. A call that
 * comes to no more than {@link #THRESHOLD} cycles is charged at once, exactly as
 * before. A longer one has done its memory effect (the whole of it, at the start:
 * an ISR that looks at the buffer mid-copy sees it finished, the one liberty taken)
 * and then parks on a {@link Blocker} that charges the cycles in pieces, each ending
 * at the chip's {@link Stm32f103#segmentEnd() current stop}. The CPU gets control back
 * between pieces, so pending interrupts are taken there and the window loop's events
 * (SysTick, tone updates, the window's end) land at their own cycle. The pieces keep
 * the call's cycle classes in proportion, so the power model and the CPU load see the
 * same mix as before; the blocker never idles, so nothing becomes WAIT.
 *
 * Only bodies that neither read the clock nor touch a pin after their first charge
 * should be wrapped: their charges move to after the body has run, which is harmless
 * for pure computation and would misplace a pin write in time.
 */
final class Preemptible {

    /**
     * Calls up to this many cycles (about 28 µs) are charged in one go, as every
     * builtin is: interrupt latency that small is the order of a long instruction
     * sequence with interrupts briefly masked, and parking costs host time.
     */
    static final long THRESHOLD = 2000;

    /** The piece charged when no window-loop stop is known (a call during boot). */
    private static final long FALLBACK_PIECE = Stm32f103.CYCLES_PER_MS;

    private static final CycleClass[] CLASSES = CycleClass.values();

    static Fn.Body wrap(Fn.Body body) {
        return c -> run(c, body);
    }

    static void run(Invocation c, Fn.Body body) {
        Deferred d = new Deferred(c);
        try {
            body.run(d);
        } catch (RuntimeException e) {
            d.chargeAllNow(); // a fault partway: what ran before it is spent
            throw e;
        }
        if (d.blocked)
            return;
        if (d.total <= THRESHOLD) {
            d.chargeAllNow();
            d.replayReturn(c);
            return;
        }
        c.block(new Pieces(d));
    }

    /** Charges what is left of a long call, a piece per poll, each ending at the chip's next stop. */
    private static final class Pieces implements Blocker {
        private final Deferred d;

        Pieces(Deferred d) {
            this.d = d;
        }

        @Override
        public long nextPollCycle() {
            return d.chip().now();
        }

        @Override
        public boolean poll(Invocation call) {
            Stm32f103 chip = d.chip();
            long now = chip.now();
            long room = chip.segmentEnd() - now;
            long piece = Math.min(d.total, room > 0 ? room : FALLBACK_PIECE);
            d.chargePiece(call, piece);
            if (d.total > 0)
                return false;
            d.replayReturn(call);
            return true;
        }
    }

    /**
     * The call as the body sees it: arguments, memory and the environment are the
     * real ones; charges are collected by class and the return value is held, to be
     * applied by {@link Preemptible}.
     */
    private static final class Deferred implements Invocation {
        private final Invocation call;
        final long[] cycles = new long[CLASSES.length];
        long total;
        boolean blocked;

        private int returnKind; // 0 none, 1 integer, 2 float, 3 double
        private long returnBits;

        Deferred(Invocation call) {
            this.call = call;
        }

        Stm32f103 chip() {
            return (Stm32f103) call.environment();
        }

        void chargeAllNow() {
            for (int k = 0; k < cycles.length; k++) {
                if (cycles[k] > 0)
                    call.charge(CLASSES[k], cycles[k]);
                cycles[k] = 0;
            }
            total = 0;
        }

        /**
         * Charges {@code piece} of what is left, split over the classes in proportion
         * to what each has left (largest remainders first), so every piece has the
         * call's mix and the pieces add up to it exactly.
         */
        void chargePiece(Invocation to, long piece) {
            if (piece >= total) {
                for (int k = 0; k < cycles.length; k++) {
                    if (cycles[k] > 0)
                        to.charge(CLASSES[k], cycles[k]);
                    cycles[k] = 0;
                }
                total = 0;
                return;
            }
            long[] share = new long[cycles.length];
            long given = 0;
            for (int k = 0; k < cycles.length; k++) {
                share[k] = Math.min(cycles[k], (long) ((double) cycles[k] / total * piece));
                given += share[k];
            }
            // the double quotient can round up; take back from the classes given most
            while (given > piece) {
                int best = 0;
                for (int k = 1; k < cycles.length; k++)
                    if (share[k] > share[best])
                        best = k;
                share[best]--;
                given--;
            }
            // and hand out what rounding down left over to the classes with the most left
            while (given < piece) {
                int best = -1;
                for (int k = 0; k < cycles.length; k++)
                    if (cycles[k] - share[k] > 0 && (best < 0 || cycles[k] - share[k] > cycles[best] - share[best]))
                        best = k;
                if (best < 0)
                    break;
                share[best]++;
                given++;
            }
            for (int k = 0; k < cycles.length; k++) {
                if (share[k] > 0) {
                    to.charge(CLASSES[k], share[k]);
                    cycles[k] -= share[k];
                    total -= share[k];
                }
            }
        }

        void replayReturn(Invocation to) {
            switch (returnKind) {
                case 1 -> to.returnValue(returnBits);
                case 2 -> to.returnFloat(Float.intBitsToFloat((int) returnBits));
                case 3 -> to.returnDouble(Double.longBitsToDouble(returnBits));
                default -> { }
            }
        }

        @Override
        public void charge(CycleClass cls, long n) {
            if (n <= 0)
                return;
            cycles[cls.ordinal()] += n;
            total += n;
        }

        /** A body that blocks after all is not pure computation: settle up and let it. */
        @Override
        public void block(Blocker blocker) {
            chargeAllNow();
            blocked = true;
            call.block(blocker);
        }

        @Override
        public void returnValue(long value) {
            returnKind = 1;
            returnBits = value;
        }

        @Override
        public void returnFloat(float value) {
            returnKind = 2;
            returnBits = Float.floatToRawIntBits(value);
        }

        @Override
        public void returnDouble(double value) {
            returnKind = 3;
            returnBits = Double.doubleToRawLongBits(value);
        }

        @Override public int argCount() { return call.argCount(); }
        @Override public long arg(int index) { return call.arg(index); }
        @Override public float argFloat(int index) { return call.argFloat(index); }
        @Override public double argDouble(int index) { return call.argDouble(index); }
        @Override public Memory memory() { return call.memory(); }
        @Override public CycleLedger ledger() { return call.ledger(); }
        @Override public McuEnvironment environment() { return call.environment(); }
        @Override public void noteTimeRead(long resolutionCycles) { call.noteTimeRead(resolutionCycles); }
        @Override public void noteSideEffect() { call.noteSideEffect(); }
        @Override public void setInterruptsEnabled(boolean enabled) { call.setInterruptsEnabled(enabled); }
        @Override public boolean interruptsEnabled() { return call.interruptsEnabled(); }
        @Override public int sourceLine() { return call.sourceLine(); }
    }

    private Preemptible() {}
}
