"""Round 203 verification: with the rarity filter relaxed, how often does a deckCard entry still
come up short, and how much gold does the fallback pay?

Reuses deckcard_loot_coverage's edition/deck parsing. For each deckCard entry it builds the exact
distribution of "how many DISTINCT names of this deck survive the colour shard" - the survivals are
independent Bernoulli trials per card name, so a Poisson-binomial DP over them is exact, not a
sample - and reads off P(survivors < wanted) plus the expected shortfall.

    stage 1 pool: deck names printed at an ALLOWED RARITY in one of the shard's editions
    stage 2 pool: deck names printed at ANY rarity in one of the shard's editions (the relaxation)

KNOWN OPTIMISM: cardTypes on an entry (the Bear's "rare Land" reward, say) is NOT modelled - card
types are not in res/editions - so real pools are somewhat smaller and real shortfalls somewhat more
common than the numbers below. Treat these as a floor.
"""
import json
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import deckcard_loot_coverage as cov   # noqa: E402

PLANE = cov.PLANE
GOLD = 50

cards_by_edition, boosterish = cov.load_editions()
booster = [c for c, ok in boosterish.items() if ok]
colour_pool = max(1, len(booster) - 12)
shard = max(1, colour_pool // 5)

# name -> editions printing it, by rarity class
any_rarity = {}
by_rarity = {}
for code in booster:
    for name, letter in cards_by_edition[code].items():
        any_rarity[name] = any_rarity.get(name, 0) + 1
        by_rarity.setdefault(letter, {})
        by_rarity[letter][name] = by_rarity[letter].get(name, 0) + 1

LETTER = {v: k for k, v in cov.RARITY_LETTER.items()}


def printings_for(rarity_list):
    """name -> how many booster editions print it at one of these rarities (None = any)."""
    if not rarity_list:
        return any_rarity
    letters = set()
    for r in rarity_list:
        key = str(r).strip().lower()
        for full, let in LETTER.items():
            if full.lower().replace(" ", "") == key.replace(" ", ""):
                letters.add(let)
    out = {}
    for let in letters:
        for name, n in by_rarity.get(let, {}).items():
            out[name] = out.get(name, 0) + n
    return out


def shortfall_stats(probs, wanted):
    """(P(survivors < wanted), expected number of cards short) for independent survivals."""
    # Poisson-binomial DP, truncated at `wanted` - everything at or above it is a full payout.
    dist = [1.0] + [0.0] * wanted
    for p in probs:
        for k in range(wanted, 0, -1):
            dist[k] = dist[k] * (1 - p) + dist[k - 1] * p
        dist[0] *= (1 - p)
    p_short = sum(dist[:wanted])
    exp_short = sum(dist[k] * (wanted - k) for k in range(wanted))
    return p_short, exp_short


enemies = json.load(open(os.path.join(PLANE, "world", "enemies.json"), encoding="utf-8"))
entries = 0
strict_short_p = 0.0
relaxed_short_p = 0.0
strict_cards = 0.0
relaxed_cards = 0.0
gold_per_duel = {}
worst = []

for e in enemies:
    if not isinstance(e, dict):
        continue
    decks = e.get("deck") or []
    if isinstance(decks, str):
        decks = [decks]
    names = set()
    for d in decks:
        names |= cov.load_deck(os.path.join(PLANE, d))
    if not names:
        continue
    duel_gold = 0.0
    for r in (e.get("rewards") or []):
        if not isinstance(r, dict) or r.get("type") != "deckCard":
            continue
        wanted = int(r.get("count", 1) or 1)
        if wanted <= 0:
            continue
        prob = float(r.get("probability", 1) or 0)
        entries += 1
        strict_tab = printings_for(r.get("rarity"))
        ps = [cov.survival_probability(strict_tab.get(n, 0), colour_pool, shard) for n in names]
        pr = [cov.survival_probability(any_rarity.get(n, 0), colour_pool, shard) for n in names]
        s_p, s_e = shortfall_stats(ps, wanted)
        r_p, r_e = shortfall_stats(pr, wanted)
        strict_short_p += s_p
        relaxed_short_p += r_p
        strict_cards += s_e
        relaxed_cards += r_e
        duel_gold += prob * r_e * GOLD
        if r_e > 0.5:
            worst.append((r_e, e.get("name"), wanted, str(r.get("rarity"))))
    if duel_gold > 0.01:
        gold_per_duel[e.get("name")] = duel_gold

print("booster editions %d   colour pool %d   shard ~%d   deckCard entries analysed %d"
      % (len(booster), colour_pool, shard, entries))
print()
print("P(entry cannot pay in full)   strict rarity: %5.1f%%    after relaxing: %5.1f%%"
      % (100.0 * strict_short_p / entries, 100.0 * relaxed_short_p / entries))
print("expected cards short/entry    strict rarity: %6.3f     after relaxing: %6.3f"
      % (strict_cards / entries, relaxed_cards / entries))
print()
print("So the relaxation removes %.0f%% of the shortfall; the gold fallback covers the rest."
      % (100.0 * (1 - relaxed_cards / strict_cards)))
print()
vals = sorted(gold_per_duel.values())
if vals:
    print("enemies that would ever pay fallback gold: %d of %d"
          % (len(vals), sum(1 for e in enemies if isinstance(e, dict) and e.get("deck"))))
    print("expected fallback gold per duel at %dg/card: median %.1f  p90 %.1f  max %.1f"
          % (GOLD, vals[len(vals) // 2], vals[int(0.9 * len(vals))], vals[-1]))
    print("  (median enemy gold reward in enemies.json is 50)")
worst.sort(reverse=True)
print()
print("worst remaining entries after relaxation (expected cards short):")
for exp, name, wanted, rar in worst[:8]:
    print("   %-32s wants %d %-26s short %.2f -> %.0fg" % (str(name)[:32], wanted, rar, exp, exp * GOLD))
