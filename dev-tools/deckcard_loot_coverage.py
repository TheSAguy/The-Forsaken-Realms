#!/usr/bin/env python3
"""How badly does the edition gate thin a "deckCard" loot pool, per enemy?

Background
----------
A defeated enemy's reward entries are stamped with that enemy's COLOUR SHARD - roughly a fifth of
every booster-capable edition, assigned randomly at world generation
(EditionProgression.seedColorShards: shuffle, neutral takes 12 off the front, the rest deal
round-robin to the 5 colours). A "deckCard" reward then draws from that ENEMY'S OWN DECK, and a card
survives only if some printing of it exists in one of the shard's editions AT an allowed rarity
(CardUtil's edition predicate tests the two together, then remapToEditionList swaps the printing).

The user hit the endgame of this: Ratfolk Scavenger's deck is entirely CHK/BOK, black's shard held
neither, and exactly one card in the deck - Befoul, via a 7ED reprint - survived. A reward asking for
five cards paid five Befouls.

What this measures
------------------
For every enemy with a deck, the EXPECTED number of distinct card names that survive its own
colour's shard, which is a property of the card pool rather than of one world's random split:

    P(a card survives) = 1 - P(none of the editions it is legally printed in land in the shard)

Each colour's shard is a ~1/5 share of the colour pool, so for a card printed at an allowed rarity in
k distinct colour-pool editions, P(survive) ~= 1 - C(N-k, s) / C(N, s) with s = shard size. Cards
printed in a NEUTRAL edition are a separate case - neutral is a fixed 12-edition slice that belongs to
nobody, so those printings never help a colour-gated drop and are excluded.

Reports the distribution and names the worst decks. Read "expected survivors" as: how many DIFFERENT
cards that enemy can pay out at all.

Usage
-----
    python dev-tools/deckcard_loot_coverage.py [--threshold 3] [--worst 25]
"""

import argparse
import json
import math
import os
import re
import sys
from collections import defaultdict

REPO = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..")
PLANE = os.path.join(REPO, "forge-gui", "res", "adventure", "The Forsaken Realms")
EDITIONS_DIR = os.path.join(REPO, "forge-gui", "res", "editions")

RARITY_LETTER = {"C": "Common", "U": "Uncommon", "R": "Rare", "M": "Mythic Rare",
                 "S": "Special", "L": "Land", "B": "Basic Land"}


def load_editions():
    """(code -> {card name: rarity letter}, code -> is_booster_capable)."""
    cards_by_edition = {}
    boosterish = {}
    for name in os.listdir(EDITIONS_DIR):
        if not name.endswith(".txt"):
            continue
        path = os.path.join(EDITIONS_DIR, name)
        code = None
        has_booster = False
        in_cards = False
        cards = {}
        try:
            fh = open(path, encoding="utf-8", errors="replace")
        except OSError:
            continue
        with fh:
            for line in fh:
                s = line.strip()
                if not in_cards:
                    if s.startswith("Code="):
                        code = s[5:].strip()
                    elif s.startswith("Booster="):
                        has_booster = True
                    elif s.lower() == "[cards]":
                        in_cards = True
                    continue
                if not s or s.startswith("["):
                    continue
                # "1 C Angelic Page @Artist"  /  "12 U Some Card"
                m = re.match(r"^\S+\s+([A-Z])\s+(.+?)(?:\s+@.*)?$", s)
                if m:
                    cards.setdefault(m.group(2).strip(), m.group(1))
        if code:
            cards_by_edition[code] = cards
            boosterish[code] = has_booster
    return cards_by_edition, boosterish


def load_deck(path):
    """Distinct non-basic card names in a .dck main deck."""
    names = set()
    in_main = False
    try:
        fh = open(path, encoding="utf-8", errors="replace")
    except OSError:
        return names
    with fh:
        for line in fh:
            s = line.strip()
            if s.lower().startswith("["):
                in_main = s.lower().startswith("[main]")
                continue
            if not in_main or not s:
                continue
            m = re.match(r"^\d+\s+(.+?)(?:\|.*)?$", s)
            if m:
                name = m.group(1).strip()
                if name not in ("Plains", "Island", "Swamp", "Mountain", "Forest", "Wastes"):
                    names.add(name)
    return names


def survival_probability(k, colour_pool, shard):
    """P(at least one of a card's k colour-pool editions lands in a shard of `shard` editions)."""
    if k <= 0:
        return 0.0
    if k >= colour_pool - shard:
        return 1.0
    # 1 - C(pool-k, shard)/C(pool, shard), computed as a product to stay exact and cheap
    p_none = 1.0
    for i in range(shard):
        p_none *= (colour_pool - k - i) / float(colour_pool - i)
        if p_none <= 0:
            return 1.0
    return 1.0 - p_none


def main():
    ap = argparse.ArgumentParser(description=__doc__,
                                 formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--threshold", type=float, default=3.0,
                    help="a deck is 'collapsed' below this many expected distinct survivors")
    ap.add_argument("--worst", type=int, default=25)
    ap.add_argument("--rarities", default="Common,Uncommon",
                    help="rarities a deckCard reward may pay (default matches the common entry)")
    args = ap.parse_args()

    allowed_rarities = {r.strip() for r in args.rarities.split(",")}
    allowed_letters = {k for k, v in RARITY_LETTER.items() if v in allowed_rarities}

    cards_by_edition, boosterish = load_editions()
    booster_codes = [c for c, ok in boosterish.items() if ok]
    # Shard geometry, from EditionProgression: neutral takes 12, the rest split 5 ways.
    colour_pool = max(1, len(booster_codes) - 12)
    shard = max(1, colour_pool // 5)
    print("booster-capable editions: %d   colour pool: %d   shard size: ~%d"
          % (len(booster_codes), colour_pool, shard))

    # card name -> how many booster-capable editions print it at an allowed rarity
    printings = defaultdict(int)
    for code in booster_codes:
        for name, letter in cards_by_edition[code].items():
            if letter in allowed_letters:
                printings[name] += 1

    enemies = json.load(open(os.path.join(PLANE, "world", "enemies.json"), encoding="utf-8"))
    rows = []
    for e in enemies:
        if not isinstance(e, dict):
            continue
        decks = e.get("deck") or []
        if isinstance(decks, str):
            decks = [decks]
        if not decks:
            continue
        has_deckcard = any(isinstance(r, dict) and r.get("type") == "deckCard"
                           for r in (e.get("rewards") or []))
        if not has_deckcard:
            continue
        names = set()
        for d in decks:
            names |= load_deck(os.path.join(PLANE, d))
        if not names:
            continue
        expected = sum(survival_probability(printings.get(n, 0), colour_pool, shard) for n in names)
        rows.append((expected, len(names), e.get("name"), decks[0]))

    rows.sort()
    total = len(rows)
    if not total:
        print("no enemies with both a deck and a deckCard reward found")
        return 1
    collapsed = [r for r in rows if r[0] < args.threshold]
    print("\nenemies with a deck AND a deckCard reward: %d" % total)
    print("expected DISTINCT survivors per deck:")
    for lo, hi in ((0, 1), (1, 2), (2, 3), (3, 5), (5, 10), (10, 10 ** 6)):
        n = len([r for r in rows if lo <= r[0] < hi])
        print("   %5.1f - %-5s %5d  (%4.1f%%)" % (lo, hi if hi < 10 ** 6 else "+", n, 100.0 * n / total))
    print("\nbelow threshold %.1f: %d of %d (%.1f%%)"
          % (args.threshold, len(collapsed), total, 100.0 * len(collapsed) / total))
    print("\nworst %d decks:" % args.worst)
    print("   %-34s %-8s %-8s %s" % ("enemy", "expect", "distinct", "deck"))
    for expected, n, name, deck in rows[:args.worst]:
        print("   %-34s %6.2f %8d  %s" % (str(name)[:34], expected, n, os.path.basename(deck)))
    return 0


if __name__ == "__main__":
    sys.exit(main())
