#!/usr/bin/env python3
"""Write the ban list into GUIDE.md's "Banned cards and sets" subsection, straight from the config files.

Round 956. The lists come from the plane's `config tables/restricted_cards.json` (restrictedCards,
commanderOnlyCards) and `config.json` (restrictedEditions, digitalOnlyEditions); each set's full name comes from
forge-gui/res/editions/*.txt. Everything from the `#### Banned cards and sets` heading up to the next heading is
replaced, so the guide always matches the files. Re-run after any change to those lists, then guide_html.py:

    python -I dev-tools/guide_ban_list.py          # rewrite the subsection in GUIDE.md
    python -I dev-tools/guide_ban_list.py --print  # only print it
    python -I dev-tools/guide_html.py --check
"""
import glob
import json
import os
import re
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
RES = os.path.join(HERE, "..", "forge-gui", "res")
PLANE = os.path.join(RES, "adventure", "The Forsaken Realms")
GUIDE = os.path.join(PLANE, "GUIDE.md")
HEADING = "#### Banned cards and sets"


def load_jsonc(path):
    """The configs carry // comments (and card names like "Fire // Ice"): drop comments outside strings only."""
    out, i, s, in_str = [], 0, open(path, encoding="utf-8").read(), False
    while i < len(s):
        c = s[i]
        if in_str:
            out.append(c)
            if c == "\\":
                out.append(s[i + 1])
                i += 1
            elif c == '"':
                in_str = False
        elif c == '"':
            in_str = True
            out.append(c)
        elif s.startswith("//", i):
            while i < len(s) and s[i] != "\n":
                i += 1
            continue
        else:
            out.append(c)
        i += 1
    text = re.sub(r",(\s*[\]}])", r"\1", "".join(out))  # trailing commas
    return json.loads(text)


def edition_names():
    names = {}
    for path in glob.glob(os.path.join(RES, "editions", "*.txt")):
        code = name = None
        aliases = []
        with open(path, encoding="utf-8", errors="replace") as f:
            for line in f:
                if line.startswith("[cards]"):
                    break
                if line.startswith("Code="):
                    code = line[5:].strip()
                elif line.startswith("Alias="):
                    aliases += [a.strip() for a in line[6:].split(",") if a.strip()]
                elif line.startswith("Name="):
                    name = line[5:].strip()
        if code and name:
            for c in [code] + aliases:  # the configs use either (HTR17 is PH17's alias)
                names.setdefault(c, name)
    return names


def block():
    cards = load_jsonc(os.path.join(PLANE, "config tables", "restricted_cards.json"))
    config = load_jsonc(os.path.join(PLANE, "config.json"))
    names = edition_names()
    restricted = sorted(set(cards["restrictedCards"]), key=str.lower)
    commander = sorted(set(cards["commanderOnlyCards"]), key=str.lower)
    sets = config["restrictedEditions"]
    digital = config["digitalOnlyEditions"]

    def set_list(codes):
        return "; ".join("`%s` %s" % (c, names.get(c, "(not in this Forge version)")) for c in sorted(codes))

    return "\n".join([
        HEADING,
        "",
        "These cards and sets are kept out of **rewards, booster packs and shops**, so you never win or buy them; a "
        "banned card won in ante becomes another card from the same deck, and a chest's traveling artificer never "
        "copies one. Your deck building is not limited. Enemies "
        "can still play the banned cards, but the Commander-only cards are also taken out of enemy decks. The "
        "digital-only sets (MTG Arena cards with mechanics paper can't do) join the banned sets unless you tick "
        "**Allow digital-only cards** in Settings.",
        "",
        "**Banned cards (%d):** %s." % (len(restricted), ", ".join(restricted)),
        "",
        "**Commander-only cards (%d)** - they do nothing, or break, without a commander: %s."
        % (len(commander), ", ".join(commander)),
        "",
        "**Banned sets (%d):** %s." % (len(sets), set_list(sets)),
        "",
        "**Digital-only sets (%d):** %s." % (len(digital), set_list(digital)),
        "",
    ])


def main():
    text = block()
    if "--print" in sys.argv:
        print(text)
        return
    with open(GUIDE, encoding="utf-8", newline="") as f:
        guide = f.read()
    nl = "\r\n" if "\r\n" in guide else "\n"  # GUIDE.md is CRLF: keep its line ends
    text = text.replace("\n", nl)
    start = guide.find(HEADING + nl)
    if start < 0:
        sys.exit("no '%s' heading in GUIDE.md: add it first, where the subsection should go" % HEADING)
    nxt = re.compile(r"^#{1,4} ", re.M).search(guide, start + len(HEADING) + 1)
    end = nxt.start() if nxt else len(guide)
    guide = guide[:start] + text + nl + guide[end:]
    with open(GUIDE, "w", encoding="utf-8", newline="") as f:
        f.write(guide)
    print("GUIDE.md: ban list written")


if __name__ == "__main__":
    main()
