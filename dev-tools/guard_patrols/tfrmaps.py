"""tfrmaps.py - one model of a TFR dungeon map, as MapStage loads it (round 316 scratch, shared by audit/plan/apply).

What it replays, and where each rule comes from:

  objects      template-merged the way libGDX's TmxMapLoader does it: the template's properties first, the
               instance's own on top; `type` from a custom "type" property, else the type/class attribute, else
               the template's (same precedence as validate_plane_data.py's round-248 route check).
  loot         MapStage.loadObjects() case "reward": a reward with a non-empty `reward`; the SPRITE decides the
               tier - "booster" in it = booster, "treasure" in it = chest (a missing sprite defaults to
               sprites/treasure.atlas, so it is a chest), anything else = other pickup.
  guards       MapStage.assignLootGuards(): loot sorted booster > chest > other (stable), each piece takes the
               NEAREST non-dialog enemy within 3 tiles (48 px, Euclidean, authored positions) that is not
               already somebody's guard. Booster guards are pinned to their post (EnemySprite.guardPost), so a
               patrol route on one is ignored at runtime.
  spawning     MapStage.canSpawn(): spawn.Hard must be true on the user's Hard save (template default true).
  walking      two grids. The REPO model is dev-tools/pixel_collision_qa.py's own (tile collision only, 10x6
               player box, flood fill from the entries) - it is what waypoint_routes_qa.py validates with. The
               STRICT model adds the object-layer collision rectangles (type "collision", collision.tx) that
               MapStage.loadObjects() also puts into collisionRect but pixel_collision_qa does not rasterize -
               125 maps carry them, shard_mines alone 92. Anything the strict model calls walkable the repo
               model does too.
  navigation   NavigationMap.initializeGeometryGraph() + findShortestPath(): tile-center vertices, removed when
               inside a collision fixture (rect shifted -8 px in x, inflated by navMapSize = 6.4 px), 8-way
               edges, every waypoint object joined to its 4 nearest vertices with a clear ray, the walker's own
               position to its 10 nearest; A* with a Euclidean cost. A patrolling enemy follows THAT path
               between waypoints (MapStage.onActing), it does not walk a straight line - so a route is only
               accepted when the path exists, is short, and the straight segment is clear as well.

Coordinates: everything here is in .tmx pixels, y DOWN, with a tile object's (x, y) being its bottom-left
corner - the convention the repo's placement tools use. Conversions to libGDX's y-up happen only inside NavSim.
"""
import collections
import glob
import heapq
import json
import math
import os
import re
import sys
import xml.etree.ElementTree as ET

sys.dont_write_bytecode = True   # importing the repo's dev-tools must not drop __pycache__ into the repo

Q = W = BG = None                 # pixel_collision_qa, waypoint_routes_qa, booster_guards (set by load_tools)

PLAYER_BOX = (10, 6)
GUARD_REACH_TILES = 3.0           # MapStage.assignLootGuards(): tileSize * 3
NAV_SPRITE = 16.0 * 0.4           # MapStage.navMapSize = defaultSpriteSize * collisionWidthMod
STORY_TAGS = {"Boss", "Story", "Legendary", "Challenger"}   # MapStage.STORY_TAGS
RANK = {"common": 0, "uncommon": 1, "rare": 2, "mythic rare": 3, "mythic": 3}
RANK_NAME = {0: "Apprentice", 1: "Adept", 2: "Master", 3: "Archmage"}


# ------------------------------------------------------------------------------------------------ tools
def find_dev_tools(plane, explicit=None):
    """The repo's dev-tools folder: --dev-tools if given, else walked up from the plane folder."""
    if explicit:
        if not os.path.isfile(os.path.join(explicit, "pixel_collision_qa.py")):
            raise SystemExit("--dev-tools %s has no pixel_collision_qa.py" % explicit)
        return os.path.abspath(explicit)
    d = os.path.abspath(plane)
    for _ in range(8):
        cand = os.path.join(d, "dev-tools")
        if os.path.isfile(os.path.join(cand, "pixel_collision_qa.py")):
            return cand
        nd = os.path.dirname(d)
        if nd == d:
            break
        d = nd
    raise SystemExit("could not find the repo's dev-tools above %s - pass --dev-tools <repo>/dev-tools" % plane)


def load_tools(dev_tools):
    """Import the repo's own collision/route/guard modules (read-only, no bytecode written)."""
    global Q, W, BG
    if dev_tools not in sys.path:
        sys.path.insert(0, dev_tools)
    import pixel_collision_qa as q
    import waypoint_routes_qa as w
    import booster_guards as bg
    Q, W, BG = q, w, bg
    return q, w, bg


def maps_root(plane):
    """<plane>/maps/map, accepting the plane folder itself or the maps/map folder."""
    p = os.path.abspath(plane)
    if os.path.isdir(os.path.join(p, "maps", "map")):
        return os.path.join(p, "maps", "map")
    if os.path.basename(p) == "map" and os.path.basename(os.path.dirname(p)) == "maps":
        return p
    raise SystemExit("%s is not a plane folder (no maps/map inside)" % plane)


def plane_of(root):
    return os.path.dirname(os.path.dirname(root))


def all_maps(root):
    return sorted(glob.glob(os.path.join(root, "**", "*.tmx"), recursive=True))


# ------------------------------------------------------------------------------------------------ enemies.json
def load_enemy_data(plane):
    path = os.path.join(plane, "world", "enemies.json")
    items = json.load(open(path, encoding="utf-8"))
    if isinstance(items, dict):
        items = items.get("enemies", [])
    out = {}
    for e in items:
        if not isinstance(e, dict) or not e.get("name"):
            continue
        tier = (e.get("tier") or "").strip()
        out[e["name"]] = {
            "tier": tier, "rank": RANK.get(tier.lower(), 0),
            "boss": bool(e.get("boss")), "legend": bool(e.get("legend")),
            "spawnRate": float(e.get("spawnRate", 1) if e.get("spawnRate") is not None else 1),
            "tags": [t for t in (e.get("questTags") or []) if t],
            "flying": bool(e.get("flying")), "speed": e.get("speed"),
            "gamesPerMatch": e.get("gamesPerMatch"),
        }
    return out


# ------------------------------------------------------------------------------------------------ .tmx objects
def props_of(elem):
    """Direct <properties>/<property> children only (a class-typed property nests its own)."""
    out = {}
    p = elem.find("properties")
    if p is None:
        return out
    for pr in p.findall("property"):
        v = pr.get("value")
        if v is None:
            v = pr.text or ""
        out[pr.get("name")] = v
    return out


def shape_of(elem):
    for s in ("point", "ellipse", "polygon", "polyline", "text"):
        if elem.find(s) is not None:
            return s
    return None


_TPL = {}


def read_template(path):
    key = os.path.normcase(os.path.normpath(path))
    if key in _TPL:
        return _TPL[key]
    info = {"ok": False, "attrs": {}, "props": {}, "shape": None}
    try:
        obj = ET.parse(path).getroot().find("object")
        if obj is not None:
            info = {"ok": True, "attrs": dict(obj.attrib), "props": props_of(obj), "shape": shape_of(obj)}
    except (OSError, ET.ParseError):
        pass
    _TPL[key] = info
    return info


def truthy(v, default=True):
    if v is None:
        return default
    return str(v).strip().lower() == "true"


class Obj(object):
    __slots__ = ("id", "x", "y", "w", "h", "gid", "type", "props", "own", "template", "tpl", "visible",
                 "layer", "layer_name", "order", "shape", "bg_dialog")

    def tile(self):
        """(tx, ty) of a tile object, the repo tools' convention: y is the bottom edge."""
        return int(self.x // 16), int(self.y // 16) - 1

    def center(self):
        if self.gid:
            return self.x + self.w / 2.0, self.y - self.h / 2.0
        return self.x + self.w / 2.0, self.y + self.h / 2.0

    def footprint(self):
        """(x0, y0, x1, y1) in .tmx pixels, y down."""
        if self.gid:
            return self.x, self.y - self.h, self.x + self.w, self.y
        if self.shape == "point":
            return self.x, self.y, self.x, self.y
        return self.x, self.y, self.x + self.w, self.y + self.h


class TMap(object):
    """One .tmx, parsed once. `rel` is the path under maps/map with forward slashes."""

    def __init__(self, path, root_dir):
        self.path = os.path.abspath(path)
        self.rel = os.path.relpath(self.path, root_dir).replace("\\", "/")
        self.base = os.path.dirname(self.path)
        tree = ET.parse(self.path)
        r = tree.getroot()
        self.xml = r
        self.width, self.height = int(r.get("width")), int(r.get("height"))
        self.tw, self.th = int(r.get("tilewidth")), int(r.get("tileheight"))
        self.wpx, self.hpx = self.width * self.tw, self.height * self.th
        self.nextobjectid = int(r.get("nextobjectid", "0") or 0)
        self.map_props = props_of(r)
        self.objects = []
        order = 0
        for li, layer in enumerate(r.findall("objectgroup")):
            for o in layer.findall("object"):
                ob = self._object(o, li, layer.get("name") or "", order)
                order += 1
                self.objects.append(ob)
        self.by_id = {o.id: o for o in self.objects}
        self._grids = None

    def _object(self, o, li, lname, order):
        tpl_path = o.get("template")
        t = read_template(os.path.join(self.base, tpl_path)) if tpl_path else {"attrs": {}, "props": {},
                                                                               "shape": None, "ok": False}
        own = props_of(o)
        props = dict(t["props"])
        props.update(own)
        ob = Obj()
        ob.id = int(o.get("id", 0))
        ob.x = float(o.get("x", 0) or 0)
        ob.y = float(o.get("y", 0) or 0)
        ob.w = float(o.get("width") or t["attrs"].get("width") or 0)
        ob.h = float(o.get("height") or t["attrs"].get("height") or 0)
        ob.gid = bool(o.get("gid") or t["attrs"].get("gid"))
        ob.type = (own.get("type") or t["props"].get("type") or o.get("type") or o.get("class")
                   or t["attrs"].get("type") or t["attrs"].get("class") or "")
        ob.props = props
        ob.own = own
        ob.template = tpl_path or ""
        ob.tpl = os.path.basename(tpl_path or "")
        ob.visible = o.get("visible", "1") != "0"
        ob.layer = li
        ob.layer_name = lname
        ob.order = order
        ob.shape = shape_of(o) or t["shape"]
        # booster_guards.objects_of() reads `dialog` from the object's own property VALUE attribute only, so a
        # dialog written as element text (every multi-line one) does not count there. Kept separately so the
        # round-286 replay below matches that tool bit for bit.
        ob.bg_dialog = any(pr.get("name") == "dialog" and pr.get("value") for pr in o.iter("property"))
        return ob

    # ---- classification -------------------------------------------------------------------------------
    def spawns(self, o, difficulty="Hard"):
        return truthy(o.props.get("spawn." + difficulty), True)

    def enemies(self, spawned_only=False):
        out = [o for o in self.objects if o.type == "enemy" and (o.props.get("enemy") or "").strip()]
        return [o for o in out if self.spawns(o)] if spawned_only else out

    def loot(self, spawned_only=True):
        """[(obj, tier)] in load order; tier is 'booster' / 'treasure' / 'other' by the runtime's sprite test."""
        out = []
        for o in self.objects:
            if o.type != "reward" or not (o.props.get("reward") or "").strip():
                continue
            if spawned_only and not self.spawns(o):
                continue
            sp = o.props.get("sprite") or "sprites/treasure.atlas"
            tier = "booster" if "booster" in sp else ("treasure" if "treasure" in sp else "other")
            out.append((o, tier))
        return out

    def waypoints(self):
        return {o.id: o for o in self.objects if o.type == "waypoint"}

    def entries(self):
        return [o for o in self.objects if o.type in ("entry", "portal", "exit")]

    def collision_objects(self):
        return [o for o in self.objects if o.type == "collision" and o.w > 0 and o.h > 0 and not o.shape]

    def referenced_ids(self):
        """Object ids named by a deleteMapObject / activateMapObject action anywhere in this map's text."""
        txt = open(self.path, encoding="utf-8", errors="replace").read()
        ids = set()
        for m in re.finditer(r'(?:deleteMapObject|activateMapObject)(?:&quot;|")\s*:\s*(-?\d+)', txt):
            v = int(m.group(1))
            if v > 0:
                ids.add(v)
        return ids

    # ---- grids ----------------------------------------------------------------------------------------
    def grids(self):
        """Both walk models (see the module docstring), computed once."""
        if self._grids is not None:
            return self._grids
        blocked_q, wpx, hpx, tw, th = Q.build_grid(self.path)
        pw, ph = PLAYER_BOX
        free_q = Q.free_positions(blocked_q, wpx, hpx, pw, ph)
        seeds = []
        for o in Q.map_objects(self.path):     # the entries exactly as the repo tools seed them
            if o["kind"] != "entry":
                continue
            for dy in range(-th - 2, th + 3):
                for dx in range(-4, 5):
                    bx, by = int(o["x"] + 4 + dx), int(o["y"] + dy)
                    if 0 <= bx < wpx and 0 <= by < hpx and free_q[by * wpx + bx]:
                        seeds.append(by * wpx + bx)
        reach_q = Q.flood(free_q, wpx, hpx, seeds, want=1) if seeds else None
        # Widened seeding, for PLANNING only. Four maps (cave_kavu, cave_cerodon, cave_spider, zombietown) have an
        # entry the repo tools cannot seed from - cave_kavu's sits at y=285 on a 272 px map, zombietown's on the
        # bottom wall - so every reachability question there answers "unknowable". The game spawns the player
        # inside anyway. Here the free positions within three tiles of each entry's footprint seed instead. The
        # round-286 count keeps the tools' own answer (reach_q), so it is never flattered by this.
        wide = seeds
        if not seeds:
            wide = []
            for o in self.entries():
                x0, y0, x1, y1 = o.footprint()
                for by in range(max(0, int(y0) - 48), min(hpx, int(y1) + 49)):
                    row = by * wpx
                    for bx in range(max(0, int(x0) - 48), min(wpx, int(x1) + 49)):
                        if free_q[row + bx]:
                            wide.append(row + bx)
        reach_qw = reach_q if seeds else (Q.flood(free_q, wpx, hpx, wide, want=1) if wide else None)
        blocked_s = bytearray(blocked_q)
        n_obj = 0
        for c in self.collision_objects():
            x0, y0 = max(0, int(math.floor(c.x))), max(0, int(math.floor(c.y)))
            x1, y1 = min(wpx, int(math.ceil(c.x + c.w))), min(hpx, int(math.ceil(c.y + c.h)))
            if x1 <= x0 or y1 <= y0:
                continue
            n_obj += 1
            for py in range(y0, y1):
                row = py * wpx
                blocked_s[row + x0:row + x1] = b"\x01" * (x1 - x0)
        if n_obj:
            free_s = Q.free_positions(blocked_s, wpx, hpx, pw, ph)
            seeds_s = [s for s in wide if free_s[s]]
            reach_s = Q.flood(free_s, wpx, hpx, seeds_s, want=1) if seeds_s else None
        else:
            free_s, reach_s = free_q, reach_qw
        self._grids = {"blocked_q": blocked_q, "free_q": free_q, "reach_q": reach_q, "reach_qw": reach_qw,
                       "blocked_s": blocked_s, "free_s": free_s, "reach_s": reach_s,
                       "has_entry": bool(seeds), "widened_entry": bool(wide) and not seeds,
                       "collision_objects_inside": n_obj}
        return self._grids


# ------------------------------------------------------------------------------------------------ enemy facts
def enemy_facts(m, e, edata, referenced):
    """Everything the planner needs to know about one enemy placement."""
    name = (e.props.get("enemy") or "").strip()
    d = edata.get(name)
    tags = set(d["tags"]) if d else set()
    reasons = []                           # why it may never be moved or removed
    if (e.props.get("dialog") or "").strip():
        reasons.append("dialog NPC")
    if (e.props.get("defeatDialog") or "").strip():
        reasons.append("defeatDialog script")
    if e.id in referenced:
        reasons.append("named by a map script (delete/activateMapObject)")
    if d is None:
        reasons.append("unknown enemy name")
    else:
        if d["boss"]:
            reasons.append("boss")
        if d["legend"]:
            reasons.append("legend")
        if d["spawnRate"] <= 0:
            reasons.append("scripted placement (spawnRate 0)")
        if tags & STORY_TAGS:
            reasons.append("story tag " + "/".join(sorted(tags & STORY_TAGS)))
    for k, why in (("displayNameOverride", "named (displayNameOverride)"), ("deckOverride", "deckOverride"),
                   ("reward", "carries its own reward"), ("questStageID", "questStageID"),
                   ("spawnCondition", "spawnCondition")):
        if (e.props.get(k) or "").strip():
            reasons.append(why)
    if (e.props.get("effect") or "").strip():
        reasons.append("crowned (effect)")
    hidden = (not e.visible) or truthy(e.props.get("hidden"), False)
    inactive = truthy(e.props.get("inactive"), False)
    if hidden:
        reasons.append("hidden ambusher")
    if inactive:
        reasons.append("inactive set-piece")
    flying = truthy(e.props.get("flying"), d["flying"] if d else False)
    try:
        threat = float(e.props.get("threatRange") or 0)
    except ValueError:
        threat = 0.0
    route = (e.props.get("waypoints") or "").strip()
    conditional = [k for k in ("Easy", "Normal", "Hard", "Insane") if not truthy(e.props.get("spawn." + k), True)]
    return {"id": e.id, "name": name, "tier": d["tier"] if d else "?", "rank": d["rank"] if d else 0,
            "protected": reasons, "dialog": bool((e.props.get("dialog") or "").strip()),
            "hidden": hidden, "inactive": inactive, "flying": flying, "threat": threat, "route": route,
            "not_on": conditional, "x": e.x, "y": e.y}


# ------------------------------------------------------------------------------------------------ guards
def match_guards(m, removed=frozenset(), extra_positions=None):
    """Replay MapStage.assignLootGuards() for a Hard save.

    Returns (loot_rows, guard_of) where loot_rows = [(obj, tier, guard_id or None, dist_px)] in the runtime's
    priority order and guard_of = {enemy id: (loot id, tier)}. `removed` drops enemies (to test a removal);
    `extra_positions` overrides an enemy's position {id: (x, y)} (to test a moved home - unused by the plan,
    which never moves a home, but kept so a caller can ask).
    """
    reach = GUARD_REACH_TILES * m.tw
    loot = m.loot(spawned_only=True)
    pri = {"booster": 2, "treasure": 1, "other": 0}
    order = sorted(range(len(loot)), key=lambda i: -pri[loot[i][1]])       # stable, like libGDX's TimSort
    mobs = [e for e in m.enemies(spawned_only=True) if e.id not in removed
            and not (e.props.get("dialog") or "").strip()]
    pos = {e.id: (extra_positions or {}).get(e.id, (e.x, e.y)) for e in mobs}
    taken = set()
    rows, guard_of = [], {}
    for i in order:
        o, tier = loot[i]
        best, bd = None, None
        for e in mobs:
            if e.id in taken:
                continue
            ex, ey = pos[e.id]
            d = math.hypot(ex - o.x, ey - o.y)
            if d <= reach and (bd is None or d < bd):
                best, bd = e, d
        if best is not None:
            taken.add(best.id)
            guard_of[best.id] = (o.id, tier)
        rows.append((o, tier, best.id if best else None, bd))
    return rows, guard_of


def proximity_guards(m, removed=frozenset(), extra=None):
    """Round 286's definition, computed on the repo model exactly as booster_guards.audit(loot='treasure') does:
    a chest (treasure.tx) is guarded when a non-dialog enemy stands within 3 tiles CHEBYSHEV of it and a reachable
    player can engage that enemy (pixel_collision_qa.engageable). Returns {chest id: [enemy ids]} - an empty list
    is an unguarded chest. `extra` = [(pseudo id, x, y)] adds guard positions (a patrol's waypoints) to test."""
    g = m.grids()
    reach = g["reach_q"]
    chests = [o for o in m.objects if o.tpl.startswith("treasure")]
    mobs = [o for o in m.objects if o.tpl.startswith("enemy") and o.id not in removed]
    out = {}
    for c in chests:
        ok = []
        for e in mobs:
            if e.bg_dialog:                              # booster_guards' own reading of `dialog`
                continue
            d = max(abs(e.x - c.x), abs(e.y - c.y)) / float(m.tw)
            if d <= 3 and reach is not None and Q.engageable(reach, m.wpx, m.hpx, e.x, e.y):
                ok.append(e.id)
        for pid, x, y in (extra or []):
            d = max(abs(x - c.x), abs(y - c.y)) / float(m.tw)
            if d <= 3 and reach is not None and Q.engageable(reach, m.wpx, m.hpx, x, y):
                ok.append(pid)
        out[c.id] = ok
    return out


# ------------------------------------------------------------------------------------------------ routes
def route_points(m, e):
    """[(waypoint id, x, y)] of an enemy's authored route in walking order (random legs expanded), plus the
    raw leg list from waypoint_routes_qa.legs()."""
    wps = m.waypoints()
    pts = []
    legs = list(W.legs(e.props.get("waypoints") or ""))
    for kind, payload in legs:
        ids = [payload] if kind == "wp" else (payload if kind == "random" else [])
        for wid in ids:
            if wid in wps:
                pts.append((wid, wps[wid].x, wps[wid].y))
    return pts, legs


# ------------------------------------------------------------------------------------------------ walk checks
def corner(x, y):
    """The 10x6 player-box corner (pixel_collision_qa's free[] index) for something standing at tile-object
    position (x, y): CharacterSprite's box is (x+4, y, w-6, h*0.4) in y-up, i.e. x+4 and 6 px above the bottom."""
    return int(round(x + 4)), int(round(y - 6))


def open_at(m, x, y, strict=True, margin=1):
    """The walker's feet fit at (x, y) on reachable floor, with `margin` px of slack all round."""
    g = m.grids()
    free = g["free_s"] if strict else g["free_q"]
    reach = g["reach_s"] if strict else g["reach_q"]
    if reach is None:
        return False
    cx, cy = corner(x, y)
    for dy in range(-margin, margin + 1):
        for dx in range(-margin, margin + 1):
            px, py = cx + dx, cy + dy
            if not (0 <= px < m.wpx and 0 <= py < m.hpx):
                return False
            i = py * m.wpx + px
            if not free[i] or not reach[i]:
                return False
    return True


def repo_standable(m, x, y):
    """waypoint_routes_qa.standable() on the repo model - the test its report applies to every waypoint (with the
    widened entry seed in the four maps the tool cannot seed; everywhere else it IS the tool's test)."""
    g = m.grids()
    legal, ok = W.standable(g["free_q"], g["reach_qw"], m.wpx, m.hpx, m.th, x, y)
    return legal and ok


def segment_clear(m, a, b, strict=True):
    """Every point of the straight walk a -> b keeps the walker's feet on reachable floor."""
    g = m.grids()
    free = g["free_s"] if strict else g["free_q"]
    reach = g["reach_s"] if strict else g["reach_q"]
    if reach is None:
        return False
    (ax, ay), (bx, by) = a, b
    n = int(max(abs(bx - ax), abs(by - ay))) + 1
    for k in range(n + 1):
        t = k / float(n)
        cx, cy = corner(ax + (bx - ax) * t, ay + (by - ay) * t)
        if not (0 <= cx < m.wpx and 0 <= cy < m.hpx):
            return False
        i = cy * m.wpx + cx
        if not free[i] or not reach[i]:
            return False
    return True


# ------------------------------------------------------------------------------------------------ nav sim
_TSX = {}


def _tileset_rects(path):
    """{local tile id: [(x, y, w, h)]} rectangle collision objects, top-down within the tile (libGDX keeps
    only RectangleMapObject in MapStage.loadCollision)."""
    key = os.path.normcase(os.path.normpath(path))
    if key in _TSX:
        return _TSX[key]
    out = {}
    try:
        root = ET.parse(path).getroot()
        for tile in root.findall("tile"):
            grp = tile.find("objectgroup")
            if grp is None:
                continue
            boxes = []
            for ob in grp.findall("object"):
                if shape_of(ob):
                    continue
                w, h = float(ob.get("width", 0) or 0), float(ob.get("height", 0) or 0)
                if w <= 0 or h <= 0:
                    continue
                boxes.append((float(ob.get("x", 0) or 0), float(ob.get("y", 0) or 0), w, h))
            if boxes:
                out[int(tile.get("id"))] = boxes
    except (OSError, ET.ParseError):
        pass
    _TSX[key] = out
    return out


def jround(v):
    return math.floor(v + 0.5)   # Java Math.round


class NavSim(object):
    """NavigationMap for one map, y-UP world coordinates like the game."""

    def __init__(self, m, extra_waypoints=()):
        self.m = m
        tw, th, W_, H_ = m.tw, m.th, m.width, m.height
        hpx = m.hpx
        rects = []
        tilesets = []
        for ts in m.xml.findall("tileset"):
            if ts.get("source"):
                tilesets.append((int(ts.get("firstgid")), os.path.join(m.base, ts.get("source"))))
        tilesets.sort()
        for layer in m.xml.findall("layer"):
            data = Q.decode_layer(layer)
            lw = int(layer.get("width", W_))
            for idx, raw in enumerate(data):
                gid = raw & Q.GID_MASK
                if not gid:
                    continue
                own = None
                for first, p in tilesets:
                    if gid >= first:
                        own = (first, p)
                if not own:
                    continue
                boxes = _tileset_rects(own[1]).get(gid - own[0])
                if not boxes:
                    continue
                col, row = idx % lw, idx // lw
                cy_up = H_ - 1 - row
                for (ox, oy, ow, oh) in boxes:
                    ry_up = th - (oy + oh)
                    rects.append((tw * col + ox, th * cy_up + ry_up, float(jround(ow)), float(jround(oh))))
        for c in m.collision_objects():
            rects.append((c.x, hpx - c.y - c.h, c.w, c.h))
        # Fixtures: the game skips a MERGED rect under 3x3; merging is area-exact, so keeping every rect is the
        # same geometry plus a few isolated specks - conservative, never more permissive.
        half = NAV_SPRITE / 2.0
        self.fx = []
        for (x, y, w, h) in rects:
            cx, cy = x + w / 2.0 - 8.0, y + h / 2.0
            hw, hh = (w + NAV_SPRITE) / 2.0, (h + NAV_SPRITE) / 2.0
            self.fx.append((cx - hw, cy - hh, cx + hw, cy + hh))
        # bucket fixtures by 64 px cells for the ray tests
        self.cell = 64.0
        self.buckets = collections.defaultdict(list)
        for k, (x0, y0, x1, y1) in enumerate(self.fx):
            for bx in range(int(math.floor(x0 / self.cell)), int(math.floor(x1 / self.cell)) + 1):
                for by in range(int(math.floor(y0 / self.cell)), int(math.floor(y1 / self.cell)) + 1):
                    self.buckets[(bx, by)].append(k)
        # grid vertices
        alive = [[True] * H_ for _ in range(W_)]
        for (x0, y0, x1, y1) in self.fx:
            i0 = max(0, int(math.ceil((x0 - tw / 2.0) / tw)))
            i1 = min(W_ - 1, int(math.floor((x1 - tw / 2.0) / tw)))
            j0 = max(0, int(math.ceil((y0 - th / 2.0) / th)))
            j1 = min(H_ - 1, int(math.floor((y1 - th / 2.0) / th)))
            for i in range(i0, i1 + 1):
                col = alive[i]
                for j in range(j0, j1 + 1):
                    col[j] = False
        self.pos = {}
        self.adj = collections.defaultdict(dict)
        for i in range(W_):
            for j in range(H_):
                if alive[i][j]:
                    self.pos[("g", i, j)] = (i * tw + tw / 2.0, j * th + th / 2.0)
        for i in range(W_):
            for j in range(H_):
                if not alive[i][j]:
                    continue
                a = ("g", i, j)
                for di, dj in ((-1, 0), (0, -1), (-1, -1), (-1, 1)):
                    ni, nj = i + di, j + dj
                    if 0 <= ni < W_ and 0 <= nj < H_ and alive[ni][nj]:
                        self._edge(a, ("g", ni, nj))
        for v in [v for v in self.pos if not self.adj.get(v)]:
            del self.pos[v]
        # waypoint vertices, in the iteration order of MapStage.waypoints (a java.util.HashMap<Integer, Vector2>
        # filled in load order): bucket = id & (capacity - 1), insertion order within a bucket. The order only
        # decides which earlier waypoint vertex a later one may link to, but it costs nothing to get right.
        allw = [(o.id, o.x, o.y) for o in sorted(m.waypoints().values(), key=lambda o: o.order)]
        allw += list(extra_waypoints)
        cap = 16
        while len(allw) > 0.75 * cap:
            cap *= 2
        seq = sorted(range(len(allw)), key=lambda k: ((allw[k][0] ^ (allw[k][0] >> 16)) & (cap - 1), k))
        for k in seq:
            wid, x, y = allw[k]
            self.add_waypoint(("w", wid), (x, hpx - y))

    def _edge(self, a, b):
        pa, pb = self.pos[a], self.pos[b]
        d = math.hypot(pa[0] - pb[0], pa[1] - pb[1])
        self.adj[a][b] = d
        self.adj[b][a] = d

    def ray_clear(self, p, q):
        """Box2D World.rayCast(p -> q) hits no fixture; a fixture containing p is not reported (as in Box2D)."""
        x0, y0 = p
        x1, y1 = q
        bx0, bx1 = sorted((x0, x1))
        by0, by1 = sorted((y0, y1))
        seen = set()
        for bx in range(int(math.floor(bx0 / self.cell)), int(math.floor(bx1 / self.cell)) + 1):
            for by in range(int(math.floor(by0 / self.cell)), int(math.floor(by1 / self.cell)) + 1):
                for k in self.buckets.get((bx, by), ()):
                    if k in seen:
                        continue
                    seen.add(k)
                    fx0, fy0, fx1, fy1 = self.fx[k]
                    if fx0 < x0 < fx1 and fy0 < y0 < fy1:
                        continue               # starts inside: Box2D's polygon ray test reports nothing
                    if _seg_hits_box(x0, y0, x1, y1, fx0, fy0, fx1, fy1):
                        return False
        return True

    def _nearest(self, p, limit, exclude=None):
        cand = sorted(self.pos.items(), key=lambda kv: jround((kv[1][0] - p[0]) ** 2 + (kv[1][1] - p[1]) ** 2))
        out = []
        for v, vp in cand:
            if len(out) >= limit:
                break
            if v == exclude or (abs(vp[0] - p[0]) < 1e-6 and abs(vp[1] - p[1]) < 1e-6):
                continue
            if self.ray_clear(p, vp):
                out.append(v)
        return out

    def add_waypoint(self, key, p):
        near = self._nearest(p, 4)
        self.pos[key] = p
        for v in near:
            self._edge(key, v)

    def path(self, origin, dest_key, origin_key=None):
        """(length px, [points]) of the A* path from a position to a waypoint vertex, or (None, []).

        `origin_key` names a vertex the walker is standing on (the previous waypoint): findShortestPath() then
        finds the origin already in the graph and uses that vertex's own edges instead of linking it anew."""
        if dest_key not in self.pos:
            return None, []
        if origin_key is not None and origin_key in self.pos:
            return self._astar(origin_key, dest_key)
        tmp = ("o",)
        self.pos[tmp] = origin
        try:
            for v in self._nearest(origin, 10, exclude=tmp):
                self._edge(tmp, v)
            return self._astar(tmp, dest_key)
        finally:
            for v in list(self.adj.get(tmp, {})):
                self.adj[v].pop(tmp, None)
            self.adj.pop(tmp, None)
            self.pos.pop(tmp, None)

    def _astar(self, s, t):
        tp = self.pos[t]
        h = lambda v: math.hypot(self.pos[v][0] - tp[0], self.pos[v][1] - tp[1])
        g = {s: 0.0}
        prev = {}
        heap = [(h(s), 0, s)]
        cnt = 1
        closed = set()
        while heap:
            _f, _c, v = heapq.heappop(heap)
            if v in closed:
                continue
            if v == t:
                pts = [self.pos[v]]
                while v in prev:
                    v = prev[v]
                    pts.append(self.pos[v])
                return g[t], pts[::-1]
            closed.add(v)
            for u, c in self.adj.get(v, {}).items():
                ng = g[v] + c
                if ng < g.get(u, 1e18):
                    g[u] = ng
                    prev[u] = v
                    heapq.heappush(heap, (ng + h(u), cnt, u))
                    cnt += 1
        return None, []


def _seg_hits_box(x0, y0, x1, y1, bx0, by0, bx1, by1):
    """Liang-Barsky: does the closed segment touch the open box?"""
    dx, dy = x1 - x0, y1 - y0
    t0, t1 = 0.0, 1.0
    for p, q in ((-dx, x0 - bx0), (dx, bx1 - x0), (-dy, y0 - by0), (dy, by1 - y0)):
        if p == 0:
            if q <= 0:
                return False
        else:
            r = q / p
            if p < 0:
                if r > t1:
                    return False
                if r > t0:
                    t0 = r
            else:
                if r < t0:
                    return False
                if r < t1:
                    t1 = r
    return t0 < t1 or (t0 == t1 and 0.0 <= t0 <= 1.0)


# ------------------------------------------------------------------------------------------------ small utils
def tiles(d_px, tw=16):
    return d_px / float(tw)


def dist(a, b):
    return math.hypot(a[0] - b[0], a[1] - b[1])


def fmt_num(v):
    """The repo tools' number format for a written coordinate (add_booster_guards.fmt)."""
    return str(int(v)) if float(v) == int(v) else ("%.4f" % v).rstrip("0").rstrip(".")
