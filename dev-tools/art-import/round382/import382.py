r"""import382.py - round 382: 56 new enemies (44 ordinary + 12 legends) into The Forsaken Realms plane, and the old
Victor renamed.

usage: python import382.py <repo root> --src <dir> [--dry]
    <repo root>   REQUIRED, no default: the folder that holds forge-gui\ and dev-tools\ (the real one is C:\TFR\repo).
                  Every file is read from there at run time and edited in place.
    --src <dir>   REQUIRED: where atlases\ (56 .atlas/.png pairs) and decks\ (deckgen382.py's 55 decks) are - the
                  staged art lives outside the repo, as round 179's and 318's did.
    --dry         print the plan, write nothing.

What it does (round 318's import316.py is the model; roster382.py is the data):
  sprites/enemy/tfr2/<slug>.atlas + .png   56 atlases (Victor's as victor_vampire)
  decks/standard/tfr2/<slug>.dck           55 generated decks (Victor plays the user's decks/legends/victor_vampire.dck)
  world/enemies.json   * the OLD Victor (the WB cleric, decks/legends/victor.dck) is renamed "Victor, Valgavoth's
                         Seneschal" - its card's name; nothing else in the entry changes
                       * +44 ordinary entries, round 179's field set and order (name, sprite, deck, ai, [flying],
                         spawnRate 1, difficulty, speed, life, rewards, colors, questTags, tier): the rank medians
                         (12/20/0.1, 20/26/1, 24/35/2, 46/45/3) with the name-seeded spread; the house reward template by
                         rank + one card of the deck theme's creature types
                       * +12 LEGENDS (roster382.LEGENDS): spawnRate 0, boss, gamesPerMatch 3, the frontier legends' rank
                         medians (life 30/37/48 - Victor 50 -, speed 39/40/50, difficulty 1/2/3), questTags without a
                         Biome (they belong to no roster), and a legend-grade reward list: the house template of the
                         rank with every entry certain and at the top of its range, the themed card a Rare or Mythic.
                         "boss" keeps them out of the cave champions and the Chest's Illegal Arena bracket (every
                         non-boss Archmage is drawn there) - the 25 roaming champions are bosses too.
                       * Victor = the new vampire: Archmage, Black, the user's deck, noAnte (an ante could hand over
                         its Black Lotus) and NO deck-card reward (a "deckCard" entry draws from his deck and does not
                         consult the restricted list) - its two deckCard entries become ordinary Black "card" entries
  config tables/roaming_champions.json     + the 12 legend names (the legend table - LegendSpawns - is their only route)
  maps/map/evilgrove/Church_of_Valgavoth_1.tmx  the two placements of the old Victor ("enemy" property) renamed
  5 AI capitals + the player capital       the "arena" enemyPool gains ordinary Masters: up to 3 of the capital's color
                                           (mono first), and one per color at the player capital (round 179's picks,
                                           fewer); no legend in any pool
  config tables/enemies.csv                rebuilt the way ContentFilterTables.registerEnemies() writes it (Include
                                           flags kept by name) - checked to reproduce the current file before this run
NOT here: the biome rosters (add_to_biomes.py - another import may be rewriting world/biomes/*.json), CREDITS.md (the
lines are in the round's report), the sizes (set_scales382.py, new entries only).
Idempotent: every step skips what is already there. It REFUSES, before writing anything, on a half-done state: some
but not all 56 names in enemies.json, a target sprite/deck that exists with different content, an enemies.json that
does not round-trip through json.dumps(indent=4), an old Victor that is not the cleric this script expects."""
import collections, csv, html, io, json, os, re, sys, zlib
import xml.etree.ElementTree as ET

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
sys.dont_write_bytecode = True
from roster382 import ROSTER, LEGENDS, ARTIFACT, VICTOR_DECK, OLD_VICTOR, OLD_VICTOR_NEW_NAME

SPRITE_DIR = "sprites/enemy/tfr2"
DECK_DIR = "decks/standard/tfr2"
TIER = {"A": "Common", "D": "Uncommon", "M": "Rare", "X": "Mythic"}
WORD = {"W": "White", "U": "Blue", "B": "Black", "R": "Red", "G": "Green", "C": "Colorless"}
BIOME = {"W": "white", "U": "blue", "B": "black", "R": "red", "G": "green"}
GUILD = {"WU": "Azorius", "UB": "Dimir", "BR": "Rakdos", "RG": "Gruul", "WG": "Selesnya", "WB": "Orzhov",
         "UR": "Izzet", "BG": "Golgari", "WR": "Boros", "UG": "Simic"}
# import179.THEME_TAGS for the themes this round uses, plus the new ones - every tag from the existing vocabulary
THEME_TAGS = {
    "angel": ["Angel", "Celestial"], "dragon": ["Dragon", "Mythical"], "knight": ["Knight", "Humanoid"],
    "elemental": ["Elemental"], "giant": ["Giant"], "griffin": ["Beast", "Mythical"], "soldier": ["Soldier", "Humanoid"],
    "cleric": ["Cleric", "Religious", "Humanoid"], "merfolk": ["Merfolk", "Humanoid"], "wizard": ["Wizard", "Humanoid"],
    "demon": ["Demon", "Evil"], "devil": ["Devil", "Evil"], "sea": ["Water", "Animal"],
    "vampire": ["Vampire", "Undead", "Unholy"], "knight_undead": ["Knight", "Undead", "Unholy"],
    "spider": ["Spider", "Predator"], "horror": ["Horror", "Monster", "Aberration"], "gorgon": ["Monster", "Mythical"],
    "wraith": ["Spirit", "Undead"], "bird": ["Bird", "Animal"], "beast": ["Beast", "Animal"], "ogre": ["Giant", "Humanoid"],
    "treefolk": ["Treefolk", "Nature"], "construct": ["Construct", "Artifact"],
    # round 382
    "unicorn": ["Beast", "Mythical"], "pegasus": ["Beast", "Mythical"], "centaur": ["Humanoid", "Wild"],
    "djinn": ["Elemental", "Mythical"], "naga": ["Snake", "Humanoid"], "lich": ["Undead", "Unholy", "Necromancer"],
    "gremlin": ["Artificer", "Humanoid"], "troglodyte": ["Subterranean", "Humanoid", "Inhuman"],
    "hellhound": ["Beast", "Predator", "Evil"], "gorgon_bull": ["Beast", "Mythical"],
    "basilisk": ["Lizard", "Monster", "Mythical"], "lizardfolk": ["Lizard", "Humanoid"],
    "gnoll": ["Humanoid", "Wild", "Tribal"], "bone_dragon": ["Dragon", "Undead", "Mythical"],
    "bone_scorpion": ["Undead", "Monster"],
}
MINION_THEMES = {"skeleton", "zombie", "orc", "soldier", "pirate", "merfolk"}
WASTELAND_THEMES = {"skeleton", "zombie", "mummy", "horror", "construct", "wraith", "shade", "knight_undead", "lich",
                    "bone_scorpion"}
STATS = {"A": (12, 20, 0.1), "D": (20, 26, 1), "M": (24, 35, 2), "X": (46, 45, 3)}
LEGEND_STATS = {"D": (30, 39, 1), "M": (37, 40, 2), "X": (48, 50, 3)}     # the frontier legends' medians by tier
VICTOR_LIFE = 50
ARENA_CAPITALS = {"maps/map/main_story/plains_capital.tmx": "W", "maps/map/main_story/island_capital.tmx": "U",
                  "maps/map/main_story/swamp_capital.tmx": "B", "maps/map/main_story/mountain_capital.tmx": "R",
                  "maps/map/main_story/forest_capital.tmx": "G"}
ARENA_PLAYER = "maps/map/towns/player_capital.tmx"
ARENA_PER_CAPITAL = 3
VICTOR_TMX = "maps/map/evilgrove/Church_of_Valgavoth_1.tmx"
OLD_VICTOR_SPRITE = "sprites/enemy/humanoid/human/cleric/corrupted_cleric.atlas"
OLD_VICTOR_DECK = "decks/legends/victor.dck"


def deck_themes():
    """deckgen382.THEMES -> {theme: primary types}, read with ast (importing deckgen382 would load the card cache)."""
    import ast
    tree = ast.parse(open(os.path.join(HERE, "deckgen382.py"), encoding="utf-8").read())
    for node in tree.body:
        if isinstance(node, ast.Assign) and any(getattr(t, "id", None) == "THEMES" for t in node.targets):
            return {k: v[0] for k, v in ast.literal_eval(node.value).items()}
    raise SystemExit("THEMES not found in deckgen382.py")


PRIMARY = deck_themes()
PRIMARY_VAMPIRE = ["Vampire"]


def stem_of(name):
    return re.sub(r"[^a-z0-9]+", "_", name.lower()).strip("_")


def jitter(name, lo, hi):
    return lo + zlib.crc32(name.encode("utf-8")) % (hi - lo + 1)


def order(cols):
    return "".join(c for c in "WUBRGC" if c in cols)


def template(rank, types):
    """import179.rewards(): the house template by rank plus one card of the theme's own creature types."""
    cu = ["Common", "Uncommon"]
    rm = ["Rare", "Mythic Rare"]
    return {
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


def as_dicts(rows):
    out = []
    for typ, p, count, add, rarity, sub in rows:
        d = {"type": typ, "probability": p, "count": count}
        if add:
            d["addMaxCount"] = add
        if rarity:
            d["rarity"] = rarity
        if sub:
            d["subTypes"] = sub
        out.append(d)
    return out


def rewards(rank, types):
    return as_dicts(template(rank, types))


def legend_rewards(rank, types, colors, victor=False):
    """The house template of the rank, every entry certain and at the top of its range; the themed card a Rare or
    Mythic. Victor's deck holds Black Lotus and a deckCard entry would draw from it (no restricted-list check on that
    path), so his deckCard entries become ordinary "card" entries of his color (that pool IS restricted-filtered)."""
    out = []
    for typ, p, count, add, rarity, sub in template(rank, types):
        if sub:
            rarity = ["Rare", "Mythic Rare"]
        if victor and typ == "deckCard":
            typ = "card"
            rarity = ["Common", "Uncommon"] if rarity and rarity[0].lower() == "common" else ["Rare", "Mythic Rare"]
        d = {"type": typ, "probability": 1, "count": count + add}
        if rarity:
            d["rarity"] = rarity
        if sub:
            d["subTypes"] = sub
        if victor and typ == "card" and not sub:
            d["colors"] = [WORD[c] for c in colors]
        out.append(d)
    return out


def quest_tags(rank, colors, tag_theme, extra, biomes, legend):
    tags = list(THEME_TAGS[tag_theme]) + list(extra)
    if not legend and rank in ("A", "D") and tag_theme in MINION_THEMES and "Leader" not in extra:
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


def entry(slug, name, rank, colors, theme, tag_theme, extra, biomes):
    colors = order(colors)
    legend = slug in LEGENDS
    victor = theme is None
    types = PRIMARY_VAMPIRE if victor else PRIMARY[theme]
    if legend:
        life, speed, diff = LEGEND_STATS[rank]
        life = VICTOR_LIFE if victor else life + jitter(name, -1, 2)
        speed += jitter(name[::-1], -2, 2)
    else:
        life, speed, diff = STATS[rank]
        life += jitter(name, -1, 2)
        speed += jitter(name[::-1], -2, 2)
        if set(extra) & {"Large", "Huge"}:
            life, speed = life + 2, speed - 2
        if "Small" in extra:
            life, speed = life - 1, speed + 1
    deck = VICTOR_DECK if victor else "%s/%s.dck" % (DECK_DIR, slug)
    e = {"name": name, "sprite": "%s/%s.atlas" % (SPRITE_DIR, slug), "deck": [deck], "ai": ""}
    if "Flying" in extra:
        e["flying"] = True
    if legend:
        e["boss"] = True
    e.update({"spawnRate": 0 if legend else 1, "difficulty": diff, "speed": speed, "life": life})
    if legend:
        e["gamesPerMatch"] = 3
    if victor:
        e["noAnte"] = True
    e.update({"rewards": legend_rewards(rank, types, colors, victor) if legend else rewards(rank, types),
              "colors": colors, "questTags": quest_tags(rank, colors, tag_theme, extra, biomes, legend),
              "tier": TIER[rank]})
    return e


def read_text(path):
    raw = open(path, "rb").read()
    text = raw.decode("utf-8")
    return text.replace("\r\n", "\n"), "\r\n" in text


def encode(text, crlf):
    return (text.replace("\n", "\r\n") if crlf else text).encode("utf-8")


def arena_pool(text):
    root = ET.fromstring(text.encode("utf-8"))
    for p in root.iter("property"):
        if p.get("name") == "arena":
            return json.loads(p.text if p.text else p.get("value"))["enemyPool"]
    raise SystemExit("no arena property")


def add_to_arena(text, names):
    """import179: append names to the "arena" property's enemyPool (XML-escaped JSON inside the .tmx)."""
    i = text.index('<property name="arena">')
    m = re.compile(r'(&quot;enemyPool&quot;\s*:\s*\[)(.*?)(\s*\])', re.S).search(text, i)
    assert m
    inner = m.group(2).rstrip()
    add = "".join(",\n\t&quot;%s&quot;" % html.escape(n, quote=False) for n in names)
    return text[:m.start(2)] + inner + add + text[m.end(2):]


def csv_rows(E, old):
    """ContentFilterTables.registerEnemies(): Name,Colors,Deck,Life,Tier,Boss,Difficulty,Include (Include kept)."""
    rows = collections.OrderedDict()
    for e in E:
        key = e["name"].lower()
        inc = old[key][7] if key in old and len(old[key]) > 7 else "Y"
        deck = re.sub(r".*/", "", e["deck"][0]).replace(".dck", "") if e.get("deck") else ""
        diff = float(e.get("difficulty", 0))
        rows[key] = [e["name"], e.get("colors") or "", deck, int(e.get("life", 0)), e.get("tier") or "",
                     "Y" if e.get("boss") else "N", repr(diff) if diff != int(diff) else "%.1f" % diff,
                     "N" if inc.strip().upper() == "N" else "Y"]
    return rows


def csv_text(rows, header=True):
    out = io.StringIO()

    def line(fields):
        cells = []
        for f in fields:
            f = "" if f is None else str(f)
            if "," in f or '"' in f or "\n" in f:
                f = '"' + f.replace('"', '""') + '"'
            cells.append(f)
        out.write(",".join(cells) + "\n")
    if header:
        line(["Name", "Colors", "Deck", "Life", "Tier", "Boss", "Difficulty", "Include"])
    for r in rows:
        line(r)
    return out.getvalue()


def roster_additions():
    """{biome: [names]} - every ordinary enemy in the roster of each of its colors; the Wasteland's takes the colorless
    ones only (the user, round 382: "3, colorless only" - round 179's rule, which also sent undead, horrors and
    constructs below Archmage there, is not applied this round). Legends: none."""
    add = collections.OrderedDict((b, []) for b in ("white", "blue", "black", "red", "green", "colorless"))
    for slug, name, rank, colors, theme, tag_theme, extra in ROSTER:
        if slug in LEGENDS:
            continue
        for c in order(colors):
            if c in BIOME:
                add[BIOME[c]].append(name)
        if colors == "C":
            add["colorless"].append(name)
    return add


def main():
    argv = sys.argv[1:]
    src = None
    if "--src" in argv:
        i = argv.index("--src")
        src = argv[i + 1]
        del argv[i:i + 2]
    args = [a for a in argv if not a.startswith("--")]
    if len(args) != 1 or not src:
        raise SystemExit(__doc__.split("\n\n")[0] + "\n\nThe repo root and --src are required (there are no defaults).")
    root = os.path.abspath(args[0])
    dry = "--dry" in sys.argv
    plane = os.path.join(root, "forge-gui", "res", "adventure", "The Forsaken Realms")
    if not os.path.isfile(os.path.join(plane, "world", "enemies.json")):
        raise SystemExit("not a TFR repo root (missing the plane's world/enemies.json)")
    writes = collections.OrderedDict()
    report = []

    # ------------------------------------------------------------------ inputs: 56 atlases, 55 decks
    copies = []
    for slug, name, rank, colors, theme, tag_theme, extra in ROSTER:
        if slug != "victor_vampire":
            assert slug == stem_of(name), (slug, name)
        for ext in (".atlas", ".png"):
            s = os.path.join(src, "atlases", slug + ext)
            if not os.path.isfile(s):
                raise SystemExit("missing input %s" % s)
            copies.append((s, os.path.join(plane, SPRITE_DIR, slug + ext)))
        head = open(os.path.join(src, "atlases", slug + ".atlas"), encoding="utf-8").readline().strip()
        assert head == slug + ".png", (slug, head)
        if theme is None:
            if not os.path.isfile(os.path.join(plane, VICTOR_DECK)):
                raise SystemExit("REFUSED: Victor's deck %s is not in the plane" % VICTOR_DECK)
            continue
        s = os.path.join(src, "decks", slug + ".dck")
        if not os.path.isfile(s):
            raise SystemExit("missing input %s - run deckgen382.py" % s)
        assert "Name=%s\n" % name in open(s, encoding="utf-8").read(), slug
        copies.append((s, os.path.join(plane, DECK_DIR, slug + ".dck")))
    n_new = n_same = 0
    for s, dst in copies:
        data = open(s, "rb").read()
        if os.path.exists(dst):
            if open(dst, "rb").read() != data:
                raise SystemExit("REFUSED: %s exists with different content" % dst)
            n_same += 1
            continue
        writes[dst] = data
        n_new += 1
    report.append("files: %d to copy into %s and %s, %d already there byte-identical" % (n_new, SPRITE_DIR, DECK_DIR,
                                                                                        n_same))

    # ------------------------------------------------------------------ enemies.json
    ep = os.path.join(plane, "world", "enemies.json")
    raw = open(ep, "rb").read()
    E = json.loads(raw.decode("utf-8"))
    if json.dumps(E, indent=4, ensure_ascii=False).encode("utf-8") + b"\n" != raw:
        raise SystemExit("REFUSED: enemies.json does not round-trip through json.dumps(indent=4) - edit by hand")
    changed_json = False
    # the old Victor -> "Victor, Valgavoth's Seneschal" (identified by its sprite and deck, never by name alone)
    olds = [e for e in E if e.get("name") == OLD_VICTOR and e.get("sprite") == OLD_VICTOR_SPRITE]
    renamed = [e for e in E if e.get("name") == OLD_VICTOR_NEW_NAME]
    if olds:
        if len(olds) != 1 or (olds[0].get("deck") or [None])[0] != OLD_VICTOR_DECK or renamed:
            raise SystemExit("REFUSED: the old Victor is not the single WB cleric this script expects")
        olds[0]["name"] = OLD_VICTOR_NEW_NAME
        changed_json = True
        report.append("enemies.json: the old %r (the cleric, %s) renamed %r" % (OLD_VICTOR, OLD_VICTOR_DECK,
                                                                               OLD_VICTOR_NEW_NAME))
    elif len(renamed) == 1:
        report.append("enemies.json: the old Victor is already %r - skipped" % OLD_VICTOR_NEW_NAME)
    else:
        raise SystemExit("REFUSED: neither the old Victor nor %r found" % OLD_VICTOR_NEW_NAME)
    adds = roster_additions()
    biomes_of = collections.defaultdict(list)
    for b, names in adds.items():
        for n in names:
            biomes_of[n].append(b)
    have = {e["name"]: e for e in E}
    present = [n for _, n, *_ in ROSTER if n in have and (n != OLD_VICTOR or have[n].get("deck") == [VICTOR_DECK])]
    if len(present) == len(ROSTER):
        report.append("enemies.json: the 56 entries are already there - skipped")
    elif present:
        raise SystemExit("REFUSED: %d of the 56 names are already in enemies.json (a partial import?): %s" % (
            len(present), present))
    else:
        clash = [n for _, n, *_ in ROSTER if n in have]
        if clash:
            raise SystemExit("REFUSED: names already taken: %s" % clash)
        new = [entry(*r, biomes_of[r[1]]) for r in ROSTER]
        E.extend(new)
        changed_json = True
        report.append("enemies.json: +%d entries (%s), %d legends, %d flying" % (
            len(new), ", ".join("%d %s" % (k, v) for v, k in collections.Counter(e["tier"] for e in new).items()),
            sum(1 for e in new if e.get("boss")), sum(1 for e in new if e.get("flying"))))
    if changed_json:
        writes[ep] = json.dumps(E, indent=4, ensure_ascii=False).encode("utf-8") + b"\n"
    catalog = {e["name"]: e for e in E}

    # ------------------------------------------------------------------ the old Victor's map placements
    mp = os.path.join(plane, VICTOR_TMX)
    text, crlf = read_text(mp)
    old_prop = '<property name="enemy" value="%s"/>' % OLD_VICTOR
    new_prop = '<property name="enemy" value="%s"/>' % html.escape(OLD_VICTOR_NEW_NAME, quote=True).replace("&#x27;", "'")
    n_old = text.count(old_prop)
    if n_old:
        text2 = text.replace(old_prop, new_prop)
        ET.fromstring(text2.encode("utf-8"))
        writes[mp] = encode(text2, crlf)
        report.append("%s: %d placement(s) of %r renamed" % (VICTOR_TMX, n_old, OLD_VICTOR))
    else:
        report.append("%s: no placement of %r left (%d of the new name) - skipped" % (VICTOR_TMX, OLD_VICTOR,
                                                                                    text.count(new_prop)))

    # ------------------------------------------------------------------ roaming_champions.json (the legend table)
    rp = os.path.join(plane, "config tables", "roaming_champions.json")
    text, crlf = read_text(rp)
    m = re.search(r'("names"\s*:\s*\[)(.*?)(\n[ \t]*\])', text, re.S)
    assert m, "roaming_champions.json names"
    cur = re.findall(r'"((?:[^"\\]|\\.)*)"', m.group(2))
    legend_names = [r[1] for r in ROSTER if r[0] in LEGENDS]
    add = [n for n in legend_names if n not in cur]
    if add:
        rows, line = [], []
        for n in add:
            if line and len("    " + ", ".join(line + [json.dumps(n)])) > 116:
                rows.append(line)
                line = []
            line.append(json.dumps(n, ensure_ascii=False))
        rows.append(line)
        inner = m.group(2).rstrip() + "".join(",\n    " + ", ".join(r) for r in rows)
        text2 = text[:m.start(2)] + inner + text[m.end(2):]
        note = ("  // Round 382: the twelve new legends (the art-import round) - spawnRate 0, best of three, no roster and\n"
                "  // no arena; this list is their only route.\n")
        if "Round 382" not in text2:
            k = text2.index('  "names"')
            text2 = text2[:k] + note + text2[k:]
        writes[rp] = encode(text2, crlf)
        report.append("roaming_champions.json: +%d names (%d -> %d)" % (len(add), len(cur), len(cur) + len(add)))
    else:
        report.append("roaming_champions.json: the 12 legends are already named - skipped")

    # ------------------------------------------------------------------ arenas: a few ordinary Masters
    masters = [(name, order(colors)) for slug, name, rank, colors, *_ in ROSTER if rank == "M" and slug not in LEGENDS]

    def ranked(color):
        c = [r for r in masters if color in r[1]]
        return sorted(c, key=lambda r: (len(r[1]), zlib.crc32(r[0].encode("utf-8"))))
    arena_add = collections.OrderedDict()
    for path, color in ARENA_CAPITALS.items():
        arena_add[path] = [r[0] for r in ranked(color)[:ARENA_PER_CAPITAL]]
    picked = []
    for color in "WUBRG":
        r = next((r for r in ranked(color) if r[0] not in picked), None)
        if r:
            picked.append(r[0])
    arena_add[ARENA_PLAYER] = picked
    for path, names in arena_add.items():
        mp = os.path.join(plane, path)
        text, crlf = read_text(mp)
        before = arena_pool(text)
        add = [n for n in names if n not in before]
        if not add:
            report.append("arena %-20s all %d already in the pool - skipped" % (os.path.basename(path), len(names)))
            continue
        text2 = add_to_arena(text, add)
        after = arena_pool(text2)
        assert after == before + add, path
        missing = [n for n in after if n not in catalog]
        assert not missing, (path, missing)
        writes[mp] = encode(text2, crlf)
        report.append("arena %-20s +%d (pool %d -> %d): %s" % (os.path.basename(path), len(add), len(before),
                                                              len(after), ", ".join(add)))

    # ------------------------------------------------------------------ enemies.csv (content-filter snapshot)
    cp = os.path.join(plane, "config tables", "enemies.csv")
    cur_text, crlf = read_text(cp)
    old = {r[0].lower(): r for r in csv.reader(io.StringIO(cur_text)) if r}
    new_text = csv_text(csv_rows(E, old).values())
    if new_text == cur_text:
        report.append("enemies.csv: already the rebuild of enemies.json - skipped")
    else:
        writes[cp] = encode(new_text, crlf)
        report.append("enemies.csv: rebuilt (%d -> %d rows)" % (cur_text.count("\n") - 1, new_text.count("\n") - 1))

    # ------------------------------------------------------------------ report, write
    print("repo root: %s%s" % (root, "   (DRY RUN - nothing written)" if dry else ""))
    for line in report:
        print("  " + line)
    print("  biome rosters (NOT written here - add_to_biomes.py): " + ", ".join(
        "%s +%d" % (b, len(n)) for b, n in adds.items()))
    if not writes:
        print("nothing to do - round 382 is already imported")
        return 0
    if dry:
        print("would write %d file(s)" % len(writes))
        return 0
    for path, data in writes.items():
        os.makedirs(os.path.dirname(path), exist_ok=True)
        with open(path, "wb") as fh:
            fh.write(data)
    print("written: %d file(s):" % len(writes))
    for path in writes:
        if not path.endswith((".png", ".atlas", ".dck")):
            print("    " + os.path.relpath(path, root))
    print("next: set_scales382.py (the new entries' sizes), add_to_biomes.py (the rosters), verify382.py")
    return 0


if __name__ == "__main__":
    sys.exit(main())
