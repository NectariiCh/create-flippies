package com.nectariich.create_flippies.content.computer.cpu.m68k;

import java.io.DataInput;
import java.io.DataOutput;
import java.io.IOException;

/**
 * A stand-in FPU for the core's tests (spec 10.10): every entry point takes the line F exception
 * (vector 11, format 0, stacked PC = the instruction), except that {@link #condition} can be told
 * to answer a fixed value so the core's FBcc, FScc, FDBcc and FTRAPcc can be tested. Records how
 * often each entry point ran.
 */
final class StubFpu implements M68kFpuUnit {

    private final M68kFpuHost host;
    /** Answer of {@link #condition}; null takes the line F exception. */
    Integer conditionResult;
    int lastPredicate = -1;
    int generals;
    int saves;
    int restores;
    int resets;
    int stateMarker = 0x5EED;

    StubFpu(M68kFpuHost host) {
        this.host = host;
    }

    private int lineF() {
        host.exception(M68kVectors.LINE_F, M68kVectors.FORMAT_0, host.instructionAddress(), 0);
        return 0;
    }

    @Override
    public void reset() {
        resets++;
    }

    @Override
    public int general(int opword) {
        generals++;
        return lineF();
    }

    @Override
    public int condition(int predicate) {
        lastPredicate = predicate;
        if (conditionResult == null) {
            lineF();
            return CONDITION_TRAPPED;
        }
        return conditionResult;
    }

    @Override
    public int save(int opword) {
        saves++;
        return lineF();
    }

    @Override
    public int restore(int opword) {
        restores++;
        return lineF();
    }

    @Override
    public void saveState(DataOutput out) throws IOException {
        out.writeInt(1);
        out.writeInt(stateMarker);
    }

    @Override
    public void loadState(DataInput in) throws IOException {
        in.readInt();
        stateMarker = in.readInt();
    }
}
