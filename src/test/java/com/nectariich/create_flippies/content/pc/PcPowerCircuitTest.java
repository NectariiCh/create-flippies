package com.nectariich.create_flippies.content.pc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.core.BlockPos;

import org.junit.jupiter.api.Test;
import org.patryk3211.powergrid.electricity.base.IElectricEntity;
import org.patryk3211.powergrid.electricity.sim.AbstractElectricWire;
import org.patryk3211.powergrid.electricity.sim.ElectricalNetwork;
import org.patryk3211.powergrid.electricity.sim.node.INode;
import org.patryk3211.powergrid.electricity.sim.node.OwnedFloatingNode;
import org.patryk3211.powergrid.electricity.sim.node.VoltageSourceCoupling;

/**
 * The case's two-terminal load against Power Grid's real solver (docs/PC_SPEC.md
 * 11.4): a source across the two socket terminals, nothing grounded anywhere, draws
 * V^2 / R, and reversing it changes nothing but the sign. Needs EJML on the test
 * classpath, as {@code PlcCircuitTest} explains; skipped without it.
 */
class PcPowerCircuitTest {

    private static boolean solverAvailable() {
        try {
            Class.forName("org.ejml.data.DMatrixD1", false, PcPowerCircuitTest.class.getClassLoader());
            return true;
        } catch (ClassNotFoundException e) {
            return false;
        }
    }

    private static double[] solve(double sourceVolts, double ohms) {
        assumeTrue(solverAvailable(), "Power Grid's solver needs EJML on the test classpath");
        List<OwnedFloatingNode> terminals = new ArrayList<>();
        List<INode> nodes = new ArrayList<>();
        List<AbstractElectricWire> wires = new ArrayList<>();
        PcPower.Circuit circuit = PcPower.buildCircuit(
                new IElectricEntity.CircuitBuilder(BlockPos.ZERO, terminals, nodes, wires));
        circuit.load().setResistance(ohms);
        ElectricalNetwork network = new ElectricalNetwork(true);
        for (OwnedFloatingNode terminal : terminals)
            network.addNode(terminal);
        for (INode node : nodes)
            network.addNode(node);
        for (AbstractElectricWire wire : wires)
            network.addWire(wire);
        VoltageSourceCoupling source = new VoltageSourceCoupling(circuit.a(), circuit.b(), 0.01f, (float) sourceVolts);
        network.addNode(source);
        network.calculate(1);
        return new double[] {circuit.volts(), Math.abs(source.getCurrent())};
    }

    @Test
    void drawsVSquaredOverRWithNoGround() {
        double ohms = PcPower.loadOhms(230, 50);
        double[] r = solve(230, ohms);
        assertEquals(230, r[0], 0.5);
        assertEquals(50, r[0] * r[1], 0.5);
    }

    @Test
    void reversedPolarityGivesTheSameMagnitude() {
        double ohms = PcPower.loadOhms(230, 50);
        double[] forward = solve(230, ohms);
        double[] reverse = solve(-230, ohms);
        assertEquals(forward[0], reverse[0], 1e-3);
        assertEquals(forward[1], reverse[1], 1e-3);
    }

    @Test
    void unpoweredIsTheLeak() {
        double[] r = solve(230, PcPower.LEAK_OHMS);
        assertEquals(230 / PcPower.LEAK_OHMS, r[1], 1e-6);
    }
}
