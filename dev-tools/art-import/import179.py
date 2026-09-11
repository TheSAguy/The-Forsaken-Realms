"""import179.py - round 179: the 197 new enemies into The Forsaken Realms plane.

What it writes (run once; it refuses if any roster name is already in enemies.json):
  sprites/enemy/tfr/<stem>.atlas + .png      the converted sheets (stem = the enemy's name, snake_case)
  decks/standard/tfr/<stem>.dck              the themed decks from deckgen179.py
  world/enemies.json                         197 entries appended (tier stats, rewards, colors, questTags)
  world/biomes/<color>.json "enemies"        each enemy in the roster of every one of its colors; the undead /
                                             horror / construct ones below Archmage also in the Wasteland's
  maps (5 AI capitals + player capital)      the "arena" enemyPool gains Masters and Archmages (see ARENA_*)
  config tables/enemies.csv                  the content-filter snapshot, rebuilt the way the game writes it
Afterwards: python dev-tools/enemy_scale.py --write (the sizes), python dev-tools/validate_plane_data.py,
python dev-tools/gen_caves.py --no-register (the caves re-pick their roamers from the new rosters).
usage: python import179.py [--dry]"""
import csv, io, json, os, re, shutil, sys, xml.etree.ElementTree as ET, zlib, collections, html

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
from roster179 import ROSTER
from deckgen179 import THEMES

REPO = r"F:\FORGE\C--Users-vicwaver-MTG-Forge"
PLANE = os.path.join(REPO, "forge-gui", "res", "adventure", "The Forsaken Realms")
STAGE = r"F:\FORGE\TFR-Art-Staging"
SPRITE_DIR = "sprites/enemy/tfr"
DECK_DIR = "decks/standard/tfr"
DRY = "--dry" in sys.argv

TIER = {"A": "Common", "D": "Uncommon", "M": "Rare", "X": "Mythic"}
WORD = {"W": "White", "U": "Blue", "B": "Black", "R": "Red", "G": "Green"}
BIOME = {"W": "white", "U": "blue", "B": "black", "R": "red", "G": "green"}
GUILD = {"WU": "Azorius", "UB": "Dimir", "BR": "Rakdos", "RG": "Gruul", "WG": "Selesnya", "WB": "Orzhov",
         "UR": "Izzet", "BG": "Golgari", "WR": "Boros", "UG": "Simic",
         "WUB": "Esper", "UBR": "Grixis", "BRG": "Jund", "WRG": "Naya", "WUG": "Bant",
         "WBG": "Abzan", "WUR": "Jeskai", "UBG": "Sultai", "WBR": "Mardu", "URG": "Temur"}
# theme -> quest tags from the vocabulary quests already ask for (Undead, Minion, Leader, Goblin, Merfolk, ...)
THEME_TAGS = {
    "skeleton": ["Skeleton", "Undead", "Unholy"], "zombie": ["Zombie", "Undead", "Unholy"],
    "mummy": ["Zombie", "Undead", "Unholy"], "knight_undead": ["Knight", "Undead", "Unholy"],
    "spirit": ["Spirit"], "wraith": ["Spirit", "Undead"], "shade": ["Spirit", "Undead"],
    "angel": ["Angel", "Celestial"], "demon": ["Demon", "Evil"], "devil": ["Devil", "Evil"],
    "vampire": ["Vampire", "Undead", "Unholy"], "dragon": ["Dragon", "Mythical"], "drake": ["Dragon"],
    "phoenix": ["Bird", "Elemental", "Mythical"], "insect": ["Insect", "Swarm"], "spider": ["Spider", "Predator"],
    "beast": ["Beast", "Animal"], "wolf": ["Animal", "Beast", "Predator"], "cat": ["Cat", "Animal", "Predator"],
    "ape": ["Simian", "Animal"], "yeti": ["Beast", "Monster"], "bird": ["Bird", "Animal"],
    "griffin": ["Beast", "Mythical"], "harpy": ["Monster", "Humanoid"], "bat": ["Animal", "Nocturnal"],
    "frog": ["Animal"], "turtle": ["Animal"], "ooze": ["Ooze"], "plant": ["Nature"], "fungus": ["Fungus", "Nature"],
    "treefolk": ["Treefolk", "Nature"], "elemental": ["Elemental"], "merfolk": ["Merfolk", "Humanoid"],
    "sea": ["Water", "Animal"], "shark": ["Water", "Animal", "Predator"],
    "orc": ["Orc", "Humanoid", "Tribal", "Aggressive"], "minotaur": ["Minotaur", "Humanoid", "Aggressive"],
    "giant": ["Giant"], "ogre": ["Giant", "Humanoid"], "soldier": ["Soldier", "Humanoid"],
    "knight": ["Knight", "Humanoid"], "warrior": ["Warrior", "Humanoid"], "samurai": ["Warrior", "Humanoid"],
    "monk": ["Humanoid", "Mystic"], "cleric": ["Cleric", "Religious", "Humanoid"], "wizard": ["Wizard", "Humanoid"],
    "rogue": ["Rogue", "Thief"], "pirate": ["Pirate", "Bandit"], "construct": ["Construct", "Artifact"],
    "horror": ["Horror", "Monster", "Aberration"], "gorgon": ["Monster", "Mythical"], "snake": ["Snake", "Animal"],
    "lizard": ["Lizard", "Animal"], "dinosaur": ["Dinosaur", "Beast"], "rat": ["Rat", "Animal", "Scavenger"],
    "squirrel": ["Animal"], "worm": ["Animal", "Subterranean"], "faerie": ["Faerie"], "satyr": ["Humanoid", "Wild"],
    "tengu": ["Bird", "Humanoid"], "loxodon": ["Humanoid"],
}
MINION_THEMES = {"skeleton", "zombie", "orc", "soldier", "pirate", "merfolk"}   # "Undead Minion" etc. quests
WASTELAND_THEMES = {"skeleton", "zombie", "mummy", "horror", "construct", "wraith", "shade", "knight_undead"}
# rank -> (life, speed, difficulty); a name-seeded jitter spreads life -1..+2 and speed -2..+2
STATS = {"A": (12, 20, 0.1), "D": (20, 26, 1), "M": (24, 35, 2), "X": (46, 45, 3)}
ARENA_CAPITALS = {"maps/map/main_story/plains_capital.tmx": "W", "maps/map/main_story/island_capital.tmx": "U",
                  "maps/map/main_story/swamp_capital.tmx": "B", "maps/map/main_story/mountain_capital.tmx": "R",
                  "maps/map/main_story/forest_capital.tmx": "G"}
ARENA_PLAYER = "maps/map/towns/player_capital.tmx"
ARENA_CAP = {"M": 8, "X": 5}            # per AI capital: new Masters / Archmages of its color
ARENA_PLAYER_PER_COLOR = ("D", "M", "X")  # player capital: one new Adept, Master and Archmage per color


def stem_of(name):
    return re.sub(r"[^a-z0-9]+", "_", name.lower()).strip("_")


def jitter(name, lo, hi):
    return lo + zlib.crc32(name.encode("utf-8")) % (hi - lo + 1)


def wubrg(cols):
    return "".join(c for c in "WUBRG" if c in cols)


def rewards(rank, theme):
    types = THEMES[theme][0]
    cu = ["Common", "Uncommon"]
    rm = ["Rare", "Mythic Rare"]
    r = {
        "A": [("deckCard", 1, 2, 4, ["common", "uncommon"], None), ("gold", 1, 20, 60, None, None),
              ("card", 1, 1, 2, cu, None), ("card", 0.4, 1, 0, cu, types), ("deckCard", 0.3, 1, 0, rm, None)],
        "D": [("deckCard", 1, 2, 4, ["common", "uncommon"], None), ("gold", 1, 30, 80, None, None),
              ("card", 1, 1, 3, cu, None), ("card", 0.5, 1, 0, ["Uncommon", "Rare"], types),
              ("deckCard", 0.5, 1, 1, rm, None), ("shards", 0.4, 1, 2, None, None)],
        "M": [("deckCard", 1, 2, 5, ["common", "uncommon"], None), ("gold", 1, 40, 100, None, None),
              ("card", 1, 2, 3, cu, None), ("card", 0.6, 1, 0, ["Rare"], types),
              ("deckCard", 0.7, 1, 2, rm, None), ("shards", 0.6, 1, 3, None, None)],
        "X": [("deckCard", 1, 3, 5, ["common", "uncommon"], None), ("gold", 1, 60, 140, None, None),
              ("card", 1, 2, 4, cu, None), ("card", 0.75, 1, 0, rm, types),
              ("deckCard", 1, 1, 3, rm, None), ("shards", 1, 2, 4, None, None)],
    }[rank]
    out = []
    for typ, p, count, add, rarity, sub in r:
        d = {"type": typ, "probability": p, "count": count}
        if add:
            d["addMaxCount"] = add
        if rarity:
            d["rarity"] = rarity
        if sub:
            d["subTypes"] = sub
        out.append(d)
    return out


def quest_tags(name, rank, colors, theme, extra, biomes):
    tags = list(THEME_TAGS[theme]) + list(extra)
    if rank in ("A", "D") and theme in MINION_THEMES and "Leader" not in extra:
        tags.append("Minion")
    tags += ["Identity" + WORD[c] for c in colors]
    if len(colors) > 1:
        tags.append("Identity" + GUILD[colors])
    tags += ["Biome" + b.capitalize() for b in biomes]
    seen, out = set(), []
    for t in tags:
        if t not in seen:
            seen.add(t)
            out.append(t)
    return out


def entry(slug, name, rank, colors, theme, extra, biomes):
    life, speed, diff = STATS[rank]
    life += jitter(name, -1, 2)
    speed += jitter(name[::-1], -2, 2)
    if set(extra) & {"Large", "Huge"}:
        life, speed = life + 2, speed - 2
    if "Small" in extra:
        life, speed = life - 1, speed + 1
    stem = stem_of(name)
    e = {"name": name, "sprite": "%s/%s.atlas" % (SPRITE_DIR, stem), "deck": ["%s/%s.dck" % (DECK_DIR, stem)], "ai": ""}
    if "Flying" in extra:
        e["flying"] = True
    e.update({"spawnRate": 1, "difficulty": diff, "speed": speed, "life": life, "rewards": rewards(rank, theme),
              "colors": colors, "questTags": quest_tags(name, rank, colors, theme, extra, biomes), "tier": TIER[rank]})
    return e


def sheet_dir(slug):
    return os.path.join(STAGE, "ro" if slug.startswith("ro_") else "gen", slug)


def add_to_list_block(text, key, names, indent):
    """Append names to a pretty-printed JSON string list "key":  [ ... ] without reformatting the file."""
    m = re.search(r'("%s"\s*:\s*\[)(.*?)(\n[ \t]*\])' % re.escape(key), text, re.S)
    assert m, key
    inner = m.group(2).rstrip()
    add = "".join(',\n%s"%s"' % (indent, n) for n in names)
    return text[:m.start(2)] + inner + add + text[m.end(2):]


def add_to_arena(text, names):
    """Append names to the "arena" property's enemyPool (XML-escaped JSON inside the .tmx)."""
    i = text.index('<property name="arena">')
    m = re.compile(r'(&quot;enemyPool&quot;\s*:\s*\[)(.*?)(\s*\])', re.S).search(text, i)
    assert m
    inner = m.group(2).rstrip()
    add = "".join(",\n\t&quot;%s&quot;" % html.escape(n, quote=False) for n in names)   # names keep a plain '
    return text[:m.start(2)] + inner + add + text[m.end(2):]


def arena_pool(path):
    root = ET.parse(path).getroot()
    for p in root.iter("property"):
        if p.get("name") == "arena":
            return json.loads(p.text if p.text else p.get("value"))["enemyPool"]
    raise SystemExit("no arena property in " + path)


def main():
    ep = os.path.join(PLANE, "world", "enemies.json")
    raw = open(ep, "rb").read()
    E = json.loads(raw.decode("utf-8"))
    if json.dumps(E, indent=4, ensure_ascii=False).encode("utf-8") + b"\n" != raw:
        raise SystemExit("enemies.json does not round-trip through json.dumps(indent=4)")
    have = {e["name"].lower() for e in E}
    clash = [n for _, n, *_ in ROSTER if n.lower() in have]
    if clash:
        raise SystemExit("already imported / name clash: %s" % clash[:5])
    stems = collections.Counter(stem_of(n) for _, n, *_ in ROSTER)
    assert max(stems.values()) == 1, [s for s, k in stems.items() if k > 1]

    # ---- rosters: every color's biome, plus the Wasteland for its restless dead and horrors
    roster_add = collections.defaultdict(list)
    for slug, name, rank, colors, theme, extra in ROSTER:
        for c in wubrg(colors):
            roster_add[BIOME[c]].append(name)
        if theme in WASTELAND_THEMES and rank != "X":
            roster_add["colorless"].append(name)
    biomes_of = collections.defaultdict(list)
    for b, names in roster_add.items():
        for n in names:
            biomes_of[n].append(b)

    # ---- entries
    new = []
    for slug, name, rank, colors, theme, extra in ROSTER:
        cols = wubrg(colors)
        order = [BIOME[c] for c in cols] + (["colorless"] if "colorless" in biomes_of[name] else [])
        new.append(entry(slug, name, rank, cols, theme, extra, order))

    # ---- arenas
    by_name = {n: (slug, n, rank, wubrg(colors), theme, extra) for slug, n, rank, colors, theme, extra in ROSTER}
    def ranked(color, rank):
        c = [r for r in by_name.values() if r[2] == rank and color in r[3]]
        return sorted(c, key=lambda r: (len(r[3]), zlib.crc32(r[1].encode("utf-8"))))
    arena_add = {}
    for path, color in ARENA_CAPITALS.items():
        arena_add[path] = [r[1] for rank in ("M", "X") for r in ranked(color, rank)[:ARENA_CAP[rank]]]
    picked = []
    for color in "WUBRG":
        for rank in ARENA_PLAYER_PER_COLOR:
            r = next(r for r in ranked(color, rank) if r[1] not in picked)
            picked.append(r[1])
    arena_add[ARENA_PLAYER] = picked

    # ---- report
    print("entries: %d  (%s)" % (len(new), dict(collections.Counter(e["tier"] for e in new))))
    for b in ("white", "blue", "black", "red", "green", "colorless"):
        print("  %-9s roster +%d" % (b, len(roster_add[b])))
    for path, names in arena_add.items():
        print("  arena %-40s +%d: %s" % (os.path.basename(path), len(names), ", ".join(names)))
    if DRY:
        print(json.dumps(new[0], indent=2)[:1500])
        return

    # ---- files: sprites, decks
    os.makedirs(os.path.join(PLANE, SPRITE_DIR), exist_ok=True)
    os.makedirs(os.path.join(PLANE, DECK_DIR), exist_ok=True)
    for slug, name, rank, colors, theme, extra in ROSTER:
        stem = stem_of(name)
        src = sheet_dir(slug)
        atlas = open(os.path.join(src, slug + ".atlas"), encoding="utf-8").read().split("\n")
        assert atlas[0].strip() == slug + ".png", (slug, atlas[0])
        atlas[0] = stem + ".png"
        open(os.path.join(PLANE, SPRITE_DIR, stem + ".atlas"), "w", encoding="utf-8", newline="\n").write("\n".join(atlas))
        shutil.copyfile(os.path.join(src, slug + ".png"), os.path.join(PLANE, SPRITE_DIR, stem + ".png"))
        deck = open(os.path.join(STAGE, "decks", slug + ".dck"), encoding="utf-8").read()
        assert "Name=%s\n" % name in deck, slug
        open(os.path.join(PLANE, DECK_DIR, stem + ".dck"), "w", encoding="utf-8", newline="\n").write(deck)

    # ---- enemies.json
    E.extend(new)
    open(ep, "wb").write(json.dumps(E, indent=4, ensure_ascii=False).encode("utf-8") + b"\n")

    # ---- biome rosters (text edit - the files keep their own layout)
    for b, names in roster_add.items():
        bp = os.path.join(PLANE, "world", "biomes", b + ".json")
        rawb = open(bp, "rb").read().decode("utf-8")
        crlf = "\r\n" in rawb
        s = rawb.replace("\r\n", "\n")
        s2 = add_to_list_block(s, "enemies", names, " " * 20)
        json.loads(s2)
        open(bp, "wb").write((s2.replace("\n", "\r\n") if crlf else s2).encode("utf-8"))

    # ---- arenas
    catalog = {e["name"] for e in E}
    for path, names in arena_add.items():
        mp = os.path.join(PLANE, path)
        rawm = open(mp, "rb").read().decode("utf-8")
        crlf = "\r\n" in rawm
        s = rawm.replace("\r\n", "\n")
        before = arena_pool(mp)
        s2 = add_to_arena(s, names)
        open(mp, "wb").write((s2.replace("\n", "\r\n") if crlf else s2).encode("utf-8"))
        after = arena_pool(mp)
        assert after == before + names, path
        missing = [n for n in after if n not in catalog]
        assert not missing, (path, missing)

    # ---- content-filter snapshot, same rows and order as ContentFilterTables.registerEnemies()
    cp = os.path.join(PLANE, "config tables", "enemies.csv")
    old = {r[0].lower(): r for r in csv.reader(io.StringIO(open(cp, encoding="utf-8").read()))}
    out = io.StringIO()
    def line(fields):
        cells = []
        for f in fields:
            f = "" if f is None else str(f)
            if "," in f or '"' in f or "\n" in f:
                f = '"' + f.replace('"', '""') + '"'
            cells.append(f)
        out.write(",".join(cells) + "\n")
    line(["Name", "Colors", "Deck", "Life", "Tier", "Boss", "Difficulty", "Include"])
    rows = collections.OrderedDict()
    for e in E:
        key = e["name"].lower()
        inc = old[key][7] if key in old and len(old[key]) > 7 else "Y"
        deck = re.sub(r".*/", "", e["deck"][0]).replace(".dck", "") if e.get("deck") else ""
        diff = float(e.get("difficulty", 0))
        rows[key] = [e["name"], e.get("colors") or "", deck, int(e.get("life", 0)), e.get("tier") or "",
                     "Y" if e.get("boss") else "N", repr(diff) if diff != int(diff) else "%.1f" % diff,
                     "N" if inc.strip().upper() == "N" else "Y"]
    for r in rows.values():
        line(r)
    open(cp, "w", encoding="utf-8", newline="\n").write(out.getvalue())
    print("written: %d sprites, %d decks, enemies.json +%d, %d rosters, %d arenas, enemies.csv %d rows" % (
        len(ROSTER), len(ROSTER), len(new), len(roster_add), len(arena_add), len(rows)))


if __name__ == "__main__":
    main()
