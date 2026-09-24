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
DOODADS = {
    "white": [
        ("WhiteBones", ["rdx:151", "rdx:149", "rdx:159", "rdx:155", "rdx:162"], None, -1, 0.0, 1.0, 1, 0.006, (30, 26)),
        ("WhiteTuft", ["rdx:84", "rdx:94", "rdx:95", "rdx:85", "rdx:93"], DRY, -1, 0.3, 0.65, 10, 0.12, (26, 22)),
        ("WhiteCactus", ["cy:136", "cy:139", "cy:152", "cy:154", "rdx:72"], None, 0, 0.65, 0.75, 5, 0.12, (24, 28)),
        ("WhitePampas", ["rdx:120"], None, 0, 0.75, 1.0, 10, 0.2, (28, 30)),
        ("WhiteFlower", ["cy:99", "cy:98", "cy:42", "rdx:87"], None, -1, 0.0, 0.2, 10, 0.2, (20, 26)),
        ("WhitePebble", ["rdx:27", "rdx:31"], None, -1, 0.2, 0.3, 5, 0.1, (28, 18)),
    ],
    "blue": [
        ("BlueCoral", ["cy:185", "cy:187", "cy:177"], None, -1, 0.0, 1.0, 1, 0.07, (24, 28), {"onStructures": ["water"]}),
        ("BlueSeaweed", ["cy:62", "cy:63", "cy:64"], dict(hue=0.12, sat=0.9, val=0.9), -1, 0.0, 1.0, 1, 0.05, (22, 30), {"onStructures": ["water"]}),
        ("BlueGrass", ["rdx:41", "rdx:42", "rdx:43", "rdx:44", "rdx:57", "rdx:61"], dict(hue=0.08, sat=0.8, val=1.0), -1, 0.3, 0.65, 10, 0.1, (26, 22)),
        ("BlueFlower", ["cy:43", "cy:49", "cy:68", "cy:79", "cy:112", "rdx:89", "rdx:90"], None, -1, 0.0, 0.25, 10, 0.25, (24, 28)),
        ("BlueRock", ["rdx:23", "rdx:17"], BLUEGREY, 0, 0.25, 0.3, 5, 0.1, (28, 28)),
        ("BlueShell", ["cy:83", "cy:102", "cy:104"], dict(hue=0.05, sat=0.5, val=1.35), -1, 0.65, 0.75, 5, 0.1, (18, 14)),
        ("BlueMushroom", ["cy:143", "cy:144", "cy:159"], None, 0, 0.75, 1.0, 5, 0.15, (18, 26)),
    ],
    "black": [
        ("BlackLily", ["rdx:126", "rdx:127", "rdx:128", "rdx:118"], None, -1, 0.0, 1.0, 1, 0.1, (28, 22), {"onStructures": ["water"]}),
        ("BlackBones", ["rdx:151", "rdx:149", "rdx:157", "rdx:158"], None, -1, 0.0, 1.0, 1, 0.006, (28, 24)),
        ("BlackFern", ["rdx:131", "rdx:132", "rdx:136", "rdx:138", "rdx:139"], None, -1, 0.3, 0.6, 10, 0.12, (26, 24)),
        ("BlackShroom", ["cy:140", "cy:141", "cy:148", "cy:181", "cy:178", "rdx:134"], None, 0, 0.6, 0.75, 5, 0.12, (26, 28)),
        ("BlackReed", ["cy:32", "cy:38"], None, 0, 0.75, 1.0, 10, 0.25, (24, 30)),
        ("BlackBranch", ["cy:167", "cy:182", "cy:183", "cy:192"], None, -1, 0.2, 0.3, 5, 0.1, (30, 20)),
        ("BlackRafflesia", ["rdx:104", "rdx:111", "cy:147"], None, 0, 0.0, 0.2, 5, 0.08, (28, 26)),
    ],
    "red": [
        ("RedBones", ["rdx:153", "rdx:160", "rdx:161"], None, -1, 0.0, 1.0, 1, 0.004, (30, 26)),
        ("RedStump", ["cy:179", "cy:172"], CHARRED, 0, 0.0, 1.0, 1, 0.006, (24, 30)),
        ("RedFern", ["rdx:83", "rdx:91", "rdx:92", "rdx:82", "rdx:96"], None, -1, 0.3, 0.6, 10, 0.1, (28, 24)),
        ("RedGravel", ["rdx:24", "rdx:32", "rdx:27"], REDDISH, -1, 0.6, 0.75, 5, 0.12, (28, 22)),
        ("RedFlower", ["cy:46", "cy:50", "cy:116", "cy:132", "cy:125", "rdx:88"], None, -1, 0.75, 1.0, 10, 0.2, (22, 28)),
        ("RedLeaves", ["rdx:71", "rdx:49"], None, -1, 0.2, 0.3, 5, 0.1, (26, 22)),
    ],
    "green": [
        ("GreenLily", ["rdx:126", "rdx:127", "rdx:128", "rdx:118"], None, -1, 0.0, 1.0, 1, 0.1, (28, 22), {"onStructures": ["water"]}),
        ("GreenStump", ["cy:188", "cy:189", "cy:190", "rdx:113", "rdx:125"], None, 0, 0.0, 1.0, 1, 0.008, (26, 26)),
        ("GreenLog", ["rdx:119", "rdx:124", "cy:191"], None, -1, 0.0, 1.0, 1, 0.005, (30, 18)),
        ("GreenFairyRing", ["cy:6", "cy:10", "cy:12", "cy:4"], None, -1, 0.55, 0.58, 5, 0.1, (30, 22)),
        ("GreenGrass", ["rdx:40", "rdx:41", "rdx:42", "rdx:43", "rdx:44", "rdx:55", "rdx:57", "rdx:58", "rdx:61", "rdx:62", "rdx:65", "rdx:66", "rdx:67", "rdx:68", "rdx:73"], None, -1, 0.3, 0.55, 10, 0.1, (26, 22)),
        ("GreenStone", ["cy:84", "cy:103", "cy:104", "cy:105"], None, -1, 0.58, 0.64, 5, 0.1, (20, 16)),
        ("GreenFern", ["cy:33", "cy:34", "cy:36", "cy:31", "rdx:102"], None, -1, 0.64, 0.72, 10, 0.15, (24, 28)),
        ("GreenMushroom", ["cy:21", "cy:23", "cy:27", "cy:13", "rdx:135"], None, 0, 0.72, 1.0, 5, 0.15, (20, 22)),
        ("GreenFlower", ["cy:79", "cy:80", "cy:81", "cy:82", "cy:90", "cy:92", "rdx:76", "rdx:77", "rdx:78", "rdx:79"], None, -1, 0.0, 0.25, 10, 0.3, (24, 24)),
        ("GreenMoss", ["rdx:100", "rdx:101"], None, -1, 0.25, 0.3, 5, 0.1, (28, 24)),
    ],
    "colorless": [
        ("WasteBones", ["rdx:151", "rdx:149", "rdx:150", "rdx:159", "rdx:162"], GREY, -1, 0.0, 1.0, 1, 0.005, (28, 24)),
        ("WasteStone", ["cy:83", "cy:84", "cy:102", "cy:103", "rdx:12", "rdx:15"], GREY, -1, 0.3, 0.6, 5, 0.05, (20, 16)),
        ("WasteBush", ["rdx:59", "rdx:70"], GREY, 0, 0.6, 0.75, 5, 0.08, (26, 28)),
        ("WasteRoots", ["cy:192", "cy:193", "rdx:156"], ASH, 0, 0.75, 1.0, 10, 0.12, (24, 28)),
        ("WasteBranch", ["cy:167", "cy:182", "cy:183", "cy:184"], ASH, -1, 0.0, 0.3, 10, 0.08, (30, 20)),
    ],
    "player": [
        ("PlayerLily", ["rdx:126", "rdx:127", "rdx:128", "rdx:118"], None, -1, 0.0, 1.0, 1, 0.1, (28, 22), {"onStructures": ["hole"]}),
        ("PlayerStump", ["cy:188", "cy:190", "rdx:125"], None, 0, 0.0, 1.0, 1, 0.008, (26, 26)),
        ("PlayerFairyRing", ["cy:6", "cy:11", "cy:12"], None, -1, 0.55, 0.58, 5, 0.1, (30, 22)),
        ("PlayerGrass", ["rdx:40", "rdx:41", "rdx:42", "rdx:43", "rdx:44", "rdx:55", "rdx:57", "rdx:58", "rdx:61", "rdx:62", "rdx:65", "rdx:66", "rdx:67", "rdx:68", "rdx:73"], dict(hue=-0.02, sat=1.05, val=1.08), -1, 0.3, 0.55, 10, 0.1, (26, 22)),
        ("PlayerStone", ["cy:84", "cy:103", "cy:105"], None, -1, 0.58, 0.64, 5, 0.1, (20, 16)),
        ("PlayerBush", ["rdx:99", "rdx:105", "rdx:106", "rdx:107"], None, 0, 0.64, 0.72, 5, 0.15, (28, 28)),
        ("PlayerFern", ["cy:33", "cy:34", "rdx:102"], None, -1, 0.72, 1.0, 10, 0.15, (24, 28)),
        ("PlayerFlower", ["rdx:76", "rdx:77", "rdx:78", "rdx:79", "rdx:88", "rdx:87"], None, -1, 0.0, 0.25, 10, 0.3, (26, 24)),
        ("PlayerGravel", ["rdx:12", "rdx:15"], None, -1, 0.25, 0.3, 5, 0.1, (28, 20)),
    ],
}

# Round 305: the ocean (world/biomes/base.json, a plane copy of common's with spriteNames added). Whirlpools on its
# open water only, about 20 on the whole map (~200k eligible tiles - 0.0003 gave 63). The picture is built by whirlpool.py.
OCEAN_DOODADS = [("Whirlpool", 0.0001)]
