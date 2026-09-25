#!/usr/bin/env python
"""
make_templates.py - the Player Town and Player Capitol templates on the player's own ground (round 342).

The user: "player_town.tmx - Replace background with new player background. player_capital.tmx - Replace background
with new player background. Replace roads with new Roads above. (Save the templates here ...Player_Cap)".

From the plane's maps/map/towns/*.tmx it writes, into --out (the user's Player_Cap folder by default):
  player_town.tmx     Background = the player land's base ground; two patch bands laid on the Ground layer where
                      nothing sits, the way the overworld lays them (bands of one smooth noise field: Player_1 at
                      <= 0.25, and at >= 0.75 the light Player_3 art rather than the overworld's Player_2 gravel,
                      which read as bits of road - round 342b).
  player_capital.tmx  the same ground; the old sand islands in Ground gone (they were the White Cap ground); every
                      road cell - the blue-grey strips in Walls (the courtyard) and Ground (the right side), and the
                      sand-hatched road in Ground (the entrance) - repainted with the road terrain set, each cell in
                      the layer it came from, so the courtyard's road still lies over the courtyard's brick.
Both templates carry every tileset make_tilesets.py writes (player_land, road, mv_walls, the MV sheets, plain and
-collide), referenced by absolute path so they open from the Player_Cap folder; Tiled relativises the paths when it
saves. When a template comes back into maps/map/towns/, point the sources at ../../tileset/ again (and drop the
tilesets the map does not use - Map > Remove Unused Tilesets).
"""
import argparse
import os
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
import paint  # noqa: E402
import tmx  # noqa: E402

REPO = os.path.dirname(os.path.dirname(HERE))
PLANE = os.path.join(REPO, "forge-gui", "res", "adventure", "The Forsaken Realms")
TOWNS = os.path.join(PLANE, "maps", "map", "towns")
TILESETS = os.path.join(PLANE, "maps", "tileset")
DEFAULT_OUT = r"C:\Users\User\Pictures\Screenshots\Player_Cap"

# main.png tile ids (local) in the Capitol
SAND = {1860, 1861, 1862, 2018, 2019, 2020, 2176, 2177, 2178, 2335, 2336, 2493, 2494}
ROAD_STRIP = {2309, 2310, 2466, 2468, 2469, 2624, 2625, 2626, 2782, 2783, 2784, 2785}       # blue-grey road strips
ROAD_SAND = {2286, 2287, 2288, 2289, 2290, 2291, 2444, 2445, 2446, 2447, 2449, 2602, 2603, 2604, 2605, 2606, 2607}
COURTYARD_BRICK = 2945

EXTRA_TILESETS = ["player_land.tsx", "road.tsx", "mv_walls.tsx", "mv_walls-collide.tsx",
                  "mv_statues.tsx", "mv_statues-collide.tsx",
                  "mv_outside_a1.tsx", "mv_outside_a1-collide.tsx", "mv_outside_a2.tsx", "mv_outside_a2-collide.tsx",
                  "mv_outside_a4.tsx", "mv_outside_a4-collide.tsx", "mv_outside_a5.tsx", "mv_outside_a5-collide.tsx",
                  "mv_outside_b.tsx", "mv_outside_b-collide.tsx", "mv_world_a2.tsx", "mv_world_a2-collide.tsx",
                  "mv_world_b.tsx", "mv_world_b-collide.tsx", "mv_world_c.tsx", "mv_world_c-collide.tsx"]

SEED = 342


def main_local(m, gid):
    """The main.png tile id behind a gid (any of the map's main / main-nocollide tilesets), or None."""
    if not gid:
        return None
    ts = m.tileset_for(gid)
    if ts is None or not ts.name.startswith("main"):
        return None
    return (gid & tmx.FLIP_MASK) - ts.firstgid


def add_tilesets(m):
    m.absolutize()
    firstgids = {}
    for name in EXTRA_TILESETS:
        path = os.path.join(TILESETS, name).replace(os.sep, "/")
        firstgids[name] = m.add_external_tileset(path)
    return firstgids


def lay_ground(m, land_gid, sets, label):
    w, h = m.width, m.height
    base = sets["ground Player"][",".join(["1"] * 8)]
    bg = m.layers["Background"]
    bg.cells = [land_gid + base] * (w * h)
    ground = m.layers["Ground"]
    ground2 = m.layers.get("Ground2")
    field = paint.value_noise(w, h, 6, SEED)
    patched = set()

    def skip(x, y):
        if ground2 is not None and ground2.get(x, y):
            return True  # under the courtyard's brick - never seen
        return ground.get(x, y) != 0 and (x, y) not in patched

    def band(name, inside):
        n = paint.paint_set(ground, w, h, inside, sets[name], land_gid, outside_counts=True, skip=skip)
        for y in range(h):
            for x in range(w):
                if inside(x, y) and not skip(x, y):
                    patched.add((x, y))
        return n

    # Round 342b (the user: the Player_2 gravel patches outside the walls read as bits of road - "make those patches
    # from the player terrain"): the high band takes the light green overlay art instead of the gravel.
    n1 = band("ground Player_1", lambda x, y: field[y][x] <= 0.25)
    n3 = band("ground Player_3", lambda x, y: field[y][x] >= 0.75)
    print("%s: base on %d cells; patches Player_1 %d, Player_3 %d" % (label, w * h, n1, n3))


def clean_capital(m):
    ground, ground2 = m.layers["Ground"], m.layers["Ground2"]
    sand = hidden = 0
    for y in range(m.height):
        for x in range(m.width):
            lid = main_local(m, ground.get(x, y))
            if lid in SAND:
                ground.set(x, y, 0)
                sand += 1
            elif lid == COURTYARD_BRICK and ground2.get(x, y):
                ground.set(x, y, 0)  # the same brick sits over it in Ground2 - never seen
                hidden += 1
    print("capital: %d sand cells cleared from Ground, %d hidden brick cells under Ground2" % (sand, hidden))


def replace_roads(m, road_gid, road_set):
    ground, ground2, walls = m.layers["Ground"], m.layers["Ground2"], m.layers["Walls"]
    mask = set()
    buried = 0
    for layer, family in ((walls, ROAD_STRIP), (ground, ROAD_STRIP | ROAD_SAND)):
        for y in range(m.height):
            for x in range(m.width):
                if main_local(m, layer.get(x, y)) in family:
                    layer.set(x, y, 0)
                    if layer is ground and ground2.get(x, y):
                        buried += 1  # the White Cap layout's road under the courtyard's brick - never seen
                    else:
                        mask.add((x, y))

    def target(x, y):
        return "Walls" if ground2.get(x, y) else "Ground"

    painted = {}
    for name in ("Ground", "Walls"):
        painted[name] = paint.paint_set(m.layers[name], m.width, m.height, lambda x, y: (x, y) in mask, road_set,
                                        road_gid, outside_counts=True, skip=lambda x, y, n=name: target(x, y) != n)
    print("capital: %d road cells - %d on Ground, %d on Walls (over the courtyard); %d buried road cells dropped"
          % (len(mask), painted["Ground"], painted["Walls"], buried))


def build(name, out_dir, renders):
    m = tmx.Map(os.path.join(TOWNS, name + ".tmx"))
    if renders:
        m.render(2).save(os.path.join(renders, "before_%s.png" % name))
    gids = add_tilesets(m)
    land = paint.load_wangsets(os.path.join(TILESETS, "player_land.tsx"))
    if name == "player_capital":
        clean_capital(m)
    lay_ground(m, gids["player_land.tsx"], land, name)
    if name == "player_capital":
        road = paint.load_wangsets(os.path.join(TILESETS, "road.tsx"))["road"]
        replace_roads(m, gids["road.tsx"], road)
    out = os.path.join(out_dir, name + ".tmx")
    m.write(out)
    print("wrote", out)
    written = tmx.Map(out)
    missing = written.missing()
    if missing:
        raise SystemExit("%s: %d reference(s) do not resolve from %s: %s" % (name, len(missing), out_dir, missing))
    print("%s: all %d file references resolve (tilesets, images, object templates)" % (name, len(written.references())))
    if renders:
        written.render(2).save(os.path.join(renders, "after_%s.png" % name))


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--out", default=DEFAULT_OUT)
    ap.add_argument("--renders", default="", help="folder for before_/after_ PNG renders (2x)")
    a = ap.parse_args()
    os.makedirs(a.out, exist_ok=True)
    for name in ("player_town", "player_capital"):
        build(name, a.out, a.renders or None)


if __name__ == "__main__":
    main()
