/*
 * netinstall (spec 12.5): installs a disk image from the modem's catalogue onto the HDD.
 *
 * Screens (80x25 text, mirrored to COM1 for tests):
 *   1. the network: "no link" until the modem's link is up, then DHCP, the address, DNS.
 *   2. the catalogue from http://get.flip/index.txt: arrows and Enter.
 *   3. the HDD's capacity (IDENTIFY) against the image's size, then "Type YES to continue"
 *      (or WIPE: also zero the chunks the image leaves out, see below).
 *   4. progress: MB written of total, speed, time left.
 *   5. verify every chunk by reading it back, then "Done. Remove the floppy and press Enter to
 *      restart." (SysCon POWER 0x7777).
 *
 * The network: ARP, IPv4 (no fragments), UDP, a DHCP client, a DNS client, one TCP connection
 * with window scaling (RFC 7323) and HTTP/1.1 GET with Range, all over the network card with
 * checksum offload (TX_CSUM fills every checksum we send, RX_INFO checks the ones we receive).
 *
 * The image (.fdi, spec 12.4.4): the header and chunk table are fetched first with Range. The
 * data comes in one GET with Range from the first chunk not yet received, into a 1 MiB ring of
 * 16 chunk slots; each chunk's CRC32 is checked as it completes and the HDD writes it straight
 * from its slot (one command per chunk or two adjacent ones) while the network fills the others.
 * The TCP window is the ring's free room, so the modem sends only what fits. A dropped
 * connection, a timeout or a CRC mismatch aborts the connection and resumes with Range from the
 * first chunk not yet received whole and checked; three failures in a row stop. After the
 * download every chunk is read back and checked again; a chunk that fails is fetched again.
 *
 * Chunks absent from the .fdi are zeros in the image. By default they are not written: a new
 * HDD reads as zeros, and the image's file systems never read their free space. WIPE writes
 * zeros over them first, for an HDD that held something else (spec 12.4.4: zero absent chunks
 * only when the user asks for a full wipe).
 *
 * The program sleeps with STOP between events (NIC, HDD, keyboard, COM1 and a 20 ms timer).
 * Everything runs at task level; the interrupt handler only ends the STOP (start.S).
 */

typedef unsigned char u8;
typedef unsigned short u16;
typedef unsigned int u32;
typedef unsigned long long u64;

#define REG(base, off) (*(volatile u32 *)((base) + (off)))
#define REG8(base, off) (*(volatile u8 *)((base) + (off)))

/* ---- devices (spec 3, 10.8.1, 11.1.1) ---- */

#define SYSCON 0xFFF00000u
#define INTC 0xFFF01000u
#define COM1 0xFFF02000u
#define PS2 0xFFF04000u
#define HDD 0xFFF05000u
#define VGA 0xFFF06000u
#define TIMER 0xFFF07000u
#define RTC 0xFFF08000u
#define NIC 0xFFF0B000u
#define VRAM 0xFFE00000u

#define SYS_POWER 0x00
#define SYS_PRESENT 0x20
#define POWER_RESET 0x7777

#define INTC_ENABLE 0x04
#define LINE_COM1 1
#define LINE_PS2_0 3
#define LINE_PS2_1 4
#define LINE_HDD 5
#define LINE_TIMER 7
#define LINE_NIC 10

#define HDD_CMD 0x00
#define HDD_STATUS 0x04
#define HDD_LBA 0x08
#define HDD_COUNT 0x0C
#define HDD_DMA 0x10
#define HDD_CAPACITY 0x14
#define HDD_IRQ 0x18
#define HDD_ERROR 0x1C
#define HDD_READ 1
#define HDD_WRITE 2
#define HDD_IDENTIFY 4

#define NIC_CTRL 0x00
#define NIC_STATUS 0x04
#define NIC_MAC_LO 0x08
#define NIC_MAC_HI 0x0C
#define NIC_TX_ADDR 0x10
#define NIC_TX_LEN 0x14
#define NIC_TX_CMD 0x18
#define NIC_RX_LEN 0x20
#define NIC_RX_ADDR 0x24
#define NIC_RX_CMD 0x28
#define NIC_RX_COUNT 0x2C
#define NIC_RX_INFO 0x38
#define CTRL_RX_ENABLE 1
#define CTRL_IRQ_RX 4
#define CTRL_IRQ_LINK 16
#define CTRL_TX_CSUM 32
#define ST_TX_DONE 2
#define ST_TX_ERROR 4
#define ST_RX_OVERFLOW 8
#define ST_LINK 16
#define ST_LINK_CHANGED 32
#define INFO_IP_OK 1
#define INFO_L4_OK 2

#define TIMER_LO 0x00
#define TIMER_HI 0x04
#define TIMER_PERIOD 0x08
#define TIMER_CTRL 0x0C
#define TIMER_STATUS 0x10
#define TICK_US 20000

#define VGA_MODE 0x00
#define VGA_CTRL 0x04
#define VGA_CURSOR 0x0C

/* ---- layout ---- */

#define SECTOR 512u
#define CHUNK 65536u
#define CHUNK_SECTORS 128u
#define SLOTS 16u
#define MAX_ENTRIES 16384u /* a 1 GiB image: the largest HDD */
#define FDI_HEADER 88u
#define MAX_ITEMS 64
#define INDEX_MAX 16384u
#define FAILURES_TO_STOP 3

#define ROWS 25
/* text buffers: room for a 63-byte title plus the rest of a line; rows show the first 80 columns */
#define LINE 160
#define COLS 80
#define A_NORMAL 0x07
#define A_BRIGHT 0x0F
#define A_TITLE 0x1F
#define A_BAR 0x70
#define A_SELECT 0x70
#define A_ERROR 0x0C
#define A_GOOD 0x0A

void netinstall_main(u32 ram, u32 boot_device, void *print) __attribute__((noreturn));
extern void irq_entry(void);
extern void sleep_until_interrupt(void);
extern u32 crc32_words(u32 c, const u32 *p, u32 words, const u32 *ta, const u32 *tb);
extern void copy_fast(void *dst, const void *src, u32 n);

/* ---- buffers (bss) ---- */

static u8 ring[SLOTS * CHUNK] __attribute__((aligned(16)));
/* read-back buffers for the verify pass; also 128 KiB of zeros for WIPE before that */
static u8 vbuf[2 * CHUNK] __attribute__((aligned(16)));
static u32 crc_ta[65536], crc_tb[65536], crc_t8[256];
/* the .fdi chunk table: per entry, the chunk's index then its CRC32, big-endian as the file */
static u32 table[2 * MAX_ENTRIES];
static char index_text[INDEX_MAX + 1];
static u8 rxf[1536] __attribute__((aligned(4)));
static u8 txf[1536] __attribute__((aligned(4)));

/* ---- small library ---- */

void *memcpy(void *dst, const void *src, unsigned long n)
{
    copy_fast(dst, src, n);
    return dst;
}

void *memset(void *dst, int c, unsigned long n)
{
    u8 *d = dst;
    if (c == 0 && ((u32)d & 3) == 0) {
        u32 *w = (u32 *)d;
        for (; n >= 16; n -= 16) {
            w[0] = 0;
            w[1] = 0;
            w[2] = 0;
            w[3] = 0;
            w += 4;
        }
        d = (u8 *)w;
    }
    while (n--)
        *d++ = (u8)c;
    return dst;
}

static u32 str_len(const char *s)
{
    u32 n = 0;
    while (s[n])
        n++;
    return n;
}

static u32 rd16(const u8 *p)
{
    return (u32)p[0] << 8 | p[1];
}

static u32 rd32(const u8 *p)
{
    return (u32)p[0] << 24 | (u32)p[1] << 16 | (u32)p[2] << 8 | p[3];
}

static void wr16(u8 *p, u32 v)
{
    p[0] = (u8)(v >> 8);
    p[1] = (u8)v;
}

static void wr32(u8 *p, u32 v)
{
    p[0] = (u8)(v >> 24);
    p[1] = (u8)(v >> 16);
    p[2] = (u8)(v >> 8);
    p[3] = (u8)v;
}

static int lower(int c)
{
    return c >= 'A' && c <= 'Z' ? c + 32 : c;
}

/* Text builders: each appends to d and returns the new end (callers keep room). */
static char *put_s(char *d, const char *s)
{
    while (*s)
        *d++ = *s++;
    *d = 0;
    return d;
}

static char *put_u(char *d, u32 v)
{
    char t[11];
    int i = 0;
    do {
        t[i++] = (char)('0' + v % 10);
        v /= 10;
    } while (v);
    while (i)
        *d++ = t[--i];
    *d = 0;
    return d;
}

/* bytes as MB (MiB) with one decimal */
static char *put_mb(char *d, u64 bytes)
{
    u32 tenths = (u32)((bytes * 10 + 524288) >> 20);
    d = put_u(d, tenths / 10);
    *d++ = '.';
    *d++ = (char)('0' + tenths % 10);
    *d = 0;
    return d;
}

static char *put_ip(char *d, u32 ip)
{
    for (int i = 3; i >= 0; i--) {
        d = put_u(d, (ip >> (8 * i)) & 0xFF);
        if (i)
            *d++ = '.';
    }
    *d = 0;
    return d;
}

static char *put_time(char *d, u32 seconds)
{
    d = put_u(d, seconds / 60);
    *d++ = ':';
    *d++ = (char)('0' + seconds % 60 / 10);
    *d++ = (char)('0' + seconds % 10);
    *d = 0;
    return d;
}

/* ---- zlib's CRC32, the state byte-swapped so a big-endian word load needs no swap ---- */

static u32 bswap(u32 v)
{
    return v >> 24 | (v >> 8 & 0xFF00) | (v << 8 & 0xFF0000) | v << 24;
}

/*
 * Reflected CRC32 c over little-endian x = c ^ word: c' = T3[x & 0xFF] ^ T2[x >> 8 & 0xFF] ^
 * T1[x >> 16 & 0xFF] ^ T0[x >> 24], Tk being "the byte, then k zero bytes". Kept swapped
 * (s = bswap(c)), a big-endian load w gives bswap(x) = s ^ w, whose high half indexes ta (the
 * T3/T2 pair) and low half tb (the T1/T0 pair), each table entry stored swapped.
 */
static void crc_init(void)
{
    static u32 t[4][256];
    for (u32 i = 0; i < 256; i++) {
        u32 c = i;
        for (int k = 0; k < 8; k++)
            c = c & 1 ? 0xEDB88320u ^ c >> 1 : c >> 1;
        t[0][i] = c;
    }
    for (int k = 1; k < 4; k++)
        for (u32 i = 0; i < 256; i++)
            t[k][i] = t[k - 1][i] >> 8 ^ t[0][t[k - 1][i] & 0xFF];
    for (u32 i = 0; i < 256; i++)
        crc_t8[i] = bswap(t[0][i]);
    for (u32 h = 0; h < 65536; h++) {
        u32 b0 = h >> 8, b1 = h & 0xFF;
        crc_ta[h] = bswap(t[3][b0] ^ t[2][b1]);
        crc_tb[h] = bswap(t[1][b0] ^ t[0][b1]);
    }
}

/* crc32(0, p, n) is zlib's crc32 of p; it can be continued like zlib's. */
static u32 crc32(u32 crc, const u8 *p, u32 n)
{
    u32 s = bswap(~crc);
    while (n && ((u32)p & 3)) {
        s = crc_t8[(s >> 24) ^ *p++] ^ s << 8;
        n--;
    }
    s = crc32_words(s, (const u32 *)p, n >> 2, crc_ta, crc_tb);
    p += n & ~3u;
    for (n &= 3; n; n--)
        s = crc_t8[(s >> 24) ^ *p++] ^ s << 8;
    return ~bswap(s);
}

/* ---- time ---- */

static u32 now_ms(void)
{
    u32 lo = REG(TIMER, TIMER_LO);
    u32 hi = REG(TIMER, TIMER_HI);
    return (u32)((((u64)hi << 32) | lo) / 1000);
}

static u32 random_state;

static u32 random32(void)
{
    u32 x = random_state += 0x9E3779B9u;
    x ^= x >> 16;
    x *= 0x85EBCA6Bu;
    x ^= x >> 13;
    x *= 0xC2B2AE35u;
    return x ^ x >> 16;
}

/* ---- COM1: a ring the THR-empty interrupt drains ---- */

#define COM_RING 16384u
static char com_ring[COM_RING];
static u32 com_head, com_tail;

static void com_putc(char c)
{
    if (com_head - com_tail < COM_RING)
        com_ring[com_head++ % COM_RING] = c;
}

static void com_puts(const char *s)
{
    while (*s)
        com_putc(*s++);
}

static void com_pump(void)
{
    if (REG8(COM1, 5) & 0x20) {
        for (int i = 0; i < 16 && com_tail != com_head; i++)
            REG8(COM1, 0) = (u8)com_ring[com_tail++ % COM_RING];
    }
    REG8(COM1, 1) = com_tail != com_head ? 2 : 0;
}

/* ---- VGA text ---- */

static volatile u16 *const screen = (volatile u16 *)VRAM;

static void row_text(int row, u8 attr, const char *s)
{
    int c = 0;
    for (; s[c] && c < COLS; c++)
        screen[row * COLS + c] = (u16)((u8)s[c] << 8 | attr);
    for (; c < COLS; c++)
        screen[row * COLS + c] = (u16)(' ' << 8 | attr);
}

static void cursor(int row, int col, int on)
{
    REG(VGA, VGA_CURSOR) = (u32)(row << 8 | col);
    REG(VGA, VGA_CTRL) = on ? 0x0D : 0x09;
}

static const char *bar_text = "";

static void clear_screen(const char *bar)
{
    for (int r = 1; r < ROWS - 1; r++)
        row_text(r, A_NORMAL, "");
    row_text(0, A_TITLE, " Create Flippies netinstall");
    bar_text = bar;
    row_text(ROWS - 1, A_BAR, bar);
    cursor(0, 0, 0);
}

/* Sends the whole screen to COM1, one line per row with trailing spaces dropped, then "--". */
static void mirror_screen(void)
{
    for (int r = 0; r < ROWS; r++) {
        char line[LINE];
        int n = 0;
        for (int c = 0; c < COLS; c++) {
            char ch = (char)(screen[r * COLS + c] >> 8);
            line[c] = ch;
            if (ch != ' ')
                n = c + 1;
        }
        line[n] = 0;
        com_puts(line);
        com_puts("\r\n");
    }
    com_puts("--\r\n");
}

/* ---- PS/2 keyboard (scancode set 2) on either port ---- */

#define K_ENTER 0x5A
#define K_ESC 0x76
#define K_BACKSPACE 0x66
#define K_UP 0x175
#define K_DOWN 0x172
#define K_PGUP 0x17D
#define K_PGDN 0x17A
#define K_HOME 0x16C
#define K_END 0x169

static u16 keys[32];
static u32 key_head, key_tail;
static int kb_e0[2], kb_f0[2];

static int key_char(int key)
{
    static const u8 codes[26] = { 0x1C, 0x32, 0x21, 0x23, 0x24, 0x2B, 0x34, 0x33, 0x43, 0x3B, 0x42, 0x4B, 0x3A,
                                  0x31, 0x44, 0x4D, 0x15, 0x2D, 0x1B, 0x2C, 0x3C, 0x2A, 0x1D, 0x22, 0x35, 0x1A };
    for (int i = 0; i < 26; i++)
        if (codes[i] == key)
            return 'A' + i;
    return 0;
}

static void kb_poll(void)
{
    for (int p = 0; p < 2; p++) {
        while (REG(PS2, 0x10 * p + 0x04) & 1) {
            u32 b = REG(PS2, 0x10 * p) & 0xFF;
            if (b == 0xE0) {
                kb_e0[p] = 1;
            } else if (b == 0xF0) {
                kb_f0[p] = 1;
            } else {
                int key = (kb_e0[p] ? 0x100 : 0) | (int)b;
                if (key == 0x15A)
                    key = K_ENTER;
                if (!kb_f0[p] && b != 0xAA && b != 0xFA && b != 0xFE && b != 0 && key_head - key_tail < 32)
                    keys[key_head++ % 32] = (u16)key;
                kb_e0[p] = kb_f0[p] = 0;
            }
        }
    }
}

/* ---- the event loop ---- */

static void net_poll(void);
static void hdd_poll(void);
static void write_next(void);
static void net_timers(void);
static int link_up;

static void poll(void)
{
    if (REG(TIMER, TIMER_STATUS) & 1)
        REG(TIMER, TIMER_STATUS) = 1;
    kb_poll();
    hdd_poll();
    write_next();
    net_poll();
    net_timers();
    com_pump();
}

/*
 * Sleeps until an interrupt (unless a key already waits), then handles what came. Callers check
 * their condition before each call; every device line is cleared or drained by poll, so the
 * STOP ends at once when something is still pending.
 */
static void idle(void)
{
    if (key_head == key_tail)
        sleep_until_interrupt();
    poll();
}

static int get_key(void)
{
    return key_head == key_tail ? 0 : keys[key_tail++ % 32];
}

static int wait_key(void)
{
    int k;
    while (!(k = get_key()))
        idle();
    return k;
}

static void pause_ms(u32 ms)
{
    u32 start = now_ms();
    while (now_ms() - start < ms)
        idle();
}

static void __attribute__((noreturn)) restart(void)
{
    while (com_tail != com_head)
        idle();
    pause_ms(50);
    REG(SYSCON, SYS_POWER) = POWER_RESET;
    for (;;)
        sleep_until_interrupt();
}

static void __attribute__((noreturn)) stop_with(const char *what, const char *why)
{
    clear_screen(" Enter: restart");
    row_text(2, A_ERROR, "Install failed.");
    row_text(4, A_NORMAL, what);
    row_text(5, A_NORMAL, why);
    row_text(7, A_NORMAL, "Press Enter to restart.");
    mirror_screen();
    while (wait_key() != K_ENTER)
        ;
    restart();
}

/* ---- HDD: one command at a time, interrupt driven ---- */

enum { JOB_NONE, JOB_IDENTIFY, JOB_WRITE, JOB_ZERO, JOB_READ };
static int hdd_job;
static u32 hdd_job_arg, hdd_job_count;
static u32 hdd_error;
static void hdd_done(int job, u32 arg, u32 count);

static void hdd_start(int cmd, u32 lba, u32 count, const void *addr, int job, u32 arg, u32 n)
{
    hdd_job = job;
    hdd_job_arg = arg;
    hdd_job_count = n;
    REG(HDD, HDD_STATUS) = 6;
    REG(HDD, HDD_LBA) = lba;
    REG(HDD, HDD_COUNT) = count;
    REG(HDD, HDD_DMA) = (u32)addr;
    REG(HDD, HDD_CMD) = (u32)cmd;
}

static void hdd_poll(void)
{
    if (hdd_job == JOB_NONE)
        return;
    u32 st = REG(HDD, HDD_STATUS);
    if (!(st & 6))
        return;
    REG(HDD, HDD_STATUS) = 6;
    int job = hdd_job;
    hdd_job = JOB_NONE;
    if (st & 4) {
        hdd_error = REG(HDD, HDD_ERROR) | 0x100;
        return;
    }
    hdd_done(job, hdd_job_arg, hdd_job_count);
}

static void check_hdd(void)
{
    if (hdd_error) {
        char why[80];
        char *d = put_s(why, "The HDD reported error ");
        d = put_u(d, hdd_error & 0xFF);
        put_s(d, ".");
        stop_with("Writing to the HDD failed.", why);
    }
}

/* ---- network: Ethernet, ARP, IPv4, UDP ---- */

#define ETH_ARP 0x0806
#define ETH_IP 0x0800
#define P_TCP 6
#define P_UDP 17
#define BROADCAST 0xFFFFFFFFu

static u8 my_mac[6];
static u32 my_ip, netmask, gateway, dns_server;
static u16 ip_id;

struct arp_entry {
    u32 ip;
    u8 mac[6];
};
static struct arp_entry arp_cache[4];
static int arp_next;

static const u8 *arp_lookup(u32 ip)
{
    for (int i = 0; i < 4; i++)
        if (arp_cache[i].ip == ip && ip)
            return arp_cache[i].mac;
    return 0;
}

static void arp_learn(u32 ip, const u8 *mac)
{
    struct arp_entry *e = 0;
    for (int i = 0; i < 4; i++)
        if (arp_cache[i].ip == ip)
            e = &arp_cache[i];
    if (!e)
        e = &arp_cache[arp_next++ % 4];
    e->ip = ip;
    memcpy(e->mac, mac, 6);
}

static void transmit(u32 len)
{
    if (len < 60) {
        memset(txf + len, 0, 60 - len);
        len = 60;
    }
    REG(NIC, NIC_TX_ADDR) = (u32)txf;
    REG(NIC, NIC_TX_LEN) = len;
    REG(NIC, NIC_TX_CMD) = 1;
    REG(NIC, NIC_STATUS) = ST_TX_DONE | ST_TX_ERROR;
}

static void eth_header(const u8 *dst, u32 type)
{
    if (dst)
        memcpy(txf, dst, 6);
    else
        memset(txf, 0xFF, 6);
    memcpy(txf + 6, my_mac, 6);
    wr16(txf + 12, type);
}

static void arp_send(int op, const u8 *dst_mac, u32 dst_ip)
{
    eth_header(op == 2 ? dst_mac : 0, ETH_ARP);
    u8 *a = txf + 14;
    wr16(a, 1);
    wr16(a + 2, ETH_IP);
    a[4] = 6;
    a[5] = 4;
    wr16(a + 6, (u32)op);
    memcpy(a + 8, my_mac, 6);
    wr32(a + 14, my_ip);
    if (op == 2)
        memcpy(a + 18, dst_mac, 6);
    else
        memset(a + 18, 0, 6);
    wr32(a + 24, dst_ip);
    transmit(14 + 28);
}

/* The next hop's MAC: the destination on our subnet, else the gateway; 0 when not known yet. */
static u32 next_hop(u32 ip)
{
    return (ip & netmask) == (my_ip & netmask) ? ip : gateway;
}

/* An IPv4 header at txf + 14 for len bytes of payload; the card fills the checksum. Returns the payload. */
static u8 *ip_header(u32 dst, int proto, u32 len)
{
    u8 *h = txf + 14;
    h[0] = 0x45;
    h[1] = 0;
    wr16(h + 2, 20 + len);
    wr16(h + 4, ip_id++);
    wr16(h + 6, 0x4000);
    h[8] = 64;
    h[9] = (u8)proto;
    wr16(h + 10, 0);
    wr32(h + 12, my_ip);
    wr32(h + 16, dst);
    return h + 20;
}

static u8 *udp_header(u32 dst, u32 sport, u32 dport, u32 len)
{
    u8 *u = ip_header(dst, P_UDP, 8 + len);
    wr16(u, sport);
    wr16(u + 2, dport);
    wr16(u + 4, 8 + len);
    wr16(u + 6, 0);
    return u + 8;
}

/* ---- DHCP client ---- */

#define DHCP_CLIENT 68
#define DHCP_SERVER 67
static int dhcp_state; /* 0 idle, 1 discovering, 2 requesting, 3 bound */
static u32 dhcp_xid, dhcp_offer, dhcp_server_id, dhcp_sent_ms;

static void dhcp_send(int type)
{
    eth_header(0, ETH_IP);
    u32 saved = my_ip;
    my_ip = 0;
    u8 *b = udp_header(BROADCAST, DHCP_CLIENT, DHCP_SERVER, 300);
    my_ip = saved;
    memset(b, 0, 300);
    b[0] = 1;
    b[1] = 1;
    b[2] = 6;
    wr32(b + 4, dhcp_xid);
    wr16(b + 10, 0x8000); /* answer by broadcast: we have no address yet */
    memcpy(b + 28, my_mac, 6);
    wr32(b + 236, 0x63825363);
    u8 *o = b + 240;
    *o++ = 53;
    *o++ = 1;
    *o++ = (u8)type;
    if (type == 3) {
        *o++ = 50;
        *o++ = 4;
        wr32(o, dhcp_offer);
        o += 4;
        *o++ = 54;
        *o++ = 4;
        wr32(o, dhcp_server_id);
        o += 4;
    }
    *o++ = 55;
    *o++ = 4;
    *o++ = 1;
    *o++ = 3;
    *o++ = 6;
    *o++ = 51;
    *o = 255;
    transmit(14 + 20 + 8 + 300);
    dhcp_sent_ms = now_ms();
}

static void dhcp_start(void)
{
    dhcp_xid = random32();
    dhcp_state = 1;
    dhcp_send(1);
}

static const u8 *dhcp_option(const u8 *b, u32 len, int code, u32 want)
{
    for (u32 i = 240; i < len;) {
        int c = b[i];
        if (c == 255)
            break;
        if (c == 0) {
            i++;
            continue;
        }
        if (i + 1 >= len || i + 2 + b[i + 1] > len)
            break;
        if (c == code && b[i + 1] >= want)
            return b + i + 2;
        i += 2 + b[i + 1];
    }
    return 0;
}

static void dhcp_input(const u8 *b, u32 len)
{
    if (len < 244 || b[0] != 2 || rd32(b + 4) != dhcp_xid || rd32(b + 236) != 0x63825363
        || b[28] != my_mac[0] || b[33] != my_mac[5])
        return;
    const u8 *t = dhcp_option(b, len, 53, 1), *sid = dhcp_option(b, len, 54, 4);
    if (!t)
        return;
    if (dhcp_state == 1 && *t == 2 && sid) {
        dhcp_offer = rd32(b + 16);
        dhcp_server_id = rd32(sid);
        dhcp_state = 2;
        dhcp_send(3);
    } else if (dhcp_state == 2 && *t == 5) {
        const u8 *mask = dhcp_option(b, len, 1, 4), *router = dhcp_option(b, len, 3, 4);
        const u8 *dns = dhcp_option(b, len, 6, 4);
        my_ip = rd32(b + 16);
        netmask = mask ? rd32(mask) : 0xFFFFFF00u;
        gateway = router ? rd32(router) : dhcp_server_id;
        dns_server = dns ? rd32(dns) : gateway;
        dhcp_state = 3;
    } else if (dhcp_state == 2 && *t == 6) {
        dhcp_start();
    }
}

/* ---- DNS client ---- */

#define DNS_PORT 53
static u16 dns_id, dns_port;
static int dns_state; /* 0 idle, 1 asking, 2 answered, 3 no such name */
static u32 dns_answer, dns_sent_ms;
static const char *dns_name;

static void dns_send(void)
{
    const u8 *mac = arp_lookup(next_hop(dns_server));
    dns_sent_ms = now_ms();
    if (!mac) {
        arp_send(1, 0, next_hop(dns_server));
        return;
    }
    u32 n = str_len(dns_name);
    u32 len = 12 + n + 2 + 4;
    eth_header(mac, ETH_IP);
    u8 *q = udp_header(dns_server, dns_port, DNS_PORT, len);
    memset(q, 0, 12);
    wr16(q, dns_id);
    wr16(q + 2, 0x0100);
    wr16(q + 4, 1);
    u8 *p = q + 12;
    const char *s = dns_name;
    while (*s) {
        u8 *lenp = p++;
        u8 l = 0;
        while (*s && *s != '.') {
            *p++ = (u8)*s++;
            l++;
        }
        *lenp = l;
        if (*s == '.')
            s++;
    }
    *p++ = 0;
    wr16(p, 1);
    wr16(p + 2, 1);
    transmit(14 + 20 + 8 + len);
}

static u32 dns_skip_name(const u8 *b, u32 len, u32 i)
{
    while (i < len) {
        u32 l = b[i];
        if (l == 0)
            return i + 1;
        if ((l & 0xC0) == 0xC0)
            return i + 2;
        i += 1 + l;
    }
    return len + 1;
}

static void dns_input(const u8 *b, u32 len)
{
    if (dns_state != 1 || len < 12 || rd16(b) != dns_id || !(b[2] & 0x80))
        return;
    if ((b[3] & 0x0F) == 3) {
        dns_state = 3;
        return;
    }
    u32 qd = rd16(b + 4), an = rd16(b + 6), i = 12;
    for (u32 k = 0; k < qd; k++)
        i = dns_skip_name(b, len, i) + 4;
    for (u32 k = 0; k < an && i < len; k++) {
        i = dns_skip_name(b, len, i);
        if (i + 10 > len)
            break;
        u32 type = rd16(b + i), rdlen = rd16(b + i + 8);
        if (type == 1 && rdlen == 4 && i + 14 <= len) {
            dns_answer = rd32(b + i + 10);
            dns_state = 2;
            return;
        }
        i += 10 + rdlen;
    }
    dns_state = 3;
}

/* ---- TCP: one connection, client side ---- */

#define TCP_FIN 1
#define TCP_SYN 2
#define TCP_RST 4
#define TCP_PSH 8
#define TCP_ACK 16
#define MSS 1460
/* our window scale: the window field counts 16-byte units, so the 1 MiB ring fits (spec 12.5) */
#define WSCALE 4
#define RTO_MS 1000
#define SYN_TRIES 5
#define DATA_TRIES 6

enum { T_CLOSED, T_SYN_SENT, T_OPEN };
static struct {
    int state;
    u32 ip;
    u16 lport;
    u32 iss, snd_una, snd_nxt, rcv_nxt;
    u8 sbuf[1024];
    u32 slen;
    u32 rtx_ms;
    int tries;
    int ack_due;
    u32 adv_room;
} tcp;
static u16 next_port;

static u32 rx_room(void);
static u32 app_data(const u8 *p, u32 n);
static void app_closed(const char *why);
static void app_connected(void);

/* One segment from lport to ip:80; with the MSS and window scale options when it is a SYN. */
static void tcp_raw(u32 ip, u32 lport, int flags, u32 seq, u32 ack, u32 window, const u8 *data, u32 len)
{
    const u8 *mac = arp_lookup(next_hop(ip));
    if (!mac)
        return;
    u32 opt = flags & TCP_SYN ? 8 : 0;
    eth_header(mac, ETH_IP);
    u8 *t = ip_header(ip, P_TCP, 20 + opt + len);
    wr16(t, lport);
    wr16(t + 2, 80);
    wr32(t + 4, seq);
    wr32(t + 8, flags & TCP_ACK ? ack : 0);
    t[12] = (u8)((20 + opt) / 4 << 4);
    t[13] = (u8)flags;
    wr16(t + 14, window);
    wr32(t + 16, 0);
    if (opt) {
        t[20] = 2;
        t[21] = 4;
        wr16(t + 22, MSS);
        t[24] = 1;
        t[25] = 3;
        t[26] = 3;
        t[27] = WSCALE;
    }
    if (len)
        memcpy(t + 20 + opt, data, len);
    transmit(14 + 20 + 20 + opt + len);
}

/* A segment of our connection, advertising the room the application has. */
static void tcp_segment(int flags, u32 seq, const u8 *data, u32 len)
{
    u32 window = 65535; /* never scaled in a SYN (RFC 7323 2.2) */
    if (!(flags & TCP_SYN)) {
        tcp.adv_room = rx_room();
        window = tcp.adv_room >> WSCALE;
        if (window > 65535)
            window = 65535;
    }
    tcp_raw(tcp.ip, tcp.lport, flags, seq, tcp.rcv_nxt, window, data, len);
    if (flags & TCP_ACK)
        tcp.ack_due = 0;
}

static void tcp_connect(u32 ip)
{
    tcp.state = T_SYN_SENT;
    tcp.ip = ip;
    tcp.lport = next_port;
    next_port = (u16)(next_port == 65535 ? 49152 : next_port + 1);
    tcp.iss = random32();
    tcp.snd_una = tcp.iss;
    tcp.snd_nxt = tcp.iss + 1;
    tcp.slen = 0;
    tcp.tries = 1;
    tcp.rtx_ms = now_ms();
    tcp_segment(TCP_SYN, tcp.iss, 0, 0);
}

/* Drops the connection with a RST (the modem forgets it at once). */
static void tcp_abort(void)
{
    if (tcp.state == T_OPEN)
        tcp_segment(TCP_RST | TCP_ACK, tcp.snd_nxt, 0, 0);
    tcp.state = T_CLOSED;
}

/* Sends a request on the open connection; it waits in sbuf until acknowledged. */
static void tcp_write(const char *s, u32 n)
{
    memcpy(tcp.sbuf, s, n);
    tcp.slen = n;
    tcp.snd_una = tcp.snd_nxt;
    tcp.snd_nxt += n;
    tcp.tries = 1;
    tcp.rtx_ms = now_ms();
    tcp_segment(TCP_ACK | TCP_PSH, tcp.snd_una, tcp.sbuf, n);
}

static void tcp_input(const u8 *ip, u32 ihl, u32 len)
{
    const u8 *t = ip + ihl;
    if (len < 20)
        return;
    u32 doff = (u32)(t[12] >> 4) * 4;
    if (doff < 20 || doff > len)
        return;
    u32 sport = rd16(t), dport = rd16(t + 2), seq = rd32(t + 4), ack = rd32(t + 8);
    int flags = t[13];
    if (tcp.state == T_CLOSED || sport != 80 || dport != tcp.lport || rd32(ip + 12) != tcp.ip) {
        /* a segment of a connection we gave up: a RST makes the server forget it (RFC 9293 3.10.7.1) */
        if (!(flags & TCP_RST) && sport == 80) {
            if (flags & TCP_ACK)
                tcp_raw(rd32(ip + 12), dport, TCP_RST, ack, 0, 0, 0, 0);
            else
                tcp_raw(rd32(ip + 12), dport, TCP_RST | TCP_ACK, 0,
                    seq + (len - doff) + (flags & TCP_SYN ? 1 : 0) + (flags & TCP_FIN ? 1 : 0), 0, 0, 0);
        }
        return;
    }
    if (tcp.state == T_SYN_SENT) {
        if ((flags & TCP_ACK) && ack != tcp.iss + 1)
            return;
        if (flags & TCP_RST) {
            if (flags & TCP_ACK) {
                tcp.state = T_CLOSED;
                app_closed("The server refused the connection.");
            }
            return;
        }
        if ((flags & (TCP_SYN | TCP_ACK)) != (TCP_SYN | TCP_ACK))
            return;
        tcp.rcv_nxt = seq + 1;
        tcp.snd_una = tcp.snd_nxt = tcp.iss + 1;
        tcp.state = T_OPEN;
        tcp_segment(TCP_ACK, tcp.snd_nxt, 0, 0);
        app_connected();
        return;
    }
    /* open */
    if (flags & TCP_RST) {
        if (seq - tcp.rcv_nxt < 65536u << WSCALE) {
            tcp.state = T_CLOSED;
            app_closed("The server reset the connection.");
        }
        return;
    }
    if (flags & TCP_SYN) {
        tcp.ack_due = 1;
        return;
    }
    if (!(flags & TCP_ACK))
        return;
    if (ack - tcp.snd_una <= tcp.snd_nxt - tcp.snd_una && ack != tcp.snd_una) {
        u32 done = ack - tcp.snd_una;
        if (done >= tcp.slen) {
            tcp.slen = 0;
        } else {
            for (u32 i = done; i < tcp.slen; i++)
                tcp.sbuf[i - done] = tcp.sbuf[i];
            tcp.slen -= done;
        }
        tcp.snd_una = ack;
        tcp.tries = 1;
        tcp.rtx_ms = now_ms();
    }
    u32 n = len - doff;
    const u8 *data = t + doff;
    if (n || (flags & TCP_FIN)) {
        u32 skip = tcp.rcv_nxt - seq;
        tcp.ack_due = 1;
        if ((int)skip < 0)
            return; /* out of order: dropped, the duplicate ACK asks for the gap */
        u32 taken = 0;
        if (skip < n) {
            taken = app_data(data + skip, n - skip);
            tcp.rcv_nxt += taken;
            if (tcp.state != T_OPEN)
                return; /* the application gave up on the connection */
        }
        if ((flags & TCP_FIN) && skip + taken >= n && seq + n == tcp.rcv_nxt) {
            tcp.rcv_nxt++;
            tcp_segment(TCP_FIN | TCP_ACK, tcp.snd_nxt, 0, 0);
            tcp.state = T_CLOSED;
            app_closed("The server closed the connection.");
        }
    }
}

static void tcp_timers(u32 now)
{
    if (tcp.state == T_SYN_SENT && now - tcp.rtx_ms >= RTO_MS) {
        if (++tcp.tries > SYN_TRIES) {
            tcp.state = T_CLOSED;
            app_closed("The server does not answer.");
            return;
        }
        tcp.rtx_ms = now;
        tcp_segment(TCP_SYN, tcp.iss, 0, 0);
    } else if (tcp.state == T_OPEN && tcp.slen && now - tcp.rtx_ms >= RTO_MS) {
        if (++tcp.tries > DATA_TRIES) {
            tcp_abort();
            app_closed("The server stopped answering.");
            return;
        }
        tcp.rtx_ms = now;
        tcp_segment(TCP_ACK | TCP_PSH, tcp.snd_una, tcp.sbuf, tcp.slen);
    }
}

/* After a batch of frames, and when the ring frees room: one ACK, or a window update. */
static void tcp_output(void)
{
    if (tcp.state != T_OPEN)
        return;
    if (tcp.ack_due) {
        tcp_segment(TCP_ACK, tcp.snd_nxt, 0, 0);
        return;
    }
    u32 room = rx_room();
    if (room > tcp.adv_room && (room - tcp.adv_room >= 16384 || tcp.adv_room < 4096))
        tcp_segment(TCP_ACK, tcp.snd_nxt, 0, 0);
}

/* ---- frames in ---- */

static u32 frames_in, frames_bad;

static void frame_input(u32 len, u32 info)
{
    if (len < 14)
        return;
    u32 type = rd16(rxf + 12);
    if (type == ETH_ARP && len >= 42) {
        const u8 *a = rxf + 14;
        if (rd16(a) != 1 || rd16(a + 2) != ETH_IP || a[4] != 6 || a[5] != 4)
            return;
        u32 sender = rd32(a + 14), target = rd32(a + 24);
        if (sender && (arp_lookup(sender) || target == my_ip))
            arp_learn(sender, a + 8);
        if (rd16(a + 6) == 1 && my_ip && target == my_ip)
            arp_send(2, a + 8, sender);
        else if (rd16(a + 6) == 2 && target == my_ip)
            arp_learn(sender, a + 8);
        return;
    }
    if (type != ETH_IP || !(info & INFO_IP_OK))
        return;
    const u8 *ip = rxf + 14;
    u32 ihl = (ip[0] & 15u) * 4, total = rd16(ip + 2), proto = ip[9], dst = rd32(ip + 16);
    if ((rd16(ip + 6) & 0x3FFF) || total < ihl)
        return;
    if (dst != my_ip && dst != BROADCAST && dhcp_state == 3)
        return;
    if (proto != P_TCP && proto != P_UDP)
        return;
    if (!(info & INFO_L4_OK)) {
        frames_bad++;
        return;
    }
    if (proto == P_TCP) {
        tcp_input(ip, ihl, total - ihl);
    } else {
        const u8 *u = ip + ihl;
        u32 ulen = rd16(u + 4);
        if (ulen < 8 || ulen > total - ihl)
            return;
        u32 sport = rd16(u), dport = rd16(u + 2);
        if (sport == DHCP_SERVER && dport == DHCP_CLIENT)
            dhcp_input(u + 8, ulen - 8);
        else if (sport == DNS_PORT && dport == dns_port)
            dns_input(u + 8, ulen - 8);
    }
}

static void link_changed(int up);

static void net_poll(void)
{
    u32 st = REG(NIC, NIC_STATUS);
    if (st & (ST_LINK_CHANGED | ST_RX_OVERFLOW))
        REG(NIC, NIC_STATUS) = ST_LINK_CHANGED | ST_RX_OVERFLOW;
    int up = (st & ST_LINK) != 0;
    if (up != link_up) {
        link_up = up;
        link_changed(up);
    }
    for (u32 n = 0; REG(NIC, NIC_RX_COUNT); n++) {
        /*
         * A full queue takes tens of ms, which may run past the next exchange with the modem:
         * keep the HDD busy meanwhile, and acknowledge as we go so the modem sees a fresh
         * window at the exchange rather than the one from before the queue filled.
         */
        if ((n & 7) == 7) {
            hdd_poll();
            write_next();
        }
        if ((n & 31) == 31)
            tcp_output();
        u32 len = REG(NIC, NIC_RX_LEN), info = REG(NIC, NIC_RX_INFO);
        if (len > sizeof rxf) {
            REG(NIC, NIC_RX_CMD) = 2;
            continue;
        }
        REG(NIC, NIC_RX_ADDR) = (u32)rxf;
        REG(NIC, NIC_RX_CMD) = 1;
        frames_in++;
        frame_input(len, info);
    }
    tcp_output();
}

static void net_timers(void)
{
    u32 now = now_ms();
    if ((dhcp_state == 1 || dhcp_state == 2) && link_up && now - dhcp_sent_ms >= 2000)
        dhcp_start();
    if (dns_state == 1 && now - dns_sent_ms >= 500)
        dns_send();
    tcp_timers(now);
}

/* ---- HTTP client: one request at a time on the TCP connection ---- */

enum { SINK_BUF, SINK_DATA };
static struct {
    int active;
    int kind;
    char req[384];
    u32 req_len;
    int sent;
    int in_body;
    char head[1024];
    u32 head_len;
    u32 status;
    u32 length;
    u32 from;
    u32 got;
    u8 *buf;
    u32 cap, len;
    int ok;
    const char *error;
    u32 last_data_ms;
    int fresh; /* data came since last_data_ms was set (now_ms is too slow for every frame) */
} http;
static u32 server_ip;

static u32 data_sink(u32 pos, const u8 *p, u32 n);
static u32 data_room(void);

static u32 rx_room(void)
{
    if (!http.active)
        return 65536;
    if (!http.in_body)
        return http.kind == SINK_DATA ? 8192 : 262144;
    if (http.kind == SINK_DATA && http.status == 206)
        return data_room();
    return 262144;
}

static void http_fail(const char *why)
{
    if (!http.active)
        return;
    http.active = 0;
    http.ok = 0;
    http.error = why;
}

static void app_connected(void)
{
    if (http.active && !http.sent) {
        http.sent = 1;
        tcp_write(http.req, http.req_len);
    }
}

static void app_closed(const char *why)
{
    http_fail(why);
}

/* The head is complete: status, Content-Length, Content-Range. */
static void http_head(void)
{
    char *h = http.head;
    http.status = 0;
    http.length = 0;
    http.from = 0;
    if (http.head_len < 12 || h[0] != 'H' || h[1] != 'T' || h[2] != 'T' || h[3] != 'P')
        return;
    for (int i = 9; i < 12; i++)
        http.status = http.status * 10 + (u32)(h[i] - '0');
    for (u32 i = 0; i < http.head_len; i++) {
        if (h[i] != '\n')
            continue;
        const char *line = h + i + 1;
        static const char cl[] = "content-length:", cr[] = "content-range:";
        int is_cl = 1, is_cr = 1;
        for (u32 k = 0; k < sizeof cl - 1; k++)
            if (lower(line[k]) != cl[k]) {
                is_cl = 0;
                break;
            }
        for (u32 k = 0; k < sizeof cr - 1; k++)
            if (lower(line[k]) != cr[k]) {
                is_cr = 0;
                break;
            }
        const char *v = line + (is_cl ? sizeof cl - 1 : sizeof cr - 1);
        if (is_cl || is_cr) {
            while (*v == ' ' || *v == '\t' || (is_cr && (*v < '0' || *v > '9') && *v != '\r' && *v != '\n'))
                v++;
            u32 x = 0;
            while (*v >= '0' && *v <= '9')
                x = x * 10 + (u32)(*v++ - '0');
            if (is_cl)
                http.length = x;
            else
                http.from = x;
        }
    }
}

static u32 app_data(const u8 *p, u32 n)
{
    u32 used = 0;
    if (!http.active)
        return n;
    http.fresh = 1;
    while (used < n && http.active && !http.in_body) {
        char c = (char)p[used++];
        if (http.head_len < sizeof http.head - 1)
            http.head[http.head_len++] = c;
        if (c == '\n' && http.head_len >= 4 && http.head[http.head_len - 2] == '\r'
            && http.head[http.head_len - 3] == '\n') {
            http.head[http.head_len] = 0;
            http_head();
            http.in_body = 1;
            http.got = 0;
            if (http.status != 200 && http.status != 206) {
                static char why[48];
                char *d = put_s(why, "The server answered HTTP ");
                put_u(d, http.status);
                http.error = why;
            }
            if (http.status == 200)
                http.from = 0;
        }
    }
    while (used < n && http.active && http.got < http.length) {
        u32 k = n - used;
        if (k > http.length - http.got)
            k = http.length - http.got;
        u32 taken = k;
        if (http.error) {
            /* the error page: read and dropped */
        } else if (http.kind == SINK_BUF) {
            u32 room = http.cap - http.len;
            copy_fast(http.buf + http.len, p + used, k < room ? k : room);
            http.len += k < room ? k : room;
        } else {
            taken = data_sink(http.from + http.got, p + used, k);
        }
        used += taken;
        http.got += taken;
        if (taken < k)
            return used;
    }
    if (http.active && http.in_body && http.got == http.length) {
        http.active = 0;
        http.ok = http.error == 0;
        if (http.error && http.status == 0)
            http.error = "The server's answer is not HTTP.";
    }
    return n;
}

/* Waits up to ms for the next hop's MAC; asks every half second. */
static int arp_resolve(u32 ip, u32 ms)
{
    u32 hop = next_hop(ip), start = now_ms(), asked = 0;
    while (!arp_lookup(hop)) {
        u32 now = now_ms();
        if (now - start >= ms || !link_up)
            return 0;
        if (!asked || now - asked >= 500) {
            asked = now ? now : 1;
            arp_send(1, 0, hop);
        }
        idle();
    }
    return 1;
}

/* Starts GET path (with Range from..to when from or to is not ~0); the answer arrives through idle(). */
static int http_start(const char *path, u32 from, u32 to, int kind, u8 *buf, u32 cap)
{
    char *d = put_s(http.req, "GET ");
    d = put_s(d, path);
    d = put_s(d, " HTTP/1.1\r\nHost: get.flip\r\nUser-Agent: flippies-netinstall/1.0\r\n");
    if (from != ~0u) {
        d = put_s(d, "Range: bytes=");
        d = put_u(d, from);
        *d++ = '-';
        if (to != ~0u)
            d = put_u(d, to);
        d = put_s(d, "\r\n");
    }
    d = put_s(d, "\r\n");
    http.req_len = (u32)(d - http.req);
    http.kind = kind;
    http.in_body = 0;
    http.head_len = 0;
    http.status = 0;
    http.length = 0;
    http.got = 0;
    http.buf = buf;
    http.cap = cap;
    http.len = 0;
    http.ok = 0;
    http.error = 0;
    http.sent = 0;
    http.fresh = 0;
    http.last_data_ms = now_ms();
    if (!arp_resolve(server_ip, 3000)) {
        http.error = link_up ? "The modem does not answer ARP." : "The network link is down.";
        return 0;
    }
    http.active = 1;
    if (tcp.state == T_OPEN) {
        http.sent = 1;
        tcp_write(http.req, http.req_len);
    } else {
        tcp_connect(server_ip);
    }
    return 1;
}

/* GET into buf; tries three times. Returns the length, or -1 with http.error set. */
static int fetch(const char *path, u32 from, u32 to, u8 *buf, u32 cap)
{
    for (int attempt = 0; attempt < FAILURES_TO_STOP; attempt++) {
        if (http_start(path, from, to, SINK_BUF, buf, cap)) {
            while (http.active) {
                idle();
                if (http.fresh) {
                    http.fresh = 0;
                    http.last_data_ms = now_ms();
                }
                if (now_ms() - http.last_data_ms > 5000) {
                    tcp_abort();
                    http_fail("The server stopped sending.");
                }
            }
            if (http.ok)
                return (int)http.len;
        }
        if (http.status == 404)
            break;
        pause_ms(500);
    }
    return -1;
}

/* ---- the image ---- */

static struct {
    u32 sectors;
    u32 count;
    u32 data_start;
    char title[64];
} fdi;
static u32 image_bytes, image_chunks, present_bytes;

static u32 entry_index(u32 k)
{
    return table[2 * k];
}

static u32 entry_crc(u32 k)
{
    return table[2 * k + 1];
}

static u32 entry_len(u32 k)
{
    u32 at = entry_index(k) * CHUNK;
    return image_bytes - at < CHUNK ? image_bytes - at : CHUNK;
}

static u32 data_offset(u32 k)
{
    return fdi.data_start + k * CHUNK;
}

static u8 *slot(u32 k)
{
    return ring + (k % SLOTS) * CHUNK;
}

/* ---- the download pipeline: network into the ring, ring onto the HDD ---- */

static char image_path[96];
static u32 range_last;    /* the last table entry of this download */
static u32 recv_entry;    /* the first entry not yet received whole and checked */
static u32 recv_off;      /* bytes of it received */
static u32 write_head;    /* the first entry not yet on the HDD */
static int writing;       /* a write is in flight */
static int downloading;
static u64 bytes_written;
static u32 crc_failures;

/* Room for more data: up to the end of the slot that the oldest unwritten entry frees. */
static u32 data_room(void)
{
    u32 expected = data_offset(recv_entry) + recv_off;
    u32 limit = data_offset(write_head + SLOTS);
    return limit > expected ? limit - expected : 0;
}

static u32 data_sink(u32 pos, const u8 *p, u32 n)
{
    u32 used = 0;
    u32 expected = data_offset(recv_entry) + recv_off;
    if (pos < expected) {
        u32 skip = expected - pos;
        if (skip >= n)
            return n;
        used = skip;
    } else if (pos > expected) {
        http_fail("The server sent data out of place.");
        tcp_abort();
        return n;
    }
    while (used < n) {
        if (recv_entry > range_last)
            return n;
        if (recv_entry >= write_head + SLOTS)
            break;
        u32 len = entry_len(recv_entry);
        u32 take = n - used < len - recv_off ? n - used : len - recv_off;
        copy_fast(slot(recv_entry) + recv_off, p + used, take);
        recv_off += take;
        used += take;
        if (recv_off == len) {
            recv_off = 0;
            hdd_poll();
            write_next();
            if (crc32(0, slot(recv_entry), len) != entry_crc(recv_entry)) {
                crc_failures++;
                static char why[64];
                char *d = put_s(why, "Chunk ");
                d = put_u(d, entry_index(recv_entry));
                put_s(d, " arrived with a CRC error.");
                http_fail(why);
                tcp_abort();
                return n;
            }
            recv_entry++;
        }
    }
    return used;
}

/* Writes the next received entries from the ring: two at once when they are adjacent on disk and in the ring. */
static void write_next(void)
{
    if (writing || hdd_job != JOB_NONE || !downloading || write_head >= recv_entry)
        return;
    u32 k = write_head, n = 1, bytes = entry_len(k);
    if (k + 1 < recv_entry && entry_index(k + 1) == entry_index(k) + 1 && (k + 1) % SLOTS != 0
        && bytes == CHUNK) {
        n = 2;
        bytes += entry_len(k + 1);
    }
    writing = 1;
    hdd_start(HDD_WRITE, entry_index(k) * CHUNK_SECTORS, bytes / SECTOR, slot(k), JOB_WRITE, k, n);
}

/* ---- verify ---- */

static u32 verify_next_check, verify_read_done;

static void hdd_done(int job, u32 arg, u32 count)
{
    if (job == JOB_WRITE) {
        writing = 0;
        for (u32 i = 0; i < count; i++)
            bytes_written += entry_len(arg + i);
        write_head = arg + count;
    } else if (job == JOB_READ) {
        verify_read_done = arg + 1;
    }
}

/* ---- screens ---- */


static void network_screen(const char *line, u8 attr)
{
    clear_screen(" Waiting for the network");
    row_text(2, A_BRIGHT, "Network");
    row_text(4, attr, line);
    mirror_screen();
}

/* Screen 1: link, DHCP, DNS for get.flip. Returns when the server's address is known. */
static void bring_up_network(void)
{
    for (;;) {
        if (!link_up) {
            network_screen("Network: no link. Connect the modem to this PC's ethernet port.", A_NORMAL);
            while (!link_up)
                idle();
        }
        network_screen("Network: link up, asking for an address (DHCP)...", A_NORMAL);
        dhcp_start();
        while (dhcp_state != 3 && link_up)
            idle();
        if (!link_up)
            continue;
        char line[80];
        char *d = put_s(line, "Network: address ");
        d = put_ip(d, my_ip);
        d = put_s(d, ", gateway ");
        d = put_ip(d, gateway);
        d = put_s(d, ", DNS ");
        put_ip(d, dns_server);
        clear_screen(" Waiting for the network");
        row_text(2, A_BRIGHT, "Network");
        row_text(4, A_GOOD, line);
        row_text(5, A_NORMAL, "Looking up get.flip...");
        mirror_screen();
        dns_name = "get.flip";
        dns_id = (u16)random32();
        dns_port = (u16)(1024 + random32() % 30000);
        dns_state = 1;
        u32 start = now_ms();
        dns_send();
        while (dns_state == 1 && link_up && now_ms() - start < 5000)
            idle();
        if (dns_state == 2) {
            server_ip = dns_answer;
            return;
        }
        dns_state = 0;
        network_screen(link_up ? "Network: get.flip cannot be found. Trying again..."
                               : "Network: the link went down.", A_ERROR);
        pause_ms(2000);
    }
}

struct item {
    int image;
    char path[96];
    u32 size;
    char title[64];
};
static struct item items[MAX_ITEMS];
static int nitems;

static void copy_field(char *dst, u32 cap, const char *s, u32 n)
{
    if (n > cap - 1)
        n = cap - 1;
    for (u32 i = 0; i < n; i++)
        dst[i] = s[i];
    dst[n] = 0;
}

/* index.txt (spec 12.4.3): kind, path, size, title; blank and '#' lines skipped. */
static void parse_index(u32 len)
{
    nitems = 0;
    index_text[len] = 0;
    const char *s = index_text;
    while (*s && nitems < MAX_ITEMS) {
        const char *end = s;
        while (*end && *end != '\n')
            end++;
        const char *p = s;
        const char *line_end = end > s && end[-1] == '\r' ? end - 1 : end;
        while (p < line_end && (*p == ' ' || *p == '\t'))
            p++;
        if (p < line_end && *p != '#') {
            const char *f[3];
            u32 fl[3];
            int nf = 0;
            while (nf < 3 && p < line_end) {
                f[nf] = p;
                while (p < line_end && *p != ' ' && *p != '\t')
                    p++;
                fl[nf] = (u32)(p - f[nf]);
                nf++;
                while (p < line_end && (*p == ' ' || *p == '\t'))
                    p++;
            }
            if (nf == 3 && p < line_end) {
                struct item *it = &items[nitems];
                it->image = fl[0] == 5 && f[0][0] == 'i' && f[0][1] == 'm';
                copy_field(it->path, sizeof it->path, f[1], fl[1]);
                it->size = 0;
                for (u32 i = 0; i < fl[2] && f[2][i] >= '0' && f[2][i] <= '9'; i++)
                    it->size = it->size * 10 + (u32)(f[2][i] - '0');
                const char *te = line_end;
                while (te > p && (te[-1] == ' ' || te[-1] == '\t'))
                    te--;
                copy_field(it->title, sizeof it->title, p, (u32)(te - p));
                if (it->path[0] == '/')
                    nitems++;
            }
        }
        s = *end ? end + 1 : end;
    }
}

#define LIST_TOP 4
#define LIST_ROWS 16

static void draw_catalogue(int sel, int top)
{
    clear_screen(" Up/Down: choose   Enter: install   ");
    row_text(2, A_BRIGHT, "Choose what to install (from http://get.flip/index.txt):");
    for (int r = 0; r < LIST_ROWS && top + r < nitems; r++) {
        const struct item *it = &items[top + r];
        char line[LINE];
        char *d = put_s(line, top + r == sel ? " > " : "   ");
        d = put_s(d, it->title);
        while (d - line < 52)
            *d++ = ' ';
        *d = 0;
        d = put_mb(d, it->size);
        d = put_s(d, " MB");
        d = put_s(d, it->image ? "  disk image" : "  file");
        row_text(LIST_TOP + r, top + r == sel ? A_SELECT : A_NORMAL, line);
    }
    if (nitems > LIST_ROWS) {
        char line[40];
        char *d = put_u(line, (u32)nitems);
        put_s(d, " entries; PgUp/PgDn to scroll");
        row_text(LIST_TOP + LIST_ROWS + 1, A_NORMAL, line);
    }
    mirror_screen();
}

/* Screen 2: returns the chosen item. */
static int choose(void)
{
    int sel = 0, top = 0;
    for (;;) {
        if (sel < top)
            top = sel;
        if (sel >= top + LIST_ROWS)
            top = sel - LIST_ROWS + 1;
        draw_catalogue(sel, top);
        int k = wait_key();
        if (k == K_UP && sel > 0)
            sel--;
        else if (k == K_DOWN && sel < nitems - 1)
            sel++;
        else if (k == K_PGUP)
            sel = sel > LIST_ROWS ? sel - LIST_ROWS : 0;
        else if (k == K_PGDN)
            sel = sel + LIST_ROWS < nitems ? sel + LIST_ROWS : nitems - 1;
        else if (k == K_HOME)
            sel = 0;
        else if (k == K_END)
            sel = nitems - 1;
        else if (k == K_ENTER)
            return sel;
    }
}

static void message(const char *title, const char *line1, const char *line2, u8 attr)
{
    clear_screen(" Enter: back to the list");
    row_text(2, A_BRIGHT, title);
    row_text(4, attr, line1);
    row_text(5, attr, line2);
    row_text(7, A_NORMAL, "Press Enter to go back to the list.");
    mirror_screen();
    while (wait_key() != K_ENTER)
        ;
}

/* Reads the .fdi header and chunk table with Range; returns 0 or why it cannot be used. */
static const char *load_fdi(const char *path)
{
    static u8 h[FDI_HEADER];
    int n = fetch(path, 0, FDI_HEADER - 1, h, sizeof h);
    if (n < 0)
        return http.error;
    if (n != (int)FDI_HEADER || h[0] != 'F' || h[1] != 'D' || h[2] != 'I' || h[3] != '1')
        return "This is not an FDI1 disk image.";
    if (crc32(0, h, 84) != rd32(h + 84))
        return "The image's header fails its CRC check.";
    if (rd32(h + 8) != CHUNK)
        return "The image's chunk size is not 64 KiB.";
    fdi.sectors = rd32(h + 4);
    fdi.count = rd32(h + 12);
    copy_field(fdi.title, sizeof fdi.title, (const char *)h + 20, 63);
    if (fdi.sectors > MAX_ENTRIES * CHUNK_SECTORS)
        return "The image is larger than 1 GiB, more than any HDD holds.";
    image_bytes = fdi.sectors * SECTOR;
    image_chunks = (image_bytes + CHUNK - 1) / CHUNK;
    if (fdi.count > image_chunks)
        return "The image's chunk table is larger than the image.";
    fdi.data_start = FDI_HEADER + 8 * fdi.count;
    if (fdi.count) {
        n = fetch(path, FDI_HEADER, fdi.data_start - 1, (u8 *)table, 8 * fdi.count);
        if (n < 0)
            return http.error;
        if (n != (int)(8 * fdi.count) || crc32(0, (const u8 *)table, 8 * fdi.count) != rd32(h + 16))
            return "The image's chunk table fails its CRC check.";
    }
    present_bytes = 0;
    for (u32 k = 0; k < fdi.count; k++) {
        if (entry_index(k) >= image_chunks || (k && entry_index(k) <= entry_index(k - 1)))
            return "The image's chunk table is out of order.";
        present_bytes += entry_len(k);
    }
    return 0;
}

/* The HDD's IDENTIFY block: capacity and model. */
static u32 hdd_capacity;
static char hdd_model[33];

static int identify(void)
{
    if (!(REG(SYSCON, SYS_PRESENT) & 1))
        return 0;
    static u8 id[512] __attribute__((aligned(4)));
    hdd_start(HDD_IDENTIFY, 0, 1, id, JOB_IDENTIFY, 0, 0);
    while (hdd_job != JOB_NONE)
        idle();
    if (hdd_error || id[0] != 'F' || id[1] != 'L' || id[2] != 'H' || id[3] != 'D') {
        hdd_error = 0;
        return 0;
    }
    hdd_capacity = (u32)id[4] | (u32)id[5] << 8 | (u32)id[6] << 16 | (u32)id[7] << 24;
    int n = 32;
    while (n > 0 && (id[16 + n - 1] == ' ' || id[16 + n - 1] == 0))
        n--;
    copy_field(hdd_model, sizeof hdd_model, (const char *)id + 16, (u32)n);
    return 1;
}

/* Screen 3: the size check and "YES". Returns 0 to go back, 1 to install, 2 to wipe and install. */
static int confirm(const struct item *it)
{
    char line[LINE], *d;
    clear_screen(" Esc: back to the list");
    row_text(2, A_BRIGHT, it->title);
    d = put_s(line, "Image: ");
    d = put_s(d, fdi.title);
    d = put_s(d, ", ");
    d = put_mb(d, image_bytes);
    d = put_s(d, " MB (");
    d = put_mb(d, present_bytes);
    put_s(d, " MB of data to download)");
    row_text(4, A_NORMAL, line);
    if (!identify()) {
        mirror_screen();
        message(it->title, "There is no HDD in this PC.", "Fit an HDD and restart the PC.", A_ERROR);
        return 0;
    }
    d = put_s(line, "HDD:   ");
    d = put_s(d, hdd_model);
    d = put_s(d, ", ");
    d = put_mb(d, (u64)hdd_capacity * SECTOR);
    put_s(d, " MB");
    row_text(5, A_NORMAL, line);
    if (fdi.sectors > hdd_capacity) {
        d = put_s(line, "The image needs ");
        d = put_mb(d, image_bytes);
        d = put_s(d, " MB; the HDD holds ");
        d = put_mb(d, (u64)hdd_capacity * SECTOR);
        put_s(d, " MB.");
        mirror_screen();
        message(it->title, "The image does not fit on this HDD.", line, A_ERROR);
        return 0;
    }
    row_text(7, A_ERROR, "This erases the HDD. Type YES to continue.");
    row_text(8, A_NORMAL, "(Type WIPE instead to also write zeros where the image has none, which an HDD");
    row_text(9, A_NORMAL, "that held something else may want; it takes longer. Esc goes back.)");
    char typed[8];
    u32 n = 0;
    for (;;) {
        typed[n] = 0;
        d = put_s(line, "> ");
        put_s(d, typed);
        row_text(11, A_BRIGHT, line);
        cursor(11, 2 + (int)n, 1);
        mirror_screen();
        int k = wait_key();
        if (k == K_ESC)
            return 0;
        if (k == K_BACKSPACE && n)
            n--;
        else if (key_char(k) && n < sizeof typed - 1)
            typed[n++] = (char)key_char(k);
        else if (k == K_ENTER) {
            typed[n] = 0;
            if (typed[0] == 'Y' && typed[1] == 'E' && typed[2] == 'S' && !typed[3])
                return 1;
            if (typed[0] == 'W' && typed[1] == 'I' && typed[2] == 'P' && typed[3] == 'E' && !typed[4])
                return 2;
            n = 0;
        }
    }
}

/* ---- screens 4 and 5 ---- */

static u32 phase_start_ms, last_draw_ms, last_mirror_ms;
static u32 failures;
static const char *last_problem;

static void draw_progress(const char *what, u64 done, u64 total, int force)
{
    u32 now = now_ms();
    if (!force && now - last_draw_ms < 250)
        return;
    last_draw_ms = now;
    u32 ms = now - phase_start_ms;
    char text[LINE], line[LINE];
    char *d = put_s(text, what);
    d = put_s(d, ": ");
    d = put_mb(d, done);
    d = put_s(d, " of ");
    d = put_mb(d, total);
    d = put_s(d, " MB");
    if (ms >= 500 && done) {
        u64 rate = done * 1000 / ms; /* bytes per second */
        d = put_s(d, ", ");
        d = put_mb(d, rate);
        d = put_s(d, " MB/s, about ");
        d = put_time(d, (u32)((total - done) / (rate ? rate : 1)));
        put_s(d, " left");
    }
    row_text(6, A_BRIGHT, text);
    u32 width = 60, fill = total ? (u32)(done * width / total) : width;
    for (u32 i = 0; i < width; i++)
        line[i] = i < fill ? (char)0xDB : (char)0xB0;
    line[width] = 0;
    row_text(8, A_NORMAL, line);
    if (last_problem) {
        d = put_s(line, "Retrying after: ");
        put_s(d, last_problem);
        row_text(10, A_ERROR, line);
    } else {
        row_text(10, A_NORMAL, "");
    }
    row_text(11, A_NORMAL, link_up ? "" : "Network: no link, waiting for the modem.");
    if (force || now - last_mirror_ms >= 1000) {
        last_mirror_ms = now;
        com_puts("progress: ");
        com_puts(text);
        com_puts("\r\n");
    }
}

static void progress_screen(const char *title, const char *phase)
{
    clear_screen(" Installing; do not turn the PC off");
    char line[LINE];
    char *d = put_s(line, "Installing ");
    put_s(d, title);
    row_text(2, A_BRIGHT, line);
    row_text(4, A_NORMAL, phase);
    mirror_screen();
    phase_start_ms = now_ms();
    last_draw_ms = last_mirror_ms = 0;
}

/* One failure: three in a row stop the install. */
static void failed(const char *why)
{
    last_problem = why;
    com_puts("problem: ");
    com_puts(why);
    com_puts("\r\n");
    if (++failures >= FAILURES_TO_STOP)
        stop_with("The download failed three times in a row.", why);
}

/* Fetches table entries first..last into the ring and writes them; resumes with Range after a failure. */
static void download(u32 first, u32 last, u64 *done, u64 total)
{
    range_last = last;
    recv_entry = write_head = first;
    recv_off = 0;
    writing = 0;
    downloading = 1;
    u32 start_written = (u32)bytes_written;
    u32 progress_entry = recv_entry;
    while (write_head <= last) {
        check_hdd();
        if (!http.active && recv_entry <= last && write_head + SLOTS > recv_entry && link_up) {
            if (!http_start(image_path, data_offset(recv_entry), data_offset(last) + entry_len(last) - 1,
                    SINK_DATA, 0, 0)) {
                failed(http.error);
                pause_ms(500);
                continue;
            }
        }
        idle();
        if (recv_entry != progress_entry) {
            progress_entry = recv_entry;
            failures = 0;
        }
        if (http.fresh || (http.active && rx_room() == 0)) {
            http.fresh = 0;
            http.last_data_ms = now_ms();
        }
        if (http.active && now_ms() - http.last_data_ms > 5000) {
            tcp_abort();
            http_fail("The download stopped for 5 seconds.");
        }
        if (!http.active && http.error) {
            failed(http.error);
            http.error = 0;
            recv_off = 0;
        } else if (!http.active && http.ok && recv_entry <= last && recv_entry < write_head + SLOTS) {
            http.ok = 0;
            failed("The server's answer ended early.");
            recv_off = 0;
        }
        draw_progress("Written", *done + (bytes_written - start_written), total, 0);
    }
    *done += bytes_written - start_written;
    downloading = 0;
}

/* WIPE: zeros over every chunk of the image that the .fdi leaves out, 128 KiB per command. */
static void wipe(void)
{
    memset(vbuf, 0, sizeof vbuf);
    u64 total = (u64)image_bytes - present_bytes, done = 0;
    progress_screen(fdi.title, "Writing zeros where the image has no data (WIPE)");
    u32 k = 0;
    for (u32 c = 0; c < image_chunks;) {
        while (k < fdi.count && entry_index(k) < c)
            k++;
        if (k < fdi.count && entry_index(k) == c) {
            c++;
            continue;
        }
        u32 n = 1;
        if (c + 1 < image_chunks && !(k < fdi.count && entry_index(k) == c + 1))
            n = 2;
        u32 bytes = 0;
        for (u32 i = 0; i < n; i++) {
            u32 at = (c + i) * CHUNK;
            bytes += image_bytes - at < CHUNK ? image_bytes - at : CHUNK;
        }
        hdd_start(HDD_WRITE, c * CHUNK_SECTORS, bytes / SECTOR, vbuf, JOB_ZERO, 0, 0);
        while (hdd_job != JOB_NONE)
            idle();
        check_hdd();
        done += bytes;
        c += n;
        draw_progress("Zeroed", done, total, 0);
    }
    draw_progress("Zeroed", done, total, 1);
}

/* Reads entries back and checks their CRC32; the first max_bad that fail go into bad[]. Returns how
   many failed, which can be more than max_bad. */
static u32 verify(u32 first, u32 last, u32 *bad, u32 max_bad, u64 *done, u64 total)
{
    u32 nbad = 0;
    verify_next_check = first;
    verify_read_done = first;
    u32 next_read = first;
    while (verify_next_check <= last) {
        check_hdd();
        if (hdd_job == JOB_NONE && next_read <= last && next_read < verify_next_check + 2) {
            hdd_start(HDD_READ, entry_index(next_read) * CHUNK_SECTORS, entry_len(next_read) / SECTOR,
                vbuf + (next_read & 1) * CHUNK, JOB_READ, next_read, 1);
            next_read++;
        }
        if (verify_read_done > verify_next_check) {
            u32 k = verify_next_check;
            if (crc32(0, vbuf + (k & 1) * CHUNK, entry_len(k)) != entry_crc(k)) {
                if (nbad < max_bad)
                    bad[nbad] = k;
                nbad++;
            }
            *done += entry_len(k);
            verify_next_check++;
            draw_progress("Verified", *done, total, 0);
            continue;
        }
        idle();
    }
    return nbad;
}

static void install(const struct item *it, int wipe_first)
{
    copy_field(image_path, sizeof image_path, it->path, sizeof image_path);
    failures = 0;
    last_problem = 0;
    crc_failures = 0;
    bytes_written = 0;
    if (wipe_first)
        wipe();
    u64 done = 0;
    u32 t0 = now_ms();
    progress_screen(fdi.title, "Downloading and writing the image");
    if (fdi.count)
        download(0, fdi.count - 1, &done, present_bytes);
    draw_progress("Written", done, present_bytes, 1);
    u32 t1 = now_ms();

    /* screen 5: every chunk read back and checked; one that fails is fetched again. More failures
       than bad[] holds: those 256 are fetched again, then the check goes on after the last of them. */
    static u32 bad[256];
    u64 vdone = 0;
    u32 t2 = 0, refetched = 0;
    progress_screen(fdi.title, "Verifying: reading every chunk back from the HDD");
    for (u32 from = 0; from < fdi.count;) {
        u32 nbad = verify(from, fdi.count - 1, bad, 256, &vdone, present_bytes);
        draw_progress("Verified", vdone, present_bytes, 1);
        if (!t2)
            t2 = now_ms();
        for (u32 i = 0; i < nbad && i < 256; i++) {
            u32 k = bad[i];
            for (int tries = 0;; tries++) {
                static char why[64];
                char *d = put_s(why, "Chunk ");
                d = put_u(d, entry_index(k));
                put_s(d, " read back with a CRC error.");
                if (tries >= FAILURES_TO_STOP)
                    stop_with("A chunk keeps failing its check after it is written.", why);
                com_puts("refetch: ");
                com_puts(why);
                com_puts("\r\n");
                u64 d1 = 0, d2 = 0;
                download(k, k, &d1, entry_len(k));
                refetched++;
                u32 again;
                if (!verify(k, k, &again, 1, &d2, entry_len(k)))
                    break;
            }
        }
        if (nbad <= 256)
            break;
        from = bad[255] + 1;
        vdone = 0;
        for (u32 k = 0; k < from; k++)
            vdone += entry_len(k);
        progress_screen(fdi.title, "Verifying again after the chunks fetched again");
    }
    if (!t2)
        t2 = now_ms();
    hdd_start(3, 0, 1, vbuf, JOB_ZERO, 0, 0); /* FLUSH */
    while (hdd_job != JOB_NONE)
        idle();
    check_hdd();

    clear_screen(" Enter: restart");
    row_text(2, A_GOOD, "Done. Remove the floppy and press Enter to restart.");
    row_text(4, A_NORMAL, fdi.title);
    char line[LINE];
    char *d = put_s(line, "Installed ");
    d = put_mb(d, present_bytes);
    d = put_s(d, " MB of ");
    d = put_mb(d, image_bytes);
    put_s(d, " MB written.");
    row_text(5, A_NORMAL, line);
    u32 dl = t1 - t0 ? t1 - t0 : 1, vf = t2 - t1 ? t2 - t1 : 1;
    d = put_s(line, "Download and write: ");
    d = put_u(d, dl / 1000);
    *d++ = '.';
    d = put_u(d, dl % 1000 / 100);
    d = put_s(d, " s, ");
    d = put_mb(d, (u64)present_bytes * 1000 / dl);
    d = put_s(d, " MB/s. Verify: ");
    d = put_u(d, vf / 1000);
    *d++ = '.';
    d = put_u(d, vf % 1000 / 100);
    d = put_s(d, " s, ");
    d = put_mb(d, (u64)present_bytes * 1000 / vf);
    put_s(d, " MB/s.");
    row_text(6, A_NORMAL, line);
    d = put_s(line, "Chunks: ");
    d = put_u(d, fdi.count);
    d = put_s(d, " present of ");
    d = put_u(d, image_chunks);
    put_s(d, wipe_first ? " (the others zeroed)" : " (the others left as they were)");
    row_text(7, A_NORMAL, line);
    d = put_s(line, "CRC errors in transit ");
    d = put_u(d, crc_failures);
    d = put_s(d, ", fetched again ");
    put_u(d, refetched);
    row_text(8, A_NORMAL, line);
    mirror_screen();
    com_puts("netinstall: done\r\n");
    while (wait_key() != K_ENTER)
        ;
    restart();
}

/* ---- link changes ---- */

static void link_changed(int up)
{
    for (int i = 0; i < 4; i++)
        arp_cache[i].ip = 0;
    if (tcp.state != T_CLOSED) {
        tcp.state = T_CLOSED;
        http_fail(up ? "The network link was down." : "The network link went down.");
    }
}

/* ---- main ---- */

void netinstall_main(u32 ram, u32 boot_device, void *print)
{
    (void)boot_device;
    (void)print;
    (void)ram;
    /* the spurious interrupt and the seven autovectors (the ROM pointed them at its trap stubs) */
    volatile u32 *vectors = 0;
    __asm__("" : "+a"(vectors)); /* the table is at address 0: hide that from the optimiser */
    for (int v = 24; v < 32; v++)
        vectors[v] = (u32)irq_entry;
    random_state = REG(RTC, 0) ^ REG(TIMER, TIMER_LO);
    next_port = (u16)(49152 + random32() % 16000);

    REG(VGA, VGA_MODE) = 0;
    clear_screen("");
    row_text(2, A_NORMAL, "Starting...");
    REG8(COM1, 1) = 0;
    REG(PS2, 0x08) = 3;
    REG(PS2, 0x18) = 3;
    REG(HDD, HDD_IRQ) = 1;
    u32 mlo = REG(NIC, NIC_MAC_LO), mhi = REG(NIC, NIC_MAC_HI);
    for (int i = 0; i < 4; i++)
        my_mac[i] = (u8)(mlo >> (8 * i));
    my_mac[4] = (u8)mhi;
    my_mac[5] = (u8)(mhi >> 8);
    REG(NIC, NIC_STATUS) = 0x2F;
    REG(NIC, NIC_CTRL) = CTRL_RX_ENABLE | CTRL_IRQ_RX | CTRL_IRQ_LINK | CTRL_TX_CSUM;
    REG(TIMER, TIMER_PERIOD) = TICK_US;
    REG(TIMER, TIMER_CTRL) = 3;
    REG(INTC, INTC_ENABLE) = 1u << LINE_COM1 | 1u << LINE_PS2_0 | 1u << LINE_PS2_1 | 1u << LINE_HDD
                             | 1u << LINE_TIMER | 1u << LINE_NIC;
    crc_init();
    com_puts("netinstall 1.0\r\n");
    link_up = (REG(NIC, NIC_STATUS) & ST_LINK) != 0;

    bring_up_network();
    for (;;) {
        clear_screen(" Loading the catalogue");
        row_text(2, A_NORMAL, "Fetching http://get.flip/index.txt...");
        mirror_screen();
        int n = fetch("/index.txt", ~0u, ~0u, (u8 *)index_text, INDEX_MAX);
        if (n < 0) {
            message("The catalogue", "http://get.flip/index.txt could not be fetched:", http.error, A_ERROR);
            if (!link_up)
                bring_up_network();
            continue;
        }
        parse_index((u32)n);
        if (!nitems) {
            /* the jar's built-in index when the server has no OS pack (spec 12.4.3) */
            message("The catalogue", "No OS is available on this server.",
                "Ask the server owner to install an OS pack.", A_ERROR);
            continue;
        }
        struct item *it = &items[choose()];
        if (!it->image) {
            message(it->title, "This is a plain file, not a disk image.", "Only disk images can be installed.",
                A_ERROR);
            continue;
        }
        clear_screen(" Loading the image's header");
        row_text(2, A_NORMAL, "Reading the image's header and chunk table...");
        mirror_screen();
        const char *bad = load_fdi(it->path);
        if (bad) {
            message(it->title, "This image cannot be installed:", bad, A_ERROR);
            continue;
        }
        int how = confirm(it);
        if (how)
            install(it, how == 2);
    }
}
