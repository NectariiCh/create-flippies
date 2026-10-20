"""Write the CRT monitor's models from the authored Blockbench export.

The export `models/block/crt-1.json` (and its texture, exported next to it as
`crt-1.png`) is where the CRT is authored, and the user re-exports to it, so
nothing here edits it: every file the game loads is struck from it, as
gen_pc_case.py does for the case and the keyboard (docs/PC_SPEC.md 11.2.14).

What comes out:

* `models/block/crt.json` -- the whole export, `screen` cube included (its +x face
  is the dark glass seen while the monitor is off; the renderer draws the picture
  just in front of it). The block state draws it, so it is chunk geometry.
* `models/block/crt/power_plug.json` -- a cube synthesised behind the `power in`
  socket, drawn while Power Grid's cord is plugged in.
* `blockstates/crt.json` -- the model turned so its +x side (the screen) faces the
  player who placed it: the case's turning with the opposite direction.
* `models/item/crt.json` -- the whole model as a block item.
* `textures/block/crt-1.png` -- a copy of the exported texture, where the game
  looks for it, written when missing or different.

A texture named without a namespace in the export (`crt-1`) is read as
`create_flippies:block/crt-1`. Unnamed elements, `groups`, `format_version`,
`credit` and `texture_size` are handled as gen_pc_case.py handles them.

It prints the screen and port boxes so `CrtGeometry` can be checked against the
export, and fails if a named element is missing.

Run from the repo root:

    python tools/gen_crt.py

Needs nothing but the standard library. It writes only under src/main/resources and
takes no part in the Gradle build.
"""

import os
import shutil

import gen_pc_case as pc

SOURCE = "crt-1"
SCREEN = "screen"
VGA_IN = "vga in"
POWER_IN = "power in"

# The plug Power Grid's cord ends in, behind the socket (`power in` is x 0..0.5,
# y 0.5..1.5, z 4..5.75): 1.25 px deep, 0.25 px in from the socket's edges.
POWER_PLUG = {"name": "power plug", "from": [-1.25, 0.75, 4.25], "to": [0, 1.25, 5.5]}

OPPOSITE = {"north": "south", "south": "north", "east": "west", "west": "east"}


def namespaced(textures):
    out = {}
    for key, value in textures.items():
        out[key] = value if ":" in value else pc.ref("block/" + value)
    return out


def copy_texture(export):
    name = export["textures"].get("0", SOURCE)
    name = name.split(":")[-1].split("/")[-1]
    source = os.path.join(pc.MODELS, name + ".png")
    target = os.path.join(pc.ASSETS, "textures", "block", name + ".png")
    if not os.path.exists(source):
        assert os.path.exists(target), f"{SOURCE}: no texture at {source} or {target}"
        return
    if os.path.exists(target):
        with open(source, "rb") as a, open(target, "rb") as b:
            if a.read() == b.read():
                return
    os.makedirs(os.path.dirname(target), exist_ok=True)
    shutil.copyfile(source, target)
    print(f"copied {source} -> {target}")


def main():
    export = pc.read_json(os.path.join(pc.MODELS, SOURCE + ".json"))
    export = dict(export, textures=namespaced(export["textures"]))
    elements = export["elements"]
    named = {}
    for e in elements:
        named.setdefault(e.get("name"), []).append(e)
    for name in (SCREEN, VGA_IN, POWER_IN):
        assert len(named.get(name, [])) == 1, f"{SOURCE}: expected one element named '{name}'"

    pc.write_json(os.path.join(pc.MODELS, "crt.json"), pc.model(export, elements, "0"))

    plug_faces = {face: {"uv": [0, 0, 16, 16], "texture": "#plug"}
                  for face in ("north", "east", "south", "west", "up", "down")}
    plug = dict(POWER_PLUG, faces=plug_faces)
    pc.write_json(os.path.join(pc.MODELS, "crt", "power_plug.json"),
                  {"textures": {"plug": pc.PLUG_TEXTURE, "particle": pc.PLUG_TEXTURE}, "elements": [plug]})

    states = {}
    for facing in pc.FACING_Y:
        y = pc.FACING_Y[OPPOSITE[facing]]
        states[f"facing={facing}"] = {"model": pc.ref("block/crt"), "y": y} if y else {"model": pc.ref("block/crt")}
    pc.write_json(os.path.join(pc.BLOCKSTATES, "crt.json"), {"variants": states})

    item = pc.model(export, elements, "0")
    item["parent"] = "minecraft:block/block"
    pc.write_json(os.path.join(pc.ITEM_MODELS, "crt.json"), item)

    copy_texture(export)

    lo, hi = pc.bounds(elements)
    print(f"wrote crt: {len(elements)} elements, bounds {pc.fmt(lo)} -> {pc.fmt(hi)}")
    for name in (SCREEN, VGA_IN, POWER_IN):
        e = named[name][0]
        print(f"  {name:8s} {pc.fmt(e['from'])} -> {pc.fmt(e['to'])}")


if __name__ == "__main__":
    main()
