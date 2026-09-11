"""card_table.py - the deck builder's view of a save's collection (round 177, 2026-09-11).

Joins every card in an Inspect dump with its Forge card script (cost, types, P/T, oracle) and its best rarity across
the edition files, then writes one compact text file per color identity to read through when building decks:
view_W.txt ... view_G.txt, view_multi.txt, view_C.txt (colorless), view_L.txt (lands), plus collection_table.tsv.

    java -cp "$JAR;$S" Inspect "$SAV" > inspect_slot1.txt        (Windows code page output - read as cp1252)
    python card_table.py inspect_slot1.txt [out dir]

Line format: "[Nx ]Name|cost|type|P/T|rarity|oracle (reminder text dropped, 120 chars)".
"""
import collections, os, re, sys

HERE = os.path.dirname(os.path.abspath(__file__))
RES = os.path.join(HERE, "..", "..", "forge-gui", "res")
inspect = sys.argv[1]
OUT = sys.argv[2] if len(sys.argv) > 2 else "."

coll = collections.OrderedDict()
for line in open(inspect, encoding="cp1252"):
    m = re.match(r"\s+(\d+)x (.+?)\s*$", line)
    if m:
        coll[m.group(2)] = coll.get(m.group(2), 0) + int(m.group(1))

cards = {}
for root, _, files in os.walk(os.path.join(RES, "cardsfolder")):
    for f in files:
        if not f.endswith(".txt"):
            continue
        txt = open(os.path.join(root, f), encoding="utf-8", errors="replace").read()
        d = {}
        for ln in txt.split("\nALTERNATE")[0].splitlines():          # front face only
            if ":" in ln:
                k, v = ln.split(":", 1)
                if k in ("Name", "ManaCost", "Types", "PT", "Oracle", "Colors", "Loyalty") and k not in d:
                    d[k] = v.strip()
        if "Name" in d:
            cards.setdefault(d["Name"], d)

RANK = {"M": 4, "R": 3, "S": 3, "U": 2, "C": 1, "L": 0, "T": 0}
rarity = {}
for f in os.listdir(os.path.join(RES, "editions")):
    for ln in open(os.path.join(RES, "editions", f), encoding="utf-8", errors="replace"):
        m = re.match(r"^\S+\s+([MRUCSLT])\s+(.+?)(\s+@.*)?$", ln.strip())
        if m and RANK.get(m.group(1), 0) > RANK.get(rarity.get(m.group(2).strip(), "L"), 0):
            rarity[m.group(2).strip()] = m.group(1)


def colors_of(cost, d):
    if d.get("Colors"):
        return "".join(sorted(set(c for c in d["Colors"].upper() if c in "WUBRG"), key="WUBRG".index)) or "C"
    cs = set(ch for ch in (cost or "").upper().replace("P", "") if ch in "WUBRG")
    return "".join(sorted(cs, key="WUBRG".index)) or ("L" if "Land" in d.get("Types", "") else "C")


def cmc(cost):
    return sum(int(t) if t.isdigit() else (0 if t in ("X", "no", "cost") else 1) for t in (cost or "").split())


rows, missing = [], []
for name, cnt in coll.items():
    d = cards.get(name) or (cards.get(name.split(" // ")[0]) if " // " in name else None) \
        or (cards.get(name[2:]) if name.startswith("A-") else None)
    if d is None:
        missing.append(name)
        continue
    cost = d.get("ManaCost", "")
    rows.append((colors_of(cost, d), cmc(cost), name, cnt, cost, d.get("Types", ""), d.get("PT", d.get("Loyalty", "")),
                 rarity.get(name, "?"), d.get("Oracle", "").replace("\\n", " / ")))
rows.sort(key=lambda r: (len(r[0]) if r[0] not in ("C", "L") else 9, r[0], r[1], r[2]))

os.makedirs(OUT, exist_ok=True)
with open(os.path.join(OUT, "collection_table.tsv"), "w", encoding="utf-8") as fh:
    for r in rows:
        fh.write("\t".join(str(x) for x in r) + "\n")
views = collections.OrderedDict()
for col, _, name, cnt, cost, typ, pt, rar, oracle in rows:
    o = re.sub(r"\s+", " ", re.sub(r"\([^)]*\)", "", oracle)).strip()
    t = typ.replace("Legendary ", "L.").replace("Creature ", "").replace("Artifact ", "Art ").replace("Enchantment ", "Ench ")[:28]
    key = col if len(col) == 1 or col in ("C", "L") else "multi"
    views.setdefault(key, []).append("%s%s|%s|%s|%s|%s|%s" % ((str(cnt) + "x ") if cnt != 1 else "", name,
                                                               cost.replace(" ", ""), t, pt, rar, o[:120]))
for k, v in views.items():
    open(os.path.join(OUT, "view_%s.txt" % k), "w", encoding="utf-8").write("\n".join(v) + "\n")
depth = collections.Counter()
for r in rows:
    for c in r[0]:
        depth[c] += r[3]
print("cards:", sum(coll.values()), "names:", len(coll), "resolved:", len(rows), "unresolved:", missing)
print("depth by color (cards, multicolor counted in each):", dict(depth))
print("views:", {k: len(v) for k, v in views.items()})
