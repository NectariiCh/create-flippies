"""Builds the DIN power supplies that are not three modules wide out of the one that is.

The authored supply, `PSU-DCDC-230V-24V-100W-3M`, is three modules (Blockbench
groups M1..M3), each the same lower base, hooks and upper base. Every other width
is those modules again:

    30 W   1 module    M1 alone; both pairs on module 1, stacked (see below)
    60 W   2 modules   M1, M3; no DC OK
    150 W  4 modules   M1, M2, M2, M3; DC OK in module 3 as on the 3M, module 4 blank

so the middle modules come from M2 and the last from M3, whose outer faces are the
case's ends. The pins are the authored ones moved to where `PsuGeometry` puts them
for that width -- gen_psu.py checks them, as it does the 3M's. On one module there
is no second module for GND and V-, so each pair is stacked on its face: the -
pin at the usual height, nearer the rail and the wall, the + pin a pixel above it,
nearer the player.

What a player reads is set again for each width, in a texture of the variant's own
(`<id>_front.png`, slot 4). The rest of the case -- sides, hooks, ends -- keeps the
authored texture in slot 0, the 230 V / 24 V one, whose own front is simply not
mapped. The front texture is 512 square, 32 px per model pixel like the authored
one, and holds per module i (counting from x = 0):

    upper base front   x 32i, y 0,   32 x 112  uv [i+1, 3.5, i, 0] (turned half round, as authored)
    lower base strip   x 32i, y 128, 32 x 160  uv [i, 4, i+1, 9]   (the screw heads either side)
    1M only: the +z and -z faces of the lower base, x 256 / 288, y 0, 32 x 80,
             each marked + and - beside its pins

Everything drawn is lifted from the authored front, read the right way up the way
copy_psu_variants.py reads it (outputs at the top, module 1 on the left): the
terminal names in their two hands, the rules, the FUWWY ELECTRIC PSU logo by word,
and the screw heads; the ratings are set in copy_psu_variants.py's 3x5 font. The
1M's ends say both of what is there, the upper pin first: V+/V- over the outputs,
VCC/GND over the inputs.

Exports are written only where none exists (pass --force to rebuild them), so one
re-authored in Blockbench is kept; front textures are always repainted. Then run
gen_psu.py. The matrix is PsuType's; keep INPUTS, OUTPUTS and SIZES in step with it.

    python tools/make_psu_widths.py
    python tools/gen_psu.py
"""

import json
import pathlib
import sys

from PIL import Image

from copy_psu_variants import GLYPHS, INK, PAPER, mask, power, width

ROOT = pathlib.Path(__file__).resolve().parent.parent / "src" / "main" / "resources"
ASSETS = ROOT / "assets" / "create_flippies"
BLOCK_MODELS = ASSETS / "models" / "block"
TEXTURES = ASSETS / "textures" / "block"

SOURCE = "PSU-DCDC-230V-24V-100W-3M"
SOURCE_ID = "psu_dcdc_230v_24v_100w_3m"
BODY = "create_flippies:block/" + SOURCE_ID
COPPER = "create_flippies:block/copper_contacts"
FRONT_SLOT = "4"

INPUTS = (48, 120, 230)
OUTPUTS = (5, 12, 24, 48)
# watts -> modules, for every width but the authored three
SIZES = ((30, 1), (60, 2), (150, 4))

# The authored front, read the right way up: 32 px per module, 112 rows.
FACE_TOP = (64, 144, 96)
FRONT_H = 112
# The authored lower base strips: both screws (modules 1, 2) and DC OK's alone (3).
STRIP_BOTH_X, STRIP_ONE_X, STRIP_Y, STRIP_H = 192, 224, 96, 160
# The authored lower base's +z face, the colour of the 1M's marked sides.
SIDE_FACE = (128, 144, 32, 56)

SIZE = 512
STRIP_TOP = 128
SIDE_X = {"south": 256, "north": 288}
SIDE_H = 80

# Elements of the authored export, by module: body parts and pins.
MODULE_ELEMENTS = {0: range(0, 8), 1: range(8, 16), 2: range(16, 23)}
PIN_NAMES = ("VCC", "GND", "V+", "V-", "DC OK")

SLASH_TALL = ("..#", "..#", ".#.", ".#.", "#..", "#..")
SLASH_SHORT = ("..#", ".#.", ".#.", "#..")
PLUS = (".#.", "###", ".#.")
MINUS = ("###",)

# Where the 1M's + and - marks sit on a side face: beside the pin, at its height.
MARK_X = 26
MARK_ROWS = {"+": 24, "-": 56}


def ident(volts_in, volts_out, watts, modules):
    return "psu_dcdc_%dv_%dv_%dw_%dm" % (volts_in, volts_out, watts, modules)


def stem(volts_in, volts_out, watts, modules):
    return "PSU-DCDC-%dV-%dV-%dW-%dM" % (volts_in, volts_out, watts, modules)


# ------------------------------------------------------------ sprites ---

class Sprite:
    """Pixels lifted from somewhere, drawn over a picture; the case colour is clear."""

    def __init__(self, rows):
        self.rows = rows
        self.w = len(rows[0])
        self.h = len(rows)

    @staticmethod
    def cut(read, x0, y0, x1, y1):
        return Sprite([[read(x, y) for x in range(x0, x1 + 1)] for y in range(y0, y1 + 1)])

    @staticmethod
    def drawn(pattern, colour):
        return Sprite([[colour if c == "#" else PAPER for c in line] for line in pattern])

    def draw(self, image, x, y):
        for dy, row in enumerate(self.rows):
            for dx, p in enumerate(row):
                if p != PAPER:
                    image.putpixel((x + dx, y + dy), p)

    @staticmethod
    def join(*parts, gap=1):
        h = max(p.h for p in parts)
        rows = [[] for _ in range(h)]
        for i, p in enumerate(parts):
            for y in range(h):
                # bottom-aligned, so a short + sits on the baseline of its V
                src = y - (h - p.h)
                rows[y].extend(p.rows[src] if 0 <= src < p.h else [PAPER] * p.w)
                if i < len(parts) - 1:
                    rows[y].extend([PAPER] * gap)
        return Sprite(rows)


def front_reader(base):
    def read(x, y):
        module, local = divmod(x, 32)
        return base.getpixel((module * 32 + 31 - local, FACE_TOP[module] + FRONT_H - 1 - y))
    return read


def ink_columns(read, x0, x1, y0, y1):
    return [x for x in range(x0, x1 + 1) if any(read(x, y) != PAPER for y in range(y0, y1 + 1))]


def words(read, y0, y1, x0, x1):
    """The logo line cut into words at gaps of three empty columns or more."""
    inked = ink_columns(read, x0, x1, y0, y1)
    runs, start, last = [], inked[0], inked[0]
    for x in inked[1:]:
        if x - last > 3:
            runs.append((start, last))
            start = x
        last = x
    runs.append((start, last))
    return [Sprite.cut(read, a, y0, b, y1) for a, b in runs]


def lift(base):
    read = front_reader(base)
    top = (2, 7)        # V+, V-, DC OK: the tall hand
    bottom = (104, 107)  # VCC, GND: the short one
    rule_colour = read(13, 11)
    label_ink = next(read(x, y) for y in range(*top) for x in range(11, 20) if read(x, y) != PAPER)
    small_ink = next(read(x, y) for y in range(104, 108) for x in range(10, 21) if read(x, y) != PAPER)
    logo = words(read, 15, 19, 0, 95)
    if len(logo) != 3:
        raise SystemExit("%s.png: the logo line is not three words any more" % SOURCE_ID)
    sprites = {
        "V+": Sprite.cut(read, 11, top[0], 19, top[1]),
        "V-": Sprite.cut(read, 43, top[0], 51, top[1]),
        "DC OK": Sprite.cut(read, 70, top[0], 90, top[1]),
        "VCC": Sprite.cut(read, 10, bottom[0], 20, bottom[1]),
        "GND": Sprite.cut(read, 42, bottom[0], 53, bottom[1]),
        "FUWWY": logo[0], "ELECTRIC": logo[1], "PSU": logo[2],
        "/tall": Sprite.drawn(SLASH_TALL, label_ink),
        "/short": Sprite.drawn(SLASH_SHORT, small_ink),
    }
    for name, sprite in sprites.items():
        if not name.startswith("/") and all(p == PAPER for row in sprite.rows for p in row):
            raise SystemExit("%s.png: nothing where %s should be" % (SOURCE_ID, name))
    return sprites, rule_colour


def screw(strip, top, size):
    """A screw head from the authored strip, the 12 px one or, at 10, with its middle row and column dropped."""
    rows = [[strip.getpixel((x, y)) for x in range(10, 22)] for y in range(top, top + 12)]
    if size == 10:
        rows = [r[:5] + r[7:] for r in rows[:5] + rows[7:]]
    return Sprite(rows)


# -------------------------------------------------------------- front ---

def text(image, x, y, line):
    if x + width(line) > image.width:
        raise SystemExit("%r does not fit a %d px front" % (line, image.width))
    for dx, dy in mask(line):
        image.putpixel((x + dx, y + dy), INK)


def rule(image, colour, y, x0, x1):
    for x in range(x0, x1 + 1):
        image.putpixel((x, y), colour)


def front(sprites, rule_colour, modules, volts_in, volts_out, watts):
    w = 32 * modules
    f = Image.new("RGBA", (w, FRONT_H), PAPER)
    if modules == 1:
        Sprite.join(sprites["V+"], sprites["/tall"], sprites["V-"]).draw(f, 4, 2)
        Sprite.join(sprites["VCC"], sprites["/short"], sprites["GND"]).draw(f, 2, 104)
        rule(f, rule_colour, 11, 2, 29)
        rule(f, rule_colour, 100, 2, 29)
        sprites["FUWWY"].draw(f, 3, 15)
        sprites["PSU"].draw(f, 3, 21)
        text(f, 3, 28, "%d VDC-" % volts_in)
        text(f, 3, 34, "%d VDC" % volts_out)
        text(f, 3, 41, power(watts))
        return f
    sprites["V+"].draw(f, 11, 2)
    sprites["V-"].draw(f, 43, 2)
    sprites["VCC"].draw(f, 10, 104)
    sprites["GND"].draw(f, 42, 104)
    if modules >= 3:
        sprites["DC OK"].draw(f, 70, 2)
    rule(f, rule_colour, 11, 13, w - 15)
    rule(f, rule_colour, 100, 13, w - 15)
    rating = "%d VDC-%d VDC" % (volts_in, volts_out)
    if modules == 2:
        sprites["FUWWY"].draw(f, 7, 15)
        Sprite.join(sprites["ELECTRIC"], sprites["PSU"], gap=4).draw(f, 7, 21)
        text(f, 7, 28, rating)
        text(f, 7, 35, power(watts))
    else:
        Sprite.join(sprites["FUWWY"], sprites["ELECTRIC"], sprites["PSU"], gap=4).draw(f, 11, 15)
        text(f, 11, 25, rating)
        text(f, 11, 32, power(watts))
    return f


def strips(base, modules):
    both = base.crop((STRIP_BOTH_X, STRIP_Y, STRIP_BOTH_X + 32, STRIP_Y + STRIP_H))
    one = base.crop((STRIP_ONE_X, STRIP_Y, STRIP_ONE_X + 32, STRIP_Y + STRIP_H))
    blank = one.copy()
    for y in range(6, 18):
        for x in range(10, 22):
            blank.putpixel((x, y), PAPER)
    if modules == 1:
        stacked = blank.copy()
        out_head, in_head = screw(both, 6, 10), screw(both, 142, 10)
        # - nearer the edge, + nearer the body: the lower tier's screw is the outer one
        out_head.draw(stacked, 11, 1)
        out_head.draw(stacked, 11, 12)
        in_head.draw(stacked, 11, 138)
        in_head.draw(stacked, 11, 149)
        return [stacked]
    out = [both, both]
    if modules >= 3:
        out.append(one)
    while len(out) < modules:
        out.append(blank)
    return out[:modules]


def side(base):
    x, y, w, h = SIDE_FACE
    face = base.crop((x, y, x + w, y + h)).resize((32, SIDE_H), Image.NEAREST)
    Sprite.drawn(PLUS, INK).draw(face, MARK_X, MARK_ROWS["+"] - 1)
    Sprite.drawn(MINUS, INK).draw(face, MARK_X, MARK_ROWS["-"])
    return face


def texture(base, sprites, rule_colour, modules, volts_in, volts_out, watts):
    image = Image.new("RGBA", (SIZE, SIZE), PAPER)
    f = front(sprites, rule_colour, modules, volts_in, volts_out, watts)
    for i, strip in enumerate(strips(base, modules)):
        # The front goes in turned half round, as the authored faces are
        face = f.crop((32 * i, 0, 32 * i + 32, FRONT_H)).rotate(180)
        image.paste(face, (32 * i, 0))
        image.paste(strip, (32 * i, STRIP_TOP))
    if modules == 1:
        marked = side(base)
        for x in SIDE_X.values():
            image.paste(marked, (x, 0))
    return image


# ------------------------------------------------------------- export ---

def template(modules, i):
    """Which authored module a module of this width is copied from."""
    if i == 0:
        return 0
    return 2 if i == modules - 1 else 1


def pin_at(name, modules):
    """(module, y offset) of a pin, as PsuGeometry places it."""
    if modules == 1:
        return 0, 1.0 if name in ("VCC", "V+") else 0.0
    return {"VCC": 0, "GND": 1, "V+": 0, "V-": 1, "DC OK": 2}[name], 0.0


def shifted(element, dx, dy=0.0):
    e = json.loads(json.dumps(element))
    e["from"] = [e["from"][0] + dx, e["from"][1] + dy, e["from"][2]]
    e["to"] = [e["to"][0] + dx, e["to"][1] + dy, e["to"][2]]
    if "rotation" in e and "origin" in e["rotation"]:
        o = e["rotation"]["origin"]
        e["rotation"]["origin"] = [o[0] + dx, o[1] + dy, o[2]]
    return e


def export(source, modules, front_texture):
    elements = source["elements"]
    by_name = {}
    for index in range(len(elements)):
        by_name.setdefault(elements[index].get("name"), (index, elements[index]))
    pins = [n for n in PIN_NAMES if n != "DC OK" or modules >= 3]

    out, groups = [], []
    for i in range(modules):
        src = template(modules, i)
        members = []
        for index in MODULE_ELEMENTS[src]:
            e = elements[index]
            if e.get("name") in PIN_NAMES:
                continue
            e = shifted(e, i - src)
            if e["name"] == "lower base":
                e["faces"]["up"] = {"uv": [i, 4, i + 1, 9], "texture": "#" + FRONT_SLOT}
                if modules == 1:
                    for face in ("north", "south"):
                        x = SIDE_X[face] / 32
                        e["faces"][face] = {"uv": [x, 0, x + 1, 2.5], "texture": "#" + FRONT_SLOT}
            elif e["name"] == "upper base":
                e["faces"]["up"] = {"uv": [i + 1, 3.5, i, 0], "texture": "#" + FRONT_SLOT}
            members.append(len(out))
            out.append(e)
        for name in pins:
            module, dy = pin_at(name, modules)
            if module != i:
                continue
            index, e = by_name[name]
            src_module = next(m for m, r in MODULE_ELEMENTS.items() if index in r)
            members.append(len(out))
            out.append(shifted(e, module - src_module, dy))
        groups.append({"name": "M%d" % (i + 1), "origin": [8, 8, 8], "color": 0, "children": members})

    data = {k: v for k, v in source.items() if k not in ("textures", "elements", "groups")}
    data["textures"] = {"0": BODY, "2": COPPER, FRONT_SLOT: front_texture, "particle": BODY}
    data["elements"] = out
    data["groups"] = groups
    return data


def main(argv):
    force = "--force" in argv
    base = Image.open(TEXTURES / (SOURCE_ID + ".png")).convert("RGBA")
    source = json.loads((BLOCK_MODELS / (SOURCE + ".json")).read_text(encoding="utf-8"))
    sprites, rule_colour = lift(base)
    made = kept = 0
    for volts_in in INPUTS:
        for volts_out in OUTPUTS:
            for watts, modules in SIZES:
                target = ident(volts_in, volts_out, watts, modules)
                tex = texture(base, sprites, rule_colour, modules, volts_in, volts_out, watts)
                tex.save(TEXTURES / (target + "_front.png"))
                path = BLOCK_MODELS / (stem(volts_in, volts_out, watts, modules) + ".json")
                if path.exists() and not force:
                    kept += 1
                    continue
                data = export(source, modules, "create_flippies:block/" + target + "_front")
                path.write_text(json.dumps(data, indent="\t") + "\n", encoding="utf-8", newline="\n")
                made += 1
    print("%d fronts painted; %d exports written, %d kept" % (made + kept, made, kept))


if __name__ == "__main__":
    main(sys.argv[1:])
