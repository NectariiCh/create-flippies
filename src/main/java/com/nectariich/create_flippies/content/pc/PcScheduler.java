package com.nectariich.create_flippies.content.pc;

import java.util.ArrayList;
import java.util.List;
import java.util.function.LongSupplier;

/**
 * Runs every powered PC once per server tick within a host-time budget (spec
 * 11.2.6). Everything is on the server thread; the jobs stay independent so a
 * worker pool can come later.
 *
 * One tick:
 * 1. every job applies its queued host input;
 * 2. the jobs run in round-robin order, each with min(per-PC budget, what is left
 *    of the total). One whose turn comes with nothing left runs with budget 0, so
 *    its emulated time stands still this tick (spec 1.4), and the next tick starts
 *    with it. Otherwise the start rotates by one, so no PC is always last;
 * 3. the exchange phase moves data between linked PCs;
 * 4. every job records its draw and lights.
 *
 * A job that throws (any {@code RuntimeException}, {@code StackOverflowError} or
 * {@code OutOfMemoryError}) crashes alone: it is told, and it is expected to leave
 * the schedule. The others carry on in the same tick.
 */
public final class PcScheduler {

    /** One running PC as the scheduler sees it. */
    public interface Job {

        /** Step 1: keyboard events, plugged devices, and the like. */
        void applyInput();

        /** Step 2: run one tick of emulated time, spending at most about {@code budgetNanos}. */
        void run(long budgetNanos);

        /** Step 4: record what the tick did. */
        void afterTick();

        /** Something this job did threw; it is dropped from the schedule by the caller. */
        void crashed(Throwable error);
    }

    private final LongSupplier nanoTime;
    private final List<Job> jobs = new ArrayList<>();
    private int start;

    public PcScheduler() {
        this(System::nanoTime);
    }

    /** With a clock of the caller's choosing, for tests. */
    public PcScheduler(LongSupplier nanoTime) {
        this.nanoTime = nanoTime;
    }

    public void add(Job job) {
        if (!jobs.contains(job))
            jobs.add(job);
    }

    public void remove(Job job) {
        int index = jobs.indexOf(job);
        if (index < 0)
            return;
        jobs.remove(index);
        if (index < start)
            start--;
        if (start >= jobs.size())
            start = 0;
    }

    public int size() {
        return jobs.size();
    }

    public List<Job> jobs() {
        return List.copyOf(jobs);
    }

    /** The index the next tick starts its round at, for tests. */
    int startIndex() {
        return start;
    }

    /** One server tick: input, the round, the exchange, then the records. */
    public void tick(long perPcNanos, long totalNanos, Runnable exchange) {
        List<Job> round = new ArrayList<>(jobs.size());
        int n = jobs.size();
        for (int i = 0; i < n; i++)
            round.add(jobs.get((start + i) % n));

        for (Job job : round)
            guarded(job, () -> job.applyInput());

        long left = totalNanos;
        Job starved = null;
        for (Job job : round) {
            if (!jobs.contains(job))
                continue;
            long budget = Math.max(0, Math.min(perPcNanos, left));
            if (budget == 0 && starved == null)
                starved = job;
            long before = nanoTime.getAsLong();
            guarded(job, () -> job.run(budget));
            left -= nanoTime.getAsLong() - before;
        }

        if (starved != null && jobs.contains(starved))
            start = jobs.indexOf(starved);
        else if (!jobs.isEmpty())
            start = (start + 1) % jobs.size();

        exchange.run();

        for (Job job : List.copyOf(jobs))
            guarded(job, job::afterTick);
    }

    private void guarded(Job job, Runnable action) {
        try {
            action.run();
        } catch (RuntimeException | StackOverflowError | OutOfMemoryError e) {
            remove(job);
            job.crashed(e);
        }
    }
}
