"""deck_audit382.py - round 318's deck_audit316.py (round 179's deck_audit.py) pointed at roster382 / deckgen382:
7-field roster rows, Victor's hand-made deck skipped, the deck folder REQUIRED, and an artifact deck's "on theme"
count is its Artifact cards. Audit the generated decks: size, lands, creatures, on-theme cards, average CMC, colors
present, legendaries, planeswalkers; flags outliers. usage: python deck_audit382.py <deck dir>"""
import collections, os, re, statistics, sys

sys.dont_write_bytecode = True
HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
sys.path.insert(0, os.path.join(HERE, "tools"))
import carddb
from roster382 import ROSTER
import ast
# deckgen382.THEMES / BAD / BAD_OK read without importing it (that would need --out and load the card cache twice)
_tree = ast.parse(open(os.path.join(HERE, "deckgen382.py"), encoding="utf-8").read())
_vals = {}
for _n in _tree.body:
    if isinstance(_n, ast.Assign) and getattr(_n.targets[0], "id", None) in ("THEMES", "BAD_OK"):
        _vals[_n.targets[0].id] = ast.literal_eval(_n.value)
THEMES, BAD_OK = _vals["THEMES"], _vals["BAD_OK"]
BAD = re.compile(r"you lose the game|at the beginning of your upkeep, sacrifice|sacrifice it at the beginning|"
                 r"can't attack or block|skip your|you can't win the game|target opponent creates|"
                 r"where x is your life total|sacrifice it unless|sacrifice any number of creatures with total power|"
                 r"an opponent gains control|each opponent creates|"
                 r"can't attack unless|when you control no|as long as you control no", re.I)

if len(sys.argv) < 2:
    raise SystemExit(__doc__)
D = sys.argv[1]
DB = carddb.load()
flags = collections.Counter()
rows = []
for slug, name, rank, colors, theme, tag_theme, tags in ROSTER:
    if theme is None:
        continue
    cards = []
    for ln in open(os.path.join(D, slug + ".dck"), encoding="utf-8"):
        m = re.match(r"^(\d+) (.+)$", ln.strip())
        if m:
            cards.append((int(m.group(1)), m.group(2)))
    total = sum(k for k, _ in cards)
    lands = sum(k for k, n in cards if "Land" in DB.get(n, {"types": ["Land"]})["types"])
    creat = sum(k for k, n in cards if "Creature" in DB.get(n, {}).get("types", []))
    prim = set(THEMES[theme][0])
    on = sum(k for k, n in cards if set(DB.get(n, {}).get("sub", [])) & prim)
    if theme.startswith("artifact"):
        on = sum(k for k, n in cards if "Artifact" in DB.get(n, {}).get("types", []))
    nonland = [(k, DB[n]) for k, n in cards if n in DB and "Land" not in DB[n]["types"]]
    avg = sum(k * c["cmc"] for k, c in nonland) / max(1, sum(k for k, _ in nonland))
    present = set(ch for k, c in nonland for ch in c["colors"])
    legend = sum(1 for k, c in nonland if "Legendary" in c["super"])
    pw = sum(k for k, c in nonland if "Planeswalker" in c["types"])
    missing = [n for k, n in cards if n not in DB and n not in ("Plains", "Island", "Swamp", "Mountain", "Forest", "Wastes")]
    f = []
    if total not in (40, 60):
        f.append("size %d" % total)
    if on < (6 if rank == "A" else 8):
        f.append("theme %d" % on)
    if set(colors) - present - {"C"}:
        f.append("missing color %s" % "".join(sorted(set(colors) - present - {"C"})))
    if "C" in colors and present:
        f.append("colored cards in a colorless deck %s" % "".join(sorted(present)))
    if avg > 3.8:
        f.append("avg cmc %.1f" % avg)
    if creat < (12 if rank == "A" else 16):
        f.append("creatures %d" % creat)
    if missing:
        f.append("unknown %s" % missing[:2])
    drawback = [n for k, n in cards if n in DB and n not in BAD_OK and "Creature" in DB[n]["types"] and BAD.search(DB[n]["oracle"])]
    if drawback:
        f.append("drawback %s" % drawback[:3])
    for x in f:
        flags[x.split()[0]] += 1
    rows.append((slug, name, rank, colors, theme, total, lands, creat, on, avg, legend, pw, f))
print("decks:", len(rows), "flags:", dict(flags))
for r in rows:
    if r[-1]:
        print("  %-30s %s %-4s %-12s %2d lands %2d creat %2d theme %2d avg %.1f leg %d pw %d  %s" % (r[1][:30], r[2], r[3], r[4], r[6], r[7], r[8], r[9], r[9], r[10], r[11], "; ".join(r[-1])))
print("\nmedian on-theme creatures by rank:", {k: statistics.median([r[8] for r in rows if r[2] == k]) for k in "ADMX"})
print("median avg cmc by rank:", {k: round(statistics.median([r[9] for r in rows if r[2] == k]), 2) for k in "ADMX"})
print("\n%-30s %s %-4s %-14s %5s %5s %5s %5s %5s" % ("deck", "r", "col", "theme", "cards", "lands", "creat", "theme", "cmc"))
for r in rows:
    print("%-30s %s %-4s %-14s %5d %5d %5d %5d %5.2f%s" % (r[1][:30], r[2], r[3], r[4], r[5], r[6], r[7], r[8], r[9], "  <- " + "; ".join(r[-1]) if r[-1] else ""))
sys.exit(1 if flags else 0)
