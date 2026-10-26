/*
 * flipboot stage 2 (spec 12.7): reads the boot area of partition 1, offers the other kernel slot
 * for 2 seconds (Esc), loads the chosen slot's kernel ELF, writes the bootinfo after it and enters
 * the kernel with the MMU off. When the chosen slot cannot be booted (empty, a bad ELF file, a
 * kernel CRC error) it says why and tries the header's other slot. Errors print one line on COM1
 * and VGA (through the boot ROM's print_string); when no slot boots, it stops.
 *
 * The kernel's command line is the slot's, plus " flipboot.slot=A" (or B) naming the slot
 * actually loaded, when that fits in 255 bytes. Linux ignores the unknown dotted parameter and
 * shows it in /proc/cmdline, where flipboot-update reads it so that it never overwrites the
 * running kernel. flipboot-update keeps stored command lines to 239 bytes so the marker fits.
 *
 * Boot area (partition 1, MBR type 0xDA), all numbers big-endian:
 *   byte 0     header, 4096 bytes:
 *                0   "FLBT"
 *                4   version, 1
 *                8   active slot, 0 (A) or 1 (B)
 *               12   CRC of the 4096 header bytes with this field read as 0
 *               16   generation, counted up by every update (informational)
 *               32   slot A record, 544 bytes; 576 slot B record:
 *                      0  kernel offset in bytes from the partition's start, a multiple of 512
 *                      4  kernel length in bytes, 0 for an empty slot
 *                      8  CRC of the kernel's bytes
 *                     32  kernel command line, NUL-terminated, 512 bytes
 *   byte 4096  a second copy of the header, used when the first fails its check. Updates write
 *              the first copy, then the second, so one of them is whole at any moment.
 *   Kernels start after the two header copies; mkdisk.py and flipboot-update put slot A at 1 MiB
 *   and slot B at 16 MiB.
 *
 * Every CRC is the POSIX cksum CRC (coreutils and busybox cksum print it), so the shell script
 * flipboot-update can make and check them with the cksum Debian always has.
 *
 * The kernel (rules checked in Linux 6.18.58, guest/linux/NOTES.md, the same as the headless
 * runner's KernelLoader): each PT_LOAD segment goes to its physical address, copied only from
 * max(p_paddr, 0x1000) on (page 0 holds the ROM's vector table); p_filesz..p_memsz is zeroed;
 * _end is the end of the last segment rounded up to a page (the kernel's _end is page aligned
 * and comes right after the last segment); the bootinfo records start exactly at _end and end
 * with BI_LAST; nothing is written after them, where head.S puts its page tables.
 */

typedef unsigned char u8;
typedef unsigned short u16;
typedef unsigned int u32;

#define REG(base, off) (*(volatile u32 *)((base) + (off)))

#define PS2 0xFFF04000u
#define HDD 0xFFF05000u
#define TIMER 0xFFF07000u
#define RTC 0xFFF08000u

#define SECTOR 512u
#define PAGE 4096u
#define LOAD_FLOOR 0x1000u
/* stage 2's own address (stage2.ld): everything the kernel needs ends below it */
#define LOAD_LIMIT 0x00F00000u

#define HEADER_SIZE 4096u
#define SLOT_RECORD 32u
#define SLOT_SIZE 544u
#define CMDLINE_FIELD 512u
/* COMMAND_LINE_SIZE on m68k, its NUL included */
#define COMMAND_LINE_SIZE 256u

#define PROMPT_MICROS 2000000u
#define KEY_ESC 0x76
/* appended to the command line; its last character becomes the slot's letter */
#define SLOT_MARKER "flipboot.slot=A"

#define BI_LAST 0x0000
#define BI_MACHTYPE 0x0001
#define BI_CPUTYPE 0x0002
#define BI_FPUTYPE 0x0003
#define BI_MMUTYPE 0x0004
#define BI_MEMCHUNK 0x0005
#define BI_COMMAND_LINE 0x0007
#define BI_RNG_SEED 0x0008
#define MACH_FLIPPIES 15
#define TYPE_68040 4
#define RNG_SEED_BYTES 32

#define MAX_SEGMENTS 16
#define BUF_SECTORS 128u

void flipboot_main(u32 ram, u32 capacity, void *print) __attribute__((noreturn));
extern void rom_print(void *print_string, const char *s);
extern void enter_kernel(u32 entry) __attribute__((noreturn));
extern u32 crc_words(u32 crc, const u32 *p, u32 words, const u32 tables[4][256]);

#define CHUNK (BUF_SECTORS * SECTOR)
/* two bounce buffers: the disk fills one while the CPU checks the other */
static u8 bounce[2][CHUNK] __attribute__((aligned(16)));
#define buf (bounce[0])
static u8 header[2][HEADER_SIZE] __attribute__((aligned(16)));
/* crc_table[k][b]: byte b followed by k zero bytes, for four bytes at a time */
static u32 crc_table[4][256];
static void *print_fn;

struct segment {
    u32 offset, paddr, filesz, memsz;
};
static struct segment segments[MAX_SEGMENTS];
static int nsegments;

/* ---- small library ---- */

void *memcpy(void *dst, const void *src, unsigned long n)
{
    u8 *d = dst;
    const u8 *s = src;
    if ((((u32)d | (u32)s) & 3) == 0) {
        u32 *dw = (u32 *)d;
        const u32 *sw = (const u32 *)s;
        for (; n >= 16; n -= 16) {
            dw[0] = sw[0];
            dw[1] = sw[1];
            dw[2] = sw[2];
            dw[3] = sw[3];
            dw += 4;
            sw += 4;
        }
        for (; n >= 4; n -= 4)
            *dw++ = *sw++;
        d = (u8 *)dw;
        s = (const u8 *)sw;
    }
    while (n--)
        *d++ = *s++;
    return dst;
}

void *memset(void *dst, int c, unsigned long n)
{
    u8 *d = dst;
    if (c == 0 && ((u32)d & 3) == 0) {
        u32 *dw = (u32 *)d;
        for (; n >= 16; n -= 16) {
            dw[0] = 0;
            dw[1] = 0;
            dw[2] = 0;
            dw[3] = 0;
            dw += 4;
        }
        for (; n >= 4; n -= 4)
            *dw++ = 0;
        d = (u8 *)dw;
    }
    while (n--)
        *d++ = (u8)c;
    return dst;
}

static void puts(const char *s)
{
    rom_print(print_fn, s);
}

static void put_hex(u32 v)
{
    char s[11];
    s[0] = '0';
    s[1] = 'x';
    for (int i = 0; i < 8; i++) {
        u32 d = (v >> (28 - 4 * i)) & 15;
        s[2 + i] = (char)(d < 10 ? '0' + d : 'A' + d - 10);
    }
    s[10] = 0;
    puts(s);
}

static void put_dec(u32 v)
{
    char s[11];
    int i = 10;
    s[i] = 0;
    do {
        s[--i] = (char)('0' + v % 10);
        v /= 10;
    } while (v);
    puts(s + i);
}

static void put_slot(int slot)
{
    puts(slot ? "B" : "A");
}

static void __attribute__((noreturn)) halt(void)
{
    for (;;)
        __asm__ volatile("stop #0x2700");
}

static void __attribute__((noreturn)) fail(const char *message)
{
    puts("flipboot: ");
    puts(message);
    puts("\r\n");
    halt();
}

/* Says why a slot cannot be booted; the caller tries the other slot or stops. */
static void slot_error(int slot, const char *message)
{
    puts("flipboot: slot ");
    put_slot(slot);
    puts(": ");
    puts(message);
    puts("\r\n");
}

static u32 be32(const u8 *p)
{
    return (u32)p[0] << 24 | (u32)p[1] << 16 | (u32)p[2] << 8 | p[3];
}

static u32 be16(const u8 *p)
{
    return (u32)p[0] << 8 | p[1];
}

static u32 le32(const u8 *p)
{
    return (u32)p[3] << 24 | (u32)p[2] << 16 | (u32)p[1] << 8 | p[0];
}

static void put_be16(u8 *p, u32 v)
{
    p[0] = (u8)(v >> 8);
    p[1] = (u8)v;
}

static void put_be32(u8 *p, u32 v)
{
    p[0] = (u8)(v >> 24);
    p[1] = (u8)(v >> 16);
    p[2] = (u8)(v >> 8);
    p[3] = (u8)v;
}

/* ---- the POSIX cksum CRC: polynomial 0x04C11DB7, MSB first, the length appended, inverted ---- */

static void crc_init(void)
{
    for (u32 i = 0; i < 256; i++) {
        u32 c = i << 24;
        for (int k = 0; k < 8; k++)
            c = (c & 0x80000000u) ? (c << 1) ^ 0x04C11DB7u : c << 1;
        crc_table[0][i] = c;
    }
    for (int k = 1; k < 4; k++)
        for (u32 i = 0; i < 256; i++) {
            u32 c = crc_table[k - 1][i];
            crc_table[k][i] = (c << 8) ^ crc_table[0][c >> 24];
        }
}

static u32 crc_update(u32 c, const u8 *p, u32 n)
{
    const u32 *t0 = crc_table[0];
    while (n && ((u32)p & 3)) {
        c = (c << 8) ^ t0[(c >> 24) ^ *p++];
        n--;
    }
    c = crc_words(c, (const u32 *)p, n / 4, crc_table);
    p += n & ~3u;
    n &= 3;
    while (n--)
        c = (c << 8) ^ t0[(c >> 24) ^ *p++];
    return c;
}

static u32 crc_finish(u32 c, u32 length)
{
    for (; length; length >>= 8)
        c = (c << 8) ^ crc_table[0][(c >> 24) ^ (length & 0xFF)];
    return ~c;
}

/* ---- devices ---- */

/* Starts reading count (1..256) sectors from lba to dst; hdd_wait waits for it. */
static void hdd_start(u32 lba, u32 count, void *dst)
{
    REG(HDD, 0x18) = 0;
    REG(HDD, 0x04) = 6;
    REG(HDD, 0x08) = lba;
    REG(HDD, 0x0C) = count;
    REG(HDD, 0x10) = (u32)dst;
    REG(HDD, 0x00) = 1;
}

/* Waits for the read hdd_start began (polled); stops on an error. */
static void hdd_wait(u32 lba)
{
    u32 status;
    while (!((status = REG(HDD, 0x04)) & 6))
        ;
    REG(HDD, 0x04) = 6;
    if (status & 4) {
        puts("flipboot: disk error ");
        put_dec(REG(HDD, 0x1C));
        puts(" reading sector ");
        put_dec(lba);
        puts("\r\n");
        halt();
    }
}

static void hdd_read(u32 lba, u32 count, void *dst)
{
    hdd_start(lba, count, dst);
    hdd_wait(lba);
}

/* The interval timer's 1 MHz counter, low 32 bits (reading COUNTER_LO latches HI). */
static u32 timer_now(void)
{
    u32 lo = REG(TIMER, 0x00);
    (void)REG(TIMER, 0x04);
    return lo;
}

/*
 * Waits up to micros for Esc on either PS/2 port (either may hold the keyboard, spec 11.1.4).
 * The ports are enabled for the wait (interrupts off), drained and put back as they were.
 */
static int wait_for_esc(u32 micros)
{
    u32 ctrl[2] = { REG(PS2, 0x08), REG(PS2, 0x18) };
    u32 last[2] = { 0, 0 };
    int esc = 0;
    REG(PS2, 0x08) = 2;
    REG(PS2, 0x18) = 2;
    u32 start = timer_now();
    while (!esc && timer_now() - start < micros) {
        for (int p = 0; p < 2; p++) {
            while (REG(PS2, 0x10 * p + 0x04) & 1) {
                u32 b = REG(PS2, 0x10 * p) & 0xFF;
                if (b == KEY_ESC && last[p] != 0xF0)
                    esc = 1;
                last[p] = b;
            }
        }
    }
    for (int p = 0; p < 2; p++) {
        while (REG(PS2, 0x10 * p + 0x04) & 1)
            (void)REG(PS2, 0x10 * p);
        REG(PS2, 0x10 * p + 0x08) = ctrl[p];
    }
    return esc;
}

/* ---- boot area ---- */

static int header_ok(const u8 *h)
{
    if (h[0] != 'F' || h[1] != 'L' || h[2] != 'B' || h[3] != 'T' || be32(h + 4) != 1)
        return 0;
    static const u8 zero[4];
    u32 c = crc_update(0, h, 12);
    c = crc_update(c, zero, 4);
    c = crc_update(c, h + 16, HEADER_SIZE - 16);
    return crc_finish(c, HEADER_SIZE) == be32(h + 12) && be32(h + 8) <= 1;
}

static int has_magic(const u8 *h)
{
    return h[0] == 'F' && h[1] == 'L' && h[2] == 'B' && h[3] == 'T';
}

static const u8 *slot_record(const u8 *h, int slot)
{
    return h + SLOT_RECORD + SLOT_SIZE * (u32)slot;
}

/* ---- the kernel ---- */

/* Reads the ELF headers in f; returns 0, or why the kernel cannot be loaded. */
static const char *check_elf(const u8 *f, u32 length)
{
    if (length < 52 || f[0] != 0x7F || f[1] != 'E' || f[2] != 'L' || f[3] != 'F')
        return "the kernel is not an ELF file";
    if (f[4] != 1 || f[5] != 2)
        return "the kernel is not a 32-bit big-endian ELF file";
    if (be16(f + 18) != 4)
        return "the kernel is not a 68k ELF file";
    if (be16(f + 16) != 2)
        return "the kernel is not an executable ELF file";
    u32 phoff = be32(f + 28), phentsize = be16(f + 42), phnum = be16(f + 44);
    u32 head = length < sizeof buf ? length : sizeof buf;
    if (phentsize < 32 || phnum > 64 || phoff > head || phentsize * phnum > head - phoff)
        return "bad ELF program header table (it must lie in the first 64 KiB)";
    nsegments = 0;
    for (u32 i = 0; i < phnum; i++) {
        const u8 *ph = f + phoff + i * phentsize;
        if (be32(ph) != 1)
            continue;
        if (nsegments == MAX_SEGMENTS)
            return "the kernel has more than 16 loadable segments";
        struct segment *s = &segments[nsegments++];
        s->offset = be32(ph + 4);
        s->paddr = be32(ph + 12);
        s->filesz = be32(ph + 16);
        s->memsz = be32(ph + 20);
        if (s->filesz > s->memsz)
            return "an ELF segment is larger in the file than in memory";
        if (s->offset > length || s->filesz > length - s->offset)
            return "an ELF segment runs past the end of the kernel";
        if (s->paddr > LOAD_LIMIT || s->memsz > LOAD_LIMIT - s->paddr)
            return "the kernel does not fit below 15 MiB";
    }
    if (nsegments == 0)
        return "the kernel has no loadable segment";
    return 0;
}

/* Copies the part of the file at [pos, pos + n), now in src, that belongs to a segment. */
static void scatter(u32 pos, u32 n, const u8 *src)
{
    for (int i = 0; i < nsegments; i++) {
        const struct segment *s = &segments[i];
        u32 start = s->paddr < LOAD_FLOOR ? LOAD_FLOOR : s->paddr;
        u32 skip = start - s->paddr;
        if (skip >= s->filesz)
            continue;
        u32 lo = s->offset + skip, hi = s->offset + s->filesz;
        if (lo < pos)
            lo = pos;
        if (hi > pos + n)
            hi = pos + n;
        if (lo < hi)
            memcpy((void *)(s->paddr + (lo - s->offset)), src + (lo - pos), hi - lo);
    }
}

/*
 * Where the disk puts the file's bytes [pos, pos + n): straight into place when every sector of
 * the read lies inside one segment's loaded bytes, else the bounce buffer given.
 */
static u8 *chunk_target(u32 pos, u32 n, u8 *spare)
{
    u32 span = (n + SECTOR - 1) & ~(SECTOR - 1);
    for (int i = 0; i < nsegments; i++) {
        const struct segment *s = &segments[i];
        u32 start = s->paddr < LOAD_FLOOR ? LOAD_FLOOR : s->paddr;
        u32 skip = start - s->paddr;
        if (skip >= s->filesz)
            continue;
        u32 lo = s->offset + skip, hi = s->offset + s->filesz;
        if (pos >= lo && pos < hi && span <= hi - pos)
            return (u8 *)(s->paddr + (pos - s->offset));
    }
    return spare;
}

static u32 record_size(u32 data)
{
    return (4 + data + 3) & ~3u;
}

static u32 mix(u32 x)
{
    x ^= x >> 16;
    x *= 0x85EBCA6Bu;
    x ^= x >> 13;
    x *= 0xC2B2AE35u;
    x ^= x >> 16;
    return x;
}

static u8 *record(u8 *p, u32 tag, u32 size)
{
    memset(p, 0, size);
    put_be16(p, tag);
    put_be16(p + 2, size);
    return p + 4;
}

/*
 * The bootinfo at end, as the headless runner's KernelLoader writes it, plus BI_RNG_SEED before
 * the command line. Returns the first byte after it.
 */
static u32 write_bootinfo(u32 end, u32 ram, const char *cmdline, u32 cmdlen, u32 seed)
{
    u8 *p = (u8 *)end;
    put_be32(record(p, BI_MACHTYPE, 8), MACH_FLIPPIES);
    p += 8;
    put_be32(record(p, BI_CPUTYPE, 8), TYPE_68040);
    p += 8;
    put_be32(record(p, BI_FPUTYPE, 8), TYPE_68040);
    p += 8;
    put_be32(record(p, BI_MMUTYPE, 8), TYPE_68040);
    p += 8;
    u8 *d = record(p, BI_MEMCHUNK, 12);
    put_be32(d, 0);
    put_be32(d + 4, ram);
    p += 12;

    u32 size = record_size(2 + RNG_SEED_BYTES);
    d = record(p, BI_RNG_SEED, size);
    put_be16(d, RNG_SEED_BYTES);
    u32 x = seed;
    for (u32 i = 0; i < RNG_SEED_BYTES; i += 4) {
        x += 0x9E3779B9u;
        put_be32(d + 2 + i, mix(x));
    }
    p += size;

    size = record_size(cmdlen + 1);
    d = record(p, BI_COMMAND_LINE, size);
    memcpy(d, cmdline, cmdlen);
    p += size;

    record(p, BI_LAST, 4);
    p += 4;
    return (u32)p;
}

static u32 bootinfo_size(u32 cmdlen)
{
    return 8 * 4 + 12 + record_size(2 + RNG_SEED_BYTES) + record_size(cmdlen + 1) + 4;
}

/*
 * Loads the kernel of the header's slot and enters it. Returns, having said why, when the slot
 * cannot be booted; by then RAM below stage 2 may hold part of it, which the next try overwrites.
 */
static void boot_slot(const u8 *h, int slot, u32 area, u32 area_sectors, u32 ram)
{
    const u8 *rec = slot_record(h, slot);
    u32 offset = be32(rec), length = be32(rec + 4), want = be32(rec + 8);
    const char *stored = (const char *)(rec + SLOT_RECORD);
    u32 area_bytes = area_sectors * SECTOR;
    if (length == 0) {
        slot_error(slot, "empty");
        return;
    }
    if (offset % SECTOR || offset < 2 * HEADER_SIZE || offset > area_bytes || length > area_bytes - offset) {
        slot_error(slot, "the kernel lies outside the boot area");
        return;
    }
    u32 cmdlen = 0;
    while (cmdlen < CMDLINE_FIELD && stored[cmdlen])
        cmdlen++;
    if (cmdlen >= COMMAND_LINE_SIZE) {
        slot_error(slot, "the command line is longer than 255 bytes");
        return;
    }
    /* the slot's command line, then the marker naming the slot loaded when it fits */
    static char cmdline[COMMAND_LINE_SIZE];
    memcpy(cmdline, stored, cmdlen);
    if (cmdlen + sizeof SLOT_MARKER - 1 + (cmdlen != 0) < COMMAND_LINE_SIZE) {
        if (cmdlen)
            cmdline[cmdlen++] = ' ';
        memcpy(cmdline + cmdlen, SLOT_MARKER, sizeof SLOT_MARKER - 1);
        cmdlen += sizeof SLOT_MARKER - 1;
        cmdline[cmdlen - 1] = slot ? 'B' : 'A';
    }
    cmdline[cmdlen] = 0;

    /* the ELF headers, then every byte of the file once: CRC it and copy what is loaded */
    u32 lba = area + offset / SECTOR;
    u32 first = length < sizeof buf ? length : sizeof buf;
    hdd_read(lba, (first + SECTOR - 1) / SECTOR, buf);
    const char *bad = check_elf(buf, length);
    if (bad) {
        slot_error(slot, bad);
        return;
    }
    u32 entry = be32(buf + 24);
    u32 top = 0;
    for (int i = 0; i < nsegments; i++) {
        const struct segment *s = &segments[i];
        if (s->paddr + s->memsz > top)
            top = s->paddr + s->memsz;
    }
    u32 end = (top + PAGE - 1) & ~(PAGE - 1);
    if (end > LOAD_LIMIT || bootinfo_size(cmdlen) > LOAD_LIMIT - end) {
        slot_error(slot, "the kernel does not fit below 15 MiB");
        return;
    }

    puts("Loading slot ");
    put_slot(slot);
    puts(": ");
    put_dec(length);
    puts(" bytes\r\n");
    for (int i = 0; i < nsegments; i++) {
        const struct segment *s = &segments[i];
        u32 start = s->paddr < LOAD_FLOOR ? LOAD_FLOOR : s->paddr;
        u32 file_end = s->paddr + s->filesz;
        u32 zero = start > file_end ? start : file_end;
        u32 mem_end = s->paddr + s->memsz;
        if (zero < mem_end)
            memset((void *)zero, 0, mem_end - zero);
    }
    /*
     * Chunk 0 is in bounce[0]. While the CPU checks and places one chunk, the disk reads the
     * next one, into place when it can (chunk_target), else into the bounce buffer not in use.
     */
    u32 crc = 0, pos = 0, n = first;
    u8 *cur = bounce[0];
    int spare = 1;
    for (;;) {
        u32 next = pos + n, nn = 0;
        u8 *dst = 0;
        if (next < length) {
            nn = length - next < CHUNK ? length - next : CHUNK;
            dst = chunk_target(next, nn, bounce[spare]);
            hdd_start(lba + next / SECTOR, (nn + SECTOR - 1) / SECTOR, dst);
        }
        crc = crc_update(crc, cur, n);
        if (cur == bounce[0] || cur == bounce[1])
            scatter(pos, n, cur);
        if (!dst)
            break;
        hdd_wait(lba + next / SECTOR);
        if (dst == bounce[spare])
            spare ^= 1;
        cur = dst;
        pos = next;
        n = nn;
    }
    crc = crc_finish(crc, length);
    if (crc != want) {
        puts("flipboot: slot ");
        put_slot(slot);
        puts(": kernel CRC error (header ");
        put_hex(want);
        puts(", read ");
        put_hex(crc);
        puts(")\r\n");
        return;
    }

    u32 seed = mix(REG(RTC, 0x00) ^ 0x464C4254u);
    seed = mix(seed ^ REG(RTC, 0x04));
    seed = mix(seed ^ timer_now());
    seed = mix(seed ^ REG(TIMER, 0x04));
    seed = mix(seed ^ crc);
    u32 bootinfo_end = write_bootinfo(end, ram, cmdline, cmdlen, seed);

    puts("Command line: ");
    puts(cmdline);
    puts("\r\nStarting the kernel at ");
    put_hex(entry);
    puts(", bootinfo ");
    put_hex(end);
    puts("..");
    put_hex(bootinfo_end);
    puts("\r\n");
    enter_kernel(entry);
}

void flipboot_main(u32 ram, u32 capacity, void *print)
{
    print_fn = print;
    crc_init();

    /* partition 1: the boot area, MBR type 0xDA */
    hdd_read(0, 1, buf);
    if (buf[510] != 0x55 || buf[511] != 0xAA)
        fail("no partition table");
    const u8 *pe = buf + 446;
    if (pe[4] != 0xDA)
        fail("partition 1 is not a boot area (type 0xDA)");
    u32 area = le32(pe + 8), area_sectors = le32(pe + 12);
    if (area_sectors < 2 * HEADER_SIZE / SECTOR || area > capacity || area_sectors > capacity - area)
        fail("partition 1 does not fit the disk");

    hdd_read(area, 2 * HEADER_SIZE / SECTOR, header);
    const u8 *h;
    if (header_ok(header[0])) {
        h = header[0];
    } else if (header_ok(header[1])) {
        h = header[1];
        puts("flipboot: the boot area's first header is damaged, using its copy\r\n");
    } else if (!has_magic(header[0]) && !has_magic(header[1])) {
        fail("no boot area header in partition 1");
    } else {
        fail("boot area header CRC error");
    }

    int slot = (int)be32(h + 8);
    int other = slot ^ 1;
    u32 other_length = be32(slot_record(h, other) + 4);
    puts("flipboot: slot ");
    put_slot(slot);
    puts(" active, generation ");
    put_dec(be32(h + 16));
    puts("\r\n");
    if (other_length) {
        puts("Booting slot ");
        put_slot(slot);
        puts(" in 2 seconds; press Esc for slot ");
        put_slot(other);
        puts("\r\n");
        if (wait_for_esc(PROMPT_MICROS)) {
            slot = other;
            puts("Esc: booting slot ");
            put_slot(slot);
            puts("\r\n");
        }
    }

    boot_slot(h, slot, area, area_sectors, ram);
    if (be32(slot_record(h, slot ^ 1) + 4)) {
        puts("flipboot: trying slot ");
        put_slot(slot ^ 1);
        puts("\r\n");
        boot_slot(h, slot ^ 1, area, area_sectors, ram);
    }
    halt();
}
