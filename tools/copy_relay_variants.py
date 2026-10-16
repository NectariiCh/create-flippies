"""Copies the plug-in relay's texture and models for every relay variant.

The family, as the user specified it on 2026-10-14:

* kind: ss (solid state) or em (electromechanical);
* switching voltage: 24, 48, 120, 230 V (12 V dropped by the user on 2026-10-15);
* coil voltage: 5, 12, 24, 48 V on any of them, plus a 120 V coil only on a
  120 V relay and a 230 V coil only on a 230 V relay;
* current: 1, 2, 5, 10, 16, 20, 24, 32 A, up to a cap per kind and voltage --
  ss 120/230 V 2 A, ss 48 V 10 A, ss 24 V 20 A, em 120/230 V 5 A, em 48 V 24 A.
  The rest (em 24 V) were given no cap and take every rating.

Each variant is named `<kind>_relay_c<coil>v_<switching>v_<amps>a`, e.g.
`ss_relay_c24v_230v_10a`, and gets:

* `textures/block/<id>.png` -- a byte copy of `ss_relay_230v10a.png`. An existing
  file is never overwritten: these are for the user to paint, and re-running this
  must not undo that;
* `models/block/<id>.json` and `models/item/<id>.json` -- the relay's generated
  models (gen_relay.py's `ss_relay_230v_10a`) pointed at that texture. These are
  regenerated every run, so re-run gen_relay.py first after re-exporting the mount.

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

SWITCHING = (24, 48, 120, 230)
AMPS = (1, 2, 5, 10, 16, 20, 24, 32)
CAP = {
    "ss": {24: 20, 48: 10, 120: 2, 230: 2},
    "em": {24: 32, 48: 24, 120: 5, 230: 5},
}


def coils(switching):
    return (5, 12, 24, 48) + ((switching,) if switching in (120, 230) else ())


def variants():
    for kind, caps in CAP.items():
        for volts in SWITCHING:
            for coil in coils(volts):
                for amps in AMPS:
                    if amps <= caps[volts]:
                        yield "%s_relay_c%dv_%dv_%da" % (kind, coil, volts, amps)


def retextured(model, texture):
    model = json.loads(json.dumps(model))
    model["__generated"] = "tools/copy_relay_variants.py from %s; textures are hand-painted" % SOURCE_MODEL
    model["textures"] = {SLOT: texture, "particle": texture}
    return model


def write_json(path, data):
    path.write_text(json.dumps(data, indent=2) + "\n", encoding="utf-8", newline="\n")


def main():
    block = json.loads((BLOCK_MODELS / (SOURCE_MODEL + ".json")).read_text(encoding="utf-8"))
    item = json.loads((ITEM_MODELS / (SOURCE_MODEL + ".json")).read_text(encoding="utf-8"))
    copied = kept = 0
    ids = list(variants())
    for id in ids:
        texture = TEXTURES / (id + ".png")
        if texture.exists():
            kept += 1
        else:
            shutil.copyfile(SOURCE_TEXTURE, texture)
            copied += 1
        reference = "create_flippies:block/" + id
        write_json(BLOCK_MODELS / (id + ".json"), retextured(block, reference))
        write_json(ITEM_MODELS / (id + ".json"), retextured(item, reference))
    for kind in CAP:
        print("%s: %d variants" % (kind, sum(1 for i in ids if i.startswith(kind + "_"))))
    print("%d total; textures copied %d, already there %d; models written %d"
          % (len(ids), copied, kept, 2 * len(ids)))


if __name__ == "__main__":
    main()
