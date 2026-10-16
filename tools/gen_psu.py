"""Writes the DIN-rail power supplies' models from their authored Blockbench exports.

Every PSU is authored once, as `models/block/PSU-DCDC-<in>V-<out>V-<watts>W-<n>M.json`,
and that export is the one place it is drawn: re-exporting over it and re-running
this is the whole of updating one. Every export matching that name is processed, so
a variant added later -- a copied model plus a row in `PsuType`'s table -- needs no
change here. Pass stems on the command line to do only those:

    python tools/gen_psu.py
    python tools/gen_psu.py PSU-DCDC-230V-24V-100W-3M

Each export becomes two files under its lower case, underscored name
(`psu_dcdc_230v_24v_100w_3m`), which is the mod's convention for generated models
and the control kind's id:

* `models/block/<id>.json` -- the partial `PsuControlRenderer` draws
  (`ModPartialModels.PSUS`). The export is authored against x = 0 like the
  breakers' are, and is moved along x by `CircuitBreakerModel.shiftX(modules)` so
  that it straddles the middle of the cell, which is where `ControlMount` expects a
  control to be drawn before it displaces it along the rail. Nothing else moves:
  y stays the depth off the plate and z the height across the rail, so the frame
  `PsuGeometry` states its boxes in is the frame of this file.
* `models/item/<id>.json` -- the same body centred on the middle of the block in
  every axis, with the breakers' display transforms, for the reason
  gen_circuit_breaker.py's `recentre` gives.

What is dropped and fixed on the way:

* `groups` (the per-module M1/M2/M3 folders, which never move on their own),
  `format_version`, `credit`, `texture_size` and `__generated` go -- the game reads
  none of them;
* texture references are folded to lower case with underscores, the breakers'
  convention, and the body texture file is renamed to match if the export brought
  it in under its hyphenated name (Blockbench names it after the export). The
  rename is a move, so a re-export that writes the hyphenated file again is
  picked up by the next run;
* `particle` is pointed at the body texture rather than whatever the export says.

The orientation is kept as authored. The PLC's export turned out to be drawn upside
down and gen_plc.py spins it; nothing says this one is, so it is not spun.

It also prints every terminal it finds, by name, in `PsuGeometry`'s frozen order,
after the shift, and fails if one is missing or not where `PsuGeometry` puts it:
the boxes a click lands on are stated in Java, and a model whose copper moved
without them would draw pins nobody can wire.

Needs nothing but the standard library. It writes only under src/main/resources and
takes no part in the Gradle build.
"""

import json
import math
import pathlib
import re
import sys

ROOT = pathlib.Path(__file__).resolve().parent.parent / "src" / "main" / "resources"
ASSETS = ROOT / "assets" / "create_flippies"
BLOCK_MODELS = ASSETS / "models" / "block"
ITEM_MODELS = ASSETS / "models" / "item"
TEXTURES = ASSETS / "textures" / "block"

# PSU-DCDC-230V-24V-100W-3M, in either case.
SOURCE_PATTERN = re.compile(r"^PSU-DCDC-(\d+)V-(\d+)V-(\d+)W-(\d+)M$", re.IGNORECASE)

# The texture slot of the body. The copper is `copper_contacts`, shared with
# every other device, and is left alone.
BODY_SLOT = "0"

STRIPPED = ("format_version", "credit", "texture_size", "groups", "__generated")

# The terminals in PsuGeometry's order. Never reorder: Power Grid numbers a
# control's terminals positionally and saved wires depend on it.
TERMINALS = ("VCC", "GND", "V+", "V-", "DC OK")

# Where PsuGeometry expects each pin, in the export's own frame (x from 0):
# the module it sits in, and the face of the base it stands on. The input pair is
# on the +z side, the outputs on the -z side; every pin is half a pixel square,
# a quarter of a pixel proud, a quarter in from its module's edge.
EXPECTED = {
    "VCC": (0, +1),
    "GND": (1, +1),
    "V+": (0, -1),
    "V-": (1, -1),
    "DC OK": (2, -1),
}
PIN_INSET = 0.25
PIN_SIZE = 0.5
PIN_RISE = 0.25
PIN_MIN_Y = 1.25
BASE_MIN_Z = 5.5
BASE_MAX_Z = 10.5

NL = "\n"


def resource_name(stem):
    """The lower case form Minecraft will load, and the mod's convention."""
    return stem.lower().replace("-", "_")


def sanitize(textures):
    fixed = {}
    for key, value in textures.items():
        namespace, _, path = value.partition(":")
        head, _, stem = path.rpartition("/")
        fixed[key] = namespace + ":" + (head + "/" if head else "") + resource_name(stem)
    return fixed


def shift_x(modules):
    """CircuitBreakerModel.shiftX: how far an x-from-0 export moves to straddle the middle."""
    return 8 - modules / 2


def move(elements, shift):
    for e in elements:
        e["from"] = [e["from"][i] + shift[i] for i in range(3)]
        e["to"] = [e["to"][i] + shift[i] for i in range(3)]
        rotation = e.get("rotation")
        if rotation and "origin" in rotation:
            rotation["origin"] = [rotation["origin"][i] + shift[i] for i in range(3)]


def corners(element):
    """Every corner an element bakes to. Only a quarter-turn-free y rotation is
    expected here; anything else is refused rather than half handled."""
    rotation = element.get("rotation")
    if rotation and rotation.get("angle", 0) != 0:
        raise SystemExit("element %r is rotated; gen_psu.py does not account for that"
                         % element.get("name"))
    out = []
    for x in (element["from"][0], element["to"][0]):
        for y in (element["from"][1], element["to"][1]):
            for z in (element["from"][2], element["to"][2]):
                out.append((x, y, z))
    return out


def bounds(elements):
    points = [p for e in elements for p in corners(e)]
    lo = [min(p[i] for p in points) for i in range(3)]
    hi = [max(p[i] for p in points) for i in range(3)]
    return lo, hi


def recentre(elements):
    """The icon, moved onto the middle of the block; see gen_circuit_breaker.py."""
    lo, hi = bounds(elements)
    move(elements, [8 - (lo[i] + hi[i]) / 2 for i in range(3)])
    lo, hi = bounds(elements)
    assert all(abs((lo[i] + hi[i]) / 2 - 8) < 1e-9 for i in range(3)), "icon off centre"
    assert all(lo[i] >= 0 and hi[i] <= 16 for i in range(3)), "icon leaves the block"


def display_block():
    """The breakers' poses, which a DIN device of this size already wears."""
    return {
        "gui": {"rotation": [30, 225, 0], "translation": [0, 0, 0], "scale": [1.5, 1.5, 1.5]},
        "ground": {"rotation": [0, 0, 0], "translation": [0, 3, 0], "scale": [0.6, 0.6, 0.6]},
        "fixed": {"rotation": [0, 180, 0], "translation": [0, 0, 0], "scale": [1.2, 1.2, 1.2]},
        "thirdperson_righthand": {"rotation": [75, 45, 0], "translation": [0, 2.5, 0], "scale": [0.9, 0.9, 0.9]},
        "thirdperson_lefthand": {"rotation": [75, 45, 0], "translation": [0, 2.5, 0], "scale": [0.9, 0.9, 0.9]},
        "firstperson_righthand": {"rotation": [0, 45, 0], "translation": [0, 0, 0], "scale": [0.95, 0.95, 0.95]},
        "firstperson_lefthand": {"rotation": [0, 225, 0], "translation": [0, 0, 0], "scale": [0.95, 0.95, 0.95]},
        "head": {"rotation": [0, 180, 0], "translation": [0, 13, 7], "scale": [1, 1, 1]},
    }


def write_json(path, data):
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(data, indent=2) + NL, encoding="utf-8", newline="\n")


def rename_texture(reference):
    """Moves a body texture saved under the export's hyphenated name to the
    underscored one its folded reference now asks for. A newer hyphenated file
    replaces an older underscored one: it is a fresh export of the same paint."""
    _, _, path = reference.partition(":")
    stem = path.rpartition("/")[2]
    wanted = TEXTURES / (stem + ".png")
    for candidate in TEXTURES.glob("*.png"):
        if candidate == wanted or resource_name(candidate.stem) != stem:
            continue
        if wanted.exists() and wanted.stat().st_mtime >= candidate.stat().st_mtime:
            print("  note: %s is older than %s and was left alone" % (candidate.name, wanted.name))
            continue
        candidate.replace(wanted)
        print("  renamed texture %s -> %s" % (candidate.name, wanted.name))
    if not wanted.exists():
        raise SystemExit("no texture %s.png (or a hyphenated form of it) in %s" % (stem, TEXTURES))


def check_terminals(stem, elements, modules, shift):
    """Prints the pins in PsuGeometry order and fails if any is missing or moved."""
    by_name = {}
    for e in elements:
        by_name.setdefault(e.get("name"), e)
    missing = [n for n in TERMINALS if n not in by_name]
    if missing:
        raise SystemExit("%s: no element for terminal(s) %s" % (stem, ", ".join(missing)))

    print("  terminals, in PsuGeometry order (from -> to, px, after the x shift of %g):" % shift)
    for index, name in enumerate(TERMINALS):
        e = by_name[name]
        print("    %d %-6s %s -> %s" % (index, name, fmt(e["from"]), fmt(e["to"])))
        module, side = EXPECTED[name]
        if module >= modules:
            raise SystemExit("%s: %s is expected in module %d of a %d module body; PsuGeometry's "
                             "pin layout needs revisiting for this width" % (stem, name, module + 1, modules))
        min_x = shift + module + PIN_INSET
        min_z = BASE_MAX_Z if side > 0 else BASE_MIN_Z - PIN_RISE
        want_from = [min_x, PIN_MIN_Y, min_z]
        want_to = [min_x + PIN_SIZE, PIN_MIN_Y + PIN_SIZE, min_z + PIN_RISE]
        if any(abs(a - b) > 1e-6 for a, b in zip(e["from"] + e["to"], want_from + want_to)):
            raise SystemExit("%s: %s is drawn at %s -> %s but PsuGeometry puts it at %s -> %s; "
                             "update one to match the other" % (stem, name, fmt(e["from"]), fmt(e["to"]),
                                                                fmt(want_from), fmt(want_to)))


def fmt(v):
    return "[" + ", ".join("%g" % x for x in v) + "]"


def process(path):
    match = SOURCE_PATTERN.match(path.stem)
    if not match:
        raise SystemExit("%s is not named PSU-DCDC-<in>V-<out>V-<watts>W-<n>M" % path.name)
    volts_in, volts_out, watts, modules = (int(match.group(i)) for i in range(1, 5))
    target = resource_name(path.stem)
    print("%s -> %s  (%d V in, %d V out, %d W, %d modules)" % (path.stem, target, volts_in, volts_out,
                                                                 watts, modules))

    source = json.loads(path.read_text(encoding="utf-8"))
    textures = sanitize({k: v for k, v in source["textures"].items() if k != "particle"})
    if BODY_SLOT not in textures:
        raise SystemExit("%s: no texture in slot %s for the body" % (path.stem, BODY_SLOT))
    rename_texture(textures[BODY_SLOT])
    textures["particle"] = textures[BODY_SLOT]

    elements = json.loads(json.dumps(source["elements"]))
    lo, hi = bounds(elements)
    if abs(lo[0]) > 1e-9 or abs(hi[0] - modules) > 1e-9:
        raise SystemExit("%s: body spans x %g..%g, not the 0..%d its name promises"
                         % (path.stem, lo[0], hi[0], modules))
    shift = shift_x(modules)
    move(elements, [shift, 0, 0])

    head = {k: v for k, v in source.items() if k not in STRIPPED and k not in ("textures", "elements")}
    note = "tools/gen_psu.py from %s.json; re-run after re-exporting" % path.stem

    block = {"__generated": note, **head, "textures": textures, "elements": elements}
    write_json(BLOCK_MODELS / (target + ".json"), block)

    icon = json.loads(json.dumps(elements))
    recentre(icon)
    write_json(ITEM_MODELS / (target + ".json"),
               {"__generated": note, **head, "textures": textures, "elements": icon, "display": display_block()})

    lo, hi = bounds(elements)
    print("  wrote block/%s and item/%s: %d elements, body %s -> %s"
          % (target, target, len(elements), fmt(lo), fmt(hi)))
    check_terminals(path.stem, elements, modules, shift)


def main(argv):
    if argv:
        sources = [BLOCK_MODELS / (stem if stem.endswith(".json") else stem + ".json") for stem in argv]
    else:
        sources = sorted(p for p in BLOCK_MODELS.glob("*.json") if SOURCE_PATTERN.match(p.stem))
    if not sources:
        raise SystemExit("no PSU exports found in %s" % BLOCK_MODELS)
    for path in sources:
        if not path.exists():
            raise SystemExit("no export %s" % path)
        process(path)


if __name__ == "__main__":
    main(sys.argv[1:])
