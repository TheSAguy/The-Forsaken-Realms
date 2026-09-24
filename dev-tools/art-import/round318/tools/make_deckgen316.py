r"""make_deckgen316.py - derive deckgen316.py / deck_audit316.py from the repo's round-179 scripts (read-only copies),
so the changes are exactly the listed ones. Run from the enemy_import folder:  python tools/make_deckgen316.py"""
import os

HERE = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
SRC = r"C:\TFR\repo\dev-tools\art-import"


def patch(text, pairs, label):
    for old, new in pairs:
        n = text.count(old)
        assert n == 1, (label, old[:70], n)
        text = text.replace(old, new)
    return text


gen = open(os.path.join(SRC, "deckgen179.py"), encoding="utf-8").read()
gen = patch(gen, [
    ('"""deckgen179.py - themed decks for the 197 round-179 enemies, built from Forge\'s own card data (carddb.py).\n',
     r'''r"""deckgen316.py - themed decks for the 43 round-316 enemies: deckgen179.py (the round-179 generator, copied by
tools/make_deckgen316.py - not imported, so no cache or bytecode is ever written into C:\TFR\repo) with three changes:
  * ROSTER from roster316.py; decks are written as <stem>.dck, the name import316.py copies them under;
  * THEMES gains the round-316 creatures (whelp, faerie_dragon, behemoth, werewolf, werewolf_white, kirin, bat_fire,
    bat_frost) - round 179's themes are unchanged;
  * COLORLESS decks (colors "C", the automatons): only colorless cards whose rules text asks for no colored mana, and
    Wastes for every land - the way the stock colorless decks are built (juggernaut.dck, rustic_construct.dck).
The card cache is tools/carddb.py's carddb.json in this folder, built from the CURRENT repo card scripts.

Round 179's description follows.
'''),
    ('sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))\nimport carddb\nfrom roster179 import ROSTER\n',
     'sys.dont_write_bytecode = True\nHERE = os.path.dirname(os.path.abspath(__file__))\nsys.path.insert(0, HERE)\n'
     'sys.path.insert(0, os.path.join(HERE, "tools"))\nimport carddb\nfrom roster316 import ROSTER\n'),
    ('else r"C:\\TFR\\art-staging\\decks"', 'else os.path.join(HERE, "decks")'),
    ('    "loxodon": (["Elephant"], ["Soldier", "Cleric"], ["Elephant"], []),\n}\n',
     '''    "loxodon": (["Elephant"], ["Soldier", "Cleric"], ["Elephant"], []),
    # ---- round 316
    "whelp": (["Dragon", "Drake", "Wyvern"], ["Bat", "Bird", "Faerie", "Imp"], ["Dragon", "Drake"], ["Flying"]),
    "faerie_dragon": (["Faerie", "Dragon", "Drake"], ["Sprite", "Spirit", "Bird"], ["Faerie", "Dragon"], ["Flying", "Flash"]),
    "behemoth": (["Beast", "Elemental"], ["Giant", "Elephant", "Dinosaur", "Wurm"], ["Beast", "Landfall"], ["Landfall", "Trample"]),
    "werewolf": (["Werewolf", "Wolf"], ["Human", "Beast"], ["Werewolf", "Wolf"], ["Daybound", "Transform"]),
    "werewolf_white": (["Werewolf", "Wolf", "Dog"], ["Human", "Soldier", "Cleric"], ["Werewolf", "Wolf"], ["Daybound", "Transform"]),
    "kirin": (["Kirin", "Spirit"], ["Monk", "Elemental", "Unicorn"], ["Spirit", "Arcane"], ["Flying"]),
    "bat_fire": (["Bat", "Phoenix", "Dragon", "Bird"], ["Elemental", "Devil"], ["Bat"], ["Flying", "Haste"]),
    "bat_frost": (["Bat", "Bird", "Drake", "Faerie"], ["Elemental", "Spirit"], ["Bat"], ["Flying", "snow"]),
}
# round 316: a colored mana symbol anywhere in the rules text (activation costs, hybrid, Phyrexian)
COLORED_MANA = re.compile(r"\\{[^}]*[WUBRG][^}]*\\}")


def colorless(colors):
    return colors == "C"
'''),
    ('        if not castable(c, colors) or c["cost"] in ("", "no cost"):\n            continue\n',
     '        if not castable(c, colors) or c["cost"] in ("", "no cost"):\n            continue\n'
     '        if colorless(colors) and (c["colors"] or COLORED_MANA.search(c["oracle"])):\n'
     '            continue                                  # round 316: an ability that needs {R} is dead in a Wastes deck\n'),
    ('    # lands: duals of exactly these colors, then basics by pip share\n    lands = pick_lands(colors, rank, n_lands, rnd)\n',
     '''    if colorless(colors):                       # round 316: Wastes for every land (they also make the {C} Eldrazi need)
        lines = ["[metadata]", "Name=%s" % name, "[Main]"] + ["%d %s" % (k, n) for n, k in main.items()]
        lines.append("%d Wastes" % n_lands)
        count = sum(main.values()) + n_lands
        theme_hits = sum(k for n, k in main.items() if set(DB[n]["sub"]) & set(THEMES[theme][0]))
        return lines, count, theme_hits, main
    # lands: duals of exactly these colors, then basics by pip share
    lands = pick_lands(colors, rank, n_lands, rnd)
'''),
    ('    open(os.path.join(OUT, "deckgen179_report.txt"), "w", encoding="utf-8").write("\\n".join(report) + "\\n")',
     '    open(os.path.join(HERE, "qa", "deckgen316_report.txt"), "w", encoding="utf-8").write("\\n".join(report) + "\\n")'),
], "deckgen")
open(os.path.join(HERE, "deckgen316.py"), "w", encoding="utf-8", newline="\n").write(gen)

aud = open(os.path.join(SRC, "deck_audit.py"), encoding="utf-8").read()
aud = patch(aud, [
    ('"""Audit the generated decks:', '"""deck_audit316.py - deck_audit.py (round 179) pointed at roster316 / deckgen316 and taught that a colorless\n'
     '("C") deck has no color to miss.\nAudit the generated decks:'),
    ('sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))\nimport carddb\nfrom roster179 import ROSTER\nfrom deckgen179 import THEMES, BAD, BAD_OK\n',
     'sys.dont_write_bytecode = True\nHERE = os.path.dirname(os.path.abspath(__file__))\nsys.path.insert(0, HERE)\n'
     'sys.path.insert(0, os.path.join(HERE, "tools"))\nimport carddb\nfrom roster316 import ROSTER\nfrom deckgen316 import THEMES, BAD, BAD_OK\n'),
    ('D = sys.argv[1] if len(sys.argv) > 1 else r"C:\\TFR\\art-staging\\decks"', 'D = sys.argv[1] if len(sys.argv) > 1 else os.path.join(HERE, "decks")'),
    ('    missing = [n for k, n in cards if n not in DB and n not in ("Plains", "Island", "Swamp", "Mountain", "Forest")]',
     '    missing = [n for k, n in cards if n not in DB and n not in ("Plains", "Island", "Swamp", "Mountain", "Forest", "Wastes")]'),
    ('    if set(colors) - present:\n        f.append("missing color %s" % "".join(sorted(set(colors) - present)))',
     '    if set(colors) - present - {"C"}:\n        f.append("missing color %s" % "".join(sorted(set(colors) - present - {"C"})))\n'
     '    if "C" in colors and present:\n        f.append("colored cards in a colorless deck %s" % "".join(sorted(present)))'),
    ('print("\\nmedian on-theme creatures by rank:", {k: statistics.median([r[8] for r in rows if r[2] == k]) for k in "ADMX"})\n'
     'print("median avg cmc by rank:", {k: round(statistics.median([r[9] for r in rows if r[2] == k]), 2) for k in "ADMX"})',
     'print("\\nmedian on-theme creatures by rank:", {k: statistics.median([r[8] for r in rows if r[2] == k]) for k in "ADMX"})\n'
     'print("median avg cmc by rank:", {k: round(statistics.median([r[9] for r in rows if r[2] == k]), 2) for k in "ADMX"})\n'
     'print("\\n%-30s %s %-4s %-14s %5s %5s %5s %5s %5s" % ("deck", "r", "col", "theme", "cards", "lands", "creat", "theme", "cmc"))\n'
     'for r in rows:\n'
     '    print("%-30s %s %-4s %-14s %5d %5d %5d %5d %5.2f%s" % (r[1][:30], r[2], r[3], r[4], r[5], r[6], r[7], r[8], r[9], "  <- " + "; ".join(r[-1]) if r[-1] else ""))\n'
     'sys.exit(1 if flags else 0)'),
], "audit")
open(os.path.join(HERE, "deck_audit316.py"), "w", encoding="utf-8", newline="\n").write(aud)
print("deckgen316.py, deck_audit316.py written")
