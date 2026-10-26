"""Synthesize Blockbench exports for the alphanumeric display sizes nobody has drawn.

`tools/gen_display.py` builds everything the game loads from one export per display,
and those exports are hand-authored. This writes stand-ins for the sizes that have no
authored export yet, in the same frame and with the same element names, so
`gen_display.py` treats them exactly like the real thing. Drop a hand-authored export
over any of them and this script leaves it alone from then on: it only ever rewrites
a file whose `credit` says this script made it.

<h2>What is copied and what is drawn</h2>

The barrel and the eight pins never change with the glass, so they are copied
verbatim from the authored 3x5 export of the same shape, as are the texture slots.
Only the glass, the bezel and the light shade are drawn here, and their face uvs are
copied unchanged: each samples a solid-coloured patch of `display-a`, so stretching a
box does not need its uv touched.

* glass: the element named `screen`, `W` along x by `H` along z, centred on the cell,
  0.15 proud.
* bezel (bordered and both shades): four 0.25 strips round the glass, laid out the
  way the authored 1x2 exports are -- the long strips span the glass only, the side
  strips span the full outer depth, so nothing overlaps.
* light shade: the top strip (low z) raised to `T`, and two cheeks standing on the
  side strips from the outer edge to `C`. `T` and `C` follow the rule every authored
  shade fits:
    - short: T = max(0.5, H/4), C = 8
    - long:  T = max(0.75, H/2), C = 8 + max(0.25, H/4 rounded down to 0.25)

Sizes are named HEIGHTxWIDTH, as the authored ones are: `2x3` is 2 px along z and
3 px along x.

Run from the repo root, then run `tools/gen_display.py`:

    python tools/gen_display_sizes.py
"""

import copy
import json
import math
import os

MODELS = os.path.join("src", "main", "resources", "assets", "create_flippies", "models", "block")

MIDDLE = 8.0
BEZEL = 0.25
PROUD = 0.15

# Marks a file as this script's, and so safe to rewrite. Blockbench's own credit is
# "Made with Blockbench", so a hand re-export always replaces it.
CREDIT = "Synthesized by tools/gen_display_sizes.py; replace with a Blockbench export to override"

# The shapes, by the prefix their exports are named with.
SHAPES = ("brdrl", "brd", "snc1", "snc2")

# The sizes to synthesize, HEIGHTxWIDTH in pixels, and the shapes each is missing.
SIZES = {
    "1x2": ("snc2",),
    "2x3": SHAPES,
    "3x4": SHAPES,
    "4x5": SHAPES,
    "4x6": SHAPES,
    "5x8": SHAPES,
    "4x10": SHAPES,
    "6x10": SHAPES,
    "8x12": SHAPES,
    "8x15": SHAPES,
}

# The authored export each shape's barrel, pins and texture list are copied from.
TEMPLATE_SIZE = "3x5"

# Solid-colour patches of display-a, as the authored exports sample them.
GLASS_FACES = {
    "north": {"uv": [12, 4, 15, 4.3125], "texture": "#2"},
    "east": {"uv": [12, 6, 15, 6.3125], "texture": "#2"},
    "south": {"uv": [8, 12, 11, 12.3125], "texture": "#2"},
    "west": {"uv": [12, 8, 15, 8.3125], "texture": "#2"},
    "up": {"uv": [7, 11, 4, 8], "texture": "#2"},
    "down": {"uv": [11, 8, 8, 11], "texture": "#2"},
}
LONG = [14.5, 15.75, 16, 16]
SMALL = [15.75, 15.75, 16, 16]
ALONG_X_FACES = {
    "north": {"uv": LONG, "texture": "#2"},
    "east": {"uv": SMALL, "texture": "#2"},
    "south": {"uv": LONG, "texture": "#2"},
    "west": {"uv": SMALL, "texture": "#2"},
    "up": {"uv": [16, 16, 14.5, 15.75], "texture": "#2"},
    "down": {"uv": [16, 15.75, 14.5, 16], "texture": "#2"},
}
ALONG_Z_FACES = {
    "north": {"uv": SMALL, "texture": "#2"},
    "east": {"uv": LONG, "texture": "#2"},
    "south": {"uv": SMALL, "texture": "#2"},
    "west": {"uv": LONG, "texture": "#2"},
    "up": {"uv": [16, 16, 14.5, 15.75], "rotation": 270, "texture": "#2"},
    "down": {"uv": [16, 15.75, 14.5, 16], "rotation": 90, "texture": "#2"},
}


def parse_size(size):
    height, width = size.split("x")
    return float(height.replace("p", ".")), float(width.replace("p", "."))


def shade(shape, height):
    """The shade's top `T` and how far its cheeks reach `C`; see the module comment."""
    if shape == "snc1":
        return max(0.5, height / 4), MIDDLE
    return max(0.75, height / 2), MIDDLE + max(0.25, math.floor(height / 4 / 0.25) * 0.25)


def box(name, lo, hi, faces):
    element = {"from": lo, "to": hi,
               "rotation": {"angle": 0, "axis": "y", "origin": list(lo)},
               "faces": copy.deepcopy(faces)}
    if name:
        element = {"name": name, **element}
    return element


def front_elements(shape, height, width):
    x0, x1 = MIDDLE - width / 2, MIDDLE + width / 2
    z0, z1 = MIDDLE - height / 2, MIDDLE + height / 2
    b = BEZEL
    elements = [box("screen", [x0, 0, z0], [x1, PROUD, z1], GLASS_FACES)]
    if shape == "brdrl":
        return elements

    top = b
    if shape in ("snc1", "snc2"):
        top, reach = shade(shape, height)
    elements.append(box("border", [x0, 0, z0 - b], [x1, top, z0], ALONG_X_FACES))
    elements.append(box("border", [x0, 0, z1], [x1, b, z1 + b], ALONG_X_FACES))
    elements.append(box("border", [x1, 0, z0 - b], [x1 + b, b, z1 + b], ALONG_Z_FACES))
    if shape in ("snc1", "snc2"):
        elements.append(box("border", [x1, b, z0 - b], [x1 + b, top, reach], ALONG_Z_FACES))
        elements.append(box("border", [x0 - b, b, z0 - b], [x0, top, reach], ALONG_Z_FACES))
    elements.append(box("border", [x0 - b, 0, z0 - b], [x0, b, z1 + b], ALONG_Z_FACES))
    return elements


def load(name):
    with open(os.path.join(MODELS, name + ".json"), encoding="utf-8") as f:
        return json.load(f)


def owned(path):
    """Whether the file at `path` is missing or was written by this script."""
    if not os.path.exists(path):
        return True
    with open(path, encoding="utf-8") as f:
        return json.load(f).get("credit") == CREDIT


def main():
    for size, shapes in SIZES.items():
        height, width = parse_size(size)
        for shape in shapes:
            name = f"oled-display-{shape}-{size}"
            path = os.path.join(MODELS, name + ".json")
            if not owned(path):
                print(f"{name}: authored, left alone")
                continue
            template = load(f"oled-display-{shape}-{TEMPLATE_SIZE}")
            behind = [e for e in template["elements"]
                      if e.get("name") not in ("screen", "border")]
            assert len(behind) == 9, f"{shape}: expected a barrel and eight pins in the template"

            front = front_elements(shape, height, width)
            lo = [min(e["from"][a] for e in front) for a in range(3)]
            hi = [max(e["to"][a] for e in front) for a in range(3)]
            assert all(0 <= v <= 16 for v in lo + hi), f"{name}: front {lo}..{hi} leaves the cell"

            export = {
                "format_version": template.get("format_version", "1.9.0"),
                "credit": CREDIT,
                "texture_size": template.get("texture_size", [1, 1]),
                "textures": template["textures"],
                "elements": front + behind,
            }
            with open(path, "w", encoding="utf-8", newline="\n") as f:
                f.write(json.dumps(export, indent=2, ensure_ascii=False) + "\n")
            print(f"{name}: front {lo + hi}")


if __name__ == "__main__":
    main()
