#!/usr/bin/env python
"""
sprite_sizes.py - measure every enemy sprite against its atlas and propose a SIZE CLASS.

THE RULE (round 160, user decision 2026-09-09): an enemy's rendered height is set by WHAT IT IS,
not by how big its art happens to be. Six classes, in tiles of 16px, all PRE-tier:

    Tiny 8 | Critter 12 | Person 16 | Medium 24 | Large 32 | Huge 48

    scale = class height / Idle-frame height        (EnemyData.scale in world/enemies.json)

The tier cue (TuningData.tierSizeMultiplier) multiplies on top, so an Archmage Person still reads
bigger than an Adept Person. For NEW art the rule is one line: pick the class, divide.

Usage (from the repo root):
    python dev-tools/sprite_sizes.py                      audit summary
    python dev-tools/sprite_sizes.py --csv out.csv        one row per enemy, all 1,787
    python dev-tools/sprite_sizes.py --json out.json      in-scope rows + thumbnails, for the review page
    python dev-tools/sprite_sizes.py --check new.atlas    frame size and the scale for every class

What "in scope" means: renders between 14 and 48px pre-tier AND off the 16px grid. Below 14px the
roster's critters are deliberate (Ladybug, Cat, Fox, Bat...) and are left alone; at 48px and up a
sprite already reads as huge. The tier cue does not enter the scope test - classes are pre-tier.
"""
import argparse, base64, collections, csv, io, json, os, statistics, sys

HERE = os.path.dirname(os.path.abspath(__file__))
REPO = os.path.dirname(HERE)
ADV = os.path.join(REPO, "forge-gui", "res", "adventure")
PLANE = os.path.join(ADV, "The Forsaken Realms")
COMMON = os.path.join(ADV, "common")

TILE = 16.0
CLASSES = [("Tiny", 8), ("Critter", 12), ("Person", 16), ("Medium", 24), ("Large", 32), ("Huge", 48)]
CLASS_PX = dict(CLASSES)
CLASS_ORDER = [c for c, _ in CLASSES]
TIER_NAME = {"Common": "Apprentice", "Uncommon": "Adept", "Rare": "Master", "Mythic": "Archmage"}
SCOPE_MIN, SCOPE_MAX = 14.0, 48.0


# ------------------------------------------------------------------ atlas reading
def parse_atlas(path):
    """-> (png filename, OrderedDict region -> [(x, y, w, h), ...]). libGDX text format, xy/size style."""
    regions = collections.OrderedDict()
    page = None
    cur = None
    pending = {}
    with open(path, encoding="utf-8", errors="replace") as f:
        for raw in f:
            line = raw.rstrip("\r\n")
            if not line.strip():
                continue
            s = line.strip()
            indented = line[0] in " \t"
            if not indented and ":" not in s:
                if s.lower().endswith((".png", ".jpg")):
                    page = page or s
                    cur = None
                else:
                    cur = s
                    pending = {}
                continue
            if cur is None:
                continue
            if s.startswith("xy:"):
                pending["x"], pending["y"] = [int(v) for v in s[3:].split(",")]
            elif s.startswith("size:"):
                pending["w"], pending["h"] = [int(v) for v in s[5:].split(",")]
            elif s.startswith("bounds:"):
                pending["x"], pending["y"], pending["w"], pending["h"] = [int(v) for v in s[7:].split(",")]
            if all(k in pending for k in ("x", "y", "w", "h")):
                regions.setdefault(cur, []).append((pending["x"], pending["y"], pending["w"], pending["h"]))
                pending = {}
    return page, regions


def idle_frame(regions):
    """The first Idle frame, else the first non-Avatar frame. -> (x, y, w, h) or None."""
    for name, frames in regions.items():
        if name.startswith("Idle") and frames:
            return frames[0]
    for name, frames in regions.items():
        if name != "Avatar" and frames:
            return frames[0]
    return None


def resolve(sprite):
    for base, tag in ((PLANE, "plane"), (COMMON, "common")):
        p = os.path.join(base, sprite.replace("/", os.sep))
        if os.path.exists(p):
            return p, tag
    return None, None


def thumbnail(atlas_path, page, frame):
    """Crop the frame out of the atlas page -> PNG data URI, or None (no PIL, no page)."""
    try:
        from PIL import Image
    except ImportError:
        return None
    if not page:
        return None
    png = os.path.join(os.path.dirname(atlas_path), page)
    if not os.path.exists(png):
        return None
    x, y, w, h = frame
    with Image.open(png) as im:
        crop = im.convert("RGBA").crop((x, y, x + w, y + h))
        buf = io.BytesIO()
        crop.save(buf, format="PNG", optimize=True)
    return "data:image/png;base64," + base64.b64encode(buf.getvalue()).decode("ascii")


# ------------------------------------------------------------------ classification
LARGE_WORDS = ("large", "giant", "titan", "colossus", "boss", "huge", "elder_dragon", "greater")
MEDIUM_WORDS = ("king", "lord", "elder", "ancient", "chief", "warlord", "queen", "champion", "ogre",
                "troll", "minotaur", "brute", "alpha", "dire", "war_")
MEDIUM_ANIMALS = ("bear", "horse", "boar", "wolf", "lion", "tiger", "panther", "stag", "deer", "elk",
                  "moose", "bull", "cow", "ox", "camel", "gorilla", "croc", "alligator", "shark",
                  "python", "anaconda", "hyena", "leopard", "jaguar", "sabre", "warthog",
                  "rhino", "hippo", "buffalo", "yak", "mammoth", "elephant", "unicorn", "pegasus",
                  "carabao", "tamaraw", "kalabaw", "kabayo", "baka")
SMALL_DRAGON = ("drake", "wyrmling", "hatchling", "whelp", "faerie")

CRITTER_FOLDERS = ("basic/animal", "beast/smallmammals", "beast/bird", "beast/insect", "beast/arachnid",
                   "beast/reptile", "beast/cat", "ooze")
PERSON_FOLDERS = ("heroes", "basic/humanoid", "basic/undead", "basic/spirit", "basic/magical", "undead",
                  "humanoid/human", "humanoid/dwarf", "humanoid/elf", "humanoid/goblin", "humanoid/kobold",
                  "humanoid/merfolk", "humanoid/viashino", "humanoid/kor", "humanoid/orc", "humanoid/nezumi",
                  "humanoid")
MEDIUM_FOLDERS = ("beast/largemammals", "beast/horse", "beast/bear", "beast/polar", "beast/ape", "beast/barn",
                  "humanoid/minotaur")
LARGE_FOLDERS = ("basic/dragon", "dragon", "giant")
RATIO_FOLDERS = ("basic/demon", "basic/monster", "basic/elemental", "mythic", "fiend", "celestial", "elemental",
                 "monstrosity", "aberration", "aberration/phyrexian", "aberration/eldrazi", "construct", "fey",
                 "plant", "beast/dinosaur", "beast/ocean", "beast")


def bump(cls, steps):
    i = min(len(CLASS_ORDER) - 1, max(0, CLASS_ORDER.index(cls) + steps))
    return CLASS_ORDER[i]


def ratio_class(raw_h, person_h):
    r = raw_h / person_h
    if r < 0.7:
        return "Critter"
    if r < 1.3:
        return "Person"
    if r < 1.8:
        return "Medium"
    if r < 2.6:
        return "Large"
    return "Huge"


def nearest_class(px, prefer_larger):
    """The ladder step closest to a rendered height; ties go up for a Master/Archmage, down otherwise."""
    best = None
    for cls, h in CLASSES:
        d = abs(h - px)
        if best is None or d < best[0] or (d == best[0] and prefer_larger):
            best = (d, cls)
    return best[1]


def classify(row, person_h):
    """-> (class, reason, needs_eyes).

    Order of authority: boss/life, then a confident SUBJECT rule from the atlas folder and the
    name, then - where the folder says nothing certain - somebody's earlier deliberate scale (a
    non-1.0 value means a size was chosen, just not on the ladder: snap to the nearest step), and
    only last the art's own proportion against its pack's person height. Every ratio call and
    every move above 40% is marked for eyes.
    """
    path = row["sprite"].lower()
    rel = path.split("sprites/enemy/", 1)[1] if "sprites/enemy/" in path else path
    folder = "/".join(rel.split("/")[:-1])
    fname = rel.split("/")[-1].replace(".atlas", "")
    text = fname + " " + row["name"].lower()
    tags = set(row["tags"])
    decided = abs(row["scale"] - 1.0) > 1e-6
    rare = row["tier"] in ("Rare", "Mythic")

    def has(words):
        return next((w for w in words if w in text), None)

    cls, reason, eyes = subject_class(row, person_h, path, folder, text, tags, decided, rare, has)
    # Toughness is not size. A boss reads one step bigger than its kin (a boss goblin is Medium,
    # not Huge) and is always worth a look; high life alone earns the look and nothing else.
    if row["boss"]:
        if decided:
            # Somebody already sized this boss for its room (the praetors sit at 40px): keep that
            # intent and land it on the nearest step rather than re-deriving it from the subject.
            cls, reason, eyes = nearest_class(row["cur"], True), reason + ", boss sized on purpose - nearest step to %.0fpx" % row["cur"], True
        else:
            cls, reason, eyes = bump(cls, 1), reason + ", +1 step for boss", True
    elif row["life"] >= 45:
        reason, eyes = reason + ", life %d" % row["life"], True
    return cls, reason, eyes


def subject_class(row, person_h, path, folder, text, tags, decided, rare, has):
    top2 = "/".join(folder.split("/")[:2])
    top1 = folder.split("/")[0]

    def in_folders(fs):
        return top2 in fs or top1 in fs

    if in_folders(PERSON_FOLDERS):
        # A "large" humanoid sprite is a BIG PERSON (a king, a warlord, a brute) - one and a half
        # tiles, not two. Only a giant, titan or colossus leaves the person ladder altogether.
        w = has(("giant", "titan", "colossus"))
        if w:
            return "Large", "%s folder, named %s" % (folder, w), False
        w = has(LARGE_WORDS) or has(MEDIUM_WORDS)
        if w:
            return "Medium", "%s folder, named %s" % (folder, w), False
        return "Person", "%s folder" % folder, False
    if in_folders(LARGE_FOLDERS):
        w = has(SMALL_DRAGON)
        if w:
            return "Medium", "%s folder, a %s" % (folder, w), False
        return "Large", "%s folder" % folder, False
    if in_folders(MEDIUM_FOLDERS):
        if has(LARGE_WORDS):
            return "Large", "%s folder, named large" % folder, False
        return "Medium", "%s folder" % folder, False
    if in_folders(CRITTER_FOLDERS):
        w = has(MEDIUM_ANIMALS)
        if w:
            return "Medium", "%s folder, a %s" % (folder, w), False
        if row["scale"] >= 1.5:
            # A legend on critter art, enlarged on purpose (The Scorpion God at x2): keep it big.
            cls = nearest_class(row["cur"], rare)
            return cls, "%s folder, but scaled x%g on purpose - nearest step to %.0fpx" % (folder, row["scale"], row["cur"]), True
        if "/basic/" in path:
            # The plane's basic pack draws people at ~32px, so its animals are read against that.
            cls = "Critter" if row["h"] / person_h < 0.6 else ("Person" if row["h"] / person_h < 1.0 else "Medium")
            return cls, "%s folder, art %dpx vs %dpx person" % (folder, row["h"], person_h), True
        if has(("giant", "dire")):
            # A giant rat is person-sized; a giant slime on 40px art is a two-tile monster.
            if row["h"] >= 32:
                return "Large", "%s folder, named giant on %dpx art" % (folder, row["h"]), False
            return "Person", "%s folder, named giant" % folder, False
        return "Critter", "%s folder" % folder, False

    # No confident subject rule. Someone's deliberate scale beats the art's proportions.
    if decided:
        cls = nearest_class(row["cur"], rare)
        reason = "scale x%g was a choice - nearest step to %.1fpx" % (row["scale"], row["cur"])
        eyes = abs(CLASS_PX[cls] - row["cur"]) / row["cur"] > 0.25
    else:
        cls = ratio_class(row["h"], person_h)
        reason = "art %dpx vs %dpx person (x%.2f)" % (row["h"], person_h, row["h"] / person_h)
        eyes = True
    if "Tiny" in tags or "Small" in tags:
        cls, reason = "Critter", reason + ", tagged small"
    elif "Humanoid" in tags or "Human" in tags:
        cls, reason = "Person", reason + ", tagged humanoid"
    elif has(LARGE_WORDS):
        cls, reason = bump(cls, 1), reason + ", named large"
    return cls, reason, eyes


# ------------------------------------------------------------------ measurement
def measure(with_thumbs=False):
    enemies = json.load(open(os.path.join(PLANE, "world", "enemies.json"), encoding="utf-8"))
    cache = {}
    rows, unresolved = [], []
    for e in enemies:
        sp = e["sprite"]
        if sp not in cache:
            p, where = resolve(sp)
            if p is None:
                cache[sp] = None
            else:
                page, regions = parse_atlas(p)
                frame = idle_frame(regions)
                cache[sp] = None if frame is None else (p, where, page, frame)
        info = cache[sp]
        if info is None:
            unresolved.append((e["name"], sp))
            continue
        p, where, page, (x, y, w, h) = info
        scale = float(e.get("scale", 1.0))
        rows.append(dict(name=e["name"], sprite=sp, where=where, w=w, h=h, scale=scale,
                         tier=e.get("tier", "Common"), life=int(e.get("life", 0)), boss=bool(e.get("boss", False)),
                         tags=list(e.get("questTags", []) or []), cur=h * scale,
                         _atlas=p, _page=page, _frame=(x, y, w, h)))
    # Pack person height: the plane's "basic" pack draws people ~20-25px, common draws them at 16.
    basic_h = [r["h"] for r in rows if "/basic/humanoid/" in r["sprite"]]
    person = {"basic": statistics.median(basic_h) if basic_h else TILE, "common": TILE}
    for r in rows:
        pack = "basic" if "/basic/" in r["sprite"] else "common"
        ph = person[pack]
        r["pack_person_h"] = ph
        on_grid = abs(r["cur"] / TILE - round(r["cur"] / TILE)) < 1e-6 and r["cur"] >= TILE
        r["in_scope"] = (not on_grid) and SCOPE_MIN <= r["cur"] < SCOPE_MAX
        r["keep_reason"] = "" if r["in_scope"] else ("on grid" if on_grid else ("critter, deliberate" if r["cur"] < SCOPE_MIN else "already huge"))
        cls, reason, eyes = classify(r, ph)
        r["cls"] = cls
        r["prop_scale"] = round(CLASS_PX[cls] / r["h"], 2)
        r["prop"] = round(r["h"] * r["prop_scale"], 1)
        move = abs(r["prop"] - r["cur"]) / r["cur"] if r["cur"] else 0
        if move > 0.4:
            eyes = True
            reason += ", moves %d%%" % round(move * 100)
        r["reason"] = reason
        r["eyes"] = eyes
        r["tier_name"] = TIER_NAME.get(r["tier"], r["tier"])
        if with_thumbs and r["in_scope"]:
            r["thumb"] = thumbnail(r["_atlas"], r["_page"], r["_frame"])
    return rows, unresolved, person


def summary(rows, unresolved, person):
    scope = [r for r in rows if r["in_scope"]]
    print("enemies measured: %d   unresolved: %d" % (len(rows), len(unresolved)))
    for u in unresolved[:10]:
        print("   MISSING", u)
    print("pack person height: basic %.0fpx, common %.0fpx" % (person["basic"], person["common"]))
    print("in scope (14-48px, off grid): %d   critters kept: %d   huge kept: %d   on grid: %d" % (
        len(scope), sum(1 for r in rows if r["keep_reason"] == "critter, deliberate"),
        sum(1 for r in rows if r["keep_reason"] == "already huge"),
        sum(1 for r in rows if r["keep_reason"] == "on grid")))
    by = collections.Counter(r["cls"] for r in scope)
    print("proposed classes:", "  ".join("%s %d" % (c, by[c]) for c in CLASS_ORDER if by[c]))
    print("need eyes: %d   automatic: %d" % (sum(1 for r in scope if r["eyes"]), sum(1 for r in scope if not r["eyes"])))
    print("\nsmallest ten after the rule:")
    for r in sorted(scope, key=lambda r: r["prop"])[:10]:
        print("   %-28s %s %4.1f -> %4.1fpx  %s" % (r["name"], r["cls"], r["cur"], r["prop"], r["reason"]))


def check(atlas_path):
    page, regions = parse_atlas(atlas_path)
    frame = idle_frame(regions)
    if frame is None:
        print("no Idle frame found in", atlas_path)
        return
    x, y, w, h = frame
    print("%s: Idle frame %dx%d (page %s, %d regions)" % (os.path.basename(atlas_path), w, h, page, len(regions)))
    for cls, px in CLASSES:
        print("   %-8s %2dpx -> scale %.2f" % (cls, px, px / h))


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--csv", help="write the full table (all enemies) to this CSV")
    ap.add_argument("--json", help="write in-scope rows with thumbnails to this JSON, for the review page")
    ap.add_argument("--check", help="an .atlas file: print its Idle frame size and the scale per class")
    a = ap.parse_args()
    if a.check:
        check(a.check)
        return
    rows, unresolved, person = measure(with_thumbs=bool(a.json))
    summary(rows, unresolved, person)
    if a.csv:
        cols = ["name", "tier", "life", "boss", "sprite", "w", "h", "scale", "cur", "in_scope", "keep_reason",
                "cls", "prop_scale", "prop", "reason", "eyes"]
        with open(a.csv, "w", newline="", encoding="utf-8") as f:
            wr = csv.writer(f)
            wr.writerow(cols)
            for r in sorted(rows, key=lambda r: (not r["in_scope"], r["cls"], r["name"])):
                wr.writerow([r[c] for c in cols])
        print("wrote", a.csv)
    if a.json:
        scope = [r for r in rows if r["in_scope"]]
        out = {
            "rule": {"classes": CLASSES, "tile": TILE, "scope": [SCOPE_MIN, SCOPE_MAX]},
            "pack_person_h": person,
            "counts": {"measured": len(rows), "in_scope": len(scope),
                       "critters_kept": sum(1 for r in rows if r["keep_reason"] == "critter, deliberate"),
                       "huge_kept": sum(1 for r in rows if r["keep_reason"] == "already huge"),
                       "on_grid": sum(1 for r in rows if r["keep_reason"] == "on grid")},
            "kept": {"critters": sorted(r["name"] for r in rows if r["keep_reason"] == "critter, deliberate"),
                     "huge": sorted(r["name"] for r in rows if r["keep_reason"] == "already huge")},
            "rows": [{k: r[k] for k in ("name", "tier", "tier_name", "life", "boss", "sprite", "where", "w", "h",
                                        "scale", "cur", "cls", "prop_scale", "prop", "reason", "eyes", "tags", "thumb")}
                     for r in sorted(scope, key=lambda r: (CLASS_ORDER.index(r["cls"]), r["name"]))],
        }
        with open(a.json, "w", encoding="utf-8") as f:
            json.dump(out, f)
        print("wrote", a.json, "(%d rows)" % len(scope))


if __name__ == "__main__":
    main()
