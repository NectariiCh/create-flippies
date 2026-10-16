"""Regenerates every DIN rail asset: the texture, four block models, one item
model, one block state and one loot table.

The rails are repetitive five-box sections and there are four models of them, so
they are generated rather than authored by hand. The section below mirrors
DinRailBlock and the two have to be changed together.
Run it from anywhere:  python tools/gen_din_rail.py

Needs Pillow. It writes only under src/main/resources and takes no part in the
Gradle build."""
import json, pathlib, random
from PIL import Image

ROOT = pathlib.Path(__file__).resolve().parent.parent / "src" / "main" / "resources"
ASSETS = ROOT / "assets" / "create_flippies"
DATA = ROOT / "data" / "create_flippies"
NS = "create_flippies"
TEX = NS + ":block/din_rail"
NL = "\n"

# --- the section, in authored pixels (mirrors DinRailBlock) -----------------
# A top hat rail after EN/IEC 60715: 35mm overall, 7.5mm deep, 27mm base, folded
# from 1mm sheet. The proportions are the real ones bar the sheet, which is held
# at a quarter pixel because a tenth of a pixel does not render.
WIDTH = 3.5
DEPTH = WIDTH * 7.5 / 35
BASE_WIDTH = WIDTH * 27.0 / 35
SHEET = 0.25
COUNTS = [1, 2]

# --- texture regions, in texture pixels ------------------------------------
CHANNEL = (0, 0, 16, BASE_WIDTH)   # the slotted base, seen down inside the rail
LIP = (0, 3, 16, 4)                # the front and outer edge of the lips
SHADE = (0, 4, 16, 5)              # channel walls and the underside of the lips
PLAIN = (0, 6, 16, 16)             # end caps and the back of the base


def region(r, width=None, height=None):
    """A sub-rectangle of a strip, taken from its top left corner."""
    u1, v1, u2, v2 = r
    if width is not None:
        u2 = u1 + width
    if height is not None:
        v2 = v1 + height
    return [u1, v1, u2, v2]


def write_json(path, data):
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(data, indent=2) + NL, encoding="utf-8")
    return path


# ---------------------------------------------------------------- texture ---
def steel(px, x, y, base, rng):
    """Galvanized zinc: a flat tone with a faint spangle over it."""
    n = rng.choice((-8, -4, -4, 0, 0, 0, 4, 4, 8))
    px[x, y] = tuple(max(0, min(255, c + n)) for c in base) + (255,)


def write_texture():
    HIGHLIGHT = (198, 202, 207)
    BASE = (166, 170, 176)
    MID = (146, 150, 156)
    SHADOW = (110, 114, 120)
    DEEP = (88, 92, 98)
    SLOT = (56, 58, 62)

    rng = random.Random(1211)
    img = Image.new("RGBA", (16, 16), (0, 0, 0, 0))
    px = img.load()

    # rows 0-2: the base, seen down inside the channel and shaded by the lips
    # standing over it
    for x in range(16):
        steel(px, x, 0, SHADOW, rng)
        steel(px, x, 1, MID, rng)
        steel(px, x, 2, SHADOW, rng)
    # the punched slots, elongated and two to a block length so a run of blocks
    # keeps the pitch across the seams
    for x in list(range(2, 6)) + list(range(10, 14)):
        px[x, 1] = SLOT + (255,)
    for x in (1, 6, 9, 14):
        px[x, 1] = DEEP + (255,)

    # row 3: the lips, the front of the rail and the only part in full light
    for x in range(16):
        steel(px, x, 3, HIGHLIGHT if x % 8 else BASE, rng)

    # row 4: the channel walls and the undersides of the lips
    for x in range(16):
        steel(px, x, 4, DEEP, rng)

    # rows 6-15: plain stock for end caps and the back of the base. Flat on
    # purpose: any pattern here shows up as stripes down the rail's cut ends.
    for y in range(6, 16):
        for x in range(16):
            steel(px, x, y, MID, rng)

    out = ASSETS / "textures" / "block" / "din_rail.png"
    out.parent.mkdir(parents=True, exist_ok=True)
    img.save(out)
    return out


# ----------------------------------------------------------------- models ---
def face(uv, rotation=0):
    f = {"uv": [round(v, 4) for v in uv], "texture": "#0"}
    if rotation:
        f["rotation"] = rotation
    return f


def rail_elements(count, along_z):
    """One count's boxes. along_z turns the run of the rails a quarter turn,
    which is the pose a block state variant cannot reach by itself: it may only
    rotate about X and then Y, and no such pair spins a model about the very
    axis it is mounted on.

    Each rail is five boxes, folded the way the real section is: a slotted base
    on the panel, two legs up from its edges, two lips folded outward at the
    front. Faces that end up buried are left off rather than drawn."""
    turn = 90 if along_z else 0
    half = WIDTH / 2
    base_half = BASE_WIDTH / 2
    lip_floor = DEPTH - SHEET
    elements = []

    def add(name, lo, hi, span, roles):
        """lo/hi are the depth off the panel; span is the pair of coordinates
        across the rail, which is z when the rails run along x and vice versa."""
        a, b = span
        frm = [a, lo, 0] if along_z else [0, lo, a]
        to = [b, hi, 16] if along_z else [16, hi, b]
        long_faces = ("east", "west") if along_z else ("north", "south")
        cap_faces = ("north", "south") if along_z else ("east", "west")
        faces = {}
        for role, key in (("low", long_faces[0]), ("high", long_faces[1])):
            if roles.get(role) is not None:
                faces[key] = roles[role]
        for cap in cap_faces:
            faces[cap] = face(region(PLAIN, width=b - a, height=hi - lo))
        if roles.get("front") is not None:
            faces["up"] = roles["front"]
        if roles.get("back") is not None:
            faces["down"] = roles["back"]
        elements.append({"name": name, "from": frm, "to": to, "faces": faces})

    for rail in range(count):
        centre = (16.0 / count) * (rail + 0.5)
        n = rail + 1
        # the base: its front is the slotted floor of the channel, its back lies
        # on the mounting plane where a solid neighbour culls it for free, and
        # its two edges run on under the legs
        add("Rail %d Base" % n, 0, SHEET, (centre - base_half, centre + base_half), {
            "front": face(region(CHANNEL, height=BASE_WIDTH), turn),
            "back": face(region(PLAIN, height=BASE_WIDTH), turn),
            "low": face(region(SHADE, height=SHEET)),
            "high": face(region(SHADE, height=SHEET)),
        })
        # the legs: outer face in line with the edge of the base, inner face the
        # wall of the channel. Top and bottom are buried under lip and base.
        for side, span in (("low", (centre - base_half, centre - base_half + SHEET)),
                           ("high", (centre + base_half - SHEET, centre + base_half))):
            add("Rail %d Leg %s" % (n, "-" if side == "low" else "+"), SHEET, lip_floor, span, {
                "low": face(region(SHADE, height=lip_floor - SHEET)),
                "high": face(region(SHADE, height=lip_floor - SHEET)),
            })
        # the lips: front in full light, outer edge likewise, underside and
        # channel side in shade. Their backs overhang the legs and are seen.
        for side, span in (("low", (centre - half, centre - base_half + SHEET)),
                           ("high", (centre + base_half - SHEET, centre + half))):
            width = span[1] - span[0]
            add("Rail %d Lip %s" % (n, "-" if side == "low" else "+"), lip_floor, DEPTH, span, {
                "front": face(region(LIP, height=width), turn),
                "back": face(region(SHADE, height=width), turn),
                "low": face(region(LIP if side == "low" else SHADE, height=SHEET)),
                "high": face(region(SHADE if side == "low" else LIP, height=SHEET)),
            })
    return elements


def write_models():
    written = []
    for count in COUNTS:
        for along_z, suffix in ((False, ""), (True, "_turned")):
            written.append(write_json(
                ASSETS / "models" / "block" / ("din_rail_%d%s.json" % (count, suffix)),
                {
                    "__generated": "tools/gen_din_rail.py; section mirrors DinRailBlock",
                    "ambientocclusion": False,
                    "textures": {"0": TEX, "particle": TEX},
                    "elements": rail_elements(count, along_z),
                }))

    # One item for every count: it places a single rail and is what you add more
    # with, so its icon is the single rail model. The block models are authored
    # lying on the floor of their cell, so the item needs transforms of its own —
    # without them the icon is the rail seen edge on, which is a line.
    written.append(write_json(ASSETS / "models" / "item" / "din_rail.json", {
        "parent": NS + ":block/din_rail_1",
        "display": {
            "gui": {"rotation": [30, 225, 0], "translation": [0, 5, 0], "scale": [0.72, 0.72, 0.72]},
            "ground": {"rotation": [0, 0, 0], "translation": [0, 5, 0], "scale": [0.25, 0.25, 0.25]},
            "fixed": {"rotation": [0, 0, 0], "translation": [0, 5, 0], "scale": [0.5, 0.5, 0.5]},
            "thirdperson_righthand": {"rotation": [75, 45, 0], "translation": [0, 4, 0], "scale": [0.375, 0.375, 0.375]},
            "thirdperson_lefthand": {"rotation": [75, 45, 0], "translation": [0, 4, 0], "scale": [0.375, 0.375, 0.375]},
            "firstperson_righthand": {"rotation": [0, 45, 0], "translation": [0, 3, 0], "scale": [0.4, 0.4, 0.4]},
            "firstperson_lefthand": {"rotation": [0, 225, 0], "translation": [0, 3, 0], "scale": [0.4, 0.4, 0.4]},
        },
    }))
    return written


# ------------------------------------------------------------- blockstates ---
# SurfaceMount rotates by Ry(yaw) then Rx(tilt), counted in right handed quarter
# turns; a block state x and y are the same two rotations measured the other way
# round, so each angle is negated.
FACING_ROTATION = {
    "down": (0, 0),
    "up": (180, 0),
    "north": (270, 0),
    "south": (270, 180),
    "west": (270, 270),
    "east": (270, 90),
}


def write_blockstates():
    variants = {}
    for count in COUNTS:
        for facing, (x, y) in FACING_ROTATION.items():
            for rotation, suffix in ((0, ""), (1, "_turned")):
                entry = {"model": NS + ":block/din_rail_" + str(count) + suffix}
                if x:
                    entry["x"] = x
                if y:
                    entry["y"] = y
                variants["facing=%s,rails=%d,rotation=%d" % (facing, count, rotation)] = entry
    return [write_json(ASSETS / "blockstates" / "din_rail.json", {"variants": variants})]


# ------------------------------------------------------------- loot tables ---
def write_loot():
    """One pool, one entry per count. A pool of one roll takes the single entry
    whose conditions pass, and exactly one count matches any given state, so a
    broken rail block gives back one rail item per rail it was carrying."""
    entries = []
    for count in COUNTS:
        entries.append({
            "type": "minecraft:item",
            "name": NS + ":din_rail",
            "conditions": [
                {"condition": "minecraft:block_state_property",
                 "block": NS + ":din_rail",
                 "properties": {"rails": str(count)}},
                {"condition": "minecraft:survives_explosion"},
            ],
            "functions": [{"function": "minecraft:set_count", "count": count}],
        })
    return [write_json(DATA / "loot_tables" / "blocks" / "din_rail.json",
                       {"type": "minecraft:block", "pools": [{"rolls": 1, "entries": entries}]})]


for path in [write_texture()] + write_models() + write_blockstates() + write_loot():
    print(path.relative_to(ROOT))
