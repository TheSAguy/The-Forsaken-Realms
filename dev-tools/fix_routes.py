"""fix_routes.py - pull stray patrol waypoints back inside the playable area (round 286).

Round 283 built `waypoint_routes_qa.py` and found the shape of the problem the user reported twice by
hand (the Blue Tower mage, then the Basilica's Duelist): a `waypoints` leg pointing at a tile the
player can never reach, so the enemy walks out of the room on every lap. Those two were fixed one at
a time. This does the rest - but only the cases where "the author was a tile off" is the only honest
reading, because the two ways a waypoint can be wrong are NOT equally diagnosable.

WHICH ROUTES IT CONSIDERS

  Only those whose ENEMY'S OWN HOME is a legal, reachable player position - a ground fight the player
  is meant to walk up to. That is `stranded_enemies.py`'s distinction applied to routes; a Griffin on
  a rooftop legitimately patrols where nobody can follow.

WHICH STRAYS IT ACTUALLY MOVES - the important limit

  "outside" only. That is a LEGAL player position the entry flood-fill never reaches: the
  drawn-but-sealed band between a room and its outer wall. Nothing is meant to patrol there, which is
  exactly the Blue Tower bug, so pulling it inside cannot destroy intent.

  "blocked" - collision covers the tile - is REPORTED, never moved. Collision means terrain, and
  terrain can be deliberate: a Magma Elemental crossing lava in Chandra's temple, a Jellyfish over
  water. From here those look identical to a Cleric stuck in a wall, and guessing would quietly
  rewrite someone's patrol. `--include-blocked` overrides it for a run where that is what you want.

  MAX_TILES is 2 for the same reason. Every case the user reported was one tile past the room's edge.
  A leg needing to travel further is not a slip - nest_blue_1's Jellyfish wanted 9.9 tiles, and moving
  it would have been inventing a patrol rather than repairing one.

HOW A WAYPOINT MOVES

  Outward ring search for the nearest position that `waypoint_routes_qa.standable()` calls legal AND
  reachable - the same test that flagged it, so a fix cannot pass the audit while still being wrong.
  Distances print in tiles so the diff is reviewable, and a waypoint shared by several enemies is
  called out: pulling it inside is right for all of them, but it should not be a surprise.

usage: python dev-tools/fix_routes.py                      # dry run over the plane
       python dev-tools/fix_routes.py --apply              # write
       python dev-tools/fix_routes.py --include-blocked    # also move collision-covered legs
       python dev-tools/fix_routes.py <map.tmx> ...        # limit to these maps
"""
import glob
import io
import math
import os
import re
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
import pixel_collision_qa as q
import waypoint_routes_qa as w

PLANE = os.path.join(HERE, '..', 'forge-gui', 'res', 'adventure', 'The Forsaken Realms', 'maps', 'map')
MAX_TILES = 2


def move_waypoint(src, wid, nx, ny):
    """Rewrite just this object's x/y, leaving every other byte of the .tmx alone."""
    pat = re.compile(r'(<object\b[^>]*?\bid="%d"[^>]*?\bx=")([-0-9.]+)("[^>]*?\by=")([-0-9.]+)(")' % wid)
    m = pat.search(src)
    if not m:
        return None
    return src[:m.start()] + m.group(1) + ('%g' % nx) + m.group(3) + ('%g' % ny) + m.group(5) + src[m.end():]


def nearest_reachable(free, reach, wpx, hpx, tw, th, x, y):
    """Nearest (x, y, tiles) that standable() calls legal AND reachable, or (None, None, None)."""
    for r in range(1, MAX_TILES + 1):
        best = None
        for dy in range(-r, r + 1):
            for dx in range(-r, r + 1):
                if max(abs(dx), abs(dy)) != r:
                    continue  # this ring only; inner ones were already tried
                cx, cy = x + dx * tw, y + dy * th
                if cx < 0 or cy < 0 or cx >= wpx or cy >= hpx:
                    continue
                legal, ok = w.standable(free, reach, wpx, hpx, th, cx, cy)
                if legal and ok:
                    d = math.hypot(dx, dy)
                    if best is None or d < best[0]:
                        best = (d, cx, cy)
        if best:
            return best[1], best[2], best[0]
    return None, None, None


def main():
    args = [a for a in sys.argv[1:] if not a.startswith('-')]
    apply_it = '--apply' in sys.argv
    include_blocked = '--include-blocked' in sys.argv
    # Round 286b, user: "Proceed with your recommendation" - also move the BLOCKED legs, but only for
    # plainly terrestrial creatures. Collision under a Magma Elemental or Fire Giant reads as lava,
    # under an Earth Elemental as rock, under a Bat / Drake / Dimir Faerie as air, under a Crocodile or
    # Jellyfish as water - all plausibly authored. Collision under a Bear or a Tiger reads as nothing
    # but a mistake. Matched by NAME on purpose: adding a creature here is a visible decision, not a
    # tag lookup that quietly changes scope when someone edits enemies.csv.
    ground_only = '--ground-blocked' in sys.argv
    GROUND = {'Bear', 'Polar Bear', 'Tiger', 'Hydra', 'Clay Golem', 'Pyromancer'}
    maps = args or sorted(glob.glob(os.path.join(PLANE, '**', '*.tmx'), recursive=True))
    moved = too_far = left_blocked = written = 0
    for path in maps:
        try:
            routes = w.routes(path)
        except Exception:
            continue
        if not routes:
            continue
        wps = w.waypoint_objects(path)
        try:
            free, reach, wpx, hpx, tw, th, _ = q.reachable_from_entries(path)
        except Exception:
            continue
        if reach is None:
            continue
        refs = {}
        for _eid, _nm, _x, _y, legs, _pn in routes:
            for kind, payload in legs:
                for i in ([payload] if kind == 'wp' else (payload if kind == 'random' else [])):
                    refs[i] = refs.get(i, 0) + 1
        todo, blocked = {}, {}
        for eid, name, ex, ey, legs, _pn in routes:
            h_legal, h_reach = w.standable(free, reach, wpx, hpx, th, ex, ey)
            if not (h_legal and h_reach):
                continue  # swimmer / flier - by design
            for kind, payload in legs:
                for wid in ([payload] if kind == 'wp' else (payload if kind == 'random' else [])):
                    if wid not in wps or wid in todo or wid in blocked:
                        continue
                    wx, wy = wps[wid]
                    legal, ok = w.standable(free, reach, wpx, hpx, th, wx, wy)
                    if ok:
                        continue
                    movable = legal or include_blocked or (ground_only and name in GROUND)
                    if not movable:
                        blocked[wid] = (name, wx, wy)
                    else:
                        todo[wid] = (name, wx, wy, 'blocked' if not legal else 'outside')
        if not todo and not blocked:
            continue
        print('\n=== %s' % os.path.relpath(path, PLANE).replace('\\', '/'))
        src = io.open(path, encoding='utf-8', newline='').read()
        changed = False
        for wid, (name, wx, wy, kind) in sorted(todo.items()):
            share = (' [shared by %d]' % refs[wid]) if refs.get(wid, 0) > 1 else ''
            nx, ny, dist = nearest_reachable(free, reach, wpx, hpx, tw, th, wx, wy)
            if nx is None:
                print('  TOOFAR wp %-4s %-7s tile(%2d,%2d) %-20s nothing reachable within %d tiles%s'
                      % (wid, kind, wx // tw, wy // th, name[:20], MAX_TILES, share))
                too_far += 1
                continue
            out = move_waypoint(src, wid, nx, ny)
            if out is None:
                print('  !!     wp %-4s could not be rewritten' % wid)
                too_far += 1
                continue
            print('  MOVE   wp %-4s %-7s tile(%2d,%2d) -> tile(%2d,%2d) %.1f tiles %-20s%s'
                  % (wid, kind, wx // tw, wy // th, nx // tw, ny // th, dist, name[:20], share))
            src, changed = out, True
            moved += 1
        for wid, (name, wx, wy) in sorted(blocked.items()):
            print('  LEAVE  wp %-4s blocked tile(%2d,%2d) %-20s collision may be deliberate terrain'
                  % (wid, wx // tw, wy // th, name[:20]))
            left_blocked += 1
        if changed and apply_it:
            io.open(path, 'w', encoding='utf-8', newline='').write(src)
            written += 1
    print('\n%s: %d moved, %d too far, %d blocked-and-left, %d map(s) written'
          % ('APPLIED' if apply_it else 'DRY RUN', moved, too_far, left_blocked, written))
    return 0


if __name__ == '__main__':
    sys.exit(main())
