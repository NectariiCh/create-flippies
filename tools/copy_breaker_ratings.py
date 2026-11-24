"""Copies the authored breakers into every current rating and supply voltage, so
that each can have its printing edited by hand.

Every rating of a breaker is the same object with a different number on the
front. Rather than author forty-eight of them, one of each pole count is authored
and copied here in two steps:

  * across the ratings, at 230 V: the model, the body texture and the lever
    texture, with the texture references inside each copy repointed at that
    rating's own files. The amp figure is left for hand editing;
  * across the voltages, from each rating's 230 V copy: the same three files,
    and the "230V" printed on the body reprinted in the body's own pixel font
    (see reprint_volts), so a 120 V breaker says so without anyone opening it.
    Copying from the rating's own 230 V files rather than the CB16s keeps the
    amp figure that was edited into them.

The copies are the new authoring sources — edit the front printing in
CB8-DIN-WO-230V-1M-SP-body.png and so on. Nothing here is generated a second
time, so running it again would overwrite that work; it refuses to touch a
breaker whose files already exist unless --force is passed.

    python tools/copy_breaker_ratings.py            # make what is missing
    python tools/copy_breaker_ratings.py --force    # overwrite, losing edits

Afterwards run gen_circuit_breaker.py, which splits every breaker it finds.

Needs Pillow for the reprinting."""
import json, pathlib, re, shutil, sys

from PIL import Image

ROOT = pathlib.Path(__file__).resolve().parent.parent / "src" / "main" / "resources"
ASSETS = ROOT / "assets" / "create_flippies"
BLOCK_MODELS = ASSETS / "models" / "block"
TEXTURES = ASSETS / "textures" / "block"

# The rating and voltage the others are cut from, and the shape of every
# breaker's name.
SOURCE_RATING = 16
SOURCE_VOLTS = 230
NAME = "CB%d-DIN-WO-%dV-%dM-%s"

# Ratings from the IEC 60898 preferred series, as far as this mod goes. Must match
# CircuitBreakerType.RATINGS.
RATINGS = [8, 10, 16, 20, 24, 32, 40, 48]

# Rated supply voltages, the mod's two mains ratings. Must match
# CircuitBreakerType.VOLTAGES.
VOLTAGES = [230, 120]

# Every pole count, as (modules, suffix). Must match CircuitBreakerPoles.
POLES = [(1, "SP"), (2, "DP"), (3, "TP")]

# The texture suffixes each breaker owns. copper_contacts is shared and stays put.
TEXTURE_PARTS = ["body", "lever"]

# The pixel font the front printing is drawn in, read off the CB16 and CB48
# bodies: three by five, a column of background between glyphs. Only the glyphs
# a voltage needs are here, plus the V that anchors one so that a "230" in the
# model number line is never mistaken for anything else.
FONT = {
    "0": ["###", "#.#", "#.#", "#.#", "###"],
    "1": [".#.", "##.", ".#.", ".#.", "###"],
    "2": ["###", "..#", "###", "#..", "###"],
    "3": ["###", "..#", "###", "..#", "###"],
    "4": ["#.#", "#.#", "###", "..#", "..#"],
    "6": ["###", "#..", "###", "#.#", "###"],
    "8": [".#.", "#.#", ".#.", "#.#", ".#."],
    "V": ["#.#", "#.#", "#.#", "#.#", ".#."],
}
INK = (35, 35, 35, 255)
GLYPH_W, GLYPH_H, PITCH = 3, 5, 4


def asset_name(rating, volts, modules, poles):
    return NAME % (rating, volts, modules, poles)


def resource_name(stem):
    """The lower case form Minecraft will actually load; see gen_circuit_breaker."""
    return stem.lower().replace("-", "_")


def find_model(stem):
    for name in (resource_name(stem), stem):
        path = BLOCK_MODELS / (name + ".json")
        if path.exists():
            return path
    return None


def repoint(model, source_stem, target_stem):
    """Aims a copied model's texture references at its own files.

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


def reprint_volts(path, source_volts, target_volts):
    """Rewrites the "230V" on a body texture as another voltage, in place.

    The printing is upside down in the texture — the front face's UVs turn it
    the right way up — so the image is turned over, searched, and turned back.
    Every place the source voltage and its V are spelled out in FONT is found
    by exact pixel match and the digits are redrawn; the background a digit is
    cleared to is whatever the gap beside it already holds, so the reprint
    lands on the plate colour the export used. Returns how many were reprinted,
    and refuses a body that prints none, since that is a font this does not
    know rather than a breaker with nothing to change."""
    text_from, text_to = "%dV" % source_volts, "%dV" % target_volts
    if len(text_from) != len(text_to):
        raise SystemExit("cannot reprint %s as %s: different widths" % (text_from, text_to))

    image = Image.open(path).convert("RGBA").rotate(180)
    pixels = image.load()
    width, height = image.size

    def matches(x0, y0, text):
        for i, ch in enumerate(text):
            for dy, row in enumerate(FONT[ch]):
                for dx, cell in enumerate(row):
                    inked = pixels[x0 + i * PITCH + dx, y0 + dy] == INK
                    if inked != (cell == "#"):
                        return False
        return True

    span = len(text_from) * PITCH - 1
    found = [(x, y) for y in range(height - GLYPH_H + 1) for x in range(width - span + 1)
             if matches(x, y, text_from)]
    if not found:
        raise SystemExit("no %s printed on %s" % (text_from, path.name))

    for x0, y0 in found:
        for i, ch in enumerate(text_to):
            gx = x0 + i * PITCH
            # The gap column after a glyph is never inked, so it is the paper
            paper = pixels[gx + GLYPH_W, y0]
            for dy, row in enumerate(FONT[ch]):
                for dx, cell in enumerate(row):
                    pixels[gx + dx, y0 + dy] = INK if cell == "#" else paper

    image.rotate(180).save(path)
    return len(found)


def copy_breaker(source_stem, target_stem, force):
    """Copies one breaker's export and textures. False if it was already there."""
    model_path = find_model(source_stem)
    if model_path is None:
        raise SystemExit("no model to copy from: %s" % source_stem)
    targets = [BLOCK_MODELS / (target_stem + ".json")]
    targets += [TEXTURES / (resource_name(target_stem) + "_" + part + ".png") for part in TEXTURE_PARTS]

    if any(t.exists() for t in targets) and not force:
        return False

    model = json.loads(model_path.read_text(encoding="utf-8"))
    targets[0].write_text(json.dumps(repoint(model, source_stem, target_stem), indent=2) + "\n",
                          encoding="utf-8")
    for part, target in zip(TEXTURE_PARTS, targets[1:]):
        origin = TEXTURES / (resource_name(source_stem) + "_" + part + ".png")
        if not origin.exists():
            raise SystemExit("missing texture to copy: %s" % origin)
        shutil.copyfile(origin, target)
    return True


def main():
    force = "--force" in sys.argv
    made = skipped = 0

    for modules, poles in POLES:
        source_stem = asset_name(SOURCE_RATING, SOURCE_VOLTS, modules, poles)
        if find_model(source_stem) is None:
            print("no %s model to copy from, skipping that pole count" % source_stem)
            continue
        print("%s from %s" % (poles, source_stem))

        for rating in RATINGS:
            for volts in VOLTAGES:
                if rating == SOURCE_RATING and volts == SOURCE_VOLTS:
                    continue
                # Other voltages come from this rating's own copy, amp figure and all
                origin = source_stem if volts == SOURCE_VOLTS \
                    else asset_name(rating, SOURCE_VOLTS, modules, poles)
                target_stem = asset_name(rating, volts, modules, poles)
                if not copy_breaker(origin, target_stem, force):
                    print("    CB%-3d %dV %s already there, left alone" % (rating, volts, poles))
                    skipped += 1
                    continue
                note = ""
                if volts != SOURCE_VOLTS:
                    body = TEXTURES / (resource_name(target_stem) + "_body.png")
                    note = ", %d reprint(s)" % reprint_volts(body, SOURCE_VOLTS, volts)
                print("    CB%-3d %dV %s -> %s + %d textures%s"
                      % (rating, volts, poles, target_stem, len(TEXTURE_PARTS), note))
                made += 1

    print("\n%d breaker(s) created, %d already present." % (made, skipped))
    print("%d in the full matrix: %d ratings x %d voltages x %d pole counts."
          % (len(RATINGS) * len(VOLTAGES) * len(POLES), len(RATINGS), len(VOLTAGES), len(POLES)))
    print("Edit the front printing in each *_body.png, then run gen_circuit_breaker.py.")


main()
