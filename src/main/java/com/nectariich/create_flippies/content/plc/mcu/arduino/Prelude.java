package com.nectariich.create_flippies.content.plc.mcu.arduino;

import com.nectariich.create_flippies.content.plc.mcu.ArduinoPins;

/**
 * The C source standing in for {@code Arduino.h}: what STM32duino's Arduino.h,
 * wiring_constants.h, binary.h, avr/pgmspace.h and the variant header declare, cut
 * down to this board and to base Arduino. Mostly macros, typedefs and constants;
 * nearly every function is a {@link Fn builtin} with its own prototype, so nothing is
 * declared twice. The exceptions are written here in C because they must call back
 * into the sketch, which a builtin cannot: {@code qsort} and {@code bsearch} (newlib's
 * own algorithms, so equal keys end up in the same order as on the board and the
 * comparator is called the same number of times). The variant's {@code digitalPin[]}
 * table and CMSIS's {@code SystemCoreClock} are here too,
 * as the data they are on the chip. The linker drops all of them from a
 * sketch that does not use them, as {@code --gc-sections} would.
 *
 * It is plain preprocessor + C++ declarations, valid for any conforming front end.
 * {@code true}/{@code false} are not defined: in C++ they are keywords, and defining
 * them as ints (as AVR's Arduino.h does) would change {@code auto b = true;}.
 * Integer typedefs follow arm-none-eabi-gcc, where {@code int32_t} is {@code long}.
 *
 * <h2>abs, round, min, max</h2>
 *
 * STM32duino 2.x builds on ArduinoCore-API, whose {@code Common.h} defines no
 * {@code abs} or {@code round} macro for C++: {@code round} is libm's
 * {@code double round(double)} and {@code abs} is {@code std::abs}, with overloads for
 * int, long, long long, float and double. So {@code round(12.345 * 10) / 10} is 12.3
 * there, not AVR's 12, and {@code abs(i++)} increments once; both are builtins here
 * (see {@link MathBuiltins}). {@code abs} of an unsigned value is ambiguous, as g++
 * says it is. {@code min} and {@code max} are templates there; this compiler has no
 * templates or statement expressions, so they stay AVR-style macros, which evaluate
 * the chosen argument twice: {@code min(x++, 5)} can increment {@code x} twice. That
 * is the one known divergence, and it only bites arguments with side effects.
 *
 * <h2>F()</h2>
 *
 * As in ArduinoCore-API, {@code F("...")} is the literal cast to
 * {@code const __FlashStringHelper *}, an incomplete struct, so a sketch can overload a
 * helper on {@code const char *} and {@code const __FlashStringHelper *} and have the
 * F() one chosen. On the F1 flash is in the address space, so the pointer is the
 * literal's own address. String's constructor, {@code =}, {@code +=}, {@code concat}
 * and {@code +} take one (the language runtime's side); handing one to a function
 * that wants {@code const char *} needs a cast, as it does with g++.
 *
 * <h2>Port registers</h2>
 *
 * {@code digitalPinToPort}, {@code portOutputRegister} and the rest hand out pointers
 * into GPIO's register block, which the emulated bus does not map (the sketch reaches
 * I/O through the core's functions only). They expand to an undeclared name that says
 * so and points at the fast functions to use instead, so the sketch fails to compile
 * rather than bus-faulting at run time.
 */
final class Prelude {

    static String build() {
        StringBuilder s = new StringBuilder(24_000);
        s.append("""
                /* Arduino.h -- S32F103-D PLC: STM32F103CBT6, STM32duino core, base Arduino only. */
                #ifndef Arduino_h
                #define Arduino_h

                #define ARDUINO 10819
                #define ARDUINO_ARCH_STM32 1
                #define STM32F1xx 1
                #define STM32F103xB 1
                #define ARDUINO_S32F103_D 1
                #define F_CPU 72000000L

                typedef signed char int8_t;
                typedef unsigned char uint8_t;
                typedef short int16_t;
                typedef unsigned short uint16_t;
                typedef long int32_t;
                typedef unsigned long uint32_t;
                typedef long long int64_t;
                typedef unsigned long long uint64_t;
                typedef unsigned int size_t;
                typedef int ptrdiff_t;
                typedef int intptr_t;
                typedef unsigned int uintptr_t;
                typedef bool boolean;
                typedef uint8_t byte;
                typedef unsigned int word;
                typedef void (*voidFuncPtr)(void);

                #define CHAR_BIT 8
                #define SCHAR_MIN (-128)
                #define SCHAR_MAX 127
                #define UCHAR_MAX 255
                #define CHAR_MIN 0
                #define CHAR_MAX 255
                #define SHRT_MIN (-32768)
                #define SHRT_MAX 32767
                #define USHRT_MAX 65535
                #define INT_MAX 2147483647
                #define INT_MIN (-INT_MAX - 1)
                #define UINT_MAX 4294967295U
                #define LONG_MAX 2147483647L
                #define LONG_MIN (-LONG_MAX - 1L)
                #define ULONG_MAX 4294967295UL
                #define LLONG_MAX 9223372036854775807LL
                #define LLONG_MIN (-LLONG_MAX - 1LL)
                #define ULLONG_MAX 18446744073709551615ULL
                #define INT8_MIN (-128)
                #define INT8_MAX 127
                #define UINT8_MAX 255
                #define INT16_MIN (-32768)
                #define INT16_MAX 32767
                #define UINT16_MAX 65535
                #define INT32_MIN (-2147483647L - 1L)
                #define INT32_MAX 2147483647L
                #define UINT32_MAX 4294967295UL
                #define INT64_MIN (-9223372036854775807LL - 1LL)
                #define INT64_MAX 9223372036854775807LL
                #define UINT64_MAX 18446744073709551615ULL
                #define SIZE_MAX 4294967295U
                #define RAND_MAX 2147483647
                #define FLT_MAX 3.40282347e+38F
                #define FLT_MIN 1.17549435e-38F
                #define FLT_EPSILON 1.19209290e-07F
                #define DBL_MAX 1.7976931348623157e+308
                #define DBL_MIN 2.2250738585072014e-308
                #define DBL_EPSILON 2.2204460492503131e-16

                #define M_E 2.7182818284590452354
                #define M_LOG2E 1.4426950408889634074
                #define M_LOG10E 0.43429448190325182765
                #define M_LN2 0.69314718055994530942
                #define M_LN10 2.30258509299404568402
                #define M_PI 3.14159265358979323846
                #define M_PI_2 1.57079632679489661923
                #define M_PI_4 0.78539816339744830962
                #define M_1_PI 0.31830988618379067154
                #define M_2_PI 0.63661977236758134308
                #define M_2_SQRTPI 1.12837916709551257390
                #define M_SQRT2 1.41421356237309504880
                #define M_SQRT1_2 0.70710678118654752440
                #define INFINITY (__builtin_inff())
                #define NAN (__builtin_nanf(""))
                #define HUGE_VAL (__builtin_huge_val())

                #define HIGH 0x1
                #define LOW 0x0

                #define INPUT 0x0
                #define OUTPUT 0x1
                #define INPUT_PULLUP 0x2
                #define INPUT_PULLDOWN 0x3
                #define INPUT_ANALOG 0x4
                #define OUTPUT_OPEN_DRAIN 0x5
                #define INPUT_FLOATING INPUT

                #define CHANGE 2
                #define FALLING 3
                #define RISING 4

                #define LSBFIRST 0
                #define MSBFIRST 1

                #define DEC 10
                #define HEX 16
                #define OCT 8
                #define BIN 2

                #define AR_DEFAULT 0

                #define PI 3.1415926535897932384626433832795
                #define HALF_PI 1.5707963267948966192313216916398
                #define TWO_PI 6.283185307179586476925286766559
                #define DEG_TO_RAD 0.017453292519943295769236907684886
                #define RAD_TO_DEG 57.295779513082320876798154814105
                #define EULER 2.718281828459045235360287471352

                #define min(a, b) ((a) < (b) ? (a) : (b))
                #define max(a, b) ((a) > (b) ? (a) : (b))
                #define constrain(amt, low, high) ((amt) < (low) ? (low) : ((amt) > (high) ? (high) : (amt)))
                #define radians(deg) ((deg) * DEG_TO_RAD)
                #define degrees(rad) ((rad) * RAD_TO_DEG)
                #define sq(x) ((x) * (x))

                #define lowByte(w) ((uint8_t)((w) & 0xff))
                #define highByte(w) ((uint8_t)((w) >> 8))
                #define bitRead(value, bit) (((value) >> (bit)) & 0x01)
                #define bitSet(value, bit) ((value) |= (1UL << (bit)))
                #define bitClear(value, bit) ((value) &= ~(1UL << (bit)))
                #define bitToggle(value, bit) ((value) ^= (1UL << (bit)))
                #define bitWrite(value, bit, bitvalue) ((bitvalue) ? bitSet(value, bit) : bitClear(value, bit))
                #define bit(b) (1UL << (b))
                #define _BV(bit) (1 << (bit))
                #define word(...) makeWord(__VA_ARGS__)

                #define interrupts() __enable_irq()
                #define noInterrupts() __disable_irq()

                #define clockCyclesPerMicrosecond() (F_CPU / 1000000L)
                #define clockCyclesToMicroseconds(a) (((a) * 1000L) / (F_CPU / 1000L))
                #define microsecondsToClockCycles(a) ((a) * (F_CPU / 1000000L))

                #define PROGMEM
                #define PGM_P const char *
                #define PGM_VOID_P const void *
                #define PSTR(s) (s)
                struct __FlashStringHelper;
                #define F(string_literal) ((const __FlashStringHelper *)(string_literal))
                #define pgm_read_byte(addr) (*(const unsigned char *)(addr))
                #define pgm_read_word(addr) (*(const unsigned short *)(addr))
                #define pgm_read_dword(addr) (*(const unsigned long *)(addr))
                #define pgm_read_float(addr) (*(const float *)(addr))
                #define pgm_read_ptr(addr) (*(void * const *)(addr))
                #define pgm_read_byte_near(addr) pgm_read_byte(addr)
                #define pgm_read_word_near(addr) pgm_read_word(addr)
                #define pgm_read_dword_near(addr) pgm_read_dword(addr)
                #define pgm_read_float_near(addr) pgm_read_float(addr)
                #define pgm_read_ptr_near(addr) pgm_read_ptr(addr)
                #define pgm_read_byte_far(addr) pgm_read_byte(addr)
                #define pgm_read_word_far(addr) pgm_read_word(addr)
                #define pgm_read_dword_far(addr) pgm_read_dword(addr)
                #define pgm_read_float_far(addr) pgm_read_float(addr)
                #define pgm_read_ptr_far(addr) pgm_read_ptr(addr)
                #define memcpy_P memcpy
                #define memcmp_P memcmp
                #define strcpy_P strcpy
                #define strncpy_P strncpy
                #define strcat_P strcat
                #define strncat_P strncat
                #define strcmp_P strcmp
                #define strncmp_P strncmp
                #define strcasecmp_P strcasecmp
                #define strlen_P strlen
                #define strchr_P strchr
                #define strstr_P strstr
                #define sprintf_P sprintf
                #define snprintf_P snprintf

                #define HAL_MAX_DELAY 0xFFFFFFFFU
                #define PWM_FREQUENCY 1000
                #define PWM_RESOLUTION 8
                #define ADC_RESOLUTION 10
                uint32_t SystemCoreClock = 72000000UL;

                #define digitalPinToPort(p) digitalPinToPort_is_not_available_on_this_PLC__use_digitalWriteFast_or_digitalReadFast(p)
                #define portOutputRegister(port) portOutputRegister_is_not_available_on_this_PLC__use_digitalWriteFast(port)
                #define portInputRegister(port) portInputRegister_is_not_available_on_this_PLC__use_digitalReadFast(port)
                #define portModeRegister(port) portModeRegister_is_not_available_on_this_PLC__use_pinMode(port)
                #define portSetRegister(port) portSetRegister_is_not_available_on_this_PLC__use_digitalWriteFast(port)
                #define portClearRegister(port) portClearRegister_is_not_available_on_this_PLC__use_digitalWriteFast(port)

                """);

        s.append("#define NUM_DIGITAL_PINS ").append(ArduinoPins.COUNT).append('\n');
        s.append("#define NUM_ANALOG_INPUTS 8\n");
        s.append("#define LED_BUILTIN ").append(ArduinoPins.STATUS).append('\n');
        s.append("#define NOT_AN_INTERRUPT ((uint32_t)-1)\n");
        s.append("#define digitalPinToInterrupt(p) (((uint32_t)(p) < (uint32_t)NUM_DIGITAL_PINS) ? (p) : NOT_AN_INTERRUPT)\n");
        s.append("#define digitalPinIsValid(p) ((uint32_t)(p) < (uint32_t)NUM_DIGITAL_PINS)\n");
        s.append("#define digitalPinHasPWM(p) ((p) == 8 || (p) == 9 || (p) == 14 || (p) == 15 || (p) == 16)\n\n");

        for (int pin = 0; pin < ArduinoPins.COUNT; pin++)
            s.append("#define ").append(ArduinoPins.name(pin)).append(' ').append(pin).append('\n');
        s.append('\n');
        for (int i = 0; i < ArduinoPins.IO_PER_BANK; i++)
            s.append("#define A").append(i).append(' ').append(ArduinoPins.FIRST_DI + i).append('\n');
        for (int i = 0; i < ArduinoPins.IO_PER_BANK; i++)
            s.append("#define DI").append(i).append(' ').append(ArduinoPins.FIRST_DI + i).append('\n');
        for (int i = 0; i < ArduinoPins.IO_PER_BANK; i++)
            s.append("#define DO").append(i).append(' ').append(ArduinoPins.FIRST_DO + i).append('\n');
        s.append("#define STLED ").append(ArduinoPins.STATUS).append("\n\n");
        pinNames(s);
        s.append(LIBC_CALLBACKS);

        // binary.h: B0 .. B11111111, every spelling of up to eight binary digits
        for (int digits = 1; digits <= 8; digits++) {
            for (int value = 0; value < (1 << digits); value++) {
                String bits = Integer.toBinaryString(value);
                bits = "0".repeat(digits - bits.length()) + bits;
                s.append("#define B").append(bits).append(' ').append(value).append('\n');
            }
        }
        s.append("\n#endif /* Arduino_h */\n");
        return s.toString();
    }

    /**
     * PinName as STM32duino's PinNames.h encodes it, {@code (port << 4) | bit} (which is
     * {@link ArduinoPins#PORT_PIN}'s encoding too), for the pins this package has; NC is
     * -1. Then the variant's {@code digitalPin[]} table (Arduino number to PinName,
     * const, so in flash) and the macros built on it.
     */
    private static void pinNames(StringBuilder s) {
        s.append("typedef enum {\n");
        for (int pin = 0; pin < ArduinoPins.COUNT; pin++)
            s.append("    ").append(pinName(pin)).append(" = 0x").append(Integer.toHexString(ArduinoPins.PORT_PIN[pin])).append(",\n");
        s.append("    NC = (int)0xFFFFFFFF\n} PinName;\n\n");
        s.append("const PinName digitalPin[] = {");
        for (int pin = 0; pin < ArduinoPins.COUNT; pin++)
            s.append(pin == 0 ? " " : ", ").append(pinName(pin));
        s.append(" };\n");
        s.append("""
                #define STM_PORT(X) (((uint32_t)(X) >> 4) & 0xF)
                #define STM_PIN(X) ((uint32_t)(X) & 0xF)
                #define STM_GPIO_PIN(X) ((uint16_t)(1 << STM_PIN(X)))
                #define STM_VALID_PINNAME(X) (STM_PORT(X) <= 2)
                #define digitalPinToPinName(p) (((uint32_t)(p) < (uint32_t)NUM_DIGITAL_PINS) ? digitalPin[(p)] : NC)
                #define digitalPinToBitMask(p) (STM_GPIO_PIN(digitalPinToPinName(p)))
                #define analogInputToDigitalPin(p) (p)

                """);
    }

    /** "PA_0" for PA0: the PinName spelling. */
    static String pinName(int arduinoPin) {
        String name = ArduinoPins.name(arduinoPin);
        return name.substring(0, 2) + "_" + name.substring(2);
    }

    /**
     * newlib's qsort.c and bsearch.c, transcribed. qsort is the BSD "engineered"
     * quicksort newlib carries: median of three (of nine above 40 elements), a
     * three-way partition, insertion sort below 7 elements or after a pass that swapped
     * nothing, then the smaller side first and the larger by going round again. They
     * call the sketch's comparator, so they are ordinary code here and cost what they
     * execute; equal keys end up where they do on the board.
     *
     * Two liberties, both invisible to the sketch's results: newlib recurses on the
     * smaller side where this keeps a small array of pending ranges (the compiler marks
     * a function used as soon as anything calls it, itself included, so a recursive
     * qsort, or one calling helper functions, would be linked into every sketch), and
     * newlib's swap/vecswap loops are one builtin, {@code __qsort_swap}, which costs
     * what those inlined loops cost and keeps the code-size estimate near the real
     * ~0.8 KB rather than counting each inlined copy. The comparator is called on the
     * same pairs in the same order.
     */
    private static final String LIBC_CALLBACKS = """
            #define __QSORT_SWAP(a, b) __qsort_swap((a), (b), es, swaptype)
            #define __QSORT_SWAPFUNC(a, b, n) __qsort_swap((a), (b), (n), swaptype == 0 ? 1 : swaptype)
            #define __QSORT_MED3(a, b, c) (cmp((a), (b)) < 0 ? (cmp((b), (c)) < 0 ? (b) : (cmp((a), (c)) < 0 ? (c) : (a))) : (cmp((b), (c)) > 0 ? (b) : (cmp((a), (c)) < 0 ? (a) : (c))))

            void qsort(void *base, size_t n, size_t es, int (*cmp)(const void *, const void *)) {
                char *a = (char *)base;
                char *pa, *pb, *pc, *pd, *pl, *pm, *pn;
                size_t d1, d2;
                int cmp_result;
                int swaptype, swap_cnt;
                char *pending_a[32];
                size_t pending_n[32];
                int pending = 0;
                for (;;) {
                    swaptype = ((uintptr_t)a % sizeof(long) || es % sizeof(long)) ? 2 : (es == sizeof(long) ? 0 : 1);
                    swap_cnt = 0;
                    if (n < 7) {
                        for (pm = a + es; pm < a + n * es; pm += es)
                            for (pl = pm; pl > a && cmp(pl - es, pl) > 0; pl -= es)
                                __QSORT_SWAP(pl, pl - es);
                        goto next;
                    }
                    pm = a + (n / 2) * es;
                    if (n > 7) {
                        pl = a;
                        pn = a + (n - 1) * es;
                        if (n > 40) {
                            size_t d = (n / 8) * es;
                            pl = __QSORT_MED3(pl, pl + d, pl + 2 * d);
                            pm = __QSORT_MED3(pm - d, pm, pm + d);
                            pn = __QSORT_MED3(pn - 2 * d, pn - d, pn);
                        }
                        pm = __QSORT_MED3(pl, pm, pn);
                    }
                    __QSORT_SWAP(a, pm);
                    pa = pb = a + es;
                    pc = pd = a + (n - 1) * es;
                    for (;;) {
                        while (pb <= pc && (cmp_result = cmp(pb, a)) <= 0) {
                            if (cmp_result == 0) {
                                swap_cnt = 1;
                                __QSORT_SWAP(pa, pb);
                                pa += es;
                            }
                            pb += es;
                        }
                        while (pb <= pc && (cmp_result = cmp(pc, a)) >= 0) {
                            if (cmp_result == 0) {
                                swap_cnt = 1;
                                __QSORT_SWAP(pc, pd);
                                pd -= es;
                            }
                            pc -= es;
                        }
                        if (pb > pc)
                            break;
                        __QSORT_SWAP(pb, pc);
                        swap_cnt = 1;
                        pb += es;
                        pc -= es;
                    }
                    if (swap_cnt == 0) {
                        for (pm = a + es; pm < a + n * es; pm += es)
                            for (pl = pm; pl > a && cmp(pl - es, pl) > 0; pl -= es)
                                __QSORT_SWAP(pl, pl - es);
                        goto next;
                    }
                    pn = a + n * es;
                    d1 = (size_t)(pa - a) < (size_t)(pb - pa) ? (size_t)(pa - a) : (size_t)(pb - pa);
                    if (d1 > 0)
                        __QSORT_SWAPFUNC(a, pb - d1, d1);
                    d1 = (size_t)(pd - pc) < (size_t)(pn - pd - es) ? (size_t)(pd - pc) : (size_t)(pn - pd - es);
                    if (d1 > 0)
                        __QSORT_SWAPFUNC(pb, pn - d1, d1);
                    d1 = pb - pa;
                    d2 = pd - pc;
                    if (d1 <= d2) {
                        /* the left side now, the right side after it */
                        if (d2 > es) {
                            pending_a[pending] = pn - d2;
                            pending_n[pending++] = d2 / es;
                        }
                        if (d1 > es) {
                            n = d1 / es;
                            continue;
                        }
                    } else {
                        /* the right side now, the left side after it */
                        if (d1 > es) {
                            pending_a[pending] = a;
                            pending_n[pending++] = d1 / es;
                        }
                        if (d2 > es) {
                            a = pn - d2;
                            n = d2 / es;
                            continue;
                        }
                    }
                next:
                    if (pending == 0)
                        return;
                    pending--;
                    a = pending_a[pending];
                    n = pending_n[pending];
                }
            }

            void *bsearch(const void *key, const void *base, size_t nmemb, size_t size, int (*compar)(const void *, const void *)) {
                if (size == 0 || nmemb == 0)
                    return NULL;
                size_t lower = 0;
                size_t upper = nmemb;
                while (lower < upper) {
                    size_t trial = (lower + upper) / 2;
                    void *p = (void *)((const char *)base + trial * size);
                    int result = compar(key, p);
                    if (result < 0)
                        upper = trial;
                    else if (result > 0)
                        lower = trial + 1;
                    else
                        return p;
                }
                return NULL;
            }

            """;

    private Prelude() {}
}
