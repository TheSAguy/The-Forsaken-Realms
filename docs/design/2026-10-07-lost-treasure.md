# Lost treasure hunts (design; a TEST version built in round 478)

User ask (2026-10-07): *"look into how we could implement a lost treasure quest. The idea is that you'd collect fragments
of a map until you have collected enough to know where to 'dig' to find a treasure. ... 6 of these in total. 1 for each
biome. Each map might consist of 9 pieces in total. You can 'dig' for the treasure before you have all 9, but if you do
have all 9, the spot would be marked with an 'X' on the overworld showing you exactly where. So nothing to implement
just yet."* The user supplied background: HoMM III's Obelisks and puzzle map (the fun is *recognizing* a spot from a
partial picture), Red Dead's sketch maps, Wind Waker's charts, Sea of Thieves' island-and-X maps, Witcher 3's region
maps, and an adaptation outline (waystone fragments, a scroll puzzle map, generation at world creation, a dig cost, a
guardian duel).

This file records the research (where each piece would hook in, with code references as of round 471) and the
recommended design. Open decisions for the user are at the end.

**Round 478 built a test version** with the user's answers (2026-10-07): obelisks from buildings.png, one per biome,
moving on weekly (one piece a region a week); the Spade on the first obelisk, 5 shards a dig, a find within 2 tiles; a
Treasure Maps button opening the six maps. What it does and what is still a stand-in: MOD_CHANGELOG "Round 478".
Round 480 (the user): the first piece is the center with the X, the other eight random; dig holes stay until
that region's treasure is found.

## Bottom line

Every part has a template in the engine already, except the treasure-map screen itself:

| Part | Template |
|---|---|
| Permanent world-map object that appears mid-game (the X) | the Bonfire / Yin-Yang half (`World.bonfires`, `World.yinYangAnchor`, `WorldStage.syncYinYangActor`) |
| A "dig" action | an item with `usableOnWorldMap` + `commandOnUse`, like the Bonfire's `bonfire place` (`ConsoleCommandInterpreter`) |
| A one-off duel launched from code | `WorldStage.startChestDuel()`, as ChestEvents' "Dangerous Enemy" uses it |
| Lazy setup for old saves | ResourceSpawns' `resourceSpawnsSeeded` flag (first world-map tick seeds it) |
| Dedup of a once-only reward | `World.getOncePaidRewards()` keys (PlaceRewards) |
| A picture of the real world | the minimap image (`World.biomeImage`, 4 px a tile) |
| A parchment screen | the `paper` window style (`ui_skin.json` `paper10Patch`), `InfoTextScene` as the skeleton |

## Recommended design

### 1. The six hunts and their spots

- **Six = the Wastes + the five colors.** One hunt per region in every world; a new game or New Game+ gets six new
  ones (state lives on `World`, cleared in `World.generateNew()`).
- **"In the Green biome" means the Green REGION, not the current owner.** Territory Control repaints ownership: at the
  end of world-gen `World.neutralizeTerritoryOutsideRadius` turns every color tile farther than 20 tiles
  (`TerritoryControl.CASTLE_KEEP_RADIUS_TILES`) from its castle into waste, and daily expansion repaints it again. The
  stable geometry is `World.computeBarrier()`'s six decorated circles (one per biome; on 700x700 roughly radius ~141
  for the Wastes at the center, ~114 for each color around its `startPointX/Y`). Pick each target inside its own
  circle and clear of the others.
- **Spot rules:** walkable (`!isColliding`), land, not a road (`getBiome & roadMask`), well clear of every POI (more
  than ResourceSpawns' 3-tile `POI_CLEARANCE_TILES`), reachable on foot from the start (a flood fill over walkable
  tiles at seeding - 700x700 is cheap), and **near landmarks**: score candidates by the water / barrier / structure /
  POI-icon variety in the picture crop, so the map is recognizable. There is no ready "free tile in biome B" helper;
  `ResourceSpawns.spawnInArea` (private) plus a region test is the closest.
- **Old saves:** seed lazily on the first world-map tick (a `treasureSeeded` / version int on `World`, like
  `resourceSpawnsSeeded`), saved behind `containsKey` checks.
- **Growth can block a tile later** (rings re-roll structures; only places get swept). The dig tolerance below covers
  it, as `World.yinYangLanding()` already does for its own anchor.

### 2. The treasure map (scroll screen)

- **Picture = a crop of the minimap image**, about 30x30 tiles (120x120 px), enlarged 3x with hard edges and drawn in a
  sepia ink tone on a `paper` window. The minimap already carries water, coastlines, mountains, biome color, roads and
  the baked town/dungeon icons (`World.redrawPoiMarkers`). Full tile art is possible (`WorldBackground.getChunkTexture`
  is an offscreen tile-to-Pixmap loop) but needs a new public, fog-free `World` method, runs on the render thread only,
  and still lacks trees (separate `MapSprite` actors) and the mountain art (`BarrierMountains`, GPU only) - not worth it.
- **Drawn on demand, not baked at world creation**, so it always matches the land as it is now (Territory Control
  changes color and some ground). Needs a getter for the clean `biomeImage` (private; `getBiomeImage()` returns the
  FOGGED pixmap when fog of war is on). Pixel row = `(height - tileY - 1) * 4`.
- **Fog of war does not apply** - the map can show land the player has never seen; that is what sends them exploring.
- **3x3 pieces; the 9th is always the center.** Fragments reveal the 8 outer pieces in a per-world random order and
  the 9th reveals the center, which holds the X. Covered pieces get a stained-paper cover (procedural, in the style of
  `util/RubbleOverlay`).
- **The treasure sits somewhere inside the center piece, not at its exact middle.** With 8 pieces the player knows the
  ~10x10-tile area and can dig on a guess; the 9th shows the exact spot. This keeps "dig before you have all 9" a real
  gamble (a centered target would be deducible from the 8 outer pieces).
- **Screen:** a new `UIScene` (skeleton: `InfoTextScene`, 155 lines; `ui/*.json` + `_portrait` variant with the
  `lastScreen` backdrop, a `paper` window and a Back button; make the window untouchable as `InfoTextScene` does). One
  tab per region the player holds fragments for. The runtime image: `new Texture(pixmap)` into an `Image` with a
  `TextureRegionDrawable`, as `MapViewScene.refreshMap()` does.

### 3. Fragments

- **Counted per region (4 of 9), not as 9 distinct items each.** The count drives which piece is revealed, so the
  center is last whatever the source. Every SOURCE is recorded once in `World.getOncePaidRewards()` (keys like
  `treasure|green|<source>`), because dungeons restock (`DungeonRotation.restock`) and legends return - a quest item on
  a map object would pay again after a restock (PlaceRewards always keeps quest items).
- **Sources needing no new art:**
  - clearing a dungeon or lair in that region - a chance on the clear (`DungeonSources.onCleared` /
    `DungeonRotation.onLairExit` are the hooks), capped;
  - that region's legends, once each (`WorldStage.setWinner` -> `PlaceRewards.filterWorldPayout` is where to add it);
  - quest rewards (epilogue `grantRewards` or a `runCommand`);
  - a cartographer in towns selling the next fragment for rising gold - the safety valve so a hunt can never stall.
- **Waystones** (the obelisk idea): permanent overworld objects, a fragment when walked onto - ResourceSpawns' pickup
  test (`checkPickup`, distance from the sprite's center) on a saved list like the bonfires. Needs art. Note there are
  no shrines, obelisks or waystones in the game today.
- **Inventory:** one quest item (a map case) whose use opens the scroll screen with a tab per region - not 54 fragment
  items. A quest-log line "4/9" needs `AdventureQuestStage.getProgressText()` extended for QuestFlag stages (it shows a
  count only for Defeat / ClearDungeons / CompleteQuest / Arena / EventFinish today); remember only `set*Flag` fires
  quest events, `advance*Flag` does not.

### 4. Digging

- **A Spade item:** `"usableOnWorldMap": true, "commandOnUse": "treasure dig"`, plus a registered console command that
  reads `WorldStage.playerTileX/Y` (feet) - the exact shape of `bonfire place`, including `refundItemInUse(...)` for "not
  here" cases (in a town, in a dungeon). `uses` counts per item NAME (`AdventurePlayer.itemUses`).
- **Hit:** Chebyshev distance <= 1 from the target (a 3x3 area).
- **Cost:** a Spade charge and a few hours of game time (`World.advanceTime`; TFR's day cycle is on, and time is a real
  cost - the colors expand and attack while you dig). Not a whole day: too expensive in TFR's economy.
- **Miss:** "Nothing but dirt." Optional: a small chance a miss disturbs something (ChestEvents' Dangerous Enemy).
- **With all 9 pieces,** walking onto the X digs for free.

### 5. The X

- On the overworld: a saved anchor + stamp on `World` and an actor synced every frame in `WorldStage.onActing()`, like
  the Yin-Yang half (`syncYinYangActor`); drawn whatever the fog (it is a map mark).
- On the map screen: one more `Image` in `MapViewScene`'s `mageMarkers` / `markerAnchors` (placed by `layoutMarkers()`).
- On the corner minimap: an `Image` in `GameHUD.mapGroup`, positioned like `updateMageMinimapMarkers()`.
- Not baked into the minimap image: every `refreshWorldMapMarkers()` re-derives the ground and would wipe it.
- No X art exists (`map_marker.atlas` has none): a small sprite.

### 6. The guardian and the treasure

- The dig launches a duel through a `startChestDuel`-style call with its own flag (`currentMobIsTreasureGuardian`),
  results in `WorldStage.setWinner`.
- **Win:** the treasure rides on the guardian sprite's own `rewards` (added untouched by `EnemySprite.getRewards`) - one
  unique artifact per region plus a rare card bundle in its color - and the hunt is marked done.
- **Loss:** the spot stays; come back.
- **Six guardians, tagged `TreasureGuardian`** (questTags) so no automatic pool picks them up: a non-boss legend-shaped
  entry would become a frontier legend (`FrontierSpawns.isCandidate`), a life-100+ one a chest heavyweight
  (`ChestEvents`), a non-boss Mythic an Illegal Arena pick. Not `boss: true`: a loss to a boss cracks an equipped item
  (`GameStage.defeatedFromBoss`), too harsh for an optional hunt.

### 7. Saves and New Game+

- On `World`: six target tiles, six fragment counts, a per-world reveal order, six "found" flags, the X anchors, a
  seeding version. All cleared in `World.generateNew()` (New Game+ reuses the `World` object), all saved behind
  `containsKey` checks; nothing needs a `SAVE_FORMAT_VERSION` bump.
- Logging: `[TFR-Treasure]` lines for seeding (spot, score), each fragment (source, count, piece revealed), each dig
  (tile, distance, hit/miss) and the guardian result.

## Where this departs from the supplied outline

- The picture is drawn on demand, not generated at world creation (the land changes under Territory Control).
- "Biome" is the world-gen region, not the current owner.
- Center piece last, and the treasure off-center inside it.
- Dig cost: Spade charges and some hours, not a whole day.

## Phasing (if built)

1. **Round A - the core:** data + spot choice + old-save seeding, the Spade and the dig command, the guardian duel and
   the treasure, the X, and one fragment source (dungeon clears) so the loop is testable end to end.
2. **Round B - the scroll screen:** the crop, the 3x3 reveal, the covers.
3. **Round C - more sources:** legends, quests, the cartographer, waystones, the quest-log count.

Art: the X sprite, a Spade icon, six treasure icons, six guardian sprites (the Pixelate pipeline could make them), and
waystone art if waystones are wanted.

## Open decisions (the user's)

1. Fragment sources: waystones (new art) or existing content only (clears, legends, quests, cartographer)?
2. Dig cost: Spade charges plus a few hours - right? Should a miss sometimes start a fight?
3. The treasure: one unique artifact per region, a card bundle, or both?
4. Town and dungeon icons in the map picture? (Recommended: keep them - they are the best landmarks.)
