r"""verify382.py - after import382.py / set_scales382.py (and, when it has run, add_to_biomes.py): the round's checks.
  ATLASES   every new entry's sprite parses the way the game reads it (dev-tools/sprite_sizes.parse_atlas): one Avatar,
            only engine animation names (Idle / Walk / Attack / Hit / Death, bare or with Right/Left/Up/Down), Idle and
            Walk in every direction the atlas uses, one cell size, every region inside its png
  DECKS     every new entry's deck exists and every card in it resolves (check_decks382.py's rules)
  VICTOR    the new "Victor" is the vampire legend - the user's deck, Archmage (Mythic, difficulty 3, life 50), best of
            three, a boss, no ante, and NO deck-card reward; every restricted card in his deck is listed with the reward
            paths that could hand it over (a deckCard entry: none; the ante: off; the Chest and the cave champions:
            excluded as a boss; the card budget: exempt at spawnRate 0)
            the old Victor is "Victor, Valgavoth's Seneschal" in enemies.json, the church map, enemies.csv and (once
            add_to_biomes.py has run) the white and black rosters
  LEGENDS   the 12: spawnRate 0, boss, gamesPerMatch 3, named in roaming_champions.json, every reward certain, in no
            biome roster and no arena pool
  CSV       config tables/enemies.csv is the rebuild of enemies.json (ContentFilterTables' writer)
usage: python verify382.py <repo root>      (exit 1 on a failure; "PENDING" lines are add_to_biomes.py's work)"""
import collections, csv, io, json, os, re, subprocess, sys
import xml.etree.ElementTree as ET

sys.dont_write_bytecode = True
HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
sys.path.insert(0, os.path.dirname(os.path.dirname(HERE)))           # dev-tools: sprite_sizes
from roster382 import ROSTER, LEGENDS, ARTIFACT, VICTOR_DECK, OLD_VICTOR, OLD_VICTOR_NEW_NAME
import import382
import sprite_sizes as ss

args = [a for a in sys.argv[1:] if not a.startswith("--")]
if len(args) != 1:
    raise SystemExit(__doc__)
ROOT = os.path.abspath(args[0])
PLANE = os.path.join(ROOT, "forge-gui", "res", "adventure", "The Forsaken Realms")
NAMES = re.compile(r"^(Idle|Walk|Attack|Hit|Death)(Right|Left|Up|Down)?$")
fails, pend = [], []


def fail(msg):
    fails.append(msg)
    print("FAIL " + msg)


def lenient(path):
    t = open(path, encoding="utf-8").read()
    t = re.sub(r"(?m)^\s*//.*$", "", t)
    return json.loads(re.sub(r",(\s*[}\]])", r"\1", t))


E = json.load(open(os.path.join(PLANE, "world", "enemies.json"), encoding="utf-8"))
by = {e["name"]: e for e in E}
new_names = [r[1] for r in ROSTER]

# ------------------------------------------------------------------ ATLASES
n_ok = 0
for slug, name, *_ in ROSTER:
    e = by.get(name)
    if e is None:
        fail("%s: not in enemies.json" % name)
        continue
    ap = os.path.join(PLANE, e["sprite"])
    if not os.path.isfile(ap):
        fail("%s: sprite %s missing" % (name, e["sprite"]))
        continue
    page, regions = ss.parse_atlas(ap)
    from PIL import Image
    png = os.path.join(os.path.dirname(ap), page)
    W, H = Image.open(png).size
    errs = []
    if len(regions.get("Avatar", [])) != 1:
        errs.append("Avatar x%d" % len(regions.get("Avatar", [])))
    bad = [k for k in regions if k != "Avatar" and not NAMES.match(k)]
    if bad:
        errs.append("region names %s" % bad)
    dirs = set((NAMES.match(k).group(2) or "") for k in regions if k != "Avatar" and NAMES.match(k))
    for an in ("Idle", "Walk"):
        for d in dirs:
            if not regions.get(an + d):
                errs.append("no %s%s" % (an, d))
    cells = set((w, h) for k, fr in regions.items() if k != "Avatar" for (_, _, w, h) in fr)
    if len(cells) != 1:
        errs.append("cell sizes %s" % cells)
    for k, fr in regions.items():
        for (x, y, w, h) in fr:
            if x < 0 or y < 0 or x + w > W or y + h > H:
                errs.append("%s outside the page" % k)
    if errs:
        fail("%s: atlas %s" % (name, "; ".join(errs)))
    else:
        n_ok += 1
print("ATLASES  %d of %d parse with the engine's regions" % (n_ok, len(ROSTER)))

# ------------------------------------------------------------------ DECKS
deck_dir = os.path.join(PLANE, import382.DECK_DIR)
r = subprocess.run([sys.executable, "-B", os.path.join(HERE, "check_decks382.py"), ROOT, deck_dir],
                   capture_output=True, text=True, env=dict(os.environ, PYTHONDONTWRITEBYTECODE="1"))
last = [ln for ln in r.stdout.splitlines() if ln.strip()][-1:] or ["(no output)"]
print("DECKS    %s%s" % (last[0], "" if r.returncode == 0 else "  <- check_decks382.py exit %d" % r.returncode))
if r.returncode != 0:
    fail("decks: " + "; ".join(ln for ln in r.stdout.splitlines() if "legal" not in ln and ln.strip())[:600])
for slug, name, *_ in ROSTER:
    e = by.get(name)
    if e and not os.path.isfile(os.path.join(PLANE, e["deck"][0])):
        fail("%s: deck %s missing" % (name, e["deck"][0]))

# ------------------------------------------------------------------ VICTOR
v = by.get(OLD_VICTOR) or {}
want = {"deck": [VICTOR_DECK], "tier": "Mythic", "difficulty": 3, "life": 50, "spawnRate": 0, "boss": True,
        "gamesPerMatch": 3, "noAnte": True, "colors": "B"}
for k, val in want.items():
    if v.get(k) != val:
        fail("Victor: %s is %r, want %r" % (k, v.get(k), val))
deck_cards = [t for t in v.get("rewards", []) if t.get("type") == "deckCard"]
if deck_cards:
    fail("Victor: %d deckCard reward(s) - they draw from his deck, Black Lotus included" % len(deck_cards))
restricted = set(lenient(os.path.join(PLANE, "config tables", "restricted_cards.json")).get("restrictedCards", []))
vdeck = []
for ln in open(os.path.join(PLANE, VICTOR_DECK), encoding="utf-8"):
    m = re.match(r"^(\d+)\s+(.+?)(\|.*)?$", ln.strip())
    if m:
        vdeck.append(m.group(2).strip())
held = sorted(set(vdeck) & restricted)
print("VICTOR   %r: deck %s (%d cards), tier %s, difficulty %s, life %s, best of %s, boss %s, noAnte %s; "
      "restricted cards in the deck: %s; deckCard rewards: %d" % (
          OLD_VICTOR, VICTOR_DECK, len(vdeck) and sum(int(re.match(r"^(\d+)", l.strip()).group(1)) for l in
          open(os.path.join(PLANE, VICTOR_DECK), encoding="utf-8") if re.match(r"^\d+ ", l.strip())),
          v.get("tier"), v.get("difficulty"), v.get("life"), v.get("gamesPerMatch"), v.get("boss"), v.get("noAnte"),
          ", ".join(held) or "none", len(deck_cards)))
old = by.get(OLD_VICTOR_NEW_NAME)
if not old or old.get("deck") != [import382.OLD_VICTOR_DECK]:
    fail("the old Victor is not %r in enemies.json" % OLD_VICTOR_NEW_NAME)
tmx = open(os.path.join(PLANE, import382.VICTOR_TMX), encoding="utf-8").read()
if '<property name="enemy" value="%s"/>' % OLD_VICTOR in tmx:
    fail("%s still places %r" % (import382.VICTOR_TMX, OLD_VICTOR))
n_new_tmx = tmx.count('<property name="enemy" value="%s"/>' % OLD_VICTOR_NEW_NAME)
print("VICTOR   the old one: %r in enemies.json, %d placement(s) in %s" % (OLD_VICTOR_NEW_NAME, n_new_tmx,
                                                                         import382.VICTOR_TMX))

# ------------------------------------------------------------------ LEGENDS + rosters + arenas
champions = set(lenient(os.path.join(PLANE, "config tables", "roaming_champions.json"))["names"])
rosters = {}
for f in os.listdir(os.path.join(PLANE, "world", "biomes")):
    if f.endswith(".json"):
        d = lenient(os.path.join(PLANE, "world", "biomes", f))
        rosters[f[:-5]] = set(d.get("enemies") or [])
pools = collections.defaultdict(set)
for dirpath, _, files in os.walk(os.path.join(PLANE, "maps")):
    for f in files:
        if not f.endswith(".tmx"):
            continue
        p = os.path.join(dirpath, f)
        t = open(p, encoding="utf-8", errors="replace").read()
        if '"arena"' not in t:
            continue
        try:
            root = ET.fromstring(t.encode("utf-8"))
        except ET.ParseError:
            continue
        for prop in root.iter("property"):
            if prop.get("name") == "arena":
                try:
                    for n in json.loads(prop.text if prop.text else prop.get("value")).get("enemyPool", []):
                        pools[n].add(os.path.relpath(p, PLANE))
                except Exception:
                    pass
for slug in LEGENDS:
    name = next(r[1] for r in ROSTER if r[0] == slug)
    e = by.get(name) or {}
    if e.get("spawnRate") != 0 or not e.get("boss") or e.get("gamesPerMatch") != 3:
        fail("legend %s: spawnRate %r boss %r gamesPerMatch %r" % (name, e.get("spawnRate"), e.get("boss"),
                                                                   e.get("gamesPerMatch")))
    if name not in champions:
        fail("legend %s: not in roaming_champions.json" % name)
    if any(t.get("probability", 1) != 1 for t in e.get("rewards", [])):
        fail("legend %s: an uncertain reward" % name)
    inr = [b for b, names in rosters.items() if name in names]
    if inr:
        (pend if name == OLD_VICTOR and set(inr) <= {"white", "black"} else fails).append(
            "legend %s is in the %s roster(s)" % (name, ", ".join(sorted(inr))))
        print(("PENDING " if name == OLD_VICTOR and set(inr) <= {"white", "black"} else "FAIL ")
              + "legend %s is in the %s roster(s)" % (name, ", ".join(sorted(inr))))
    if pools.get(name):
        fail("legend %s is in an arena pool: %s" % (name, sorted(pools[name])))
print("LEGENDS  %d checked: spawnRate 0, boss, best of 3, named in roaming_champions.json, no arena pool" % len(LEGENDS))

# the ordinary ones' rosters (add_to_biomes.py)
adds = import382.roster_additions()
missing = {b: [n for n in names if n not in rosters.get(b, set())] for b, names in adds.items()}
n_missing = sum(len(v) for v in missing.values())
old_listed = [b for b in ("white", "black") if OLD_VICTOR_NEW_NAME not in rosters.get(b, set())]
if n_missing or old_listed:
    pend.append("rosters")
    print("PENDING  add_to_biomes.py: %d roster additions%s" % (
        n_missing, ", and the old Victor's rename in %s" % "/".join(old_listed) if old_listed else ""))
else:
    print("ROSTERS  every ordinary enemy listed (%s); the old Victor renamed in white/black" % ", ".join(
        "%s +%d" % (b, len(n)) for b, n in adds.items()))
print("ARENAS   %s" % "; ".join("%s: %s" % (n, ", ".join(sorted(os.path.basename(p) for p in pools[n])))
                                for n in new_names if pools.get(n)))

# ------------------------------------------------------------------ CSV
cp = os.path.join(PLANE, "config tables", "enemies.csv")
cur, crlf = import382.read_text(cp)
oldrows = {r_[0].lower(): r_ for r_ in csv.reader(io.StringIO(cur)) if r_}
if import382.csv_text(import382.csv_rows(E, oldrows).values()) != cur:
    fail("enemies.csv is not the rebuild of enemies.json")
else:
    print("CSV      enemies.csv = the rebuild of enemies.json (%d rows)" % (cur.count("\n") - 1))

print("\n%d failure(s)%s" % (len(fails), ", pending: add_to_biomes.py" if pend else ""))
sys.exit(1 if fails else 0)
