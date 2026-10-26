package com.nectariich.create_flippies.content.plc;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.zip.Deflater;

import com.nectariich.create_flippies.content.plc.mcu.CompiledProgram;
import com.nectariich.create_flippies.content.plc.mcu.HardFault;
import com.nectariich.create_flippies.content.plc.mcu.McuState;
import com.nectariich.create_flippies.content.plc.mcu.PinDrive;
import com.nectariich.create_flippies.content.plc.mcu.PlcCore;
import com.nectariich.create_flippies.foundation.control.HostedCircuitBuilder;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;

import org.junit.jupiter.api.Test;
import org.patryk3211.powergrid.electricity.base.IElectricEntity;
import org.patryk3211.powergrid.electricity.sim.AbstractElectricWire;
import org.patryk3211.powergrid.electricity.sim.node.FloatingNode;
import org.patryk3211.powergrid.electricity.sim.node.INode;

/**
 * What a PLC keeps across saves and syncs, and what it does when that, or the
 * emulator, goes wrong. Every test here is a regression for a review finding:
 *
 * <ul>
 * <li>an exception out of the emulator went through the block entity's tick and
 * stopped the server, and because the sketch stayed saved as flashed it did so again
 * on every load;
 * <li>the sketch was deflated afresh, at the best level, on every sync;
 * <li>a save could carry a sketch three times longer than an upload allows, which
 * then could never be sent to the menu and was booted anyway.
 * </ul>
 *
 * The circuit is built but not solved, so none of this needs Power Grid's solver:
 * every node sits at 0 V and the board is simply unpowered.
 */
class PlcSavedStateTest {

    private static final String SKETCH = "void setup() {}\nvoid loop() {}\n";

    /** A chip that throws on demand. */
    private static final class ThrowingCore implements PlcCore {
        Supplier<Throwable> onRun;
        Supplier<Throwable> onFlash;
        Supplier<Throwable> onReset;
        CompiledProgram program;
        int flashes;

        private static void maybeThrow(Supplier<Throwable> what) {
            if (what == null)
                return;
            Throwable t = what.get();
            if (t instanceof RuntimeException e)
                throw e;
            throw (Error) t;
        }

        public void flash(CompiledProgram p) {
            flashes++;
            if (p != null)
                maybeThrow(onFlash);
            program = p;
        }
        public CompiledProgram program() { return program; }
        public void setPowered(boolean p) {}
        public boolean powered() { return false; }
        public void setResetHeld(boolean h) {}
        public void reset() { maybeThrow(onReset); }
        public void setInput(int pin, boolean high) {}
        public void runFor(long nanos, long budget) { maybeThrow(onRun); }
        public PinDrive pin(int pin) { return PinDrive.OFF; }
        public McuState state() { return program == null ? McuState.NO_PROGRAM : McuState.RUNNING; }
        public HardFault fault() { return null; }
        public int faultLine() { return 0; }
        public double speed() { return 1; }
        public double cpuLoad() { return 0; }
        public double mcuPowerWatts() { return 0; }
        public int stackPeak() { return 0; }
        public int heapPeak() { return 0; }
        public long uptimeMillis() { return 0; }
    }

    private static PlcSmallControl builtPlc(PlcCore core) {
        PlcSmallControl plc = (PlcSmallControl) PlcSmallControlType.DIN.create();
        FloatingNode[] terminals = new FloatingNode[PlcSmallGeometry.TERMINALS];
        for (int i = 0; i < terminals.length; i++)
            terminals[i] = new FloatingNode();
        HostedCircuitBuilder builder = new HostedCircuitBuilder(BlockPos.ZERO, PlcSmallGeometry.TERMINALS,
                i -> terminals[i]);
        plc.buildCircuit(builder);
        List<INode> nodes = new ArrayList<>();
        List<AbstractElectricWire> wires = new ArrayList<>();
        builder.handOver(new IElectricEntity.CircuitBuilder(BlockPos.ZERO, new ArrayList<>(), nodes, wires));
        plc.useCore(core);
        return plc;
    }

    private static CompoundTag flashedTag(String source) {
        CompoundTag tag = new CompoundTag();
        tag.putByteArray("Source", deflate(source));
        tag.putBoolean("Flashed", true);
        return tag;
    }

    /** Ticks until the PLC's compile, if any, has finished and been taken in. */
    private static void tickUntilCompiled(PlcSmallControl plc) throws InterruptedException {
        long deadline = System.nanoTime() + 20_000_000_000L;
        plc.serverTick(null);
        while (plc.compiling()) {
            assertTrue(System.nanoTime() < deadline, "the compile finishes");
            Thread.sleep(5);
            plc.serverTick(null);
        }
    }

    private static CompoundTag written(PlcSmallControl plc) {
        CompoundTag tag = new CompoundTag();
        plc.write(tag);
        return tag;
    }

    // ------------------------------------------------- emulator exceptions ---

    private static void assertBlankedByEmulator(PlcSmallControl plc, ThrowingCore core) {
        assertNull(core.program, "the chip is blank");
        PlcStatus status = plc.status();
        assertTrue(status.notRestored(), "and says so");
        assertTrue(status.restoreError().startsWith("internal emulator error: "), status.restoreError());
        for (int i = 0; i < PlcBoard.OUTPUTS; i++)
            assertEquals(0, plc.duty(i), 0, "output " + i + " is off");
        CompoundTag tag = written(plc);
        assertFalse(tag.getBoolean("Flashed"), "nothing is saved as flashed");
        assertFalse(tag.contains("FlashedSource"));
        assertTrue(tag.contains("Source"), "the editor keeps the text");
        assertTrue(tag.getBoolean("NotRestored"));
    }

    @Test
    void anExceptionFromRunForBlanksThePlcInsteadOfLeavingTheTick() throws InterruptedException {
        ThrowingCore core = new ThrowingCore();
        PlcSmallControl plc = builtPlc(core);
        plc.read(flashedTag(SKETCH));
        tickUntilCompiled(plc);
        assertNotNull(core.program, "booted");

        core.onRun = () -> new IllegalStateException("interpreter bug");
        plc.serverTick(null);
        assertBlankedByEmulator(plc, core);
        assertEquals(SKETCH, plc.source());
    }

    @Test
    void aStackOverflowFromTheEmulatorIsCaughtToo() throws InterruptedException {
        ThrowingCore core = new ThrowingCore();
        PlcSmallControl plc = builtPlc(core);
        plc.read(flashedTag(SKETCH));
        tickUntilCompiled(plc);
        core.onRun = StackOverflowError::new;
        plc.serverTick(null);
        assertBlankedByEmulator(plc, core);
    }

    /**
     * The crash loop: a chip that throws as the saved program goes in used to throw
     * again on every load. Now the first load blanks it, and what that saves boots
     * nothing.
     */
    @Test
    void aFlashThatThrowsAtBootIsNotRetriedAfterAReload() throws InterruptedException {
        ThrowingCore core = new ThrowingCore();
        core.onFlash = () -> new ArrayIndexOutOfBoundsException(7);
        PlcSmallControl plc = builtPlc(core);
        plc.read(flashedTag(SKETCH));
        tickUntilCompiled(plc);
        assertBlankedByEmulator(plc, core);

        ThrowingCore again = new ThrowingCore();
        again.onFlash = core.onFlash;
        PlcSmallControl reloaded = builtPlc(again);
        reloaded.read(written(plc));
        for (int i = 0; i < 3; i++) {
            reloaded.serverTick(null);
            assertFalse(reloaded.compiling(), "nothing is booted after the reload");
        }
        assertNull(again.program);
        assertTrue(reloaded.status().notRestored(), "and the reason survives it");
    }

    @Test
    void anUploadTheChipThrowsOnIsAFailedUpload() throws InterruptedException {
        ThrowingCore core = new ThrowingCore();
        core.onFlash = () -> new IllegalArgumentException("bad image");
        PlcSmallControl plc = builtPlc(core);
        plc.serverTick(null);
        assertTrue(plc.upload(SKETCH, true, UUID.randomUUID()));
        tickUntilCompiled(plc);
        assertBlankedByEmulator(plc, core);
    }

    @Test
    void aResetThatThrowsBlanksThePlc() throws InterruptedException {
        ThrowingCore core = new ThrowingCore();
        PlcSmallControl plc = builtPlc(core);
        plc.read(flashedTag(SKETCH));
        tickUntilCompiled(plc);
        core.onReset = () -> new NullPointerException("constructor");
        plc.pressReset();
        assertBlankedByEmulator(plc, core);
    }

    @Test
    void aGoodUploadClearsTheNotice() throws InterruptedException {
        ThrowingCore core = new ThrowingCore();
        PlcSmallControl plc = builtPlc(core);
        plc.read(flashedTag(SKETCH));
        tickUntilCompiled(plc);
        core.onRun = () -> new IllegalStateException("once");
        plc.serverTick(null);
        core.onRun = null;
        assertTrue(plc.upload(SKETCH, true, UUID.randomUUID()));
        tickUntilCompiled(plc);
        assertNotNull(core.program);
        assertFalse(plc.status().notRestored());
        assertTrue(written(plc).getBoolean("Flashed"));
    }

    // ------------------------------------------------------- deflate cache ---

    @Test
    void theSketchIsDeflatedOncePerEditNotOncePerWrite() {
        PlcSmallControl plc = (PlcSmallControl) PlcSmallControlType.DIN.create();
        plc.upload(SKETCH, false, UUID.randomUUID());
        byte[] first = written(plc).getByteArray("Source");
        for (int i = 0; i < 5; i++)
            assertArrayEquals(first, written(plc).getByteArray("Source"));
        assertEquals(1, plc.deflations(), "five more writes, no more deflates");

        plc.upload(SKETCH + "// edited\n", false, UUID.randomUUID());
        written(plc);
        written(plc);
        assertEquals(2, plc.deflations(), "an edit deflates once");
    }

    @Test
    void aTagReadBackIsWrittenWithoutDeflatingAgain() {
        PlcSmallControl plc = (PlcSmallControl) PlcSmallControlType.DIN.create();
        CompoundTag tag = new CompoundTag();
        tag.putByteArray("Source", deflate(SKETCH));
        tag.putByteArray("FlashedSource", deflate("void setup(){}void loop(){}"));
        plc.read(tag);
        CompoundTag out = written(plc);
        assertEquals(0, plc.deflations());
        assertArrayEquals(tag.getByteArray("Source"), out.getByteArray("Source"));
        assertArrayEquals(tag.getByteArray("FlashedSource"), out.getByteArray("FlashedSource"));
    }

    @Test
    void writtenArraysAreNotTheCachesOwn() {
        PlcSmallControl plc = (PlcSmallControl) PlcSmallControlType.DIN.create();
        plc.upload(SKETCH, false, UUID.randomUUID());
        byte[] first = written(plc).getByteArray("Source");
        byte[] copy = first.clone();
        first[0] ^= 0x55;
        assertArrayEquals(copy, written(plc).getByteArray("Source"), "scribbling on a tag does not reach the cache");
    }

    // ----------------------------------------------------- oversized saves ---

    @Test
    void anOversizedSavedSketchIsClippedInTheEditorAndNotBooted() throws InterruptedException {
        String huge = "// " + "x".repeat(PlcToolchain.MAX_SOURCE_CHARS * 2) + "\n" + SKETCH;
        ThrowingCore core = new ThrowingCore();
        PlcSmallControl plc = builtPlc(core);
        plc.read(flashedTag(huge));
        assertEquals(PlcToolchain.MAX_SOURCE_CHARS, plc.source().length(), "the editor keeps what fits");
        assertTrue(huge.startsWith(plc.source()));
        assertTrue(plc.status().notRestored());
        assertTrue(plc.status().restoreError().contains(String.valueOf(PlcToolchain.MAX_SOURCE_CHARS)));
        plc.serverTick(null);
        assertFalse(plc.compiling(), "the oversized program is not compiled at boot");
        assertNull(core.program);
        CompoundTag out = written(plc);
        assertFalse(out.getBoolean("Flashed"));
        assertFalse(out.contains("FlashedSource"));
    }

    @Test
    void anOversizedFlashedSourceAloneIsDropped() {
        PlcSmallControl plc = (PlcSmallControl) PlcSmallControlType.DIN.create();
        CompoundTag tag = new CompoundTag();
        tag.putByteArray("Source", deflate(SKETCH));
        tag.putByteArray("FlashedSource", deflate("y".repeat(PlcToolchain.MAX_SOURCE_CHARS + 1)));
        plc.read(tag);
        assertEquals(SKETCH, plc.source(), "the editor's own text is untouched");
        assertTrue(plc.status().notRestored());
        assertFalse(written(plc).contains("FlashedSource"));
    }

    @Test
    void aSketchAtTheLimitIsKept() {
        String atLimit = "z".repeat(PlcToolchain.MAX_SOURCE_CHARS);
        PlcSmallControl plc = (PlcSmallControl) PlcSmallControlType.DIN.create();
        plc.read(flashedTag(atLimit));
        assertEquals(atLimit, plc.source());
        assertFalse(plc.status().notRestored());
        assertTrue(written(plc).getBoolean("Flashed"));
    }

    @Test
    void clippingNeverSplitsASurrogatePair() {
        String text = "a".repeat(PlcToolchain.MAX_SOURCE_CHARS - 1) + "😀" + "tail";
        String clipped = PlcSmallControl.clipSource(text);
        assertEquals(PlcToolchain.MAX_SOURCE_CHARS - 1, clipped.length());
        assertFalse(Character.isHighSurrogate(clipped.charAt(clipped.length() - 1)));
        assertEquals("short", PlcSmallControl.clipSource("short"));
    }

    private static byte[] deflate(String text) {
        Deflater deflater = new Deflater();
        try {
            deflater.setInput(text.getBytes(StandardCharsets.UTF_8));
            deflater.finish();
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] chunk = new byte[4096];
            while (!deflater.finished())
                out.write(chunk, 0, deflater.deflate(chunk));
            return out.toByteArray();
        } finally {
            deflater.end();
        }
    }
}
