package com.nectariich.create_flippies.content.plc;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.nectariich.create_flippies.content.plc.mcu.CompileResult;
import com.nectariich.create_flippies.content.plc.mcu.CompiledProgram;
import com.nectariich.create_flippies.content.plc.mcu.Diagnostic;
import com.nectariich.create_flippies.content.plc.mcu.Stm32f103cb;
import com.nectariich.create_flippies.content.plc.mcu.arduino.ArduinoCore;
import com.nectariich.create_flippies.content.plc.mcu.lang.SketchCompiler;

/**
 * The one way the Minecraft side compiles a sketch, on either side: the menu's Verify
 * on the client, Upload and the cold boot after a load on the server.
 *
 * Both sides go through here so they cannot disagree about what a sketch is — the
 * client's Verify is only worth anything if it says what the server's Upload will
 * say. And both are protected from the toolchain itself: a compiler is a large piece
 * of code fed arbitrary text, and a bug in it must come back as a diagnostic in the
 * menu rather than as an exception on the server thread or a crashed client.
 *
 * Plain Java, like {@link PlcBoard}: nothing here needs the game.
 */
public final class PlcToolchain {

    /**
     * The longest sketch the menu accepts, in characters. Generous for anything
     * written by hand for a 128 KB part — a 2000-line sketch is around 60 KB — and
     * small enough that the whole of one travels in a handful of packets.
     */
    public static final int MAX_SOURCE_CHARS = 65536;

    /** And in UTF-8 bytes, which is what is actually sent and stored. */
    public static final int MAX_SOURCE_BYTES = MAX_SOURCE_CHARS * 3;

    /**
     * Threads compiling at once, in the whole JVM. A compile is single-threaded and
     * cannot be interrupted, and a hostile sketch (a macro that expands to a million
     * tokens) can keep one busy for seconds, so the toolchain gets threads of its own
     * rather than a share of {@code Util.backgroundExecutor()}: that pool is the one
     * chunk generation and loading run on, and a queue of compiles in it stalls the
     * world. Two, so that one slow sketch does not hold up everyone else's.
     */
    static final int COMPILER_THREADS = 2;

    /**
     * Compiles that may wait for a thread. Past this {@link #compileAsync} refuses
     * rather than queueing without limit: an upload is answered "busy", and a PLC
     * booting after a load simply asks again on its next tick.
     */
    static final int COMPILE_QUEUE = 16;

    private static final ThreadPoolExecutor COMPILER = compiler();

    private PlcToolchain() {}

    private static ThreadPoolExecutor compiler() {
        AtomicInteger count = new AtomicInteger();
        ThreadFactory factory = runnable -> {
            Thread thread = new Thread(runnable, "Create Flippies PLC compiler " + count.incrementAndGet());
            // Never what keeps a server from shutting down, and never ahead of the game
            thread.setDaemon(true);
            thread.setPriority(Thread.NORM_PRIORITY - 1);
            return thread;
        };
        ThreadPoolExecutor executor = new ThreadPoolExecutor(COMPILER_THREADS, COMPILER_THREADS, 30,
                TimeUnit.SECONDS, new ArrayBlockingQueue<>(COMPILE_QUEUE), factory,
                new ThreadPoolExecutor.AbortPolicy());
        executor.allowCoreThreadTimeOut(true);
        return executor;
    }

    /**
     * Starts {@link #compile} on the toolchain's own threads.
     *
     * Whatever the compile throws, an {@link OutOfMemoryError} included, completes the
     * future exceptionally rather than escaping: a caller joining it gets a
     * {@link java.util.concurrent.CompletionException} to report.
     *
     * @return the result to come, or null if {@link #COMPILE_QUEUE} compiles are
     *         already waiting and this one was not started
     */
    public static CompletableFuture<CompileResult> compileAsync(String source) {
        return submit(() -> compile(source));
    }

    /**
     * Runs any job on the compiler threads, under the same bound; what
     * {@link #compileAsync} does with a compile. Separate so that the bound can be
     * tested with jobs that wait on the test rather than with sketches slow enough
     * to fill it.
     */
    static <T> CompletableFuture<T> submit(Supplier<T> job) {
        try {
            return CompletableFuture.supplyAsync(job, COMPILER);
        } catch (RejectedExecutionException e) {
            return null;
        }
    }

    /**
     * Compiles a sketch for this board, never throwing.
     *
     * Only the compiler's own failures are caught, not the JVM's: a
     * {@link StackOverflowError} is how a recursive-descent parser fails on a
     * pathologically nested expression, so it is a compile error like any other;
     * running out of memory is not something the compiler did.
     */
    public static CompileResult compile(String source) {
        try {
            CompileResult result = SketchCompiler.compile(source, ArduinoCore.INSTANCE);
            if (result != null)
                return result;
            return failure("internal compiler error: no result");
        } catch (RuntimeException | StackOverflowError e) {
            return failure("internal compiler error: " + e);
        }
    }

    /** A failed build with one positionless error, for when the toolchain itself could not say. */
    public static CompileResult failed(String message) {
        return failure(message);
    }

    private static CompileResult failure(String message) {
        return new CompileResult(null, List.of(new Diagnostic(Diagnostic.Severity.ERROR, 0, 0, message)));
    }

    /**
     * What arduino-cli prints for a build: every diagnostic, then either the two
     * memory lines or its failure line. Kept in its words, untranslated, because it
     * is a tool's output and those are the words anyone searching for them will find.
     */
    public static List<String> report(CompileResult result) {
        List<String> lines = new ArrayList<>();
        for (Diagnostic diagnostic : result.diagnostics())
            lines.add(diagnostic.line() > 0 ? format(diagnostic) : diagnostic.message());
        CompiledProgram program = result.program();
        if (program == null) {
            lines.add("Compilation error: exit status 1");
            return lines;
        }
        lines.addAll(usage(program.flashBytes(), program.staticRamBytes()));
        return lines;
    }

    /** The two lines arduino-cli prints after a successful build. */
    public static List<String> usage(int flashBytes, int staticRamBytes) {
        int flashMax = Stm32f103cb.FLASH_SIZE;
        int ramMax = Stm32f103cb.SRAM_SIZE;
        return List.of(
                "Sketch uses " + flashBytes + " bytes (" + percent(flashBytes, flashMax)
                        + "%) of program storage space. Maximum is " + flashMax + " bytes.",
                "Global variables use " + staticRamBytes + " bytes (" + percent(staticRamBytes, ramMax)
                        + "%) of dynamic memory, leaving " + (ramMax - staticRamBytes)
                        + " bytes for local variables. Maximum is " + ramMax + " bytes.");
    }

    /**
     * A diagnostic as GCC prints it: {@code sketch.ino:12:5: error: ...}. The same
     * text as {@link Diagnostic#toString()}, but with the severity lowercased in the
     * root locale: under a Turkish default locale that record's own toString() prints
     * "warning" with a dotless i, which the menu's console would then fail to
     * recognise as a warning.
     */
    public static String format(Diagnostic diagnostic) {
        return "sketch.ino:" + diagnostic.line() + ":" + diagnostic.column() + ": "
                + diagnostic.severity().name().toLowerCase(Locale.ROOT) + ": " + diagnostic.message();
    }

    /**
     * A diagnostic line as printed by {@link #format}. Case-insensitive and
     * Unicode-aware, so that a severity lowercased under some other locale's rules —
     * "warning" with a dotless i under Turkish, from the frozen
     * {@link Diagnostic#toString()}, which does not use the root locale — is still
     * recognised.
     */
    private static final Pattern DIAGNOSTIC = Pattern.compile(
            "^sketch\\.ino:(\\d+):(\\d+): (error|warning|note): .*", Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);

    /**
     * Reads a report line back into where it points and how bad it is, for the menu's
     * console to colour it and jump to it; null for a line that is not a diagnostic.
     * The message is not kept, only the position and severity.
     */
    public static Diagnostic parse(String text) {
        Matcher m = DIAGNOSTIC.matcher(text);
        if (!m.matches())
            return null;
        int line, column;
        try {
            line = Integer.parseInt(m.group(1));
            column = Integer.parseInt(m.group(2));
        } catch (NumberFormatException e) {
            return null;
        }
        // By first letter, which every locale's lowercasing leaves alone for these three
        Diagnostic.Severity severity = switch (Character.toLowerCase(m.group(3).charAt(0))) {
            case 'e' -> Diagnostic.Severity.ERROR;
            case 'w' -> Diagnostic.Severity.WARNING;
            default -> Diagnostic.Severity.NOTE;
        };
        return new Diagnostic(severity, line, column, "");
    }

    /** The first error, for a one-line summary; null if there is none. */
    public static Diagnostic firstError(CompileResult result) {
        for (Diagnostic diagnostic : result.diagnostics())
            if (diagnostic.severity() == Diagnostic.Severity.ERROR)
                return diagnostic;
        return null;
    }

    /** Truncated, as arduino-cli's integer percentages are. */
    private static long percent(long part, long whole) {
        return part * 100 / whole;
    }
}
