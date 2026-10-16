"""Writes the DIN terminal blocks' partials and item models from their exports.

Every `models/block/din_terminal_block_<variant>.json` that is not itself a
generated `_body` is an export: the user's authored `6p3g`, and the copies
`copy_terminal_block_variants.py` made for the rest of the family. Each becomes

* `models/block/din_terminal_block_<variant>_body.json` -- the partial
  `TerminalBlockControlRenderer` draws, moved along x by
  `CircuitBreakerModel.shiftX` of its half-module width so it straddles the middle
  of the cell where `ControlMount` expects a control drawn;
* `models/item/din_terminal_block_<variant>.json` -- the same, centred on the block,
  in the poses the relay mount wears.

The exports are left exactly as they are; re-exporting over one and re-running
this is the whole of updating it. It also prints each block's pins in
`TerminalBlockType`'s frozen order and fails if one is missing or not where
`TerminalBlockGeometry` puts it.

    python tools/gen_terminal_blocks.py

Needs nothing but the standard library. It writes only under src/main/resources and
takes no part in the Gradle build.
"""

import json
import pathlib
import sys

sys.path.insert(0, str(pathlib.Path(__file__).resolve().parent))
from gen_relay import STRIPPED, bounds, display, fmt, move, recentre, used_textures, write_json  # noqa: E402
from copy_terminal_block_variants import VARIANTS  # noqa: E402

ROOT = pathlib.Path(__file__).resolve().parent.parent / "src" / "main" / "resources"
BLOCK_MODELS = ROOT / "assets" / "create_flippies" / "models" / "block"
ITEM_MODELS = ROOT / "assets" / "create_flippies" / "models" / "item"

PREFIX = "din_terminal_block_"
WIDTH = 0.5

# TerminalBlockType's table: each block's pins in terminal order, (label, step, side).
# Never reorder: Power Grid numbers a control's terminals positionally and saved
# wires depend on it.
PINS = {
    "6p3g": [("G1A", 0, -1), ("G1B", 0, +1), ("G2A", 1, -1), ("G2B", 1, +1), ("G3A", 2, -1), ("G3B", 2, +1)],
    **VARIANTS,
}

# TerminalBlockGeometry's pins, in the export's own frame (x from 0): the bottom
# of the pin on each step, and the -z and +z faces of each step.
PIN_Y = (0.85, 1.4, 1.9)
FACE_MINUS_Z = (5.5, 6.0, 6.5)
FACE_PLUS_Z = (10.5, 10.0, 9.5)
PIN_SIZE = 0.25
PIN_RISE = 0.125


def check_pins(variant, elements, shift):
    by_name = {e.get("name"): e for e in elements}
    pins = PINS[variant]
    missing = [label for label, _, _ in pins if label not in by_name]
    if missing:
        raise SystemExit("%s%s: no element for terminal(s) %s" % (PREFIX, variant, ", ".join(missing)))
    print("  terminals, in TerminalBlockType order (px, after the x shift of %g):" % shift)
    for index, (label, level, side) in enumerate(pins):
        e = by_name[label]
        print("    %d %s %s -> %s" % (index, label, fmt(e["from"]), fmt(e["to"])))
        min_x = shift + (WIDTH - PIN_SIZE) / 2
        min_z = FACE_PLUS_Z[level] if side > 0 else FACE_MINUS_Z[level] - PIN_RISE
        want = [min_x, PIN_Y[level], min_z, min_x + PIN_SIZE, PIN_Y[level] + PIN_SIZE, min_z + PIN_RISE]
        if any(abs(a - b) > 1e-6 for a, b in zip(e["from"] + e["to"], want)):
            raise SystemExit("%s%s: %s is drawn at %s -> %s but TerminalBlockGeometry puts it at %s -> %s; "
                             "update one to match the other" % (PREFIX, variant, label, fmt(e["from"]),
                                                                fmt(e["to"]), fmt(want[:3]), fmt(want[3:])))


def generate(variant):
    source_name = PREFIX + variant
    source = json.loads((BLOCK_MODELS / (source_name + ".json")).read_text(encoding="utf-8"))
    head = {k: v for k, v in source.items() if k not in STRIPPED and k not in ("textures", "elements", "display")}
    textures = {k: v for k, v in source["textures"].items() if k != "particle"}
    note = "tools/gen_terminal_blocks.py from %s.json; re-run after re-exporting" % source_name

    elements = json.loads(json.dumps(source["elements"]))
    lo, hi = bounds(elements)
    if abs(lo[0]) > 1e-9 or abs(hi[0] - WIDTH) > 1e-9:
        raise SystemExit("%s spans x %g..%g, not the 0..%g of a half module" % (source_name, lo[0], hi[0], WIDTH))
    shift = 8 - WIDTH / 2
    move(elements, [shift, 0, 0])

    print("%s.json -> %s_body" % (source_name, source_name))
    write_json(BLOCK_MODELS / (source_name + "_body.json"),
               {"__generated": note, **head, "textures": used_textures(textures, elements), "elements": elements})
    icon = json.loads(json.dumps(elements))
    recentre(icon)
    write_json(ITEM_MODELS / (source_name + ".json"),
               {"__generated": note, **head, "textures": used_textures(textures, elements), "elements": icon,
                "display": display(1.0)})
    lo, hi = bounds(elements)
    print("  body: %d elements, %s -> %s" % (len(elements), fmt(lo), fmt(hi)))
    check_pins(variant, elements, shift)


def main():
    exports = sorted(p.stem[len(PREFIX):] for p in BLOCK_MODELS.glob(PREFIX + "*.json")
                     if not p.stem.endswith("_body"))
    unknown = [v for v in exports if v not in PINS]
    if unknown:
        raise SystemExit("no pin table for %s; add it to PINS and to TerminalBlockType" % ", ".join(unknown))
    absent = [v for v in PINS if v not in exports]
    if absent:
        raise SystemExit("no export for %s; run tools/copy_terminal_block_variants.py" % ", ".join(absent))
    for variant in exports:
        generate(variant)


if __name__ == "__main__":
    main()
