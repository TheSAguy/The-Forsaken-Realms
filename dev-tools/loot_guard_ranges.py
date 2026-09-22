"""loot_guard_ranges.py - a booster's guard reacts sooner than a chest's (round 286).

User: *"I want Boosters and Chests guarded please. Boosters need stronger more aggressive guards than
chests."*

STRONGER was already true and needed no work: `booster_guards.py` reports 260 of 261 boosters guarded
(the one exception is a map with no entry object, so reachability is unknowable), and
`upgrade_booster_guards.py` finds 0 Apprentice booster guards left - round 279's pass did that. What
was NOT differentiated is AGGRESSION: measured across the plane, booster guards carried threatRange 30
(113 of them), 40 (59), 20 (30), 50 (27) and chest guards carried a near-identical spread. A booster's
guard was as likely to be sleepier than a chest's as keener.

So this sets the two sides of one gap:

  booster guard  threatRange RAISED to at least BOOSTER_FLOOR
  chest guard    threatRange LOWERED to at most CHEST_CAP, and written explicitly when absent

The absent case matters and is easy to miss: with no authored threatRange,
`MapStage.applyDefaultReactionRange()` fills the plane default (32 px), which is ABOVE the chest cap -
so leaving it blank would quietly make an unauthored chest guard keener than the floor a booster guard
gets. Writing 20 is what keeps the gap real.

What it will not do:

  * touch threatRange -1. That is an authored "never react", which round 252 deliberately honours; a
    guard someone made inert on purpose stays inert.
  * touch dialog carriers. Round 253 exempted quest NPCs from reaction ranges because an NPC that
    charges the player is a bug, and that has not changed.
  * lower a guard that watches a booster AND a chest. Boosters win the tie - the whole point is that
    the better loot is better defended.

usage: python dev-tools/loot_guard_ranges.py                    # dry run
       python dev-tools/loot_guard_ranges.py --apply
       python dev-tools/loot_guard_ranges.py --booster 40 --chest 20
"""
import argparse
import collections
import glob
import io
import math
import os
import re
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
PLANE = os.path.join(HERE, '..', 'forge-gui', 'res', 'adventure', 'The Forsaken Realms', 'maps', 'map')
OBJ = re.compile(r'<object\b[^>]*?/>|<object\b.*?</object>', re.S)


def kind_of(o):
    t = re.search(r'template="[^"]*/([a-z_0-9]+)\.tx"', o)
    if t:
        return t.group(1)
    m = re.search(r'type="([^"]+)"', o)
    return m.group(1) if m else ''


def parse(path):
    src = io.open(path, encoding='utf-8', newline='').read()
    tw = int(re.search(r'tilewidth="(\d+)"', src).group(1))
    loot, foes = [], []
    for o in OBJ.findall(src):
        k = kind_of(o)
        oid = re.search(r'\bid="(\d+)"', o)
        x = re.search(r'\bx="([-0-9.]+)"', o)
        y = re.search(r'\by="([-0-9.]+)"', o)
        if not (oid and x and y):
            continue
        px, py = float(x.group(1)), float(y.group(1))
        if k in ('booster', 'treasure'):
            loot.append((k, px, py))
        elif k == 'enemy':
            tr = re.search(r'name="threatRange"[^>]*value="([-0-9]+)"', o)
            foes.append({'id': int(oid.group(1)), 'x': px, 'y': py,
                         'dialog': 'name="dialog' in o,
                         'threat': int(tr.group(1)) if tr else None,
                         'name': (re.search(r'name="enemy" value="([^"]+)"', o) or
                                  re.match(r'()', '')).group(1) if re.search(
                                      r'name="enemy" value="([^"]+)"', o) else '?'})
    return src, tw, loot, foes


def set_threat(src, oid, value):
    """Set (or insert) this object's threatRange, leaving every other byte alone."""
    # (?=[\s/>]) not \b after the closing quote: a quote followed by a space is NOT a word boundary,
    # so `id="12"\b` matches nothing. Cost a whole dry run the first time this was written, and the
    # round that learned it wrote it down - then this file repeated it.
    m = re.search(r'<object\b[^>]*?\bid="%d"(?=[\s/>])(?:(?!</object>).)*?</object>|'
                  r'<object\b[^>]*?\bid="%d"(?=[\s/>])[^>]*?/>' % (oid, oid), src, re.S)
    if not m:
        return None
    blk = m.group(0)
    if re.search(r'name="threatRange"', blk):
        new = re.sub(r'(name="threatRange"[^>]*?value=")[-0-9]+(")',
                     lambda mm: mm.group(1) + str(value) + mm.group(2), blk, count=1)
    elif '<properties>' in blk:
        new = blk.replace('<properties>',
                          '<properties>\n    <property name="threatRange" type="int" value="%d"/>'
                          % value, 1)
    else:
        return None  # a bare self-closing object with no properties block - skip rather than guess
    return src[:m.start()] + new + src[m.end():]


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('maps', nargs='*')
    ap.add_argument('--apply', action='store_true')
    ap.add_argument('--radius', type=float, default=3.0)
    ap.add_argument('--booster', type=int, default=40)
    ap.add_argument('--chest', type=int, default=20)
    a = ap.parse_args()

    files = a.maps or sorted(glob.glob(os.path.join(PLANE, '**', '*.tmx'), recursive=True))
    tally = collections.Counter()
    written = 0
    for path in files:
        try:
            src, tw, loot, foes = parse(path)
        except Exception:
            continue
        if not loot or not foes:
            continue
        reach = a.radius * tw
        # which loot does each enemy guard? nearest-wins per piece, same as the runtime.
        role = {}
        for k, lx, ly in loot:
            best = None
            for f in foes:
                if f['dialog']:
                    continue
                d = math.hypot(f['x'] - lx, f['y'] - ly)
                if d <= reach and (best is None or d < best[0]):
                    best = (d, f)
            if best:
                cur = role.get(best[1]['id'])
                role[best[1]['id']] = 'booster' if 'booster' in (k, cur) else k
        if not role:
            continue
        changes = []
        for f in foes:
            r = role.get(f['id'])
            if not r or f['threat'] == -1:
                continue
            want = a.booster if r == 'booster' else a.chest
            cur = f['threat']
            if r == 'booster' and cur is not None and cur >= want:
                continue
            if r == 'treasure' and cur is not None and cur <= want:
                continue
            changes.append((f, r, cur, want))
        if not changes:
            continue
        print('\n=== %s' % os.path.relpath(path, PLANE).replace('\\', '/'))
        for f, r, cur, want in changes:
            out = set_threat(src, f['id'], want)
            if out is None:
                print('  SKIP obj %-4s %-8s %-22s no properties block' % (f['id'], r, f['name'][:22]))
                tally['skip'] += 1
                continue
            print('  obj %-4s %-8s %-22s threatRange %s -> %d'
                  % (f['id'], r, f['name'][:22], 'none' if cur is None else cur, want))
            src = out
            tally[r] += 1
        if a.apply:
            io.open(path, 'w', encoding='utf-8', newline='').write(src)
            written += 1
    print('\n%s: %d booster guard(s) raised to >=%d, %d chest guard(s) held to <=%d, %d skipped, %d map(s)'
          % ('APPLIED' if a.apply else 'DRY RUN', tally['booster'], a.booster,
             tally['treasure'], a.chest, tally['skip'], written))
    return 0


if __name__ == '__main__':
    sys.exit(main())
