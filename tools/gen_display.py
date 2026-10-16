"""Write the alphanumeric displays' assets from the authored Blockbench exports.

One display is one export -- `alphanum-display-<border>-<size>-<shape>.json` -- and
everything the game loads is struck from it here, so a re-export is wired up by
re-running this rather than by editing anything by hand.

<h2>What a display is split into</h2>

Each export becomes two models plus a stub:

* `<form>_screen` -- everything standing in the display's own cell, which is the
  glass. It is drawn on its own so the renderer can raise its light level when the
  backlight is driven: a lit screen glows where it is drawn without lighting the room,
  exactly as an indicator's lens does. It has to be a split rather than Forge's
  per-face emissive data, because the model goes through a SuperByteBuffer and
  `light()` rewrites the lightmap of every vertex it is handed, baked-in face data
  included.
* `<form>_body` -- everything behind the panel: the barrel and the rack of pins.
  Always drawn at whatever light is falling on the panel.
* `<form>` -- a particle-only stub for the block states to point at. A display is
  drawn entirely from partials by a block entity renderer, so a real model here would
  draw a second display inside the first; all the block state is for is the particle a
  broken one throws and the texture a breaking one is stippled with.

Which elements are the screen is not guessed by index: it is everything that reaches
above the mounting plane. The exports are authored in the frame everything
panel-mounted uses -- the face of the device flush with the floor of its own cell and
the body hanging through the panel below it -- so `y > 0` is "in front of the panel"
and nothing else can be.

<h2>Blocks</h2>

One block per form and rated supply voltage, because the rating is the resistance the
display puts on the line and therefore cannot change under a wire. Every rating of a
form shares every model written here; what differs between them is only how much
voltage they want.

Run from the repo root:

    python tools/gen_display.py
"""

import json
import os
import re

ASSETS = os.path.join("src", "main", "resources", "assets", "create_flippies")
MODELS = os.path.join(ASSETS, "models", "block")
ITEM_MODELS = os.path.join(ASSETS, "models", "item")
BLOCKSTATES = os.path.join(ASSETS, "blockstates")

# The authored exports, by the id their generated files and their blocks are named
# after. Never written. Add a line here when a new one is dropped in.
SOURCES = {
    "brdrl_1p5_sq": "alphanum-display-brdrl-1p5-sq",
}

# One block per form and rating; see the module comment. These mirror SupplyVoltage.
RATINGS = ("5v", "12v", "24v")

FACINGS = ("down", "up", "north", "south", "west", "east")

MIDDLE = 8.0

# How much bigger a display is drawn as an item than it is on a wall. The borderless
# 1.5 px screen is a speck in an inventory slot otherwise, and a display transform
# cannot rescue it -- vanilla clamps display scale at 4. The same factor the
# indicators use, and for the same reason: the model is only ever a picture of the
# unit, never the unit itself.
ITEM_SCALE = 3.0

# How a display is held. The rotary switches' transforms, which are authored for a
# part standing off a panel rather than for a flat item.
ITEM_DISPLAY = {
    "thirdperson_righthand": {"rotation": [75, 45, 0], "translation": [0, 2.5, 0], "scale": [0.9, 0.9, 0.9]},
    "thirdperson_lefthand": {"rotation": [75, 45, 0], "translation": [0, 2.5, 0], "scale": [0.9, 0.9, 0.9]},
    "firstperson_righthand": {"rotation": [0, 45, 0], "translation": [0, 0, 0], "scale": [0.95, 0.95, 0.95]},
    "firstperson_lefthand": {"rotation": [0, 225, 0], "translation": [0, 0, 0], "scale": [0.95, 0.95, 0.95]},
    "ground": {"rotation": [0, 0, 0], "translation": [0, 3, 0], "scale": [0.6, 0.6, 0.6]},
    "gui": {"rotation": [30, 225, 0], "translation": [0, 0, 0], "scale": [1.5, 1.5, 1.5]},
    "head": {"rotation": [0, 180, 0], "translation": [0, 13, 7], "scale": [1, 1, 1]},
    "fixed": {"rotation": [0, 180, 0], "translation": [0, 0, 0], "scale": [1.2, 1.2, 1.2]},
}

# Dropped from the generated models. `format_version` and `credit` are Blockbench's
# own bookkeeping, and `texture_size` is a Blockbench field the game does not read --
# a model's uv is always measured in 0..16 whatever the texture's real resolution is,
# so leaving a wrong one in reads as though the uv meant something else.
STRIPPED = ("format_version", "credit", "texture_size")


def write_json(path, value):
    with open(path, "w", encoding="utf-8", newline="\n") as f:
        f.write(json.dumps(value, indent=2, ensure_ascii=False) + "\n")


def load(source):
    with open(os.path.join(MODELS, source + ".json"), encoding="utf-8") as f:
        return json.load(f)


def screen_texture(model):
    """Which texture slot the screen samples, for the particle a broken one throws.

    The first slot that is not the shared copper the pins are drawn in, so a form
    that lists its textures in another order still names its own.
    """
    for key, texture in model["textures"].items():
        if key != "particle" and not texture.endswith("copper_contacts"):
            return texture
    return next(iter(model["textures"].values()))


def head(model, texture):
    """The parts of a generated model that every one of them shares."""
    textures = {k: v for k, v in model["textures"].items() if k != "particle"}
    textures["particle"] = texture
    return {
        "render_type": "minecraft:cutout",
        "textures": textures,
    }


def split(form, source, model, texture):
    """The screen and the body, as the two models the renderer actually draws.

    Both keep the whole export's texture list. A slot the remaining elements do not
    use costs nothing -- an unreferenced texture is simply not stitched -- and keeping
    the lists identical means the two halves diff against the export line for line.
    """
    screen = [e for e in model["elements"] if max(e["from"][1], e["to"][1]) > 0]
    body = [e for e in model["elements"] if max(e["from"][1], e["to"][1]) <= 0]
    assert screen, f"{source}: nothing stands in front of the panel, so there is no screen"
    assert body, f"{source}: nothing stands behind the panel, so there is no body"

    for suffix, elements in (("screen", screen), ("body", body)):
        part = head(model, texture)
        part["__generated"] = (f"tools/gen_display.py from {source}.json; "
                               "re-run after re-exporting")
        part["elements"] = elements
        write_json(os.path.join(MODELS, f"alphanum_display_{form}_{suffix}.json"), part)
    return screen, body


def write_stub(form, texture):
    write_json(os.path.join(MODELS, f"alphanum_display_{form}.json"),
               {"textures": {"particle": texture}})


def write_blockstates(form):
    """Every orientation listed, all pointing at the same stub.

    All 24 are written out rather than left to a default, because the renderer reaches
    orientations a variant's x/y rotations cannot express and the block state exists
    only to be a valid one, not to aim anything.
    """
    for rating in RATINGS:
        variants = {f"facing={facing},rotation={rotation}":
                    {"model": f"create_flippies:block/alphanum_display_{form}"}
                    for facing in FACINGS for rotation in range(4)}
        write_json(os.path.join(BLOCKSTATES, f"alphanum_display_{form}_{rating}.json"),
                   {"variants": variants})


def scaled(value, shift, axis):
    """One authored coordinate, recentred on the cube and then blown up about it."""
    return round(MIDDLE + (value + shift[axis] - MIDDLE) * ITEM_SCALE, 6)


def write_item_models(form, source, model, texture):
    elements = [dict(e) for e in model["elements"]]
    lo = [min(e["from"][a] for e in elements) for a in range(3)]
    hi = [max(e["to"][a] for e in elements) for a in range(3)]
    shift = [MIDDLE - (lo[a] + hi[a]) / 2 for a in range(3)]

    for element in elements:
        for key in ("from", "to"):
            element[key] = [scaled(element[key][a], shift, a) for a in range(3)]
        if "rotation" in element:
            rotation = dict(element["rotation"])
            rotation["origin"] = [scaled(rotation["origin"][a], shift, a) for a in range(3)]
            element["rotation"] = rotation

    item = f"alphanum_display_{form}"
    whole = head(model, texture)
    whole["__generated"] = (f"block/{source} recentred on the block middle and scaled "
                            f"{ITEM_SCALE:g}x; re-run tools/gen_display.py after re-exporting")
    whole["elements"] = elements
    whole["display"] = ITEM_DISPLAY
    write_json(os.path.join(ITEM_MODELS, item + ".json"), whole)
    for rating in RATINGS:
        write_json(os.path.join(ITEM_MODELS, f"{item}_{rating}.json"),
                   {"parent": f"create_flippies:item/{item}"})


def report(form, model, screen, body):
    """The boxes the Java side states as constants, printed so the two can be checked.

    `DisplayForm` and `DisplayPins` mirror the export, and nothing enforces that from
    here -- so a re-export that moved the glass or a pin shows up as these numbers
    disagreeing with the ones in those two files.
    """
    def bounds(elements):
        lo = [min(e["from"][a] for e in elements) for a in range(3)]
        hi = [max(e["to"][a] for e in elements) for a in range(3)]
        return [round(v, 4) for v in lo + hi]

    print(f"{form}:")
    print(f"  screen  {bounds(screen)}  ({len(screen)} element(s))")
    print(f"  body    {bounds(body)}  ({len(body)} element(s))")
    for element in model["elements"]:
        name = element.get("name")
        if name:
            print(f"  pin {name:<14} {[round(v, 4) for v in element['from'] + element['to']]}")


def main():
    for form, source in SOURCES.items():
        model = load(source)
        for key in STRIPPED:
            model.pop(key, None)
        texture = screen_texture(model)

        screen, body = split(form, source, model, texture)
        write_stub(form, texture)
        write_blockstates(form)
        write_item_models(form, source, model, texture)
        report(form, model, screen, body)
        print(f"  wrote screen/body/stub, {len(RATINGS)} block states, "
              f"1 item model + {len(RATINGS)} parenting to it")


if __name__ == "__main__":
    main()
