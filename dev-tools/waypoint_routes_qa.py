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

usage: python dev-tools/waypoint_routes_qa.py                 # every plane map with routes
       python dev-tools/waypoint_routes_qa.py <map.tmx> [...]
"""
import glob
import os
import re
import sys
import xml.etree.ElementTree as ET

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
import pixel_collision_qa as q

PLANE = os.path.join(HERE, '..', 'forge-gui', 'res', 'adventure', 'The Forsaken Realms', 'maps', 'map')


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


def main():
    args = [a for a in sys.argv[1:] if not a.startswith('-')]
    maps = args or sorted(glob.glob(os.path.join(PLANE, '**', '*.tmx'), recursive=True))
    bad_maps = tot_routes = tot_legs = 0
    findings = []
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
            if propname != 'waypoints':
                rows.append(('case', eid, name, propname, ex, ey))
        if rows:
            bad_maps += 1
            print('\n=== %s' % os.path.relpath(path, PLANE).replace('\\', '/'))
            for kind, eid, name, wid, wx, wy in rows:
                if kind == 'case':
                    print('  CASE     enemy %-4s %-20s property is "%s", not "waypoints"'
                          % (eid, name[:20], wid))
                elif kind == 'unparsed':
                    print('  UNPARSED enemy %-4s %-20s leg "%s" - the game logs a Navigation error'
                          % (eid, name[:20], wid))
                elif kind == 'dangling':
                    print('  DANGLING enemy %-4s %-20s waypoint id %s resolves to nothing'
                          % (eid, name[:20], wid))
                else:
                    print('  %-8s enemy %-4s %-20s waypoint %-4s at (%.1f, %.1f)'
                          % (kind.upper(), eid, name[:20], wid, wx, wy))
            findings.extend((path, r) for r in rows)
    print('\n%d map(s) with routes checked: %d route(s), %d leg(s), %d map(s) with findings'
          % (len(maps), tot_routes, tot_legs, bad_maps))
    return 1 if findings else 0


if __name__ == '__main__':
    sys.exit(main())
