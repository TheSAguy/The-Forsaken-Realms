#!/usr/bin/env python3
"""Is every deck in the game legal? (2026-09-16 user report)

"one of the decks you created me the other day had 5 copies of one card and 6 of another."

Magic's constructed rule: a deck may contain at most FOUR cards with the same English name,
except basic lands and the handful of cards that say otherwise. Forge already encodes every
exception in its own card scripts, so this reads the rules out of `forge-gui/res/cardsfolder`
rather than hardcoding a list that would rot:

    Types:... Basic ... Land             -> unlimited (Plains, Wastes, Snow-Covered Swamp, ...)
    K:A deck can have any number of ...  -> unlimited (Relentless Rats, Persistent Petitioners, ...)
    K:DeckLimit:<N>:...                  -> exactly N (Seven Dwarves 7, Nazgul 9, ...)
    anything else                        -> 4

WHAT IT SCANS
    *.dck                       Forge deck files - `<count> <Card Name>|<SET>` under [Main]/[Sideboard].
                                A name may appear on SEVERAL lines with different sets, so counts are
                                SUMMED per name per section; checking line-by-line misses the real bug.
    decks/**/*.json             Adventure deck templates. Only entries with an explicit `cardName` can
                                be judged here; the filter buckets ("10 green commons of cmc 1-2") are
                                generated at run time and are reported as unaudited, not as passes.
    dev-tools/save-editing/*.txt  The decklists written into save slots - `<count> <Card Name>`.

Sections are checked INDEPENDENTLY. In paper the limit applies to main + sideboard combined, but
adventure mode uses the sideboard as a holding area (won ante cards are appended to it), so a
combined count would raise false alarms. A section that breaks the limit on its own is unambiguous.

Usage
-----
    python dev-tools/deck_legality_audit.py [--root DIR] [--quiet] [--json OUT]

Exit code is 1 when any illegal deck is found, so it can gate a build.
"""
import argparse
import collections
import io
import json
import os
import re
import sys

DEFAULT_LIMIT = 4
UNLIMITED = 10 ** 6

RE_DECKLIMIT = re.compile(r"^K:DeckLimit:(\d+):", re.M)
RE_ANY_NUMBER = re.compile(r"^K:A deck can have any number of cards named", re.M)
RE_TYPES = re.compile(r"^Types:(.*)$", re.M)
RE_NAME = re.compile(r"^Name:(.*)$", re.M)
RE_DCK_LINE = re.compile(r"^\s*(\d+)\s+(.+?)\s*$")


def build_limits(cardsfolder):
    """name -> maximum legal copies, read from Forge's own card scripts."""
    limits = {}
    for root, _dirs, files in os.walk(cardsfolder):
        for fn in files:
            if not fn.endswith(".txt"):
                continue
            path = os.path.join(root, fn)
            try:
                text = io.open(path, encoding="utf-8", errors="replace").read()
            except OSError:
                continue
            m = RE_NAME.search(text)
            if not m:
                continue
            name = m.group(1).strip()
            limit = DEFAULT_LIMIT
            types = RE_TYPES.search(text)
            if types and "Basic" in types.group(1) and "Land" in types.group(1):
                limit = UNLIMITED
            elif RE_ANY_NUMBER.search(text):
                limit = UNLIMITED
            else:
                dl = RE_DECKLIMIT.search(text)
                if dl:
                    limit = int(dl.group(1))
            limits[name] = limit
    return limits


def limit_for(limits, lower_limits, name):
    """Unknown names default to 4 - the safe assumption for an audit."""
    if name in limits:
        return limits[name]
    # Deck files are hand-written and the casing wanders ("plains", "snow-covered Forest"),
    # so fall back to a case-insensitive match before assuming the default of 4 - otherwise
    # every lowercase basic land reads as a 36-of.
    low = name.lower()
    if low in lower_limits:
        return lower_limits[low]
    # split cards ("Wax // Wane") are filed under their full name; try the front half too
    if " // " in name:
        head = name.split(" // ")[0].strip()
        if head in limits:
            return limits[head]
        if head.lower() in lower_limits:
            return lower_limits[head.lower()]
    return DEFAULT_LIMIT


def rel(path, root):
    """Repo-relative where possible. A save dump lives on another drive on Windows, and
    os.path.relpath raises across mounts - fall back to the absolute path rather than blow up."""
    try:
        return os.path.relpath(path, root).replace("\\", "/")
    except ValueError:
        return path.replace("\\", "/")


def strip_set(card):
    """`Lightning Bolt|M10` -> `Lightning Bolt`; also drops a trailing |SET|artIndex."""
    return card.split("|", 1)[0].strip()


def read_dck(path):
    """-> {section: Counter(name -> count)}"""
    sections = collections.defaultdict(collections.Counter)
    current = None
    for raw in io.open(path, encoding="utf-8", errors="replace"):
        line = raw.rstrip("\n").strip()
        if not line:
            continue
        if line.startswith("["):
            current = line.strip("[]").strip().lower()
            continue
        if current in (None, "metadata"):
            continue
        m = RE_DCK_LINE.match(line)
        if not m:
            continue
        sections[current][strip_set(m.group(2))] += int(m.group(1))
    return sections


def read_txt_list(path):
    """The save-editing decklists: `<count> <Card Name>` per line."""
    counts = collections.Counter()
    for raw in io.open(path, encoding="utf-8", errors="replace"):
        line = raw.strip()
        if not line or line.startswith("#") or line.startswith("//"):
            continue
        m = RE_DCK_LINE.match(line)
        if not m:
            continue
        counts[strip_set(m.group(2))] += int(m.group(1))
    return {"main": counts}


RE_SAVE_SLOT = re.compile(r'^===\s*SLOT\s+(\d+)\s+"(.*)"\s*===\s*$')


def read_save_dump(path):
    """A DumpDecks output file: several `=== SLOT n "Name" ===` blocks of `<count> <Card Name>`.

    Each slot is its own section so a violation names the deck the player actually carries.
    """
    sections = collections.defaultdict(collections.Counter)
    current = None
    for raw in io.open(path, encoding="utf-8", errors="replace"):
        line = raw.rstrip("\n").strip()
        if not line:
            continue
        m = RE_SAVE_SLOT.match(line)
        if m:
            current = 'slot %s "%s"' % (m.group(1), m.group(2))
            continue
        if current is None:
            continue
        m = RE_DCK_LINE.match(line)
        if m:
            sections[current][strip_set(m.group(2))] += int(m.group(1))
    return sections


def read_deck_json(path):
    """Adventure templates. Returns (sections, unaudited_bucket_count)."""
    try:
        data = json.load(io.open(path, encoding="utf-8"))
    except ValueError:
        return {}, 0
    if not isinstance(data, dict):
        return {}, 0
    sections = collections.defaultdict(collections.Counter)
    buckets = 0
    for key, section in (("mainDeck", "main"), ("sideBoard", "sideboard")):
        entries = data.get(key)
        if not isinstance(entries, list):
            continue
        for entry in entries:
            if not isinstance(entry, dict):
                continue
            count = entry.get("count", 1)
            name = entry.get("cardName")
            if name:
                # Adventure templates write the printing, not the name ("Swamp|ISD") - strip the
                # set or every basic land in every pile deck reads as an illegal 15-of.
                sections[section][strip_set(str(name))] += int(count)
            else:
                buckets += 1
    return sections, buckets


def audit(root, quiet=False, save_dumps=()):
    cardsfolder = os.path.join(root, "forge-gui", "res", "cardsfolder")
    if not os.path.isdir(cardsfolder):
        print("cardsfolder not found at %s" % cardsfolder)
        return 2, []
    limits = build_limits(cardsfolder)
    lower_limits = {}
    for k, v in limits.items():
        # on a casing clash keep the most permissive - a legality audit must not invent violations
        lower_limits[k.lower()] = max(v, lower_limits.get(k.lower(), 0))
    if not quiet:
        special = {k: v for k, v in limits.items() if v != DEFAULT_LIMIT and v != UNLIMITED}
        unlimited = sum(1 for v in limits.values() if v == UNLIMITED)
        print("card rules: %d card(s) known, %d unlimited (basics + 'any number'), %d with a named limit %s"
              % (len(limits), unlimited, len(special), sorted(special.items())))

    targets = []
    for base in (os.path.join(root, "forge-gui", "res", "adventure"),):
        for dirpath, _dirs, files in os.walk(base):
            for fn in files:
                if fn.endswith(".dck"):
                    targets.append((os.path.join(dirpath, fn), "dck"))
                elif fn.endswith(".json") and os.sep + "decks" + os.sep in dirpath + os.sep:
                    targets.append((os.path.join(dirpath, fn), "json"))
    for sd in save_dumps:
        targets.append((sd, "savedump"))
    save_lists = os.path.join(root, "dev-tools", "save-editing")
    if os.path.isdir(save_lists):
        for fn in sorted(os.listdir(save_lists)):
            if fn.endswith(".txt"):
                targets.append((os.path.join(save_lists, fn), "txt"))

    violations = []
    scanned = 0
    skipped_pools = 0
    unaudited_buckets = 0
    for path, kind in targets:
        # decks/rewards/ and decks/shop/ are not decks. They are POOLS the game draws from, and
        # they deliberately list the same card once per alternate printing - Alt-Art_Staples.dck
        # holds "1 Sol Ring|ECC|[58]", "1 Sol Ring|ECC|[57]", "1 Sol Ring|EOC|[57]"... Summing
        # those to "30 Sol Ring" and calling it illegal would be nonsense.
        norm = path.replace("\\", "/")
        if "/decks/rewards/" in norm or "/decks/shop/" in norm:
            skipped_pools += 1
            continue
        if kind == "dck":
            sections = read_dck(path)
        elif kind == "savedump":
            sections = read_save_dump(path)
        elif kind == "txt":
            sections = read_txt_list(path)
        else:
            sections, buckets = read_deck_json(path)
            unaudited_buckets += buckets
        if not sections:
            continue
        scanned += 1
        for section, counts in sections.items():
            for name, n in sorted(counts.items()):
                cap = limit_for(limits, lower_limits, name)
                if n > cap:
                    violations.append({
                        "file": rel(path, root),
                        "section": section,
                        "card": name,
                        "count": n,
                        "limit": cap,
                    })
    return 0, (violations, scanned, len(targets), unaudited_buckets, skipped_pools)


def main(argv):
    ap = argparse.ArgumentParser()
    ap.add_argument("--root", default=os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
    ap.add_argument("--quiet", action="store_true")
    ap.add_argument("--json", dest="json_out")
    ap.add_argument("--save-dump", dest="save_dumps", action="append", default=[],
                    help="a DumpDecks output file; repeatable. Audits the decks a save actually holds.")
    args = ap.parse_args(argv)

    rc, payload = audit(args.root, args.quiet, args.save_dumps)
    if rc:
        return rc
    violations, scanned, total, buckets, pools = payload

    print("scanned %d deck file(s) with explicit card counts (of %d candidates); "
          "skipped %d reward/shop pool file(s) (they list one line per printing, not per copy); "
          "%d generated bucket(s) could not be audited statically"
          % (scanned, total, pools, buckets))
    if not violations:
        print("\nAll decks are legal - no card exceeds its copy limit.")
        return 0

    by_file = collections.defaultdict(list)
    for v in violations:
        by_file[v["file"]].append(v)
    print("\nILLEGAL: %d card entr(ies) across %d deck file(s)\n" % (len(violations), len(by_file)))
    for fn in sorted(by_file):
        print("  %s" % fn)
        for v in sorted(by_file[fn], key=lambda x: (-x["count"], x["card"])):
            print("      [%s] %-42s %d copies (limit %d)"
                  % (v["section"], v["card"], v["count"], v["limit"]))
    if args.json_out:
        io.open(args.json_out, "w", encoding="utf-8").write(
            json.dumps(violations, indent=2, ensure_ascii=False))
        print("\nwrote %s" % args.json_out)
    return 1


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
