// Cog Maze: a first-person maze for the Create Flippies RV32I PC.
//
// Collect every brass cog in the maze, then reach the exit door before the
// clock runs out. Each level is a bigger maze. The high score is kept in the
// last sector of the boot disk.
//
// Freestanding C++: no libc, no floats at run time (the CPU has no FPU), no
// 64-bit division. Fixed point is 16.16 throughout; angles are 1024 per turn.
//
// Display: VGA mode 1 (320x200, 8 bpp), two pages in VRAM flipped with START.
// The 3D view is 160 columns, each 2 pixels wide, over the top 160 rows.

#include <stdint.h>

// ---------------------------------------------------------------- hardware

namespace hw {
constexpr uint32_t CLINT_MTIME = 0x0200BFF8;
constexpr uint32_t IRQC = 0x0C000000;
constexpr uint32_t PS2 = 0x10002000;
constexpr uint32_t HDD = 0x10003000;
constexpr uint32_t VGA = 0x10004000;
constexpr uint32_t RTC = 0x10006000;
constexpr uint32_t FDC = 0x10008000;
constexpr uint32_t VRAM = 0x20000000;

inline void w32(uint32_t a, uint32_t v) { *(volatile uint32_t*)a = v; }
inline uint32_t r32(uint32_t a) { return *(volatile uint32_t*)a; }

// VGA control registers
constexpr uint32_t VGA_MODE = VGA + 0x00, VGA_CTRL = VGA + 0x04, VGA_STATUS = VGA + 0x08,
                   VGA_FRAME = VGA + 0x10, VGA_START = VGA + 0x24, VGA_PALETTE = VGA + 0x400;
// disk controller registers (floppy and HDD share these offsets)
constexpr uint32_t D_COMMAND = 0x00, D_STATUS = 0x04, D_LBA = 0x08, D_COUNT = 0x0C, D_DMA = 0x10;
}  // namespace hw

// ---------------------------------------------------------------- fixed point and tables

constexpr int FIX = 16;
constexpr int32_t ONE = 1 << FIX;
constexpr int ANG = 1024;  // angle units per turn

static inline int32_t fmul(int32_t a, int32_t b) { return (int32_t)(((int64_t)a * b) >> FIX); }

struct SinTable {
    int32_t v[ANG];
};

// Taylor series evaluated by the compiler; only the integers reach the binary.
constexpr SinTable make_sin() {
    SinTable t{};
    for (int i = 0; i < ANG; i++) {
        double x = i * (2 * 3.14159265358979323846 / ANG);
        while (x > 3.14159265358979323846) x -= 2 * 3.14159265358979323846;
        double term = x, sum = x;
        for (int n = 1; n < 12; n++) {
            term *= -x * x / ((2 * n) * (2 * n + 1));
            sum += term;
        }
        t.v[i] = (int32_t)(sum * ONE + (sum >= 0 ? 0.5 : -0.5));
    }
    return t;
}
constexpr SinTable SIN = make_sin();

static inline int32_t fsin(int a) { return SIN.v[a & (ANG - 1)]; }
static inline int32_t fcos(int a) { return SIN.v[(a + ANG / 4) & (ANG - 1)]; }

static inline int iabs(int v) { return v < 0 ? -v : v; }
static inline int imin(int a, int b) { return a < b ? a : b; }
static inline int imax(int a, int b) { return a > b ? a : b; }
static inline int clampi(int v, int lo, int hi) { return v < lo ? lo : v > hi ? hi : v; }

static uint32_t rng_state = 0x12345678;
static uint32_t rnd() {
    uint32_t x = rng_state;
    x ^= x << 13;
    x ^= x >> 17;
    x ^= x << 5;
    return rng_state = x;
}
static int rnd_below(int n) { return (int)(rnd() % (uint32_t)n); }

static uint32_t hash(uint32_t x) {
    x ^= x >> 16;
    x *= 0x7feb352d;
    x ^= x >> 15;
    x *= 0x846ca68b;
    x ^= x >> 16;
    return x;
}

// ---------------------------------------------------------------- palette and shading
//
// 16 ramps of 16 shades: colour index = ramp * 16 + shade, shade 15 brightest.
// Darkening by distance subtracts from the shade within the ramp.

enum Ramp {
    STONE, BRICK, BRASS, COPPER, WOOD, MOSS, SKY, CYAN, RED, GREEN, WHITE, ANDESITE, STEEL, PURPLE, YELLOW, SPARE
};
static constexpr uint8_t col(int ramp, int shade) { return (uint8_t)(ramp * 16 + shade); }
constexpr uint8_t TRANSPARENT = 255;  // index 255 never appears in art

static const uint8_t RAMP_RGB[16][3] = {
    {200, 200, 205}, {196, 72, 48},   {246, 196, 84},  {226, 122, 60},
    {156, 104, 58},  {96, 168, 72},   {104, 156, 236}, {84, 236, 224},
    {255, 64, 56},   {64, 246, 96},   {255, 255, 255}, {150, 158, 150},
    {92, 104, 126},  {176, 96, 210},  {255, 236, 96},  {0, 0, 0},
};

constexpr int LEVELS = 16;
static uint16_t cmap16[LEVELS][256];  // shaded colour doubled into both bytes (2-pixel columns)

static void init_palette() {
    for (int r = 0; r < 16; r++)
        for (int s = 0; s < 16; s++) {
            // a slight curve keeps the dark end from crushing to black
            int k = 10 + s * 15;  // 10..235 of 245
            uint32_t rr = RAMP_RGB[r][0] * k / 235, gg = RAMP_RGB[r][1] * k / 235, bb = RAMP_RGB[r][2] * k / 235;
            if (rr > 255) rr = 255;
            if (gg > 255) gg = 255;
            if (bb > 255) bb = 255;
            hw::w32(hw::VGA_PALETTE + 4 * (r * 16 + s), rr << 16 | gg << 8 | bb);
        }
    for (int l = 0; l < LEVELS; l++)
        for (int i = 0; i < 256; i++) {
            int shade = imax(0, (i & 15) - l);
            uint8_t c = (uint8_t)((i & 0xF0) | shade);
            cmap16[l][i] = (uint16_t)(c | c << 8);
        }
}

// ---------------------------------------------------------------- font (5x7)

static const char FONT_CHARS[] = " !',-./0123456789:+?ABCDEFGHIJKLMNOPQRSTUVWXYZ";
static const uint8_t FONT[][7] = {
    {0, 0, 0, 0, 0, 0, 0},                             // space
    {0x04, 0x04, 0x04, 0x04, 0x04, 0x00, 0x04},        // !
    {0x04, 0x04, 0x08, 0, 0, 0, 0},                    // '
    {0, 0, 0, 0, 0x0C, 0x04, 0x08},                    // ,
    {0, 0, 0, 0x1F, 0, 0, 0},                          // -
    {0, 0, 0, 0, 0, 0x0C, 0x0C},                       // .
    {0x01, 0x01, 0x02, 0x04, 0x08, 0x10, 0x10},        // /
    {0x0E, 0x11, 0x13, 0x15, 0x19, 0x11, 0x0E},        // 0
    {0x04, 0x0C, 0x04, 0x04, 0x04, 0x04, 0x0E},        // 1
    {0x0E, 0x11, 0x01, 0x02, 0x04, 0x08, 0x1F},        // 2
    {0x1F, 0x02, 0x04, 0x02, 0x01, 0x11, 0x0E},        // 3
    {0x02, 0x06, 0x0A, 0x12, 0x1F, 0x02, 0x02},        // 4
    {0x1F, 0x10, 0x1E, 0x01, 0x01, 0x11, 0x0E},        // 5
    {0x06, 0x08, 0x10, 0x1E, 0x11, 0x11, 0x0E},        // 6
    {0x1F, 0x01, 0x02, 0x04, 0x08, 0x08, 0x08},        // 7
    {0x0E, 0x11, 0x11, 0x0E, 0x11, 0x11, 0x0E},        // 8
    {0x0E, 0x11, 0x11, 0x0F, 0x01, 0x02, 0x0C},        // 9
    {0, 0x0C, 0x0C, 0, 0x0C, 0x0C, 0},                 // :
    {0, 0x04, 0x04, 0x1F, 0x04, 0x04, 0},              // +
    {0x0E, 0x11, 0x01, 0x02, 0x04, 0x00, 0x04},        // ?
    {0x0E, 0x11, 0x11, 0x1F, 0x11, 0x11, 0x11},        // A
    {0x1E, 0x11, 0x11, 0x1E, 0x11, 0x11, 0x1E},        // B
    {0x0E, 0x11, 0x10, 0x10, 0x10, 0x11, 0x0E},        // C
    {0x1C, 0x12, 0x11, 0x11, 0x11, 0x12, 0x1C},        // D
    {0x1F, 0x10, 0x10, 0x1E, 0x10, 0x10, 0x1F},        // E
    {0x1F, 0x10, 0x10, 0x1E, 0x10, 0x10, 0x10},        // F
    {0x0E, 0x11, 0x10, 0x17, 0x11, 0x11, 0x0F},        // G
    {0x11, 0x11, 0x11, 0x1F, 0x11, 0x11, 0x11},        // H
    {0x0E, 0x04, 0x04, 0x04, 0x04, 0x04, 0x0E},        // I
    {0x07, 0x02, 0x02, 0x02, 0x02, 0x12, 0x0C},        // J
    {0x11, 0x12, 0x14, 0x18, 0x14, 0x12, 0x11},        // K
    {0x10, 0x10, 0x10, 0x10, 0x10, 0x10, 0x1F},        // L
    {0x11, 0x1B, 0x15, 0x15, 0x11, 0x11, 0x11},        // M
    {0x11, 0x11, 0x19, 0x15, 0x13, 0x11, 0x11},        // N
    {0x0E, 0x11, 0x11, 0x11, 0x11, 0x11, 0x0E},        // O
    {0x1E, 0x11, 0x11, 0x1E, 0x10, 0x10, 0x10},        // P
    {0x0E, 0x11, 0x11, 0x11, 0x15, 0x12, 0x0D},        // Q
    {0x1E, 0x11, 0x11, 0x1E, 0x14, 0x12, 0x11},        // R
    {0x0F, 0x10, 0x10, 0x0E, 0x01, 0x01, 0x1E},        // S
    {0x1F, 0x04, 0x04, 0x04, 0x04, 0x04, 0x04},        // T
    {0x11, 0x11, 0x11, 0x11, 0x11, 0x11, 0x0E},        // U
    {0x11, 0x11, 0x11, 0x11, 0x11, 0x0A, 0x04},        // V
    {0x11, 0x11, 0x11, 0x15, 0x15, 0x15, 0x0A},        // W
    {0x11, 0x11, 0x0A, 0x04, 0x0A, 0x11, 0x11},        // X
    {0x11, 0x11, 0x11, 0x0A, 0x04, 0x04, 0x04},        // Y
    {0x1F, 0x01, 0x02, 0x04, 0x08, 0x10, 0x1F},        // Z
};

static const uint8_t* glyph(char c) {
    if (c >= 'a' && c <= 'z') c = (char)(c - 32);
    for (int i = 0; FONT_CHARS[i]; i++)
        if (FONT_CHARS[i] == c) return FONT[i];
    return FONT[0];
}

static int str_len(const char* s) {
    int n = 0;
    while (s[n]) n++;
    return n;
}

// Writes v into buf with at least `digits` digits; returns buf.
static char* fmt_num(char* buf, uint32_t v, int digits) {
    char tmp[12];
    int n = 0;
    do {
        tmp[n++] = (char)('0' + v % 10);
        v /= 10;
    } while (v);
    while (n < digits) tmp[n++] = '0';
    for (int i = 0; i < n; i++) buf[i] = tmp[n - 1 - i];
    buf[n] = 0;
    return buf;
}

// Concatenates up to four strings into buf.
static char* cat(char* buf, const char* a, const char* b = "", const char* c = "", const char* d = "") {
    int n = 0;
    for (const char* parts[] = {a, b, c, d}; const char* p : parts)
        while (*p) buf[n++] = *p++;
    buf[n] = 0;
    return buf;
}

// ---------------------------------------------------------------- framebuffer drawing

constexpr int SW = 320, SH = 200;
constexpr int VIEW_H = 160;  // 3D view rows; the HUD takes the rest
constexpr int COLS = 160;    // rays, 2 pixels each
constexpr uint32_t PAGE_SIZE = 0x10000;

static uint8_t* fb;  // the page being drawn

static void fill_rect(int x, int y, int w, int h, uint8_t c) {
    int x0 = imax(x, 0), y0 = imax(y, 0), x1 = imin(x + w, SW), y1 = imin(y + h, SH);
    for (int yy = y0; yy < y1; yy++) {
        uint8_t* p = fb + yy * SW;
        for (int xx = x0; xx < x1; xx++) p[xx] = c;
    }
}

static inline void pset(int x, int y, uint8_t c) {
    if ((unsigned)x < (unsigned)SW && (unsigned)y < (unsigned)SH) fb[y * SW + x] = c;
}

static int text_width(const char* s, int scale) { return str_len(s) * 6 * scale - scale; }

static void text(int x, int y, const char* s, uint8_t c, int scale = 1) {
    for (; *s; s++, x += 6 * scale) {
        const uint8_t* g = glyph(*s);
        for (int row = 0; row < 7; row++)
            for (int bit = 0; bit < 5; bit++)
                if (g[row] & (0x10 >> bit)) {
                    if (scale == 1)
                        pset(x + bit, y + row, c);
                    else
                        fill_rect(x + bit * scale, y + row * scale, scale, scale, c);
                }
    }
}

static void text_shadow(int x, int y, const char* s, uint8_t c, int scale = 1) {
    text(x + scale, y + scale, s, col(STONE, 0), scale);
    text(x, y, s, c, scale);
}

static void text_center(int y, const char* s, uint8_t c, int scale = 1) {
    text_shadow((SW - text_width(s, scale)) / 2, y, s, c, scale);
}

// ---------------------------------------------------------------- textures (64x64, column-major)

constexpr int TEX = 64;
enum Tex { T_BRICK, T_CASING, T_BRASS, T_WOOD, T_DOOR_LOCKED, T_DOOR_OPEN, T_COUNT };
static uint8_t textures[T_COUNT][TEX * TEX];
constexpr int COG_FRAMES = 8;
static uint8_t cog_frames[COG_FRAMES][TEX * TEX];

static inline void tset(uint8_t* t, int x, int y, uint8_t c) {
    if ((unsigned)x < TEX && (unsigned)y < TEX) t[x * TEX + y] = c;
}

static void tex_text(uint8_t* t, int x, int y, const char* s, uint8_t c) {
    for (; *s; s++, x += 6) {
        const uint8_t* g = glyph(*s);
        for (int row = 0; row < 7; row++)
            for (int bit = 0; bit < 5; bit++)
                if (g[row] & (0x10 >> bit)) tset(t, x + bit, y + row, c);
    }
}

static void tex_rivet(uint8_t* t, int cx, int cy, int ramp) {
    for (int dy = -2; dy <= 2; dy++)
        for (int dx = -2; dx <= 2; dx++) {
            int d = dx * dx + dy * dy;
            if (d > 5) continue;
            int shade = d <= 1 ? (dx + dy < 0 ? 15 : 12) : (dx + dy < 0 ? 11 : 5);
            tset(t, cx + dx, cy + dy, col(ramp, shade));
        }
}

// The beveled frame of Create's andesite casing.
static void tex_frame(uint8_t* t, int ramp, int base) {
    for (int x = 0; x < TEX; x++)
        for (int y = 0; y < TEX; y++) {
            int edge = imin(imin(x, y), imin(TEX - 1 - x, TEX - 1 - y));
            if (edge >= 5) continue;
            int shade = base;
            if (edge == 0) shade = base - 5;
            else if (x < 5 && x <= y && x <= TEX - 1 - y) shade = base + 3;   // left: lit
            else if (y < 5 && y <= x && y <= TEX - 1 - x) shade = base + 3;   // top: lit
            else shade = base - 3;                                           // right, bottom: shadow
            shade += (int)(hash(x * 131 + y * 7919) & 1);
            t[x * TEX + y] = col(ramp, clampi(shade, 0, 15));
        }
}

static void make_textures() {
    // brick
    uint8_t* t = textures[T_BRICK];
    for (int x = 0; x < TEX; x++)
        for (int y = 0; y < TEX; y++) {
            int row = y / 16;
            int bx = (x + (row & 1) * 16) & 63;
            bool mortar = (y % 16) < 2 || (bx % 32) < 2;
            uint32_t n = hash(x * 73 + y * 9137);
            if (mortar) {
                t[x * TEX + y] = col(STONE, 5 + (n & 1));
            } else {
                int brick_id = row * 4 + ((x + (row & 1) * 16) & 63) / 32;
                int shade = 8 + (int)(hash(brick_id * 3 + 1) % 4) + (int)(n % 3) - 1;
                if ((y % 16) == 2) shade += 2;          // top lip catches light
                if ((y % 16) == 15) shade -= 2;
                t[x * TEX + y] = col(BRICK, clampi(shade, 0, 15));
            }
        }

    // andesite casing with a wooden-looking inset
    t = textures[T_CASING];
    for (int x = 0; x < TEX; x++)
        for (int y = 0; y < TEX; y++) {
            uint32_t n = hash(x * 3571 + y * 11);
            t[x * TEX + y] = col(ANDESITE, 8 + (int)(n % 3));
        }
    tex_frame(t, ANDESITE, 9);
    for (int x = 12; x < 52; x++)
        for (int y = 12; y < 52; y++) {
            uint32_t n = hash(x * 17 + (y / 3) * 1009);
            t[x * TEX + y] = col(WOOD, 7 + (int)(n % 3) + ((x % 10) == 0 ? -3 : 0));
        }
    tex_rivet(t, 8, 8, STONE);
    tex_rivet(t, 55, 8, STONE);
    tex_rivet(t, 8, 55, STONE);
    tex_rivet(t, 55, 55, STONE);

    // brass plating: two plates with a seam and rivets, vertical sheen
    t = textures[T_BRASS];
    for (int x = 0; x < TEX; x++)
        for (int y = 0; y < TEX; y++) {
            int sheen = (fsin(x * 16) >> 14);  // -4..4
            int shade = 9 + sheen / 2 + (int)(hash(x * 911 + y * 37) % 2);
            if (y == 31) shade = 4;
            if (y == 32) shade = 13;
            if (x == 0 || y == 0) shade = 13;
            if (x == 63 || y == 63) shade = 4;
            t[x * TEX + y] = col(BRASS, clampi(shade, 0, 15));
        }
    for (int i = 0; i < 4; i++) {
        tex_rivet(t, 8 + i * 16, 26, COPPER);
        tex_rivet(t, 8 + i * 16, 37, COPPER);
    }

    // wooden planks
    t = textures[T_WOOD];
    for (int x = 0; x < TEX; x++)
        for (int y = 0; y < TEX; y++) {
            int plank = x / 16;
            int px = x % 16;
            int grain = (int)(hash(plank * 977 + px / 2 * 31 + (y / 6) * 3) % 3);
            int shade = 8 + grain + (int)(hash(plank * 5) % 3) - 1;
            if (px == 0) shade = 3;
            if (px == 1) shade = 11;
            int ring = (y + plank * 23) % 40;
            if (px > 4 && px < 9 && ring > 18 && ring < 22) shade -= 3;  // knot
            t[x * TEX + y] = col(WOOD, clampi(shade, 0, 15));
        }
    for (int p = 0; p < 4; p++) {
        tex_rivet(t, p * 16 + 8, 5, STONE);
        tex_rivet(t, p * 16 + 8, 58, STONE);
    }

    // exit door, locked and open
    for (int which = 0; which < 2; which++) {
        t = textures[which == 0 ? T_DOOR_LOCKED : T_DOOR_OPEN];
        for (int x = 0; x < TEX; x++)
            for (int y = 0; y < TEX; y++) {
                uint8_t c;
                if (which == 0) {
                    bool stripe = ((x + y) / 8) & 1;
                    c = stripe ? col(RED, 9) : col(STEEL, 3);
                } else {
                    int glow = 15 - iabs(y - 32) / 4 - iabs(x - 32) / 8;
                    c = col(GREEN, clampi(glow, 4, 15));
                }
                t[x * TEX + y] = c;
            }
        tex_frame(t, STEEL, 9);
        for (int x = 14; x < 50; x++)
            for (int y = 22; y < 37; y++) t[x * TEX + y] = col(STEEL, 2);
        tex_text(t, 21, 26, "EXIT", which == 0 ? col(RED, 15) : col(GREEN, 15));
        tex_rivet(t, 8, 8, STEEL);
        tex_rivet(t, 55, 8, STEEL);
        tex_rivet(t, 8, 55, STEEL);
        tex_rivet(t, 55, 55, STEEL);
    }
}

// Integer atan2 in angle units (0..1023), good to about a unit; startup only.
static int iatan2(int y, int x) {
    if (x == 0 && y == 0) return 0;
    int ax = iabs(x), ay = iabs(y);
    int mn = imin(ax, ay), mx = imax(ax, ay);
    int t = (mn << 16) / mx;  // 0..1 in Q16
    // atan(t) ~ pi/4 t + 0.273 t (1 - t), in angle units
    int a = (int)(((int64_t)t * 128 + (int64_t)fmul(t, ONE - t) * 44) >> 16);
    if (ay > ax) a = 256 - a;
    if (x < 0) a = 512 - a;
    if (y < 0) a = ANG - a;
    return a & (ANG - 1);
}

// A spinning brass cogwheel: eight frames over one eighth of a turn.
static void make_cogs() {
    // polar coordinates of every texel, shared by the frames
    static uint8_t radius[TEX * TEX];
    static uint16_t angle[TEX * TEX];
    for (int x = 0; x < TEX; x++)
        for (int y = 0; y < TEX; y++) {
            int dx = 2 * x - 63, dy = 2 * y - 63;  // half-pixel units, centred
            int r2 = dx * dx + dy * dy, r = 0;
            while ((r + 1) * (r + 1) <= r2) r++;  // integer sqrt
            radius[x * TEX + y] = (uint8_t)imin(r, 255);
            angle[x * TEX + y] = (uint16_t)iatan2(dy, dx);
        }
    for (int f = 0; f < COG_FRAMES; f++) {
        uint8_t* t = cog_frames[f];
        for (int x = 0; x < TEX; x++)
            for (int y = 0; y < TEX; y++) {
                int dx = 2 * x - 63, dy = 2 * y - 63;
                int a = (angle[x * TEX + y] + f * 16) & (ANG - 1);
                int tooth_phase = (a * 8) & (ANG - 1);
                uint8_t c = TRANSPARENT;
                int r = radius[x * TEX + y];
                bool tooth = tooth_phase < 400 + (60 - r) * 4;
                if (r < 46 || (r < 60 && tooth)) {
                    int light = -(dx + dy) / 12;  // lit from the top left
                    int shade = 10 + light;
                    if (r >= 42 && r < 46) shade -= 2;              // groove at the tooth root
                    if (r < 22) shade = 7 + light / 2;              // hub
                    bool window = r >= 26 && r < 38 && ((a * 8 + 512) & (ANG - 1)) < 560;  // 8 windows between spokes
                    if (r < 9 || window) c = TRANSPARENT;
                    else c = col(r < 22 ? COPPER : YELLOW, clampi(shade, 3, 15));
                }
                t[x * TEX + y] = c;
            }
        // a dark outline so the cog reads against any wall, brass included
        static uint8_t copy[TEX * TEX];
        for (int i = 0; i < TEX * TEX; i++) copy[i] = t[i];
        for (int x = 0; x < TEX; x++)
            for (int y = 0; y < TEX; y++) {
                if (copy[x * TEX + y] == TRANSPARENT) continue;
                bool edge = x == 0 || y == 0 || x == TEX - 1 || y == TEX - 1 || copy[(x - 1) * TEX + y] == TRANSPARENT ||
                            copy[(x + 1) * TEX + y] == TRANSPARENT || copy[x * TEX + y - 1] == TRANSPARENT ||
                            copy[x * TEX + y + 1] == TRANSPARENT;
                if (edge) t[x * TEX + y] = col(WOOD, 2);
            }
    }
}

// ---------------------------------------------------------------- keyboard (PS/2 set 2)

namespace key {
constexpr int EXT = 0x80;
constexpr int W = 0x1D, A = 0x1C, S = 0x1B, D = 0x23, Q = 0x15, E = 0x24, M = 0x3A, P = 0x4D,
              SPACE = 0x29, ENTER = 0x5A, LSHIFT = 0x12, RSHIFT = 0x59, UP = EXT | 0x75,
              DOWN = EXT | 0x72, LEFT = EXT | 0x6B, RIGHT = EXT | 0x74, KP_ENTER = EXT | 0x5A;
}  // namespace key

static bool key_down[256];
static bool key_hit[256];  // went down since the last frame
static int kb_port = -1;
static bool kb_ext, kb_release;

static uint32_t ps2_reg(int port, int reg) { return hw::PS2 + port * 0x10 + reg; }

static int ps2_read(int port) {
    if ((hw::r32(ps2_reg(port, 4)) & 1) == 0) return -1;
    return (int)(hw::r32(ps2_reg(port, 0)) & 0xFF);
}

// Finds the port the keyboard is on: identify replies 0xFA 0xAB for a keyboard.
static void probe_keyboard() {
    kb_port = -1;
    for (int p = 0; p < 2 && kb_port < 0; p++) {
        if ((hw::r32(ps2_reg(p, 4)) & 4) == 0) continue;
        hw::w32(ps2_reg(p, 8), 2);  // port enable, no IRQ
        while (ps2_read(p) >= 0) {}
        hw::w32(ps2_reg(p, 0), 0xF2);
        int a = ps2_read(p), b = ps2_read(p);
        while (ps2_read(p) >= 0) {}
        if (a == 0xFA && b == 0xAB) kb_port = p;
    }
    kb_ext = kb_release = false;
}

static void poll_keyboard() {
    for (int i = 0; i < 256; i++) key_hit[i] = false;
    if (kb_port < 0 || (hw::r32(ps2_reg(kb_port, 4)) & 4) == 0) return;
    int b;
    while ((b = ps2_read(kb_port)) >= 0) {
        if (b == 0xE0) { kb_ext = true; continue; }
        if (b == 0xF0) { kb_release = true; continue; }
        if (b == 0xE1 || b == 0xAA || b == 0xFA || b == 0x00) { kb_ext = kb_release = false; continue; }
        int k = (b & 0x7F) | (kb_ext ? key::EXT : 0);
        if (kb_release) key_down[k] = false;
        else {
            if (!key_down[k]) key_hit[k] = true;
            key_down[k] = true;
        }
        kb_ext = kb_release = false;
    }
}

static bool held(int k) { return key_down[k]; }
static bool hit(int k) { return key_hit[k]; }
static bool hit_confirm() { return hit(key::ENTER) || hit(key::KP_ENTER) || hit(key::SPACE); }

// ---------------------------------------------------------------- maze

constexpr int MAX_GRID = 31;
constexpr uint8_t EMPTY = 0, DOOR = 5;  // walls are 1..4 (texture + 1)
static uint8_t grid[MAX_GRID * MAX_GRID];
static uint8_t seen[MAX_GRID * MAX_GRID];
static int gw, gh;
static int door_x, door_y;

static inline uint8_t& cell(int x, int y) { return grid[y * MAX_GRID + x]; }

static void make_maze(int cells_w, int cells_h) {
    gw = cells_w * 2 + 1;
    gh = cells_h * 2 + 1;
    for (int i = 0; i < MAX_GRID * MAX_GRID; i++) grid[i] = 1, seen[i] = 0;

    // recursive backtracker on an explicit stack
    static uint16_t stack[MAX_GRID * MAX_GRID];
    int sp = 0;
    cell(1, 1) = EMPTY;
    stack[sp++] = 1 * MAX_GRID + 1;
    static const int DX[4] = {2, -2, 0, 0}, DY[4] = {0, 0, 2, -2};
    while (sp) {
        int cur = stack[sp - 1];
        int cx = cur % MAX_GRID, cy = cur / MAX_GRID;
        int dirs[4], n = 0;
        for (int d = 0; d < 4; d++) {
            int nx = cx + DX[d], ny = cy + DY[d];
            if (nx > 0 && ny > 0 && nx < gw - 1 && ny < gh - 1 && cell(nx, ny) != EMPTY) dirs[n++] = d;
        }
        if (!n) { sp--; continue; }
        int d = dirs[rnd_below(n)];
        cell(cx + DX[d] / 2, cy + DY[d] / 2) = EMPTY;
        cell(cx + DX[d], cy + DY[d]) = EMPTY;
        stack[sp++] = (uint16_t)((cy + DY[d]) * MAX_GRID + cx + DX[d]);
    }

    // a few loops so the maze is not a pure tree
    int loops = cells_w * cells_h / 8;
    for (int i = 0; i < loops; i++) {
        int x = 1 + rnd_below(gw - 2), y = 1 + rnd_below(gh - 2);
        if (cell(x, y) == EMPTY) continue;
        bool horiz = (x & 1) == 0 && (y & 1) == 1;
        bool vert = (x & 1) == 1 && (y & 1) == 0;
        if (horiz || vert) cell(x, y) = EMPTY;
    }

    // wall materials in patches, so areas feel distinct
    int theme = rnd_below(4);
    for (int y = 0; y < gh; y++)
        for (int x = 0; x < gw; x++)
            if (cell(x, y) != EMPTY) {
                uint32_t h = hash((uint32_t)((x / 5) * 31 + (y / 5) * 977 + theme * 7));
                cell(x, y) = (uint8_t)(1 + (h % 4));
            }
}

static uint16_t dist[MAX_GRID * MAX_GRID];

static void bfs_from(int sx, int sy) {
    static uint16_t queue[MAX_GRID * MAX_GRID];
    for (int i = 0; i < MAX_GRID * MAX_GRID; i++) dist[i] = 0xFFFF;
    int head = 0, tail = 0;
    dist[sy * MAX_GRID + sx] = 0;
    queue[tail++] = (uint16_t)(sy * MAX_GRID + sx);
    while (head < tail) {
        int cur = queue[head++];
        int cx = cur % MAX_GRID, cy = cur / MAX_GRID;
        static const int DX[4] = {1, -1, 0, 0}, DY[4] = {0, 0, 1, -1};
        for (int d = 0; d < 4; d++) {
            int nx = cx + DX[d], ny = cy + DY[d];
            int ni = ny * MAX_GRID + nx;
            if (cell(nx, ny) == EMPTY && dist[ni] == 0xFFFF) {
                dist[ni] = (uint16_t)(dist[cur] + 1);
                queue[tail++] = (uint16_t)ni;
            }
        }
    }
}

// ---------------------------------------------------------------- game state

struct Cog {
    int32_t x, y;
    bool alive;
};
constexpr int MAX_COGS = 16;
static Cog cogs[MAX_COGS];
static int cog_count, cogs_left;

struct Player {
    int32_t x, y;  // Q16 cells
    int angle;
    int walk_phase;
};
static Player pl;

enum State { TITLE, PLAYING, PAUSED, LEVEL_CLEAR, GAME_OVER };
static State state = TITLE;
static int level;
static uint32_t score, high_score;
static int32_t time_left;  // frames (20 per second)
static uint32_t ticks;     // frames since boot
static int clear_bonus;
static bool show_map = true;
static bool door_open;
static bool touched_door;
static const char* msg;
static int msg_timer;
static bool new_high;
static const char* save_note;

constexpr int FPS = 20;

static void flash(const char* m, int frames = 40) {
    msg = m;
    msg_timer = frames;
}

// ---------------------------------------------------------------- high score on disk

static uint32_t disk_base;  // the boot device's controller
static uint32_t disk_last;  // its last sector
static uint8_t sector[512] __attribute__((aligned(4)));

static bool disk_cmd(int cmd, uint32_t lba) {
    hw::w32(disk_base + hw::D_LBA, lba);
    hw::w32(disk_base + hw::D_COUNT, 1);
    hw::w32(disk_base + hw::D_DMA, (uint32_t)(uintptr_t)sector);
    hw::w32(disk_base + hw::D_COMMAND, (uint32_t)cmd);
    uint32_t s;
    while (((s = hw::r32(disk_base + hw::D_STATUS)) & 6) == 0) {}
    hw::w32(disk_base + hw::D_STATUS, 6);
    return (s & 4) == 0;
}

constexpr uint32_t HS_MAGIC = 0x315A4D43;  // "CMZ1"

static void load_high_score() {
    if (!disk_cmd(1, disk_last)) return;
    uint32_t* w = (uint32_t*)sector;
    if (w[0] == HS_MAGIC && w[2] == (w[1] ^ 0xA5A5A5A5u)) high_score = w[1];
}

static const char* save_high_score() {
    for (int i = 0; i < 512; i++) sector[i] = 0;
    uint32_t* w = (uint32_t*)sector;
    w[0] = HS_MAGIC;
    w[1] = high_score;
    w[2] = high_score ^ 0xA5A5A5A5u;
    if (disk_base == hw::FDC && (hw::r32(hw::FDC + hw::D_STATUS) & 0x10)) return "DISK IS WRITE PROTECTED";
    return disk_cmd(2, disk_last) ? "SAVED TO DISK" : "DISK WRITE FAILED";
}

// ---------------------------------------------------------------- level setup

static void place_player_facing_open() {
    pl.x = ONE + ONE / 2;
    pl.y = ONE + ONE / 2;
    pl.angle = cell(2, 1) == EMPTY ? 0 : 256;
    pl.walk_phase = 0;
}

static void start_level(int lv) {
    level = lv;
    int cw = imin(4 + lv, 15), ch = imin(4 + lv, 15);
    make_maze(cw, ch);
    place_player_facing_open();
    bfs_from(1, 1);

    // exit: the border cell farthest from the start
    int best = -1;
    for (int y = 1; y < gh - 1; y++)
        for (int x = 1; x < gw - 1; x++) {
            if (cell(x, y) != EMPTY) continue;
            int d = dist[y * MAX_GRID + x];
            if (d == 0xFFFF) continue;
            int bx = -1, by = -1;
            if (x == 1) bx = 0, by = y;
            else if (x == gw - 2) bx = gw - 1, by = y;
            else if (y == 1) bx = x, by = 0;
            else if (y == gh - 2) bx = x, by = gh - 1;
            if (bx < 0 || d <= best) continue;
            best = d;
            door_x = bx;
            door_y = by;
        }
    cell(door_x, door_y) = DOOR;
    door_open = false;

    // cogs: prefer dead ends, never near the start
    cog_count = imin(3 + lv, MAX_COGS);
    int placed = 0;
    for (int pass = 0; pass < 2 && placed < cog_count; pass++)
        for (int tries = 0; tries < 400 && placed < cog_count; tries++) {
            int x = 1 + 2 * rnd_below((gw - 1) / 2), y = 1 + 2 * rnd_below((gh - 1) / 2);
            if (dist[y * MAX_GRID + x] < 4 || dist[y * MAX_GRID + x] == 0xFFFF) continue;
            int walls = (cell(x + 1, y) != EMPTY) + (cell(x - 1, y) != EMPTY) + (cell(x, y + 1) != EMPTY) +
                        (cell(x, y - 1) != EMPTY);
            if (pass == 0 && walls < 3) continue;
            bool taken = false;
            for (int i = 0; i < placed; i++)
                if (cogs[i].x >> FIX == x && cogs[i].y >> FIX == y) taken = true;
            if (taken) continue;
            cogs[placed++] = {x * ONE + ONE / 2, y * ONE + ONE / 2, true};
        }
    cog_count = cogs_left = placed;
    time_left += (40 + 12 * lv) * FPS;
    flash("COLLECT ALL THE COGS", 60);
}

static void new_game() {
    score = 0;
    time_left = 0;
    new_high = false;
    save_note = nullptr;
    start_level(1);
    state = PLAYING;
}

// ---------------------------------------------------------------- rendering

constexpr int32_t WALL_K = 190;     // wall height in pixels at distance 1
constexpr int32_t PLANE_K = 43254;  // 0.66 in Q16: field of view about 66 degrees
static int32_t cam_x[COLS];         // -1..1 across the view, Q16
static int32_t zbuf[COLS];
static uint16_t row_color[VIEW_H];

static int light_for(int32_t d) { return clampi((d * 3) >> 18, 0, 12); }

struct View {
    int32_t px, py, dx, dy, plx, ply;
    int horizon;
};

static View make_view(int32_t x, int32_t y, int angle, int bob) {
    View v;
    v.px = x;
    v.py = y;
    v.dx = fcos(angle);
    v.dy = fsin(angle);
    v.plx = -fmul(v.dy, PLANE_K);
    v.ply = fmul(v.dx, PLANE_K);
    v.horizon = VIEW_H / 2 + bob;
    return v;
}

static void render_walls(const View& v) {
    for (int y = 0; y < VIEW_H; y++) {
        int d = iabs(y - v.horizon);
        if (d == 0) d = 1;
        int32_t rd = (WALL_K << 15) / d;  // distance of this floor or ceiling row
        int l = light_for(rd);
        row_color[y] = y < v.horizon ? cmap16[l][col(STEEL, 6)] : cmap16[l][col(ANDESITE, 7)];
    }

    int mx0 = v.px >> FIX, my0 = v.py >> FIX;
    for (int c = 0; c < COLS; c++) {
        int32_t rdx = v.dx + fmul(v.plx, cam_x[c]);
        int32_t rdy = v.dy + fmul(v.ply, cam_x[c]);
        int mx = mx0, my = my0;
        uint32_t adx = (uint32_t)iabs(rdx), ady = (uint32_t)iabs(rdy);
        uint32_t ddx = adx < 64 ? (1u << 26) : imin((int)(0xFFFFFFFFu / adx), 1 << 26);
        uint32_t ddy = ady < 64 ? (1u << 26) : imin((int)(0xFFFFFFFFu / ady), 1 << 26);
        int stx = rdx < 0 ? -1 : 1, sty = rdy < 0 ? -1 : 1;
        uint32_t fx = (uint32_t)(v.px & (ONE - 1)), fy = (uint32_t)(v.py & (ONE - 1));
        uint32_t sdx = (uint32_t)(((uint64_t)(rdx < 0 ? fx : ONE - fx) * ddx) >> FIX);
        uint32_t sdy = (uint32_t)(((uint64_t)(rdy < 0 ? fy : ONE - fy) * ddy) >> FIX);
        int side = 0;
        uint8_t tile = 1;
        for (int guard = 0; guard < 64; guard++) {
            if (sdx < sdy) {
                sdx += ddx;
                mx += stx;
                side = 0;
            } else {
                sdy += ddy;
                my += sty;
                side = 1;
            }
            int i = my * MAX_GRID + mx;
            seen[i] = 1;
            tile = grid[i];
            if (tile) break;
        }
        int32_t perp = (int32_t)(side == 0 ? sdx - ddx : sdy - ddy);
        if (perp < 0x800) perp = 0x800;
        zbuf[c] = perp;

        int line_h = (WALL_K << FIX) / perp;
        int32_t wall = side == 0 ? v.py + fmul(perp, rdy) : v.px + fmul(perp, rdx);
        int tx = (wall & (ONE - 1)) >> 10;
        if (side == 0 && rdx < 0) tx = TEX - 1 - tx;  // keep textures (the EXIT sign) unmirrored
        if (side == 1 && rdy > 0) tx = TEX - 1 - tx;

        int t = tile == DOOR ? (door_open ? T_DOOR_OPEN : T_DOOR_LOCKED) : tile - 1;
        const uint8_t* texcol = textures[t] + tx * TEX;
        int l = light_for(perp) + side;
        if (tile == DOOR && door_open) l = imax(0, l - 4);  // the open door glows
        const uint16_t* cm = cmap16[l];

        int top = v.horizon - line_h / 2;
        int y0 = imax(top, 0), y1 = imin(top + line_h, VIEW_H);
        int32_t step = (TEX << FIX) / line_h;
        int32_t tpos = (y0 - top) * step;
        uint16_t* p = (uint16_t*)(fb + c * 2);
        int y = 0;
        for (; y < y0; y++, p += SW / 2) *p = row_color[y];
        for (; y < y1; y++, p += SW / 2, tpos += step) *p = cm[texcol[(tpos >> FIX) & (TEX - 1)]];
        for (; y < VIEW_H; y++, p += SW / 2) *p = row_color[y];
    }
}

static void render_cogs(const View& v) {
    // project, then draw far to near
    struct Proj {
        int32_t depth;
        int index;
    } order[MAX_COGS];
    int n = 0;
    for (int i = 0; i < cog_count; i++) {
        if (!cogs[i].alive) continue;
        int32_t sx = cogs[i].x - v.px, sy = cogs[i].y - v.py;
        int32_t depth = fmul(v.dx, sx) + fmul(v.dy, sy);
        if (depth < 0x3000) continue;
        order[n++] = {depth, i};
    }
    for (int i = 1; i < n; i++)
        for (int j = i; j > 0 && order[j].depth > order[j - 1].depth; j--) {
            Proj t = order[j];
            order[j] = order[j - 1];
            order[j - 1] = t;
        }

    for (int k = 0; k < n; k++) {
        int i = order[k].index;
        int32_t depth = order[k].depth;
        int32_t sx = cogs[i].x - v.px, sy = cogs[i].y - v.py;
        int32_t lateral = fmul(v.dx, sy) - fmul(v.dy, sx);
        lateral = (int32_t)(((int64_t)lateral * 99297) >> FIX);  // divide by 0.66
        int center = COLS / 2 + lateral * (COLS / 2) / depth;  // fits 32 bits: |lateral| < 50 cells
        int h = imin((WALL_K << 15) / depth, 400);
        if (h < 2) continue;
        int wcols = imax((h * 5) >> 3, 1);
        int bob = (fsin((int)(ticks * 24 + i * 200)) * h) >> (FIX + 4);
        int top = v.horizon + h / 4 - h / 2 + bob;
        int left = center - wcols / 2;
        int c0 = imax(left, 0), c1 = imin(left + wcols, COLS);
        if (c0 >= c1) continue;
        int y0 = imax(top, 0), y1 = imin(top + h, VIEW_H);
        const uint8_t* frame = cog_frames[(ticks + i * 3) & (COG_FRAMES - 1)];
        const uint16_t* cm = cmap16[imax(light_for(depth) - 3, 0)];
        int32_t ystep = (TEX << FIX) / h, xstep = (TEX << FIX) / wcols;
        for (int c = c0; c < c1; c++) {
            if (depth >= zbuf[c]) continue;
            int tx = ((c - left) * xstep) >> FIX;
            const uint8_t* texcol = frame + imin(tx, TEX - 1) * TEX;
            int32_t tpos = (y0 - top) * ystep;
            uint16_t* p = (uint16_t*)(fb + y0 * SW + c * 2);
            for (int y = y0; y < y1; y++, p += SW / 2, tpos += ystep) {
                uint8_t t = texcol[(tpos >> FIX) & (TEX - 1)];
                if (t != TRANSPARENT) *p = cm[t];
            }
        }
    }
}

static void render_minimap() {
    int ts = gw <= 21 ? 3 : 2;
    int w = gw * ts, h = gh * ts;
    int x0 = SW - w - 6, y0 = 6;
    fill_rect(x0 - 2, y0 - 2, w + 4, h + 4, col(BRASS, 8));
    fill_rect(x0 - 1, y0 - 1, w + 2, h + 2, col(STONE, 0));
    for (int y = 0; y < gh; y++)
        for (int x = 0; x < gw; x++) {
            int i = y * MAX_GRID + x;
            if (!seen[i]) continue;
            uint8_t t = grid[i];
            uint8_t c = t == EMPTY ? col(STEEL, 3) : t == DOOR ? (door_open ? col(GREEN, 14) : col(RED, 12))
                                                               : col(BRASS, 10);
            fill_rect(x0 + x * ts, y0 + y * ts, ts, ts, c);
        }
    for (int k = 0; k < cog_count; k++)
        if (cogs[k].alive && seen[(cogs[k].y >> FIX) * MAX_GRID + (cogs[k].x >> FIX)])
            fill_rect(x0 + (cogs[k].x * ts >> FIX) - 1, y0 + (cogs[k].y * ts >> FIX) - 1, 2, 2,
                      (ticks & 4) ? col(YELLOW, 15) : col(COPPER, 12));
    int px = x0 + (pl.x * ts >> FIX), py = y0 + (pl.y * ts >> FIX);
    fill_rect(px - 1, py - 1, 2, 2, col(WHITE, 15));
    pset(px + (fcos(pl.angle) * 3 >> FIX), py + (fsin(pl.angle) * 3 >> FIX), col(CYAN, 15));
}

static void render_hud(bool stats) {
    // brass-trimmed steel panel
    fill_rect(0, VIEW_H, SW, 2, col(BRASS, 12));
    fill_rect(0, VIEW_H + 2, SW, 1, col(BRASS, 6));
    for (int y = VIEW_H + 3; y < SH; y++) fill_rect(0, y, SW, 1, col(STEEL, 5 - (y - VIEW_H) / 12));
    static const int RIVETS[4][2] = {{4, 167}, {315, 167}, {4, 195}, {315, 195}};
    for (auto& r : RIVETS) {
        fill_rect(r[0] - 1, r[1] - 1, 3, 3, col(STONE, 7));
        pset(r[0] - 1, r[1] - 1, col(STONE, 13));
    }

    char a[16], b[40];
    if (!stats) {
        text_shadow(12, 168, "CREATE FLIPPIES PC", col(BRASS, 13), 1);
        text_shadow(12, 182, "RV32I  10 MHZ", col(STONE, 10), 1);
        text_shadow(200, 168, "HIGH SCORE", col(STONE, 11), 1);
        text_shadow(200, 179, fmt_num(a, high_score, 6), col(YELLOW, 15), 2);
        return;
    }
    // cog icon
    const uint8_t* icon = cog_frames[(ticks >> 1) & 7];
    for (int x = 0; x < 16; x++)
        for (int y = 0; y < 16; y++) {
            uint8_t t = icon[(x * 4) * TEX + y * 4];
            if (t != TRANSPARENT) pset(12 + x, 168 + y, t);
        }
    cat(b, fmt_num(a, (uint32_t)(cog_count - cogs_left), 1), "/");
    char total[8];
    cat(b, b, fmt_num(total, (uint32_t)cog_count, 1));
    text_shadow(34, 169, b, cogs_left ? col(BRASS, 15) : col(GREEN, 15), 2);

    int secs = (time_left + FPS - 1) / FPS;
    bool urgent = secs <= 10;
    uint8_t tc = urgent ? ((ticks & 4) ? col(RED, 15) : col(RED, 9)) : col(WHITE, 14);
    text_shadow(118, 169, "TIME", col(STONE, 11), 1);
    text_shadow(118, 179, fmt_num(a, (uint32_t)imax(secs, 0), 3), tc, 2);

    text_shadow(176, 169, "LEVEL", col(STONE, 11), 1);
    text_shadow(176, 179, fmt_num(a, (uint32_t)level, 1), col(CYAN, 14), 2);

    text_shadow(232, 169, "SCORE", col(STONE, 11), 1);
    text_shadow(232, 179, fmt_num(a, score, 6), col(YELLOW, 15), 1);
    text_shadow(232, 189, cat(b, "HI ", fmt_num(a, high_score, 6)), col(STONE, 9), 1);

    text(34, 188, door_open ? "EXIT OPEN!" : "FIND COGS", door_open ? col(GREEN, 13) : col(STONE, 9), 1);
}

static void panel(int y, int h) {
    for (int yy = y; yy < y + h; yy++) {
        uint8_t* p = fb + yy * SW;
        for (int x = 40; x < SW - 40; x++)  // darken the view behind the text
            p[x] = (uint8_t)((p[x] & 0xF0) | ((p[x] & 15) >> 2));
    }
    fill_rect(40, y, SW - 80, 1, col(BRASS, 12));
    fill_rect(40, y + h - 1, SW - 80, 1, col(BRASS, 6));
}

// ---------------------------------------------------------------- game logic

static bool solid_at(int32_t x, int32_t y) {
    int cx = x >> FIX, cy = y >> FIX;
    if (cx < 0 || cy < 0 || cx >= gw || cy >= gh) return true;
    uint8_t t = cell(cx, cy);
    if (t == DOOR && door_open) touched_door = true;
    return t != EMPTY;
}

static bool blocked(int32_t x, int32_t y) {
    constexpr int32_t R = ONE / 5;
    return solid_at(x - R, y - R) || solid_at(x + R, y - R) || solid_at(x - R, y + R) || solid_at(x + R, y + R);
}

static void update_play(int steps) {
    if (hit(key::P)) {
        state = PAUSED;
        return;
    }
    if (hit(key::M)) show_map = !show_map;
    for (int s = 0; s < steps && state == PLAYING; s++) {
        bool run = held(key::LSHIFT) || held(key::RSHIFT);
        int turn = 0, fwd = 0, strafe = 0;
        if (held(key::A) || held(key::LEFT)) turn -= 1;
        if (held(key::D) || held(key::RIGHT)) turn += 1;
        if (held(key::W) || held(key::UP)) fwd += 1;
        if (held(key::S) || held(key::DOWN)) fwd -= 1;
        if (held(key::Q)) strafe -= 1;
        if (held(key::E)) strafe += 1;
        pl.angle = (pl.angle + turn * (run ? 30 : 22)) & (ANG - 1);

        int32_t speed = run ? ONE * 11 / 50 : ONE * 7 / 50;  // cells per frame
        int32_t dx = fcos(pl.angle), dy = fsin(pl.angle);
        int32_t mx = fmul(dx, fwd * speed) - fmul(dy, strafe * speed * 3 / 4);
        int32_t my = fmul(dy, fwd * speed) + fmul(dx, strafe * speed * 3 / 4);
        if (mx || my) pl.walk_phase += run ? 90 : 64;
        touched_door = false;
        if (!blocked(pl.x + mx, pl.y)) pl.x += mx;
        if (!blocked(pl.x, pl.y + my)) pl.y += my;
        if (touched_door && fwd > 0) {
            clear_bonus = (time_left / FPS) * 10 + level * 250;
            score += (uint32_t)clear_bonus;
            state = LEVEL_CLEAR;
            return;
        }

        for (int i = 0; i < cog_count; i++) {
            if (!cogs[i].alive) continue;
            int32_t ddx = cogs[i].x - pl.x, ddy = cogs[i].y - pl.y;
            if (iabs(ddx) < ONE * 45 / 100 && iabs(ddy) < ONE * 45 / 100) {
                cogs[i].alive = false;
                cogs_left--;
                score += 100;
                time_left += 5 * FPS;
                if (cogs_left == 0) {
                    door_open = true;
                    seen[door_y * MAX_GRID + door_x] = 1;
                    flash("ALL COGS! THE EXIT IS OPEN", 70);
                } else {
                    flash("COG +100  +5 SEC", 25);
                }
            }
        }

        if (--time_left <= 0) {
            time_left = 0;
            state = GAME_OVER;
            if (score > high_score) {
                high_score = score;
                new_high = true;
                save_note = save_high_score();
            }
            return;
        }
        if (msg_timer > 0) msg_timer--;
    }
}

// ---------------------------------------------------------------- frame pacing

static uint32_t last_frame;
static uint32_t page;  // page being drawn: 0 or 1

// Shows the page just drawn at the next frame end; returns frames elapsed since the last flip.
static int flip() {
    while ((hw::r32(hw::VGA_STATUS) & 1) == 0) asm volatile("wfi");
    hw::w32(hw::VGA_STATUS, 1);
    hw::w32(hw::VGA_START, page * PAGE_SIZE);
    page ^= 1;
    fb = (uint8_t*)(hw::VRAM + page * PAGE_SIZE);
    uint32_t now = hw::r32(hw::VGA_FRAME);
    int elapsed = (int)(now - last_frame);
    last_frame = now;
    return clampi(elapsed, 1, 4);
}

static void text_screen_message(const char* line1, const char* line2) {
    // still in text mode: write straight into the 80x25 buffer
    volatile uint16_t* t = (volatile uint16_t*)hw::VRAM;
    for (int i = 0; i < 80 * 25; i++) t[i] = 0x0720;
    for (int i = 0; line1[i]; i++) t[10 * 80 + 2 + i] = (uint16_t)(0x0E00 | (uint8_t)line1[i]);
    for (int i = 0; line2[i]; i++) t[12 * 80 + 2 + i] = (uint16_t)(0x0700 | (uint8_t)line2[i]);
}

// ---------------------------------------------------------------- main

extern "C" int main(uint32_t ram_size, uint32_t boot_device, uint32_t capacity) {
    if (ram_size < 256 * 1024) {
        text_screen_message("COG MAZE needs at least 256 KiB of RAM.", "Install a bigger RAM stick and reboot.");
        for (;;) asm volatile("wfi");
    }
    disk_base = boot_device == 1 ? hw::FDC : hw::HDD;
    disk_last = capacity - 1;
    rng_state = hw::r32(hw::RTC) ^ (hw::r32(hw::CLINT_MTIME) * 2654435761u) ^ 0x9E3779B9u;
    if (!rng_state) rng_state = 1;

    text_screen_message("COG MAZE", "Building the workshop...");
    load_high_score();
    for (int c = 0; c < COLS; c++) cam_x[c] = (int32_t)((2 * c + 1) * ONE / COLS) - ONE;
    make_textures();
    make_cogs();
    probe_keyboard();

    // VGA 320x200x8, page flipping, vertical blank wakes the CPU from WFI
    hw::w32(hw::VGA_MODE, 1);
    init_palette();
    hw::w32(hw::VGA_CTRL, 1 | 2);
    hw::w32(hw::IRQC + 0x04, 1u << 6);
    asm volatile("csrw mie, %0" ::"r"(1u << 11));
    page = 1;
    fb = (uint8_t*)(hw::VRAM + PAGE_SIZE);
    last_frame = hw::r32(hw::VGA_FRAME);

    // a maze to wander through behind the title
    level = 3;
    make_maze(9, 9);
    place_player_facing_open();
    cog_count = 0;
    int demo_angle = 0;

    char a[16], b[48];
    int steps = 1;
    for (;;) {
        poll_keyboard();
        if (kb_port < 0 && (ticks % FPS) == 0) probe_keyboard();

        switch (state) {
        case TITLE: {
            demo_angle = (demo_angle + 3 * steps) & (ANG - 1);
            View v = make_view(pl.x, pl.y, demo_angle, (fsin((int)ticks * 20) * 2) >> FIX);
            render_walls(v);
            render_hud(false);
            panel(18, 124);
            uint8_t gold = col(BRASS, 13 + ((ticks >> 2) & 1) * 2);
            text_center(28, "COG MAZE", gold, 4);
            text_center(66, "COLLECT EVERY COG, THEN FIND THE EXIT", col(WHITE, 14));
            text_center(78, "BEFORE THE CLOCK RUNS OUT", col(WHITE, 14));
            text_center(94, "W A S D / ARROWS  MOVE AND TURN", col(STONE, 12));
            text_center(104, "Q E  STRAFE   SHIFT  RUN", col(STONE, 12));
            text_center(114, "M  MAP   P  PAUSE", col(STONE, 12));
            if ((ticks >> 3) & 1) text_center(128, "PRESS ENTER", col(GREEN, 15), 1);
            if (kb_port < 0) text_center(128, "NO KEYBOARD FOUND", col(RED, 15), 1);
            if (hit_confirm()) new_game();
            break;
        }
        case PLAYING:
        case PAUSED: {
            if (state == PLAYING) update_play(steps);
            else if (hit(key::P) || hit_confirm()) state = PLAYING;
            int bob = (fsin(pl.walk_phase) * 2) >> FIX;
            View v = make_view(pl.x, pl.y, pl.angle, bob);
            render_walls(v);
            render_cogs(v);
            if (show_map) render_minimap();
            render_hud(true);
            if (msg_timer > 0) text_center(20, msg, col(YELLOW, 15), 1);
            if (state == PAUSED) {
                panel(60, 40);
                text_center(68, "PAUSED", col(WHITE, 15), 3);
            }
            break;
        }
        case LEVEL_CLEAR: {
            View v = make_view(pl.x, pl.y, pl.angle, 0);
            render_walls(v);
            render_hud(true);
            panel(36, 90);
            text_center(46, cat(b, "LEVEL ", fmt_num(a, (uint32_t)level, 1), " CLEAR!"), col(GREEN, 15), 3);
            text_center(80, cat(b, "BONUS +", fmt_num(a, (uint32_t)clear_bonus, 1)), col(YELLOW, 15), 2);
            if ((ticks >> 3) & 1) text_center(108, "PRESS ENTER FOR THE NEXT MAZE", col(WHITE, 14));
            if (hit_confirm()) {
                start_level(level + 1);
                state = PLAYING;
            }
            break;
        }
        case GAME_OVER: {
            View v = make_view(pl.x, pl.y, pl.angle, 0);
            render_walls(v);
            render_hud(true);
            panel(30, 104);
            text_center(40, "TIME'S UP", col(RED, 15), 3);
            text_center(72, cat(b, "SCORE ", fmt_num(a, score, 1)), col(YELLOW, 15), 2);
            if (new_high) {
                text_center(94, "NEW HIGH SCORE!", col(GREEN, 15), 1);
                if (save_note) text_center(104, save_note, col(STONE, 11), 1);
            }
            if ((ticks >> 3) & 1) text_center(120, "PRESS ENTER", col(WHITE, 14));
            if (hit_confirm()) {
                state = TITLE;
                make_maze(9, 9);
                place_player_facing_open();
                cog_count = 0;
            }
            break;
        }
        }

        steps = flip();
        ticks += (uint32_t)steps;
    }
}
