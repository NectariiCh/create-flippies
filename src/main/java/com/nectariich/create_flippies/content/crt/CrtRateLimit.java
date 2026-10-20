package com.nectariich.create_flippies.content.crt;

/**
 * The two limits on picture traffic (spec 11.2.14). Pure, so both are tested.
 *
 * - Frames: one CRT sends a given player at most {@code maxFps} updates a second,
 *   so at least {@link #intervalTicks} ticks apart. Changes in between are not lost:
 *   they pile up in the display's row generations and go in the next update.
 * - Bytes: each player has a {@link Bucket} filled by the per-tick allowance and
 *   emptied by what is sent. An update may overdraw it (a frame is never split),
 *   and nothing more is sent to that player until it is back above zero, so over
 *   any stretch a player receives the allowance per tick on average, plus at most
 *   one update's overdraw.
 */
public final class CrtRateLimit {

    /** Server ticks per second. */
    public static final int TICKS_PER_SECOND = 20;
    /** How many ticks of allowance a quiet player may save up. */
    public static final int BURST_TICKS = 4;

    private CrtRateLimit() {}

    /** The fewest ticks between two updates of one CRT to one player. */
    public static int intervalTicks(int maxFps) {
        int fps = Math.max(1, Math.min(TICKS_PER_SECOND, maxFps));
        return (TICKS_PER_SECOND + fps - 1) / fps;
    }

    /** Whether an update sent at {@code lastTick} allows another at {@code now}. */
    public static boolean due(long lastTick, long now, int intervalTicks) {
        return now - lastTick >= intervalTicks;
    }

    /** One player's byte allowance. */
    public static final class Bucket {
        private long credit;

        /** A new tick: the allowance comes in, up to {@link #BURST_TICKS} ticks' worth. */
        public void refill(int bytesPerTick) {
            credit = Math.min(credit + bytesPerTick, (long) bytesPerTick * BURST_TICKS);
        }

        public boolean canSend() {
            return credit > 0;
        }

        public void spend(int bytes) {
            credit -= bytes;
        }

        public long credit() {
            return credit;
        }
    }
}
