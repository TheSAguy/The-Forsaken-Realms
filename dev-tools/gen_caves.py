"""Generate the round-125 cave set for The Forsaken Realms: 13 caves per biome (78 maps), organic chambers cut with
the shared tileset's Tiled corner-Wang rules, biome-flavored floors, roaming enemies (1-2 "wildcards" of any tier,
the rest Common/Uncommon), loot scaled to the cave's depth, and POI registration.

Usage: python dev-tools/gen_caves.py [--no-register] [--sheets] [biome,biome,...]
Outputs: maps/map/cave/cave_<biome>_<nn>.tmx, points_of_interest.json entries (Cave<L>Gen<nn>), biome
pointsOfInterest lists, gen_caves_manifest.txt beside this script (one line per cave) and, with --sheets, a
contact-sheet PNG per biome. Re-running without --no-register on an already-registered set aborts (names exist).
Round 125 (2026-09-06); deterministic - the same seeds always produce the same 78 files.
"""
import xml.etree.ElementTree as ET, random, json, os, sys, collections, re
from PIL import Image, ImageDraw

REPO = r"F:\FORGE\C--Users-vicwaver-MTG-Forge"
PLANE = os.path.join(REPO, "forge-gui", "res", "adventure", "The Forsaken Realms")
COMMON = os.path.join(REPO, "forge-gui", "res", "adventure", "common")
OUTDIR = os.path.join(PLANE, "maps", "map", "cave")
SCRATCH = os.path.dirname(os.path.abspath(__file__))
TILE = 16
DUNGEON_FIRSTGID = 11905
W = H = 0                                   # set per cave
CAVES_PER_BIOME = 13
SPEED_MODIFIER = 4                          # "movement speed to way-points a little faster than normal" (Common roamers move 20)
THREAT_RANGE = 40

BIOMES = collections.OrderedDict([
    ("white",     dict(letter="W", base=3188, floorsets=["LightDirt", "Cobbles"],     seed=11, icon="CaveWhite",
                       names=["Frosthollow", "Hermit's Cleft", "Snowfall Grotto", "Chapel Undercroft", "Icicle Warren", "Pilgrim's Hollow",
                              "Silent Vestry", "Glacier Pocket", "Whitebriar Den", "Winter Kennel", "Rimeguard Cave", "Marble Sink", "Hallowed Hollow"])),
    ("blue",      dict(letter="U", base=3361, floorsets=["Cobbles", "DarkCobbles"],   seed=22, icon="CaveBlue",
                       names=["Tidecutter Grotto", "Drowned Cistern", "Mistfall Cave", "Kelp Hollow", "Scholar's Refuge", "Brinewell",
                              "Frostmere Cave", "Sunken Archive", "Barnacle Gallery", "Foghorn Cleft", "Saltglass Cavern", "Undertow Pocket", "Coldwater Vault"])),
    ("black",     dict(letter="B", base=6813, floorsets=["DarkCobbles", "Cobbles"],   seed=33, icon="CaveBlack",
                       names=["Bonepile Hollow", "Rotroot Cellar", "Gallows Cave", "Cadaver Vault", "Blightmarsh Pit", "Plague Cistern",
                              "Dead Man's Hollow", "Cutpurse Hideout", "Gravedust Warren", "Miasma Cleft", "Sorrow's Sink", "Nightsoil Cave", "Ashgrave Pocket"])),
    ("red",       dict(letter="R", base=3378, floorsets=["Dirt", "Stone"],            seed=44, icon="CaveRed",
                       names=["Cinder Hollow", "Ember Vent", "Slag Pocket", "Magma Seam", "Brimstone Hollow", "Ashfall Gallery",
                              "Scorchstone Cave", "Devil's Chimney", "Coalbed Warren", "Furnace Cleft", "Red Iron Diggings", "Smolder Pit", "Kiln Hollow"])),
    ("green",     dict(letter="G", base=4186, floorsets=["DarkGras", "Dirt"],         seed=55, icon="CaveGreen",
                       names=["Mossback Hollow", "Root Cellar", "Fungal Bloom", "Bramble Den", "Frogsong Grotto", "Dryad's Nook",
                              "Deepwood Pocket", "Loam Warren", "Fernfall Cave", "Wildvine Cleft", "Beetle Gallery", "Mulch Sink", "Verdant Hollow"])),
    ("colorless", dict(letter="C", base=2856, floorsets=["Cave", "LightDirt"],        seed=66, icon="CaveColorless",
                       names=["Wasteland Cleft", "Corrupted Shaft", "Ashen Hollow", "Dust Cistern", "Salt Cave", "Broken Vault",
                              "Grey Warren", "Hollow of Echoes", "Shattered Adit", "Barren Pocket", "Null Grotto", "Scoured Gallery", "Forsaken Sink"])),
])
CLUTTER = [15668, 15669, 15731, 15732, 15731, 15732]   # dungeon.tsx bones and rubble (no collision)
FARM = {"Clucking Chicken", "Honking Goose", "Meowing Cat", "Tiny Chick", "Barnyard Hen", "Stray Cat", "Camel", "Dog", "Fox", "Pasturing Sheep", "Dainty Pig"}
DANGER = {i: ("shallow" if i <= 5 else "deep" if i <= 10 else "rich") for i in range(1, CAVES_PER_BIOME + 1)}
SIZES = {"shallow": [(36, 18), (40, 20)], "deep": [(40, 20), (44, 22)], "rich": [(44, 22), (48, 24)]}
ENEMY_COUNT = {"shallow": 3, "deep": 4, "rich": 5}

# ---------------------------------------------------------------- tileset: corner wang lookups + sheets for rendering
tsx = ET.parse(os.path.join(COMMON, "maps", "tileset", "main.tsx")).getroot()
def corner_lookup(name):
    ws = [w for w in tsx.find("wangsets").findall("wangset") if w.get("name") == name and w.get("type") == "corner"][0]
    lk = {}
    for wt in ws.findall("wangtile"):
        top, tr, right, br, bottom, bl, left, tl = [int(v) for v in wt.get("wangid").split(",")]
        if any(c not in (0, 1) for c in (tl, tr, br, bl)):
            continue   # multi-color sets (LightDirt, Sand, Gras): only the primary "Surface" color is a floor
        lk.setdefault((1 if tl else 0, 1 if tr else 0, 1 if br else 0, 1 if bl else 0), []).append(int(wt.get("tileid")) + 1)
    return lk
WALLS = corner_lookup("Walls")
FLOORSETS = {n: corner_lookup(n) for cfg in BIOMES.values() for n in cfg["floorsets"]}
MAIN_IMG = Image.open(os.path.join(COMMON, "maps", "tileset", "main.png")).convert("RGBA"); MAIN_COLS = 158
dtsx = ET.parse(os.path.join(COMMON, "maps", "tileset", "dungeon.tsx")).getroot()
DUN_IMG = Image.open(os.path.join(COMMON, "maps", "tileset", dtsx.find("image").get("source"))).convert("RGBA"); DUN_COLS = int(dtsx.get("columns"))
def tile_img(gid):
    if gid >= DUNGEON_FIRSTGID:
        g = gid - DUNGEON_FIRSTGID; return DUN_IMG.crop(((g % DUN_COLS) * 16, (g // DUN_COLS) * 16, (g % DUN_COLS) * 16 + 16, (g // DUN_COLS) * 16 + 16))
    g = gid - 1; return MAIN_IMG.crop(((g % MAIN_COLS) * 16, (g // MAIN_COLS) * 16, (g % MAIN_COLS) * 16 + 16, (g // MAIN_COLS) * 16 + 16))

catalog = {e["name"]: e for e in json.load(open(os.path.join(PLANE, "world", "enemies.json"), encoding="utf-8-sig"))}

def roster(biome, letter):
    """Biome roster split by tier: tagged non-boss roamers (no Boss/Story/Legendary tags, no farm animals, no seasonal
    (Elite) specials) whose colors include the biome's letter. The colorless wasteland's own-color roster is six names,
    so it takes its whole mixed roster (that biome IS every color's cast-offs)."""
    b = json.load(open(os.path.join(PLANE, "world", "biomes", "%s.json" % biome), encoding="utf-8-sig"))
    out = {"Common": [], "Uncommon": [], "Rare": [], "Mythic": []}
    for name in b.get("enemies") or []:
        e = catalog.get(name)
        if not e or e.get("boss") or e.get("tier") not in out: continue
        tags = e.get("questTags") or []
        if not tags or any(t in ("Boss", "Story", "Legendary", "Challenger") for t in tags): continue   # Challenger 20-22 = arena champions
        if name in FARM or "(Elite)" in name: continue
        if (e.get("spawnRate") or 0) <= 0: continue   # arena champions / event-only enemies never roam
        col = e.get("colors") or ""
        if letter != "C" and letter not in col: continue
        out[e["tier"]].append(name)
    for v in out.values(): v.sort()
    return out
ROSTERS = {b: roster(b, cfg["letter"]) for b, cfg in BIOMES.items()}

# ---------------------------------------------------------------- helpers on boolean grids
def blob(mask, cx, cy, rx, ry, rng, wobble=0.35, value=True):
    noise = [[rng.random() for _ in range(W)] for _ in range(H)]
    def sm(x, y):
        tot = n = 0
        for dy in (-1, 0, 1):
            for dx in (-1, 0, 1):
                xx, yy = x + dx, y + dy
                if 0 <= xx < W and 0 <= yy < H: tot += noise[yy][xx]; n += 1
        return tot / n
    for y in range(H):
        for x in range(W):
            d = ((x + 0.5 - cx) / rx) ** 2 + ((y + 0.5 - cy) / ry) ** 2
            if d + wobble * (sm(x, y) - 0.5) < 1.0:
                mask[y][x] = value

def smooth(mask):
    for _ in range(2):
        nxt = [row[:] for row in mask]
        for y in range(1, H - 1):
            for x in range(1, W - 1):
                n = sum(1 for dy in (-1, 0, 1) for dx in (-1, 0, 1) if (dx or dy) and mask[y + dy][x + dx])
                if mask[y][x] and n < 3: nxt[y][x] = False
                elif not mask[y][x] and n >= 6: nxt[y][x] = True
        mask[:] = nxt

def unpinch(mask):
    """No diagonal-only contacts - the corner sets have no checkerboard tiles."""
    changed = True
    while changed:
        changed = False
        for y in range(H - 1):
            for x in range(W - 1):
                a, b, c, d = mask[y][x], mask[y][x + 1], mask[y + 1][x], mask[y + 1][x + 1]
                if a and d and not b and not c: mask[y][x + 1] = True; changed = True
                if b and c and not a and not d: mask[y][x] = True; changed = True

def paint(mask, lookup, rng, repair_by_removal=False):
    """Corner-terrain paint: vertex = 1 if any adjacent cell is in the mask; a cell whose corner combination has no tile
    (the two checkerboard cases: the mask touches the cell only at two opposite corners) is repaired either by adding
    the cell to the mask (floor patches - harmless) or, for the rock mask, by REMOVING one of the two diagonal rock
    cells (widening the passage) so a repair can never seal a corridor. Frame cells are never removed."""
    def vertex(vx, vy):
        for (xx, yy) in ((vx - 1, vy - 1), (vx, vy - 1), (vx - 1, vy), (vx, vy)):
            if 0 <= xx < W and 0 <= yy < H and mask[yy][xx]: return 1
        return 0
    for _ in range(4000):
        grid = [[0] * W for _ in range(H)]; bad = None
        for y in range(H):
            for x in range(W):
                key = (vertex(x, y), vertex(x + 1, y), vertex(x + 1, y + 1), vertex(x, y + 1))
                if key == (0, 0, 0, 0): continue
                if key not in lookup: bad = (x, y, key); break
                v = lookup[key]; grid[y][x] = v[0] if len(v) == 1 else rng.choice(v)
            if bad: break
        if bad is None: return grid
        x, y, key = bad
        fixed = False
        if repair_by_removal:
            diag = [(x - 1, y - 1), (x + 1, y + 1)] if key == (1, 0, 1, 0) else [(x + 1, y - 1), (x - 1, y + 1)]
            for (cx, cy) in diag:
                if 1 <= cx <= W - 2 and 1 <= cy <= H - 2 and mask[cy][cx]:
                    mask[cy][cx] = False; fixed = True; break
        if not fixed:
            mask[y][x] = True
    raise SystemExit("could not repair the terrain")

def components(mask):
    seen = [[False] * W for _ in range(H)]; comps = []
    for y in range(H):
        for x in range(W):
            if mask[y][x] and not seen[y][x]:
                comp = []; stack = [(x, y)]; seen[y][x] = True
                while stack:
                    cx, cy = stack.pop(); comp.append((cx, cy))
                    for dx, dy in ((1, 0), (-1, 0), (0, 1), (0, -1)):
                        nx, ny = cx + dx, cy + dy
                        if 0 <= nx < W and 0 <= ny < H and mask[ny][nx] and not seen[ny][nx]:
                            seen[ny][nx] = True; stack.append((nx, ny))
                comps.append(comp)
    return comps

def dig_corridor(mask, a, b, width=2):
    (x1, y1), (x2, y2) = a, b
    for x in range(min(x1, x2), max(x1, x2) + 1):
        for k in range(width): mask[min(H - 2, max(1, y1 + k))][x] = True
    for y in range(min(y1, y2), max(y1, y2) + 1):
        for k in range(width): mask[y][min(W - 2, max(1, x2 + k))] = True

def xml_attr(s):
    return s.replace("&", "&amp;").replace('"', "&quot;").replace("<", "&lt;").replace(">", "&gt;")

def reward_json(entries):
    return xml_attr(json.dumps(entries))

# ---------------------------------------------------------------- one cave
def make_cave(biome, idx, cfg):
    global W, H
    danger = DANGER[idx]
    rng = random.Random(100000 + cfg["seed"] * 100 + idx)
    W, H = rng.choice(SIZES[danger])
    floorset_name = cfg["floorsets"][0] if rng.random() < 0.7 else cfg["floorsets"][1]
    floorset = FLOORSETS[floorset_name]
    ENTRY_X0 = W // 2 - 1
    entry_cols = (ENTRY_X0, ENTRY_X0 + 1, ENTRY_X0 + 2)

    # chambers: a big main one plus one to three side chambers, overlapping or joined by a corridor
    floor = [[False] * W for _ in range(H)]
    main_c = (W * 0.5 + rng.uniform(-3, 3), H * 0.55 + rng.uniform(-1.0, 1.0))
    blob(floor, main_c[0], main_c[1], rng.uniform(W * 0.22, W * 0.3), rng.uniform(H * 0.22, H * 0.3), rng)
    side_centers = []
    n_sides = rng.choice([1, 2, 2]) if danger == "shallow" else rng.choice([2, 2, 3])
    for i in range(n_sides):
        if i == 0: side = rng.choice([-1, 1])
        elif i == 1: side = -1 if side_centers[0][0] > main_c[0] else 1
        else: side = rng.choice([-1, 1])
        cx = min(W - 6, max(5, main_c[0] + side * rng.uniform(W * 0.27, W * 0.38)))
        cy = min(H - 5, max(4, main_c[1] + rng.uniform(-H * 0.25, H * 0.1) - (H * 0.3 if i == 2 else 0)))
        blob(floor, cx, cy, rng.uniform(4.5, 6.5), rng.uniform(3, 4.5), rng)
        side_centers.append((cx, cy))
    islands = []
    for i in range(rng.choice([1, 2, 2, 3])):
        icx, icy = main_c[0] + rng.uniform(-6, 6), main_c[1] + rng.uniform(-2.5, 2.5)
        blob(floor, icx, icy, rng.uniform(1.4, 2.6), rng.uniform(1.0, 1.8), rng, wobble=0.2, value=False)
        islands.append((icx, icy))
    for x in range(W): floor[0][x] = False; floor[H - 1][x] = False
    for y in range(H): floor[y][0] = floor[y][W - 1] = False
    smooth(floor)
    lowest = max([y for y in range(H) for x in entry_cols if floor[y][x]] or [int(main_c[1])])
    for y in range(lowest, H):
        for x in entry_cols: floor[y][x] = True
    for x in range(W):
        if x not in entry_cols: floor[H - 1][x] = False
    comps = components(floor); comps.sort(key=lambda c: -len(c))
    while len(comps) > 1:
        main, other = comps[0], comps[1]
        a = min(main, key=lambda p: min((p[0] - q[0]) ** 2 + (p[1] - q[1]) ** 2 for q in other[::7]))
        b = min(other, key=lambda q: (a[0] - q[0]) ** 2 + (a[1] - q[1]) ** 2)
        dig_corridor(floor, a, b)
        for x in range(W): floor[0][x] = False; floor[H - 1][x] = floor[H - 1][x] and x in entry_cols
        for y in range(H): floor[y][0] = floor[y][W - 1] = False
        comps = components(floor); comps.sort(key=lambda c: -len(c))
    unpinch(floor)
    rock = [[not floor[y][x] for x in range(W)] for y in range(H)]
    for _ in range(4):
        ground = paint(rock, WALLS, rng, repair_by_removal=True)
        floor = [[not rock[y][x] for x in range(W)] for y in range(H)]
        entrance_comp = next((c for c in components(floor) if (ENTRY_X0 + 1, H - 1) in c), [])
        keep = set(entrance_comp)
        for y in range(H):
            for x in range(W):
                if floor[y][x] and (x, y) not in keep: floor[y][x] = False; rock[y][x] = True

    # floor patches: the base color shows where the cobble set is absent
    cobble = [[True] * W for _ in range(H)]
    interior = [(x, y) for y in range(2, H - 2) for x in range(2, W - 2) if floor[y][x]
                and all(floor[y + dy][x + dx] for dx in (-1, 0, 1) for dy in (-1, 0, 1)) and y < lowest - 1]
    if len(interior) < 12:
        for row in floor: print("".join("." if v else "#" for v in row))
        print("lowest", lowest, "entry", entry_cols, "floor", sum(1 for r in floor for v in r if v), "comps", [len(c) for c in components(floor)])
        raise SystemExit("cave %s %d has too little interior (%d)" % (biome, idx, len(interior)))
    n_patches = rng.choice([2, 3, 3]) + (1 if danger != "shallow" else 0)
    for i in range(n_patches):
        px, py = rng.choice(interior)
        blob(cobble, px, py, rng.uniform(2.0, 3.6), rng.uniform(1.4, 2.4), rng, wobble=0.3, value=False)
    for y in range(H):
        for x in range(W):
            if not floor[y][x]: cobble[y][x] = True
    unpinch(cobble)
    floor_layer = paint(cobble, floorset, rng)

    # placements
    taken = set()
    def far_from(cells, refs, minimum=4):
        return [c for c in cells if all((c[0] - r[0]) ** 2 + (c[1] - r[1]) ** 2 >= minimum * minimum for r in refs)]
    def take(cands):
        cands = [c for c in cands if c not in taken and all((c[0] - t[0]) ** 2 + (c[1] - t[1]) ** 2 > 2 for t in taken)]
        if not cands: cands = [c for c in interior if c not in taken]
        c = rng.choice(cands); taken.add(c); return c
    entrance = (ENTRY_X0 + 1, H - 1)
    chamber_cells = list(interior)
    # waypoints: main chamber, each side chamber, the corridor top, plus 1-2 random interior spots on deeper caves
    targets = [main_c] + side_centers + [(ENTRY_X0 + 1, lowest - 2)]
    for _ in range({"shallow": 0, "deep": 1, "rich": 2}[danger]):
        targets.append(rng.choice(chamber_cells))
    wps = []
    for (cx, cy) in targets:
        near = sorted(chamber_cells, key=lambda c: (c[0] - cx) ** 2 + (c[1] - cy) ** 2)[:12]
        if near: wps.append(take(near))
    # enemies: wildcards of any tier, the rest Common/Uncommon roamers (life <= 20); all a little faster than stock
    ros = ROSTERS[biome]
    n_enemies = ENEMY_COUNT[danger]
    n_wild = 1 if danger == "shallow" else rng.choice([1, 2]) if danger == "deep" else 2
    picks = []
    tiers_left = ["Common", "Uncommon", "Rare", "Mythic"]
    for _ in range(n_wild):
        for _try in range(50):
            tier = rng.choice(tiers_left)
            names = [n for n in ros[tier] if n not in picks]
            if names:
                picks.append(rng.choice(names)); break
    wild_names = set(picks)
    regular = [n for t in ("Common", "Uncommon") for n in ros[t] if (catalog[n].get("life") or 99) <= 20 and n not in picks]
    picks += rng.sample(regular, n_enemies - len(picks))
    rng.shuffle(picks)
    picks.sort(key=lambda n: n not in wild_names)   # wildcards first, so the manifest/render can mark them
    enemy_cells = []
    for name in picks:
        cands = far_from(chamber_cells, [entrance, (ENTRY_X0 + 1, lowest)], 6)
        cands = far_from(cands, enemy_cells, 5) or cands
        enemy_cells.append(take(cands))
    # loot by depth
    def farthest(cells, ref):
        return sorted(cells, key=lambda c: -((c[0] - ref[0]) ** 2 + (c[1] - ref[1]) ** 2))[:10]
    loot = []   # (template, cell, extra_props)
    chest = take(farthest(far_from(chamber_cells, enemy_cells, 3) or chamber_cells, entrance))
    if danger == "shallow":
        chest_reward = [{"type": "randomCard", "count": 1, "addMaxCount": 1}]
    elif danger == "deep":
        chest_reward = [{"type": "randomCard", "count": 1, "addMaxCount": 1}, {"type": "randomCard", "count": 1, "probability": 0.5, "rarity": ["rare"]}]
    else:
        chest_reward = [{"type": "randomCard", "count": 2, "addMaxCount": 1}, {"type": "randomCard", "count": 1, "probability": 0.6, "rarity": ["rare"]}]
    loot.append(("../../../../common/maps/obj/treasure.tx", chest, {"reward": reward_json(chest_reward)}))
    gold = take(far_from(chamber_cells, [chest, entrance], 6) or chamber_cells)
    loot.append(("../../../../common/maps/obj/gold.tx", gold, {} if danger != "rich" else {"reward": reward_json([{"type": "gold", "count": 120, "addMaxCount": 60}])}))
    near_island = [c for c in chamber_cells if abs(c[0] - islands[0][0]) < 5 and abs(c[1] - islands[0][1]) < 4]
    stone = take(far_from(near_island or chamber_cells, [chest, gold], 3) or chamber_cells)
    loot.append(("../../obj/stone.tx", stone, {}))
    if danger != "shallow":
        wood = take(far_from(chamber_cells, [chest, gold, stone], 4) or chamber_cells)
        loot.append(("../../obj/wood.tx", wood, {}))
        shards = take(far_from(chamber_cells, [chest, gold, stone, wood], 4) or chamber_cells)
        loot.append(("../../../../common/maps/obj/manashards.tx", shards, {}))
    if danger == "rich":
        gold2 = take(far_from(chamber_cells, [c for _, c, _ in loot], 4) or chamber_cells)
        loot.append(("../../../../common/maps/obj/gold.tx", gold2, {}))
        if rng.random() < 0.5:
            booster = take(farthest(far_from(chamber_cells, [c for _, c, _ in loot], 3) or chamber_cells, entrance))
            loot.append(("../../../../common/maps/obj/booster.tx", booster, {}))
    clutter = [[0] * W for _ in range(H)]
    for _ in range(rng.randint(5, 10)):
        c = take([c for c in chamber_cells if c not in taken]); clutter[c[1]][c[0]] = rng.choice(CLUTTER)

    # ---- write TMX
    def csv(grid):
        return "\n" + "\n".join(",".join(str(v) for v in row) + ("," if y < H - 1 else "") for y, row in enumerate(grid)) + "\n"
    objs = []; oid = 1
    objs.append('  <object id="%d" template="../../../../common/maps/obj/entry_up.tx" x="%d" y="%d" width="48" height="16">\n'
                '   <properties>\n    <property name="teleport" value=""/>\n   </properties>\n  </object>' % (oid, ENTRY_X0 * TILE, H * TILE + 10)); oid += 1
    wp_ids = []
    for (x, y) in wps:
        objs.append('  <object id="%d" template="../../../../common/maps/obj/waypoint.tx" x="%d" y="%d"/>' % (oid, x * TILE + 8, y * TILE + 8)); wp_ids.append(oid); oid += 1
    enemy_desc = []
    for i, ((x, y), name) in enumerate(zip(enemy_cells, picks)):
        start = (i * 2) % len(wp_ids)
        order = wp_ids[start:] + wp_ids[:start]
        if i % 2 == 1: order = order[::-1]
        objs.append('  <object id="%d" template="../../../../common/maps/obj/enemy.tx" x="%d" y="%d">\n   <properties>\n'
                    '    <property name="enemy" value="%s"/>\n    <property name="speedModifier" type="float" value="%d"/>\n'
                    '    <property name="threatRange" type="int" value="%d"/>\n'
                    '    <property name="waypoints" value="%s"/>\n   </properties>\n  </object>'
                    % (oid, x * TILE, (y + 1) * TILE, xml_attr(name), SPEED_MODIFIER, THREAT_RANGE, ",".join(map(str, order))))
        e = catalog[name]
        enemy_desc.append("%s [%s%s, life %s]" % (name, e.get("tier"), "*" if name in picks[:0] else "", e.get("life"))); oid += 1
    for template, (x, y), props in loot:
        if props:
            objs.append('  <object id="%d" template="%s" x="%d" y="%d">\n   <properties>\n%s\n   </properties>\n  </object>'
                        % (oid, template, x * TILE, (y + 1) * TILE, "\n".join('    <property name="%s">%s</property>' % (k, v) for k, v in props.items())))
        else:
            objs.append('  <object id="%d" template="%s" x="%d" y="%d"/>' % (oid, template, x * TILE, (y + 1) * TILE))
        oid += 1
    base_layer = [[cfg["base"]] * W for _ in range(H)]
    tmx = ('<?xml version="1.0" encoding="UTF-8"?>\n'
           '<map version="1.10" tiledversion="1.12.2" orientation="orthogonal" renderorder="right-down" width="%d" height="%d" tilewidth="16" tileheight="16" infinite="0" nextlayerid="7" nextobjectid="%d">\n'
           ' <tileset firstgid="1" source="../../../../common/maps/tileset/main.tsx"/>\n'
           ' <tileset firstgid="10113" source="../../../../common/maps/tileset/buildings.tsx"/>\n'
           ' <tileset firstgid="11905" source="../../../../common/maps/tileset/dungeon.tsx"/>\n'
           ' <layer id="1" name="Background" width="%d" height="%d">\n  <data encoding="csv">%s</data>\n </layer>\n'
           ' <layer id="6" name="Floor" width="%d" height="%d">\n  <data encoding="csv">%s</data>\n </layer>\n'
           ' <layer id="2" name="Ground" width="%d" height="%d">\n  <data encoding="csv">%s</data>\n </layer>\n'
           ' <layer id="3" name="Foreground" width="%d" height="%d">\n  <properties>\n   <property name="spriteLayer" type="bool" value="true"/>\n  </properties>\n'
           '  <data encoding="csv">%s</data>\n </layer>\n'
           ' <layer id="5" name="AboveSprites" width="%d" height="%d">\n  <data encoding="csv">%s</data>\n </layer>\n'
           ' <objectgroup id="4" name="Objects">\n%s\n </objectgroup>\n</map>\n'
           % (W, H, oid, W, H, csv(base_layer), W, H, csv(floor_layer), W, H, csv(ground), W, H, csv(clutter), W, H, csv([[0] * W for _ in range(H)]), "\n".join(objs)))
    fname = "cave_%s_%02d.tmx" % (biome, idx)
    open(os.path.join(OUTDIR, fname), "w", encoding="utf-8", newline="\n").write(tmx)

    # ---- render
    img = Image.new("RGBA", (W * TILE, H * TILE), (0, 0, 0, 255))
    for layer in (base_layer, floor_layer, ground, clutter):
        for y in range(H):
            for x in range(W):
                g = layer[y][x]
                if g: img.alpha_composite(tile_img(g), (x * TILE, y * TILE))
    d = ImageDraw.Draw(img)
    def mark(cell, color, label):
        x, y = cell[0] * TILE, cell[1] * TILE
        d.rectangle([x, y, x + TILE, y + TILE], outline=color, width=1); d.text((x + 1, y - 10), label, fill=color)
    for i, c in enumerate(wps): mark(c, (255, 220, 0), "w%d" % (i + 1))
    wild = set(picks[:n_wild])
    for c, n in zip(enemy_cells, picks): mark(c, (255, 70, 70), n[:14])
    for template, c, _ in loot: mark(c, (120, 255, 120), os.path.basename(template)[:-3])
    d.rectangle([ENTRY_X0 * TILE, (H - 1) * TILE, (ENTRY_X0 + 3) * TILE, H * TILE], outline=(0, 255, 0), width=2)
    d.text((3, 3), "%02d %s (%s, %dx%d)" % (idx, cfg["names"][idx - 1], danger, W, H), fill=(255, 255, 255))
    tiers = [catalog[n].get("tier") for n in picks]
    return dict(file=fname, name=cfg["names"][idx - 1], danger=danger, size=(W, H), enemies=list(zip(picks, tiers)), wild=n_wild,
                loot=[os.path.basename(t)[:-3] for t, _, _ in loot], floor=sum(1 for r in floor for v in r if v), floorset=floorset_name, img=img)

# ---------------------------------------------------------------- registration
def poi_entry(biome, cfg, idx, r):
    return ('\t{\n\t\t"name": "Cave%sGen%02d",\n\t\t"displayName": "%s",\n\t\t"type": "cave",\n\t\t"count": 1,\n'
            '\t\t"spriteAtlas": "../The Forsaken Realms/maps/tileset/caves.atlas",\n\t\t"sprite": "%s",\n'
            '\t\t"map": "../The Forsaken Realms/maps/map/cave/%s",\n\t\t"radiusFactor": 0.8,\n'
            '\t\t"questTags": [\n\t\t\t"Hostile",\n\t\t\t"Cave",\n\t\t\t"Biome%s"\n\t\t]\n\t}'
            % (cfg["letter"], idx, r["name"].replace('"', '\\"'), cfg["icon"], r["file"], biome.capitalize()))

def register(results):
    poi_path = os.path.join(PLANE, "world", "points_of_interest.json")
    raw = open(poi_path, "rb").read().decode("utf-8-sig")
    crlf = "\r\n" in raw; s = raw.replace("\r\n", "\n")
    names = ["Cave%sGen%02d" % (BIOMES[b]["letter"], i) for b in results for i in results[b]]
    for n in names:
        assert ('"name": "%s"' % n) not in s, "already registered: " + n
    body = s.rstrip(); assert body.endswith("]")
    last = body.rfind("}")
    entries = ",\n".join(poi_entry(b, BIOMES[b], i, results[b][i]) for b in results for i in sorted(results[b]))
    s2 = body[:last + 1] + ",\n" + entries + "\n]\n"
    json.loads(s2)   # must stay strict JSON
    open(poi_path, "wb").write((s2.replace("\n", "\r\n") if crlf else s2).encode("utf-8"))
    print("points_of_interest.json: +%d entries" % len(names))
    for b in results:
        bp = os.path.join(PLANE, "world", "biomes", "%s.json" % b)
        raw = open(bp, "rb").read().decode("utf-8-sig"); crlf = "\r\n" in raw; s = raw.replace("\r\n", "\n")
        m = re.search(r'("pointsOfInterest"\s*:\s*\[)(.*?)(\n[ \t]*\])', s, re.S)
        assert m, b
        inner = m.group(2)
        indent = re.search(r'\n([ \t]*)"', inner).group(1)
        add = "".join(',\n%s"Cave%sGen%02d"' % (indent, BIOMES[b]["letter"], i) for i in sorted(results[b]))
        new_inner = inner.rstrip().rstrip(",") + add
        s2 = s[:m.start(2)] + new_inner + s[m.end(2):]
        open(bp, "wb").write((s2.replace("\n", "\r\n") if crlf else s2).encode("utf-8"))
        print("%s.json: +%d pointsOfInterest" % (b, len(results[b])))

# ---------------------------------------------------------------- main
args = [a for a in sys.argv[1:] if not a.startswith("--")]
biomes = args[0].split(",") if args else list(BIOMES.keys())
results = collections.OrderedDict()
manifest = []
for biome in biomes:
    cfg = BIOMES[biome]; results[biome] = collections.OrderedDict()
    for idx in range(1, CAVES_PER_BIOME + 1):
        r = make_cave(biome, idx, cfg); results[biome][idx] = r
        line = "%-9s %02d %-20s %-7s %2dx%-2d floor=%3d %-11s enemies: %s | loot: %s" % (
            biome, idx, r["name"], r["danger"], r["size"][0], r["size"][1], r["floor"], r["floorset"],
            "; ".join("%s (%s%s)" % (n, t, " WILD" if k < r["wild"] else "") for k, (n, t) in enumerate(r["enemies"])), ", ".join(r["loot"]))
        manifest.append(line); print(line)
    if "--sheets" in sys.argv:   # contact sheet per biome, next to this script
        imgs = [results[biome][i]["img"] for i in results[biome]]
        cols = 3; cw = max(im.size[0] for im in imgs); ch = max(im.size[1] for im in imgs); rows = (len(imgs) + cols - 1) // cols
        sheet = Image.new("RGBA", (cols * cw + (cols - 1) * 6, rows * ch + (rows - 1) * 6), (30, 30, 30, 255))
        for i, im in enumerate(imgs): sheet.paste(im, ((i % cols) * (cw + 6), (i // cols) * (ch + 6)))
        sheet.save(os.path.join(SCRATCH, "caves_%s.png" % biome))
open(os.path.join(SCRATCH, "gen_caves_manifest.txt"), "w", encoding="utf-8").write("\n".join(manifest) + "\n")
if "--no-register" not in sys.argv:
    register(results)
print("done:", sum(len(v) for v in results.values()), "caves")
