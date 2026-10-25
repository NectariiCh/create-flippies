"""Write the alarm speakers' assets from the authored Blockbench export.

The export is `models/block/speaker-1-alarm.json` and is never written. Everything
the game loads is struck from it here, so a re-export is wired up by re-running this.

* `speaker_1_alarm_body` -- the whole export as one partial. Nothing on a speaker
  moves or lights, so unlike a display there is nothing to split off. The export's
  `parent` points at itself and is dropped.
* `speaker_1_alarm` -- a particle-only stub for the block states. A speaker is drawn
  from the partial by a block entity renderer, for the displays' reason: a block
  state variant reaches only 16 of the 24 orientations it can be placed in.
* 15 block states, one per input rating x supply rating, all pointing at the stub.
* one item model, recentred on the block, and 15 item models parenting to it.
* 15 loot tables, each dropping its own block.

It prints the boxes it finds so `SpeakerPins` can be checked against a re-export.

Run from the repo root:

    python tools/gen_speaker.py
"""

import json
import os

ASSETS = os.path.join("src", "main", "resources", "assets", "create_flippies")
DATA = os.path.join("src", "main", "resources", "data", "create_flippies")
MODELS = os.path.join(ASSETS, "models", "block")
ITEM_MODELS = os.path.join(ASSETS, "models", "item")
BLOCKSTATES = os.path.join(ASSETS, "blockstates")
LOOT = os.path.join(DATA, "loot_tables", "blocks")

SOURCE = "speaker-1-alarm"
NAME = "speaker_1_alarm"

# These mirror SpeakerInput and SpeakerSupply, in SpeakerKind's order.
INPUTS = ("5v", "12v", "24v")
SUPPLIES = ("12v", "24v", "48v", "120v", "240v")

FACINGS = ("down", "up", "north", "south", "west", "east")
MIDDLE = 8.0

# The speaker is most of a block tall already, so it is drawn as an item at its own
# size, only recentred. The rotary switches' held transforms.
ITEM_SCALE = 1.0
ITEM_DISPLAY = {
    "thirdperson_righthand": {"rotation": [75, 45, 0], "translation": [0, 2.5, 0], "scale": [0.375, 0.375, 0.375]},
    "thirdperson_lefthand": {"rotation": [75, 45, 0], "translation": [0, 2.5, 0], "scale": [0.375, 0.375, 0.375]},
    "firstperson_righthand": {"rotation": [0, 45, 0], "translation": [0, 0, 0], "scale": [0.4, 0.4, 0.4]},
    "firstperson_lefthand": {"rotation": [0, 225, 0], "translation": [0, 0, 0], "scale": [0.4, 0.4, 0.4]},
    "ground": {"rotation": [0, 0, 0], "translation": [0, 3, 0], "scale": [0.25, 0.25, 0.25]},
    "gui": {"rotation": [30, 225, 0], "translation": [0, 0, 0], "scale": [0.8, 0.8, 0.8]},
    "head": {"rotation": [0, 180, 0], "translation": [0, 13, 7], "scale": [1, 1, 1]},
    "fixed": {"rotation": [0, 180, 0], "translation": [0, 0, 0], "scale": [0.5, 0.5, 0.5]},
}

STRIPPED = ("format_version", "credit", "texture_size", "parent", "groups")


def write_json(path, value):
    with open(path, "w", encoding="utf-8", newline="\n") as f:
        f.write(json.dumps(value, indent=2, ensure_ascii=False) + "\n")


def block_ids():
    return [f"{NAME}_in{i}_{s}" for i in INPUTS for s in SUPPLIES]


def head(model):
    textures = {k: v for k, v in model["textures"].items() if k != "particle"}
    textures["particle"] = model["textures"].get("particle", textures.get("0"))
    return {"render_type": "minecraft:cutout", "textures": textures}


def scaled(value, shift, axis):
    return round(MIDDLE + (value + shift[axis] - MIDDLE) * ITEM_SCALE, 6)


def item_model(model):
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
    item = head(model)
    item["__generated"] = f"block/{SOURCE} recentred on the block middle; re-run tools/gen_speaker.py"
    item["elements"] = elements
    item["display"] = ITEM_DISPLAY
    return item


def loot_table(block):
    return {
        "type": "minecraft:block",
        "pools": [{
            "rolls": 1,
            "entries": [{"type": "minecraft:item", "name": f"create_flippies:{block}"}],
            "conditions": [{"condition": "minecraft:survives_explosion"}],
        }],
    }


def report(model):
    def bounds(elements):
        lo = [min(e["from"][a] for e in elements) for a in range(3)]
        hi = [max(e["to"][a] for e in elements) for a in range(3)]
        return [round(v, 4) for v in lo + hi]

    front = [e for e in model["elements"] if max(e["from"][1], e["to"][1]) > 0]
    back = [e for e in model["elements"] if not e.get("name") and max(e["from"][1], e["to"][1]) <= 0]
    print(f"{NAME}:")
    print(f"  front   {bounds(front)}")
    print(f"  barrel  {bounds(back)}")
    pins = sorted((e for e in model["elements"] if e.get("name")),
                  key=lambda e: (e["name"], e["from"][1]))
    for element in pins:
        print(f"  pin {element['name']:<5} {[round(v, 4) for v in element['from'] + element['to']]}")


def main():
    with open(os.path.join(MODELS, SOURCE + ".json"), encoding="utf-8") as f:
        model = json.load(f)
    for key in STRIPPED:
        model.pop(key, None)

    body = head(model)
    body["__generated"] = f"tools/gen_speaker.py from {SOURCE}.json; re-run after re-exporting"
    body["elements"] = model["elements"]
    write_json(os.path.join(MODELS, f"{NAME}_body.json"), body)
    write_json(os.path.join(MODELS, f"{NAME}.json"), {"textures": {"particle": body["textures"]["particle"]}})

    write_json(os.path.join(ITEM_MODELS, f"{NAME}.json"), item_model(model))
    os.makedirs(LOOT, exist_ok=True)
    for block in block_ids():
        variants = {f"facing={facing},rotation={rotation}": {"model": f"create_flippies:block/{NAME}"}
                    for facing in FACINGS for rotation in range(4)}
        write_json(os.path.join(BLOCKSTATES, f"{block}.json"), {"variants": variants})
        write_json(os.path.join(ITEM_MODELS, f"{block}.json"), {"parent": f"create_flippies:item/{NAME}"})
        write_json(os.path.join(LOOT, f"{block}.json"), loot_table(block))

    report(model)
    print(f"  wrote body/stub, {len(block_ids())} block states, item models and loot tables")


if __name__ == "__main__":
    main()
