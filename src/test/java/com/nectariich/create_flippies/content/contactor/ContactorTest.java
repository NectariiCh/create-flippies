package com.nectariich.create_flippies.content.contactor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import com.nectariich.create_flippies.content.circuit_breaker.CircuitBreakerModel;
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

/** The contactor matrix, its geometry and its circuit against Power Grid's solver. */
class ContactorTest {

    private static final Path ASSETS = Path.of("src/main/resources/assets/create_flippies");

    private static boolean overlap(double[] a, double[] b) {
        for (int axis = 0; axis < 3; axis++)
            if (!(a[axis] < b[axis + 3] && b[axis] < a[axis + 3]))
                return false;
        return true;
    }

    @Test
    void theMatrixIsSixSizesBySixCoils() {
        assertEquals(36, ContactorType.all().size());
        assertEquals("contactor_1m_spdt_5v", ContactorType.of(1, 5).id());
        assertEquals("contactor_3m_tpdt_125v", ContactorType.of(3, 125).id());
        assertEquals("contactor_8m_8pdt_250v", ContactorType.of(8, 250).id());
        for (ContactorType type : ContactorType.all())
            assertEquals(2 + 3 * type.modules(), type.terminalCount());
    }

    @Test
    void everyContactorHasItsModelsTextureAndName() throws IOException {
        String lang = Files.readString(ASSETS.resolve("lang/en_us.json"), StandardCharsets.UTF_8);
        for (ContactorType type : ContactorType.all()) {
            String id = type.id();
            assertTrue(Files.exists(ASSETS.resolve("models/" + type.model() + ".json")), id + " partial");
            assertTrue(Files.exists(ASSETS.resolve("models/item/" + id + ".json")), id + " item model");
            assertTrue(Files.exists(ASSETS.resolve("textures/block/contactor-1m-spdt-" + type.coilVolts() + "v.png")),
                    id + " texture");
            assertTrue(lang.contains("\"item.create_flippies." + id + "\""), id + " lang name");
        }
    }

    @Test
    void clickBoxesHoldTheirPinsAndNeverOverlap() {
        for (int modules : ContactorType.SIZES) {
            int n = ContactorGeometry.terminals(modules);
            double lo = CircuitBreakerModel.minX(modules);
            double hi = CircuitBreakerModel.maxX(modules);
            for (int a = 0; a < n; a++) {
                double[] box = ContactorGeometry.contactBox(modules, a);
                double[] pin = ContactorGeometry.pin(modules, a);
                for (int axis = 0; axis < 3; axis++)
                    assertTrue(box[axis] <= pin[axis] && pin[axis + 3] <= box[axis + 3], modules + "M terminal " + a);
                assertTrue(box[0] > lo && box[3] < hi, modules + "M terminal " + a + " leaves the contactor");
                for (int b = a + 1; b < n; b++)
                    assertFalse(overlap(box, ContactorGeometry.contactBox(modules, b)),
                            modules + "M terminals " + a + " and " + b + " overlap");
            }
        }
    }

    @Test
    void theCoilRule() {
        // Pull-in at 90 %, nothing less
        assertFalse(ContactorCoil.decide(false, 0.899, 0.5));
        assertTrue(ContactorCoil.decide(false, 0.9, 0.5));
        assertTrue(ContactorCoil.decide(false, -1.0, 0.5), "not polarised");
        // Held safely from 20 % whatever the roll
        assertTrue(ContactorCoil.decide(true, 0.2, 0.0));
        assertTrue(ContactorCoil.decide(true, 0.3, 0.0));
        // 10-20 %: held unless the 0.1 % roll comes up
        assertTrue(ContactorCoil.decide(true, 0.15, 0.001));
        assertFalse(ContactorCoil.decide(true, 0.15, 0.0009));
        // Below 10 % it lets go
        assertFalse(ContactorCoil.decide(true, 0.099, 0.9));
    }

    @Test
    void theCoilDrawsInProportionToItsVoltage() {
        // 24 V, 10 W: 0.4167 A rated, held as a constant current between 10 % and 100 %
        double amps = 10.0 / 24;
        for (double volts : new double[] { 2.4, 3.6, 12, 24 })
            assertEquals(amps, volts / ContactorCoil.targetOhms(24, 10, volts), 1e-9, volts + " V");
        assertEquals(57.6, ContactorCoil.targetOhms(24, 10, 30), 1e-9, "a plain resistor above rated");
        assertEquals(5.76, ContactorCoil.targetOhms(24, 10, 0), 1e-9, "and below 10 %");
        assertEquals(57.6, ContactorCoil.ratedOhms(24, 10), 1e-9);
    }

    @Test
    void openContactorBoxesNeverOverlap() {
        for (int a = 0; a < OpenContactorGeometry.MODULE_TERMINALS; a++)
            for (int b = a + 1; b < OpenContactorGeometry.MODULE_TERMINALS; b++)
                assertFalse(overlap(OpenContactorGeometry.moduleBox(a), OpenContactorGeometry.moduleBox(b)),
                        "module terminals " + a + " and " + b);
        for (int a = 0; a < OpenContactorGeometry.COIL_TERMINALS; a++)
            for (int b = a + 1; b < OpenContactorGeometry.COIL_TERMINALS; b++)
                assertFalse(overlap(OpenContactorGeometry.coilBox(a), OpenContactorGeometry.coilBox(b)),
                        "coil terminals " + a + " and " + b);
    }

    @Test
    void openContactorModelsExist() {
        for (OpenContactorPiece piece : OpenContactorPiece.values()) {
            assertTrue(Files.exists(ASSETS.resolve("models/" + piece.bodyModel() + ".json")), piece + " body");
            assertTrue(Files.exists(ASSETS.resolve("models/" + piece.plungerModel() + ".json")), piece + " plunger");
        }
        assertTrue(Files.exists(ASSETS.resolve("models/item/contactor_open.json")));
        for (OpenContactorCoilType coil : OpenContactorCoilType.all()) {
            assertTrue(Files.exists(ASSETS.resolve("models/" + coil.model() + ".json")), coil.id());
            assertTrue(Files.exists(ASSETS.resolve("models/item/" + coil.id() + ".json")), coil.id());
        }
    }

    @Test
    void openContactorPiecesAndCoilPower() {
        assertEquals(OpenContactorPiece.SINGLE, OpenContactorPiece.of(false, false));
        assertEquals(OpenContactorPiece.LEFT, OpenContactorPiece.of(false, true));
        assertEquals(OpenContactorPiece.RIGHT, OpenContactorPiece.of(true, false));
        assertEquals(OpenContactorPiece.CENTER, OpenContactorPiece.of(true, true));
        assertEquals(10, OpenContactorCoilType.watts(0), 1e-9);
        assertEquals(10, OpenContactorCoilType.watts(1), 1e-9);
        assertEquals(11, OpenContactorCoilType.watts(2), 1e-9);
        assertEquals(15, OpenContactorCoilType.watts(6), 1e-9);
        assertEquals(4, OpenContactorCoilType.all().size());
    }

    private static boolean solverAvailable() {
        try {
            Class.forName("org.ejml.data.DMatrixD1", false, ContactorTest.class.getClassLoader());
            return true;
        } catch (ClassNotFoundException e) {
            return false;
        }
    }

    @Test
    void everyPoleChangesOverWithTheCoil() {
        assumeTrue(solverAvailable(), "Power Grid's solver needs EJML on the test classpath");
        ContactorType type = ContactorType.of(3, 24);
        ContactorControl contactor = (ContactorControl) type.create();
        FloatingNode[] t = new FloatingNode[type.terminalCount()];
        for (int i = 0; i < t.length; i++)
            t[i] = new FloatingNode();
        HostedCircuitBuilder builder = new HostedCircuitBuilder(BlockPos.ZERO, t.length, i -> t[i]);
        contactor.buildCircuit(builder);
        List<INode> nodes = new ArrayList<>();
        List<AbstractElectricWire> wires = new ArrayList<>();
        builder.handOver(new IElectricEntity.CircuitBuilder(BlockPos.ZERO, new ArrayList<>(), nodes, wires));
        assertEquals(1 + 2 * type.poles(), wires.size(), "a coil and two contacts a pole");

        ElectricalNetwork input = new ElectricalNetwork(true);
        ElectricalNetwork load = new ElectricalNetwork(true);
        Set<FloatingNode> coilSide = Set.of(t[ContactorGeometry.TERMINAL_A1], t[ContactorGeometry.TERMINAL_A2]);
        for (FloatingNode node : t)
            (coilSide.contains(node) ? input : load).addNode(node);
        for (AbstractElectricWire wire : wires)
            (coilSide.contains(wire.getNode1()) ? input : load).addWire(wire);
        VoltageSourceCoupling supply = new VoltageSourceCoupling(t[ContactorGeometry.TERMINAL_A1],
                t[ContactorGeometry.TERMINAL_A2], 0.01f, 0f);
        input.addNode(supply);

        FloatingNode neutral = new FloatingNode();
        load.addNode(neutral);
        ElectricWire[] noLoads = new ElectricWire[type.poles()];
        ElectricWire[] ncLoads = new ElectricWire[type.poles()];
        for (int pole = 0; pole < type.poles(); pole++) {
            load.addNode(new VoltageSourceCoupling(t[ContactorGeometry.common(pole)], neutral, 0.01f, 400f));
            noLoads[pole] = new ElectricWire(20, t[ContactorGeometry.no(pole)], neutral);
            ncLoads[pole] = new ElectricWire(20, t[ContactorGeometry.nc(pole)], neutral);
            load.addWire(noLoads[pole]);
            load.addWire(ncLoads[pole]);
        }

        Runnable tick = () -> {
            input.calculate(1);
            load.calculate(1);
            contactor.serverTick();
            input.calculate(1);
            load.calculate(1);
        };

        tick.run();
        assertFalse(contactor.energised());
        for (int pole = 0; pole < type.poles(); pole++) {
            assertEquals(20, Math.abs(ncLoads[pole].current()), 0.1, "NC closed at rest");
            assertEquals(0, Math.abs(noLoads[pole].current()), 1e-3);
        }

        supply.setVoltage(24f);
        tick.run();
        assertTrue(contactor.energised());
        // The coil's resistance eases back to its rated value over a few ticks
        for (int i = 0; i < 20; i++)
            tick.run();
        assertEquals(ContactorType.COIL_WATTS / 24, Math.abs(supply.getCurrent()), 1e-3, "3 W coil");
        for (int pole = 0; pole < type.poles(); pole++) {
            assertEquals(20, Math.abs(noLoads[pole].current()), 0.1, "NO closed when pulled in");
            assertEquals(0, Math.abs(ncLoads[pole].current()), 1e-3);
        }
        assertEquals(20, contactor.loadAmps(), 0.1);

        supply.setVoltage(0f);
        tick.run();
        assertFalse(contactor.energised());
    }
}
