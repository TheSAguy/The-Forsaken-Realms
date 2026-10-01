r"""add_to_biomes.py - round 382's biome-roster changes, applied on their own (another import may be rewriting
world/biomes/*.json, so import382.py leaves them alone):
  * every ORDINARY new enemy joins the "enemies" roster of each of its colors' lands; the Wasteland's (colorless.json)
    takes the colorless ones only (the user: "3, colorless only"), computed by import382.roster_additions() (white +10,
    blue +10, black +10, red +10, green +10, colorless +3). The 12 legends join NO roster (the legend table is their
    only route).
  * the old Victor's roster entries in white.json and black.json become "Victor, Valgavoth's Seneschal" (import382.py
    renamed the enemy; "Victor" is now the vampire legend, who must not roam the lands).
Each file is edited as text: the rename touches one string, the new names are appended after the last entry of the
"enemies" list at its own indentation - every other byte stays as it is. Idempotent: a name already listed is
skipped, a rename already made is skipped. It REFUSES (writes nothing) unless enemies.json already has every name it
would add, the renamed old Victor, and the new Victor as the vampire legend - run import382.py first.
usage: python add_to_biomes.py <repo root> [--check]
    --check   print what would change and write nothing; exit status 0 when everything is applied, 1 when not."""
import json, os, re, sys

sys.dont_write_bytecode = True
HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
from roster382 import OLD_VICTOR, OLD_VICTOR_NEW_NAME, VICTOR_DECK
from import382 import roster_additions

RENAME_IN = ("white", "black")


def read_text(path):
    raw = open(path, "rb").read()
    text = raw.decode("utf-8")
    return text.replace("\r\n", "\n"), "\r\n" in text


def encode(text, crlf):
    return (text.replace("\n", "\r\n") if crlf else text).encode("utf-8")


def enemies_block(text):
    m = re.search(r'("enemies"\s*:\s*\[)(.*?)(\n[ \t]*\])', text, re.S)
    if not m:
        raise SystemExit("no \"enemies\" list")
    return m, re.findall(r'"((?:[^"\\]|\\.)*)"', m.group(2))


def plan(text, biome, adds):
    """-> (new text, renamed?, [added names]) for one biome file."""
    m, cur = enemies_block(text)
    inner = m.group(2)
    renamed = False
    if biome in RENAME_IN and OLD_VICTOR in cur and OLD_VICTOR_NEW_NAME not in cur:
        pat = re.compile(r'(?m)^([ \t]*)"%s"(,?)[ \t]*$' % re.escape(OLD_VICTOR))
        hits = pat.findall(inner)
        if len(hits) != 1:
            raise SystemExit("REFUSED: %s.json lists %r %d times" % (biome, OLD_VICTOR, len(hits)))
        inner = pat.sub(lambda mm: '%s%s%s' % (mm.group(1), json.dumps(OLD_VICTOR_NEW_NAME, ensure_ascii=False),
                                               mm.group(2)), inner)
        renamed = True
    new_names = [n for n in adds if n not in cur]
    if new_names:
        last = [ln for ln in inner.split("\n") if ln.strip()][-1]
        indent = re.match(r"[ \t]*", last).group(0)
        body = inner.rstrip()
        inner = body + "".join(",\n%s%s" % (indent, json.dumps(n, ensure_ascii=False)) for n in new_names) + \
            inner[len(body):]
    new_text = text[:m.start(2)] + inner + text[m.end(2):]
    return new_text, renamed, new_names


def main():
    args = [a for a in sys.argv[1:] if not a.startswith("--")]
    if len(args) != 1:
        raise SystemExit(__doc__)
    check = "--check" in sys.argv
    plane = os.path.join(os.path.abspath(args[0]), "forge-gui", "res", "adventure", "The Forsaken Realms")
    E = json.load(open(os.path.join(plane, "world", "enemies.json"), encoding="utf-8"))
    names = {e["name"]: e for e in E}
    adds = roster_additions()
    missing = sorted(set(n for v in adds.values() for n in v) - set(names))
    problems = []
    if missing:
        problems.append("enemies.json lacks %d of the names to add (%s ...)" % (len(missing), ", ".join(missing[:3])))
    if OLD_VICTOR_NEW_NAME not in names:
        problems.append("enemies.json has no %r - the old Victor is not renamed yet" % OLD_VICTOR_NEW_NAME)
    if (names.get(OLD_VICTOR) or {}).get("deck") != [VICTOR_DECK]:
        problems.append("enemies.json's %r is not the new vampire legend (%s)" % (OLD_VICTOR, VICTOR_DECK))
    if problems:
        for p in problems:
            print("REFUSED: " + p)
        print("run import382.py first")
        return 1
    pending = 0
    writes = []
    for biome, add in adds.items():
        path = os.path.join(plane, "world", "biomes", biome + ".json")
        text, crlf = read_text(path)
        new_text, renamed, new_names = plan(text, biome, add)
        _, before = enemies_block(text)
        _, after = enemies_block(new_text)
        expect = [OLD_VICTOR_NEW_NAME if (renamed and n == OLD_VICTOR) else n for n in before] + new_names
        assert after == expect, biome
        json.loads(new_text)                                # still valid JSON
        if new_text == text:
            print("%-10s up to date (%d listed)" % (biome, len(before)))
            continue
        pending += 1
        print("%-10s %s+%d: %s" % (biome, "rename %r -> %r, " % (OLD_VICTOR, OLD_VICTOR_NEW_NAME) if renamed else "",
                                   len(new_names), ", ".join(new_names)))
        writes.append((path, encode(new_text, crlf)))
    if check:
        print("CHECK: %s" % ("everything is applied" if not pending else "%d file(s) would change" % pending))
        return 1 if pending else 0
    for path, data in writes:
        with open(path, "wb") as fh:
            fh.write(data)
    print("written: %d file(s)" % len(writes) if writes else "nothing to do")
    return 0


if __name__ == "__main__":
    sys.exit(main())
