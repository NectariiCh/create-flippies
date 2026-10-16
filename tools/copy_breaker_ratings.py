"""Copies the authored breakers into every current rating, so that each can have
its printing edited by hand.

Every rating of a breaker is the same object with a different number on the
front. Rather than author twenty-four of them, one of each pole count is authored
and copied across the ratings here: the model, the body texture and the lever
texture, with the texture references inside each copy repointed at that rating's
own files.

The copies are the new authoring sources — edit the front printing in
CB8-DIN-WO-230V-1M-SP-body.png and so on. Nothing here is generated a second
time, so running it again would overwrite that work; it refuses to touch a
breaker whose files already exist unless --force is passed.

    python tools/copy_breaker_ratings.py            # make what is missing
    python tools/copy_breaker_ratings.py --force    # overwrite, losing edits

Afterwards run gen_circuit_breaker.py, which splits every breaker it finds."""
import json, pathlib, re, shutil, sys

ROOT = pathlib.Path(__file__).resolve().parent.parent / "src" / "main" / "resources"
ASSETS = ROOT / "assets" / "create_flippies"
BLOCK_MODELS = ASSETS / "models" / "block"
TEXTURES = ASSETS / "textures" / "block"

# The rating the others are cut from, and the shape of every breaker's name.
SOURCE_RATING = 16
NAME = "CB%d-DIN-WO-230V-%dM-%s"

# Ratings from the IEC 60898 preferred series, as far as this mod goes. Must match
# CircuitBreakerType.RATINGS.
RATINGS = [8, 10, 16, 20, 24, 32, 40, 48]

# Every pole count, as (modules, suffix). Must match CircuitBreakerPoles.
POLES = [(1, "SP"), (2, "DP"), (3, "TP")]

# The texture suffixes each breaker owns. copper_contacts is shared and stays put.
TEXTURE_PARTS = ["body", "lever"]


def asset_name(rating, modules, poles):
    return NAME % (rating, modules, poles)


def resource_name(stem):
    """The lower case form Minecraft will actually load; see gen_circuit_breaker."""
    return stem.lower().replace("-", "_")


def source_model(modules, poles):
    stem = asset_name(SOURCE_RATING, modules, poles)
    for name in (resource_name(stem), stem):
        path = BLOCK_MODELS / (name + ".json")
        if path.exists():
            return path
    return None


def repoint(model, source_stem, target_stem):
    """Aims a copied model's texture references at its own rating's files.

    Both spellings are substituted because an export names its textures one way
    and the files on disk are named the other, and which one a given export used
    depends on when it was made."""
    patterns = [(re.compile(re.escape(source_stem), re.IGNORECASE), target_stem),
                (re.compile(re.escape(resource_name(source_stem)), re.IGNORECASE),
                 resource_name(target_stem))]
    textures = {}
    for key, value in model.get("textures", {}).items():
        for pattern, replacement in patterns:
            value = pattern.sub(replacement, value)
        textures[key] = value
    model["textures"] = textures
    return model


def main():
    force = "--force" in sys.argv
    made = skipped = 0

    for modules, poles in POLES:
        model_path = source_model(modules, poles)
        if model_path is None:
            print("no CB%d %s model to copy from, skipping that pole count" % (SOURCE_RATING, poles))
            continue
        source_stem = asset_name(SOURCE_RATING, modules, poles)
        model = json.loads(model_path.read_text(encoding="utf-8"))
        print("%s from %s" % (poles, model_path.relative_to(ROOT)))

        for rating in RATINGS:
            if rating == SOURCE_RATING:
                continue
            target_stem = asset_name(rating, modules, poles)
            targets = [BLOCK_MODELS / (target_stem + ".json")]
            targets += [TEXTURES / (resource_name(target_stem) + "_" + part + ".png")
                        for part in TEXTURE_PARTS]

            existing = [t for t in targets if t.exists()]
            if existing and not force:
                print("    CB%-3d %s already there, left alone" % (rating, poles))
                skipped += 1
                continue

            targets[0].write_text(
                json.dumps(repoint(json.loads(json.dumps(model)), source_stem, target_stem), indent=2) + "\n",
                encoding="utf-8")
            for part, target in zip(TEXTURE_PARTS, targets[1:]):
                origin = TEXTURES / (resource_name(source_stem) + "_" + part + ".png")
                if not origin.exists():
                    raise SystemExit("missing texture to copy: %s" % origin)
                shutil.copyfile(origin, target)
            print("    CB%-3d %s -> %s + %d textures"
                  % (rating, poles, targets[0].name, len(TEXTURE_PARTS)))
            made += 1

    print("\n%d breaker(s) created, %d already present." % (made, skipped))
    print("%d in the full matrix: %d ratings x %d pole counts."
          % (len(RATINGS) * len(POLES), len(RATINGS), len(POLES)))
    print("Edit the front printing in each *_body.png, then run gen_circuit_breaker.py.")


main()
