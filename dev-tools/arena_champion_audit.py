"""Round 311: the arena pools' champions (spawnRate <= 0, carrying their own rewards) and every other route that already
reaches each one - map placement, cave champion (not a legend), frontier legend, the Chest's heavyweights, war champion.
The ones with none are the roaming champions: their names go in "config tables/roaming_champions.json".
python dev-tools/arena_champion_audit.py <repo root>"""
import collections
import glob
import html
import json
import os
import re
import sys

if len(sys.argv) != 2:
    raise SystemExit("usage: python dev-tools/arena_champion_audit.py <repo root>")
PLANE = os.path.join(sys.argv[1], "forge-gui", "res", "adventure", "The Forsaken Realms")
HEAVY = 100  # ChestEvents.HEAVYWEIGHT_LIFE


def jload(path):
    text = open(path, encoding="utf-8").read()
    text = re.sub(r"^\s*//.*$", "", text, flags=re.M)
    return json.loads(text)


enemies = {e["name"]: e for e in jload(os.path.join(PLANE, "world", "enemies.json"))}
maps = glob.glob(os.path.join(PLANE, "maps", "**", "*.tmx"), recursive=True)

pools = collections.defaultdict(set)   # arena pool name -> maps
placed = collections.defaultdict(set)  # enemy placed on a map -> maps
for tmx in maps:
    text = open(tmx, encoding="utf-8", errors="ignore").read()
    for m in re.finditer(r'enemyPool&quot;:\[(.*?)\]', text):
        for name in re.findall(r'&quot;(.*?)&quot;', m.group(1)):
            pools[html.unescape(name)].add(os.path.basename(tmx))
    for m in re.finditer(r'<property name="enemy" value="([^"]+)"', text):
        placed[html.unescape(m.group(1))].add(os.path.basename(tmx))

war = set()
wc = jload(os.path.join(PLANE, "config tables", "war_champions.json"))
for v in wc.values():
    if isinstance(v, list):
        war.update(x for x in v if isinstance(x, str))

rows = []
for name in sorted(pools):
    e = enemies.get(name)
    if e is None:
        rows.append((name, "NOT IN CATALOG"))
        continue
    if (e.get("spawnRate", 0) or 0) > 0 or not e.get("rewards"):
        continue
    boss, legend, tier = bool(e.get("boss")), bool(e.get("legend")), e.get("tier")
    quest, life = bool(e.get("questTags")), e.get("life", 0)
    routes = []
    if not boss and not quest and tier != "Mythic" and legend and life < 60:
        routes.append("frontier(unhappy/war)")
    if not boss and not legend:
        routes.append("cave")
    if not boss and not quest and life >= HEAVY and tier != "Mythic":
        routes.append("chest-heavy")
    if tier == "Mythic" and not boss:
        routes.append("chest-archmage?")
    if name in war:
        routes.append("war-champion")
    if name in placed:
        routes.append("placed:" + ",".join(sorted(placed[name]))[:60])
    rows.append((name, "tier=%s colors=%s life=%s boss=%s legend=%s quest=%s | %s" % (
        tier, e.get("colors"), life, boss, legend, quest, "; ".join(routes) or "ARENA ONLY")))

print(len(pools), "names in arena pools;", len(rows), "champions (spawnRate<=0 with rewards)")
for name, desc in rows:
    print("%-28s %s" % (name, desc))
only = [name for name, desc in rows if "ARENA ONLY" in desc]
print(len(only), "arena only - the roaming champions:")
print(json.dumps(only))
