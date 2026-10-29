# Linux fork notes: kernel facts checked for spec 12.6 and 12.7

Facts the Linux fork (spec 12.6) and the boot chain (spec 12.7) depend on, read from the pinned
kernel tree. Every path below is relative to the unpacked tree, every line number is from that
exact release. Checked on 2026-10-25.

## Pinned kernel

- Release: 6.18.58, the newest longterm on kernel.org on 2026-10-25 (`releases.json` listed
  longterm 6.18.58, 6.12.114, 6.6.160, 6.1.190, 5.15.223, 5.10.272; mainline 7.3; stable 7.2.12).
  The spec's "6.12 at the time of writing" is out of date: 6.18 is the newer longterm.
- Tarball: `https://cdn.kernel.org/pub/linux/kernel/v6.x/linux-6.18.58.tar.xz`, 154,851,680 bytes.
- SHA-256: `069b19f9032c4dac8149ae4b6520c35debca0f42a9f6d52ee55ba6ca9b818013`.
- Cross-checked: the same hash is listed in kernel.org's `v6.x/sha256sums.asc`, and that file's
  signature checks good against the "Kernel.org checksum autosigner" key
  (fingerprint B886 8C80 BA62 A1FF FAF5 FDA9 632D 3A06 589D A6B1, fetched from kernel.org's
  `pgpkeys.git`, `keys/632D3A06589DA6B1.asc`).
- Fetched by `guest/linux/fetch.sh` into the toolchain container's `/build` volume
  (`/build/linux/linux-6.18.58.tar.xz`) and refused unless `sha256sum` matches the hash above;
  the script unpacks it there and applies `patches/` (see "Step one of the port" below). The copy
  in `guest/linux/.cache/` (ignored by git) was fetched by hand for these checks; no script uses
  it.
- Unpack and build the tree in the toolchain container's `/build` volume (`build.sh` mounts a
  named Docker volume there), never in the repo. On the Mac the repo is case-insensitive APFS
  and the tarball has 13 paths that differ only in case: uapi headers `xt_CONNMARK.h`,
  `xt_DSCP.h`, `xt_MARK.h`, `xt_RATEEST.h`, `xt_TCPMSS.h`, `ipt_ECN.h`, `ipt_TTL.h`,
  `ip6t_HL.h`, sources `net/netfilter/xt_DSCP.c`, `xt_HL.c`, `xt_RATEEST.c`, `xt_TCPMSS.c`,
  each against its lower-case twin, and one litmus test. Unpacked there, each pair silently
  becomes one file.
- `bindeb-pkg` needs the build profile `pkg.linux-upstream.nokernelheaders` in this image: the
  generated `debian/control` (`scripts/package/mkdebian` lines 210-215) asks for
  `libssl-dev` of the target arch (m68k, not installable on Debian stable) unless that profile
  is set, and the profile also drops the `linux-headers` package (line 243), which we do not
  need. The Dockerfile sets `DEB_BUILD_PROFILES` to it. Checked: with it, `virt_defconfig`
  plus `bindeb-pkg` builds `linux-image-6.18.58-virt_6.18.58-1_m68k.deb` and
  `linux-libc-dev_6.18.58-1_m68k.deb`; with the variable emptied, `dpkg-checkbuilddeps` stops on
  "unmet build dependencies: libssl-dev".

## Bootinfo (`arch/m68k/include/uapi/asm/bootinfo.h`)

- Record layout (lines 34-38): `__be16 tag`, `__be16 size`, then data. `size` is the whole record
  in bytes, header included: the walkers add it to the record's start (head.S line 1540,
  setup_mm.c line 191).
- Tags (lines 56-73): `BI_LAST` 0x0000, `BI_MACHTYPE` 0x0001, `BI_CPUTYPE` 0x0002,
  `BI_FPUTYPE` 0x0003, `BI_MMUTYPE` 0x0004, `BI_MEMCHUNK` 0x0005 (two `__be32`: address, size),
  `BI_RAMDISK` 0x0006 (address, size), `BI_COMMAND_LINE` 0x0007 (NUL-terminated string),
  `BI_RNG_SEED` 0x0008 (a `__be16` length then that many seed bytes, padded to 4). Machine
  specific tags start at 0x8000 (line 53).
- Machine types (lines 80-93): AMIGA 1, ATARI 2, MAC 3, APOLLO 4, SUN3 5, MVME147 6, MVME16x 7,
  BVME6000 8, HP300 9, Q40 10, SUN3X 11, M54XX 12, M5441X 13, VIRT 14. The last used number is
  14 (`MACH_VIRT`), so `MACH_FLIPPIES` = 15 is free.
- CPU, FPU and MMU values are bit masks, not small numbers (lines 107-147):
  - `CPU_68040` = 1 << `CPUB_68040` (2) = 0x4.
  - `FPU_68040` = 1 << `FPUB_68040` (2) = 0x4.
  - `MMU_68040` = 1 << `MMUB_68040` (2) = 0x4.
  - So the loader writes 4 in each of `BI_CPUTYPE`, `BI_FPUTYPE`, `BI_MMUTYPE`.
- `BI_MACHTYPE`, `BI_CPUTYPE`, `BI_FPUTYPE` and `BI_MMUTYPE` are all required: head.S stores the
  word at the returned pointer without checking that the tag was found (head.S lines 599-613).
- Only one memory chunk matters with `CONFIG_SINGLE_MEMORY_CHUNK` (setup_mm.c lines 197-203);
  the spec's single `BI_MEMCHUNK` (0, RAM size) fits.
- The kernel does not check a bootinfo version for an unknown machine. The `bootversion` table
  at `_stext` (head.S lines 564-581) is for bootstraps that want to check it; ours can ignore it.

## Where `head.S` expects bootinfo, and how it detects the machine (`arch/m68k/kernel/head.S`)

- Placement rule: the first record starts exactly at the symbol `_end`, at the physical address
  where `_end` lands in the loaded image. `get_bi_record` (lines 1532-1549) starts with
  `lea %pc@(_end),%a0`, PC-relative because the MMU is still off. `setup_arch` parses from
  `_end` again later (`arch/m68k/kernel/setup_mm.c` lines 206-210).
- `_end` is page aligned: `arch/m68k/kernel/vmlinux-std.lds` lines 51-58 align `.init_end` to
  `PAGE_SIZE` and put `_end` right after it. So stage 2 writes the records at `_end` with no gap.
- The list ends with a `BI_LAST` record (tag 0). `get_bi_record` stops at the first tag word of
  0 (line 1536); write a full 4-byte record (tag 0, size 4) to be safe.
- Free memory for head.S's early page tables starts at the next page boundary after the
  `BI_LAST` record (lines 2542-2556). The loader must not put anything there (the ramdisk, if
  any, goes well above).
- Link and load address: `vmlinux-std.lds` line 14 links the kernel at 0x1000, and
  `PAGE_OFFSET` is 0 for this platform (`arch/m68k/include/asm/page_offset.h` line 9). The kernel
  must be loaded at its link address plus the first memory chunk's base: `paging_init` sets
  `m68k_memoffset = m68k_memory[0].addr - PAGE_OFFSET` and reserves everything from the chunk's
  base up to the end of head.S's allocations (`arch/m68k/mm/motorola.c` lines 451-484). With the
  chunk at 0 the kernel goes at physical 0x1000, entry at the ELF entry `_start`
  (`vmlinux-std.lds` line 10, `_start` = `_stext` + one page, head.S lines 585-588).
- BSS: the MMU `head.S` does not clear it (only the no-MMU heads do, `arch/m68k/68000/head.S`
  and `arch/m68k/coldfire/head.S`). The loader zeroes each segment from `p_filesz` to `p_memsz`.
- Measured on a real build (`virt_defconfig`, built in the toolchain image in about a minute):
  entry 0x2000, `_stext` 0x1000, two LOAD segments. The first starts at address 0 and file
  offset 0, file size 0x6C4F80, memory size 0x6F86C8: BSS (0x6C4F80 to 0x6F86C8) is not in the
  file at all. The second is the init section at 0x6F9000 with a 0x1C8 zero tail. `_end` =
  0x716000, page aligned.
- Page 0 is not the kernel's to load: the first segment's bytes below 0x1000 are the ELF header
  and padding, and page 0 holds the boot ROM's RAM vector table (spec 10.8.4 step 3). Copied
  there, they set the bus error and address error vectors to 0 (the `e_ident` padding), so any
  later fault in stage 2 jumps to address 0 instead of the ROM's `rom_trap` message. Rule for
  stage 2: copy each segment only from `max(p_paddr, 0x1000)`, advancing the file offset by the
  same amount. Nothing below `_stext` is needed: `head.S` points its first stack at `_stext`
  (line 594, `lea %pc@(_stext),%sp`) and grows it down into page 0 only after the jump.
- Consequence for 12.7: the kernel occupies physical 0x1000 up to `_end` (about 7 MiB for
  `virt_defconfig`; our config must stay under 12.6's 6 MiB target), then the bootinfo, then
  head.S's tables. Stage 2 at 0x10000 (12.7) sits inside that range, so stage 2 must either run
  from high RAM or load the kernel high and copy it down as its last step before the jump.
- Machine detection: none by probing. `__start` (lines 590-613) copies the data of
  `BI_MACHTYPE`, `BI_FPUTYPE`, `BI_MMUTYPE` and `BI_CPUTYPE` into `m68k_machtype` and friends.
  Every later machine test is a compare against `m68k_machtype` (`is_not_virt` etc., lines
  521-534). The CPU class comes from the `BI_CPUTYPE` bits (lines 657-700: bit `CPUB_68040`
  selects the 040 paths).
- An unknown machine type panics in `setup_arch` ("No configuration setup",
  `arch/m68k/kernel/setup_mm.c` line 327), so the fork adds a `case MACH_FLIPPIES` to the switch
  at line 257, next to `MACH_VIRT` (lines 321-325).
- Places in head.S where the fork adds a `MACH_FLIPPIES` branch, modelled on the virt machine:
  - `mmu_init`: virt maps its device space with one transparent translation register
    (lines 1246-1251: `mmu_map_tt #1,#0xFF000000,#0x01000000,#_PAGE_NOCACHE_S`, which sets
    ITT1 and DTT1, lines 2070-2082). The same call covers our VRAM, devices and ROM
    (0xFFD8_0000 to 0xFFFF_FFFF), so a TT register is the simple answer to 12.6's "ioremap or a
    DTT register" choice. `arch/m68k/mm/kmap.c` lines 182-187 and 307-310 show the matching
    `ioremap` and `iounmap` shortcut virt uses (return the physical address unchanged at or
    above 0xFF000000); the fork adds the same for `MACH_IS_FLIPPIES`.
  - `serial_putc` (starts at line 2999; the virt branch is lines 3202-3208): one branch writing
    the byte to COM1's THR, waiting for THRE if wanted.
  - The early bootinfo reads after `__start` (virt reads its tty base at lines 648-655) if the
    fork passes any machine-specific tags.
- MMU switch-on for the 040 (lines 2473-2490): `pflusha`, SRP, `movec` TC = `TC_ENABLE` +
  `TC_PAGE4K` (0x8000, constants at lines 308-310), an absolute `jmp`, then a second SRP
  write while translation is on. So the emulator must flush on MOVEC SRP (12.2.2 already says so)
  and the kernel uses 4 KiB pages. The TT bit values match 12.2.1: E 0x8000, S field "any mode"
  0x4000, "kernel only" 0x2000, "user only" 0 (lines 313-316).

## `access_error040` and a format 7 frame with no writebacks (`arch/m68k/kernel/traps.c`)

- Dispatch: `buserr_c` sends frame format 7 to `access_error040` (lines 787-792).
- Frame fields as Linux reads them (`arch/m68k/include/asm/traps.h` lines 219-235): EA, SSW,
  WB3S, WB2S, WB1S, FA, WB3A, WB3D, WB2A, WB2D, WB1A, WB1D/PD0, PD1, PD2, PD3. That is the order
  in 12.2.1.
- SSW bits Linux uses (`traps.h` lines 130-140): CP 0x8000, CU 0x4000, CT 0x2000, CM 0x1000, MA
  0x0800, ATC 0x0400, LK 0x0200, RW 0x0100, SIZE 0x0060, TT 0x0018, TM 0x0007. These agree with
  12.2.1 (bit 7 X is not used by Linux).
- What `access_error040` (lines 291-361) needs:
  - ATC set for a translation fault. With ATC clear the fault is handled as a bus error
    (SIGBUS for user mode, an exception fixup or an oops for kernel mode, lines 347-358).
  - RW: 1 for a read. Write faults are `!(ssw & RW_040)`, or LK set (lines 324-327).
  - TM (bits 2..0) must be the function code of the faulting access: the handler passes the
    whole SSW to `probe040` (lines 181-197), whose `set_fc` loads it into SFC and DFC
    (`arch/m68k/include/asm/processor.h` lines 90-97), then runs PTESTR or PTESTW at FA and reads
    MMUSR. So MOVEC to SFC and DFC must keep only bits 2..0, and PTEST must use DFC.
  - FA inside the faulting page. If MA is set the handler rounds FA up to the next multiple of
    8 before probing (lines 311-312); with MA clear it uses FA as is. Reporting MA = 0 with FA
    inside the page that faulted is the simplest correct choice for a page-crossing access.
  - MMUSR R (bit 0) clear means "not present": the handler runs `__flush_tlb040_one` (a PFLUSH
    by address) and passes error code 0; R set means a protection fault, error code 1
    (lines 317-322). MMUSR bits Linux reads (`traps.h` lines 161-168): B 0x800, G 0x400, S 0x80,
    CM 0x60, M 0x10, W 0x4, T 0x2, R 0x1, matching 12.2.1.
- Writebacks: `do_040writebacks` (lines 238-275) acts only on WB2S and WB3S with the valid bit
  `WBV_040` (0x80) set; WB1S is ignored (the check is under `#if 0`). With WB1S, WB2S and WB3S
  all 0 nothing is written back and no signal is raised. `berr_040cleanup` after sigreturn
  (lines 283-289) likewise does nothing. So a format 7 frame with all three status words 0 is
  accepted.
- CT, CU, CP: Linux never reads them; 0 is fine.
- Stacked PC: kernel faults on user memory are fixed up through the exception table keyed by
  the stacked PC (`fixup_exception`, `arch/m68k/kernel/signal.c` lines 92-109, which also
  rebuilds a format 0 frame). `put_user` lists both the faulting `moves` and the instruction
  after it (`arch/m68k/include/asm/uaccess.h` lines 31-44, entries `1b` and `2b`), so the
  restart model's PC (the first word of the faulting instruction) is found.
- Frame size: `frame_size_change[7]` is the size of the format 7 extension
  (`signal.c` line 76), 52 bytes, so the whole frame is 60 bytes (30 words) as 12.2.1 says.

## U and M bits in the page tables (`arch/m68k/include/asm/motorola_pgtable.h`)

- Bit values (lines 9-20): PRESENT 0x001, SHORT 0x002 (table descriptor type), RONLY 0x004 (W),
  ACCESSED 0x008 (U), DIRTY 0x010 (M), SUPER 0x080, GLOBAL040 0x400, cache mode in 0x060.
- Linux reads U as "young" and M as "dirty" (lines 147-148), and clears them to age pages and
  to mark them clean (`pte_mkold`, `pte_mkclean`, lines 151-152).
- Every user protection already includes ACCESSED (`PAGE_SHARED`, `PAGE_COPY`,
  `PAGE_READONLY`, lines 76-79), and table descriptors are written with ACCESSED set
  (`pmd_set`, `pud_set`, lines 92 and 97). DIRTY is not in `PAGE_SHARED`: a writable page starts
  clean and only the MMU's M write-back marks it dirty.
- Consequence for the emulator: M must be written back on the first write through a page (else
  Linux can drop modified file pages as clean: data loss). U must be written back on access
  after Linux clears it (else every page looks idle to reclaim: wrong aging, not corruption).
  This confirms 12.2.1 and the write-entry rule of 12.2.2.
- No indirect descriptors: Linux never writes PDT = 2 in a page entry. Swap entries keep bits
  1..0 at 0 (lines 182-186, type in bits 10..4, offset from bit 12, bit 11 is the exclusive
  marker, line 45), so the MMU sees them as invalid. `PAGE_NONE` is RONLY with PRESENT clear
  (PDT 0, line 42): it faults like any invalid page.

## atkbd over a raw serio port (`drivers/input/keyboard/atkbd.c`)

- No translation flag is needed. The port driver registers its serio with
  `id.type = SERIO_8042` (value 0x01, `include/uapi/linux/serio.h` line 30) and a `write`
  callback. atkbd binds to `SERIO_8042` (lines 1409-1414) and only treats the port as
  translated (set 1) for `SERIO_8042_XL` (lines 1291-1301). Many non-PC ports do exactly this
  (`drivers/input/serio/ambakmi.c` line 125, `q40kbd.c` line 115, `ps2-gpio.c` line 440).
- With `write` present it talks to the keyboard (lines 1316-1338) and stays in set 2, the
  default (`atkbd_set = 2`, line 36; `atkbd_select_set` returns 2 for set 2 without sending
  anything when `atkbd_extra` is off, lines 881-916).
- Commands the emulated keyboard will see, in order: FF reset (on by default outside x86, lines
  40-44; libps2 accepts FA then AA, `drivers/input/serio/libps2.c` lines 326-368), F2 get ID
  (expects FA AB 83, lines 832-855), F5 disable (`atkbd_deactivate`), ED 00 LEDs and F3 00
  typematic (`atkbd_reset_state`, lines 942-965), F4 enable. `Ps2Keyboard` already handles FF,
  F2, F5, ED, F3 and F4.
- psmouse also binds to `SERIO_8042` (`drivers/input/mouse/psmouse-base.c` lines 1757-1763), so
  both ports use the same type. atkbd gives up on the mouse port when GET ID returns a
  non-keyboard ID (lines 852-853), then psmouse takes it.
- atkbd checks parity and framing flags on non-x86 (lines 434-437); the port driver passes 0
  flags, so this never fires.

## debian-ports facts (spec 12.8)

- snapshot.debian.org archives debian-ports: `https://snapshot.debian.org/archive/debian-ports/`
  lists monthly runs from 2008-10 to 2026-10, with gaps in 2023-01 and from 2023-08 to
  2024-04. October 2026
  has 102 timestamps, the latest at the time of checking 20261025T063552Z (four a day). apt
  reads it with `[check-valid-until=no]`, signed by the keys in
  `debian-ports-archive-keyring`.
- The keyring package in Debian 13 (2026.02.04~deb13u1) holds the 2026 and 2027 ports signing
  keys, installed into `/etc/apt/trusted.gpg.d/`, so mmdebstrap and apt trust debian-ports
  without extra options. Checked: with an empty keyring apt refuses the archive ("is not
  signed"); with the package's keys `mmdebstrap --variant=extract` of `busybox-static` for m68k
  from `http://deb.debian.org/debian-ports/` succeeds.
- Installable on m68k unstable at snapshot 20261025T063552Z (`apt-get -s install` against an
  empty status file, so every dependency resolved; `unstable` plus `unreleased`):
  - X: xserver-xorg-core 2:21.1.25-2, xserver-xorg-video-fbdev 1:0.5.0-2,
    xserver-xorg-input-evdev 1:2.11.0-1, xserver-xorg-input-libinput 1.5.0-1, xinit 1.4.2-1,
    xterm 412-1, xfonts-base 1:1.0.5+nmu1, xdm 1:1.1.17-5.
  - Window managers: jwm 2.4.6-2.1, icewm 3.6.0-1.1, fluxbox 1.3.7-1+b1, openbox 3.6.1-12+b4,
    twm 1:1.0.10-1+b1, fvwm 1:2.7.0-3, wmaker 0.96.0-6, matchbox-window-manager 1.2.3-1,
    dwm 6.8-1, i3-wm 4.25.1-1. All installable.
  - System: sysvinit-core 3.18-1, ifupdown 0.8.45+nmu1+b1, dhcpcd-base 1:10.5.2-6,
    busybox-static 1:1.38.0-3. `isc-dhcp-client` is not in the archive any more: use
    `dhcpcd-base` as ifupdown's DHCP client.
  - Debian's own m68k kernel is linux-image-m68k 7.2.12-1 (not used; we build our own).
- Installed together, sysvinit-core, ifupdown, dhcpcd-base, the X server with fbdev and evdev,
  xinit, xterm, xfonts-base and jwm resolve to 170 packages, provided
  `systemd-standalone-sysusers` is named: xserver-xorg-core depends on udev, and udev depends on
  `systemd | systemd-standalone-sysusers`. Without it apt picks the full `systemd` package
  (installed beside sysvinit-core, not as init, but heavier).

## Step one of the port: what booting showed (spec 12.6, 12.10 step 3)

Checked on 2026-10-25 by booting the series in the headless runner (68040 at 25 MHz, 128 MiB of
RAM, `--kernel`). Line numbers are in the unpatched 6.18.58 tree.

- Layout of the step-one build (`config.d/00-base.config`): entry `_start` 0x2000, `_stext`
  0x1000, two PT_LOAD segments. The first starts at address 0 and file offset 0 (file size
  0x2E2AE0, memory size 0x30D740; BSS from `__bss_start` 0x2E2AE0), the second is the init
  section at 0x30E000 (file size 0x14324, memory size 0x15000). `_end` is 0x323000, the end of
  the last segment, already page aligned: a loader without the symbol table finds it by rounding
  the segments' end up to a page. About 3.1 MiB is loaded, under 12.6's 6 MiB.
- 32-bit time system calls are needed. allnoconfig turns `CONFIG_COMPAT_32BIT_TIME` off (a
  visible prompt, `arch/Kconfig` lines 1466-1472), but Debian's userland makes those calls: under
  `qemu-m68k -strace` the static busybox's `sleep 1` calls `clock_nanosleep` (the 32-bit system
  call, 265), not `clock_nanosleep_time64` (407). Without the option it gets ENOSYS and every
  sleep returns at once; `clock_nanosleep_time64` itself sleeps correctly (checked with a raw
  system call). The base fragment sets the option.
- Timekeeping after a long tickless idle: `accumulate_nsecs_to_secs` (`kernel/time/timekeeping.c`
  line 2241) calls `second_overflow` once per elapsed second, inside `timekeeping_advance` with
  `tk_core.lock` held and interrupts off (line 2391). Measured on the emulated 25 MHz 68040:
  about 20 us per idle second, so after 1000 s of idle the catch-up kept interrupts off for 21 ms
  and overran COM1's 16-byte receive FIFO (1.4 ms at 115200 baud). The timer's 64-bit counter
  lets NO_HZ sleep up to the clocksource's `max_idle_ns` of 3526 s; the clock event device's
  maximum delta of 1 s keeps every catch-up short.
- The 8250 driver's autoconfig (`autoconfig` in `drivers/tty/serial/8250/8250_port.c`) passes
  the IER and loopback tests on the emulated UART and reads FIFO bits 0b11 in IIR, so every COM
  port is a 16550A without forcing the type. `SERIAL_8250_16550A_VARIANTS` (line 62 of the 8250
  Kconfig) is off, which skips probing for the 16550A's variants. COM1 to COM3 are ttyS0 to
  ttyS2 on irqs 9, 10 and 16 (8 plus the controller line).
- Random numbers: with no seed from the loader, "random: crng init done" came 259 s and 405 s
  after boot in two runs, and `getrandom()` blocks until then. The boot chain's stage 2 (12.7)
  should pass `BI_RNG_SEED` (tag 0x0008, above).
- Upstream quirk, harmless: `arch/m68k/mm/motorola.c` line 520 stores `memblock_end_of_DRAM()`, a
  byte address, as the DMA zone's last page frame, so the log shows "DMA [mem
  0x0000000000000000-0x0000007fffffffff]" on a 128 MiB machine; the zone is clamped to the memory
  present.
