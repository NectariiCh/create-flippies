"""Writes the DIN contactors' partials and item models from their exports.

Every `models/block/contactor-<n>m-<poles>-<v>v.json` is an export: the two the
user authored and the copies `copy_contactor_variants.py` made. Each becomes

* `models/block/contactor_<n>m_<poles>_<v>v_body.json` -- the partial
  `ContactorControlRenderer` draws, moved along x by `CircuitBreakerModel.shiftX`
  of its module count so it straddles the middle of the cell where
  `ControlMount` expects a control drawn;
* `models/item/contactor_<n>m_<poles>_<v>v.json` -- the same, centred on the
  block, in the poses every DIN device wears.

The exports are left as they are. It also checks every terminal of every export
against `ContactorGeometry` and fails on a mismatch.

    python tools/gen_contactors.py

Needs nothing but the standard library. It writes only under src/main/resources.
"""

import json
import pathlib
import re
import sys

sys.path.insert(0, str(pathlib.Path(__file__).resolve().parent))
from gen_relay import STRIPPED, bounds, display, fmt, move, recentre, used_textures, write_json  # noqa: E402
from copy_contactor_variants import COILS, SIZES, export_name  # noqa: E402

ROOT = pathlib.Path(__file__).resolve().parent.parent / "src" / "main" / "resources"
BLOCK_MODELS = ROOT / "assets" / "create_flippies" / "models" / "block"
ITEM_MODELS = ROOT / "assets" / "create_flippies" / "models" / "item"

# ContactorGeometry's pins in the export's own frame, module 0 (x from 0):
# (from, to). Pole k is the pole-0 pin moved k modules along x.
COIL_PINS = {
    "coil1": ([0.25, 1.0, 5.25], [0.75, 1.5, 5.5]),   # A1
    "coil2": ([0.25, 1.75, 5.25], [0.75, 2.25, 5.5]),  # A2
}
POLE_PINS = {
    "C": ([0.25, 1.5, 10.5], [0.75, 1.75, 10.75]),
    "NO": ([0.25, 1.0, 10.5], [0.75, 1.25, 10.75]),
    "NC": ([0.25, 2.0, 10.5], [0.75, 2.25, 10.75]),
}


def item_id(modules, coil):
    return "contactor_%dm_%s_%dv" % (modules, SIZES[modules], coil)


def check_pins(name, elements, modules, shift):
    by_name = {}
    for e in elements:
        by_name.setdefault(e.get("name"), e)
    wanted = dict(COIL_PINS)
    for pole in range(modules):
        for contact, (lo, hi) in POLE_PINS.items():
            label = contact if modules == 1 else "%s%d" % (contact, pole + 1)
            wanted[label] = ([lo[0] + pole, lo[1], lo[2]], [hi[0] + pole, hi[1], hi[2]])
    for label, (lo, hi) in wanted.items():
        e = by_name.get(label)
        if e is None:
            raise SystemExit("%s: no element named %s" % (name, label))
        want = [lo[0] + shift, lo[1], lo[2], hi[0] + shift, hi[1], hi[2]]
        if any(abs(a - b) > 1e-6 for a, b in zip(e["from"] + e["to"], want)):
            raise SystemExit("%s: %s is drawn at %s -> %s but ContactorGeometry puts it at %s -> %s"
                             % (name, label, fmt(e["from"]), fmt(e["to"]), fmt(want[:3]), fmt(want[3:])))


def generate(modules, coil):
    name = export_name(modules, coil)
    out = item_id(modules, coil)
    source = json.loads((BLOCK_MODELS / (name + ".json")).read_text(encoding="utf-8"))
    head = {k: v for k, v in source.items() if k not in STRIPPED and k not in ("textures", "elements", "display")}
    textures = {k: v for k, v in source["textures"].items() if k != "particle"}
    note = "tools/gen_contactors.py from %s.json; re-run after re-exporting" % name

    elements = json.loads(json.dumps(source["elements"]))
    lo, hi = bounds(elements)
    if abs(lo[0]) > 1e-9 or abs(hi[0] - modules) > 1e-9:
        raise SystemExit("%s spans x %g..%g, not the 0..%d of %d modules" % (name, lo[0], hi[0], modules, modules))
    shift = 8 - modules / 2
    move(elements, [shift, 0, 0])
    check_pins(name, elements, modules, shift)

    used = used_textures(textures, elements)
    write_json(BLOCK_MODELS / (out + "_body.json"),
               {"__generated": note, **head, "textures": used, "elements": elements})
    icon = json.loads(json.dumps(elements))
    recentre(icon)
    write_json(ITEM_MODELS / (out + ".json"),
               {"__generated": note, **head, "textures": used, "elements": icon, "display": display(1.0)})
    print("%s.json -> %s (%d elements)" % (name, out, len(elements)))


def main():
    pattern = re.compile(r"contactor-(\d+)m-[a-z0-9]+-(\d+)v")
    exports = {pattern.fullmatch(p.stem).groups() for p in BLOCK_MODELS.glob("contactor-*.json")
               if pattern.fullmatch(p.stem)}
    wanted = {(str(m), str(c)) for m in SIZES for c in COILS}
    if exports - wanted:
        raise SystemExit("exports outside the matrix: %s" % sorted(exports - wanted))
    if wanted - exports:
        raise SystemExit("no export for %s; run tools/copy_contactor_variants.py" % sorted(wanted - exports))
    for modules in SIZES:
        for coil in COILS:
            generate(modules, coil)


if __name__ == "__main__":
    main()
