package com.nectariich.create_flippies.content.computer.machine;

import com.nectariich.create_flippies.content.computer.bus.Bus;
import com.nectariich.create_flippies.content.computer.bus.CycleClock;
import com.nectariich.create_flippies.content.computer.cpu.Cpu;
import com.nectariich.create_flippies.content.computer.cpu.CpuHost;
import com.nectariich.create_flippies.content.computer.cpu.CpuModel;

import java.util.ArrayList;
import java.util.List;

/**
 * A stand-in CPU that follows the {@link Cpu} contract exactly but runs a scripted program: step
 * i is a Java action, each costing {@link #cost} cycles, and past the end it spins on no-ops. It
 * publishes the instruction's start cycle before each step (so device accesses see the right
 * "now"), re-reads the horizon after each step, checks the deadline every
 * {@link #DEADLINE_STRIDE} steps, and records what it did for the machine tests.
 */
final class FakeCpu implements Cpu {

    static final int DEADLINE_STRIDE = 16;

    /** One scripted instruction. */
    @FunctionalInterface
    interface Step {
        void run(FakeCpu cpu, Bus bus);
    }

    final CpuHost host;
    final List<Step> program;
    final int cost;
    final List<Long> runUntil = new ArrayList<>();
    final List<Long> runReturnedAt = new ArrayList<>();
    final List<Integer> executed = new ArrayList<>();
    final List<Long> executedAt = new ArrayList<>();
    final List<String> pinLog = new ArrayList<>();
    final boolean[] pins = new boolean[32];
    int resets;

    private int pc;
    private long retired;
    private boolean waiting;
    private boolean halted;

    FakeCpu(CpuHost host, List<Step> program, int cost) {
        this.host = host;
        this.program = program;
        this.cost = cost;
    }

    // ---- actions for steps ----

    void waitForInterrupt() {
        waiting = true;
    }

    void halt() {
        halted = true;
    }

    long now() {
        return host.clock().now();
    }

    boolean anyPin() {
        for (boolean p : pins)
            if (p)
                return true;
        return false;
    }

    // ---- Cpu ----

    @Override
    public CpuModel model() {
        return host.model();
    }

    @Override
    public void reset() {
        resets++;
        pc = 0;
        waiting = false;
        halted = false;
    }

    @Override
    public void saveState(java.io.DataOutput out) {
        throw new UnsupportedOperationException("fake CPU has no saved state");
    }

    @Override
    public void loadState(java.io.DataInput in) {
        throw new UnsupportedOperationException("fake CPU has no saved state");
    }

    @Override
    public Exit run(long untilCycle, long hostDeadlineNanos) {
        CycleClock clock = host.clock();
        clock.setHorizon(untilCycle);
        runUntil.add(untilCycle);
        long cycle = clock.now();
        try {
            if (halted)
                return Exit.HALTED;
            if (waiting) {
                if (!anyPin())
                    return Exit.WAITING;
                waiting = false;
            }
            int countdown = DEADLINE_STRIDE;
            while (true) {
                if (cycle >= clock.horizon())
                    return Exit.REACHED;
                if (--countdown == 0) {
                    countdown = DEADLINE_STRIDE;
                    if (System.nanoTime() - hostDeadlineNanos > 0)
                        return Exit.DEADLINE;
                }
                clock.advanceTo(cycle);
                int at = pc++;
                executed.add(at);
                executedAt.add(cycle);
                if (at < program.size())
                    program.get(at).run(this, host.memory());
                cycle += cost;
                retired++;
                if (halted)
                    return Exit.HALTED;
                if (waiting)
                    return Exit.WAITING;
            }
        } finally {
            clock.advanceTo(cycle);
            runReturnedAt.add(clock.now());
        }
    }

    @Override
    public void setIrq(int pin, boolean level) {
        if (pins[pin] != level)
            pinLog.add(pin + (level ? "+" : "-") + "@" + host.clock().now());
        pins[pin] = level;
    }

    @Override
    public boolean halted() {
        return halted;
    }

    @Override
    public String haltReason() {
        return halted ? "fake halt" : null;
    }

    @Override
    public int pc() {
        return pc;
    }

    @Override
    public long instructionsRetired() {
        return retired;
    }
}
