package com.nectariich.create_flippies.content.pc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

/** The scheduler with fake machines and a fake clock (docs/PC_SPEC.md 11.4). */
class PcSchedulerTest {

    /** A clock the fake jobs advance by the time they "spend". */
    private static final class Clock {
        long now;
    }

    private static final class FakePc implements PcScheduler.Job {
        final String name;
        final Clock clock;
        final List<String> log;
        final long spends;
        RuntimeException throwOnRun;
        Error errorOnRun;
        final List<Long> budgets = new ArrayList<>();
        Throwable crash;
        int afterTicks;

        FakePc(String name, Clock clock, List<String> log, long spends) {
            this.name = name;
            this.clock = clock;
            this.log = log;
            this.spends = spends;
        }

        @Override
        public void applyInput() {}

        @Override
        public void run(long budgetNanos) {
            budgets.add(budgetNanos);
            log.add(name);
            if (throwOnRun != null)
                throw throwOnRun;
            if (errorOnRun != null)
                throw errorOnRun;
            clock.now += Math.min(spends, budgetNanos);
        }

        @Override
        public void afterTick() {
            afterTicks++;
        }

        @Override
        public void crashed(Throwable error) {
            crash = error;
        }
    }

    @Test
    void budgetSplitAndTotalCap() {
        Clock clock = new Clock();
        List<String> log = new ArrayList<>();
        PcScheduler scheduler = new PcScheduler(() -> clock.now);
        FakePc a = new FakePc("a", clock, log, 2_000_000), b = new FakePc("b", clock, log, 2_000_000),
                c = new FakePc("c", clock, log, 2_000_000);
        scheduler.add(a);
        scheduler.add(b);
        scheduler.add(c);
        // 2 ms each, 5 ms in all: the third gets what is left
        scheduler.tick(2_000_000, 5_000_000, () -> {});
        assertEquals(2_000_000L, a.budgets.get(0));
        assertEquals(2_000_000L, b.budgets.get(0));
        assertEquals(1_000_000L, c.budgets.get(0));
    }

    @Test
    void budgetZeroWhenExhaustedAndAnEarlyTurnNext() {
        Clock clock = new Clock();
        List<String> log = new ArrayList<>();
        PcScheduler scheduler = new PcScheduler(() -> clock.now);
        FakePc a = new FakePc("a", clock, log, 3_000_000), b = new FakePc("b", clock, log, 3_000_000),
                c = new FakePc("c", clock, log, 3_000_000);
        scheduler.add(a);
        scheduler.add(b);
        scheduler.add(c);
        scheduler.tick(3_000_000, 4_000_000, () -> {});
        assertEquals(3_000_000L, a.budgets.get(0));
        assertEquals(1_000_000L, b.budgets.get(0));
        assertEquals(0L, c.budgets.get(0), "nothing left: its time stands still");
        log.clear();
        scheduler.tick(3_000_000, 4_000_000, () -> {});
        assertEquals("c", log.get(0), "the starved PC goes first next tick");
    }

    @Test
    void roundRobinRotation() {
        Clock clock = new Clock();
        List<String> log = new ArrayList<>();
        PcScheduler scheduler = new PcScheduler(() -> clock.now);
        for (String name : new String[] {"a", "b", "c"})
            scheduler.add(new FakePc(name, clock, log, 1000));
        List<String> firsts = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            log.clear();
            scheduler.tick(2_000_000, 20_000_000, () -> {});
            firsts.add(log.get(0));
        }
        assertEquals(List.of("a", "b", "c"), firsts);
    }

    @Test
    void aThrowingMachineCrashesOnlyItself() {
        Clock clock = new Clock();
        List<String> log = new ArrayList<>();
        PcScheduler scheduler = new PcScheduler(() -> clock.now);
        FakePc good = new FakePc("good", clock, log, 1000), bad = new FakePc("bad", clock, log, 1000),
                worse = new FakePc("worse", clock, log, 1000), other = new FakePc("other", clock, log, 1000);
        bad.throwOnRun = new IllegalStateException("emulator bug");
        worse.errorOnRun = new StackOverflowError();
        scheduler.add(good);
        scheduler.add(bad);
        scheduler.add(worse);
        scheduler.add(other);
        List<String> exchanged = new ArrayList<>();
        scheduler.tick(2_000_000, 20_000_000, () -> exchanged.add("x"));
        assertSame(bad.throwOnRun, bad.crash);
        assertTrue(worse.crash instanceof StackOverflowError);
        assertEquals(2, scheduler.size());
        assertEquals(List.of("x"), exchanged);
        assertEquals(1, good.afterTicks);
        assertEquals(1, other.afterTicks);
        assertEquals(0, bad.afterTicks);
        assertTrue(log.containsAll(List.of("good", "other")));
    }
}
