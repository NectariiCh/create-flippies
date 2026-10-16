"""Splits every authored circuit breaker into the models the game needs: a body,
the switch group that swings on its own pivot, and a centred icon.

The sources are the Blockbench exports, one per rating and pole count, kept as
the single place the breakers are authored. Each is found by name, and four
things are done to it:

  * the group named SWITCH_GROUP is lifted into its own model, because a part
    that moves cannot be baked into the same model as the part it moves against.
    Everything else — the per-module M1/M2/M3 groups — is one body: the modules
    are grouped for editing and never move independently;
  * the pivot is derived rather than read off the switch group, because
    Blockbench leaves a group's origin at [8, 8, 8] unless it is deliberately
    moved and the multi-pole exports do exactly that. See pivot_of();
  * everything is shifted so the breaker straddles the middle of the block.
    The exports author against x = 0, which is handy to edit, but ControlMount
    displaces a control from the middle of a face, so an authored model has to
    be centred there for an anchor of CENTRED to leave it alone. How far is the
    breaker's own width: 7.5, 7 and 6.5 pixels for one, two and three modules;
  * the icon is additionally centred on the middle of the block in every axis,
    which the block models must never be — see recentre().

Re-run after any re-export:  python tools/gen_circuit_breaker.py

It prints every figure CircuitBreakerModel derives, so an export that moves
something is caught by running this and comparing.

Needs nothing but the standard library. It writes only under
src/main/resources and takes no part in the Gradle build."""
import json, math, pathlib, re

ROOT = pathlib.Path(__file__).resolve().parent.parent / "src" / "main" / "resources"
BLOCK_MODELS = ROOT / "assets" / "create_flippies" / "models" / "block"
ITEM_MODELS = ROOT / "assets" / "create_flippies" / "models" / "item"

# CB16-DIN-WO-230V-2M-DP and friends, in either case.
SOURCE_PATTERN = re.compile(r"^CB(\d+)-DIN-WO-(\d+)V-(\d+)M-([A-Z]+)$", re.IGNORECASE)
SWITCH_GROUP = "switch"

# How far below the front panel of the breaker the switch hinges. The panel is the
# top of the housing, so a fifth of a pixel under it puts the hinge just inside the
# case — where a real toggle's is. Must match CircuitBreakerModel.PIVOT_DROP; set
# it to 0 if the drop is ever baked into the exports instead.
PIVOT_DROP = 0.2

# Mirrors CircuitBreakerModel.CLOSED_ANGLE; the pose the item icons are baked in.
CLOSED_ANGLE = -45

NL = "\n"


def resource_name(stem):
    """The lower case form Minecraft will actually load.

    Resource paths may hold only [a-z0-9/._-], so an export named after the part
    is rejected by the loader outright and every model referencing it fails with
    it — which reads like a broken model rather than a broken filename."""
    return stem.lower().replace("-", "_")


def sanitize(textures):
    """The same fold, applied to an export's texture references."""
    fixed = {}
    for key, value in textures.items():
        namespace, _, path = value.partition(":")
        head, _, stem = path.rpartition("/")
        fixed[key] = namespace + ":" + head + "/" + resource_name(stem)
    return fixed


def corners(element):
    """Every corner an element actually bakes to, its own rotation included.

    A switch turned to closed reaches further in y and z than its box does, so
    bounds taken from from/to alone would be wrong about where it ends up — which
    is what put the icon a tenth of a pixel off centre before this existed."""
    rotation = element.get("rotation")
    angle = math.radians(rotation["angle"]) if rotation else 0.0
    py, pz = (rotation["origin"][1], rotation["origin"][2]) if rotation else (0.0, 0.0)
    out = []
    for x in (element["from"][0], element["to"][0]):
        for y0 in (element["from"][1], element["to"][1]):
            for z0 in (element["from"][2], element["to"][2]):
                y, z = y0, z0
                if angle:
                    dy, dz = y - py, z - pz
                    y = py + dy * math.cos(angle) - dz * math.sin(angle)
                    z = pz + dy * math.sin(angle) + dz * math.cos(angle)
                out.append((x, y, z))
    return out


def bounds(elements):
    points = [p for e in elements for p in corners(e)]
    lo = [min(p[i] for p in points) for i in range(3)]
    hi = [max(p[i] for p in points) for i in range(3)]
    return lo, hi


def move(elements, shift):
    for e in elements:
        e["from"] = [e["from"][i] + shift[i] for i in range(3)]
        e["to"] = [e["to"][i] + shift[i] for i in range(3)]
        rotation = e.get("rotation")
        if rotation and "origin" in rotation:
            rotation["origin"] = [rotation["origin"][i] + shift[i] for i in range(3)]


def pivot_of(body, switch):
    """Where the switch hinges, derived from the geometry rather than declared.

    The one pole export carries a deliberate group origin of [0, 3.5, 8.25]; the
    two and three pole ones were left at Blockbench's default [8, 8, 8], which is
    the middle of the block and no use as a hinge. Rather than trust a value that
    is only sometimes meant, it is derived — and the derivation reproduces the
    one pole export's declared origin exactly:

      y  the top of the housing, which is where a toggle is hinged
      z  the middle of the switch, which is the axis it is symmetrical about

    PIVOT_DROP then takes it down into the body.
    """
    _, body_hi = bounds(body)
    switch_lo, switch_hi = bounds(switch)
    return [0.0, body_hi[1] - PIVOT_DROP, (switch_lo[2] + switch_hi[2]) / 2]


def recentre(elements):
    """Moves an icon's geometry onto the middle of the block.

    A breaker is authored where it sits on a rail: against the mounting plane, so
    its middle is nearly six pixels below the middle of the block. Every display
    transform frames the block and not the model, so an icon left where the export
    puts it renders a full GUI tile low and hangs out of the slot.

    Nudging the display translations instead was the obvious fix and the wrong
    one: there are eight of them, the offset has to be scaled and rotated
    differently in each, and getting one wrong is invisible until someone holds
    it. Moving the geometry once means the ordinary block transforms are correct
    by construction, which is what the rotary switch's item model does.

    Only the icon is moved. The block models keep the authored frame, because that
    is the frame the mount, the terminals and the hitboxes are all built in.
    """
    lo, hi = bounds(elements)
    move(elements, [8 - (lo[i] + hi[i]) / 2 for i in range(3)])
    # An off-centre icon is the bug this function exists to prevent and is
    # invisible without holding one, so the result is checked rather than trusted
    lo, hi = bounds(elements)
    assert all(abs((lo[i] + hi[i]) / 2 - 8) < 1e-9 for i in range(3)), "icon off centre"
    assert all(lo[i] >= 0 and hi[i] <= 16 for i in range(3)), "icon leaves the block"


def write_json(path, data):
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(data, indent=2) + NL, encoding="utf-8")
    return path


def display_block():
    """The rotary switch's poses, which is what a small panel device already
    looks like in this mod's hand and inventory. Correct for any of the pole
    counts because the geometry has been centred first."""
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


def split(path):
    """One export into its body, switch and icon. Returns what was derived."""
    stem = path.stem
    match = SOURCE_PATTERN.match(stem)
    if not match:
        return None
    rating, volts, modules, poles = int(match.group(1)), int(match.group(2)), int(match.group(3)), match.group(4)
    target = resource_name(stem)

    source = json.loads(path.read_text(encoding="utf-8"))
    elements = source["elements"]
    textures = sanitize(source["textures"])

    switch_indices = []
    for group in source.get("groups", []):
        if isinstance(group, dict) and group.get("name", "").lower() == SWITCH_GROUP:
            switch_indices = [i for i in group.get("children", []) if isinstance(i, int)]
            break
    if not switch_indices:
        raise SystemExit("no group named %r in %s" % (SWITCH_GROUP, stem))

    body = [json.loads(json.dumps(e)) for i, e in enumerate(elements) if i not in switch_indices]
    switch = [json.loads(json.dumps(elements[i])) for i in switch_indices]

    pivot = pivot_of(body, switch)

    # Straddle the middle of the block: the breaker's own width decides how far
    lo, hi = bounds(body + switch)
    shift_x = 8 - (lo[0] + hi[0]) / 2
    move(body, [shift_x, 0, 0])
    move(switch, [shift_x, 0, 0])
    pivot[0] += shift_x

    for name, group in ((target + "_body", body), (target + "_switch", switch)):
        write_json(BLOCK_MODELS / (name + ".json"), {
            "__generated": "tools/gen_circuit_breaker.py from %s.json; re-run after re-exporting" % stem,
            "textures": textures,
            "elements": json.loads(json.dumps(group)),
        })

    icon = json.loads(json.dumps(body))
    for e in json.loads(json.dumps(switch)):
        e["rotation"] = {"angle": CLOSED_ANGLE, "axis": "x", "origin": list(pivot)}
        icon.append(e)
    recentre(icon)
    write_json(ITEM_MODELS / (target + ".json"), {
        "__generated": "tools/gen_circuit_breaker.py from %s.json; re-run after re-exporting" % stem,
        "textures": textures,
        "elements": icon,
        "display": display_block(),
    })

    radius = max(math.hypot(y - pivot[1], z - pivot[2])
                 for e in switch for _, y, z in corners(e))
    body_lo, body_hi = bounds(body)
    return {
        "stem": stem, "target": target, "rating": rating, "volts": volts,
        "modules": modules, "poles": poles, "shift_x": shift_x, "pivot": pivot,
        "radius": radius, "body_lo": body_lo, "body_hi": body_hi,
        "elements": len(body) + len(switch),
    }


def main():
    sources = sorted(p for p in BLOCK_MODELS.glob("*.json") if SOURCE_PATTERN.match(p.stem))
    if not sources:
        raise SystemExit("no breaker exports found in %s" % BLOCK_MODELS)

    done = [split(p) for p in sources]
    done = [d for d in done if d]
    for d in done:
        print("%-28s -> %s_{body,switch} + icon  (%d elements)" % (d["stem"], d["target"], d["elements"]))

    print("\n  %d breaker(s) split. What CircuitBreakerModel must derive:" % len(done))
    print("    %-8s %-7s %-8s %-9s %-8s %s" % ("modules", "shiftX", "pivot y", "pivot z", "sweep", "body x"))
    for d in sorted({(d["modules"], d["shift_x"], round(d["pivot"][1], 4), round(d["pivot"][2], 4),
                      round(d["radius"], 4), round(d["body_lo"][0], 3), round(d["body_hi"][0], 3))
                     for d in done}):
        print("    %-8d %-7.2f %-8.4f %-9.4f %-8.4f %.2f..%.2f" % d)
    depth = {(round(d["body_lo"][1], 4), round(d["body_hi"][1], 4)) for d in done}
    across = {(round(d["body_lo"][2], 4), round(d["body_hi"][2], 4)) for d in done}
    print("    body y (depth off the panel): %s" % sorted(depth))
    print("    body z (height across rail):  %s" % sorted(across))
    assert len(depth) == 1 and len(across) == 1, "pole counts disagree on depth or height"
    print("    -> every pole count shares one depth, height, pivot and sweep;")
    print("       only the width and the contact count change.")


main()
