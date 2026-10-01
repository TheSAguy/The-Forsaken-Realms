r"""check_decks382.py - the round-382 decks against dev-tools/deck_legality_audit.py's own rules (read-only import), as
round 318's tools/check_decks.py did, plus what this round adds:
  * every card name exists in Forge's card scripts; no card over its copy limit (4, or the script's own DeckLimit /
    the basic-land exemption); the size by rank (40 Apprentice, 60 from Adept up); Name= is the enemy's name;
  * a colorless deck holds no colored card and no colored mana symbol;
  * no card on the plane's restricted list (config tables/restricted_cards.json);
  * the color identity: every nonland card's colors inside the enemy's colors;
  * the "<color> + artifacts" decks (roster382.ARTIFACT) hold a real artifact package: at least 14 Artifact cards in
    60 (9 in 40) and their color still present;
  * Victor's hand-made deck (roster382.VICTOR_DECK): every card resolves; its restricted cards are LISTED (the importer
    keeps every deck-card reward and the ante off him - see import382.py).
usage: python check_decks382.py <repo root> <deck dir>     (exit 1 on a problem)"""
import json, os, re, sys

sys.dont_write_bytecode = True
HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
sys.path.insert(0, os.path.join(HERE, "tools"))
if len(sys.argv) < 3:
    raise SystemExit(__doc__)
ROOT, D = sys.argv[1], sys.argv[2]
REPO = os.path.dirname(os.path.dirname(os.path.dirname(HERE)))      # this script's repo: dev-tools, card scripts
sys.path.insert(0, os.path.join(REPO, "dev-tools"))
import deck_legality_audit as dla
import carddb
from roster382 import ROSTER, ARTIFACT, VICTOR_DECK

PLANE = os.path.join(ROOT, "forge-gui", "res", "adventure", "The Forsaken Realms")
limits = dla.build_limits(os.path.join(REPO, "forge-gui", "res", "cardsfolder"))
lower = {k.lower(): v for k, v in limits.items()}
DB = carddb.load()
COLORED = re.compile(r"\{[^}]*[WUBRG][^}]*\}")
BASICS = {"Plains", "Island", "Swamp", "Mountain", "Forest", "Wastes"}
restricted = set(carddb.lenient(os.path.join(PLANE, "config tables", "restricted_cards.json")).get("restrictedCards", []))


def card_name(entry):
    return entry.split("|")[0].strip()


bad = 0
arts = {}
for slug, name, rank, colors, theme, tag_theme, extra in ROSTER:
    path = os.path.join(PLANE, VICTOR_DECK) if theme is None else os.path.join(D, slug + ".dck")
    text = open(path, encoding="utf-8").read()
    secs = dla.read_dck(path)
    main = secs.get("main", {})
    errs, notes = [], []
    if "Name=%s\n" % name not in text:
        errs.append("Name= line")
    size = sum(main.values())
    if theme is not None and size != (40 if rank == "A" else 60):
        errs.append("size %d" % size)
    for card, n in main.items():
        cn = card_name(card)
        if cn not in limits and cn.lower() not in lower:
            errs.append("unknown card %r" % card)
        elif n > dla.limit_for(limits, lower, cn):
            errs.append("%d x %s (limit %d)" % (n, cn, dla.limit_for(limits, lower, cn)))
        if cn in restricted:
            (notes if theme is None else errs).append("restricted card %s" % cn)
        c = DB.get(cn)
        if theme is not None and cn not in BASICS:
            if c is None:
                errs.append("not in the card cache: %s" % cn)
            elif colors == "C" and (c["colors"] or COLORED.search(c["oracle"])):
                errs.append("colored card in a colorless deck: %s" % cn)
            elif colors != "C" and any(ch not in colors for ch in c["colors"]):
                errs.append("off-color card %s (%s)" % (cn, c["colors"]))
    if slug in ARTIFACT:
        n_art = sum(k for card, k in main.items() if "Artifact" in DB.get(card_name(card), {}).get("types", []))
        colored = sum(k for card, k in main.items() if DB.get(card_name(card), {}).get("colors"))
        arts[slug] = (n_art, colored, size)
        if n_art < (9 if rank == "A" else 14):
            errs.append("only %d artifacts" % n_art)
        if colored < (8 if rank == "A" else 12):
            errs.append("only %d cards of its color" % colored)
    if set(secs) - {"main"}:
        errs.append("extra sections %s" % sorted(set(secs) - {"main"}))
    bad += bool(errs)
    print("%-28s %s %-3s %2d cards  %s%s%s" % (name, rank, colors, size, "; ".join(errs) or "legal",
                                             ("  [art %d, colored %d]" % arts[slug][:2]) if slug in arts else "",
                                             ("  NOTE " + "; ".join(notes)) if notes else ""))
print("\n%d decks, %d with problems" % (len(ROSTER), bad))
sys.exit(1 if bad else 0)
