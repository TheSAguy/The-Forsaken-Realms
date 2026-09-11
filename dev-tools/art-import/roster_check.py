"""Balance check for roster179: counts by color count, per-color presence by rank (new and new+current roaming),
name collisions with the catalog, and slugs missing from the staging manifests."""
import collections, json, os, sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from roster179 import ROSTER

PLANE = r"F:\FORGE\C--Users-vicwaver-MTG-Forge\forge-gui\res\adventure\The Forsaken Realms"
STAGE = r"F:\FORGE\TFR-Art-Staging"
RANK = {"A": "Common", "D": "Uncommon", "M": "Rare", "X": "Mythic"}
E = json.load(open(os.path.join(PLANE, "world", "enemies.json"), encoding="utf-8"))
names = {e["name"].lower() for e in E} | {(e.get("nameOverride") or "").lower() for e in E}

print("roster:", len(ROSTER))
ncol = collections.Counter(len(c) for _, _, _, c, _, _ in ROSTER)
print("mono / two / three:", ncol[1], ncol[2], ncol[3])
print("ranks:", collections.Counter(r for _, _, r, _, _, _ in ROSTER))
cur = collections.defaultdict(collections.Counter)
for e in E:
    if e.get("boss") or (e.get("spawnRate") or 0) <= 0:
        continue
    for c in set(ch for ch in (e.get("colors") or "").upper() if ch in "WUBRG"):
        cur[c][e.get("tier", "Common")] += 1
new = collections.defaultdict(collections.Counter)
for _, _, r, cols, _, _ in ROSTER:
    for c in cols:
        new[c][RANK[r]] += 1
print("\ncolor  new slots (A/Ad/M/Ar)          roaming today -> after")
for c in "WUBRG":
    n = new[c]
    print("  %s   %3d  (%2d/%2d/%2d/%2d)     %4d -> %4d   ranks after: %s" % (
        c, sum(n.values()), n["Common"], n["Uncommon"], n["Rare"], n["Mythic"], sum(cur[c].values()),
        sum(cur[c].values()) + sum(n.values()),
        "/".join(str(cur[c][t] + n[t]) for t in ("Common", "Uncommon", "Rare", "Mythic"))))
dup = [nm for _, nm, _, _, _, _ in ROSTER if nm.lower() in names]
print("\nname collisions with the catalog:", dup)
seen = collections.Counter(nm for _, nm, _, _, _, _ in ROSTER)
print("duplicate names in the roster:", [k for k, v in seen.items() if v > 1])
slugs = set()
for mf in ("ro/manifest_ro.json", "gen/manifest_generic.json"):
    slugs |= set(json.load(open(os.path.join(STAGE, mf))).keys())
print("slugs not in the staging manifests:", [s for s, *_ in ROSTER if s not in slugs])
print("themes:", collections.Counter(t for *_, t, _ in ROSTER).most_common())
