"""waypoint_routes_qa.py - does a patrol route stay inside the room? (round 283)

Rounds 275/278/279 audited where enemies STAND. `stranded_enemies.py` reports every magetower map
clean, and it is right: every authored enemy position in the Blue Tower is a legal, reachable player
position. The user still watched a mage walk out through the top wall, and said exactly why - *"needs
it's route edited"*. Nothing had ever validated a ROUTE.

An enemy's patrol is a `waypoints` property holding OBJECT IDs (not coordinates) of
`common/maps/obj/waypoint.tx` objects elsewhere in the same map. `EnemySprite.parseWaypoints()` splits
on commas and the walker visits them in order, so a single waypoint dropped outside the walls sends the
enemy outside every lap - and it looks like a placement bug while every placement audit says clean.

Three things this reports, in descending severity:

  unparsed  the leg is neither waitN / wN / rA-B-C / an integer, so `MovementBehavior.resolve()`
            prints "Navigation error for object ID..." and drops it.
  dangling  the id resolves to no waypoint object at all. The route silently loses that leg.
  blocked   the waypoint's own tile cannot hold the player box (collision covers it) - the enemy is
            being sent into a wall, water or a rooftop.
  outside   the waypoint is a legal position but NOT in the flood fill from the map's entries, i.e.
            the drawn-but-sealed band between the room and the outer wall. This is the Blue Tower case:
            the enemy leaves the playable area entirely.

"blocked"/"outside" are not automatically bugs - a flier or a swimmer may belong there, which is the
same judgment `stranded_enemies.py` documents. For a mage in a tower it is a bug.

--feet (round 562) adds a fifth, stricter finding for the legs that pass the four above:

  feet      the enemy's FEET at the waypoint are mostly off the reachable floor. standable() gives a
            waypoint a full tile of slack up and down, and that is too kind: a patrol moves the
            sprite's bottom-left corner exactly onto the waypoint (MovementBehavior hands the waypoint's
            Vector2 to the path, and CharacterSprite.draw() anchors the frame at getX()/getY()), so a
            waypoint on a room's OUTER top edge passes because the row below it is floor, while every
            lap draws the whole body above the wall. The Blue Tower Doppelganger did exactly that three
            times (rounds 283, 561).

            The feet are the bottom 4 px rows of the VISIBLE art, across its columns: the opaque box of
            the enemy's Idle frame at the size CharacterSprite.draw() draws it (frame x EnemyData.scale x
            the tier cue). Not the frame itself - many frames are mostly transparent margin, and an LPC
            frame's width would put the feet in thin air. A feet pixel is "floor" when a reachable
            player box covers it. The leg passes when at least half of its feet pixels are floor: a
            sprite on the top row of a room overlaps the wall art above it, which is how every enemy in
            a top-down room looks, but more than half of the feet in a wall is a body off the floor.

            Booster guards are pinned to their post (MapStage.assignLootGuards -> guardPost), so the
            game never walks their routes; this tool still reports them, the fix tool skips them.

usage: python dev-tools/waypoint_routes_qa.py                 # every plane map with routes
       python dev-tools/waypoint_routes_qa.py <map.tmx> [...]
       python dev-tools/waypoint_routes_qa.py --feet [<map.tmx> ...]   # also the strict feet check
"""
import glob
import json
import math
import os
import re
import sys
import xml.etree.ElementTree as ET

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
import pixel_collision_qa as q

PLANE = os.path.join(HERE, '..', 'forge-gui', 'res', 'adventure', 'The Forsaken Realms', 'maps', 'map')
PLANE_ROOT = os.path.join(HERE, '..', 'forge-gui', 'res', 'adventure', 'The Forsaken Realms')
FEET_ROWS = 4          # px above the waypoint's y that count as the feet
FEET_PASS = 0.5        # share of the feet that must be on reachable floor
PLAYER_BOX = (10, 6)   # pixel_collision_qa's default player box; a "floor" pixel is one such a box covers


def waypoint_objects(tmx_path):
    """id -> (x, y) for every waypoint.tx object, x/y exactly as the .tmx stores them."""
    root = ET.parse(tmx_path).getroot()
    out = {}
    for o in root.findall('.//object'):
        tpl = (o.get('template') or '')
        if os.path.basename(tpl) != 'waypoint.tx' and (o.get('type') or '') != 'waypoint':
            continue
        try:
            out[int(o.get('id', 0))] = (float(o.get('x')), float(o.get('y')))
        except (TypeError, ValueError):
            continue
    return out


def legs(value):
    """Parse a `waypoints` value into ('wait'|'wp'|'random'|'bad', payload) legs.

    The grammar is NOT "a list of object ids" - reading it that way made the first run of this tool
    report 16 perfectly good routes as dangling, which is the round 281 lesson all over again. From
    `EnemySprite.parseWaypoints()` and `MovementBehavior.resolve()`, in their order of precedence:

      waitN      a pause of N seconds. parseWaypoints() consumes it and never sets a destination.
      rA-B-C     pick ONE of those waypoints at random each lap (resolve() tests startsWith("r")
                 first, and strips every "r" before splitting on "-").
      wN         also a pause - resolve() tests startsWith("w") after the "r" case.
      <integer>  a waypoint object id, looked up in MapStage.waypoints.
      anything else prints "Navigation error for object ID..." at runtime and clears the leg.
    """
    for tok in (value or '').replace(' ', '').split(','):
        if not tok:
            continue
        if tok.startswith('wait'):
            yield ('wait', tok)
        elif re.fullmatch(r'r[0-9-]+', tok):
            yield ('random', [int(p) for p in tok[1:].split('-') if p])
        elif re.fullmatch(r'w[0-9.]+', tok):
            yield ('wait', tok)
        elif re.fullmatch(r'-?[0-9]+', tok):
            yield ('wp', int(tok))
        else:
            yield ('bad', tok)


def routes(tmx_path):
    """[(enemy_id, enemy_name, x, y, [ids], property_name)] - the property name is kept because the
    tree is inconsistent about its case (1348 'waypoints' against 22 'Waypoints')."""
    root = ET.parse(tmx_path).getroot()
    out = []
    for o in root.findall('.//object'):
        prop = None
        for pr in o.findall('.//property'):
            if (pr.get('name') or '').lower() == 'waypoints':
                prop = pr
                break
        if prop is None:
            continue
        props = {pr.get('name'): pr.get('value') for pr in o.findall('.//property')}
        try:
            x, y = float(o.get('x')), float(o.get('y'))
        except (TypeError, ValueError):
            continue
        out.append((int(o.get('id', 0)), props.get('enemy') or props.get('name') or '?',
                    x, y, list(legs(prop.get('value'))), prop.get('name')))
    return out


def standable(free, reachable, wpx, hpx, th, x, y):
    """(is_legal, is_reachable) for a waypoint, snapped like reachable_from_entries' entry seeds.

    The same +/-1 tile vertical snap is used and for the same reason: .tmx tile-object y is the BOTTOM
    edge, and a waypoint authored a pixel off a tile boundary must not read as being in a wall.
    """
    legal = reach = False
    for dy in range(-th - 2, th + 3):
        for dx in range(-4, 5):
            bx, by = int(x + 4 + dx), int(y + dy)
            if not (0 <= bx < wpx and 0 <= by < hpx):
                continue
            i = by * wpx + bx
            if free[i]:
                legal = True
                if reachable is not None and reachable[i]:
                    reach = True
    return legal, reach


def feet_box(x, y, feet=None):
    """(x0, y0, x1, y1) in .tmx pixels, y down, half-open: the feet of an enemy whose bottom-left corner
    stands on (x, y). `feet` is enemy_feet()'s (left, right, lift) for it; without one, a 16 px sprite's
    collision box (CharacterSprite.updateBoundingRect(): x + 4 to x + width - 2) standing on y."""
    left, right, lift = feet or DEFAULT_FEET
    bottom = int(math.floor(y - lift))
    return (int(math.floor(x + left)), bottom - FEET_ROWS, int(math.ceil(x + right)), bottom)


def feet_on_floor(reachable, wpx, hpx, x, y, feet=None, player=PLAYER_BOX):
    """Share (0..1) of the feet pixels at (x, y) that a reachable player box covers.

    `reachable` is the corner mask reachable_from_entries() returns (box top-left corners), so a pixel
    is floor when a reachable corner sits within one box of it, up and to the left."""
    pw, ph = player
    x0, y0, x1, y1 = feet_box(x, y, feet)
    good = total = 0
    for py in range(y0, y1):
        for px in range(x0, x1):
            total += 1
            if not (0 <= px < wpx and 0 <= py < hpx):
                continue
            hit = False
            for cy in range(max(0, py - ph + 1), py + 1):
                row = cy * wpx
                for cx in range(max(0, px - pw + 1), px + 1):
                    if reachable[row + cx]:
                        hit = True
                        break
                if hit:
                    break
            good += hit
    return good / float(total) if total else 0.0


DEFAULT_FEET = (4.0, 14.0, 0.0)
ACCEPTED_FEET = os.path.join(HERE, 'waypoint_feet_accepted.json')


def feet_accepted():
    """{map path under maps/map: {"<enemy id>:<waypoint id>": reason}} - the feet legs a reviewed pass
    accepted as deliberate (a flier over water, a lava creature in lava, a pinned booster guard). Same
    idea as pixel_collision_qa.ACCEPTED_UNREACHABLE: a settled question is counted, not re-raised."""
    try:
        return json.load(open(ACCEPTED_FEET, encoding='utf-8')).get('accepted', {})
    except (OSError, ValueError):
        return {}
_FEET = None


def enemy_feet():
    """{enemy name: (left, right, lift)} - where an enemy's VISIBLE feet are, in px from the point it
    stands on: the opaque box (alpha >= 128) of its Idle frames, where CharacterSprite.draw() draws it -
    atlas frame x EnemyData.scale x TuningData.tierSizeMultiplier(tier, frame height x scale,
    boss || keepSize). Many frames carry transparent margins (an LPC frame is 64 px wide around a 16 px
    body), so the frame's own width would put the "feet" in thin air; `lift` is the empty rows under
    the feet. Round 578: an atlas with a `footprint` field draws its body in its tile and a walker on the
    ground, so the geometry comes from sprite_footprints.enemy_geometry(), which knows both ways the game
    draws (and reads settings.json past its // comments, which json.load never could - this used the Java
    defaults every run). An enemy whose atlas cannot be read is left out; callers fall back to DEFAULT_FEET."""
    global _FEET
    if _FEET is not None:
        return _FEET
    import sprite_footprints as sf
    _FEET = {name: (g['body_l'], g['body_r'], g['feet']) for name, g in sf.enemy_geometry().items()}
    return _FEET


def main():
    args = [a for a in sys.argv[1:] if not a.startswith('-')]
    feet = '--feet' in sys.argv
    feet_of = enemy_feet() if feet else {}
    accepted = feet_accepted() if feet else {}
    maps = args or sorted(glob.glob(os.path.join(PLANE, '**', '*.tmx'), recursive=True))
    bad_maps = tot_routes = tot_legs = n_accepted = 0
    findings, stale = [], []
    for path in maps:
        try:
            rs = routes(path)
        except ET.ParseError:
            continue
        if not rs:
            continue
        wps = waypoint_objects(path)
        try:
            free, reachable, wpx, hpx, tw, th, _ = q.reachable_from_entries(path)
        except Exception as e:
            print('  !! %s: %s' % (os.path.basename(path), e))
            continue
        rows = []
        feet_seen = set()   # a leg repeated in one route is one finding
        rel = os.path.relpath(path, PLANE).replace('\\', '/')
        ok_here = accepted.get(rel, {})
        for eid, name, ex, ey, ls, propname in rs:
            tot_routes += 1
            for kind, payload in ls:
                tot_legs += 1
                if kind == 'wait':
                    continue
                if kind == 'bad':
                    rows.append(('unparsed', eid, name, payload, None, None))
                    continue
                for wid in ([payload] if kind == 'wp' else payload):
                    if wid not in wps:
                        rows.append(('dangling', eid, name, wid, None, None))
                        continue
                    wx, wy = wps[wid]
                    legal, reach = standable(free, reachable, wpx, hpx, th, wx, wy)
                    if not legal:
                        rows.append(('blocked', eid, name, wid, wx, wy))
                    elif reachable is not None and not reach:
                        rows.append(('outside', eid, name, wid, wx, wy))
                    elif feet and (eid, wid) not in feet_seen:
                        feet_seen.add((eid, wid))
                        share = feet_on_floor(reachable if reachable is not None else free,
                                              wpx, hpx, wx, wy, feet_of.get(name))
                        key = '%d:%d' % (eid, wid)
                        if share >= FEET_PASS:
                            if key in ok_here:
                                stale.append((rel, key))
                        elif key in ok_here:
                            n_accepted += 1
                        else:
                            rows.append(('feet', eid, name, wid, wx, wy, share))
            if propname != 'waypoints':
                rows.append(('case', eid, name, propname, ex, ey))
        if rows:
            bad_maps += 1
            print('\n=== %s' % rel)
            for row in rows:
                kind, eid, name, wid, wx, wy = row[:6]
                if kind == 'case':
                    print('  CASE     enemy %-4s %-20s property is "%s", not "waypoints"'
                          % (eid, name[:20], wid))
                elif kind == 'unparsed':
                    print('  UNPARSED enemy %-4s %-20s leg "%s" - the game logs a Navigation error'
                          % (eid, name[:20], wid))
                elif kind == 'dangling':
                    print('  DANGLING enemy %-4s %-20s waypoint id %s resolves to nothing'
                          % (eid, name[:20], wid))
                elif kind == 'feet':
                    print('  FEET     enemy %-4s %-20s waypoint %-4s at (%.1f, %.1f) - %d%% of its feet on the floor'
                          % (eid, name[:20], wid, wx, wy, round(row[6] * 100)))
                else:
                    print('  %-8s enemy %-4s %-20s waypoint %-4s at (%.1f, %.1f)'
                          % (kind.upper(), eid, name[:20], wid, wx, wy))
            findings.extend((path, r) for r in rows)
    print('\n%d map(s) with routes checked: %d route(s), %d leg(s), %d map(s) with findings'
          % (len(maps), tot_routes, tot_legs, bad_maps))
    if feet:
        print('%d feet leg(s) accepted as deliberate (%s)' % (n_accepted, os.path.basename(ACCEPTED_FEET)))
        for rel, key in stale:
            print('  STALE    %s %s is accepted but now passes - drop it from the file' % (rel, key))
    return 1 if findings else 0


if __name__ == '__main__':
    sys.exit(main())
