# Small PLC (FWM32F103 IOP) — emulated STM32F103 running Arduino sketches

Mod: Create: Flippies, Forge 1.20.1, Create 6.0.7, Create: Power Grid 0.6.0. Repo root:
`C:\Users\nektr\Documents\Create Flippies`. Java 17 (`C:\Program Files\Java\jdk-17\bin`).
Base package `com.nectariich.create_flippies` (below: `cf`).

## What the user asked for (verbatim intent)

- The Blockbench model `src/main/resources/assets/create_flippies/models/block/plc_fwm32f103_iop_small_din.json`
  is the **8 DI / 8 DO, no-comms** version ("Pinout 1" of an STM32F103 PLC design).
- Build a small interpretation system that runs **base Arduino (.ino) code** on an emulated
  **STM32F103CBT6**: 72 MHz Cortex-M3 (no FPU), 128 KB flash, 20 KB SRAM. Simulate RAM, flash,
  clock speed, etc. faithfully.
- Power Grid cannot run at MCU clock speed: pin states are exchanged with Power Grid once per
  game tick (20 Hz, 50 ms windows).
- Programming: right-click the PLC with a screwdriver → menu. From there: edit code, flash
  (reflash), reset.
- Strip every pin the model does not have. No onboard status LED or reset button (the model has
  STLED and RESET *terminals*, which stay). No ethernet. **No data transfer at all**: no Serial /
  UART, SPI, I2C, Wire, Stream, USB, CAN. But **cover all of base Arduino** otherwise.
- **24 V logic for everything**, including the PLC's own supply.
- Compute **every operation** the PLC does and from that its **power consumption**; add the power
  consumed **through every pin** to the PLC's total.
- **Every pin limited to 1 A**: if exceeded, force that pin low (off) until the PLC is reset.

## Board (frozen)

Model terminals, in this exact terminal-index order (never reorder — Power Grid numbers terminals
positionally and saved wires depend on it):

| idx | model element | board signal | MCU pin | Arduino # | role |
|----|----|----|----|----|----|
| 0–7 | pin_a_00..pin_a_07 | DI0..DI7 | PA0..PA7 | 0..7 (A0..A7) | 24 V sourcing (PNP) inputs |
| 8 | VCC | +24 V supply | — | — | supply |
| 9 | GND | 0 V | — | — | supply return, reference for all I/O |
| 10–17 | pin_b_00..pin_b_07 | DO0..DO7 | PB0..PB7 | 8..15 | 24 V high-side (sourcing) outputs |
| 18 | STLED | STATUS | PA11 | 16 (LED_BUILTIN) | 24 V high-side output, same stage as DO |
| 19 | RESET | NRST via opto | NRST | — | 24 V input; ≥ 15 V holds the MCU in reset |

Pin-row geometry is in the model (rows at z 5.25–5.75 and 10.25–10.75, pitch 0.75 px, pins 0.5 px
wide, top at y 2.75 in the DIN frame). **User correction (after round 1):** the export is drawn upside
down. `tools/gen_plc.py` now turns it 180° about the vertical (x→16−x, z→16−z) and
`PlcSmallGeometry.pin` mirrors to match: row A (DI0..GND) at z 10.25–10.75, row B at z 5.25–5.75,
each counting from high x down. This is intentional — do not "fix" it back. The missing panel block
model `models/block/plc_small_panel.json` was derived from the panel item model with the same
spin. The status/pins pane toggle reads "Debug: Status ▸ Pins" / "Debug: Pins ▸ Status" by the
user's request. Stripped from the source design: DO_EN, SWD header,
RS-485/CAN/service UART, FRAM, V24_SENSE, RUN_STOP, LSE/RTC, onboard LEDs, BOOT0 jumper.

Arduino numbering is frozen in `mcu/ArduinoPins.java`. Hardware PWM (from the pinout): PB0 =
TIM1_CH2N, PB1 = TIM1_CH3N (TIM1 partial remap), PB6 = TIM4_CH1, PB7 = TIM4_CH2. EXTI line n = pin
bit n (PA0..PA7 → EXTI0..7, PA11 → EXTI11, etc.; one port per line, as AFIO_EXTICR enforces).

### Electrical behaviour (Minecraft side)

- Supply: V = VCC − GND. Power-on when V ≥ 18.0 V, brown-out off below 16.0 V (hysteresis).
  Operating range per IEC 61131-2 is 19.2–28.8 V; above 30 V report "overvoltage" in the menu
  (keep running — no destruction mechanic).
- Logic load: an ElectricWire VCC→GND whose resistance is updated each tick so it draws the board's
  logic power: R = V² / P_logic (clamp to a sane range; effectively open when unpowered, e.g. 1 MΩ
  standby leakage of the input stage of the buck).
- Inputs DI0–7 and RESET: ElectricWire terminal→GND, 6.8 kΩ (≈3.5 mA at 24 V, IEC Type 3 front
  end). Level: ON when V(pin)−V(GND) ≥ 15 V, OFF when ≤ 5 V, between: hold previous (hysteresis).
- Outputs DO0–7 and STLED: high-side switch VCC→pin, R_on = 0.12 Ω (ISO8200-class). Off = open
  (SwitchedWire off, or ElectricWire at very high resistance). PWM/tone/bit-bang faster than the tick
  is presented as its average: conductance scaled by `PinDrive.highFraction` (R = R_on / duty, open
  at duty 0). **Correction (MC fixer, round 1):** R_on / duty does not average anything — R_on is
  tiny next to any load, so 25 % duty into 48 Ω still passed 99 % of full current. Implemented
  instead: the load is read off the previous solve, R_L = V(pin)/I, and the switch is set to
  R_sw = (R_on + R_L)/duty − R_L (≥ R_on; falls back to R_on/duty until a load has been seen), so
  the load's *mean current* is duty × I_on (chosen over mean power so the supply's draw is exact).
  The 1 A limit is checked against the on-state current V/(R_on + R_L), not the averaged wire, and
  the menu's driver loss / delivered power are duty × I_on²·R_on and duty × I_on·V − that. Output stage ON only if the MCU pin is configured as an output and driving high; an
  input-mode pin (any pull) leaves the driver off. Unpowered PLC or held reset → all outputs off.
  A HardFault freezes outputs at their last state (STM32duino's handler is `while(1)`).
- Overcurrent: every I/O terminal (DI0–7, DO0–7, STLED, RESET) is limited to 1 A. Each server tick
  read |I| through that pin's wire; if > 1.0 A, latch the pin's fault: the pin is forced low (driver
  off; for an input, its front end is disconnected and the MCU sees LOW) until the PLC is reset. Reset
  = RESET terminal asserted, the menu's Reset or Flash, or a power cycle (brown-out). VCC/GND are the
  supply rails and carry the sum of the outputs, so they are not limited (state this in the tooltip
  docs / comments).
- Board power, reported in the menu and goggles, and drawn from VCC through the logic load:
  - MCU 3.3 V domain from `PlcCore.mcuPowerWatts()` (per-operation model, below),
  - plus board 3.3 V overhead (HSE oscillator, opto output pull-ups when inputs are on: 3.3 V/4.7 kΩ
    each, regulator quiescent),
  - converted through a buck: P_24 = P_3v3 / η, η ≈ 0.85,
  - plus output driver quiescent from 24 V (≈ 3 mA idle + 0.5 mA per channel on),
  - plus per pin: inputs V·I (the 6.8 kΩ front end), outputs I²·R_on driver loss **and** the power
    delivered to the load V(pin)·I(pin) ("power consumed from every pin" — the user wants this in the
    total; report it as its own line so the logic draw is still visible).
  - Only the logic + driver-quiescent part goes into the VCC→GND load wire (pin power already flows
    through Power Grid's own wires; do not double-draw it).

## Package layout and ownership

All emulation lives in `cf.content.plc.mcu` and is **pure Java** — no Minecraft, Forge, Power Grid
or Create import anywhere under `mcu/`. It must compile and run with plain `javac`/`java`.

Frozen files already written (do not change signatures; you may add Javadoc-only edits if needed,
and if a contract is genuinely broken, say so in your report rather than silently changing it):
`mcu/{Stm32f103cb, CycleClass, CycleLedger, HardFault, Memory, Blocker, Invocation, Builtin,
McuEnvironment, ArduinoLibrary, Diagnostic, CompileResult, CompiledProgram, CpuCore, McuState,
PinDrive, ArduinoPins, PlcCore}.java`.

Frozen entry points (currently stubs, replaced by their owners):
- `mcu.lang.SketchCompiler.compile(String source, ArduinoLibrary library) → CompileResult` (LANG agent)
- `mcu.arduino.ArduinoCore.INSTANCE` implements `ArduinoLibrary` (CHIP agent)
- `mcu.chip.Stm32f103` — `public final class Stm32f103 implements PlcCore, McuEnvironment`,
  public no-arg constructor = powered-down chip with blank flash (CHIP agent)

Ownership (agents must not edit each other's directories):
- LANG agent: `mcu/lang/**` (+ its tests under `src/test/java/.../mcu/lang/`)
- CHIP agent: `mcu/chip/**`, `mcu/arduino/**` (+ tests under `src/test/java/.../mcu/chip|arduino/`)
- MC agent: everything else for the PLC: `content/plc/*.java` (not `mcu/`), `client/Plc*`,
  `network/*Plc*`, registry/lang/model/tool edits.

## LANG agent: the toolchain and the CPU

`SketchCompiler.compile(source, library)` — pure, deterministic, thread-safe, fast enough to run on
the client for a "Verify" button (≤ ~200 ms for a 2000-line sketch).

Language: what arduino-cli compiles for an .ino on STM32duino, minus classes beyond what the core
needs. Concretely, everything in the Arduino Language Reference's *Structure* and *Variables*
sections, plus the C/C++ an ordinary sketch uses:
- .ino preprocessing like arduino-builder: implicit `#include <Arduino.h>` (= `library.prelude()`),
  **automatic function prototypes** (functions usable before their definition).
- Preprocessor: `#define` (object- and function-like, `#`, `##`, variadic), `#undef`, `#if/#ifdef/
  #ifndef/#elif/#else/#endif`, `defined`, `#include` of `<Arduino.h>` and other known-harmless core
  headers (`stdint.h`, `stdbool.h`, `math.h`, `string.h`, `stdlib.h`, `ctype.h`, `limits.h`,
  `avr/pgmspace.h`) → no-op; communication libraries (`SPI.h`, `Wire.h`, `SoftwareSerial.h`,
  `EEPROM.h`, `Ethernet.h`, …) → error "not available on this PLC". `#error`, `#warning`, `#pragma`
  (ignored), `#line`, `__LINE__`, `__FILE__`, `__COUNTER__`.
- Types: `void, bool/boolean, char, signed/unsigned char, byte, short, int, long, long long`, all
  `unsigned` forms, `word`, `size_t`, `int8_t..uint64_t`, `float`, `double` (64-bit on ARM),
  pointers (incl. to functions), arrays (multi-dim, initialiser lists, string-literal init, size
  inference), `struct` (incl. nested, bitfields if feasible, designated-free aggregate init), `union`,
  `enum` (incl. `enum class`), `typedef`, `using X = …`, `const`, `volatile`, `static` (globals and
  function-local statics), `extern`, `register` (ignored), `inline`, `constexpr` (as const),
  `PROGMEM` (→ goes to flash; `pgm_read_byte/word/dword/float/ptr` work), `F("...")` → const char*.
  `auto` for locals with initialiser. References (`T&`) for parameters and locals. Function
  overloading and default arguments (sketches use them). `new`/`delete`/`new[]`/`delete[]` for
  scalars, structs and arrays (heap). `nullptr`, `NULL`.
- The Arduino `String` class (it is in the language reference): constructors from literal, char,
  int/long/unsigned (with base), float/double (with decimals); `+`, `+=`, `concat`, `==`, `!=`, `<`,
  `>`, `<=`, `>=`, `[]`, `charAt`, `setCharAt`, `compareTo`, `equals`, `equalsIgnoreCase`,
  `startsWith`, `endsWith`, `indexOf`, `lastIndexOf`, `substring`, `replace`, `remove`, `reserve`,
  `length`, `toInt`, `toFloat`, `toDouble`, `toLowerCase`, `toUpperCase`, `trim`, `c_str`,
  `getBytes`, `toCharArray`. Buffers live on the simulated heap (malloc), so String fragmentation and
  out-of-memory behave as on the chip (a failed allocation leaves the String invalid/empty, as
  WString does). The object itself is 12 bytes {char* buffer; unsigned capacity; unsigned len}.
- Statements/operators: all of C: `if/else, switch/case/default` (fallthrough), `for, while,
  do-while, break, continue, return, goto`/labels, comma, ternary, all compound assignments,
  `++/--` pre/post, bitwise, logical with short-circuit, `sizeof` (types and expressions), casts
  (C-style and functional `int(x)`, `byte(x)`, `static_cast`/`reinterpret_cast`/`const_cast`),
  `&`/`*`, `->`, `[]`, pointer arithmetic and comparison, string literals (in flash, `const char*`),
  character escapes, integer literals in dec/hex/octal/binary (`0b`, and Arduino's `B10101` macros in
  the prelude), suffixes `u l ul ll ull f`, float literals. Lambdas without captures are a nice
  extra (attachInterrupt([]{...})) — only if cheap.
- **Exact C/C++ semantics on ARM**: `int` 32-bit, `long` 32-bit, `long long` 64, `char` is
  **unsigned** on ARM GCC, pointers 4 bytes, `double` 8 bytes, struct alignment/padding per AAPCS,
  usual arithmetic conversions and integer promotions, unsigned wraparound, signed overflow wraps
  (two's complement, as GCC emits), shifts masked like the hardware does for registers (document
  choice), integer division truncates toward zero, **division by zero returns 0** (Cortex-M3 UDIV/SDIV
  with DIV_0_TRP clear, which STM32duino leaves clear), float→int conversion saturates like VCVT is
  irrelevant (soft-float `__aeabi_f2iz` truncates; out-of-range is UB — pick saturating), float math
  is IEEE single precision (Java float), double is IEEE double.
- Diagnostics with line:column for errors in the sketch (not the prelude). Messages GCC-like.
  Undeclared identifier, type mismatch, missing return value, wrong arg count, redefinition, use of a
  communication API ("'Serial' was not declared in this scope" is the honest GCC message; append a
  note "this PLC has no serial port").
- Linker-style checks: image `flashBytes()` = `library.coreFlashBytes()` + Σ `flashBytesFor(name)`
  over builtins referenced + estimated Thumb-2 code size of the sketch (use a per-IR-op estimate,
  ~2–4 bytes, documented) + .rodata (string literals, const/PROGMEM data) + .data initialisers +
  soft-float/libm support pulled in when float/double are used (document numbers, e.g. +~1.5 KB
  float add/mul/div/conv, +~3 KB double, +1–4 KB per libm function family). Fail with
  "region `FLASH' overflowed by N bytes" if > 128 KB. `staticRamBytes()` = `coreRamBytes()` +
  sketch .data + .bss (+ alignment). Fail with "region `RAM' overflowed by N bytes" when static RAM +
  MIN_HEAP + MIN_STACK > 20 KB. Report both in the result (the MC side shows "Sketch uses N bytes
  (x%) of program storage space. Maximum is 131072 bytes." / "Global variables use N bytes (x%) of
  dynamic memory, leaving M bytes for local variables. Maximum is 20480 bytes.").

CPU (`CompiledProgram.createCpu(env, ledger)` → `CpuCore`):
- Compile to a compact **bytecode** (or equivalent) executed by a suspendable interpreter — it must
  stop at any instruction boundary when the cycle target or host deadline is reached and resume
  exactly there. Explicit operand stack; no Java recursion per sketch call.
- **All sketch-visible data lives in the simulated memory** (`Memory` implementation over a 20 KB
  byte[] SRAM + read-only flash image): globals (.data/.bss at the bottom of SRAM, core's
  `coreRamBytes()` first), heap above (newlib-style malloc/free/realloc with headers, growing up; out
  of memory when it would cross the stack pointer → malloc returns NULL), stack from the top of SRAM
  growing down (locals, saved frames, spilled args — frames really occupy SRAM bytes, so a stack/heap
  collision corrupts data as on hardware). Stack pointer below `SRAM_BASE` → `HardFault
  STACK_OVERFLOW`. Null/wild pointer → BUS_FAULT. Store to flash/const → MEM_MANAGE. Function
  pointers are flash addresses (Thumb bit set); calling a non-function → USAGE_FAULT.
  Locals may be kept in VM registers for speed only if their address is never taken *and* the frame
  still reserves and accounts their bytes; simplest faithful choice: everything addressable in SRAM.
- **Cycle accounting**: charge every op to the ledger with a Cortex-M3 cost and a `CycleClass`:
  ALU 1, MUL 1 (32×32→32) / 3–5 (64-bit), DIV 2–12 (use ~ 2 + leading-bit based estimate), LOAD 2
  (+ flash wait states for const/flash data reads: +2 at 72 MHz unless prefetch hides it — document),
  STORE 1–2, taken branch 3 (BRANCH), call/return ~ 4–6 + push/pop per saved register, soft-float
  (SOFT_FLOAT): fadd ~ 50, fmul ~ 45, fdiv ~ 110, fcmp ~ 25, int↔float ~ 30, double ×1.8–2.5,
  libm (sinf ~ 1000+, sqrtf ~ 250, powf ~ 2000; double versions roughly 2×) — document sources /
  rationale in comments. Cycle costs should model what GCC -Os would emit for the operation, not the
  interpreter's own bytecode count.
- **Interrupts**: `pendInterrupt(irq, handler)`; taken between instructions when enabled, including
  while a Blocker is parked; exception entry/exit costs 12 + 12 cycles (INTERRUPT class), handler
  body cycles charged as INTERRUPT too. `noInterrupts()` / `interrupts()` via `Invocation`.
- **Builtins**: resolve calls to `library.builtins()` by name + overload; convert args per the
  prototype; `invoke(Invocation)`; support `block(Blocker)`.
- **Idle-loop fast-forward** (essential for performance; Java must not spend 3.6 M interpreted
  cycles per PLC per tick on `while(digitalRead(DI0)==LOW){}` or a `millis()`-polling loop):
  at each backward branch / loop() re-entry, if one full iteration since the previous visit to the
  same branch target changed **no byte of SRAM** (stores of identical values do not count), called no
  builtin that `noteSideEffect()`ed, took no interrupt, and the operand stack/frames are identical,
  then the next iterations are identical until something external changes: skip ahead (charging the
  skipped time as the same mix — or as WAIT if simpler; document) to
  min(target cycle, `env.nextEventCycle()`, next multiple of the finest `noteTimeRead` resolution
  seen in the iteration (if any), next pending interrupt). Must be provably semantics-preserving.
  Iteration timing is preserved by skipping a whole number of iterations (cycles per iteration ×
  n) so a polling loop observes time at the same phase it would have.
- Host deadline checked cheaply (e.g. every 4096 ops). Target throughput ≥ 30 M simple ops/s on a
  desktop JVM.
- `reset()`: copy .data, zero .bss, run global constructors/initialisers (globals initialised from
  non-constant expressions run at startup, before setup(), as C++ does), set SP, then setup(), then
  loop() forever (STM32duino main also calls `serialEventRun` — irrelevant here).
- Report `currentLine()`; HardFault carries the line it happened on.

## CHIP agent: STM32F103 peripherals, the Arduino core, the power model

`mcu.arduino.ArduinoCore.INSTANCE` (ArduinoLibrary):
- `prelude()`: C source for what Arduino.h + STM32duino declare, restricted to this board and to
  base Arduino: `HIGH LOW INPUT OUTPUT INPUT_PULLUP INPUT_PULLDOWN OUTPUT_OPEN_DRAIN INPUT_ANALOG
  RISING FALLING CHANGE LSBFIRST MSBFIRST LED_BUILTIN true false PI HALF_PI TWO_PI DEG_TO_RAD
  RAD_TO_DEG EULER`, pin names `PA0..PA15, PB0..PB15, PC13..PC15` (per `ArduinoPins`; pins not in
  the table are not defined), board aliases `DI0..DI7, DO0..DO7, STLED`, `A0..A7`,
  `NUM_DIGITAL_PINS`, `NUM_ANALOG_INPUTS`, macros `min max abs constrain round radians degrees sq
  lowByte highByte bitRead bitSet bitClear bitWrite bit _BV interrupts noInterrupts
  digitalPinToInterrupt clockCyclesPerMicrosecond clockCyclesToMicroseconds
  microsecondsToClockCycles`, `B0..B11111111` binary constants (binary.h), `typedef`s (`byte`,
  `boolean`, `word`, `uint8_t…`), `F()`, `PROGMEM`, `pgm_read_*`, `F_CPU` (72000000L).
- `builtins()`: Digital I/O `pinMode digitalWrite digitalRead digitalToggle`; Analog I/O
  `analogRead analogReference analogReadResolution analogWrite analogWriteResolution
  analogWriteFrequency`; Advanced I/O `tone noTone pulseIn pulseInLong shiftIn shiftOut`; Time
  `delay delayMicroseconds millis micros`; Math `sqrt pow sin cos tan asin acos atan atan2 exp log
  log10 fabs floor ceil fmod` (float `…f` variants too) and `map` (long), `random randomSeed`
  (Arduino's `random(max)`, `random(min,max)`, using newlib `random()`'s algorithm so sequences match
  the real board as far as practical); Characters `isAlpha isAlphaNumeric isAscii isControl isDigit
  isGraph isHexadecimalDigit isLowerCase isPrintable isPunct isSpace isUpperCase isWhitespace`
  (+ `<ctype.h>` `isdigit` etc., `toupper tolower`); interrupts `attachInterrupt detachInterrupt`
  (and `interrupts()/noInterrupts()` builtins if not macros); C library sketches routinely use:
  `memset memcpy memmove memcmp strlen strcpy strncpy strcat strncat strcmp strncmp strchr strrchr
  strstr atoi atol atof strtol strtoul strtod itoa ltoa utoa dtostrf abs labs` plus `malloc calloc
  realloc free` **only if the LANG agent does not own the heap** (it does — coordinate: heap belongs
  to LANG; CHIP must not implement malloc). `yield()` (no-op), `abort()` → HardFault ABORT.
  Excluded entirely: Serial*, Stream, Print, SPI, Wire, Keyboard, Mouse, USB, EEPROM, HardwareTimer
  class. If a sprintf/snprintf is cheap to add, add it (it is libc, sketches use it to build Strings).
- Faithful STM32duino behaviour and costs: `pinMode` (HAL GPIO init, ~ 3–8 µs, disconnects JTAG
  when a JTAG pin is configured), `digitalWrite` (~ 40–70 cycles via the LL/HAL path),
  `digitalRead` similar, `analogRead` (re-initialises ADC each call in STM32duino: ~ 10–20 µs, 12-bit
  hardware, default 10-bit result scaling, `analogReadResolution` up to 12 (16 allowed and scaled)),
  `analogWrite` (default 8-bit, 1 kHz, on PWM pins starts/updates the timer; on non-PWM pins writes
  HIGH if value ≥ half of max else LOW — STM32duino behaviour; value 0 / max → static levels),
  `tone` (any pin, timer-driven toggling, 50 % duty, optional duration → stops itself), `pulseIn`
  (busy-waits polling, timeout default 1 s, returns µs; its resolution is bounded by when inputs can
  change — once per window — which is the honest result), `shiftOut/shiftIn` (bit-banged
  digitalWrite/Read with the real per-bit cost; they are GPIO bit-banging, not a comms peripheral —
  keep them), `delay` (busy, charged as WAIT), `micros/millis` from SysTick (1 kHz SysTick ISR cost
  charged as INTERRUPT: ~ 30 cycles each ms; millis frozen while interrupts are disabled, with the
  pended tick taken once on re-enable — or document a simpler faithful choice).
- GPIO model per pin: mode (input floating / pull-up / pull-down / analog / output PP / output OD /
  AF PWM), ODR, input level from the board, IDR. Reading a pin: input with board signal → the front
  end drives it (DI pins 0–7: the board level); unconnected pins → pull level (floating reads LOW);
  output pins read back their ODR. Output timeline per pin: record the high-time over the window from
  cycle-stamped writes + PWM duty for timer outputs, producing `PinDrive` per window.
- EXTI: `attachInterrupt(digitalPinToInterrupt(pin), isr, mode)`; one pin per line number; edges from
  `setInput` (at window start) and from output-pin writes on the same pin (if configured). Pends via
  `CpuCore.pendInterrupt`.
- `Stm32f103` implements `PlcCore` and `McuEnvironment`: owns the `CycleLedger`, GPIO/EXTI/timers/
  ADC/SysTick state, the `CpuCore` from the flashed image, power state, reset logic, the window loop
  (`runFor`): split the window at peripheral events (tone end etc.), call `cpu.run(...)`, respect the
  host budget (emulated time dilates; `speed()`), compute `PinDrive`s and power. Flash programming
  takes emulated/real time? — keep it simple: `flash()` completes immediately but the chip reports
  PROGRAMMING for a duration proportional to image size at ~ 25 KB/s (the MC side can show a
  progress bar), holding reset meanwhile. Blank flash → NO_PROGRAM, CPU not created.
- Power model (`mcuPowerWatts()` over the last window): per-cycle-class energy at 3.3 V calibrated
  to the STM32F103xB datasheet (DS5319): run mode from flash at 72 MHz ≈ 36 mA all peripherals
  enabled / 27 mA disabled (≈ 0.375–0.5 mA/MHz), i.e. ≈ 1.2–1.65 nJ per cycle; weight classes
  (e.g. ALU 1.0, MUL 1.05, DIV 1.0, LOAD 1.15 (flash/SRAM access), STORE 1.1, BRANCH 1.25 (prefetch
  refill from flash), SOFT_FLOAT 1.0, PERIPHERAL 1.3 (APB bus), INTERRUPT 1.2, WAIT 0.95), plus
  static: leakage + HSE/PLL + clocked peripherals (GPIOA/B/C, AFIO, plus TIM1/TIM4/ADC1 only while
  in use, from the datasheet's per-peripheral current table), plus ADC conversion energy. Unpowered →
  0. In HardFault the CPU still spins at full clock (a `b .` loop: BRANCH-heavy). Document
  calibration in comments. Expose a per-class breakdown accessor for the menu
  (`public long[] lastWindowCycles()` or similar — additive public API on Stm32f103 is fine).

## MC agent: the device in the world

Existing code to extend (read them first): `content/plc/PlcSmallControl.java`,
`PlcSmallControlType.java`, `PlcSmallGeometry.java`, `client/PlcSmallControlRenderer.java`,
`client/ModPartialModels.java` (`PLC_SMALL_DIN` → `block/plc_small_din`, which currently does not
exist as a file), `registry/ModItems.java` (`PLC_SMALL_DIN`), lang `en_us.json`. Analogues to copy
patterns from: `content/display/AlphanumDisplayControl(Type).java` + `DisplayPins.java` (terminals,
supply, editor open packet, screen), `content/circuit_breaker/CircuitBreakerControl.java` (switched
wires, overcurrent), `content/procedural_gauge/SquareProceduralGaugeControl.java` (`setResistance` at
runtime), `network/OpenAlphanumDisplayPacket`, `ConfigureAlphanumDisplayPacket`, `client/
AlphanumDisplayScreen`, `network/ModPackets.java`, `foundation/control/*` (HostedControl,
HostedCircuitBuilder, ControlConfig, TerminalSlots), `content/screwdriver/ScrewdriverInteractions`.
Memory notes the user keeps: configuring = plain right-click with a screwdriver → `hasEditor()` +
`openEditor(ServerPlayer)`; `HostedControl.use` stays PASS. Goggle tooltip: lines describing the
device's own state.

1. **Model**: write `tools/gen_plc.py` (style of `tools/gen_display.py` / `gen_circuit_breaker.py`)
   that reads the export `models/block/plc_fwm32f103_iop_small_din.json` and writes
   `models/block/plc_small_din.json` (the partial): drop the `din_rail_1` group's elements (the rail is
   drawn by the rail block), drop `groups`/`__generated`, keep textures `0`/`1`, fix `particle` to the
   body texture, lowercase anything needed. Also regenerate `models/item/plc_small_din.json` from the
   export with the existing item model's vertical offset and display block. Print the pin boxes it
   finds (names → from/to) so `PlcSmallGeometry` can be checked against it. Run it.
2. **Geometry/terminals**: 20 terminals in the frozen order above. Grown click boxes (outward = up,
   +y, by ~0.375 px; across padding < half the 0.25 px gap so no two boxes overlap), wire origin at
   the pin's top centre, names (translatable: "DI0 (PA0)" … "VCC +24V", "GND 0V", "DO0 (PB0)" …,
   "STLED (PA11)", "RESET"), colours (VCC red, GND blue, inputs e.g. green, outputs e.g. yellow/orange,
   RESET white) — use `IDecoratedTerminal` constants that exist (check with javap). Footprint: keep
   `DIN_BOXES` but include the grown terminal boxes.
3. **Control** (`PlcSmallControl`): buildCircuit per the electrical section. Server-side tick: read
   supply/inputs (with hysteresis), overcurrent check on the previous solve's currents, drive the
   `Stm32f103` (`setPowered`, `setResetHeld`, `setInput`, `runFor(50 ms, budget)`), apply outputs
   (resistances), compute the board power breakdown, update the logic load. Host budget per PLC per
   tick: a constant (e.g. 2 ms) — add a server config value if there is an existing server config
   pattern, else a documented constant. Persist: sketch source, whether a program is flashed (re-
   compile on load; if it no longer compiles, come back blank and say so), latched pin faults. The
   MCU cold-boots on load (SRAM is not persisted — say so in a comment). Write as little as possible
   to the synced tag (the host syncs one tag for disk and network); do not call `changed()` per tick.
   Client side: no emulation.
4. **Menu** (screwdriver plain right-click): `hasEditor()` true, `openEditor` sends an open packet
   carrying source + status. Screen (`client/PlcScreen`): a usable multi-line code editor (vanilla
   `MultiLineEditBox` is acceptable; line numbers and a monospace-looking layout are a plus; must
   handle paste and sketches up to 64 KB), buttons **Verify** (compile on the client with
   `SketchCompiler`, show diagnostics + flash/RAM usage lines as arduino-cli prints them), **Upload**
   (send the source to the server, chunked under the 32 KB serverbound payload limit; server
   re-compiles authoritatively, flashes, resets, clears pin fault latches, replies with result),
   **Reset** (pulse NRST, clear latches). A live status pane refreshed while the screen is open (client
   polls every ~10 ticks with a request packet; server replies to that player only, after checking
   distance/permission): state, fault + line, uptime, CPU load, emulation speed, VCC voltage, flash
   used/128 KB, RAM static + stack/heap peaks /20 KB, power breakdown (MCU logic, board overhead,
   inputs, output drivers, delivered to loads, **total**), per-pin table (DI level, DO duty/current,
   latched faults). Validate every serverbound packet (distance, control still there, size limits).
5. **Goggles**: state, total power, supply voltage, any latched pin faults, fault line.
6. Lang entries for everything; register packets in `ModPackets`.
7. The panel-mount PLC block (`PlcSmallPanelBlock`) is out of scope — leave it alone.

## Testing

Add JUnit 5 to `build.gradle` (`testImplementation 'org.junit.jupiter:junit-jupiter:5.10.2'`,
`testRuntimeOnly 'org.junit.platform:junit-platform-launcher'`, `test { useJUnitPlatform() }`) — only
one agent (the integrator) edits build.gradle. Because `mcu/` is pure Java, agents can and should test
it **without Gradle**: `javac -d <scratch dir> $(find src/main/java/com/nectariich/create_flippies/
content/plc/mcu -name '*.java') <test files>` and run a `main` harness, which avoids fighting over
Gradle locks while several agents work at once. Use your own scratch directory for build output,
never `build/`.

Do not run `gradlew runClient`. `gradlew compileJava` is allowed for the MC agent and integrator only.
