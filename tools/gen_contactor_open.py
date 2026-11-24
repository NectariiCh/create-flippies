"""Writes the open contactor's partials and item models from the user's exports.

Exports (all authored from x = 0, plate down):

* `contactor-open-<piece>.json`, piece single/left/right/center -- one module.
  Its element named `button` is the plunger; everything else is the body.
* `contactor-open-coil-terminal-<v>v.json`, v 24/48/125/250 -- half a module.

Each becomes, moved along x by `CircuitBreakerModel.shiftX`'s rule for its width
so it straddles the middle of the cell:

* `block/contactor_open_<piece>_body.json` and `block/contactor_open_<piece>_plunger.json`;
* `block/contactor_open_coil_terminal_<v>v_body.json`;
* `item/contactor_open.json` (the single piece, plunger included) and
  `item/contactor_open_coil_terminal_<v>v.json`.

It checks every pin against `OpenContactorGeometry` and fails on a mismatch: the
module's by element name, the coil terminal's (all named `upper base`) by position.
The exports are left as they are.

    python tools/gen_contactor_open.py
"""

import json
import pathlib
import sys

sys.path.insert(0, str(pathlib.Path(__file__).resolve().parent))
from gen_relay import STRIPPED, bounds, display, fmt, move, recentre, used_textures, write_json  # noqa: E402

ROOT = pathlib.Path(__file__).resolve().parent.parent / "src" / "main" / "resources"
ASSETS = ROOT / "assets" / "create_flippies"
BLOCK_MODELS = ASSETS / "models" / "block"
ITEM_MODELS = ASSETS / "models" / "item"
TEXTURES = ASSETS / "textures" / "block"

PIECES = ("single", "left", "right", "center")
COILS = (24, 48, 125, 250)
PLUNGER = "button"

# OpenContactorGeometry's module pins in terminal order, export frame (x from 0).
MODULE_PINS = [
    ("c-no1", [0.25, 2.125, 6.0], [0.75, 2.375, 6.25]),
    ("no1", [0.25, 1.625, 6.0], [0.75, 1.875, 6.25]),
    ("c-no2", [0.25, 2.125, 9.75], [0.75, 2.375, 10.0]),
    ("no2", [0.25, 1.625, 9.75], [0.75, 1.875, 10.0]),
    ("c-nc1", [0.25, 2.625, 6.0], [0.75, 2.875, 6.25]),
    ("nc1", [0.25, 3.125, 9.75], [0.75, 3.375, 10.0]),
    ("c-nc2", [0.25, 2.625, 9.75], [0.75, 2.875, 10.0]),
    ("nc2", [0.25, 3.125, 6.0], [0.75, 3.375, 6.25]),
]

# The coil terminal's pins: A1 (lower) and A2 on the -z face, then on the +z face.
COIL_PINS = [
    ("A1", [0.125, 1.375, 6.375], [0.375, 1.625, 6.5]),
    ("A2", [0.125, 1.875, 6.375], [0.375, 2.125, 6.5]),
    ("A1", [0.125, 1.375, 9.5], [0.375, 1.625, 9.625]),
    ("A2", [0.125, 1.875, 9.5], [0.375, 2.125, 9.625]),
]


def load(name):
    return json.loads((BLOCK_MODELS / (name + ".json")).read_text(encoding="utf-8"))


def same(e, lo, hi, shift):
    want = [lo[0] + shift, lo[1], lo[2], hi[0] + shift, hi[1], hi[2]]
    return all(abs(a - b) < 1e-6 for a, b in zip(e["from"] + e["to"], want))


def check_textures(name, textures):
    for slot, ref in textures.items():
        path = TEXTURES / (ref.split(":", 1)[1].split("/", 1)[1] + ".png")
        if ref.startswith("create_flippies:") and not path.exists():
            print("  WARNING %s: texture #%s %s has no %s" % (name, slot, ref, path.name))


def split(name, width):
    source = load(name)
    head = {k: v for k, v in source.items() if k not in STRIPPED and k not in ("textures", "elements", "display")}
    textures = {k: v for k, v in source["textures"].items() if k != "particle"}
    check_textures(name, textures)
    elements = json.loads(json.dumps(source["elements"]))
    lo, hi = bounds(elements)
    if abs(lo[0]) > 1e-9 or abs(hi[0] - width) > 1e-9:
        raise SystemExit("%s spans x %g..%g, not 0..%g" % (name, lo[0], hi[0], width))
    shift = 8 - width / 2
    move(elements, [shift, 0, 0])
    return head, textures, elements, shift


def piece(name):
    head, textures, elements, shift = split("contactor-open-" + name, 1)
    note = "tools/gen_contactor_open.py from contactor-open-%s.json; re-run after re-exporting" % name
    by_name = {e.get("name"): e for e in elements}
    for label, lo, hi in MODULE_PINS:
        if label not in by_name or not same(by_name[label], lo, hi, shift):
            raise SystemExit("contactor-open-%s: pin %s is missing or not where OpenContactorGeometry puts it"
                             % (name, label))
    plunger = [e for e in elements if e.get("name") == PLUNGER]
    body = [e for e in elements if e.get("name") != PLUNGER]
    if len(plunger) != 1:
        raise SystemExit("contactor-open-%s: %d elements named %r, want one" % (name, len(plunger), PLUNGER))
    out = "contactor_open_" + name
    write_json(BLOCK_MODELS / (out + "_body.json"),
               {"__generated": note, **head, "textures": used_textures(textures, body), "elements": body})
    write_json(BLOCK_MODELS / (out + "_plunger.json"),
               {"__generated": note, **head, "textures": used_textures(textures, plunger), "elements": plunger})
    if name == "single":
        icon = json.loads(json.dumps(elements))
        recentre(icon)
        write_json(ITEM_MODELS / "contactor_open.json",
                   {"__generated": note, **head, "textures": used_textures(textures, elements), "elements": icon,
                    "display": display(1.0)})
    print("contactor-open-%s.json -> %s_body (%d) + _plunger" % (name, out, len(body)))


def coil(volts):
    name = "contactor-open-coil-terminal-%dv" % volts
    head, textures, elements, shift = split(name, 0.5)
    note = "tools/gen_contactor_open.py from %s.json; re-run after re-exporting" % name
    for label, lo, hi in COIL_PINS:
        if not any(same(e, lo, hi, shift) for e in elements):
            raise SystemExit("%s: no element where OpenContactorGeometry puts %s (%s -> %s)"
                             % (name, label, fmt(lo), fmt(hi)))
    out = "contactor_open_coil_terminal_%dv" % volts
    used = used_textures(textures, elements)
    write_json(BLOCK_MODELS / (out + "_body.json"),
               {"__generated": note, **head, "textures": used, "elements": elements})
    icon = json.loads(json.dumps(elements))
    recentre(icon)
    write_json(ITEM_MODELS / (out + ".json"),
               {"__generated": note, **head, "textures": used, "elements": icon, "display": display(1.0)})
    print("%s.json -> %s_body (%d)" % (name, out, len(elements)))


def main():
    for name in PIECES:
        piece(name)
    for volts in COILS:
        coil(volts)


if __name__ == "__main__":
    main()
