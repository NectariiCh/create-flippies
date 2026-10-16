package com.nectariich.create_flippies.content.plc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import com.nectariich.create_flippies.content.plc.mcu.CompileResult;
import com.nectariich.create_flippies.content.plc.mcu.Diagnostic;

import org.junit.jupiter.api.Test;

/**
 * The toolchain wrapper both sides compile through: its bound on concurrent compiles,
 * and the report lines the menu reads back. Plain Java, like {@link PlcToolchain}.
 */
class PlcToolchainTest {

    /**
     * Regression: every upload used to start a compile on the shared background pool,
     * unbounded. Now there are {@link PlcToolchain#COMPILER_THREADS} threads of the
     * toolchain's own and at most {@link PlcToolchain#COMPILE_QUEUE} waiting; one more
     * is refused at once rather than queued.
     */
    @Test
    void compilesAreBoundedAndRefusedPastTheQueue() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch running = new CountDownLatch(PlcToolchain.COMPILER_THREADS);
        List<CompletableFuture<String>> jobs = new ArrayList<>();
        List<String> threads = new ArrayList<>();
        try {
            int capacity = PlcToolchain.COMPILER_THREADS + PlcToolchain.COMPILE_QUEUE;
            for (int i = 0; i < capacity; i++) {
                CompletableFuture<String> job = PlcToolchain.submit(() -> {
                    running.countDown();
                    try {
                        release.await(10, TimeUnit.SECONDS);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                    return Thread.currentThread().getName();
                });
                assertNotNull(job, "job " + i + " fits");
                jobs.add(job);
            }
            assertTrue(running.await(10, TimeUnit.SECONDS), "both threads picked a job up");
            assertNull(PlcToolchain.submit(() -> "one too many"), "past the queue: refused");
            assertNull(PlcToolchain.compileAsync("void setup(){}\nvoid loop(){}\n"), "a compile too");
        } finally {
            release.countDown();
        }
        for (CompletableFuture<String> job : jobs)
            threads.add(job.get(10, TimeUnit.SECONDS));
        for (String name : threads)
            assertTrue(name.startsWith("Create Flippies PLC compiler"), "own threads, not a shared pool: " + name);

        // Room again once they are done
        CompletableFuture<String> after = PlcToolchain.submit(() -> "ok");
        assertNotNull(after);
        assertEquals("ok", after.get(10, TimeUnit.SECONDS));
    }

    /** Whatever a job throws, an Error included, comes back through the future and not out of a thread. */
    @Test
    void aThrowingJobCompletesExceptionally() {
        CompletableFuture<String> job = PlcToolchain.submit(() -> {
            throw new OutOfMemoryError("test");
        });
        assertNotNull(job);
        CompletionException thrown = assertThrows(CompletionException.class, job::join);
        assertTrue(thrown.getCause() instanceof OutOfMemoryError);
    }

    @Test
    void anAsyncCompileReturnsTheSameAsASyncOne() throws Exception {
        String sketch = "void setup() { pinMode(DO0, OUTPUT); }\nvoid loop() { digitalWrite(DO0, HIGH); }\n";
        CompletableFuture<CompileResult> async = PlcToolchain.compileAsync(sketch);
        assertNotNull(async);
        CompileResult result = async.get(30, TimeUnit.SECONDS);
        assertTrue(result.ok(), () -> String.join("\n", PlcToolchain.report(result)));
        assertEquals(PlcToolchain.compile(sketch).program().flashBytes(), result.program().flashBytes());
    }

    /**
     * Regression: the frozen {@code Diagnostic.toString()} lowercases in the default
     * locale, which under Turkish prints "warning" with a dotless i. The report goes
     * through {@link PlcToolchain#format}, which does not.
     */
    @Test
    void reportLinesAreLocaleIndependent() {
        Locale before = Locale.getDefault();
        try {
            Locale.setDefault(Locale.forLanguageTag("tr-TR"));
            Diagnostic warning = new Diagnostic(Diagnostic.Severity.WARNING, 3, 7, "unused variable 'i'");
            assertEquals("sketch.ino:3:7: warning: unused variable 'i'", PlcToolchain.format(warning));
            CompileResult failed = new CompileResult(null, List.of(warning,
                    new Diagnostic(Diagnostic.Severity.ERROR, 4, 1, "expected ';'")));
            List<String> lines = PlcToolchain.report(failed);
            assertEquals("sketch.ino:3:7: warning: unused variable 'i'", lines.get(0));
            assertEquals("sketch.ino:4:1: error: expected ';'", lines.get(1));
            assertEquals("Compilation error: exit status 1", lines.get(2));
        } finally {
            Locale.setDefault(before);
        }
    }

    @Test
    void reportLinesParseBack() {
        Diagnostic error = PlcToolchain.parse("sketch.ino:12:5: error: 'x' was not declared in this scope");
        assertNotNull(error);
        assertEquals(Diagnostic.Severity.ERROR, error.severity());
        assertEquals(12, error.line());
        assertEquals(5, error.column());

        assertEquals(Diagnostic.Severity.NOTE, PlcToolchain.parse("sketch.ino:1:1: note: here").severity());
        assertEquals(Diagnostic.Severity.WARNING, PlcToolchain.parse("sketch.ino:2:3: warning: w").severity());
        // What the frozen record prints under a Turkish default locale
        Diagnostic turkish = PlcToolchain.parse("sketch.ino:2:3: warnıng: unused");
        assertNotNull(turkish, "a dotless i still reads as a warning");
        assertEquals(Diagnostic.Severity.WARNING, turkish.severity());
        assertEquals(Diagnostic.Severity.ERROR, PlcToolchain.parse("sketch.ino:2:3: ERROR: e").severity());

        assertNull(PlcToolchain.parse("Compilation error: exit status 1"));
        assertNull(PlcToolchain.parse("Sketch uses 100 bytes (0%) of program storage space."));
        assertNull(PlcToolchain.parse("sketch.ino:99999999999:1: error: overflow"), "an absurd line is not a crash");
    }

    @Test
    void failedIsAPositionlessError() {
        CompileResult result = PlcToolchain.failed("internal compiler error: boom");
        assertFalse(result.ok());
        assertEquals("internal compiler error: boom", PlcToolchain.report(result).get(0));
        assertEquals(Diagnostic.Severity.ERROR, PlcToolchain.firstError(result).severity());
    }
}
