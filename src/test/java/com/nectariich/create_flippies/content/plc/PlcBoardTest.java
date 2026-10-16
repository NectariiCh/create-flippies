package com.nectariich.create_flippies.content.plc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.nectariich.create_flippies.content.plc.mcu.McuState;
import com.nectariich.create_flippies.content.plc.mcu.PinDrive;

import org.junit.jupiter.api.Test;

/**
 * The board's arithmetic, without a game: thresholds, latches, the output stage and
 * the power budget. {@link PlcBoard} imports nothing but the emulator's own records,
 * so this compiles and runs with javac and the JUnit API alone.
 */
class PlcBoardTest {

    private static final double EPS = 1e-9;

    private static double[] volts(double each) {
        double[] v = new double[PlcBoard.INPUTS];
        java.util.Arrays.fill(v, each);
        return v;
    }

    private static double[] amps(int channel, double value) {
        double[] a = new double[PlcBoard.CHANNELS];
        a[channel] = value;
        return a;
    }

    @Test
    void supplyHasHysteresis() {
        PlcBoard board = new PlcBoard();
        board.updateSupply(17.9);
        assertFalse(board.supplyOn(), "17.9 V does not start the board");
        board.updateSupply(18.0);
        assertTrue(board.supplyOn(), "18 V does");
        board.updateSupply(16.5);
        assertTrue(board.supplyOn(), "sagging to 16.5 V keeps it running");
        assertTrue(board.updateSupply(15.9), "below 16 V is a brown-out");
        assertFalse(board.supplyOn());
        board.updateSupply(17.0);
        assertFalse(board.supplyOn(), "and it stays off until 18 V again");
        board.updateSupply(Double.NaN);
        assertFalse(board.supplyOn());
    }

    @Test
    void inputsHaveHysteresis() {
        PlcBoard board = new PlcBoard();
        board.updateInputs(volts(10));
        assertFalse(board.inputHigh(0), "10 V from cold is not ON");
        board.updateInputs(volts(15));
        assertTrue(board.inputHigh(3), "15 V is ON");
        board.updateInputs(volts(5.01));
        assertTrue(board.inputHigh(3), "just above 5 V holds ON");
        board.updateInputs(volts(5));
        assertFalse(board.inputHigh(3), "5 V is OFF");
        board.updateInputs(volts(-24));
        assertFalse(board.inputHigh(7), "reverse voltage is OFF");
    }

    @Test
    void conditionBands() {
        assertEquals(PlcBoard.SupplyCondition.OFF, PlcBoard.condition(false, 24));
        assertEquals(PlcBoard.SupplyCondition.LOW, PlcBoard.condition(true, 18.5));
        assertEquals(PlcBoard.SupplyCondition.NORMAL, PlcBoard.condition(true, 24));
        assertEquals(PlcBoard.SupplyCondition.HIGH, PlcBoard.condition(true, 29));
        assertEquals(PlcBoard.SupplyCondition.OVERVOLTAGE, PlcBoard.condition(true, 30.5));
    }

    @Test
    void overcurrentLatchesUntilReset() {
        PlcBoard board = new PlcBoard();
        board.updateSupply(24);
        assertFalse(board.checkOvercurrent(amps(PlcBoard.CH_DO0 + 2, 1.0)), "exactly 1 A is allowed");
        assertTrue(board.checkOvercurrent(amps(PlcBoard.CH_DO0 + 2, -1.01)), "either direction counts");
        assertTrue(board.faulted(PlcBoard.CH_DO0 + 2));
        assertFalse(board.checkOvercurrent(amps(PlcBoard.CH_DO0 + 2, 0)), "a latch is not news twice");
        assertTrue(board.faulted(PlcBoard.CH_DO0 + 2), "the load going away does not unlatch it");

        PinDrive high = new PinDrive(true, true, 1, 0);
        assertEquals(0, board.outputDuty(PlcBoard.CH_DO0 + 2, high, McuState.RUNNING), EPS);
        assertEquals(1, board.outputDuty(PlcBoard.CH_DO0 + 3, high, McuState.RUNNING), EPS);

        board.updateReset(0);
        assertTrue(board.updateReset(24), "asserting RESET is an edge");
        assertFalse(board.faulted(PlcBoard.CH_DO0 + 2), "and clears the latch");
        assertFalse(board.updateReset(24), "holding it is not another edge");
    }

    @Test
    void brownOutClearsLatches() {
        PlcBoard board = new PlcBoard();
        board.updateSupply(24);
        board.checkOvercurrent(amps(PlcBoard.CH_STLED, 5));
        assertTrue(board.faulted(PlcBoard.CH_STLED));
        board.updateSupply(10);
        assertEquals(0, board.faults());
    }

    @Test
    void unpoweredBoardHoldsNoLatch() {
        PlcBoard board = new PlcBoard();
        assertFalse(board.checkOvercurrent(amps(0, 3)));
        assertEquals(0, board.faults());
    }

    @Test
    void latchedInputReadsLow() {
        PlcBoard board = new PlcBoard();
        board.updateSupply(24);
        board.updateInputs(volts(24));
        assertTrue(board.mcuInput(4));
        board.checkOvercurrent(amps(PlcBoard.CH_DI0 + 4, 2));
        assertFalse(board.mcuInput(4), "a latched input is disconnected");
        assertTrue(board.inputHigh(4), "though its terminal is still high");
        assertTrue(board.mcuInput(5));
    }

    @Test
    void latchedResetCannotAssert() {
        PlcBoard board = new PlcBoard();
        board.updateSupply(24);
        board.updateReset(24);
        assertTrue(board.resetAsserted());
        board.checkOvercurrent(amps(PlcBoard.CH_RESET, 2));
        assertFalse(board.resetAsserted(), "the latch disconnects the RESET front end");
        assertFalse(board.updateReset(24));
        assertFalse(board.resetAsserted());
    }

    @Test
    void restoredFaultsAreMasked() {
        PlcBoard board = new PlcBoard();
        board.restoreFaults(-1);
        assertEquals(PlcBoard.ALL_CHANNELS, board.faults());
        assertTrue(board.clearFaults());
        assertFalse(board.clearFaults());
    }

    @Test
    void outputStageFollowsThePinOnlyWhenItMayConduct() {
        PlcBoard board = new PlcBoard();
        PinDrive pwm = new PinDrive(true, true, 0.25, 1000);
        PinDrive pullUp = new PinDrive(false, true, 1, 0);
        int ch = PlcBoard.CH_DO0;

        assertEquals(0, board.outputDuty(ch, pwm, McuState.RUNNING), EPS, "unpowered");
        board.updateSupply(24);
        assertEquals(0.25, board.outputDuty(ch, pwm, McuState.RUNNING), EPS);
        assertEquals(0.25, board.outputDuty(ch, pwm, McuState.HARD_FAULT), EPS, "a fault freezes, not drops");
        assertEquals(0, board.outputDuty(ch, pullUp, McuState.RUNNING), EPS, "INPUT_PULLUP must not drive");
        assertEquals(0, board.outputDuty(ch, pwm, McuState.IN_RESET), EPS);
        assertEquals(0, board.outputDuty(ch, pwm, McuState.PROGRAMMING), EPS);
        assertEquals(0, board.outputDuty(ch, pwm, McuState.NO_PROGRAM), EPS);
        assertEquals(0, board.outputDuty(ch, null, McuState.RUNNING), EPS);
        assertEquals(1, board.outputDuty(ch, new PinDrive(true, true, 1.2, 0), McuState.RUNNING), EPS);
        assertEquals(0, board.outputDuty(ch, new PinDrive(true, true, Double.NaN, 0), McuState.RUNNING), EPS);

        board.updateReset(24);
        assertEquals(0, board.outputDuty(ch, pwm, McuState.RUNNING), EPS, "RESET held turns everything off");
    }

    /**
     * The current a load to GND carries through an averaged output stage, as Power
     * Grid would solve it: the supply across the switch's resistance and the load in
     * series.
     */
    private static double solvedAmps(double duty, double loadOhms, double knownLoad) {
        return 24 / (PlcBoard.outputResistance(duty, knownLoad) + loadOhms);
    }

    @Test
    void averagedOutputCarriesTheDutysShareOfTheOnCurrent() {
        // Regression: R_on / duty left the load nearly fully on at any duty — 25 % into
        // 24 ohm passed 0.98 A where the real average is a quarter of 0.995 A
        for (double load : new double[] { 24, 48, 1000 }) {
            double on = 24 / (PlcBoard.OUTPUT_ON_OHMS + load);
            for (double duty : new double[] { 0.01, 0.25, 0.5, 0.9 }) {
                double amps = solvedAmps(duty, load, load);
                assertEquals(duty * on, amps, 1e-9, "load " + load + " ohm at duty " + duty);
            }
            assertEquals(on, solvedAmps(1, load, load), 1e-9, "full duty is the bare switch");
        }
        // The harness's case: DO2 at 25 % into 48 ohm averages 0.1247 A, not 0.495
        assertEquals(0.1247, solvedAmps(0.25, 48, 48), 1e-4);
    }

    @Test
    void averagedOutputResistanceIsBoundedAndFallsBackWithoutALoad() {
        assertEquals(0.12, PlcBoard.outputResistance(1, 48), EPS);
        assertEquals(0.12, PlcBoard.outputResistance(1, Double.NaN), EPS);
        assertEquals(Double.POSITIVE_INFINITY, PlcBoard.outputResistance(0, 48));
        assertEquals(Double.POSITIVE_INFINITY, PlcBoard.outputResistance(Double.NaN, 48));
        assertEquals(0.48, PlcBoard.outputResistance(0.25, Double.NaN), EPS, "no load seen yet: R_on / duty");
        assertEquals(0.48, PlcBoard.outputResistance(0.25, Double.POSITIVE_INFINITY), EPS);
        assertEquals((0.12 + 48) / 0.25 - 48, PlcBoard.outputResistance(0.25, 48), EPS);
        assertEquals(0.12 / 0.25, PlcBoard.outputResistance(0.25, 0), EPS, "a dead short: only the switch limits");
        assertTrue(PlcBoard.outputResistance(0.999999, 48) >= PlcBoard.OUTPUT_ON_OHMS, "never below R_on");
    }

    @Test
    void loadIsReadFromThePinAndTheCurrent() {
        assertEquals(48, PlcBoard.loadOhms(12, 0.25), EPS);
        assertEquals(48, PlcBoard.loadOhms(12, -0.25), EPS, "either sign of current");
        assertTrue(Double.isNaN(PlcBoard.loadOhms(12, 0.0005)), "too little current to say");
        assertTrue(Double.isNaN(PlcBoard.loadOhms(-1, 0.5)), "a pin pulled below GND is not a load to GND");
        assertTrue(Double.isNaN(PlcBoard.loadOhms(Double.NaN, 0.5)));
    }

    @Test
    void pwmOutputIsLimitedOnItsOnStateCurrent() {
        // Regression: a 1.5 A load at a third duty averages 0.5 A, under the limit,
        // but the switch passes 1.5 A on every pulse and must trip
        double load = 16;
        double duty = 1.0 / 3;
        double avg = solvedAmps(duty, load, load);
        double pin = avg * load;
        double on = PlcBoard.onStateAmps(avg, duty, 24, pin);
        assertEquals(24 / (0.12 + load), on, 1e-9);
        assertTrue(avg < PlcBoard.PIN_LIMIT_AMPS && on > PlcBoard.PIN_LIMIT_AMPS);

        PlcBoard board = new PlcBoard();
        board.updateSupply(24);
        double[] limit = amps(PlcBoard.CH_DO0 + 5, on);
        assertTrue(board.checkOvercurrent(limit), "the on-state current latches the pin");
        assertTrue(board.faulted(PlcBoard.CH_DO0 + 5));

        // The first tick, before the load is known, the switch was R_on / duty: the
        // on-state figure is still right, since it works from the voltages
        double firstAvg = solvedAmps(duty, load, Double.NaN);
        assertEquals(on, PlcBoard.onStateAmps(firstAvg, duty, 24, firstAvg * load), 1e-9);
        // With nothing to read the load from, the average is scaled back up
        assertEquals(0.3, PlcBoard.onStateAmps(0.1, duty, 24, Double.NaN), 1e-9);
        assertEquals(0.7, PlcBoard.onStateAmps(-0.7, 1, 24, 23.9), 1e-9, "full duty is as measured");
    }

    @Test
    void outputPowerIsWorkedOutFromTheOnStateCurrent() {
        // 25 % into 48 ohm: delivered and lost are a quarter of the full-on figures,
        // not the ~99 % the old averaging gave
        double on = 24 / 48.12;
        double loss = PlcBoard.driverLoss(0.25, on);
        double delivered = PlcBoard.delivered(0.25, on, 24);
        assertEquals(0.25 * on * on * 0.12, loss, 1e-12);
        assertEquals(0.25 * on * on * 48, delivered, 1e-9);
        assertEquals(0.25 * 24 * on, loss + delivered, 1e-9, "together, what the supply gives");
        assertEquals(0, PlcBoard.driverLoss(0, on), EPS);
        assertEquals(0, PlcBoard.delivered(0, on, 24), EPS);
    }

    @Test
    void powerBudgetAddsUp() {
        PlcBoard board = new PlcBoard();
        board.updateSupply(24);
        // 0.12 W in the MCU, two inputs on, one output on at full duty
        PlcBoard.Power p = board.power(24, 0.12, 2, 1, 0.17, 0.03, 12);
        double board3v3 = 3.3 * (0.0005 + 0.00025) + 2 * 3.3 * 3.3 / 4700;
        assertEquals(board3v3, p.board(), EPS);
        assertEquals((0.12 + board3v3) / 0.85, p.mcu() + p.board() + p.buckLoss(), EPS, "through the buck");
        assertEquals(24 * (0.003 + 0.0005), p.driverQuiescent(), EPS);
        assertEquals(0.17 + 0.03 + 12, p.pins(), EPS);
        assertEquals(p.logicDraw() + p.pins(), p.total(), EPS);
        assertEquals(0, p.standby(), EPS);

        board.updateSupply(0);
        PlcBoard.Power off = board.power(15, 0.5, 2, 1, 0.03, 0, 0);
        assertEquals(0, off.mcu(), EPS, "an unpowered board runs nothing");
        assertEquals(15 * 15 / 1e6, off.logicDraw(), EPS, "but leaks through standby");
        assertEquals(0.03, off.inputs(), EPS, "and its input resistors still conduct");
    }

    @Test
    void logicLoadIsAConstantPowerResistance() {
        assertEquals(24 * 24 / 0.5, PlcBoard.logicLoadResistance(24, 0.5), EPS);
        assertEquals(PlcBoard.STANDBY_OHMS, PlcBoard.logicLoadResistance(24, 0), EPS);
        assertEquals(PlcBoard.STANDBY_OHMS, PlcBoard.logicLoadResistance(0, 1), EPS);
        assertEquals(PlcBoard.STANDBY_OHMS, PlcBoard.logicLoadResistance(-24, 1), EPS);
        assertEquals(PlcBoard.MIN_LOAD_OHMS, PlcBoard.logicLoadResistance(24, 1000), EPS);
        assertEquals(PlcBoard.STANDBY_OHMS, PlcBoard.logicLoadResistance(1, 1e-12), EPS);
    }
}
