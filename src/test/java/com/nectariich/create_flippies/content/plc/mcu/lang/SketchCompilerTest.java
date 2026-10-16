package com.nectariich.create_flippies.content.plc.mcu.lang;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.stream.Stream;

import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;

/**
 * The language harnesses under JUnit, for {@code gradlew test}. Each harness also
 * runs on its own with plain {@code java}, which is how they are run while the
 * Gradle build is busy elsewhere.
 */
class SketchCompilerTest {

    @TestFactory
    Stream<DynamicTest> sketches() {
        return LangTests.cases().stream().map(c -> DynamicTest.dynamicTest(c.name, () -> {
            int before = LangTests.failed;
            c.run();
            assertEquals(before, LangTests.failed, () -> LangTests.lastFailure);
        }));
    }

    @Test
    void timeInterruptsAndThroughput() {
        assertEquals(0, LangRuntimeTests.runAll());
    }

    @Test
    void mutatedSketchesNeverBreakTheToolchain() {
        assertEquals(0, LangFuzz.run(2000, 1234));
    }
}
