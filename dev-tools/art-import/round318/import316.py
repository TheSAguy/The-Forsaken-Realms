r"""import316.py - round 316: 43 new enemies and 4 re-skins into The Forsaken Realms plane.

usage: python import316.py <repo root> [--dry] [--src <dir>]
    <repo root>   REQUIRED, no default: the folder that holds forge-gui\ and standalone-packaging\ (the real one is
                  C:\TFR\repo). Every file is read from there at run time and edited in place - nothing is copied
                  over from an older snapshot.
    --dry         print the plan, write nothing.
    --src <dir>   where atlases\ and decks\ are (default: this script's folder) - for when the scripts move into
                  dev-tools/art-import and the staged files stay outside the repo, as round 179's did.

What it does (the round-179 import, import179.py, is the model):
  sprites/enemy/tfr/<stem>.atlas + .png     47 atlases from atlases\ (43 new enemies + the 4 re-skin atlases)
  decks/standard/tfr/<stem>.dck             43 themed decks from decks\ (deckgen316.py, deck_audit316.py clean)
  world/enemies.json                        43 entries appended (round 179's field set and order: name, sprite, deck,
                                            ai, [flying], spawnRate, difficulty, speed, life, rewards, colors,
                                            questTags, tier); tier stats with round 179's name-seeded spread; the
                                            behemoths speed 0; RE-SKINS: Jugan, Yosei, Ryusei, Keiga get only a new
                                            "sprite" (every entry of that name - there is one each today)
  world/biomes/<color>.json "enemies"       every new enemy in the roster of each of its colors; the undead, horror
                                            and construct ones below Archmage (and so the colorless automatons) also
                                            in the Wasteland's (colorless.json) - round 179's rule
  5 AI capitals + the player capital        the "arena" enemyPool gains the new Masters and Archmages of the capital's
                                            color (up to 8 / 5, mono first); the player capital one new Adept, Master
                                            and Archmage per color - round 179's rule
  config tables/enemies.csv                 the new rows, the way ContentFilterTables.registerEnemies() writes them
  standalone-packaging/CREDITS.md           one line under "Enemy art packs"
Idempotent: every step skips what is already there (a name already in a roster or a pool, a sprite path already
set, a file already copied byte-identical). It REFUSES, before writing anything, on a half-done state it cannot
reason about: some but not all 43 names in enemies.json, a target sprite/deck file that exists with different
content, a re-skin name with no entry.
Afterwards (from the repo root):
    python dev-tools/enemy_scale.py --write          the sizes (the new entries have no "scale" until then). NOTE: it
                                                     also re-sizes 12 EXISTING enemies whose sheets round 218 cleaned
                                                     without re-measuring them (all grow back toward their rank size) -
                                                     see summary.txt
    python dev-tools/validate_plane_data.py "forge-gui/res/adventure/The Forsaken Realms" report.txt dev-tools/validate_plane_data_stage_fields.txt
Do NOT run dev-tools/gen_caves.py --no-register (round 179's last step): it regenerates all 78 caves from scratch
and would wipe the booster/chest guards and patrol repairs rounds 257-258 and 286 made inside them (tested: it
rewrites every cave even on an untouched plane). The new enemies simply are not cave roamers this round."""
import ast, collections, csv, html, io, json, os, re, sys, zlib
import xml.etree.ElementTree as ET

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
sys.dont_write_bytecode = True
from roster316 import ROSTER, RESKINS, STATIONARY

SPRITE_DIR = "sprites/enemy/tfr"
DECK_DIR = "decks/standard/tfr"
SRC = sys.argv[sys.argv.index("--src") + 1] if "--src" in sys.argv else HERE
ATLAS_SRC = os.path.join(SRC, "atlases")
DECK_SRC = os.path.join(SRC, "decks")

TIER = {"A": "Common", "D": "Uncommon", "M": "Rare", "X": "Mythic"}
WORD = {"W": "White", "U": "Blue", "B": "Black", "R": "Red", "G": "Green", "C": "Colorless"}
BIOME = {"W": "white", "U": "blue", "B": "black", "R": "red", "G": "green"}
GUILD = {"WU": "Azorius", "UB": "Dimir", "BR": "Rakdos", "RG": "Gruul", "WG": "Selesnya", "WB": "Orzhov",
         "UR": "Izzet", "BG": "Golgari", "WR": "Boros", "UG": "Simic"}
# import179.THEME_TAGS for the themes this round uses, plus the new ones (the vocabulary quests ask for)
THEME_TAGS = {
    "dragon": ["Dragon", "Mythical"], "drake": ["Dragon"], "construct": ["Construct", "Artifact"],
    "horror": ["Horror", "Monster", "Aberration"], "wraith": ["Spirit", "Undead"], "zombie": ["Zombie", "Undead", "Unholy"],
    "bat": ["Animal", "Nocturnal"],
    # round 316
    "whelp": ["Dragon"], "faerie_dragon": ["Faerie", "Dragon"], "behemoth": ["Beast", "Monster"],
    "werewolf": ["Werewolf", "Beast", "Predator"], "werewolf_white": ["Werewolf", "Beast", "Predator"],
    "kirin": ["Spirit", "Mythical"], "bat_fire": ["Animal", "Nocturnal"], "bat_frost": ["Animal", "Nocturnal"],
}
MINION_THEMES = {"skeleton", "zombie", "orc", "soldier", "pirate", "merfolk"}
WASTELAND_THEMES = {"skeleton", "zombie", "mummy", "horror", "construct", "wraith", "shade", "knight_undead"}
STATS = {"A": (12, 20, 0.1), "D": (20, 26, 1), "M": (24, 35, 2), "X": (46, 45, 3)}
ARENA_CAPITALS = {"maps/map/main_story/plains_capital.tmx": "W", "maps/map/main_story/island_capital.tmx": "U",
                  "maps/map/main_story/swamp_capital.tmx": "B", "maps/map/main_story/mountain_capital.tmx": "R",
                  "maps/map/main_story/forest_capital.tmx": "G"}
ARENA_PLAYER = "maps/map/towns/player_capital.tmx"
ARENA_CAP = {"M": 8, "X": 5}
ARENA_PLAYER_PER_COLOR = ("D", "M", "X")
CREDITS_MARK = "**RPG Maker MV/MZ creature sheets**"
CREDITS_LINE = ("- **RPG Maker MV/MZ creature sheets** (43 enemies - the whelps, dragonets and wyverns, the land-backed\n"
                "  behemoths, werewolves, kirin, fanged bats, stone golems and automatons, winged-eye horrors, reapers and\n"
                "  zombie beasts - and the coiled eastern dragons Jugan, Yosei, Ryusei and Keiga wear) - supplied by the\n"
                "  user; pack author not recorded.\n")
CREDITS_AFTER = "## Enemy art packs"


def themes_primary():
    """deckgen316.THEMES -> {theme: primary creature types}, read with ast (importing deckgen316 would load the
    10 MB card cache for nothing)."""
    tree = ast.parse(open(os.path.join(HERE, "deckgen316.py"), encoding="utf-8").read())
    for node in tree.body:
        if isinstance(node, ast.Assign) and any(getattr(t, "id", None) == "THEMES" for t in node.targets):
            return {k: v[0] for k, v in ast.literal_eval(node.value).items()}
    raise SystemExit("THEMES not found in deckgen316.py")


PRIMARY = themes_primary()


def stem_of(name):
    return re.sub(r"[^a-z0-9]+", "_", name.lower()).strip("_")


def jitter(name, lo, hi):
    return lo + zlib.crc32(name.encode("utf-8")) % (hi - lo + 1)


def order(cols):
    return "".join(c for c in "WUBRGC" if c in cols)


def rewards(rank, theme):
    """import179.rewards(): the house template by rank plus one card of the theme's own creature types."""
    types = PRIMARY[theme]
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


def quest_tags(rank, colors, theme, extra, biomes):
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


def entry(name, rank, colors, theme, extra, biomes):
    life, speed, diff = STATS[rank]
    life += jitter(name, -1, 2)
    speed += jitter(name[::-1], -2, 2)
    if set(extra) & {"Large", "Huge"}:
        life, speed = life + 2, speed - 2
    if "Small" in extra:
        life, speed = life - 1, speed + 1
    if name in STATIONARY:
        speed = 0                     # the behemoths rest like the mountains they carry (see summary.txt)
    stem = stem_of(name)
    e = {"name": name, "sprite": "%s/%s.atlas" % (SPRITE_DIR, stem), "deck": ["%s/%s.dck" % (DECK_DIR, stem)], "ai": ""}
    if "Flying" in extra:
        e["flying"] = True
    e.update({"spawnRate": 1, "difficulty": diff, "speed": speed, "life": life, "rewards": rewards(rank, theme),
              "colors": colors, "questTags": quest_tags(rank, colors, theme, extra, biomes), "tier": TIER[rank]})
    return e


def read_text(path):
    raw = open(path, "rb").read()
    text = raw.decode("utf-8")
    return text.replace("\r\n", "\n"), "\r\n" in text


def encode(text, crlf):
    return (text.replace("\n", "\r\n") if crlf else text).encode("utf-8")


def list_block(text, key):
    m = re.search(r'("%s"\s*:\s*\[)(.*?)(\n[ \t]*\])' % re.escape(key), text, re.S)
    assert m, key
    return m, re.findall(r'"((?:[^"\\]|\\.)*)"', m.group(2))


def add_to_list_block(text, key, names, indent):
    """import179: append names to a pretty-printed JSON string list without reformatting the file."""
    m, _ = list_block(text, key)
    inner = m.group(2).rstrip()
    add = "".join(',\n%s%s' % (indent, json.dumps(n, ensure_ascii=False)) for n in names)
    return text[:m.start(2)] + inner + add + text[m.end(2):]


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


def main():
    argv = sys.argv[1:]
    if "--src" in argv:
        i = argv.index("--src")
        del argv[i:i + 2]
    args = [a for a in argv if not a.startswith("--")]
    if len(args) != 1:
        raise SystemExit(__doc__.split("\n\n")[0] + "\n\nThe repo root is required (there is no default).")
    root = os.path.abspath(args[0])
    dry = "--dry" in sys.argv
    plane = os.path.join(root, "forge-gui", "res", "adventure", "The Forsaken Realms")
    for need in (os.path.join(plane, "world", "enemies.json"), os.path.join(root, "standalone-packaging", "CREDITS.md")):
        if not os.path.isfile(need):
            raise SystemExit("not a TFR repo root (missing %s)" % need)
    writes = collections.OrderedDict()           # path -> bytes, all computed before anything is written
    report = []

    # ------------------------------------------------------------------ inputs: 47 atlases, 43 decks, from this folder
    stems = [stem_of(n) for _, n, *_ in ROSTER]
    assert stems == [r[0] for r in ROSTER], "roster slugs must be the name stems"
    atlas_stems = stems + [s for _, s, _, _ in RESKINS]
    copies = []
    for s in atlas_stems:
        for ext in (".atlas", ".png"):
            src = os.path.join(ATLAS_SRC, s + ext)
            if not os.path.isfile(src):
                raise SystemExit("missing input %s - run build_atlases.py" % src)
            copies.append((src, os.path.join(plane, SPRITE_DIR, s + ext)))
        head = open(os.path.join(ATLAS_SRC, s + ".atlas"), encoding="utf-8").readline().strip()
        assert head == s + ".png", (s, head)
    for (_, name, *_), s in zip(ROSTER, stems):
        src = os.path.join(DECK_SRC, s + ".dck")
        if not os.path.isfile(src):
            raise SystemExit("missing input %s - run deckgen316.py" % src)
        assert "Name=%s\n" % name in open(src, encoding="utf-8").read(), s
        copies.append((src, os.path.join(plane, DECK_DIR, s + ".dck")))
    n_new_files = n_same = 0
    for src, dst in copies:
        data = open(src, "rb").read()
        if os.path.exists(dst):
            if open(dst, "rb").read() != data:
                raise SystemExit("REFUSED: %s exists with different content (a previous import changed since?)" % dst)
            n_same += 1
            continue
        writes[dst] = data
        n_new_files += 1
    report.append("files: %d to copy into %s and %s, %d already there byte-identical" % (
        n_new_files, SPRITE_DIR, DECK_DIR, n_same))

    # ------------------------------------------------------------------ rosters (who goes where) - round 179's rule
    roster_add = collections.defaultdict(list)
    for slug, name, rank, colors, theme, extra in ROSTER:
        for c in order(colors):
            if c in BIOME:
                roster_add[BIOME[c]].append(name)
        if (theme in WASTELAND_THEMES and rank != "X") or colors == "C":
            roster_add["colorless"].append(name)
    biomes_of = collections.defaultdict(list)
    for b in ("white", "blue", "black", "red", "green", "colorless"):
        for n in roster_add[b]:
            biomes_of[n].append(b)

    # ------------------------------------------------------------------ enemies.json
    ep = os.path.join(plane, "world", "enemies.json")
    raw = open(ep, "rb").read()
    E = json.loads(raw.decode("utf-8"))
    if json.dumps(E, indent=4, ensure_ascii=False).encode("utf-8") + b"\n" != raw:
        raise SystemExit("REFUSED: enemies.json does not round-trip through json.dumps(indent=4) - edit by hand")
    have = {e["name"].lower() for e in E} | {(e.get("nameOverride") or "").lower() for e in E if e.get("nameOverride")}
    present = [n for _, n, *_ in ROSTER if n.lower() in have]
    changed_json = False
    if len(present) == len(ROSTER):
        report.append("enemies.json: the 43 entries are already there - skipped")
    elif present:
        raise SystemExit("REFUSED: %d of the 43 names are already in enemies.json (a partial import?): %s" % (
            len(present), present))
    else:
        new = [entry(name, rank, order(colors), theme, extra, biomes_of[name])
               for slug, name, rank, colors, theme, extra in ROSTER]
        E.extend(new)
        changed_json = True
        report.append("enemies.json: +%d entries (%s), %d flying, %d with speed 0" % (
            len(new), ", ".join("%d %s" % (k, v) for v, k in collections.Counter(e["tier"] for e in new).items()),
            sum(1 for e in new if e.get("flying")), sum(1 for e in new if e["speed"] == 0)))
    for name, s, sheet, row in RESKINS:
        hits = [e for e in E if e.get("name") == name]
        if not hits:
            raise SystemExit("REFUSED: re-skin target %r not in enemies.json" % name)
        want = "%s/%s.atlas" % (SPRITE_DIR, s)
        for e in hits:
            if e.get("sprite") == want:
                report.append("re-skin %s: already %s - skipped" % (name, want))
            else:
                report.append("re-skin %s: sprite %s -> %s (tier %s, deck %s unchanged)" % (
                    name, e.get("sprite"), want, e.get("tier"), e.get("deck")))
                e["sprite"] = want
                changed_json = True
    if changed_json:
        writes[ep] = json.dumps(E, indent=4, ensure_ascii=False).encode("utf-8") + b"\n"
    catalog = {e["name"]: e for e in E}

    # ------------------------------------------------------------------ biome rosters (text edit, the files keep their layout)
    for b in ("white", "blue", "black", "red", "green", "colorless"):
        bp = os.path.join(plane, "world", "biomes", b + ".json")
        text, crlf = read_text(bp)
        _, cur = list_block(text, "enemies")
        add = [n for n in roster_add[b] if n not in cur]
        if not add:
            report.append("biome %-9s roster: all %d already listed - skipped" % (b, len(roster_add[b])))
            continue
        text2 = add_to_list_block(text, "enemies", add, " " * 20)
        json.loads(text2)
        writes[bp] = encode(text2, crlf)
        report.append("biome %-9s roster +%d: %s" % (b, len(add), ", ".join(add)))

    # ------------------------------------------------------------------ arenas - round 179's picks
    by_rank = [(name, rank, order(colors)) for _, name, rank, colors, *_ in ROSTER]

    def ranked(color, rank):
        c = [r for r in by_rank if r[1] == rank and color in r[2]]
        return sorted(c, key=lambda r: (len(r[2]), zlib.crc32(r[0].encode("utf-8"))))
    arena_add = collections.OrderedDict()
    for path, color in ARENA_CAPITALS.items():
        arena_add[path] = [r[0] for rank in ("M", "X") for r in ranked(color, rank)[:ARENA_CAP[rank]]]
    picked = []
    for color in "WUBRG":
        for rank in ARENA_PLAYER_PER_COLOR:
            r = next(r for r in ranked(color, rank) if r[0] not in picked)
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
    rows = csv_rows(E, old)
    missing_rows = [r for k, r in rows.items() if k not in old]
    if not missing_rows:
        report.append("enemies.csv: every enemy has its row - skipped")
    else:
        rebuilt_old = csv_text([r for k, r in rows.items() if k in old])
        if rebuilt_old == cur_text:
            new_text = csv_text(rows.values())       # the game's own rebuild = the current file + the new rows
        else:                                        # the snapshot drifted from enemies.json: append only
            new_text = cur_text + ("" if cur_text.endswith("\n") else "\n") + csv_text(missing_rows, header=False)
            report.append("enemies.csv: NOTE the current file differs from a fresh rebuild of enemies.json - the new "
                          "rows are appended and nothing else is touched (the game rewrites it on its next start)")
        writes[cp] = encode(new_text, crlf)
        report.append("enemies.csv: +%d rows (%d -> %d)" % (len(missing_rows), len(old) - 1, len(old) - 1 + len(missing_rows)))

    # ------------------------------------------------------------------ CREDITS
    crp = os.path.join(root, "standalone-packaging", "CREDITS.md")
    text, crlf = read_text(crp)
    if CREDITS_MARK in text:
        report.append("CREDITS.md: the RPG Maker line is already there - skipped")
    else:
        i = text.index(CREDITS_AFTER)
        j = text.find("\n## ", i + len(CREDITS_AFTER))
        if j < 0:
            raise SystemExit("CREDITS.md: no section after %r" % CREDITS_AFTER)
        body = text[:j].rstrip("\n") + "\n"            # the section's last bullet, then the new one, a blank line
        text2 = body + CREDITS_LINE + "\n" + text[j + 1:]
        writes[crp] = encode(text2, crlf)
        report.append("CREDITS.md: + the RPG Maker MV/MZ creature sheets line under %r" % CREDITS_AFTER)

    # ------------------------------------------------------------------ report, write
    print("repo root: %s%s" % (root, "   (DRY RUN - nothing written)" if dry else ""))
    for line in report:
        print("  " + line)
    if not writes:
        print("nothing to do - round 316 is already imported")
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
    print("next: python dev-tools/enemy_scale.py --write  (the sizes; it also moves 12 older enemies - summary.txt), "
          "then dev-tools/validate_plane_data.py. Do NOT run dev-tools/gen_caves.py (it would wipe rounds 257/258/286's "
          "cave guards).")
    return 0


if __name__ == "__main__":
    sys.exit(main())
