"""Copies the plug-in relays' textures and models for every relay variant.

The family, as the user specified it on 2026-10-14:

* kind: ss (solid state) or em (electromechanical);
* switching voltage: 24, 48, 120, 230 V (12 V dropped by the user on 2026-10-15);
* coil voltage: 5, 12, 24, 48 V on any of them, plus a 120 V coil only on a
  120 V relay and a 230 V coil only on a 230 V relay;
* current: 1, 2, 5, 10, 16, 20, 24, 32 A, up to a cap per kind and voltage --
  ss 120/230 V 2 A, ss 48 V 10 A, ss 24 V 20 A, em 120/230 V 5 A, em 48 V 24 A.
  The rest (em 24 V) were given no cap and take every rating.

Each variant is named `<kind>_relay_c<coil>v_<switching>v_<amps>a`, e.g.
`ss_relay_c24v_230v_10a`.

The 8-pin family (added 2026-10-29) is the DPDT plug-in for the 8-pin socket,
electromechanical only: every coil / switching / current combination the em
5-pin relays have, with higher caps -- 16 A at 120/230 V, 32 A at 24 and 48 V.
Each is named `em_relay_8pin_c<coil>v_<switching>v_<amps>a`; the first 8-pin
relay, `em_relay_8pin_c24v_230v_16a`, is one of them and is also the family's
source model.

Every variant gets:

* `textures/block/<id>.png` -- a byte copy of its family's authored texture
  (`ss_relay_230v10a.png`, `relay_8pin_230v16a_24cv.png`). An existing file is
  never overwritten; gen_relay_textures.py paints the labels over them;
* `models/block/<id>.json` and `models/item/<id>.json` -- the family's generated
  models (gen_relay.py's `ss_relay_230v_10a`, `em_relay_8pin_c24v_230v_16a`)
  pointed at that texture. These are regenerated every run, so re-run gen_relay.py
  first after re-exporting a mount.

Models and textures only: nothing here registers an item or a RelayType row.

    python tools/copy_relay_variants.py
"""

import json
import pathlib
import shutil

ASSETS = pathlib.Path(__file__).resolve().parent.parent / "src" / "main" / "resources" / "assets" / "create_flippies"
TEXTURES = ASSETS / "textures" / "block"
BLOCK_MODELS = ASSETS / "models" / "block"
ITEM_MODELS = ASSETS / "models" / "item"

SOURCE_TEXTURE = TEXTURES / "ss_relay_230v10a.png"
SOURCE_MODEL = "ss_relay_230v_10a"
SLOT = "2"

EIGHT_PIN_TEXTURE = TEXTURES / "relay_8pin_230v16a_24cv.png"
EIGHT_PIN_MODEL = "em_relay_8pin_c24v_230v_16a"
EIGHT_PIN_SLOT = "3"

SWITCHING = (24, 48, 120, 230)
AMPS = (1, 2, 5, 10, 16, 20, 24, 32)
CAP = {
    "ss": {24: 20, 48: 10, 120: 2, 230: 2},
    "em": {24: 32, 48: 24, 120: 5, 230: 5},
}
EIGHT_PIN_CAP = {24: 32, 48: 32, 120: 16, 230: 16}


def coils(switching):
    return (5, 12, 24, 48) + ((switching,) if switching in (120, 230) else ())


def variants():
    for kind, caps in CAP.items():
        for volts in SWITCHING:
            for coil in coils(volts):
                for amps in AMPS:
                    if amps <= caps[volts]:
                        yield "%s_relay_c%dv_%dv_%da" % (kind, coil, volts, amps)


def eight_pin_variants():
    for volts in SWITCHING:
        for coil in coils(volts):
            for amps in AMPS:
                if amps <= EIGHT_PIN_CAP[volts]:
                    yield "em_relay_8pin_c%dv_%dv_%da" % (coil, volts, amps)


# Each family: its ids, the texture copied for them, the model they are drawn
# with, and the texture slot that model's faces use.
FAMILIES = (
    ("5-pin", variants, SOURCE_TEXTURE, SOURCE_MODEL, SLOT),
    ("8-pin", eight_pin_variants, EIGHT_PIN_TEXTURE, EIGHT_PIN_MODEL, EIGHT_PIN_SLOT),
)


def retextured(model, source, slot, texture):
    model = json.loads(json.dumps(model))
    model["__generated"] = "tools/copy_relay_variants.py from %s; textures are hand-painted" % source
    model["textures"] = {**model["textures"], slot: texture, "particle": texture}
    return model


def write_json(path, data):
    path.write_text(json.dumps(data, indent=2) + "\n", encoding="utf-8", newline="\n")


def main():
    for name, ids, source_texture, source, slot in FAMILIES:
        # Read before writing: the 8-pin source model is also one of its variants.
        block = json.loads((BLOCK_MODELS / (source + ".json")).read_text(encoding="utf-8"))
        item = json.loads((ITEM_MODELS / (source + ".json")).read_text(encoding="utf-8"))
        copied = kept = 0
        ids = list(ids())
        for id in ids:
            texture = TEXTURES / (id + ".png")
            if texture.exists():
                kept += 1
            else:
                shutil.copyfile(source_texture, texture)
                copied += 1
            reference = "create_flippies:block/" + id
            write_json(BLOCK_MODELS / (id + ".json"), retextured(block, source, slot, reference))
            write_json(ITEM_MODELS / (id + ".json"), retextured(item, source, slot, reference))
        if name == "5-pin":
            for kind in CAP:
                print("%s: %d variants" % (kind, sum(1 for i in ids if i.startswith(kind + "_"))))
        print("%s: %d total; textures copied %d, already there %d; models written %d"
              % (name, len(ids), copied, kept, 2 * len(ids)))


if __name__ == "__main__":
    main()
