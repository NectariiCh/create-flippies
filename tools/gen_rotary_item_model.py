"""Build the rotary switches' item models by merging a body and a handle.

A rotary switch has no baked block model: the block entity renderer draws the body
and the handle as separate partials so that it can turn the handle 45 degrees and
aim the whole switch at any of the 24 orientations a block state cannot reach. An
item in a hand or a slot has neither a block entity nor a block state, so the one
place the two halves have to be baked together is the item model, and that is what
this writes.

The merge is mechanical. The two authored models are stated in the frame every
panel-mounted part of this mod uses -- front plate flush with the floor of the
cell, everything else hanging below it -- so the merged switch sits mostly outside
the item cube until it is moved. Recentring its bounding box on the middle of the
block is the whole of the transform.

Run from the repo root:

    python tools/gen_rotary_item_model.py
"""

import json
import os

ASSETS = os.path.join("src", "main", "resources", "assets", "create_flippies")
MODELS = os.path.join(ASSETS, "models")

# The handle model names its own texture "0"; the body has already claimed that
# slot, so the handle's faces are remapped onto a slot of their own as they merge.
HANDLE_TEXTURE = "3"

MIDDLE = 8.0

# How the switch is held, shared by every rotary item. Authored once by hand and
# copied here so that regenerating a model cannot quietly lose it.
DISPLAY = {
    "thirdperson_righthand": {"rotation": [75, 45, 0], "translation": [0, 2.5, 0], "scale": [0.9, 0.9, 0.9]},
    "thirdperson_lefthand": {"rotation": [75, 45, 0], "translation": [0, 2.5, 0], "scale": [0.9, 0.9, 0.9]},
    "firstperson_righthand": {"rotation": [0, 45, 0], "translation": [0, 0, 0], "scale": [0.95, 0.95, 0.95]},
    "firstperson_lefthand": {"rotation": [0, 225, 0], "translation": [0, 0, 0], "scale": [0.95, 0.95, 0.95]},
    "ground": {"rotation": [0, 0, 0], "translation": [0, 3, 0], "scale": [0.6, 0.6, 0.6]},
    "gui": {"rotation": [30, 225, 0], "translation": [0, 0, 0], "scale": [1.5, 1.5, 1.5]},
    "head": {"rotation": [0, 180, 0], "translation": [0, 13, 7], "scale": [1, 1, 1]},
    "fixed": {"rotation": [0, 180, 0], "translation": [0, 0, 0], "scale": [1.2, 1.2, 1.2]},
}

# One per block: the item to write, and the body and handle it is a picture of.
# The body chosen is the front the switch is placed wearing, which is the first
# marking its kind offers.
ITEMS = [
    ("rotary_switch_med_2pos", "rotary-switch-med-a-body-io", "rotary-switch-med-a-handle"),
    ("rotary_switch_med_3pos", "rotary-switch-med-a-body-iioi", "rotary-switch-med-a-handle"),
    ("rotary_switch_large_3pos", "rotary-switch-large-a-body-mnp", "rotary-switch-large-a-handle"),
]


def load(name):
    with open(os.path.join(MODELS, "block", name + ".json"), encoding="utf-8") as f:
        return json.load(f)


def merged_elements(body, handle):
    """The body's boxes, then the handle's with their faces moved off slot 0."""
    elements = [dict(e) for e in body["elements"]]
    for element in handle["elements"]:
        element = dict(element)
        element["faces"] = {side: dict(face, texture="#" + HANDLE_TEXTURE)
                            for side, face in element["faces"].items()}
        elements.append(element)
    return elements


def recentre(elements):
    """Move the whole switch so its bounding box sits on the middle of the cube."""
    lo = [min(e["from"][axis] for e in elements) for axis in range(3)]
    hi = [max(e["to"][axis] for e in elements) for axis in range(3)]
    shift = [MIDDLE - (lo[axis] + hi[axis]) / 2 for axis in range(3)]
    for element in elements:
        for key in ("from", "to"):
            element[key] = [round(element[key][axis] + shift[axis], 6) for axis in range(3)]
        if "rotation" in element:
            rotation = dict(element["rotation"])
            rotation["origin"] = [round(rotation["origin"][axis] + shift[axis], 6) for axis in range(3)]
            element["rotation"] = rotation
    return shift


def build(item, body_name, handle_name):
    body = load(body_name)
    handle = load(handle_name)

    # Numbered slots first and "particle" last, the order Blockbench writes them in
    textures = {slot: path for slot, path in body["textures"].items() if slot != "particle"}
    textures[HANDLE_TEXTURE] = handle["textures"]["0"]
    textures["particle"] = body["textures"]["particle"]

    elements = merged_elements(body, handle)
    recentre(elements)

    model = {
        # Every one of the switch's textures has transparent pixels in it.
        "render_type": "minecraft:cutout",
        "__generated": (f"merge of block/{body_name} and block/{handle_name}, "
                        "recentred on the block middle; regenerate if either changes"),
        "textures": textures,
        "elements": elements,
        "display": DISPLAY,
    }
    path = os.path.join(MODELS, "item", item + ".json")
    with open(path, "w", encoding="utf-8", newline="\n") as f:
        f.write(json.dumps(model, indent=2, ensure_ascii=False) + "\n")
    print(f"{path}: {len(elements)} elements")


def main():
    for item, body, handle in ITEMS:
        build(item, body, handle)


if __name__ == "__main__":
    main()
