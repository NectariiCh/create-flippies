package com.nectariich.create_flippies.content.relay;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import com.nectariich.create_flippies.foundation.control.HostedCircuitBuilder;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;

import org.junit.jupiter.api.Test;
import org.patryk3211.powergrid.electricity.base.IElectricEntity;
import org.patryk3211.powergrid.electricity.sim.AbstractElectricWire;
import org.patryk3211.powergrid.electricity.sim.ElectricWire;
import org.patryk3211.powergrid.electricity.sim.ElectricalNetwork;
import org.patryk3211.powergrid.electricity.sim.node.FloatingNode;
import org.patryk3211.powergrid.electricity.sim.node.INode;
import org.patryk3211.powergrid.electricity.sim.node.VoltageSourceCoupling;

/**
 * The mounts and their relays against Power Grid's real solver: a supply on the
 * input, and per pole a 230 V source on C with one load on NO and one on NC,
 * solved as two networks because nothing in a mount joins A1/A2 to the contacts.
 * Needs EJML, as PsuCircuitTest does.
 */
class RelayMountCircuitTest {

    private static final RelayType SSR = RelayType.of("ss_relay_230v_10a");
    private static final RelayType EM8 = RelayType.of("em_relay_8pin_c24v_230v_16a");

    private static final class Bench {
        final RelayMountControl mount;
        final FloatingNode[] t;
        final ElectricalNetwork input = new ElectricalNetwork(true);
        final ElectricalNetwork load = new ElectricalNetwork(true);
        final FloatingNode neutral = new FloatingNode();
        final VoltageSourceCoupling coilSupply;
        final ElectricWire[] noLamps;
        final ElectricWire[] ncLamps;

        Bench(RelayMountType type, double loadOhms) {
            assumeTrue(solverAvailable(), "Power Grid's solver needs EJML on the test classpath");
            mount = (RelayMountControl) type.create();
            RelayMountGeometry g = type.geometry();
            t = new FloatingNode[g.terminals()];
            for (int i = 0; i < t.length; i++)
                t[i] = new FloatingNode();
            HostedCircuitBuilder builder = new HostedCircuitBuilder(BlockPos.ZERO, t.length, i -> t[i]);
            mount.buildCircuit(builder);
            List<INode> nodes = new ArrayList<>();
            List<AbstractElectricWire> wires = new ArrayList<>();
            builder.handOver(new IElectricEntity.CircuitBuilder(BlockPos.ZERO, new ArrayList<>(), nodes, wires));
            assertTrue(nodes.isEmpty(), "the mount has no internal nodes");
            assertEquals(1 + 2 * g.poles(), wires.size(), "a coil and two contacts a pole");

            Set<FloatingNode> coilSide = Set.of(t[RelayMountGeometry.TERMINAL_A1], t[RelayMountGeometry.TERMINAL_A2]);
            for (FloatingNode node : t)
                (coilSide.contains(node) ? input : load).addNode(node);
            for (AbstractElectricWire wire : wires) {
                boolean in1 = coilSide.contains(wire.getNode1());
                assertEquals(in1, coilSide.contains(wire.getNode2()), "a wire joins the coil to the contacts");
                (in1 ? input : load).addWire(wire);
            }
            coilSupply = new VoltageSourceCoupling(t[RelayMountGeometry.TERMINAL_A1],
                    t[RelayMountGeometry.TERMINAL_A2], 0.01f, 0f);
            input.addNode(coilSupply);

            load.addNode(neutral);
            noLamps = new ElectricWire[g.poles()];
            ncLamps = new ElectricWire[g.poles()];
            for (int pole = 0; pole < g.poles(); pole++) {
                load.addNode(new VoltageSourceCoupling(t[RelayMountGeometry.common(pole)], neutral, 0.01f, 230f));
                noLamps[pole] = new ElectricWire(loadOhms, t[RelayMountGeometry.no(pole)], neutral);
                ncLamps[pole] = new ElectricWire(loadOhms, t[RelayMountGeometry.nc(pole)], neutral);
                load.addWire(noLamps[pole]);
                load.addWire(ncLamps[pole]);
            }
        }

        void coil(double volts) {
            coilSupply.setVoltage((float) volts);
        }

        void ticks(int n) {
            for (int i = 0; i < n; i++) {
                solve();
                mount.serverTick();
            }
            solve();
        }

        void solve() {
            input.calculate(1);
            load.calculate(1);
        }

        double noAmps(int pole) {
            return Math.abs(noLamps[pole].current());
        }

        double ncAmps(int pole) {
            return Math.abs(ncLamps[pole].current());
        }
    }

    private static boolean solverAvailable() {
        try {
            Class.forName("org.ejml.data.DMatrixD1", false, RelayMountCircuitTest.class.getClassLoader());
            return true;
        } catch (ClassNotFoundException e) {
            return false;
        }
    }

    @Test
    void theRelaysAreRegistered() {
        assertNotNull(SSR);
        assertEquals(5, SSR.pins());
        assertEquals(24, SSR.coilVolts());
        assertEquals(10, SSR.ratedAmps());
        assertFalse(SSR.changeover());

        assertNotNull(EM8);
        assertEquals(8, EM8.pins());
        assertEquals(24, EM8.coilVolts());
        assertEquals(230, EM8.loadVolts());
        assertEquals(16, EM8.ratedAmps());
        assertTrue(EM8.changeover());
        assertFalse(EM8.polarised());
    }

    @Test
    void decisionThresholds() {
        assertFalse(RelayMountControl.decide(SSR, false, 17.9));
        assertTrue(RelayMountControl.decide(SSR, false, 18));
        assertTrue(RelayMountControl.decide(SSR, true, 2.5));
        assertFalse(RelayMountControl.decide(SSR, true, 2.4));
        assertFalse(RelayMountControl.decide(SSR, false, -24), "polarised input");
        assertTrue(RelayMountControl.decide(EM8, false, -24), "a coil does not care which way round");
        assertFalse(RelayMountControl.decide(null, true, 24), "nothing fitted");
    }

    @Test
    void aRelayFitsOnlyItsOwnSocket() {
        RelayMountControl five = (RelayMountControl) RelayMountType.FIVE_PIN.create();
        RelayMountControl eight = (RelayMountControl) RelayMountType.EIGHT_PIN.create();
        assertTrue(five.fits(SSR));
        assertFalse(five.fits(EM8));
        assertTrue(eight.fits(EM8));
        assertFalse(eight.fits(SSR));
    }

    @Test
    void anEmptyMountSwitchesNothing() {
        for (RelayMountType type : RelayMountType.all()) {
            Bench bench = new Bench(type, 23);
            bench.coil(24);
            bench.ticks(5);
            assertFalse(bench.mount.energised());
            assertEquals(0, bench.noAmps(0), 1e-3);
            assertEquals(0, bench.ncAmps(0), 1e-3);
            assertEquals(0, bench.coilSupply.getCurrent(), 1e-5, "no relay, no input load");
        }
    }

    @Test
    void twentyFourVoltsOnTheSsrLightsTheLoad() {
        Bench bench = new Bench(RelayMountType.FIVE_PIN, 23);
        bench.mount.fit(SSR);
        bench.ticks(3);
        assertEquals(0, bench.noAmps(0), 1e-3, "off with no input");

        bench.coil(24);
        bench.ticks(3);
        assertTrue(bench.mount.energised());
        assertEquals(230 / (23 + SSR.onOhms()), bench.noAmps(0), 0.05);
        assertEquals(bench.noAmps(0), bench.mount.loadAmps(), 0.05);
        assertEquals(0.010, Math.abs(bench.coilSupply.getCurrent()), 1e-3, "10 mA input");
        assertEquals(0, bench.ncAmps(0), 1e-3, "an SSR has no NC");

        bench.coil(0);
        bench.ticks(3);
        assertFalse(bench.mount.energised());
        assertEquals(0, bench.noAmps(0), 1e-3);
    }

    @Test
    void theEightPinRelaySwitchesBothPolesOver() {
        Bench bench = new Bench(RelayMountType.EIGHT_PIN, 23);
        bench.mount.fit(EM8);
        bench.ticks(3);
        for (int pole = 0; pole < 2; pole++) {
            assertEquals(0, bench.noAmps(pole), 1e-3, "pole " + pole + " NO open at rest");
            assertEquals(230 / (23 + EM8.onOhms()), bench.ncAmps(pole), 0.05, "pole " + pole + " NC made at rest");
        }

        bench.coil(24);
        bench.ticks(3);
        assertTrue(bench.mount.energised());
        for (int pole = 0; pole < 2; pole++) {
            assertEquals(230 / (23 + EM8.onOhms()), bench.noAmps(pole), 0.05, "pole " + pole + " NO made");
            assertEquals(0, bench.ncAmps(pole), 1e-3, "pole " + pole + " NC open");
        }
        assertEquals(0.05, Math.abs(bench.coilSupply.getCurrent()), 1e-3, "a 1.2 W coil at 24 V");
        assertEquals(10, bench.mount.loadAmps(), 0.05);

        bench.coil(0);
        bench.ticks(3);
        assertFalse(bench.mount.energised());
        assertEquals(0, bench.noAmps(1), 1e-3);
    }

    @Test
    void pullingTheRelayDropsTheLoad() {
        Bench bench = new Bench(RelayMountType.FIVE_PIN, 23);
        bench.mount.fit(SSR);
        bench.coil(24);
        bench.ticks(3);
        assertTrue(bench.noAmps(0) > 9);
        bench.mount.fit(null);
        bench.ticks(1);
        assertEquals(0, bench.noAmps(0), 1e-3);
    }

    @Test
    void theFittedRelayIsSaved() {
        RelayMountControl a = (RelayMountControl) RelayMountType.EIGHT_PIN.create();
        a.fit(EM8);
        CompoundTag tag = new CompoundTag();
        a.write(tag);
        RelayMountControl b = (RelayMountControl) RelayMountType.EIGHT_PIN.create();
        b.read(tag);
        assertEquals(EM8, b.relay());

        RelayMountControl wrong = (RelayMountControl) RelayMountType.FIVE_PIN.create();
        wrong.read(tag);
        assertNull(wrong.relay(), "a save naming a relay the socket cannot take loads empty");

        CompoundTag empty = new CompoundTag();
        ((RelayMountControl) RelayMountType.EIGHT_PIN.create()).write(empty);
        b.read(empty);
        assertNull(b.relay());
    }
}
