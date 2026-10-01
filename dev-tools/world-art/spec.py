"""Round 303 design: every color's structures (autotile areas) and doodads (scattered sprites).

STRUCTURES[color][name] = (source, transform)
  source: "a2:c_r" / "a1:name" (a converted autotile) or "grid:<sprite ref>" (one object per tile)
  transform: dict for sources.tint(), or None
DOODADS[color] = [(name, [sprite refs], sprite transform, layer, startArea, endArea, resolution, density, box[, extra])]
  layer -1 lies flat under everything, 0 stands with the actors; box = the max w x h in px at 32 px per tile;
  extra = {"onStructures": [...]} for a doodad placed only on those structures' tiles (water doodads).
"""

AUTUMN = dict(hue=-0.24, sat=1.35, val=1.45)        # green foliage -> bright orange-red
GOLDEN = dict(hue=-0.14, sat=1.2, val=1.4)          # green -> golden yellow
TEAL = dict(hue=0.10, sat=0.85, val=0.85)           # green -> dark teal
SWAMP = dict(hue=0.06, sat=0.65, val=0.62)          # murky dark green
CHARRED = dict(sat=0.45, val=0.55)
GREY = dict(sat=0.12, val=0.95)
ASH = dict(sat=0.10, val=0.78)
PALE = dict(sat=0.55, val=1.12)
REDDISH = dict(hue=-0.03, sat=1.25, val=0.92)
DARKROCK = dict(hue=0.30, sat=0.35, val=0.70)
BLUEGREY = dict(hue=0.55, sat=0.35, val=1.0)
DRY = dict(hue=-0.07, sat=0.75, val=1.05)
SPRING = dict(hue=-0.03, sat=1.1, val=1.12)

# Round 328 (user: "On the Wasteland terrain, it's a little hard to see some of the 'collision' tiles, so I keep
# running into them. Let's maybe add a 1 pixel black border to them?"): obstacles drawn with a 1-px black outline,
# so an outlined picture reads as blocked beside the walkable doodads. Only GRID structures (one picture repeated
# per tile) - an area autotile is stitched from quarter tiles in the world, and an outline drawn on its block breaks
# at those seams (stray dashes, seen in the preview).
# Round 331: EMPTY - the game draws the border itself, around the stitched shape of every colliding structure
# (World.outlineStructures(), "outline" in a biome's mappingInfo), so nothing is baked any more; tree4's baked
# outline was taken back out (an entry here would give it a double border).
OUTLINED_STRUCTURES = {
}

STRUCTURES = {
    "white": {
        "tree": ("a2:4_0", AUTUMN),
        "tree2": ("a2:4_0", GOLDEN),
        "tree3": ("a2:4_2", None),
        "plateau": ("a2:7_1", None),
        "rock": ("a2:6_3", None),
        "mesa": ("a2:6_1", None),
        "cactus": ("grid:rdx:54", None),
        "cactus2": ("grid:cy:154", None),
        "cactus3": ("grid:cy:153", None),
    },
    "blue": {
        "water": ("a1:sea", None),
        "tree": ("a2:4_2", None),
        "tree2": ("a2:4_2", dict(hue=0.06, sat=0.9, val=0.85)),
        "pineapple": ("grid:cy:45", None),
        "rock": ("grid:rdx:23", dict(hue=0.55, sat=0.3, val=1.0)),   # r305: grey coastal boulders, not the snow hills
        "rock2": ("a2:6_2", None),
        "rock3": ("a2:6_3", BLUEGREY),
        "rock4": ("a2:6_1", PALE),
        "dune": ("a2:6_1", dict(sat=0.6, val=1.15)),
        "dune2": ("a2:7_1", dict(sat=0.6, val=1.15)),
    },
    "black": {
        "water": ("a1:lake", dict(hue=-0.05, sat=0.75, val=0.8)),
        "tree": ("a2:4_0", TEAL),
        "tree2": ("a2:4_1", None),
        "tree3": ("grid:rdx:146", dict(hue=0.72, sat=0.8, val=0.85)),
        "tree4": ("a2:5_0", SWAMP),
        "rock": ("a2:6_2", DARKROCK),
        "rock2": ("a2:6_3", ASH),
        "muck": ("a1:poison", None),
        "dead_tree": ("a2:4_1", CHARRED),
        "dead_tree2": ("a2:4_1", dict(hue=-0.05, sat=0.8, val=0.7)),
        "dead_tree3": ("grid:rdx:48", CHARRED),
    },
    "red": {
        "mountain": ("a2:7_2", None),
        "tree": ("a2:5_0", None),
        "tree2": ("a2:5_0", dict(sat=0.8, val=0.8)),
        "tree3": ("a2:4_0", dict(hue=-0.27, sat=1.3, val=1.05)),
        "tree4": ("a2:4_1", dict(hue=-0.02, sat=1.2, val=0.95)),
        "rock": ("a2:6_3", REDDISH),
        "lava": ("a1:lava", None),
        "dead_tree": ("a2:4_1", CHARRED),
        "dead_tree2": ("grid:rdx:48", dict(hue=-0.02, sat=0.7, val=0.6)),
    },
    "green": {
        "water": ("a1:lake", None),
        "tree": ("a2:4_0", None),
        "tree2": ("a2:4_0", SPRING),
        "vine": ("grid:rdx:141", None),
        "tree3": ("a2:4_0", dict(hue=0.07, sat=0.9, val=0.95)),
        "tree4": ("a2:5_0", None),
        "tree5": ("grid:rdx:99", None),
        "rock": ("grid:rdx:23", None),
        "mountain": ("a2:6_0", None),
        "plant": ("grid:rdx:106", None),
        "bush": ("grid:rdx:102", None),
    },
    "colorless": {
        "crater": ("a2:5_3", dict(sat=0.1, val=0.68)),   # r305: the dim rim the user liked, not the bright one
        "hole": ("a2:5_3", dict(sat=0.1, val=0.62)),
        "tree": ("a2:4_1", GREY),
        "tree2": ("grid:rdx:59", GREY),
        "tree3": ("a2:4_1", ASH),
        "tree4": ("grid:rdx:48", GREY),
        "rock": ("a2:6_3", GREY),
        "mountain": ("a2:6_2", None),
    },
    "player": {
        "crater": ("a2:4_0", None),
        "tree": ("a2:4_0", SPRING),
        "tree2": ("a2:5_0", SPRING),
        "tree3": ("grid:rdx:106", None),
        "tree4": ("a2:4_0", dict(hue=0.03, sat=1.0, val=1.05)),
        "rock": ("grid:rdx:23", None),
        "mountain": ("a2:6_0", None),
        "hole": ("a1:lake", None),
    },
}

# name, sprites, transform, layer, start, end, resolution, density, box(w,h)[, extra]
# Bands: world generation reads simplex noise mapped to 0..1, which sits mostly between 0.3 and 0.7 - the common filler
# of each land lives there, themed clusters in the tails, rare finds (bones, stumps) anywhere (0..1, resolution 1). The
# first kind in the list that passes wins a tile, so the rare ones come first.
# Round 309 (user: "I want to double or triple them. The the number on the map, but the variety of them"): each land's
# kinds roughly doubled and its pictures more than doubled, from the user's other sheets (sources.SHEETS). The number on
# the map stays: a new kind takes a slice of an older kind's noise band at the same resolution and density (World.
# pickDoodad(): the first kind whose band holds the tile and whose density roll passes), and the new rare kinds share
# the old rare kind's density. A ref may carry its own transform - (ref, tint) - over the kind's.
BLEACH = dict(sat=0.35, val=1.25)                   # driftwood, sun-bleached
PURPLE = dict(hue=0.45, sat=0.55, val=0.72)         # green plants -> black's dusky purple
VIOLET = dict(hue=-0.28, sat=0.9, val=0.8)          # orange and brown caps -> purple
GRAVE = dict(toward=(70, 45, 85), amount=0.15)       # stone -> a touch of black's purple
SHELL = dict(hue=0.05, sat=0.5, val=1.35)           # round 303's grey shells, whitened
DUSK = dict(toward=(70, 45, 85), amount=0.35, val=0.85)   # rock and bone -> black's shadowed purple-grey
RUST = dict(hue=-0.04, sat=1.35, val=0.9)           # sandstone -> red canyon rock
EMBER = dict(hue=-0.05, sat=1.2, val=0.8)           # dry plants -> red's scorched brown
SAND = dict(sat=0.85, val=1.05)                     # desert art on white's savanna

# Round 379, 2026-09-30 (user: "find 5-8 new doodads we can add per biome ... I don't want to add MORE doodads on the map,
# but have more variety"): 8 new pictures per land from the user's New Art\Terrain sheets, pre-cut into one
# sheet (sources.SHEETS "nd", 96 px cells), each in place of a duplicate; the copies a land shared with another
# land go from all but one of them, so no picture is on two of the six lands and no kind holds a twin. The kinds,
# bands and densities are unchanged (the same number on the map); saves need no re-scatter - a placed doodad keeps
# its kind and draws its picture from the atlas by position. All 48 are ground level (the user: "small things on
# the ground you can run over" - no trees, trunks, rocks or pillars, which read as the blocking structures).
# Round 381 (user: "swap the older tree doodads for ground-level ones too"): every picture that read as tall or
# blocking - trees, trunks, tall stumps, palms, conifers, dead trees, boulders/rocks, spires, pillars, arches, tall
# cacti, gravestones, and pictures that are also a blocking structure - replaced in place by a ground-level one
# (sheet nd rows 6+), on all seven lands; the kinds, bands, densities and picture counts are unchanged.
# Round 388, 2026-10-01 (user: "some look cut/clipped"): 29 pictures were single 32 px tiles of objects that span
# several tiles (or a tile of a ground texture). Each was re-cut whole (the full block, staged into its old nd cell) or
# swapped for a whole, unused ground-level picture (doodad_table.py draws every picture by land); eight cy:/rdx:
# pictures drop the soft shadow that hardened into a black blob ("~"), and bc:15,2#0 sheds a stray pixel. Cells
# nd:0..3,18 are new. Kinds, bands, densities and picture counts are unchanged.
# Round 388 (the user: "It looks like I'm behind the doodads ... the doodads need to be on the ground and the player
# can't seem to be behind it"): every doodad lies flat under everything (layer -1). Layer 0 stood with the actors
# and was depth-sorted against them, so walking just above one drew the player behind it. The game reads the layer
# from map_sprites.json, not the save, so placed doodads follow at once.
DOODADS = {
    "white": [
        ("WhiteBones", ["nd:2,0", "rdx:155", "nd:1,0", "zb@163,422,187,446"], None, -1, 0.0, 1.0, 1, 0.004, (30, 26)),
        ("WhiteSkull", [("nd:0,0", dict(sat=0.6, val=1.1))],
         None, -1, 0.0, 1.0, 1, 0.002, (30, 26)),
        ("WhiteTuft", ["rdx:84", "rdx:95", "rdx:85", "rdx:93", "dt@529,438,575,474"], DRY, -1, 0.3, 0.45, 10, 0.12, (26, 22)),
        ("WhiteDryGrass", ["bc:9,2", "dt@485,461,506,476", "dt@502,437,521,452",
                           ("nd:7,0", dict(hue=-0.03, sat=0.8, val=1.05))], DRY, -1, 0.45, 0.55, 10, 0.12, (24, 24)),
        ("WhiteSucculent", [("zb:5,0", dict(val=1.6, sat=0.9)), "dt@344,450,367,466", "ob:1,0", "nd:7,6"], None, -1, 0.55, 0.65, 10, 0.12, (24, 26)),
        ("WhiteCactus", ["nd:0,6", "cy:139", "nd:1,6", "nd:2,6", "nd:3,6", "ob:2,0", "nd:4,6", "nd:5,6", "nd:6,6"], None, -1, 0.65, 0.7, 5, 0.12, (24, 28)),
        ("WhiteBarrel", ["zb:8,1", "zb:9,2", "zb:7,3", "dt@398,500,421,524", "dt@390,533,430,568", "wc:1,10"],
         None, -1, 0.7, 0.75, 5, 0.12, (22, 24)),
        ("WhitePampas", ["rdx:120", "nd:6,0"], None, -1, 0.75, 0.82, 10, 0.2, (28, 30)),
        ("WhiteTumbleweed", [("nd:3,0", None), ("nd:4,0", None)], DRY, -1, 0.82, 0.9, 10, 0.2, (26, 26)),
        ("WhiteSpire", [("nd:5,0", dict(sat=0.9, val=1.2)), "nd:0,7", "dt@529,52,576,96"], SAND, -1, 0.9, 1.0, 10, 0.2, (26, 30)),
        ("WhiteFlower", ["cy:99", "cy:98", "cy:42", "rdx:87", "ob:2,5", "ob:3,5", "dt@444,449,465,468", "fo:4,7"],
         None, -1, 0.0, 0.2, 10, 0.2, (20, 26)),
        ("WhitePebble", ["rdx:31", "wc:3,4", "wc:4,4", "wc:5,8", "wc:6,8"], None, -1, 0.2, 0.25, 5, 0.1, (28, 18)),
        ("WhiteDune", ["wc:2,9", "wc:3,9", "wc:3,10", "ob:2,1", "zb@5,740,28,763", "zb@100,739,125,764"],
         None, -1, 0.25, 0.3, 5, 0.1, (30, 22)),
    ],
    "blue": [
        ("BlueCoral", ["cy:185~", "cy:187", "cy:177", "zb:4,2", "zb:4,3"], None, -1, 0.0, 1.0, 1, 0.05, (24, 28), {"onStructures": ["water"]}),
        ("BlueSeaweed", ["cy:62", "cy:63", "cy:64"], dict(hue=0.12, sat=0.9, val=0.9), -1, 0.0, 1.0, 1, 0.04, (22, 30), {"onStructures": ["water"]}),
        ("BlueLotus", ["nd:0,3", "nd:1,3"], None, -1, 0.0, 1.0, 1, 0.03, (26, 24), {"onStructures": ["water"]}),
        ("BlueGrass", ["nd:2,3", "nd:3,3", "zb@419,768,446,792", "zb@426,800,446,827"],
         dict(hue=0.08, sat=0.8, val=1.0), -1, 0.3, 0.45, 10, 0.1, (26, 22)),
        ("BlueDriftwood", [("nd:4,3", dict(sat=0.3, val=0.85)), "nd:6,3", ("nd:5,3", dict(sat=0.3, val=0.85))], BLEACH, -1, 0.45, 0.55, 10, 0.1, (30, 22)),
        ("BlueTidePool", ["ob:3,1", "wc:5,4", "wc:5,5", "wc:5,6", "wc:5,7"], None, -1, 0.55, 0.65, 10, 0.1, (28, 20)),
        ("BlueFlower", ["cy:43~", "cy:49", "cy:68", "cy:112~", "rdx:89", "rdx:90", "bc:11,1", "bc:11,5"],
         None, -1, 0.0, 0.25, 10, 0.25, (24, 28)),
        ("BlueRock", ["nd:1,7", "nd:2,7", ("nd:3,7", None)], BLUEGREY, -1, 0.25, 0.28, 5, 0.1, (28, 28)),
        ("BlueCrystal", ["nd:6,7", "nd:7,7", "nd:0,8", "wc:14,0"], None, -1, 0.28, 0.3, 5, 0.1, (26, 28)),
        ("BlueShell", ["ob:0,1#0", "ob:0,1#1", "ob:1,1#0", "ob:1,1#1", "ob:0,4", "ob:1,4", "zb:0,0", "zb:2,0",
                       "zb:3,0", "zb:4,0", "zb:0,1", "zb:1,1", "zb:2,1", "zb:4,1", "zb:0,2",
                       "zb:1,2", "zb:3,2#0", "zb:0,3", "zb:3,3"], None, -1, 0.65, 0.7, 5, 0.1, (18, 14)),
        ("BlueStarfish", ["ob:0,2", "ob:1,2", "zb:1,0", "zb:2,2", "zb:5,2", "zb:1,3", "zb:2,3", "ob:0,3", "ob:1,3"],
         None, -1, 0.7, 0.75, 5, 0.1, (20, 18)),
        ("BlueMushroom", ["cy:143~", "cy:144~", "cy:159"], None, -1, 0.75, 0.85, 5, 0.15, (18, 26)),
        ("BluePalm", ["nd:4,7", "nd:7,3", "nd:5,7"], None, -1, 0.85, 1.0, 5, 0.15, (28, 30)),
    ],
    "black": [
        ("BlackLily", [("nd:0,2", SWAMP), ("nd:1,2", PURPLE)], None, -1, 0.0, 1.0, 1, 0.1, (28, 22), {"onStructures": ["water"]}),
        ("BlackBones", ["rdx:157", "rdx:158", "ob:4,5"], None, -1, 0.0, 1.0, 1, 0.004, (28, 24)),
        ("BlackSkeleton", ["wc:3,8", "wc:4,8", "ob:4,6", "nd:0,18", "nd:7,8"], DUSK, -1, 0.0, 1.0, 1, 0.002, (30, 26)),
        ("BlackFern", ["rdx:131", "rdx:132", "rdx:136", "rdx:138", "rdx:139"], None, -1, 0.3, 0.42, 10, 0.12, (26, 24)),
        ("BlackMoss", ["bc:3,0", "bc:5,0", "bc:11,0", "fo:9,2"], PURPLE, -1, 0.42, 0.5, 10, 0.12, (30, 24)),
        ("BlackThorn", ["nd:6,2", "nd:7,2", "bc:13,0", "bc:12,11"], PURPLE, -1, 0.5, 0.6, 10, 0.12, (26, 28)),
        ("BlackShroom", ["cy:140", "cy:141", "cy:148", "cy:181", "cy:178", "rdx:134"], None, -1, 0.6, 0.68, 5, 0.12, (26, 28)),
        ("BlackGrave", ["nd:1,8", "nd:2,8", ("nd:3,8", None), "nd:4,8"], GRAVE, -1, 0.68, 0.75, 5, 0.12, (22, 28)),
        ("BlackReed", ["cy:32", "cy:38"], None, -1, 0.75, 0.85, 10, 0.25, (24, 30)),
        ("BlackStalagmite", [("nd:2,2", None), ("nd:3,2", VIOLET)], DUSK, -1, 0.85, 1.0, 10, 0.25, (22, 30)),
        ("BlackBranch", ["nd:4,2", "nd:5,2"], None, -1, 0.2, 0.25, 5, 0.1, (30, 20)),
        ("BlackTar", [("nd:0,9", dict(toward=(20, 10, 25), amount=0.85)), "ob:4,12", "nd:1,9", "ob:5,13"], None, -1, 0.25, 0.3, 5, 0.1, (28, 26)),
        ("BlackRafflesia", ["rdx:104", "rdx:111", "cy:147", "bc:14,11"], None, -1, 0.0, 0.12, 5, 0.08, (28, 26)),
        ("BlackCrystal", ["nd:5,8", "nd:6,8"], None, -1, 0.12, 0.2, 5, 0.08, (24, 30)),
    ],
    "red": [
        ("RedBones", ["rdx:153", "rdx:160", "rdx:161", "zb@128,452,191,512"], None, -1, 0.0, 1.0, 1, 0.002, (30, 26)),
        ("RedSkull", ["zb@72,388,151,435", "zb@65,452,125,507", "ob:1,10,2,11"], None, -1, 0.0, 1.0, 1, 0.001, (30, 26)),
        ("RedPot", ["ob:3,10", "ob:3,11", "ob:4,11", "nd:2,9", "nd:3,9"], None, -1, 0.0, 1.0, 1, 0.001, (22, 26)),
        ("RedStump", ["nd:4,9", "nd:5,9", ("nd:5,1", dict(sat=0.35, val=0.36))], CHARRED, -1, 0.0, 1.0, 1, 0.005, (24, 30)),
        ("RedMagma", ["nd:6,9", "nd:3,1", ("nd:4,1", dict(toward=(255, 120, 0), amount=0.95))], None, -1, 0.0, 1.0, 1, 0.001, (28, 28)),
        ("RedFern", ["rdx:83", "rdx:91", "rdx:92", "rdx:82", "dt:8,9", ("nd:7,1", dict(sat=0.8, val=0.8))], None, -1, 0.3, 0.4, 10, 0.1, (28, 24)),
        ("RedTumbleweed", ["zb:6,0", "dt@388,446,423,474", "nd:7,9", "nd:0,10"], EMBER, -1, 0.4, 0.5, 10, 0.1, (26, 26)),
        ("RedCactus", ["nd:1,10", "nd:2,10", "nd:3,10", "wc:0,9", "wc:1,9", "nd:4,10", "zb:8,3"], None, -1, 0.5, 0.6, 10, 0.1, (24, 28)),
        ("RedGravel", ["nd:5,10", ("nd:6,10", dict(toward=(140, 65, 35), amount=0.55)), "rdx:27",
                       "wc@735,10,768,40"], REDDISH, -1, 0.6, 0.65, 5, 0.12, (28, 22)),
        ("RedDune", ["wc:4,9", "wc:5,9", "wc:5,10", "zb@197,740,220,763", "zb@292,739,317,764"], None, -1, 0.65, 0.7, 5, 0.12, (30, 22)),
        ("RedRock", [("nd:7,10", dict(toward=(140, 65, 35), amount=0.55)), "nd:0,11", "nd:1,11", "nd:2,11",
                     "nd:3,11"], RUST, -1, 0.7, 0.75, 5, 0.12, (28, 26)),
        ("RedFlower", ["cy:46", "cy:50", "cy:116", "cy:132", "cy:125", "rdx:88", "wc:7,4"], None, -1, 0.75, 0.85, 10, 0.2, (22, 28)),
        ("RedSpire", ["nd:4,11", ("nd:5,11", dict(hue=-0.05, sat=1.2, val=0.8)), "nd:6,11", ("nd:7,11", None),
                      "nd:0,12", ("nd:1,12", None)], RUST, -1, 0.85, 1.0, 10, 0.2, (22, 30)),
        ("RedLeaves", ["rdx:71", "rdx:49", ("nd:6,1", dict(hue=-0.05, sat=1.3, val=1.0))], None, -1, 0.2, 0.25, 5, 0.1, (26, 22)),
        ("RedCrack", [("nd:0,1", None), ("nd:1,1", dict(sat=0.2, val=0.3)), "ob:6,11#0", ("nd:2,1", None)],
         dict(toward=(60, 20, 10), amount=0.6), -1, 0.25, 0.3, 5, 0.1, (28, 24)),
    ],
    "green": [
        ("GreenLily", ["rdx:126", "rdx:127", "rdx:128", "rdx:118"], None, -1, 0.0, 1.0, 1, 0.07, (28, 22), {"onStructures": ["water"]}),
        ("GreenLotus", ["fo:13,0", "fo:14,0", "fo:12,2", "fo:8,1", "fo:10,4", "fo:11,2", "nd:1,18"], None, -1, 0.0, 1.0, 1, 0.03,
         (26, 26), {"onStructures": ["water"]}),
        ("GreenStump", ["cy:188", "cy:189", "nd:2,12", "rdx:113", "rdx:125~", "nd:5,4", "bc:15,2#0", "nd:3,12"], None, -1, 0.0, 1.0, 1, 0.006, (26, 26)),
        ("GreenLog", ["rdx:119", "nd:0,4", "cy:191", "fo:0,12,1,13", "fo:2,12,3,13",
                      "bc:13,11"], None, -1, 0.0, 1.0, 1, 0.005, (30, 22)),
        ("GreenTracks", ["fo:4,12#0", "fo:4,12#1", "fo:5,12#0", "fo:5,12#1", "fo:4,13#0", "fo:5,13#0"], None, -1, 0.0, 1.0, 1, 0.002, (14, 14)),
        ("GreenFairyRing", ["cy:6", "cy:10", "cy:12", "cy:4"], None, -1, 0.55, 0.58, 5, 0.1, (30, 22)),
        ("GreenGrass", ["rdx:40", "rdx:41", "rdx:42", "rdx:43", "rdx:44", "rdx:55", "rdx:57", "rdx:58", "rdx:61",
                        "rdx:62", "rdx:65", "rdx:66", "rdx:67", "rdx:68", "nd:7,4",
                        "fo:3,6", "bc:9,0", "bc:10,0"], None, -1, 0.3, 0.42, 10, 0.1, (26, 22)),
        ("GreenClover", ["fo:4,6", "fo:5,6", "fo:6,6", "fo:11,7", "fo:9,7", "bc:12,0", "bc:10,2", "bc:9,15", "bc:10,15"],
         None, -1, 0.42, 0.48, 10, 0.1, (26, 22)),
        ("GreenBerry", ["fo:4,9", "bc:15,6", "fo:5,9", "bc:14,3"], None, -1, 0.48, 0.55, 10, 0.1, (26, 26)),
        ("GreenStone", ["cy:84", "nd:3,4", "cy:104", "nd:4,4", "fo:8,2"], None, -1, 0.58, 0.61, 5, 0.1, (20, 16)),
        ("GreenBoulder", ["nd:4,12", "nd:5,12", "nd:6,12", "nd:7,12", "nd:0,13", "nd:1,13", "nd:2,13", "nd:3,13"],
         None, -1, 0.61, 0.64, 5, 0.1, (28, 28)),
        ("GreenFern", ["cy:33", "cy:34~", "cy:36~", "cy:31", "nd:4,13", "fo:7,6", "fo:8,7", "bc:8,0", "ob:2,3,3,4"], None, -1, 0.64, 0.68, 10, 0.15, (24, 28)),
        ("GreenBush", ["fo:0,6", "fo:1,6", "fo:2,6", "fo:0,7", "fo:1,7", "fo:2,7", "bc:1,0", "bc:8,3", "bc:12,1",
                       "nd:2,14", "fo:0,8,1,9"],
         None, -1, 0.68, 0.72, 10, 0.15, (28, 28)),
        ("GreenMushroom", ["cy:21", "cy:23", "cy:27", "cy:13", "rdx:135", "fo:6,9", "fo:7,9", "bc:15,10", "bc:14,10", "fo:0,13"],
         None, -1, 0.72, 0.85, 5, 0.15, (20, 22)),
        ("GreenSapling", ["nd:5,13", "nd:6,13", "nd:1,4", "nd:7,13", "nd:2,4", "nd:0,14", "nd:1,14"], None, -1, 0.85, 1.0, 5, 0.15, (24, 30)),
        ("GreenFlower", ["cy:79", "cy:80", "cy:81", "cy:82", "cy:90", "cy:92", "rdx:76", "rdx:77", "rdx:78",
                         "rdx:79", "fo:3,7", "fo:5,7", "fo:0,9", "bc:9,1", "bc:10,1",
                         "nd:6,4", "bc:8,14", "bc:9,14", "fo:9,8"],
         None, -1, 0.0, 0.25, 10, 0.3, (24, 24)),
        ("GreenMoss", ["rdx:100", "rdx:101", "bc:2,0", "bc:4,0", "bc:14,2"], None, -1, 0.25, 0.3, 5, 0.1, (28, 24)),
    ],
    "colorless": [
        ("WasteBones", ["rdx:151", "rdx:149", "rdx:150", "rdx:159", "rdx:162", "zb@172,385,219,416", "zb@198,451,222,511",
                        "zb@227,451,250,511"], GREY, -1, 0.0, 1.0, 1, 0.002, (28, 24)),
        ("WasteSkull", ["nd:0,5", "nd:7,15", "nd:1,5"], GREY, -1, 0.0, 1.0, 1, 0.001, (30, 26)),
        ("WasteGrave", [("nd:2,5", dict(sat=0.1, val=1.0)), ("nd:3,5", dict(sat=0.1, val=0.4))], ASH, -1, 0.0, 1.0, 1, 0.001, (22, 28)),
        ("WastePot", ["nd:4,5", ("nd:3,14", dict(sat=0.12, val=0.45))], ASH, -1, 0.0, 1.0, 1, 0.001, (22, 26)),
        ("WasteStone", ["rdx:12", "rdx:15", "wc:3,5", "wc:3,7"], GREY, -1, 0.3, 0.45, 5, 0.05, (20, 16)),
        ("WasteRubble", [("nd:4,14", dict(sat=0.1, val=0.35)), "bc:11,15", "bc:12,15",
                         ("nd:5,14", dict(sat=0.1, val=1.0)), "ob:4,2",
                         ("nd:6,14", dict(sat=0.1, val=1.0))], GREY, -1, 0.45, 0.6, 5, 0.05, (28, 22)),
        ("WasteBush", [("nd:7,14", dict(sat=0.12, val=0.45)), ("nd:5,5", dict(sat=0.1, val=0.36)), ("nd:6,5", ASH)], GREY, -1, 0.6, 0.68, 5, 0.08, (26, 28)),
        ("WasteSpire", [("nd:0,15", dict(sat=0.12, val=0.45)), ("nd:1,15", dict(sat=0.12, val=0.45)),
                        ("nd:2,15", dict(sat=0.12, val=0.45)),
                        ("nd:3,15", dict(sat=0.1, val=1.3))], ASH, -1, 0.68, 0.75, 5, 0.08, (22, 30)),
        ("WasteRoots", ["cy:192", "cy:193", "rdx:156"], ASH, -1, 0.75, 0.85, 10, 0.12, (24, 28)),
        ("WasteDeadTree", [("nd:4,15", dict(sat=0.12, val=0.45)), ("nd:5,15", dict(sat=0.1, val=0.3)),
                           ("nd:7,5", dict(sat=0.1, val=0.58)),
                           ("nd:6,15", dict(sat=0.12, val=0.45))], ASH, -1, 0.85, 1.0, 10, 0.12, (26, 30)),
        ("WasteBranch", ["cy:167", "cy:182", "cy:183", "cy:184", "zb@359,258,440,343"], ASH, -1, 0.0, 0.2, 10, 0.08, (30, 20)),
        ("WasteCrack", ["ob:6,3#0", "ob:6,4", "ob:6,5", "ob:6,6", "ob:6,7", "ob:6,8", "ob:6,9", "ob:6,10", "ob:6,11#1", "ob:5,11",
                        "ob:1,12"], dict(val=0.9), -1, 0.2, 0.3, 10, 0.08, (28, 24)),
    ],
    "player": [
        ("PlayerLily", ["rdx:126", "rdx:127", "rdx:128", "rdx:118"], None, -1, 0.0, 1.0, 1, 0.07, (28, 22), {"onStructures": ["hole"]}),
        ("PlayerLotus", ["fo:13,0", "fo:14,0", "fo:12,2", "fo:8,1", "fo:10,4"], None, -1, 0.0, 1.0, 1, 0.03, (26, 24), {"onStructures": ["hole"]}),
        ("PlayerStump", ["cy:188", "nd:2,17", "rdx:125~", "bc:15,2#0"], None, -1, 0.0, 1.0, 1, 0.005, (26, 26)),
        ("PlayerSign", ["nd:3,17", "nd:4,17"], None, -1, 0.0, 1.0, 1, 0.001, (24, 26)),
        ("PlayerTracks", ["fo:4,12#0", "fo:4,12#1", "fo:5,12#0", "fo:5,12#1", "fo:4,13#0", "fo:5,13#0"], None, -1, 0.0, 1.0, 1, 0.002, (14, 14)),
        ("PlayerFairyRing", ["cy:6", "cy:11", "cy:12"], None, -1, 0.55, 0.58, 5, 0.1, (30, 22)),
        ("PlayerGrass", ["rdx:40", "rdx:41", "rdx:42", "rdx:43", "rdx:44", "rdx:55", "rdx:57", "rdx:58", "rdx:61", "rdx:62", "rdx:65",
                         "rdx:66", "rdx:67", "rdx:68", "rdx:73"], dict(hue=-0.02, sat=1.05, val=1.08), -1, 0.3, 0.42, 10, 0.1, (26, 22)),
        ("PlayerClover", ["fo:4,6", "fo:5,6", "fo:6,6", "fo:11,7", "bc:12,0", "bc:10,2", "bc:15,4#0"], None, -1, 0.42, 0.48, 10, 0.1, (26, 22)),
        ("PlayerMeadow", ["fo:2,8,3,9", "bc:14,12", "nd:2,18", "nd:3,18"], None, -1, 0.48, 0.55, 10, 0.1, (30, 26)),
        ("PlayerStone", ["cy:84", "cy:103", "cy:105"], None, -1, 0.58, 0.61, 5, 0.1, (20, 16)),
        ("PlayerRock", ["nd:5,16", "nd:6,16", "nd:7,16", "nd:0,17", "nd:1,17"], None, -1, 0.61, 0.64, 5, 0.1, (26, 24)),
        ("PlayerBush", ["nd:0,16", "nd:1,16", "nd:2,16", "nd:3,16"], None, -1, 0.64, 0.68, 5, 0.15, (28, 28)),
        ("PlayerFlowerBush", ["fo:0,7", "fo:1,7", "fo:2,7", "nd:5,17", "bc:8,3", "bc:11,2", "bc:12,1", "bc:12,5",
                              "nd:6,17"],
         None, -1, 0.68, 0.72, 5, 0.15, (28, 28)),
        ("PlayerFern", ["cy:33", "cy:34~", "nd:4,16", "fo:7,6"], None, -1, 0.72, 0.82, 10, 0.15, (24, 28)),
        ("PlayerBerry", ["fo:4,9", "bc:15,6", "fo:4,8,5,8"], None, -1, 0.82, 0.9, 10, 0.15, (26, 26)),
        ("PlayerTulip", ["fo:4,7", "fo:9,8", "fo:3,7", "fo:5,7"], None, -1, 0.9, 1.0, 10, 0.15, (22, 26)),
        ("PlayerFlower", ["rdx:76", "rdx:77", "rdx:78", "rdx:79", "rdx:88", "rdx:87", "bc:9,1", "bc:10,1", "bc:9,5", "bc:10,5",
                          "bc:7,14", "bc:8,14"], None, -1, 0.0, 0.25, 10, 0.3, (26, 24)),
        ("PlayerGravel", ["rdx:12", "rdx:15", "fo:8,2"], None, -1, 0.25, 0.28, 5, 0.1, (28, 20)),
        ("PlayerMushroom", ["fo:6,9", "fo:7,9", "bc:14,10", "bc:15,10"], None, -1, 0.28, 0.3, 5, 0.1, (20, 22)),
    ],
}

# Round 305: the ocean (world/biomes/base.json, a plane copy of common's with spriteNames added). Whirlpools on its
# open water only, about 20 on the whole map (~200k eligible tiles - 0.0003 gave 63). The picture is built by whirlpool.py.
OCEAN_DOODADS = [("Whirlpool", 0.0001)]
