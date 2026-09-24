"""Round 324: an Apprentice "Eldrazi Scion" for the Eldrazi prisons' chest guards (the user picked it over the Adept
Eldrazi and the Eye). Writes the deck, inserts the enemies.json entry after "Eldrazi" and adds the enemies.csv row.
python add_eldrazi_scion.py <repo root>   (REQUIRED: the tree to patch)"""
import json
import os
import sys

if len(sys.argv) != 2 or not os.path.isdir(os.path.join(sys.argv[1], "forge-gui")):
    raise SystemExit("usage: python add_eldrazi_scion.py <repo root>")
PLANE = os.path.join(sys.argv[1], "forge-gui", "res", "adventure", "The Forsaken Realms")

DECK = """[metadata]
Name=eldrazi_scion
[Main]
4 Blisterpod|BFZ
4 Nest Invader|ROE
3 Carrier Thrall|BFZ
2 Culling Drone|BFZ
3 Catacomb Sifter|BFZ
2 Kozilek's Predator|ROE
2 Brood Monitor|BFZ
1 Dread Drone|ROE
2 Complete Disregard|BFZ
1 Natural Connection|BFZ
8 Forest|BFZ
8 Swamp|BFZ
[Sideboard]
"""

ENTRY = {
    "name": "Eldrazi Scion",
    "sprite": "sprites/enemy/aberration/eldrazi/eldrazi.atlas",
    "deck": ["decks/standard/tfr/eldrazi_scion.dck"],
    "ai": "",
    "spawnRate": 0.5,
    "difficulty": 0.1,
    "speed": 20,
    "life": 12,
    "rewards": [
        {"type": "deckCard", "probability": 1, "count": 1, "addMaxCount": 2, "rarity": ["common", "uncommon"]},
        {"type": "gold", "probability": 0.3, "count": 10, "addMaxCount": 40},
        {"type": "card", "probability": 1, "count": 1, "addMaxCount": 1, "subTypes": ["Eldrazi"], "cardTypes": ["Creature"]},
        {"type": "shards", "probability": 0.5, "count": 5, "addMaxCount": 5},
    ],
    "colors": "BG",
    "questTags": ["Aberration", "Eldrazi", "IdentityBlack", "IdentityGreen", "IdentityGolgari", "BiomeColorless"],
    "tier": "Common",
    "scale": 1.0,
}

deck_path = os.path.join(PLANE, "decks", "standard", "tfr", "eldrazi_scion.dck")
if os.path.exists(deck_path):
    raise SystemExit("already applied: " + deck_path)
n = sum(int(l.split(" ", 1)[0]) for l in DECK.split("[Main]")[1].split("[Sideboard]")[0].strip().splitlines())
assert n == 40, n
open(deck_path, "w", encoding="utf-8", newline="\n").write(DECK)
print("deck", deck_path, n, "cards")

path = os.path.join(PLANE, "world", "enemies.json")
raw = open(path, "rb").read().decode("utf-8")
nl = "\r\n" if "\r\n" in raw else "\n"
text = raw.replace("\r\n", "\n")
data = json.loads(text)
if any(e.get("name") == "Eldrazi Scion" for e in data):
    raise SystemExit("enemies.json already has Eldrazi Scion")
anchor = '        "name": "Eldrazi",\n'
start = text.index(anchor)
# the end of the "Eldrazi" object: the first "\n    }," after its name line
end = text.index("\n    },", start) + len("\n    },")
block = json.dumps(ENTRY, indent=4)
block = "\n".join("    " + line for line in block.splitlines())
text = text[:end] + "\n" + block + "," + text[end:]
check = json.loads(text)
assert sum(1 for e in check if e.get("name") == "Eldrazi Scion") == 1 and len(check) == len(data) + 1
open(path, "wb").write(text.replace("\n", nl).encode("utf-8"))
print("enemies.json +1 (Eldrazi Scion), now", len(check))

csv_path = os.path.join(PLANE, "config tables", "enemies.csv")
csv = open(csv_path, "rb").read().decode("utf-8")
cnl = "\r\n" if "\r\n" in csv else "\n"
row = "Eldrazi Scion,BG,eldrazi_scion,12,Common,N,0.1,Y"
lines = csv.replace("\r\n", "\n").rstrip("\n").split("\n")
at = next(i for i, l in enumerate(lines) if l.startswith("Eldrazi,")) + 1
lines.insert(at, row)
open(csv_path, "wb").write((cnl.join(lines) + cnl).encode("utf-8"))
print("enemies.csv +1 row")
