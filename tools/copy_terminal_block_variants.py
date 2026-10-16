"""Copies the DIN terminal block's export and texture for every terminal block variant.

The family, as the user specified it on 2026-10-14 -- all rated 660 V, 85 A:

* 6 pins: 3 groups (the authored export), 2 groups, 1 group, and a 1-group ground;
* 4 pins: 2 groups, 1 group, and a 1-group ground;
* 2 pins: one group, and a 1-group ground.

The authored export, `models/block/din_terminal_block_6p3g.json`, is three stepped
levels with a pin on each side of each level. A 4-pin block is that with the top
level taken off, a 2-pin block with the top two taken off; the topmost step left
wears the label region of the texture (the 6p3g's `upper base` top), so a
shortened block keeps its print.

Each pin element is renamed to the contact it is on the variant -- `G<group><contact>`,
the label `TerminalBlockType` gives that pin -- so the export names its contacts
the same way the authored one does:

    6p3g  G1A G1B | G2A G2B | G3A G3B     level by level, -z side then +z side
    6p2g  G1A G1B G1C (-z side, bottom up) | G2A G2B G2C (+z side, bottom up)
    6p1g  G1A..G1F                        level by level, -z side then +z side
    4p2g  G1A G1B | G2A G2B
    4p1g  G1A..G1D
    2p1g  G1A G1B

Each variant gets:

* `models/block/din_terminal_block_<variant>.json` -- the export, cut down and
  relabelled as above, its body texture pointed at the variant's own;
* `textures/block/din_terminal_block_<variant>.png` -- a byte copy of
  `din_terminal_block_3g.png`.

An existing file is never overwritten: both are for the user to author, and
re-running this must not undo that. Run `tools/gen_terminal_blocks.py` after
this (and after any re-export) to write the partials and item models the game
loads.

    python tools/copy_terminal_block_variants.py
"""

import json
import pathlib
import shutil

ASSETS = pathlib.Path(__file__).resolve().parent.parent / "src" / "main" / "resources" / "assets" / "create_flippies"
TEXTURES = ASSETS / "textures" / "block"
BLOCK_MODELS = ASSETS / "models" / "block"

SOURCE = "din_terminal_block_6p3g"
SOURCE_TEXTURE = TEXTURES / "din_terminal_block_3g.png"
BODY_SLOT = "0"

# The authored pins by where they stand: (level, side), level 0 the lowest step,
# side -1 the -z face and +1 the +z face.
SOURCE_PINS = {
    (0, -1): "G1A", (0, +1): "G1B",
    (1, -1): "G2A", (1, +1): "G2B",
    (2, -1): "G3A", (2, +1): "G3B",
}
LEVEL_BASES = ("lower base", "mid base", "upper base")

# The variant's pins in TerminalBlockType's order: (label, level, side). Must
# match TerminalBlockType.TABLE; gen_terminal_blocks.py checks the pair.
VARIANTS = {
    "6p2g": [("G1A", 0, -1), ("G1B", 1, -1), ("G1C", 2, -1),
             ("G2A", 0, +1), ("G2B", 1, +1), ("G2C", 2, +1)],
    "6p1g": [("G1A", 0, -1), ("G1B", 0, +1), ("G1C", 1, -1), ("G1D", 1, +1), ("G1E", 2, -1), ("G1F", 2, +1)],
    "6p1g_ground": [("G1A", 0, -1), ("G1B", 0, +1), ("G1C", 1, -1), ("G1D", 1, +1), ("G1E", 2, -1),
                    ("G1F", 2, +1)],
    "4p2g": [("G1A", 0, -1), ("G1B", 0, +1), ("G2A", 1, -1), ("G2B", 1, +1)],
    "4p1g": [("G1A", 0, -1), ("G1B", 0, +1), ("G1C", 1, -1), ("G1D", 1, +1)],
    "4p1g_ground": [("G1A", 0, -1), ("G1B", 0, +1), ("G1C", 1, -1), ("G1D", 1, +1)],
    "2p1g": [("G1A", 0, -1), ("G1B", 0, +1)],
    "2p1g_ground": [("G1A", 0, -1), ("G1B", 0, +1)],
}

# The 6p3g's label region (its upper base top, 3 px across the rail at 2 uv/px),
# grown about its middle to fit the step that is topmost on a shorter block
LABEL_U = (5.5, 6.5)
LABEL_V_MIDDLE = 9.5


def top_uv(level):
    """The label region sized to the top of step `level`."""
    base = next(e for e in SOURCE_ELEMENTS if e["name"] == LEVEL_BASES[level])
    half = (base["to"][2] - base["from"][2])  # 2 uv per px, halved
    return [LABEL_U[0], LABEL_V_MIDDLE - half, LABEL_U[1], LABEL_V_MIDDLE + half]


def variant(source, pins):
    levels = 1 + max(level for _, level, _ in pins)
    by_place = {(level, side): label for label, level, side in pins}
    pin_names = set(SOURCE_PINS.values())
    dropped_bases = set(LEVEL_BASES[levels:])

    elements = []
    for e in source["elements"]:
        name = e.get("name")
        if name in dropped_bases:
            continue
        e = json.loads(json.dumps(e))
        if name in pin_names:
            place = next(p for p, n in SOURCE_PINS.items() if n == name)
            if place not in by_place:
                continue
            e["name"] = by_place[place]
        elif name == LEVEL_BASES[levels - 1] and levels < len(LEVEL_BASES):
            e["faces"]["up"]["uv"] = top_uv(levels - 1)
        elements.append(e)

    out = json.loads(json.dumps(source))
    out["elements"] = elements
    return out


def main():
    global SOURCE_ELEMENTS
    source_path = BLOCK_MODELS / (SOURCE + ".json")
    source = json.loads(source_path.read_text(encoding="utf-8"))
    SOURCE_ELEMENTS = source["elements"]
    names = {e.get("name") for e in SOURCE_ELEMENTS}
    missing = [n for n in list(SOURCE_PINS.values()) + list(LEVEL_BASES) if n not in names]
    if missing:
        raise SystemExit("%s.json has no element(s) %s" % (SOURCE, ", ".join(missing)))

    for suffix, pins in VARIANTS.items():
        vid = "din_terminal_block_" + suffix
        model_path = BLOCK_MODELS / (vid + ".json")
        texture_path = TEXTURES / (vid + ".png")

        if texture_path.exists():
            print("  %s.png exists, kept" % vid)
        else:
            shutil.copyfile(SOURCE_TEXTURE, texture_path)
            print("  %s.png copied" % vid)

        if model_path.exists():
            print("  %s.json exists, kept" % vid)
            continue
        out = variant(source, pins)
        texture = "create_flippies:block/" + vid
        old = out["textures"][BODY_SLOT]
        for key, value in list(out["textures"].items()):
            if value == old:
                out["textures"][key] = texture
        # Blockbench's own layout: tabs, one key per line
        model_path.write_text(json.dumps(out, indent="\t"), encoding="utf-8", newline="\n")
        print("  %s.json written, %d pins" % (vid, len(pins)))


if __name__ == "__main__":
    main()
