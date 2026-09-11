"""Card database for deck generation: every Forge card script (front face) joined with its printings (edition file,
rarity, set type), filtered to cards a TFR enemy deck may use. Cached to carddb.json.
usage: python carddb.py   (rebuilds the cache)"""
import json, os, re

REPO = r"F:\FORGE\C--Users-vicwaver-MTG-Forge"
RES = os.path.join(REPO, "forge-gui", "res")
PLANE = os.path.join(RES, "adventure", "The Forsaken Realms")
HERE = os.path.dirname(os.path.abspath(__file__))
CACHE = os.path.join(HERE, "carddb.json")
PAPER = {"Expansion", "Core", "Commander", "Draft", "Starter", "Duel_Deck", "Reprint", "Boxed_Set", "Multiplayer",
         "Collector_Edition", "Other"}
RANK = {"C": 1, "U": 2, "R": 3, "M": 4, "S": 3}
BAD_TYPES = {"Conspiracy", "Scheme", "Plane", "Phenomenon", "Vanguard", "Dungeon", "Attraction", "Contraption",
             "Emblem", "Hero", "Stickers"}
UB = re.compile(r"Doctor Who|Warhammer|Fallout|Lord of the Rings|Middle-earth|Assassin's Creed|Final Fantasy|Jurassic|"
                r"Transformers|Marvel|Spider-Man|Teenage Mutant|Avatar|Star Trek|Stranger Things|Walking Dead|"
                r"Street Fighter|Monster Hunter|Fortnite|Secret Lair|Universes Beyond|Tomb Raider|Hatsune|Godzilla|"
                r"Dungeons & Dragons: Honor|Special Guests|Wicked|SpongeBob|Hobbit|Tolkien|Dune|Stargate", re.I)


def lenient(path):
    t = open(path, encoding="utf-8").read()
    t = re.sub(r"(?m)^\s*//.*$", "", t)
    t = re.sub(r",(\s*[}\]])", r"\1", t)
    return json.loads(t)


def cmc_of(cost):
    n = 0
    for tok in (cost or "").split():
        if tok.isdigit():
            n += int(tok)
        elif tok in ("X", "Y", "no", "cost"):
            continue
        else:
            n += 1
    return n


def colors_of(cost, colors_line):
    if colors_line:
        return "".join(c for c in "WUBRG" if c.lower() in colors_line.lower().replace("blue", "u").replace("black", "b").replace("white", "w").replace("red", "r").replace("green", "g")[:0] + colors_line.lower() and {"w": "white", "u": "blue", "b": "black", "r": "red", "g": "green"}[c.lower()] in colors_line.lower())
    s = set()
    for tok in (cost or "").upper().split():
        for ch in tok:
            if ch in "WUBRG":
                s.add(ch)
    return "".join(c for c in "WUBRG" if c in s)


def build():
    printings = {}
    for f in os.listdir(os.path.join(RES, "editions")):
        txt = open(os.path.join(RES, "editions", f), encoding="utf-8", errors="replace").read()
        m = re.search(r"(?m)^Type=(\S+)", txt)
        code = re.search(r"(?m)^Code=(\S+)", txt)
        date = re.search(r"(?m)^Date=(\S+)", txt)
        stype = m.group(1) if m else "Other"
        if stype not in PAPER:
            continue
        nm = re.search(r"(?m)^Name=(.+)$", txt)
        if nm and UB.search(nm.group(1)):
            continue                                  # Universes Beyond: out of place in a fantasy enemy deck
        sec = txt.split("[cards]", 1)[1] if "[cards]" in txt else ""
        sec = re.split(r"(?m)^\[", sec)[0]
        for ln in sec.splitlines():
            mm = re.match(r"^\S+\s+([CURMS])\s+(.+?)(\s+@.*)?$", ln.strip())
            if mm:
                printings.setdefault(mm.group(2).strip(), []).append((code.group(1) if code else f, mm.group(1), date.group(1) if date else ""))
    restricted = set()
    try:
        restricted = set(lenient(os.path.join(PLANE, "config tables", "restricted_cards.json")).get("restrictedCards", []))
    except Exception as ex:
        print("restricted list unreadable:", ex)
    db = {}
    for root, _, files in os.walk(os.path.join(RES, "cardsfolder")):
        for f in files:
            if not f.endswith(".txt"):
                continue
            raw = open(os.path.join(root, f), encoding="utf-8", errors="replace").read()
            face = raw.split("\nALTERNATE")[0]
            d, kws, ai = {}, [], []
            for ln in face.splitlines():
                if ":" not in ln:
                    continue
                k, v = ln.split(":", 1)
                v = v.strip()
                if k == "K":
                    kws.append(v.split(":")[0])
                elif k == "AI":
                    ai.append(v)
                elif k in ("Name", "ManaCost", "Types", "PT", "Oracle", "Colors", "Loyalty") and k not in d:
                    d[k] = v
            name = d.get("Name")
            if not name or name.startswith("A-") or name in restricted or name not in printings:
                continue
            types = d.get("Types", "").split()
            if BAD_TYPES & set(types):
                continue
            oracle = d.get("Oracle", "").replace("\\n", " / ")
            if "playing for ante" in oracle.lower():
                continue
            if any(a.startswith("RemoveDeck:All") for a in ai):
                continue
            rar = min((RANK[r] for _, r, _ in printings[name]), default=3)
            main = [t for t in types if t in ("Creature", "Instant", "Sorcery", "Enchantment", "Artifact", "Land", "Planeswalker", "Battle", "Kindred", "Tribal")]
            sub = [t for t in types if t not in main and t not in ("Legendary", "Basic", "Snow", "World", "Ongoing", "-")]
            produce = ""
            if "Land" in types:
                # only what a mana ability ADDS - "{2}{W}{B}: ..." activation costs are not production
                got = set()
                for mm in re.finditer(r"Add ((?:\{[WUBRGC]\}(?:, | or |)?)+)", oracle):
                    got |= set(re.findall(r"\{([WUBRG])\}", mm.group(1)))
                produce = "".join(c for c in "WUBRG" if c in got)
                if re.search(r"Add one mana of any (color|one color)", oracle):
                    produce = "WUBRG"
                for basic, c in (("Plains", "W"), ("Island", "U"), ("Swamp", "B"), ("Mountain", "R"), ("Forest", "G")):
                    if basic in types and c not in produce:
                        produce += c
            db[name] = {
                "cost": d.get("ManaCost", ""), "cmc": cmc_of(d.get("ManaCost", "")),
                "colors": colors_of(d.get("ManaCost", ""), None) if not d.get("Colors") else "".join(
                    c for c, word in (("W", "white"), ("U", "blue"), ("B", "black"), ("R", "red"), ("G", "green")) if word in d["Colors"].lower()),
                "types": main, "sub": sub, "super": [t for t in types if t in ("Legendary", "Basic", "Snow")],
                "pt": d.get("PT", ""), "kw": kws, "oracle": oracle, "rar": rar,
                "random": any(a.startswith("RemoveDeck:Random") for a in ai), "produce": produce,
                "sets": sorted(set(s for s, _, _ in printings[name]))[:6],
            }
    json.dump(db, open(CACHE, "w", encoding="utf-8"))
    print("cards:", len(db), "->", CACHE)
    return db


def load():
    if not os.path.exists(CACHE):
        return build()
    return json.load(open(CACHE, encoding="utf-8"))


if __name__ == "__main__":
    build()
