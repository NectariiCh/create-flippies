package com.nectariich.create_flippies.content.plc.mcu.chip;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

/**
 * The chip's own harnesses under Gradle. They were written as plain {@code main}
 * classes so they could run with javac alone while the toolchain was being built
 * beside them; this runs the same checks in {@code gradlew test}. A failing check
 * prints its name to the test's standard output, which the report keeps.
 */
class ChipHarnessTest {

    @Test
    void unitChecksAgainstAScriptedCpu() {
        assertEquals(0, ChipTest.runAll(), "ChipTest failures; see the test output for which");
    }

    @Test
    void sketchesThroughTheRealToolchain() {
        assertEquals(0, EndToEndTest.runAll(), "EndToEndTest failures; see the test output for which");
    }

    @Test
    void reviewFindingRegressions() {
        assertEquals(0, ChipFixTest.runAll(), "ChipFixTest failures; see the test output for which");
    }

    @Test
    void secondReviewFindingRegressions() {
        assertEquals(0, ChipFix2Test.runAll(), "ChipFix2Test failures; see the test output for which");
    }
}
