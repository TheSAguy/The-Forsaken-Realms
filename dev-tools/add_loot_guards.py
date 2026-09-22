"""add_loot_guards.py - a DEDICATED guard for every booster and chest (round 287).

User: *"Place more enemies."*

`add_booster_guards.py` asks "is there a non-dialog enemy within 3 tiles of this loot". That is a
PROXIMITY test, and one enemy satisfies it for every chest in the room at once - which is why it
reported the graveyards fully covered while the game logged "3 of 12 have a guard".

`MapStage.assignLootGuards()` does a MATCHING, not a proximity test: it walks the loot and takes the
nearest enemy that is not already somebody's guard (`guardedRewardId != 0` skips it afterwards). So
twelve chests and three enemies means nine chests go unwatched no matter how close those three stand.

This replays that matching - in round 286e's priority order, boosters first, then chests, then the
loose pickups that also register - and places a NEW enemy for each booster or chest still unmatched.
Loose pickups (gold, wood, stone, shards) are counted as competitors for guards, because they are, but
never given one: guarding a gold pile is not what the user asked for and every one placed would be an
enemy the next chest cannot have.

The two tiers get different guards, following round 286's spec (*"Boosters need stronger more
aggressive guards than chests"*):

    booster   an Adept+ enemy from the map's own roster, threatRange 40
    chest     an Apprentice from the map's own roster,   threatRange 20

Placement is `add_booster_guards.plan`'s, reused rather than rewritten - in particular its
`standable_here`, which requires the guard's OWN tile to hold a reachable player position. Round 269
placed ten guards outside their rooms by checking only that something reachable was within 20px, and
round 275 had to undo it.

usage: python dev-tools/add_loot_guards.py                # dry run over the plane
       python dev-tools/add_loot_guards.py --apply
       python dev-tools/add_loot_guards.py <map.tmx> ...
"""
import argparse
import glob
import math
import os
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
import add_booster_guards as ag
import booster_guards as bg
import pixel_collision_qa as q

PLANE = os.path.join(HERE, '..', 'forge-gui', 'res', 'adventure', 'The Forsaken Realms', 'maps', 'map')
BOOSTER_THREAT, CHEST_THREAT = 40, 20


def loot_of(tmx, kind):
    return [o for o in bg.objects_of(tmx, kind) if o['kind'] == 'loot']


def unmatched(tmx, radius):
    """[(piece, kind)] boosters/chests that get NO dedicated guard, replaying the game's assignment."""
    boosters = loot_of(tmx, 'booster')
    chests = loot_of(tmx, 'treasure')
    if not boosters and not chests:
        return [], None
    others = []
    for k in ('gold', 'wood', 'stone', 'manashards'):
        others.extend(loot_of(tmx, k))
    enemies = [o for o in bg.objects_of(tmx) if o['kind'] == 'enemy' and not o['has_dialog']]
    try:
        _free, reach, _wpx, _hpx, tw, _th, _o = q.reachable_from_entries(tmx)
    except Exception:
        return [], 'unreadable'
    if reach is None:
        return [], 'no entry object to flood-fill from'
    used = set()
    need = []
    # Boosters first, then chests, then the loose pickups - the same order the runtime now uses, so
    # the tool and the game agree about who loses when guards are scarce.
    for kind, pieces in (('booster', boosters), ('treasure', chests), ('other', others)):
        for p in pieces:
            best = None
            for i, e in enumerate(enemies):
                if i in used:
                    continue
                d = math.hypot(e['x'] - p['x'], e['y'] - p['y'])
                if d <= radius * tw and (best is None or d < best[0]):
                    best = (d, i)
            if best:
                used.add(best[1])
            elif kind != 'other':
                need.append((p, kind))
    return need, None


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('maps', nargs='*')
    ap.add_argument('--apply', action='store_true')
    ap.add_argument('--radius', type=int, default=3)
    ap.add_argument('--fallback', default=ag.FALLBACK_ENEMY)
    a = ap.parse_args()

    files = a.maps or sorted(glob.glob(os.path.join(PLANE, '**', '*.tmx'), recursive=True))
    placed = {'booster': 0, 'treasure': 0}
    failed = skipped = maps_done = 0
    for tmx in files:
        try:
            need, why = unmatched(tmx, a.radius)
        except Exception:
            continue
        if why:
            if need or why != 'unreadable':
                pass
            continue
        if not need:
            continue
        rel = os.path.relpath(tmx, PLANE).replace('\\', '/')
        # One planning pass per tier, so each gets its own roster rank, and the second pass sees the
        # tiles the first one consumed (plan() re-reads the map's objects each time it is called, so
        # the tiers are applied separately below rather than batched).
        rows = []
        for kind, rank, threat in (('booster', 1, BOOSTER_THREAT), ('treasure', 0, CHEST_THREAT)):
            want = [p for p, k in need if k == kind]
            if not want:
                continue
            # Hand plan() the matching's verdict. Without need_override it would re-derive the list
            # from its own proximity audit, call this loot already guarded, and place nothing - which
            # is exactly what the first run of this tool did, reporting "no free reachable tile" for
            # pieces it had never looked at.
            made, _bad = ag.plan(tmx, a.radius, a.fallback, kind, rank, need_override=want)
            still = [p for p in want if p['id'] not in {m[0]['id'] for m in made}]
            for m in made:
                rows.append((kind, m, threat))
            failed += len(still)
            for p in still:
                print('  %-46s %-8s obj %-5s no free reachable tile' % (rel, kind, p['id']))
        if not rows:
            continue
        print('  %-46s %d guard(s): %s' % (rel, len(rows),
                                           ', '.join('%s->%s' % (k, m[3]) for k, m, _t in rows)))
        if a.apply:
            for kind, m, threat in rows:
                ag.apply(tmx, [m], threat)
            maps_done += 1
        for kind, _m, _t in rows:
            placed[kind] += 1
    print('\n%s: %d booster guard(s) + %d chest guard(s) placed, %d with nowhere to stand, %d map(s)'
          % ('APPLIED' if a.apply else 'DRY RUN', placed['booster'], placed['treasure'],
             failed, maps_done))
    return 0


if __name__ == '__main__':
    sys.exit(main())
