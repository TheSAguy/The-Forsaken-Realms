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

--feet (round 565) - THE STRICT PASS

  Moves the legs `waypoint_routes_qa.py --feet` reports: a waypoint the old test passes while the
  enemy's visible feet stand mostly off the floor (a tent, a boulder, the outer wall of a room, off the
  map's edge). Never a leg in waypoint_feet_accepted.json (fliers and swimmers over terrain, lava
  creatures in lava, pinned booster guards - reviewed one by one). Rules, per waypoint:

    * the feet land FULLY on reachable floor, for the enemy that failed there - and every other enemy
      routed through the same waypoint keeps at least half its feet on the floor;
    * the body stays off loot: the enemy's lower body clears every reward's footprint by 2 px;
    * it stays on the patrol line: sliding the waypoint along one of its own legs (toward the point
      before or after it in the route, never closer than half a tile to that point) is preferred to any
      other direction unless the slide is over 1.5x longer (and over 8 px). A sideways move is printed
      as OFF-LINE so the review knows to look at it;
    * at most FEET_MAX_PX (two tiles) - further than that is not a nudge.

usage: python dev-tools/fix_routes.py                      # dry run over the plane
       python dev-tools/fix_routes.py --apply              # write
       python dev-tools/fix_routes.py --include-blocked    # also move collision-covered legs
       python dev-tools/fix_routes.py <map.tmx> ...        # limit to these maps
       python dev-tools/fix_routes.py --feet [--apply] [<map.tmx> ...]   # the strict feet pass
       python dev-tools/fix_routes.py --feet --json out.json             # also write the moves
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


FEET_MAX_PX = 2 * 16      # the same two tiles as MAX_TILES
FEET_LOOT_MARGIN = 2      # px of clearance between an enemy's lower body and a reward
FEET_MIN_LEG = 8          # a slide stops half a tile short of the point it slides toward
FEET_APART = 16           # nor may a nudge bring a waypoint within a tile of another point of its routes
FEET_NAV_TRIES = 40       # clean spots tried, best first, until one keeps every leg on the nav graph


def loot_footprints(path):
    """(x0, y0, x1, y1) of every reward in the map, spawned on any difficulty or not - the reading of
    guard_patrols/tfrmaps.py, which merges templates the way MapStage.loadObjects() does."""
    if os.path.join(HERE, 'guard_patrols') not in sys.path:
        sys.path.insert(0, os.path.join(HERE, 'guard_patrols'))
    import tfrmaps
    tfrmaps.load_tools(HERE)
    return [o.footprint() for o, _tier in tfrmaps.TMap(path, PLANE).loot(spawned_only=False)]


def on_loot(loot, x, y, feet):
    """Would the lower body (feet columns, the 8 px above the feet) of an enemy at (x, y) touch a reward?"""
    left, right, lift = feet or w.DEFAULT_FEET
    bx0, bx1, by1 = x + left, x + right, y - lift
    by0 = by1 - 8
    m = FEET_LOOT_MARGIN
    return any(bx0 < lx1 + m and bx1 > lx0 - m and by0 < ly1 + m and by1 > ly0 - m
               for lx0, ly0, lx1, ly1 in loot)


class FloorPatch(object):
    """Reachable floor around one waypoint with a summed-area table, so share() answers what
    waypoint_routes_qa.feet_on_floor() does (same pixels, same "floor") in four lookups."""

    def __init__(self, reach, wpx, hpx, x0, y0, x1, y1, player=w.PLAYER_BOX):
        pw, ph = player
        self.x0, self.y0, self.x1, self.y1 = x0, y0, x1, y1
        width = x1 - x0
        sat = [[0] * (width + 1)]
        for py in range(y0, y1):
            prev, row, run = sat[-1], [0], 0
            for i, px in enumerate(range(x0, x1)):
                hit = 0
                if 0 <= px < wpx and 0 <= py < hpx:
                    for cy in range(max(0, py - ph + 1), py + 1):
                        base = cy * wpx
                        if any(reach[base + cx] for cx in range(max(0, px - pw + 1), px + 1)):
                            hit = 1
                            break
                run += hit
                row.append(prev[i + 1] + run)
            sat.append(row)
        self.sat = sat

    def share(self, x, y, feet):
        fx0, fy0, fx1, fy1 = w.feet_box(x, y, feet)
        total = (fx1 - fx0) * (fy1 - fy0)
        if total <= 0 or fx0 < self.x0 or fy0 < self.y0 or fx1 > self.x1 or fy1 > self.y1:
            return 0.0
        a, b = fx0 - self.x0, fx1 - self.x0
        c, d = fy0 - self.y0, fy1 - self.y0
        s = self.sat
        return (s[d][b] - s[c][b] - s[d][a] + s[c][a]) / float(total)


def route_neighbours(routes, wps, wid):
    """{(x, y)} of the points an enemy walks from or to on either side of this waypoint, over every
    route through it. The enemy's own start precedes its first leg; the route loops, so the last leg
    also precedes the first; waits are skipped; a random leg's members all count."""
    out = set()
    for _eid, _nm, ex, ey, legs, _pn in routes:
        steps = []
        for kind, payload in legs:
            ids = [payload] if kind == 'wp' else (payload if kind == 'random' else [])
            ids = [i for i in ids if i in wps]
            if ids:
                steps.append(ids)
        for k, ids in enumerate(steps):
            if wid not in ids:
                continue
            before = [steps[k - 1]] if k else [steps[-1]]
            after = [steps[(k + 1) % len(steps)]]
            if k == 0:
                out.add((ex, ey))
            for grp in before + after:
                for i in grp:
                    if i != wid:
                        out.add(wps[i])
    return out


def route_points(routes, wps, wid):
    """[(x, y)] of every OTHER point on the routes through this waypoint - their starts and waypoints."""
    out = []
    for _eid, _nm, ex, ey, legs, _pn in routes:
        ids = [i for kind, payload in legs
               for i in ([payload] if kind == 'wp' else (payload if kind == 'random' else []))]
        if wid in ids:
            out.append((ex, ey))
            out.extend(wps[i] for i in ids if i != wid and i in wps)
    return out


def feet_candidates(free, reach, wpx, hpx, th, wx, wy, need, others, nbrs, loot, apart=()):
    """[(x, y, px moved, 'line'|'OFF-LINE')] - every clean spot within FEET_MAX_PX, best first.

    `need` - feet that must land fully on floor (the enemies that failed here); `others` - feet of the
    other enemies through this waypoint, which must keep at least half; `apart` - the other points of
    its routes, which it must stay FEET_APART from (or as far as it already was, if that was less) so
    that a fix never collapses a patrol onto its own next stop.

    Order: a slide along one of its own legs ranks as if it were 1.5x shorter, and any slide of 8 px
    or less comes first - the "patrol line" preference in the module docstring."""
    everyone = need + others
    keep = [(px, py, min(FEET_APART, math.hypot(px - wx, py - wy))) for px, py in apart]
    lefts = [f[0] for f in everyone] + [w.DEFAULT_FEET[0]]
    rights = [f[1] for f in everyone] + [w.DEFAULT_FEET[1]]
    lifts = [f[2] for f in everyone] + [0.0]
    R = FEET_MAX_PX
    patch = FloorPatch(reach, wpx, hpx,
                       int(math.floor(wx - R + min(lefts))) - 2, int(math.floor(wy - R - max(lifts))) - w.FEET_ROWS - 2,
                       int(math.ceil(wx + R + max(rights))) + 2, int(math.ceil(wy + R)) + 2)

    def clean(x, y):
        if any(patch.share(x, y, f) < 1.0 for f in need):
            return False
        if any(patch.share(x, y, f) < w.FEET_PASS for f in others):
            return False
        if any(on_loot(loot, x, y, f) for f in everyone):
            return False
        if any(math.hypot(x - px, y - py) < k for px, py, k in keep):
            return False
        legal, ok = w.standable(free, reach, wpx, hpx, th, x, y)
        return legal and ok

    ranked = []
    for nx, ny in nbrs:
        d = math.hypot(nx - wx, ny - wy)
        for t in range(1, int(min(R, d - FEET_MIN_LEG)) + 1):
            x, y = round(wx + (nx - wx) * t / d, 1), round(wy + (ny - wy) * t / d, 1)
            if clean(x, y):
                ranked.append(((0.0 if t <= 8 else t / 1.5), t, (x, y, float(t), 'line')))
    for dx in range(-R, R + 1):
        for dy in range(-R, R + 1):
            dist = math.hypot(dx, dy)
            if 0 < dist <= R:
                x, y = round(wx + dx, 1), round(wy + dy, 1)
                if clean(x, y):
                    ranked.append((dist, dist, (x, y, math.hypot(x - wx, y - wy), 'OFF-LINE')))
    ranked.sort(key=lambda r: (r[0], r[1]))
    return [r[2] for r in ranked]


def nav_legs(nav, m, wid):
    """{(enemy id, from, to): the A* length of each leg into or out of waypoint `wid`, or None} on the
    replica of the game's navigation graph (guard_patrols/tfrmaps.NavSim). 'start' is the walk from the
    enemy's own position to its first waypoint. A leg with no path freezes the enemy for good: MapStage
    idles a mob whose path is empty, and the behaviour never completes."""
    # Round 595: a later leg sets off from a hair off waypoint A, as the game does (the mob stops within 2 px
    # of A, so findShortestPath() links a NEW vertex to its 10 nearest) - not from A's own vertex (NavSim's
    # origin_key), which is stricter than the game and called legs frozen that walk.
    out = {}
    wpo = m.waypoints()
    for e in m.enemies():
        ids = [i for kind, payload in w.legs(e.props.get('waypoints') or '')
               for i in ([payload] if kind == 'wp' else (payload if kind == 'random' else [])) if i in wpo]
        if wid not in ids:
            continue
        if ids[0] == wid:
            out[(e.id, 'start', wid)] = nav.path((e.x, m.hpx - e.y), ('w', wid))[0]
        for a, b in zip(ids, ids[1:] + ids[:1]):
            if wid in (a, b) and a != b:
                out[(e.id, a, b)] = nav.path((wpo[a].x + 0.01, m.hpx - wpo[a].y + 0.01), ('w', b))[0]
    return out


def feet_main():
    args = [a for a in sys.argv[1:] if not a.startswith('-') and not a.endswith('.json')]
    apply_it = '--apply' in sys.argv
    out_json = None
    if '--json' in sys.argv:
        out_json = sys.argv[sys.argv.index('--json') + 1]
    if os.path.join(HERE, 'guard_patrols') not in sys.path:
        sys.path.insert(0, os.path.join(HERE, 'guard_patrols'))
    import tfrmaps
    tfrmaps.load_tools(HERE)
    feet_of = w.enemy_feet()
    accepted = w.feet_accepted()
    maps = args or sorted(glob.glob(os.path.join(PLANE, '**', '*.tmx'), recursive=True))
    moved = stuck = written = 0
    record = []
    for path in maps:
        try:
            routes = w.routes(path)
        except Exception:
            continue
        if not routes:
            continue
        rel = os.path.relpath(path, PLANE).replace('\\', '/')
        ok_here = accepted.get(rel, {})
        wps = w.waypoint_objects(path)
        try:
            free, reach, wpx, hpx, tw, th, _ = q.reachable_from_entries(path)
        except Exception:
            continue
        if reach is None:
            continue
        users, failing = {}, {}
        for eid, name, _x, _y, legs, _pn in routes:
            for kind, payload in legs:
                for wid in ([payload] if kind == 'wp' else (payload if kind == 'random' else [])):
                    if wid not in wps:
                        continue
                    users.setdefault(wid, {})[eid] = name
                    wx, wy = wps[wid]
                    legal, ok = w.standable(free, reach, wpx, hpx, th, wx, wy)
                    if not (legal and ok) or '%d:%d' % (eid, wid) in ok_here:
                        continue
                    if w.feet_on_floor(reach, wpx, hpx, wx, wy, feet_of.get(name)) < w.FEET_PASS:
                        failing.setdefault(wid, {})[eid] = name
        if not failing:
            continue
        print('\n=== %s' % rel)
        loot = loot_footprints(path)
        tm = tfrmaps.TMap(path, PLANE)   # the nav replica; moved waypoints are moved in it too
        src = io.open(path, encoding='utf-8', newline='').read()
        changed = False
        for wid in sorted(failing):
            wx, wy = wps[wid]
            need = [feet_of.get(n) or w.DEFAULT_FEET for n in failing[wid].values()]
            others = [feet_of.get(n) or w.DEFAULT_FEET for e, n in users[wid].items() if e not in failing[wid]]
            who = ', '.join('%s %d' % (n, e) for e, n in sorted(users[wid].items()))
            cands = feet_candidates(free, reach, wpx, hpx, th, wx, wy, need, others,
                                    route_neighbours(routes, wps, wid), loot, route_points(routes, wps, wid))
            before = nav_legs(tfrmaps.NavSim(tm), tm, wid)
            got, tried = None, 0
            for cand in cands[:FEET_NAV_TRIES]:
                tried += 1
                tm.by_id[wid].x, tm.by_id[wid].y = cand[0], cand[1]
                after = nav_legs(tfrmaps.NavSim(tm), tm, wid)
                if all(after.get(k) is not None for k, v in before.items() if v is not None):
                    got = cand
                    break
            if got is None:
                tm.by_id[wid].x, tm.by_id[wid].y = wx, wy
                print('  NONE   wp %-4s (%.1f, %.1f) %s - %s'
                      % (wid, wx, wy, ('nothing clean within %d px' % FEET_MAX_PX) if not cands else
                         ('%d clean spot(s) tried, each cuts a walkable leg off the nav graph' % tried), who))
                stuck += 1
                continue
            broken = sorted('%s->%s' % (k[1], k[2]) for k, v in before.items() if v is None)
            if broken:
                print('  NOTE   wp %-4s leg(s) %s had no nav path before this move either' % (wid, ', '.join(broken)))
            nx, ny, dist, how = got
            out = move_waypoint(src, wid, nx, ny)
            if out is None:
                print('  !!     wp %-4s could not be rewritten' % wid)
                stuck += 1
                continue
            print('  MOVE   wp %-4s (%.1f, %.1f) -> (%g, %g) %4.1f px %-8s - %s'
                  % (wid, wx, wy, nx, ny, dist, how, who))
            record.append({'map': rel, 'waypoint': wid, 'from': [wx, wy], 'to': [nx, ny], 'px': round(dist, 1),
                           'how': how, 'enemies': sorted(users[wid]), 'failing': sorted(failing[wid])})
            src, changed = out, True
            wps[wid] = (nx, ny)   # the next waypoint of this map keeps apart from where this one went
            moved += 1
        if changed and apply_it:
            io.open(path, 'w', encoding='utf-8', newline='').write(src)
            written += 1
    if out_json:
        import json
        io.open(out_json, 'w', encoding='utf-8').write(json.dumps(record, indent=1))
    print('\n%s: %d moved, %d left (nothing clean within %d px), %d map(s) written'
          % ('APPLIED' if apply_it else 'DRY RUN', moved, stuck, FEET_MAX_PX, written))
    return 0


def main():
    if '--feet' in sys.argv:
        return feet_main()
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
    # 'Magma Elemental' added on the user's call ("Make Magma Elemental a walker too"). Its 8 blocked
    # legs were all in templeofchandra.
    #
    # An earlier version of this comment said those legs were blocking a "capital-W flip" - a claim
    # round 286f RETRACTED, because that hazard never existed: the grep behind it matched
    # <objectgroup name="Waypoints">, a Tiled LAYER name, not <property name="Waypoints">, of which
    # there are zero. templeofchandra's routes really were broken and are really fixed; they were
    # just never gating anything.
    #
    # Note 'Fire Giant' and 'Earth Elemental' are still OUT: same lava/rock argument, not covered by
    # that instruction.
    GROUND = {'Bear', 'Polar Bear', 'Tiger', 'Hydra', 'Clay Golem', 'Pyromancer',
              'Magma Elemental'}
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
