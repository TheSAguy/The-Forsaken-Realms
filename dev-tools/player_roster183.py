"""Round 183 (user: "Sure why not. Add some."): put a share of round 179's 197 new enemies into the
PLAYER biome roster, which since round 178 also stocks the dungeons standing on the player's land.

Keeps the roster's own tier shape (it is a friendly-land roster: mostly Apprentice/Adept), spreads the
picks evenly over the five colors, and is deterministic - sorted names, round-robin by color, so a re-run
picks the same 36. Edits only the "enemies" array, in place, preserving the file's hand-written layout."""
import json, re, collections, os

PLANE = r"F:\FORGE\C--Users-vicwaver-MTG-Forge\forge-gui\res\adventure\The Forsaken Realms"
ENEMIES = os.path.join(PLANE, "world", "enemies.json")
PLAYER = os.path.join(PLANE, "world", "biomes", "player.json")

# Same shape as the existing 72 (22 Common / 33 Uncommon / 16 Rare / 1 Mythic), at half the size.
QUOTA = {"Common": 12, "Uncommon": 16, "Rare": 7, "Mythic": 1}
COLORS = ["W", "U", "B", "R", "G"]


def load(path):
    return json.loads(re.sub(r"^\s*//.*$", "", open(path, encoding="utf-8").read(), flags=re.M))


catalog = load(ENEMIES)
new = [e for e in catalog if "tfr" in str(e.get("sprite", "")).replace("\\", "/").split("/")]
assert len(new) == 197, len(new)

raw = open(PLAYER, "rb").read()
crlf = b"\r\n" in raw
text = raw.decode("utf-8").replace("\r\n", "\n")
current = set(load(PLAYER)["enemies"])

# Round-robin over colors inside each tier, taking each color's names in sorted order, so the picks are
# spread over colors AND over the alphabet rather than clustering on one sheet's worth of monsters.
by_tier_color = collections.defaultdict(list)
for e in new:
    if e["name"] in current:
        continue
    letters = [c for c in str(e.get("colors", "")).upper() if c in COLORS] or ["W"]
    by_tier_color[(e.get("tier"), letters[0])].append(e["name"])
for key in by_tier_color:
    by_tier_color[key].sort()

picked = []
for tier, quota in QUOTA.items():
    cursors = {c: 0 for c in COLORS}
    while len([p for p in picked if p[1] == tier]) < quota:
        progressed = False
        for color in COLORS:
            pool = by_tier_color.get((tier, color), [])
            if cursors[color] >= len(pool):
                continue
            picked.append((pool[cursors[color]], tier, color))
            cursors[color] += 1
            progressed = True
            if len([p for p in picked if p[1] == tier]) >= quota:
                break
        if not progressed:
            break

names = sorted(p[0] for p in picked)
print("picked %d: %s" % (len(names), collections.Counter(p[1] for p in picked)))
print("by color:", collections.Counter(p[2] for p in picked))

# Splice into the "enemies" array, keeping the file's own indentation, and re-sort it alphabetically
# (which is how it already reads).
m = re.search(r'("enemies":\s*\[\n)(.*?)(\n\s*\],?\n)', text, re.S)
assert m, "enemies array not found"
body = m.group(2)
indent = re.match(r"\s*", body).group(0)
existing = re.findall(r'"([^"]+)"', body)
assert len(existing) == len(current), (len(existing), len(current))
merged = sorted(set(existing) | set(names))
new_body = ",\n".join(indent + '"' + n + '"' for n in merged)
text = text[:m.start(2)] + new_body + text[m.end(2):]

open(PLAYER, "wb").write((text.replace("\n", "\r\n") if crlf else text).encode("utf-8"))
print("player.json roster %d -> %d" % (len(existing), len(merged)))
json.loads(re.sub(r"^\s*//.*$", "", open(PLAYER, encoding="utf-8").read(), flags=re.M))
print("re-parsed OK")
for n in names:
    print("   +", n)
