"""Round 316 roster: 43 new enemies from the user's RPG Maker MV/MZ art folder, plus 4 re-skins.

ROSTER rows keep round 179's shape - (slug, name, rank, colors, theme, extra quest tags) - with slug = the file stem
(the enemy's name in snake_case), so the atlas, deck and enemies.json paths all share it.
Ranks: A = Apprentice (Common), D = Adept (Uncommon), M = Master (Rare), X = Archmage (Mythic).
Colors: WUBRG letters, "C" = colorless. Theme keys: deckgen316.THEMES (round 179's plus the new ones).
"Flying" in the extra tags sets enemies.json "flying": true (import179's rule).

ART says how each atlas is cut from its sheet (build_atlases.py):
    sheet     path under ART_ROOT
    pick      character block 1-8 (v1-v4 top row left->right, v5-v8 bottom row) or, for a front-only sheet, row 1-4
    kind      "walk"  = 4-direction walker: Idle<Dir> the neutral frame, Walk<Dir> 0,1,2,1
              "fly"   = 4-direction flier: Idle<Dir> AND Walk<Dir> 0,1,2,1 (wings never stop)
              "front" = front-only sheet (rows are color variants): un-suffixed Idle and Walk 0,1,2,1
    avatar    "top"    = top-centre square of the Down (front) neutral frame - the face of a walker / front view
              "head"   = the square at the HEAD end of the Right neutral frame (round 273's rule for wide creatures:
                         the wyverns, and the dragonets and the bear, whose front views put a tail curl or a hump
                         where the face should be)
              "bottom" = bottom-centre square of the front frame (the behemoth's face is under its mountain)
    shadow    True: the sheet draws its ground shadow in OPAQUE gray (127,127,127) - keyed to translucent black
Every pick was checked by eye against a labeled grid of its sheet (qa/src_*.png): the colors match the names."""

ART_ROOT = r"C:\Users\User\Desktop\New Enemy Art"
DR = "dragons and wyverns/"
MY = "Mythological animals/"
AN = "animals/"

ROSTER = [
    # ---------------------------------------------------------------- whelps (small_flyingbabydragon.png)
    ("tidewing_whelp", "Tidewing Whelp", "A", "U", "drake", ["Small", "Flying"]),
    ("duskwing_whelp", "Duskwing Whelp", "A", "B", "whelp", ["Small", "Flying"]),
    ("cinderwing_whelp", "Cinderwing Whelp", "A", "R", "dragon", ["Small", "Flying", "Fire"]),
    ("leafwing_whelp", "Leafwing Whelp", "A", "G", "whelp", ["Small", "Flying"]),
    ("pixiewing_whelp", "Pixiewing Whelp", "A", "UG", "faerie_dragon", ["Small", "Flying"]),
    ("sunwing_whelp", "Sunwing Whelp", "A", "W", "faerie_dragon", ["Small", "Flying"]),
    # ---------------------------------------------------------------- dragonets (young_dragonwalk.png)
    ("dawnscale_dragonet", "Dawnscale Dragonet", "D", "W", "dragon", ["Small"]),
    ("rimescale_dragonet", "Rimescale Dragonet", "D", "U", "dragon", ["Small", "Snow"]),
    ("gravescale_dragonet", "Gravescale Dragonet", "D", "B", "dragon", ["Small"]),
    ("emberscale_dragonet", "Emberscale Dragonet", "D", "R", "dragon", ["Small", "Fire"]),
    ("fernscale_dragonet", "Fernscale Dragonet", "D", "G", "dragon", ["Small"]),
    # ---------------------------------------------------------------- wyverns
    ("thornback_wyvern", "Thornback Wyvern", "M", "RG", "dragon", ["Wyvern", "Flying"]),
    ("gloamwing_wyvern", "Gloamwing Wyvern", "M", "UB", "drake", ["Wyvern", "Flying"]),
    # ---------------------------------------------------------------- behemoths ($behemoth.png) - speed 0, they never move
    ("dunebacked_behemoth", "Dunebacked Behemoth", "X", "W", "behemoth", ["Huge"]),
    ("ashpeak_behemoth", "Ashpeak Behemoth", "X", "BR", "behemoth", ["Huge", "Fire"]),
    ("grovebacked_behemoth", "Grovebacked Behemoth", "X", "G", "behemoth", ["Huge", "Nature"]),
    ("glacierbacked_behemoth", "Glacierbacked Behemoth", "X", "U", "behemoth", ["Huge", "Snow"]),
    # ---------------------------------------------------------------- werewolves (wolfbeast.png)
    ("cinderpelt_werewolf", "Cinderpelt Werewolf", "D", "R", "werewolf", ["Werewolf"]),
    ("thicketmaw_werewolf", "Thicketmaw Werewolf", "D", "G", "werewolf", ["Werewolf"]),
    ("moonless_ravager", "Moonless Ravager", "M", "RG", "werewolf", ["Werewolf", "Leader"]),
    ("moonsilver_werewolf", "Moonsilver Werewolf", "D", "W", "werewolf_white", ["Werewolf"]),
    # ---------------------------------------------------------------- kirin (Kirin.png)
    ("dawnmane_kirin", "Dawnmane Kirin", "D", "W", "kirin", ["Kirin"]),
    ("cloudmane_kirin", "Cloudmane Kirin", "D", "U", "kirin", ["Kirin"]),
    ("flamemane_kirin", "Flamemane Kirin", "D", "R", "kirin", ["Kirin", "Fire"]),
    ("mossantler_kirin", "Mossantler Kirin", "D", "G", "kirin", ["Kirin", "Nature"]),
    # ---------------------------------------------------------------- bats (Bats_recolor.png)
    ("bloodfang_bat", "Bloodfang Bat", "A", "B", "bat", ["Small", "Flying"]),
    ("pyrefang_bat", "Pyrefang Bat", "A", "R", "bat_fire", ["Small", "Flying", "Fire"]),
    ("frostfang_bat", "Frostfang Bat", "A", "U", "bat_frost", ["Small", "Flying", "Snow"]),
    # ---------------------------------------------------------------- stone golems (golems.png)
    ("marblehewn_golem", "Marblehewn Golem", "M", "W", "construct", ["Golem", "Stone", "Large"]),
    ("rimeglass_golem", "Rimeglass Golem", "M", "U", "construct", ["Golem", "Snow", "Large"]),
    ("obsidian_warden", "Obsidian Warden", "M", "B", "construct", ["Golem", "Stone", "Large"]),
    ("kilnfired_golem", "Kilnfired Golem", "M", "R", "construct", ["Golem", "Fire", "Large"]),
    ("jadestone_golem", "Jadestone Golem", "M", "G", "construct", ["Golem", "Stone", "Large"]),
    # ---------------------------------------------------------------- winged-eye horrors (Ariman.png)
    ("dreadgaze_horror", "Dreadgaze Horror", "D", "B", "horror", ["Eye", "Flying"]),
    ("veilgaze_horror", "Veilgaze Horror", "D", "UB", "horror", ["Eye", "Flying"]),
    ("bloodgaze_horror", "Bloodgaze Horror", "D", "BR", "horror", ["Eye", "Flying"]),
    # ---------------------------------------------------------------- reapers ($BigDeaths_recolors.png)
    ("soulreaver_specter", "Soulreaver Specter", "M", "UB", "wraith", ["Specter", "Flying"]),
    ("harvester_of_last_breaths", "Harvester of Last Breaths", "X", "B", "wraith", ["Specter", "Flying"]),
    # ---------------------------------------------------------------- zombie beasts (zombieanimals.png)
    ("rotfang_hound", "Rotfang Hound", "A", "B", "zombie", ["Animal"]),
    ("gravemoss_bear", "Gravemoss Bear", "D", "BG", "zombie", ["Animal"]),
    ("carrion_steed", "Carrion Steed", "D", "B", "zombie", ["Animal"]),
    # ---------------------------------------------------------------- automatons (golems2.png) - colorless
    ("bronzecrest_automaton", "Bronzecrest Automaton", "D", "C", "construct", ["Robot"]),
    ("ironcrest_sentinel", "Ironcrest Sentinel", "M", "C", "construct", ["Robot", "Guard"]),
]

WHELP = DR + "small_flyingbabydragon.png"
DRAGONET = DR + "young_dragonwalk.png"
WOLF = MY + "wolfbeast.png"
KIRIN = MY + "Kirin.png"
BAT = AN + "Bats_recolor.png"
GOLEM = MY + "golems.png"
EYE = MY + "Ariman.png"
REAPER = MY + "$BigDeaths_recolors.png"
ZOMBIE = MY + "zombieanimals.png"
AUTOMATON = MY + "golems2.png"
BEHEMOTH = MY + "$behemoth.png"
COILED = DR + "!$large_asiandragon.png"

# stem -> (sheet, pick, kind, avatar, shadow)
ART = {
    "tidewing_whelp": (WHELP, 5, "fly", "top", False),
    "duskwing_whelp": (WHELP, 7, "fly", "top", False),
    "cinderwing_whelp": (WHELP, 8, "fly", "top", False),
    "leafwing_whelp": (WHELP, 6, "fly", "top", False),
    "pixiewing_whelp": (WHELP, 1, "fly", "top", False),
    "sunwing_whelp": (WHELP, 2, "fly", "top", False),
    "dawnscale_dragonet": (DRAGONET, 6, "walk", "head", False),
    "rimescale_dragonet": (DRAGONET, 4, "walk", "head", False),
    "gravescale_dragonet": (DRAGONET, 2, "walk", "head", False),
    "emberscale_dragonet": (DRAGONET, 8, "walk", "head", False),
    "fernscale_dragonet": (DRAGONET, 1, "walk", "head", False),
    "thornback_wyvern": (DR + "!$greenwyvern.png", 1, "fly", "head", False),
    "gloamwing_wyvern": (DR + "!$purplewyvern.png", 1, "fly", "head", False),
    "dunebacked_behemoth": (BEHEMOTH, 1, "front", "bottom", False),
    "ashpeak_behemoth": (BEHEMOTH, 2, "front", "bottom", False),
    "grovebacked_behemoth": (BEHEMOTH, 3, "front", "bottom", False),
    "glacierbacked_behemoth": (BEHEMOTH, 4, "front", "bottom", False),
    "cinderpelt_werewolf": (WOLF, 7, "walk", "top", False),
    "thicketmaw_werewolf": (WOLF, 3, "walk", "top", False),
    "moonless_ravager": (WOLF, 2, "walk", "top", False),
    "moonsilver_werewolf": (WOLF, 6, "walk", "top", False),
    "dawnmane_kirin": (KIRIN, 4, "walk", "top", False),
    "cloudmane_kirin": (KIRIN, 8, "walk", "top", False),
    "flamemane_kirin": (KIRIN, 6, "walk", "top", False),
    "mossantler_kirin": (KIRIN, 1, "walk", "top", False),
    "bloodfang_bat": (BAT, 3, "fly", "top", True),
    "pyrefang_bat": (BAT, 5, "fly", "top", True),
    "frostfang_bat": (BAT, 7, "fly", "top", True),
    "marblehewn_golem": (GOLEM, 3, "walk", "top", False),
    "rimeglass_golem": (GOLEM, 4, "walk", "top", False),
    "obsidian_warden": (GOLEM, 5, "walk", "top", False),
    "kilnfired_golem": (GOLEM, 8, "walk", "top", False),
    "jadestone_golem": (GOLEM, 7, "walk", "top", False),
    "dreadgaze_horror": (EYE, 3, "fly", "top", True),
    "veilgaze_horror": (EYE, 1, "fly", "top", True),
    "bloodgaze_horror": (EYE, 2, "fly", "top", True),
    "soulreaver_specter": (REAPER, 1, "front", "top", False),
    "harvester_of_last_breaths": (REAPER, 3, "front", "top", False),
    "rotfang_hound": (ZOMBIE, 1, "walk", "top", False),
    "gravemoss_bear": (ZOMBIE, 3, "walk", "head", False),
    "carrion_steed": (ZOMBIE, 4, "walk", "top", False),
    "bronzecrest_automaton": (AUTOMATON, 1, "walk", "top", False),
    "ironcrest_sentinel": (AUTOMATON, 3, "walk", "top", False),
}

# Re-skins: an existing enemies.json entry keeps its name, deck, stats and everything else - only "sprite" moves to a
# new atlas cut from the coiled eastern dragon sheet (front-only, it hovers on its coil: a "flying" look). Kokusho stays
# as it is (the sheet has no black dragon). (enemy name, new atlas stem, sheet, row)
RESKINS = [
    ("Jugan", "jugan", COILED, 1),     # green
    ("Yosei", "yosei", COILED, 2),     # white
    ("Ryusei", "ryusei", COILED, 3),   # gold
    ("Keiga", "keiga", COILED, 4),     # blue
]
for _name, _stem, _sheet, _row in RESKINS:
    ART[_stem] = (_sheet, _row, "front", "top", False)

# enemies that never move (EnemyData.speed 0): the land-backed behemoths rest like the mountains they carry
STATIONARY = {"Dunebacked Behemoth", "Ashpeak Behemoth", "Grovebacked Behemoth", "Glacierbacked Behemoth"}

assert len(ROSTER) == 43 and len({r[0] for r in ROSTER}) == 43 and all(r[0] in ART for r in ROSTER)
