#!/usr/bin/env python3
"""Shop rarity audit (round 361): does each card shop's pool fit the build-menu tier it sits in?

Usage (from the repo root):
    python dev-tools/shop_rarity_audit.py [--all] ["forge-gui/res/adventure/The Forsaken Realms"]
Read-only. Prints every shop type the player can build, its tier (the player templates' commonShopList ..
mythicShopList - the same lookup EconomyBuildings.playerTemplateTier makes, and the blueprint price with it) and the
rarity mix of the cards its filters can reach. Flags a Common/Uncommon type whose pool is mostly Rare + Mythic.
--all prints every type, not only the flagged ones.

WHY THIS EXISTS. The player's report (round 361): "Al's Dose of Apotheosis" (the Gods shop) sold only Gods and
Demigods at 300-500 gold a card, yet its blueprint cost 20 shards - it sat on the Common list of both player
templates (and of 48 maps). No shop filter in shops.json names a rarity, so a shop's rarity is simply the rarity of
the cards its filters (subTypes / cardText / colors / cardTypes / ...) can reach.

Approximation: every card in cardsfolder that has an edition printing, at its LOWEST printed rarity (a reprint at
Common counts as Common), filters matched the way RewardData reads them (colors any-of unless matchAllColors,
cardText as a regex on the oracle text, types/subTypes/superTypes any-of, colorType MultiColor/Colorless). The
plane's edition restrictions are ignored, so pools read larger than in play; the mix is what matters.
"""
import collections
import glob
import os
import re
import sys
import xml.etree.ElementTree as ET
import json

REPO = os.path.normpath(os.path.join(os.path.dirname(os.path.abspath(__file__)), ".."))
ARGS = [a for a in sys.argv[1:] if not a.startswith("--")]
SHOW_ALL = "--all" in sys.argv
PLANE = ARGS[0] if ARGS else os.path.join(REPO, "forge-gui", "res", "adventure", "The Forsaken Realms")
RES = os.path.join(REPO, "forge-gui", "res")
TIERS = ("commonShopList", "uncommonShopList", "rareShopList", "mythicShopList")
TIER_NAME = dict(zip(TIERS, ("Common", "Uncommon", "Rare", "Mythic")))
PLAYER = ("towns/player_town.tmx", "towns/player_capital.tmx")
RANK = {"C": 1, "U": 2, "R": 3, "M": 4, "S": 3}
COLOR = {"white": "W", "blue": "U", "black": "B", "red": "R", "green": "G"}


def load_json(path):
    return json.loads(re.sub(r"^\s*//.*$", "", open(path, encoding="utf-8").read(), flags=re.M))


def player_tiers():
    tier = {}
    for rel in PLAYER:
        for prop in ET.parse(os.path.join(PLANE, "maps", "map", rel)).getroot().iter("property"):
            if prop.get("name") in TIERS:
                names = [n.strip() for n in (prop.get("value") or prop.text or "").split(",") if n.strip()]
                if len(names) > 1:  # single-name lists are fixed shops, never chooser slots
                    for n in names:
                        prev = tier.get(n)
                        if prev is None or TIERS.index(prop.get("name")) < TIERS.index(prev):
                            tier[n] = prop.get("name")
    return tier


def load_cards():
    rarity = {}
    for f in os.listdir(os.path.join(RES, "editions")):
        for ln in open(os.path.join(RES, "editions", f), encoding="utf-8", errors="replace"):
            m = re.match(r"^\S+\s+([CURMS])\s+(.+?)(\s+@.*)?$", ln.strip())
            if m:
                name, r = m.group(2).strip(), m.group(1)
                if name not in rarity or RANK[r] < RANK[rarity[name]]:
                    rarity[name] = r
    cards = []
    for root, _, files in os.walk(os.path.join(RES, "cardsfolder")):
        for f in files:
            if not f.endswith(".txt"):
                continue
            d = {}
            txt = open(os.path.join(root, f), encoding="utf-8", errors="replace").read()
            for ln in txt.split("\nALTERNATE")[0].splitlines():
                if ":" in ln:
                    k, v = ln.split(":", 1)
                    if k in ("Name", "ManaCost", "Types", "Oracle", "Colors") and k not in d:
                        d[k] = v.strip()
            name = d.get("Name")
            if not name or name not in rarity:
                continue
            cost = d.get("ManaCost", "")
            cols = set(c for c in (d.get("Colors") or "").upper() if c in "WUBRG") if d.get("Colors") else \
                set(c for c in cost.upper().replace("P", "") if c in "WUBRG")
            types = d.get("Types", "").split()
            cards.append({"name": name, "rarity": rarity[name], "colors": cols, "types": set(types),
                          "oracle": d.get("Oracle", "").replace("\\n", "\n")})
    return cards


def matches(card, f):
    if f.get("cardName") and card["name"] != f["cardName"]:
        return False
    if f.get("colors"):
        want = set(COLOR.get(c.lower(), "?") for c in f["colors"])
        if str(f.get("matchAllColors", "")).lower() == "true":
            if not want <= card["colors"]:
                return False
        elif not (want & card["colors"]):
            return False
    ct = f.get("colorType")
    if ct == "MultiColor" and len(card["colors"]) < 2:
        return False
    if ct == "Colorless" and card["colors"]:
        return False
    for key in ("cardTypes", "subTypes", "superTypes"):
        if f.get(key) and not (set(f[key]) & card["types"]):
            return False
    if f.get("cardText"):
        try:
            if not re.search(f["cardText"], card["oracle"]):
                return False
        except re.error:
            return False
    return True


def pool(cards, reward):
    if reward.get("type") not in ("Union", "card", None):
        return None
    filters = reward.get("cardUnion") or [reward]
    return [c for c in cards if any(matches(c, f) for f in filters)]


def main():
    tier = player_tiers()
    cards = load_cards()
    shops = {s["name"]: s for s in load_json(os.path.join(PLANE, "world", "shops.json"))}
    rows = []
    for name, t in tier.items():
        shop = shops.get(name)
        if not shop:
            continue
        reach = {}
        for r in shop.get("rewards", []):
            p = pool(cards, r)
            if p:
                for c in p:
                    reach[c["name"]] = c["rarity"]
        if not reach:
            continue
        mix = collections.Counter(("R" if r == "S" else r) for r in reach.values())
        n = sum(mix.values())
        high = (mix["R"] + mix["M"]) / n
        flag = TIER_NAME[t] in ("Common", "Uncommon") and high >= 0.5
        rows.append((TIERS.index(t), -high, name, shop.get("description", ""), n, mix, high, flag))
    rows.sort()
    flagged = 0
    for ti, _, name, desc, n, mix, high, flag in rows:
        flagged += flag
        if flag or SHOW_ALL:
            print("%s %-9s %-22s %-40s pool %5d  C%3d%% U%3d%% R%3d%% M%3d%%  R+M %3d%%" % (
                "!!" if flag else "  ", TIER_NAME[TIERS[ti]], name, desc[:40], n,
                100 * mix["C"] // n, 100 * mix["U"] // n, 100 * mix["R"] // n, 100 * mix["M"] // n, 100 * high))
    print("%d buildable card shop type(s), %d Common/Uncommon type(s) with a pool at least half Rare + Mythic" % (
        len(rows), flagged))


if __name__ == "__main__":
    main()
