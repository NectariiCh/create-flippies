"""Write the PC case's and the keyboard's models from the authored Blockbench exports.

The exports -- `pc-case-1.json` and `keyboard-1.json` -- are the one place the case
and the keyboard are authored, and the user re-exports to them, so nothing here
edits them: every file the game loads is struck from them, and a re-export is wired
up by re-running this rather than by editing anything by hand.

What comes out of the case export (docs/PC_SPEC.md 11.2.1):

* `models/block/pc_case/shell.json` -- the case group without the buttons and the
  two lights. The block state draws it, so it is chunk geometry and costs nothing.
  The port cubes and the power socket stay in it, because they never change.
* one model per part the renderer draws while it is there: `front_panel`,
  `motherboard`, `floppy_disk` (the `floppy disc` cube, which is the disk itself),
  `ram_a`, `ram_b` (both named `ram`; slot A is the lower x), `hdd`, `cpu`,
  `power_button`, `reset_button`, `power_led`, `error_led`. Each keeps the
  coordinates it was authored at, so the renderer only turns it with the case.
* `power_plug` -- a cube synthesised in front of the socket, drawn while Power
  Grid's cord is plugged in.
* `data_plug_<type>` -- a 0.5 px deep cube the size of one port type's face, drawn
  at every data cord end. Authored with its back face on the plane x = 8 and its
  face centred on (8, 8), so the renderer can put it on any port.
* `blockstates/pc_case.json` -- the shell, turned so the model's -x end (buttons
  and floppy slot) faces the player who placed it.
* item models: `pc_case` (shell, front panel, buttons and lights), one per
  component item reusing that component's part, moved to the middle of the item's
  box, and `data_cord`, a flat item with a placeholder texture until the user
  supplies one.

And from the keyboard export (11.2.12): `models/block/keyboard.json`, its block
state and its item model.

What is dropped and kept, as gen_plc.py does: `groups`, `format_version`,
`credit` and `texture_size` go (the game ignores them, and a wrong `texture_size`
reads as though the uv meant something else); each part keeps only the texture
slots its faces sample, and `particle` is set.

It prints the port, button and part boxes in the frozen order, so
`PcCaseGeometry` can be checked against the export after any change, and fails if a
named element is missing, because the port order is positional and saved links
depend on it.

Run from the repo root:

    python tools/gen_pc_case.py

Needs nothing but the standard library. It writes only under src/main/resources and
takes no part in the Gradle build.
"""

import json
import os
import struct
import zlib

ASSETS = os.path.join("src", "main", "resources", "assets", "create_flippies")
MODELS = os.path.join(ASSETS, "models", "block")
PARTS = os.path.join(MODELS, "pc_case")
ITEM_MODELS = os.path.join(ASSETS, "models", "item")
BLOCKSTATES = os.path.join(ASSETS, "blockstates")
ITEM_TEXTURES = os.path.join(ASSETS, "textures", "item")

CASE_SOURCE = "pc-case-1"
KEYBOARD_SOURCE = "keyboard-1"
NAMESPACE = "create_flippies"

STRIPPED = ("format_version", "credit", "texture_size", "groups")

# The data ports in the frozen order PcCaseGeometry numbers them. Never reorder:
# saved links name a port by its index.
PORTS = ["ps/2 1", "ps/2 2", "ethernet", "com1", "com2", "com3", "vga1", "vga2", "vga3"]
SOCKET = "power connector"
BUTTONS = ["power button", "reset button"]
LIGHTS = ["power indicator", "error indicator"]

# Part model name -> export element name. Both RAM sticks are named `ram`; they are
# told apart by x, slot A being the lower.
PART_ELEMENTS = {
    "front_panel": "front panel",
    "motherboard": "motherboard",
    "floppy_disk": "floppy disc",
    "hdd": "hdd",
    "cpu": "cpu",
    "power_button": "power button",
    "reset_button": "reset button",
    "power_led": "power indicator",
    "error_led": "error indicator",
}

# The plug Power Grid's cord ends in, synthesised in front of the socket (11.2.1).
POWER_PLUG = {"name": "power plug", "from": [14.25, 1.5, 9.25], "to": [15.5, 2.75, 10.5]}

# One plug per port type, the size of that type's port face, from the ports below.
PLUG_TYPES = {"ps2": "ps/2 1", "ethernet": "ethernet", "com": "com1", "vga": "vga1"}
PLUG_DEPTH = 0.5
PLUG_TEXTURE = "powergrid:block/plug"

# The component items and the part each one's item model is struck from.
COMPONENT_ITEMS = {
    "pc_front_panel": "front_panel",
    "pc_motherboard": "motherboard",
    "floppy_disk": "floppy_disk",
    "cpu_rv32i_10mhz": "cpu",
    "cpu_rv32i_25mhz": "cpu",
    "cpu_rv32i_50mhz": "cpu",
    "cpu_68000_8mhz": "cpu",
    "cpu_68000_12_5mhz": "cpu",
    "cpu_68040_25mhz": "cpu",
    "cpu_68040_33mhz": "cpu",
    "ram_512k": "ram_a",
    "ram_1m": "ram_a",
    "ram_4m": "ram_a",
    "ram_16m": "ram_a",
    "ram_64m": "ram_a",
    "hdd_16m": "hdd",
    "hdd_64m": "hdd",
    "hdd_256m": "hdd",
    "hdd_1g": "hdd",
}

# Facing -> y rotation that turns the model's -x end towards that direction.
# A block state's y rotation turns clockwise seen from above: west to north at 90.
FACING_Y = {"west": 0, "north": 90, "east": 180, "south": 270}


def write_json(path, value):
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, "w", encoding="utf-8", newline="\n") as f:
        f.write(json.dumps(value, indent=2, ensure_ascii=False) + "\n")


def read_json(path):
    with open(path, encoding="utf-8") as f:
        return json.load(f)


def fmt(v):
    return "[" + ", ".join(f"{x:g}" for x in v) + "]"


def ref(path):
    return f"{NAMESPACE}:{path}"


def copy(value):
    return json.loads(json.dumps(value))


def group_children(groups, name):
    """Every element index under the named group, nested groups included."""
    found = set()

    def walk(nodes, inside):
        for node in nodes:
            if isinstance(node, int):
                if inside:
                    found.add(node)
                continue
            walk(node.get("children", []), inside or node.get("name") == name)

    walk(groups, False)
    return found


def textures_used(elements):
    used = set()
    for e in elements:
        for face in e["faces"].values():
            used.add(face.get("texture", "").lstrip("#"))
    return used


def model(export, elements, particle_slot=None):
    """A model holding `elements`, with only the texture slots they sample."""
    used = textures_used(elements)
    textures = {k: v for k, v in export["textures"].items() if k in used}
    slot = particle_slot if particle_slot in used else sorted(used)[0]
    textures["particle"] = export["textures"][slot]
    return {"textures": textures, "elements": copy(elements)}


def bounds(elements):
    lo = [min(min(e["from"][a], e["to"][a]) for e in elements) for a in range(3)]
    hi = [max(max(e["from"][a], e["to"][a]) for e in elements) for a in range(3)]
    return lo, hi


def moved(elements, shift):
    out = []
    for e in copy(elements):
        e["from"] = [e["from"][a] + shift[a] for a in range(3)]
        e["to"] = [e["to"][a] + shift[a] for a in range(3)]
        if "rotation" in e:
            e["rotation"]["origin"] = [e["rotation"]["origin"][a] + shift[a] for a in range(3)]
        out.append(e)
    return out


def item_display(size):
    """Display transforms that show a part of `size` px across at a readable size."""
    scale = max(0.4, min(4.0, 0.625 * 12.0 / size))
    hand = max(0.3, min(2.0, 0.4 * 12.0 / size))
    return {
        "gui": {"rotation": [30, 225, 0], "translation": [0, 0, 0], "scale": [scale] * 3},
        "ground": {"rotation": [0, 0, 0], "translation": [0, 3, 0], "scale": [hand * 0.6] * 3},
        "fixed": {"rotation": [0, 90, 0], "translation": [0, 0, 0], "scale": [scale * 0.8] * 3},
        "thirdperson_righthand": {"rotation": [75, 45, 0], "translation": [0, 2.5, 0], "scale": [hand * 0.6] * 3},
        "thirdperson_lefthand": {"rotation": [75, 45, 0], "translation": [0, 2.5, 0], "scale": [hand * 0.6] * 3},
        "firstperson_righthand": {"rotation": [0, 45, 0], "translation": [0, 0, 0], "scale": [hand] * 3},
        "firstperson_lefthand": {"rotation": [0, 225, 0], "translation": [0, 0, 0], "scale": [hand] * 3},
    }


def centred_item(export, elements):
    lo, hi = bounds(elements)
    shift = [8 - (lo[a] + hi[a]) / 2 for a in range(3)]
    item = model(export, moved(elements, shift))
    item["display"] = item_display(max(hi[a] - lo[a] for a in range(3)))
    return item


def placeholder_png(path, rgb):
    """A 16x16 flat-colour PNG with a darker border, written only if missing."""
    if os.path.exists(path):
        return
    dark = tuple(c // 2 for c in rgb)
    rows = b""
    for y in range(16):
        row = b"\x00"
        for x in range(16):
            edge = x in (0, 15) or y in (0, 15)
            on = 3 <= x <= 12 and 3 <= y <= 12
            colour = dark if edge else rgb
            row += bytes(colour) + (b"\xff" if (on or edge) else b"\x00")
        rows += row

    def chunk(kind, data):
        return struct.pack(">I", len(data)) + kind + data + struct.pack(">I", zlib.crc32(kind + data) & 0xFFFFFFFF)

    png = b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", struct.pack(">IIBBBBB", 16, 16, 8, 6, 0, 0, 0))
    png += chunk(b"IDAT", zlib.compress(rows)) + chunk(b"IEND", b"")
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, "wb") as f:
        f.write(png)


def blockstate(target):
    return {"variants": {f"facing={facing}": ({"model": ref(target), "y": y} if y else {"model": ref(target)})
                         for facing, y in FACING_Y.items()}}


def case(export):
    elements = export["elements"]
    named = {}
    for i, e in enumerate(elements):
        named.setdefault(e.get("name"), []).append(i)
    wanted = PORTS + [SOCKET] + BUTTONS + LIGHTS + list(PART_ELEMENTS.values())
    missing = [n for n in wanted if n not in named]
    assert not missing, f"{CASE_SOURCE}: no element named {', '.join(missing)}"
    assert len(named.get("ram", [])) == 2, f"{CASE_SOURCE}: expected two elements named 'ram'"

    case_group = group_children(export.get("groups", []), "case")
    assert case_group, f"{CASE_SOURCE}: no 'case' group; has the export been restructured?"
    moving = {named[n][0] for n in BUTTONS + LIGHTS}
    shell = [elements[i] for i in sorted(case_group) if i not in moving]

    write_json(os.path.join(PARTS, "shell.json"), model(export, shell, "0"))

    parts = {name: [elements[named[source][0]]] for name, source in PART_ELEMENTS.items()}
    ram = sorted(named["ram"], key=lambda i: min(elements[i]["from"][0], elements[i]["to"][0]))
    parts["ram_a"] = [elements[ram[0]]]
    parts["ram_b"] = [elements[ram[1]]]
    for name, part in parts.items():
        write_json(os.path.join(PARTS, name + ".json"), model(export, part))

    # The export has no plug of its own, so plugs wear Power Grid's plug texture
    # rather than a corner of a parts sheet
    plug_faces = {face: {"uv": [0, 0, 16, 16], "texture": "#plug"}
                  for face in ("north", "east", "south", "west", "up", "down")}

    def plug_model(cube):
        return {"textures": {"plug": PLUG_TEXTURE, "particle": PLUG_TEXTURE}, "elements": [cube]}

    plug = dict(POWER_PLUG, faces=plug_faces)
    write_json(os.path.join(PARTS, "power_plug.json"), plug_model(plug))

    for kind, port in PLUG_TYPES.items():
        e = elements[named[port][0]]
        dy = abs(e["to"][1] - e["from"][1]) / 2
        dz = abs(e["to"][2] - e["from"][2]) / 2
        cube = {"name": "data plug", "from": [8, 8 - dy, 8 - dz], "to": [8 + PLUG_DEPTH, 8 + dy, 8 + dz],
                "faces": copy(plug_faces)}
        write_json(os.path.join(PARTS, f"data_plug_{kind}.json"), plug_model(cube))

    write_json(os.path.join(BLOCKSTATES, "pc_case.json"), blockstate("block/pc_case/shell"))

    whole = shell + parts["front_panel"] + [elements[named[n][0]] for n in BUTTONS + LIGHTS]
    item = model(export, whole, "0")
    item["parent"] = "minecraft:block/block"
    write_json(os.path.join(ITEM_MODELS, "pc_case.json"), item)
    for item_id, part in COMPONENT_ITEMS.items():
        write_json(os.path.join(ITEM_MODELS, item_id + ".json"), centred_item(export, parts[part]))

    write_json(os.path.join(ITEM_MODELS, "data_cord.json"),
               {"parent": "minecraft:item/generated", "textures": {"layer0": ref("item/data_cord")}})
    placeholder_png(os.path.join(ITEM_TEXTURES, "data_cord.png"), (70, 90, 160))

    lo, hi = bounds(shell)
    print(f"wrote pc_case: shell of {len(shell)} elements, bounds {fmt(lo)} -> {fmt(hi)}")
    print("ports, in PcCaseGeometry order (from -> to, px as authored):")
    for index, name in enumerate(PORTS):
        e = elements[named[name][0]]
        print(f"  {index} {name:9s} {fmt(e['from'])} -> {fmt(e['to'])}")
    e = elements[named[SOCKET][0]]
    print(f"  socket      {fmt(e['from'])} -> {fmt(e['to'])}")
    print("buttons and lights:")
    for name in BUTTONS + LIGHTS:
        e = elements[named[name][0]]
        print(f"  {name:16s} {fmt(e['from'])} -> {fmt(e['to'])}")
    print("parts:")
    for name, part in parts.items():
        print(f"  {name:13s} {fmt(part[0]['from'])} -> {fmt(part[0]['to'])}")


def keyboard(export):
    elements = export["elements"]
    ports = [e for e in elements if e.get("name") == "ps/2"]
    assert len(ports) == 1, f"{KEYBOARD_SOURCE}: expected one element named 'ps/2'"
    write_json(os.path.join(MODELS, "keyboard.json"), model(export, elements, "0"))
    write_json(os.path.join(BLOCKSTATES, "keyboard.json"), blockstate("block/keyboard"))
    item = model(export, elements, "0")
    item["parent"] = "minecraft:block/block"
    write_json(os.path.join(ITEM_MODELS, "keyboard.json"), item)
    lo, hi = bounds([e for e in elements if e.get("name") != "ps/2"])
    print(f"wrote keyboard: body {fmt(lo)} -> {fmt(hi)}, ps/2 {fmt(ports[0]['from'])} -> {fmt(ports[0]['to'])}")


def main():
    case(read_json(os.path.join(MODELS, CASE_SOURCE + ".json")))
    keyboard(read_json(os.path.join(MODELS, KEYBOARD_SOURCE + ".json")))


if __name__ == "__main__":
    main()
