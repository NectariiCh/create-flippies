"""Paints every plug-in relay variant's texture from the one authored relay texture.

All the variants share `ss_relay_230v10a.png` and differ only in the label printed
on the relay's top face -- the `up` face, mapped at uv 0,8 (texture pixels 0,64).
The label is five lines of 3x5 seven-segment-style glyphs, and four of them change:

    SSR      kind            x 1..13, face row 1   (EMR is two pixels wider)
    SPDT                     fixed
    230V     switching       from x 1, row 13
    10A      current         from x 1, row 19
    C24V     coil            from x 5, row 25, after the authored C

Values are set left-aligned with their unit straight after them, so `24V` and
`1A` close up to the left edge like the authored lines do.

A 120 V or 230 V coil does not fit four characters, and those coils only ever come
on a relay switching the same voltage, so the user had them printed as `CF` instead.

Before painting anything this reads the authored label back and fails unless it
says SSR / 230 / 10 / 24 where the table below expects them, so a re-authored base
that moved its text is an error rather than 216 smudged labels.

Overwrites `textures/block/<id>.png` for every id copy_relay_variants.py lists; the
models that point at them are that script's job. The base itself is never written.

    python tools/gen_relay_textures.py
"""

import pathlib
import sys

from PIL import Image

sys.path.insert(0, str(pathlib.Path(__file__).resolve().parent))
from copy_relay_variants import SOURCE_TEXTURE, TEXTURES, variants  # noqa: E402

FACE_X, FACE_Y = 0, 64

INK = (41, 41, 41, 255)
PAPER = (132, 132, 132, 255)

GLYPHS = {
    "0": ("###", "#.#", "#.#", "#.#", "###"),
    "1": (".#.", "##.", ".#.", ".#.", "###"),
    "2": ("###", "..#", "###", "#..", "###"),
    "3": ("###", "..#", "###", "..#", "###"),
    "4": ("#.#", "#.#", "###", "..#", "..#"),
    "5": ("###", "#..", "###", "..#", "###"),
    "6": ("###", "#..", "###", "#.#", "###"),
    "8": ("###", "#.#", "###", "#.#", "###"),
    "F": ("###", "#..", "##.", "#..", "#.."),
    "V": ("#.#", "#.#", "#.#", "#.#", ".#."),
    "A": (".#.", "#.#", "###", "#.#", "#.#"),
}

KIND = {
    "ss": ("01100110110", "10001000101", "11101110110", "00100010101", "11001100101"),
    "em": ("0110010100110", "1000101010101", "1110101010110", "1000101010101", "0110101010101"),
}

KIND_AT = (1, 1, 13)            # x, row, width
SWITCHING_AT = (1, 13, 15)      # x, row, cleared width (widest: 230V)
CURRENT_AT = (1, 19, 11)        # widest: 10A
COIL_AT = (5, 25, 11)           # widest: 24V


def bitmap(text):
    """Rows of a string set in GLYPHS with one pixel between characters."""
    return tuple(".".join(GLYPHS[c][row] for c in text) for row in range(5))


def clear(image, x, row, width):
    for dy in range(5):
        for dx in range(width):
            image.putpixel((FACE_X + x + dx, FACE_Y + row + dy), PAPER)


def stamp(image, x, row, rows):
    for dy, line in enumerate(rows):
        for dx, c in enumerate(line):
            if c in "#1":
                image.putpixel((FACE_X + x + dx, FACE_Y + row + dy), INK)


def read(image, x, row, width):
    return tuple("".join("#" if image.getpixel((FACE_X + x + dx, FACE_Y + row + dy)) == INK else "."
                         for dx in range(width)) for dy in range(5))


def line(image, field, text):
    x, row, width = field
    clear(image, x, row, width)
    stamp(image, x, row, bitmap(text))


def check(base):
    as_ink = lambda rows: tuple(r.replace("1", "#").replace("0", ".") for r in rows)
    expected = [
        ("kind", KIND_AT[:2], as_ink(KIND["ss"]) , len(KIND["ss"][0])),
        ("switching", SWITCHING_AT[:2], bitmap("230V"), 15),
        ("current", CURRENT_AT[:2], bitmap("10A"), 11),
        ("coil", COIL_AT[:2], bitmap("24V"), 11),
    ]
    for name, (x, row), rows, width in expected:
        found = read(base, x, row, width)
        if found != rows:
            sys.exit("%s: the base's label is not where expected at face %d,%d:\n  %s\nexpected\n  %s"
                     % (SOURCE_TEXTURE.name, x, row, "\n  ".join(found), "\n  ".join(rows)))


def paint(base, kind, coil, switching, amps):
    image = base.copy()
    clear(image, *KIND_AT)
    stamp(image, KIND_AT[0], KIND_AT[1], KIND[kind])
    line(image, SWITCHING_AT, "%dV" % switching)
    line(image, CURRENT_AT, "%dA" % amps)
    line(image, COIL_AT, "F" if coil in (120, 230) else "%dV" % coil)
    return image


def main():
    base = Image.open(SOURCE_TEXTURE).convert("RGBA")
    check(base)
    count = 0
    for id in variants():
        kind, _, coil, switching, amps = id.split("_")
        image = paint(base, kind, int(coil[1:-1]), int(switching[:-1]), int(amps[:-1]))
        image.save(TEXTURES / (id + ".png"))
        count += 1
    print("painted %d relay textures" % count)


if __name__ == "__main__":
    main()
