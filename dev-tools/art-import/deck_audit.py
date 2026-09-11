"""Audit the generated decks: size, lands, creatures, on-theme creatures, average CMC, colors present, legendaries,
planeswalkers; flags outliers. usage: python deck_audit.py [deck dir]"""
import collections, os, re, statistics, sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import carddb
from roster179 import ROSTER
from deckgen179 import THEMES, BAD, BAD_OK

D = sys.argv[1] if len(sys.argv) > 1 else r"F:\FORGE\TFR-Art-Staging\decks"
DB = carddb.load()
flags = collections.Counter()
rows = []
for slug, name, rank, colors, theme, tags in ROSTER:
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
    nonland = [(k, DB[n]) for k, n in cards if n in DB and "Land" not in DB[n]["types"]]
    avg = sum(k * c["cmc"] for k, c in nonland) / max(1, sum(k for k, _ in nonland))
    present = set(ch for k, c in nonland for ch in c["colors"])
    legend = sum(1 for k, c in nonland if "Legendary" in c["super"])
    pw = sum(k for k, c in nonland if "Planeswalker" in c["types"])
    missing = [n for k, n in cards if n not in DB and n not in ("Plains", "Island", "Swamp", "Mountain", "Forest")]
    f = []
    if total not in (40, 60):
        f.append("size %d" % total)
    if on < (6 if rank == "A" else 8):
        f.append("theme %d" % on)
    if set(colors) - present:
        f.append("missing color %s" % "".join(sorted(set(colors) - present)))
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
