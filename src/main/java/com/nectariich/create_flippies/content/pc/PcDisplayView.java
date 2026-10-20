package com.nectariich.create_flippies.content.pc;

import java.util.BitSet;

import com.nectariich.create_flippies.content.computer.device.VgaDisplay;
import com.nectariich.create_flippies.content.computer.device.VgaFrame;
import com.nectariich.create_flippies.content.crt.CrtStream;

/**
 * One of a running PC's three displays, as a monitor reads it (spec 11.2.10,
 * 11.2.14). Only the server thread calls it, between scheduler ticks.
 */
public final class PcDisplayView implements CrtStream.Source {

    private final VgaDisplay display;

    PcDisplayView(VgaDisplay display) {
        this.display = display;
    }

    /** The display itself: the same object for as long as the machine lives. */
    @Override
    public Object identity() {
        return display;
    }

    /** Changes whenever anything visible changed; a monitor syncs when it moves. */
    @Override
    public long generation() {
        return display.generation();
    }

    @Override
    public int mode() {
        return display.mode();
    }

    @Override
    public int width() {
        return display.width();
    }

    @Override
    public int height() {
        return display.height();
    }

    @Override
    public int pitch() {
        return display.pitch();
    }

    @Override
    public int bitsPerPixel() {
        return display.bitsPerPixel();
    }

    @Override
    public int ctrl() {
        return display.ctrl();
    }

    /** The CURSOR register (reading it has no side effect). */
    @Override
    public int cursor() {
        return display.read(VgaDisplay.CURSOR, 4);
    }

    @Override
    public int palette(int index) {
        return display.palette(index);
    }

    /** Mode, palette, cursor and the visible bytes, for a full frame. */
    public VgaFrame snapshot() {
        return display.snapshot();
    }

    /** The rows changed since {@code generation}, for an incremental frame. */
    @Override
    public BitSet dirtyRowsSince(long generation) {
        return display.dirtyRowsSince(generation);
    }

    /** Copies the visible bytes into {@code dst}; returns how many. */
    @Override
    public int copyVisibleInto(byte[] dst) {
        return display.copyVisibleInto(dst);
    }
}
