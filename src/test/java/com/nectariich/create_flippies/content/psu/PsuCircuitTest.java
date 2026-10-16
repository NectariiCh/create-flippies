package com.nectariich.create_flippies.content.psu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import com.nectariich.create_flippies.foundation.control.HostedCircuitBuilder;

import net.minecraft.core.BlockPos;

import org.junit.jupiter.api.Test;
import org.patryk3211.powergrid.electricity.base.IElectricEntity;
import org.patryk3211.powergrid.electricity.sim.AbstractElectricWire;
import org.patryk3211.powergrid.electricity.sim.ElectricWire;
import org.patryk3211.powergrid.electricity.sim.ElectricalNetwork;
import org.patryk3211.powergrid.electricity.sim.node.FloatingNode;
import org.patryk3211.powergrid.electricity.sim.node.ICouplingNode;
import org.patryk3211.powergrid.electricity.sim.node.IElectricNode;
import org.patryk3211.powergrid.electricity.sim.node.INode;
import org.patryk3211.powergrid.electricity.sim.node.VoltageSourceCoupling;

/**
 * The supply against Power Grid's real solver: a 230 V source on its input, loads
 * on its output, and {@link PsuControl#serverTick()} between solves as the game
 * runs it.
 *
 * The input and output are solved as two networks, because that is what they are
 * in the world: Power Grid's {@code ElectricBehaviour.tracedAdd} joins a terminal
 * to a network by following only the internal wires and couplings that touch it,
 * and its island discovery groups by the same connectivity, so with nothing in the
 * supply joining the two sides each lands in a network of its own. That matters
 * beyond tidiness: two islands with no common node in one {@code ElectricalNetwork}
 * leave the solver without a reference for one of them, and it solves the whole
 * network to zero. That the elements split cleanly at all is the isolation test.
 *
 * Needs EJML on the test classpath, as {@code PlcCircuitTest} explains; skipped
 * without it.
 */
class PsuCircuitTest {

    private static final PsuType TYPE = PsuType.of(230, 24, 100, 3);

    /** A supply between an input network and an output network. */
    private static final class Bench {
        final PsuControl psu = (PsuControl) TYPE.create();
        final FloatingNode[] terminals = new FloatingNode[PsuGeometry.TERMINALS];
        final ElectricalNetwork input = new ElectricalNetwork(true);
        final ElectricalNetwork output;
        final VoltageSourceCoupling mains;
        ElectricWire load;

        Bench(double mainsVolts, double loadOhms) {
            this(mainsVolts, loadOhms, new ElectricalNetwork(true));
        }

        /** A supply whose output side goes into {@code output}, which other benches may share. */
        Bench(double mainsVolts, double loadOhms, ElectricalNetwork output) {
            this.output = output;
            assumeTrue(solverAvailable(), "Power Grid's solver needs EJML on the test classpath");
            for (int i = 0; i < terminals.length; i++)
                terminals[i] = new FloatingNode();
            HostedCircuitBuilder builder = new HostedCircuitBuilder(BlockPos.ZERO, PsuGeometry.TERMINALS,
                    i -> terminals[i]);
            psu.buildCircuit(builder);
            List<INode> nodes = new ArrayList<>();
            List<AbstractElectricWire> wires = new ArrayList<>();
            builder.handOver(new IElectricEntity.CircuitBuilder(BlockPos.ZERO, new ArrayList<>(), nodes, wires));

            Set<IElectricNode> inputSide = Set.of(terminals[PsuGeometry.TERMINAL_VCC],
                    terminals[PsuGeometry.TERMINAL_GND]);
            for (int i = 0; i < terminals.length; i++)
                (inputSide.contains(terminals[i]) ? input : output).addNode(terminals[i]);
            // Everything the supply builds internally is on its output side
            for (INode node : nodes) {
                if (node instanceof ICouplingNode coupling)
                    for (IElectricNode coupled : coupling.coupledNodes())
                        assertFalse(inputSide.contains(coupled), "a coupling reaches the input: " + coupling);
                output.addNode(node);
            }
            for (AbstractElectricWire wire : wires) {
                boolean in1 = inputSide.contains(wire.getNode1());
                boolean in2 = inputSide.contains(wire.getNode2());
                assertEquals(in1, in2, "a wire joins the input to the output: " + wire);
                (in1 ? input : output).addWire(wire);
            }

            mains = new VoltageSourceCoupling(terminals[PsuGeometry.TERMINAL_VCC],
                    terminals[PsuGeometry.TERMINAL_GND], 0.01f, (float) mainsVolts);
            input.addNode(mains);
            if (loadOhms > 0) {
                load = new ElectricWire(loadOhms, vPlus(), vMinus());
                output.addWire(load);
            }
        }

        FloatingNode vPlus() {
            return terminals[PsuGeometry.TERMINAL_V_PLUS];
        }

        FloatingNode vMinus() {
            return terminals[PsuGeometry.TERMINAL_V_MINUS];
        }

        FloatingNode dcOk() {
            return terminals[PsuGeometry.TERMINAL_DC_OK];
        }

        void ticks(int n) {
            for (int i = 0; i < n; i++) {
                solve();
                psu.serverTick();
            }
            solve();
        }

        void solve() {
            input.calculate(1);
            output.calculate(1);
        }

        double outputVolts() {
            return vPlus().getVoltage() - vMinus().getVoltage();
        }

        double inputWatts() {
            // The current through the mains source, times its voltage
            return Math.abs(mains.getCurrent() * mains.getVoltage());
        }
    }

    private static boolean solverAvailable() {
        try {
            Class.forName("org.ejml.data.DMatrixD1", false, PsuCircuitTest.class.getClassLoader());
            return true;
        } catch (ClassNotFoundException e) {
            return false;
        }
    }

    /** V+ on {@code ohms} behind one running supply: 24 V less its 0.05 ohm droop. */
    private static double rail(double ohms) {
        return 24 * ohms / (ohms + PsuRegulator.OUTPUT_OHMS + PsuControl.SOURCE_OHMS);
    }

    @Test
    void twentyFourVoltsIntoARatedLoad() {
        Bench bench = new Bench(230, 6);
        bench.ticks(40);
        assertEquals(PsuRegulator.State.RUNNING, bench.psu.regulator().state());
        assertEquals(rail(6), bench.outputVolts(), 0.01);
        assertEquals(rail(6) / 6, bench.psu.outputAmps(), 0.01, "positive when sourcing");
        assertTrue(bench.psu.dcOkClosed());
    }

    @Test
    void theInputDrawsTheOutputOverItsEfficiency() {
        Bench bench = new Bench(230, 6);
        bench.ticks(40);
        double delivered = rail(6) * rail(6) / 6;
        double expected = delivered / PsuRegulator.efficiency(delivered / 100) + PsuRegulator.NO_LOAD_WATTS;
        assertEquals(expected, bench.inputWatts(), expected * 0.01);
    }

    @Test
    void dcOkDrivesAnInputToVPlus() {
        Bench bench = new Bench(230, 24);
        // A PLC input's 6.8 kilohm from DC OK to V-
        bench.output.addWire(new ElectricWire(6800, bench.dcOk(), bench.vMinus()));
        bench.ticks(40);
        assertEquals(bench.outputVolts(), bench.dcOk().getVoltage() - bench.vMinus().getVoltage(), 0.05);
        assertTrue(bench.outputVolts() > 23.9);
    }

    @Test
    void aShortedDcOkLatchesOpen() {
        Bench bench = new Bench(230, 24);
        bench.ticks(40);
        assertTrue(bench.psu.dcOkClosed());
        bench.output.addWire(new ElectricWire(0.01, bench.dcOk(), bench.vMinus()));
        bench.ticks(3);
        assertFalse(bench.psu.dcOkClosed());
        assertTrue(bench.psu.regulator().dcOkLatched());
        // The tick the short was on overloaded the output too; once it has ramped
        // back, the output itself carries on
        bench.ticks(40);
        assertFalse(bench.psu.dcOkClosed());
        assertEquals(rail(24), bench.outputVolts(), 0.01, "the output itself is unaffected");
    }

    @Test
    void anOverloadIsHeldAtTheLimit() {
        Bench bench = new Bench(230, 2);
        bench.ticks(40);
        assertEquals(PsuRegulator.State.OVERLOAD, bench.psu.regulator().state());
        assertEquals(1.1 * 100 / 24, bench.psu.outputAmps(), 0.02);
        assertFalse(bench.psu.dcOkClosed());
    }

    @Test
    void aShortHiccups() {
        Bench bench = new Bench(230, 0.001);
        bench.ticks(3);
        assertEquals(PsuRegulator.State.HICCUP, bench.psu.regulator().state());
        assertFalse(bench.psu.outputClosed());
    }

    @Test
    void noInputLeavesTheOutputOpenNotShorted() {
        // A stopped supply with 24 V pushed onto its output through 1 ohm: the
        // output must not take it, as a 0 V source would
        Bench bench = new Bench(0, 0);
        FloatingNode other = new FloatingNode();
        bench.output.addNode(other);
        bench.output.addNode(new VoltageSourceCoupling(other, bench.vMinus(), 0.01f, 24f));
        ElectricWire feed = new ElectricWire(1, other, bench.vPlus());
        bench.output.addWire(feed);
        bench.ticks(4);
        assertEquals(PsuRegulator.State.OFF, bench.psu.regulator().state());
        assertFalse(bench.psu.outputClosed());
        assertEquals(0, feed.current(), 1e-5, "nothing flows into a stopped supply");
        assertEquals(24, bench.outputVolts(), 0.01);
        assertEquals(PsuRegulator.OPEN_OHMS, bench.psu.inputOhms(), 1e-6);
    }

    @Test
    void twoRunningSuppliesInParallelShareWithoutCirculating() {
        // B on the same rail as A, wired V+ to V+ and V- to V-, into 3 ohm: 8 A,
        // more than either one's limit. B is switched on 5 ticks after A
        ElectricalNetwork rail = new ElectricalNetwork(true);
        Bench a = new Bench(230, 3, rail);
        Bench b = new Bench(0, 0, rail);
        rail.addWire(new ElectricWire(0.001, a.vPlus(), b.vPlus()));
        rail.addWire(new ElectricWire(0.001, a.vMinus(), b.vMinus()));
        double limit = a.psu.regulator().limitAmps();
        double lastA = 0;
        double lastB = 0;
        for (int t = 0; t < 60; t++) {
            if (t == 5)
                b.mains.setVoltage(230);
            a.psu.serverTick();
            b.psu.serverTick();
            a.input.calculate(1);
            b.input.calculate(1);
            rail.calculate(1);
            double ia = a.psu.outputAmps();
            double ib = b.psu.outputAmps();
            assertTrue(ia >= -0.05 && ib >= -0.05, "neither sinks: " + ia + " / " + ib + " at tick " + t);
            if (t > 12) {
                assertTrue(ia <= 1.2 * limit && ib <= 1.2 * limit, ia + " / " + ib + " A at tick " + t);
                assertTrue(Math.abs(ia - lastA) < limit && Math.abs(ib - lastB) < limit,
                        "no swing between ticks at " + t);
            }
            lastA = ia;
            lastB = ib;
        }
        assertEquals(24, a.outputVolts(), 0.25, "within the droop of two supplies sharing 8 A");
        assertEquals(a.outputVolts() / 3, a.psu.outputAmps() + b.psu.outputAmps(), 0.05);
        // B's share is behind the two milliohms of interconnect as well, 4 % more
        // than A's 0.05 ohm, and the droop shares by exactly that
        assertEquals(a.psu.outputAmps(), b.psu.outputAmps(), 0.25, "and sharing it");
    }

    @Test
    void aRailHeldAboveTheRatingIsNotSunk() {
        // A 26 V source through 0.1 ohm onto a running supply's output
        Bench bench = new Bench(230, 0);
        FloatingNode other = new FloatingNode();
        bench.output.addNode(other);
        bench.output.addNode(new VoltageSourceCoupling(other, bench.vMinus(), 0.01f, 26f));
        ElectricWire feed = new ElectricWire(0.1, other, bench.vPlus());
        bench.output.addWire(feed);
        for (int t = 0; t < 40; t++) {
            bench.ticks(1);
            assertTrue(feed.current() < 0.05, "at most a trickle into V+: " + feed.current() + " A at tick " + t);
        }
        assertTrue(bench.psu.regulator().running());
        assertFalse(bench.psu.outputClosed(), "the output diode holds it off");
        assertEquals(26, bench.outputVolts(), 0.01);
    }

    @Test
    void anInputBelowTheWindowNeverStarts() {
        Bench bench = new Bench(170, 24);
        bench.ticks(40);
        assertEquals(PsuRegulator.State.INPUT_LOW, bench.psu.regulator().state());
        assertEquals(0, bench.outputVolts(), 0.01);
    }
}
