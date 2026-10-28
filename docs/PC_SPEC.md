# Buildable PC: emulated CPUs, assemblers, PS/2, COM and VGA

Mod: Create: Flippies, Forge 1.20.1, Java 17. Repo root `/Users/ac/Create Flippies`.
Base package `com.nectariich.create_flippies` (below: `cf`). Everything in this spec lives under
`cf.content.computer` (below: `computer`).

This is the master plan for the whole computer feature. Only part of it is built now (see
"What is built now"); the rest is here so the parts built now do not paint the later ones into
a corner.

## What the user asked for

- A buildable PC: the player picks a CPU, fits RAM and an HDD. Models and blocks come later.
- CPUs: 8086, 8088, 68000, ARM2, MIPS R3000, RISC-V RV32I. The first two and the 68000 are named
  by number only; no vendor name appears anywhere (names, docs, comments, identifiers, strings).
- Added later: the 68040 (integer unit and built-in FPU, no MMU), also named by number only. The
  68000 and 68040 are the next CPUs built (section 10).
- Each CPU is emulated as the chip itself: it runs real machine code. Each CPU gets an assembler
  that turns assembly source into that machine code. C and C++ come after assembly.
- Peripherals: PS/2 keyboard and mouse, COM (serial) ports, VGA for displays. VGA is not emulated
  as a chip: the guest writes a framebuffer and the host copies the image across.
- Build only one CPU now, the easiest: RV32I (reasons in "Difficulty ranking").
- Core only: no blocks, items, GUIs, networking or models yet.
- Later (section 11, planned): the in-game PC: a case block with installable parts, a floppy
  drive, COM3, three independent VGA outputs, an in-game-only network card, Power Grid power,
  data cords between ports, and the keyboard block.
- Later (section 12, planned): Debian with a GUI on the 68040 PC. The 68040 gets its MMU, a
  network modem block serves an in-mod "internet" over real IP, and a netinstall floppy writes
  the Debian image onto the HDD.

## What is built now

- The CPU-neutral core: system bus, clock, device interface, interrupt plumbing, machine loop.
- All devices: system controller, interrupt controller, CLINT-style machine timer, interval timer,
  RTC, two 16550-style UARTs, PS/2 controller with keyboard and mouse, VGA framebuffer, HDD.
- The RV32I CPU (RV32IM + Zicsr + Zicntr + Zifencei, machine mode only).
- The shared assembler framework and its RV32I backend.
- The RV32I boot ROM and end-to-end tests.
- Save and restore of a running machine (1.5): a PC freezes when its chunk unloads and resumes
  exactly where it stopped.
- The 68000 and 68040 (section 10): the integer core for both models, the 68040's FPU with the
  in-game strict FPU option, the 68k assembler backend, both machines with their boot ROM, and
  their end-to-end tests.
- Section 12, step 1: the 68040 MMU, faster ethernet, the modem's network stack and `.fdi`, the
  headless PC runner, and the guest toolchain Docker image (12.14).

## House rules

- Pure Java under `computer/**`: no Minecraft, Forge, Create or Power Grid import. It compiles with
  plain `javac` and its tests run on the plain JUnit 5 platform, like `content/plc/mcu`.
- Match the PLC emulator's style (`content/plc/mcu/CpuCore.java`, `CycleLedger.java`,
  `Memory.java`, `chip/Stm32f103.java`): small focused classes, Javadoc that says what and why,
  `final` classes, records for plain data, constants with a comment giving their source.
- Do not import from or modify `content/plc/**`. Ideas may be copied, code is not shared.
- No em-dashes anywhere. No Markdown tables. No vendor name for the 8086, 8088, 68000 or 68040.
- Do not commit.

## 1. Overall architecture

### 1.1 Parts the player assembles (later in game, modelled now in `MachineConfig`)

- CPU: one `CpuModel` plus a clock rate. Each CPU item is one model at one clock (for example
  "8086 @ 8 MHz", "RV32I @ 10 MHz"). No CPU: the PC does not power on. A 68040 PC also has the
  strict FPU option (`MachineConfig.strictFpu`, chosen in game, default off; 10.4).
- RAM: one to four sticks; the machine sees one contiguous block whose size is the sum.
  (In game, 11.2.3: two slots, sticks of 512 KiB, 1, 4, 16 and 64 MiB.) The core accepts any multiple of
  4 KiB from 64 KiB to 64 MiB (128 MiB once 11.1.10 is built). A CPU caps what it can address (8086/8088: 640 KiB of
  conventional RAM; 68000: 13.5 MiB below VRAM once the three displays of 11.1.3 exist, 14 MiB
  before; ARM2: 32 MiB). RAM beyond the cap is unused
  and reported. No RAM: the PC does not power on.
- HDD: optional. A block device with 512-byte sectors. Planned capacities (11.2.3): 16, 64,
  256 MiB and 1 GiB. The core
  accepts any multiple of 64 KiB from 64 KiB to 1 GiB. The disk is persisted as a byte image
  (sparse, in 64 KiB chunks) independent of the PC, so a drive can move between PCs.
- Fixed on the motherboard: boot ROM, system controller, interrupt controller, timers, RTC,
  COM1 and COM2, PS/2 controller (keyboard and mouse ports), VGA output. Section 11.1 adds COM3,
  two more VGA outputs (three independent displays), the floppy controller and the network card,
  all on the motherboard.
- Floppy drive (11.1.5): built into the case, 1.44 MB removable disks, boot order floppy then
  HDD.

### 1.2 The system bus

- One physical address space per machine, addressed with 32-bit unsigned addresses carried in a
  Java `int`. CPUs with narrower buses mask first (8086/8088: 20 bits; 68000: 24 bits;
  R3000: physical address after segment/TLB translation). ARM2: data addresses at or above 2^26
  raise the address exception before reaching the bus; fetches are 26-bit by construction (the
  PC field is 24 bits of word address).
- The bus maps regions: byte-array memory regions (RAM, ROM, VRAM and views of VRAM) and device
  register windows. Regions never overlap; mapping an overlapping region is an
  `IllegalArgumentException`. The one exception is the reset-time ROM overlay of the 68000 and
  ARM2 machines (`SystemBus.setRomOverlay`): while it is on, the ROM also answers at address 0 and
  wins over RAM there.
- A device window's size is chosen at mapping time (`mapDevice(base, windowSize, device)`), not
  by the device: the RV32I machine gives every device 0x1000 bytes, the 8086 port bus gives a
  UART 8 ports. Offsets inside a window that the device does not decode read 0 and ignore writes.
- Accesses are 8, 16 or 32 bits, reads zero-extended. Unaligned accesses are legal on memory
  regions; whether a CPU allows them is the CPU's business (a 68000 raises an address error before
  the access ever reaches the bus). Device accesses must be naturally aligned (offset a multiple
  of the width), otherwise `BusFault`.
- Byte order: memory regions compose 16- and 32-bit values in the bus's `order()`, which the
  machine sets from `CpuModel.byteOrder()`. So a big-endian CPU gets big-endian memory with no
  swapping in the CPU. Device registers are value-addressed: a full-width access returns the
  register's value whatever the CPU's byte order. A narrower access to a 32-bit register at
  offset `o` returns bits 7:0 at `o`, 15:8 at `o+1` and so on (16-bit halves at `o` and `o+2`),
  for every CPU. This is what lets one device implementation serve all six CPUs.
- CPUs issue one `read32`/`write32` for a 32-bit device access and never split it (a 68000
  emulator must not split a long access to a device into two word accesses). The only split is
  8086/8088 port I/O, which can move at most 16 bits: a 32-bit register is two 16-bit port
  accesses, low half (`o`) first.
- Register side effects under split access: a read side effect that captures state (latching
  COUNTER_HI, popping a FIFO byte) fires on the access that touches the register's byte 0; a write
  side effect (SET_HI setting the RTC, PAL_DATA storing an entry) and the PAL_DATA read
  auto-increment fire on the access that touches the register's byte 3 (byte 0 for 8-bit
  registers). With low-half-first splitting this gives the same result as one full access.
- An access no region answers, a write to ROM, a misaligned device access, or a multi-byte access
  that crosses a region edge throws `BusFault`. The CPU turns that into its own exception (RV32I
  access fault, 68000 bus error, ARM2 data abort, R3000 bus error; the 8086 has none: a faulting
  port read returns all ones and a faulting port write is dropped).
- Fast path: RAM, ROM and VRAM are exposed as raw `byte[]` with base and size, so the CPU's fetch,
  load and store path never goes through region dispatch for them. The ROM array is padded with
  zeros to the platform's ROM window size, so "bytes past the image read 0" holds on the fast
  path. CPUs test the ROM range first, then RAM, then VRAM (only matters under the ROM overlay).
  A VRAM store also sets the store's 4 KiB page bit in `vramDirtyPages()` (one `long` OR), which
  the VGA device reads at frame end. Only device accesses (and banked or aliased VRAM views) use
  the slow path, and only device accesses cost the CPU `MMIO_EXTRA_CYCLES` (2) on top of the
  instruction.
- The fast-path arrays and bases change only between `Cpu.run` calls (ROM overlay off, reset);
  a CPU reloads them into locals at the start of every `run`.
- DMA (the HDD) writes RAM or VRAM arrays directly through a `DmaWindow` and then calls its
  `onWrite`, which marks VRAM pages dirty and notifies the bus's RAM write listener (unused now;
  it is the hook a future decode cache needs for invalidation).
- Port I/O: the 8086/8088 also have a 64 KiB I/O port space. The machine for those CPUs gives
  them a second `Bus` instance for ports, holding the same device objects at port windows. Its
  fast-path arrays are empty (length 0, size 0). Other CPUs only see memory-mapped devices.

### 1.3 Devices and interrupts

- A device is a `BusDevice`: registers spanning `size()` bytes, `read(offset, width)`,
  `write(offset, width, value)`, `reset()`, plus lazy time: `advanceTo(cycle)` and
  `nextEventCycle()`.
- Lazy simulation: a device does nothing on its own between accesses. Its own activity (a UART
  shifting bytes, an HDD finishing a transfer, a timer expiring) is computed on demand from cycle
  stamps when it is accessed (`advanceTo(clock.now())` at the start of every `read`/`write`) and
  when the machine catches it up at segment ends. `nextEventCycle()` is the earliest cycle at
  which the device would, by itself, change an interrupt line (given its current enables) or
  change memory the CPU reads on its fast path (HDD DMA completion). Changes visible only through
  the device's own registers (a UART byte leaving with its IRQ disabled, a mouse packet with
  reporting IRQ off) do not count: they are caught up lazily on the next access. A device may
  return an earlier cycle than necessary (it only costs a segment end), never a later one.
- Stop horizon: when a device schedules a new event during a CPU segment (mtimecmp or PERIOD
  written, HDD command started, THR written with the THRE interrupt enabled), it calls
  `clock.requestStopAt(eventCycle)`. The CPU re-reads `clock.horizon()` after every slow-path
  access, so the segment ends at the new event even if the guest is busy-looping, not up to a
  tick later.
- Guest requests that change machine state (SysCon reset, power off, ROM overlay off, WAIT; the
  68000 RESET instruction through `CpuHost.resetDevices`) are only recorded by the device, which
  calls `clock.requestStopAt(clock.now())`. The CPU stops after the current instruction and the
  machine performs the request between `run` calls, so no CPU writes stale locals back over a
  reset and no CPU keeps a stale memory map.
- Interrupt lines from devices are levels. A device holds an `IrqSink` and calls `set(level)` when
  its output changes. Device lines go into the interrupt controller (`InterruptController`,
  32 lines, 8 outputs, each line routed to one output). The controller's outputs, and any
  CPU-specific sources (the RV32I CLINT), drive CPU pins through `Cpu.setIrq(pin, level)`.
  `setIrq` can be called while the CPU is inside `run` (a device access raising its line), so a
  CPU must not cache pin state in locals across a slow-path access.
- Pin numbering per CPU, each pin a level line:
  - RV32I: the `mip` bit number (3, 7, 11). Controller output 0 drives pin 11.
  - 8086/8088: pin 0 INTR, pin 1 NMI. Controller output 0 drives INTR, output 1 drives NMI.
  - 68000: pins 1..7 are the seven IPL levels; the CPU takes the highest asserted pin as its
    interrupt priority level (none asserted = 0). Controller output 0 drives IPL 4 (so the reset
    routing works) and outputs 1..7 drive IPL 1..7 (output 4 ORed with output 0). A guest routes,
    for example, VBLANK to output 2 and the UARTs to output 4.
  - ARM2: pin 0 IRQ, pin 1 FIQ, from controller outputs 0 and 1.
  - R3000: pins 0..5 are Int0..Int5, from controller outputs 0..5.
- Two CPU inputs are edge-sensitive on the real chips: the 8086 NMI (rising edge) and 68000
  level 7 (taken on the transition to level 7, not again while it is held). The pins stay levels;
  the CPU edge-detects these two internally.
- For the 8086/8088, the interrupt controller also supplies the vector number on acknowledge
  (`VECTOR_BASE + line`), through `CpuHost.interruptAck`.

### 1.4 Clock, cycles and Minecraft ticks

- Each machine has one `CycleClock`: a monotonic count of CPU clock cycles since power-on. It is
  the only notion of "now" for CPU and devices, as the PLC's `CycleLedger` is.
- The game calls `Machine.runTick(50_000_000L, hostBudgetNanos)` once per server tick (20 Hz).
  The tick is worth `clockHz / 20` cycles (remainder carried across ticks so long runs are exact).
- Inside a tick the machine runs the CPU in segments. Each segment stops at the earliest of: the
  end of the tick window, any device's `nextEventCycle()`, and any stop a device requests during
  the segment (`CycleClock.requestStopAt`, see 1.3). After each segment it performs pending guest
  requests (reset, power off, ROM overlay off, WAIT), catches every device up to now and loops.
- Time conversions: devices that count in microseconds (CLINT `mtime`, interval timer, HDD
  latency, UART character times, typematic and mouse sample periods) convert with
  `CycleClock.cyclesToMicros(cycles, clockHz)` = floor(cycles * 1_000_000 / clockHz) and its
  inverse `microsToCycles`, both computed overflow-safely by splitting at whole seconds. So any
  clock rate works, including the 8088's 4_772_727 Hz.
- Idle: when the CPU executes its wait instruction (RV32I `WFI`, 8086 `HLT`, 68k `STOP`) or the
  guest writes SysCon `WAIT`, the CPU returns `Exit.WAITING`. The machine then jumps the clock
  straight to the segment end (no host time spent), catches devices up, and calls `run` again,
  which returns `WAITING` at once unless the CPU's wake rule now holds. A sleeping PC costs almost
  nothing. Wake rules:
  - RV32I `WFI`: any interrupt pending and enabled in `mie`, even with `mstatus.MIE = 0`.
  - 8086/8088 `HLT`: NMI, or INTR only while IF = 1 (reset also ends it).
  - 68000 and 68040 `STOP`: an interrupt whose level is above the SR mask (or level 7), trace, or
    reset.
  - SysCon `WAIT` (any CPU; meant for ARM2 and R3000, which have no wait instruction): the machine
    treats the CPU as waiting until any of its interrupt pins is asserted, masked or not.
- The CPU loads its cycle count from `clock.now()` at the start of every `run`, so it follows the
  clock jumps the machine makes while it waits.
- Host deadline: `run()` is given `hostDeadlineNanos` (`System.nanoTime()` value). The CPU checks
  it every 4096 instructions. If it passes, the rest of the tick window is dropped: emulated time
  stops for this tick and resumes next tick. Emulated time dilates; a slow server never stalls.
  `Machine.speed()` reports emulated cycles run over cycles requested, smoothed over 20 ticks.
- Default host budget: `Machine.DEFAULT_HOST_BUDGET_NANOS = 2_000_000` (2 ms per PC per tick).
  The in-game integration may later make it a server config value.
- What that budget means: at the target speed of 100 M instructions per second, 2 ms is 200 k
  instructions per tick. A 10 MHz RV32I busy for a whole tick needs 500 k, so a busy RV32I PC runs
  at about 40% speed by design (a busy 25 MHz R3000 at about 16%). Idle PCs (in WFI) are not
  affected. Budgets add up per PC: 25 busy PCs at 2 ms fill a whole 50 ms tick. A server-wide
  budget manager is planned for the integration; the core keeps each `Machine` single-threaded and
  machines independent of each other, so the integration can later run them on a worker pool.
- Threading: a `Machine` and everything in it are used from one thread at a time. Host APIs
  (`keyDown`, `move`, `hostWrite`, `hostRead`, `snapshot`) must be called on the thread that runs
  `runTick`, between ticks.
- At the end of every tick the machine calls `VgaDisplay.endFrame()` (frame counter, VBLANK, dirty
  generation). So the guest sees a 20 Hz vertical blank.
- ARM2 and R3000 have no wait instruction. Their guest runtime uses SysCon `WAIT`; a later idle
  detector (a backward branch to itself, or a loop that only reads a device status register) is
  optional on top. Neither is needed for RV32I.

### 1.5 Power, reset and persistence

- Power on: RAM zero-filled, devices reset, CPU reset, clock to 0.
- Reset (guest via system controller, or host): devices and CPU reset, RAM kept, ROM overlay back
  on (68000, 68040, ARM2), clock keeps running (monotonic). A guest reset takes effect after the
  instruction that wrote POWER (see 1.3).
- Power off (guest via system controller): machine state `OFF_BY_GUEST` with the exit code, also
  after the writing instruction, with no further instruction executed.
- Persistence (user decision): a PC freezes in place when its chunk unloads and comes back on
  when it loads, exactly where it stopped. Chunk unload writes to disk, so a server restart
  behaves the same. Emulated time is frozen while unloaded: the clock is saved with every absolute
  cycle stamp measured against it, so there is no catch-up burst on load. Everything round-trips:
  CPU registers, pc, CSRs and wait or halt state, pin levels, RAM, the ROM overlay, pending
  interrupts and line levels, CLINT and interval timer, UART registers, FIFOs, the byte in the
  shift register and host buffers, PS/2 queues, typematic and mouse state, VGA mode, palette,
  VRAM, cursor and frame counters, HDD registers and the command in flight, SysCon requests and
  debug log, the power state and the machine's tick bookkeeping. The one exception is the RTC,
  which runs on host time like a battery clock: its guest offset is saved, so after a load it
  shows the real time that passed.
- The disk image is persisted on its own, as before: the disk exposes dirty 64 KiB chunks (as
  copies, so a save can run off the machine's thread) so the game can save incrementally to
  `<world>/create_flippies/disks/<uuid>/`. The machine state stores only the HDD controller's
  state plus the attached disk's capacity, to check on load that the same kind of disk is there.
- API: `Machine.saveState()` returns a `byte[]` (for an NBT byte array);
  `Machine.loadState(byte[])` replaces the state of a machine built from the same config. `Cpu`
  and `BusDevice` have `saveState(DataOutput)` and `loadState(DataInput)`; the format is plain
  `java.io` data streams (big-endian), with no Minecraft types.
- Format of `Machine.saveState()`:
  - "FLPS", the envelope version (`Machine.STATE_VERSION`, 3 now; 2 added the strict FPU flag,
    3 the pending device reset of the 68k RESET instruction; the loader reads the strict FPU flag
    only from version 2 on and the reset flag only from version 3 on, so a version 1 state loads
    with the strict FPU off and a version 1 or 2 state with no reset pending).
  - The config it must load into: CPU model name (`CpuModel.name()`), clock Hz, RAM bytes,
    CRC-32 of the boot ROM image, reset PC, the strict FPU flag.
  - Machine fields (state, exit code, SysCon WAIT parking, tick remainder, window end, speed
    history), the clock's now and horizon, the ROM overlay flag, the pending device reset.
  - RAM, compactly: its length, a bitmap with one bit per 4 KiB page holding a non-zero byte,
    then only those pages, deflated at the fastest level (`StateIo.writePages`). An idle 64 MiB
    PC costs about 2 KiB; a full one costs what deflate makes of it. VRAM is stored the same way
    inside the VGA section.
  - Sections: the CPU (tag = model name), then a count and every device (tags `syscon`, `timer`,
    `rtc`, `com1`, `com2`, `ps2`, `vga`, `hdd`, `clint`, `pic`). A section is tag, byte length,
    payload; each payload starts with that component's own format version, so a component can
    change its format, and a new CPU (68000, 68040 with its FPU registers) brings its own payload
    and version, with no change to the envelope.
  - CRC-32 of everything before it.
  - Planned (11.1.8): envelope version 4 for the in-game board (the MAC in the config, sections
    `com3`, `fdc`, `nic`, `vga.1`, `vga.2`, disk ids); versions 1..3 are then rejected and the PC
    cold boots.
- Load rules: `loadState` throws `StateFormatException` (an `IOException` whose message names the
  problem) and leaves the machine exactly as it was for: a checksum mismatch, truncated or
  otherwise corrupt data, an unknown envelope or component version, a different CPU model, clock,
  RAM size, boot ROM, reset PC or strict FPU flag, a missing, extra or unknown section, a section not used to its
  last byte, and an attached disk that differs from the saved one (present or not, capacity).
  Never a half-loaded machine: `loadState` first saves the current state and puts it back on any
  failure. On that exception the game logs it and cold-boots the PC with `powerOn()`.
- A component's `loadState` restores fields only; it never drives interrupt sinks, since the
  receiving end (interrupt controller levels, CPU pins) restores its own copy. State lives in
  plain fields and absolute cycle stamps (nothing hidden in lambdas or host objects); ring
  buffers are stored oldest byte first, so equal states give equal bytes.

## 2. The CPUs

Facts below are the real chips'. "Emulation must model" lists what the instruction-level
emulator must get right for software written for the real chip to run.

### 2.1 8086

- Released 1978. Clock 5 MHz (8086), 8 MHz (8086-2), 10 MHz (8086-1). Planned CPU items:
  5, 8 and 10 MHz.
- 16-bit data bus, 20-bit address bus (1 MiB), separate 64 KiB I/O port space. Little-endian.
- Registers: AX, BX, CX, DX (with 8-bit halves AL..DH), SI, DI, BP, SP, segment registers CS, DS,
  SS, ES, IP, FLAGS (CF 0, PF 2, AF 4, ZF 6, SF 7, TF 8, IF 9, DF 10, OF 11; bits 12..15 read
  as 1 on the 8086).
- Addressing: physical = segment * 16 + offset, wrapping at 1 MiB (no A20 gate).
- Reset: CS = 0xFFFF, IP = 0x0000 (first fetch at 0xFFFF0), DS = ES = SS = 0, FLAGS = 0
  (interrupts off).
- Interrupts: vector table at 0x00000, 256 entries of (IP, CS). INTR (maskable, vector from the
  controller), NMI (type 2). Divide error is type 0 and on the 8086 the saved IP points after the
  DIV/IDIV. Single step type 1, INT3 type 3, INTO type 4.
- Emulation must model: all documented opcodes and ModR/M forms, segment override, LOCK/REP
  prefixes, string instructions with REP, the flag results including AF and PF, and the 8086
  quirks:
  - An interrupt during a REP string instruction saves the address of the last prefix byte only,
    so on return any earlier prefix is lost (a segment override placed before REP no longer
    applies to the remaining iterations). Software works around it; the emulator reproduces it.
  - Shift counts are not masked; PUSH SP pushes the decremented SP; 0x0F is POP CS; 0x60..0x6F
    alias 0x70..0x7F; 0xC0/0xC1 alias 0xC2/0xC3; 0xC8/0xC9 alias 0xCA/0xCB; 0xD6 is SALC; 0xF1
    is an alias of LOCK; D0..D3 with ModR/M reg field 6 is SETMO (result all ones, flags as for
    OR); a REP prefix before IMUL or IDIV negates the result.
  - A load of any segment register (MOV sreg or POP sreg, not only SS) inhibits interrupts for
    the next instruction.
  - HLT returns `WAITING`; it wakes per the rule in 1.4.
- Cycles: per the 8086 datasheet tables, for example MOV reg,reg 2; ADD reg,reg 3; Jcc 16 taken
  or 4 not taken; effective address calculation 5 to 12 (+2 with a segment override); MUL r16
  118 to 133; DIV r16 144 to 162. +4 per word access at an odd address. The 6-byte prefetch queue
  is not modelled (documented approximation; the datasheet timings assume a full queue).
- Bus mapping: memory via the memory `Bus` (address masked to 20 bits), IN/OUT via the port `Bus`
  (`CpuHost.io`).
- Planned memory map: RAM 0x00000 up to 0x9FFFF (max 640 KiB), VRAM graphics window
  0xA0000..0xAFFFF (64 KiB, a banked `MemoryRegion` view: 320x200x8 directly; 640x480 modes
  through the VGA BANK register), text buffer 0xB8000..0xB8FFF, boot ROM 0xF0000..0xFFFFF. BANK
  affects only 0xA0000..0xAFFFF; 0xB8000 always maps VRAM offset 0.
- Planned ports (custom register layouts, the CPU-neutral ones of section 3):
  - interrupt controller 0x20..0x3F (offsets 0x00..0x1F; CLAIM_OUT registers not reachable, the
    8086 uses output 0 and its CLAIM);
  - interval timer 0x40..0x5F;
  - PS/2 0x60..0x7F (keyboard port at +0x00, mouse port at +0x10);
  - RTC 0x80..0x8F;
  - HDD 0x1F0..0x21F;
  - COM2 0x2F8..0x2FF, COM1 0x3F8..0x3FF (the 16550 layout is identical);
  - VGA control 0x3B0..0x3EF (offsets 0x00..0x3F, which hold every control register including
    PAL_INDEX 0x2C and PAL_DATA 0x30; the direct palette at 0x400..0x7FF is not reachable from
    ports, the palette is reached through PAL_INDEX/PAL_DATA);
  - system controller 0x500..0x53F.
  32-bit device registers are reached as two 16-bit ports (low at `o`, high at `o+2`), per the
  bus rule in 1.2.
- Assembler syntax: the 8086 vendor operand order (destination first) in the NASM style:
  `mov ax, [bx+si+4]`, `byte`/`word` size keywords, `es:` overrides, `far` jumps and calls,
  `$` for the location counter. Accept NASM data aliases (`db`, `dw`, `dd`, `times`, `equ`,
  `org`, `resb`/`resw`) next to the shared dot-directives. Jumps are relaxed (short 2 bytes
  versus near 3 bytes), which needs the framework's multi-pass mode.

### 2.2 8088

- Same core and instruction set as the 8086. Released 1979. Clock 4.77 MHz in its best-known
  machine (exactly 14.31818 MHz / 3 = 4_772_727 Hz); 5 MHz (8088) and 8 MHz (8088-2) parts exist.
  Planned CPU items: 4.77 and 8 MHz.
- 8-bit external data bus, 20-bit address, 4-byte prefetch queue.
- Emulation: the 8086 emulator with a bus-width flag. Every 16-bit memory or I/O transfer costs
  an extra bus cycle (+4 clocks), not only odd-address ones. The 8088's main cost is instruction
  fetch (4 clocks per byte through the 8-bit bus), which the datasheet timings hide by assuming a
  full queue; with no queue model, each instruction is charged `max(exec, 4 * instruction bytes)`
  (documented approximation). Everything else is shared, so the 8088 is almost free once the 8086
  exists.

### 2.3 68000

- Released 1979. Clock 8 MHz typical (parts from 4 to 16.67 MHz; 7.16 to 8 MHz in the classic
  home machines). Planned CPU items: 8 and 12.5 MHz; the platform accepts the real range, 4 to
  16.67 MHz.
- Full design (core, assembler, machine, tests, owners): section 10.
- 16-bit data bus, 24-bit address bus (16 MiB), 32-bit registers. Big-endian.
- Registers: D0..D7, A0..A6, A7 as USP or SSP depending on the S bit, PC, SR (T 15, S 13, interrupt
  mask 10..8, CCR: X 4, N 3, Z 2, V 1, C 0).
- Modes: supervisor and user. Privileged instructions in user mode raise privilege violation.
- Reset: SSP loaded from address 0x000000, PC from 0x000004, S = 1, T = 0, mask = 7. The boot ROM
  is mirrored at 0x000000 after reset until the guest writes the system controller's
  `ROM_OVERLAY_OFF`, then RAM appears at 0.
- Exceptions: vector table at 0 (256 longwords). Bus error 2, address error 3 (word or long
  data access at an odd address, and any instruction fetch from an odd address, such as a jump,
  branch or return to an odd target; with the long group 0 stack frame), illegal instruction 4,
  zero divide 5, CHK 6, TRAPV 7, privilege violation 8, trace 9, line 1010 10, line 1111 11,
  uninitialised interrupt 15, spurious 24, autovectors 25..31 for IPL 1..7, TRAP #0..15 at
  32..47. IPL 7 is not maskable (and is edge-triggered, see 1.3).
- Interrupt acknowledge: the machine uses autovectors only (no device supplies a vector number),
  so vectors 25..31 are the only interrupt vectors taken; 15 and 24 exist for software written
  for the real chip.
- Emulation must model: the 12 effective-address modes (Dn, An, (An), (An)+, -(An), d16(An),
  d8(An,Xn), abs.W, abs.L, d16(PC), d8(PC,Xn), #imm) per instruction and size, the X flag, BCD
  instructions (ABCD, SBCD, NBCD), MOVEM, MOVEP, TAS, exception priorities and stack frames,
  RESET (asks the machine to reset devices through `CpuHost.resetDevices`, 132 clocks) and STOP
  (returns `WAITING`; wakes per 1.4).
- Cycles: per the 68000 user manual timing tables; 4 clocks per bus cycle, for example NOP 4,
  MOVE.W Dn,Dn 4, MULU and MULS 38 + 2n (up to 70), DIVU up to 140, DIVS up to 158.
- Bus mapping: everything memory-mapped. Planned map: RAM from 0x000000 (up to 14 MiB), VRAM
  0xE00000..0xE7FFFF, devices 0xF00000..0xF0FFFF (same window layouts as RV32I), boot ROM
  0xFC0000..0xFFFFFF. Exact map: 10.8.1.
- Assembler syntax: the classic 68000 syntax: `move.l d0,(a0)+`, `#` immediates, `$` hex, `%`
  binary, `.b/.w/.l/.s` size suffixes, `dc.b/dc.w/dc.l`, `ds.b`, `even`. Branch relaxation
  (`bra.s` 2 bytes versus `bra.w` 4 bytes) uses the multi-pass mode.

### 2.4 ARM2

- 1986, first shipped in 1987. Clock 8 MHz (planned items: 8 and 12 MHz).
- 32-bit data bus, 26-bit address bus (64 MiB). Little-endian. 3-stage pipeline: reading R15 as
  an operand gives the instruction's address + 8 (+12 for a register-specified shift).
- Registers: 16 visible (R0..R15), 27 physical: FIQ banks R8..R14, IRQ and SVC bank R13..R14.
- R15 combines PC and PSR: N 31, Z 30, C 29, V 28, I 27 (IRQ disable), F 26 (FIQ disable),
  PC 25..2, mode 1..0 (USR 0, FIQ 1, IRQ 2, SVC 3).
- Instruction set (ARMv2): conditional execution on every instruction, the 16 data-processing
  ops with the barrel shifter (and its carry out), 8-bit immediates rotated by an even amount,
  MUL and MLA, LDR/STR (word and byte; an unaligned word load rotates), LDM/STM (with `^` for user
  bank or PSR restore), B/BL, SWI, the coprocessor instructions (none fitted, so they take the
  undefined instruction trap), and TEQP/TSTP/CMPP/CMNP for PSR writes. No SWP, no halfwords, no
  Thumb.
- Corner cases: STR and STM of R15 store the instruction's address + 12 (with the PSR bits).
  TEQP and its siblings in user mode change only N, Z, C and V (I, F and the mode bits are kept).
  MUL and MLA with Rd == Rm or Rd == R15 are unpredictable on the chip; the emulator does
  something fixed and documented, and the assembler rejects them.
- Exception vectors at 0x00: reset 0x00, undefined 0x04, SWI 0x08, prefetch abort 0x0C, data abort
  0x10, address exception 0x14 (data address with any of bits 31..26 non-zero), IRQ 0x18, FIQ
  0x1C. All but IRQ and FIQ enter SVC mode. The old R15 (PC and PSR) goes to the mode's R14.
- On entry I is set for every exception; F is set only on reset and FIQ. R14 holds:
  - SWI and undefined: the address of the next instruction (return `MOVS pc, lr`);
  - IRQ, FIQ and prefetch abort: the address of the interrupted or aborted instruction + 4
    (return `SUBS pc, lr, #4`);
  - data abort and address exception: the address of the aborted instruction + 8 (return
    `SUBS pc, lr, #8` to retry it).
- Reset: SVC mode, I and F set, PC = 0. The boot ROM is mirrored at 0 until `ROM_OVERLAY_OFF`.
- Cycles: in S (sequential), N (non-sequential) and I (internal) cycles, with N = 2 clocks,
  S = I = 1 clock at 8 MHz with page-mode DRAM. Data processing 1S (+1I with a register shift,
  +1S+1N when writing PC); LDR 1S+1N+1I; STR 2N; LDM nS+1N+1I; STM (n-1)S+2N; B and BL 2S+1N;
  MUL and MLA 1S + up to 16 I (2 bits of the multiplier per cycle, early exit); SWI 2S+1N.
- Planned map: RAM from 0x0000000, VRAM 0x2000000, devices 0x3000000, boot ROM 0x3800000.
- Assembler syntax: classic ARM syntax, condition before `S` (`ADDEQS r0, r1, r2, LSL #2`), UAL
  order accepted too, `LDMFD sp!, {r0-r3, pc}^`, `LDR r0, =value` with literal pools
  (`.ltorg`/`.pool`), `ADR`. Immediates that do not fit the rotated form become MVN/CMN/SUB
  alternatives or an error with the line number.

### 2.5 MIPS R3000

- 1988. Clock 20, 25 or 33 MHz (R3000A up to 40). Planned items: 20 and 25 MHz. At 25 MHz it is
  the most host-expensive CPU (1.25 M instructions per tick when busy): expect time dilation on
  busy servers.
- 32-bit data and address buses. Bi-endian, chosen at reset; this machine wires it
  little-endian.
- Registers: $0 (always zero) .. $31, HI, LO, PC. Coprocessor 0 (system control): Index 0,
  Random 1, EntryLo 2, Context 4, BadVAddr 8, EntryHi 10, Status 12, Cause 13, EPC 14, PRId 15.
  No Count/Compare (those came with the R4000), so the timer is the external interval timer.
- Pipeline effects the emulator must model: one load delay slot (the instruction after a load
  still sees the old register value; modelled as a pending (register, value) pair committed after
  the next instruction reads its operands, where a write by that next instruction to the same
  register wins); LWL and LWR read the pending (delay-slot) value of their target register when
  it is the target of the load still in flight, so two back-to-back LWL/LWR into one register
  merge correctly (a well-known point emulators get wrong); one branch delay slot (always
  executed, MIPS I has no branch-likely); exceptions in a delay slot set Cause.BD and EPC points
  at the branch.
- Status: CU3..0 31..28, RE 25 (reverse endian in user mode), BEV 22, TS 21, PE 20, CM 19, PZ 18,
  SwC 17, IsC 16 (isolate cache: stores do not reach memory, used by cache flush code, must be
  honoured), IM 15..8, and the KUo/IEo/KUp/IEp/KUc/IEc stack in 5..0, pushed on exception and
  popped by RFE.
- PRId reads 0x0000_0230 (implementation 2, revision 3.0: R3000A).
- Exception codes: Int 0, Mod 1, TLBL 2, TLBS 3, AdEL 4, AdES 5, IBE 6, DBE 7, Sys 8, Bp 9, RI 10,
  CpU 11, Ov 12. ADD/ADDI/SUB trap on signed overflow; the U forms do not.
- Vectors: reset 0xBFC00000; UTLB miss 0x80000000 (BEV 0) or 0xBFC00100 (BEV 1); general
  0x80000080 or 0xBFC00180. Only TLB misses on kuseg addresses use the UTLB vector; a miss on a
  kseg2 address goes to the general vector.
- Segments: kuseg 0x00000000..0x7FFFFFFF (TLB mapped), kseg0 0x80000000..0x9FFFFFFF (unmapped,
  cached), kseg1 0xA0000000..0xBFFFFFFF (unmapped, uncached), kseg2 0xC0000000.. (mapped). The
  TLB has 64 fully associative entries (EntryHi VPN 31..12, ASID 11..6; EntryLo PFN 31..12, N 11,
  D 10, V 9, G 8), Random counts down 63..8, instructions TLBR, TLBWI, TLBWR, TLBP.
- MULT about 12 cycles and DIV about 35, running in parallel with later instructions; MFHI/MFLO
  stall until done. LWL/LWR/SWL/SWR unaligned access. No FPU fitted: COP1 raises CpU.
- Planned physical map: RAM from 0, VRAM 0x10000000, devices 0x1F000000 (kseg1 0xBF000000), boot
  ROM 0x1FC00000.
- Assembler syntax: GNU MIPS: `$t0` names and numbers, `.set reorder` (default: the assembler
  fills branch and load delay slots with NOPs where needed) and `.set noreorder` (programmer
  fills them), `.set at`/`noat`, pseudo-instructions (`li`, `la`, `move`, `b`, `blt`, `bge`...)
  using `$at`, `%hi`/`%lo`.

### 2.6 RISC-V RV32I (built now)

- RV32I is an instruction set, not one chip. This machine's CPU is a simple in-order 32-bit core
  implementing RV32IM + Zicsr + Zicntr + Zifencei, machine mode only. In game it is called
  "RV32I".
- Clock: 10 MHz (planned items: 10 and 25 MHz). The RV32I platform accepts any whole number of
  MHz from 1 to 100 MHz. This limit is the RV32I platform's own (`Rv32Platform.validate`); the
  time conversions (1.4) work for any rate, and other platforms set their own limits (the 8088
  needs 4_772_727 Hz, the 68000 12.5 MHz).
- 32-bit data and address buses (4 GiB space). Little-endian.
- Registers: x0 (always zero) .. x31, PC. ABI names: zero, ra, sp, gp, tp, t0..t2, s0/fp, s1,
  a0..a7, s2..s11, t3..t6.
- Privilege: M-mode only. No S or U mode, no PMP, no MMU, no A, F, D or C extension.
- Misaligned loads and stores are performed in hardware (allowed by the spec) at +1 cycle; they
  never trap. Instruction fetch alignment is 4: a taken branch or jump to a target that is not a
  multiple of 4 raises instruction-address-misaligned on the jump itself.
- Details of CSRs, traps and the memory map are in sections 4 and 7.

### 2.7 Difficulty ranking and why RV32I goes first

Easiest first:

1. RV32I: fixed 32-bit encodings in six formats, no flags, no delay slots, no modes beyond M,
   no segmentation, a tiny privileged spec, GNU assembler syntax is simple and regular, and a
   standard C/C++ toolchain targets it, which gives the later C/C++ path a reference.
2. MIPS R3000: also fixed 32-bit and regular, but load and branch delay slots, exceptions in delay
   slots, CP0 with the KU/IE stack, a TLB, HI/LO interlocks, LWL/LWR, overflow traps.
3. ARM2: conditional execution everywhere, barrel shifter carry-out rules, rotated immediates,
   PC+8/+12 reads, the combined 26-bit PC/PSR, banked registers, LDM/STM corner cases, the S/N/I
   cycle model, literal pools in the assembler.
4. 8086 (8088 rides along): variable length 1 to 6 bytes plus prefixes, ModR/M, segmentation,
   AF/PF flags, REP string ops, undocumented aliases, per-form cycle tables, jump relaxation in
   the assembler.
5. 68000: variable length 2 to 10 bytes, 12 addressing modes crossed with three sizes, X flag and
   BCD, supervisor/user stacks, group 0/1/2 exception frames, address errors, the longest timing
   tables, branch relaxation.

Planned order after RV32I: R3000, ARM2, 8086 with 8088, 68000. Changed by the user: the 68000
and the 68040 come next (section 10).

### 2.8 68040

- Released 1990. The 68000's successor with a 32-bit bus, the full 68020-class integer
  instruction set, a built-in FPU and a built-in MMU. Real parts at 25, 33 and 40 MHz. CPU
  items: 25 and 33 MHz; the platform accepts 25 to 40 MHz.
- 32-bit data and address buses. Big-endian. Misaligned data accesses are legal.
- Emulated: the whole integer unit (user and supervisor, with ISP, MSP and the 68040 stack
  frames) and the FPU (80-bit extended registers, exact soft-float arithmetic). Not emulated: the
  MMU (its registers exist, paging never turns on) and the caches (CINV and CPUSH are no-ops).
- FPU instructions the real chip leaves to its floating-point support package (the
  transcendentals, FINT, FMOD, FREM, FMOVECR, packed decimal, denormal operands and others) are
  emulated by default. The strict FPU machine option makes them trap as on bare hardware.
- Interrupts, reset, memory map and boot ROM are shared with the 68000 machine. Full design:
  section 10.

## 3. Peripherals (CPU-neutral)

Register offsets are bytes inside the device's window. All registers are 32 bits wide unless
said; they accept 8, 16 and 32-bit accesses per the bus rule in 1.2. Reserved bits read 0 and
ignore writes. Reads of unused offsets inside a window return 0; writes are ignored. "W1C" means
write 1 to clear. Reset values are given as "reset X".

### 3.1 System controller (`SysCon`)

- 0x00 POWER (W): 0x5555 power off (exit code 0); 0x3333 | (code << 16) power off with failure
  code `code`; 0x7777 reset. Other values ignored. (Same encoding as QEMU's test finisher so
  existing bare-metal test code works.) SysCon only records the request and calls
  `clock.requestStopAt(clock.now())`; the machine acts on it after the writing instruction (1.3).
- 0x04 RAM_BASE (R), 0x08 RAM_SIZE (R, bytes), 0x0C CPU_HZ (R).
- 0x10 MACHINE_ID (R): 0x43504C46 (ASCII "FLPC" little-endian).
- 0x14 DEBUG_OUT (W): low byte appended to the host debug log (separate from COM ports; for tests
  and for the in-game debug screen).
- 0x18 VRAM_BASE (R), 0x1C VRAM_SIZE (R).
- 0x20 PRESENT (R): bit 0 HDD, bit 1 keyboard, bit 2 mouse, bit 3 COM1 host connected, bit 4
  COM2 host connected.
- 0x24 ROM_OVERLAY_OFF (W): any write asks the machine to turn off the reset-time ROM mirror at 0
  (68000 and ARM2 only; ignored on RV32I). Recorded and acted on between runs, like POWER.
- 0x28 WAIT (W): any write asks the machine to treat the CPU as waiting until one of its interrupt
  pins is asserted (1.4). Recorded and acted on between runs, like POWER. Works on every CPU;
  RV32I software normally uses WFI instead.

### 3.2 Interrupt controller (`InterruptController`)

- 32 level-sensitive lines, line 0 unused. 8 outputs. Each line is routed to one output by its
  ROUTE field. Output k = (PENDING & ENABLE & lines routed to k) != 0. How outputs reach CPU pins
  is per platform (1.3); RV32I uses output 0 only.
- 0x00 PENDING (R): bit n = current level of line n.
- 0x04 ENABLE (RW, reset 0).
- 0x08 CLAIM (R): the lowest n with PENDING & ENABLE bit n set (any output), 0 if none. No side
  effect: lines are levels, so the handler clears the source in the device.
- 0x0C VECTOR_BASE (RW, reset 0x08): used only by the 8086/8088 acknowledge cycle.
- 0x10, 0x14, 0x18, 0x1C ROUTE0..ROUTE3 (RW, reset 0): 4 bits per line, line n in ROUTE(n / 8)
  bits 4 * (n % 8) + 2 .. 4 * (n % 8) (output 0..7; bit 4 * (n % 8) + 3 reserved).
- 0x20 + 4 * k CLAIM_OUT k (R), k = 0..7: like CLAIM but only lines routed to output k.
- Line assignment (all machines): 1 COM1, 2 COM2, 3 PS/2 keyboard, 4 PS/2 mouse, 5 HDD, 6 VGA
  vertical blank, 7 interval timer. Planned (11.1.1): 8 COM3, 9 floppy, 10 network card,
  11 VGA display 1 vertical blank, 12 VGA display 2 vertical blank. Lines 3 and 4 become "PS/2
  port 0" and "PS/2 port 1" (11.1.4).

### 3.3 Interval timer (`IntervalTimer`)

- Counts at 1 MHz from power-on, derived from the cycle clock:
  `CycleClock.cyclesToMicros(clock.now(), clockHz)` (exact for any clock rate, see 1.4). Expiry
  cycles come from `microsToCycles` of the absolute expiry time, so the grid never drifts.
- 0x00 COUNTER_LO (R): reading it latches COUNTER_HI. 0x04 COUNTER_HI (R, latched).
- 0x08 PERIOD (RW, microseconds, reset 0). 0 stops the timer.
- 0x0C CTRL (RW, reset 0): bit 0 ENABLE, bit 1 IRQ_ENABLE, bit 2 ONE_SHOT.
- 0x10 STATUS: bit 0 EXPIRED (W1C).
- Behaviour: writing CTRL with ENABLE set (or PERIOD while enabled) restarts the countdown from
  now (and calls `clock.requestStopAt` for the expiry when IRQ_ENABLE is set). Each time PERIOD
  microseconds elapse, EXPIRED is set; with ONE_SHOT, ENABLE then clears. Periodic expiry stays on
  the original grid (no drift). Line 7 = EXPIRED && IRQ_ENABLE.
- This is the timer for CPUs without their own (8086, 68000, ARM2, R3000). RV32I has it too.

### 3.4 Real-time clock (`Rtc`)

- Wall-clock seconds since the Unix epoch from a host `LongSupplier` (default
  `System.currentTimeMillis() / 1000`; tests inject a fixed source; the game may later use world
  time). It runs on host time, not emulated time, as a battery-backed RTC would.
- 0x00 SECONDS_LO (R): reading latches SECONDS_HI. 0x04 SECONDS_HI (R, latched).
- 0x08 SET_LO (W), 0x0C SET_HI (W): writing SET_HI sets the clock to (HI << 32 | LO) by storing an
  offset from the host source. Reset keeps the offset; power-on clears it.

### 3.5 COM ports: 16550 subset (`Uart16550`)

- Two ports, COM1 and COM2 (three with COM3, planned in 11.1.2). Registers are 8-bit at offsets 0..7 (register shift 0); a wider access
  reads the register zero-extended and writes its low byte. Input clock 1.8432 MHz, so baud =
  115200 / divisor.
- 0 RBR (R, DLAB 0): pop the RX FIFO (0 if empty). THR (W, DLAB 0): push to the TX FIFO.
  DLL (RW, DLAB 1): divisor low byte.
- 1 IER (RW, DLAB 0, reset 0): bit 0 received data available, bit 1 THR empty, bit 2 receiver line
  status, bit 3 modem status. DLM (RW, DLAB 1): divisor high byte. Setting bit 1 while the THR (TX
  FIFO) is empty raises the THR empty interrupt at once, as on the real chip; drivers rely on it
  to start sending.
- 2 IIR (R, reset 0x01): bit 0 = 0 when an interrupt is pending; bits 3..1 identify it in priority
  order: 0b011 receiver line status (cleared by reading LSR), 0b010 received data at or above the
  trigger level (cleared when the FIFO drops below it), 0b110 character timeout (cleared by
  reading RBR), 0b001 THR empty (cleared by reading IIR when it is the reported source, or by
  writing THR), 0b000 modem status (cleared by reading MSR). Bits 7..6 = 0b11 when FIFOs are
  enabled. FCR (W): bit 0 FIFO enable (16 bytes each way; disabled means 1-byte holding
  registers), bit 1 clear RX FIFO, bit 2 clear TX FIFO, bits 7..6 RX trigger level 1, 4, 8, 14.
  DMA mode bit 3 is ignored.
- 3 LCR (RW, reset 0): bits 1..0 word length 5..8, bit 2 stop bits, bits 5..3 parity, bit 6 break
  (ignored), bit 7 DLAB. Only the character length matters: char bits = 1 start + data + parity +
  stop.
- 4 MCR (RW, reset 0): bit 0 DTR, bit 1 RTS, bit 2 OUT1, bit 3 OUT2, bit 4 loopback. In loopback
  TX goes to RX internally (nothing reaches the host) and the MSR inputs are wired from MCR as on
  the real chip: CTS = RTS, DSR = DTR, RI = OUT1, DCD = OUT2. The interrupt output is not gated by
  OUT2 (that gate was a feature of one PC board, not of the UART); stated here because PC-era code
  sets OUT2 and bare-metal code does not.
- 5 LSR (R, reset 0x60): bit 0 data ready, bit 1 overrun (cleared on read), bit 2 parity error, bit 3
  framing error, bit 4 break (bits 2..4 always 0: the link is perfect), bit 5 THR empty, bit 6
  transmitter empty (FIFO and shift register), bit 7 error in RX FIFO (always 0).
- 6 MSR (R): bit 4 CTS, bit 5 DSR, bit 6 RI, bit 7 DCD. Outside loopback CTS, DSR and DCD are set
  while a host endpoint is connected and RI is 0. Bits 3..0, cleared on read: bit 0 DCTS, bit 1
  DDSR (changed since last read), bit 2 TERI (trailing edge of RI: RI went from 1 to 0, only
  possible in loopback through OUT1), bit 3 DDCD.
- 7 SCR (RW): scratch.
- Divisor reset value 1 (115200 baud). The real chip's reset divisor is undefined; a divisor of 0
  is treated as 1.
- Timing (lazy, from cycle stamps): the transmitter takes one character time
  (`charBits * divisor * 16 * clockHz / 1_843_200` cycles) per byte from TX FIFO to the host
  output buffer. Bytes from the host enter the RX FIFO at the same pace. Completion cycles are
  computed from an absolute stamp (the cycle the stream last started from idle, or the divisor or
  LCR last changed): byte n of the stream completes at
  `start + n * charBits * divisor * 16 * clockHz / 1_843_200` with the multiplication done
  overflow-safely, never by adding rounded per-byte durations. RX FIFO full on arrival: set
  overrun, drop the byte. TX FIFO full on THR write: the byte is dropped. Character timeout: at
  least one byte in the RX FIFO and the FIFO neither read nor written for 4 character times (no
  trigger-level condition; when the FIFO is at or above the trigger the received-data interrupt,
  IIR 0x04, is reported instead because it has the same priority and is checked first).
  `nextEventCycle()` is the next TX completion, RX arrival or timeout that would change the
  interrupt output under the current IER (1.3); other completions are caught up lazily. A THR write
  with THRE interrupts enabled calls `clock.requestStopAt` for the completion.
- IRQ line = IIR bit 0 == 0.
- Host side: `int hostWrite(byte[] src, int off, int len)` queues inbound bytes (inbound queue 4096
  bytes, returns how many were accepted); `int hostRead(byte[] dst, int off, int len)` drains
  transmitted bytes (output buffer 64 KiB, oldest dropped on overflow, with a counter);
  `setHostConnected(boolean)`. A later in-game COM cable connects two PCs by pumping one's output
  into the other's input each tick.

### 3.6 PS/2 controller, keyboard and mouse (`Ps2Controller`, `Ps2Keyboard`, `Ps2Mouse`)

- One controller window with two identical ports: keyboard at 0x00, mouse at 0x10. Planned
  (11.1.4): either port takes either device, so a keyboard works in the second port too.
- Port +0x0 DATA: read pops the next byte from the device (0 if none); write sends a command byte
  to the device, whose reply bytes are queued at once.
- Port +0x4 STATUS (R; W1C for bit 1): bit 0 RX ready, bit 1 overrun (a byte was dropped), bit 2
  device present, bits 15..8 number of bytes queued.
- Port +0x8 CTRL (RW, reset 0): bit 0 IRQ enable, bit 1 port enable (while 0, host input to the
  device is discarded).
- IRQ: keyboard line 3 = RX ready && IRQ enable on port 0; mouse line 4 likewise on port 1.
- No byte-time emulation (a real PS/2 byte takes about 1 ms): bytes are available immediately.
  Host input arrives once per tick anyway.
- Keyboard (scancode set 2 only):
  - FIFO of 32 bytes. When full, one 0x00 (the set 2 overrun code) is queued in the last slot and
    further bytes are dropped until it is read.
  - Power-on: queues 0xAA only (self-test passed; nothing to acknowledge), and only while the
    keyboard is plugged in. Command 0xFF: 0xFA, then 0xAA. Unplugging empties the queue, overrun
    code included, so a replugged keyboard queues bytes again at once.
  - Commands: 0xED set LEDs (0xFA, then the data byte, 0xFA; bit 0 Scroll, 1 Num, 2 Caps lock,
    exposed to the host for rendering); 0xEE echo (0xEE); 0xF0 scancode set (0xFA, then data: 0 =
    query, replies 0xFA 0x02; 2 = ok 0xFA; 1 or 3 = 0xFE); 0xF2 identify (0xFA 0xAB 0x83); 0xF3
    typematic rate and delay (0xFA, data, 0xFA); 0xF4 enable (0xFA); 0xF5 disable and defaults
    (0xFA); 0xF6 defaults (0xFA); 0xFE resend (repeat last byte); anything else 0xFE.
  - Host API: `keyDown(Ps2Key)`, `keyUp(Ps2Key)`, `inject(byte...)` for raw bytes, `leds()`.
    `Ps2Key` is an enum of the 104-key layout with each key's set 2 make code. Make = code
    (with 0xE0 prefix for extended keys); break = 0xF0 + code (0xE0 0xF0 code for extended).
    Specials: Print Screen make E0 12 E0 7C, break E0 F0 7C E0 F0 12; Pause make E1 14 77 E1 F0
    14 F0 77 and no break. Examples to test: A 0x1C, Enter 0x5A, Esc 0x76, Space 0x29, Left Shift
    0x12, Right Ctrl E0 14, Right Arrow E0 74.
  - Typematic repeat is done by the keyboard device in emulated time: the last key held repeats
    its make code after the delay (default 500 ms) at the rate (default 10.9 per second), both
    set by 0xF3 per the standard table. `nextEventCycle()` is the next repeat while the keyboard
    port's IRQ is enabled (1.3).
- Mouse (standard 3-byte protocol, no wheel):
  - FIFO of 48 bytes; on overflow whole packets are dropped, never partial ones.
  - Power-on: 0xAA 0x00, only while the mouse is plugged in. Command 0xFF: 0xFA 0xAA 0x00. Data
    reporting starts disabled.
  - Commands: 0xE6/0xE7 scaling 1:1/2:1 (0xFA; 2:1 applies the standard transform), 0xE8
    resolution (0xFA, data, 0xFA), 0xE9 status (0xFA + 3 status bytes, below), 0xEA stream mode, 0xEB read
    data (0xFA + one packet), 0xF2 identify (0xFA 0x00), 0xF3 sample rate (0xFA, data, 0xFA), 0xF4
    enable reporting, 0xF5 disable, 0xF6 defaults, 0xFE resend; others 0xFE.
  - Status bytes (0xE9). Byte 1: bit 6 remote mode, bit 5 reporting enabled, bit 4 scaling 2:1,
    bit 2 left, bit 1 middle, bit 0 right (the reverse of the packet's button order). Byte 2: the
    resolution code (0..3). Byte 3: the sample rate.
  - Host API: `move(int dx, int dy)` (dy positive means up, the host converts), `buttons(int mask)`
    (bit 0 left, 1 right, 2 middle). Movement accumulates and one packet is emitted per sample
    period (default 100 per second) while there is movement or a button change.
  - Packet: byte 0 = left | right << 1 | middle << 2 | 1 << 3 | xSign << 4 | ySign << 5 |
    xOverflow << 6 | yOverflow << 7; byte 1 = dx low 8 bits; byte 2 = dy low 8 bits. dx, dy are
    clamped to -256..255 per packet with the overflow bit set when clamped; the remainder carries.

### 3.7 VGA framebuffer (`VgaDisplay`)

- Not a VGA chip: no CRTC, sequencer, planes or timing. The guest draws into VRAM; once per game
  tick the host can copy the visible image across. This is the user's explicit choice for
  performance.
- Planned (11.1.3): three independent displays, each with its own control window and its own
  512 KiB of VRAM, the three slices held in one 1.5 MiB array so the CPU fast path still has one
  VRAM range.
- VRAM: a 512 KiB byte array, on the CPU fast path (1.2) at a machine-specific base. Every write
  sets the bit of its 4 KiB page in the device's `long[] dirtyPages` (128 pages, 2 longs): the CPU
  does it on its fast-path store, the bus on slow-path stores through VRAM views, DMA through its
  `DmaWindow.onWrite`. VRAM stores cost no extra emulated cycles.
- Control window:
  - 0x00 MODE (RW, reset 0). 0: text 80x25, 2 bytes per cell (character in CP437, then attribute:
    bits 3..0 foreground, bits 6..4 background, bit 7 blink or bright background per CTRL bit 3),
    pitch 160, rendered by the host with an 8x16 font into 640x400. 1: 320x200, 8 bpp through the
    palette, pitch 320. 2: 640x480, 4 bpp (two pixels per byte, high nibble is the left pixel),
    palette entries 0..15, pitch 320. 3: 640x480, 8 bpp through the palette, pitch 640. Other
    values are ignored (MODE keeps its old value).
  - 0x04 CTRL (RW, reset 0xD): bit 0 display enable, bit 1 VBLANK IRQ enable, bit 2 cursor enable,
    bit 3 attribute bit 7 means blink (else bright background). Reset has blink on, as real VGA
    does, so PC-era text code looks the same.
  - 0x08 STATUS: bit 0 VBLANK (set at every frame end, W1C).
  - 0x0C CURSOR (RW, reset 0): bits 7..0 column, 15..8 row (text mode).
  - 0x10 FRAME (R): frame counter, +1 per frame end (20 per second).
  - 0x14 WIDTH, 0x18 HEIGHT (R): pixels in graphics modes, 80 and 25 in text mode. 0x1C BPP (R):
    16 (text), 8 or 4. 0x20 PITCH (R): bytes per row.
  - 0x24 START (RW, reset 0): VRAM byte offset of the displayed image, for page flipping. If
    START + PITCH * HEIGHT exceeds VRAM, the frame wraps to VRAM offset 0.
  - 0x28 BANK (RW, reset 0, values 0..7): 64 KiB bank for CPUs with a 64 KiB VRAM window
    (8086/8088). The machine applies it with `MemoryRegion.setOffset(BANK * 0x10000)` on the
    banked view. Unused on RV32I, where all of VRAM is mapped.
  - 0x2C PAL_INDEX (RW, reset 0, values 0..255): palette entry PAL_DATA addresses.
  - 0x30 PAL_DATA (RW): reads or writes palette entry PAL_INDEX (0x00RRGGBB); PAL_INDEX then
    advances by 1 (wrapping at 256), on the access that touches byte 3 (1.2). This is how CPUs
    whose window does not reach 0x400 (8086 ports) set the palette.
  - 0x400..0x7FF PALETTE: 256 entries of 0x00RRGGBB, entry i at 0x400 + 4 * i, directly mapped
    where the window is large enough. Reset palette: 0..15 the 16 CGA colours
    (0x000000, 0x0000AA, 0x00AA00, 0x00AAAA, 0xAA0000, 0xAA00AA, 0xAA5500, 0xAAAAAA, 0x555555,
    0x5555FF, 0x55FF55, 0x55FFFF, 0xFF5555, 0xFF55FF, 0xFFFF55, 0xFFFFFF), 16..231 a 6x6x6 colour
    cube (levels 0, 95, 135, 175, 215, 255), 232..255 a grey ramp (8 + 10 * i).
- Dirty tracking: VRAM writes set page bits (above); palette, MODE, CTRL, CURSOR or START writes
  set a `fullDirty` flag. `endFrame()` (called by the machine at the end of each tick): FRAME += 1,
  STATUS.VBLANK = 1, and if any page bit or `fullDirty` is set: `generation += 1`; every visible
  row that overlaps a dirty page (or every row, if `fullDirty`) gets `rowGeneration[row] =
  generation`; page bits and `fullDirty` are cleared. IRQ line 6 = VBLANK && IRQ enable.
- Host side, no allocation on the hot path:
  - `long generation()`.
  - `int copyVisibleInto(byte[] dst)`: copies the visible bytes (PITCH * HEIGHT from START,
    wrapped) into a caller-owned buffer, returns the count; `IllegalArgumentException` if `dst`
    is too small.
  - `BitSet dirtyRowsSince(long generation)`: the rows changed after that generation (all rows if
    the mode, START or palette changed since then), so the integration can send deltas.
  - `VgaFrame snapshot()`: an immutable copy (mode, width, height, pitch, cursor column, row and
    visibility, blink flag, display enable, the visible bytes, the 256-entry palette, the
    generation), for tests and first frames.
  The game sends data only when the generation changed; text mode is 4000 bytes, so deltas matter
  only for graphics modes. The client turns it into a texture (font rendering for text mode lives
  client-side, later).

### 3.8 HDD controller (`HddController`) and disk image

- Block device, 512-byte sectors, DMA into memory regions (RAM or VRAM) only.
- 0x00 COMMAND (W): 1 READ (disk to memory), 2 WRITE (memory to disk), 3 FLUSH (no-op that
  completes), 4 IDENTIFY (writes a 512-byte info block to DMA_ADDR). Writing while BUSY is
  ignored and sets ERROR with code BUSY.
- 0x04 STATUS (R; W1C for bits 1 and 2): bit 0 BUSY, bit 1 DONE, bit 2 ERROR, bit 3 media present.
- 0x08 LBA (RW), 0x0C COUNT (RW, sectors, 1..256), 0x10 DMA_ADDR (RW, bus address).
- 0x14 CAPACITY (R, sectors; 0 with no media). 0x18 IRQ_ENABLE (RW, bit 0). 0x1C ERROR_CODE (R):
  0 none, 1 no media, 2 LBA range (LBA + COUNT > CAPACITY), 3 DMA range (the buffer is not wholly
  inside one RAM or VRAM region), 4 bad command, 5 busy, 6 bad count. 0x20 SECTOR_SIZE (R, 512).
- Command flow: on a valid command, BUSY = 1, DONE = ERROR = 0, and completion is scheduled at
  `now + cycles(HDD_COMMAND_US + COUNT * HDD_SECTOR_US)` with `HDD_COMMAND_US = 500` and
  `HDD_SECTOR_US = 100` (about 5 MB/s), converted with `microsToCycles`. The command write calls
  `clock.requestStopAt(completion)`. The data moves at completion (one array copy through the
  `DmaWindow` from `SystemBus.dma`, then its `onWrite`), then BUSY = 0, DONE = 1. An invalid
  command completes at once with ERROR = 1 and ERROR_CODE set. IRQ line 5 = IRQ_ENABLE &&
  (DONE || ERROR). `nextEventCycle()` is the completion cycle whether or not IRQ_ENABLE is set,
  because the transfer changes memory the CPU reads directly (1.3). The host may swap or remove
  the disk while a command is in flight, so completion checks again: no disk ends in ERROR with
  code no media, and a READ or WRITE that no longer fits the new disk ends in ERROR with code
  LBA range (no data moves).
- IDENTIFY block: offset 0 ASCII "FLHD", 4 capacity in sectors (u32 LE), 8 sector size (u32 LE),
  16 model string (32 bytes ASCII, space padded, for example "FLIPPIES HDD 16M"), rest 0.
- `DiskImage` interface (sector-level), implemented now by `SparseDiskImage`: 64 KiB chunks
  allocated on first write (never-written chunks read as zeros and are not stored), a dirty-chunk
  set for incremental saves, and chunk load/export for the future world-save code. Export returns
  copies, so a save can run off the machine's thread.
- Planned (11.1.6): disks get an identity (`DiskImage.id()`, a UUID) checked by saved state, a
  lazily loaded `PagedDiskImage` for in-game disks of up to 1 GiB, and ERROR_CODE 7 media error.

## 4. The RV32I machine

### 4.1 Memory map (constants in `machine/Rv32Map.java`)

- 0x0000_0000..0x0000_0FFF: unmapped (null pointers fault).
- 0x0000_1000..0x0000_FFFF: boot ROM (60 KiB window, read-only; the image is padded with zeros to
  60 KiB, so bytes past it read 0). Reset PC 0x0000_1000 unless `MachineConfig.resetPc` says
  otherwise.
- 0x0010_0000 (0x1000): system controller.
- 0x0200_0000 (0x1_0000): CLINT (machine timer and software interrupt).
- 0x0C00_0000 (0x1000): interrupt controller.
- 0x1000_0000 (0x1000): COM1. 0x1000_1000: COM2. 0x1000_2000: PS/2 controller.
  0x1000_3000: HDD controller. 0x1000_4000: VGA control. 0x1000_5000: interval timer.
  0x1000_6000: RTC. Each window is 0x1000 bytes.
- 0x2000_0000 (0x8_0000): VRAM, 512 KiB.
- 0x8000_0000: RAM, RAM_SIZE bytes (64 KiB to 64 MiB).
- Planned additions (11.1.1): RAM up to 128 MiB (0x8000_0000..0x87FF_FFFF); VRAM grows to 0x18_0000 (displays 0, 1, 2 at 0x2000_0000,
  0x2008_0000, 0x2010_0000); COM3 0x1000_7000, floppy 0x1000_8000, network card 0x1000_9000,
  VGA display 1 control 0x1000_A000, VGA display 2 control 0x1000_B000.
- Everything else: unmapped (access fault).

### 4.2 CLINT (`Clint`)

- 0x0000 MSIP (RW): bit 0 drives `mip.MSIP`.
- 0x4000 MTIMECMP_LO, 0x4004 MTIMECMP_HI (RW, reset 0xFFFF_FFFF each).
- 0xBFF8 MTIME_LO, 0xBFFC MTIME_HI (R). `mtime = CycleClock.cyclesToMicros(clock.now(), clockHz)`:
  1 MHz. mtime is read-only in this machine (writes ignored) so the `time` CSR, which the CPU
  computes the same way from its own cycle count, always agrees with it. Reading the halves is
  not latched; software uses the standard hi, lo, hi re-read loop.
- `mip.MTIP` = mtime >= mtimecmp (unsigned 64-bit), a level. `nextEventCycle()` =
  `microsToCycles(mtimecmp, clockHz)`, the first cycle at which mtime reaches mtimecmp
  (Long.MAX_VALUE if mtimecmp is negative as a signed long, or the result would overflow). A
  write to either MTIMECMP half updates MTIP at once and calls `clock.requestStopAt` with the new
  event cycle.

### 4.3 Interrupt wiring

- CPU pins are `mip` bit numbers: `IRQ_PIN_MSIP = 3`, `IRQ_PIN_MTIP = 7`, `IRQ_PIN_MEIP = 11`.
- CLINT MSIP and MTIP drive pins 3 and 7. Interrupt controller output 0 drives pin 11; outputs
  1..7 are not connected on this machine.
- Device lines as in 3.2.

### 4.4 CSRs

Accessing a CSR not listed is an illegal instruction. Writing a read-only CSR (address bits 11..10
= 0b11) with CSRRW, or with CSRRS/CSRRC and rs1 != x0 (or a nonzero immediate), is an illegal
instruction. Reads have no side effects.

- mvendorid 0xF11 = 0, marchid 0xF12 = 0, mimpid 0xF13 = 1, mhartid 0xF14 = 0 (read-only).
- mstatus 0x300: only MIE (bit 3) and MPIE (bit 7) are writable; MPP (bits 12..11) reads 0b11;
  everything else reads 0. mstatush 0x310 reads 0, writes ignored.
- misa 0x301: 0x4000_1100 (MXL = 1, I and M), writes ignored.
- mie 0x304: bits 3, 7, 11 writable, others 0.
- mtvec 0x305: BASE bits 31..2, MODE bits 1..0; MODE 0 direct, 1 vectored; a written MODE of 2 or
  3 is stored as 0. Reset 0.
- mscratch 0x340 (RW). mepc 0x341 (RW, bits 1..0 read 0). mcause 0x342 (RW). mtval 0x343 (RW).
- mip 0x344: bits 3, 7, 11 reflect the pins; writes are ignored (all three are driven by devices).
- mcycle 0xB00, mcycleh 0xB80, minstret 0xB02, minstreth 0xB82 (RW). They are computed from the
  CPU's own cycle and retired counts plus a stored offset, never from `clock.now()` (which is only
  published at slow-path accesses). A write sets the value the next instruction reads: the
  writing instruction's own increment is suppressed, as in Spike. Writing one half keeps the
  other half.
- cycle 0xC00, time 0xC01, instret 0xC02, cycleh 0xC80, timeh 0xC81, instreth 0xC82 (read-only
  shadows; time = `CycleClock.cyclesToMicros(cpu cycle count, clockHz)`, equal to the CLINT's
  mtime).
- Hardware performance counters (exist so software that probes them works; all hardwired 0):
  mhpmcounter3..31 0xB03..0xB1F and mhpmcounter3h..31h 0xB83..0xB9F (read 0, writes ignored);
  mhpmevent3..31 0x323..0x33F (read 0, writes ignored); hpmcounter3..31 0xC03..0xC1F and
  hpmcounter3h..31h 0xC83..0xC9F (read-only 0).
- mcountinhibit 0x320: reads 0, writes ignored.
- mconfigptr 0xF15: read-only 0.

### 4.5 Traps

- Reset: PC = 0x0000_1000, x1..x31 = 0, mstatus.MIE = 0, MPIE = 0, mie = 0, mtvec = 0, mcause = 0,
  mepc = 0, mtval = 0, counters 0.
- A JAL, JALR or taken branch whose target is not a multiple of 4 raises instruction address
  misaligned on the jump itself, and the jump does not retire: rd is not written (this matters
  when rd == rs1, for example `jalr ra, 2(ra)`), minstret does not count it, mepc = the jump.
- Exception codes used: 0 instruction address misaligned (mtval = target), 1 instruction access
  fault (mtval = address), 2 illegal instruction (mtval = the instruction bits), 3 breakpoint
  (EBREAK, mtval = pc), 5 load access fault (mtval = address), 7 store access fault (mtval =
  address), 11 environment call from M-mode (mtval = 0). Codes 4 and 6 never occur (misaligned
  data is handled in hardware).
- Interrupts: mcause = 0x8000_0000 | 3, 7 or 11. Taken at an instruction boundary when
  mstatus.MIE = 1 and (mip & mie) != 0. Priority when several are pending: MEI, then MSI, then
  MTI. mtval = 0.
- Trap entry (2 cycles on top of the instruction): mepc = the faulting instruction (exceptions)
  or the next instruction to execute (interrupts); mcause, mtval set; MPIE = MIE; MIE = 0;
  PC = mtvec.BASE, or BASE + 4 * cause for interrupts when MODE = 1.
- MRET: PC = mepc, MIE = MPIE, MPIE = 1.
- Illegal instructions include: all-zero and all-one words, any word whose low two bits are not
  0b11, unknown opcodes and funct3/funct7 combinations, SLLI/SRLI/SRAI with bit 25 set, unknown or
  read-only-written CSRs (so `unimp` = 0xC000_1073, CSRRW to `cycle`, is illegal), MISC-MEM
  funct3 other than 000 and 001, SYSTEM funct3 000 words other than ECALL, EBREAK, MRET and WFI,
  and the A/F/D/C encodings.
- Not illegal, executed as no-ops: MISC-MEM funct3 000 (FENCE) with any fm, pred, succ, rd or rs1
  value (the spec says reserved fm values and non-zero rd/rs1 execute as a normal fence), and
  FENCE.I (funct3 001) with any imm, rs1 and rd (the spec says these fields are ignored).
- Double fault halt: if trap entry jumps to an address whose fetch faults before any instruction
  at the handler has retired (typically mtvec still 0), the CPU halts with reason
  "trap handler at 0x%08x is not executable (mcause %d, mepc 0x%08x)". `Exit.HALTED`; the machine
  state becomes `HALTED` until reset or power cycle.
- ECALL and EBREAK always trap (no host hooks). Tests stop machines through the system
  controller.
- WFI: 1 cycle; then the CPU waits until (mip & mie) != 0, regardless of mstatus.MIE. On wake
  with MIE = 1 the interrupt is taken with mepc = the instruction after WFI; with MIE = 0 the CPU
  simply continues after WFI.
- FENCE, FENCE.TSO, PAUSE and FENCE.I: 1-cycle no-ops (single hart, no caches, no decode cache in
  v1; if a decode cache is added later, ordinary stores must invalidate it, so FENCE.I stays a
  no-op).

### 4.6 Cycle costs (the "RV32I" chip's timing, a classic 5-stage in-order core)

- Every instruction 1 cycle, plus:
  - loads +1 (2 total); misaligned load or store +1 more;
  - taken branch, JAL, JALR +2 (3 total, pipeline refill);
  - MUL, MULH, MULHSU, MULHU +2 (3 total);
  - DIV, DIVU, REM, REMU +32 (33 total, one quotient bit per cycle);
  - any device register access +2 (`MMIO_EXTRA_CYCLES`); RAM, ROM and VRAM accesses cost nothing
    extra;
  - trap entry +2; MRET +2.
- mcycle counts every cycle including WFI idle; minstret counts retired instructions (not WFI
  idle, not trapped instructions).

### 4.7 Boot ROM (`machine/BootRom.java`, written at the integration step)

RV32I assembly kept as a Java text block in `BootRom`, assembled once (lazily, cached) with the
RV32I assembler at `textBase = 0x0000_1000`. Behaviour:

1. sp = RAM_BASE + RAM_SIZE (read from the system controller); mtvec = the ROM trap handler.
2. Clear VGA text screen (attribute 0x07), print the banner to COM1 and VGA:
   "Create Flippies PC  RV32I  <n> KiB RAM".
3. If PRESENT bit 0 is clear: print "No disk" and loop on WFI.
4. HDD READ LBA 0, COUNT 1 to RAM_BASE; poll STATUS until DONE or ERROR (no interrupts used).
5. If ERROR, or bytes 510..511 are not 0x55, 0xAA: print "Disk not bootable" and loop on WFI.
6. Jump to RAM_BASE with a0 = RAM_SIZE, a1 = disk capacity in sectors, a2 = the ROM's
   `print_string` routine address (a0 = string pointer; prints to COM1 and VGA). The boot sector
   loads the rest of the program itself.
7. ROM trap handler: print "TRAP mcause=XXXXXXXX mepc=XXXXXXXX mtval=XXXXXXXX" to COM1 and VGA, then
   POWER = 0x3333 | (0xDEAD << 16).

`MachineConfig.bootRom` may supply any other ROM image (tests use this to run programs from ROM
directly, or put programs straight into RAM with `Machine.loadRam` and `MachineConfig.resetPc`).

## 5. Assembler

### 5.1 Shared framework (`computer.asm`)

- Input: one source string, or a list of `AsmSource(fileName, text)` assembled together into one
  image (no `.include`; the game has no file system for it). With several files, `.L*` labels,
  numeric local labels and every symbol not declared `.globl`/`.global`/`.weak` are local to
  their file; global symbols are shared, and a global defined in two files is an error. This is
  what lets several GCC `-S` outputs be combined without a linker (phase C0). Output: `AsmResult`
  with diagnostics and, if there are no errors, an `AsmImage`.
- Guest source is player-authored and assembled on the server thread, so the assembler must
  never run out of memory, overflow the stack or throw: the image is capped at
  `AsmOptions.maxImageBytes` (default 64 MiB; `.space`, `.fill`, `.org`, `.align` and section
  layout check it before allocating and report an error), expressions nest at most 256 deep
  (parentheses and unary operators; deeper is an error), and any internal exception is a bug.
- Tokenizer (`AsmLexer`), configured per backend (`LexerSyntax`): line comment starters (RV32I:
  `#` and `//`), block comments `/* */`, statement separator (RV32I: `;`), number prefixes,
  register sigils (`$` for MIPS, `%` for none here), whether `#` starts an immediate (68000,
  ARM2). Tokens: identifier, integer, character literal, string, punctuation, newline. Token
  positions keep line and column.
- Integers: decimal, `0x`, `0b`, `0o`, and GNU's leading-zero octal (`010` = 8; documented
  because it surprises people). Character literals `'a'`, `'\n'`, `'\t'`, `'\\'`, `'\''`, `'\0'`,
  `'\xNN'`. Strings with the same escapes plus `\"` and octal `\NNN`.
- Symbols: `[A-Za-z_.$][A-Za-z0-9_.$]*`, case-sensitive. Labels `name:`; several labels may share a
  line; label then instruction on one line is fine. Numeric local labels `1:` .. `99:`, referenced
  as `1b` (nearest previous) and `1f` (nearest next), GNU style. `.` is the current location.
- Expressions: 64-bit evaluation, C precedence and semantics: unary `- ~ !`, `* / %`, `+ -`,
  `<< >>` (arithmetic), `< <= > >=`, `== !=` (1 or 0), `&`, `^`, `|`, `&&`, `||`, parentheses.
  This differs from GNU as, which puts `<< >>` with `* /` (so `1+2<<3` is 17 in GNU as and 24
  here); GCC output never depends on it, and C rules are what players expect. Division by zero is
  an error. Each use site range-checks the result for its field. Backend relocation operators
  (RV32I: `%hi`, `%lo`, `%pcrel_hi`, `%pcrel_lo`) are parsed as prefix functions and delegated to
  the backend.
- Symbol values are addresses or equates. Addresses are zero-extended 32-bit values held in a
  `long` (RAM at 0x8000_0000 is +2147483648, not negative), so `sym - .` and range checks are
  right across the sign boundary. A 32-bit backend normalises an operand to signed 32 bits where
  its encoding needs it (see `li` in 5.2).
- Sections: `.text`, `.rodata`, `.data`, `.bss`, and `.section NAME[, flags...]` where NAME maps by
  prefix: `.text*` to text, `.rodata*` and `.srodata*` to rodata, `.data*` and `.sdata*` to data,
  `.bss*` and `.sbss*` to bss, `.init_array*` to init_array, `.fini_array*` to fini_array.
  Sections that are not loaded are dropped silently: names starting `.note` (including
  `.note.GNU-stack`), `.comment` or `.debug`, and any other `.section` whose flags string lacks
  `a`. Statements in a dropped section are parsed (syntax errors still count) but not evaluated
  and emit nothing; labels defined there are not symbols. Any other name with the `a` flag is an
  error. Flags, `@progbits` and `@nobits` are otherwise ignored. This lets GCC and Clang output
  assemble unchanged.
- Layout: text at `options.textBase`; then rodata, init_array, fini_array, data and bss, each
  aligned to the larger of 16 and its own largest `.align`. If `options.dataBase` is set (a ROM
  program), data and bss run at dataBase instead: symbols in data resolve to their run address
  from dataBase, while data's initial bytes are emitted in the image at a load address right
  after fini_array, for `crt0` to copy. Predefined symbols after layout: `__text_start`,
  `__text_end`, `__rodata_start`, `__init_array_start`, `__init_array_end`,
  `__fini_array_start`, `__fini_array_end`, `__data_load_start` (equal to `__data_start` when
  dataBase is not set), `__data_start`, `__data_end`, `__bss_start`, `__bss_end`, `_end`. bss
  content must be zero or reserved space only (a data directive with nonzero values in bss is an
  error).
- Directives (shared):
  - `.org expr`: move the location counter forward within the section (backwards is an error;
    the gap is zero-filled).
  - `.byte`, `.half`/`.short`/`.2byte`, `.word`/`.long`/`.4byte`, `.dword`/`.quad`/`.8byte`: values
    in the backend's byte order; each value must fit the size as signed or unsigned.
  - `.ascii "s"[, "s"...]`, `.asciz`/`.string "s"` (NUL-terminated).
  - `.space`/`.skip`/`.zero n[, fill]`, `.fill count[, size[, value]]`.
  - `.align n` (backend-defined: power of two for RV32I, as GNU RISC-V does), `.p2align n[, fill]`,
    `.balign n[, fill]`. In text, padding is the backend's NOP when the gap is a multiple of the
    instruction size, else zeros.
  - `.equ name, expr` and `.set name, expr` (redefinable with `.set`, not with `.equ`), and
    `name = expr`.
  - `.uleb128` and `.sleb128 expr[, expr...]`: LEB128 encoded values (their size depends on the
    value, so their operands must be known in pass 1; otherwise an error).
  - `.global`/`.globl name`, `.local name`: symbol visibility across files (see Input above).
    `.weak name`: global; an undefined weak symbol resolves to 0 instead of an error.
  - `.comm name, size[, align]` and `.lcomm name, size[, align]` (from `-fcommon` and older GCC):
    reserve `size` bytes in bss at `align` (default: the largest power of two not above size,
    capped at 16) and define `name` there (`.comm` also makes it global).
  - Accepted and ignored for compiler compatibility: `.file`, `.loc` (from `-g`), `.ident`,
    `.type`, `.size`, `.hidden`, `.attribute`, `.option` (RV32I: `rvc` is an error "C extension
    not supported"; `norvc`, `push`, `pop`, `pic`, `nopic`, `relax`, `norelax` ignored), `.cfi_*`,
    `.addrsig*`.
  - Unknown directive: error.
- Passes: pass 1 parses every statement, assigns each a size and lays out sections; pass 2 encodes
  with all symbols known. The framework also supports relaxation for variable-length backends
  (x86, 68000, ARM literal pools): pass 1 repeats until sizes stop changing, sizes may only grow
  (except an auto-alignment pad, which follows the statement's current offset, and data
  statements such as a classic `org`, 10.7.1), at most 16 plus the number of instruction
  statements iterations, then an error. Forward labels come from the previous pass's layout, so
  a chain of nested forward branches settles one branch per pass; one pass per instruction
  statement covers such a chain, and the cap still ends a run that never settles. RV32I fixes every size in the first pass and needs exactly two
  passes. A statement's size in pass 2 must equal pass 1's; a backend that emits a different
  size is a bug (assert).
- Diagnostics: `AsmDiagnostic(Severity, fileName, line, column, message)`, errors and warnings,
  sorted by file (input order), then line, then column. Assembly continues after an error to report more (at most 100 errors). Messages name the
  thing: "unknown instruction 'ad'", "branch target out of range (5000 bytes, limit +-4096)",
  "immediate 4096 does not fit in 12 signed bits", "undefined symbol 'loop2'", "symbol 'x'
  already defined on line 4", "unknown register 'x32'".
- `AsmImage`: a list of `AsmSegment(int address, byte[] bytes)` sorted by address, one per
  contiguous initialised range (so text in ROM at 0x1000 and data at 0x8000_0000 never produce a
  2 GiB array; bss is never in a segment), `entry` (`_start` if defined, else textBase),
  `bssStart`, `bssEnd`, a symbol table (`Map<String, Long>` of zero-extended values: global and
  file-local labels and equates; `.L*` and numeric local labels excluded; with several files a
  file-local name is keyed `fileName:name`), and a line map (`lineAt(address)` returning the
  `AsmLine(fileName, line)` of the instruction at that address, or null).
- The internal statement records keep relocation intent (symbol + kind) even though v1 resolves
  everything itself, so a relocatable object format and linker can be added for multi-file C.

### 5.2 RV32I backend (`computer.asm.rv32`)

- Registers: `x0`..`x31` and ABI names (`fp` = `s0`). CSR operands: a number 0..4095 or a name
  from 4.4 (including the user counter names, `mhpmcounterN`, `mhpmcounterNh`, `hpmcounterN`,
  `hpmcounterNh`, `mhpmeventN` and `mconfigptr`).
- Byte order little-endian; instruction size 4; NOP 0x0000_0013; `.align` is power of two.
- Operand forms: `rd, rs1, rs2`; `rd, rs1, imm`; loads `rd, imm(rs1)`, `rd, (rs1)`,
  `rd, %lo(sym)(rs1)`; stores `rs2, imm(rs1)`; branches `rs1, rs2, target`; `jal rd, target` and
  `jal target`; `jalr rd, imm(rs1)`, `jalr rd, rs1, imm`, `jalr rs1`; CSR `rd, csr, rs1` and
  `rd, csr, uimm5`.
- Base instructions and encodings (opcode bits 6..0):
  - LUI 0110111, AUIPC 0010111 (U-type, imm 31..12).
  - JAL 1101111 (J-type: imm[20|10:1|11|19:12] in bits 31..12), range +-1 MiB, even.
  - JALR 1100111 funct3 000 (I-type).
  - BRANCH 1100011 (B-type: imm[12|10:5] bits 31..25, imm[4:1|11] bits 11..7), range +-4 KiB,
    funct3: BEQ 000, BNE 001, BLT 100, BGE 101, BLTU 110, BGEU 111.
  - LOAD 0000011: LB 000, LH 001, LW 010, LBU 100, LHU 101.
  - STORE 0100011 (S-type): SB 000, SH 001, SW 010.
  - OP-IMM 0010011: ADDI 000, SLTI 010, SLTIU 011, XORI 100, ORI 110, ANDI 111, SLLI 001
    (funct7 0000000), SRLI 101 (funct7 0000000), SRAI 101 (funct7 0100000); shamt 0..31.
  - OP 0110011: ADD 000/0000000, SUB 000/0100000, SLL 001, SLT 010, SLTU 011, XOR 100,
    SRL 101/0000000, SRA 101/0100000, OR 110, AND 111.
  - MISC-MEM 0001111: FENCE funct3 000 (operands `pred, succ` from letters `iorw`; no operands
    means `iorw, iorw`), FENCE.TSO (0x8330_000F), PAUSE (0x0100_000F), FENCE.I funct3 001.
  - SYSTEM 1110011: ECALL 0x0000_0073, EBREAK 0x0010_0073, MRET 0x3020_0073, WFI 0x1050_0073,
    CSRRW 001, CSRRS 010, CSRRC 011, CSRRWI 101, CSRRSI 110, CSRRCI 111.
  - M (OP, funct7 0000001): MUL 000, MULH 001, MULHSU 010, MULHU 011, DIV 100, DIVU 101, REM 110,
    REMU 111.
- Relocation operators:
  - `%hi(x)` = ((x + 0x800) >>> 12) & 0xFFFFF.
  - `%lo(x)` = ((x & 0xFFF) ^ 0x800) - 0x800 (sign-extended low 12 bits), so that
    (%hi(x) << 12) + %lo(x) == x mod 2^32.
  - `%pcrel_hi(sym)` = %hi(sym - address of this AUIPC).
  - `%pcrel_lo(label)`: `label` marks an AUIPC whose operand is `%pcrel_hi(sym)`; the value is
    %lo(sym - label). Using it on a label that is not such an AUIPC is an error.
- Pseudo-instructions (expansion; sizes fixed in pass 1):
  - `nop` = addi x0, x0, 0.
  - `li rd, imm`: imm must fit 32 bits signed or unsigned (else an error); it is then normalised
    to signed 32 bits (as GNU as does on RV32) before these checks. If known in pass 1 and it
    fits 12 signed bits: addi rd, x0, imm. If known and %lo(imm) == 0: lui rd, %hi(imm). If known
    otherwise, or unknown in pass 1 (forward symbol): lui rd, %hi(imm); addi rd, rd, %lo(imm)
    (8 bytes).
  - `la rd, sym` and `lla rd, sym`: auipc rd, %pcrel_hi(sym); addi rd, rd, %pcrel_lo(.L).
  - `mv rd, rs` = addi rd, rs, 0; `not rd, rs` = xori rd, rs, -1; `neg rd, rs` = sub rd, x0, rs;
    `seqz rd, rs` = sltiu rd, rs, 1; `snez rd, rs` = sltu rd, x0, rs; `sltz rd, rs` = slt rd, rs, x0;
    `sgtz rd, rs` = slt rd, x0, rs; `zext.b rd, rs` = andi rd, rs, 255.
  - `sgt rd, rs, rt` = slt rd, rt, rs; `sgtu rd, rs, rt` = sltu rd, rt, rs.
  - `sext.b rd, rs` = slli rd, rs, 24; srai rd, rd, 24. `sext.h rd, rs` = slli rd, rs, 16;
    srai rd, rd, 16. `zext.h rd, rs` = slli rd, rs, 16; srli rd, rd, 16. (8 bytes each; these are
    the base-ISA expansions GNU as uses without Zbb.)
  - `unimp` = 0xC000_1073 (csrrw x0, cycle, x0), which GCC emits for `__builtin_trap`; it is an
    illegal instruction on this CPU because `cycle` is read-only (4.5).
  - `beqz rs, t` = beq rs, x0, t; `bnez` = bne rs, x0; `blez rs, t` = bge x0, rs, t;
    `bgez rs, t` = bge rs, x0, t; `bltz rs, t` = blt rs, x0, t; `bgtz rs, t` = blt x0, rs, t.
  - `bgt rs, rt, t` = blt rt, rs, t; `ble rs, rt, t` = bge rt, rs, t; `bgtu` = bltu rt, rs;
    `bleu` = bgeu rt, rs.
  - `j t` = jal x0, t; `jal t` = jal ra, t; `jr rs` = jalr x0, 0(rs); `jalr rs` = jalr ra, 0(rs);
    `ret` = jalr x0, 0(ra).
  - `call sym` = auipc ra, %pcrel_hi(sym); jalr ra, %pcrel_lo(.L)(ra). `call rd, sym` = the same
    with rd in place of ra in both words. `tail sym` = auipc t1, ...; jalr x0, ...(t1). Always
    8 bytes (no relaxation).
  - `csrr rd, csr` = csrrs rd, csr, x0; `csrw csr, rs` = csrrw x0, csr, rs; `csrs` = csrrs x0;
    `csrc` = csrrc x0; `csrwi`, `csrsi`, `csrci` = the immediate forms with rd = x0.
  - `rdcycle`, `rdcycleh`, `rdtime`, `rdtimeh`, `rdinstret`, `rdinstreth rd` = csrrs rd, csr, x0.
  - `fence` with no operands as above.
  - Global load/store forms: `lw rd, sym` = auipc rd, %pcrel_hi(sym); lw rd, %pcrel_lo(.L)(rd)
    (same for lb, lbu, lh, lhu); `sw rs, sym, rt` = auipc rt, %pcrel_hi(sym);
    sw rs, %pcrel_lo(.L)(rt) (same for sb, sh).
- Branch and jump targets: a symbol or an expression with an address (`label`, `. + 8`) is a
  target address; a plain number (including a `.equ` constant) is a PC offset, so `jal x1, 8`
  jumps 8 bytes ahead. This is how LLVM's assembler reads a plain number. GNU as is believed to
  read it as an absolute address instead; that was not checked (no toolchain installed). Code
  meant for both should branch to labels.
- Range errors: branch and jump targets must be in range and even (multiple of 4 is checked by
  the CPU, not the assembler, but a target that is not a multiple of 4 gets a warning); no
  automatic long-branch rewriting.
- Encoding checks to use in tests (hand-checked against the unprivileged spec):
  - `addi x1, x0, 5` = 0x0050_0093; `add x3, x1, x2` = 0x0020_81B3; `sub x3, x1, x2` = 0x4020_81B3.
  - `lui x5, 0x12345` = 0x1234_52B7; `jal x1, 8` (offset +8) = 0x0080_00EF;
    `beq x1, x2, 8` (offset +8) = 0x0020_8463.
  - `sw x2, 4(x1)` = 0x0020_A223; `lw x3, -4(x1)` = 0xFFC0_A183.
  - `csrrw x0, mtvec, x5` = 0x3052_9073; `mul x3, x1, x2` = 0x0220_81B3.
  - `li a0, 0x12345FFF` = lui a0, 0x12346 (0x1234_6537) then addi a0, a0, -1 (0xFFF5_0513).
  - `li a0, 0x800` = lui a0, 1; addi a0, a0, -2048. `li a0, -1` = addi a0, x0, -1 (one word).
    `li a0, 0x7FFFFFFF` = lui a0, 0x80000; addi a0, a0, -1. `li a0, 0x12345000` = lui only.
  - `li a0, 0xFFFFF800` = addi a0, x0, -2048 (0x8000_0513, one word, after normalising to -2048).
    `li a0, 0x80000000` = lui a0, 0x80000 only (0x8000_0537). `li a0, 0x1_0000_0000` is an error.
  - `unimp` = 0xC000_1073.
- Public entry: `Rv32Assembler.assemble(String source, AsmOptions options)`.

### 5.3 Other backends (later)

- One package per CPU (`asm.x86`, `asm.m68k`, `asm.arm2`, `asm.mips`) implementing `IsaBackend`
  with the syntaxes in section 2. The 8086 and 8088 share one backend; the 68000 and 68040 share
  one (10.7).

## 6. C and C++ (plan, not built now)

- Phase C0 (free once the assembler ships): accept assembly produced by an external GCC or Clang
  (`-march=rv32im_zicsr_zifencei -mabi=ilp32 -O2 -S -ffreestanding -fno-pic -mno-relax
  -mcmodel=medlow`; from GCC 12 on, plain `-march=rv32im` rejects CSR instructions in inline
  assembly). Several `-S` outputs are assembled together with `assemble(List<AsmSource>)` (5.1).
  The directive and section handling in 5.1 exists for this. Ship as assembly sources usable by
  players:
  - a `crt0`: set sp, copy `__data_end - __data_start` bytes from `__data_load_start` to
    `__data_start` when they differ, zero bss, call the functions in `__init_array_start` ..
    `__init_array_end`, call `main`, power off with its return code;
  - a small runtime: memcpy, memmove, memset, memcmp, strlen, integer-to-text, COM and VGA
    console output;
  - the libgcc helpers GCC calls on RV32IM: `__divdi3`, `__udivdi3`, `__moddi3`, `__umoddi3`
    (64-bit divide and remainder), and later the soft-float set (`__addsf3`, `__subsf3`,
    `__mulsf3`, `__divsf3`, compares, conversions; the same with `df` for double).
- Phase C1: an in-mod C compiler in `computer.cc`, structured like the PLC's `lang` package
  (lexer, preprocessor, parser, sema, types) but written fresh (the PLC compiler targets its own
  VM and Arduino builtins and stays frozen). New middle end: a typed three-address IR with
  virtual registers, simple optimisations (constant folding, dead code, copy propagation), then
  per-CPU backends that select instructions, allocate registers (linear scan), and emit
  assembly text for the shared assembler. RV32I first with the ILP32 ABI (a0..a7 arguments, a0/a1
  return, ra, s0..s11 callee-saved, sp 16-byte aligned). Later ABIs: R3000 o32, ARM2 APCS-26,
  68000 stack-based (d0 return), 8086 small model cdecl (far pointers later).
- Phase C1 also needs: a relocatable object format and a linker (sections plus relocations:
  RV32I HI20, LO12_I, LO12_S, PCREL_HI20, PCREL_LO12_I, PCREL_LO12_S, BRANCH, JAL, CALL, 32), a freestanding
  libc subset (string.h, ctype.h, stdlib.h with malloc/free, stdio.h printf to COM1/VGA), soft
  float (`__addsf3` and friends; no F extension on this CPU), and device headers
  (`<flippies/uart.h>`, `<flippies/vga.h>`, `<flippies/ps2.h>`, `<flippies/hdd.h>`).
- Phase C2: a C++ subset on the same front end: classes, single inheritance, virtual functions
  (vtables), constructors and destructors (RAII), references, function and operator overloading
  (Itanium-style name mangling), namespaces, new/delete, constexpr basics, simple templates by
  substitution. No exceptions, no RTTI, no standard containers at first. Its runtime also needs:
  static constructors through `.init_array` (already mapped in 5.1), COMDAT deduplication of
  template and inline functions in the linker, `__cxa_pure_virtual`, `_Znwj` and `_ZdlPv`
  (operator new and delete for ILP32), `__cxa_atexit` and `__dso_handle`, and either
  `__cxa_guard_acquire`/`release`/`abort` or `-fno-threadsafe-statics`.
- Disks carry the compiled programs: the game's editor will compile, and write the image plus a
  boot sector to the HDD. Before C0 ships, a program header is fixed so the boot sector, the
  runtime and the editor agree. Planned layout (little-endian u32 fields, at the start of the
  program's first sector): 0 magic "FLPX", 4 header version (1), 8 load address, 12 entry point,
  16 file size in bytes (header included), 20 bss size in bytes, 24 CPU model ordinal, 28
  reserved 0. The boot sector loads `file size` bytes to the load address, zeroes bss, and jumps
  to the entry.

## 7. Code layout and contracts for what is built now

All paths below are under `src/main/java/com/nectariich/create_flippies/content/` (main) or
`src/test/java/com/nectariich/create_flippies/content/` (tests), shortened to `computer/...` and
`test: computer/...`. This section is final: the foundation step writes 7.2 verbatim, then the
three agents work in parallel against it. Nobody changes a 7.2 signature; Javadoc may be extended.
Public APIs in 7.3 to 7.5 are fixed for their owner; their private internals are free.

### 7.1 Packages and owners

- Foundation step (first, by the orchestrator, before any agent starts), exactly the files of 7.2:
  - `computer/bus/Bus.java`, `BusDevice.java`, `BusFault.java`, `IrqSink.java`, `CycleClock.java`
    (added with save and restore, 1.5: `StateFormatException.java` and the format helpers
    `StateIo.java`, used by every CPU and device)
  - `computer/cpu/Cpu.java`, `CpuModel.java`, `CpuHost.java`, `CpuFactory.java`
  - `computer/machine/Rv32Map.java`
  - `test: computer/bus/BusContractTest.java` (abstract; both bus implementations must pass it)
  - It ends with the 7.2 files compiling with plain `javac` (main and the abstract test).
- Agent A, RV32I CPU:
  - main: `computer/cpu/rv32/Rv32Cpu.java`, `computer/cpu/rv32/Rv32Csr.java` (CSR numbers and
    names), plus any private helpers under `computer/cpu/rv32/`.
  - tests: `test: computer/cpu/rv32/**`, including `TestBus.java` (a small `Bus`: RAM, ROM, VRAM
    and one test device; no `SystemBus`) and `TestBusContractTest.java` (extends
    `BusContractTest` for `TestBus`).
- Agent B, assembler:
  - main framework `computer/asm/`: `Assembler`, `AsmLexer`, `LexerSyntax`, `AsmToken`,
    `ExprParser`, `AsmContext`, `IsaBackend`, `AsmOptions`, `AsmSource`, `AsmResult`, `AsmImage`,
    `AsmSegment`, `AsmLine`, `AsmDiagnostic`, `Severity`, `SymbolTable`, plus any internals.
  - main RV32I backend `computer/asm/rv32/`: `Rv32Assembler`, `Rv32Backend`, `Rv32Registers`,
    `Rv32Csrs` (its own CSR name table; it does not import `computer/cpu/**`).
  - tests: `test: computer/asm/**`.
- Agent C, bus, devices and machine:
  - main bus: `computer/bus/SystemBus.java`, `MemoryRegion.java`, `DmaWindow.java`.
  - main devices `computer/device/`: `SysCon`, `InterruptController`, `IntervalTimer`, `Rtc`,
    `Uart16550`, `Ps2Controller`, `Ps2Keyboard`, `Ps2Mouse`, `Ps2Key`, `VgaDisplay`, `VgaFrame`,
    `HddController`, `DiskImage`, `SparseDiskImage`, `Clint`.
  - main machine `computer/machine/`: `Machine`, `MachineConfig`, `MachineState`, `Platform`,
    `Board`, `Rv32Platform` (everything in the package except `Rv32Map` and the integration
    files below).
  - tests: `test: computer/bus/**` (except `BusContractTest`), `test: computer/device/**`,
    `test: computer/machine/**`, including `SystemBusContractTest.java` (extends
    `BusContractTest`). Machine tests use a fake `Cpu` built through a `CpuFactory`.
- Integration step (after A, B and C):
  - main: `computer/machine/StandardCpus.java` (the `CpuFactory`: `Rv32Cpu` with
    `mExtension = true` for `CpuModel.RV32I`; `UnsupportedOperationException("<displayName> is
    not emulated yet")` for the others), `computer/machine/BootRom.java`.
  - tests: `test: computer/e2e/**`.
- No file has two owners. An agent that needs something from another agent's area writes a
  private stand-in in its own test tree.
- The 68000 and 68040 have their own foundation step, owners and integration step: 10.9 and
  10.10.

### 7.2 Contract sources (write exactly; Javadoc may be extended, signatures may not)

`computer/bus/Bus.java`:

```java
package com.nectariich.create_flippies.content.computer.bus;

import java.nio.ByteOrder;

/**
 * A machine's physical address space (or, for the 8086 and 8088, its port space) as a CPU sees
 * it. Addresses are 32-bit unsigned values carried in an int; a CPU with a narrower address bus
 * masks before calling. Reads are zero-extended; writes use the low 8, 16 or 32 bits of the value.
 *
 * Memory regions (RAM, ROM, VRAM) compose 16- and 32-bit accesses in {@link #order()} and allow
 * unaligned accesses. Device registers are value-addressed: a full-width access gives the
 * register's value whatever the CPU's byte order, and device accesses must be naturally aligned.
 * An access that nothing answers, a write to ROM, a misaligned device access, or a multi-byte
 * access that crosses a region edge throws {@link BusFault}. A CPU issues one 32-bit call for a
 * 32-bit device access and never splits it (only 8086/8088 port I/O splits, low half first).
 *
 * RAM, ROM and VRAM are also exposed as raw arrays so a CPU can serve its own fetches, loads and
 * stores from them without region dispatch; the read and write methods give the same results for
 * those ranges. An absent array is empty (length 0) with size 0, never null (the port bus has
 * none). Test the ROM range first: under the reset-time ROM overlay the ROM range overlaps RAM
 * and the ROM wins. A store into VRAM through the array must also set bit
 * {@code (index >>> 12) & 63} of {@code vramDirtyPages()[index >>> 18]}, where
 * {@code index = address - vramBase()}; the write methods do this themselves.
 *
 * The arrays, bases and sizes change only between {@code Cpu.run} calls; a CPU reloads them at
 * the start of every run.
 */
public interface Bus {

    int read8(int address);

    int read16(int address);

    int read32(int address);

    void write8(int address, int value);

    void write16(int address, int value);

    void write32(int address, int value);

    ByteOrder order();

    /** Main RAM's backing array; index = address - {@link #ramBase()}; length == {@link #ramSize()}. */
    byte[] ramArray();

    int ramBase();

    int ramSize();

    /**
     * The boot ROM's backing array (read-only to the CPU); index = address - {@link #romBase()};
     * length == {@link #romSize()}, zero-padded to the platform's whole ROM window. While the ROM
     * overlay is on, {@link #romBase()} is 0.
     */
    byte[] romArray();

    int romBase();

    int romSize();

    /** VRAM's backing array; index = address - {@link #vramBase()}; length == {@link #vramSize()}. */
    byte[] vramArray();

    int vramBase();

    int vramSize();

    /** One bit per 4 KiB VRAM page, set on every VRAM write; owned and cleared by the VGA device. Empty when there is no VRAM. */
    long[] vramDirtyPages();
}
```

`computer/bus/BusDevice.java`:

```java
package com.nectariich.create_flippies.content.computer.bus;

import java.io.DataInput;
import java.io.DataOutput;
import java.io.IOException;

/**
 * A device's register block on a bus. Offsets are bytes from the window's base; widths are 1, 2
 * or 4, and the bus only passes naturally aligned accesses. The window a platform maps may be
 * larger than {@link #size()} (offsets the device does not decode read 0 and ignore writes) or
 * smaller (the 8086 VGA port window omits the direct palette).
 *
 * Devices are simulated lazily: they bring themselves up to {@code clock.now()} at the start of
 * every read and write, and the machine catches them up at the end of every CPU segment. When a
 * device schedules an event during a segment it calls {@link CycleClock#requestStopAt}. Requests
 * that change the machine (reset, power off, memory map) are recorded and stop the CPU through
 * {@code requestStopAt(clock.now())}; the machine performs them between runs.
 */
public interface BusDevice {

    /** Bytes of registers the device decodes; a multiple of 4. */
    int size();

    /** Reads {@code width} bytes at {@code offset}, zero-extended. */
    int read(int offset, int width);

    void write(int offset, int width, int value);

    /** Back to the power-on state; interrupt outputs low. */
    void reset();

    /** Catches the device's own activity up to {@code cycle}. Never called with a cycle earlier than a previous one. */
    default void advanceTo(long cycle) {
    }

    /**
     * The earliest cycle at which the device, by itself, would change one of its interrupt lines
     * (given its current enables) or change memory the CPU reads directly (DMA), or
     * Long.MAX_VALUE. Earlier is allowed (it only ends a segment early); later is a bug.
     */
    default long nextEventCycle() {
        return Long.MAX_VALUE;
    }

    /**
     * Writes the device's complete state (spec 1.5): its format version first, then every field,
     * cycle stamps as they are (absolute, so they stay valid when the clock is restored with them).
     * Pure: no catch-up, no side effect. Wiring (clock, sinks, bus, disk) is not state.
     */
    void saveState(DataOutput out) throws IOException;

    /**
     * Replaces the device's state with what {@link #saveState} wrote on a device built the same
     * way. Interrupt sinks are not driven: line levels are part of the restored state on both ends.
     * StateFormatException for an unknown version or values the device cannot hold. After any
     * exception the device's state is unspecified; {@code Machine.loadState} restores the machine.
     */
    void loadState(DataInput in) throws IOException;
}
```

`computer/bus/BusFault.java`:

```java
package com.nectariich.create_flippies.content.computer.bus;

/** An access the bus cannot complete. Thrown on the slow path only; carries no stack trace. */
public final class BusFault extends RuntimeException {

    public final int address;
    public final boolean write;

    public BusFault(int address, boolean write, String reason) {
        super(reason, null, false, false);
        this.address = address;
        this.write = write;
    }
}
```

`computer/bus/IrqSink.java`:

```java
package com.nectariich.create_flippies.content.computer.bus;

/** One level interrupt line, as a device drives it. May be called while the CPU is inside run. */
@FunctionalInterface
public interface IrqSink {

    IrqSink NONE = level -> { };

    void set(boolean level);
}
```

`computer/bus/CycleClock.java`:

```java
package com.nectariich.create_flippies.content.computer.bus;

/**
 * A machine's one clock: CPU cycles since power-on. It only moves forward. The CPU keeps its own
 * running count in a local for speed; before every slow-path bus access it publishes the count
 * at the start of the current instruction, and before {@code run} returns it publishes its final
 * count, so devices always see the right "now".
 *
 * The horizon is the cycle the CPU must stop at. {@code Cpu.run} sets it from its
 * {@code untilCycle}; a device lowers it with {@link #requestStopAt} when it schedules an event
 * or needs the machine to act, and the CPU re-reads it after every slow-path access.
 */
public final class CycleClock {

    private long now;
    private long horizon = Long.MAX_VALUE;

    public long now() {
        return now;
    }

    /** Moves the clock to {@code cycle}; earlier values are ignored. */
    public void advanceTo(long cycle) {
        if (cycle > now)
            now = cycle;
    }

    public long horizon() {
        return horizon;
    }

    /** Sets the horizon outright; called by {@code Cpu.run} at entry. */
    public void setHorizon(long cycle) {
        horizon = cycle;
    }

    /** Lowers the horizon to {@code cycle} (never below now); a later cycle than the current horizon is ignored. */
    public void requestStopAt(long cycle) {
        long c = Math.max(cycle, now);
        if (c < horizon)
            horizon = c;
    }

    /** Back to cycle zero, at power-on. */
    public void reset() {
        now = 0;
        horizon = Long.MAX_VALUE;
    }

    /** floor(cycles * 1_000_000 / clockHz) without overflow, for cycles >= 0 and clockHz >= 1 MHz. */
    public static long cyclesToMicros(long cycles, int clockHz) {
        return cycles / clockHz * 1_000_000L + cycles % clockHz * 1_000_000L / clockHz;
    }

    /**
     * The smallest cycle count c with {@code cyclesToMicros(c, clockHz) >= micros}, for
     * micros >= 0; Long.MAX_VALUE if micros is negative or the result does not fit a long.
     */
    public static long microsToCycles(long micros, int clockHz) {
        if (micros < 0)
            return Long.MAX_VALUE;
        long whole = micros / 1_000_000L;
        long part = micros % 1_000_000L;
        if (whole > (Long.MAX_VALUE - clockHz) / clockHz)
            return Long.MAX_VALUE;
        return whole * clockHz + (part * clockHz + 999_999L) / 1_000_000L;
    }
}
```

`computer/cpu/Cpu.java`:

```java
package com.nectariich.create_flippies.content.computer.cpu;

import java.io.DataInput;
import java.io.DataOutput;
import java.io.IOException;

/**
 * One emulated CPU running real machine code from the buses in its {@link CpuHost}.
 *
 * Time: at the start of every {@link #run} the CPU loads its cycle count from
 * {@code clock.now()} (the machine may have moved the clock while it waited) and reloads the bus
 * arrays. Counter registers the guest can read (RV32I mcycle, time, instret) come from the CPU's
 * own counts, not from the clock, which is only published at slow-path accesses and on return.
 *
 * Pins: {@link #setIrq} may be called between runs and also during a run (a device raising its
 * line inside a slow-path access), so pin state lives in fields, never in run locals. Pins are
 * levels; inputs that are edge-triggered on the real chip (8086 NMI, 68000 level 7) are
 * edge-detected inside the CPU.
 *
 * A CPU never changes machine state itself: anything that resets devices or changes the memory
 * map goes through a recorded request (SysCon, {@link CpuHost#resetDevices}) that the machine
 * performs after run returns.
 */
public interface Cpu {

    /** Why {@link #run} came back. */
    enum Exit {
        /** Reached the horizon (untilCycle, or an earlier stop a device requested). */
        REACHED,
        /** The host-time deadline passed first: emulated time is running behind. */
        DEADLINE,
        /** The CPU is idle in its wait instruction until an interrupt pin wakes it. */
        WAITING,
        /** The CPU has stopped for good until reset; see {@link #haltReason()}. */
        HALTED
    }

    CpuModel model();

    /** Cold reset: registers to their reset values, PC to the reset vector. Memory and clock are not touched. */
    void reset();

    /**
     * Calls {@code clock.setHorizon(untilCycle)}, then executes until its cycle count reaches
     * {@code clock.horizon()} (re-read after every slow-path access), the CPU waits or halts, or
     * {@code System.nanoTime()} passes {@code hostDeadlineNanos} (checked at least every 4096
     * instructions). Never runs past the horizon by more than one instruction; returns REACHED at
     * once if the horizon is not after the clock. Called on a waiting CPU, returns WAITING at once
     * unless its wake rule now holds; on a halted CPU, returns HALTED. Publishes its final cycle
     * count to the clock before returning.
     */
    Exit run(long untilCycle, long hostDeadlineNanos);

    /** Drives interrupt input {@code pin} (numbering per CPU model, see the spec); a level. */
    void setIrq(int pin, boolean level);

    boolean halted();

    /** Why the CPU halted, or null. */
    String haltReason();

    /** Address of the next instruction to execute. */
    int pc();

    long instructionsRetired();

    /**
     * Writes the CPU's complete state (spec 1.5): its format version first, then registers, pc,
     * control state, pin levels, counters and any internal wait or halt state. Called between
     * runs only. Pure: no side effect. The memory map is not state (it is reloaded every run).
     */
    void saveState(DataOutput out) throws IOException;

    /**
     * Replaces the CPU's state with what {@link #saveState} wrote on a CPU of the same model and
     * options, between runs. StateFormatException for an unknown version or a CPU built
     * differently (for example RV32I without M). After any exception the state is unspecified;
     * {@code Machine.loadState} restores the machine.
     */
    void loadState(DataInput in) throws IOException;
}
```

`computer/cpu/CpuModel.java`:

```java
package com.nectariich.create_flippies.content.computer.cpu;

import java.nio.ByteOrder;

/** The CPUs a PC can be built with, with the real chips' bus facts. */
public enum CpuModel {
    CPU_8086("8086", 5_000_000, 16, 20, ByteOrder.LITTLE_ENDIAN, false),
    CPU_8088("8088", 4_772_727, 8, 20, ByteOrder.LITTLE_ENDIAN, false),
    CPU_68000("68000", 8_000_000, 16, 24, ByteOrder.BIG_ENDIAN, false),
    CPU_68040("68040", 25_000_000, 32, 32, ByteOrder.BIG_ENDIAN, false),
    ARM2("ARM2", 8_000_000, 32, 26, ByteOrder.LITTLE_ENDIAN, false),
    MIPS_R3000("MIPS R3000", 25_000_000, 32, 32, ByteOrder.LITTLE_ENDIAN, false),
    RV32I("RV32I", 10_000_000, 32, 32, ByteOrder.LITTLE_ENDIAN, true);

    private final String displayName;
    private final int defaultClockHz;
    private final int dataBusBits;
    private final int addressBits;
    private final ByteOrder byteOrder;
    private final boolean emulated;

    CpuModel(String displayName, int defaultClockHz, int dataBusBits, int addressBits,
             ByteOrder byteOrder, boolean emulated) {
        this.displayName = displayName;
        this.defaultClockHz = defaultClockHz;
        this.dataBusBits = dataBusBits;
        this.addressBits = addressBits;
        this.byteOrder = byteOrder;
        this.emulated = emulated;
    }

    public String displayName() { return displayName; }

    public int defaultClockHz() { return defaultClockHz; }

    public int dataBusBits() { return dataBusBits; }

    public int addressBits() { return addressBits; }

    public ByteOrder byteOrder() { return byteOrder; }

    /** Whether an emulator exists yet. */
    public boolean emulated() { return emulated; }
}
```

`computer/cpu/CpuHost.java`:

```java
package com.nectariich.create_flippies.content.computer.cpu;

import com.nectariich.create_flippies.content.computer.bus.Bus;
import com.nectariich.create_flippies.content.computer.bus.CycleClock;

import java.util.Objects;
import java.util.function.IntSupplier;

/**
 * Everything a CPU is connected to.
 *
 * @param memory        the memory bus
 * @param io            the port bus (8086/8088 IN/OUT); null for CPUs without port I/O
 * @param interruptAck  supplies the vector number in an interrupt acknowledge cycle (8086/8088);
 *                      null for CPUs that do not ask
 * @param resetDevices  records a request to reset every device (68000 RESET instruction); the
 *                      machine performs it after run returns; null means no-op
 * @param resetPc       where execution starts after reset, for CPUs whose reset PC is not fixed
 *                      by the chip (RV32I); ignored by CPUs that read a reset vector
 * @param strictFpu     68040 only: the FPU traps what the real chip leaves to its support
 *                      package (the machine config's strict FPU option, spec 10.4)
 */
public record CpuHost(CpuModel model, int clockHz, Bus memory, Bus io, CycleClock clock,
                      IntSupplier interruptAck, Runnable resetDevices, int resetPc, boolean strictFpu) {

    public CpuHost {
        Objects.requireNonNull(model, "model");
        Objects.requireNonNull(memory, "memory");
        Objects.requireNonNull(clock, "clock");
        if (clockHz <= 0)
            throw new IllegalArgumentException("clockHz must be positive: " + clockHz);
        if (strictFpu && model != CpuModel.CPU_68040)
            throw new IllegalArgumentException("strict FPU needs a 68040, not a " + model.displayName());
    }

    /** A host whose CPU has no strict FPU (every model but a strict 68040). */
    public CpuHost(CpuModel model, int clockHz, Bus memory, Bus io, CycleClock clock,
                   IntSupplier interruptAck, Runnable resetDevices, int resetPc) {
        this(model, clockHz, memory, io, clock, interruptAck, resetDevices, resetPc, false);
    }

    /** A host with only a memory bus, as RV32I, ARM2, R3000 and tests use. */
    public static CpuHost of(CpuModel model, int clockHz, Bus memory, CycleClock clock, int resetPc) {
        return new CpuHost(model, clockHz, memory, null, clock, null, null, resetPc);
    }
}
```

`computer/cpu/CpuFactory.java`:

```java
package com.nectariich.create_flippies.content.computer.cpu;

/** Builds the CPU for a machine; the machine wires its interrupt pins afterwards. */
@FunctionalInterface
public interface CpuFactory {

    Cpu create(CpuHost host);
}
```

`computer/machine/Rv32Map.java`: a `public final class` with a private constructor and only
`public static final int` constants (each with a one-line comment naming its spec section), the
addresses, sizes, pins, lines, limits and costs of sections 1.2, 3.2, 4.1, 4.3, 4.6 and 7.5,
exactly:
`ROM_BASE = 0x0000_1000`, `ROM_SIZE = 0xF000`, `RESET_PC = ROM_BASE`,
`SYSCON_BASE = 0x0010_0000`, `CLINT_BASE = 0x0200_0000`, `CLINT_SIZE = 0x1_0000`,
`PIC_BASE = 0x0C00_0000`, `COM1_BASE = 0x1000_0000`, `COM2_BASE = 0x1000_1000`,
`PS2_BASE = 0x1000_2000`, `HDD_BASE = 0x1000_3000`, `VGA_BASE = 0x1000_4000`,
`TIMER_BASE = 0x1000_5000`, `RTC_BASE = 0x1000_6000`, `DEVICE_WINDOW = 0x1000`,
`VRAM_BASE = 0x2000_0000`, `VRAM_SIZE = 0x8_0000`, `RAM_BASE = 0x8000_0000`,
`RAM_MIN = 64 * 1024`, `RAM_MAX = 64 * 1024 * 1024`, `CLOCK_MIN_HZ = 1_000_000`,
`CLOCK_MAX_HZ = 100_000_000`, `IRQ_PIN_MSIP = 3`, `IRQ_PIN_MTIP = 7`, `IRQ_PIN_MEIP = 11`,
`LINE_COM1 = 1`, `LINE_COM2 = 2`, `LINE_KEYBOARD = 3`, `LINE_MOUSE = 4`, `LINE_HDD = 5`,
`LINE_VGA = 6`, `LINE_TIMER = 7`, `MMIO_EXTRA_CYCLES = 2`.

`test: computer/bus/BusContractTest.java`: an abstract JUnit 5 class the foundation step writes
in full. It declares

```java
protected record Layout(ByteOrder order, int ramBase, int ramSize, int romBase, byte[] rom,
                        int vramBase, int vramSize, int deviceBase, int deviceWindow) { }

/** A bus with RAM, ROM (rom is already zero-padded), VRAM and {@code device} mapped per layout. */
protected abstract Bus createBus(Layout layout, BusDevice device);
```

and tests, each run with a little-endian and a big-endian layout and a recording device:
fast-path arrays, bases and sizes match the layout and agree with `read8/16/32`; `romArray()`
length equals `romSize()`; 16- and 32-bit RAM values compose in `order()`; unaligned RAM access
works; a RAM word crossing the RAM end faults; ROM write faults with `write == true`; unmapped
read faults with the right `address`; device accesses reach the device with `offset =
address - deviceBase` and the right width, a 32-bit access as one call, offsets past `size()` but
inside the window included; a misaligned device access faults; a `write8` into VRAM sets the
matching `vramDirtyPages()` bit.

### 7.3 Agent A: `Rv32Cpu`

```java
public final class Rv32Cpu implements Cpu {
    public Rv32Cpu(CpuHost host, boolean mExtension);
    // Cpu methods, plus for tests, debugging and the future in-game debugger:
    public int reg(int index);
    public void setReg(int index, int value);   // writes to x0 ignored
    public void setPc(int pc);
    /** Debug read of an implemented CSR, no side effects; IllegalArgumentException if not implemented. */
    public int csr(int address);
    public long cycles();                        // the CPU's cycle count (mcycle without the write offset)
}
```

- `mExtension = false` makes M encodings illegal (for testing; the machine always passes true).
- `host.memory()` is the only bus; `io`, `interruptAck` and `resetDevices` are unused.
- `time`/`timeh` = `CycleClock.cyclesToMicros(cycle count, host.clockHz())`, consistent with the
  CLINT. mcycle/minstret = own counts plus a write offset (4.4).
- Pins: `setIrq(Rv32Map.IRQ_PIN_*, level)` sets the matching `mip` bit (a field); any other pin
  is an `IllegalArgumentException`.
- Interrupts are re-evaluated only: at the start of `run`, after any slow-path bus access, after a
  CSR write to mstatus or mie, after MRET, and when `setIrq` is called between runs. Device levels
  change only at those moments, so this is exact and keeps the per-instruction loop free of an
  interrupt check.
- The limit is re-read from `clock.horizon()` at the same moments. Before a slow-path access the
  CPU publishes the cycle count at the start of the current instruction.
- `BusFault` from a slow-path access becomes the matching access fault trap.
- No Java exception may escape `run` because of guest code: every input word is either executed
  or trapped. Java `/` and `%` by zero throw, so DIV, DIVU, REM and REMU test the divisor first.
- Performance plan (the loop must not allocate, box or call through interfaces on the RAM path):
  - `run` copies `pc`, the cycle count (from `clock.now()`), the limit, `int[] x`, and the RAM,
    ROM and VRAM arrays, bases and sizes plus `vramDirtyPages` into locals, and writes the
    mutable ones back on exit and before slow-path calls.
  - Fetch and data access on RAM/ROM/VRAM: `int off = addr - base;` then an unsigned bounds
    check (`Integer.compareUnsigned(off, size - 4) <= 0` for words) and a little-endian
    `VarHandle` from `MethodHandles.byteArrayViewVarHandle(int[].class, ByteOrder.LITTLE_ENDIAN)`
    (and `short[].class` for halves). These handle unaligned offsets in plain mode. ROM range is
    tested first (1.2). Stores to VRAM also set the dirty page bit. Anything else goes to
    `bus.readN/writeN`.
  - Decode: check `(inst & 3) == 3`, then `switch ((inst >>> 2) & 0x1F)` (dense, a tableswitch),
    with nested `switch` on funct3; fields extracted with shifts inline; immediates
    sign-extended with arithmetic shifts.
  - Keep the hot loop method under HotSpot's 8000-bytecode `HugeMethodLimit`, or it is never
    JIT-compiled (about 10 times slower): CSR access, SYSTEM, M divide, trap entry and every
    slow-path access are separate methods. The 20 M instructions per second benchmark floor
    catches the interpreted case.
  - x0: execute writes unconditionally into `x[rd]` and set `x[0] = 0` after each instruction.
  - Host deadline: a countdown local, `System.nanoTime()` once every 4096 instructions.
  - No decode cache in v1. If the benchmark misses the target, add a per-4 KiB-page predecoded
    cache invalidated by stores into the page and by the bus's RAM write listener (DMA).
  - Target: at least 100 M simple instructions per second on a desktop JVM (see 1.4 for what the
    2 ms budget means at that speed).
- Division semantics per spec: x / 0 = -1 (DIVU: 0xFFFF_FFFF), x % 0 = x, (-2^31) / -1 = -2^31
  with remainder 0. No traps.

### 7.4 Agent B: assembler public API (package `computer.asm` unless noted)

```java
public record AsmSource(String fileName, String text) { }
public record AsmOptions(int textBase, Integer dataBase, int maxImageBytes) {
    public static final int DEFAULT_MAX_IMAGE_BYTES = 64 * 1024 * 1024;
    public static AsmOptions at(int textBase);             // dataBase null, default max
}
public enum Severity { ERROR, WARNING }
public record AsmDiagnostic(Severity severity, String fileName, int line, int column, String message) { }
public record AsmSegment(int address, byte[] bytes) { }    // AsmImage hands out copies
public record AsmLine(String fileName, int line) { }
public final class AsmResult {
    public boolean ok();                                    // no errors
    public List<AsmDiagnostic> diagnostics();               // file order, then line, then column
    public AsmImage image();                                // null when !ok()
}
public final class AsmImage {
    public List<AsmSegment> segments();                     // sorted by address, defensive copies
    /** The image's bytes in [base, base + length) with gaps zero; IllegalArgumentException if a segment lies outside. */
    public byte[] flatten(int base, int length);
    public int entry();
    public int bssStart();
    public int bssEnd();
    public Map<String, Long> symbols();                     // unmodifiable, zero-extended values
    public OptionalLong symbol(String name);
    public AsmLine lineAt(int address);                     // null if unknown
}
// package computer.asm.rv32
public final class Rv32Assembler {
    public static AsmResult assemble(String source, AsmOptions options);          // fileName "<source>"
    public static AsmResult assemble(List<AsmSource> sources, AsmOptions options);
}
```

- `IsaBackend` (framework-internal, public so other backends can live in sibling packages)
  provides: `LexerSyntax syntax()`, `ByteOrder byteOrder()`, `int instructionAlignment()`,
  `byte[] nopPadding()`, `boolean alignIsPowerOfTwo()`, `int size(Statement, AsmContext)` (pass
  1), `void encode(Statement, AsmContext, Emitter)` (pass 2), `boolean directive(String,
  List<Operand>, AsmContext)`, `OptionalLong relocationOperator(String name, Expr arg,
  AsmContext)`. Agent B may refine this interface; it is not a cross-agent contract.
- Determinism: same sources and options give byte-identical output. Thread-safe (no static
  mutable state). Fast: 10 000 lines in under 200 ms. Never throws on any input (5.1).

### 7.5 Agent C: bus, devices, machine public API

Bus (package `computer.bus`):

```java
public final class SystemBus implements Bus {
    public SystemBus(ByteOrder order);                      // no RAM, ROM or VRAM: empty arrays
    public void setRam(byte[] ram, int base);
    public void setRom(byte[] rom, int base);               // rom already padded to the ROM window
    public void setVram(byte[] vram, int base, long[] dirtyPages);
    /** ROM also answers at 0 and wins over RAM; romBase() reads 0 while on. 68000 and ARM2 only. */
    public void setRomOverlay(boolean on);
    /** IllegalArgumentException if windowSize is not a positive multiple of 4 or the window overlaps. */
    public void mapDevice(int base, int windowSize, BusDevice device);
    /** Slow-path memory such as the 8086 banked VRAM view and text alias. */
    public void mapMemory(int base, MemoryRegion region);
    /** The array range for a DMA transfer, or null if it is not wholly inside RAM, VRAM or one writable region. */
    public DmaWindow dma(int address, int length);
    public void setRamWriteListener(WriteListener listener); // null for none; called by DMA into RAM

    @FunctionalInterface
    public interface WriteListener {
        void written(int address, int length);
    }
}
public final class MemoryRegion {
    /** A window of length bytes onto data starting at offset; dirtyPages (nullable) gets a bit per 4 KiB page of data written. */
    public MemoryRegion(byte[] data, int offset, int length, boolean writable, long[] dirtyPages);
    public void setOffset(int offset);                      // banking; offset + length <= data.length
    public int length();
}
/** Bytes [offset, offset + length) of array; call onWrite after writing into it. */
public record DmaWindow(byte[] array, int offset, int length, Runnable onWrite) { }
```

Machine (package `computer.machine`):

```java
public record MachineConfig(CpuModel cpu, int clockHz, int ramBytes, DiskImage disk,
                            byte[] bootRom, LongSupplier rtcEpochSeconds, Integer resetPc,
                            boolean strictFpu) {
    // compact constructor validates only what holds for every platform: cpu non-null,
    // clockHz > 0, ramBytes > 0 and a multiple of 4 KiB, bootRom non-null and non-empty,
    // strictFpu only with CPU_68040 (10.9).
    // disk may be null (no HDD); rtcEpochSeconds null means host time; resetPc null means the
    // platform default. The integration step adds a convenience that defaults bootRom to
    // BootRom.image(). A seven-argument constructor (without strictFpu) passes false.
}
public enum MachineState { OFF, RUNNING, HALTED, OFF_BY_GUEST }

/** What differs per CPU family: limits, memory map, device placement and pin wiring. */
public interface Platform {
    CpuModel model();
    /** IllegalArgumentException naming the problem if this platform cannot run the config. */
    void validate(MachineConfig config);
    int defaultResetPc();
    /** Creates RAM, ROM (padded), VRAM, devices and buses, maps them, wires device lines into the controller. */
    Board build(MachineConfig config, CycleClock clock);
    /** Connects controller outputs and CPU-specific sources (CLINT) to the CPU's pins. */
    void wirePins(Board board, Cpu cpu);
    /** The platform for a model; UnsupportedOperationException if none exists yet (only RV32I now). */
    static Platform forModel(CpuModel model);
}
public final class Rv32Platform implements Platform { public static final Rv32Platform INSTANCE; ... }

/** The hardware of one machine as a platform built it. */
public final class Board {
    public SystemBus memory();
    public SystemBus io();                                  // null on platforms without port I/O
    public byte[] ram();
    public SysCon sysCon();
    public InterruptController pic();
    public IntervalTimer timer();
    public Rtc rtc();
    public Uart16550 com(int index);                        // 0 = COM1, 1 = COM2
    public Ps2Controller ps2();
    public VgaDisplay vga();
    public HddController hdd();
    public Clint clint();                                   // null on platforms without one
    public List<BusDevice> devices();                       // every device, for reset and catch-up
}

public final class Machine {
    public static final long DEFAULT_HOST_BUDGET_NANOS = 2_000_000L;
    public static final long TICK_NANOS = 50_000_000L;
    public Machine(MachineConfig config, CpuFactory cpus);  // Platform.forModel, validate, build, create CPU, wirePins
    public void powerOn();
    public void powerOff();
    public void reset();
    public void runTick(long nanos, long hostBudgetNanos);
    public MachineState state();
    public int exitCode();                                  // valid in OFF_BY_GUEST
    public double speed();                                  // 1.0 = full speed
    public Cpu cpu();
    public Board board();
    public SystemBus bus();                                 // board().memory()
    public CycleClock clock();
    public Uart16550 com(int index);                        // 0 = COM1, 1 = COM2
    public Ps2Keyboard keyboard();
    public Ps2Mouse mouse();
    public VgaDisplay vga();
    public HddController hdd();
    public SysCon sysCon();
    public String debugLog();                               // SysCon DEBUG_OUT text so far
    public void loadRam(int address, byte[] bytes);         // tests and tools
    public static final int STATE_VERSION = 1;
    public byte[] saveState();                              // spec 1.5
    public void loadState(byte[] data) throws StateFormatException;  // all or nothing
}
```

- Only `CpuModel.RV32I` machines are built now (`Rv32Platform`, the memory map in 4.1);
  `Platform.forModel` throws `UnsupportedOperationException` for another model, from the
  `Machine` constructor. `Rv32Platform.validate`: clock a whole number of MHz in
  `CLOCK_MIN_HZ..CLOCK_MAX_HZ`, RAM in `RAM_MIN..RAM_MAX`, bootRom at most `ROM_SIZE` bytes.
- Construction order: platform `build` (RAM, ROM, VRAM, devices with their `IrqSink`s, buses),
  then `cpus.create(new CpuHost(RV32I, clockHz, board.memory(), null, clock, null, null, resetPc))`, then `wirePins` (CLINT MSIP/MTIP to pins 3 and 7, controller output 0 to pin 11).
- `runTick` implements 1.4 exactly: per segment, `cpu.run(segmentEnd, deadline)`, then pending
  SysCon requests (reset, power off, ROM overlay, WAIT) and device-reset requests, then
  `advanceTo(clock.now())` on every device, then the next segment. When the CPU halts, the rest
  of the window is idle time (devices still advance; the UART still drains). When the guest powers
  off, the machine stops after that instruction.
- Device `read`/`write` start with `advanceTo(clock.now())`.
- The VGA device owns `dirtyPages` and passes it to `SystemBus.setVram`; `endFrame` reads and
  clears it.

### 7.6 Integration step

- `StandardCpus` and `BootRom` as in 7.1 and 4.7. `BootRom.image()` assembles the ROM source once
  (lazily, cached) with `Rv32Assembler` at `ROM_BASE` and returns `flatten(ROM_BASE, ROM_SIZE)`
  trimmed to the last non-zero byte (the platform pads it again).
- The end-to-end tests of 8.4, including the encoder and decoder cross-check.

## 8. Test plan

All tests are JUnit 5, pure Java, under `src/test/java/com/nectariich/create_flippies/content/computer`.
Run with `./gradlew test --tests 'com.nectariich.create_flippies.content.computer.*'` (JDK 17), or
with `javac` plus the JUnit console standalone jar if Gradle is too slow.

### 8.1 CPU (agent A, hand-encoded words in a test bus)

- Every ALU op, including SRA of negative values, SLT vs SLTU on 0x8000_0000, register shift
  amounts using only the low 5 bits, and writes to x0 ignored.
- Loads: LB and LH sign-extend, LBU and LHU zero-extend; SB/SH write only their bytes;
  misaligned LW/SW work and cost the extra cycle.
- Branches taken and not taken; JAL links pc + 4; JALR clears bit 0 of the target and links
  correctly when rd == rs1; jump to a target that is 2 mod 4 traps with cause 0, mepc = the jump,
  mtval = the target, and rd is not written (tested with `jalr ra, 2(ra)`, rd == rs1 == ra, and
  with JAL) and minstret does not count it.
- M: MUL low bits; MULH(-1, -1) = 0; MULHU(0xFFFF_FFFF, 0xFFFF_FFFF) = 0xFFFF_FFFE;
  MULHSU(-1, 0xFFFF_FFFF) = 0xFFFF_FFFF; DIV and REM by zero; DIV overflow (-2^31 / -1);
  with `mExtension = false` they are illegal.
- CSRs: read-modify-write semantics of all six ops; CSRRS with rs1 = x0 on a read-only CSR is
  legal and does not write; CSRRW to a read-only CSR is illegal; an unknown CSR is illegal;
  misa, mhartid values; mip not writable; mcycle and minstret count as specified; a write to
  mcycle or minstret is what the next instruction reads (own increment suppressed), and writing
  one half keeps the other; mhpmcounter3/mhpmcounter31h/mhpmevent3/hpmcounter3/mconfigptr read 0,
  CSRRW to hpmcounter3 and to mconfigptr is illegal; `time` agrees with
  `CycleClock.cyclesToMicros`.
- Fences: FENCE with reserved fm (for example 0x9000_000F), with rd or rs1 non-zero, and FENCE.I
  with non-zero imm, rs1 and rd all execute as 1-cycle no-ops; MISC-MEM funct3 010 is illegal.
- Traps: ECALL (11), EBREAK (3), illegal (2) with mtval = the instruction (including
  0x0000_0000, 0xFFFF_FFFF and `unimp` 0xC000_1073), load and store access faults on unmapped addresses (5, 7), store to
  ROM (7), fetch from unmapped (1); MRET restores MIE from MPIE; vectored mtvec sends interrupts
  to BASE + 4 * cause but exceptions to BASE; double-fault halt with mtvec = 0.
- Interrupts: MTIP pending with MIE = 1 and mie.MTIE = 1 is taken (mcause 0x8000_0007, mepc = next
  instruction); masked with MIE = 0; MEI beats MTI when both pend; WFI returns WAITING, wakes on a
  pin even with MIE = 0 and continues after WFI.
- Timing: exact cycle totals for a small program mixing each cost class; `run(until)` stops within
  one instruction of `until`; slicing: running a program in 1-cycle, 7-cycle and one big `run`
  gives identical registers, memory, pc and cycle count (property test over several programs).
- Horizon: a test device whose write calls `clock.requestStopAt(now + 50)` makes `run` return
  REACHED within one instruction of that cycle, although `untilCycle` is far later; a device write
  that calls `requestStopAt(now)` stops after the writing instruction; `run` loads its cycle count
  from `clock.now()` (advance the clock between runs, mcycle follows).
- Re-entrant pins: a test device whose write calls `cpu.setIrq(IRQ_PIN_MEIP, true)` from inside
  `run` gets the interrupt taken right after the writing instruction (MIE = 1), with mepc = the
  next instruction.
- Deadline: a past deadline returns DEADLINE after at most 4096 instructions with state consistent.
- Guest fuzz: random instruction words over random RAM contents with a random mtvec, run for
  millions of steps in many seeds; nothing but traps, WAITING, HALTED or a normal return comes out
  of `run` (no Java exception), and DIV/REM by zero never throws.
- Benchmark (tagged, prints MIPS; asserts only a lenient floor of 20 M instructions per second so
  it does not flake on CI).

### 8.2 Assembler (agent B)

- Every encoding listed in 5.2 matches its hand-checked word; every base, M, Zicsr and SYSTEM
  instruction round-trips to its documented fields (opcode, funct3, funct7, registers,
  immediate).
- Every pseudo-instruction expands as specified (including `sgt`, `sgtu`, `sext.b`, `sext.h`,
  `zext.h`, `unimp`, `call rd, sym`); `li` boundaries with their expected words: 0, 2047, -2048,
  2048 (0x800), -2049, 0x7FFF_FFFF, 0x8000_0000 (lui only, 0x8000_0537), 0xFFFF_F800 (one addi,
  0x8000_0513), 0x1234_5000, 0x1234_5FFF, 0x1_0000_0000 (error); a forward-referenced `li` is
  8 bytes.
- `%hi`/`%lo` and `%pcrel_hi`/`%pcrel_lo` on values whose bit 11 is set and clear; `la` and `call`
  reach a symbol 1 MiB away correctly.
- Labels: forward and backward references; numeric local labels `1b`/`1f`; duplicate label
  error with both lines; undefined symbol error.
- Ranges: branch out of range error, JAL out of range, 12-bit immediate out of range, shift amount
  32, bad register, wrong operand count, all with correct line and column; several errors in one
  source all reported.
- Directives: `.byte/.half/.word/.dword` little-endian, value range checks, `.ascii` escapes,
  `.asciz`, `.space` with fill, `.align` (power of two) with NOP padding in text and zeros in data,
  `.balign`, `.p2align`, `.equ` and `.set` and `=`, `.org` forward and its backward error.
- Sections: text, rodata, init_array, fini_array, data and bss layout and the predefined
  symbols; bss in no segment; `.section .rodata.str1.4,"aMS",@progbits,1` maps to rodata;
  `.section .note.GNU-stack,"",@progbits`, `.comment` and `.debug_info` sections are dropped with
  no diagnostic; `.comm` and `.lcomm` land in bss with the right alignment; `.uleb128` and
  `.sleb128` encodings (0, 127, 128, -1, -128, 624485).
- `dataBase`: text at 0x1000 and data at 0x8000_0000 give two small segments, not one huge one;
  data's bytes sit at `__data_load_start` right after rodata, and a data label resolves to its
  0x8000_0000 run address; `flatten` of the ROM range holds the load copy.
- Several files: two files each with a local `loop` label and `.L1` assemble without conflict;
  a call from one file to a `.globl` function in the other resolves; a global defined twice is an
  error naming both files; an undefined `.weak` symbol is 0; diagnostics carry the file name.
- Symbols near the sign boundary: with RAM at 0x8000_0000, `sym - .` and `.word sym` are right and
  `symbols()` holds the zero-extended value.
- Limits: `.space 0x7FFFFFFF`, `.fill 0x40000000, 4, 0` and `.org 0x7FFF_0000` give an error, not
  an OutOfMemoryError; 100 000 nested parentheses give an error, not a StackOverflowError.
- Fuzz: random token soup and random mutations of valid sources never throw, only produce
  diagnostics.
- GCC compatibility: a hand-written snippet in the shape of `riscv32-unknown-elf-gcc -S` output
  (`.file`, `.option nopic`, `.attribute arch, "rv32i2p1_m2p0"`, `.text`, `.align 2`, `.globl main`,
  `.type main, @function`, `.size main, .-main`, `.ident`) assembles with no diagnostics.
- Determinism and comments (`#`, `//`, `/* */`) and `;` as statement separator.

### 8.3 Bus and devices (agent C)

- `SystemBusContractTest` passes (7.2). SystemBus beyond the contract: window size independent
  of `size()`; overlapping `mapDevice` is an `IllegalArgumentException`; ROM overlay on and off
  (`romBase()` 0 and back, ROM wins at 0); a banked `MemoryRegion` follows `setOffset` and marks
  the right dirty pages; `dma` windows inside RAM, VRAM and across an edge (null); the RAM write
  listener sees DMA writes; a bus with nothing set has empty arrays and size 0.
- CycleClock: `cyclesToMicros` and `microsToCycles` at 10 MHz, 4_772_727 Hz and 100 MHz, round
  trips, values near Long.MAX_VALUE, negative micros; `requestStopAt` never raises the horizon
  and never goes below now.
- SysCon: all read values, POWER codes recorded as requests with `requestStopAt(now)`, WAIT and
  ROM_OVERLAY_OFF requests, DEBUG_OUT text.
- InterruptController: PENDING reflects levels, CLAIM picks the lowest enabled line, output 0
  follows ENABLE; ROUTE moves a line to output 3 (output 0 drops, output 3 rises, CLAIM_OUT 3
  reports it); 16-bit split access to ROUTE registers.
- CLINT: mtime rate at two clock rates (one not a whole number of MHz), MTIP level when mtime >=
  mtimecmp, nextEventCycle exact, writes of hi/lo halves, an MTIMECMP write lowers the horizon.
- IntervalTimer: periodic expiry on the grid, one-shot, W1C, IRQ level, counter latch.
- Rtc: fixed source; SET_HI sets the offset.
- Uart16550: reset values; DLAB access to the divisor; THR to host with the right character time
  (byte not visible to `hostRead` before it); THRE and TEMT timing; FIFO depth 16 vs 1; RX trigger
  levels and the RDA interrupt; character timeout after 4 character times with one byte (no
  trigger condition); overrun; loopback with CTS = RTS, DSR = DTR, RI = OUT1, DCD = OUT2 and TERI
  when OUT1 goes 1 to 0; setting IER bit 1 with an empty THR raises the THRE interrupt at once;
  IIR priority and clearing rules; MSR deltas on host connect; 1000 bytes sent back to back
  complete at exactly start + 1000 character times (no rounding drift) at 4_772_727 Hz.
- PS/2 keyboard: power-on 0xAA alone, 0xFF gives 0xFA 0xAA; keyDown/keyUp byte sequences for A, Enter, Right Ctrl, Right
  Arrow, Print Screen, Pause; command replies (0xFF, 0xED + data, 0xEE, 0xF0 0x00, 0xF2, unknown);
  overflow 0x00; typematic repeat timing after 500 ms; IRQ line follows RX ready and CTRL.
- PS/2 mouse: power-on 0xAA 0x00, 0xFF gives 0xFA 0xAA 0x00; reporting off until 0xF4; packet
  bit layout for negative dx and dy, button bits, clamping with overflow bits and carry; 0xE9
  status bytes with left held (byte 1 bit 2) and right held (bit 0), after 0xF4 (bit 5) and 0xE7
  (bit 4); identify.
- VGA: mode dimensions and pitch; CTRL resets to 0xD; generation increments only when dirty at
  `endFrame`; VBLANK and FRAME; START wrap; snapshot and `copyVisibleInto` copy only the visible
  bytes; `dirtyRowsSince` reports exactly the rows over a dirtied page, and all rows after a
  palette or START change; PAL_INDEX/PAL_DATA write and read with auto-increment, also as two
  16-bit halves; reset palette values.
- HDD: READ and WRITE round trip via DMA with completion exactly at the scheduled cycle (not
  before), the command write lowering the horizon to it; every error code; IDENTIFY block; IRQ; BUSY rejection; disk dirty chunks; a sparse
  image does not allocate unwritten chunks.
- Saved state (`DeviceStateTest`, `StateIoTest`): every device is driven into a busy state, saved,
  loaded into a fresh device at the same cycle and run on beside the original; observations and
  final bytes match. Each rejects an unknown version; the HDD rejects a missing or different
  disk. Paged RAM storage: all-zero RAM is tiny, random and partial pages round-trip, a size
  mismatch, corrupt or truncated pages are rejected.
- Device register fuzz: random reads and writes (all widths, aligned offsets in the window) on
  every device with random host input never throw and never move an IRQ line without a matching
  register state.
- Machine with a fake CPU: windows end at device events and at `requestStopAt`; a WAITING CPU
  fast-forwards with no host time; SysCon WAIT parks the CPU until a pin rises; a past deadline
  dilates time and `speed()` drops; guest power off and guest reset take effect after the writing
  instruction with no further instruction run; reset keeps RAM; `endFrame` once per tick;
  `Platform.forModel` throws for non-RV32I models; `Rv32Platform.validate` rejects 4_772_727 Hz,
  RAM above 64 MiB and an oversized ROM; `resetPc` is honoured.

### 8.4 End-to-end (integration step: assemble, run on `Machine`, check results)

- Hello: a program prints "Hello, PC!\n" to COM1 and powers off with 0x5555; `hostRead` returns
  exactly that text; state OFF_BY_GUEST, exit code 0.
- VGA text: writes "HI" with attribute 0x1F at row 0 and the cursor at (2, 0); after one tick the
  snapshot holds bytes 'H', 0x1F, 'I', 0x1F and the generation went up by one.
- VGA graphics: mode 1, fills the palette entry 1 with 0xFF0000, draws a diagonal; snapshot checks
  pixel bytes and the palette.
- PS/2 echo: the program enables the keyboard IRQ and loops on WFI; its handler reads DATA and
  writes the byte to COM1. The test injects keyDown(A), keyUp(A); COM1 output is 0x1C 0xF0 0x1C
  (after the power-on 0xAA, which the program discards first).
- HDD: write a sector of a known pattern to LBA 5 from RAM, read it back into another buffer,
  compare in the guest, print "OK"; the disk image holds the pattern at byte 5 * 512.
- Timer interrupt: mtimecmp set 1 ms ahead, the handler increments a counter and re-arms; after 10
  interrupts the program powers off; total emulated time is about 10 ms (checked against the clock).
- Busy-loop latency: the main loop spins on a RAM counter (no WFI); mtimecmp armed 1 ms ahead; the
  interrupt is taken within one instruction of the 1 ms cycle (mepc and the clock stamp in the
  handler checked), not at the next tick.
- Interval timer through the interrupt controller (MEI path) with CLAIM.
- Illegal instruction: a handler prints mcause and mtval in hex; output matches.
- Boot ROM: no disk prints "No disk"; a disk whose sector 0 has 0x55AA and a small program prints
  through the ROM's `print_string` and powers off; a disk without the signature prints
  "Disk not bootable".
- Budget: a busy loop at 10 MHz with a tiny host budget shows `speed() < 1` and still makes
  progress each tick.
- Freeze and resume (`SaveStateE2eTest`): a guest keeps both timers interrupting, COM1 sending at
  9600 baud, the keyboard polled (keys injected after ticks, one held for typematic repeat), an
  HDD READ in flight and VGA mode 1 drawing and writing the palette. One machine runs
  uninterrupted; its twin is saved and loaded into a new `Machine` after every tick. Their saved
  bytes match after every tick, and output, debug log and final state match at the end. A single
  mid-run save resumes with the clock unchanged. OFF and HALTED machines round-trip. Rejections,
  each leaving the target unchanged: unknown envelope version, unknown component version after
  RAM was already loaded (rolled back), wrong CPU model, RAM size, clock, boot ROM or reset PC,
  missing or different disk, flipped byte, truncation, foreign data.
- Encoder and decoder cross-check: the two agents implemented the encodings independently, so for
  every base, M, Zicsr and SYSTEM instruction (random registers and immediates, boundary
  immediates included) assemble one instruction with `Rv32Assembler`, execute it on `Rv32Cpu`
  from a known register state, and compare registers, memory and pc with a small Java reference
  model written in the test. A field-layout mismatch shows up without a reference toolchain.

## 9. Out of scope now

- In-game anything: blocks (PC case, monitor, keyboard), items (CPU, RAM sticks, HDD), GUIs and the
  code editor, networking and frame sync, Blockbench models, textures, the client text-mode font
  renderer, world-save files for disks, server config, power draw.
- The 8086, 8088, ARM2 and MIPS R3000 and their assemblers. The 68000 and 68040 are planned in
  section 10 and in progress.
- C and C++ (any phase), relocatable objects and the linker.
- Idle-loop detection for CPUs without a wait instruction, decode caches, PS/2 wheel mouse,
  UART break and modem control beyond what 3.5 lists, VGA register-level emulation of any kind.

## 10. The 68000 and 68040

Two 68k CPUs for the buildable PC, each a chip-level emulator plus an assembler, wired into the
existing machine (`StandardCpus`, `Platform`, boot ROM). The 68000 is the chip of 2.3. The 68040
runs the full integer instruction set (user and supervisor) and its built-in FPU, with no MMU.
This section is authoritative for both; where 2.3 is shorter, this section wins. House rules
apply unchanged.

How sure each fact is: facts marked "(verify)" were not confirmed against a primary source while
writing this section; the owner of that area checks them in the 68040 User's Manual (UM040) or the
68000 Family Programmer's Reference Manual (PRM) before coding and records the result here.
Everything else is standard and well established, or was checked in the existing code.

User decisions that settle this section (also in "Review decisions"):

- 68040 CPU items: 25 and 33 MHz. The 68000 stays in its real range (items 8 and 12.5 MHz).
- `.word` is 2 bytes in the 68k assembler (the classic 68k meaning); RV32I keeps its 4-byte
  `.word`.
- The strict FPU mode (trap the instructions and data types bare 68040 hardware leaves to its
  support package) is selectable in game: a machine configuration option, part of
  `MachineConfig` and so of the saved-state config check, default off (emulate them).
- No third-party 68000 test-vector suite is used: the one known public suite has no licence.
  Tests are hand-checked only.
- No MMU on the 68040: the integer unit and the FPU are emulated.

### 10.1 Decisions

1. One integer core class, `M68kCpu`, parameterised by model (68000 or 68040), with one
   65536-entry opcode-class table per model built once (static). Not two classes.
2. The FPU is a separate class, `M68kFpu`, present only on the 68040, behind a small host
   interface so it can be built and tested in parallel.
3. FPU arithmetic uses a soft-float 80-bit extended type (64-bit mantissa in a `long`, 15-bit
   exponent plus sign in an `int`), bit exact for every operation the real 68040 does in
   hardware. Transcendentals are computed through `StrictMath` in double precision and rounded to
   the destination precision (documented deviation in the low bits).
4. Unimplemented-on-040 FPU instructions (FSIN, FETOX, FMOVECR, FINT, packed decimal, denormal
   operands...) are emulated by default, so the chip behaves like a real 68040 system with its
   floating-point support package installed. The strict FPU option makes them trap like bare
   hardware: a machine configuration option the player selects in game for a 68040
   (`MachineConfig.strictFpu`, default off), part of the saved-state config check (user
   decision).
5. No MMU: MMU control registers exist as storage, TC.E always reads 0, PFLUSH variants are
   privileged no-ops, PTEST reports a transparent hit. CINV and CPUSH are privileged no-ops. CACR
   keeps only its two defined bits. Superseded by 12.2, built in step 1 of section 12: the 68040
   now has its MMU (12.14.1). CINV, CPUSH and CACR are unchanged.
6. One memory map for both CPUs, written as 32-bit addresses whose low 24 bits are the 68000 map:
   devices at 0xFFF0_0000 (68000 sees 0xF0_0000), VRAM 0xFFE0_0000, ROM 0xFFFC_0000. One boot ROM
   source serves both CPUs.
7. Interrupts: autovectors only (vector 24 + level), so the missing interrupt acknowledge hook is
   not needed for 68k. Controller output k drives IPL k, output 0 also drives IPL 4. Level 7 is
   edge-detected inside the CPU.
8. The RESET instruction and ROM overlay gaps are closed in `Machine`/`Platform` with recorded
   requests, performed between runs.
9. Reset vectors (SSP, PC) are fetched by the first `run` after `reset()`, not inside `reset()`.
10. 68000 cycle counts follow the reference timing tables (EA time plus base time, MOVE matrix,
    data-dependent MULU, MULS, DIVU, DIVS, shifts); the 68040 uses a small fixed-cost model.
11. No 68000 prefetch queue model (documented deviation: group 0 frame PC and self-modifying
    code within the next 4 bytes).
12. Assembler: one backend, `M68kBackend`, with a target (68000 or 68040) that gates 68040-only
    instructions, EA modes and `.l` branches. Small, flag-guarded extensions to the shared lexer
    and parser (`$hex`, `%bin`, float literals, `*` comments and location counter,
    `name equ expr`), written in the foundation step. `.word` is 2 bytes in this backend (user
    decision; RV32I keeps 4).
13. Branch and absolute-address size selection by relaxation (smallest form that reaches);
    `move.l #n,Dn` to MOVEQ and `add`/`sub #1..8` to ADDQ/SUBQ automatically (flag-identical).
14. Three parallel owners after the foundation step (integer core, FPU, assembler), then one
    integration step (10.10).
15. Clocks (user decision): 68040 CPU items 25 and 33 MHz; the 68000 stays in its real range
    (items 8 and 12.5 MHz, parts from 4 to 16.67 MHz).
16. Tests are hand-checked only (user decision). No third-party 68000 test-vector suite is used:
    the one known public suite has no licence. Flags the chip leaves undefined get a fixed,
    documented rule here instead of being matched to vectors.

### 10.2 Chip facts

#### 10.2.1 Common 680x0 programmer's model

- D0..D7, A0..A7 (A7 is the active stack pointer), PC, SR. CCR is SR bits 4..0: X 4, N 3, Z 2,
  V 1, C 0. System byte: T 15 (68000; on the 68040 T1 15 and T0 14), S 13, M 12 (68040 only),
  interrupt mask 10..8.
- Big-endian. Instructions are 16-bit words, 2 to 10 bytes on the 68000, up to 22 bytes on the
  68040 (full extension words with 32-bit base and outer displacements).
- Exception vectors: 0 reset SSP, 1 reset PC, 2 bus error (68040: access error), 3 address error,
  4 illegal instruction, 5 zero divide, 6 CHK and CHK2, 7 TRAPV, TRAPcc, FTRAPcc, 8 privilege
  violation, 9 trace, 10 line 1010, 11 line 1111, 13 coprocessor protocol violation (not used on
  the 68040), 14 format error (68010 and later), 15 uninitialised interrupt, 24 spurious, 25..31
  autovectors for levels 1..7, 32..47 TRAP #0..#15, 48 FP branch or set on unordered (BSUN),
  49 FP inexact, 50 FP divide by zero, 51 FP underflow, 52 FP operand error, 53 FP overflow,
  54 FP signalling NaN, 55 FP unimplemented data type (68040), 64..255 user vectors.
- Condition tests (Bcc, DBcc, Scc, TRAPcc), code: T 0, F 1, HI 2 (!C & !Z), LS 3 (C | Z),
  CC 4 (!C), CS 5 (C), NE 6 (!Z), EQ 7 (Z), VC 8, VS 9, PL 10 (!N), MI 11 (N), GE 12 (N == V),
  LT 13 (N != V), GT 14 (N == V & !Z), LE 15 (Z | N != V). Precompute a 16 x 32 boolean table
  indexed by condition and CCR low 4 bits.

#### 10.2.2 68000 specifics (2.3 plus)

- 24-bit address bus: every bus address is masked with 0x00FF_FFFF; the PC and address registers
  hold all 32 bits (a BSR pushes the full 32-bit PC).
- Address error (vector 3): word or long data access at an odd address, and any instruction fetch
  at an odd address (taken branch, JMP, JSR, RTS, RTE, RTR to an odd target; also exception
  vectors with an odd handler address). Raised before the access reaches the bus.
- Group 0 frame (bus and address error), 7 words, pushed on the supervisor stack, from the new SP
  upwards: status word (bit 4 R/W, 1 = read; bit 3 I/N, 1 = the access was not an instruction fetch
  (verified: 68000 user's manual, figure 6-7, "Instruction = 0, Not = 1", "Write = 0, Read = 1");
  bits 2..0 function code), access address (long), instruction
  register (the first word of the faulting instruction), SR before the exception, PC (long). The
  stacked PC is "advanced" 2 to 10 bytes past the instruction start depending on where in the
  instruction the fault hit (68000 prefetch detail); this emulation stacks instruction address + 2 and
  documents it (10.1, decision 11). Function codes: 1 user data, 2 user program, 5 supervisor data, 6
  supervisor program, 7 interrupt acknowledge.
- Group 1 and 2 frames are 3 words: SR then PC (PC on top of SR: SP points at SR). PC is the
  instruction's address for illegal, privilege violation, line A and line F; the next
  instruction for TRAP, TRAPV, CHK, zero divide, and for trace and interrupts.
- Exception groups and priority: group 0 (reset, bus error, address error) highest; group 1
  (trace, interrupt, illegal, privilege violation); group 2 (TRAP, TRAPV, CHK, zero divide)
  arise from instruction execution. After an instruction that raises a group 2 exception with T
  set, the group 2 frame is built first and then trace is taken (the trace handler sees the group
  2 handler's first instruction pending). Trace before interrupt.
- A double fault (a bus or address error while building a group 0 frame or reading the reset
  vectors) halts the chip: `Exit.HALTED` with a reason naming the vector and addresses.
- MOVE from SR is not privileged on the 68000 (it is on the 68040). No MOVE from CCR, no
  MOVEC, no VBR (vectors always at 0), no RTD, no TST An, no Bcc.L (a byte displacement of
  0xFF is a branch by -1, to an odd address, so an address error).
- Brief extension word on the 68000: bits 10..8 are ignored (no scale, no full format).
- RESET: privileged, asserts the reset line for 124 clocks, 132 clocks in total; resets devices,
  not the CPU.
- STOP #imm: privileged, loads SR, then waits; wakes on an interrupt above the new mask (or level 7
  edge), trace (if the new SR has T set, trace is taken at once), or reset.
- Cycle counts (68000 UM timing tables; 4 clocks per bus cycle, no wait states):
  - EA calculation time, byte or word / long: Dn 0/0, An 0/0, (An) 4/8, (An)+ 4/8, -(An) 6/10,
    d16(An) 8/12, d8(An,Xn) 10/14, abs.W 8/12, abs.L 12/16, d16(PC) 8/12, d8(PC,Xn) 10/14,
    #imm 4/8.
  - MOVE has its own source by destination matrix (MOVE.W Dn,Dn 4; MOVE.L Dn,Dn 4; MOVE.W
    (An),(An) 12; MOVE.L (An),(An) 20; and so on); copy the table, do not derive it.
  - Examples to test: NOP 4, Bcc taken 10, not taken 8 (.S) or 12 (.W), BRA 10, BSR 18, DBcc
    10 (loop), 12 (condition true), 14 (count expired), RTS 16, JSR (An) 16, LEA (An) 4,
    shifts on a register 6 + 2n (byte, word) and 8 + 2n (long), MULU 38 + 2n (n = ones in the
    source), MULS 38 + 2n (n = 01 or 10 bit pairs in the source with a 0 appended), DIVU up to
    140, DIVS up to 158 (implement the exact data-dependent algorithm from the published per-bit
    description; tests check hand-computed totals for a few dividends, including the worst
    case). Verified: the user's manual's 140 and 158 (table 8-4) are marked as maximum values,
    and the published per-bit description (J. Cwik, "68000 division timing", 2005; also the
    "Yet Another Cycle Hunting Table") gives a true worst case of 136 for DIVU.W and 156 for
    DIVS.W with a register divisor, best cases 76 and 122, overflow 10 (DIVU) and 16 or 18
    (DIVS, positive or negative dividend). The core implements that algorithm, so its maximum is
    136 and 156; no source explains the manual's extra 4 and 2 cycles.
  - TAS and ANDI.L (checked, kept as the manual): the manual gives TAS to memory as 14 + EA
    (table 8-6) and ANDI.L #,Dn as 14 (table 8-5); measurements on real chips reported in the
    "Yet Another Cycle Hunting Table" give 10 + EA and 16. The core follows the manual's tables,
    as decision 10 says; documented deviation from the measured chip. AND.L #,Dn is 16 in both.
  - Exceptions: TRAP 34, illegal and privilege 34, line A and F 34, trace 34, TRAPV 34 (taken),
    CHK 40 (taken, plus EA), zero divide 38 (plus EA), interrupt 44 (autovector), bus and
    address error 50, RESET 132, reset sequence 40 (verified: table 8-14, 40(6/0), from RESET
    and HALT negated to the first instruction).

#### 10.2.3 68040 integer specifics

- Clock: real parts at 25, 33 and 40 MHz. CPU items 25 and 33 MHz (user decision); the platform
  accepts 25 to 40 MHz (`M68kMap`).
- 32-bit address and data buses. Misaligned data accesses are legal (no address error); an
  instruction fetch at an odd address (a jump to an odd target) is an address error with a
  format 2 frame. Verified (UM040 8.2.2 and 8.4): the stacked PC is the instruction that caused
  the error (the jump or branch, not its target), and the address field is the odd address with
  bit 0 cleared. The core was fixed to clear bit 0 at integration.
- Stacks: USP (S = 0), ISP (S = 1, M = 0), MSP (S = 1, M = 1). MOVEC reads and writes all three
  (USP 0x800, MSP 0x803, ISP 0x804). An interrupt taken with M = 1 pushes a format 0 frame on the
  MSP, clears M, then pushes a format 1 throwaway frame on the ISP. Other exceptions keep M.
- Control registers (MOVEC numbers): SFC 0x000, DFC 0x001, CACR 0x002, TC 0x003, ITT0 0x004,
  ITT1 0x005, DTT0 0x006, DTT1 0x007, USP 0x800, VBR 0x801, MSP 0x803, ISP 0x804, MMUSR 0x805,
  URP 0x806, SRP 0x807. Any other number is an illegal instruction. CACR implements only DE
  (bit 31) and IE (bit 15); the other bits read 0.
- Stack frame formats (format code in bits 15..12 of the format/vector word, vector offset
  = vector * 4 in bits 11..0):
  - Format 0, 4 words: SR, PC, format/vector. Used for TRAP #n, illegal, line A, line F
    (non-FPU), privilege violation, interrupts, format error, FP pre-instruction (BSUN, and the
    unimplemented data type trap on an input operand).
  - Format 1, 4 words: throwaway frame on the ISP after an interrupt with M = 1.
  - Format 2, 6 words: adds an address field (long). Used for CHK, CHK2, TRAPcc, FTRAPcc,
    TRAPV, trace, zero divide (address = the instruction that caused it), address error,
    and the unimplemented FP instruction trap. Verified (UM040 8.4, 9.6.1): address error
    stores the referenced address with bit 0 cleared and PC = the faulting instruction; the
    unimplemented FP instruction (vector 11) stores the calculated effective address and PC =
    the next instruction.
  - Format 3, 6 words: FP post-instruction exceptions (including the unimplemented data type
    trap on FMOVE out); address = the effective address.
  - Format 4, 8 words: effective address and PC of the faulted FP instruction. The PRM lists it
    for the FPU-less 040 variants' FP traps; the full 68040 does not use it (verified: UM040
    9.6.1 gives format 2 for the unimplemented FP instruction on the full chip).
  - Format 7, 30 words: access error. Fill the fault address, an SSW with RW (bit 8), SIZE
    (bits 6..5), TT (bits 4..3), TM (bits 2..0) and ATC = 0, and zero the write-back and
    push-data fields (no write-backs pending in this emulation).
  - RTE checks the format: 0, 1, 2, 3, 4, 7 are accepted (with their lengths 8, 8, 12, 12, 16,
    60 bytes); anything else is a format error (vector 14) with a format 0 frame. RTE of format 7
    re-runs the faulted instruction (the emulation never leaves a pending write-back).
- Trace: T1 = trace every instruction, T0 = trace change of flow (branches taken, jumps, returns,
  SR writes). T1T0 = 11 is undefined; treat it as T1.
- New integer instructions over the 68000 (68020 class, all present on the 040 except CALLM and
  RTM, which are illegal): Bcc.L, BSR.L, BFCHG, BFCLR, BFEXTS, BFEXTU, BFFFO, BFINS, BFSET,
  BFTST, BKPT (takes the illegal instruction exception, no debugger), CAS, CAS2, CHK.L, CHK2,
  CMP2, DIVS.L, DIVU.L, DIVSL.L, DIVUL.L, EXTB.L, LINK.L, MOVE from CCR, MOVEC, MOVES, MULS.L,
  MULU.L, PACK, UNPK, RTD, TRAPcc (no operand, .W, .L), CMPI and TST with PC-relative source, TST
  An, MOVE16 (68040 only), CINV, CPUSH, PFLUSH, PFLUSHN, PFLUSHA, PFLUSHAN, PTESTR, PTESTW.
- New EA forms: scaled index (scale 1, 2, 4, 8 in brief bits 10..9), full extension word
  (bit 8 = 1: BS base suppress bit 7, IS index suppress bit 6, BD size bits 5..4 (01 null,
  10 word, 11 long), I/IS bits 2..0 selecting no indirect, pre-indexed or post-indexed memory
  indirect with null, word or long outer displacement). Reserved combinations are illegal.
- RESET on the 68040 asserts the reset output for 512 clocks (verified: UM040, "drives the reset
  out (RSTO) signal for 512 BCLK cycles"); charge about 520.
- MOVES: superseded by 12.2.4. SFC and DFC now select the function code (UM040 3.2.5, table 3-2):
  codes 1 and 2 are user data, 5 and 6 supervisor data, translated through URP or SRP; codes 0,
  3, 4 and 7 are physical, untranslated accesses.
- MOVE16: copies a 16-byte line, addresses with bits 3..0 cleared; forms (Ax)+,(Ay)+ and
  absolute long either way, postincrementing by 16.

#### 10.2.4 68040 FPU facts

- Registers FP0..FP7 (80-bit extended), FPCR, FPSR, FPIAR.
- FPCR: exception enables 15..8 (BSUN 15, SNAN 14, OPERR 13, OVFL 12, UNFL 11, DZ 10, INEX2 9,
  INEX1 8); rounding precision 7..6 (00 extended, 01 single, 10 double); rounding mode 5..4
  (RN, RZ, RM, RP); other bits read 0.
- FPSR: condition code byte 27..24 (N 27, Z 26, I 25, NAN 24); quotient byte 23..16 (sign 23,
  7 low quotient bits 22..16; FMOD and FREM only); exception status 15..8 in the same order as
  the FPCR enables; accrued exceptions 7..3 (IOP 7, OVFL 6, UNFL 5, DZ 4, INEX 3). Accrued bits
  are ORed from the status byte after each arithmetic instruction (IOP from BSUN, SNAN, OPERR;
  INEX from INEX1, INEX2 or OVFL; UNFL from UNFL and INEX2; and so on per the PRM).
- FPIAR: address of the last FP instruction that could raise an exception (arithmetic,
  FMOVE to memory), written by those instructions.
- Memory formats: B byte, W word, L long, S single, D double, X extended (12 bytes: sign and
  15-bit exponent in the first word, a zero word, then the 64-bit mantissa with explicit integer
  bit), P packed decimal (12 bytes; static or dynamic k factor on output). Source specifier
  codes: L 000, S 001, X 010, P 011 (static k on output), W 100, D 101, B 110, P 111 (dynamic k).
- Implemented in 68040 hardware (from the FPSP split): FMOVE, FSMOVE, FDMOVE, FADD, FSADD,
  FDADD, FSUB, FSSUB, FDSUB, FMUL, FSMUL, FDMUL, FDIV, FSDIV, FDDIV, FSQRT, FSSQRT, FDSQRT,
  FABS, FSABS, FDABS, FNEG, FSNEG, FDNEG, FCMP, FTST, FMOVE to and from FPCR, FPSR, FPIAR,
  FMOVEM (data and control registers), FBcc, FDBcc, FScc, FTRAPcc, FNOP, FSAVE, FRESTORE.
- Not in hardware (trap on a bare 68040; emulated by the support package on every real system):
  FACOS, FASIN, FATAN, FATANH, FCOS, FCOSH, FETOX, FETOXM1, FGETEXP, FGETMAN, FINT, FINTRZ,
  FLOG10, FLOG2, FLOGN, FLOGNP1, FMOD, FMOVECR, FREM, FSCALE, FSGLDIV, FSGLMUL, FSIN, FSINCOS,
  FSINH, FTAN, FTANH, FTENTOX, FTWOTOX; packed decimal operands; denormal and unnormal extended
  operands (unimplemented data type, vector 55).
- Opmode field (command word bits 6..0): FMOVE 0x00, FINT 0x01, FSINH 0x02, FINTRZ 0x03,
  FSQRT 0x04, FLOGNP1 0x06, FETOXM1 0x08, FTANH 0x09, FATAN 0x0A, FASIN 0x0C, FATANH 0x0D,
  FSIN 0x0E, FTAN 0x0F, FETOX 0x10, FTWOTOX 0x11, FTENTOX 0x12, FLOGN 0x14, FLOG10 0x15,
  FLOG2 0x16, FABS 0x18, FCOSH 0x19, FNEG 0x1A, FACOS 0x1C, FCOS 0x1D, FGETEXP 0x1E,
  FGETMAN 0x1F, FDIV 0x20, FMOD 0x21, FADD 0x22, FMUL 0x23, FSGLDIV 0x24, FREM 0x25,
  FSCALE 0x26, FSGLMUL 0x27, FSUB 0x28, FSINCOS 0x30..0x37, FCMP 0x38, FTST 0x3A, FSMOVE 0x40,
  FSSQRT 0x41, FDMOVE 0x44, FDSQRT 0x45, FSABS 0x58, FSNEG 0x5A, FDABS 0x5C, FDNEG 0x5E,
  FSDIV 0x60, FSADD 0x62, FSMUL 0x63, FDDIV 0x64, FDADD 0x66, FDMUL 0x67, FSSUB 0x68,
  FDSUB 0x6C. Cross-check every code against the PRM table before freezing the tables (both the
  CPU and the assembler use them, see 10.9).
- FMOVECR ROM offsets: 0x00 pi, 0x0B log10(2), 0x0C e, 0x0D log2(e), 0x0E log10(e), 0x0F 0.0,
  0x30 ln(2), 0x31 ln(10), 0x32..0x3F 10^0, 10^1, 10^2, 10^4, 10^8, 10^16, 10^32, 10^64,
  10^128, 10^256, 10^512, 10^1024, 10^2048, 10^4096. Other offsets return +0.0 (the real ROM
  holds undocumented values there; documented deviation).
- FSAVE frames on the 68040 (FPSP040 `fpsp.h`): first long = version byte, size byte, zero word.
  Null frame 0x0000_0000 (4 bytes, FPU in reset state); idle frame version 0x41, size 0, 4 bytes;
  unimplemented-instruction frame 0x41 with size 0x30 (52 bytes in total); busy frame size 0x60
  (100 bytes). Version 0x40 frames (original mask set) have sizes 4, 44 and 100. Verified: UM040
  figure 9-10 (idle 4, unimplemented instruction 0x41 0x30 = 52 bytes, busy 0x41 0x60 = 100
  bytes); the 44-byte 0x40 frame comes from the support package header (`UNIMP_40_SIZE`), not
  the manual. The emulated FPU writes only 0x41 frames and accepts both versions on FRESTORE.
- FP exceptions on the 68040: BSUN is pre-instruction (format 0, PC = the FP instruction); the
  arithmetic ones are post-instruction (format 3, address = the effective address, PC = next)
  when enabled in FPCR. Ordering when several are pending, as built (not checked against a
  primary source): BSUN, SNAN, OPERR, OVFL, UNFL, DZ, INEX2, INEX1.
- FSxxx and FDxxx forms round the result to single or double whatever FPCR says. Decided (FPU
  owner): single or double rounding precision, from FPCR or from an FSxxx or FDxxx form, also
  narrows the exponent range to single or double, so overflow and underflow happen at those
  formats' thresholds.

### 10.3 Integer core design

#### 10.3.1 One class, parameterised

`M68kCpu implements Cpu`, constructed as `M68kCpu(CpuHost host, M68kOptions options,
M68kFpuUnit.Factory fpu)`: `M68kOptions` (10.9) holds the model and the strict FPU flag; `fpu`
is null on the 68000 and builds the FPU on the 68040. Reasons:

- The 68040 integer ISA is a superset of the 68000 with a short list of behaviour differences
  (address error rules, address mask, frame formats, stack pointers, MOVE from SR privilege,
  extension word decoding, timing). Two classes would duplicate the 90% that is identical:
  every flag rule, EA mode, MOVEM, BCD, shifts, exception entry.
- Differences become `final` instance fields read in the hot loop (`addrMask`, `is040`,
  `misalignedOk`), which the JIT treats as constants per object. Opcode legality differs
  through the per-model table, at zero runtime cost.
- Timing differs completely, so it is table driven: the 68000 looks up EA and base costs, the
  68040 uses its own table (10.3.9). Both tables are arrays chosen in the constructor.
- The FPU is a separate class (10.4) because it is independent work, large, and absent on the
  68000.

Rejected: an abstract base with two subclasses. Virtual calls in the hot path either stay
monomorphic (no gain over final fields) or turn megamorphic in a JVM that runs both models.

#### 10.3.2 Decode strategy

- `M68kOpTable.build(model)` returns a `short[65536]` mapping each opcode word to an op id
  (dense, about 200 ids: `OP_MOVE_B`, `OP_MOVE_W`, `OP_MOVEQ`, `OP_ADD_EA_DN_W`, `OP_BCC`,
  ... and `OP_ILLEGAL`, `OP_LINE_A`, `OP_LINE_F`). Built once per model into a static field.
  The builder enumerates every instruction pattern with its legal EA modes and sizes, so an
  illegal EA (for example `move.w d0,#1` or `lea d0,a0`) maps to `OP_ILLEGAL` and the executor
  never checks legality. The builder asserts no opcode is claimed twice.
- The loop fetches the opcode word and does `switch (opTable[op])` over contiguous ids, which the
  JIT compiles to a jump table. Each case calls a private method or inlines a short body.
- Hot cases get specialised fast paths in the switch: MOVE with Dn/An/(An)/(An)+/-(An)/d16(An)
  on both sides, MOVEQ, ADDQ/SUBQ to Dn and An, Bcc/BRA/BSR, DBcc, CMP/TST with Dn, LEA, JSR,
  RTS. The general EA path handles the rest.
- Do not use lambdas or interface handlers per opcode (megamorphic calls, slower than the
  switch).
- No decode cache in v1 (9). The table plus the switch is the decoder.

#### 10.3.3 Effective addresses

- `ea(mode, reg, size)` computes an address for memory modes; register modes are handled by
  callers. Helpers: `readEa(mode, reg, size)`, `writeEa`, and a read-modify-write pair that
  computes the address once (`eaAddr` then `read`/`write` at that address), so (An)+ and -(An)
  update An exactly once.
- -(An) and (An)+ with A7 and byte size move by 2 (keeps SP even). Same on both models.
- 68000 brief extension: D/A 15, register 14..12, W/L 11 (index is sign-extended word or long),
  bits 10..8 ignored, displacement 7..0 signed.
- 68040 extension: bit 8 selects brief (0) or full (1). Brief adds scale bits 10..9. Full format:
  BS 7, IS 6, BD size 5..4, bit 3 must be 0, I/IS 2..0. The address is
  `base + bd + index * scale` (no memory indirect), or memory indirect
  `mem32[base + bd (+ index)] (+ index) + od`. PC-relative full forms use the address of the
  first extension word as base, like the brief form. Reserved encodings (BD size 00, I/IS 100,
  IS = 1 with I/IS 101..111, bit 3 set) go to the illegal instruction exception.
- PC-relative base: the address of the extension word (instruction address + 2 for the first
  operand). For a second operand (MOVE destination cannot be PC-relative; CMPI/TST on the 040
  can be), use the extension word's own address.
- Immediate fetch: byte immediates are the low byte of a word; long is two words.
- Address arithmetic is 32-bit; the 68000 masks only at the bus (10.3.5).

#### 10.3.4 Condition codes, exact rules per instruction

X is unchanged unless stated. "NZ" means N = result sign at the operation size, Z = result zero.

- MOVE, MOVEQ, MOVE to (An) etc., AND, ANDI, OR, ORI, EOR, EORI, NOT, TST, EXT, EXTB, SWAP, CLR,
  MULU.W, MULS.W, TAS (on the byte before bit 7 is set): NZ, V = 0, C = 0.
- ADD, ADDI, ADDQ (not to An): X = C = carry out, V = (S and D same sign, result differs), NZ.
- SUB, SUBI, SUBQ (not to An): X = C = borrow, V = (S and D signs differ, result sign differs
  from D), NZ.
- CMP, CMPI, CMPA (32-bit compare after sign-extending a word source), CMPM: as SUB, X
  unchanged.
- ADDA, SUBA, ADDQ and SUBQ to An (always 32-bit), MOVEA, LEA, PEA, EXG, LINK, UNLK, Bcc, DBcc,
  Scc, JMP, JSR, RTS, NOP, MOVEM, MOVEP, MOVE USP, MOVEC, MOVES, PACK, UNPK, MOVE16: no flags.
- ADDX, SUBX, NEGX: X = C = carry or borrow, V as ADD or SUB, N, and Z cleared if the result is
  nonzero, unchanged otherwise (multi-precision chains).
- NEG: X = C = (result != 0), V = (operand was the most negative value), NZ.
- ABCD, SBCD, NBCD: X = C = decimal carry or borrow; Z cleared if the result is nonzero, else
  unchanged; N and V are documented as undefined. Rule here (the behaviour reported for the real
  68000; fixed and documented, checked by hand-worked tests): N = bit 7 of the corrected result;
  V for ABCD = (bit 7 of the uncorrected binary sum is 0 and bit 7 of the corrected result is
  1); for SBCD and NBCD V = (bit 7 of the uncorrected difference is 1 and of the corrected
  result is 0). Same rule on the 68040. Digit correction follows the hardware-verified model of
  flamewing's 68k-bcd-verifier (bcd-emul.cc), which also fixes the results for invalid digits:
  ABCD adds 6 to a digit when its binary add carried or the uncorrected sum shows a decimal
  carry (low digit above 9; whole byte at least 0x9A for the high digit), and C is the binary
  carry or a carry out of the correction; SBCD and NBCD subtract 6 from a digit only when its
  binary subtract borrowed, and C is the binary borrow or a borrow out of the correction. So
  0x8F + 0x05 gives 0x9A with C = 0, and 0x0A - 0x00 gives 0x0A with C = 0.
- Shifts and rotates, count n (immediate 1..8, 0 encoding means 8; register count mod 64):
  - ASL: X = C = last bit shifted out; V = 1 if the sign bit changed at any point during the
    shift; NZ. ASR: same with V = 0.
  - LSL, LSR: X = C = last bit out, V = 0, NZ.
  - ROL, ROR: C = last bit out, X unchanged, V = 0, NZ.
  - ROXL, ROXR: rotate through X; X = C = last bit out; V = 0, NZ.
  - Count 0: C = 0 for all, except ROXL/ROXR where C = X; X unchanged; NZ from the operand;
    V = 0.
  - Memory forms shift a word by 1.
- BTST, BCHG, BCLR, BSET: Z = !(old bit), nothing else. Bit number mod 32 for Dn, mod 8 for
  memory.
- MULU.W, MULS.W: 32-bit result, NZ from it, V = C = 0.
- MULU.L, MULS.L (68040): 32-bit form: NZ of the low 32 bits, V = 1 if the 64-bit product does
  not fit 32 bits (signed for MULS), C = 0. 64-bit form (Dh:Dl): NZ of the 64-bit result, V = 0,
  C = 0.
- DIVU.W, DIVS.W: C = 0 always. Overflow (quotient does not fit 16 bits, signed for DIVS):
  V = 1, destination unchanged; N and Z are undefined on the chip (it aborts the algorithm
  early and leaves intermediate state); here N and Z keep their old values (documented
  deviation). No overflow: quotient in the low word, remainder (sign of dividend) in
  the high word, NZ from the 16-bit quotient, V = 0.
- DIVU.L, DIVS.L, DIVUL.L, DIVSL.L (68040): 32/32 to 32q (Dq) with 32r (Dr, if Dr != Dq), and
  64/32 (Dr:Dq dividend) to 32r:32q. Overflow: V = 1, registers unchanged, C = 0. Otherwise
  NZ of the quotient, V = C = 0. If Dr == Dq in the remainder forms, the quotient is kept
  (as built; not checked against a primary source).
- Divide by zero (all forms): zero divide exception; C = 0, N, Z and V keep their old values
  (undefined on the chip; documented); the destination is unchanged.
- CHK, CHK.L: trap if Dn < 0 (N = 1) or Dn > bound (N = 0); Z, V, C are undefined on the chip
  and keep their old values here (documented).
- CHK2, CMP2: read lower then upper bound at the EA (byte, word or long). For a data register
  the bounds and register are compared at the operation size; for an address register the
  bounds are sign-extended to 32 bits and all 32 bits compared. Z = 1 if Rn equals either bound;
  C = 1 if Rn is out of bounds. The comparison is signed or unsigned depending on the bounds:
  treat as signed when lower > upper unsigned (the usual emulator rule, as built; not checked
  against UM040). CHK2 traps (vector 6) when C = 1.
- CAS: compare Dc with the operand (CMP flags at the size); if equal write Du to the operand,
  else load the operand into Dc. CAS2: two compares (both must be equal) and two writes; CMP
  flags of the first compare that differs, else of the second. No bus lock needed (one CPU, no
  DMA during an instruction).
- Bitfield instructions (BFTST, BFEXTU, BFEXTS, BFCHG, BFCLR, BFSET, BFFFO, BFINS): offset
  0..31 immediate or signed 32-bit from Dn; width 1..32 (0 means 32) immediate or Dn mod 32.
  Register operand: the field wraps around bit 0 to bit 31. Memory operand: base byte address
  + floor(offset / 8), field may span 5 bytes; read and write only the bytes the field touches.
  N = the field's most significant bit, Z = field == 0 (both before modification; BFINS: of the
  inserted value), V = C = 0. BFFFO: Dn = offset + index of the first set bit from the field's
  MSB, or offset + width if none.
- MOVE to CCR, ANDI/ORI/EORI to CCR: as written. RTR: CCR from the stack. MOVE to SR, ANDI/ORI/
  EORI to SR, RTE, STOP: privileged; may change S and M, which switches A7 (10.3.6).
- Scc, TRAPcc, Bcc, DBcc test the CCR without changing it.

#### 10.3.5 Memory access and the bus

- Byte order: the machine sets the bus to big-endian from `CpuModel.byteOrder()`, so RAM, ROM and
  VRAM arrays hold guest bytes in guest order. Fast path uses `MethodHandles.byteArrayViewVarHandle`
  with `ByteOrder.BIG_ENDIAN` (`BE16`, `BE32`), like `Rv32Cpu` does with `LE32`.
- Every address goes through `a & addrMask` (0x00FF_FFFF on the 68000, -1 on the 68040).
- Same fast-path order as `Rv32Cpu`: ROM range, then RAM, then VRAM, then the slow path through
  `Bus`. Reload the arrays and bases at the start of every `run` (they change with the ROM
  overlay). VRAM stores set the dirty page bits.
- 68000 alignment: a word or long access with bit 0 set raises the address error before any
  access. 68040: misaligned memory accesses are performed (two array reads at most; the fast
  path can still use the VarHandles, which allow any offset). A misaligned device access throws
  `BusFault` from the bus and becomes an access error (documented; the real chip would split it).
- Long accesses to devices are one `read32`/`write32`, never split (1.2), on both CPUs.
- Device register byte lanes: devices are value-addressed (1.2), so on a big-endian CPU
  `move.b` at a 32-bit register's offset +0 returns bits 7..0, not 31..24 as memory would. Guest
  code reads and writes 32-bit registers with `.l`, and 8-bit UART registers with `.b` at their
  own offsets. Document this in the guest-facing notes and use it in the boot ROM.
- Slow-path accesses cost `MMIO_EXTRA_CYCLES` (2) on top of the instruction and re-read the
  horizon afterwards (`limit = clock.horizon()`), then re-check interrupts, exactly as `Rv32Cpu`.
  Before a slow-path access: `clock.advanceTo(cycles)`.
- `BusFault` on the 68000: bus error (vector 2), group 0 frame with the access address and
  function code. On the 68040: access error, format 7 frame.
- Instruction fetch: word at a time; the hot loop reads the opcode from the fast arrays and
  extension words through a small inline `fetch16()` that tries the same array first.
- MOVEM, MOVEP, MOVE16 and CAS2 access several locations; if one faults midway, earlier writes
  stay done (as on the real chip for MOVEM).

#### 10.3.6 Stack pointers and privilege

- Keep `a[7]` as the active SP and store the inactive ones in `usp`, `ssp` (68000) or `usp`,
  `isp`, `msp` (68040). Any write to SR that changes S or M calls `switchStack(oldS, oldM, newS,
  newM)`, which saves `a[7]` into the old slot and loads the new one. Exception entry sets S
  (and keeps M) through the same routine.
- Privileged instructions in user mode raise privilege violation (vector 8, PC = the
  instruction). 68000: MOVE to SR, ANDI/ORI/EORI to SR, MOVE USP, RESET, RTE, STOP. 68040 adds
  MOVE from SR, MOVEC, MOVES, CINV, CPUSH, PFLUSH*, PTEST*, FSAVE, FRESTORE.

#### 10.3.7 Exceptions, interrupts, trace

- `exception(vector, frameFormat, pcToStack, extra...)` builds the frame: copy SR, set S, clear T
  (T1 and T0), switch stacks, push the frame (68000: 3-word or group 0 7-word; 68040: by format),
  read the vector from `vbr + vector * 4` (VBR is 0 on the 68000), jump. A fault while pushing
  or reading the vector: 68000 double fault halts; 68040 also halts (the real chip halts on a
  double access error during exception processing). An odd handler address on the 68000 raises
  an address error at the handler fetch (a second group 0 fault, so halt if it happens while
  building a group 0 frame).
- Interrupt pins: `setIrq(pin, level)` for pins 1..7 keeps a bit mask `pins` and recomputes
  `ipl = 31 - numberOfLeadingZeros(pins)` (0 when none). A rising edge to level 7 (ipl becomes 7
  from below 7) sets `nmiPending`. `irqPending = nmiPending || ipl > mask` is recomputed on every
  pin change and every SR write; the loop checks the field after each instruction (re-entrant
  pins: never cache it in a local across a slow-path access, 1.3).
- Taking an interrupt at level L: frame (68000 3 words; 68040 format 0, plus the format 1
  throwaway when M was set), vector 24 + L (autovector; the machine has no vectored acknowledge,
  2.3), mask = L, S = 1, T = 0, 44 cycles on the 68000 (68040: fixed cost, 10.3.9). Clear
  `nmiPending` when taking level 7.
- Priority after each instruction: pending group 0 (taken immediately during the instruction),
  then trace, then interrupt. Illegal and privilege are detected at decode.
- Trace: the T state at the start of the instruction decides; after the instruction (and any
  group 2 exception frame), take trace with PC = the next instruction to run. On the 68040 T0
  traces only change-of-flow instructions. STOP with T set: trace at once (68000 behaviour).
- STOP: load SR (privileged; a new SR with S = 0 is allowed and switches stacks), set
  `stopped = true`, return `Exit.WAITING` at the end of the segment. `run` on a stopped CPU
  returns WAITING unless `irqPending` (or trace) holds; then it takes the interrupt.
- RESET: privileged; `clock.advanceTo(cycles)`, call `host.resetDevices().run()` (null means
  no-op), charge 132 (68000) or 520 (68040) cycles, set `limit = clock.horizon()`. The machine
  performs the device reset after `run` returns (10.8.3).
- Line A (0xAxxx): vector 10 on both. Line F: on the 68000 vector 11; on the 68040, coprocessor
  id 1 (bits 11..9 = 001) goes to the FPU, the CINV/CPUSH/PFLUSH/PTEST/MOVE16 encodings
  (0xF4xx, 0xF5xx, 0xF6xx) to the core, everything else vector 11.
- ILLEGAL (0x4AFC) vector 4; unlisted opcodes vector 4.

#### 10.3.8 Reset

- `reset()`: registers to 0 (D0..D7, A0..A6 are undefined on the chip; zero is deterministic),
  SR = 0x2700 (S = 1, mask 7, T = 0, M = 0), VBR = 0, CACR = 0, FPU to its null state, `stopped
  = false`, `halted = false`, and `resetPending = true`. It does not touch memory.
- First `run` with `resetPending`: read SSP from address 0 and PC from address 4 through the bus
  (slow path is fine; the ROM overlay answers there), set A7 (68000 SSP; 68040 ISP), charge the
  reset sequence cycles, clear `resetPending`. A fault here halts with "reset vector unreadable".
  Reason: `Machine` creates the CPU before `powerOn` turns the overlay on, and the real chip
  fetches its vectors as its first bus cycles after reset.

#### 10.3.9 Timing models

- 68000: per op id, a base cost plus the EA cost of each operand from 10.2.2's table, plus the
  data-dependent parts (shift count, MULx bit counts, DIVx algorithm, MOVEM 4 or 8 per register,
  DBcc outcome, Bcc taken or not). MOVE uses the full matrix. Tables live in `M68kTiming` as
  `int[]` arrays indexed by op id and EA mode, copied from the reference tables by hand and
  unit-tested with hand-computed totals (10.12.1).
- 68040: a deliberately simple model (documented as approximate; the real chip overlaps
  pipelines and depends on cache hits): 1 cycle for register-only integer ops, +1 per memory
  operand read, +1 per write, taken branch 2 (not taken 1), JSR/BSR 3, RTS 3, MULx.W 16 and
  MULx.L 20, DIVx.W 27 and DIVx.L 44, exception entry 16, RTE 13, FPU: FMOVE 2, FADD/FSUB 3,
  FMUL 5, FDIV 38, FSQRT 103, emulated transcendentals 100 (stand-in for the support package),
  plus `MMIO_EXTRA_CYCLES` for device accesses. As built: these numbers, with "+1 per memory
  operand" applied as base + 1 per data access (10.3.10); not reconciled with UM040 chapter 10.
- Host cost: a busy 68040 at 25 MHz at about 1.5 cycles per instruction asks for about
  16 M instructions per second, 830 k per tick, more than a 2 ms budget covers (1.4): expect
  time dilation on busy servers, as for the R3000. A 68000 at 8 MHz averages about 1 M
  instructions per second and runs at full speed. Measured: see "Implementation status".

#### 10.3.10 Integer core as built (agent A's decisions and deviations)

- Decode: `M68kOpTable` builds each model's 65536-entry table from one decision tree over the
  opcode bits (not a list of patterns), with 131 op ids; illegal EA combinations map to the
  illegal op id, so the executor never checks legality.
- 68040 timing: the base cost of 10.3.9 plus 1 cycle per data memory access (reads and writes),
  plus the fixed costs listed there.
- The 68000's CLR, Scc and MOVE from SR do not read their destination before writing it (the
  real chip does a dummy read). Only visible to a device with read side effects.
- Undefined flags, fixed rules: DIV overflow and divide by zero keep N and Z and clear C (V is
  set on overflow, kept on divide by zero); CHK keeps Z, V and C; CHK2 and CMP2 keep N and V.
- Illegal, privilege violation, line A and line F do not count as retired instructions and are
  not traced. The same holds for every format 0 exception the FPU raises before its instruction
  executes (line F, BSUN, FRESTORE format error, the pre-instruction unimplemented data type
  trap): `M68kFpuHost.exception` with format 0 marks the instruction as faulted.
- 68000 bus and address errors on an operand read through a PC-relative mode report the
  program space function code (6 supervisor, 2 user; PRM 2.2), like an instruction fetch.
- 68040 address error: format 2 frame, PC = the instruction that jumped to the odd address,
  address field = the odd target with bit 0 cleared (UM040 8.4; bit 0 clearing added at
  integration, with a test).
- No 68000 prefetch model (decision 11): a group 0 frame stacks the instruction address + 2, and
  code that modifies the next 4 bytes sees its own change at once.
- Trace is taken inside the same step as its instruction, so a run never ends between them and
  saved state needs no pending-trace field.

### 10.4 FPU design

- `M68kFpu` holds FP0..FP7 as two arrays (`int[] fpSe` sign and 15-bit exponent, `long[]
  fpMant` 64-bit mantissa with explicit integer bit), FPCR, FPSR, FPIAR, and a `nullState` flag
  (FSAVE null frame until the first FP instruction after reset or a null FRESTORE).
- Host interface `M68kFpuHost` (frozen, 10.9; implemented by `M68kCpu`): instruction words,
  the instruction address (FPIAR) and PC, D and A registers, effective addresses with their side
  effects (one call per operand, whole transfer size for FMOVEM and FSAVE), data reads and writes
  of 1, 2 and 4 bytes, and `exception(vector, format, stackedPc, address)`. The FPU never touches
  the bus directly.
- Entry points, `M68kFpuUnit` (frozen, 10.9; implemented by `M68kFpu`): `general(opword)` for
  0xF200..0xF23F, `condition(predicate)` with which the core itself performs FBcc (.W and .L),
  FScc, FDBcc and FTRAPcc, `save(opword)` and `restore(opword)` (privilege checked by the core),
  `reset`, `saveState`, `loadState`. Each returns the cycles it costs.
- Soft-float type `Fx80` (static methods over (se, mant) pairs and a reusable result holder, no
  allocation in the hot path): add, sub, mul (128-bit product from two `Math.multiplyHigh`
  calls with unsigned correction; JDK 17 has no `unsignedMultiplyHigh`), div (128 by 64 bit long
  division, 64 iterations or Knuth D with 32-bit digits), sqrt (bitwise integer square root of a
  128-bit radicand), round to precision (64, 53 or 24 mantissa bits) with guard, round and
  sticky bits in all four modes, overflow and underflow with denormal results, conversions to
  and from byte, word, long (with rounding per FPCR and OPERR on overflow, saturating as the
  chip does), single, double (exact bit patterns), compare (unordered aware), and NaN rules
  (quiet a signalling NaN and raise SNAN; the default NaN is 0x7FFF_FFFF_FFFF_FFFF_FFFF;
  operations with two NaNs propagate the destination's: decided, see "FPU as built" below).
- Why soft-float and not `double`: `double` has a 53-bit mantissa and an 11-bit exponent, so it
  cannot hold extended values, cannot round in extended precision, cannot represent the
  extended exponent range, and cannot produce exact FPSR inexact, underflow and overflow flags.
  FMOVE.X round trips, FMOVEM saves, and integer-to-float conversions of 64-bit-ish values would
  all be visibly wrong. The basic ops are a few hundred lines each and are testable against
  Berkeley TestFloat (10.12.2).
- Transcendentals and other support-package instructions: convert the operand to `double`
  (exact if it fits, else rounded, with range handling for huge or tiny values), compute with
  `StrictMath` (deterministic on every host, unlike `Math`), convert back and round to the
  destination precision; set INEX2 whenever the result is not exact. FSINCOS writes two
  registers. FMOD and FREM are exact (integer remainder algorithm on the mantissas, which also
  yields the quotient byte), FINT and FINTRZ exact, FGETEXP, FGETMAN, FSCALE exact, FSGLDIV and
  FSGLMUL exact per their definition (single-precision mantissa, extended exponent). Only the
  transcendentals are approximate (documented: correct to about 53 bits, not 64).
- Packed decimal (FMOVE.P in and out): convert through `BigDecimal` with exact decimal
  arithmetic and the k-factor rules; rare and slow, fine.
- Strict FPU (user decision: selectable in game). `MachineConfig.strictFpu` is a machine
  configuration option of the 68040, default off; it reaches the CPU as `CpuHost.strictFpu`,
  then `M68kOptions.strictFpu` and the `strict` argument of `M68kFpuUnit.Factory`. It is part of
  the machine's saved-state config check (1.5) and of the CPU payload (10.6). On: the
  unimplemented-on-040 instructions take vector 11 (format 2, 10.2.3) and leave the FSAVE frame
  as the unimplemented-instruction frame; packed and denormal operands take vector 55 (format 0
  for an input operand, format 3 for FMOVE out, UM040 9.6.2). Off (default): all emulated. Reasoning for the default: every real
  68040 system ran with the support package installed, so "emulate" is what software on a real
  040 saw (only timing differs); players writing assembly get FSIN without having to supply a
  40 KB package the boot ROM cannot carry; the option keeps hardware fidelity available.
- FSAVE: privileged; writes the null frame when `nullState`, else the idle frame 0x4100_0000.
  Because the emulated FPU finishes every instruction, it never produces busy or exceptional
  frames from arithmetic exceptions (documented: an FP exception handler that inspects the
  exceptional operand in a busy frame will not find it). The strict FPU traps are the exception:
  the next FSAVE after one writes the frame its handler expects (below), with zero contents.
- FRESTORE: privileged; reads the first long: 0 resets the FPU (FP0..FP7 to non-signalling NaN,
  FPCR, FPSR, FPIAR to 0, `nullState`); version 0x40 or 0x41 with a known size is consumed
  (size + 4 bytes, postincrement form advances by that) and leaves the state as is; anything
  else is a format error (vector 14).
- FPU exceptions: after each arithmetic instruction, if (exception status & FPCR enables) != 0,
  take the highest-priority enabled one as a post-instruction exception (format 3). BSUN (an
  unordered compare in a signalling-predicate FBcc, FScc, FDBcc, FTRAPcc) is pre-instruction
  with format 0. FBcc conditions: 32 predicates (bits 5..0), bit 4 set means "signal on
  unordered" (sets BSUN when NAN is set).
- 68000: the FPU object is null and every line F opcode takes vector 11.

#### 10.4.1 FPU as built (agent B's decisions)

- FMOVECR follows the support package's routine and its per-rounding-mode tables bit for bit
  (including their few last-bit differences from exact rounding and the INEX2 quirks), rather
  than exact constants; undocumented offsets give +0.0.
- When both operands are NaNs, the destination's NaN (quieted) is the result.
- Tininess is detected before rounding; UNFL is set when the result is tiny and inexact, or
  tiny with the underflow trap enabled.
- On an enabled SNAN, OPERR or DZ trap the destination is not written; OVFL, UNFL and INEX
  traps see the default result in the destination.
- Strict mode: an unimplemented-on-040 instruction takes vector 11 with a format 2 frame (PC =
  the next instruction, address = the effective address, 0 for a register operand) after the
  effective address and its side effects are done; a packed, denormal or unnormal operand takes
  vector 55 with the frame UM040 9.6.2 gives: an input operand (opclass 000 and 010) is a
  pre-instruction exception, format 0 with PC = the instruction; FMOVE out (opclass 011) is a
  post-instruction exception, format 3 with PC = the next instruction and address = the
  effective address. Both come after the effective address side effects. The pre-instruction
  form does not retire and is not traced (10.3.10). An unimplemented instruction with an
  unsupported operand takes vector 11 (UM040 9.6.1: it takes precedence). The next FSAVE writes
  the 52-byte 0x4130 unimplemented-instruction frame after vector 11, or the 100-byte 0x4160
  busy frame after vector 55.
- An empty FMOVEM control register list and an undefined opmode take vector 11 (line F).
- FCMP of equal operands gives Z plus N = the destination's sign; an unordered compare gives NAN
  plus N from the NaN's sign. FTST and FCMP update FPIAR.
- Packed decimal input is correctly rounded from the exact decimal value (it may differ in the
  last bit from a real 68040's support package).
- Transcendentals go through `StrictMath` in double precision (10.4).
- Cycle costs: FMOVE 2, FADD 3, FMUL 5, FDIV 38, FSQRT 103, support-package instructions 100,
  FMOVEM 2 + 2 per register.
- Saved state includes the pending FSAVE frame kind and the strict flag.
- FSAVE sizes: idle 4, unimplemented instruction 52 (44 for a version 0x40 frame on
  FRESTORE), busy 100 (10.2.4, verified).
- Single and double rounding precision also narrow the exponent range (10.2.4).

### 10.5 MMU and cache instructions on the 68040

Superseded by 12.2 for everything but the cache lines (CINV, CPUSH, CACR), which still hold. In
particular the "store as written" rules no longer apply: TC keeps only E and P (mask 0xC000,
UM040 3.1.2), the TT registers keep only their defined bits (mask 0xFFFF_E364, UM040 3.1.3),
TC.E can be set, the TT registers translate, and PTEST and PFLUSH work (12.2.4, 12.14.1). The
text below is the pre-MMU design, kept for the record.

- TC, ITT0, ITT1, DTT0, DTT1, URP, SRP, MMUSR are plain 32-bit registers through MOVEC. TC bit 15
  (E, enable) is forced to 0 on write, so software that probes for paging sees it cannot be
  turned on; other bits store as written. Transparent translation registers have no effect.
- PFLUSH, PFLUSHN, PFLUSHA, PFLUSHAN: privileged, no effect.
- PTESTR, PTESTW: privileged; MMUSR = (address & 0xFFFF_F000) | T (bit 1) | R (bit 0), the
  report of a transparent-translation hit, since every address maps to itself.
- CINV, CPUSH (all scopes and caches): privileged, no effect (no caches are modelled; a decode
  cache added later must be invalidated by ordinary stores anyway, so these stay no-ops).
- CACR: DE and IE stored and readable, no effect.

### 10.6 Save state (`saveState`/`loadState`, contract of 1.5)

Format: version int first, then the options (model name, strict FPU; loading into a CPU built
differently is a `StateFormatException`), then:

- D0..D7, A0..A7 (A7 as the active SP), PC, SR (16 bits, including T1, T0, M).
- Inactive stack pointers: USP and SSP (68000); USP, ISP, MSP (68040).
- 68040 control registers: VBR, CACR, SFC, DFC, TC, ITT0, ITT1, DTT0, DTT1, URP, SRP, MMUSR.
- Cycle count and instructions retired.
- Pin mask (`pins`), `nmiPending` (the level 7 edge already seen), so a restore does not invent
  or lose an edge.
- Run state: `stopped`, `resetPending`, `halted` with reason (nullable UTF via `StateIo`),
  pending trace (if an instruction finished with trace due and the segment ended before it was
  taken; better design: never end a run between an instruction and its trace, then this field is
  not needed; decide in the core and record it).
- FPU (68040): written by `M68kFpuUnit.saveState` inside the CPU payload: its own version,
  `nullState`, FPCR, FPSR, FPIAR, and FP0..FP7 as (16-bit sign and exponent, 64-bit mantissa)
  each, exactly the bits held (no conversion through `double`).
- Not state: the memory map arrays, `limit`, op tables, options objects.
- `irqPending` is derived; recompute it after load.
- The machine envelope already holds the ROM overlay flag (1.5) and, since the foundation step,
  the strict FPU flag. The integration step adds the pending device-reset request (10.8.3).

### 10.7 Assembler

#### 10.7.1 Fit with the shared framework

What already works unchanged:

- Mnemonics with size suffixes lex as one identifier (`move.l`, `bra.s`, `dc.w`), because `.` is
  an identifier character. Register forms with size suffix too (`d0.w`, `a1.l`).
- Statements whose head has no leading dot are INSTRUCTION statements handed to the backend, so
  `dc.b`, `dc.w`, `dc.l`, `ds.b`, `ds.w`, `ds.l`, `dcb.b`, `even` are handled by the backend's
  `size`/`encode` like instructions (they must also be allowed in data sections: the framework
  rejects INSTRUCTION in `.bss`, so `ds.*` in bss needs the framework to ask the backend first;
  see the changes below).
- `#` is punctuation when `LexerSyntax.hashImmediate` is set.
- Relaxation (`IsaBackend.relaxes() = true`): pass 1 repeats until sizes stop changing.
- Labels `name:`, numeric local labels, `.equ`, `.set`, `=`, all shared directives.

Framework changes, done in the foundation step (10.9) behind flags so RV32I is unchanged (its
`LexerSyntax` keeps every new flag off, through the old five-argument constructor):

- `LexerSyntax` gains `dollarHex` (`$1F` is 0x1F: `$` followed by a hex digit starts a number,
  otherwise `$` stays an identifier character), `percentBinary` (`%1010` is 10 in operand
  position; after a value `%` stays modulo), `floatLiterals` (`1.5`, `1.0e-3`, `2E10` become
  `AsmToken.Kind.FLOAT` tokens with the exact text; a number then ends at a `.` not followed by
  a digit, so `1000.w` and `$1000.w` are an integer then the identifier `.w`), `equKeyword`
  (`name equ expr` parses as `.equ name, expr`, not redefinable; `name set expr` as `.set`,
  redefinable; keywords in any case), `starColumnOneComment` (`*` in column 1 starts a comment
  line) and `starLocationCounter` (`*` in operand position is the location counter, as `.`).
- Operand position, for `%` and `*`: at the start of a statement, after punctuation other than
  `)` and `]`, after the statement's head identifier (its first identifier after any labels), or
  after an `equ` or `set` keyword. Anywhere else a value just ended and the character is an
  operator (`x%2`, `(*)*2`).
- Line comments for 68k: `;`, plus `*` in column 1. Not `|`: it stays the OR operator in
  expressions. The statement separator stays off.
- `IsaBackend.isDataStatement(Statement)` (default false): the framework asks it before the
  "instructions are not allowed in .bss" error; for 68k it is true for `ds.*`, `dcb.*`, `even`,
  `align` and `org`. Since the integration step a data statement may also shrink between
  relaxation passes (instructions still only grow), so a classic `org` or `align` after a
  branch that grew lands the code where `.org` and `.balign` do.
- `IsaBackend.autoAlignment(Statement)` (default 1, added at integration): the boundary a
  statement pads itself to inside its own size. Labels immediately before such a statement get
  the padded address. The 68k backend returns 2 for instructions and word or long data.
- `IsaBackend.wordBytes()` (default 4): the width of `.word`; the 68k backend returns 2.
- Not done: `@` octal, multi-character constants (`'ABCD'`), and labels without a colon in
  column 1 (classic style). 68k labels need a colon: the parser cannot tell a label from a
  mnemonic without asking the backend, and it is not worth a framework change now.
- Further framework changes are not the assembler owner's to make: they go through the
  orchestrator (10.10), which keeps every existing `asm/**` test green.

#### 10.7.2 Syntax

- Registers: `d0`..`d7`, `a0`..`a7`, `sp` (= a7), `pc`, `sr`, `ccr`, `usp`; 68040 adds `vbr`,
  `cacr`, `sfc`, `dfc`, `msp`, `isp`, `tc`, `itt0`, `itt1`, `dtt0`, `dtt1`, `mmusr`, `urp`, `srp`,
  `fp0`..`fp7`, `fpcr`, `fpsr`, `fpiar`, and `zpc`, `za0`..`za7`, `zd0`..`zd7` (suppressed base or
  index in full-format modes). Case-insensitive.
- Operands:
  - `Dn`, `An`, `(An)`, `(An)+`, `-(An)`.
  - `d16(An)` and `(d16,An)`; `d8(An,Xn.s*k)` and `(d8,An,Xn.s*k)` where `.s` is `.w` or `.l`
    (default `.w`) and `*k` is 1, 2, 4, 8 (68040 only for k != 1).
  - 68040: `(bd,An,Xn*k)` with a displacement too large for 8 bits picks the full format;
    `([bd,An],Xn*k,od)` post-indexed and `([bd,An,Xn*k],od)` pre-indexed memory indirect; omitted
    parts suppress (base, index) or are null (displacements).
  - Absolute: `expr`, `expr.w`, `expr.l`, `(expr).w`, `(expr).l`. Without a suffix: abs.W if the
    value is known in pass 1 and fits signed 16 bits (that is, 0..0x7FFF or 0xFFFF_8000..
    0xFFFF_FFFF as a 32-bit address), else abs.L, and relaxation may grow it (never shrink).
  - PC-relative: `label(pc)`, `(label,pc)`, `label(pc,Xn)`, `(label,pc,Xn*k)`; the displacement
    is `label - (address of the extension word)`.
  - Immediate `#expr`; FPU immediates `#1.5` (single, double, extended rounded exactly from the
    decimal text with `BigDecimal`, round to nearest even), and `#expr` integer for integer
    formats.
  - Register lists for MOVEM and FMOVEM: `d0-d3/a0/a2-a4`, `fp0-fp7`, `fpcr/fpsr/fpiar`; also a
    numeric mask `#$00FF`. The `-(An)` form reverses the mask bit order (encoder does it).
- Size: `.b`, `.w`, `.l`, plus `.s` (short branch) and FPU `.s .d .x .p`. Missing size defaults
  to `.w` for integer instructions (classic convention), `.x` for FPU register forms.
- Number syntax: `$FF`, `%1010`, `0x`, `0b`, decimal, character constants `'A'` and multi-char
  `'ABCD'` as a 32-bit value (classic; optional).

#### 10.7.3 Directives

- Backend (no dot): `dc.b` (numbers and strings, strings emit their bytes), `dc.w`, `dc.l`
  (big-endian, range checked signed or unsigned), `ds.b n`, `ds.w n`, `ds.l n` (zero-filled
  reservation), `dcb.b n,value` (and `.w`, `.l`), `even` (pad to even with 0), `align n` (n
  bytes, not a power of two; maps to `.balign`), `org` maps to `.org`, `end` ignored.
- `dc.w`, `dc.l`, `ds.w`, `ds.l`, `dcb.w`, `dcb.l` and every instruction auto-align to an even
  address with one zero pad byte when needed, and the pad is counted in the statement's size
  (the section offset's parity is known in pass 1 because sections are aligned to 16; for that
  the text base and the data base must be even, and an odd one is an error, "text base
  0x00001001 is not a multiple of 2", through `IsaBackend.baseAlignment()`). The pad follows the
  statement's offset in every relaxation pass: a statement that moves from an odd to an even
  offset loses its pad even though instructions otherwise only grow.
  Instructions get a warning when padded ("instruction at odd address, padded"). This avoids
  address errors from forgotten `even` after strings. A label just before a padded statement
  names the padded (even) address, also when statements that emit nothing (`.cpu`, an `equ`)
  stand between them; a label before `even` keeps the odd one.
- Shared dot-directives work as for RV32I. `.align n` means n bytes for this backend
  (`alignIsPowerOfTwo() = false`), as classic assemblers do. `.word` is 2 bytes in this backend,
  the classic 68k meaning (user decision; `wordBytes() = 2`); `.long` is 4. RV32I keeps its
  4-byte `.word`.
- `nopPadding()` returns 0x4E71; `instructionAlignment()` 2.
- `.cpu 68000` and `.cpu 68040` (backend directive) switch the target within a source, so one
  source can carry a 68040-only routine guarded by a runtime check. Default target comes from
  the entry point.

#### 10.7.4 Size selection and auto-optimisation

- Bcc, BRA, BSR without a size: start at `.s` (2 bytes) if the target is known and within
  -128..+127 (and not 0, which would mean `.w`, and not -1 on the 68040, which means `.l`); else
  `.w`; on the 68040, `.l` if out of 16-bit range. Forward targets start at `.s` and relaxation
  grows them. With an explicit `.s`/`.b`, `.w`, `.l` the size is fixed and range errors are
  reported ("branch target out of range (300 bytes, limit -128..+127 for .s)"). `.l` on the 68000
  target is an error ("bra.l needs a 68040").
- A `.s` branch to the next instruction (displacement 0) is an error with a suggestion to use
  `.w` (explicit) or silently becomes `.w` (implicit).
- DBcc, FBcc, FDBcc: fixed sizes (DBcc and FDBcc 16-bit; FBcc `.w` or `.l`, relaxed like Bcc).
- Absolute addresses: abs.W when known and fits, else abs.L (relaxable). PC-relative is never
  chosen implicitly (it changes semantics for position-independent data).
- `move.l #n,Dn` with n known in pass 1 and in -128..127: MOVEQ (identical flags). `add.x #n,ea`
  and `sub.x #n,ea` with n in 1..8: ADDQ/SUBQ (identical flags and, for An, identical result).
  Otherwise `add #n,Dn` encodes ADDI (or ADDA for An); `cmp #n,ea` encodes CMPI; `move #n,An`
  encodes MOVEA; `add ea,An` encodes ADDA. Forms with a forward-referenced immediate take the
  long form and stay there (no shrinking).
- Not optimised (flags would differ): `clr` versus `moveq #0`, `lea` versus `addq`, `tst` versus
  `cmp #0`.
- Explicit mnemonics (`moveq`, `addq`, `addi`, `adda`, `cmpi`, `cmpa`, `movea`) always encode
  exactly as written, with range errors.

#### 10.7.5 Target gating

- 68000 target rejects: every 68040-only instruction of 10.2.3 ("'bfextu' needs a 68040
  (assembling for the 68000)"), scaled index, full format and memory indirect EA, `bcc.l`,
  `tst An`, PC-relative CMPI and TST, `move ccr,ea`, `link.l`, `chk.l`, `extb`, all FPU and MMU
  and cache instructions, `movec` and its control registers.
- 68040 target rejects CALLM, RTM and the 68030 PMMU forms (PMOVE, PFLUSH with function-code
  operands) with "not on the 68040".
- Unimplemented-on-040 FPU instructions are accepted on the 68040 target: they are emulated by
  default and trap at run time only with the strict FPU option on (10.4).

#### 10.7.6 FPU mnemonics

`fmove`, `fsmove`, `fdmove`, `fmovem`, `fmovecr`, `fadd`, `fsadd`, `fdadd`, `fsub`, `fssub`,
`fdsub`, `fmul`, `fsmul`, `fdmul`, `fdiv`, `fsdiv`, `fddiv`, `fsqrt`, `fssqrt`, `fdsqrt`, `fabs`,
`fsabs`, `fdabs`, `fneg`, `fsneg`, `fdneg`, `fcmp`, `ftst`, `fint`, `fintrz`, `fmod`, `frem`,
`fscale`, `fsgldiv`, `fsglmul`, `fgetexp`, `fgetman`, every transcendental of 10.2.4, `fsincos
fpS,fpC:fpS` syntax, `fb<cc>`, `fdb<cc>`, `fs<cc>`, `ftrap<cc>` with the 32 predicate names
(`f`, `eq`, `ogt`, `oge`, `olt`, `ole`, `ogl`, `or`, `un`, `ueq`, `ugt`, `uge`, `ult`, `ule`, `ne`,
`t`, `sf`, `seq`, `gt`, `ge`, `lt`, `le`, `gl`, `gle`, `ngle`, `ngl`, `nle`, `nlt`, `nge`, `ngt`,
`sne`, `st`), `fnop`, `fsave`, `frestore`. Single-operand forms `fneg fp0` mean `fneg fp0,fp0`.

#### 10.7.7 Public API

`M68kAssembler.assemble(String source, AsmOptions options, M68kTarget target)` and the
`List<AsmSource>` overload, mirroring `Rv32Assembler`. `M68kTarget` is an enum (`M68000`,
`M68040`). Two backend singletons, one per target (backends hold no mutable state).

#### 10.7.8 Assembler as built (agent C's decisions)

- One backend instance per assembler call, not two singletons: the `.cpu` directive switches the
  target mid-source, which is state that lives for one run.
- A branch to a plain number is absolute (`bra $1000` branches to address 0x1000), as classic
  68k assemblers read it; RV32I keeps its PC-offset meaning (5.2).
- Numeric MOVEM and FMOVEM masks (`movem.l #$00FF,-(sp)`) are used exactly as written: the
  encoder does not reverse a numeric mask for `-(An)`; only register lists are reversed.
- `fmove.p` to memory without a k-factor uses `{#0}`.

### 10.8 The 68k machines

#### 10.8.1 Memory map (`machine/M68kMap.java`)

32-bit addresses; the 68000 sees the low 24 bits because it masks every access.

- 0x0000_0000..: RAM, from 64 KiB up to 14 MiB (68000; below 0xE0_0000) or 64 MiB (68040).
  The vector table lives at 0 (68000 always; 68040 at VBR, 0 after reset).
- 0x0000_0000..0x0003_FFFF while the ROM overlay is on: the boot ROM mirror (reads; writes there
  fault, ROM wins).
- 0xFFE0_0000 (68000: 0xE0_0000): VRAM, 512 KiB.
- Planned (11.1.1): VRAM becomes one 1.5 MiB range 0xFFD8_0000..0xFFEF_FFFF (display 2 at
  0xFFD8_0000, display 0 stays at 0xFFE0_0000, display 1 at 0xFFE8_0000), so the 68000's RAM
  limit drops to 13.5 MiB (0xD8_0000) and the 68040's rises to 128 MiB; new device windows +0x9000 COM3, +0xA000 floppy,
  +0xB000 network card, +0xC000 VGA display 1 control, +0xD000 VGA display 2 control.
- 0xFFF0_0000 (68000: 0xF0_0000): devices, 0x1000-byte windows: +0x0000 SysCon, +0x1000
  interrupt controller, +0x2000 COM1, +0x3000 COM2, +0x4000 PS/2, +0x5000 HDD, +0x6000 VGA
  control, +0x7000 interval timer, +0x8000 RTC. No CLINT (the 68k uses the interval timer,
  3.3).
- 0xFFFC_0000 (68000: 0xFC_0000): boot ROM, 256 KiB window, image zero-padded.
- Everything else unmapped (bus error or access error).
- The platform maps the bus at the CPU's own view: the 68000 machine maps at 24-bit addresses
  (0xF0_0000...), the 68040 machine at the 0xFFxx_xxxx addresses. Guest code written with the
  32-bit constants runs on both, because the 68000 masks 0xFFF0_0000 to 0xF0_0000. This lets
  one boot ROM and one set of guest equates serve both CPUs.
- SysCon reports RAM_BASE 0, VRAM_BASE as the 32-bit constant on both (0xFFE0_0000; the 68000
  masks it).
- Device DMA addresses (HDD DMA_ADDR) are bus addresses and go through the same 24 address lines
  on the 68000: the 68000 machine sets `SystemBus.setDmaAddressMask(0xFF_FFFF)`, so a READ to
  0xFFE0_0000 lands in VRAM on both machines. The HDD's register keeps the 32-bit value the
  guest wrote; other machines keep the full 32 bits.

#### 10.8.2 Interrupt wiring

- Pins 1..7 are IPL levels (1.3). `M68kPlatform.wirePins`: controller output k (1..7)
  drives pin k; output 0 also drives pin 4, so pin 4 = output 0 OR output 4. The platform keeps
  the two levels in a small object and calls `cpu.setIrq(4, out0 || out4)` on either change.
- Device lines as 3.2; the guest routes lines with ROUTE registers (reset: all to output
  0, so everything arrives at IPL 4 by default).
- Interrupt acknowledge: autovector only, so `CpuHost.interruptAck` stays null for 68k. The spec
  gap (it is null) only matters for the 8086.
- `anyPinAsserted`: any controller output asserted.

#### 10.8.3 RESET, ROM overlay and the changes to existing files

- `cpu/CpuModel.java`: `CPU_68040("68040", 25_000_000, 32, 32, BIG_ENDIAN, false)` exists since
  the foundation step; the integration step sets `emulated` to true for both 68k models.
- `cpu/CpuHost.java`: no signature change; the machine passes a real `resetDevices` runnable.
- `machine/Platform.java`: `forModel` returns `M68kPlatform.M68000` and `M68kPlatform.M68040`;
  add `default boolean romOverlayAtReset() { return false; }` and `default byte[] defaultBootRom()`
  (or a `BootRoms.forModel` helper, see below).
- `machine/Machine.java` (coordinate with the save-state owner, who is editing it now; do this
  after that work lands):
  - Construct `CpuHost` with `resetDevices = this::requestDeviceReset`, which sets a field and
    calls `clock.requestStopAt(clock.now())` (the CPU advanced the clock first).
  - `performRequests`: if a device reset was requested, reset every device (not the CPU, not
    RAM, not the overlay), call `platform.wirePins`, clear the flag. The RESET instruction does
    not re-enable the ROM overlay (it would hide the vector table under the running program);
    documented.
  - Act on `SysCon.romOverlayOffRequested()`: `board.memory().setRomOverlay(false)` when the
    platform uses the overlay (it is already a recorded request; today it is ignored).
  - `resetHardware` and `powerOn`: `board.memory().setRomOverlay(platform.romOverlayAtReset())`
    before `cpu.reset()` (1.5: reset and power-on turn the overlay back on).
  - Saved state: the pending device reset (a new field in the envelope, with a version bump;
    the overlay flag and the strict FPU flag are there already).
- `machine/MachineConfig.of`: pick the boot ROM by model (`BootRoms.image(cpu)`), not always the
  RV32I one.
- `machine/StandardCpus`: `case CPU_68000 -> new M68kCpu(host, M68kOptions.m68000(), null)`,
  `case CPU_68040 -> new M68kCpu(host, M68kOptions.m68040(host.strictFpu()), M68kFpu::new)`.
- `machine/M68kPlatform` (new): `validate` (clock: 68000 from 4 to 16.67 MHz, the real parts,
  items 8 and 12.5 MHz; 68040 from 25 to 40 MHz, the real parts, items 25 and 33 MHz; RAM 64 KiB
  to 14 MiB or 64 MiB; ROM at most 256 KiB; all limits in `M68kMap`; the strict FPU option is
  already limited to the 68040 by `MachineConfig`), `defaultResetPc` unused (reset vector),
  `build` (big-endian `SystemBus`, maps per
  10.8.1, overlay on), `wirePins`, `anyPinAsserted`, `romOverlayAtReset() = true`. One class with
  two instances, parameterised by model and base addresses.
- `Rv32Platform`: unchanged.

#### 10.8.4 Boot ROM (`machine/M68kBootRom.java`)

68k assembly as a Java text block, assembled once per target at 0xFFFC_0000 with
`M68kAssembler` (one source; a few lines of prologue differ per target and are concatenated in
Java, since the framework has no `.if`). Equivalent to the RV32I ROM (4.7):

1. ROM offset 0: initial SSP long (unused value, 0) and initial PC long = the home address of
   `_start` (0xFFFC_0008 or so), so the CPU leaves the overlay range with its first fetch.
2. `_start`, interrupts masked (SR = 0x2700 from reset), no stack use yet: write SysCon
   ROM_OVERLAY_OFF (`move.l d0,SYSCON+$24`), which stops the CPU after that instruction and
   unmaps the mirror. Then read RAM_SIZE (`move.l SYSCON+$08,d7`), set SP = RAM_SIZE (top of RAM,
   RAM_BASE is 0). 68040 prologue: clear VBR, CACR, TC; `fmove.l #0,fpcr`.
3. Install the vector table in RAM at 0: vector 0 and 1 unused; every vector 2..255 points to a
   per-vector stub in ROM (256 stubs of `bsr.w rom_trap`, 4 bytes each), so the handler can tell
   the vector from the return address on both CPUs (the 68000 frame has no vector number).
4. COM1 8N1 with FIFOs (byte writes to LCR and FCR), clear the VGA text screen (word writes of
   0x2007: character then attribute, big-endian order matches VGA's cell layout), cursor to
   (0, 0), banner to COM1 and VGA: "Create Flippies PC  68000  <n> KiB RAM" (or 68040).
5. PRESENT bit 0 clear: "No disk", then `stop #$2700` forever (loop around STOP).
6. HDD READ LBA 0, COUNT 1 to 0x0000_1000 (above the vector table), poll STATUS. ERROR or bytes
   510..511 not 0x55 0xAA: "Disk not bootable" and sleep.
7. Jump to 0x1000 with D0 = RAM size, D1 = capacity in sectors, A1 = `print_string` (string
   pointer in A0, prints to COM1 and VGA), SP at top of RAM, still in supervisor mode with
   interrupts masked.
8. `rom_trap`: prints "TRAP vector=XX pc=XXXXXXXX sr=XXXX" (and on a 68000 group 0 fault,
   vector 2 or 3, also "addr=XXXXXXXX" from the long frame; on a 68040 the frame's format word
   gives the format) to COM1 and VGA, then POWER = 0x3333 | (0xDEAD << 16).
- Device notes for 68k guests: 32-bit registers are accessed as `.l` (value-addressed), the HDD
  IDENTIFY block and the planned program header (6) are little-endian, so 68k code
  byte-swaps those fields (`ror.w #8`, `swap`, `ror.w #8`).
- `BootRom` (RV32I) stays as it is; a small `BootRoms` helper picks per model.

#### 10.8.5 As built (integration step)

- `M68kPlatform`: one class, instances `M68000` and `M68040`. It maps every region at the CPU's
  own view (`address & 0xFF_FFFF` on the 68000), turns the ROM overlay on in `build`, and wires
  controller outputs 1..7 to pins 1..7 with pin 4 driven by output 0 OR output 4 (both sinks
  re-drive it from the controller's current levels). Clock limits: 68000 4 to 16.67 MHz, 68040
  25 to 40 MHz; RAM 64 KiB to 14 MiB or 64 MiB; ROM at most 256 KiB.
- `Platform.romOverlayAtReset()` (default false, true for both 68k platforms) and `forModel`
  for RV32I, 68000 and 68040. `BootRoms.image(model)` gives each model's standard ROM;
  `MachineConfig.of` uses it.
- `Machine`: `CpuHost.resetDevices` records a device reset and stops the CPU after the RESET
  instruction. Between runs the machine first performs a recorded device reset (every device
  reset and the pins re-driven; CPU, RAM and the overlay untouched), then the SysCon requests;
  ROM_OVERLAY_OFF unmaps the mirror on platforms that use it (RV32I ignores it as before).
  Power-on and every reset (host button or guest POWER 0x7777) set the overlay to
  `romOverlayAtReset()` before the CPU reset, so the CPU reads its reset vectors from ROM.
  Saved-state envelope version 3 adds the pending device reset (1.5).
- `StandardCpus`: `M68kCpu` with `M68kOptions.m68000()` and no FPU, or
  `M68kOptions.m68040(host.strictFpu())` with `M68kFpu::new`. `CpuModel` marks both 68k models
  emulated.
- Boot ROM (`M68kBootRom`), one source with three spliced parts per CPU (the 68040's MOVEC and
  FPCR prologue, the banner's CPU name, the trap handler's frame reading):
  - ROM offset 0: SSP 0 (unused) and PC 0xFFFC_0008 (`_start`'s home address).
  - `_start` writes ROM_OVERLAY_OFF first, sets SP from RAM_SIZE, points vectors 2..255 at 256
    four-byte stubs of `bsr.w rom_trap`, sets COM1 to 8N1 with FIFOs, blanks VGA text (cells
    0x2007) and prints "Create Flippies PC  68000  <n> KiB RAM" (or 68040) to COM1 and VGA.
  - No disk: "No disk" and `stop #$2700` in a loop. Bootable disk: sector 0 to
    `BOOT_ADDRESS` 0x1000, entered with D0 = RAM size, D1 = capacity in sectors, A1 =
    print_string (A0 = string, keeps every register), SP = top of RAM, SR 0x2700.
  - Trap handler: masks interrupts, finds the vector from the stub's return address and prints
    "TRAP vector=XX pc=XXXXXXXX sr=XXXX" plus " addr=XXXXXXXX" for the 68000's vectors 2 and 3
    and the 68040's frame formats 2, 3 and 7, then waits for COM1 to drain and powers off with
    POWER 0xDEAD3333 (`TRAP_EXIT_CODE` 0xDEAD).
- End-to-end rig (`test: computer/e2e/M68kE2e`): skips the ROM's start-up but keeps its trap
  handler (overlay off, reset vectors to a program at 0x1000, vectors 2..255 to the ROM's stubs);
  the 68000 runs at 8 MHz and the 68040 at 25 MHz with 1 MiB of RAM.

### 10.9 Foundation step (done): the shared contracts

Written first, before the three parallel owners start. Nobody changes these signatures; Javadoc
may be extended. The source files are the contract text. Paths are under
`src/main/java/com/nectariich/create_flippies/content/computer/` as in 7.

- `cpu/CpuModel.java`: `CPU_68040("68040", 25_000_000, 32, 32, BIG_ENDIAN, false)`, after
  `CPU_68000`. Both 68k models stay `emulated = false` until integration.
- `cpu/CpuHost.java`: new last component `boolean strictFpu` (only with `CPU_68040`, else
  IllegalArgumentException). The old eight-argument constructor stays and passes false.
- `machine/MachineConfig.java`: new last component `boolean strictFpu`, the in-game strict FPU
  option (only with `CPU_68040`). The old seven-argument constructor stays and passes false.
- `machine/Machine.java`: passes `config.strictFpu()` into `CpuHost`; the saved-state envelope
  (version 2) writes the flag after the reset PC and a load checks it like the rest of the
  config (1.5).
- `machine/M68kMap.java`: the memory map of 10.8.1 as 32-bit constants, `ADDRESS_MASK_68000`,
  RAM limits, clock limits and items, pins (`IRQ_PIN_OUTPUT_0 = 4`, `IRQ_PIN_LEVEL_7`),
  controller lines and `MMIO_EXTRA_CYCLES`.
- `cpu/m68k/M68kOptions.java`: `record M68kOptions(CpuModel model, boolean strictFpu)`, 68k
  models only, strict only on the 68040; `m68000()`, `m68040(boolean)`, `hasFpu()`.
- `cpu/m68k/M68kSr.java`: SR and CCR bits (C, V, Z, N, X, `CCR_MASK`, `IPL_MASK`, `IPL_SHIFT`,
  M, S, T0, T1), the per-model SR masks and the reset value 0x2700.
- `cpu/m68k/M68kVectors.java`: vector numbers of 10.2.1 (reset, bus or access error, address
  error, ..., `AUTOVECTOR_BASE` 24, `TRAP_BASE` 32, FP 48..55), 68040 frame formats 0, 1, 2, 3,
  4, 7 and `frameBytes(format)` (-1 for a format RTE rejects).
- `cpu/m68k/M68kFpuHost.java`, implemented by the core, called by the FPU:
  `fetch16()`, `instructionAddress()`, `pc()`, `register(n)` and `setRegister(n, v)` (0..7
  D0..D7, 8..15 A0..A7), `effectiveAddress(mode, reg, bytes)` (memory modes 2..7 with their side
  effects done once, immediate data addressed in the instruction stream), `read8/16/32`,
  `write8/16/32`, `exception(vector, format, stackedPc, address)`. A fault inside a host call
  unwinds the instruction with an unchecked exception of the core's own, which the FPU never
  catches.
- `cpu/m68k/M68kFpuUnit.java`, implemented by the FPU, called by the core: `reset()`,
  `general(opword)`, `condition(predicate)` (1, 0 or `CONDITION_TRAPPED`), `save(opword)`,
  `restore(opword)`, `saveState`, `loadState`; entry points return cycles. Nested
  `Factory.create(M68kFpuHost host, boolean strict)`, so `M68kFpu::new` is one.
- `cpu/m68k/FpuOpcodes.java`: opmodes of 10.2.4 (support-package ones marked), operand format
  codes with `formatBytes`, the 32 predicate names in code order and `PREDICATE_SIGNALING`. The
  FPU owner verifies every code against the PRM; the assembler keeps its own copy.
- `asm/m68k/M68kTarget.java`: `enum M68kTarget { M68000, M68040 }`.
- Assembler front end (10.7.1): `LexerSyntax` (six new flags, old constructor kept),
  `AsmLexer`, `AsmToken.Kind.FLOAT`, `IsaBackend.wordBytes()` and `isDataStatement()`,
  `Assembler` (`equ`/`set`, `.word` width, data statements in `.bss`).
- Tests: `test: computer/asm/ClassicSyntaxTest.java` (every new flag, a toy big-endian backend
  for `equ`, `set`, 2-byte `.word` and `ds.b` in `.bss`, RV32I unchanged) and
  `test: computer/machine/M68kFoundationTest.java` (strict FPU config rules, options, shared
  tables). `test: computer/e2e/SaveStateE2eTest` now derives its unknown-version case from
  `Machine.STATE_VERSION`.

Not shared: the extended-precision type. The core passes FP operands to the FPU only as memory
bytes and register values (through `M68kFpuHost`), and the FPU saves its own registers, so
`Fx80` and its signature belong to the FPU owner alone.

The core's constructor, fixed here so integration can rely on it:
`public M68kCpu(CpuHost host, M68kOptions options, M68kFpuUnit.Factory fpu)`, `fpu` null on the
68000 and required on the 68040. The FPU's: `public M68kFpu(M68kFpuHost host, boolean strict)`.

### 10.10 Owners

Main paths under `computer/`, tests under `test: computer/`. No file has two owners. An owner
that needs another owner's piece writes a private stand-in in its own test tree (7.1 rule). The
foundation files of 10.9 are nobody's to change; a needed change goes through the orchestrator.

- Agent A, integer core:
  - main `cpu/m68k/` (not `cpu/m68k/fpu/`, not the 10.9 files): `M68kCpu.java` (registers, run
    loop, fetch, memory access, EA, every integer instruction, exceptions and frames,
    interrupts, trace, STOP, RESET, MOVEC, MMU and cache no-ops, the `M68kFpuHost`
    implementation, line F dispatch to `M68kFpuUnit`, FBcc, FScc, FDBcc and FTRAPcc through
    `condition`, save state), `M68kOpTable.java` (per-model 65536-entry table builder),
    `M68kTiming.java` (68000 tables and the 68040 cost model), `M68kFlags.java` (condition
    table, BCD and shift helpers), `Bitfield.java` (bitfield helpers), plus private helpers.
  - tests `test: computer/cpu/m68k/` (not `fpu/`): `M68kRig.java` (a big-endian `SystemBus`
    with RAM, ROM, VRAM and a test device), a stub `M68kFpuUnit` (takes vector 11 for every
    entry point) and the tests of 10.12.1.
- Agent B, FPU:
  - main `cpu/m68k/fpu/`: `Fx80.java` (soft-float extended: arithmetic, rounding, conversions,
    compare, classification), `M68kFpu.java` (implements `M68kFpuUnit`: registers, FPCR, FPSR,
    FPIAR, command word decode, FMOVE, FMOVEM, control moves, conditions, FSAVE, FRESTORE,
    exception reporting, transcendental dispatch, strict mode), `FpPacked.java` (packed
    decimal), `FpSupport.java` (transcendentals through `StrictMath`, FMOD, FREM, FINT, FSCALE,
    FGETEXP, FGETMAN, FSGLxxx), `FpConstantRom.java` (FMOVECR values as exact extended bit
    patterns with a comment giving each value).
  - tests `test: computer/cpu/m68k/fpu/**`: `Fx80` unit tests, the optional TestFloat runner,
    `M68kFpu` tests with a fake `M68kFpuHost` (no CPU needed), the tests of 10.12.2.
- Agent C, assembler:
  - main `asm/m68k/` (not `M68kTarget`): `M68kAssembler.java`, `M68kBackend.java` (sizes,
    encode, directives; its `LexerSyntax` turns every 10.7.1 flag on, comments `;` only,
    `hashImmediate` on), `M68kOperands.java` (operand parser into an EA record: mode,
    register, extension words, relaxable parts), `M68kInstructions.java` (mnemonic table:
    sizes, EA classes per operand, encoder per form, target), `M68kFpInstructions.java`,
    `M68kRegisters.java`, `M68kFloat.java` (decimal text to single, double, extended bits,
    exact), `M68kFpOpcodes.java` (its own copy of `FpuOpcodes`; the assembler imports nothing
    from `cpu/**`, as RV32I does).
  - The shared framework files in `asm/` are not the assembler owner's (done in 10.9).
  - tests `test: computer/asm/m68k/**` (the tests of 10.12.3); every existing `asm/**` test
    stays green.
- Integration step (after A, B and C):
  - main `machine/`: `M68kPlatform.java`, `M68kBootRom.java`, `BootRoms.java`; edits to
    `Platform.java` (`forModel`, `romOverlayAtReset`), `Machine.java` (device reset request
    through `CpuHost.resetDevices`, acting on ROM_OVERLAY_OFF, overlay on at reset and power-on,
    the pending device reset in saved state with an envelope version bump),
    `MachineConfig.java` (`of` picks the boot ROM by model), `StandardCpus.java` (10.8.3);
    `cpu/CpuModel.java` emulated flags.
  - tests `test: computer/e2e/`: `M68kE2e.java` (rig, equates, a small 68k library) and the
    tests of 10.12.4, including the FPU opcode table cross-check (assembler copy against
    `FpuOpcodes`), the encoder and decoder cross-check and the save and load round trip.
  - Spec: every "(verify)" outcome recorded, the 68k machine's measured throughput, and the
    implementation status.

### 10.11 Ordering

1. Foundation (10.9). Done.
2. In parallel:
   - A: 68000 subset first (it is the base of everything): op table, EA, MOVE/arith/logic/shift,
     branches, exceptions and group 0 frames, interrupts, STOP, RESET, 68000 timing, each with
     its hand-checked tests; then the 68040 integer additions and frames.
   - B: `Fx80` against TestFloat first (add, sub, mul, div, sqrt, conversions, round to
     precision), then `M68kFpu` instruction layer, then support-package instructions, then
     packed decimal.
   - C: lexer and framework flags with RV32I tests green, then the 68000 instruction set with
     relaxation, then 68040 integer forms, then FPU mnemonics.
3. Integration once A has the 68000 working and C can assemble the boot ROM (68000 machine
   first), then the 68040 machine when A and B are done. Done (10.8.5).
4. Spec update and a review pass (ISA facts and architecture, as for RV32I).

### 10.12 Test plan

All JUnit 5, pure Java, same commands as section 8.

#### 10.12.1 Integer core (agent A)

- Hand-checked only (user decision, 10.1): no third-party 68000 test-vector suite is downloaded or
  used. Encodings in tests are hand-assembled words with the expected flags worked out in comments,
  for example: `moveq #-1,d0` = 0x70FF (D0 = 0xFFFF_FFFF, N = 1, Z = 0, V = C = 0, X unchanged);
  `add.w d1,d0` = 0xD041; `addq.l #1,a0` = 0x5288 (no flags); `move.l d0,(a0)+` = 0x20C0; `lea
  8(a0),a1` = 0x43E8 0x0008; `bra.s *+4` = 0x6002; `dbf d0,loop` = 0x51C8 disp; `nop` = 0x4E71;
  `rts` = 0x4E75; `rte` = 0x4E73; `trap #0` = 0x4E40; `illegal` = 0x4AFC; `stop #$2000` = 0x4E72
  0x2000; `reset` = 0x4E70. Each checked by hand against the PRM opcode maps before use.
- Flags: carry and overflow boundaries for ADD/SUB/CMP/NEG at each size (0x7F + 1, 0x80 - 1,
  0xFF + 1), ADDX/SUBX Z chaining (Z stays 1 across a zero chain, clears on nonzero), X
  untouched by CMP and by MOVE, shifts by 0, by 1, by size, by 63 (register mod 64), ASL V
  when the sign changes mid-shift, ROXL with count 0 copying X into C, BCD known cases
  (0x99 + 0x01 + X=0 = 0x00 with C = 1; 0x45 - 0x46 = 0x99 with C = 1; NBCD of 0 with X = 0
  leaves Z), DIVU overflow leaves the register unchanged and sets V, DIVS -32768 cases, divide by
  zero trap with PC = next instruction, MULS sign cases, CHK both sides, TAS, and the fixed
  rules for undefined flags (BCD N and V, DIV overflow, CHK) as 10.3.4 writes them.
- EA: every mode on a read and a write instruction, -(A7).b moves by 2, (An)+ with An as both
  source and destination (`move.l (a0)+,(a0)+`), index register word sign extension, PC-relative
  base, 68040 scales, full format with each suppress combination, memory indirect pre and post,
  reserved full-format encodings illegal.
- MOVEM: register to memory with -(An) (reverse mask, stored order, An updated once), memory to
  register with (An)+ (word loads sign-extended into the whole register, including An), the
  stack pointer in the list.
- 68040: MULU.L/MULS.L 32 and 64-bit forms, DIVx.L both forms and overflow, bitfields
  (register wrap-around, memory field spanning 5 bytes, BFFFO with no set bit, BFINS flags),
  CAS equal and not equal, CAS2, CHK2/CMP2 with signed and unsigned bounds and an address
  register, PACK/UNPK with adjustment, LINK.L, RTD, EXTB.L, TRAPcc, MOVEC every register and an
  invalid number, MOVES, MOVE16 alignment, misaligned long load from RAM works.
- Exceptions and frames: 68000 group 0 frame layout for an odd word read and an odd jump
  (status word bits, access address, instruction register, SR, PC = instruction + 2 per the
  documented deviation), bus error on an unmapped address, privilege violation from user mode
  (PC = the instruction), TRAP #n vector and return address, line A and line F, illegal, RTE
  restoring SR and switching stacks; 68040 format 0, 1 (interrupt with M set: two frames, M
  cleared), 2 (CHK, zero divide with address = the instruction), 7 (access error fault
  address and SSW), RTE of each format, format error on a bad format word, VBR relocates the
  table.
- Interrupts: level 3 pending with mask 2 taken (vector 27, mask becomes 3), masked with mask 3,
  level 7 taken with mask 7 once per edge (held level 7 does not retrigger; dropping and raising
  again does), interrupt raised from inside a device write is taken after that instruction,
  STOP returns WAITING and wakes on a level above the mask, STOP with a masked level stays
  asleep, trace after each instruction with T set, trace and interrupt priority.
- RESET calls `resetDevices` once and costs 132 cycles; reset vectors read on the first run,
  halt on an unreadable vector, double fault halts with a reason.
- Timing: exact totals, worked out by hand from the reference tables, for short 68000 programs
  mixing each class (MOVE matrix samples, EA
  costs, DBcc loop, MULU of 0 and 0xFFFF, DIVU worst case, interrupt entry 44), slicing
  invariance (1-cycle, 7-cycle and single run give the same state, as for RV32I), horizon and
  deadline behaviour as 8.1.
- Save state: save mid-program, load into a fresh CPU, run both, identical; loading a 68000 state
  into a 68040 is a `StateFormatException`.
- Fuzz: random words in RAM with a vector table pointing at a handler, millions of steps over many
  seeds: only exceptions, WAITING, HALTED or normal returns; no Java exception, including
  division and bitfield edge cases.
- Benchmark (tagged): prints MIPS for a mixed loop on each model; lenient floor (for example
  15 M instructions per second).

#### 10.12.2 FPU (agent B)

- Berkeley TestFloat 3e (`testfloat_gen`, BSD licence) generates operand and result vectors for
  `extF80` add, sub, mul, div, sqrt, conversions to and from int32, float32, float64, compare, with
  rounding precision 32, 64 and 80 bits and every rounding mode, including exception flags. Build it
  once offline, generate vector files outside the repo, run them through a tagged `external` test
  that skips (JUnit assumption) when the vector directory is absent, so CI and the offline Gradle
  run stay green; also commit a small hand-picked subset (a few hundred lines) as a normal test.
  Caveat: TestFloat's default NaN and NaN propagation follow its build's specialisation, which
  differs from the 68k (default NaN 0x7FFF_FFFF...; positive) and the extended format's unnormal and
  pseudo-denormal handling differs too: compare NaN results by class only and drop unnormal inputs.
- Hand tests: rounding of halfway cases in all modes and precisions, overflow to infinity versus
  largest finite per mode, underflow to denormal with UNFL and INEX2, FPSR condition codes for
  zero, negative zero, infinity, NaN, accrued bits, FPCR enables raising the right vector with
  format 3, BSUN on FBGT with a NaN, FMOVE.X round trip of every class, FMOVE.L of 2^31 sets OPERR
  and saturates, FMOVEM predecrement order, FMOVECR pi and 10^4096 bit patterns, FSAVE null then
  idle, FRESTORE null resets, bad frame is a format error, FMOD and FREM quotient byte, FINT in
  each mode, strict FPU traps FSIN to vector 11 and denormals to 55 (the fake host built with
  `strict = true`), default mode emulates both.
- Transcendentals: compare with a `BigDecimal` reference at a few hundred points with a tolerance
  of 2 ulp in double precision; exact special cases (sin 0 = 0 with sign, log 1 = +0, etox of
  -infinity = +0, operand errors for log of a negative, asin of 2).

#### 10.12.3 Assembler (agent C)

- Every instruction form encodes to a hand-checked word sequence (the 10.12.1 examples plus each EA
  mode, each size, MOVEM masks both directions, bitfields, CAS2, full extension words, FPU
  general, FMOVEM, FBcc).
- Relaxation: `bra` to a target 127, 128, 32767, 32768 bytes away picks .s, .w, .w, .l (68040)
  or error (68000); a chain of forward branches growing each other converges; abs.W/abs.L
  selection at 0x7FFF, 0x8000, 0xFFFF_8000; `bra` to the next instruction is .w.
- Auto-optimisation: `move.l #5,d0` is MOVEQ, `move.l #200,d0` is MOVE.L immediate,
  `addq` from `add.l #8,a0`, `add.l #9,a0` becomes ADDA; forward-referenced immediates stay long.
- Gating: every 68040-only form errors on the 68000 target with the expected message; `.cpu`
  directive switches.
- Lexer flags: `$FF`, `%1010`, `x%2` is modulo, `*` comment in column 1, `;` comment, float
  literal tokens, `name equ 5`; all existing RV32I lexer and assembler tests still pass.
- Directives: `dc.b "hi",0` then `dc.w 1` pads one byte; `ds.l 4` in bss; `even`; `dcb.w 3,$FFFF`.
- Floats: `fmove.s #1.1,fp0` emits 0x3F8CCCCD; `fmove.d #0.1` 0x3FB9_9999_9999_999A; `.x`
  extended pattern of 0.1 checked by hand.
- Diagnostics: bad register, bad EA for the instruction ("'lea' does not take a data register"),
  size not allowed ("'lea.b'"), out-of-range quick immediates, all with line and column.
- Fuzz: token soup never throws.

#### 10.12.4 End-to-end on the PC (integration step)

The RV32I end-to-end set ported to 68k assembly, on both machines where it makes sense:

- Hello: prints "Hello, PC!\n" to COM1 and powers off with 0x5555.
- VGA text and graphics as 8.4, with big-endian word writes for cells.
- PS/2 echo: keyboard IRQ routed to output 2 (IPL 2), handler at autovector 26 reads DATA and
  writes the byte to COM1; main loop in STOP #$2000.
- HDD: write LBA 5 and read it back, compare, "OK"; disk image checked.
- Timer interrupt: interval timer through the controller at IPL 4 (reset routing), 1 ms period,
  10 interrupts then power off; emulated time about 10 ms. Busy-loop latency: interrupt taken
  within one instruction of the expiry cycle.
- IPL masking: a level-2 source with SR mask 3 is not taken until the mask is lowered; level 7
  through output 7 interrupts a handler running at mask 7 once.
- RESET instruction: program sets the UART up, executes RESET, reads the UART back at reset
  values, continues running.
- ROM overlay: after reset address 0 reads the ROM's first long; after the ROM's overlay-off
  write it reads RAM; a guest SysCon reset turns it back on and the ROM boots again.
- Boot ROM: no disk, disk not bootable, bootable disk calling `print_string` through A1; trap
  handler output for an illegal instruction and for an odd word read on the 68000.
- 68040 extras: an FPU program computing sqrt(2) and printing its double bits in hex; FSIN on a
  machine with the strict FPU option off works, and with it on (`MachineConfig.strictFpu`)
  reaches the ROM trap handler with vector 11; a state saved with the option on does not load
  into a machine with it off.
- FPU opcode tables: the assembler's copy equals `FpuOpcodes` (opmodes, formats, predicates).
- Encoder and decoder cross-check: for every instruction form (random registers, immediates,
  EA modes), assemble with `M68kAssembler`, run on `M68kCpu` from a known state, compare with a
  small reference model in the test (as the RV32I cross-check does).
- Save and load round trip: run a program with interrupts and FPU state halfway, save the
  machine, load into a fresh one, run both to the end, identical COM1 output, registers, RAM and
  cycle counts.
- Throughput benchmark (tagged): machine MIPS for each model; record in the spec.

### 10.13 Difficulty and risks

- Integer core size: the 68000 alone is about 4,000 to 6,000 lines with the 68040 additions; the
  largest single piece in the project. Mitigation: hand-checked tests per instruction class,
  written alongside each class, and the fuzz test.
- Exact 68000 cycle counts and undefined flags: the reference tables are long and error-prone,
  and without a test-vector suite (no licence) mistakes are caught only by hand-computed totals.
  Divide timing is the hardest. Undefined flags follow the fixed rules of 10.3.4, which may
  differ from the real chip (documented).
- Group 0 frame PC and prefetch: deliberately approximated (10.1, decision 11). Real software only
  prints these values; the risk is low.
- Soft-float correctness: rounding, sticky bits and denormals are classic bug sources; TestFloat
  vectors are the mitigation. Packed decimal is fiddly but rarely used.
- 68040 frame details and FPU exception reporting: several "(verify)" items; get them from UM040
  section 8 and the PRM before coding.
- Performance: a 68040 at 25 to 33 MHz will dilate time on busy servers; a 65536-entry table and
  specialised hot paths keep the interpreter near RV32I speed per instruction, but 68k
  instructions do more work. Measure early with the benchmark.
- Shared framework edits: the lexer flags touch RV32I's path; they are flag-guarded and the full
  existing assembler suite passed after the foundation step.
- The pending device reset must join the machine's saved state at integration (10.8.3). Done:
  envelope version 3.
- Device byte lanes on a big-endian CPU (value-addressed registers) will surprise guest authors;
  document it in the guest notes and use it correctly in the ROM.

## 11. In-game PC (planned)

The PC as a block in the world: a case the player fills with parts, wired to Power Grid for
power, connected to other PCs and to a keyboard with data cords. This section is the plan for two
owners working in parallel: the core owner (pure Java under `computer/**`, house rules unchanged)
and the game owner (Minecraft, Forge, Create and Power Grid code outside `computer/**`). The
monitor and the mouse are not designed yet; this section defines the server-side interfaces they
will use and nothing more.

User decisions that settle this section:

- Everything in the model's `case` group belongs to the case. The front panel can be taken off.
  Motherboard, RAM, HDD and CPU are separate components the player installs and removes. The
  floppy drive is built into the case; the model's `floppy disc` cube is the floppy disk itself,
  drawn only while a disk is inserted.
- Every port has its own cube, which is the attachment point for cords. One universal data cord,
  copied from the Power Grid wire, connects two identical port types only (VGA to VGA, COM to COM,
  PS/2 to PS/2, ethernet to ethernet).
- RAM sticks up to 64 MB, suiting every current CPU (the 68000's 24-bit bus is accounted for).
  The limit is 128 MB, so two 64 MB sticks are fully usable on RV32I and the 68040; up to 128 MB
  of server memory per running PC is accepted. HDD up to 1 GB.
- Networking is in-game only: a simple custom network card (own registers: send frame, receive
  frame, MAC, interrupt), direct PC-to-PC ethernet cords, no switch or router.
- The floppy drive works: 1.44 MB floppy disk items are inserted and ejected; the boot ROM boots
  from floppy; read and write.
- Power from Power Grid. The power connector is a socket: Power Grid's own two-conductor cord
  (one cable, two conductors) plugs into it, drawn with a cube for the plug, and joins a Power
  Grid network. Works from 95 to 270 V DC. Draws
  power according to the load (CPU, RAM, drives), as the PLC does.
- The three VGA ports are three independent displays, each with its own framebuffer.
- COM3 exists on the case; the core adds it.
- Power button, reset button, power indicator and error indicator are functional.
- The PC freezes when its chunk unloads and resumes on load; disk and floppy images are persisted
  separately with an ID, so a swapped disk is detected.
- The keyboard block (model `keyboard-1.json`) plugs into a case PS/2 port; a player types on it
  by capturing keyboard input; a rapid double press of Escape releases it, a single Escape goes
  to the PC as a normal key (11.2.12).

### 11.1 Core additions (owner: core)

Everything here is pure Java under `computer/**`, with tests on the plain JUnit platform.

#### 11.1.1 Memory maps and interrupt lines

New interrupt controller lines (3.2), the same on every machine:

- 8 COM3, 9 floppy controller, 10 network card, 11 VGA display 1 vertical blank, 12 VGA display 2
  vertical blank. Line 6 stays VGA display 0. Lines 3 and 4 become PS/2 port 0 and port 1
  (11.1.4); with the default devices that is still keyboard and mouse.

RV32I (`Rv32Map`, additions to 4.1):

- 0x1000_7000 COM3, 0x1000_8000 floppy controller, 0x1000_9000 network card, 0x1000_A000 VGA
  display 1 control, 0x1000_B000 VGA display 2 control. Windows 0x1000 bytes as before.
  `VGA_BASE` keeps naming display 0's control window.
- VRAM: 0x2000_0000..0x2017_FFFF, 1.5 MiB (`VRAM_SIZE` becomes 0x18_0000). Display n's
  framebuffer is at 0x2000_0000 + n * 0x8_0000. New constants `VRAM_DISPLAY_SIZE = 0x8_0000` and
  `VRAM_DISPLAY_BASE[]` (or `vramDisplayBase(int)`).
- RAM: 0x8000_0000, 64 KiB to 128 MiB (`RAM_MAX` doubles; RAM ends at 0x87FF_FFFF at most,
  nothing else lives above 0x8000_0000).

68000 and 68040 (`M68kMap`, additions to 10.8.1):

- Device windows: +0x9000 COM3, +0xA000 floppy controller, +0xB000 network card, +0xC000 VGA
  display 1 control, +0xD000 VGA display 2 control (all below the ROM at 0xFFFC_0000).
- VRAM: one 1.5 MiB range 0xFFD8_0000..0xFFEF_FFFF (68000: 0xD8_0000..0xEF_FFFF), so the bus
  keeps one VRAM array on the CPU fast path. Display 0 stays at 0xFFE0_0000, where existing guest
  code and both boot ROMs expect it; display 1 is at 0xFFE8_0000 and display 2 at 0xFFD8_0000.
  Inside the array: display 2 at offset 0, display 0 at 0x8_0000, display 1 at 0x10_0000.
- 68000 RAM limit: `RAM_MAX_68000` drops from 14 MiB to 13.5 MiB (0xD8_0000), everything below the
  VRAM range. A 24-bit bus has 16 MiB: 13.5 MiB RAM, 1.5 MiB VRAM, 768 KiB of device windows, 256
  KiB of ROM.
- 68040 RAM limit: `RAM_MAX_68040` rises from 64 MiB to 128 MiB (0x0000_0000..0x07FF_FFFF, far
  below VRAM and the devices).
- DMA addresses on the 68000 are still masked to 24 bits (10.8.1), so floppy and network card DMA
  to the 32-bit VRAM constants lands in VRAM on both machines.

System controller (3.1) additions:

- 0x18 VRAM_BASE and 0x1C VRAM_SIZE keep their meaning for display 0 (base, and 512 KiB per
  display). New 0x2C VRAM1_BASE (R) and 0x30 VRAM2_BASE (R): displays 1 and 2.
- 0x34 LEDS (RW, reset 0): bit 0 guest error light. The case's error indicator shows it ORed with
  the host's own error conditions (11.2.9), so guest software can signal a fault.
- 0x20 PRESENT gains: bit 5 floppy media present, bit 6 COM3 host connected, bit 7 network link
  up. (The floppy drive itself is always there.) Bits 1 and 2 now mean "a device is plugged into PS/2 port 0"
  and "port 1" (11.1.4).
- `SysCon.SIZE` grows to 0x38.

`Platform` gains `int maxRamBytes()` (RV32I 128 MiB, 68000 13.5 MiB, 68040 128 MiB), so the game asks
the platform instead of copying limits.

#### 11.1.2 COM3

- A third `Uart16550`, identical to COM1 and COM2, on line 8 at the addresses above.
- `Board.com(2)`, `Machine.com(2)`; `Machine.COM_PORTS = 3`. Saved-state tag `com3`.
- Boot ROMs keep printing to COM1 only.

#### 11.1.3 Three VGA displays

- Three `VgaDisplay` instances, each a complete device with its own control window, mode,
  palette, cursor, START, frame counter, generation and row generations, as in 3.7. Each one's
  framebuffer is a 512 KiB slice of the platform's single VRAM array.
- `VgaDisplay` gets a second constructor `VgaDisplay(IrqSink irq, byte[] vram, int offset,
  long[] dirtyPages)`: `offset` a multiple of 512 KiB, `dirtyPages` the bus's page bits for the
  whole array (6 longs for 1.5 MiB; the bus contract of 7.2 already indexes any size). The
  display reads and clears only its own 128 page bits (two longs starting at `offset >>> 18`).
  The old `VgaDisplay(IrqSink)` constructor keeps its own 512 KiB array, for tests.
- START, the wrap rule and `copyVisibleInto` are relative to the display's own slice; START can
  not reach another display's slice (it wraps inside its 512 KiB).
- VRAM budget: 512 KiB per display is enough for 640x480 at 8 bpp (300 KiB) with room for a
  second text or 4 bpp page; 1.5 MiB per PC in total.
- The platform constructs the VRAM array and the dirty-page array and passes them to
  `SystemBus.setVram` and to the three displays. `SysCon` reports the three bases.
- `Board.vga(int)` with index 0..2; `Board.vga()` stays and means `vga(0)`. `Machine.vga(int)`,
  `Machine.DISPLAYS = 3`. `endFrame()` is called on all three at the end of every tick.
- Saved-state tags: `vga` (display 0, as before), `vga.1`, `vga.2`. Each section stores its own
  slice of VRAM with `StateIo.writePages`.
- The 8086/8088 (not built) will need one 64 KiB banked window per display; noted for later.

#### 11.1.4 PS/2: either device on either port

The case has two PS/2 ports and the keyboard must work in whichever one it is plugged into
(user request, 11.2.12). Today port 0 is hard-wired to a keyboard and port 1 to a mouse.

- `Ps2Controller` holds, per port, one `Ps2Keyboard` and one `Ps2Mouse` object and a selected
  device kind: `enum Ps2DeviceKind { NONE, KEYBOARD, MOUSE }`. Only the selected device is present;
  the other is unplugged (its queue empty). Reset default: port 0 KEYBOARD, port 1 MOUSE, both
  unplugged until the host plugs them in.
- Host API: `void setDevice(int port, Ps2DeviceKind kind)` (selects and plugs in, or unplugs with
  NONE; changing kind unplugs the old device, which empties its queue, then plugs the new one,
  which queues its power-on bytes), `Ps2DeviceKind device(int port)`, `Ps2Keyboard keyboard(int
  port)`, `Ps2Mouse mouse(int port)`.
- `Machine.keyboard()` stays as `keyboard(0)` and `Machine.mouse()` as `mouse(1)`; new
  `Machine.ps2()` returns the controller.
- Interrupt lines stay per port (3 and 4). `Ps2Controller` saved-state version 2: per port the
  selected kind and both device states. A version 1 payload loads as port 0 KEYBOARD, port 1
  MOUSE.
- `Ps2Key`: its declaration order becomes frozen (append only), because the keyboard's network
  packet sends the ordinal (11.2.12). Say so in its Javadoc.

#### 11.1.5 Floppy controller (`FloppyController`)

A simple LBA controller with DMA, like the HDD (3.8), not an emulation of any real floppy chip.
One drive, always present (built into the case), 1.44 MB media: 2880 sectors of 512 bytes, presented as 80 cylinders, 2 heads, 18
sectors per track for software that wants CHS.

Registers (window 0x1000, registers 32-bit, rules of 3):

- 0x00 COMMAND (W): 1 READ (disk to memory), 2 WRITE (memory to disk), 3 SEEK (moves the head to
  LBA's cylinder, no data), 4 MOTOR_OFF (stops the spindle at once). Writing while BUSY is ignored
  and sets ERROR with code 5 busy.
- 0x04 STATUS (R; W1C for bits 1, 2 and 5): bit 0 BUSY, bit 1 DONE, bit 2 ERROR, bit 3 MEDIA
  present, bit 4 WRITE_PROTECT (the inserted disk's tab), bit 5 MEDIA_CHANGED (set by every insert
  and eject; set at power-on), bit 6 MOTOR (spinning).
- 0x08 LBA (RW), 0x0C COUNT (RW, 1..256 sectors), 0x10 DMA_ADDR (RW, bus address).
- 0x14 CAPACITY (R, sectors; 2880 with media, 0 without).
- 0x18 IRQ_ENABLE (RW): bit 0 command completion (DONE or ERROR), bit 1 media change.
- 0x1C ERROR_CODE (R): the HDD's codes 0..6 (none, no media, LBA range, DMA range, bad command,
  busy, bad count), plus 7 media error (the image could not be read from storage, 11.1.6),
  8 write protected, 9 media changed during the command.
- 0x20 SECTOR_SIZE (R, 512). 0x24 GEOMETRY (R): cylinders << 16 | heads << 8 | sectors per track
  (80 << 16 | 2 << 8 | 18).

Timing (lazy from cycle stamps, converted with `microsToCycles`, constants in the class):

- `FDD_SPINUP_US = 300_000` when the motor is off; the motor stays on `FDD_MOTOR_HOLD_US =
  2_000_000` after the last command, then stops by itself (caught up lazily; it changes no line).
- Seek `FDD_STEP_US = 3_000` per cylinder moved plus `FDD_SETTLE_US = 15_000` if it moved at all;
  cylinder = LBA / 36.
- Rotational latency `FDD_LATENCY_US = 100_000` (half a turn at 300 rpm), then
  `FDD_SECTOR_US = 11_111` per sector (18 sectors per 200 ms turn, about 45 KB/s). So the boot
  sector arrives in about 0.4 s from cold and a 64 KiB program in about 1.8 s.
- As with the HDD: the command write calls `clock.requestStopAt(completion)`, data moves at
  completion through `SystemBus.dma` (one copy, then `onWrite`), `nextEventCycle()` is the
  completion whether or not IRQ_ENABLE is set.

Rules:

- WRITE on a write-protected disk completes at once with ERROR code 8, nothing written.
- Insert or eject while a command is in flight: completion ends in ERROR with code 9, no data
  moves. MEDIA_CHANGED is set on both events.
- IRQ line 9 = (IRQ_ENABLE bit 0 && (DONE || ERROR)) || (IRQ_ENABLE bit 1 && MEDIA_CHANGED).
  Insert and eject happen between ticks (host calls), so they may drive the line directly.
- Host API: `insert(DiskImage media, boolean writeProtected)` (IllegalArgumentException unless
  the media has 2880 sectors), `eject()`, `DiskImage media()`, `boolean writeProtected()`,
  `boolean motorOn()`, `boolean takeActivity()` (true if a command ran
  since the last call; the game uses it for power draw).
- Saved state (tag `fdc`, own version 1): registers, motor state and its stamps, head cylinder,
  the command in flight with its completion cycle, the write-protect flag and the media's id
  (`DiskImage.id()`, or absent). On load a different media id (or present versus absent) is NOT
  an error: the controller loads, then behaves as if the host had swapped the disk at that moment
  (MEDIA_CHANGED set, a command in flight ends with code 9). Removable media may legitimately
  change while a PC is unloaded.

Boot ROMs (4.7 and 10.8.4 change; boot order floppy, then HDD):

1. After the banner: if PRESENT bit 5 (floppy media) is set, READ LBA 0, COUNT 1 from
   the floppy to the usual boot address, polling STATUS. On success with 0x55 0xAA at 510..511:
   print "Booting from floppy" and enter it with boot device 1.
2. Otherwise, if a floppy was present: print "Floppy not bootable" and carry on.
3. Then the HDD exactly as today (same "No disk" and "Disk not bootable" texts when nothing boots;
   "No disk" only when there is neither floppy media nor an HDD). Boot device 0.
4. Entry registers gain the boot device: RV32I a3 = 0 HDD or 1 floppy, a1 = the boot device's
   capacity in sectors; 68k D2 = boot device, D1 = its capacity. Everything else as before.
5. Each ROM gains equates for the floppy registers. The ROM image must stay under the windows
   (60 KiB RV32I, 256 KiB 68k), which it does by a wide margin.

#### 11.1.6 Disk identity and the paged disk image

- `DiskImage` gains `default UUID id() { return null; }` (null means anonymous, as in tests).
- New `DiskChunkStore` (interface): `byte[] load(int chunk) throws IOException`, returning null
  for a chunk never written. The game implements it over files.
- New `PagedDiskImage implements DiskImage`: `PagedDiskImage(UUID id, long capacityBytes,
  DiskChunkStore store, int maxCachedChunks)`. Capacity any multiple of 512 from 1 sector to
  1 GiB (so the 1,474,560-byte floppy fits; its last 64 KiB chunk is half used). Chunks of 64 KiB
  are loaded on first access through the store, kept in an LRU of clean chunks capped at
  `maxCachedChunks`, and dirty chunks are never evicted until taken. So a 1 GiB disk costs memory
  only for what the guest touches. `Map<Integer, byte[]> takeDirtyChunks()` (copies, so the
  world save runs off the machine's thread), `boolean hasDirtyChunks()`, `long capacityBytes()`,
  `UUID id()`. A store failure surfaces as `UncheckedIOException` from `read`.
- `HddController` and `FloppyController` catch `UncheckedIOException` at completion and end the
  command in ERROR with code 7 media error (the HDD gains code 7 too). Nothing else throws out of
  the machine for storage.
- `SparseDiskImage` stays as is (tests, tools).
- `HddController` saved-state version 2 adds the disk id after the capacity. Load fails (cold boot)
  when the id differs, as it already does for presence and capacity (1.5). A version 1 payload
  (no id) is checked by capacity only.

#### 11.1.7 Network card (`Nic`)

A simple card of our own, not a model of a real chip. Frames are Ethernet II without preamble and
FCS: destination MAC (6), source MAC (6), type (2), payload. The card neither pads nor checks the
payload; any length from `MIN_FRAME = 14` to `MAX_FRAME = 1514` is legal.

Registers (window 0x1000, 32-bit, rules of 3):

- 0x00 CTRL (RW, reset 0): bit 0 RX_ENABLE (frames arriving while 0 are dropped and counted),
  bit 1 PROMISC (accept every frame, not only ours, broadcast and multicast), bit 2 IRQ_RX,
  bit 3 IRQ_TX, bit 4 IRQ_LINK.
- 0x04 STATUS (R; W1C for bits 1, 2, 3, 5): bit 0 RX_READY (the receive queue is not empty),
  bit 1 TX_DONE, bit 2 TX_ERROR, bit 3 RX_OVERFLOW (a frame was dropped because the queue was
  full), bit 4 LINK (cable connected and the peer powered), bit 5 LINK_CHANGED.
- 0x08 MAC_LO (R): MAC bytes 0..3, byte 0 in bits 7..0 (so a byte read at offset 0 gives byte 0
  on every CPU, per the value-addressed rule of 1.2). 0x0C MAC_HI (R): bytes 4 and 5 in bits 15..0.
- 0x10 TX_ADDR (RW), 0x14 TX_LEN (RW), 0x18 TX_CMD (W): 1 = send. The frame is copied out of
  memory at once (one `SystemBus.dma` read) into the transmit queue, then TX_DONE is set. Errors
  (TX_ERROR, frame not sent): bad length, DMA range, transmit queue full, no link.
- 0x20 RX_LEN (R): length of the oldest received frame, 0 if none. 0x24 RX_ADDR (RW).
  0x28 RX_CMD (W): 1 = copy the oldest frame to RX_ADDR (RX_LEN bytes) and drop it from the queue,
  2 = drop it without copying. A copy with a bad DMA range sets ERROR_CODE and keeps the frame.
  0x2C RX_COUNT (R): frames queued.
- 0x30 ERROR_CODE (R): 0 none, 1 bad length, 2 DMA range, 3 transmit queue full, 4 receive queue
  empty, 5 no link. 0x34 RX_DROPPED (R): frames dropped since reset (filter misses not counted).
- Queues: `QUEUE_FRAMES = 32` each way (at most about 48 KiB each).
- Receive filter: destination equal to our MAC, or broadcast (all ones), or multicast (bit 0 of
  byte 0 set), or PROMISC.
- IRQ line 10 = (IRQ_RX && RX_READY) || (IRQ_TX && (TX_DONE || TX_ERROR)) || (IRQ_LINK &&
  LINK_CHANGED). No device-internal timing: `nextEventCycle()` is `Long.MAX_VALUE`.
- MAC: `MachineConfig.macAddress` (48 bits in a `long`), part of the config check of saved state.
  The game assigns it (11.2.3): locally administered unicast (byte 0 = random & 0xFC | 0x02).
- Host API (between ticks, on the machine's thread):
  - `long macAddress()`
  - `void setLink(boolean up)`: sets LINK, and LINK_CHANGED on a change; dropping the link
    does not clear the queues.
  - `boolean deliver(byte[] frame, int offset, int length)`: an incoming frame; copies it, applies
    RX_ENABLE and the filter, queues it, raises the line if enabled; false if dropped (queue
    full: RX_OVERFLOW set and RX_DROPPED counted).
  - `byte[] takeOutgoing()`: the oldest transmitted frame (exact length), or null.
  - `int outgoingCount()`.
- Throughput is bounded by the queues and the once-per-tick exchange (11.2.10): at most 32 frames
  each way per tick, about 970 KB/s. Latency is one tick.
- Saved state (tag `nic`, version 1): registers, both queues oldest first, counters, the MAC.

#### 11.1.8 Machine, config and saved state

`MachineConfig` gets one new component after `strictFpu`: `long macAddress` (0..2^48-1). The
eight- and seven-argument constructors stay and pass 0. `MachineConfig.of(cpu, clockHz, ramBytes,
disk)` stays; a new `of(cpu, clockHz, ramBytes, disk, strictFpu, macAddress)` is what the game
uses. Every machine has the floppy controller with its drive (no config flag).

`Machine` additions:

- `com(int)` 0..2, `vga(int)` 0..2, `ps2()`, `keyboard(int port)`, `mouse(int port)`,
  `floppy()`, `nic()`, constants `COM_PORTS = 3`, `DISPLAYS = 3`, `PS2_PORTS = 2`.
- `double busyFraction()`: over the last tick, the share of emulated cycles the CPU spent running
  (not waiting in WFI, STOP or SysCon WAIT, not halted), 0..1. The game's power draw follows it.
- `boolean guestErrorLed()`: SysCon LEDS bit 0.
- `String haltReason()`: the CPU's halt reason when the state is HALTED, else null (for the case
  screen).
- `HddController.takeActivity()` like the floppy's.

Saved state (1.5):

- Envelope version 4. The config block adds the MAC after the strict FPU flag. Device tags add `com3`, `fdc`, `nic`, `vga.1`, `vga.2`; `ps2` moves to its version 2;
  `hdd` to version 2.
- A version 1..3 state is rejected with `StateFormatException` ("saved before the in-game board;
  cold boot"). The memory map changed under it (the RAM caps, VRAM) and no in-game PC
  exists yet, so nothing is lost. `ReviewM68kSystemTest`'s version 1 case changes to expect the
  rejection; `SaveStateE2eTest` keeps deriving its unknown-version case from `STATE_VERSION`.
- Disk identity: the HDD section checks the id (11.1.6); the floppy section never fails on media
  (11.1.5).

Resetting a device on load when a section is missing, the known gap noted in Implementation
status, stays unsolved; version 4 does not need it.

#### 11.1.9 Power, reset and indicator hooks

The core already has `powerOn()`, `powerOff()`, `reset()`, `state()` and `exitCode()`. Added:
`guestErrorLed()`, `busyFraction()`, `haltReason()`, the activity calls. The meaning of the case
buttons and lights is the game's (11.2.9); the core adds no button registers.

#### 11.1.10 RAM limits per CPU

- RV32I: 64 KiB to 128 MiB. 68000: 64 KiB to 13.5 MiB (its 24-bit bus). 68040: 64 KiB to
  128 MiB. Two 64 MiB sticks are fully usable on RV32I and the 68040 (user decision).
- RAM stays one `byte[]` of exactly the configured size, so the CPU fast path keeps its single
  range test. Paged or lazily allocated RAM was considered and rejected: every load and store would
  pay a page lookup, and Java zero-fills a new array anyway, so nothing is saved for a guest that
  touches little. What keeps memory down is the size: the game passes the usable RAM, not the
  maximum, and holds a `Machine` only while the PC is on and loaded (11.2.6), so an off or
  unloaded PC costs no RAM. Saved state already stores only non-zero 4 KiB pages (1.5); at
  128 MiB its page bitmap is 4 KiB.
- Allocating 128 MiB takes tens of milliseconds on the server thread at power-on or load;
  accepted, and measured in the throughput benchmark (C6).
- `MachineConfig.ramBytes` stays an `int` (128 MiB fits).
- `Platform.validate` keeps rejecting a config above the limit. The game never builds one: it
  passes the usable amount (11.2.3), so too much RAM is a game rule, not a core error.

#### 11.1.11 Core order of work

1. C0, contract stubs (first, small, before anything else): every new public signature of 11.3
   compiles, bodies may throw `UnsupportedOperationException`. The game owner codes against them.
2. C1: maps, COM3, three displays, PS/2 either-device, SysCon additions, `Platform.maxRamBytes`.
3. C2: `DiskImage.id`, `DiskChunkStore`, `PagedDiskImage`, HDD version 2 and error code 7.
4. C3: `FloppyController`, both boot ROMs.
5. C4: `Nic`.
6. C5: `MachineConfig`, `Machine` additions, saved state version 4, the end-to-end tests of 11.4.
7. C6: spec update (as-built notes, Implementation status).

#### 11.1.12 As built (core)

C0 landed first with every signature of 11.3 unchanged; C1 to C6 followed. Decisions taken
where 11.1 left room:

- Maps. `Rv32Map` as specified (`VRAM_SIZE` 1.5 MiB, `VRAM_DISPLAY_SIZE`, `RAM_MAX` 128 MiB) plus
  `COM3_BASE`, `FDC_BASE`, `NIC_BASE`, `VGA1_BASE`, `VGA2_BASE`, `LINE_COM3`, `LINE_FDC`,
  `LINE_NIC`, `LINE_VGA1`, `LINE_VGA2` and `vramDisplayBase(n)` / `vramDisplayOffset(n)`.
  `M68kMap` has the same names; there `VRAM_BASE` and `VRAM_SIZE` keep meaning display 0's
  framebuffer and one display's 512 KiB (guest code and tests use them), and the 1.5 MiB range is
  `VRAM_REGION_BASE` / `VRAM_REGION_SIZE`. `LINE_KEYBOARD` and `LINE_MOUSE` keep their names and
  now mean PS/2 port 0 and port 1.
- `Platform.maxRamBytes()` on every platform; the 68k RAM error message now gives the limit in
  KiB (13.5 MiB is not a whole number of MiB). A static `Platform.presentBits` builds SysCon
  PRESENT for both platforms. `Board` takes arrays of three COM ports and three displays plus the
  floppy controller and the network card; `Board.vga()` is `vga(0)`.
- SysCon: a second constructor takes the three display bases; the old one (one display, bases 1
  and 2 read 0) stays for tests. New constants `PRESENT_FLOPPY`, `PRESENT_COM3`,
  `PRESENT_NIC_LINK`, `LED_ERROR`, and `errorLed()`. Reset clears LEDS. Saved-state version 2
  adds LEDS; a version 1 payload loads with LEDS 0.
- `VgaDisplay`: `vramOffset()` gives the slice's start; `vram()` and `dirtyPages()` return the
  shared arrays. Its saved section copies the slice out (512 KiB allocated per save and per load)
  so `StateIo` is unchanged.
- PS/2: `Ps2Controller.PORTS = 2`. A port remembers its selected kind while unplugged, and
  `device(port)` reports NONE then; `setDevice(port, NONE)` unplugs without forgetting the kind.
  Plugging in is the device's power-on (its reset, so 0xAA or 0xAA 0x00 queued) with the port
  enable taken from CTRL. The same kind again while plugged in changes nothing. Only the selected
  device of each port is caught up in time. A new controller has nothing plugged in, so the
  game calls `setDevice` for every port with a cord (and the test rigs plug a keyboard into port
  0 and a mouse into port 1 before `powerOn`).
- Floppy controller: MOTOR_OFF needs no media and completes at once with DONE. Checks run in the
  order bad command, no media, (SEEK) LBA range, bad count, LBA range, DMA range, write protect.
  A READ or WRITE leaves the head over the cylinder of its last sector; the transfer time does
  not add steps for cylinders crossed inside one command (the 11.1.5 totals hold). On load, a
  different write-protect tab also counts as a media change. Extra host calls: `mediaPresent()`,
  `busy()`, `headCylinder()`. The activity flags of the floppy and the HDD are host-side and not
  saved.
- Network card: a successful TX_CMD or RX_CMD sets ERROR_CODE back to 0. A delivered frame shorter
  than 14 or longer than 1514 bytes is dropped and counted in RX_DROPPED. Reset clears the
  registers, both queues and the counter and keeps the link and the MAC. MAC byte 0 is bits
  47..40 of the `long`. Its saved section stores the MAC and refuses a different one. Extra host
  call `incomingCount()`.
- `PagedDiskImage`: the id may be null; `maxCachedChunks` must be at least 1. A stored chunk of
  the wrong length is cut or zero-padded to the chunk's length. `takeDirtyChunks` returns the
  chunks in ascending order, and the taken chunks stay cached as clean.
- `MachineConfig.MAC_MAX`; the compact constructor rejects a MAC outside 48 bits.
- `Machine.busyFraction()`: cycles spent inside `Cpu.run` over the cycles the last tick advanced;
  0 while off; host-side, not saved (it is recomputed every tick). `haltReason()` is the CPU's
  while HALTED. Envelope version 4 writes the MAC after the strict FPU flag; versions 1 to 3 fail
  with "saved before the in-game board; cold boot". Device sections: `syscon`, `timer`, `rtc`,
  `com1`, `com2`, `com3`, `ps2`, `vga`, `vga.1`, `vga.2`, `hdd`, `fdc`, `nic`, `clint` (RV32I),
  `pic`.
- Boot ROMs: the RV32I ROM keeps "floppy media was present" in s2, the 68k ROM in D6;
  "Booting from floppy" is printed before the boot sector is entered.
- Measured (developer's Mac, JDK 17, after warm-up): building a 128 MiB machine about 2 to 5 ms
  (about 50 ms for the first one in a cold JVM, which includes assembling the boot ROM), power-on
  about 1 ms, saving an idle 128 MiB machine about 6 to 9 ms (about 21 KB), building one and
  loading that state about 9 ms. Machine throughput did not drop.

### 11.2 In-game (owner: game)

#### 11.2.1 Model and part models

The model `models/block/pc-case-1.json` (Blockbench, texture size 32, textures `#0` pc-case-1,
`#1` indicator-green, `#2` indicator-red, `#3` components-1), in pixels:

- Case group: shell elements 0..4 (top y 12..13, bottom, back wall z 11..12, walls x 2..3 and
  13..14); the case is open at z 5, where the front panel goes.
- Ports, all on the +x face (x 14..14.25), in the frozen port order used everywhere below:
  0 `ps/2 1` (y 6.75..7.5, z 10..10.75), 1 `ps/2 2` (z 9..9.75), 2 `ethernet` (z 7..8.25),
  3 `com1` (y 5.5..6.25, z 9.375..10.75), 4 `com2` (z 7.875..9.25), 5 `com3` (z 6.375..7.75),
  6 `vga1` (y 4.25..5, z 9.25..10.75), 7 `vga2` (z 7.5..9), 8 `vga3` (z 5.75..7.25), and the
  `power connector` socket (y 1.25..3, z 9..10.75).
- Top (y 13..13.25): `power button` (x 2.25..3.25, z 5..6), `power indicator` (`#1`),
  `error indicator` (`#2`), `reset button` (z 10..11).
- Outside the group: `front panel` (x 2..14, y 0..13, z 4..5), `motherboard` (vertical at
  z 10.25..10.5), `floppy disc` (the floppy disk itself, x 1..5, y 9.625..9.875; its x 1..2 end
  sticks out of the -x wall, where the built-in drive's slot is, so the slot is reachable with
  the front panel on), two `ram` (same name:
  pick by index 22 and 25, slot A is the lower x), `hdd`, `cpu`.
- The model fits one block. Its -x end (buttons, floppy slot) is the PC's front; the ports are at
  the back.

`tools/gen_pc_case.py` (stdlib Python, style of `gen_plc.py` and the splitter in
`gen_circuit_breaker.py`; re-runnable; the export stays untouched because the user re-exports to
it):

- Reads the export and writes part models under `models/block/pc_case/`: `shell` (the case group
  without the buttons and indicators; the port cubes stay in the shell since they never change),
  `front_panel`, `motherboard`, `floppy_disk` (the `floppy disc` cube, drawn only while a disk
  is inserted), `ram_a`, `ram_b`, `hdd`, `cpu`, `power_button`,
  `reset_button`, `power_led`, `error_led`. It drops `groups`, `format_version`, `credit`,
  `texture_size` handling as `gen_plc.py` does, keeps only the textures each part uses, sets
  `particle`.
- Writes `power_plug` (a cube it synthesises in front of the socket: x 14.25..15.5,
  y 1.5..2.75, z 9.25..10.5, texture `#3`) and `data_plug` (a 0.5 px deep cube matching a port's
  face, drawn at every attached cord end).
- Writes the blockstate `blockstates/pc_case.json` (four `facing` variants of `pc_case/shell`,
  y rotation so the model's -x front faces the player who placed it) and item models:
  `pc_case` (shell plus front panel), one per component item reusing that component's part,
  `floppy_disk` (from its part) and the data cord (flat generated item models with textures the user supplies later;
  placeholders until then).
- Asserts every named element exists (two `ram`), prints the port, button and part boxes in the
  frozen order so `PcCaseGeometry` can be checked against them.
- Same treatment for `keyboard-1.json` (11.2.12): `models/block/keyboard.json`, blockstate and item
  model, and its `ps/2` box printed.

Rendering:

- The shell is the baked block model (chunk geometry, free).
- `PcCaseRenderer` (block entity renderer, `CachedBuffers.partial` as `PlcSmallControlRenderer`
  does) draws the parts that change: front panel when attached, each installed component, the two
  buttons (pushed in 0.125 px for 4 ticks after a press), the two lights, the power plug when the
  socket has a wire, and a `data_plug` on every port with a cord.
- Lights: lit = full-bright light and full colour; dark = world light and the colour multiplied
  by about 0.35. No second texture needed.
- The client knows only what the sync tag carries (11.2.11).

#### 11.2.2 Blocks, block entities and registration

Plain Forge `DeferredRegister` as the rest of the mod (`ModBlocks`, `ModItems`,
`ModBlockEntities`); entities in a new `registry/ModEntityTypes.java`; packets appended at the end
of `ModPackets`; partials in `ModPartialModels`; renderers in `ModClientEvents`; lang in
`en_us.json`.

- `pc_case` block (`PcCaseBlock extends ElectricBlock`, Power Grid's base, so the socket is a Power
  Grid terminal): horizontal `facing`, not a full cube (shape from the block entity, as
  `ControlHostBlock` does), drops itself plus every part it holds when broken.
- `PcCaseBlockEntity extends ElectricBlockEntity`: one Power Grid terminal (the socket), the
  installed parts, the runtime (11.2.6), cord links, persistence.
- `keyboard` block and `KeyboardBlockEntity` (11.2.12).
- `data_cord` entity type (`DataCordEntity`, 11.2.5).

#### 11.2.3 Component items

Every component is a plain item with a data table (an enum per family), registered one item per
variant, so adding a variant is one table row, one item model, one lang line.

- `pc_case` (block item; placed with the front panel on and nothing installed).
- `pc_front_panel`.
- `pc_motherboard`: carries its MAC in NBT (`Mac`, a long), assigned on first install if
  absent. All ports and the network card are on the motherboard (onboard ethernet, no separate
  card).
- CPUs (`PcCpuType`: model, clock, power): `cpu_rv32i_10mhz`, `cpu_rv32i_25mhz`,
  `cpu_rv32i_50mhz`, `cpu_68000_8mhz`, `cpu_68000_12_5mhz`, `cpu_68040_25mhz`, `cpu_68040_33mhz`.
  The 68040 items carry the strict FPU option in NBT (`StrictFpu`, default off); sneak and use
  the item in the air to toggle it, the tooltip shows it. It becomes `MachineConfig.strictFpu`.
- RAM (`PcRamType`): `ram_512k`, `ram_1m`, `ram_4m`, `ram_16m`, `ram_64m`. Two slots.
  - Usable RAM = min(stick A + stick B, `Platform.maxRamBytes()` of the CPU), already a multiple
    of 4 KiB.
  - Too much installed (more than 13.5 MiB on a 68000; two 64 MiB sticks fit RV32I and the
    68040): the PC
    boots with the usable amount; the rest is idle. The case screen and goggles show "installed
    X, usable Y". Not an error and no error light, matching 1.1 ("RAM beyond the cap is unused and
    reported").
  - No RAM: does not power on (11.2.9).
- HDD (`PcHddType`): `hdd_16m`, `hdd_64m`, `hdd_256m`, `hdd_1g`. NBT `DiskId` (UUID) assigned on
  first install; the image lives in the disk store (11.2.8), so the drive keeps its data when
  moved to another PC or dropped. Tooltip: capacity and the first 8 hex digits of the id.
- `floppy_disk`: 1.44 MB, NBT `DiskId` (assigned on first insert), `Label` (renamed in an anvil),
  `WriteProtect` (sneak and use in the air toggles it). Stacks to 1.
- `data_cord` (11.2.5). No power cord item of our own: Power Grid's `copper_cord` is used
  (11.2.4).
- Recipes come later; creative tab entries come for free (`ModCreativeTabs` lists every item).

Install and remove (pure rules in `PcSlotRules`, applied by `PcInteractions`):

- The front panel must be off to install or remove any internal component. Remove it with a
  screwdriver: right-click the panel; it goes to the inventory (or drops). Fit it back by
  right-clicking the open side with the panel item.
- Install: right-click the open side with a component item; it goes into its slot (RAM: the aimed
  slot if empty, else the first free one). Remove: sneak and right-click the component with an
  empty hand.
- The PC must be off (no machine running) to install or remove the motherboard, CPU, RAM or HDD; otherwise an action-bar message, nothing changes.
- The CPU, RAM and HDD sit on the motherboard: they need it installed, and the motherboard
  cannot come out while any of them is in. The floppy drive is part of the case and works once a
  motherboard is in.
- Floppy disks: right-click the floppy slot (the `floppy disc` cube's box, a hit box whether or
  not a disk is in) with a disk to insert, with
  an empty hand to eject. Allowed at any time, front panel on or off, PC on or off; that is the
  point of removable media (11.1.5 handles the swap).
- Picking: the block's outline shape includes the interior part boxes while the panel is off;
  `PcCaseGeometry` ray-tests the parts, buttons, slot and ports in block-local space and takes the
  nearest hit (the `ControlRack.controlAt` approach).

#### 11.2.4 Power: socket, power cord and draw

- User decision: Power Grid's own two-conductor cord (`CordItem`, item `copper_cord`; one cable,
  two conductors, `CordEntity` with `wire1` and `wire2`) plugs into the case's power connector.
  No cord item of our own and no single wire returning through ground.
- The socket is two Power Grid terminals of the case, 0 "+" and 1 "-", both placed on the
  `power connector` cube (`TerminalBoundingBox`es splitting the cube's face, grown outward +x by
  0.375 px with a Power Grid expand of 0, as `PlcSmallGeometry` explains).
- `PcCaseBlock` implements Power Grid's `IAcceptCord` (signatures checked with javap):
  `getEndpoint(UseOnContext)` returns `new AutoCordEndpoint(pos, 0, 1, socketFaceCentre,
  plugFacing)` with `plugFacing` the model's +x rotated by `facing`; `cordTerminal(...)` returns
  the socket's placement when the hit is on it, else null so a cord used elsewhere on the case
  does nothing. Power Grid's `IAcceptCord.Handler` (a registered `ICordPlacementHandler`) then
  places, renders, cuts and simulates the cord. The cord's other end goes wherever Power Grid
  allows (a socket, another cord acceptor).
- The plug: `renderPlug()` returns false and `PcCaseRenderer` draws our `power_plug` cube at the
  socket while a cord is attached (terminals 0 and 1 both have connections in
  `ElectricBehaviour.getConnections()`), so the plug matches the model. The implementer checks in
  game what Power Grid draws with `renderPlug()` true; if its plug fits the socket as well, using
  it is acceptable and the cube is dropped (record the choice here).
- Circuit: `buildCircuit` sets two terminals and connects them through the load,
  `builder.connect(ohms, builder.terminalNode(0), builder.terminalNode(1))`, as the PLC's logic
  load does. Supply voltage = V(0) - V(1). Current flows out one conductor and back the other;
  no grounding needed.
- Supply window (pure logic in `PcPower`, constants with comments):
  - Magnitude of the voltage (a bridge rectifier front end, as the PSU spec reasons), so reverse
    polarity works.
  - Can start when 95 V <= V <= 270 V. While running, keeps running from 90 V to 280 V
    (hysteresis). Below 90 V: brown-out, the machine is powered off at once (RAM lost, as a real
    PC). Above 280 V: over-voltage shutdown, same, and the error light comes on.
  - After an outage the PC stays off until the power button is pressed (a real ATX PC's default).
- Draw (watts at the PC's internal rails, then through the supply):
  - Motherboard 8 W (chipset, ports, network card).
  - CPU idle and busy watts per item, blended by `Machine.busyFraction()`: RV32I 10 MHz 0.5/2,
    25 MHz 1/4, 50 MHz 2/8; 68000 8 MHz 0.8/1.5, 12.5 MHz 1/2; 68040 25 MHz 2/5, 33 MHz 2.5/6.
  - RAM per stick: 512 KiB 0.5 W, 1 MiB 0.75, 4 MiB 1.5, 16 MiB 2.5, 64 MiB 4 (installed sticks,
    all of them, even the unusable part).
  - HDD 3 W spinning, 6 W in a tick with activity (`HddController.takeActivity()`).
  - Built-in floppy drive 0.3 W, 3 W while its motor is on.
  - Supply efficiency 0.8: input watts = rail watts / 0.8. Standby while plugged in and off: 1 W.
  - Load resistance R = V^2 / P, clamped to 10 ohm .. 1 Mohm, updated each tick with a 1%
    deadband (the PLC's approach; written fresh in `PcPower`, no PLC code shared). Unpowered: the
    1 Mohm leak.
- Order inside a tick: the block entity tick reads the voltage and updates the supply state (may
  power off), and sets the resistance from the previous tick's draw; the scheduler (11.2.6) then
  runs the machine and records this tick's draw.

#### 11.2.5 Ports and the universal data cord

Ports (pure, `PcCaseGeometry` and `PortType`):

- `PortType { PS2, ETHERNET, COM, VGA }`; `PortRole { PEER, HOST, DEVICE, SOURCE, SINK }`.
  Case ports: PS2 HOST, ETHERNET PEER, COM PEER, VGA SOURCE. Keyboard: PS2 DEVICE. Future monitor:
  VGA SINK; future mouse: PS2 DEVICE.
- Boxes from the model's port cubes, grown 0.375 px outward (+x) and not across (no two overlap),
  rotated by `facing`. The power socket is not a data port.
- A data port host is any block entity implementing `DataPortHost`:
  `int dataPortCount()`, `PortType portType(int)`, `PortRole portRole(int)`,
  `Vec3 portOrigin(int)` (world space), `AABB portBox(int)` (world space),
  `DataLink link(int)`, `void setLink(int, DataLink)` (null to clear). The PC and the keyboard
  implement it; the monitor and mouse will.

Compatibility (pure, `PortCompatibility.check(a, b)` returning OK or a reason):

- Same `PortType`, and roles PEER with PEER, HOST with DEVICE, SOURCE with SINK.
- Both ports free; not the same port; within `MAX_CORD_BLOCKS = 16` of each other.
- Two ports on the same PC are allowed when the rules hold (COM1 to COM2 is a loopback cable).

The cord (`data_cord` item and `DataCordEntity`):

- Not a Power Grid wire: Power Grid's endpoint types are a closed enum and every endpoint resolves
  to an electrical node, so a data cord in its registry would join circuits. The data cord is our
  own item and entity, with Power Grid's look.
- Placing: right-click a port with the cord, which stores the port (pos, index, type) in the
  stack's NBT; right-click a second port, which must pass `PortCompatibility`. One item per cord,
  any length up to the limit. Right-click air or sneak cancels. The `DataPortHost` picking reuses
  `ProtrudingHitboxPicker` style ray tests in block-local space.
- The entity holds both endpoints (pos, port index), the port type and a colour; it sits at the
  midpoint. The link is also written into both endpoint block entities' NBT (`DataLink(peerPos,
  peerPort, cordId)`), so the exchange (11.2.10) works from block entities alone, without loading
  the entity's chunk. The entity is only for drawing and breaking.
- Breaking: attack the cord to drop it (and clear both links); breaking either block drops its
  cords; a link whose other end is gone on load (block missing, port mismatched, no matching
  `cordId`) is cleared and the cord dropped.
- Drawing: a hanging catenary like Power Grid's hanging wire (call Power Grid's public render
  helpers where they fit, `HangingWireRenderer`/`CurveParameters`; copy only what is needed and
  check Power Grid's licence first), coloured by type (VGA blue, COM grey, PS/2 purple, ethernet
  yellow), with a `data_plug` cube at each end.

#### 11.2.6 Running the machines: scheduler, budget, crash safety

- `PcRuntime` (one per powered PC, server only) owns the `Machine` and every call into it. Each
  call is wrapped: any `RuntimeException`, `StackOverflowError` or `OutOfMemoryError` from the
  emulator crashes that PC only: the machine is dropped, the PC is marked crashed with the message,
  the error light comes on, the event is logged once with the position. Pressing the power button
  clears it and cold boots.
- `PcScheduler` (server-wide, registered on `TickEvent.ServerTickEvent` END, so after every block
  entity tick of the tick):
  1. Apply queued host input (keyboard events, plug and unplug of PS/2 devices, floppy swaps,
     link states).
  2. Run the PCs in round-robin order (the start index rotates every tick so no PC is always
     last): budget for this PC = min(`hostBudgetPerPcMicros`, what is left of
     `hostBudgetTotalMicros`); `machine.runTick(TICK_NANOS, budget)`. A PC whose turn comes with
     nothing left runs with budget 0: its emulated time stands still this tick (1.4 dilation), and
     it gets an early turn next tick.
  3. Exchange phase (11.2.10).
  4. Record each PC's draw and light state for the next block entity tick.
- Threading: everything on the server thread; `Machine` objects stay independent so a worker
  pool can come later.
- Caps: `maxRunningPcs` (default 64) and `maxTotalRamMiB` (default 2048, the sum of the usable
  RAM of every machine held in memory). Pressing power when either cap would be passed refuses
  with a message and the error light for 3 seconds. A PC loading with a saved running state that
  would pass the RAM cap stays frozen (machine not built, state file kept) and retries every
  100 ticks, so loading never discards a running PC. With the defaults a server holds at most
  2 GiB of emulated RAM; owners size it to their heap.
- The 68040 note of Implementation status holds: at the default 2 ms a busy 68040 runs at about a
  fifth of real time. The config lets server owners raise it.

#### 11.2.7 Server config

New `foundation/ServerConfig.java` (`ForgeConfigSpec`, `ModConfig.Type.SERVER`, registered in
`CreateFlippies` next to the client config):

- `pc.hostBudgetPerPcMicros` (default 20000, range 100..50000; raised from 2000 for Linux).
- `pc.hostBudgetTotalMicros` (default 20000, range 1000..45000).
- `pc.maxRunningPcs` (default 64).
- `pc.maxTotalRamMiB` (default 2048, range 64..65536).
- `pc.diskCacheChunksPerDisk` (default 64, so 4 MiB of clean chunks per open disk).
- Reserved for the monitor: `pc.displaySyncRadius` (default 32 blocks),
  `pc.displaySyncBytesPerPlayerPerTick` (default 32768).
- The 68040 strict FPU is per CPU item (11.2.3), not a config value.

#### 11.2.8 Persistence and the chunk-unload freeze

- Block entity NBT (disk save): `PcId` (UUID, assigned at placement), `Parts` (item stacks for
  front panel, motherboard, CPU, RAM A, RAM B, HDD, inserted floppy), `Supply` (on
  or off), `Crashed` and `CrashMessage`, `StateGen` (long), `Links` (port to `DataLink`).
- Machine state lives in a side file, not in chunk NBT: a busy 128 MiB PC deflates to megabytes,
  which does not belong in a region file or on the main thread at chunk load. Path:
  `<world>/create_flippies/pcs/<PcId>.state`, holding `StateGen` then `Machine.saveState()`.
- Save: `saveAdditional` on the server, when a machine exists, takes `saveState()` (machine
  thread, now), bumps `StateGen`, hands the bytes to a single background writer (temporary file,
  then atomic rename). A powered-off PC deletes its file. `setChanged` every 600 ticks while
  running, so autosaves include it; never per tick.
- Chunk unload (`onChunkUnloaded`): save as above, flush the disks' dirty chunks, drop the
  machine from the scheduler. Emulated time is frozen while unloaded (1.5).
- Load: on the first server tick after load, build `MachineConfig` from the parts, create the
  machine, read the side file. The file's `StateGen` must equal the NBT's (a world backup can pair
  region files with newer or older side files); then `loadState`. Any mismatch or
  `StateFormatException`: log, then cold boot if the saved supply state was on and power is
  there, else stay off. Then insert the floppy (a different disk than saved shows up as a media
  change, 11.1.5).
- Disk store (`DiskStore`, per server): `<world>/create_flippies/disks/<DiskId>/<chunk>.bin`
  (deflated 64 KiB chunks; never-written chunks have no file). Opens one shared
  `PagedDiskImage` per id (a duplicated creative item gets the same image), implements
  `DiskChunkStore`, writes dirty chunks on world save, on chunk unload of the PC, and when a disk
  leaves a PC. Writes go through the same background writer.
- Broken PC: drops its parts (the disks keep their images), clears its links, deletes its state
  file.

#### 11.2.9 Buttons and indicators

- Power button (right-click it, empty hand or anything that is not a tool with its own action):
  - Off, crashed or off by guest: clear the crash, check that a motherboard, CPU and RAM are
    installed, the supply is in the start window and the running cap allows it; then build the
    machine and `powerOn()`. Any check failing: an action-bar message naming it, error light for
    3 seconds.
  - On (RUNNING or HALTED): `powerOff()`, the hard way (no soft shutdown signal).
- Reset button: `reset()` when a machine exists; else nothing.
- Power light: lit while a machine exists and is RUNNING or HALTED.
- Error light, lit while any of: crashed; HALTED (double fault); OFF_BY_GUEST with a non-zero exit
  code (until the next power on); over-voltage shutdown; the guest's SysCon LEDS bit 0; a refused
  power-on (3 seconds).
- Button presses animate (synced press tick) and play a click (`ModSounds`).

#### 11.2.10 Data paths between ports

All of it runs in the scheduler's exchange phase, after every PC ran this tick, over links sorted
by position so results do not depend on iteration order.

- Ethernet (PC to PC): for each link, `takeOutgoing()` frames of A go to B's `deliver` and the
  other way. `setLink(up)` on both NICs each tick: up while the cord exists and both PCs are loaded
  and powered. Frames sent while the link is down fail at send time (code 5), so none are lost
  silently in the exchange.
- COM (PC to PC, or COMn to COMm on one PC): `hostRead` of each side into a per-link buffer
  (4 KiB each way), then `hostWrite` of the other side, keeping what was not accepted for the next
  tick (oldest dropped past 4 KiB). `setHostConnected(true)` on both UARTs while linked and both
  sides are loaded; false otherwise. Baud mismatch is not modelled.
- PS/2 (case to keyboard, later mouse): when a keyboard links to case port n, the scheduler calls
  `ps2().setDevice(n, KEYBOARD)`; unlinking or the keyboard unloading sets NONE. Key events reach
  `keyboard(n)` (11.2.12).
- VGA (case to monitor, later): no data moves until monitors exist. Server-side interface now:
  `PcCaseBlockEntity.display(int index)` returns a `PcDisplayView` (null while the machine is not
  running) with `long generation()`, `int mode()`, `VgaFrame snapshot()`, `BitSet
  dirtyRowsSince(long)`, `int copyVisibleInto(byte[])`. Only the scheduler's thread calls it,
  between ticks.
- Framebuffer sync plan (built with the monitor): the monitor block entity tracks the display's
  generation; players within `displaySyncRadius` who track the monitor's chunk get a full frame
  first (mode, palette, cursor, visible bytes, deflated), then only rows changed since the
  generation that player last received, at most every 2 ticks per monitor and within
  `displaySyncBytesPerPlayerPerTick` (the rest waits; generations coalesce). Text mode is 4000
  bytes, so it is always a whole frame; a 640x480x8 frame is 300 KiB raw and usually far less
  deflated. Palette changes force a full frame (3.7 already marks them).

#### 11.2.11 Client sync and the case screen

- Sync tag (`getUpdateTag`, kept small, sent on change only): parts present (bitmask), facing,
  front panel, power and error lights, button press ticks, socket wired, ports with cords, the
  CPU and RAM summary for goggles. Never the machine state.
- Goggles (`addToGoggleTooltip`): state, supply voltage, draw in watts, speed percent, error
  reason.
- Case screen: screwdriver right-click on the case (not on the panel, which removes it). Read-only
  list: parts (CPU and clock, strict FPU, RAM installed and usable, HDD size and id prefix, the
  inserted floppy's label with its write-protect state, MAC), state, exit code or halt
  reason or crash message, speed, supply voltage and draw, each port's peer. No BIOS-style
  settings. Status by polling, as the PLC does: `PcActionPacket(pos, POLL)` every 10 ticks while
  open, the server checks reach (16 blocks) and answers `PcStatusPacket(pos, PcStatus)` to that
  player only. `OpenPcCasePacket` opens it.

#### 11.2.12 Keyboard block

Model `models/block/keyboard-1.json` (texture `keyboard-1`, 64x64): an unnamed body x 0..7,
y 0..1, z 0..16 and a `ps/2` cube at x 7..7.25, y 0.25..0.75, z 0.25..0.75, the cord attachment
point on the body's +x side.

- Block `keyboard`, block entity `KeyboardBlockEntity implements DataPortHost` with one port
  (PS2, DEVICE). Horizontal `facing`, placed so the model's -x edge (the near side, away from the
  port) faces the player who placed it; must stand on a block top. Shape: the body box rotated.
  No power of its own (bus powered from the PS/2 port).
- Cord: the universal data cord from the keyboard's `ps/2` cube to either case PS/2 port. The port
  decides the controller port: case `ps/2 1` is PS/2 port 0, `ps/2 2` is port 1, and the core's
  either-device ports (11.1.4) make the keyboard work in both.
- Typing (client):
  - Right-click the keyboard with an empty hand to ask to type: `KeyboardCapturePacket(pos,
    true)`. The server grants it when nobody else is typing on it, the player is within 8 blocks
    and the keyboard is linked; it answers `KeyboardCaptureStatePacket(pos, granted, reason)`.
  - Granted: the client opens `KeyboardCaptureScreen`, a transparent screen that does not pause
    the game, shows "Typing on <PC>. Press Esc twice to release." and takes every key: `keyPressed` and
    `keyReleased` map the GLFW key code (positional, US-layout names) to a `Ps2Key` through a pure
    table (`GlfwPs2Keys`), unknown keys ignored. GLFW repeat events are dropped: the emulated
    keyboard does typematic repeat itself in emulated time (3.6), honouring the guest's 0xF3 rate.
  - Escape (user decision): a single Escape is a normal key for the PC, set 2 make 0x76 and
    break 0xF0 0x76, sent like any other key (the screen overrides `shouldCloseOnEsc` to false).
    A rapid double press releases the capture: when an Escape press comes within the double-press
    window of the previous Escape press, the client closes the screen and sends
    `KeyboardCapturePacket(pos, false)`.
    - Window: 300 ms by default, measured press to press, a client config value
      (`ClientConfig` `keyboardReleaseDoublePressMillis`, range 150..1000).
    - What the PC receives: the first Escape is sent normally (make, and break when the key comes
      up), because the client cannot know a second press is coming. The second press is consumed:
      neither its make nor its break is sent. So releasing always leaves exactly one Esc keystroke
      in the guest, which the player should expect.
    - A third Escape inside the window does not count: the window restarts after a release, and
      after a single Escape the next press counts as the second only if no other key was pressed
      in between (typing Esc, x, Esc quickly sends all three).
    - The pure rule lives in `KeyboardReleaseDetector` (client side, no Minecraft types: press
      time, key, returns SEND or RELEASE), tested on its own.
  - Events are batched per client tick: `KeyboardKeysPacket(pos, short[] events)`, each event
    `Ps2Key` ordinal << 1 | down, at most 64 per packet.
- Server:
  - Validates every `KeyboardKeysPacket`: the sender is the current typist of that keyboard,
    within 8 blocks, the keyboard still exists and is linked; else ignored (and the capture ends).
  - Queues the events on the linked PC's runtime; the scheduler applies them before `runTick`
    as `keyboard(port).keyDown/keyUp`. A PC that is off or not loaded drops them.
  - One typist per keyboard. Capture ends on Escape, logout, dimension change, distance over 8,
    the keyboard or cord breaking, or the PC unloading; on any end the server sends key-up for
    every key still held, so no key sticks. When the client releases by double Escape, the first
    Escape's break is still sent if that key was down, so the guest sees a complete keystroke.
  - Keyboard lights (0xED) are not drawn: the model has none. The capture screen may show
    Num, Caps and Scroll from `PcStatus` later.
- Packets (appended to `ModPackets`): `KeyboardCapturePacket` (to server),
  `KeyboardCaptureStatePacket` (to client), `KeyboardKeysPacket` (to server).

#### 11.2.13 As built (game owner)

Built as planned above, with these decisions and deviations:

- Socket terminals (11.2.4): Power Grid is told the two terminals are a speck inside the bottom
  wall (`PcCaseGeometry.HIDDEN_TERMINAL`), not on the socket face. Power Grid's `CordItem` checks
  `terminalIndexAt` before it asks the `IAcceptCord` handlers, and a click on a terminal starts a
  single-conductor "half" connection, so with terminals on the socket the cord could not plug in
  with one click. `cordTerminal` still outlines the socket for a held cord, and `getEndpoint`
  returns null anywhere else on the case.
- Plug (11.2.4, the choice to record): our `power_plug` cube is drawn by `PcCaseRenderer` while
  both terminals have a wire; `renderPlug()` is false and the endpoint's plug facing is null, so
  Power Grid draws no plug of its own. The cord ends at the plug cube's outer face. Power Grid's
  plug was not tried in game.
- Picking (11.2.3): every target box is part of the block's outline shape, so the point where
  vanilla's ray meets the shape is the nearest hit; `PcCaseGeometry.targetAt` takes the smallest
  box containing that point instead of casting a ray of its own. The floppy slot's box is
  x 1..3, y 9.375..10.125, z 5.75..10.25 (the disk's protruding end grown 0.25 px up and down, so
  the slot can be hit with no disk in; the disk itself also counts as the slot). The "open side"
  is the inside box x 3..13, y 1..12, z 5..11, reachable only with the panel off.
- Data plugs (11.2.1, 11.2.5): drawn by the cord entity's renderer at both ends (it carries both
  port origins and normals), not by the case renderer, so the case's sync tag has no port mask.
  `gen_pc_case.py` writes one plug per port type, `data_plug_ps2`, `_ethernet`, `_com`, `_vga`,
  each the size of that type's case port face; the keyboard end uses the PS/2 one (0.75 px against
  the keyboard's 0.5 px port).
- Stale links (11.2.5): a link found stale when its peer is loaded is cleared without dropping an
  item, because the side that broke the cord (attack, or a block broken) already dropped it. The
  cord entity discards itself when a loaded end no longer holds its id. The cord entity's UUID is
  the links' `cordId`.
- Floppy label (11.2.3): the disk's anvil name (vanilla's display name), not a separate `Label`
  tag.
- Restore (11.2.8): for 40 ticks after a case loads the supply checks are suspended and a PC that
  was on waits for Power Grid to solve (start window reached, or the 40 ticks up) before it
  decides, because on the first tick the network may not have solved yet and reads 0 V. The
  machine state is written by `saveAdditional` (the chunk save just before an unload), and
  `onChunkUnloaded` only flushes the disks and drops the machine. The floppy is inserted before
  `loadState`, which is what the core's `FloppyController.loadState` compares against.
- Screwdriver (11.2.3, 11.2.11): the case is a `ConfigurableBlock`, so the screwdriver reaches it
  in the idle mode, or a placement mode with nothing in the other hand. On the front panel it
  takes the panel off, anywhere else it opens the case screen.
- The wrench does not turn a case (its ports would move under their cords).
- Config ranges (11.2.7) the spec left open: `maxRunningPcs` 1..4096,
  `diskCacheChunksPerDisk` 4..16384.
- The capture screen reads "Typing on the keyboard at x, y, z" (the client does not know the PC).
- Buttons click with the relay click sound. No recipes: the mod has none for similar items.
- The data cord's item texture is a placeholder the generator writes only if the file is missing.

Files (game owner): `tools/gen_pc_case.py`; `content/pc` (`PcCaseBlock`, `PcCaseBlockEntity`,
`PcCaseGeometry`, `PcSlot`, `PcSlotRules`, `PcRamRules`, `PcPower`, `PcRuntime`, `PcScheduler`,
`PcServer`, `PcServerEvents`, `PcLinkExchange`, `PcStateStore`, `DiskStore`, `PcFileWriter`,
`PcInteractions`, `PcStatus`, `PcDisplayView`, the part items and their type tables);
`content/data_cord` (`PortType`, `PortRole`, `DataLink`, `DataPortHost`, `PortCompatibility`,
`DataCords`, `DataCordItem`, `DataCordEntity`); `content/keyboard` (`KeyboardBlock`,
`KeyboardBlockEntity`, `KeyboardCapture`, `KeyboardCaptures`, `KeyboardEvents`); `client/pc`
(`PcCaseRenderer`, `DataCordRenderer`, `PcCaseScreen`, `KeyboardCaptureScreen`, `KeyboardClient`,
`GlfwPs2Keys`, `KeyboardReleaseDetector`); the six packets; `foundation/ServerConfig.java`;
`registry/ModEntityTypes.java`; generated models under `models/block/pc_case/`, `keyboard.json`,
the two block states, item models, loot tables.

Tests (game owner), all passing: `PcCaseGeometryTest`, `PortCompatibilityTest`,
`PcSlotRulesTest`, `PcRamRulesTest`, `PcPowerTest`, `PcPowerCircuitTest` (real Power Grid solve),
`PcSchedulerTest`, `LinkExchangeTest` (real machines), `PcStateStoreTest`,
`KeyboardReleaseDetectorTest`, `GlfwPs2KeysTest`, `KeyboardCaptureTest`: 58 tests.

#### 11.2.14 CRT monitor

Model `models/block/crt-1.json` (texture `crt-1`, 64x64, exported next to the model): the cabinet
and frame (unnamed elements 0..4, x 0.5..12, y 0..12, z 1..15), `screen` x 11..11.75, y 1.5..10.5,
z 2.5..13.5 (its +x face, 11 px wide by 9 px tall, is the display surface), and on the back (-x)
`vga in` x 0..0.5, y 0.5..1.5, z 1.5..3.25 and `power in` x 0..0.5, y 0.5..1.5, z 4..5.75.

User decisions: the screen is 11:9 at a "360p equivalent", so a nominal 440x360 canvas; when the
source does not fit, the canvas grows rather than the source shrinking; the monitor shows the
live picture on its front in the world, with no screen or menu.

Block and models:

- Block `crt` (`CrtBlock extends ElectricBlock`, `IAcceptCord`), block entity `CrtBlockEntity
  extends ElectricBlockEntity implements DataPortHost`, block item `crt`, loot table dropping
  itself. Horizontal `facing` is the direction the screen faces; it is placed facing the player.
  The model's front is +x, so every model box turns with `PcCaseGeometry`'s rotation applied to
  the opposite of `facing` (`CrtGeometry`). Shape: the cabinet box plus the two port cubes. The
  wrench does not turn it (its ports would move under their cords). Nothing to click on it.
- `tools/gen_crt.py` (reuses `gen_pc_case.py`'s helpers; the export stays untouched) writes
  `models/block/crt.json` (the whole export, screen included, as the block model),
  `models/block/crt/power_plug.json` (a cube synthesised behind the socket, x -1.25..0,
  y 0.75..1.25, z 4.25..5.5, Power Grid's plug texture), `blockstates/crt.json` (y rotation of
  the opposite facing, so +x faces `facing`), the item model, and copies the exported
  `crt-1.png` to `textures/block/crt-1.png` when missing or different. A texture named without a
  namespace in the export is read as `create_flippies:block/<name>`.

Ports and power:

- One data port, index 0, `vga in`: `PortType.VGA`, `PortRole.SINK`. `PortCompatibility` then
  pairs it with the case's three VGA ports (SOURCE) only. The port's click box is the cube as
  authored, not grown outward: its outer face is the block's edge, and growing it would leave the
  block. The cord's plug is the case's `data_plug_vga`.
- The case port decides the display: `vga1` is display 0, `vga2` display 1, `vga3` display 2,
  each an independent `VgaDisplay` (11.1.3). `PcCaseGeometry.portType(int)` and `portRole(int)`
  are now static so the pairing is tested without a world; the case's block entity uses them.
- Power: the case's socket design (11.2.4, 11.2.13) on `power in`: two Power Grid terminals
  hidden inside the cabinet, Power Grid's copper cord plugged in with one click on the socket
  through `IAcceptCord`, our plug cube drawn while both terminals are wired, the load between the
  two terminals. Same window as the case: comes on from 95 to 270 V (magnitude), stays on from 90
  to 280 V. No switch: it is on whenever its supply is. Draw at the cord (`CrtPower`): 60 W with a
  picture (a small colour VGA monitor; a 14 inch one drew about 60 to 80 W), 15 W on without a
  signal (the power-saving standby of a monitor that lost sync), the leak when off. R = V^2 / P
  with the case's clamp and 1% deadband.
- Persistence: NBT `Link` (the `DataLink`) and `On`. The CRT holds no machine state. For 40 ticks
  after loading a CRT that was on stays on while Power Grid solves (the case's grace), so a
  chunk reload or a server restart does not flash it off. Links are validated every second as the
  keyboard's are.

Signal: the CRT has a signal while it is on, its link pairs with a loaded case's VGA port, and
that case's machine exists (`PcCaseBlockEntity.display(n)` is not null). `On`, `Signal`, the
socket state and the rounded supply voltage are in the sync tag. Goggles: screen state (off, no
signal, picture), supply voltage and draw.

The canvas (`CrtCanvas`, pure):

1. Source size in pixels: graphics modes as they are; text mode is its cells at the font's 8x16,
   so 80x25 is 640x400.
2. Scale k: the largest whole number with k times the source inside 440x360, and at least 1. A
   source larger than 440x360 in either direction stays at 1: never shrunk.
3. Canvas: 11n x 9n with n = max(40, ceil(k * width / 11), ceil(k * height / 9)): the smallest
   11:9 size of at least 440x360 that holds the scaled source.
4. The source is centred (rounded towards the top left); the rest is black, a letter or pillar
   box. Pixels stay square because canvas and screen are both 11:9. Nearest-neighbour only.

Worked examples (tested in `CrtCanvasTest`):

- 440x360: k 1, canvas 440x360, offset 0, 0.
- 320x200 (mode 1): k 1 (1.375 across would fit, but only whole scales), canvas 440x360, offset
  60, 80.
- 640x480 (modes 2 and 3): k 1, n = max(40, 59, 54) = 59, canvas 649x531, offset 4, 25.
- Text 80x25 (640x400): k 1, n = max(40, 59, 45) = 59, canvas 649x531, offset 4, 65.
- Smaller sources: 160x100 at k 2 in 440x360 at 60, 80; 220x180 at k 2 fills 440x360.

Text mode is drawn on the client: the font is the game's own `minecraft:textures/font/ascii.png`,
laid out in code page 437 order, each 8x8 glyph doubled to the VGA's 8x16 cell (box drawing
stays joined; the sheet's empty cells, mostly accented letters, draw blank; a resource pack's
font changes it). `CrtFont` and `CrtRaster` are pure: attribute bits 3..0 foreground, 6..4
background, bit 7 blink (CTRL bit 3, the off phase shows the background) or a bright background,
colours through palette entries 0..15; the cursor is the cell's two bottom rows in its
foreground, blinking every 266 ms, characters every 533 ms; display off is black. Graphics: 8 bpp
through the palette, 4 bpp high nibble first. The core had no font, so rendering text on the
client keeps the wire format the raw VRAM bytes (4000 bytes for a text screen).

Framebuffer sync (the plan of 11.2.10, as built):

- Server, `CrtSync` (owned by `PcServer`, run in its tick right after the scheduler, so every
  display is read between machine ticks on the server thread). Each loaded CRT's `CrtStream`
  captures its source each tick: one generation compare when nothing changed, else one
  `copyVisibleInto` into its own second buffer. The core marks rows dirty by 4 KiB page (a
  one-byte store marks the 6 or 7 rows of 640 bytes sharing the page), so the stream compares
  only the rows `dirtyRowsSince` names with what it held, and stamps the rows whose bytes really
  changed with a capture sequence number. A cursor or CTRL change is stamped as a header change
  with no rows; a palette change bumps a palette version (the bytes did not change). The epoch
  moves when the source is a different `VgaDisplay` object (the PC was switched off and on, or
  unloaded and loaded), when the generation went backwards, when the layout (mode, size, pitch,
  depth) changed, and after any tick without a signal.
- Viewers: players who track the CRT's chunk (so within the server's view distance) and are within
  `pc.displaySyncRadius` blocks (default 32). A player leaving the set is forgotten, so coming
  back starts with a full frame.
- Per viewer the stream keeps the epoch, sequence number and palette version last sent. A viewer
  on another epoch gets a full frame (header, palette, every row). Otherwise it gets the rows
  stamped after its sequence number, the palette if its version moved, and always the header (so
  a cursor move is a few dozen bytes; the client redraws every row when the cursor or CTRL
  changed, which is 25 text rows). Nothing changed for it: nothing sent. Players in the same state
  share one encoding per tick.
- Rate: per CRT and player at most `pc.displaySyncMaxFps` updates a second (new, default 20, range
  1..20; 20 is every tick, so the plan's "every 2 ticks" is the value 10). Changes in between are
  not lost, they arrive together. Bandwidth: `pc.displaySyncBytesPerPlayerPerTick` (default 32768)
  fills a per-player bucket each tick, capped at 4 ticks' worth; updates go nearest CRT first while
  the bucket is above zero, and one update may overdraw it (a frame is never split), after which
  that player gets nothing until it is repaid. So a player averages at most the allowance per
  tick plus one update. The rest waits; generations coalesce.
- Wire (`CrtFrameCodec`, packet `CrtFramePacket(pos, payload)`, appended to `ModPackets`): version,
  flags (full, palette), epoch, mode, width, height, pitch, depth, cursor column and row, CTRL,
  the palette (256 x RGB) when flagged, the rows as runs (first, count), then the rows' raw bytes
  deflated (best speed). The client checks everything (header ranges, rows inside the height, the
  inflated length exact, no trailing bytes) and drops what does not fit; a payload is read up to
  516 KiB.
- Client (`CrtClient`): per CRT a `CrtImage` (pure: header, palette, bytes; a delta applies only on
  a full frame of the same epoch and layout; a palette, cursor or CTRL change marks every row) and a `DynamicTexture` of the canvas size, registered
  under `create_flippies:crt_screen/<n>`. An update only marks rows dirty; when the renderer asks
  for the texture, the dirty rows (and blink phase changes) are drawn into the texture's pixels and
  only that band of rows is uploaded (`NativeImage.upload` with a row offset, no blur). A mode or
  size change makes a new texture. The texture is freed when the CRT unloads or is broken, when it
  loses its signal (not when it gains it, as the first frame can arrive before the block entity's
  update), on leaving the level and on logout.
- Renderer (`CrtRenderer`): the texture on the `screen` cube's +x face, 0.01 px in front of it,
  with `RenderType.textPolygonOffset`, full bright. Off: nothing drawn, the model's dark glass
  shows. On without a signal (or before the first frame): the glass glows a faint near-black and
  shows a dim "No signal". Warm-up: after coming on the picture fades in from 10% over 30 ticks.
  The power plug is drawn while the socket is wired.

Shared files touched, each in blocks marked `// CRT`: `ModBlocks`, `ModItems`, `ModBlockEntities`,
`ModPackets`, `ModPartialModels`, `ModClientEvents`, `ServerConfig` (field, definition and accessor
blocks; the two `displaySync` comments no longer say "reserved"), `PcServer` (field, accessor and
the tick call), `en_us.json`. Also `PcDisplayView` (implements `CrtStream.Source`, with the
geometry, CTRL, CURSOR and palette reads) and `PcCaseGeometry` (static port type and role).

Files: `content/crt` (`CrtBlock`, `CrtBlockEntity`, `CrtGeometry`, `CrtPower`, `CrtCanvas`,
`CrtFont`, `CrtRaster`, `CrtImage`, `CrtFrameCodec`, `CrtStream`, `CrtRateLimit`, `CrtSync`),
`network/CrtFramePacket`, `client/pc` (`CrtClient`, `CrtRenderer`), `tools/gen_crt.py`, the
generated models, block state, item model, texture copy and loot table.

Tests: `CrtCanvasTest` (the examples above, boxing, refusals), `CrtStreamTest` (against a real
`VgaDisplay`: full frame first, one changed row (not its page's neighbours), rows missed while
rate limited arrive together, palette, mode change, restart and signal loss give a new epoch, a
foreign delta is dropped, shared encodings, leavers start over, a store that changes nothing and a
cursor move, writes outside the picture), `CrtFrameCodecTest` (round
trips, runs, deflate size, malformed payloads), `CrtRateLimitTest`, `CrtPowerTest`,
`CrtPortsTest` (the CRT pairs with case ports 6..8 only), `CrtRasterTest`.

#### 11.2.15 Keyboard and mouse

User decisions: one device holding a keyboard and a mouse, its model a copy of the keyboard's to
be edited later; two PS/2 ports; the player's mouse drives the PC's mouse, with a key in
Minecraft's Controls that switches between that and looking around (right Ctrl, right Command
on macOS).

- `KeyboardMouseEntity` (entity and item `keyboard_and_mouse`, "Keyboard and Mouse") extends
  `KeyboardEntity`: placed, supported, broken and dropped like the keyboard.
- Model `block/keyboard_and_mouse.json` (and the item model): the keyboard's model with a second
  port cube, `ps/2 2`, 1 px further along +z beside `ps/2`. Same texture. The cubes are in
  `KeyboardGeometry`, checked against the model file by `KeyboardMousePortsTest`.
- Ports, both PS2 and DEVICE: port 0 (`ps/2`) is the keyboard, port 1 (`ps/2 2`) the mouse.
  Each is linked to a case's PS/2 port on its own. The case asks the device which kind sits
  behind the port its cord is on (`KeyboardEntity.deviceKind`), and `PcRuntime` plugs that kind
  into the controller port: a mouse cord on case `ps/2 2` gives the guest a mouse there, and on
  `ps/2 1` a mouse there too. Port 1's link saves as "Link1"; port 0 keeps the keyboard's "Link".
- Using it: right-click with an empty hand, as the keyboard. The capture is the keyboard's
  (one typist, double Escape releases, the same end conditions); it is refused as "unlinked"
  only when neither port leads to a case. Keys go to the case on port 0, and are dropped while
  port 0 has no cord.
- The mouse, while the capture screen gives it to the PC (the state it starts in):
  - `MouseHandlerPcMouseMixin` takes the movement `MouseHandler.turnPlayer` would turn the camera
    by, so the camera stays still, and hands it to `KeyboardCaptureScreen`.
  - Left, right and middle buttons are held as PS/2 button bits; the wheel is ignored (the
    emulated mouse has none).
  - Once per client tick, if anything changed, a `KeyboardMousePacket` (entity id, dx, dy up,
    buttons) goes to the server: one screen pixel is one PS/2 count. The server checks it as it
    checks key packets, clamps each axis to 4096, and queues it on the case behind port 1.
- The toggle key `key.create_flippies.pc_mouse_toggle` ("PC mouse / look around", category
  "Create: Flippies") is a KeyMapping, so it can be rebound in Controls. It never reaches the
  PC. Switching drops movement not yet sent and lets go of the buttons. A second hint line under
  the capture screen's says which state it is in and names the key.
- Any end of the capture also lets go of the mouse buttons, as held keys get their key-ups.
- Not done: no recipe (the keyboard has none either); the model is the keyboard's until it is
  remodelled.

### 11.3 Ownership split and the API between the owners

Both owners work in the same tree at the same time. No file has two owners. The gauge and design
workbench owner is also active: `content/gauge`, `content/procedural_gauge`,
`content/design_workbench`, `client/design` are theirs; neither PC owner edits them.

Core owner (pure Java, house rules of this spec):

- Main, under `computer/`: `device/FloppyController.java`, `device/Nic.java`,
  `device/PagedDiskImage.java`, `device/DiskChunkStore.java`, `device/Ps2DeviceKind.java` (new);
  edits to `device/DiskImage.java`, `HddController.java`, `VgaDisplay.java`, `SysCon.java`,
  `Ps2Controller.java`, `Ps2Key.java` (Javadoc only); `machine/Rv32Map.java`, `M68kMap.java`,
  `Rv32Platform.java`, `M68kPlatform.java`, `Platform.java`, `Board.java`, `Machine.java`,
  `MachineConfig.java`, `BootRom.java`, `M68kBootRom.java`.
- Tests under `test: computer/**`, including updates to existing tests that the map, ROM and
  version changes break.
- Spec: the core parts of this section's as-built notes and Implementation status (C6).

Game owner:

- New packages: `content/pc/**` (block, block entity, runtime, scheduler, rules, geometry, power,
  stores, interactions, items), `content/keyboard/**`, `content/data_cord/**` (item, entity,
  links, port interfaces, compatibility, exchange), `client/pc/**` (renderers, case screen,
  capture screen, `GlfwPs2Keys`), new packets in `network/` (`OpenPcCasePacket`,
  `PcActionPacket`, `PcStatusPacket`, `KeyboardCapturePacket`, `KeyboardCaptureStatePacket`,
  `KeyboardKeysPacket`), `foundation/ServerConfig.java`, `registry/ModEntityTypes.java`,
  `tools/gen_pc_case.py`, the generated models and blockstates, item models and textures for
  the new items.
- Tests under `test: content/pc/**`, `test: content/keyboard/**`, `test: content/data_cord/**`,
  `test: client/pc/**`.
- Spec: the game parts of the as-built notes.

Shared files (both the game owner and the gauge owner edit them): `registry/ModBlocks.java`,
`ModItems.java`, `ModBlockEntities.java`, `network/ModPackets.java`,
`client/ModPartialModels.java`, `client/ModClientEvents.java`, `CreateFlippies.java`,
`foundation/ClientConfig.java` (the double-Escape window), `lang/en_us.json`. Rules: re-read the file right before every edit; add in one contiguous block
marked `// PC` (or a `"_comment"`-free block of `create_flippies.pc.*` keys in the lang file);
append packets at the end of the registration list; never reorder or reformat others' lines.
The core owner edits none of them.

Build and test contention: one Gradle run at a time in the tree. The core owner prefers plain
`javac` into its own scratch directory plus the JUnit console launcher for `computer/**` (as the
PLC spec suggests); Gradle runs (`compileJava`, `test`) are announced and not overlapped. Never
`build/` for scratch output; never `runClient`.

The API the game codes against (C0 lands these signatures first; Javadoc may grow, signatures do
not change without both owners agreeing):

```java
// computer/machine/MachineConfig.java
public record MachineConfig(CpuModel cpu, int clockHz, int ramBytes, DiskImage disk,
                            byte[] bootRom, LongSupplier rtcEpochSeconds, Integer resetPc,
                            boolean strictFpu, long macAddress) {
    public static MachineConfig of(CpuModel cpu, int clockHz, int ramBytes, DiskImage disk,
                                   boolean strictFpu, long macAddress);
    // existing 8- and 7-argument constructors and of(cpu, clockHz, ramBytes, disk) stay
}

// computer/machine/Platform.java (addition)
int maxRamBytes();

// computer/machine/Machine.java (additions)
public static final int COM_PORTS = 3, DISPLAYS = 3, PS2_PORTS = 2;
public static final int STATE_VERSION = 4;
public Uart16550 com(int index);            // 0..2
public VgaDisplay vga(int index);           // 0..2; vga() == vga(0)
public Ps2Controller ps2();
public Ps2Keyboard keyboard(int port);      // keyboard() == keyboard(0)
public Ps2Mouse mouse(int port);            // mouse() == mouse(1)
public FloppyController floppy();
public Nic nic();
public double busyFraction();               // last tick, 0..1
public boolean guestErrorLed();
public String haltReason();                 // null unless HALTED

// computer/device/Ps2DeviceKind.java
public enum Ps2DeviceKind { NONE, KEYBOARD, MOUSE }

// computer/device/Ps2Controller.java (additions)
public void setDevice(int port, Ps2DeviceKind kind);
public Ps2DeviceKind device(int port);
public Ps2Keyboard keyboard(int port);
public Ps2Mouse mouse(int port);

// computer/device/DiskImage.java (addition)
default UUID id() { return null; }

// computer/device/DiskChunkStore.java
public interface DiskChunkStore {
    /** The chunk's bytes (CHUNK_SIZE, the last chunk may be shorter), or null if never written. */
    byte[] load(int chunk) throws IOException;
}

// computer/device/PagedDiskImage.java
public final class PagedDiskImage implements DiskImage {
    public static final int CHUNK_SIZE = 64 * 1024;
    public static final long FLOPPY_CAPACITY = 1_474_560L;
    public static final long MAX_CAPACITY = 1024L * 1024 * 1024;
    public PagedDiskImage(UUID id, long capacityBytes, DiskChunkStore store, int maxCachedChunks);
    public UUID id();
    public long capacityBytes();
    public int chunkCount();
    public boolean hasDirtyChunks();
    public Map<Integer, byte[]> takeDirtyChunks();   // copies
}

// computer/device/HddController.java (addition)
public boolean takeActivity();

// computer/device/FloppyController.java
public final class FloppyController implements BusDevice {
    public static final int SECTORS = 2880;
    public void insert(DiskImage media, boolean writeProtected);
    public void eject();
    public DiskImage media();
    public boolean writeProtected();
    public boolean motorOn();
    public boolean takeActivity();
}

// computer/device/Nic.java
public final class Nic implements BusDevice {
    public static final int MIN_FRAME = 14, MAX_FRAME = 1514, QUEUE_FRAMES = 32;
    public long macAddress();
    public void setLink(boolean up);
    public boolean link();
    public boolean deliver(byte[] frame, int offset, int length);
    public byte[] takeOutgoing();                    // null when empty
    public int outgoingCount();
}
```

Existing API the game also uses unchanged: `Machine(MachineConfig, CpuFactory)` with
`StandardCpus`, `powerOn`, `powerOff`, `reset`, `runTick`, `state`, `exitCode`, `speed`,
`saveState`, `loadState`, `Uart16550.hostRead/hostWrite/setHostConnected`,
`Ps2Keyboard.keyDown/keyUp/leds`, `VgaDisplay.generation/snapshot/dirtyRowsSince/
copyVisibleInto`, `CpuModel`, `M68kMap` and `Rv32Map` clock items.

Game order of work (G1 needs nothing from the core, G2 needs C0, G3 needs C1 to C5):

1. G1: `gen_pc_case.py` and assets, blocks, items, registration, geometry with its test, install
   and remove rules, rendering, the socket with `IAcceptCord` and `PcPower` with the circuit test, the
   data cord with port rules, the keyboard block and capture screen (events go nowhere yet), server
   config, case screen with the parts list.
2. G2: `PcRuntime` and `PcScheduler` over the stub API (a fake machine in tests), buttons and
   lights, persistence and the disk store.
3. G3: against the real core: links exchange, keyboard to PC, floppy media, the in-game checks of
   11.4.

### 11.4 Test plan

Core (JUnit 5, pure Java):

- `FloppyControllerTest`: every register; spin-up, seek and sector timing to the cycle; motor
  hold and stop; READ and WRITE through DMA into RAM and VRAM; write protect (code 8); no media,
  no media; bad count, LBA range, DMA range; insert and eject set MEDIA_CHANGED and its
  interrupt; eject during a command ends with code 9; GEOMETRY; saved state round trip, and a
  different media id on load giving a media change instead of a failure.
- `PagedDiskImageTest`: lazy loads through a fake store; never-written chunks read as zeros; LRU
  evicts clean chunks only; `takeDirtyChunks` copies and clears; the floppy's half chunk; id;
  a store `IOException` becoming HDD and floppy error code 7.
- `NicTest`: MAC registers by byte, half and word on both byte orders; TX DMA, bad length, queue
  full, no link; receive filter (ours, broadcast, multicast, other, promiscuous); RX copy, drop,
  bad DMA keeping the frame; overflow and RX_DROPPED; each interrupt condition; link change;
  saved state round trip.
- `Ps2Controller` either-device tests: keyboard on port 1 answers identify with 0xAB 0x83 on
  line 4; switching kinds empties the queue and queues power-on bytes; version 1 payload loads.
- Map tests per platform: every new window, the three VRAM bases, SysCon VRAM1/VRAM2 and PRESENT
  bits, the 68000 rejecting 14 MiB and accepting 13.5 MiB, DMA into display 2 at 0xFFD8_0000 on
  the 68000.
- Three displays: each display's registers independent, dirty rows of one do not touch another,
  `endFrame` on all three, VBLANK on lines 6, 11, 12.
- Machine: config with the floppy flag and MAC in the saved-state check; version 3 states
  rejected; HDD id mismatch rejected; `busyFraction` for a WFI-idle and a busy guest;
  `guestErrorLed`.
- End to end (RV32I, 68000 and 68040 unless noted):
  - Boot from floppy: bootable floppy runs with boot device 1; non-bootable floppy plus bootable
    HDD boots the HDD with "Floppy not bootable" printed; neither gives the old messages.
  - Floppy write then read back, and the image's dirty chunks.
  - Network: two `Machine`s, a harness moving frames once per tick as the game will; A sends,
    B echoes, A checks the payload and the MACs; mixed pair RV32I and 68040; link down gives
    code 5.
  - Three displays: a program writes different text to each; three snapshots differ as expected.
  - COM3: echo through `hostWrite`/`hostRead`; a COM link between two machines pumped per tick.
  - Saved state round trip in lockstep with a floppy read in flight, frames queued both ways,
    COM3 traffic and all three displays drawing.

Game (JUnit 5; pure logic, plus real Power Grid solves where noted, as the PLC tests do):

- `PcCaseGeometryTest`: port, button, slot and part boxes equal the model file's cubes (read the
  JSON in the test), grown boxes do not overlap, each box resolves to itself, rotation per facing.
- `PortCompatibilityTest`: same type, role pairs, occupied ports, same port, length limit,
  loopback COM on one PC.
- `PcSlotRulesTest`: panel off required; PC off required; motherboard first and last; RAM slot
  choice; floppy insert and eject always allowed.
- `PcRamRulesTest`: usable RAM per CPU, the 68000 cap, two 64 MiB sticks fully usable on RV32I
  and the 68040, the total RAM cap refusing a power-on, the "installed X,
  usable Y" report.
- `PcPowerTest`: draw per part, idle versus busy blending, HDD and floppy activity, efficiency,
  standby, resistance clamp and deadband, the 95/90/270/280 V window with hysteresis, reverse
  polarity.
- `PcPowerCircuitTest` (real Power Grid solve): a 230 V source wired to terminals 0 and 1 draws
  V^2/R through the load, with no ground anywhere; reversed polarity gives the same magnitude.
- `PcSchedulerTest` (fake machines): budget split, the total cap, round-robin rotation, budget 0
  when exhausted, a throwing machine crashing only its own PC.
- `LinkExchangeTest` (real `Machine`s): ethernet exchange and link state; COM pumping with the
  leftover buffer; deterministic order.
- `PcStateStoreTest`: generation match, mismatch leading to cold boot or staying off.
- `KeyboardReleaseDetectorTest`: a single Escape is SEND; two presses inside the window give SEND
  then RELEASE; outside the window both SEND; another key between them resets; the window from
  config; a third press after a release starts over.
- `GlfwPs2KeysTest`: every mapped key gives the right `Ps2Key` (A, Enter, Escape, arrows, right
  Ctrl, keypad), unmapped keys ignored; packet event encoding round trip.
- `KeyboardCaptureTest`: one typist at a time, release on every end condition sends key-ups for
  held keys, events from a non-typist ignored.

Only in game:

- Part rendering and alignment with the shell for each facing, lights, button travel, power plug
  and data plugs; item models.
- Hit-box feel for ports, buttons, slot and parts; install and remove flow with the panel.
- Plugging Power Grid's cord into the socket (only on the socket, plug cube or Power Grid's plug
  aligned for each facing), data cords placed, drawn and cut, drops on breaking.
- Power Grid: draw shown on meters changing with load, brown-out and over-voltage behaviour with
  real sources.
- The floppy disk cube appearing in the slot on insert and vanishing on eject.
- Booting from a floppy item, ejecting mid-read, moving an HDD between PCs.
- Chunk unload and reload (time frozen, resumes), server restart, a swapped HDD forcing a cold
  boot, a backup with a stale side file.
- Two PCs exchanging frames over an ethernet cord and bytes over a COM cord.
- Typing on the keyboard in both case PS/2 ports, key repeat, two players contending, release on
  walking away.
- Many PCs on one server: tick time with the budgets, speed shown in goggles.

### 11.5 Open questions

None open. Answered by the user after the first draft and folded in above:

- Power: Power Grid's own two-conductor cord into the case's power connector, plug cube drawn at
  the socket, 95 to 270 V DC (11.2.4). No single wire through ground.
- RAM: the limit is 128 MB on RV32I and the 68040 so two 64 MB sticks are fully usable; the
  68000 stays limited by its 24-bit bus (13.5 MiB); up to 128 MB of server memory per running PC
  is accepted (11.1.10).
- Floppy: the model's `floppy disc` cube is the floppy disk, drawn in place only while one is
  inserted; the drive is built into the case, so there is no floppy drive item (11.2.1, 11.2.3).
- Defaults accepted: RV32I CPU items at 10, 25 and 50 MHz; HDD 16 MB, 64 MB, 256 MB and 1 GB; the
  PC stays off after a power cut; power figures in watts.

## 12. Linux on the 68040 PC (planned)

Graphical Debian on the in-game 68040 PC, installed onto its 1 GB HDD over the network from a
floppy, with no real internet involved. This section is the plan. Step 1 of 12.10 (the MMU,
faster ethernet, the modem stack, the headless runner and the Docker image) is built; 12.14
records it as built, and the plan text below has been corrected where step 1 proved it wrong.
It changes one earlier decision: the 68040 has its MMU (10.5 and decision 5 of 10.1 are
superseded). House rules apply unchanged to everything under `computer/**`.

How sure each fact is: "(verify)" marked facts not confirmed against a primary source while
writing this section. Step 1 checked every one (UM040 sections 3 and 8 for the MMU, the pinned
Linux 6.18.58 tree for kernel facts, the debian-ports snapshot for package facts); each result is
now stated in place, with the source. Kernel facts with file paths and line numbers are in
`guest/linux/NOTES.md`. Upstream kernel file paths such as `motorola_pgtable.h` and
`arch/m68k/mm/motorola.c` keep their real names; that is the one accepted exception to the house
rule on the chip maker's name.

User decisions that settle this section:

- The OS is Debian (its m68k port) with a GUI on top, running on the 68040. Arch has no m68k port.
- Our own devices stay as they are. We keep a Linux fork with a platform port and drivers for
  them, rather than reshaping the machine to look like another emulator's board.
- A "network modem" block connects to the PC with an ethernet data cord and is powered by the PC
  over that cord (PoE). It is the PC's only way to the "internet".
- The "internet" is content hardcoded into the mod (12.4.3), not real web access.
- Real IP from day one: the modem is an IPv4 router with DHCP, DNS and an HTTP server; the
  install floppy carries a small IP stack and speaks HTTP over TCP. Debian later uses the same
  modem with its normal network stack.
- Floppy installs only: a netinstall floppy (12.5) downloads the Debian disk image onto the HDD.
  Installing from many floppies is not an option.
- Ethernet gets faster so the download does not take a quarter of an hour (12.3).

### 12.1 Pieces and how they fit

1. 68040 MMU (12.2, core): paging, access errors that can be restarted, PTEST, PFLUSH, MOVES.
   Linux cannot run without it.
2. Faster ethernet (12.3, core): bigger queues and checksum offload in `Nic`.
3. The modem (12.4): a pure-Java IPv4 router and HTTP server under `computer/net`, a block in the
   game, PoE, and the content catalogue in the mod's resources.
4. The netinstall floppy (12.5, guest): a freestanding C program that gets an address by DHCP,
   lists the catalogue, and writes a chosen disk image onto the HDD.
5. The Linux fork (12.6, guest): a pinned upstream kernel plus our patch series: the `flippies`
   m68k platform and drivers for every device of 3 and 11.1.
6. The boot chain (12.7, guest): an MBR boot sector, a second-stage loader and a Debian hook that
   installs new kernels for it.
7. The Debian image (12.8, guest): debian-ports m68k, a light X desktop, built reproducibly in
   Docker and turned into the modem's image format.
8. Tooling (12.9): one Docker image for every cross build, and a headless PC runner so the whole
   chain runs and is tested outside Minecraft.

Guest code lives under `guest/` next to `guest/cogmaze`: `guest/netinstall`, `guest/linux`,
`guest/boot`, `guest/debian`, `guest/docker`. Nothing under `guest/` is compiled by Gradle; build
outputs that the mod ships are copied into `src/main/resources` by a script (12.4.3).

### 12.2 68040 MMU (owner: core)

#### 12.2.1 Chip facts

Every fact below was checked in UM040 (MC68040UM/AD Rev. 1, 1993) and against the constants
Linux uses (`traps.h`, `motorola_pgtable.h`, `head.S`); all matched except where noted.

- TC (MOVEC 0x003): bit 15 E (enable), bit 14 P (page size: 0 = 4 KiB, 1 = 8 KiB). Bits 13..0
  read 0 (UM040 3.1.2). Linux uses 4 KiB pages (`TC_ENABLE` + `TC_PAGE4K` = 0x8000).
- URP (0x806) and SRP (0x807): root table addresses, 512-byte aligned (bits 8..0 ignored). User
  accesses (function codes 1, 2) walk from URP, supervisor accesses (5, 6) from SRP (FC2 selects,
  UM040 figure 3-9).
- Three-level walk (UM040 3.2.1, figure 3-8). 4 KiB pages: root index = address bits 31..25 (128
  entries of 4 bytes), pointer index = bits 24..18 (128 entries), page index = bits 17..12 (64
  entries, a 256-byte table). 8 KiB pages: page index = bits 17..13 (32 entries, a 128-byte
  table).
- Root and pointer descriptors (UM040 figure 3-11): table address in the high bits (pointer
  tables 512-byte aligned; page tables 256-byte aligned for 4 KiB, 128-byte for 8 KiB), bit 3 U
  (used), bit 2 W (write protect), bits 1..0 UDT (0 and 1 invalid, 2 and 3 resident).
- Page descriptors (UM040 figure 3-12): physical page address in bits 31..12, bit 11 UR (user
  reserved), bit 10 G (global), bits 9..8 U1 and U0 (user attributes, no effect here), bit 7 S
  (supervisor only), bits 6..5 CM (cache mode, no effect here), bit 4 M (modified), bit 3 U
  (used), bit 2 W, bits 1..0 PDT (0 invalid, 1 and 3 resident, 2 indirect). An indirect
  descriptor holds the address of the real page descriptor in bits 31..2; an indirect
  descriptor that points at another indirect descriptor is invalid (UM040 3.2.4.1). Linux never
  writes indirect descriptors.
- The chip sets U in every table and page descriptor it walks through, and M in the page
  descriptor on a write, writing them back to memory (locked read-modify-write). M is not set on
  a write-protect or supervisor violation, and the read of a locked read-modify-write counts as
  a write (UM040 3.2.2.3, table 3-1). Linux uses U and M as its accessed and dirty bits
  (`motorola_pgtable.h`: ACCESSED 0x008, DIRTY 0x010). `PAGE_SHARED` has no DIRTY, so a writable
  page starts clean and only the M write-back marks it dirty: without it Linux can drop modified
  file pages as clean.
- W anywhere on the path write-protects the page (W accumulates over the levels). S on a page
  denies user accesses.
- ITT0, ITT1 (instruction) and DTT0, DTT1 (data) transparent translation (UM040 3.1.3, 3.4):
  bits 31..24 address base, bits 23..16 address mask (1 = ignore that bit), bit 15 E, bits
  14..13 S field (0 user only, 1 supervisor only, 2 and 3 both), bits 9..8 U1 U0, bits 6..5 CM,
  bit 2 W. Bits 12..10, 7, 4, 3, 1 and 0 read 0. A match maps the address to itself and skips
  the tables; W makes a match write-protected; TT0 wins over TT1. TT registers work with TC.E
  clear too.
- MMUSR (0x805), written by PTEST (UM040 3.1.4): bits 31..12 physical address, bit 11 B (bus
  error during the walk), bit 10 G, bits 9..8 U1 U0, bit 7 S, bits 6..5 CM, bit 4 M, bit 3 0,
  bit 2 W, bit 1 T (a TT register matched), bit 0 R (resident). With B set every other bit is 0.
  With T set, R is set and every other bit is 0.
- Access error (vector 2), stack frame format 7, 30 words (UM040 8.4.6; Linux `traps.h` reads
  the same fields at the same offsets, and `signal.c` sizes the frame at 60 bytes): SR, PC,
  format and vector, EA, SSW, WB3S, WB2S, WB1S, FA (fault address), WB3A, WB3D, WB2A, WB2D,
  WB1A, WB1D or PD0, PD1, PD2, PD3. SSW bits (UM040 figure 8-7): 15 CP, 14 CU, 13 CT, 12 CM, 11
  MA (misaligned: the fault is on the second page of an access that spans two), 10 ATC (fault in
  translation), 9 LK, 8 RW (1 = read), 7 X, 6..5 SIZE (byte 1, word 2, long 0, line 3), 4..3 TT,
  2..0 TM (function code). EA is meaningful only when a continuation bit is set.

#### 12.2.2 Translation in the emulator

- The MMU is part of `M68kCpu` for the 68040 model only, in a new class `M68kMmu` the core calls.
  The 68000 is untouched.
- Every CPU access already goes through `a & addrMask` and then the physical fast path (ROM, RAM,
  VRAM, then the bus). With the MMU on, a logical address first becomes a physical one, then the
  existing physical path runs unchanged.
- Order of a translation: (1) the TT registers of the access's kind (instruction fetch: ITT0,
  ITT1; data: DTT0, DTT1) by function code; (2) if TC.E is clear, physical = logical; (3) else
  the software TLB; (4) on a miss, the table walk, which fills the TLB.
- Software TLB, not a model of the real ATC: direct-mapped, 1024 entries per kind (instruction,
  data read, data write) and privilege (user, supervisor), keyed by the logical page number.
  An entry holds the logical page tag and the physical page base. A data write entry is only
  filled when the page is writable and its M bit is already set (the walk sets M on the first
  write), so later writes never need to touch the descriptor. A read entry is filled when U is
  set (the walk sets it). TT hits and the identity mapping while TC.E is clear are cached too.
  Only successful translations are cached (the chip also caches failed searches; see 12.14.1).
- Instruction fetch keeps one "current code page" (logical tag and physical base) and
  re-translates only when the PC crosses a page or after a flush, so straight-line code costs
  one compare per fetch.
- MMU off (TC.E = 0 and no TT register enabled): a single boolean in the hot loop skips all of
  the above, so today's speed is kept for every existing program.
- TLB flushes: MOVEC to TC, URP, SRP, ITTx or DTTx; every PFLUSH form (by address and function
  code, or all, with or without global entries; as built, all four forms are exact); RESET;
  `reset()`. `loadState` restores the saved TLB instead (12.2.5). Writes to page tables in
  memory do not flush, as on the real chip; Linux flushes after changing them.
- Table walk reads and U/M write-backs are physical accesses through the existing path. A walk
  that hits a bus error (descriptor address unmapped) is an access error with B set in PTEST's
  result. A walk costs 0 cycles.
- Physical addresses keep the 32-bit map of 10.8.1, so devices at 0xFFF0_0000 are reached
  through page tables or a TT register, whichever the guest sets up.
- Cost target: MMU on, at least 60% of the MMU-off instruction rate in
  `M68kThroughputBenchmarkTest` (a new MMU-on variant). Met: 86% to 93% (12.14.1).

#### 12.2.3 Access errors and restart

- All faults are precise: when a translation fails (invalid descriptor, write to a protected
  page, user access to a supervisor page), the instruction is abandoned and restarted from its
  first word after the handler's RTE. Every register the instruction changed is put back as it
  was at the start of the instruction (an undo record of the address registers changed by
  (An)+ and -(An), of the CCR, and for RTE of SR and every stack pointer). Checked in the core:
  data registers are only written at the end of an instruction, except MOVEM memory to
  registers, which now buffers its loads.
- Memory writes the instruction already made before the fault are repeated when it runs again.
  That is harmless for every instruction that writes the same values the second time. Where it
  is not, the instruction translates every page it touches before its first write, or reorders
  its work; the list is in 12.14.1.
- Every access of an instruction that crosses a page boundary is translated for both pages
  before any byte moves.
- The format 7 frame reports no pending writebacks (WB1S, WB2S, WB3S all 0), FA = the first byte
  of the access (UM040 8.4.6.4), MA set when the second page of a page-crossing access faulted,
  the SSW with ATC = 1, RW, SIZE, TT and TM of the access, and EA = FA. CT, CU, CP are 0 (no
  continuation). A locked read-modify-write (TAS, CAS, CAS2) is translated as a write before its
  read, and a fault on it reports LK and RW. Linux's 68040 access error handler
  (`access_error040` in `arch/m68k/kernel/traps.c`) accepts this frame: it acts only on WB2S and
  WB3S with the valid bit set, needs ATC, takes TM as the function code for its PTEST, and with
  MA set probes at (FA + 7) & -8. Checked in 6.12.1 and 6.18.58.
- Instruction fetch faults set the SSW for a program-space access (TM 2 or 6), SIZE word, RW 1,
  FA = the fetch address. FPU immediate operands are fetched as part of the instruction stream
  too.
- A fault while stacking any exception frame halts the CPU (as today for double faults). This is
  a deviation: the chip halts only for a fault while stacking an access error or reset frame.

#### 12.2.4 PTEST, PFLUSH, MOVES, MOVEC

- PTESTR and PTESTW: flush the page, walk the tables for the address in An with the function
  code in DFC, fill MMUSR, and load the TLB after a successful search. A TT match gives MMUSR =
  T | R and nothing else (UM040 3.1.4). With TC.E clear and no TT match the chip's result is
  undefined; ours is (address & 0xFFFF_F000) | R. Linux uses PTESTR and PTESTW in its fault
  handler. UM040 does not say whether PTEST writes U and M back; it is built to set U, and M for
  PTESTW, as any search does (inferred, not confirmed).
- PFLUSH, PFLUSHN (by address, non-global only for N), PFLUSHA, PFLUSHAN: flush the TLB (12.2.2).
  All four are exact: the N forms keep global entries; the by-address forms act on the table
  DFC's FC2 selects.
- MOVES: now honours SFC and DFC: the access is translated with that function code, so the
  kernel reaches user memory with user permissions. Codes 1 and 2 are user data, 5 and 6
  supervisor data; codes 0, 3, 4 and 7 are physical, untranslated alternate-space accesses (UM040
  3.2.5, table 3-2). MOVEC to SFC and DFC keeps only bits 2..0.
- MOVEC TC: stores E and P only (mask 0xC000, no longer forced to 0). The TT registers store only
  their defined bits (mask 0xFFFF_E364).
- CINV and CPUSH stay no-ops (no caches modelled).

#### 12.2.5 Saved state and tests

- Saved state: the CPU payload already holds TC, ITT0..DTT1, URP, SRP, MMUSR (10.6). The CPU's
  state version is 2. The TLB entries and the current code page are saved with the CPU (after
  MMUSR: an entry count, then per entry its slot, logical tag, physical page and G; then the code
  page's logical tag and physical base; at most 6144 entries of 11 bytes, about 67 KB), because a
  guest may keep using an entry whose descriptor it changed without a PFLUSH, and a restored
  machine must behave the same. A version 1 state loads with TC.E and the E bit of every TT
  register cleared and an empty TLB.
- Tests (in `cpu/m68k`, hand-checked like the rest of section 10):
  - Walks with 4 KiB and 8 KiB pages, indirect descriptors, invalid at each level, U and M
    written back to memory, W at each level, S against user accesses, TT matches by function
    code and mask, TT with TC.E clear.
  - Restart of every addressing mode after a fault ((An)+ and -(An) put back), MOVEM across a
    page boundary, MOVE16, CAS, CAS2, a misaligned long across two pages where only the second
    is missing.
  - The format 7 frame word by word for a data read, a data write and an instruction fetch.
  - PTEST results, every PFLUSH form invalidating exactly what it should (and no stale write
    entry after a page is made read-only and flushed), MOVES with each SFC and DFC.
  - End to end (`e2e`): a 68040 guest that turns on paging, runs a user-mode program, and
    serves its page faults by mapping pages on demand, then reads results over COM1.
  - Saved state with the MMU on, every tick in lockstep (like `M68kSaveStateE2eTest`).
  - The MMU-on throughput benchmark.

### 12.3 Faster ethernet (owner: core)

Today each `Nic` queue holds 32 frames and the exchange runs once per tick, so a link carries at
most about 970 KB/s. A 400 MB image would need about 7 minutes on the wire alone.

- `QUEUE_FRAMES` goes from 32 to 256 each way: up to about 7.7 MB/s per link (256 frames of
  1514 bytes, 20 ticks a second). A full NIC holds about 760 KiB. Saved states with up to 32
  frames still load (the reader caps at the new size), so no state version change.
- Checksum offload, so the guest does not sum every byte:
  - New register 0x38 RX_INFO (R): flags for the oldest received frame, worked out from its bytes
    on read: bit 0 IPv4 header checked and good, bit 1 TCP or UDP checksum checked and good
    (IPv4 only, no fragments), bit 2 the frame is IPv4. A guest that ignores it works as before.
  - New CTRL bit 5 TX_CSUM: on TX_CMD the card fills in the IPv4 header checksum and the TCP or
    UDP checksum of an IPv4 frame before queueing it.
  - `SIZE` becomes 0x3C. Checksums are pure functions of the frame, so no new state.
- Linux uses both (`NETIF_F_IP_CSUM`, `CHECKSUM_UNNECESSARY`), and so does the netinstall
  floppy.
- What bounds the download then is the HDD (about 5 MB/s, 3.8) and the guest CPU, not the wire.
  A 400 MB image at HDD speed takes about 80 seconds of emulated time. The downloader sleeps while
  waiting (12.5), so an idle-waiting PC runs at real time even under the default host budget.
- `NetworkE2eTest` and `NicTest` gain cases for 256 queued frames, RX_INFO on good and corrupt
  frames, and TX_CSUM output checked against a reference sum.

### 12.4 The modem

#### 12.4.1 Network shape

- One modem serves one PC: its single ethernet port links to one PC's port with the existing data
  cord (`PortType.ETHERNET`, role PEER). Each modem is its own private network.
- Addresses: the modem is 10.88.0.1/24; its DHCP server hands out 10.88.0.2 with gateway and DNS
  10.88.0.1 and a lease of one day. The modem's MAC is fixed per block (locally administered,
  assigned at placement like the motherboard's).
- Names: the modem's DNS answers A records for the hosts of the catalogue (12.4.3), all
  10.88.0.1, under the made-up top-level domain `.flip` (for example `get.flip`, `deb.flip`).
  Every other name gets NXDOMAIN. No traffic leaves the modem; nothing touches the real network.

#### 12.4.2 The modem's stack (`computer/net`, pure Java)

A small, purpose-built IPv4 host, not a general router. Package `cf.content.computer.net`, house
rules apply, testable on the plain JUnit platform.

- `FrameEndpoint` interface: `byte[] takeOutgoing()`, `boolean deliver(byte[], int, int)`,
  `default boolean canReceive()`, `void setLink(boolean)`. `canReceive` asks, without changing
  any state, whether `deliver` has room now (default true; `Nic` answers RX_ENABLE on and its
  queue under `QUEUE_FRAMES`). `Nic` implements the interface. `PcLinkExchange.ethernet` takes
  two endpoints and serves PC to PC links; there a refused frame is lost, as before.
- `Modem` (one per block): implements `FrameEndpoint` and has `exchange(FrameEndpoint pc)` and
  `tick()`. The PC-to-modem exchange, once per server tick, is `modem.exchange(nic)` then
  `modem.tick()`, and `PcLinkExchange.ethernetDown(modem)` while the link is down. `exchange`
  raises both links, moves every frame the PC sent into the modem (the modem answers at once,
  so a request and its reply take one tick), then moves the modem's frames to the PC until
  `canReceive` is false or `deliver` refuses one; that frame and all after it wait for the next
  tick. Asking first keeps the card from counting a held frame in RX_DROPPED or RX_OVERFLOW.
  `tick()` then runs the timers and sends what TCP may.
- ARP (request and reply), ICMP echo (so `ping 10.88.0.1` works), DHCP server (DISCOVER, OFFER,
  REQUEST, ACK, RELEASE; options subnet, router, DNS, lease, server id), DNS server over UDP
  (A queries; AAAA and others get an empty answer).
- TCP server, port 80 only: passive open, MSS 1460, in-order receive, sliding-window send limited
  by the peer's window, retransmission after 20 ticks without an ACK, FIN and RST handling. At
  most 8 connections. The window scale option (RFC 7323) is honoured when the client offers it
  (the modem answers with shift 0); without it a connection carries at most 64 KiB per tick.
  Frames the PC's NIC has no room for stay queued in the modem and go next tick, so the link
  loses nothing and TCP rarely retransmits; the TCP resend timers pause while frames wait. If the
  card takes nothing for 20 exchanges in a row (its receiver is off), the waiting frames are
  dropped and counted in the modem.
- HTTP/1.1 server: GET and HEAD, keep-alive, `Content-Length`, single `Range` (for resumed
  downloads), 404 for unknown paths. Bodies stream from the catalogue without loading a whole
  file into memory. At most 32 unacknowledged responses per connection: input beyond that is
  left unacknowledged, so the client sends it again (this bounds server memory against a guest
  that pipelines requests behind a zero window).

#### 12.4.3 The content catalogue (hardcoded in the mod)

- Resources under `src/main/resources/data/create_flippies/internet/`: one folder per host
  (`get.flip/`, `deb.flip/`, ...), files served at their path. Read through Forge's resource
  manager on the server, so a datapack can add or replace content without code.
- Forge resource paths allow only `[a-z0-9/._-]`, and Debian file names contain `+`, `~`, `%`
  and upper case. The game's `ContentSource` and the image build script must share an escaping
  rule that maps every served name into that set and back; the rule is not chosen yet (game
  owner, with 12.8). The modem passes the decoded request path through unchanged, and a name the
  store cannot hold (any unchecked exception from the source) is answered with 404.
- `get.flip/index.txt`: the catalogue the floppy shows. One entry per line: kind, path, size in
  bytes, title. Kind `image` is a disk image (12.4.4); kind `file` is plain.
- Large files are stored deflated in 64 KiB chunks in the jar and inflated on the fly by the
  modem, so the jar holds compressed data while the wire carries plain bytes (the guest CPU is
  too slow to inflate at link speed). Layout: file `host/dir/name` becomes a folder
  `host/dir/name.z/` holding `size.txt` (the plain size in decimal) and `0.z`, `1.z`, ... (zlib
  streams, RFC 1950, each inflating to exactly 65,536 bytes except the last). A plain file wins
  when both forms exist. Large files should be stored this way: the default size lookup of a
  plain file reads it through.
- Size: the Debian image is expected to be 120 to 200 MB compressed (measured at 12.8). That
  goes straight into the jar's size; see open question 1.

#### 12.4.4 Disk image format (`.fdi`)

A sparse image, so zero chunks are never sent:

- Header, all numbers big-endian 32-bit unsigned (the reader is a 68k), by byte offset: 0 magic
  "FDI1"; 4 image size in sectors; 8 chunk size, always 65,536; 12 number of chunks present; 16
  CRC32 of the chunk table (a field added at build time); 20 the title, 64 bytes of UTF-8 (at
  most 63 bytes, zero padded); 84 CRC32 of bytes 0 to 83. CRC32 is zlib's (check value
  0xCBF43926).
- Chunk table at 88: per present chunk, its index then the CRC32 of its data, indexes strictly
  rising. Chunks not in the table are zero.
- Then the present chunks' data, in table order. Every chunk is 65,536 bytes except the image's
  last, so entry k's data starts at 88 + 8 * count + k * 65,536, which is where a Range request
  resumes. `guest/debian/mkfdi.py` and the netinstall reader must use exactly this layout.
- The downloader fetches it with one GET (or a Range GET to resume from the first unverified
  chunk), writes each chunk to its LBA, checks each CRC32, and zeroes the absent chunks only when
  the user asks for a full wipe (writing zeros to 600 MB of empty disk costs minutes for no
  gain; a fresh `PagedDiskImage` already reads as zeros).
- `guest/debian/mkfdi.py` turns a raw image into `.fdi`; the same tool writes the deflated chunk
  files for the jar.

#### 12.4.5 The block (owner: game)

As built: 12.17.1 and 12.17.2.

- `network_modem` block with a block entity. Model: open question 4; a placeholder cube with one
  ethernet port cube until then.
- One ethernet port (`DataPortHost`, one port, ETHERNET, PEER), so the existing data cord and
  `PortCompatibility` work unchanged.
- PoE: the modem has no power input. It is up while its cord's other end is a PC that is loaded
  and powered. That PC's draw (11.2.4) gains 2.5 W while a modem is linked. The link goes up and
  down with that PC exactly like a PC-to-PC link.
- Lights on the model: power (PoE present) and link/activity (blinks on frames, synced like the
  case lights).
- The scheduler's exchange phase (11.2.10) handles PC to modem links: `modem.exchange(nic)`,
  then `modem.tick()`; `PcLinkExchange.ethernetDown(modem)` while the link is down (12.4.2).
  Plain `PcLinkExchange.ethernet` would also work but loses refused frames.
- Persistence: the modem keeps no state worth saving (DHCP leases are re-requested, TCP
  connections drop on unload; the downloader resumes with Range).

### 12.5 The netinstall floppy (`guest/netinstall`)

As built: 12.17.3 onward.

- Freestanding C17 plus a little 68k assembly, built with the cross GCC from the Docker image
  (12.9): `-m68040 -O2 -ffreestanding -nostdlib -fno-pic`, own linker script, flat binary,
  `mkfloppy.py` as Cog Maze does (with a 68k entry instead of RV32I). MMU off, supervisor mode.
- Boot: the ROM loads sector 0 to 0x1000 and enters with D2 = 1 (floppy) (11.1.5). Sector 0 loads
  the rest of the program (target under 96 KiB, about 2 s from the floppy) and jumps to it.
- Hardware use: NIC with TX_CSUM and RX_INFO, HDD, VGA text mode, PS/2 keyboard (set 2 decoding of
  the keys it needs), COM1 mirror of the screen for tests, interval timer for timeouts. It sleeps
  with STOP between events (NIC, HDD and timer interrupts), so a waiting PC costs no host time.
- IP: ARP, IPv4 (no fragments), UDP, DHCP client, DNS client, TCP client (one connection, with
  window scaling, RFC 7323), HTTP/1.1 GET with Range. The receive window must be 256 KiB to
  1 MiB (scale shift 2 or more). Measured against the modem with the card's 256-frame queue: a
  64 KiB window gives only about 1.24 MiB/s (a 400 MB image in about 5.4 minutes), 256 KiB about
  4.8 MiB/s, 1 MiB or more about 7 MiB/s, with no retransmissions.
- Screens (80x25 text):
  1. "Network: no link" until the modem's link is up, then DHCP, showing the address.
  2. The catalogue from `http://get.flip/index.txt`: arrows and Enter to choose.
  3. For an image: the HDD's capacity (from IDENTIFY) against the image's size; refuse if it
     does not fit. Then "This erases the HDD. Type YES to continue."
  4. Progress: MB written of total, speed, time left. Two 128 KiB buffers: the HDD writes one
     while the network fills the other.
  5. Verify every chunk's CRC32 again by reading back from the HDD, then "Done. Remove the floppy
     and press Enter to restart." (SysCon POWER 0x7777).
- Failure handling: a dropped connection resumes with Range from the first chunk not yet
  verified; a CRC mismatch re-fetches that chunk; three failures in a row stop with a message.
- In game: a creative-tab item "Netinstall floppy" (a `floppy_disk` with NBT `Preset:
  "netinstall"`, write-protected). On its first insert the game copies the image bundled at
  `data/create_flippies/floppies/netinstall.img` into a new disk id, then it behaves like any
  floppy. Recipe later.

### 12.6 The Linux fork (`guest/linux`)

- Base: Linux 6.18.58, the newest upstream longterm on 2026-10-25 (6.12 was out of date). Tarball
  `https://cdn.kernel.org/pub/linux/kernel/v6.x/linux-6.18.58.tar.xz`, 154,851,680 bytes,
  SHA-256 `069b19f9032c4dac8149ae4b6520c35debca0f42a9f6d52ee55ba6ca9b818013` (matches kernel.org's
  signed `sha256sums.asc`). The kernel build script, not written yet, downloads it to
  `guest/linux/.cache/` (ignored by git) and refuses it unless `sha256sum` matches. It unpacks
  and builds in the toolchain container's `/build` volume, never in the repo: on the Mac the repo
  is case-insensitive and the tarball has 13 paths that differ only in case (netfilter headers
  and sources). Our changes are a numbered patch series in `guest/linux/patches/`, plus
  `config-flippies` (a full `.config`). No kernel source is committed. Upgrading means rebasing
  the series.
- Built with `make ARCH=m68k CROSS_COMPILE=m68k-linux-gnu- bindeb-pkg` in Docker. The image sets
  `DEB_BUILD_PROFILES=pkg.linux-upstream.nokernelheaders`, which drops the m68k `libssl-dev`
  build dependency (not installable on Debian stable) and the `linux-headers` package we do not
  need; a build that sets `KDEB_SOURCENAME` must set the matching profile name itself. The
  package is named `linux-image-<release><LOCALVERSION>`, so `config-flippies` sets
  `LOCALVERSION=-flippies` to get `linux-image-*-flippies`. Checked with `virt_defconfig`: the
  build gives `linux-image-6.18.58-virt` and `linux-libc-dev` packages.
- Platform `CONFIG_FLIPPIES` under arch/m68k (Kconfig.machine), CPU options M68040 and MMU only:
  - Bootinfo machine type `MACH_FLIPPIES` = 15. Upstream's last is `MACH_VIRT` = 14; our fork
    never upstreams it, so a clash only matters on a rebase and is checked then. The loader
    passes `BI_MACHTYPE` (0x0001), `BI_CPUTYPE` (0x0002), `BI_FPUTYPE` (0x0003), `BI_MMUTYPE`
    (0x0004), one `BI_MEMCHUNK` (0x0005: 0, RAM size), `BI_COMMAND_LINE` (0x0007), optionally
    `BI_RAMDISK` (0x0006), then `BI_LAST` (tag 0, size 4). Each record is `__be16` tag, `__be16`
    size of the whole record, then data. CPU, FPU and MMU values are bit masks: `CPU_68040`,
    `FPU_68040` and `MMU_68040` are each 4. The first four tags are required (head.S stores
    them without checking they were found). Checked in `uapi/asm/bootinfo.h` and `head.S`.
  - `setup_arch` panics on an unknown machine, so the fork adds `case MACH_FLIPPIES` next to
    `MACH_VIRT` in `arch/m68k/kernel/setup_mm.c`.
  - `arch/m68k/flippies/config.c`: parse the machine's bootinfo, set `mach_sched_init`,
    `mach_init_IRQ`, `mach_reset` (SysCon POWER 0x7777), `mach_power_off` (0x5555),
    `mach_get_model` ("Create Flippies PC"), register the platform devices below.
  - `head.S` early console: our machine branch prints through COM1 for `CONFIG_EARLY_PRINTK`.
  - Interrupts: every controller line routed to output 0, which reaches IPL 4 (10.8.2). The IPL 4
    autovector handler reads CLAIM and dispatches Linux irq `FLIPPIES_IRQ_BASE + line` until it
    reads 0; mask and unmask write ENABLE. Line numbers as 3.2 and 11.1.1.
  - Timer: the interval timer (3.3) as a clockevent (periodic and one-shot, line 7) and as the
    clocksource and `sched_clock` (1 MHz, 64-bit COUNTER with its HI latch).
  - Device windows: one transparent translation register, as the virt machine does. `head.S`'s
    `mmu_init` gains a `MACH_FLIPPIES` branch with `mmu_map_tt #1,#0xFF000000,#0x01000000,
    #_PAGE_NOCACHE_S` (sets ITT1 and DTT1), which covers VRAM, devices and ROM (0xFFD8_0000 to
    0xFFFF_FFFF), and `arch/m68k/mm/kmap.c` gets virt's `ioremap` and `iounmap` shortcut (the
    physical address unchanged at or above 0xFF000000) for `MACH_IS_FLIPPIES`.
- Drivers (in the series, each a platform driver bound to devices that `config.c` registers):
  - COM1..COM3: the stock 8250 driver with platform data (`UPIO_MEM`, regshift 0, uartclk
    1,843,200, lines 1, 2, 8). Console `ttyS0`. No new driver.
  - `rtc-flippies`: read and set (3.4).
  - `flippies-hdd`: blk-mq, one request at a time, one HDD command per contiguous physical
    segment (at most 256 sectors), completion by interrupt (line 5), `/dev/fhda` with partitions.
  - The floppy through the same driver (line 9), removable, with media-change events
    (`/dev/fhdb`).
  - `flippies-nic`: NAPI, RX by interrupt (line 10), TX_CSUM and RX_INFO offload, link state
    from STATUS (`eth0`).
  - `flippiesfb`: fbdev for the three displays (`/dev/fb0` to `fb2`), 640x480 at 8 bits
    pseudocolour (mode 3) by default, 320x200 (mode 1) selectable, palette through the 0x400
    window, `mmap` of each display's VRAM slice, VBLANK interrupts (lines 6, 11, 12) for
    `FBIO_WAITFORVSYNC`. fbcon on fb0.
  - `flippies-ps2`: a serio port driver for each PS/2 port (lines 3 and 4). The stock `atkbd`
    and `psmouse` drivers sit on top (the controller passes raw set 2 bytes, like other
    non-PC PS/2 ports). No translation flag is needed: the port registers with type
    `SERIO_8042` and a `write` callback, and atkbd then stays raw in set 2. The commands it sends
    (FF, F2, F5, ED, F3, F4) are all handled by `Ps2Keyboard`.
  - `flippies-syscon`: reboot and power-off handlers, and the guest error light as an LED class
    device so a userspace error can light the case.
- FPU: the strict FPU option must be off for Linux PCs (the default), so the CPU emulates what the
  kernel's FPSP would otherwise trap for. `CONFIG_M68KFPU_EMU` off. Documented in the case
  tooltip later if it matters.
- Kernel size target under 6 MiB loaded (physical 0x1000 to `_end`); everything the boot needs
  is built in (no initramfs needed). For scale, `virt_defconfig` loads to about 7 MiB (`_end` at
  0x716000), so `config-flippies` must trim.

### 12.7 Boot chain (`guest/boot`)

- Disk layout (an MBR partition table, so Linux reads it with `CONFIG_MSDOS_PARTITION`). The boot
  ROM's check of 0x55 0xAA at 510 is also the MBR signature, so one sector does both jobs:
  - Sector 0: stage 1 (at most 440 bytes of 68k code), the partition table, 0x55 0xAA.
  - Sectors 1..127: stage 2 (`flipboot`), up to 63.5 KiB.
  - Partition 1 (type 0xDA, raw data), 32 MiB: the boot area, two kernel slots (A and B).
  - Partition 2, 128 MiB: swap.
  - Partition 3: the root file system, ext4, the rest of 1 GiB.
- Stage 1: loads stage 2 with the HDD in polled mode and jumps, passing the boot device and RAM
  size. Prints "flipboot" or a short error through the ROM's print routine (A1, 10.8.4). Stage 2
  cannot live at 0x10000: the kernel loads at physical 0x1000 up to `_end` (about 7 MiB for
  `virt_defconfig`), followed by the bootinfo and head.S's early page tables, so 0x10000 is
  inside that range. Stage 2 either runs from high RAM, above everything the kernel uses, or
  loads the kernel high and copies it down as its last step before the jump (boot chain owner's
  choice, recorded).
- Stage 2: reads the boot area's header ("FLBT", version, active slot, per slot: kernel offset and
  length, command line up to 512 bytes, CRC32s), loads the active slot's kernel ELF segments to
  their physical addresses, writes the bootinfo records, and enters the kernel at the ELF entry
  (`_start`, 0x2000 in the measured build) with the MMU off. Holding Esc during the 2-second
  prompt boots the other slot. Errors print on VGA and COM1 and stop. Rules checked in 6.18.58:
  - The kernel links at 0x1000 with `PAGE_OFFSET` 0; with the memory chunk at 0 it is loaded at
    physical 0x1000.
  - Each segment is copied only from max(p_paddr, 0x1000), advancing the file offset by the same
    amount. The first LOAD segment starts at address 0 and file offset 0, and its bytes below
    0x1000 are the ELF header and padding; copied, they would overwrite the boot ROM's vector
    table in page 0 (10.8.4 step 3) and send any later fault in stage 2 to address 0. Nothing
    below `_stext` (0x1000) is needed.
  - The loader zeroes each segment from `p_filesz` to `p_memsz`: the MMU `head.S` does not clear
    BSS, and BSS is entirely outside the file.
  - The bootinfo records start exactly at the physical address of `_end`, which is page
    aligned, with no gap, and end with a full `BI_LAST` record. head.S puts its early page
    tables on the next page after it, so the loader puts nothing there (a ramdisk goes well
    above).
- `flippies-boot` Debian package: `flipboot-update` writes a new kernel into the inactive slot,
  verifies it, then flips the active slot; installed as an `/etc/kernel/postinst.d` hook, as
  Debian's `flash-kernel` does for boards without a firmware bootloader. Default command line:
  `root=/dev/fhda3 rw console=tty0 console=ttyS0,115200`.

### 12.8 The Debian image (`guest/debian`)

- Userland: debian-ports m68k (unstable only; m68k has no stable release), from a snapshot pinned
  by date. snapshot.debian.org does archive debian-ports
  (`https://snapshot.debian.org/archive/debian-ports/`, monthly from 2008-10 to 2026-10, with
  gaps in 2023-01 and from 2023-08 to 2024-04, four timestamps a day lately; the latest when
  checked was 20261025T063552Z). apt reads it with `[check-valid-until=no]`, signed by the keys
  in `debian-ports-archive-keyring` (the 2026 and 2027 keys), so no mirror cache is needed.
- Built with `mmdebstrap` in Docker, running m68k binaries through qemu user mode. Docker
  Desktop's binfmt does not cover m68k; the image's entrypoint registers it when the container
  runs privileged, and the registration holds for later containers until the Docker VM restarts
  (12.14.5). `mmdebstrap` needs `arch-test` for any foreign-architecture bootstrap that runs
  maintainer scripts; checked: an essential m68k bootstrap succeeds in about 40 seconds.
- Contents: sysvinit-core (a lot lighter than systemd on a 25 MHz CPU), ifupdown with
  `dhcpcd-base` as its DHCP client (`isc-dhcp-client` is no longer in the archive),
  `systemd-standalone-sysusers` (xserver-xorg-core depends on udev, which needs `systemd |
  systemd-standalone-sysusers`; without naming it apt installs the full systemd package beside
  sysvinit-core), our kernel package and `flippies-boot`, Xorg with the fbdev driver and evdev
  input, xinit, the desktop (open question 2), xterm, a small font set (xfonts-base). Auto-login
  on tty1 starts X. `/etc/apt/sources.list` points at `http://deb.flip/` (only what the
  catalogue serves).
- Checked installable on m68k at snapshot 20261025T063552Z (`unstable` plus `unreleased`, an apt
  simulation against an empty status file; maintainer scripts not run): xserver-xorg-core
  2:21.1.25-2, xserver-xorg-video-fbdev, xserver-xorg-input-evdev, xserver-xorg-input-libinput,
  xinit, xterm, xfonts-base, xdm, sysvinit-core, ifupdown, dhcpcd-base, busybox-static. Window
  managers, all installable: jwm, icewm, fluxbox, openbox, twm, fvwm, wmaker,
  matchbox-window-manager, dwm, i3-wm. sysvinit-core, ifupdown, dhcpcd-base, the X server with
  fbdev and evdev, xinit, xterm, xfonts-base and jwm resolve to 170 packages together.
- The script writes a raw 1 GiB image (12.7's layout, stage 1 and 2 installed, kernel in slot A),
  then `mkfdi.py` makes the `.fdi` and the jar's chunk files. Target: under 450 MiB of used
  blocks, so the download is a few minutes at most.
- 128 MiB of RAM and 128 MiB of swap suit an X desktop of this weight.

### 12.9 Toolchains and the headless runner

- `guest/docker/Dockerfile`: one image (Debian 13 base, pinned by digest) with
  `gcc-m68k-linux-gnu`, `binutils-m68k-linux-gnu`, the kernel build dependencies, `mmdebstrap`
  with `arch-test`, `qemu-user` and `qemu-user-binfmt` (in Debian 13 `qemu-user-static` is only a
  transitional package), `debian-ports-archive-keyring`, Python 3. Every guest build script runs
  inside it through `guest/docker/build.sh`, with `/build` (a named volume) as the
  case-sensitive scratch area; the repo holds only inputs and outputs. The Mac needs only Docker
  (present: Docker Desktop 29 on arm64). As built: 12.14.5.
- Headless runner (test sources, `computer/tools/HeadlessPc`, Gradle task `runHeadlessPc`): builds a
  `Machine` from command-line options (CPU, RAM, HDD image file, floppy image), connects COM1 to
  stdin and stdout, links the NIC to an in-process `Modem` serving a catalogue folder from disk,
  runs unthrottled, and writes a PNG of a display on request (a text dump in text mode). This is
  the development loop for the kernel, the boot chain, the image and the floppy, without
  starting Minecraft. As built, with its usage: 12.14.4.

### 12.10 Order of work and owners

1. Done (12.14). In parallel, no dependencies between them:
   - Core: the MMU (12.2).
   - Core: faster ethernet (12.3) and `FrameEndpoint`.
   - Core: the modem stack and `.fdi` reader (12.4.2 to 12.4.4), then the headless runner (12.9).
   - Guest: the Docker image (12.9).
2. Guest: the netinstall floppy (12.5), tested in JUnit against the modem stack with a small
   test image. Needs 12.3, 12.4 and the Docker image.
3. Guest: the kernel (12.6), in steps, each booted in the headless runner: serial console and
   timer to a busybox initramfs shell first, then the HDD, then the NIC, then the framebuffer and
   PS/2. Needs the MMU.
4. Guest: the boot chain (12.7), then the Debian image (12.8). Needs the desktop decision.
5. Game: the modem block, PoE, the exchange, the netinstall floppy item, the catalogue in
   resources (12.4.5, 12.5). Can start after step 1's modem stack; needs the model.
6. Spec update: as-built notes and Implementation status.

### 12.11 Test plan

- MMU: 12.2.5.
- Ethernet: 12.3.
- Modem (`computer/net` tests): ARP, ICMP echo, DHCP exchange, DNS hits and NXDOMAIN, TCP
  handshake, a transfer with the NIC's queue full part of the time (no loss), retransmission
  after a dropped frame, FIN and RST, HTTP GET, HEAD, Range and 404, the catalogue reader and
  the `.fdi` writer and reader round trip.
- Netinstall (`e2e`): a 68040 machine plus a `Modem` in one JUnit test: boot the floppy, script
  the keys, download a 2 MiB test image, check the HDD's bytes and that absent chunks were not
  written; a connection dropped midway resumes; a corrupt chunk is re-fetched.
- Linux (tagged `linux`, not in the default suite because it needs the built image): boot in the
  headless runner to "login:" on COM1 within a set emulated time; `ping 10.88.0.1`; fetch a file
  over HTTP from the modem; a screenshot of X showing the desktop.

### 12.12 Risks

- MMU restart: Linux leans on precise, restartable faults everywhere (demand paging,
  copy-on-write, user copies). Bugs show up as rare memory corruption. Mitigation: the restart
  tests of 12.2.5 for every addressing mode, and running the Linux boot many times.
- Speed: a busy 68040 at 25 MHz needs about 10 ms of host time per tick without the MMU, against
  a 2 ms default budget (Implementation status), and the MMU adds 7% to 14% (12.14.1). Linux
  booting is CPU-bound, so at the default budget it runs at a fifth of real time or less: several minutes to boot.
  Raising `pc.hostBudgetPerPcMicros` to about 15000 for a server running one Linux PC makes it
  close to real time. A faster interpreter (a decoded-instruction cache) is the next lever and
  is not planned here.
- debian-ports m68k is unstable-only: some packages may fail to build or install on the snapshot
  date. Mitigation: pin a snapshot that installs cleanly; choose the desktop from what is there.
- Kernel facts about bootinfo, `head.S` and the 68040 fault path were checked in 6.18.58 (step
  1). A rebase onto a later longterm must check them again (`guest/linux/NOTES.md` lists them
  with file paths and line numbers).
- Licences: shipping a Linux kernel and Debian binaries inside the mod jar is redistribution
  under the GPL and other licences, which requires offering the corresponding source. See open
  question 5.

### 12.13 Open questions

1. Jar size: the Debian image adds roughly 120 to 200 MB compressed to the mod jar. Keep it in
   the jar (as decided), or ship a small jar and have the server fetch the image once from a
   pinned, hash-checked release asset into the world folder?
2. Answered (12.16): a super minimal X desktop with a window manager and taskbar. Was: "a custom GUI based on Debian" could mean Xorg with a light window manager we theme
   (JWM, IceWM or similar; 12.8 lists the ones that install on m68k), or our own desktop program
   on top of X or straight on the framebuffer. Which?
3. Answered (12.16): a combined keyboard and mouse block, not built yet. Was: an X desktop with only a keyboard is awkward. The PS/2 mouse is emulated already; the
   mouse block is not designed. Build it alongside this, or keep the desktop keyboard-driven
   for now?
4. The modem's model: one you make, like the PC case and keyboard?
5. Licences: is shipping GPL binaries in the jar acceptable for where the mod is published, with
   the kernel patches and image build scripts in the public repo as the source offer?
6. Should the HDD get faster for the bigger drives (5 MB/s today for every size), or stay as it
   is?

### 12.14 As built (step 1)

Step 1 of 12.10: the MMU, faster ethernet, the modem stack with `.fdi`, the headless runner and
the Docker toolchain. Each piece had an adversarial review; every finding was real and is fixed,
with a regression test where the piece has a test harness. The plan text above is already
corrected; this section records decisions, deviations and measurements a later owner needs.

#### 12.14.1 MMU

- Files: `cpu/m68k/M68kMmu.java` (new, package-private): TC, ITT0..DTT1, URP, SRP and MMUSR
  (moved out of `M68kCpu`), the table walk, PTEST, the flushes and the TLB. `M68kCpu` reads the
  TLB inline: each data accessor translates only when the `mmuOn` flag is set, and `fetch16`
  compares against the current code page.
- TLB: direct-mapped, 6 x 1024 entries (user and supervisor, times fetch, read and write), keyed
  by logical page: an int tag, an int physical page and a global flag per entry. MOVEC to TC,
  URP, SRP, ITTx or DTTx flushes all of it; MOVEC to MMUSR does not. RESET flushes it too (a
  superset of the chip).
- Register masks: TC keeps 0xC000, the TT registers 0xFFFF_E364, URP and SRP every bit (the walk
  ignores bits 8..0). These replace 10.5's "store as written".
- Restart, instruction by instruction:
  - The undo record (kept only while the MMU is on, so MMU-off behaviour is unchanged) holds up
    to 8 address registers changed by (An)+ and -(An), the CCR at the start of the instruction,
    and for RTE a snapshot of SR, USP, ISP, MSP and A7 (a format 1 frame switches stacks before
    the next frame is read).
  - Reordered so registers change only after the last access that can fault: push16 and push32
    write before moving SP; LINK, UNLK, RTR, RTD; MOVE16 reads the whole line, writes it, then
    moves its address registers; MOVEM memory to registers buffers all its loads.
  - Translated before the first write: CAS2 (both operands, for writing), CAS and TAS (locked
    translation), bitfield instructions that modify memory, and any access that crosses a page
    (both pages; adjacent physical pages run as one access, otherwise byte by byte).
  - Harmless to re-run, so left as they were: MOVEM to memory (including -(An) with An in the
    list), MOVEP, PACK and UNPK to memory, ADDX, SUBX, ABCD and SBCD on memory (CCR restored),
    other read-modify-writes of one location, FMOVEM loads (FPSR accrued bits may be ORed twice,
    which changes nothing).
- Frame details beyond 12.2.3: MOVE16 reports SIZE line (3) and TT 1, even with the MMU off.
  Instruction fetch faults report SIZE word (the chip fetches longs or lines; Linux ignores the
  field).
- Deviations from the chip, all deliberate:
  - The TLB caches only successful translations. The chip's ATC also caches failed searches (R
    clear, or S set), so on real hardware software must PFLUSH after repairing an invalid
    descriptor; the emulator is more permissive. Linux flushes anyway.
  - A fault while stacking any exception frame halts the CPU (the old double-fault rule). The
    chip takes an access error unless it is already stacking an access error or reset frame.
    Linux keeps its kernel stacks mapped.
  - A table walk costs 0 cycles.
  - A version 1 CPU state loads with every TT register's E bit cleared as well as TC.E (TT
    registers had no effect in version 1).
- Review findings fixed:
  - FPU immediate operands (`#imm`) were read through data translation (DTT registers, the data
    TLB, data function codes). They are now fetched through the instruction stream (code page,
    ITT registers, TM 2 or 6 on a fault), at the same cycle cost.
  - The TLB was not saved, so after a descriptor change without a PFLUSH a restored machine
    left lockstep with an uninterrupted one (different page, extra U and M write-backs). The TLB
    entries and the current code page are now saved state (12.2.5). Version 2 was never
    released, so its layout changed without a version bump.
- Tests: `M68kMmuTest` (32: walks with both page sizes, indirect descriptors, faults at every
  level, U and M write-back, W and S, TT matching, restart of every addressing mode, the format
  7 frame word by word, PTEST, every PFLUSH form, MOVES, FPU immediates, the saved TLB),
  `M68kMmuDemandPagingE2eTest` (2: a 68040 guest that pages a user program in on demand, run
  straight through and saved and loaded every tick in lockstep), and
  `M68kMmuThroughputBenchmarkTest` (tagged `benchmark`).
- Benchmark, 68040 machine at 40 MHz, single runs on a host shared with other Gradle runs, so
  rough: MMU off 82.0 and 81.5 M instructions per second in one run, 82.1 and 83.6 in another;
  MMU on 76.7 (93% of off) and 71.5 (86%). The target was at least 60%. The MMU-off benchmarks
  did not slow down: 68040 interpreter 102.0 M/s, 68040 machine 85.0, 68000 interpreter 100.2,
  68000 machine at 16.67 MHz 87.8.

#### 12.14.2 Faster ethernet

- `Nic`: `QUEUE_FRAMES` is 256 each way, RX_INFO at 0x38, CTRL bit 5 TX_CSUM (CTRL masked to
  0x3F on write and on load), `SIZE` 0x3C. The saved state stays at version 1 with the same
  format; the reader caps each queue at 256 frames (257 is refused), and a hand-written state with
  32 frames in each queue loads byte for byte. A full card adds up to about 760 KiB to a PC's
  saved state.
- RX_INFO is recomputed from the oldest received frame on every read (0 with the queue empty);
  nothing is cached or saved. Bit 2 means only that the type field is 0x0800. Bit 0 needs
  version 4, a header of at least 20 bytes, a total length that covers the header and fits in the
  frame (padding after it allowed), and a header that sums to 0xFFFF. Bit 1 needs bit 0, no
  fragment, TCP or UDP, and a good checksum over pseudo-header and segment. A UDP checksum of 0
  (none computed) leaves bit 1 clear, which Linux allows.
- TX_CSUM recomputes from scratch (ignoring what the guest left in the field) the IPv4 header
  checksum and, for a TCP or UDP frame that is not a fragment, its checksum; a UDP result of 0 is
  sent as 0xFFFF. Fragments get only the header checksum; frames that are not well-formed IPv4
  go out unchanged. Only the queued copy changes, not guest memory.
- `FrameEndpoint` (`computer/net`) with `canReceive` (12.4.2); `PcLinkExchange.ethernet` takes
  two endpoints and `ethernetDown` one. Its real caller is `PcServer`, which needed no change.
- Review findings fixed: a UDP segment shorter than its 8-byte header made RX_INFO and TX_CSUM
  read past the frame and throw out of a bus access (one guest could crash another PC on its
  link); `l4Length` now checks the payload length first. The modem's held frames counted as
  RX_DROPPED and RX_OVERFLOW; `canReceive` fixed that.
- Tests: `NicTest` (15), `NetworkE2eTest` adds a burst of 256 full frames with offload and
  RX_INFO on RV32I, the 68000 and the 68040, and `LinkExchangeTest` adds a NIC linked to another
  endpoint.

#### 12.14.3 The modem stack and `.fdi`

- Package `computer/net`. Public: `Modem`, `Catalogue`, `ContentSource`, `FolderContent`,
  `MemoryContent`, `CatalogueFile`, `CatalogueIndex`, `DeflatedChunks`, `Fdi`, `Packets`,
  `FrameEndpoint`. Package-private: `DhcpServer`, `DnsServer`, `Tcp`, `TcpConnection`,
  `HttpSession`.
- `Modem`: `new Modem(long mac, Catalogue)`, `exchange`, `tick`, plus counters for the block's
  lights and for tests (`framesIn`, `framesOut`, `framesDropped`, `outgoingCount`,
  `connectionCount`, `retransmissions`, `leaseHolder`). Link down clears the queue and drops
  every TCP connection silently; `tick` does nothing while the link is down. The outgoing queue
  holds 256 data frames plus 64 slots for frames without data.
- IPv4: fragments, bad header checksums and bad TCP or UDP checksums are dropped; frames sent
  carry DF, TTL 64 and a rising IP ID. ICMP: echo reply only.
- DHCP: DISCOVER gets an OFFER; a REQUEST for 10.88.0.2 (option 50 or ciaddr) gets an ACK, any
  other address a NAK, one naming another server id is ignored; RELEASE clears the lease;
  DECLINE and INFORM are ignored. Options 53 and 54, plus 51 (86400), 1, 3 and 6 on OFFER and
  ACK. Replies are padded to 300 bytes. Addressing follows RFC 2131 4.1; relays are not
  supported.
- DNS: A or ANY for a catalogue host (any case) answers 10.88.0.1, TTL 3600; other types for a
  catalogue host get NOERROR with no answer; every other name, including `flip` itself and
  subdomains of a host, gets NXDOMAIN. Not exactly one question: FORMERR; another opcode:
  NOTIMP; a response: dropped. AA and RA set, RD copied, EDNS ignored.
- TCP: window scaling as 12.4.2 says (a deviation the plan lacked). MSS 1460 offered, the peer's
  honoured (536 when absent). Go-back-N resend after 20 ticks without progress; after 10
  timeouts in a row, a RST. Zero window: a 1-byte probe on each timeout. A SYN when 8
  connections exist is dropped silently. Out-of-order segments are dropped with a duplicate ACK.
  A SYN on an open connection gets a challenge ACK (RFC 5961). An in-window RST closes the
  connection; segments for no connection get a RST (RFC 9293 3.5.2). Idle for 1200 ticks: we
  close with a FIN, then wait up to 1200 ticks for the client's; no TIME_WAIT. The first ISS is
  0xFFFFF000, so a first big transfer crosses the 32-bit wrap.
- HTTP: Host from the header or an absolute URL (port ignored). 400 for a missing Host,
  malformed or folded lines, a bad Content-Length, `%2F` or `%00`; 431 for headers over 8 KiB;
  505 for HTTP/2 and later; 501 for Transfer-Encoding (and close) and other methods (keep the
  connection). `.`, `..`, empty segments and paths ending in `/` get 404. Range: one range in
  the forms a-b, a- or -n; several or an invalid one send the whole file; unsatisfiable gives
  416 with `Content-Range: bytes */size`; If-Range always sends the whole file (we send no
  validators). Content-Type is text for txt, html for html, else octet-stream. A store error
  gives 500; an error while streaming a body ends the connection with a RST.
- `index.txt`: kind, path, size, then the title as the rest of the line, separated by spaces or
  tabs; the path is absolute on get.flip; `#` lines and blank lines are skipped. `FolderContent`
  treats only lower-case folder names as hosts.
- `.fdi`: the layout of 12.4.4. Deviation: a CRC32 of the chunk table at offset 16, which the
  plan did not list.
- Measured throughput (8 MiB, the PC's card holding 256 frames, the client reading 256 frames a
  tick, no retransmissions in any case): TCP receive window 64 KiB, 129 ticks, about
  1.24 MiB/s; 256 KiB, 33 ticks, about 4.8 MiB/s; 1 MiB or 4 MiB, 23 ticks, about 7 MiB/s.
- Review findings fixed: a valid-checksum TCP segment shorter than 13 bytes threw out of
  `Modem.deliver` (the length check now comes first); a client advertising a zero window could
  queue responses without limit (now at most 32 per connection, 12.4.2); a `RuntimeException`
  from the content source (an invalid path on Windows, a `ResourceLocationException` in the
  game) escaped into the exchange phase and now gives 404. During the build, a NAK that copied
  ciaddr (RFC 2131 table 3 says 0) was also fixed.
- Not built (not in the plan): DHCP relays, DHCPINFORM, ICMP unreachable replies, fast
  retransmit.
- Tests: `ModemTest` 8, `TcpTest` 20, `HttpSessionTest` 15, `CatalogueTest` 9, `FdiTest` 6,
  `ModemE2eTest` 3 (DHCP, DNS and a download through a real `Nic` that stalls, with RX_DROPPED 0
  and RX_OVERFLOW clear afterwards).

#### 12.14.4 Headless runner

- Test sources, package `computer/tools`: `HeadlessPc` (options, run loop), `RawDiskFile` (a raw
  image file as a `PagedDiskImage`, written back with `flush`), `Screenshot`. Gradle task
  `runHeadlessPc` (a `JavaExec` on the test runtime classpath, Java 17, stdin forwarded,
  `-Djava.awt.headless=true`).
- Usage:
  `./gradlew -q --console=plain runHeadlessPc --args='--cpu 68040 --ram 32M --hdd hdd.img --floppy boot.img'`
  - `--cpu rv32i|68000|68040` (default 68040); `--clock HZ` (25000000, 25MHz, 12.5M; default
    the model's); `--ram SIZE` (K, M or G, powers of 1024; default 8M).
  - `--hdd FILE` (a multiple of 512 bytes, at most 1 GiB); `--hdd-size SIZE` creates a missing
    HDD file, sparse; `--floppy FILE` (1,474,560 bytes; shorter reads as zeros past its end).
  - `--readonly` never writes the image files; `--sync-seconds S` also writes disk changes back
    every S emulated seconds.
  - `--catalogue DIR` links the NIC to a modem serving DIR (one lower-case folder per host);
    `--modem` a modem with an empty catalogue (DHCP, DNS, ping; HTTP 404s).
  - `--mac MAC` (default 02:46:4c:50:43:01; the modem's is 02:46:4c:4d:44:01); `--strict-fpu`;
    `--epoch SECONDS` fixes the RTC start (default host time).
  - `--max-seconds S` in emulated time; `--screenshot FILE` at exit, of `--display N` (0 to 2);
    `--realtime` holds emulated time to wall-clock time; `--help`.
  - Both `--opt value` and `--opt=value` work.
- The machine is built from `MachineConfig` and the model's `BootRoms` image, as the game does,
  then cold-booted (floppy first, then HDD). A keyboard is on PS/2 port 0 and a mouse on port 1,
  neither given input. The floppy is not write-protected.
- Each tick: `machine.runTick` for 50 ms with a 10-second budget (no emulated time dropped), then
  COM1 to stdout and stdin to COM1, then with a modem `modem.exchange(nic)` and `modem.tick()`.
  The link is always up. COM1 is raw bytes with no line-ending translation (run `stty raw -echo`
  first for an interactive shell). stdin is read on a daemon thread; bytes the UART cannot take
  yet wait, and end of input does not stop the guest.
- Disks: changed 64 KiB chunks are written back in place and fsync'd at every exit (a shutdown
  hook covers SIGINT and SIGTERM, waiting up to 30 s) and every `--sync-seconds`.
- Exit status: the guest's on SysCon power-off (POWER 0x5555 gives 0; 0x3333 with code C in bits
  31..16 gives C's low byte, or 1 if that is 0). The runner's own: 124 time limit, 125 CPU
  halted, 64 bad options or setup, 70 runner error, 130 on SIGINT, 143 on SIGTERM (the JVM's
  status after a signal wins). The last stderr line names the reason, the guest's full code in
  hex, emulated and host seconds and the speed. Through Gradle any non-zero status becomes 1;
  for the exact status run the class with `java` on the test runtime classpath. Gradle's stdout
  starts with build.gradle's "Java: ..." line even with `-q`.
- Screenshot: graphics modes give a PNG at the mode's resolution (8 bpp through the palette,
  4 bpp with the high nibble the left pixel, black while DISPLAY is clear). Text mode gives FILE
  with `.txt`: one line per row, code page 437, control characters as spaces, trailing spaces
  trimmed, attributes dropped. There is no PNG for text mode because the codebase has no 8x16
  font outside Minecraft (`CrtFont` is built from Minecraft's `ascii.png`).
- Deviations from 12.9: the extra options `--realtime`, `--sync-seconds`, `--readonly`,
  `--hdd-size`, `--epoch`, `--mac`, `--strict-fpu` and `--display`; text-mode screenshots as
  text. A review finding fixed: the docs said 130 for SIGTERM as well.
- Speed on the developer's Mac, through Gradle with JVM start: a busy 68040 loop at 25 MHz, MMU
  off, 30 s emulated in 1.76 s (about 17 times real time); Cog Maze on RV32I at 25 MHz, mostly
  waiting for vertical blank, about 5 times; an idle 68040 boot floppy, 3.3 to 3.8 times. The
  plan's "about 3 times real time for a busy 68040" was not reproduced; Linux with the MMU on is
  the real measure.
- Tests: `HeadlessPcTest` (6): a 68040 boot floppy that prints through the ROM, ARPs the modem,
  writes the HDD and echoes a stdin byte, then exits with 42; `--readonly`; no modem until the
  time limit (124); Cog Maze on RV32I to its title screen PNG; option parsing; setup errors.

#### 12.14.5 Docker toolchain

- Files: `guest/docker/Dockerfile`, `guest/docker/build.sh`, `guest/docker/entrypoint.sh`;
  kernel and debian-ports facts in `guest/linux/NOTES.md`; `guest/linux/.cache/` for tarballs
  (ignored by git).
- Base `debian:trixie-slim` pinned by digest
  (`sha256:62bd423aeee912639ca8ee681c2b762cb3e25137e0a92b20be455898a9f034ad`). Cross GCC 14.2 and
  binutils 2.44. Packages beyond 12.9's list: `arch-test`, `libdw-dev`, `debhelper`, `dpkg-dev`,
  `fakeroot`, `gpg`, `patch`, `git`, `xz-utils`, `zstd`, `curl`, `ca-certificates`, `file`,
  `e2fsprogs`, `fdisk`. `qemu-user` and `qemu-user-binfmt` replace `qemu-user-static`.
- `DEB_BUILD_PROFILES=pkg.linux-upstream.nokernelheaders` is set in the image (12.6).
- `/build` is created with mode 1777 and `build.sh` mounts a named volume there
  (`FLIPPIES_GUEST_VOLUME`, default `create-flippies-guest-build`), the case-sensitive work area
  for kernel trees and other large intermediates.
- The entrypoint registers m68k with binfmt_misc from qemu's `/usr/lib/binfmt.d/qemu-m68k.conf`
  (flags POF) when the container is privileged and m68k is missing.
- `build.sh` usage: `guest/docker/build.sh` for a shell, `guest/docker/build.sh <command>` to run
  one command (in the matching directory under `/work` when called from inside the repo),
  `--privileged <command>` for mmdebstrap and the binfmt registration, `--image-only` to build
  the image and stop. The image is rebuilt from cache on every call. On a Linux host unprivileged
  runs use the caller's uid and gid. `FLIPPIES_GUEST_IMAGE` overrides the image name (default
  `create-flippies-guest:latest`).
- Proved by running: a freestanding `-m68040` object linked to a flat binary (`__mc68040__`
  defined, 68040-only instructions emitted); `mmdebstrap --variant=extract` from debian-ports,
  and a refusal with an empty keyring; an essential m68k bootstrap that runs maintainer scripts
  (about 40 s, an 83 MB tarball); `virt_defconfig` plus `bindeb-pkg` of 6.18.58 in about a
  minute, giving `linux-image-6.18.58-virt_6.18.58-1_m68k.deb` (3.1 MB) and
  `linux-libc-dev_6.18.58-1_m68k.deb`, and failing on unmet `libssl-dev` with the profile
  emptied.
- Review findings fixed: `arch-test` was missing (mmdebstrap refused every m68k bootstrap that
  runs maintainer scripts); `libdw-dev` and the build profile were missing (`bindeb-pkg`
  failed); kernel trees were to be unpacked in the case-insensitive repo folder (13 paths
  merged); NOTES called loading page 0 harmless (now the max(p_paddr, 0x1000) rule of 12.7) and
  claimed a fetch script that does not exist.

### 12.15 As built (step 3: the Linux fork)

Linux 6.18.58 with our own drivers boots on the 68040 PC (25 MHz, 128 MiB) to a busybox shell
on COM1 and on the framebuffer console. Nothing under `src/main` changed in this step: every
fault found was in the guest port, and the emulator was checked against qemu-m68k byte for byte
on integer, floating point, compression and hashing work.

#### 12.15.1 Patch series and config

- `guest/linux/patches`, applied in name order by `fetch.sh`:
  - 0001: the machine (`MACH_FLIPPIES`, head.S early serial, bootinfo, clock, reset and power).
  - 0002: the platform devices, and `min_low_pfn = 0` in `arch/m68k/flippies/platform.c`.
  - 110: `flippies-hdd`, one blk-mq driver for the HDD (`fhda`, MBR partitions) and the floppy
    (`fhdb`, removable, media-change interrupt, read-only while the tab is set).
  - 120: `flippies-nic`, no rings (one buffer per command), NAPI receive, checksum offload both
    ways (TX_CSUM, RX_INFO), link state from the link interrupt.
  - 130: `flippiesfb` for all three displays (default 640x480 at 8 bits, panning, mmap,
    FBIO_WAITFORVSYNC on the display's interrupt, fbcon 80x30 on fb0) and `flippies_ps2`, which
    registers both ports as `SERIO_8042` so the stock atkbd and psmouse bind.
  - 140: `rtc-flippies` (64-bit seconds, hwclock works) and the error light as the LED
    `flippies::fault` (also the panic indicator).
- Each of 110 to 140 applies alone on top of 0001 and 0002.
- Config: fragments `00-base`, `block`, `input-fb`, `misc` and `net` in `config.d`, merged onto
  allnoconfig in that order. Not yet the single `config-flippies` of 12.6.
- checkpatch `--strict --no-signoff`: 0 errors on every patch, one warning each (no
  MAINTAINERS entry). No Signed-off-by lines.
- Why `min_low_pfn = 0` is in 0002: both the disk controllers and the network card DMA into
  kernel image pages freed after boot, which dma-direct otherwise refuses ("DMA addr ...
  overflow"), and the read then retries forever. The virt machine does the same.
- flippies_ps2 keeps each port's interrupt and port enable on from probe onward, as i8042
  does. Bytes reaching an unbound port make serio rescan it, so a keyboard or mouse plugged in
  after boot binds. (A review found that clearing CTRL in serio close left hot-plugged devices
  dead; fixed.)
- Port fixes found while booting: `COMPAT_32BIT_TIME` must be on (glibc sleeps returned at
  once without it), and the clock event device's maximum delta is capped at 1 s (a long
  tickless idle made timekeeping catch up with interrupts off and overran the COM FIFO).

#### 12.15.2 The kernel

- `guest/linux/fetch.sh`, `build.sh` (`--tree`, `--patches`, `--fragments`, `--out`, `--deb`)
  and `mkinitramfs.sh` (busybox from the pinned debian-ports snapshot; `--net`).
- Full series: text 3,903,806, data 1,129,300, bss 196,800. `_end` is 0x4ff000, so 4.99 MiB
  loads from 0x1000, under the 6 MiB target. Entry 0x2000.
- `vmlinux` is 5.9 MB. `build.sh --deb` makes `linux-image-6.18.58-flippies_*_m68k.deb`
  (2.2 MB) whose `/boot/vmlinuz` is `out/vmlinux` byte for byte.
- With every driver in: "Memory: 122384K/131072K available".
- `initramfs/init` also starts a shell on tty1 when the console is a ttyS port and /dev/fb0
  exists, so the PS/2 keyboard has a shell.

#### 12.15.3 Measured

- Boot to the prompt with the console pair `console=tty0 console=ttyS0,115200`: 5.2 s
  emulated, 1.3 s of host time. With ttyS0 alone: 3.4 s. The serial console is the main cost:
  it reprints the whole log when it registers and holds interrupts off about 7 ms per line, so
  `quiet` on the Debian command line roughly halves boot time.
- Network (busybox wget from the modem): at 25 MHz the guest CPU limits it, about 4.8 MiB/s to
  /dev/null. At 40 MHz the card's 256 frames per tick is the steady state: 32 MiB in 4.84 s
  (6.6 MiB/s) after a 5-tick receive-window ramp. Checksum offload saves about 20% of the CPU;
  NAPI gives 124 interrupts for 17,338 frames.
- Disk: mke2fs of 16 MiB takes about 3 s. A direct 64 KiB floppy read takes 4 s (one command
  per 4 KiB page). sha256 runs at about 190 KB/s at 25 MHz.
- The whole test session: 30.9 s emulated in 5.6 s host (5.5x real time) on the developer's
  Mac. Under the game's 2 ms budget per tick Linux runs far slower than real time (12.12).

#### 12.15.4 Runner and tests

- HeadlessPc gained `--kernel`, `--initrd` and `--cmdline` (new `KernelLoader`: segments from
  max(p_paddr, 0x1000), bootinfo at `_end`, the initrd page-aligned at the top of RAM),
  `--input FILE` (timed keys, text, mouse moves and buttons, screenshots), `--screenshot-at S`,
  and a package-private per-tick listener for tests.
- `LinuxBootTest`, tagged `linux`, runs with `./gradlew linuxTest`; the default `test` task
  excludes the tag. It is skipped while `guest/linux/out/vmlinux` or the initramfs is missing.
  - The boot session: 128 MiB, a 32 MiB HDD with an MBR, a floppy, the modem with a small
    catalogue and scripted PS/2 typing. 26 commands on COM1 cover the partitions, mke2fs and
    mount, the floppy, DHCP, ping, two wget downloads, the RTC and the error light; then a
    screenshot of fbcon and power off, and host-side checks of the disk images.
  - The hot-plug test empties both PS/2 ports, waits out the probe (it ends about 9.85 s after
    power-on, so the test sleeps 10 s), then plugs in a keyboard and a mouse and checks they
    bind and deliver input.
- Computer suite: 1012 tests, 0 failures.

#### 12.15.5 Open

- No `BI_RNG_SEED`: "crng init done" came 259 to 405 s after boot, and Debian's `getrandom()`
  callers will block until then. Stage 2 (12.7) should pass a seed.
- The system clock drifts from the RTC, because the RTC runs on host time and the kernel on
  emulated time.
- Runner gaps: no floppy eject or write-protect option, and `--kernel` is not reloaded after a
  guest reboot (stage 2 will do that in the real boot chain).
- Not yet covered from 12.11: "login:" on COM1 and an X screenshot, which need the Debian
  image.
- The Docker image lacks `libc6-dev-m68k-cross` (no m68k userland C programs; harmless
  dpkg-genbuildinfo error) and checkpatch's python3-ply and python3-git.
- `build.sh --deb` with a custom `--tree` removes `/build/*.deb`, shared by every tree.
- The Docker build volume is nearly full (about 1.2 GB free); old check trees can go. The
  current series lives as git commits in `/build/series`, branch `flippies`.
- VgaDisplay marks the whole frame dirty on every CTRL write (3.7), so a guest that toggles the
  vertical blank interrupt makes the game resend the frame. fbcon's cursor blink also dirties
  rows five times a second; `vt.global_cursor_default=0` avoids it.
- Later config the Debian image needs: SYSVIPC, INOTIFY_USER, SWAP, UNIX sockets and the rest of
  Debian's list.

### 12.16 As built (step 4: boot chain, Debian image and desktop)

One raw 1 GiB disk image (`guest/debian/out/debian.img`) boots from the boot ROM, with no
`--kernel`, to a JWM desktop: the ROM loads flipboot stage 1 from the MBR, stage 1 loads stage 2,
stage 2 loads the kernel from the boot area's active slot, then sysvinit, Xorg on flippiesfb and
JWM with a taskbar and an xterm. The PS/2 mouse moves the cursor and opens the Start menu.
Nothing under `src/main` changed. The GUI decision (12.13 question 2): a super minimal X desktop,
a simple window manager plus a taskbar. The mouse block (question 3) is decided but not built:
one "keyboard and mouse" block that duplicates the keyboard model.

#### 12.16.1 Disk layout (`guest/boot/mkdisk.py`; `FlipbootDisk` in the tests writes the same bytes)

- Sector 0: stage 1 in bytes 0..439, disk signature "FLPC" at 440, the MBR partition table (LBA
  only), 0x55 0xAA. Sectors 1..127: stage 2.
- p1, sectors 2048..67583, type 0xDA, bootable: the boot area. p2, 67584..329727, type 0x82:
  swap. p3, 329728 to the end, type 0x83: the ext4 root.
- `mkdisk.py create OUT --stage1 --stage2 [--size] [--kernel-a F --cmdline-a T] [--kernel-b ...]
  [--active a|b] [--root F]` writes a sparse image; `mkdisk.py show IMAGE` checks it.

#### 12.16.2 Boot area format (big-endian)

- Header, 4096 bytes: "FLBT", version 1 at 4, active slot at 8 (0 = A, 1 = B), CRC at 12 (over
  the header with this field as 0), generation at 16, slot A record at 32, slot B at 576.
- Slot record, 544 bytes: kernel offset from the partition start (multiple of 512), length (0
  for empty), CRC of the whole kernel file, and at +32 a 512-byte NUL-terminated command line
  of at most 239 characters, so stage 2's marker always fits in m68k's 255.
- A second header copy at byte 4096. Stage 2 uses copy 0 if it checks, else copy 1, else stops.
- Slot A's kernel at 1 MiB, slot B's at 16 MiB.
- Every CRC is the POSIX `cksum` CRC, so the Debian package needs no m68k binary.

#### 12.16.3 Stage 1 (`guest/boot/stage1.S`, 252 bytes)

- Prints "flipboot" through the ROM's print routine (A1). Needs 16 MiB of RAM.
- Reads sectors 1..127 to 0x00F00000 with the HDD polled, checks "FLB2", and enters stage 2 with
  D0 = RAM size, D1 = capacity, A1 unchanged. Errors print "flipboot: ..." and stop.

#### 12.16.4 Stage 2 (`start.S`, `flipboot.c`, 5,408 bytes)

- Linked at 15 MiB, with bss, buffers and stack below 16 MiB. The kernel loads straight to
  0x1000.._end with the bootinfo after it, so a stage 2 above all of that never moves itself;
  the kernel plus bootinfo must end below 15 MiB (it ends at 0x51E000).
- Reads both header copies, prints "flipboot: slot X active, generation N". If the other slot
  holds a kernel it waits 2 s for Esc on either PS/2 port, which boots the other slot. With the
  other slot empty there is no prompt and no wait. A key held since power-on is lost (the ports
  are disabled until stage 2 enables them).
- Loads the ELF with KernelLoader's rules: each PT_LOAD from max(p_paddr, 0x1000), p_filesz to
  p_memsz zeroed, `_end` as the last segment's end rounded to 4 KiB (the symbol table is
  streamed past; equal to the `_end` symbol today). The file streams once in 64 KiB chunks,
  CRC'd as it goes, straight into place by DMA where possible, the next read overlapping the
  CRC.
- When a slot cannot boot (empty, bad ELF, CRC error, too big) it says why and tries the other
  slot. Disk errors stop at once.
- Writes the bootinfo at `_end` in KernelLoader's order: MACHTYPE 15, CPUTYPE, FPUTYPE and
  MMUTYPE 4, MEMCHUNK, BI_RNG_SEED (tag 8; 32 bytes from the RTC, the interval timer and the
  kernel CRC, mixed; the kernel credits it, so "crng init done" comes at once; not
  cryptographic), COMMAND_LINE with ` flipboot.slot=A` or `B` appended, LAST.
- Enters the ELF entry with SR 0x2700, SP 0x1000, the MMU off.
- Power-on to kernel entry: about 1.5 s emulated (disk-bound: the HDD's 5 MB/s alone needs
  1.25 s for the 6 MB vmlinux), plus 2 s with the prompt.

#### 12.16.5 `flippies-boot` package (`guest/boot/flippies-boot`, `flippies-boot_1.0_all.deb`)

- `/usr/sbin/flipboot-update [--device DEV] [--cmdline TEXT] KERNEL` and `--show`, POSIX sh with
  dd, od, cksum and blockdev, plus the `/etc/kernel/postinst.d/zz-flipboot` hook.
- Order of writes, each followed by sync, so a power loss at any point leaves one whole header
  naming a whole kernel:
  1. A stale or damaged header copy is rewritten from the valid one.
  2. If the running slot (from `flipboot.slot=` in /proc/cmdline) is not the active one, it is
     made active first. So the running kernel is never overwritten, even after an Esc.
  3. The kernel goes into the other slot and is read back.
  4. The new header: copy 0, then copy 1.
- A kernel already in the active slot changes nothing.
- At 25 MHz the hook takes 28.7 s to install a kernel, and 19.7 s when it finds it already
  active (two cksums of 6 MB).

#### 12.16.6 Kernel changes for Debian

- `guest/linux/config.d/debian.config`: SYSVIPC, POSIX_MQUEUE, INOTIFY_USER and SWAP turned on,
  and the other facilities udev, Xorg and sysvinit need restated so the build stops if one
  drops out. Off on purpose: IPv6, CGROUPS (udevd only logs that it has none), POSIX ACLs.
- `guest/linux/build.sh` builds with `KCFLAGS=-fno-tree-slsr`. GCC 14.2 for m68k at -O2
  miscompiles `pivots[end + 1] = mas->last` in lib/maple_tree.c's mas_wr_append into a store to
  `pivots[end]`. The maple tree gets corrupted and udev oopses on every Debian boot. The kernel's
  own maple tree self-test fails the same way in qemu-system-m68k (upstream virt kernel), and
  passes with the flag. Whether other files or Debian's userland hit this is unknown.
- Size: text 4,014,622, data 1,144,792, bss 198,336; `_end` 0x51e000, 5.11 MiB loaded.
- The runner's `--kernel` path still passes no BI_RNG_SEED; only stage 2 does.

#### 12.16.7 The Debian image (`guest/debian`)

- `guest/debian/build.sh` (through `guest/docker/build.sh --privileged`): `--size` 512M to 1G
  (default 1G), `--kernel-deb`, `--cmdline`, `--chunks`, `--rebootstrap`, `--out`.
  - mmdebstrap minbase from snapshot 20261025T063552Z, recommends off, man pages, docs and
    locales excluded by dpkg path-exclude. Cached in `/build/debian/base-<hash>.tar` (the hash
    covers the snapshot, the package list and the kernel package), 3 to 4 minutes when not
    cached.
  - Then `guest/debian/rootfs/` is copied over, the image is written with `mkdisk.py` and
    `mkfs.ext4 -d` (no loop mounts), and `mkfdi.py` makes the `.fdi` and, with `--chunks`, the
    deflated chunk files of 12.4.3.
  - Not byte-for-byte reproducible between builds yet (cause not looked into).
- Packages beyond the required set: sysvinit-core, systemd-standalone-sysusers, udev, ifupdown,
  udhcpc, netbase, iputils-ping, wget, xserver-xorg-core, -video-fbdev, -input-evdev, xinit,
  xauth, jwm, xterm, xfonts-base, x11-xserver-utils, x11-utils, e2fsprogs, sudo, nano, less,
  procps, debian-ports-archive-keyring, flippies-boot, the kernel. 252 packages.
- Deviations from 12.8:
  - busybox udhcpc instead of dhcpcd-base: dhcpcd 10.5.2 crashes on m68k at this snapshot (also
    in qemu-system-m68k).
  - Root fsck off (fstab pass 0): e2fsck refuses the mounted root as busy; the cause (the kernel
    naming it /dev/root) is inferred.
- Configuration:
  - Hostname `flippies`. fstab: /dev/fhda3 root (noatime), /dev/fhda2 swap, /tmp on a 16 MiB
    tmpfs. eth0 by DHCP; ping works unprivileged.
  - `/etc/apt/sources.list` is `deb [check-valid-until=no] http://deb.flip/ unstable main`, but
    the catalogue has no deb.flip archive yet, so apt cannot install anything.
  - User `flip`: empty password, passwordless sudo, root locked. Auto-login on tty1 runs
    startx (not exec'd: when X ends, tty1 stays at a shell saying how to restart it). Logins on
    tty2 and COM1 (agetty ttyS0, the "login:" of 12.11).
  - X: fbdev on /dev/fb0, 640x480 at 8 bits (PseudoColor), shadow framebuffer, no blanking or
    DPMS, COMPOSITE off, no GLX. Input through evdev and udev.
  - `~/.jwmrc`: a 24 px bottom taskbar with Start, the task list and an `%H:%M` clock (a click
    shows `busybox cal`). Start menu: Terminal, Root terminal, Display info, Show key and mouse
    events, Close a window by clicking it, Restart the desktop, Exit to the text console,
    Reboot, Power off. Flat grey, navy and teal theme, focus by click. Alt+F1 menu, Alt+Tab,
    Alt+F4, Alt+F2 terminal. An xterm opens at start.
- Default command line: `root=/dev/fhda3 rw console=tty0 console=ttyS0,115200 quiet`. `quiet`
  saves only 3 to 4 s: Debian's boot is userland time, not console output.
- Sizes: root 280 MiB of used blocks of 863 MiB (target under 450). `.fdi` 270,238,000 bytes
  (4,123 of 16,384 chunks). Deflated chunks for the jar: about 94.3 MB.

#### 12.16.8 Measured (developer's Mac, 25 MHz 68040, 128 MiB, headless runner)

- Power-on to "login:" on COM1: about 99 s emulated, 26 s host.
- Power-on to the complete desktop: about 210 s emulated, 44 s host. X's RENDER start-up in
  8-bit PseudoColor takes about 66 s of that and udev coldplug plus the rcS scripts about 50 s;
  neither has been tuned. Under the game's 2 ms budget per tick this is many minutes.

#### 12.16.9 Tests (12.11 complete)

- `FlipbootE2eTest` (computer/tools): in the default suite, skipped until `guest/boot/build.sh`
  has run. ROM banner, prompt, Esc fallback, CRC errors, bootinfo, slot fallback, two power
  losses during upgrades, the running slot never overwritten, command line limits. Two of its
  tests are tagged `linux` (the real kernel from the disk).
- `LinuxDesktopTest` (tagged `linux`) boots `guest/debian/out/debian.img` (or
  `FLIPPIES_DEBIAN_IMAGE`) read-only, skipped while the image is missing, with no network
  access. One session: "login:" within 180 s; a COM1 login, ping and wget from the modem; the
  postinst hook into slot B; the desktop within 330 s from power-on, checked from Java (taskbar
  row, xterm); a mouse move changes the screen; a click on Start opens the menu; `reboot -f`
  back through flipboot from slot B; X exit leaves tty1 at a shell; `poweroff` exits 0. The whole
  session: 512 s emulated in about 100 s host.
- Gradle does not list guest outputs as test inputs: after rebuilding stage 2 use `cleanTest`
  (linuxTest always reruns).
- Results: computer suite 1028 tests, 0 failures; `./gradlew linuxTest` 5 tests, 0 failures.

#### 12.16.10 Open

- In-game mouse block: built, see 11.2.15.
- deb.flip has no archive layout, so apt in the image installs nothing.
- Boot time to the desktop (X RENDER start-up, udev coldplug) is untuned.
- The runner's `--kernel` passes no RNG seed; the system clock still drifts from the RTC.
- Security defaults: empty password and passwordless sudo for `flip`.
- Build volume: `/build/debian/linux-tree`, `/build/linux-net` and `/build/linux-input-fb` are
  stale, and the `flippies-qemu-scratch` Docker image can go.
- A GCC bug report for the SLSR miscompile has not been filed.

### 12.17 As built (step 5: the modem, the bundled catalogue and the netinstall floppy)

Everything below was tested headless. Nothing has run inside Minecraft yet (runClient or runServer).

#### 12.17.1 The modem block (12.4.5)


Block and block entity (`content/modem`):
- `network_modem`: `NetworkModemBlock extends Block implements IBE` and `NetworkModemBlockEntity extends SmartBlockEntity implements DataPortHost`.
- It is registered in ModBlocks, ModBlockEntities and ModItems as a plain BlockItem, and appears in the creative tab through the item list. Recipe later.
- Horizontal `facing`. It is placed with its front (the lights' end, model -x) towards the player, and the blockstate turns it as the case does (west 0, north 90, east 180, south 270).
- It takes no clicks of its own: the data cord item handles the port, and the wrench does nothing. Breaking it drops the item (loot table) and its cord (`DataCords.breakAll`).

Placeholder model (open question 4), made to be replaced:
- `models/block/network_modem/shell.json` holds the cube `body` (3,0,4 to 13,3,12, light grey concrete) and the cube `ethernet` (13,1,7.25 to 13.25,2,8.75, the case's port texture).
- The lights are partials: `power_led.json` (4,3,5 to 4.75,3.25,5.75, green) and `link_led.json` (4,3,6.25 to 4.75,3.25,7, amber). The item model is the shell.
- `ModemGeometry` holds the same boxes in pixels, in the case's convention (`PcCaseGeometry.toBlock`). A real model only needs those four boxes updated.

Port:
- One port, index 0, ETHERNET, PEER. Its click box is the port cube grown 0.375 px outward (+x).
- Origin and normal are worked out the case's way. The link is saved as `Link`, and `DataCords.validateLinks` runs every 20 ticks.

PoE and the exchange:
- `PcLinkExchange.modem(modem, nic)` is `modem.exchange(nic)` then `modem.tick()`, or `ethernetDown(modem)` when `nic` is null.
- `PcServer.exchange` checks a running PC's ethernet port with `PcCaseBlockEntity.peerModem()`, a loaded modem whose link pairs, and drives it with that PC's card.
- After all PCs, every loaded modem that no PC drove this tick gets `exchange(null)`: no power, link down, queue and TCP connections dropped. Loaded modems are tracked by `PcServer.modemLoaded` and `modemUnloaded`.
- The link goes up and down with the PC as a PC-to-PC link does.
- Draw: `PcPower.MODEM_WATTS = 2.5` on the rails while the PC runs and a loaded modem is linked (3.125 W at the input at 0.8 efficiency). See `railWatts(Draw, boolean modemLinked)`.

Modem instance:
- One `Modem` per block entity, server only. It is made the first time a running PC drives it and dropped on unload or removal.

Lights:
- Power: driven by a running PC this tick. Link: the same, since the link is up exactly while powered.
- Activity: frames moved (framesIn plus framesOut changed) within the last 6 ticks.
- All three are synced with `sendData` only when one changes.
- `NetworkModemRenderer` draws the partials the case's way: lit is full bright and full colour, dark is world light at 89/255. While activity is set, the link light blinks off every other 2-tick period, computed on the client.

Catalogue (deviation from 12.4.3: class path, not Forge's resource manager, so datapacks cannot add or replace content; the user's "hardcoded in the jar" decision):
- `ClasspathContent` (computer/net, pure Java) serves `data/create_flippies/internet/` from a class loader, lazily: one stream per request, one 64 KiB chunk inflated at a time by `CatalogueFile`. All modems share one `Catalogue(ClasspathContent.bundled())`.
- Hosts come from `internet/hosts.txt`, because a class loader cannot list a folder in a jar. It has one host per line, with `#` and blank lines skipped. Today it lists only `get.flip`.
- No escaping rule is needed: the class path takes any name.

Bundling (build.gradle; build outputs only, never src/main/resources):
- `processResources` copies `guest/debian/out/chunks/` into `data/create_flippies/internet/get.flip/`, giving `get.flip/debian.fdi.z/size.txt, 0.z ...`. It also copies `guest/netinstall/out/netinstall.img` into `data/create_flippies/floppies/`. Missing folders copy nothing.
- The `internetIndex` task writes `build/generated/internet/data/create_flippies/internet/get.flip/index.txt`, with one `image /<name>.fdi <size> <title>` line per `*.fdi.z` folder. The size comes from size.txt; the title comes from the FDI header inflated from chunk 0 (bytes 20 to 83).
- Measured: the jar is 99,156,751 bytes with the Debian chunks (270,238,000-byte .fdi, 4,124 chunk entries, about 98 MB) and the floppy.
- After rebuilding either guest output, run `./gradlew jar` again.

Headless runner:
- `--catalogue JAR` serves a mod jar's bundled internet through `ClasspathContent` on a parentless `URLClassLoader`, the same lookups the block makes. `--catalogue DIR` is unchanged.

#### 12.17.2 Modem server side, failures and catalogue sizes (after review)

Server side:
- The block entity's server logic is `ModemDriver` (content/modem, plain Java, unit tested). `NetworkModemBlockEntity` owns one driver and syncs the lights when `ModemDriver.exchange(pc, gameTime)` reports that one changed.
- The block entity registers with `PcServer.modemLoaded` and assigns its MAC in `onLoad` (server side), as `PcCaseBlockEntity` does, not in Create's lazy `initialize()`. So a modem in a loaded chunk that does not tick still gets the "no PC drove it" pass and has a MAC before any PC drives it.
- MAC: locally administered (`PcMotherboardItem.randomMac`), assigned at the first server load after placement and saved as `Mac`.
- Unload (`onChunkUnloaded`) or removal: `modemUnloaded`, then `ModemDriver.close()`, which takes the Modem's link down before dropping it. That runs `Tcp.dropAll` and closes the HTTP sessions and their open catalogue files now, rather than when the Modem is garbage collected.

Failures:
- A throw (RuntimeException or StackOverflowError) from the modem's own stack stops that modem only:
  - It is closed (link down), the PC card's link is taken down, and the lights go dark.
  - No new Modem is made for `RETRY_TICKS = 100` ticks (5 s). Until then the modem is treated as unpowered and the card's link stays down.
  - It is logged ("Network modem at <pos> failed; restarting it in 5 s", with the stack trace) at most once per `QUIET_TICKS = 6000` ticks (5 min) per block entity. A later failure is logged again once that time has passed.
- The PC's card is passed to the Modem wrapped. Anything the card throws is rethrown unchanged, out of the modem's failure handling. `PcServer` then crashes that PC, as it does on a PC-to-PC link.

Catalogue sizes and folders (replaces "Sizes come from the URL connection's length, else by reading through"):
- When a resource URL names a file system path through an installed provider (`Path.of(url.toURI())`: "file:" in a dev run, Forge's "union:" for a mod jar):
  - Only a regular file is served, so a folder such as `/debian.fdi.z` is a 404 without any read.
  - The size is `Files.size`, and the file streams through `Files.newInputStream`.
  - This matters because Forge's own union URL connection gives no length and reads the whole resource into memory for every stream.
- Otherwise (a plain "jar:" URL): a path ending in '/' or a jar directory entry is absent, the size is the connection's length, and only failing that is the file read through.
- Tested against securejarhandler 2.1.10's union filesystem and its `UnionURLStreamHandler` stream function. The `test` task passes `--add-opens java.base/java.lang.invoke=ALL-UNNAMED` for it, as Forge's launcher does.

Bundling: `linuxTest` depends on `jar`, so `./gradlew linuxTest` alone installs from a jar built from the current guest outputs. processResources takes those outputs as inputs.

Tests: ClasspathContentTest 5, ModemLinkTest 4, ModemDriverTest 4 and FloppyPresetsTest 4 in the default suite. NetinstallDebianTest (linux).


#### 12.17.3 The netinstall floppy (12.5)


Program (`guest/netinstall/`):
- Files: `netinstall.c` (freestanding C17, about 2,200 lines), `start.S` (boot sector, C start, interrupt handler, CRC32 word loop, MOVEM copy), `link.ld` (linked at 0x1000, boot sector padded to 510 bytes plus 0x55 0xAA, at most 96 KiB), `mkfloppy.py` (pads to 1,474,560 bytes, checks the signature and size) and `build.sh` (runs in the Docker toolchain).
- Flags: `-std=c17 -m68040 -O2 -ffreestanding -fno-builtin -nostdlib -fno-pic -fno-pie -fno-stack-protector -fno-asynchronous-unwind-tables -fno-tree-loop-distribute-patterns -fno-delete-null-pointer-checks -Wall -Wextra -Werror`, plus libgcc.
- Output: `out/netinstall.img`. The program is 29,329 bytes in 58 sectors, loaded in about 0.7 s. bss is 1.8 MiB.
- 68040 only: on a 68000 it takes an illegal-instruction trap, and an RV32I PC cannot run its boot sector.

Boot and memory:
- The ROM loads sector 0 to 0x1000 with D2 = 1. Sector 0 reads sectors 1..N to 0x1200, 128 per command, polled.
- It needs about 1.9 MiB of RAM. Below 2 MiB it prints "netinstall: needs 2 MiB of RAM" and stops.
- bss holds the 1 MiB receive ring, 128 KiB of read-back buffers, 513 KiB of CRC tables, a 128 KiB chunk table (16,384 entries, 1 GiB) and the 16 KiB index text.
- The interrupt handler only raises the interrupted SR's mask. All work runs at task level between `stop #0x2000`. Lines in use: COM1, both PS/2 ports, the HDD, the 20 ms interval timer and the NIC.

Devices:
- NIC: RX_ENABLE, IRQ_RX, IRQ_LINK and TX_CSUM. IPv4 frames need RX_INFO IP_OK, and TCP and UDP also need L4_OK. Frames are padded to 60 bytes.
- HDD: interrupt driven, by DMA, with IDENTIFY for the size and model and FLUSH at the end.
- Keyboard: set 2 on either PS/2 port.
- Display: VGA text 80x25 on display 0.
- COM1: mirrors every screen (25 trimmed lines, then "--"), "progress: ..." at most once a second, "problem: ...", "refetch: ..." and "netinstall: done".

Network:
- ARP with a 4-entry cache, cleared on link change.
- DHCP (DISCOVER, OFFER, REQUEST, ACK; broadcast flag; restart every 2 s or on NAK).
- DNS: an A query for get.flip every 500 ms, giving up after 5 s.
- TCP: one connection to port 80, MSS 1460, window scale shift 4, in-order receive. The window is the free room in the ring, up to 1 MiB. Inside long bursts it ACKs every 32 frames and services the HDD every 8.
- HTTP/1.1: GET with Range and keep-alive, at most one request in flight. It accepts 200 and 206.

Screens:
1. "Network: no link. Connect the modem to this PC's ethernet port.", then DHCP, then "Network: address 10.88.0.2, gateway 10.88.0.1, DNS 10.88.0.1".
2. The catalogue from http://get.flip/index.txt: up to 64 entries, 16 per page. A `file` entry is refused.
3. The header and chunk table are fetched and checked (magic, CRCs, rising indexes, at most 1 GiB). An image larger than the HDD is refused with both sizes. Then "This erases the HDD. Type YES to continue.": YES installs, WIPE first zeroes the absent chunks, and Esc goes back.
4. Progress: MB written of the MB present, speed, time left and a bar.
5. Verify: every present chunk is read back and its CRC32 checked. Then "Done. Remove the floppy and press Enter to restart." Enter writes SysCon POWER 0x7777.

Download:
- A 1 MiB ring of 16 chunk slots replaces "two 128 KiB buffers" (deviation). Entry k goes to slot k mod 16, and the HDD writes straight from the slots. One GET with Range runs from the first entry not yet whole and checked.
- Failures: a CRC mismatch, a RST, an early FIN, a link change, a 5 s stall or an HTTP error each count as one failure. Each resumes with Range. Three in a row stop the install.
- A chunk that fails read-back is fetched again alone, up to three times.
- With YES, absent chunks are not written: a blank HDD reads zeros. On a used HDD the free space keeps its old data, which is untested for booting.

In game (owner: game):
- `FloppyDiskItem` reads NBT `Preset`. The creative tab lists a write-protected `floppy_disk` with `Preset: "netinstall"`, named "Netinstall Floppy".
- On install into a case, a preset disk without a `DiskId` gets a new id. `FloppyPresets` copies `data/create_flippies/floppies/<preset>.img` from the class path (names [a-z0-9_]+, at most 1,474,560 bytes) from sector 0, with the last sector zero-padded. After that it is an ordinary floppy.
- A preset that is not bundled leaves a blank disk and logs a warning.

Measured (developer's Mac, 25 MHz 68040, 128 MiB, the modem exchanged every 50 ms tick, headless runner, the mod jar as the catalogue and the jar's floppy):
- Power-on to "netinstall: done": 116.4 s emulated, 19.9 s host.
- Download and write: 59.0 s (4.4 MB/s). Verify: 54.9 s (4.7 MB/s). 0 CRC errors, 0 refetches.
- The 1 GiB HDD then equals guest/debian/out/debian.img byte for byte.
- Restart: kernel entry 1.5 s after the reset, "login:" 99 s after it, and the JWM desktop 206 s after it.
- The whole session, to poweroff: 343 s emulated in 67 s host.
- The HDD (about 4.9 MB/s for 64 KiB writes) is the limit, not the network.

Tests:
- `NetinstallE2eTest` (default suite, skipped until guest/netinstall/build.sh has run): 3 tests. They cover the catalogue, the plain-file and too-large refusals, YES and WIPE, a corrupt chunk in transit, a link dropped halfway, a chunk damaged on the HDD during verify, and the restart.
- `NetinstallDebianTest` (tagged linux; `./gradlew jar linuxTest`; skipped while the jar lacks the Debian chunks or the floppy; output in build/netinstall-debian): 2 tests. One checks that the jar's catalogue serves debian.fdi byte for byte. The other runs the full install from the jar, compares the HDD with debian.img, removes the floppy, restarts, reaches login and the desktop, runs wget of get.flip/index.txt inside Debian, and powers off. The HDD is a read-write file synced every 10 s, so the heap stays small.
- Gradle does not track guest outputs or the jar as test inputs: after rebuilding the floppy use `cleanTest`, and rebuild the jar before linuxTest.


#### 12.17.4 Netinstall verify and preset name (after review)
- Program: `netinstall.c` is 29,497 bytes of program in 58 sectors (bss unchanged at 1.8 MiB).
- Screen 5, verify:
  - Every present chunk is read back and its CRC32 checked.
  - The first 256 that fail are listed. Each is fetched again with a Range request and checked again, up to 3 tries, after which the install stops with "A chunk keeps failing its check after it is written."
  - When more than 256 fail, the check goes on after the last chunk listed ("Verifying again after the chunks fetched again") and repeats until a pass lists them all. No damaged chunk is left behind a "Done".
  - The done screen's "fetched again N" counts every refetch.
  - The verify time on the done screen is the first full pass.


- A preset floppy is named after its preset ("Netinstall Floppy") until it is the player's own disk: once it has a `DiskId` (its image was copied on first insert) and is writable, it loses `Preset` and is named as a plain floppy. Its id and data stay.
- This is applied when the write-protect tab is switched off (sneak-use), and at the first insert's copy when the stack is already writable.
- A write-protected copied disk keeps the name. A never-inserted stack keeps `Preset` either way, so its first insert still copies the image.


#### 12.17.5 Netinstall internals: Files and build

- `guest/netinstall/`:
  - `netinstall.c`: the program, freestanding C17, about 2,200 lines.
  - `start.S`: the boot sector, the C start, the interrupt handler, the CRC32 word loop and a MOVEM copy.
  - `link.ld`: linked at 0x1000, the boot sector padded to 510 bytes and followed by 0x55 0xAA, an assertion that the image is at most 96 KiB.
  - `mkfloppy.py`: pads the program to 1,474,560 bytes and checks the boot signature and the 96 KiB limit.
  - `build.sh`: runs itself in the toolchain image like guest/boot/build.sh.
- Flags: `-std=c17 -m68040 -O2 -ffreestanding -fno-builtin -nostdlib -fno-pic -fno-pie -fno-stack-protector -fno-asynchronous-unwind-tables -fno-tree-loop-distribute-patterns -fno-delete-null-pointer-checks -Wall -Wextra -Werror`, linked with libgcc for 64-bit division.
- Output: `guest/netinstall/out/netinstall.img`. The program is 29,329 bytes in 58 sectors, loaded from the floppy in about 0.7 s. bss is 1.8 MiB and is not on the floppy.
- build.gradle's processResources copies the image to `data/create_flippies/floppies/netinstall.img` when it exists (12.5 "In game").


#### 12.17.6 Netinstall internals: Boot and memory

- The ROM loads sector 0 to 0x1000 and enters it with D2 = 1 (11.1.5). The boot sector reads sectors 1..N to 0x1200, 128 sectors per command with the controller polled. It uses the floppy controller for D2 = 1 and the HDD controller for D2 = 0.
- It checks that RAM reaches past the bss plus 64 KiB of stack. Otherwise it prints "netinstall: needs 2 MiB of RAM" and stops; the real floor is about 1.9 MiB.
- Then it sets SP to the top of RAM, clears the bss and calls `netinstall_main`.
- bss contents:
  - the receive ring, 1 MiB;
  - read-back buffers, 128 KiB;
  - CRC tables, 513 KiB;
  - the .fdi chunk table, 128 KiB, enough for 16,384 entries, a 1 GiB image;
  - the index text, 16 KiB.
- Interrupts:
  - Vectors 24..31 point to a handler that only raises the interrupted SR's mask to 7 and returns.
  - The program sleeps with `stop #0x2000` and does all its work at task level after waking.
  - Lines enabled: COM1, both PS/2 ports, HDD, the interval timer (20 ms period) and the network card. Every line is cleared or drained before the next STOP.


#### 12.17.7 Netinstall internals: Devices

- Network card:
  - CTRL is RX_ENABLE, IRQ_RX, IRQ_LINK and TX_CSUM, so every checksum we send is left 0 for the card to fill.
  - Received IPv4 frames need RX_INFO IP_OK, and TCP and UDP also L4_OK; others are dropped.
  - Frames go out padded to 60 bytes.
- HDD: interrupt driven, one command at a time. IDENTIFY gives the capacity and model. Writes and reads go straight between the HDD and the ring or the read-back buffers by DMA. FLUSH runs at the end.
- Keyboard: scancode set 2 on either PS/2 port. Keys used: Up, Down, PgUp, PgDn, Home, End, Enter (both), Esc, Backspace, A..Z.
- VGA: text mode, 80x25, display 0. The cursor is shown only on the YES prompt.
- COM1:
  - A 16 KiB ring drained by the THR-empty interrupt, 16 bytes per FIFO refill.
  - Every full screen is mirrored as 25 lines with trailing spaces trimmed, then a line "--".
  - Progress lines ("progress: ...") are sent at most once a second.
  - Each failure is sent as "problem: ...", each read-back re-fetch as "refetch: ...", and the end as "netinstall: done".


#### 12.17.8 Netinstall internals: Network

- ARP: requests and replies, and answers for our address. A cache of 4 entries, cleared on every link change.
- DHCP:
  - DISCOVER, OFFER, REQUEST with options 50 and 54, then ACK, sent with the broadcast flag and padded to 300 bytes.
  - Options asked: 1, 3, 6 and 51. Every 2 s it starts again while not bound, and a NAK starts it again.
  - The address is kept across link drops.
- DNS: one A query for get.flip to the DHCP server's DNS address, asked again every 500 ms, given up after 5 s with a retry screen.
- TCP:
  - One connection to port 80. The SYN carries MSS 1460 and window scale shift 4. Receive is in order only; out-of-order segments get a duplicate ACK.
  - The window is the receiver's free room: 8 KiB while response headers arrive, then the ring's room, at most 1 MiB.
  - Retransmission: the SYN every 1 s, up to 5 times. An unacknowledged request every 1 s, up to 6 times.
  - Closing: a RST in window closes. A FIN is answered with FIN and ACK, then the connection is closed. A segment for any other connection gets a RST so the modem forgets it.
  - Inside a long receive burst it acknowledges every 32 frames and services the HDD every 8. Without this, the modem saw a stale window at the next exchange and throughput swung between ticks.
- HTTP/1.1:
  - GET with Host get.flip and User-Agent flippies-netinstall/1.0, keep-alive, at most one request outstanding.
  - Accepts 200 and 206, and reads Content-Length and the start of Content-Range. Any other status is read to its end and counts as a failure; 404 stops a catalogue or header fetch at once.
  - A catalogue or header fetch is tried up to 3 times, with a 5 s stall timeout.


#### 12.17.9 Netinstall internals: Screens

1. Network:
   - "Network: no link. Connect the modem to this PC's ethernet port." until LINK.
   - Then "Network: link up, asking for an address (DHCP)...".
   - Then "Network: address 10.88.0.2, gateway 10.88.0.1, DNS 10.88.0.1" and "Looking up get.flip...".
2. The catalogue from http://get.flip/index.txt:
   - Up to 64 entries, 16 per page, each with its title, size in MB and kind.
   - A `file` entry is refused with "This is a plain file, not a disk image."
3. Confirmation:
   - The image's header (Range bytes=0-87) and chunk table (Range 88 to the table's end) are fetched and checked: magic, header CRC, table CRC, chunk size, rising indexes, at most 1 GiB.
   - Then "Image: ..." and "HDD: model, capacity". An image larger than the HDD is refused with both sizes.
   - Then "This erases the HDD. Type YES to continue." YES installs; WIPE also zeroes the absent chunks first; Esc goes back.
4. Progress:
   - "Written: X of Y MB, R MB/s, about M:SS left", where Y counts only the data present in the .fdi.
   - A 60-column bar, the last problem being retried, and "Network: no link, waiting for the modem." while the link is down.
5. Verify, then the done screen:
   - "Verified: X of Y MB", then "Done. Remove the floppy and press Enter to restart."
   - It shows the image's title, the MB written of the image's size, the times and rates of the download and verify passes, the chunks present, and the CRC errors in transit and chunks fetched again.
   - Enter writes SysCon POWER 0x7777.
- A stop for any reason shows "Install failed." with the cause, and Enter restarts.


#### 12.17.10 Netinstall internals: The download

- The ring has 16 slots of 64 KiB. Table entry k goes into slot k mod 16, and its data begins at file offset 88 + 8 * count + k * 65,536.
- There is one GET with Range from the first entry not yet received whole and checked to the end of the range.
- When an entry is complete, its CRC32 is checked:
  - a mismatch aborts the connection with a RST and resumes from that entry;
  - a good entry is written from its slot, two adjacent entries in one command when they are adjacent on disk and in the ring.
- New data is taken only up to 16 entries past the oldest unwritten one, which is what the window advertises.
- Failures:
  - A RST, a FIN before the end, a link change, 5 s without data while the window is open, a CRC mismatch, an HTTP error, or an answer that ends early each count as one failure.
  - Any chunk passing its check resets the count. Three in a row stop.
  - While the link is down, no request starts.
- After the download every present chunk is read back, one 64 KiB command at a time, double-buffered, and its CRC32 checked again. A chunk that fails is fetched again alone with Range, written and checked again. If it fails three times, the install stops.
- Absent chunks:
  - YES does not write them: a new HDD reads zeros there (12.4.4).
  - WIPE writes zeros over them first, 128 KiB per command, before the download.
  - Nothing past the image's last sector is ever written.
  - With YES on an HDD that held other data, the image's free space keeps that old data. This is inferred to be harmless for the Debian image's ext4, swap and boot area, but it is not tested.


#### 12.17.11 Netinstall internals: CRC32

- zlib's CRC32, with the state kept byte-swapped so a big-endian long load needs no swap.
- Two tables of 65,536 entries (512 KiB) built at start: per long, two lookups (`ta[x >> 16] ^ tb[x & 0xFFFF]`). The loop in start.S is unrolled four times.
- Under the 68040 cost model it is about 2.75 cycles a byte, about 9 MB/s at 25 MHz. The fastest 64 KiB-table (slicing-by-4) loop was about 5.3 cycles a byte.


#### 12.17.12 Netinstall internals: Deviations from 12.5

- A 1 MiB ring of 16 chunk slots, written straight to the HDD, instead of "two 128 KiB buffers". The window must reach 256 KiB to 1 MiB, and the data has to be held until the HDD has written it.
- WIPE as a second answer to the confirmation, the "full wipe" of 12.4.4.
- The minimum RAM is about 1.9 MiB, not stated by the plan.
- The program is 68040-only. On a 68000 PC the C code takes an illegal-instruction trap, and an RV32I PC cannot run its boot sector.

#### 12.17.13 Open

- Not seen in Minecraft: the placeholder model and lights, the cord into the modem port, the floppy copy on first insert, the union: URLs Forge's module class loader hands out, a modem in a non-ticking chunk, and the install's real-time length under the 2 ms per-tick budget.
- The jar is 99 MB (open question 1). A dev runClient also copies about 98 MB into build/resources/main.
- deb.flip is not in hosts.txt, so apt inside the installed Debian fails at DNS.
- A modem bug stops only that modem and never crashes the PC, unlike PC-to-PC ethernet. Needs the user's confirmation.
- Two modems cabled together are allowed by PortCompatibility. Harmless, but could be refused.
- With YES on a used HDD, the image's free space keeps old data. Booting from such a disk is untested. WIPE zeroes it.
- The install is HDD-bound (4.4 MB/s against the HDD's 4.9 MB/s). Going faster needs a faster HDD model (open question on HDD speed).
- The floppy runs only on a 68040 PC. On a 68000 or RV32I PC it fails to boot.
- At most 64 catalogue entries; index.txt is read up to its first 16 KiB.
- A preset disk made writable and then write-protected again keeps the plain name.
- Not covered by tests (they need a Minecraft bootstrap): PcServer's undriven-modem pass, NetworkModemBlockEntity's onLoad and light sync, and FloppyDiskItem.fillFromPreset's ordering.

### 12.18 As built (step 6: content packs, the share folder, the apt archive and apps)

Built for both loaders (Forge 1.20.1 and NeoForge 1.21.1). This supersedes 12.17's bundling of the Debian image into the jar: the jar keeps only the netinstall floppy and tiny built-in pages.

#### 12.18.1 Content packs

The jar
- The jar holds no OS image or package archive.
- It keeps the modem code, the netinstall floppy (data/create_flippies/floppies/netinstall.img, 1.4 MB) and a few small built-in pages under data/create_flippies/internet/:
  - hosts.txt, which lists get.flip
  - get.flip/index.html
  - get.flip/index.txt, comments only (no entries)
- Both jars are about 4.9 MB.

Pack format
- A content pack is a zip file named *.flippack.
- pack.json sits at the root: {"format": 1, "id": "...", "version": "...", "title": "...", "hosts": [...]}.
  - id is [a-z0-9-]+.
  - Hosts are lower-case DNS names; localfilesys.com is refused.
  - Unknown keys are ignored.
- hosts/<host>/<path> is served at http://<host>/<path>.
- Already compressed data is STORED: chunk folders (<name>.fdi.z/ with size.txt and N.z), and .deb, .gz, .xz and .gpg files. Small text may be deflated.
- Packs are read with random access (java.util.zip.ZipFile): sizes come from the central directory, and files stream on request.
- A pack that cannot be used is skipped with one log line naming the reason: not a zip, missing or invalid pack.json, wrong format, bad id or host, or an id already loaded.

Folders and lookup
- Pack folders, both created at server start:
  - <world>/flippies_packs/, looked up first
  - config/create_flippies/packs/
- Inside a folder, packs are ordered by file name.
- The first pack that has a path (plain, or as a chunk folder) serves it. A later copy is logged once per pack pair.
- The jar's built-in pages serve only what no pack has.
- get.flip/index.txt is every pack's own index lines joined in lookup order, so several OS packs all show in the netinstall menu. When the packs list no entry, the jar's index is served.
- With an index that has no entries, the netinstall floppy says "No OS is available on this server. Ask the server owner to install an OS pack."

Commands (operator level 2)
- "/flippies packs" lists the packs in lookup order (id, version, title, hosts, world or server, file), every served host, and the folders.
- "/flippies packs reload" rescans the folders.
- On reload the old set is retired, not closed. A response already being served keeps reading its old pack until it ends, and that pack's zip closes when its last reader lets go. New requests see the new set.

Auto-download, server config [packs]
- Settings:
  - downloads: a list of "<file name> <sha256> <url>" strings, empty by default
  - allowHttp: default false
  - maxPackMiB: default 4096
- On server start, the listed packs are fetched one after another on a daemon thread, never the server thread, into config/create_flippies/packs/.
- Each download goes to a .part file, with a 30 s connect timeout, a 60 s read timeout and the size cap.
- The SHA-256 and pack.json are checked, then the file is moved into place atomically, and a reload is queued on the server thread.
- A file of the target name already there is never replaced: with the right hash it is not fetched; with another hash it is kept and logged.
- Failures are logged and the server keeps running. Downloads stop when the server stops.
- The config is read before the packs load on both Forge 1.20.1 and NeoForge 1.21.1 (seen in the dev server logs).

File share
- <world>/localfilesys/ is created at server start and served read-only and live at http://localfilesys.com/. The share comes before the packs.
- Folders get an HTML 3.2 index:
  - a parent link, then folders, then files, by name
  - file sizes
  - percent-encoded UTF-8 links with escaped names
  - at most 2000 entries
- Each path must stay under the share's real path, both after normalizing and after resolving symlinks.
- '..', '.', empty segments, '\' and control characters are refused.
- Only regular files are served, in 64 KiB blocks.

Headless runner
- --pack FILE (repeatable, first wins) layers packs over --catalogue or over the jar's built-in pages.
- --share DIR serves DIR as localfilesys.com.

The two packs
- guest/packs/build.sh writes them to guest/packs/out/ with guest/packs/mkpack.py. Arguments: os, apps or --test.
- The zips are deterministic: pack.json first, then the files sorted by name, all with the snapshot's time and mode 0644, and no directory entries.
- Each pack gets a .sha256 next to it.
- debian-os.flippack:
  - serves get.flip: index.txt with "image /debian.fdi 319920352 Debian GNU/Linux m68k desktop (20261025T063552Z)", plus the debian.fdi.z chunks from guest/debian/build.sh --chunks
  - 110,588,602 bytes, sha256 b53b03aebc4556ab560f9247b8a00824bf8ffdfe1494dfa5546d5cadbf685720
- debian-apps.flippack:
  - serves deb.flip from guest/apt/out/archive
  - 226,554,729 bytes, sha256 ffe6a9d977b4880e720c4c9337b3926b8ee740314c1c51d15b5f9d5253758714

#### 12.18.2 The apt archive and the desktop apps

Building the archive
- guest/apt/build.sh builds the deb.flip archive. It runs in the toolchain container, and starts it itself when run elsewhere.
- Packages come from the debian-ports snapshot 20261025T063552Z (unstable and unreleased).
- The package set is resolved by apt, with Recommends off, against guest/apt/oldest-image.status (the first image served), so it completes every later disk too. --image FILE resolves against a built disk instead.
- The build fails if the plan would remove or upgrade an installed package, or install systemd, openrc or systemd-sysv.
- Every .deb is checked against the snapshot's signed indexes.
- Layout: dists/unstable/main/binary-m68k with Packages, Packages.gz and Packages.xz, plus pool/, pool-m68k/ and pool/local/.
- Release is signed as InRelease and Release.gpg with our Ed25519 key, dated at the snapshot. The output is identical byte for byte across builds.
- The private key stays in guest/apt/out/signing/, which is ignored by git and never goes into a pack.
- The public key is served at http://deb.flip/flippies-archive.gpg. The image has it as /etc/apt/trusted.gpg.d/flippies-archive.gpg.

What the archive offers (188 packages, 217 MB)
- vim, nedit
- python3, python3-tk, idle, tk8.6, perl, lua5.4, bc
- gcc, make, libc6-dev
- openjdk-17-jdk-headless (Zero VM)
- x11-apps (xcalc, xclock, xeyes, xload, xmag, xedit)
- the file manager worker
- feh, dillo, xpdf
- GStreamer tools and base plugins, with flippies-video
- htop, mc, zip, unzip, curl, file, tmux, tree, sqlite3, bzip2, git
- games: bsdgames, xbill, xjump, xgalaga, xmahjongg, ace-of-penguins, xboard, gnuchess
- our own packages: flippies-desktop 1.1, flippies-apps 1.2, flippies-sharedfiles 1.2, flippies-sysinfo 1.1, flippies-video 1.0

Left out, and why
- GTK 3 programs (pcmanfm, gpicview, galculator, mousepad): they pull in dbus, dconf, at-spi and systemd, and start too slowly.
- xfe: FOX 1.6 on big-endian m68k draws no text on its buttons, menus and column headers, and pink frames, at 256 colours. Fixing its colour settings did not help. worker (its own Xlib toolkit, about 4.5 MB installed, adds avfs and fuse3) is the file manager instead.
- mpv, mplayer, ffplay, vlc: not installable on m68k at this snapshot.
- gst-play: cannot draw on the 8-bit PseudoColor display, so flippies-video shows GStreamer's frames through Tk.

Measured on the 68040 at 25 MHz with 128 MiB (emulated / host)
- apt update: 27 s / 5.5 s
- Installing vim and python3: 559 s / 126 s
- Installing worker, feh, x11-apps, flippies-video, dillo and nedit: 2661 s / 581 s
- openjdk-17-jdk-headless: about 62 min to install. Then java -version takes 19 s, a hello world 43 s, and javac 10 min for one small file.

The desktop apps
- guest/apps/build.sh runs the host tests, then builds four Architecture: all packages reproducibly.
- flippies-desktop:
  - The Start menu's Dynamic "Apps" submenu, built from the .desktop files and grouped by category, with "Install more apps..." at the end.
  - A dpkg trigger on /usr/share/applications, /usr/bin and /usr/games refreshes it.
  - On an older disk, its postinst adds the submenu to each user's ~/.jwmrc and restarts JWM.
- flippies-apps:
  - A Tk package center with Apps, Installed and All views, search, and one-click Install and Remove through sudo -n apt-get, with a progress bar.
  - It refuses a removal that would take away a protected package.
  - Its window is 624x420.
  - The featured list now names worker under Files.
- flippies-sharedfiles:
  - Browses http://localfilesys.com/ and downloads into ~/Downloads.
  - It opens that folder with xdg-open, pcmanfm or worker, the first one installed.
- flippies-sysinfo: shows CPU, memory, disk, network and kernel.
- The first time worker starts, it offers to copy in its example configuration (one click).

The image
- guest/debian/build.sh adds the archive key, python3 and python3-tk, the four guest/apps packages and /usr/share/man/man1 (which openjdk's postinst needs).
- Its sources.list stays "deb [check-valid-until=no] http://deb.flip/ unstable main".

A disk installed before this step
- sudo wget -qO /etc/apt/trusted.gpg.d/flippies-archive.gpg http://deb.flip/flippies-archive.gpg && sudo apt update && sudo apt install -y flippies-desktop flippies-apps
- Java on such a disk also needs: sudo mkdir -p /usr/share/man/man1
- A disk that already has the 1.1 apps gets 1.2 with: sudo apt update && sudo apt upgrade -y

#### 12.18.3 Changes after review

Changes after review to 12.18.1 and 12.18.2.

In 12.18.1, under "Auto-download, server config [packs]", replace the bullet "Each download goes to a .part file, with a 30 s connect timeout, a 60 s read timeout and the size cap." with:
- Each download goes to <file name>.part in the server pack folder, with a 30 s connect timeout, a 60 s read timeout and the size cap. A retry writes over its own .part file, so one pack never has more than one.
- When the server starts, before any download, every *.flippack*.part file in config/create_flippies/packs/ is deleted and logged with its size. A server stopped or killed mid-download can leave one, because the download thread is a daemon thread and may end before it cleans up. This happens even with an empty download list.

Replace "Downloads stop when the server stops." with:
- Downloads stop at their next read when the server stops.

In 12.18.1, under "The two packs", replace the two size and hash lines:
- debian-os.flippack: 110,588,747 bytes, sha256 aab3f84706a8fe0dbd381f3d2f17f0400da108e3e7e53280f9a575a7e5a72fe5
- debian-apps.flippack: 226,554,779 bytes, sha256 dd3c9156680075ab1b98b89be86b697af451523b718b2834902eedce7905217e

In 12.18.2:
- In "our own packages", flippies-apps 1.2 becomes flippies-apps 1.3.
- In the flippies-apps item, replace "It refuses a removal that would take away a protected package." with:
  - Before a removal it simulates the exact command it will run (apt-get -s --autoremove remove).
  - The confirmation dialog lists every package that goes, including auto-installed packages that nothing needs any more anywhere on the system, not only the app's own.
  - It refuses the removal if any of them is protected.

In "A disk installed before this step", replace the last bullet with:
- A disk that already has the 1.1 or 1.2 apps gets flippies-apps 1.3 with: sudo apt update && sudo apt upgrade -y

### 12.19 As built: PC snapshots and freezing

User decisions (2026-10-28):
- A snapshot captures one PC case: its parts, the machine state (RAM, CPU, every device) and its
  hard disk and floppy. Monitors, keyboards, modems and cords are not captured; they are plugged
  in again after placing.
- Snapshots are kept on the player's own computer by default (single player and multiplayer),
  or with an option on the server.
- A snapshot comes back as a PC case item from a command, placed by hand, and the placed PC is
  frozen until a command unfreezes it.

#### 12.19.1 Freezing

- `PcCaseBlockEntity` gains `Frozen` (saved NBT and synced). A frozen PC keeps its machine in
  memory but is out of the scheduler: emulated time stands still, it draws no power, a power cut
  does not turn it off, keys and mouse input are dropped, and it moves no data on its ports (a
  running peer's frames wait in its queues; a modem it drives loses power). Its screens keep
  their last picture. The goggles and case screen say "Frozen".
- The power and reset buttons and the floppy slot refuse with a message while frozen; other part
  changes are already refused while a machine exists.
- Freezing needs a machine (an off PC is refused). Unfreezing needs the supply in its start
  window and room under the running cap. Frozen machines count towards the total RAM cap, not
  the running cap.
- A frozen PC saves and unloads like a running one. On load it restores its state and stays
  frozen; a state that cannot be loaded leaves it off (never a cold boot behind a freeze).

#### 12.19.2 The snapshot file (`PcSnapshot`, `.flipsnap`)

A zip of:
- `snapshot.json`: format 1, name, creation time, Minecraft version, loader, whether it holds a
  state, CPU, usable RAM, and the HDD's and floppy's disk ids at the time.
- `case.nbt`: the parts list as the case saves it (item NBT differs between game versions, so a
  snapshot is placed only on the Minecraft version it was taken on).
- `state.bin`: `Machine.saveState()`, only for a PC that was on.
- `disks/hdd/<chunk>.bin`, `disks/floppy/<chunk>.bin`: the disk store's chunk files, verbatim.

The disk chunks and state are already compressed and are STORED. A Debian desktop PC is a few
hundred MiB.

#### 12.19.3 Commands (`PcCommands`, operators only)

- `/flippies pc freeze [<pos>|all]` and `/flippies pc unfreeze [<pos>|all]`. Without a
  position, the case the player looks at (16 blocks).
- `/flippies pc snapshot save <name> [server] [<pos>]`: a running PC is frozen while its disks
  are copied (seconds), then runs on unless it was frozen before. The state is taken on the
  server thread; the disks are copied after the PC writer has written everything queued
  (`PcFileWriter.barrier`), on a background thread.
- `/flippies pc snapshot give <name> [server]`: checks the file, copies it into the world's store
  `<world>/create_flippies/snapshots/<sha256>.flipsnap`, and gives a PC case item named
  "PC Case (snapshot <name>)" with tag `Snapshot` {`Sha`, `Name`, `Running`}.
- `/flippies pc snapshot list [server]`.
- Names: letters, digits, `_`, `-`, `.`, up to 64, not starting with a dot.

Where files go:
- "Local" is `<game dir>/create_flippies/snapshots/` on the player's computer. For the host of
  a single-player or LAN world the server writes and reads it directly. Any other player's copy
  travels in packets: `SnapshotChunkPacket` (512 KiB pieces down, at most 8 unacknowledged,
  `SnapshotAckPacket` back), and for give, `SnapshotRequestPacket` then `SnapshotUploadPacket`
  (30,000-byte pieces up, at most 256 unacknowledged, `SnapshotUploadAckPacket` every 16). The
  client sends only what the server asked for by transfer number; the server refuses pieces out
  of order or past `[snapshots] maxSnapshotMiB` (default 2048). A player leaving ends their
  transfers. Partial files are `.part` and are never used.
- "Server" is `config/create_flippies/snapshots/`.

#### 12.19.4 Placing

`PcCaseBlock.setPlacedBy` hands a snapshot item to `PcSnapshots.place`:
- The case NBT is read at once and the parts go in, the HDD and floppy with fresh disk ids, so
  every placed copy has disks of its own.
- On the background thread the disk chunks are copied under the new ids, and the state is moved
  to them (`Machine.withDiskId`: the id's 17 bytes in the HDD or floppy section replaced and the
  checksum made again) and written as the case's state file at generation 1.
- The case is frozen with its supply on and waits for the copy before it restores. A failed copy
  leaves it off and says why.
- The MAC address is the snapshot's own (1:1), so two copies of one snapshot on the same cable
  would clash.

#### 12.19.5 Tests and what is not verified

- `PcSnapshotTest` (the file round trip, names, chunk file names) and
  `BoardE2eTest.aStateMovesToCopiesOfItsDisks` (a running machine's state moved to copied disks
  loads and saves identically; the old state is refused there).
- Not run: the commands in a real game, a multiplayer transfer, placing a Debian snapshot.

## Review decisions

Two reviews (ISA facts, and architecture) were applied. Everything was accepted except what is
listed here.

- Changed in form, same intent:
  - 8086 ports (ISA review item 3, architecture item 7): PS/2 got 0x60..0x7F and the RTC moved to
    0x80..0x8F, rather than moving PS/2 to 0x80..0x9F.
  - 8086 VGA ports (ISA item 4, architecture item 8): the window is 0x3B0..0x3EF, not
    0x3C0..0x3EF, because PAL_DATA at control offset 0x30 needs offsets up to 0x33.
  - Overlapping regions (architecture item 9): instead of a general `SystemBus.unmap`, the one
    overlap that exists (the 68000 and ARM2 reset-time ROM mirror) is `setRomOverlay(boolean)`, and
    banking is `MemoryRegion.setOffset`. A general unmap would let the fast-path arrays and the
    region list disagree; nothing needs it.
  - Stop horizon (architecture item 1): `Cpu.run` keeps its `untilCycle` parameter and writes it
    into the clock's horizon at entry, so the clock is the single source of the limit.
  - `nextEventCycle` (architecture item 5): the IRQ-line-only definition, plus changes to memory
    the CPU reads directly (HDD DMA), because a guest polling a RAM buffer would otherwise see the
    data up to a tick late.
  - Interrupt controller outputs (architecture item 12): 8 outputs with 4-bit ROUTE fields packed
    in 4 registers, so the 8086's 32-port window still reaches every register it needs.
  - `Platform` (architecture item 14): it is owned by Agent C, not a frozen contract, because only
    the machine package uses it; the shared constants moved to the contract class `Rv32Map`
    (formerly `Rv32Platform`).
  - REP quirk (ISA item 24): the negation applies to IMUL and IDIV (the signed forms, whose
    microcode uses the flag the REP prefix sets), not to MUL as the review wrote.
  - `.init_array` (ISA item 18, architecture item 25): mapped now with its start and end symbols
    rather than only planned, because it costs little and `crt0` can use it from C0 on.
- Kept, with the review's note added:
  - Expression precedence (ISA item 17): C rules stay; the difference from GNU as is documented.
  - Blink default (ISA item 22): CTRL now resets to 0xD (blink on) to match real VGA, rather
    than documenting the old 0x5 as a deliberate change.
- Decided by the user after the reviews:
  - Save and restore of running machines (architecture item 22): "the pc should freeze in place
    when the chunk is unloaded and come back on after loading." Built as described in 1.5:
    `saveState`/`loadState` on `Cpu`, `BusDevice` and `Machine`, versioned per component,
    emulated time frozen while unloaded, all-or-nothing loads. The save tests of architecture
    item 30 are in 8.3 and 8.4. Every CPU added later (68000, 68040 and the others) implements
    the two `Cpu` methods with its own payload and version.
- Decided by the user for the 68000 and 68040 (section 10):
  - 68040 clock items 25 and 33 MHz; the 68000 stays in its real range.
  - `.word` is 2 bytes in the 68k assembler; RV32I keeps 4.
  - The strict FPU mode is selectable in game: `MachineConfig.strictFpu`, part of the saved-state
    config check, default off (the transcendentals and other support-package instructions are
    emulated).
  - The public 68000 test-vector suite has no licence: it is not downloaded or used. Tests are
    hand-checked only.
  - No MMU on the 68040; the integer unit and the FPU are emulated. Superseded by section 12:
    the 68040 has its MMU (12.2, 12.14.1).

## Implementation status

As of the first build, its two reviews (ISA, and bus, devices and machine), save and restore
(1.5), the 68000 and 68040 foundation step (10.9), the three parallel 68k owners (integer core,
FPU, assembler), the 68k integration step (10.8.5), the core additions of section 11 (11.1,
as built in 11.1.12) and step 1 of section 12 with its reviews (as built in 12.14). The game
side of section 11 (11.2) is the game owner's and is not covered here.

What exists now:

- Everything in "What is built now": the CPU-neutral bus, clock and machine loop, all devices,
  the RV32I CPU (RV32IM, Zicsr, Zicntr, Zifencei, machine mode only), the shared assembler with
  its RV32I and 68k backends, the RV32I boot ROM, and the 68000 and 68040 machines: integer core
  for both models, the 68040 FPU with the strict option, the 68k boot ROM, RESET instruction
  device reset, the ROM overlay through reset and power-on.
- The in-game board's core (11.1): COM3, the floppy controller with its drive and boot order
  floppy then HDD in both boot ROMs, three VGA displays sharing one VRAM array, PS/2 ports that
  take either device, the network card, disk ids and `PagedDiskImage`, 128 MiB of RAM on RV32I
  and the 68040 (13.5 MiB on the 68000), `busyFraction`, `guestErrorLed`, `haltReason`, the
  activity calls and saved-state version 4.
- Section 12, step 1 (12.14): the 68040 MMU (paging with 4 KiB and 8 KiB pages, TT registers,
  a saved software TLB, restartable access errors with format 7 frames, PTEST, PFLUSH, MOVES
  with SFC and DFC, CPU state version 2); the network card's 256-frame queues, RX_INFO and
  TX_CSUM; `FrameEndpoint`; the modem's pure-Java stack (ARP, ICMP echo, DHCP, DNS, TCP on port
  80 with window scaling, HTTP/1.1 with Range), the content catalogue with deflated chunks, and
  the `.fdi` reader and writer; the headless runner `HeadlessPc` with Gradle task
  `runHeadlessPc`; the guest toolchain Docker image under `guest/docker` and the checked kernel
  and debian-ports facts in `guest/linux/NOTES.md`. Not yet built: the netinstall floppy, the
  kernel fork, the boot chain, the Debian image and the game's modem block (steps 2 to 5 of
  12.10).
- No Minecraft, Forge or Create imports anywhere under `computer`, so the tests run on the plain
  JUnit platform.

Main files, under `src/main/java/com/nectariich/create_flippies/content/computer`:

- `asm`: AsmContext, AsmDiagnostic, AsmError, AsmImage, AsmLexer, AsmLine, AsmOptions,
  AsmResult, AsmSegment, AsmSource, AsmToken, Assembler, Diagnostics, Emitter, Expr, ExprParser,
  IsaBackend, LexerSyntax, Operand, Section, Severity, Statement, SymbolTable, Val.
- `asm/rv32`: Rv32Assembler, Rv32Backend, Rv32Csrs, Rv32Registers.
- `asm/m68k`: M68kAssembler, M68kBackend, M68kFloat, M68kFpInstructions, M68kFpOpcodes,
  M68kInstructions, M68kOperands, M68kRegisters, M68kTarget.
- `bus`: Bus, BusDevice, BusFault, ByteArrays, CycleClock, DmaWindow, IrqSink, MemoryRegion,
  StateFormatException, StateIo, SystemBus.
- `cpu`: Cpu, CpuFactory, CpuHost, CpuModel.
- `cpu/m68k`: Bitfield, FpuOpcodes, M68kCpu, M68kFlags, M68kFpuHost, M68kFpuUnit, M68kMmu,
  M68kOpTable, M68kOptions, M68kSr, M68kTiming, M68kVectors.
- `cpu/m68k/fpu`: FpConstantRom, FpPacked, FpSupport, Fx80, M68kFpu.
- `cpu/rv32`: Rv32Cpu, Rv32Csr.
- `device`: Clint, DiskChunkStore, DiskImage, FloppyController, HddController,
  InterruptController, IntervalTimer, Nic, PagedDiskImage, Ps2Controller, Ps2Device,
  Ps2DeviceKind, Ps2Key, Ps2Keyboard, Ps2Mouse, Regs, Rtc, SparseDiskImage, SysCon, Uart16550,
  VgaDisplay, VgaFrame.
- `machine`: Board, BootRom, BootRoms, M68kBootRom, M68kMap, M68kPlatform, Machine,
  MachineConfig, MachineState, Platform, Rv32Map, Rv32Platform, StandardCpus.
- `net`: Catalogue, CatalogueFile, CatalogueIndex, ContentSource, DeflatedChunks, DhcpServer,
  DnsServer, Fdi, FolderContent, FrameEndpoint, HttpSession, MemoryContent, Modem, Packets, Tcp,
  TcpConnection.
- Outside `computer`: `content/pc/PcLinkExchange` takes `FrameEndpoint`s. Under test sources,
  `computer/tools`: HeadlessPc, RawDiskFile, Screenshot. Under `guest/docker`: Dockerfile,
  build.sh, entrypoint.sh.

Tests, under `src/test/java/com/nectariich/create_flippies/content/computer`:

- `asm` and `asm/rv32`: lexer, expressions, directives, layout, relaxation, robustness,
  encodings, pseudo-instructions, diagnostics, and the classic 68k syntax flags
  (`ClassicSyntaxTest`).
- `asm/m68k`: integer and FPU encodings, addressing modes, relaxation (including a classic `org`
  and `align` that shrink after a branch grew, and labels before padded statements), target
  gating, directives, floats, diagnostics, whole programs.
- `bus`: bus contract, system bus, cycle clock, saved-state helpers.
- `cpu/rv32`: instructions, CSRs, traps, timing, encodings, fuzz, interpreter benchmark, saved
  state.
- `cpu/m68k`: instructions, 68040 additions, exceptions and frames (including the 68040 address
  error frame), timing, saved state, fuzz, interpreter benchmark, and `M68kMmuTest` (12.14.1).
- `net`: `ModemTest`, `TcpTest`, `HttpSessionTest`, `CatalogueTest`, `FdiTest`, `ModemE2eTest`
  (12.14.3).
- `tools`: `HeadlessPcTest` (12.14.4).
- `cpu/m68k/fpu`: `Fx80`, packed decimal, support-package functions, `M68kFpu` with a fake host.
- `device`: one class per device, a device fuzz test, and saved state of every device; for
  11.1 `FloppyControllerTest` (registers, spin-up, seek and sector timing to the cycle, motor
  hold, DMA to RAM and VRAM, write protect, every error, media change and its interrupt, eject
  in flight, saved state and a swapped disk on load), `PagedDiskImageTest` (lazy loads, zeros,
  LRU of clean chunks, dirty chunk copies, the floppy's half chunk, store failures as error 7 on
  the HDD and the floppy, the HDD's id check and its version 1 payload), `NicTest` (MAC by byte,
  half and word on both byte orders, TX and its errors, the receive filter, RX copy, drop and bad
  DMA, overflow, each interrupt, link changes, saved state), `Ps2EitherPortTest` (empty ports, a
  keyboard on port 1 on line 4, a mouse on port 0, kind changes, reset, saved state, a version 1
  payload) and `VgaSharedVramTest` (slices, independent registers and dirty rows, START inside the
  slice, VBLANK lines, saved slices).
- `machine`: the machine loop with a scripted fake CPU, and the 68k foundation types.
- `e2e`, RV32I: assembled programs on a real `Machine` (boot ROM, interrupts, UART, PS/2, VGA,
  HDD, host budget, encoder and decoder cross-check, machine throughput benchmark, freeze and
  resume).
- `e2e`, 68k (each on both models unless noted): `M68kE2e` rig; `M68kProgramE2eTest` (hello,
  exit codes, UART echo, recursive Fibonacci and factorial, memcpy and strlen, MOVEM, LINK and
  UNLK calls, VGA text and graphics, illegal instruction, privilege violation and odd jump
  through the ROM trap handler, odd word read faulting only on the 68000);
  `M68kInterruptE2eTest` (PS/2 keyboard at IPL 2 to VGA text and COM1, interval timer at IPL 4
  ten times, interrupt within one instruction of the expiry, SR mask holding level 2, level 7
  once per edge); `M68kHddE2eTest` (write, read back, image contents, persistence through the
  dirty chunks, read with the HDD interrupt); `M68kBootRomE2eTest` (ROM image, banner and no
  disk, bootable disk with its entry registers, non-bootable disk, guest and host reset turning
  the overlay back on, RESET instruction resetting devices while the program carries on);
  `M68kFpuE2eTest` (68040: sqrt(2) and sin(0.5) as double bits over COM1, strict FPU trapping
  FSIN on vector 11 through the ROM handler, strict flag in the config check, the assembler's
  FPU tables equal to the CPU's and every opmode and predicate assembled to the CPU's code);
  `M68kSaveStateE2eTest` (save and load every tick in lockstep with timer interrupts, COM1 at
  9600 baud, keyboard input, HDD reads, VGA drawing and FPU state; one save into a fresh
  machine; a state does not load into the other model); `M68kBudgetE2eTest` (overshoot of one
  instruction at most, exact time over ticks, tiny budget, past deadline);
  `M68kCrossCheckTest` (assembler against CPU against a reference model: MOVE, MOVEA, MOVEQ,
  LEA, ADD, SUB, CMP, AND, OR, EOR, ADDX, SUBX, ADDQ, SUBQ, NEG, NOT, CLR, TST, EXT, SWAP, EXG,
  every shift and rotate by immediate, register and on memory, MULU, MULS, DIVU, DIVS, the four
  bit instructions, Bcc, Scc and DBcc for every condition, and on the 68040 EXTB.L, MULx.L and
  DIVx.L, with random registers, sizes and effective addressing modes); and
  `M68kThroughputBenchmarkTest` (tagged `benchmark`); for the MMU, `M68kMmuDemandPagingE2eTest`
  and `M68kMmuThroughputBenchmarkTest` (tagged `benchmark`).
- `e2e`, in-game board (11.4), each on RV32I, the 68000 and the 68040 unless noted, through the
  `PcE2e` rig: `FloppyE2eTest` (a bootable floppy boots first with boot device 1, a non-bootable
  floppy falls back to the HDD, the old messages without a bootable floppy, a guest write and
  read back with the image's dirty chunk); `NetworkE2eTest` (two machines with frames moved once
  per tick: A sends, B echoes, A checks payload and MACs; a mixed RV32I and 68040 pair both ways;
  link down gives code 5); `BoardE2eTest` (every new window, the three VRAM bases and PRESENT
  bits, display 2 by DMA at 0xFFD8_0000 on the 68000, RAM limits per platform, 128 MiB machines
  on RV32I and the 68040, a 13.5 MiB 68000 running the ROM, different text on three displays,
  frame ends and VBLANK lines 6, 11 and 12, COM3 echo through the host and a COM3 link between
  two machines, `busyFraction` busy and asleep, the guest error light, the MAC and the HDD id in
  the saved-state check); `BoardSaveStateE2eTest` (save and load every tick in lockstep with a
  floppy read in flight, frames queued both ways, COM3 traffic and three displays drawing).
- `ReviewSystemTest`: regression tests for the bugs the system review found.
- `ReviewM68kCoreTest` (68k core and FPU) and `ReviewM68kSystemTest` (68k assembler, framework
  and machine): regression tests for the bugs the adversarial 68k review found.

Test counts: 1033 tests, 0 failures, 0 errors, under this command:

`./gradlew test --tests 'com.nectariich.create_flippies.content.computer.*' --tests 'com.nectariich.create_flippies.content.pc.LinkExchangeTest' --offline`

That is the computer suite (1028) plus the 5 tests of `LinkExchangeTest`, which now covers
`FrameEndpoint`. Step 3 (12.15) added `KernelLoaderTest` and more `HeadlessPcTest` cases; the
`linux`-tagged `LinuxBootTest` runs only under `./gradlew linuxTest`. Step 1 of section 12
added 111 to the computer suite: 35 for the MMU (32 unit, 2 end to end, 1 benchmark), 61 in
`net`, 6 for the headless runner, and the new `NicTest` and `NetworkE2eTest` cases. Before step
1: 888 tests in 90 classes under
`./gradlew test --tests 'com.nectariich.create_flippies.content.computer.*' --offline`. The
section 11 core added 99: 45 device tests in five classes and 54 end-to-end tests in four
(counting each CPU of a parameterised test). The 789 tests from before still pass; tests whose
assumptions section 11 changed were updated (the PS/2 tests and rigs plug their devices in, the
map, RAM limit and PRESENT checks, and the sixth test of `ReviewM68kSystemTest`, which now checks
that RV32I states of versions 1 to 3 are refused). Of the 789 before section 11, 314 are for the 68k: 74 assembler, 90 integer core, 67 FPU, 3 foundation, 69
end to end, and 11 in the two 68k review classes.

Benchmark (JDK 17, the developer's Mac, single run, so a rough figure):

- RV32I interpreter alone: about 132 M instructions per second.
- RV32I whole machine loop: about 190 M instructions per second at 100 MHz (about 16 ms per
  50 ms tick). The 100 M target in 1.4 is met. Unchanged after section 11's core (178 to 191 M
  over three runs).
- After section 11's core, the 68k machine loop measured about 100 M instructions per second on
  both models (68000 at 16.67 MHz about 0.85 ms per tick; 68040 at 40 MHz about 13 ms per tick),
  no slower than before.
- 68000 interpreter alone: about 82 M instructions per second; whole machine at 16.67 MHz on a
  mixed register and memory loop: about 78 M instructions per second, 1.1 ms of host time per
  50 ms tick (45 times real time).
- 68040 interpreter alone: about 89 M instructions per second; whole machine at 40 MHz on the
  same loop: about 80 M instructions per second, 16.7 ms per 50 ms tick (3 times real time; at
  25 MHz about 10 ms per tick).
- After step 1 of section 12, 68040 machine at 40 MHz: MMU off 81.5 to 83.6 M instructions per
  second, MMU on 71.5 to 76.7 (86% to 93% of off; target 60%). The MMU-off figures did not drop
  (68040 interpreter 102.0 M/s, machine 85.0). Single runs on a shared host (12.14.1).

Fixed after review (the reviews of section 12's step 1 are in 12.14):

- PS/2 keyboard: an unplug while the queue held the overrun code left the keyboard dropping every
  byte until the next reset. Emptying the queue now clears the overrun mark.
- PS/2 keyboard and mouse: a reset queued the power-on bytes even with the device unplugged.
- HDD: a host swap to a smaller disk during a READ or WRITE made completion throw out of the
  machine loop. Completion now ends in ERROR with code LBA range.
- `Machine.runTick` threw on windows above about 92 s at 100 MHz (overflow in the cycle
  product). The cycle count is now split at whole seconds.
- `Rv32Cpu.setPc` (debug API) right after a trap could be taken for the double-fault halt. A
  host-set pc now disarms that check.
- 68040 address error frame: the address field held the odd target; UM040 8.4 says bit 0 is
  cleared. Fixed in the core at integration, with a test.
- Assembler: a classic `org` or `align` after a branch that grew during relaxation was an error
  (the framework never let an instruction statement shrink); a label just before an
  auto-padded instruction or word datum named the pad byte. Both fixed in the shared framework
  at integration (10.7.1), with tests; RV32I is unchanged.
- Adversarial 68k review, each with a regression test in `ReviewM68kCoreTest`,
  `ReviewM68kSystemTest` or the owner's test class:
  - ABCD, SBCD, NBCD with invalid digits: digit correction now follows the hardware-verified
    model of flamewing's 68k-bcd-verifier (10.3.4). SBCD corrected a digit above 9 without a
    borrow (0x0A - 0x00 gave 0x04); ABCD decided the high correction from the corrected sum
    (0x8F + 0x05 gave 0xFA with C instead of 0x9A, C = 0).
  - 68000 bus and address errors on a PC-relative operand read stacked the data space function
    code; they now stack program space (6 supervisor, 2 user), PRM 2.2.
  - FPU format 0 exceptions (line F, BSUN, FRESTORE format error) counted as retired and were
    traced; `M68kFpuHost.exception` with format 0 now marks the instruction as faulted, as the
    core's own illegal and line F do (10.3.10).
  - Strict FPU vector 55 stacked format 2; it now stacks format 0 (input operand,
    pre-instruction) or format 3 (FMOVE out, post-instruction), as UM040 9.6.2 says (10.4.1).
  - Assembler: `$FFFFFFFE(a0)` was rejected on the 68000 and promoted to a 32-bit full format
    on the 68040; an An-relative displacement is now read as a signed 32-bit value (-2), as GNU
    as reads it.
  - Framework: an auto-padded statement that moved to an even offset in a later relaxation pass
    kept its stale pad and failed pass 2; the pad now follows the current offset.
  - Framework: the relaxation cap of 16 passes stopped valid chains of nested forward branches;
    it is now 16 plus the number of instruction statements (5).
  - Framework: an odd text or data base put every 68k instruction at an odd address; it is now
    an error for the 68k backend (`IsaBackend.baseAlignment()`).
  - Framework: a label followed by a statement that emits nothing (`.cpu`) and then a padded
    statement kept the odd address; pending labels now carry over statements that emit nothing.
  - 68000 machine: HDD DMA to the advertised VRAM base 0xFFE0_0000 failed with DMA range;
    the 68000 machine now masks DMA addresses to 24 bits (10.8.1).
  - Saved state: a version 1 envelope (no strict FPU flag) was misread as corrupt; the flag is
    now read only from version 2 on (1.5).

Known gaps:

- `Machine` still passes null for `interruptAck` in `CpuHost` (the 8086 and 8088 need the
  interrupt controller's acknowledge vector). A guest write to VGA BANK has no listener, so
  nothing calls `MemoryRegion.setOffset` at that moment; the 8086 needs one.
- On RV32I, every ROM_OVERLAY_OFF write still stops the CPU after that instruction. Correct but
  slow if a guest writes it in a loop.
- Interval timer: enabling with PERIOD = 0 and then writing PERIOD with byte stores gives one
  spurious EXPIRED, because the restart happens on the access that touches byte 3. This follows
  3.3 as written.
- `Rv32Cpu.setPc` with a pc that is not a multiple of 4 fetches the unaligned word instead of
  trapping. Debug API only; guest jumps trap correctly.
- RV32I: a branch to a plain number is a PC offset (5.2). Whether GNU as agrees is unchecked.
  The 68k assembler reads it as an absolute address (10.7.8).
- Saved state: a different disk of the same capacity is detected only when the disks have ids
  (`DiskImage.id()`, 11.1.6); anonymous disks (`SparseDiskImage`, tests) are still checked by
  capacity alone.
- Section 11 core: the 8086 and 8088 (not built) will need one 64 KiB banked VRAM window per
  display. A floppy transfer that crosses cylinders adds no step time. Each display's saved
  section allocates a 512 KiB copy of its slice on save and on load. The unselected device of a
  PS/2 port is not caught up in time (it is reset when plugged in). A new `Ps2Controller` has
  nothing plugged in: a game that forgets `setDevice` gets a PC with no keyboard.
- Saved state: a device or CPU added to a board later makes older saves fail with "has no
  device" (cold boot) unless that change also teaches the loader to reset the missing component.
- Saved state: save and load run on the machine's thread and are not benchmarked. Deflating a
  fully used 64 MiB RAM is the expensive case; idle RAM pages cost nothing.
- Saved state: on load the VGA keeps its saved row generations; a client that joined later still
  needs a full frame, which is the game's sync logic, not the core's.
- 68040 throughput: about 80 M instructions per second on the developer's Mac, so a busy 68040
  at 25 MHz needs about 10 ms of host time per 50 ms tick (17 ms at 40 MHz), more than the
  2 ms default budget: a busy 68040 runs at about a fifth of real time unless the game raises
  the budget (10.3.9 predicted the dilation). A busy 68000 at 8 MHz fits easily.
- 68000 timing follows the user's manual tables where real chips measure differently (TAS to
  memory, ANDI.L #,Dn; 10.2.2). The 68040's timing is the simple model of 10.3.9, not UM040
  chapter 10.
- Strict FPU: the busy frame FSAVE writes after vector 55 has zero contents (no ETEMP or
  FPTEMP operand), so a support package written for real hardware cannot emulate from it; the
  stack frame format is now the one UM040 9.6.2 gives.
- Not checked against a primary source: the FP exception priority order, the CHK2 and CMP2
  signedness rule and the DIVx.L Dr == Dq rule (10.2.4, 10.3.4).
- The pending device reset in the saved state is always false at a save point today (the
  machine performs it before a run returns to the tick loop); it is stored so a later change to
  when states are taken stays correct.
- C and C++ (section 6) are planned, not built.
- Section 12, step 1:
  - PTEST writes U back (and M for PTESTW) as any table search does; UM040 does not say whether
    the chip does, so this is inferred, not confirmed.
  - Failed searches are not cached in the TLB (the chip's ATC caches them), so the emulator does
    not need the PFLUSH that real hardware needs after an invalid descriptor is repaired.
  - A fault while stacking any exception frame halts the CPU; the chip halts only while stacking
    an access error or reset frame.
  - Not checked in UM040's bus pages: the TM the chip puts out for an FPU immediate fetch (built
    as 2 or 6, like any instruction fetch).
  - The 13 MB partial kernel extract at `guest/linux/.cache/linux-6.18.58/`, unpacked on the
    case-insensitive repo folder for the NOTES checks, is still there and should be deleted (the
    tarball and `sha256sums.asc` stay).
  - No script fetches the kernel tarball or checks its SHA-256 yet; the kernel build script
    (12.6) must.
  - `Catalogue.hasHost` can still throw if the content store's `hosts()` fails (a server-side
    storage fault, not guest input).
  - The resource name escaping rule for the catalogue (12.4.3) is not chosen.
  - Interactive Ctrl-C under `./gradlew runHeadlessPc` is untested (SIGINT and SIGTERM to the
    runner's JVM are).
  - The m68k binfmt registration lasts only until the Docker VM restarts; any privileged
    `build.sh` run registers it again.
  - The house rule against the chip maker's name has one accepted exception: upstream kernel
    file paths such as `motorola_pgtable.h` and `arch/m68k/mm/motorola.c`, cited by their real
    names here and in `guest/linux/NOTES.md`.
