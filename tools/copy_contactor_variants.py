"""Fills the contactor matrix from the two authored exports.

The user authors two models by hand:

* `contactor-1m-spdt-5v.json` -- the one-module, single-pole contactor;
* `contactor-2m-dpdt-5v.json` -- the two-module one. Its M1 group is the 1M
  module, coil included, and its M2 group is a second pole with no coil and its
  own UV mapping.

Every wider contactor is the 2M one with the M2 group repeated one module
further along x for each extra pole, keeping M2's UVs; its contacts are renamed
NO<k>/C<k>/NC<k>. Every coil voltage of a size is that size's 5 V model pointed
at that voltage's texture, `contactor-1m-spdt-<v>v.png`: textures differ by coil
voltage only, never by size. A missing voltage texture is copied from the 5 V
one, for the user to repaint; an existing one is left alone.

The 1M and 2M 5 V exports are never written. Every other `contactor-*.json` is
overwritten, so edit the authored pair and re-run this, then gen_contactors.py.

    python tools/copy_contactor_variants.py

The same matrix is `ContactorType`'s table; change both together.
"""

import copy
import json
import pathlib
import re
import shutil

ROOT = pathlib.Path(__file__).resolve().parent.parent / "src" / "main" / "resources"
BLOCK_MODELS = ROOT / "assets" / "create_flippies" / "models" / "block"
TEXTURES = ROOT / "assets" / "create_flippies" / "textures" / "block"

# Modules -> pole suffix, as the exports are named.
SIZES = {1: "spdt", 2: "dpdt", 3: "tpdt", 4: "4pdt", 6: "6pdt", 8: "8pdt"}
COILS = (5, 12, 24, 48, 125, 250)

POLE_NAMES = ("NO2", "C2", "NC2")


def export_name(modules, coil):
    return "contactor-%dm-%s-%dv" % (modules, SIZES[modules], coil)


def texture_name(coil):
    return "contactor-1m-spdt-%dv" % coil


def fmt(data):
    """Blockbench's layout: tabs, and numeric arrays and small objects on one line."""
    s = json.dumps(data, indent="\t")
    s = re.sub(r"\[\s*([-\d.,\s]+?)\s*\]", lambda m: "[" + ", ".join(x.strip() for x in m.group(1).split(",")) + "]", s)
    s = re.sub(r'\{\s*("uv": \[[^\]]*\]),\s*("texture": "[^"]*")\s*\}', r"{\1, \2}", s)
    s = re.sub(r'\{\s*("angle": [^,]+),\s*("axis": "[^"]*"),\s*("origin": \[[^\]]*\])\s*\}', r"{\1, \2, \3}", s)
    return s + "\n"


def retexture(text, coil):
    return text.replace("create_flippies:block/" + texture_name(5), "create_flippies:block/" + texture_name(coil))


def widen(two, modules):
    """The 2M model with M2 repeated out to this many modules."""
    elements = two["elements"]
    groups = {g["name"]: g for g in two["groups"]}
    m2 = [elements[i] for i in groups["M2"]["children"]]
    data = copy.deepcopy(two)
    for k in range(3, modules + 1):
        start = len(data["elements"])
        for e in m2:
            e = copy.deepcopy(e)
            for key in ("from", "to"):
                e[key][0] += k - 2
            if "rotation" in e and "origin" in e["rotation"]:
                e["rotation"]["origin"][0] += k - 2
            if e["name"] in POLE_NAMES:
                e["name"] = e["name"][:-1] + str(k)
            data["elements"].append(e)
        data["groups"].append({"name": "M%d" % k, "origin": [8, 8, 8], "scope": 0, "color": (k - 1) % 8,
                               "children": list(range(start, len(data["elements"])))})
    return data


def main():
    one_text = (BLOCK_MODELS / (export_name(1, 5) + ".json")).read_text(encoding="utf-8")
    two_text = (BLOCK_MODELS / (export_name(2, 5) + ".json")).read_text(encoding="utf-8")
    two = json.loads(two_text)
    base_png = TEXTURES / (texture_name(5) + ".png")
    for coil in COILS:
        png = TEXTURES / (texture_name(coil) + ".png")
        if not png.exists():
            shutil.copyfile(base_png, png)
            print("copied %s" % png.name)
        for modules in SIZES:
            if modules <= 2 and coil == 5:
                continue
            if modules == 1:
                text = retexture(one_text, coil)
            elif modules == 2:
                text = retexture(two_text, coil)
            else:
                text = retexture(fmt(widen(two, modules)), coil)
            (BLOCK_MODELS / (export_name(modules, coil) + ".json")).write_text(text, encoding="utf-8", newline="\n")
            print("wrote %s.json" % export_name(modules, coil))


if __name__ == "__main__":
    main()
