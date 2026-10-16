"""Write the indicators' assets: a texture per colour and state, a model per size,
colour and state, plus the block states, the particle stubs and the item models.

Every indicator is the same moulded body with a different cap in it, and the
authored texture says so: `indicator-med-red.png` is three colours -- the grey body,
the transparent background, and one flat fill for the lens. So a colour variant is
that fill swapped, and a lit variant is the same hue turned up, which is what this
does rather than asking for twenty-eight hand-painted files that differ by 64 pixels.

The unlit lenses keep the red's saturation and value exactly, so a dark lens reads
as dark glass whatever colour it is; the lit ones go to full value and give up a
little saturation, which is what a coloured lens does when it is shining. Black and
grey are the exceptions and have levels of their own: they are not lenses at all but
the caps an unilluminated pushbutton wears, so they sit far below the lit colours and
lighten only a little when something is driving them.

Nothing here writes the authored red models -- `indicator-med-red.json` and
`indicator-large-red.json` are the inputs, and they are the two files to open in
Blockbench. What it does instead is recompute red's unlit lens and check it against
the pixels really in the file, so a drift between the numbers below and the art is a
failure and not a silent wrong colour in the other twenty-six.

<h2>Two sizes, one set of textures</h2>

The large unit differs from the medium one in its cap and in nothing else: its other
nine elements are identical, and its cap samples exactly the same texels, so the two
sizes share every texture. The textures are therefore named after the file that was
authored -- `indicator-med-<colour>` -- and a large model points at them. Naming them
for neither size would have meant renaming the authored red, which is the one file
this script must not touch.

Models are edited as text rather than round-tripped through a JSON parser, so the
generated ones keep Blockbench's own formatting and diff against the authored file in
the two lines that actually differ.

Each whole unit is then split into the two models the game actually draws: a body
and a cap. That split is what makes a lit lamp glow in the dark without lighting
anything up -- the renderer draws the body at whatever light is falling on the panel
and the cap at full bright -- and it is also what lets a pressed cap sink into the
panel while the body behind it stays put. It has to be a split rather than Forge's
per-face emissive data, because a unit is drawn through a SuperByteBuffer and
`light()` rewrites the lightmap of every vertex it is given, baked-in face data
included.

The whole-unit models are kept: the two authored ones are what to open in Blockbench,
and everything else is struck from them. Which element is the cap is not guessed --
it is the one whose faces sample the lens fill and nothing else, and the script
asserts that exactly one element in each size does.

Repaint any of them freely -- nothing here is load-bearing beyond the file names.

Run from the repo root:

    python tools/gen_indicator_variants.py
"""

import colorsys
import json
import os

from PIL import Image

ASSETS = os.path.join("src", "main", "resources", "assets", "create_flippies")
MODELS = os.path.join(ASSETS, "models", "block")
TEXTURES = os.path.join(ASSETS, "textures", "block")

# The authored texture everything else is struck from. Never written.
SOURCE_TEXTURE = "indicator-med-red"

# The two authored models, by size: the geometry each size is generated from. Never
# written either, and both already point at SOURCE_TEXTURE.
SOURCES = {
    "med": "indicator-med-red",
    "large": "indicator-large-red",
}

# The lens fill in the authored texture. Every other pixel is the body or nothing,
# and is copied through untouched.
LENS = (143, 20, 20, 255)

# Saturation and value of a lens with nothing behind it, read off the red unit.
UNLIT = (0.860, 0.561)

# ...and of one that is lit.
LIT = (0.750, 1.0)

# Hue in degrees, or None for a cap with no colour in it at all, and the levels each
# is drawn at when it is dark and when it is lit. The five lenses share the levels
# above; the two button caps are much darker, because a black button that went white
# when its lamp was driven would be a lens after all.
COLOURS = {
    "red": (0.0, UNLIT, LIT),
    "amber": (38.0, UNLIT, LIT),
    "green": (125.0, UNLIT, LIT),
    "blue": (210.0, UNLIT, LIT),
    "white": (None, UNLIT, LIT),
    "black": (None, (0.0, 0.12), (0.0, 0.28)),
    "grey": (None, (0.0, 0.38), (0.0, 0.62)),
}


def lens(hue, levels):
    """The fill for one cap: a hue at the saturation and value of a given state."""
    saturation, value = levels
    if hue is None:
        saturation = 0.0
    r, g, b = colorsys.hsv_to_rgb((hue or 0.0) / 360.0, saturation, value)
    return (round(r * 255), round(g * 255), round(b * 255), 255)


def pixels(image):
    read = getattr(image, "get_flattened_data", None) or image.getdata
    return list(read())


def write_texture(name, fill, source):
    recoloured = Image.new("RGBA", source.size)
    recoloured.putdata([fill if p == LENS else p for p in pixels(source)])
    recoloured.save(os.path.join(TEXTURES, name + ".png"))


def repoint(model, texture):
    """One authored model's text, pointed at another colour's texture."""
    return model.replace(f"create_flippies:block/{SOURCE_TEXTURE}", f"create_flippies:block/{texture}")


def write_model(name, model):
    with open(os.path.join(MODELS, name + ".json"), "w", encoding="utf-8", newline="") as f:
        f.write(model)


# ---------------------------------------------------------------------------
# The body/cap split the renderer draws
# ---------------------------------------------------------------------------

def lens_element(model, texture):
    """Which element is the cap: the one whose faces sample the lens fill.

    Worked out from the art rather than by index, so that adding a bezel or moving
    the cap around in Blockbench cannot silently make the wrong box glow -- or, now,
    make the wrong box sink when the button is pressed. The faces of a model in this
    frame index the texture in whole pixels -- Minecraft maps uv 0..16 across a
    texture that is 16 across -- which is what makes reading them back this cheap.

    The contacts are skipped for free: they sample the copper texture in the model's
    second slot, and only faces on slot #0 are read.
    """
    pixel = texture.load()
    found = None
    for index, element in enumerate(model["elements"]):
        samples = set()
        for face in element["faces"].values():
            if face["texture"] != "#0":
                continue
            u0, v0, u1, v1 = face["uv"]
            for x in range(int(min(u0, u1)), int(max(u0, u1))):
                for y in range(int(min(v0, v1)), int(max(v0, v1))):
                    samples.add(pixel[x, y])
        if LENS in samples:
            assert samples == {LENS}, f"element {index} mixes the lens fill with {samples - {LENS}}"
            assert found is None, f"elements {found} and {index} both draw the cap"
            found = index
    assert found is not None, "no element draws the cap"
    return found


def write_split(name, model, lens_index):
    """The unit as the two models the renderer draws: everything else, then the cap.

    Both keep the whole model's texture list. A slot the remaining elements do not
    use costs nothing -- an unreferenced texture is simply not stitched -- and
    keeping the lists identical means the split models can be diffed against the
    whole one line for line.
    """
    for suffix, keep in (("body", lambda i: i != lens_index), ("lens", lambda i: i == lens_index)):
        part = dict(model)
        part["elements"] = [e for i, e in enumerate(model["elements"]) if keep(i)]
        write_json(os.path.join(MODELS, f"{name}-{suffix}.json"), part)


# ---------------------------------------------------------------------------
# Blocks, block states and item models
# ---------------------------------------------------------------------------

BLOCKSTATES = os.path.join(ASSETS, "blockstates")
ITEM_MODELS = os.path.join(ASSETS, "models", "item")

# One block per cap size and rated voltage. Nothing else about a unit is among them
# -- the cap colour, whether it clicks and what is printed on it are per-unit state a
# screwdriver sets -- so all five ratings of a size share every model here.
RATINGS = ("6v", "12v", "24v", "125v", "230v")

# The stub the block states point at, one per size. A unit is drawn entirely from
# partials by its block entity renderer, so a real model here would draw a second
# indicator inside the first; all the block state is needed for is the particle a
# broken unit throws and the texture a breaking one is stippled with.
def block_stub(size):
    return f"indicator_{size}"


MIDDLE = 8.0

# How much bigger a unit is drawn as an item than it is on a wall.
#
# The medium cap is one pixel across, which as an item icon is a speck: a display
# transform cannot rescue it, because vanilla clamps display scale at 4 and even that
# leaves it smaller than the border of the slot it sits in. Blowing the geometry up
# about the middle of the cube instead is what item models for very small parts have
# always done, and it costs nothing -- the model is only ever a picture of the unit,
# never the unit itself. Both sizes take the same factor, so the large one still looks
# larger in the hand, which is the whole difference between them.
ITEM_SCALE = 3.0

# How a unit is held. The same transforms the rotary switches use, which are authored
# for a part standing off a panel rather than for a flat item.
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

FACINGS = ("down", "up", "north", "south", "west", "east")


def write_json(path, value):
    with open(path, "w", encoding="utf-8", newline="\n") as f:
        f.write(json.dumps(value, indent=2, ensure_ascii=False) + "\n")


def write_blockstates():
    """One file per size and rating, every orientation pointing at that size's stub.

    All 24 combinations are listed rather than left to a default, because the
    renderer reaches orientations a variant's x/y rotations cannot express and the
    block state exists only to be a valid one, not to aim anything.
    """
    for size in SOURCES:
        for rating in RATINGS:
            variants = {f"facing={facing},rotation={rotation}":
                        {"model": f"create_flippies:block/{block_stub(size)}"}
                        for facing in FACINGS for rotation in range(4)}
            write_json(os.path.join(BLOCKSTATES, f"indicator_{size}_{rating}.json"), {"variants": variants})
    print(f"blockstates: {len(SOURCES)} sizes x {len(RATINGS)} ratings")


def write_block_stubs():
    for size in SOURCES:
        write_json(os.path.join(MODELS, block_stub(size) + ".json"),
                   {"textures": {"particle": f"create_flippies:block/{SOURCE_TEXTURE}"}})
    print(f"block stubs: {', '.join(block_stub(size) for size in SOURCES)} (particle only)")


def scaled(value, shift, axis):
    """One authored coordinate, recentred on the cube and then blown up about it."""
    return round(MIDDLE + (value + shift[axis] - MIDDLE) * ITEM_SCALE, 6)


def write_item_models():
    for size, source in SOURCES.items():
        with open(os.path.join(MODELS, source + ".json"), encoding="utf-8") as f:
            unit = json.load(f)

        elements = [dict(e) for e in unit["elements"]]
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

        item = f"indicator_{size}"
        write_json(os.path.join(ITEM_MODELS, item + ".json"), {
            "render_type": "minecraft:cutout",
            "__generated": (f"block/{source} recentred on the block middle and scaled "
                            f"{ITEM_SCALE:g}x; regenerate if the model changes"),
            "textures": unit["textures"],
            "elements": elements,
            "display": ITEM_DISPLAY,
        })
        for rating in RATINGS:
            write_json(os.path.join(ITEM_MODELS, f"indicator_{size}_{rating}.json"),
                       {"parent": f"create_flippies:item/{item}"})
        print(f"item/{item}: {len(elements)} elements, +{len(RATINGS)} items parenting to it")


def main():
    texture = Image.open(os.path.join(TEXTURES, SOURCE_TEXTURE + ".png")).convert("RGBA")
    drawn = set(pixels(texture))
    assert LENS in drawn, f"{SOURCE_TEXTURE}.png does not use the lens colour {LENS} this works from"
    assert lens(COLOURS["red"][0], UNLIT) == LENS, "UNLIT no longer reproduces the authored red lens"

    # The textures first, once for both sizes: the art is the same and only the cap
    # box differs between them
    for colour, (hue, unlit, lit) in COLOURS.items():
        for suffix, levels in (("", unlit), ("-lit", lit)):
            name = f"indicator-med-{colour}{suffix}"
            if name == SOURCE_TEXTURE:
                print(f"{name}.png: authored, left alone")
                continue
            fill = lens(hue, levels)
            write_texture(name, fill, texture)
            print(f"{name}.png: cap #{fill[0]:02X}{fill[1]:02X}{fill[2]:02X}")

    for size, source in SOURCES.items():
        with open(os.path.join(MODELS, source + ".json"), encoding="utf-8", newline="") as f:
            model = f.read()
        lens_index = lens_element(json.loads(model), texture)

        for colour in COLOURS:
            for suffix in ("", "-lit"):
                name = f"indicator-{size}-{colour}{suffix}"
                part = repoint(model, f"indicator-med-{colour}{suffix}")
                if name != source:
                    write_model(name, part)
                # Only the cap differs between a unit's two states, so the body is
                # written once per size and colour and the lit one is a cap on its own
                whole = json.loads(part)
                if suffix == "":
                    write_split(name, whole, lens_index)
                else:
                    lit_cap = dict(whole)
                    lit_cap["elements"] = [whole["elements"][lens_index]]
                    write_json(os.path.join(MODELS, f"{name}-lens.json"), lit_cap)
        print(f"{size}: {len(COLOURS)} colours x 2 states, element {lens_index} is the cap")

    write_block_stubs()
    write_blockstates()
    write_item_models()


if __name__ == "__main__":
    main()
