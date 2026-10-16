"""Writes the DIN-rail relay mounts' and their plug-in relays' models from the authored exports.

Each mount and the relay that plugs into it are authored together, as one export
per mount (`models/block/din_relay_mount_<n>pin.json`), with the relay drawn seated
in its socket -- as one element named after it on the 5-pin export, as a group on
the 8-pin one. In the game they are two things -- the mount clips to a rail empty
and a relay is pushed into it later -- so this splits each export:

* `models/block/<mount>_body.json` -- the mount without the relay, the partial
  `RelayMountControlRenderer` always draws;
* `models/block/<relay>.json` -- the relay on its own, in the very place the
  export seats it, drawn on top of the body only while one is fitted;
* `models/item/<mount>.json` and `models/item/<relay>.json` -- the same, centred on
  the block. A relay is a pixel or two at its largest, which is a speck at any
  display scale vanilla allows (it clamps them to 4), so its icon's elements are
  blown up by RELAY_ICON_SCALE about the middle instead.

The exports are authored like the PSU's -- plate down, `y` the depth off the plate,
`z` across the rail, `x` along it. The 5-pin one is drawn from x = 0 and half a
module wide; the 8-pin one is drawn already straddling x = 8 and a module wide.
Each is moved along x so it straddles the middle of the cell, where `ControlMount`
expects a control drawn.

What is fixed on the way, in the generated files only:

* texture references are folded to lower case with underscores, and a texture file
  saved under the hyphenated name is renamed to match (gen_psu.py's convention);
* a face on an undefined texture slot (Blockbench's `#missing`) is pointed at the
  relay's own texture, with a warning -- assign it in Blockbench to silence it;
* a relay whose texture has partly transparent pixels gets `render_type`
  translucent, so its item draws the clear case as clear.

The source exports are left exactly as they are; re-exporting over one and
re-running this is the whole of updating it. Pins are found by where they are, not
by name, and the run fails if one is missing or not where `RelayMountGeometry`
puts it.

    python tools/gen_relay.py

Needs nothing but the standard library. It writes only under src/main/resources and
takes no part in the Gradle build.
"""

import json
import pathlib
import struct
import zlib

ROOT = pathlib.Path(__file__).resolve().parent.parent / "src" / "main" / "resources"
BLOCK_MODELS = ROOT / "assets" / "create_flippies" / "models" / "block"
ITEM_MODELS = ROOT / "assets" / "create_flippies" / "models" / "item"
TEXTURES = ROOT / "assets" / "create_flippies" / "textures" / "block"

RELAY_ICON_SCALE = 4.0

STRIPPED = ("format_version", "credit", "texture_size", "groups", "__generated")

# Every mount. `relays` maps each relay id (its RelayType row, partial and item) to
# what picks its elements out of the export: an element name, or a group name.
# `pins` are RelayMountGeometry's terminals in its frozen order, each as the box
# the pin is drawn at once the export has been centred. Never reorder: Power Grid
# numbers a control's terminals positionally and saved wires depend on it.
MOUNTS = (
    {
        "mount": "din_relay_mount_5pin",
        "width": 0.5,
        "relays": {"ss_relay_230v_10a": ("element", "ss_relay_230v_10a")},
        "pins": (
            ("A1", (7.875, 1.1, 5.375, 8.125, 1.35, 5.5)),
            ("A2", (7.875, 1.9, 5.875, 8.125, 2.15, 6)),
            ("C", (7.875, 1.1, 10.5, 8.125, 1.35, 10.625)),
            ("NO", (7.875, 1.9, 10, 8.125, 2.15, 10.125)),
            ("NC", (7.875, 2.65, 9.5, 8.125, 2.9, 9.625)),
        ),
    },
    {
        "mount": "din_relay_mount_8pin",
        "width": 1.0,
        "relays": {"em_relay_8pin_c24v_230v_16a": ("group", "relay_8pin_230v16a-24cv")},
        "pins": (
            ("A1", (7.75, 1.75, 5.25, 8.25, 2, 5.5)),
            ("A2", (7.75, 1.25, 5.25, 8.25, 1.5, 5.5)),
            ("C1", (7.625, 1.5, 10.5, 7.875, 1.75, 10.75)),
            ("NO1", (7.625, 1, 10.5, 7.875, 1.25, 10.75)),
            ("NC1", (7.625, 2, 10.5, 7.875, 2.25, 10.75)),
            ("C2", (8.125, 1.5, 10.5, 8.375, 1.75, 10.75)),
            ("NO2", (8.125, 1, 10.5, 8.375, 1.25, 10.75)),
            ("NC2", (8.125, 2, 10.5, 8.375, 2.25, 10.75)),
        ),
    },
)

NL = "\n"


def resource_name(stem):
    return stem.lower().replace("-", "_")


def fold(reference):
    namespace, _, path = reference.partition(":")
    head, _, stem = path.rpartition("/")
    return namespace + ":" + (head + "/" if head else "") + resource_name(stem)


def rename_texture(reference):
    """Moves a texture saved under a hyphenated name to the folded one its reference
    now asks for; a newer hyphenated file replaces an older folded one."""
    stem = reference.partition(":")[2].rpartition("/")[2]
    wanted = TEXTURES / (stem + ".png")
    for candidate in TEXTURES.glob("*.png"):
        if candidate == wanted or resource_name(candidate.stem) != stem:
            continue
        if wanted.exists() and wanted.stat().st_mtime >= candidate.stat().st_mtime:
            continue
        candidate.replace(wanted)
        print("  renamed texture %s -> %s" % (candidate.name, wanted.name))
    if not wanted.exists():
        raise SystemExit("no texture %s.png (or a hyphenated form of it)" % stem)
    return wanted


def translucent(png):
    """Whether any pixel is neither fully clear nor fully opaque."""
    data = png.read_bytes()
    i, idat = 8, b""
    while i < len(data):
        n = struct.unpack(">I", data[i:i + 4])[0]
        kind, chunk = data[i + 4:i + 8], data[i + 8:i + 8 + n]
        i += 12 + n
        if kind == b"IHDR":
            width, height, depth, colour = struct.unpack(">IIBB", chunk[:10])
        elif kind == b"IDAT":
            idat += chunk
    if colour != 6 or depth != 8:
        return False
    raw, stride, prev, o = zlib.decompress(idat), width * 4, bytearray(width * 4), 0
    for _ in range(height):
        f, line = raw[o], bytearray(raw[o + 1:o + 1 + stride])
        o += 1 + stride
        for x in range(stride):
            a = line[x - 4] if x >= 4 else 0
            b, c = prev[x], prev[x - 4] if x >= 4 else 0
            if f == 1:
                line[x] = (line[x] + a) & 255
            elif f == 2:
                line[x] = (line[x] + b) & 255
            elif f == 3:
                line[x] = (line[x] + (a + b) // 2) & 255
            elif f == 4:
                p = a + b - c
                pa, pb, pc = abs(p - a), abs(p - b), abs(p - c)
                line[x] = (line[x] + (a if pa <= pb and pa <= pc else b if pb <= pc else c)) & 255
        if any(0 < line[x] < 255 for x in range(3, stride, 4)):
            return True
        prev = line
    return False


def move(elements, shift):
    for e in elements:
        e["from"] = [e["from"][i] + shift[i] for i in range(3)]
        e["to"] = [e["to"][i] + shift[i] for i in range(3)]
        rotation = e.get("rotation")
        if rotation and "origin" in rotation:
            rotation["origin"] = [rotation["origin"][i] + shift[i] for i in range(3)]


def scale(elements, factor, about):
    for e in elements:
        for key in ("from", "to"):
            e[key] = [about[i] + (e[key][i] - about[i]) * factor for i in range(3)]
        rotation = e.get("rotation")
        if rotation and "origin" in rotation:
            rotation["origin"] = [about[i] + (rotation["origin"][i] - about[i]) * factor for i in range(3)]


def bounds(elements):
    for e in elements:
        if e.get("rotation", {}).get("angle", 0) != 0:
            raise SystemExit("element %r is rotated; gen_relay.py does not account for that" % e.get("name"))
    lo = [min(min(e["from"][i], e["to"][i]) for e in elements) for i in range(3)]
    hi = [max(max(e["from"][i], e["to"][i]) for e in elements) for i in range(3)]
    return lo, hi


def recentre(elements):
    lo, hi = bounds(elements)
    move(elements, [8 - (lo[i] + hi[i]) / 2 for i in range(3)])
    lo, hi = bounds(elements)
    assert all(lo[i] >= 0 and hi[i] <= 16 for i in range(3)), "icon leaves the block"


def display(gui_scale):
    """The breakers' and PSU's poses, which a DIN device of this size already wears."""
    s = lambda f: [round(gui_scale * f, 4)] * 3
    return {
        "gui": {"rotation": [30, 225, 0], "translation": [0, 0, 0], "scale": s(1.5)},
        "ground": {"rotation": [0, 0, 0], "translation": [0, 3, 0], "scale": s(0.6)},
        "fixed": {"rotation": [0, 180, 0], "translation": [0, 0, 0], "scale": s(1.2)},
        "thirdperson_righthand": {"rotation": [75, 45, 0], "translation": [0, 2.5, 0], "scale": s(0.9)},
        "thirdperson_lefthand": {"rotation": [75, 45, 0], "translation": [0, 2.5, 0], "scale": s(0.9)},
        "firstperson_righthand": {"rotation": [0, 45, 0], "translation": [0, 0, 0], "scale": s(0.95)},
        "firstperson_lefthand": {"rotation": [0, 225, 0], "translation": [0, 0, 0], "scale": s(0.95)},
        "head": {"rotation": [0, 180, 0], "translation": [0, 13, 7], "scale": s(1)},
    }


def slots_of(elements):
    slots = []
    for e in elements:
        for face in e.get("faces", {}).values():
            slot = face.get("texture", "").lstrip("#")
            if slot and slot not in slots:
                slots.append(slot)
    return slots


def patch_missing(elements, textures, label):
    """Points faces on an undefined slot at the first defined one these elements use."""
    defined = [s for s in slots_of(elements) if s in textures]
    for e in elements:
        undefined = set()
        for face in e.get("faces", {}).values():
            slot = face.get("texture", "").lstrip("#")
            if slot and slot not in textures:
                if not defined:
                    raise SystemExit("%s: no defined texture to stand in for #%s" % (label, slot))
                undefined.add(slot)
                face["texture"] = "#" + defined[0]
        for slot in sorted(undefined):
            print("  WARNING: %s element %r has faces on undefined #%s; drawn with #%s until it is "
                  "assigned in Blockbench" % (label, e.get("name"), slot, defined[0]))


def used_textures(textures, elements):
    """Only the slots these elements draw with, and a particle from the first of them."""
    slots = slots_of(elements)
    out = {s: textures[s] for s in sorted(slots)}
    out["particle"] = textures[slots[0]]
    return out


def write_json(path, data):
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(data, indent=2) + NL, encoding="utf-8", newline="\n")


def fmt(v):
    return "[" + ", ".join("%g" % x for x in v) + "]"


def group_members(groups, name):
    """Element indices under the group called `name`, nested groups included."""
    def walk(node):
        if isinstance(node, int):
            return [node]
        return [i for child in node.get("children", []) for i in walk(child)]

    def find(nodes):
        for node in nodes:
            if isinstance(node, dict):
                if node.get("name") == name:
                    return walk(node)
                found = find(node.get("children", []))
                if found is not None:
                    return found
        return None

    return find(groups or [])


def check_pins(mount, body, pins):
    """Every pin in RelayMountGeometry's order, found by position among the body's elements."""
    print("  terminals, in RelayMountGeometry order:")
    for index, (name, want) in enumerate(pins):
        hits = [e for e in body if all(abs(a - b) < 1e-6 for a, b in zip(e["from"] + e["to"], want))]
        if not hits:
            raise SystemExit("%s: nothing is drawn where RelayMountGeometry puts %s, %s -> %s; update one to "
                             "match the other" % (mount, name, fmt(want[:3]), fmt(want[3:])))
        drawn = hits[0].get("name")
        aliases = {"A1": "coil1", "A2": "coil2"}
        note = "" if drawn in (name, aliases.get(name)) else "   (named %r in the export)" % drawn
        print("    %d %-4s %s -> %s%s" % (index, name, fmt(want[:3]), fmt(want[3:]), note))


def process(spec):
    mount, width = spec["mount"], spec["width"]
    source = json.loads((BLOCK_MODELS / (mount + ".json")).read_text(encoding="utf-8"))
    head = {k: v for k, v in source.items() if k not in STRIPPED and k not in ("textures", "elements", "display")}
    textures = {k: fold(v) for k, v in source["textures"].items() if k != "particle"}
    for reference in textures.values():
        rename_texture(reference)
    note = "tools/gen_relay.py from %s.json; re-run after re-exporting" % mount

    elements = json.loads(json.dumps(source["elements"]))
    lo, hi = bounds(elements)
    if abs(hi[0] - lo[0] - width) > 1e-9:
        raise SystemExit("%s spans x %g..%g, not the %g module its mount is" % (mount, lo[0], hi[0], width))
    shift = 8 - (lo[0] + hi[0]) / 2
    move(elements, [shift, 0, 0])

    chosen = set()
    relays = {}
    for relay, (kind, key) in spec["relays"].items():
        if kind == "element":
            picked = [i for i, e in enumerate(elements) if e.get("name") == key]
        else:
            picked = group_members(source.get("groups"), key) or []
        if not picked:
            raise SystemExit("%s: no %s named %s" % (mount, kind, key))
        chosen.update(picked)
        relays[relay] = [elements[i] for i in picked]
    body = [e for i, e in enumerate(elements) if i not in chosen]

    print("%s.json -> %s_body, %s (x shift %g)" % (mount, mount, ", ".join(relays), shift))
    patch_missing(body, textures, mount)
    write_json(BLOCK_MODELS / (mount + "_body.json"),
               {"__generated": note, **head, "textures": used_textures(textures, body), "elements": body})
    icon = json.loads(json.dumps(body))
    recentre(icon)
    write_json(ITEM_MODELS / (mount + ".json"),
               {"__generated": note, **head, "textures": used_textures(textures, body), "elements": icon,
                "display": display(1.0)})
    lo, hi = bounds(body)
    print("  body: %d elements, %s -> %s" % (len(body), fmt(lo), fmt(hi)))

    for relay, parts in relays.items():
        patch_missing(parts, textures, relay)
        used = used_textures(textures, parts)
        clear = any(translucent(rename_texture(ref)) for slot, ref in used.items() if slot != "particle")
        extra = {"render_type": "minecraft:translucent"} if clear else {}
        write_json(BLOCK_MODELS / (relay + ".json"),
                   {"__generated": note, **head, **extra, "textures": used, "elements": parts})
        icon = json.loads(json.dumps(parts))
        recentre(icon)
        scale(icon, RELAY_ICON_SCALE, [8, 8, 8])
        recentre(icon)
        write_json(ITEM_MODELS / (relay + ".json"),
                   {"__generated": note, **head, **extra, "textures": used, "elements": icon,
                    "display": display(1.0)})
        lo, hi = bounds(parts)
        print("  relay %s: %d elements, %s -> %s%s" % (relay, len(parts), fmt(lo), fmt(hi),
                                                     ", translucent" if clear else ""))

    check_pins(mount, body, spec["pins"])


def main():
    for spec in MOUNTS:
        process(spec)


if __name__ == "__main__":
    main()
