"""Write the DIN-rail small PLC's models from the authored Blockbench export.

The export -- `plc_fwm32f103_iop_small_din.json` -- is the one place the PLC is
authored, and it is drawn standing on a short length of rail so that it can be
checked against the lips it clips to. Everything the game loads is struck from it
here, so a re-export is wired up by re-running this rather than by editing anything
by hand.

Two files come out of it:

* `models/block/plc_s32f103-d_din.json` -- the partial `PlcSmallControlRenderer` draws
  for a PLC clipped to a rail (`ModPartialModels.PLC_S32F103_D_DIN`). The rail section
  the export carries for reference is dropped: the rail is a block of its own and is
  drawn by its own model, so keeping it here would draw a second rail through the
  first wherever a PLC sits.
* `models/item/plc_s32f103-d_din.json` -- the same body, lifted so it sits in the middle
  of an item's box, with the display transforms the hand-made item model already had.

What is kept and what is dropped:

* every kept element is turned half a turn about the vertical axis through the
  cell's centre (`spun`): the export is drawn upside down relative to how the PLC
  reads on a rail, and `PlcSmallGeometry.pin` mirrors the terminals to match, so
  the boxes a click lands on stay on the copper that is drawn;

* the `din_rail_1` group's elements go, by the group's own child list rather than
  by name or index, so a re-export that adds or reorders elements still drops the
  right ones;
* `groups` and `__generated` go -- the game does not read them, and the export's
  `__generated` is a stale note about where its rail section was copied from;
* `format_version`, `credit` and `texture_size` go, for the reason gen_display.py
  gives: the game ignores them and a wrong `texture_size` reads as though the uv
  meant something else;
* textures keep the two slots the body uses -- `0` the case and `1` the copper
  terminals -- and `particle` is pointed at the case rather than at the rail, which
  is what the export names because the rail happened to be the first thing drawn.

It also prints every terminal box it finds, by name, in the frozen terminal order,
so `PlcSmallGeometry` can be checked against the export after any change. The
names are the export's (`pin_a_00`..`pin_a_07`, `VCC`, `GND`, `pin_b_00`..
`pin_b_07`, `STLED`, `RESET`); the run fails if any is missing, because
the terminal order is positional and saved wires depend on it.

Run from the repo root:

    python tools/gen_plc.py

Needs nothing but the standard library. It writes only under src/main/resources and
takes no part in the Gradle build.
"""

import json
import os

ASSETS = os.path.join("src", "main", "resources", "assets", "create_flippies")
MODELS = os.path.join(ASSETS, "models", "block")
ITEM_MODELS = os.path.join(ASSETS, "models", "item")

SOURCE = "plc_fwm32f103_iop_small_din"
TARGET = "plc_s32f103-d_din"

# The group that is the reference length of rail, not part of the PLC.
RAIL_GROUP = "din_rail_1"

# The texture slots the PLC itself samples. Anything else the export lists (the
# rail's `1_0`) belongs to the dropped group.
KEPT_TEXTURES = ("0", "1", "3")
PARTICLE = "create_flippies:block/plc_small_body"

STRIPPED = ("format_version", "credit", "texture_size", "groups", "__generated")

# How far the item model lifts the body: the hand-made item model stood the base
# (authored at y 0.75 on the rail's lips) at 6.625, which centres the 3.5 px tall
# case in the item's box. Kept so the icon does not move when this replaces it.
ITEM_LIFT = 5.875

# The terminals in the order PlcSmallGeometry numbers them. Never reorder: Power
# Grid numbers a control's terminals positionally and saved wires depend on it.
TERMINALS = (
    [f"pin_a_{i:02d}" for i in range(8)]
    + ["VCC", "GND"]
    + [f"pin_b_{i:02d}" for i in range(8)]
    + ["STLED", "RESET"]
)

# Used only if there is no item model yet to take the transforms from. The
# breakers' small-part transforms, which is what the hand-made model used.
DEFAULT_DISPLAY = {
    "gui": {"rotation": [30, 225, 0], "translation": [0, 0, 0], "scale": [1, 1, 1]},
    "ground": {"rotation": [0, 0, 0], "translation": [0, 3, 0], "scale": [0.5, 0.5, 0.5]},
    "fixed": {"rotation": [0, 180, 0], "translation": [0, 0, 0], "scale": [1, 1, 1]},
    "thirdperson_righthand": {"rotation": [75, 45, 0], "translation": [0, 2.5, 0], "scale": [0.6, 0.6, 0.6]},
    "thirdperson_lefthand": {"rotation": [75, 45, 0], "translation": [0, 2.5, 0], "scale": [0.6, 0.6, 0.6]},
    "firstperson_righthand": {"rotation": [0, 45, 0], "translation": [0, 0, 0], "scale": [0.65, 0.65, 0.65]},
    "firstperson_lefthand": {"rotation": [0, 225, 0], "translation": [0, 0, 0], "scale": [0.65, 0.65, 0.65]},
    "head": {"rotation": [0, 180, 0], "translation": [0, 13, 7], "scale": [1, 1, 1]},
}


def write_json(path, value):
    with open(path, "w", encoding="utf-8", newline="\n") as f:
        f.write(json.dumps(value, indent=2, ensure_ascii=False) + "\n")


def read_json(path):
    with open(path, encoding="utf-8") as f:
        return json.load(f)


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


def resource_texture(value):
    """A texture reference in the lower case form Minecraft will load."""
    namespace, _, path = value.partition(":")
    return namespace + ":" + path.lower().replace("-", "_")


def body_of(export):
    rail = group_children(export.get("groups", []), RAIL_GROUP)
    assert rail, f"{SOURCE}: no '{RAIL_GROUP}' group; has the export been restructured?"
    elements = [spun(e) for i, e in enumerate(export["elements"]) if i not in rail]
    for element in elements:
        for face in element["faces"].values():
            texture = face.get("texture", "")
            assert texture.lstrip("#") in KEPT_TEXTURES, \
                f"{SOURCE}: element '{element.get('name')}' samples {texture}, which is not a PLC texture"
    return elements


def head(export):
    textures = {k: resource_texture(v) for k, v in export["textures"].items() if k in KEPT_TEXTURES}
    textures["particle"] = PARTICLE
    model = {k: v for k, v in export.items() if k not in STRIPPED and k not in ("textures", "elements")}
    model["textures"] = textures
    return model


def spun(element):
    """The element turned 180 degrees about the vertical line x = z = 8.

    x and z are mirrored, so north/south and east/west swap faces; a side face
    keeps its uv, because it is still seen upright from outside, and the top and
    bottom faces turn their texture half a turn with the element. A tilt about x
    or z changes sign, because that axis now points the other way.
    """
    copy = json.loads(json.dumps(element))
    f, t = element["from"], element["to"]
    copy["from"] = [16 - t[0], f[1], 16 - t[2]]
    copy["to"] = [16 - f[0], t[1], 16 - f[2]]
    rotation = copy.get("rotation")
    if rotation:
        o = rotation["origin"]
        rotation["origin"] = [16 - o[0], o[1], 16 - o[2]]
        if rotation.get("axis") in ("x", "z"):
            rotation["angle"] = -rotation["angle"]
    swap = {"north": "south", "south": "north", "east": "west", "west": "east"}
    faces = {}
    for name, face in element["faces"].items():
        face = json.loads(json.dumps(face))
        if name in ("up", "down"):
            turned = (face.get("rotation", 0) + 180) % 360
            if turned:
                face["rotation"] = turned
            else:
                face.pop("rotation", None)
        if "cullface" in face:
            face["cullface"] = swap.get(face["cullface"], face["cullface"])
        faces[swap.get(name, name)] = face
    copy["faces"] = faces
    return copy


def lifted(element, dy):
    copy = json.loads(json.dumps(element))
    copy["from"][1] += dy
    copy["to"][1] += dy
    if "rotation" in copy:
        copy["rotation"]["origin"][1] += dy
    return copy


def report_terminals(elements):
    by_name = {e.get("name"): e for e in elements}
    missing = [name for name in TERMINALS if name not in by_name]
    assert not missing, f"{SOURCE}: no element for terminal(s) {', '.join(missing)}"
    print("terminals, in PlcSmallGeometry order (from -> to, px as drawn, after the half turn):")
    for index, name in enumerate(TERMINALS):
        e = by_name[name]
        print(f"  {index:2d} {name:9s} {fmt(e['from'])} -> {fmt(e['to'])}")


def fmt(v):
    return "[" + ", ".join(f"{x:g}" for x in v) + "]"


def main():
    export = read_json(os.path.join(MODELS, SOURCE + ".json"))
    elements = body_of(export)

    block = head(export)
    block["elements"] = elements
    write_json(os.path.join(MODELS, TARGET + ".json"), block)

    item_path = os.path.join(ITEM_MODELS, TARGET + ".json")
    display = DEFAULT_DISPLAY
    if os.path.exists(item_path):
        display = read_json(item_path).get("display", DEFAULT_DISPLAY)
    item = head(export)
    item["elements"] = [lifted(e, ITEM_LIFT) for e in elements]
    item["display"] = display
    write_json(item_path, item)

    lo = [min(min(e["from"][a], e["to"][a]) for e in elements) for a in range(3)]
    hi = [max(max(e["from"][a], e["to"][a]) for e in elements) for a in range(3)]
    print(f"wrote {TARGET} (block partial, item): {len(elements)} elements, "
          f"dropped {len(export['elements']) - len(elements)} rail elements")
    print(f"body bounds {fmt(lo)} -> {fmt(hi)}")
    report_terminals(elements)


if __name__ == "__main__":
    main()
