#!/usr/bin/env python3
"""Shop blueprint audit (round 350): can every shop type a rival town sells a blueprint for be BUILT by the player?

Usage (from the repo root):
    python dev-tools/shop_blueprint_audit.py ["forge-gui/res/adventure/The Forsaken Realms"]
Exit code 0 when every learnable type is buildable, 1 otherwise. Read-only. Run it after editing any town map's shop
lists or the player templates.

WHY THIS EXISTS. A card shop in a rival town offers "Buy Blueprint" for its type whenever its slot is a real
multi-type slot (a comma list with more than one name), and blueprint drops draw from the same lists. The player then
builds shop types through the chooser in their own towns, whose choices are exactly the tier lists of the player
templates - towns/player_town.tmx and towns/player_capital.tmx. A type a rival lists but the templates do not is a
blueprint the player can buy and never use: a player's "Domain of Dominaria" (WUBRG) report, round 350. That round
found two causes - the chooser ignored mythicShopList (29 types only ever listed there, now the Capitol-only Mythic
tier) and ten Common types (the colored booster shops, the capitals' Instant6 shops) the templates never listed.

Tiers read: commonShopList, uncommonShopList, rareShopList, mythicShopList. Single-name lists are fixed shops (land
shops, Armory tiers) - never chooser slots, never blueprint sources - and are skipped on both sides.
"""
import glob
import os
import sys
import xml.etree.ElementTree as ET
from collections import defaultdict

REPO = os.path.normpath(os.path.join(os.path.dirname(os.path.abspath(__file__)), ".."))
PLANE = sys.argv[1] if len(sys.argv) > 1 else os.path.join(REPO, "forge-gui", "res", "adventure", "The Forsaken Realms")
MAPS = os.path.join(PLANE, "maps", "map")
TIERS = {"commonShopList": "Common", "uncommonShopList": "Uncommon", "rareShopList": "Rare",
         "mythicShopList": "Mythic"}
PLAYER = ("towns/player_town.tmx", "towns/player_capital.tmx")


def tier_lists(path):
    """tier -> the names on that tier's multi-name lists in one map."""
    out = defaultdict(set)
    for obj in ET.parse(path).getroot().iter("object"):
        for prop in obj.iter("property"):
            tier = TIERS.get(prop.get("name"))
            if not tier:
                continue
            names = [n.strip() for n in (prop.get("value") or prop.text or "").split(",") if n.strip()]
            if len(names) > 1:
                out[tier].update(names)
    return out


def main():
    player = defaultdict(set)  # name -> tiers across both templates
    for rel in PLAYER:
        for tier, names in tier_lists(os.path.join(MAPS, rel)).items():
            for name in names:
                player[name].add(tier)
    rival = defaultdict(lambda: defaultdict(set))  # name -> tier -> maps
    for path in sorted(glob.glob(os.path.join(MAPS, "**", "*.tmx"), recursive=True)):
        rel = os.path.relpath(path, MAPS).replace(os.sep, "/")
        if rel in PLAYER:
            continue
        try:
            lists = tier_lists(path)
        except ET.ParseError:
            print("  unreadable map skipped: " + rel)
            continue
        for tier, names in lists.items():
            for name in names:
                rival[name][tier].add(rel)
    missing = sorted(n for n in rival if n not in player)
    mythic_only = sorted(n for n, tiers in player.items() if tiers == {"Mythic"})
    print("rival shop types (blueprint sources): %d | buildable in the player templates: %d" % (len(rival), len(player)))
    print("buildable only in the Capitol's Mythic tier: %d" % len(mythic_only))
    print("\n== learnable but NOT buildable (%d)" % len(missing))
    for name in missing:
        where = "; ".join("%s: %s" % (t, ", ".join(sorted(m)[:2]) + (" ..." if len(m) > 2 else ""))
                          for t, m in sorted(rival[name].items()))
        print("  %-26s %s" % (name, where))
    print("\n%s" % ("OK" if not missing else "%d problem(s)" % len(missing)))
    return 1 if missing else 0


if __name__ == "__main__":
    sys.exit(main())
