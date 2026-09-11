"""deckgen179.py - themed decks for the 197 round-179 enemies, built from Forge's own card data (carddb.py).

For each enemy: its colors, its rank and a THEME (the creature it is). The deck is:
  Apprentice 40 cards (17 lands, commons + a few uncommons, 1-2 copies);
  Adept / Master / Archmage 60 cards (24 lands, 2-4 copies; rarity cap rises with the rank).
Creatures come first from the theme's primary types (an insect plays insects), then its secondary types, then the best
creatures of its colors; spells favor removal and the theme's own payoffs (cards whose text names its types); a mana
curve target shapes both; every color of a multicolor deck is kept represented; lands are basics by pip share plus
dual / tri lands of exactly those colors (better ones at higher ranks).
usage: python deckgen179.py [out dir] [--only slug,slug]  -> <out>/<slug>.dck + deckgen179_report.txt"""
import collections, os, random, re, sys, zlib

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import carddb
from roster179 import ROSTER

OUT = sys.argv[1] if len(sys.argv) > 1 and not sys.argv[1].startswith("--") else r"F:\FORGE\TFR-Art-Staging\decks"
ONLY = set(sys.argv[sys.argv.index("--only") + 1].split(",")) if "--only" in sys.argv else None
DB = carddb.load()

# theme -> (primary creature types, secondary types, extra words the payoff text may name, mechanic keywords)
THEMES = {
    "skeleton": (["Skeleton"], ["Zombie", "Spirit"], ["Skeleton"], []),
    "zombie": (["Zombie"], ["Skeleton", "Horror"], ["Zombie"], ["Decayed", "Exploit"]),
    "mummy": (["Zombie"], ["Cleric", "Spirit"], ["Zombie"], ["Embalm", "Eternalize", "Afflict", "Exert"]),
    "knight_undead": (["Knight"], ["Zombie", "Skeleton", "Spirit"], ["Knight"], []),
    "spirit": (["Spirit"], ["Wizard", "Cleric"], ["Spirit"], ["Disturb", "Flying"]),
    "wraith": (["Spirit", "Wraith", "Specter", "Shade"], ["Horror"], ["Spirit"], []),
    "shade": (["Shade", "Spirit", "Specter"], ["Horror"], ["Shade"], []),
    "angel": (["Angel"], ["Cleric", "Spirit", "Human"], ["Angel"], ["Flying"]),
    "demon": (["Demon"], ["Devil", "Imp", "Horror"], ["Demon"], []),
    "devil": (["Devil"], ["Demon", "Imp"], ["Devil"], []),
    "vampire": (["Vampire"], ["Bat"], ["Vampire", "Blood"], ["Lifelink"]),
    "dragon": (["Dragon"], ["Drake", "Wyvern"], ["Dragon"], ["Flying"]),
    "drake": (["Drake", "Wyvern"], ["Dragon", "Bird"], ["Drake"], ["Flying"]),
    "phoenix": (["Phoenix"], ["Bird", "Elemental"], ["Phoenix"], ["Haste", "Flying"]),
    "insect": (["Insect"], ["Spider", "Scorpion"], ["Insect"], []),
    "spider": (["Spider"], ["Insect"], ["Spider"], ["Reach", "Deathtouch"]),
    "beast": (["Beast"], ["Boar", "Bear", "Cat", "Wolf", "Elephant"], ["Beast"], ["Trample"]),
    "wolf": (["Wolf", "Werewolf"], ["Beast", "Elf"], ["Wolf"], []),
    "cat": (["Cat"], ["Beast"], ["Cat"], []),
    "ape": (["Ape", "Monkey"], ["Beast"], ["Ape"], []),
    "yeti": (["Yeti", "Ape"], ["Beast", "Giant"], ["Snow"], []),
    "bird": (["Bird"], ["Spirit", "Soldier"], ["Bird"], ["Flying"]),
    "griffin": (["Griffin"], ["Bird", "Hippogriff"], ["Griffin"], ["Flying"]),
    "harpy": (["Harpy"], ["Bird", "Bat"], ["Harpy"], ["Flying"]),
    "bat": (["Bat"], ["Vampire"], ["Bat"], ["Flying", "Lifelink"]),
    "frog": (["Frog"], ["Lizard", "Salamander"], ["Frog"], []),
    "turtle": (["Turtle"], ["Crab", "Fish"], ["Turtle"], []),
    "ooze": (["Ooze"], ["Elemental"], ["Ooze"], []),
    "plant": (["Plant"], ["Treefolk", "Fungus", "Dryad"], ["Plant"], []),
    "fungus": (["Fungus", "Saproling"], ["Plant"], ["Fungus", "Saproling"], []),
    "treefolk": (["Treefolk"], ["Plant", "Dryad"], ["Treefolk", "Forest"], []),
    "elemental": (["Elemental"], ["Giant", "Golem"], ["Elemental"], []),
    "merfolk": (["Merfolk", "Siren"], ["Fish", "Serpent"], ["Merfolk"], []),
    "sea": (["Fish", "Crab", "Octopus", "Jellyfish"], ["Serpent", "Leviathan", "Kraken", "Merfolk"], ["Island"], []),
    "shark": (["Shark", "Fish"], ["Serpent", "Leviathan"], ["Shark"], []),
    "orc": (["Orc"], ["Goblin", "Warrior"], ["Orc", "Army"], ["Amass"]),
    "minotaur": (["Minotaur"], ["Warrior", "Berserker"], ["Minotaur"], []),
    "giant": (["Giant", "Cyclops"], ["Ogre", "Warrior"], ["Giant"], []),
    "ogre": (["Ogre"], ["Giant", "Demon"], ["Ogre"], []),
    "soldier": (["Soldier"], ["Knight", "Human"], ["Soldier"], []),
    "knight": (["Knight"], ["Soldier", "Angel"], ["Knight"], []),
    "warrior": (["Warrior"], ["Samurai", "Berserker"], ["Warrior"], []),
    "samurai": (["Samurai"], ["Warrior", "Monk"], ["Samurai"], ["Bushido"]),
    "monk": (["Monk"], ["Human", "Warrior"], ["Monk"], ["Prowess"]),
    "cleric": (["Cleric"], ["Human", "Angel"], ["Cleric"], ["Lifelink"]),
    "wizard": (["Wizard"], ["Shaman", "Warlock"], ["Wizard"], []),
    "rogue": (["Rogue"], ["Faerie", "Bird"], ["Rogue"], []),
    "pirate": (["Pirate"], ["Skeleton", "Zombie"], ["Pirate", "Treasure"], []),
    "construct": (["Construct", "Golem", "Thopter", "Myr"], ["Artificer"], ["artifact"], []),
    "horror": (["Horror", "Nightmare", "Eldrazi"], ["Beast"], ["Horror"], []),
    "gorgon": (["Gorgon"], ["Snake", "Naga"], ["Gorgon"], ["Deathtouch"]),
    "snake": (["Snake", "Naga"], ["Lizard"], ["Snake"], ["Deathtouch"]),
    "lizard": (["Lizard", "Salamander"], ["Dinosaur", "Frog"], ["Lizard"], []),
    "dinosaur": (["Dinosaur"], ["Beast", "Lizard"], ["Dinosaur"], ["Enrage"]),
    "rat": (["Rat"], ["Zombie"], ["Rat"], []),
    "squirrel": (["Squirrel"], ["Rabbit", "Rat"], ["Squirrel", "Food"], []),
    "worm": (["Worm", "Wurm"], ["Insect"], ["Worm", "Wurm"], []),
    "faerie": (["Faerie"], ["Sprite", "Spirit"], ["Faerie"], ["Flying", "Flash"]),
    "satyr": (["Satyr"], ["Faun", "Centaur"], ["Satyr"], []),
    "tengu": (["Bird"], ["Spirit", "Monk"], ["Spirit"], ["Flying"]),
    "loxodon": (["Elephant"], ["Soldier", "Cleric"], ["Elephant"], []),
}
CARDS = {"A": (40, 17, 15, 1, 2), "D": (60, 24, 21, 2, 3), "M": (60, 24, 20, 3, 4), "X": (60, 24, 19, 4, 4)}
# rank -> max card rarity (1 C, 2 U, 3 R, 4 M) and how many cards above that cap may slip in (one step up)
CAP = {"A": (1, 4), "D": (2, 5), "M": (3, 3), "X": (4, 0)}
REMOVAL = re.compile(r"(destroy target (creature|nonblack|nonartifact|nonwhite|attacking|blocking|tapped|artifact or creature|creature or planeswalker|permanent)(?! you (own|control))|"
                     r"exile target (creature|nonland|permanent|attacking)(?! you (own|control))|deals? (\d+|x) damage to (any target|target creature|target attacking|each creature|target player or planeswalker and)|"
                     r"target creature (an opponent controls )?gets -\d+/-\d+|gets -x/-x|fights? (target|up to one target|another target)|"
                     r"counter target (spell|creature spell|noncreature spell))", re.I)
DRAW = re.compile(r"draw (two|three|a) cards?|investigate|scry \d", re.I)
# drawbacks the AI plays badly or that hand the opponent value: a creature with one of these is all but excluded
BAD = re.compile(r"you lose the game|at the beginning of your upkeep, sacrifice|sacrifice it at the beginning|"
                 r"can't attack or block|skip your|you can't win the game|target opponent creates|"
                 r"where x is your life total|sacrifice it unless|sacrifice any number of creatures with total power|"
                 r"an opponent gains control|each opponent creates|"
                 r"can't attack unless|when you control no|as long as you control no", re.I)
# chance cards: playable, just less reliable
CHANCE = re.compile(r"random|coin|d20|roll a", re.I)
BAD_OK = {"Lovestruck Beast"}                        # its "can't attack unless" costs nothing in practice
NEVER = {"Death's Shadow", "Phyrexian Dreadnought", "Hunted Horror", "Hunted Dragon", "Hunted Troll", "Hunted Wumpus",
         "Hunted Phantasm", "Hunted Lammasu", "Force of Savagery", "Tarmogoyf", "Sleeper Agent", "Eater of Days",
         "Worldgorger Dragon", "Lich's Mirror", "Transcendence", "Coalition Victory", "Laboratory Maniac", "Uba Mask",
         "Dandân", "Manta Ray", "Sealock Monster", "Veiled Serpent", "Leviathan", "Tidal Kraken", "Island Fish Jasconius"}


def pt(c):
    try:
        p, t = c["pt"].split("/")
        return (int(p) if p.isdigit() else 1), (int(t) if t.isdigit() else 1)
    except Exception:
        return 1, 1


def castable(c, colors):
    return all(ch in colors for ch in c["colors"])


def score(c, theme, colors, name=""):
    prim, sec, words, mech = THEMES[theme]
    s = c["rar"] * 0.5
    o = c["oracle"]
    if "Creature" in c["types"]:
        p, t = pt(c)
        cmc = max(1, c["cmc"])
        s += min(2.6, (p + t) / (cmc * 1.5))      # capped: a 12/12 for one is a drawback card, not a bargain
        kw = set(c["kw"])
        s += 0.5 * len(kw & {"Flying", "Deathtouch", "Double Strike", "Indestructible", "Hexproof"})
        s += 0.3 * len(kw & {"Trample", "Lifelink", "First Strike", "Haste", "Vigilance", "Menace", "Reach", "Ward"})
        if re.search(r"when .{0,40}enters", o, re.I):
            s += 0.5
        if set(c["sub"]) & set(prim):
            s += 2.6
        elif set(c["sub"]) & set(sec):
            s += 1.1
    if REMOVAL.search(o):
        s += 2.2 if "Creature" not in c["types"] else 0.8
    if DRAW.search(o):
        s += 0.7
    if any(re.search(r"\b%s" % re.escape(w), o) for w in words) and not ("Creature" in c["types"] and set(c["sub"]) & set(prim) and o.count(words[0]) <= 1 and False):
        s += 1.3
    if any(m in c["kw"] or m.lower() in o.lower() for m in mech):
        s += 0.6
    if c["random"]:
        s -= 1.2
    if c["cmc"] >= 7:
        s -= 0.9 + 0.3 * (c["cmc"] - 7)
    if BAD.search(o) and name not in BAD_OK:
        s -= 6.0 if "Creature" in c["types"] else 2.0
    elif CHANCE.search(o):
        s -= 1.0
    if "Defender" in c["kw"]:
        s -= 1.0
    if len(c["colors"]) < len(colors) and len(colors) > 1:
        s += 0.05 * len(c["colors"])
    return s


def pool_for(colors, rank):
    cap, extra = CAP[rank]
    out = []
    for name, c in DB.items():
        if "Land" in c["types"] or "Basic" in c["super"] or name in NEVER:
            continue
        if not castable(c, colors) or c["cost"] in ("", "no cost"):
            continue
        if not c["colors"] and "Creature" in c["types"] and not set(c["sub"]) & {"Construct", "Golem", "Thopter", "Myr", "Eldrazi"}:
            continue                                  # colorless creatures only for construct / horror themes
        if c["rar"] > cap + 1:
            continue
        if "Planeswalker" in c["types"] and rank in ("A", "D"):
            continue
        out.append(name)
    return out


def pick_lands(colors, rank, n_lands, rnd):
    lands = []
    if len(colors) > 1:
        want = {"A": 1, "D": 3, "M": 5, "X": 7}[rank] if len(colors) == 2 else {"A": 2, "D": 4, "M": 6, "X": 8}[rank]
        cands = []
        for name, c in DB.items():
            if "Land" not in c["types"] or "Basic" in c["super"]:
                continue
            prod = c["produce"]
            if not prod or len(prod) < 2 or any(ch not in colors for ch in prod):
                continue
            bonus = (c["rar"] if rank in ("M", "X") else -c["rar"]) + (1.0 if len(prod) == len(colors) else 0)
            if "enters tapped" in c["oracle"] and rank in ("M", "X"):
                bonus -= 1.0
            if c["random"] or "sacrifice" in c["oracle"].lower():
                bonus -= 3
            cands.append((bonus + rnd.random() * 0.3, name, prod))
        # each pick favors the color with the fewest sources so far - three RW duals leave a Mardu deck no black
        src = collections.Counter()
        while cands and sum(k for k, _ in lands) < want:
            low = min(src[ch] for ch in colors)
            best = max(cands, key=lambda t: t[0] + 1.5 * sum(1 for ch in t[2] if src[ch] == low))
            cands.remove(best)
            cnt = min(4 if rank != "A" else 1, want - sum(k for k, _ in lands))
            lands.append((cnt, best[1]))
            for ch in best[2]:
                if ch in colors:
                    src[ch] += cnt
    return lands


def build(slug, name, rank, colors, theme, rnd):
    total, n_lands, n_creat, lo_copies, hi_copies = CARDS[rank]
    n_spells = total - n_lands
    cap, extra = CAP[rank]
    pool = pool_for(colors, rank)
    scored = sorted(((score(DB[n], theme, colors, n) + rnd.random() * 0.25, n) for n in pool), reverse=True)
    creatures = [(s, n) for s, n in scored if "Creature" in DB[n]["types"]]
    spells = [(s, n) for s, n in scored if "Creature" not in DB[n]["types"]]
    over_cap = [0]
    color_count = collections.Counter()

    def ok(n):
        c = DB[n]
        if c["rar"] > cap:
            if over_cap[0] >= extra:
                return False
        return True

    def take(n):
        if DB[n]["rar"] > cap:
            over_cap[0] += 1
        for ch in DB[n]["colors"]:
            color_count[ch] += 1

    main = collections.OrderedDict()
    # creatures by curve bucket
    buckets = {1: 2, 2: 5, 3: 5, 4: 4, 5: 3, 6: 2} if rank != "A" else {1: 2, 2: 4, 3: 4, 4: 3, 5: 1, 6: 1}
    need = n_creat
    per_card = hi_copies if rank != "A" else 2
    for cmc in (2, 3, 4, 1, 5, 6):
        slots = round(buckets[cmc] * n_creat / sum(buckets.values()))
        cands = [(s, n) for s, n in creatures if min(max(DB[n]["cmc"], 1), 6) == cmc and n not in main]
        # keep every color represented: boost the under-used colors
        while slots > 0 and cands and need > 0:
            cands.sort(key=lambda sn: sn[0] + sum(0.4 for ch in DB[sn[1]]["colors"] if color_count[ch] < 3), reverse=True)
            s, n = cands.pop(0)
            if not ok(n):
                continue
            copies = min(per_card, slots, need)
            if "Legendary" in DB[n]["super"]:
                copies = min(copies, 1 if rank == "A" else 2)
            if s < 2.0 and copies > lo_copies:
                copies = lo_copies
            main[n] = copies
            take(n)
            slots -= copies
            need -= copies
    for s, n in creatures:                      # top up with the best remaining creatures
        if need <= 0:
            break
        if n in main or not ok(n):
            continue
        copies = min(lo_copies, need)
        main[n] = copies
        take(n)
        need -= copies
    spells_left = n_spells - sum(main.values())
    removal = [(s, n) for s, n in spells if REMOVAL.search(DB[n]["oracle"]) and DB[n]["cmc"] <= 5]
    others = [(s, n) for s, n in spells if (s, n) not in removal and DB[n]["cmc"] <= 5]
    for group, share in ((removal, 0.6), (others, 1.0)):
        quota = round(spells_left * share) if share < 1 else spells_left
        for s, n in group:
            if quota <= 0 or spells_left <= 0:
                break
            if n in main or not ok(n):
                continue
            if "Planeswalker" in DB[n]["types"] and sum(1 for m in main if "Planeswalker" in DB[m]["types"]) >= 2:
                continue
            copies = min(hi_copies if s >= 3.0 else lo_copies, quota, spells_left)
            if "Legendary" in DB[n]["super"]:
                copies = min(copies, 2)
            main[n] = copies
            take(n)
            quota -= copies
            spells_left -= copies
    # every color of a multicolor deck keeps a real share: while one is under its floor, the weakest card without it
    # makes way for the best card with it (the theme pool can be all one color - white sea creatures are rare)
    if len(colors) > 1:
        floor = 3 if rank == "A" else 6
        sc = {n: s for s, n in scored}
        for ch in colors:
            for _ in range(12):
                have = sum(k for n, k in main.items() if ch in DB[n]["colors"])
                if have >= floor:
                    break
                share = {c2: sum(k for m, k in main.items() if c2 in DB[m]["colors"]) for c2 in colors}
                victims = sorted((n for n in main if ch not in DB[n]["colors"]
                                  and all(share[c2] - main[n] >= floor for c2 in DB[n]["colors"])),
                                 key=lambda n: sc.get(n, 0))
                # same kind in, same kind out: a creature makes way for a creature, a spell for a spell - otherwise
                # the high-scoring theme creatures push every removal spell out of a three-color deck
                v = cand = None
                for vic in victims:
                    is_creature = "Creature" in DB[vic]["types"]
                    cand = next((n for s, n in scored if ch in DB[n]["colors"] and n not in main and ok(n)
                                 and "Land" not in DB[n]["types"] and DB[n]["cmc"] <= 5
                                 and ("Creature" in DB[n]["types"]) == is_creature), None)
                    if cand:
                        v = vic
                        break
                if not cand:
                    break
                k = main.pop(v)
                main[cand] = min(k, 2 if "Legendary" in DB[cand]["super"] else k)
                take(cand)
    # top up to the exact size (a swap to a legendary can leave a copy or two short): more copies of the best
    # non-legendary cards first, then new cards
    sc2 = {n: s for s, n in scored}
    for n in sorted(main, key=lambda m: -sc2.get(m, 0)):
        short = n_spells - sum(main.values())
        if short <= 0:
            break
        if "Legendary" not in DB[n]["super"] and main[n] < hi_copies:
            main[n] += min(short, hi_copies - main[n])
    for s, n in scored:
        short = n_spells - sum(main.values())
        if short <= 0:
            break
        if n not in main and ok(n) and DB[n]["cmc"] <= 5 and "Legendary" not in DB[n]["super"]:
            main[n] = min(short, lo_copies)
            take(n)
    # lands: duals of exactly these colors, then basics by pip share
    lands = pick_lands(colors, rank, n_lands, rnd)
    basics_n = n_lands - sum(k for k, _ in lands)
    pips = collections.Counter()
    for n, k in main.items():
        for tok in DB[n]["cost"].upper().split():
            for ch in tok:
                if ch in "WUBRG":
                    pips[ch] += k
    for ch in colors:
        pips[ch] = max(pips[ch], 1)
    tot = sum(pips[ch] for ch in colors)
    BASIC = {"W": "Plains", "U": "Island", "B": "Swamp", "R": "Mountain", "G": "Forest"}
    # basics make up each color's pip share of ALL the lands, counting what the duals already give it
    dual_src = collections.Counter()
    for k, n in lands:
        for ch in DB[n]["produce"]:
            if ch in colors:
                dual_src[ch] += k
    least = basics_n if len(colors) == 1 else 3
    alloc = {ch: max(least, int(round(n_lands * pips[ch] / tot - dual_src[ch]))) for ch in colors}
    while sum(alloc.values()) > basics_n:
        over = [ch for ch in colors if alloc[ch] > least] or list(colors)
        alloc[max(over, key=lambda ch: alloc[ch] + dual_src[ch])] -= 1
    while sum(alloc.values()) < basics_n:
        alloc[max(colors, key=lambda ch: n_lands * pips[ch] / tot - dual_src[ch] - alloc[ch])] += 1
    lines = ["[metadata]", "Name=%s" % name, "[Main]"]
    for n, k in main.items():
        lines.append("%d %s" % (k, n))
    for k, n in lands:
        lines.append("%d %s" % (k, n))
    for ch in colors:
        if alloc[ch] > 0:
            lines.append("%d %s" % (alloc[ch], BASIC[ch]))
    count = sum(main.values()) + sum(k for k, _ in lands) + sum(alloc.values())
    theme_hits = sum(k for n, k in main.items() if set(DB[n]["sub"]) & set(THEMES[theme][0]))
    return lines, count, theme_hits, main


def main():
    os.makedirs(OUT, exist_ok=True)
    report = []
    for slug, name, rank, colors, theme, tags in ROSTER:
        if ONLY and slug not in ONLY:
            continue
        rnd = random.Random(zlib.crc32(slug.encode("utf-8")))     # stable across runs (str hash() is salted)
        lines, count, hits, main_ = build(slug, name, rank, colors, theme, rnd)
        open(os.path.join(OUT, slug + ".dck"), "w", encoding="utf-8", newline="\n").write("\n".join(lines) + "\n")
        report.append("%-34s %s %-4s %-13s %3d cards, %2d on-theme creatures | %s" % (
            name[:34], rank, colors, theme, count, hits, ", ".join("%d %s" % (k, n) for n, k in list(main_.items())[:10])))
    open(os.path.join(OUT, "deckgen179_report.txt"), "w", encoding="utf-8").write("\n".join(report) + "\n")
    print("\n".join(report[:12]))
    print("... %d decks -> %s" % (len(report), OUT))


if __name__ == "__main__":
    main()
