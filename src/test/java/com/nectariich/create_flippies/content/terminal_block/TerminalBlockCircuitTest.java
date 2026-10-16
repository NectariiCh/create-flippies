package com.nectariich.create_flippies.content.terminal_block;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

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

/** Terminal blocks against Power Grid's real solver. Needs EJML, as PsuCircuitTest does. */
class TerminalBlockCircuitTest {

    private static final class Bench {
        final TerminalBlockControl block;
        final FloatingNode[] t;
        final List<INode> nodes = new ArrayList<>();
        final List<AbstractElectricWire> wires = new ArrayList<>();

        Bench(String id) {
            TerminalBlockType type = TerminalBlockType.of(id);
            block = (TerminalBlockControl) type.create();
            t = new FloatingNode[type.terminalCount()];
            for (int i = 0; i < t.length; i++)
                t[i] = new FloatingNode();
            HostedCircuitBuilder builder = new HostedCircuitBuilder(BlockPos.ZERO, t.length, i -> t[i]);
            block.buildCircuit(builder);
            builder.handOver(new IElectricEntity.CircuitBuilder(BlockPos.ZERO, new ArrayList<>(), nodes, wires));
        }

        /** Which group each node ends up in, by following the block's own wires. */
        Map<INode, Integer> groupOf() {
            Map<INode, Integer> groups = new HashMap<>();
            List<TerminalBlockType.Pin> pins = block.terminalBlock().pins();
            for (int i = 0; i < t.length; i++)
                groups.put(t[i], pins.get(i).group());
            boolean grew = true;
            while (grew) {
                grew = false;
                for (AbstractElectricWire wire : wires) {
                    Integer a = groups.get(wire.getNode1());
                    Integer b = groups.get(wire.getNode2());
                    if (a != null && b != null)
                        assertEquals(a, b, "a wire joins two groups");
                    else if (a != null)
                        grew |= groups.put(wire.getNode2(), a) == null;
                    else if (b != null)
                        grew |= groups.put(wire.getNode1(), b) == null;
                }
            }
            return groups;
        }
    }

    private static boolean solverAvailable() {
        try {
            Class.forName("org.ejml.data.DMatrixD1", false, TerminalBlockCircuitTest.class.getClassLoader());
            return true;
        } catch (ClassNotFoundException e) {
            return false;
        }
    }

    @Test
    void groupsNeverTouch() {
        for (TerminalBlockType type : TerminalBlockType.all()) {
            Bench bench = new Bench(type.id());
            assertEquals(type.groups(), bench.nodes.size(), type.id() + ": one bus bar per group");
            assertEquals(type.terminalCount(), bench.wires.size(), type.id() + ": one clamp per pin");
            Map<INode, Integer> groups = bench.groupOf();
            for (INode bus : bench.nodes)
                assertNotNull(groups.get(bus), type.id() + ": a bus bar no pin reaches");
        }
    }

    @Test
    void onlyGroundBlocksOfferTheRailABus() {
        for (TerminalBlockType type : TerminalBlockType.all()) {
            Bench bench = new Bench(type.id());
            if (type.ground())
                assertNotNull(bench.block.groundBus(), type.id());
            else
                assertNull(bench.block.groundBus(), type.id());
        }
    }

    @Test
    void aGroupCarriesTheLoadAndEachPinReadsItsShare() {
        assumeTrue(solverAvailable(), "Power Grid's solver needs EJML on the test classpath");
        // 230 V into G1A of a 6p2g, a 23 ohm load out of G1C, both back to a neutral
        Bench bench = new Bench("din_terminal_block_6p2g");
        ElectricalNetwork network = new ElectricalNetwork(true);
        for (FloatingNode node : bench.t)
            network.addNode(node);
        bench.nodes.forEach(network::addNode);
        bench.wires.forEach(network::addWire);
        FloatingNode neutral = new FloatingNode();
        network.addNode(neutral);
        network.addNode(new VoltageSourceCoupling(bench.t[0], neutral, 0.01f, 230f));
        ElectricWire load = new ElectricWire(23, bench.t[2], neutral);
        network.addWire(load);
        network.calculate(1);

        double amps = Math.abs(load.current());
        assertEquals(10, amps, 0.01);
        assertEquals(amps, bench.block.pinAmps(0), 0.01);
        assertEquals(amps, bench.block.pinAmps(2), 0.01);
        assertEquals(0, bench.block.pinAmps(1), 0.01);
        assertEquals(amps, bench.block.groupAmps(0), 0.01);
        assertEquals(0, bench.block.groupAmps(1), 1e-9);
    }
}
