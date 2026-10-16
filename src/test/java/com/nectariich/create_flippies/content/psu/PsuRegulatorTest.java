package com.nectariich.create_flippies.content.psu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * The supply's rules on plain numbers: the input window and its hysteresis, the
 * current limit, hiccup, the DC OK window and its latch, and the input draw.
 *
 * {@link Load} stands in for Power Grid with the one circuit that matters here — each
 * supply's source behind its series resistance, onto one rail, into a load that is a
 * resistor or a source behind a resistance — solved exactly, a tick behind, the way
 * {@link PsuControl} feeds the regulator.
 */
class PsuRegulatorTest {

    /**
     * Ticks to let a start or a load step ramp out: the series resistance comes
     * down by at most half a tick, and no faster than keeps the limit into the
     * rail, so a near-rated load reaches 24 V in a little over a second.
     */
    private static final int SETTLE = 40;

    /**
     * How close regulation is held: the source's own 0.1 mΩ, which the regulator
     * does not know about, puts a fraction of a millivolt on the terminals.
     */
    private static final double VOLTS = 1e-3;

    /**
     * Where V+ sits on a resistor of {@code ohms} behind {@code supplies} running
     * supplies in constant voltage: the rated 24 V, drooping by the output
     * resistance (and the source's own 0.1 mΩ) shared between them.
     */
    private static double rail(double ohms, int supplies) {
        double inside = (PsuRegulator.OUTPUT_OHMS + PsuControl.SOURCE_OHMS) / supplies;
        return 24 * ohms / (ohms + inside);
    }

    private static PsuRegulator psu() {
        return new PsuRegulator(230, 24, 100);
    }

    /**
     * One or more regulators paralleled onto one rail, into a load of
     * {@link #loadVolts} behind {@link #ohms} (infinite for none), solved exactly
     * after every tick. An open output switch is Power Grid's 200 MΩ.
     */
    private static final class Load {
        final PsuRegulator psu;
        final PsuRegulator[] supplies;
        /** Ticks before which each supply's input is held at zero. */
        final int[] startAt;
        double ohms;
        double loadVolts;
        double input = 230;
        double volts;
        double amps;
        double[] each;
        double dcOkAmps;
        int tick;

        Load(PsuRegulator psu, double ohms) {
            this(ohms, new PsuRegulator[] {psu}, new int[] {0});
        }

        Load(double ohms, PsuRegulator[] supplies, int[] startAt) {
            this.psu = supplies[0];
            this.supplies = supplies;
            this.startAt = startAt;
            this.ohms = ohms;
            this.each = new double[supplies.length];
        }

        void ticks(int n) {
            for (int i = 0; i < n; i++) {
                for (int k = 0; k < supplies.length; k++)
                    supplies[k].tick(tick < startAt[k] ? 0 : input, volts, each[k], k == 0 ? dcOkAmps : 0);
                solve();
                tick++;
            }
        }

        private static double conductance(PsuRegulator supply) {
            return supply.outputOn() ? 1 / (supply.outputOhms() + PsuControl.SOURCE_OHMS) : 5e-9;
        }

        private void solve() {
            double g = 0;
            double j = 0;
            for (PsuRegulator supply : supplies) {
                g += conductance(supply);
                j += supply.setpoint() * conductance(supply);
            }
            if (Double.isFinite(ohms)) {
                g += 1 / ohms;
                j += loadVolts / ohms;
            }
            volts = j / g;
            for (int k = 0; k < supplies.length; k++)
                each[k] = supplies[k].outputOn() ? (supplies[k].setpoint() - volts) * conductance(supplies[k]) : 0;
            amps = each[0];
        }
    }

    // ------------------------------------------------------------- input ---

    @Test
    void startsAtEightyFivePercentAndDropsOutUnderSeventyFive() {
        PsuRegulator psu = psu();
        psu.tick(195, 0, 0, 0);
        assertFalse(psu.outputOn(), "195 V is under 195.5");
        assertEquals(PsuRegulator.State.INPUT_LOW, psu.state());
        psu.tick(195.5, 0, 0, 0);
        assertTrue(psu.outputOn(), "195.5 V starts it");
        psu.tick(173, 0, 0, 0);
        assertTrue(psu.outputOn(), "and it rides down to 172.5 V");
        psu.tick(172.5, 0, 0, 0);
        assertTrue(psu.outputOn(), "172.5 V is not under 172.5");
        psu.tick(172.4, 0, 0, 0);
        assertFalse(psu.outputOn(), "under 172.5 V it drops out");
        assertEquals(PsuRegulator.State.INPUT_LOW, psu.state());
        psu.tick(190, 0, 0, 0);
        assertFalse(psu.outputOn(), "and needs 195.5 V again to restart");
    }

    @Test
    void overVoltageTripsAboveOneTwentyAndRestartsAtOneFifteen() {
        PsuRegulator psu = psu();
        psu.tick(276, 0, 0, 0);
        assertTrue(psu.outputOn(), "276 V is not over 276");
        psu.tick(276.1, 0, 0, 0);
        assertFalse(psu.outputOn());
        assertEquals(PsuRegulator.State.INPUT_HIGH, psu.state());
        psu.tick(265, 0, 0, 0);
        assertFalse(psu.outputOn(), "265 V is still over 264.5");
        psu.tick(264.5, 0, 0, 0);
        assertTrue(psu.outputOn(), "264.5 V restarts it");
    }

    @Test
    void noInputIsOffAndSomeInputIsInputLow() {
        PsuRegulator psu = psu();
        psu.tick(0, 0, 0, 0);
        assertEquals(PsuRegulator.State.OFF, psu.state());
        psu.tick(22, 0, 0, 0);
        assertEquals(PsuRegulator.State.OFF, psu.state(), "under a tenth of nominal is no input at all");
        psu.tick(120, 0, 0, 0);
        assertEquals(PsuRegulator.State.INPUT_LOW, psu.state());
    }

    @Test
    void reversedInputRunsLikeABridgeRectifier() {
        Load load = new Load(psu(), 24);
        load.input = -230;
        load.ticks(SETTLE);
        assertEquals(PsuRegulator.State.RUNNING, load.psu.state());
        assertEquals(rail(24, 1), load.volts, VOLTS);
    }

    @Test
    void windowScalesWithTheNominalInput() {
        PsuRegulator psu = new PsuRegulator(120, 24, 100);
        psu.tick(101, 0, 0, 0);
        assertFalse(psu.outputOn(), "101 V is under 0.85 × 120 = 102");
        psu.tick(102, 0, 0, 0);
        assertTrue(psu.outputOn());
    }

    // ------------------------------------------------------------ output ---

    @Test
    void softStartDrivesNoMoreThanTheLimitIntoAShort() {
        PsuRegulator psu = psu();
        psu.tick(230, 0, 0, 0);
        assertTrue(psu.outputOn());
        assertEquals(24, psu.setpoint(), 1e-12, "the source starts at the rated voltage");
        // Into zero ohms the series resistance passes the limit exactly
        assertEquals(psu.limitAmps(), psu.setpoint() / psu.outputOhms(), 1e-9);
    }

    @Test
    void softStartPicksUpAnOutputAlreadyHeld() {
        PsuRegulator psu = psu();
        psu.tick(230, 23.9, 0, 0);
        double into = (psu.setpoint() - 23.9) / psu.outputOhms();
        assertTrue(into >= 0 && into <= psu.limitAmps(),
                "no more than the limit into a rail something else already holds, and nothing out of it: " + into);
    }

    @Test
    void droopsByItsOutputResistanceUnderLoad() {
        Load load = new Load(psu(), 6);
        load.ticks(SETTLE);
        assertEquals(PsuRegulator.State.RUNNING, load.psu.state());
        assertEquals(24, load.psu.setpoint(), 1e-12, "the source stays at the rated voltage");
        assertEquals(rail(6, 1), load.volts, VOLTS, "and V+ droops by 0.05 ohm, about 0.2 V at 4 A");
        assertTrue(load.volts > 0.99 * 24, "inside 1 % load regulation");
        assertEquals(rail(6, 1) / 6, load.amps, VOLTS);
        assertTrue(load.psu.dcOk());
    }

    @Test
    void openOutputSitsAtTheRatedVoltage() {
        Load load = new Load(psu(), Double.POSITIVE_INFINITY);
        load.ticks(3);
        assertEquals(24, load.psu.setpoint(), 1e-12);
        assertEquals(PsuRegulator.State.RUNNING, load.psu.state());
        assertTrue(load.psu.dcOk());
    }

    @Test
    void anOverloadIsHeldAtTheLimit() {
        // 2 ohm would draw 12 A at 24 V; the limit is 1.1 × 100 / 24 = 4.58 A
        Load load = new Load(psu(), 2);
        load.ticks(SETTLE);
        double limit = 1.1 * 100 / 24;
        assertEquals(limit, load.psu.limitAmps(), 1e-12);
        assertEquals(PsuRegulator.State.OVERLOAD, load.psu.state());
        assertEquals(limit, load.amps, VOLTS);
        assertEquals(limit * 2, load.volts, VOLTS, "the voltage folds back to I_lim × R");
        assertFalse(load.psu.dcOk(), "9.2 V is out of the DC OK window");

        // Take the overload off and it is back to regulating
        load.ohms = 12;
        load.ticks(SETTLE);
        assertEquals(PsuRegulator.State.RUNNING, load.psu.state());
        assertEquals(rail(12, 1), load.volts, VOLTS);
    }

    @Test
    void justUnderTheLimitIsNotAnOverload() {
        // 4.5 A at 24 V
        Load load = new Load(psu(), 24 / 4.5);
        load.ticks(SETTLE);
        assertEquals(PsuRegulator.State.RUNNING, load.psu.state());
        assertEquals(rail(24 / 4.5, 1), load.volts, VOLTS);
    }

    @Test
    void aShortHiccupsForASecondAndTriesAgain() {
        Load load = new Load(psu(), 0.001);
        load.ticks(1);
        assertTrue(load.psu.outputOn(), "soft start");
        assertTrue(load.amps <= load.psu.limitAmps() + 1e-9, "the start tick carries no more than the limit");
        load.ticks(1);
        assertEquals(PsuRegulator.State.HICCUP, load.psu.state(), "the first solve finds the short");
        assertFalse(load.psu.outputOn());

        // The tick that found it, then HICCUP_TICKS more, all off
        for (int i = 0; i < PsuRegulator.HICCUP_TICKS; i++) {
            load.ticks(1);
            assertFalse(load.psu.outputOn(), "still off " + i + " ticks into the hiccup");
            assertEquals(PsuRegulator.State.HICCUP, load.psu.state());
        }
        load.ticks(1);
        assertTrue(load.psu.outputOn(), "and then it tries again");
        load.ticks(1);
        assertEquals(PsuRegulator.State.HICCUP, load.psu.state(), "into the same short");

        // Clear the short during the hiccup and it comes up normally
        load.ohms = 24;
        load.ticks(PsuRegulator.HICCUP_TICKS + SETTLE);
        assertEquals(PsuRegulator.State.RUNNING, load.psu.state());
        assertEquals(rail(24, 1), load.volts, VOLTS);
    }

    @Test
    void aHeavyLoadAboveTheShortThresholdStaysInConstantCurrent() {
        // 4.58 A × 0.6 ohm = 2.75 V, over a tenth of 24
        Load load = new Load(psu(), 0.6);
        load.ticks(10);
        assertEquals(PsuRegulator.State.OVERLOAD, load.psu.state());
        // and 0.5 ohm, at 2.29 V, is a short, found within a few ticks of the limit
        // catching up with it
        load.ohms = 0.5;
        load.ticks(3);
        assertEquals(PsuRegulator.State.HICCUP, load.psu.state());
    }

    @Test
    void anInputLostWhileHiccupingResetsTheTimer() {
        Load load = new Load(psu(), 0.001);
        load.ticks(2);
        assertTrue(load.psu.hiccupLeft() > 0);
        load.input = 0;
        load.ticks(1);
        assertEquals(0, load.psu.hiccupLeft());
        load.input = 230;
        load.ohms = 24;
        load.ticks(SETTLE);
        assertEquals(PsuRegulator.State.RUNNING, load.psu.state());
    }

    // ------------------------------------------- loads that are not resistors ---

    /**
     * Steps a bench and checks every supply after {@code settle} ticks of each
     * event: never more than 1.2 × the limit, and never sinking more than a
     * trickle at any tick, settled or not.
     */
    private static void assertBounded(Load load, int ticks, int settle, int... events) {
        double limit = load.psu.limitAmps();
        for (int t = 0; t < ticks; t++) {
            load.ticks(1);
            boolean settled = true;
            for (int event : events)
                if (t >= event && t < event + settle)
                    settled = false;
            for (int k = 0; k < load.supplies.length; k++) {
                double amps = load.each[k];
                assertTrue(amps >= -0.01 * limit, "supply " + k + " sinks " + amps + " A at tick " + t);
                if (settled)
                    assertTrue(amps <= 1.2 * limit, "supply " + k + " carries " + amps + " A at tick " + t);
            }
        }
    }

    @Test
    void aBatteryIsChargedAtTheLimitNotSwungThrough() {
        // 12 V behind 0.1 ohm: at 24 V it would take 120 A
        Load load = new Load(psu(), 0.1);
        load.loadVolts = 12;
        assertBounded(load, 60, 3, 0);
        assertEquals(PsuRegulator.State.OVERLOAD, load.psu.state());
        assertEquals(load.psu.limitAmps(), load.amps, VOLTS);
        assertEquals(12 + load.psu.limitAmps() * 0.1, load.volts, VOLTS);
    }

    @Test
    void aRailHeldAboveTheRatingIsNotSunk() {
        for (double held : new double[] {24.5, 26}) {
            Load load = new Load(psu(), 0.1);
            load.loadVolts = held;
            load.volts = held;
            assertBounded(load, 40, 0);
            assertTrue(load.psu.running(), "the converter runs at " + held + " V");
            assertTrue(load.psu.blocked(), "behind its output diode at " + held + " V");
            assertEquals(held, load.volts, 1e-6);
        }
    }

    @Test
    void currentIntoTheOutputBlocksItAndAFallingRailUnblocksIt() {
        Load load = new Load(psu(), 6);
        load.ticks(4);
        assertTrue(load.psu.outputOn());
        load.psu.tick(230, 24.3, -6, 0);
        assertFalse(load.psu.outputOn(), "current into V+ reverse-biases the diode");
        assertTrue(load.psu.running());
        assertEquals(PsuRegulator.State.RUNNING, load.psu.state());
        assertEquals(0, load.psu.outputWatts(), 1e-12, "and nothing is delivered for it");
        load.psu.tick(230, 24.2, 0, 0);
        assertFalse(load.psu.outputOn(), "still blocked while the rail sits above the rating");
        load.psu.tick(230, 23, 0, 0);
        assertTrue(load.psu.outputOn(), "and conducting again once it falls below");
        assertTrue((load.psu.setpoint() - 23) / load.psu.outputOhms() <= load.psu.limitAmps() + 1e-9,
                "picking the rail up through no more than the limit");
    }

    @Test
    void parallelSuppliesStartedApartShareWithoutCirculating() {
        // Into 3 ohm the pair carries 8 A, more than either one's limit
        Load load = new Load(3, new PsuRegulator[] {psu(), psu()}, new int[] {0, 5});
        assertBounded(load, 80, 5, 0, 5);
        assertEquals(rail(3, 2), load.volts, VOLTS);
        assertEquals(load.each[0], load.each[1], VOLTS, "two alike supplies share alike");
    }

    @Test
    void parallelSuppliesStartedTogetherShareARatedLoad() {
        Load load = new Load(6, new PsuRegulator[] {psu(), psu()}, new int[] {0, 1});
        assertBounded(load, 40, 5, 0, 1);
        assertEquals(rail(6, 2), load.volts, VOLTS);
        assertEquals(load.each[0], load.each[1], VOLTS);
    }

    @Test
    void parallelSuppliesPushedIntoOverloadBothHoldTheLimit() {
        Load load = new Load(6, new PsuRegulator[] {psu(), psu()}, new int[] {0, 20});
        assertBounded(load, 60, 5, 0, 20);
        load.ohms = 2;
        assertBounded(load, 80, 5, 0);
        for (int k = 0; k < 2; k++) {
            assertEquals(PsuRegulator.State.OVERLOAD, load.supplies[k].state());
            assertEquals(load.psu.limitAmps(), load.each[k], VOLTS);
        }
        // and the sign never flips tick to tick once it has settled
        double[] before = load.each.clone();
        for (int t = 0; t < 20; t++) {
            load.ticks(1);
            for (int k = 0; k < 2; k++)
                assertTrue(Math.signum(load.each[k]) == Math.signum(before[k]), "supply " + k + " swung at " + t);
            before = load.each.clone();
        }
    }

    // ------------------------------------------------------------- DC OK ---

    @Test
    void dcOkWindowIsEighteenToTwentySevenVolts() {
        assertDcOk(psu(), 17.99, false);
        assertDcOk(psu(), 18, true);
        assertDcOk(psu(), 27, true);
        assertDcOk(psu(), 27.01, false);
    }

    @Test
    void dcOkWindowScalesWithTheRating() {
        assertDcOk(new PsuRegulator(230, 12, 100), 8.99, false);
        assertDcOk(new PsuRegulator(230, 12, 100), 9, true);
        assertDcOk(new PsuRegulator(230, 12, 100), 13.5, true);
        assertDcOk(new PsuRegulator(230, 12, 100), 13.51, false);
        assertDcOk(new PsuRegulator(120, 48, 500), 36, true);
        assertDcOk(new PsuRegulator(120, 48, 500), 54.01, false);
    }

    private static void assertDcOk(PsuRegulator psu, double volts, boolean expected) {
        psu.tick(psu.ratedInputVolts(), 0, 0, 0);
        assertTrue(psu.outputOn());
        psu.tick(psu.ratedInputVolts(), volts, 0, 0);
        assertEquals(expected, psu.dcOk(), "DC OK at " + volts + " V of " + psu.ratedOutputVolts());
    }

    @Test
    void dcOkIsNeverOnWhileStartingOrOff() {
        PsuRegulator psu = psu();
        psu.tick(230, 24, 0, 0);
        assertFalse(psu.dcOk(), "the start tick has not seen the output yet");
        psu.tick(0, 24, 0, 0);
        assertFalse(psu.dcOk(), "an output held up by something else is not this supply's");
    }

    @Test
    void anOverloadedDcOkLatchesUntilTheNextStart() {
        Load load = new Load(psu(), 24);
        load.ticks(SETTLE);
        assertTrue(load.psu.dcOk());
        load.dcOkAmps = 0.05;
        load.ticks(1);
        assertTrue(load.psu.dcOk(), "50 mA is its rating");
        load.dcOkAmps = 0.2;
        load.ticks(1);
        assertFalse(load.psu.dcOk());
        assertTrue(load.psu.dcOkLatched());
        load.dcOkAmps = 0;
        load.ticks(5);
        assertFalse(load.psu.dcOk(), "held open with the output still good");

        // Losing and regaining the input is a restart, which forgives it
        load.input = 0;
        load.ticks(1);
        load.input = 230;
        load.ticks(SETTLE);
        assertTrue(load.psu.dcOk());
        assertFalse(load.psu.dcOkLatched());
    }

    // -------------------------------------------------------- input draw ---

    @Test
    void efficiencyRunsFromEightyAtATenthToNinetyAtFullLoad() {
        assertEquals(0.80, PsuRegulator.efficiency(0), 1e-12);
        assertEquals(0.80, PsuRegulator.efficiency(0.1), 1e-12);
        assertEquals(0.85, PsuRegulator.efficiency(0.55), 1e-12);
        assertEquals(0.90, PsuRegulator.efficiency(1), 1e-12);
        assertEquals(0.90, PsuRegulator.efficiency(1.1), 1e-12);
    }

    @Test
    void inputPowerIsOutputOverEfficiencyPlusNoLoad() {
        assertEquals(0.3, PsuRegulator.inputWatts(0, 100), 1e-12);
        assertEquals(100 / 0.9 + 0.3, PsuRegulator.inputWatts(100, 100), 1e-9);
        assertEquals(10 / 0.8 + 0.3, PsuRegulator.inputWatts(10, 100), 1e-9);
    }

    @Test
    void theInputLoadDrawsThatPowerAtTheMeasuredVoltage() {
        Load load = new Load(psu(), 6);
        load.ticks(SETTLE);
        double delivered = load.volts * load.amps;
        double watts = delivered / PsuRegulator.efficiency(delivered / 100) + 0.3;
        assertEquals(watts, load.psu.inputWatts(), 1e-9);
        assertEquals(230 * 230 / watts, load.psu.inputOhms(), 1e-6);
    }

    @Test
    void anIdleSupplyDrawsItsNoLoadPowerAndAStoppedOneNothing() {
        Load load = new Load(psu(), Double.POSITIVE_INFINITY);
        load.ticks(3);
        assertEquals(230 * 230 / 0.3, load.psu.inputOhms(), 1e-6);
        load.input = 150;
        load.ticks(1);
        assertEquals(PsuRegulator.OPEN_OHMS, load.psu.inputOhms(), 1e-12);
    }
}
