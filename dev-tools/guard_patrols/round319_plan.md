# Round 316 plan: chest patrols and crowded enemies

Generated 2026-09-24 00:39:20 from `C:\TFR\repo\forge-gui\res\adventure\The Forsaken Realms`. Nothing here has been applied to the repo.

## The short version

* **The 296 is stale.** It is round 286's count. Rounds 286b and 287 then *added* 363 chest guards (238 + 125) plus 12 booster guards. Re-measured today with round 286's own audit (`booster_guards.audit(loot="treasure")`): **9 of 534 chests have no guard.** Of those: 1 by a patrol of an existing enemy; 5 already guarded (an enemy stands beside it or an authored route walks past it - the audit cannot see either); 2 in the map you accepted as-is (phyrexian_black1); 1 needing a move rather than a patrol (optional relocation).
* **Crowding is mostly the new guards.** 64 clusters (142 enemies) stand within 2 tiles of each other; most pairs involve a guard placed by rounds 279-287 next to an enemy that already stood by the loot. The plan resolves 30 by a patrol and 8 by a removal; 9 more are resolvable only by an optional relocation; 3 are hand-authored set-pieces left as designed; 14 stay as they are (reasons and trade-offs below).
* **Changes:** 33 patrols (66 new waypoint objects, 1 for chests, 32 to split clusters), 9 removals, in 39 maps. Optional, off by default: 12 relocations.

## Decisions

* **Cluster threshold: 2 tiles, center to center.** Covers the same tile, touching (1.0 orthogonal, 1.41 diagonal) and one empty tile between (2.0). Below it most pairs involve a tool-placed guard; above it the pairs are mostly hand-authored layouts (see the audit's distribution below). Two placements with disjoint `spawn.X` flags are one placement per difficulty and never count.
* **"A lot of enemies": 8+ fighters on Hard** (non-dialog, active): the median map with enemies has 6, the 75th percentile 9, and 122 of 371 maps reach 8+ - the busier third. Only there is anything removed, and only the weaker/duplicate member, and only if no booster or chest loses its guard and no booster changes guard (rounds 279/286 tuned those: Adept+, threatRange 40). Otherwise - or when no removal is safe - one member patrols.
* **Who may be removed.** A group mixing hand-authored and tool-placed enemies loses a tool-placed one (or an exact duplicate), never an original. A group that is ALL hand-authored (every member placed before any guard tool ran) is somebody's layout: with a crowned / own-reward / named / scripted member it is a set-piece and is left alone (the story temple's line of guardians on the carpet to Chandra); otherwise only an exact duplicate (same enemy) is ever removed, and two different creatures get a patrol at most.
* **Patrols** are back-and-forth routes of 2 new waypoint objects, written exactly as the maps already write them (`waypoint.tx` objects, a `waypoints` property of their ids). A split patrol walks 2.2+ tiles from its cluster and stays within 4 tiles of its post; a chest's guard keeps passing its chest. Every leg is checked against a replay of the game's own NavigationMap (the enemy pathfinds between waypoints) plus waypoint_routes_qa's standable test.
* **Never touched:** bosses, legends, story-tagged or spawnRate-0 placements, dialog NPCs, anything a map script deletes/activates, hidden ambushers, inactive set-pieces, and any enemy that already has a `waypoints` route. Booster guards cannot patrol (the runtime pins them) - only the optional relocation can move one.

### Nearest other enemy, standing fighters (cumulative, tiles)

| <=1 | <=1.5 | <=2 | <=2.5 | <=3 | <=4 | <=6 | all |
|---|---|---|---|---|---|---|---|
| 22 | 89 | 142 | 212 | 264 | 396 | 650 | 1275 |
## 1. The unguarded chests

| map | chest (tile) | why unguarded | outcome |
|---|---|---|---|
| cave/cave_cerodon.tmx | 55 (13,5) | no entry object - reachability unknowable, so the round-286 audit counts it unguarded | already guarded: Cerodon #61 stands 0.6 tiles away; the round-286 audit only says 'unguarded' because it cannot seed a flood fill from this map's entry |
| cave/cave_kavu.tmx | 64 (21,4) | no entry object - reachability unknowable, so the round-286 audit counts it unguarded | **patrol** - Kavu #61 (Uncommon, hand-authored) walks (17,1) <-> (21,3), from 4.6 tiles away |
| cave/cave_kavu.tmx | 55 (2,3) | no entry object - reachability unknowable, so the round-286 audit counts it unguarded | already guarded: Kavu #60 stands 1.3 tiles away; the round-286 audit only says 'unguarded' because it cannot seed a flood fill from this map's entry |
| cave/cave_spider.tmx | 48 (9,1) | no entry object - reachability unknowable, so the round-286 audit counts it unguarded | already patrolled: Cave Spider #50's authored route passes 1.0 tiles from it (round 286's count reads only where enemies stand, not where they walk); this map's entry is also one the audit cannot seed from |
| garruk/garruk.tmx | 48 (32,22) | enemies within 3 tiles are dialog NPCs or cannot be engaged | no enemy can patrol to it (Viper #99: no valid walk post <-> chest (no second open tile near its post to walk from); Fox #65: no valid walk post <-> chest (last leg {'straight': 8.62, 'nav': None, 'clear': False})); **optional relocation**: Viper #99 to tile (32,21) |
| hostiletown/zombietown.tmx | 64 (36,12) | no entry object - reachability unknowable, so the round-286 audit counts it unguarded | already patrolled: Demoncaller #72's authored route passes 1.1 tiles from it (round 286's count reads only where enemies stand, not where they walk); this map's entry is also one the audit cannot seed from |
| hostiletown/zombietown.tmx | 65 (30,12) | no entry object - reachability unknowable, so the round-286 audit counts it unguarded | already patrolled: Ghoul Gravecaller #54's authored route passes 0.7 tiles from it (round 286's count reads only where enemies stand, not where they walk); this map's entry is also one the audit cannot seed from |
| phyrexia/phyrexian_black1.tmx | 48 (27,8) | enemies within 3 tiles are dialog NPCs or cannot be engaged | left alone: the user accepted this map as designed (ACCEPTED_UNREACHABLE) |
| phyrexia/phyrexian_black1.tmx | 76 (27,7) | enemies within 3 tiles are dialog NPCs or cannot be engaged | left alone: the user accepted this map as designed (ACCEPTED_UNREACHABLE) |

A patroller keeps its home, so it is not the chest's *registered* guard (MapStage.assignLootGuards() matches on authored positions): the patrol is presence, not the chase-on-theft hook.

## 2. Clusters fixed by removing one enemy

| map | removed | stood beside | fighters on Hard |
|---|---|---|---|
| fort/fort_green_4_scarecrowfarm.tmx | Chicken #68 (Rare, hand-authored) at (14,18) | #65 | 16 -> 15 |
| grolnok/grolnok_f1.tmx | Wild Rat #218 (Common, placed by r287) at (19,54) | #214, #215 | 16 -> 15 |
| magetower/magetower_4_monastery.tmx | Monk #73 (Uncommon, hand-authored) at (15,6) | #72 | 11 -> 10 |
| main_story/temple_of_liliana/town.tmx | Wild Rat #297 (Common, placed by r287) at (28,0) | #218 | 39 -> 38 |
| monastery/unhallowed_abbey_1F.tmx | False Monk #69 (Uncommon, hand-authored) at (10,13) | #68 | 11 -> 9 |
| monastery/unhallowed_abbey_1F.tmx | False Monk #73 (Uncommon, hand-authored) at (15,11) | #72 | 11 -> 9 |
| tibalt/tibalt_f3.tmx | Skeleton #212 (Common, placed by r287) at (30,6) | #211 | 10 -> 9 |
| zedruu/zedruu.tmx | Camel #274 (Common, placed by r287) at (5,13) | #273 | 25 -> 24 |
| zedruu/zedruu_f4.tmx | Camel #226 (Common, hand-authored) at (33,4) | #162 | 16 -> 15 |

## 3. Patrols

Legs: walk from its post to the first waypoint, then the loop. `a/b` = straight / pathfinding tiles; `*` = the straight line is blocked and the game's pathfinding walks round it; `fly` = a flier, which goes straight (so its line had to be clear).

| map | enemy (post tile) | purpose | waypoints (tiles) | returns to post | legs |
|---|---|---|---|---|---|
| barbariancamp/barbariancamp_bandit.tmx | Bandit Scoundrel #63 (Uncommon, hand-authored) (24,8) | split cluster [53, 63] | (24,7) <-> (22,7) | yes | 1.0/1.0 2.0/2.0 2.0/2.0 |
| barbariancamp/barbariancamp_goblin_3.tmx | Skeleton #104 (Common, placed by r287) (16,10) | split cluster [55, 104] | (15,10) <-> (16,12) | yes | 1.0/1.35 2.24/2.24 2.24/2.45 |
| cave/cave_bluewiz.tmx | Apprentice Blue Wizard #83 (Common, placed by r286b) (26,11) | split cluster [82, 83] | (26,10) <-> (28,9) | yes | 1.0 fly 2.24 fly 2.24 fly |
| cave/cave_huge.tmx | Big Zombie #92 (Common, hand-authored) (36,187) | split cluster [91, 92, 93] | (34,187) <-> (33,185) | no - it patrols clear of the group | 2.0/2.28 2.24/2.27 2.24/2.24 |
| cave/cave_kavu.tmx | Kavu #61 (Uncommon, hand-authored) (17,2) | guard chest 64 | (17,1) <-> (21,3) | yes | 1.0/1.0* 4.56/4.81* 4.56/4.77* |
| cave/cave_multilevel/cave_16BR2U2.tmx | Devil #85 (Common, placed by r286b) (13,0) | split cluster [85, 86] | (13,0) <-> (11,1) | yes | 0.0/0.0 2.24/2.24* 2.24/2.24* |
| evilgrove/evilgrove_2_blackgolem.tmx | Black Golem #97 (Rare, placed by r286b) (26,9) | split cluster [64, 97] | (26,9) <-> (25,7) | yes | 0.0/0.0 2.24/2.24 2.24/2.24 |
| fort/Peaceful_Clearing.tmx | Grakk #64 (Uncommon, hand-authored) (13,6) | split cluster [56, 64] | (14,6) <-> (14,4) | yes | 1.0/1.0 2.0/2.0 2.0/2.0 |
| fort/fort_blue_2_canyon.tmx | Geistmage #100 (Common, placed by r287) (1,0) | split cluster [98, 100] | (1,0) <-> (3,2) | yes | 0.0/0.0 2.83/2.89 2.83/2.86 |
| fort/fort_colorless_2_wizards.tmx | Skeleton #83 (Common, placed by r287) (15,4) | split cluster [55, 83] | (15,5) <-> (13,5) | yes | 1.0/1.0 2.0/2.2 2.0/2.28 |
| fort/fort_colorless_7_multilevel4.tmx | Skeleton #92 (Common, placed by r287) (31,19) | split cluster [91, 92] | (31,18) <-> (29,18) | yes | 1.0/1.0 2.0/2.0 2.0/2.0 |
| graveyard_crypt/crypt_5.tmx | Necrogoyf #58 (Mythic, hand-authored) (11,40) | split cluster [57, 58] | (12,39) <-> (12,41) | yes | 1.41 fly 2.0 fly 2.0 fly |
| grolnok/grolnok.tmx | Shade #148 (Uncommon, hand-authored) (2,53) | split cluster [148, 259] | (3,54) <-> (3,56) | yes | 1.41 fly 2.0 fly 2.0 fly |
| grove/An-Havva_Inn.tmx | Daughter of Autumn #62 (Uncommon, hand-authored) (14,8) | split cluster [60, 62] | (13,7) <-> (11,9) | yes | 1.41/1.41 2.83/2.83 2.83/2.84 |
| grove/grove_2_wolf.tmx | Elk #98 (Common, placed by r287) (20,3) | split cluster [87, 98] | (19,3) <-> (19,5) | yes | 1.0/1.0 2.0/2.01 2.0/2.0 |
| lavaforge/lavaforge_1_sulfuricvortex.tmx | Viashino #75 (Common, placed by r287) (23,5) | split cluster [59, 75] | (23,5) <-> (21,6) | yes | 0.0/0.0 2.24/2.24 2.24/2.24 |
| magetower/magetower_13_doppelganger.tmx | Skeleton #108 (Common, placed by r287) (7,20) | split cluster [71, 108] | (7,20) <-> (7,23) | yes | 0.0/0.0 3.0/3.0 3.0/3.02 |
| magetower/magetower_7_church.tmx | Skeleton #143 (Common, placed by r287) (8,2) | split cluster [86, 143] | (8,2) <-> (7,4) | yes | 0.0/0.0 2.24/2.26 2.24/2.26 |
| main_story/temple_of_liliana/town.tmx | Wild Rat #298 (Common, placed by r287) (53,5) | split cluster [251, 298, 300] | (54,3) <-> (56,3) | no - it patrols clear of the group | 2.24/2.24 2.0/2.29 2.0/2.29 |
| main_story_explore/dig_site_1.tmx | Skeleton #134 (Common, placed by r287) (28,11) | split cluster [132, 134] | (28,11) <-> (25,12) | yes | 0.0/0.0 3.16/3.29 3.16/3.41 |
| maze/maze_1.tmx | Minotaur #177 (Common, placed by r287) (14,17) | split cluster [176, 177] | (15,18) <-> (15,20) | yes | 1.41/2.02* 2.0/2.01 2.0/2.0 |
| maze/maze_2.tmx | Minotaur Warcaller #77 (Rare, placed by r286b) (14,6) | split cluster [76, 77] | (14,7) <-> (16,7) | yes | 1.0/1.0 2.0/2.0 2.0/2.09 |
| maze/maze_3.tmx | Heart-Piercer Manticore #133 (Common, placed by r286b) (15,21) | split cluster [132, 133] | (16,20) <-> (12,22) | yes | 1.41/1.41 4.47/4.73 4.47/4.62 |
| merfolkpool/merfolkpool_2.tmx | Water Elemental #71 (Common, placed by r287) (14,3) | split cluster [53, 71] | (14,2) <-> (13,4) | yes | 1.0/1.0 2.24/3.6* 2.24/3.6* |
| merfolkpool/merfolkpool_4.tmx | Water Elemental #77 (Common, placed by r287) (15,3) | split cluster [76, 77] | (15,2) <-> (16,0) | yes | 1.0/1.0 2.24/2.3 2.24/2.24 |
| monastery/unhallowed_abbey_2F.tmx | Skeleton #135 (Common, placed by r287) (15,2) | split cluster [75, 133, 135] | (15,2) <-> (13,1) | yes | 0.0/0.0 2.24/2.3 2.24/2.28 |
| nahiri/nahiri.tmx | Yeti #104 (Common, hand-authored) (5,21) | split cluster [104, 111] | (5,20) <-> (7,21) | yes | 1.0/1.0 2.24/2.27 2.24/2.24 |
| naktamun/naktamun.tmx | Khenra Warrior #42 (Common, hand-authored) (8,23) | split cluster [42, 45] | (7,21) <-> (10,21) | no - it patrols clear of the group | 2.24/2.39 3.0/3.05 3.0/3.05 |
| phyrexia/phyrexian_b1.tmx | Gitaxian Underling #85 (Common, placed by r287) (16,2) | split cluster [84, 85] | (16,2) <-> (13,3) | yes | 0.0/0.0 3.16/3.26 3.16/3.29 |
| phyrexia/phyrexian_r1.tmx | Skeleton #99 (Common, placed by r287) (10,8) | split cluster [79, 98, 99] | (10,6) <-> (13,8) | no - it patrols clear of the group | 2.0/2.0 3.61/3.61 3.61/3.61 |
| phyrexia/phyrexian_w1.tmx | Skeleton #87 (Common, placed by r287) (18,3) | split cluster [86, 87] | (18,3) <-> (15,2) | yes | 0.0/0.0 3.16/3.16* 3.16/3.16* |
| vampirecastle/vampirecastle_4C.tmx | Vampire #100 (Common, placed by r287) (48,2) | split cluster [79, 100] | (48,2) <-> (46,1) | yes | 0.0/0.0 2.24 fly 2.24 fly |
| zedruu/zedruu.tmx | Dwarf Mercenary #137 (Uncommon, hand-authored) (4,24) | split cluster [137, 202] | (6,25) <-> (5,27) | no - it patrols clear of the group | 2.24/2.41 2.24/2.69 2.24/2.26 |

## 4. Optional relocations (not applied unless `apply.py --with-relocations`)

The request named removal or patrol; these are moves, offered because a booster's guard can do neither (pinned, and the only guard its booster has), and for a chest's guard only where no patrol fits. Each new post is open floor in the same room as its loot (a short walk, not just a short distance), keeps its own loot and changes no other guard - replayed.

| map | enemy | from -> to (tiles) | its loot | why |
|---|---|---|---|---|
| aerie/aerie_0.tmx | Griffin #140 (Uncommon, placed by r287) | (25,25) -> (27,24) | booster 112 at 2.0 tiles | split from #139 |
| aerie/aerie_0.tmx | Griffin #138 (Uncommon, placed by r279) | (24,23) -> (23,23) | booster 110 at 1.0 tiles | split from #139 |
| djinnpalace/djinnpalace_2B.tmx | Skeleton #83 (Common, placed by r287) | (11,7) -> (14,7) | booster 76 at 1.4 tiles | split from #82 |
| evilgrove/Court_of_Paliano.tmx | Grenzo #124 (Uncommon, placed by r279) | (16,14) -> (18,14) | booster 88 at 1.0 tiles | split from #123 |
| garruk/garruk.tmx | Viper #99 (Common, placed by r286b) | (32,23) -> (32,21) | chest 48 at 1.0 tiles | its registered guard onto reachable floor beside chest 48 |
| grolnok/grolnok_f1.tmx | Witch #215 (Uncommon, placed by r279) | (20,52) -> (20,50) | booster 177 at 1.0 tiles | split from #214 |
| grove/An-Havva_Inn.tmx | Joven and Chandler #85 (Rare, placed by r279) | (11,0) -> (10,0) | booster 69 at 1.4 tiles | split from #82 |
| grove/Squirrel_Farm.tmx | Chatterfang #171 (Rare, placed by r279) | (1,22) -> (2,22) | booster 130 at 2.0 tiles | split from #169 |
| main_story/templeofchandra.tmx | Skeleton #194 (Common, placed by r287) | (45,33) -> (47,31) | chest 28 at 1.0 tiles | split from #192 |
| monastery/unhallowed_abbey_2F.tmx | Demon #132 (Uncommon, placed by r279) | (9,2) -> (8,1) | booster 93 at 1.0 tiles | split from #136 |
| vampirecastle/vampirecastle_4C.tmx | Unholy Skull #99 (Rare, placed by r279) | (43,4) -> (44,5) | booster 82 at 1.0 tiles | split from #101 |
| vampirecastle/vampirecastle_grave_2.tmx | Naked Mole Rat #113 (Uncommon, placed by r279) | (11,0) -> (14,2) | chest 65 at 2.0 tiles | split from #114, #115 |

## 5. Hand-authored set-pieces, left as designed

| map | cluster | why |
|---|---|---|
| magetower/magetower_13_doppelganger.tmx | #80, #82 | hand-authored set-piece (#82 crowned (effect)) - left as designed |
| main_story/templeofchandra.tmx | #41, #171 | hand-authored set-piece (#41 boss, story tag Boss, carries its own reward, crowned (effect); #171 boss, story tag Boss, carries its own reward, crowned (effect)) - left as designed |
| main_story/templeofchandra.tmx | #42, #43 | hand-authored set-piece (#42 carries its own reward, crowned (effect); #43 carries its own reward, crowned (effect)) - left as designed |

## 6. Left as they are

| map | cluster | on | why nothing safe fits | what removing the weaker one would cost |
|---|---|---|---|---|
| catlair/catlair_white_1_lionden.tmx | #63, #82 | Easy/Normal/Hard/Insane | #82: pinned booster guard; #63: no candidate route passes the walk checks (1 tried) | #82 Caracal: loot [51] loses its registered guard; booster 60 passes to #63 Lion (Uncommon, threatRange 40) |
| cave/Valors_Reach_Arena.tmx | #191, #192 | Easy/Normal/Hard/Insane | #192: scripted placement (spawnRate 0), pinned booster guard; #191: scripted placement (spawnRate 0), pinned booster guard | #192 Gwafa Hazid: protected (scripted placement (spawnRate 0)) |
| cave/cave_skeleton.tmx | #62, #89 | Easy/Normal/Hard/Insane | #89: pinned booster guard; #62: no open floor 2.2+ tiles from the group within 4 tiles of its post that also keeps it within 2 tiles of its chest | #89 Skeleton: loot [58] loses its registered guard; booster 73 passes to #62 Adept Black Wizard (Uncommon, threatRange 50) |
| djinnpalace/djinnpalace_1.tmx | #50, #71 | Easy/Normal/Hard/Insane | #71: already has a waypoints route, pinned booster guard; #50: no candidate route passes the walk checks (12 tried) | #50 Djinn: nothing lost, but the map has fewer than 8 fighters / #71 Adept White Wizard: booster 70 passes to #50 Djinn (Uncommon, threatRange 20) |
| evilgrove/Gitrog_Bog_2.tmx | #224, #225, #226 | Easy/Normal/Hard/Insane | #225: scripted placement (spawnRate 0), pinned booster guard; #226: scripted placement (spawnRate 0), pinned booster guard; #224: scripted placement (spawnRate 0), pinned booster guard | #224 The Gitrog Monster: protected (scripted placement (spawnRate 0)) / #225 The Gitrog Monster: protected (scripted placement (spawnRate 0)) / #226 The Gitrog Monster: protected (scripted placement (spawnRate 0)) |
| graveyard_crypt/crypt.tmx | #99, #100 | Easy/Normal/Hard/Insane | #100: no open floor 2.2+ tiles from the group within 4 tiles of its post that also keeps it within 2 tiles of its chest; #99: no open floor 2.2+ tiles from the group within 4 tiles of its post that also keeps it within 2 tiles of its chest | #99 Adept Black Wizard: loot [52] loses its registered guard / #100 Adept Black Wizard: loot [52] loses its registered guard |
| grove/An-Havva_Inn.tmx | #81, #82, #85, #86 | Easy/Normal/Hard/Insane | #82: scripted placement (spawnRate 0), pinned booster guard; #81: scripted placement (spawnRate 0), pinned booster guard; #86: scripted placement (spawnRate 0), pinned booster guard; #85: scripted placement (spawnRate 0), pinned booster guard | #81 Joven and Chandler: protected (scripted placement (spawnRate 0)) / #82 Joven and Chandler: protected (scripted placement (spawnRate 0)) / #85 Joven and Chandler: protected (scripted placement (spawnRate 0)) / #86 Joven and Chandler: protected (scripted placement (spawnRate 0)) |
| grove/An-Havva_Inn.tmx | #87, #88 | Easy/Normal/Hard/Insane | #88: scripted placement (spawnRate 0), pinned booster guard; #87: scripted placement (spawnRate 0), pinned booster guard | #87 Joven and Chandler: protected (scripted placement (spawnRate 0)) / #88 Joven and Chandler: protected (scripted placement (spawnRate 0)) |
| magetower/magetower_14_horrors.tmx | #114, #115 | Easy/Normal/Hard/Insane | #115: no open floor 2.2+ tiles from the group within 4 tiles of its post that also keeps it within 2 tiles of its chest; #114: pinned booster guard | #114 Skeleton: loot [100] loses its registered guard; booster 97 passes to #72 Adept Black Wizard (Uncommon, threatRange 50) / #115 Skeleton: loot [100] loses its registered guard |
| monastery/unhallowed_abbey_2F.tmx | #75, #133, #135 | Easy/Normal/Hard/Insane | #133: pinned booster guard; #75: already has a waypoints route, pinned booster guard | #75 Demon: loot [88] loses its registered guard; booster 87 passes to #134 Skeleton (Common, threatRange 20) / #133 Demon: loot [88] loses its registered guard; booster 89 passes to #135 Skeleton (Common, threatRange 20) |
| monastery/unhallowed_abbey_2F.tmx | #131, #137 | Easy/Normal/Hard/Insane | #137: pinned booster guard; #131: no open floor 2.2+ tiles from the group within 4 tiles of its post that also keeps it within 2 tiles of its chest | #137 Skeleton: loot [91] loses its registered guard; booster 92 passes to #131 Demon (Uncommon, threatRange 40) |
| phyrexia/phyrexian_r1.tmx | #79, #98, #99 | Easy/Normal/Hard/Insane | #98: no open floor 2.2+ tiles from the group within 4 tiles of its post that also keeps it within 2 tiles of its chest; #79: already has a waypoints route, pinned booster guard | #98 Skeleton: loot [85] loses its registered guard |
| tibalt/tibalt_f4.tmx | #179, #180 | Easy/Normal/Hard/Insane | #180: pinned booster guard; #179: boss, story tag Boss | #180 Skeleton: booster 167 passes to #179 Tibalt (Rare, threatRange 40) |
| vampirecastle/vampirecastle_grave_1.tmx | #68, #69 | Easy/Normal/Hard/Insane | #69: pinned booster guard; #68: no open floor 2.2+ tiles from the group within 4 tiles of its post that also keeps it within 2 tiles of its chest | #69 Skeleton: loot [65] loses its registered guard; booster 66 passes to #68 Ancient Vampire (Mythic, threatRange 40) |

## Found on the way (not changed by this plan)

* **The repo's reachability tools ignore object-layer collision.** `MapStage.loadObjects()` adds every `collision` object (collision.tx) to collisionRect, but `pixel_collision_qa.build_grid()` rasterizes tile collision only - 125 maps carry such objects, shard_mines alone 92. This plan's checks include them (tfrmaps' strict model).
* **5 maps have an entry the tools cannot seed a flood fill from** (cave_kavu's sits below the map, zombietown's on its bottom wall, ...): every chest there audits as unguarded whatever stands beside it.
* templeofchandra's two Chandras (#41, #171) are difficulty variants whose `spawn.X` flags overlap on Insane only, so an Insane game meets both side by side - probably an authoring slip.

## Vetoing a line

Every removal and patrol above is individually safe, but the hand-authored removals in particular are judgment calls. To keep an enemy exactly as it is, re-plan with `plan.py ... --exclude <map>#<id>` (repeatable): the planner then works around it instead of apply.py skipping a line whose neighbors were planned assuming it.

## For the record: removal checks that failed (these clusters fell through to a patrol)

- aerie/aerie_0.tmx: no safe removal among [138, 139, 140]: #139 loot 112 would lose its dedicated guard; #140 loot 112 would lose its dedicated guard; #138 loot 112 would lose its dedicated guard
- barbariancamp/barbariancamp_bandit.tmx: no safe removal among [53, 63]: hand-authored and not duplicates of each other, so patrol only
- barbariancamp/barbariancamp_goblin_3.tmx: no safe removal among [55, 104]: #104 loot 79 would lose its dedicated guard
- cave/Valors_Reach_Arena.tmx: no safe removal among [191, 192]: #192 scripted placement (spawnRate 0); #191 scripted placement (spawnRate 0)
- cave/cave_bluewiz.tmx: no safe removal among [82, 83]: #83 loot 64 would lose its dedicated guard; #82 loot 64 would lose its dedicated guard
- cave/cave_huge.tmx: no safe removal among [91, 92, 93]: hand-authored and not duplicates of each other, so patrol only
- evilgrove/evilgrove_2_blackgolem.tmx: no safe removal among [64, 97]: #97 chest 84 would have no enemy within 3 tiles
- fort/fort_blue_2_canyon.tmx: no safe removal among [98, 100]: #100 loot 51 would lose its dedicated guard; #98 loot 51 would lose its dedicated guard
- fort/fort_colorless_2_wizards.tmx: no safe removal among [55, 83]: #83 loot 48 would lose its dedicated guard
- graveyard_crypt/crypt.tmx: no safe removal among [99, 100]: #100 loot 52 would lose its dedicated guard; #99 loot 52 would lose its dedicated guard
- graveyard_crypt/crypt_5.tmx: no safe removal among [57, 58]: hand-authored and not duplicates of each other, so patrol only
- grolnok/grolnok.tmx: no safe removal among [148, 259]: #259 booster 86 would pass to a different guard
- grolnok/grolnok_f1.tmx: no safe removal among [214, 215]: #215 loot 177 would lose its dedicated guard; #214 loot 177 would lose its dedicated guard
- grove/An-Havva_Inn.tmx: no safe removal among [60, 62]: hand-authored and not duplicates of each other, so patrol only
- grove/An-Havva_Inn.tmx: no safe removal among [81, 82, 85, 86]: #82 scripted placement (spawnRate 0); #81 scripted placement (spawnRate 0); #86 scripted placement (spawnRate 0); #85 scripted placement (spawnRate 0)
- grove/An-Havva_Inn.tmx: no safe removal among [87, 88]: #88 scripted placement (spawnRate 0); #87 scripted placement (spawnRate 0)
- grove/Squirrel_Farm.tmx: no safe removal among [169, 171]: #171 scripted placement (spawnRate 0); #169 scripted placement (spawnRate 0)
- grove/grove_2_wolf.tmx: no safe removal among [87, 98]: #98 loot 86 would lose its dedicated guard
- magetower/magetower_13_doppelganger.tmx: no safe removal among [71, 108]: #108 loot 72 would lose its dedicated guard
- main_story/temple_of_liliana/town.tmx: no safe removal among [251, 298, 300]: #298 loot 286 would lose its dedicated guard; #300 loot 286 would lose its dedicated guard
- main_story/templeofchandra.tmx: no safe removal among [192, 194]: #194 loot 172 would lose its dedicated guard; #192 loot 172 would lose its dedicated guard
- main_story_explore/dig_site_1.tmx: no safe removal among [132, 134]: #134 loot 115 would lose its dedicated guard; #132 loot 115 would lose its dedicated guard
- maze/maze_1.tmx: no safe removal among [176, 177]: #177 loot 59 would lose its dedicated guard; #176 loot 59 would lose its dedicated guard
- maze/maze_3.tmx: no safe removal among [132, 133]: #133 loot 111 would lose its dedicated guard; #132 loot 111 would lose its dedicated guard
- monastery/unhallowed_abbey_2F.tmx: no safe removal among [75, 133, 135]: #135 loot 88 would lose its dedicated guard; #133 loot 88 would lose its dedicated guard; #75 loot 88 would lose its dedicated guard
- monastery/unhallowed_abbey_2F.tmx: no safe removal among [75, 133]: #133 loot 88 would lose its dedicated guard; #75 loot 88 would lose its dedicated guard
- monastery/unhallowed_abbey_2F.tmx: no safe removal among [131, 137]: #137 loot 91 would lose its dedicated guard; #131 loot 91 would lose its dedicated guard
- monastery/unhallowed_abbey_2F.tmx: no safe removal among [132, 136]: #136 loot 90 would lose its dedicated guard; #132 loot 90 would lose its dedicated guard
- nahiri/nahiri.tmx: no safe removal among [104, 111]: #111 loot 72 would lose its dedicated guard; #104 loot 48 would lose its dedicated guard
- phyrexia/phyrexian_r1.tmx: no safe removal among [79, 98, 99]: #99 loot 85 would lose its dedicated guard; #98 loot 85 would lose its dedicated guard
- phyrexia/phyrexian_r1.tmx: no safe removal among [79, 98]: #98 loot 85 would lose its dedicated guard
- vampirecastle/vampirecastle_4C.tmx: no safe removal among [79, 100]: #100 loot 75 would lose its dedicated guard
- vampirecastle/vampirecastle_4C.tmx: no safe removal among [99, 101]: #101 loot 76 would lose its dedicated guard; #99 loot 76 would lose its dedicated guard
- zedruu/zedruu.tmx: no safe removal among [137, 202]: hand-authored and not duplicates of each other, so patrol only
