r"""check_decks.py - the 43 round-316 decks against dev-tools/deck_legality_audit.py's own rules (read-only import):
every card name exists in Forge's card scripts, no card over its copy limit (4, or the script's own DeckLimit /
basic-land exemption), the size by rank (40 Apprentice, 60 from Adept up), Name= = the enemy's name, and a colorless
deck holds no colored card and no colored mana symbol.
usage: python tools/check_decks.py [deck dir]"""
import json, os, re, sys

sys.dont_write_bytecode = True
HERE = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
sys.path.insert(0, HERE)
sys.path.insert(0, r"C:\TFR\repo\dev-tools")
import deck_legality_audit as dla
from roster316 import ROSTER

D = sys.argv[1] if len(sys.argv) > 1 else os.path.join(HERE, "decks")
limits = dla.build_limits(r"C:\TFR\repo\forge-gui\res\cardsfolder")
lower = {k.lower(): v for k, v in limits.items()}
DB = json.load(open(os.path.join(HERE, "carddb.json"), encoding="utf-8"))
COLORED = re.compile(r"\{[^}]*[WUBRG][^}]*\}")
bad = 0
for slug, name, rank, colors, theme, extra in ROSTER:
    path = os.path.join(D, slug + ".dck")
    text = open(path, encoding="utf-8").read()
    secs = dla.read_dck(path)
    main = secs.get("main", {})
    errs = []
    if "Name=%s\n" % name not in text:
        errs.append("Name= line")
    size = sum(main.values())
    if size != (40 if rank == "A" else 60):
        errs.append("size %d" % size)
    for card, n in main.items():
        if card not in limits:
            errs.append("unknown card %r" % card)
        elif n > dla.limit_for(limits, lower, card):
            errs.append("%d x %s (limit %d)" % (n, card, dla.limit_for(limits, lower, card)))
        if colors == "C" and card != "Wastes":
            c = DB.get(card)
            if c is None or c["colors"] or COLORED.search(c["oracle"]):
                errs.append("colored card in a colorless deck: %s" % card)
    if set(secs) - {"main"}:
        errs.append("extra sections %s" % sorted(set(secs) - {"main"}))
    bad += bool(errs)
    print("%-28s %s %-3s %2d cards  %s" % (name, rank, colors, size, "; ".join(errs) or "legal"))
print("\n%d decks, %d with problems" % (len(ROSTER), bad))
sys.exit(1 if bad else 0)
