"""Copies the one authored DIN power supply into every variant in PsuType's table,
and paints each copy's rating onto its texture.

Every variant is the authored `PSU-DCDC-230V-24V-100W-3M` with a different rating:
the same body, so the same export under a new name, pointing at a texture of its
own. The textures differ only in the rating printed on the front.

The front of the supply is the `up` face of each module's upper base, and the three
faces are cut from three places in the texture, each turned half round (the uv runs
backwards in both u and v):

    module 1   texture x 0..31,  y 64..175
    module 2   texture x 32..63, y 144..255
    module 3   texture x 64..95, y 96..207

Laid side by side and read the right way up they make one 96 x 112 front, and the
label is set in that frame and written back through the same mapping. Read that
way, the authored front says

    FUWWY ELECTRIC PSU    brand, row 20, fixed
    230 VDC-24 VDC        input-output, from x 11, row 25   <- rewritten
    100 W                 power, from x 11, row 32          <- rewritten

in a 3x5 font (W is 5 wide), ink (56, 56, 56) on the case's (168, 168, 168), one
pixel between glyphs, and a space is one more pixel of gap. The terminal names (VCC, GND, V+, V-,
DC OK) are a different, taller hand and the same on every variant.

The texture also holds a stray `230V-3M-` at x 0..31, y 217..223 that no face maps;
it is left as authored.

Before painting anything this reads the authored label back and fails unless it
says `230 VDC-24 VDC` / `100 W`, so a re-authored base that moved its text is an
error rather than a dozen smudged fronts.

Export copies are only made where none exists, so a variant the user has since
re-authored is never overwritten (pass --force to recopy them all); the textures
are always repainted. Afterwards run gen_psu.py to build the models.

    python tools/copy_psu_variants.py
    python tools/gen_psu.py
"""

import json
import pathlib
import sys

from PIL import Image

ROOT = pathlib.Path(__file__).resolve().parent.parent / "src" / "main" / "resources"
ASSETS = ROOT / "assets" / "create_flippies"
BLOCK_MODELS = ASSETS / "models" / "block"
TEXTURES = ASSETS / "textures" / "block"

SOURCE = "PSU-DCDC-230V-24V-100W-3M"
SOURCE_ID = "psu_dcdc_230v_24v_100w_3m"

# PsuType.TABLE: input x output, all 100 W on three modules. Keep in step with it.
INPUTS = (48, 120, 230)
OUTPUTS = (5, 12, 24, 48)
WATTS = 100
MODULES = 3

INK = (56, 56, 56, 255)
PAPER = (168, 168, 168, 255)

FRONT_W, FRONT_H = 96, 112
# Top of each module's front face in the texture (its x is 32 per module).
FACE_TOP = (64, 144, 96)

RATING_AT = (11, 25)
POWER_AT = (11, 32)
# The box the two rewritten lines may use: to the end of the rule above them.
LINE_RIGHT = 85

GLYPHS = {
    "0": ("###", "#.#", "#.#", "#.#", "###"),
    "1": (".#.", "##.", ".#.", ".#.", "###"),
    "2": ("###", "..#", "###", "#..", "###"),
    "3": ("###", "..#", "###", "..#", "###"),
    "4": ("#.#", "#.#", "###", "..#", "..#"),
    "5": ("###", "#..", "###", "..#", "###"),
    "6": ("###", "#..", "###", "#.#", "###"),
    "7": ("###", "..#", "..#", "..#", "..#"),
    "8": ("###", "#.#", "###", "#.#", "###"),
    "9": ("###", "#.#", "###", "..#", "###"),
    "V": ("#.#", "#.#", "#.#", "#.#", ".#."),
    "D": ("##.", "#.#", "#.#", "#.#", "##."),
    "C": (".##", "#..", "#..", "#..", ".##"),
    "W": ("#.#.#", "#.#.#", "#.#.#", "#.#.#", ".#.#."),
    "-": ("...", "...", "###", "...", "..."),
    " ": ("",) * 5,
}


def ident(volts_in, volts_out, watts, modules):
    return "psu_dcdc_%dv_%dv_%dw_%dm" % (volts_in, volts_out, watts, modules)


def stem(volts_in, volts_out, watts, modules):
    return "PSU-DCDC-%dV-%dV-%dW-%dM" % (volts_in, volts_out, watts, modules)


def variants():
    return [(i, o) for i in INPUTS for o in OUTPUTS]


def texel(x, y):
    """The texture pixel behind front pixel (x, y), read the right way up."""
    module, local = divmod(x, 32)
    return module * 32 + 31 - local, FACE_TOP[module] + FRONT_H - 1 - y


def width(text):
    return sum(len(GLYPHS[c][0]) + 1 for c in text) - 1


def mask(text):
    """The set front pixels of `text` set from (0, 0)."""
    out, x = set(), 0
    for c in text:
        glyph = GLYPHS[c]
        for row, line in enumerate(glyph):
            for col, mark in enumerate(line):
                if mark == "#":
                    out.add((x + col, row))
        x += len(glyph[0]) + 1
    return out


def reads(image, at, text):
    """Whether the line at `at` is exactly `text` and nothing else, to the box's end."""
    ink = {(at[0] + x, at[1] + y) for x, y in mask(text)}
    for y in range(at[1], at[1] + 5):
        for x in range(at[0], LINE_RIGHT + 1):
            if (image.getpixel(texel(x, y)) == INK) != ((x, y) in ink):
                return False
    return True


def paint(image, at, text):
    if at[0] + width(text) - 1 > LINE_RIGHT:
        raise SystemExit("%r does not fit the PSU front" % text)
    for y in range(at[1], at[1] + 5):
        for x in range(at[0], LINE_RIGHT + 1):
            image.putpixel(texel(x, y), PAPER)
    for x, y in mask(text):
        image.putpixel(texel(at[0] + x, at[1] + y), INK)


def rating(volts_in, volts_out):
    return "%d VDC-%d VDC" % (volts_in, volts_out)


def power(watts):
    return "%d W" % watts


def main(argv):
    force = "--force" in argv
    base = Image.open(TEXTURES / (SOURCE_ID + ".png")).convert("RGBA")
    if not (reads(base, RATING_AT, rating(230, 24)) and reads(base, POWER_AT, power(WATTS))):
        raise SystemExit("%s.png no longer says '230 VDC-24 VDC' / '100 W' where this expects; "
                         "update RATING_AT / POWER_AT / the font" % SOURCE_ID)
    export = json.loads((BLOCK_MODELS / (SOURCE + ".json")).read_text(encoding="utf-8"))

    for volts_in, volts_out in variants():
        target = ident(volts_in, volts_out, WATTS, MODULES)
        image = base.copy()
        paint(image, RATING_AT, rating(volts_in, volts_out))
        paint(image, POWER_AT, power(WATTS))
        if target == SOURCE_ID:
            if image.tobytes() != base.tobytes():
                raise SystemExit("repainting the base does not reproduce it; the font is off")
            print("%s: base, repaint matches" % target)
            continue
        image.save(TEXTURES / (target + ".png"))

        copy = BLOCK_MODELS / (stem(volts_in, volts_out, WATTS, MODULES) + ".json")
        if copy.exists() and not force:
            print("%s: texture painted, export kept" % target)
            continue
        data = json.loads(json.dumps(export))
        for key, value in data["textures"].items():
            if value.endswith("/" + SOURCE_ID):
                data["textures"][key] = value[: -len(SOURCE_ID)] + target
        copy.write_text(json.dumps(data, indent="\t") + "\n", encoding="utf-8", newline="\n")
        print("%s: texture painted, export copied" % target)


if __name__ == "__main__":
    main(sys.argv[1:])
