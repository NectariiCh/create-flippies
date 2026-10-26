package com.nectariich.create_flippies.content.plc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.util.ArrayList;
import java.util.List;

import com.nectariich.create_flippies.content.plc.mcu.CompiledProgram;
import com.nectariich.create_flippies.content.plc.mcu.HardFault;
import com.nectariich.create_flippies.content.plc.mcu.McuState;
import com.nectariich.create_flippies.content.plc.mcu.PinDrive;
import com.nectariich.create_flippies.content.plc.mcu.PlcCore;
import com.nectariich.create_flippies.foundation.control.HostedCircuitBuilder;

import net.minecraft.core.BlockPos;

import org.junit.jupiter.api.Test;
import org.patryk3211.powergrid.electricity.base.IElectricEntity;
import org.patryk3211.powergrid.electricity.sim.AbstractElectricWire;
import org.patryk3211.powergrid.electricity.sim.ElectricWire;
import org.patryk3211.powergrid.electricity.sim.ElectricalNetwork;
import org.patryk3211.powergrid.electricity.sim.node.FloatingNode;
import org.patryk3211.powergrid.electricity.sim.node.INode;
import org.patryk3211.powergrid.electricity.sim.node.VoltageSourceCoupling;

/**
 * The PLC's outputs against Power Grid's real solver, with a chip that does what it is
 * told: what a load actually carries when an output is switched faster than the tick.
 *
 * Regression for the averaged output stage. It used to be R_on / duty, which against
 * any real load is still next to nothing, so a quarter duty into 48 ohm carried
 * 24 / 48.48 = 0.495 A — 99 % of full on — and a 1.5 A load at a third duty never
 * tripped the 1 A limit. The tests here read the current off the solved wires.
 *
 * Power Grid's solver needs EJML, which the mod jar carries inside itself rather than
 * declaring, so it is not on a test classpath unless the build puts it there
 * ({@code testRuntimeOnly 'org.ejml:ejml-simple:0.44.0'}); without it these tests
 * are skipped rather than failed.
 */
class PlcCircuitTest {

    private static final int DO0 = PlcSmallGeometry.TERMINAL_DO0;

    /** A chip that does what it is told. */
    private static final class FakeCore implements PlcCore {
        boolean powered, resetHeld;
        final PinDrive[] pins = new PinDrive[35];
        CompiledProgram program;

        public void flash(CompiledProgram p) { program = p; }
        public CompiledProgram program() { return program; }
        public void setPowered(boolean p) { powered = p; }
        public boolean powered() { return powered; }
        public void setResetHeld(boolean h) { resetHeld = h; }
        public void reset() {}
        public void setInput(int pin, boolean high) {}
        public void runFor(long nanos, long budget) {}
        public PinDrive pin(int pin) { return pins[pin] == null ? PinDrive.OFF : pins[pin]; }
        public McuState state() { return !powered ? McuState.UNPOWERED : resetHeld ? McuState.IN_RESET : McuState.RUNNING; }
        public HardFault fault() { return null; }
        public int faultLine() { return 0; }
        public double speed() { return 1; }
        public double cpuLoad() { return 0.5; }
        public double mcuPowerWatts() { return powered ? 0.1 : 0; }
        public int stackPeak() { return 0; }
        public int heapPeak() { return 0; }
        public long uptimeMillis() { return 0; }
    }

    /** A PLC on a 24 V supply with a load to GND on some outputs. */
    private static final class Bench {
        final PlcSmallControl plc = (PlcSmallControl) PlcSmallControlType.DIN.create();
        final FakeCore core = new FakeCore();
        final FloatingNode[] terminals = new FloatingNode[PlcSmallGeometry.TERMINALS];
        final List<AbstractElectricWire> wires = new ArrayList<>();
        final ElectricalNetwork network = new ElectricalNetwork(false);

        Bench(double... loads) {
            assumeTrue(solverAvailable(), "Power Grid's solver needs EJML on the test classpath");
            for (int i = 0; i < terminals.length; i++)
                terminals[i] = new FloatingNode();
            HostedCircuitBuilder builder = new HostedCircuitBuilder(BlockPos.ZERO, PlcSmallGeometry.TERMINALS,
                    i -> terminals[i]);
            plc.buildCircuit(builder);
            List<INode> nodes = new ArrayList<>();
            builder.handOver(new IElectricEntity.CircuitBuilder(BlockPos.ZERO, new ArrayList<>(), nodes, wires));

            FloatingNode vcc = terminals[PlcSmallGeometry.TERMINAL_VCC];
            FloatingNode gnd = terminals[PlcSmallGeometry.TERMINAL_GND];
            for (FloatingNode node : terminals)
                network.addNode(node);
            network.addNode(new VoltageSourceCoupling(vcc, gnd, 0.05f, 24f));
            FloatingNode earth = new FloatingNode();
            network.addNode(earth);
            for (AbstractElectricWire wire : wires)
                network.addWire(wire);
            network.addWire(new ElectricWire(0.001, gnd, earth));
            for (int i = 0; i < loads.length; i++)
                if (loads[i] > 0)
                    network.addWire(new ElectricWire(loads[i], terminals[DO0 + i], gnd));
            plc.useCore(core);
        }

        void drive(int output, double duty) {
            core.pins[8 + output] = new PinDrive(true, true, duty, duty > 0 && duty < 1 ? 1000 : 0);
        }

        void ticks(int n) {
            for (int i = 0; i < n; i++) {
                network.calculate(1);
                plc.serverTick(null);
            }
            network.calculate(1);
        }

        /** The current through output {@code i}'s switch on the last solve. */
        double amps(int output) {
            // The logic load, eight inputs and RESET come first; see PlcSmallControl
            return Math.abs(((ElectricWire) wires.get(10 + output)).current());
        }

        double supply() {
            return terminals[PlcSmallGeometry.TERMINAL_VCC].getVoltage()
                    - terminals[PlcSmallGeometry.TERMINAL_GND].getVoltage();
        }
    }

    private static boolean solverAvailable() {
        try {
            Class.forName("org.ejml.data.DMatrixD1", false, PlcCircuitTest.class.getClassLoader());
            return true;
        } catch (ClassNotFoundException e) {
            return false;
        }
    }

    @Test
    void quarterDutyIntoFortyEightOhmCarriesAQuarterOfTheCurrent() {
        Bench bench = new Bench(48, 48);
        bench.drive(0, 1);
        bench.drive(1, 0.25);
        bench.ticks(4);
        double full = bench.supply() / (48 + PlcBoard.OUTPUT_ON_OHMS);
        assertEquals(full, bench.amps(0), 0.005, "DO0 fully on");
        assertEquals(0.25 * full, bench.amps(1), 0.003, "DO1 at 25 %: about 0.124 A, not 0.495");
        assertEquals(0.25, bench.plc.duty(1), 1e-9);
    }

    @Test
    void dutySweepTracksTheAverage() {
        for (double duty : new double[] { 0.01, 0.1, 0.5, 0.75 }) {
            Bench bench = new Bench(24);
            bench.drive(0, duty);
            bench.ticks(4);
            double full = bench.supply() / (24 + PlcBoard.OUTPUT_ON_OHMS);
            assertEquals(duty * full, bench.amps(0), 0.004 + 0.01 * duty * full, "duty " + duty);
        }
    }

    @Test
    void pwmIntoAnOverloadStillTrips() {
        // 16 ohm is 1.5 A on every pulse; at a third duty the wire averages 0.5 A
        Bench bench = new Bench(16, 48);
        bench.drive(0, 1.0 / 3);
        bench.drive(1, 1.0 / 3);
        bench.ticks(4);
        assertTrue(bench.plc.board().faulted(PlcBoard.CH_DO0), "DO0 latched on its on-state current");
        assertEquals(0, bench.plc.duty(0), 1e-9, "and forced off");
        assertFalse(bench.plc.board().faulted(PlcBoard.CH_DO0 + 1), "0.5 A pulses are within the limit");
    }

    @Test
    void menuPowerIsTheDutysShare() {
        Bench bench = new Bench(48);
        bench.drive(0, 0.25);
        bench.ticks(5);
        PlcBoard.Power power = bench.plc.status().power();
        double on = bench.supply() / (48 + PlcBoard.OUTPUT_ON_OHMS);
        assertEquals(0.25 * on * on * 48, power.delivered(), 0.05 * 0.25 * on * on * 48, "delivered to the load");
        assertEquals(0.25 * on * on * PlcBoard.OUTPUT_ON_OHMS, power.driverLoss(), 1e-4, "switch loss");
    }
}
