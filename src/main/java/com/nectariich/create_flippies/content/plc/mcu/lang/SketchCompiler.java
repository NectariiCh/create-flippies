package com.nectariich.create_flippies.content.plc.mcu.lang;

import java.util.List;

import com.nectariich.create_flippies.content.plc.mcu.ArduinoLibrary;
import com.nectariich.create_flippies.content.plc.mcu.CompileResult;
import com.nectariich.create_flippies.content.plc.mcu.CompiledProgram;
import com.nectariich.create_flippies.content.plc.mcu.Diagnostic;
import com.nectariich.create_flippies.content.plc.mcu.Stm32f103cb;

/**
 * The toolchain: an .ino in, a linked image or GCC-style diagnostics out, as
 * arduino-cli builds a sketch for STM32duino with arm-none-eabi-gcc -Os.
 *
 * The phases are the usual ones: {@link Preprocessor} (with the core's prelude
 * standing for {@code #include <Arduino.h>}), {@link Parser}, {@link Sema} (types,
 * overloads, initialisers; the .ino's automatic prototypes), {@link CodeGen}
 * (bytecode with Cortex-M3 costs and Thumb-2 size estimates) and {@link Image}'s
 * linker (layout, relocation, the FLASH and RAM region checks).
 *
 * Pure and thread-safe: nothing is shared between calls, so the client can run it
 * for a Verify button while the server compiles the same sketch for an upload.
 *
 * <h2>Its own stack</h2>
 * The parser and the semantic passes recurse along the program's nesting, and a
 * left-leaning chain such as {@code a + b + c + ...} or a long else-if chain is as
 * deep as it is long. GCC takes thousands of those; on the caller's thread (1 MB of
 * stack on the server and on Minecraft's worker threads) that would end in a
 * StackOverflowError a few hundred levels in, so each compile runs on a short-lived
 * thread of its own with {@link #STACK_BYTES} of stack, which a 64 KB sketch cannot
 * exhaust, and the parser bounds nesting itself ({@link Parser#MAX_NESTING}).
 */
public final class SketchCompiler {

    /** Stack for the compiling thread: reserved address space, committed only as deep as the compile goes. */
    static final long STACK_BYTES = 256L << 20;

    /**
     * What arm-none-eabi-gcc predefines for -mcpu=cortex-m3 -mthumb, what newlib's
     * stdint.h, limits.h, float.h and stddef.h provide (int32_t is long on this
     * target, size_t unsigned int), and the -D flags the STM32 platform passes.
     */
    static final String INTERNAL_PRELUDE = """
            #define __GNUC__ 12
            #define __GNUC_MINOR__ 2
            #define __GNUC_PATCHLEVEL__ 1
            #define __VERSION__ "12.2.1"
            #define __arm__ 1
            #define __thumb__ 1
            #define __thumb2__ 1
            #define __ARM_ARCH 7
            #define __ARM_ARCH_7M__ 1
            #define __ARM_ARCH_PROFILE 77
            #define __ARMEL__ 1
            #define __ARM_EABI__ 1
            #define __SOFTFP__ 1
            #define __CHAR_UNSIGNED__ 1
            #define __SIZEOF_INT__ 4
            #define __SIZEOF_LONG__ 4
            #define __SIZEOF_LONG_LONG__ 8
            #define __SIZEOF_SHORT__ 2
            #define __SIZEOF_POINTER__ 4
            #define __SIZEOF_FLOAT__ 4
            #define __SIZEOF_DOUBLE__ 8
            #define __SIZEOF_LONG_DOUBLE__ 8
            #define __SIZEOF_SIZE_T__ 4
            #define __INT_MAX__ 0x7fffffff
            #define __LONG_MAX__ 0x7fffffffL
            #define __SCHAR_MAX__ 0x7f
            #define __SHRT_MAX__ 0x7fff
            #define __LONG_LONG_MAX__ 0x7fffffffffffffffLL
            #define __STDC_HOSTED__ 1
            #define __OPTIMIZE__ 1
            #define __OPTIMIZE_SIZE__ 1
            #define __BYTE_ORDER__ 1234
            #define __ORDER_LITTLE_ENDIAN__ 1234
            #define __ORDER_BIG_ENDIAN__ 4321
            #define __cplusplus 201703L
            #define ARDUINO 10819
            #define ARDUINO_ARCH_STM32 1
            #define STM32F1xx 1
            #define STM32F103xB 1
            #define NULL 0
            #define CHAR_BIT 8
            #define SCHAR_MIN (-128)
            #define SCHAR_MAX 127
            #define UCHAR_MAX 255
            #define CHAR_MIN 0
            #define CHAR_MAX 255
            #define SHRT_MIN (-32768)
            #define SHRT_MAX 32767
            #define USHRT_MAX 65535
            #define INT_MIN (-2147483647 - 1)
            #define INT_MAX 2147483647
            #define UINT_MAX 4294967295U
            #define LONG_MIN (-2147483647L - 1)
            #define LONG_MAX 2147483647L
            #define ULONG_MAX 4294967295UL
            #define LLONG_MIN (-9223372036854775807LL - 1)
            #define LLONG_MAX 9223372036854775807LL
            #define ULLONG_MAX 18446744073709551615ULL
            #define INT8_MIN (-128)
            #define INT8_MAX 127
            #define UINT8_MAX 255
            #define INT16_MIN (-32768)
            #define INT16_MAX 32767
            #define UINT16_MAX 65535
            #define INT32_MIN (-2147483647L - 1)
            #define INT32_MAX 2147483647L
            #define UINT32_MAX 4294967295UL
            #define INT64_MIN (-9223372036854775807LL - 1)
            #define INT64_MAX 9223372036854775807LL
            #define UINT64_MAX 18446744073709551615ULL
            #define SIZE_MAX 4294967295U
            #define INTPTR_MAX 2147483647
            #define UINTPTR_MAX 4294967295U
            #define INT8_C(x) x
            #define UINT8_C(x) x
            #define INT16_C(x) x
            #define UINT16_C(x) x
            #define INT32_C(x) x ## L
            #define UINT32_C(x) x ## UL
            #define INT64_C(x) x ## LL
            #define UINT64_C(x) x ## ULL
            #define FLT_MAX 3.40282347e+38F
            #define FLT_MIN 1.17549435e-38F
            #define FLT_EPSILON 1.19209290e-7F
            #define FLT_DIG 6
            #define DBL_MAX 1.7976931348623157e+308
            #define DBL_MIN 2.2250738585072014e-308
            #define DBL_EPSILON 2.2204460492503131e-16
            #define DBL_DIG 15
            #define offsetof(t, m) ((size_t)&(((t*)0)->m))
            #define __builtin_offsetof(t, m) offsetof(t, m)
            #define __builtin_expect(x, y) (x)
            #define PROGMEM
            typedef signed char int8_t;
            typedef unsigned char uint8_t;
            typedef short int16_t;
            typedef unsigned short uint16_t;
            typedef long int32_t;
            typedef unsigned long uint32_t;
            typedef long long int64_t;
            typedef unsigned long long uint64_t;
            typedef signed char int_least8_t;
            typedef unsigned char uint_least8_t;
            typedef short int_least16_t;
            typedef unsigned short uint_least16_t;
            typedef long int_least32_t;
            typedef unsigned long uint_least32_t;
            typedef long long int_least64_t;
            typedef unsigned long long uint_least64_t;
            typedef int int_fast8_t;
            typedef unsigned int uint_fast8_t;
            typedef int int_fast16_t;
            typedef unsigned int uint_fast16_t;
            typedef int int_fast32_t;
            typedef unsigned int uint_fast32_t;
            typedef long long int_fast64_t;
            typedef unsigned long long uint_fast64_t;
            typedef long long intmax_t;
            typedef unsigned long long uintmax_t;
            typedef int intptr_t;
            typedef unsigned int uintptr_t;
            typedef unsigned int size_t;
            typedef int ssize_t;
            typedef int ptrdiff_t;
            """;

    public static CompileResult compile(String source, ArduinoLibrary library) {
        CompileResult[] result = new CompileResult[1];
        Throwable[] thrown = new Throwable[1];
        Thread t = new Thread(null, () -> {
            try {
                result[0] = compileHere(source, library);
            } catch (Throwable e) {
                thrown[0] = e;
            }
        }, "sketch-compiler", STACK_BYTES);
        t.setDaemon(true);
        t.start();
        boolean interrupted = false;
        while (true) {
            try {
                t.join();
                break;
            } catch (InterruptedException e) {
                interrupted = true;
            }
        }
        if (interrupted)
            Thread.currentThread().interrupt();
        if (thrown[0] instanceof OutOfMemoryError) {
            // Everything it allocated was the dead compile thread's, and is garbage now. The
            // known ways to get here (macro bombs, tokens built by ## and # doubling at each
            // level, huge static objects) are bounded before they allocate much; this
            // keeps an unknown one a failed compile rather than a dead worker thread.
            return new CompileResult(null, List.of(new Diagnostic(Diagnostic.Severity.ERROR, 0, 0,
                    "internal compiler error: the compiler ran out of memory")));
        }
        if (thrown[0] instanceof Error e)
            throw e;
        if (thrown[0] instanceof RuntimeException e)
            throw e;
        return result[0];
    }

    private static CompileResult compileHere(String source, ArduinoLibrary library) {
        Diagnostics d = new Diagnostics();
        try {
            List<Token> toks = Preprocessor.run(d,
                    new String[] { INTERNAL_PRELUDE, library.prelude(), source },
                    new int[] { Token.INTERNAL, Token.PRELUDE, Token.SKETCH });
            Parser parser = new Parser(toks, d);
            List<Ast.Node> unit = parser.translationUnit();
            Sema sema = new Sema(d, library);
            sema.parsedRanges = parser.rangeHi;
            Sym.Global emptyStr = sema.stringGlobal(new byte[0]);
            sema.run(unit);
            if (d.hasErrors())
                return new CompileResult(null, List.copyOf(d.list));
            CodeGen cg = new CodeGen(sema, d);
            cg.run();
            if (d.hasErrors())
                return new CompileResult(null, List.copyOf(d.list));
            Image img = Image.link(source, sema, cg, library, emptyStr, d);
            if (d.hasErrors()) {
                d.list.add(new Diagnostic(Diagnostic.Severity.NOTE, 0, 0, flashUsage(img.flashBytes())));
                d.list.add(new Diagnostic(Diagnostic.Severity.NOTE, 0, 0, ramUsage(img.staticRamBytes())));
                return new CompileResult(null, List.copyOf(d.list));
            }
            return new CompileResult(img, List.copyOf(d.list));
        } catch (Diagnostics.Abort a) {
            return new CompileResult(null, List.copyOf(d.list));
        } catch (RuntimeException | StackOverflowError e) {
            // Name where in the toolchain it broke, so a report from a player can be traced.
            StringBuilder where = new StringBuilder();
            for (StackTraceElement f : e.getStackTrace()) {
                if (f.getClassName().startsWith(SketchCompiler.class.getPackageName())) {
                    where.append(where.length() == 0 ? " at " : " < ").append(f.getClassName().substring(f.getClassName().lastIndexOf('.') + 1))
                            .append(':').append(f.getLineNumber());
                    if (where.length() > 200)
                        break;
                }
            }
            d.list.add(new Diagnostic(Diagnostic.Severity.ERROR, 0, 0, "internal compiler error: " + e + where));
            return new CompileResult(null, List.copyOf(d.list));
        }
    }

    /** arduino-cli's first size line: "Sketch uses N bytes (x%) of program storage space. Maximum is 131072 bytes." */
    public static String flashUsage(int bytes) {
        return "Sketch uses " + bytes + " bytes (" + (bytes * 100L / Stm32f103cb.FLASH_SIZE)
                + "%) of program storage space. Maximum is " + Stm32f103cb.FLASH_SIZE + " bytes.";
    }

    /** arduino-cli's second size line, about static RAM and what is left for the stack and heap. */
    public static String ramUsage(int bytes) {
        return "Global variables use " + bytes + " bytes (" + (bytes * 100L / Stm32f103cb.SRAM_SIZE)
                + "%) of dynamic memory, leaving " + (Stm32f103cb.SRAM_SIZE - bytes)
                + " bytes for local variables. Maximum is " + Stm32f103cb.SRAM_SIZE + " bytes.";
    }

    /** Both size lines for a linked program, as arduino-cli prints them after a successful build. */
    public static String[] usage(CompiledProgram program) {
        return new String[] { flashUsage(program.flashBytes()), ramUsage(program.staticRamBytes()) };
    }

    private SketchCompiler() {}
}
