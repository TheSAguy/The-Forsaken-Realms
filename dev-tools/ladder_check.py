"""Re-derives the round-183 ladder percentages straight from the shipped table, the way
SpawnTierWeighting.targetTierWeight() does: max(0, base + delta) * scale. Confirms the numbers in
the changelog/release notes, and that no hostile row can conjure a rank the week bracket zeroes."""
import json, re, sys

P = (r"F:\FORGE\C--Users-vicwaver-MTG-Forge\forge-gui\res\adventure\The Forsaken Realms"
     r"\config tables\spawn_tier_weighting.json")
raw = open(P, encoding="utf-8").read()
raw = re.sub(r"^\s*//.*$", "", raw, flags=re.M)          # line comments, as libGDX's reader tolerates
d = json.loads(raw)

TIERS = ["common", "uncommon", "rare", "mythic"]
NAMES = ["Apprentice", "Adept", "Master", "Archmage"]
ROWS = ["PLAYER_OWNED", "PARTNER", "HAPPY", "WASTELAND", "NEUTRAL", "UNHAPPY", "WAR"]


def weights(bracket, row):
    out = []
    for t in TIERS:
        base = float(bracket[t])
        delta = float(row.get(t, 0)) if row else 0.0
        scale = float(row.get(t + "Scale", 1.0)) if row else 1.0
        out.append(max(0.0, base + delta) * scale)
    # ... then renormalized to the bracket's own total, as targetTierWeight() does, so the row
    # changes the MIX without changing how often an exempt entry (a boss) wins a roll.
    total = sum(out)
    bracket_total = sum(float(bracket[t]) for t in TIERS)
    if total > 0:
        out = [x * bracket_total / total for x in out]
    return out


def show(week_label, bracket):
    print("\n== week bracket %s (%s) ==" % (week_label, "/".join(str(bracket[t]) for t in TIERS)))
    print("%-14s %s" % ("land", "  ".join("%9s" % n for n in NAMES)))
    for key in ROWS:
        w = weights(bracket, d["territoryDeltas"].get(key))
        tot = sum(w)
        pct = ["%8.1f%%" % (100 * x / tot) if tot else "      -  " for x in w]
        print("%-14s %s   (row total %5.1f)" % (key, "  ".join(pct), tot))


brackets = {("%d-%s" % (b["weekMin"], "on" if b["weekMax"] == -1 else b["weekMax"])): b
            for b in d["weekBrackets"]}
for label in ["1-1", "4-6", "21-on"]:
    show(label, brackets[label])

# The guarantee that matters: a zero in the week bracket stays zero on every row.
bad = []
for label, b in brackets.items():
    for i, t in enumerate(TIERS):
        if float(b[t]) != 0:
            continue
        for key in ROWS:
            if weights(b, d["territoryDeltas"].get(key))[i] > 0:
                bad.append((label, NAMES[i], key))
print("\nweek-bracket zeros that a territory row re-opens:", bad if bad else "none - the calendar still gates ranks")
sys.exit(1 if bad else 0)
